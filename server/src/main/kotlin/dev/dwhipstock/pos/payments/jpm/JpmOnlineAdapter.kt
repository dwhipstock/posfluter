package dev.dwhipstock.pos.payments.jpm

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
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** A J.P. Morgan Online Payments answer, reduced to what the store needs. */
data class JpmPayment(
    val transactionId: String,
    val approved: Boolean,
    /** transactionState: AUTHORIZED, CLOSED, VOIDED, DECLINED, ERROR… */
    val state: String? = null,
    val responseCode: String? = null,
    val message: String? = null,
    val approvalCode: String? = null,
)

/**
 * A documented sandbox test card (never a real card). J.P. Morgan triggers
 * declines by AMOUNT on specific cards, so a decline scenario carries the
 * trigger amount to send instead of the sale amount ([triggerAmountCents]).
 */
data class JpmTestCard(
    val number: String, val expiryMonth: Int, val expiryYear: Int, val brand: String,
    val triggerAmountCents: Long? = null,
)

/**
 * The J.P. Morgan Online Payments calls the store makes (sandbox). An HTTP
 * implementation is [JpmOnlineHttp]; tests use a fake.
 */
interface JpmOnlineApi {
    /** Shown on the receipt and the tablet ("J.P. Morgan sandbox"). */
    val label: String
    /** false for J.P. Morgan's mock host, which approves everything (stateless canned answers). */
    val canDecline: Boolean
    /** The sandbox test card that plays [brand] with this outcome ([scenario]: approve, insufficient_funds, do_not_honour). */
    fun testCard(brand: String, scenario: String): JpmTestCard
    /** Authorize only (captureMethod MANUAL). [requestId] makes a retry safe. */
    fun authorize(requestId: String, amountCents: Long, currency: String, card: JpmTestCard, orderRef: String): JpmPayment
    fun capture(transactionId: String, amountCents: Long, requestId: String): JpmPayment
    fun void(transactionId: String, requestId: String): JpmPayment
    fun refund(transactionId: String, amountCents: Long, currency: String, requestId: String): JpmPayment
}

/**
 * `payment.terminal=jpmorgan` (mode online, the default): the built-in
 * simulator is the card reader people touch (tap / insert / swipe on the Mac
 * or the tablet sheet), and when a card is presented the store sends a REAL
 * J.P. Morgan **Online Payments sandbox** authorization with a sandbox test
 * card standing in for the simulated one, then captures it once the store
 * has re-checked the amount is still due. Refunds and voids go to J.P. Morgan
 * too; the J.P. Morgan transaction id is the tender's reference and prints on
 * the receipt.
 *
 * Honest framing (docs/payments-terminals.md): Online Payments is J.P.
 * Morgan's card-not-present API. A production card-present store would use
 * their in-store terminal program ([JpmInStoreAdapter]). Sandbox only.
 *
 * J.P. Morgan unreachable or not configured → the reader shows a decline with
 * `processor_unavailable`, nothing is recorded, cash and the counter terminal
 * still work.
 */
class JpmOnlineAdapter(
    private val link: () -> SimulatorLink,
    private val api: JpmOnlineApi?,
    private val timeoutSeconds: Int,
    private val currency: String = "USD",
) : PaymentTerminal {
    override val kind = TerminalKind.JPMORGAN

    companion object {
        private val log = LoggerFactory.getLogger(JpmOnlineAdapter::class.java)
        const val PROCESSOR_UNAVAILABLE = "processor_unavailable"
    }

    /** What J.P. Morgan holds for one reader transaction (reader id → JPM transaction). */
    private data class Held(val transactionId: String, val amountCents: Long, @Volatile var captured: Boolean = false)
    private val held = ConcurrentHashMap<String, Held>()

    override fun status(): ReaderStatus {
        if (api == null) return ReaderStatus(ReaderState.OFFLINE, "J.P. Morgan sandbox (not configured)", reason = "jpm_not_configured")
        val l = link()
        return try {
            val s = l.status()
            val name = "${s.name} · ${api.label}"
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
        if (api == null) throw TerminalException(409, "jpm_not_configured",
            "J.P. Morgan isn't set up on this store (JPM_CLIENT_ID / JPM_CLIENT_SECRET / JPM_TOKEN_URL / JPM_MERCHANT_ID)")
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

    /** The reader read a card: ask J.P. Morgan. Never throws: a failure is a decline the reader shows. */
    private fun authorize(t: SimTxnView): SimHostRequest {
        val a = api ?: return SimHostRequest(false, declineCode = "jpm_not_configured", message = "Card processor not set up")
        val scenario = t.scenario ?: "approve"
        if (scenario != "approve" && !a.canDecline) {
            // the mock host approves everything; say so rather than pretend it declined
            return SimHostRequest(false, declineCode = if (scenario == "insufficient_funds") "insufficient_funds" else "do_not_honor",
                message = "Declined (simulated on the reader: the J.P. Morgan mock host can't decline)")
        }
        val card = a.testCard(t.card?.brand ?: t.cardKey ?: "visa", scenario)
        return try {
            val p = a.authorize("pos-auth-${t.id}", card.triggerAmountCents ?: t.totalCents, t.currency, card, t.reference)
            log.info("J.P. Morgan sandbox authorization ${p.transactionId}: ${p.state} ${p.responseCode ?: ""} for reader ${t.id}")
            if (p.approved) held[t.id] = Held(p.transactionId, t.totalCents)
            SimHostRequest(
                approved = p.approved, authCode = p.approvalCode,
                declineCode = if (p.approved) null else declineCode(p),
                message = if (p.approved) "Approved" else "Declined — ${p.message ?: p.responseCode ?: "declined"}",
                processorRef = p.transactionId, processor = a.label,
            )
        } catch (e: TerminalException) {
            log.warn("J.P. Morgan sandbox unreachable for reader ${t.id}: ${e.code} ${e.message}")
            SimHostRequest(false, declineCode = PROCESSOR_UNAVAILABLE,
                message = "Card processor unreachable — take cash or the counter terminal")
        }
    }

    private fun declineCode(p: JpmPayment): String {
        val m = (p.message ?: "").lowercase()
        return when {
            "insufficient" in m -> "insufficient_funds"
            "honor" in m || "honour" in m -> "do_not_honor"
            else -> p.responseCode?.lowercase()?.let { "jpm_$it" } ?: "card_declined"
        }
    }

    private fun toResult(t: SimTxnView): PaymentResult {
        val base = SimulatorTerminal.toResult(t)
        return base.copy(
            captured = held[t.id]?.captured == true,
            readerPrompt = if (t.awaitingHost) "processing" else base.readerPrompt,
        )
    }

    override fun capture(terminalRef: String, idempotencyKey: String): PaymentResult {
        val a = api ?: throw TerminalException(409, "jpm_not_configured", "J.P. Morgan isn't set up")
        val l = link()
        val t = l.get(terminalRef)
        val h = held[terminalRef] ?: t.card?.processorRef?.let { Held(it, t.totalCents).also { n -> held[terminalRef] = n } }
            ?: throw TerminalException(409, "terminal_not_approved", "no J.P. Morgan authorization for this payment")
        if (!h.captured) {
            val p = a.capture(h.transactionId, h.amountCents, idempotencyKey)
            if (!p.approved) throw TerminalException(502, "jpm_capture_failed", "J.P. Morgan capture ${p.state}: ${p.message ?: p.responseCode}")
            h.captured = true
            runCatching { l.capture(terminalRef) }
            log.info("J.P. Morgan sandbox capture ${h.transactionId}: ${p.state}")
        }
        return toResult(l.get(terminalRef))
    }

    override fun cancel(terminalRef: String, idempotencyKey: String): PaymentResult {
        val l = link()
        held[terminalRef]?.takeIf { !it.captured }?.let { h ->
            api?.let { a ->
                runCatching { a.void(h.transactionId, idempotencyKey) }
                    .onSuccess { log.info("J.P. Morgan sandbox void ${h.transactionId}: ${it.state}") }
                    .onFailure { log.warn("J.P. Morgan sandbox void ${h.transactionId} failed: ${it.message} (the authorization lapses)") }
            }
            held.remove(terminalRef)
        }
        return toResult(l.cancel(terminalRef))
    }

    /**
     * The tender's reference is the reader's id; the J.P. Morgan transaction id
     * comes with the request ([TerminalRefundRequest.processorRef], saved with
     * the tender's card fields) — or from memory for a payment just taken.
     */
    override fun refund(request: TerminalRefundRequest): TerminalRefundResult {
        val a = api ?: throw TerminalException(409, "jpm_not_configured", "J.P. Morgan isn't set up")
        val h = held[request.terminalRef]
        val jpmId = request.processorRef ?: h?.transactionId
            ?: throw TerminalException(409, "jpm_refund_no_transaction", "no J.P. Morgan transaction id on this card payment")
        val amount = request.amountCents ?: h?.amountCents
            ?: throw TerminalException(409, "jpm_refund_amount_required", "J.P. Morgan refunds need an amount")
        val p = a.refund(jpmId, amount, currency, request.idempotencyKey)
        log.info("J.P. Morgan sandbox refund ${p.transactionId} of $amount on $jpmId: ${p.state}")
        return TerminalRefundResult(p.transactionId, if (p.approved) Outcome.APPROVED else Outcome.DECLINED,
            p.state, amount, p.message)
    }
}
