package dev.dwhipstock.pos.payments.taptopay

import dev.dwhipstock.pos.payments.StripeClient
import dev.dwhipstock.pos.payments.StripeException
import dev.dwhipstock.pos.payments.StripeTerminalAdapter
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
import dev.dwhipstock.pos.payments.str
import org.slf4j.LoggerFactory

/**
 * `payment.terminal=tap_to_pay`: the owner's phone is the card reader (Stripe
 * Tap to Pay on Android). The store creates a card_present PaymentIntent
 * (manual capture) and queues it for the paired phone ([PhoneReaderHub]); the
 * phone's Terminal SDK collects and confirms it; the answer is the
 * PaymentIntent's own state at Stripe. Capture (full or partial, for a fuel
 * pre-authorisation), cancel and refund are Stripe API calls, like the
 * Stripe simulated reader.
 *
 * The client secret goes only to the paired phone, never to the tablet, the
 * database or the log.
 *
 * [client] null = Stripe not configured. [currency] is the Stripe account's
 * currency, and throws when it isn't the store's (StripeService enforces it).
 */
class TapToPayAdapter(
    val hub: PhoneReaderHub,
    private val client: () -> StripeClient?,
    private val currency: () -> String,
    private val timeoutSeconds: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) : PaymentTerminal {
    override val kind = TerminalKind.TAP_TO_PAY

    companion object {
        private val log = LoggerFactory.getLogger(TapToPayAdapter::class.java)
        const val LABEL = "Tap to Pay (phone)"
        /** The phone collected, Stripe is still settling: wait this long before calling it an error. */
        private const val SETTLE_MS = 30_000L
    }

    private fun stripe(): StripeClient = client()
        ?: throw TerminalException(409, "stripe_not_configured", "Stripe isn't set up on this store (the store's Stripe test key)")
    private fun api() = StripeTerminalAdapter(stripe())

    override fun status(): ReaderStatus {
        if (client() == null) return ReaderStatus(ReaderState.OFFLINE, "$LABEL (Stripe not configured)", reason = "stripe_not_configured")
        if (!hub.paired) return ReaderStatus(ReaderState.NOT_PAIRED, LABEL, reason = TerminalException.NOT_PAIRED)
        val name = "${hub.name()} · $LABEL"
        if (!hub.online()) return ReaderStatus(ReaderState.OFFLINE, name, reason = "phone_reader_offline")
        val hb = hub.heartbeat
        if (hb?.state == "error") return ReaderStatus(ReaderState.OFFLINE, name, reason = "phone_reader_error")
        return if (hub.active() != null) ReaderStatus(ReaderState.BUSY, name) else ReaderStatus(ReaderState.IDLE, name)
    }

    override fun connect(host: String?, pairingCode: String?) = status()

    override fun startPayment(request: PaymentRequest): TerminalPayment {
        val c = stripe()
        if (!hub.paired) throw TerminalException(409, TerminalException.NOT_PAIRED, "no phone is paired as the card reader")
        if (!hub.online()) throw TerminalException(503, TerminalException.UNAVAILABLE, "the phone card reader isn't connected")
        hub.active()?.let { expireIfOld(it) }
        if (hub.active() != null) throw TerminalException(409, TerminalException.BUSY, "the phone is taking another payment")
        val cur = currency() // the Stripe account's; throws when it isn't the store's
        // tagged with the store's payment id, so a PaymentIntent can't be recorded on the wrong payment
        val meta = request.metadata.takeIf { m -> m.any { it.first == "pos_payment_id" } }
            ?: (listOf("pos_payment_id" to request.reference) + request.metadata)
        val started = StripeTerminalAdapter(c).startPayment(request.copy(currency = cur, metadata = meta))
        val secret = started.clientSecret?.takeIf { it.isNotBlank() }
            ?: throw StripeException(502, StripeException.ERROR, "Stripe returned no client secret")
        hub.enqueue(PhoneReaderHub.Job(started.terminalRef, secret, request.amountCents, cur.lowercase(), request.description, clock()))
        log.info("Tap to Pay: ${started.terminalRef} queued for the phone (${request.amountCents} $cur)")
        return TerminalPayment(started.terminalRef, null, request.amountCents)
    }

    override fun result(terminalRef: String): PaymentResult {
        val job = hub.job(terminalRef) ?: return requeue(terminalRef)
        when (job.state) {
            "queued", "collecting", "processing" -> {
                if (expireIfOld(job)) return PaymentResult(terminalRef, Outcome.TIMEOUT, "timeout", job.amountCents,
                    message = "no card was tapped on the phone in time")
                // the phone went quiet mid-payment: the POS shows "reader offline" and keeps waiting
                if (!hub.online()) throw TerminalException(503, TerminalException.UNAVAILABLE, "the phone card reader isn't answering")
                return PaymentResult(terminalRef, Outcome.PENDING, "requires_payment_method", job.amountCents,
                    readerPrompt = when (job.state) {
                        "queued" -> "waiting_for_phone"
                        "processing" -> "processing"
                        else -> "present_card"
                    })
            }
            "collected" -> {
                // expanded: the card (brand, last 4, EMV) is known before capture (a fuel hold)
                val r = StripeTerminalAdapter.toResult(stripe().retrievePaymentIntent(terminalRef, listOf("expand[]" to "latest_charge")))
                if (r.outcome != Outcome.PENDING) return r
                if (clock() - job.createdAt > timeoutSeconds * 1000L + SETTLE_MS)
                    return r.copy(outcome = Outcome.ERROR, message = "Stripe did not confirm the payment (${r.rawStatus})")
                return r.copy(readerPrompt = "processing")
            }
            "failed" -> {
                releaseQuietly(terminalRef)
                return if (job.declined) PaymentResult(terminalRef, Outcome.DECLINED, "declined", job.amountCents,
                    declineCode = job.code ?: "card_declined", message = job.message)
                else PaymentResult(terminalRef, Outcome.ERROR, job.code, job.amountCents, message = phoneMessage(job))
            }
            "timeout" -> return PaymentResult(terminalRef, Outcome.TIMEOUT, "timeout", job.amountCents)
            else -> { // canceled (by the customer on the phone, or the POS)
                releaseQuietly(terminalRef)
                return PaymentResult(terminalRef, Outcome.CANCELLED, "canceled", job.amountCents, message = job.message)
            }
        }
    }

    /**
     * The store restarted (the queue is memory only): look the PaymentIntent up
     * at Stripe. Not yet paid → put it back on the phone's queue.
     */
    private fun requeue(terminalRef: String): PaymentResult {
        val c = stripe()
        val pi = c.retrievePaymentIntent(terminalRef, listOf("expand[]" to "latest_charge"))
        val r = StripeTerminalAdapter.toResult(pi)
        val secret = pi.str("client_secret")
        if (r.outcome == Outcome.PENDING && secret != null && r.amountCents != null) {
            hub.enqueue(PhoneReaderHub.Job(terminalRef, secret, r.amountCents, pi.str("currency") ?: currency(),
                pi.str("description"), clock()))
            return r.copy(readerPrompt = "waiting_for_phone")
        }
        return r
    }

    /** Past the payment timeout with no card: cancel at Stripe, end the job. */
    private fun expireIfOld(job: PhoneReaderHub.Job): Boolean {
        if (!job.active || clock() - job.createdAt <= timeoutSeconds * 1000L) return false
        hub.end(job.paymentIntentId, "timeout", "timeout")
        releaseQuietly(job.paymentIntentId)
        return true
    }

    private fun releaseQuietly(terminalRef: String) {
        runCatching { client()?.cancelPaymentIntent(terminalRef, "pos-ttp-release-$terminalRef") }
    }

    private fun phoneMessage(job: PhoneReaderHub.Job): String = when (job.code) {
        "tapToPayInsecureEnvironment" -> "The phone refused the payment: turn Developer options off on the phone, then try again"
        "tapToPayDeviceTampered" -> "The phone failed Stripe's security check (rooted or modified phone)"
        "tapToPayUnsupportedDevice", "tapToPayUnsupportedOperatingSystemVersion" ->
            "This phone can't take Tap to Pay (needs NFC and Android 13 or newer)"
        "tapToPayDebugNotSupported" -> "Tap to Pay needs the release build of the card reader app"
        else -> job.message ?: "the phone couldn't take the card (${job.code ?: "error"})"
    }

    override fun capture(terminalRef: String, idempotencyKey: String): PaymentResult = api().capture(terminalRef, idempotencyKey)

    override fun captureAmount(terminalRef: String, idempotencyKey: String, amountCents: Long): PaymentResult =
        api().captureAmount(terminalRef, idempotencyKey, amountCents)

    override fun cancel(terminalRef: String, idempotencyKey: String): PaymentResult {
        hub.end(terminalRef, "canceled", "pos_canceled")
        val c = stripe()
        return try {
            StripeTerminalAdapter.toResult(c.cancelPaymentIntent(terminalRef, idempotencyKey))
        } catch (e: StripeException) {
            if (e.unreachable) throw e
            // already captured (a sale that finished first) or already cancelled: report where it is
            api().result(terminalRef)
        }
    }

    override fun refund(request: TerminalRefundRequest): TerminalRefundResult = api().refund(request)
}
