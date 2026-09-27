package dev.dwhipstock.pos.payments

import dev.dwhipstock.pos.payments.simulator.SimHostRequest
import dev.dwhipstock.pos.payments.simulator.SimStartRequest
import dev.dwhipstock.pos.payments.simulator.SimTxnView
import dev.dwhipstock.pos.payments.simulator.SimulatorLink
import dev.dwhipstock.pos.payments.simulator.SimulatorTerminal
import dev.dwhipstock.pos.payments.terminal.Outcome
import dev.dwhipstock.pos.payments.terminal.PaymentRequest
import dev.dwhipstock.pos.payments.terminal.PaymentResult
import dev.dwhipstock.pos.payments.terminal.PaymentTerminal
import dev.dwhipstock.pos.payments.terminal.ReaderState
import dev.dwhipstock.pos.payments.terminal.ReaderStatus
import dev.dwhipstock.pos.payments.terminal.TerminalException
import dev.dwhipstock.pos.payments.terminal.TerminalKind
import dev.dwhipstock.pos.payments.terminal.TerminalPayment
import dev.dwhipstock.pos.payments.terminal.TerminalRefundRequest
import dev.dwhipstock.pos.payments.terminal.TerminalRefundResult
import dev.dwhipstock.pos.payments.terminal.TipMode
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * `payment.terminal=simulator` + `payment.simulator.processor=stripe`: the
 * built-in simulator page is the card people tap, and behind it the payment
 * runs through **Stripe's own simulated reader** (a test-mode WisePOS E,
 * server-driven): the store creates a card_present PaymentIntent, hands it to
 * that reader, and Stripe's test helper "presents" the Stripe test card that
 * matches the chosen card and outcome. Approvals, declines, capture and
 * refunds are Stripe's real test-mode answers; only the plastic card is
 * pretend. The PaymentIntent id is the processor reference on the receipt.
 *
 * Stripe off, unreachable or in the wrong currency → the reader shows a
 * decline (`processor_unavailable`); nothing is recorded, cash still works.
 * Timeout and "customer cancels" stay on the reader (no card reaches Stripe).
 */
class StripeSimReaderAdapter(
    private val link: () -> SimulatorLink,
    private val stripe: StripeService,
    private val timeoutSeconds: Int,
    private val pollMillis: Long = 500,
    private val pollAttempts: Int = 30,
) : PaymentTerminal {
    override val kind = TerminalKind.SIMULATOR

    companion object {
        private val log = LoggerFactory.getLogger(StripeSimReaderAdapter::class.java)
        const val LABEL = "Stripe test mode (simulated reader)"

        /** Stripe's documented test card numbers for card_present / interac_present. */
        fun testCard(cardKey: String?, scenario: String): Pair<String, String> {
            when (scenario) {
                "insufficient_funds" -> return "card_present" to "4000000000009995"
                "do_not_honour" -> return "card_present" to "4000000000000002"
            }
            return when (cardKey?.lowercase()) {
                "mastercard" -> "card_present" to "5555555555554444"
                "amex" -> "card_present" to "378282246310005"
                "discover" -> "card_present" to "6011111111111117"
                "interac" -> "interac_present" to "4506445006931933"
                else -> "card_present" to "4242424242424242"
            }
        }
    }

    private data class Held(val paymentIntentId: String, val amountCents: Long, @Volatile var captured: Boolean = false)
    private val held = ConcurrentHashMap<String, Held>()

    override fun status(): ReaderStatus {
        if (!stripe.enabled) return ReaderStatus(ReaderState.OFFLINE, "$LABEL (Stripe not configured)", reason = "stripe_not_configured")
        val l = link()
        return try {
            val s = l.status()
            val name = "${s.name} · $LABEL"
            when {
                !s.paired && s.requiresPairing -> ReaderStatus(ReaderState.NOT_PAIRED, name, l.address, TerminalException.NOT_PAIRED, l.embedded)
                s.state == "busy" -> ReaderStatus(ReaderState.BUSY, name, l.address, null, l.embedded)
                else -> ReaderStatus(ReaderState.IDLE, name, l.address, null, l.embedded)
            }
        } catch (e: TerminalException) {
            ReaderStatus(ReaderState.OFFLINE, "Card reader", l.address, e.code, l.embedded)
        }
    }

    override fun connect(host: String?, pairingCode: String?) = status()

    override fun startPayment(request: PaymentRequest): TerminalPayment {
        if (!stripe.enabled) throw TerminalException(409, "stripe_not_configured", "Stripe isn't set up on this store (STRIPE_KEY)")
        val txn = link().start(SimStartRequest(
            reference = request.reference, amountCents = request.amountCents, currency = request.currency.uppercase(),
            tipOnReader = request.tipMode == TipMode.ON_READER, timeoutSeconds = timeoutSeconds,
            label = request.description, hostAuthorization = true,
        ))
        return TerminalPayment(txn.id, null, request.amountCents)
    }

    override fun result(terminalRef: String): PaymentResult {
        val l = link()
        var t = l.get(terminalRef)
        if (t.awaitingHost) {
            l.host(terminalRef, authorize(t))
            t = l.get(terminalRef)
        }
        return toResult(t)
    }

    /** The page read a card: run it through Stripe's simulated reader. Never throws. */
    private fun authorize(t: SimTxnView): SimHostRequest {
        val c = stripe.apiClient
            ?: return SimHostRequest(false, declineCode = "stripe_not_configured", message = "Card processor not set up")
        val (type, number) = testCard(t.card?.brand ?: t.cardKey, t.scenario ?: "approve")
        return try {
            val currency = stripe.accountCurrency()
            val reader = stripe.simulatedReaderId()
            runCatching { c.cancelReaderAction(reader) } // a leftover action from an earlier sale
            val pi = c.createPaymentIntent(listOf(
                "amount" to t.totalCents.toString(), "currency" to currency,
                "payment_method_types[]" to type, "capture_method" to "manual",
                "metadata[pos_ref]" to t.reference,
            ), "pos-simpi-${t.id}")
            val piId = pi.str("id") ?: throw StripeException(502, StripeException.ERROR, "Stripe returned no PaymentIntent id")
            c.processPaymentIntent(reader, piId, "pos-simproc-${t.id}")
            c.presentPaymentMethod(reader, listOf("type" to type, "$type[number]" to number))
            val done = poll(c, piId)
            val status = done.str("status")
            if (status == "requires_capture" || status == "succeeded") {
                held[t.id] = Held(piId, t.totalCents)
                log.info("Stripe simulated reader: $piId $status for reader ${t.id}")
                SimHostRequest(true, authCode = done.str("latest_charge.payment_method_details.$type.receipt.authorization_code"),
                    message = "Approved", processorRef = piId, processor = LABEL)
            } else {
                val code = done.str("last_payment_error.decline_code") ?: done.str("last_payment_error.code") ?: "card_declined"
                val msg = done.str("last_payment_error.message") ?: "declined"
                log.info("Stripe simulated reader: $piId declined ($code) for reader ${t.id}")
                runCatching { c.cancelPaymentIntent(piId, "pos-simcancel-${t.id}") }
                SimHostRequest(false, declineCode = code, message = "Declined — $msg", processorRef = piId, processor = LABEL)
            }
        } catch (e: StripeException) {
            log.warn("Stripe simulated reader unavailable for reader ${t.id}: ${e.code} ${e.message}")
            SimHostRequest(false, declineCode = "processor_unavailable",
                message = "Card processor unreachable — take cash or another card")
        }
    }

    /** Stripe settles the simulated read asynchronously: wait for a final PaymentIntent state. */
    private fun poll(c: StripeClient, piId: String): JsonObject {
        val expand = listOf("expand[]" to "latest_charge")
        repeat(pollAttempts) {
            val pi = c.retrievePaymentIntent(piId, expand)
            val status = pi.str("status")
            val failed = status == "requires_payment_method" && pi["last_payment_error"] != null
            if (status == "requires_capture" || status == "succeeded" || status == "canceled" || failed) return pi
            Thread.sleep(pollMillis)
        }
        throw StripeException(504, StripeException.UNAVAILABLE, "Stripe's simulated reader did not finish in time")
    }

    private fun toResult(t: SimTxnView): PaymentResult {
        val base = SimulatorTerminal.toResult(t)
        return base.copy(
            captured = held[t.id]?.captured == true,
            readerPrompt = if (t.awaitingHost) "processing" else base.readerPrompt,
        )
    }

    override fun capture(terminalRef: String, idempotencyKey: String): PaymentResult {
        val c = stripe.apiClient ?: throw TerminalException(409, "stripe_not_configured", "Stripe isn't set up")
        val l = link()
        val t = l.get(terminalRef)
        val h = held[terminalRef] ?: t.card?.processorRef?.let { Held(it, t.totalCents).also { n -> held[terminalRef] = n } }
            ?: throw TerminalException(409, "terminal_not_approved", "no Stripe authorization for this payment")
        if (!h.captured) {
            val pi = try { c.capturePaymentIntent(h.paymentIntentId, idempotencyKey) } catch (e: StripeException) {
                throw TerminalException(502, "stripe_capture_failed", "Stripe capture failed: ${e.message}")
            }
            h.captured = true
            runCatching { l.capture(terminalRef) }
            log.info("Stripe simulated reader: captured ${h.paymentIntentId} (${pi.str("status")})")
        }
        return toResult(l.get(terminalRef))
    }

    override fun cancel(terminalRef: String, idempotencyKey: String): PaymentResult {
        val l = link()
        held[terminalRef]?.takeIf { !it.captured }?.let { h ->
            stripe.apiClient?.let { c ->
                runCatching { c.cancelPaymentIntent(h.paymentIntentId, idempotencyKey) }
                    .onFailure { log.warn("Stripe cancel ${h.paymentIntentId} failed: ${it.message} (the authorization lapses)") }
            }
            held.remove(terminalRef)
        }
        return toResult(l.cancel(terminalRef))
    }

    override fun refund(request: TerminalRefundRequest): TerminalRefundResult {
        val c = stripe.apiClient ?: throw TerminalException(409, "stripe_not_configured", "Stripe isn't set up")
        val h = held[request.terminalRef]
        val piId = request.processorRef ?: h?.paymentIntentId
            ?: throw TerminalException(409, "stripe_refund_no_payment", "no Stripe PaymentIntent on this card payment")
        val params = listOfNotNull("payment_intent" to piId, (request.amountCents ?: h?.amountCents)?.let { "amount" to it.toString() })
        return try {
            val r = c.createRefund(params, request.idempotencyKey)
            val id = r.str("id") ?: "refund"
            log.info("Stripe simulated reader: refund $id on $piId (${r.str("status")})")
            TerminalRefundResult(id, if (r.str("status") == "failed") Outcome.DECLINED else Outcome.APPROVED,
                r.str("status"), request.amountCents ?: h?.amountCents, null)
        } catch (e: StripeException) {
            throw TerminalException(502, "stripe_refund_failed", "Stripe refund failed: ${e.message}")
        }
    }
}
