package dev.dwhipstock.pos.payments

import dev.dwhipstock.pos.payments.terminal.CardDetails
import dev.dwhipstock.pos.payments.terminal.EntryMode
import dev.dwhipstock.pos.payments.terminal.Outcome
import dev.dwhipstock.pos.payments.terminal.PaymentRequest
import dev.dwhipstock.pos.payments.terminal.PaymentResult
import dev.dwhipstock.pos.payments.terminal.PaymentTerminal
import dev.dwhipstock.pos.payments.terminal.ReaderState
import dev.dwhipstock.pos.payments.terminal.ReaderStatus
import dev.dwhipstock.pos.payments.terminal.TerminalKind
import dev.dwhipstock.pos.payments.terminal.TerminalPayment
import dev.dwhipstock.pos.payments.terminal.TerminalRefundRequest
import dev.dwhipstock.pos.payments.terminal.TerminalRefundResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Stripe Terminal behind the [PaymentTerminal] contract: card_present
 * PaymentIntents with manual capture. The reader itself is connected by the
 * tablet's Terminal SDK (mek_stripe_terminal), which collects the payment with
 * [TerminalPayment.clientSecret]; this adapter only speaks to Stripe's REST API.
 *
 * Stripe-specific setup (account currency, Terminal Location, connection
 * tokens) stays in [StripeService], which drives this adapter for every money
 * call. Idempotency keys are the caller's, so a retry can't charge twice.
 */
class StripeTerminalAdapter(private val client: StripeClient) : PaymentTerminal {
    override val kind = TerminalKind.STRIPE

    /** The reader is paired on the tablet, not by the store. */
    override fun status() = ReaderStatus(ReaderState.EXTERNAL, name = "Stripe Terminal (reader on the tablet)")

    override fun connect(host: String?, pairingCode: String?) = status()

    override fun startPayment(request: PaymentRequest): TerminalPayment {
        val params = listOfNotNull(
            "amount" to request.amountCents.toString(),
            "currency" to request.currency.lowercase(),
            "payment_method_types[]" to "card_present",
            "capture_method" to "manual",
            request.description?.let { "description" to it },
        ) + request.metadata.map { (k, v) -> "metadata[$k]" to v }
        val pi = client.createPaymentIntent(params, "pos-pi-${request.reference}")
        val id = pi.str("id") ?: throw StripeException(502, StripeException.ERROR, "Stripe returned no PaymentIntent id")
        return TerminalPayment(id, pi.str("client_secret") ?: "", request.amountCents)
    }

    override fun result(terminalRef: String): PaymentResult = toResult(client.retrievePaymentIntent(terminalRef))

    override fun capture(terminalRef: String, idempotencyKey: String): PaymentResult =
        // the charge comes back expanded so the receipt can show the card (brand, last 4, EMV)
        toResult(client.capturePaymentIntent(terminalRef, idempotencyKey, listOf("expand[]" to "latest_charge")))

    override fun cancel(terminalRef: String, idempotencyKey: String): PaymentResult =
        toResult(client.cancelPaymentIntent(terminalRef, idempotencyKey))

    override fun refund(request: TerminalRefundRequest): TerminalRefundResult {
        val params = listOfNotNull(
            "payment_intent" to request.terminalRef,
            request.amountCents?.let { "amount" to it.toString() },
        ) + request.metadata.map { (k, v) -> "metadata[$k]" to v }
        val refund = client.createRefund(params, request.idempotencyKey)
        val id = refund.str("id") ?: throw StripeException(502, StripeException.ERROR, "Stripe returned no refund id")
        val status = refund.str("status")
        val outcome = when (status) {
            "failed", "canceled" -> Outcome.ERROR
            else -> Outcome.APPROVED // succeeded / pending: Stripe has it
        }
        return TerminalRefundResult(id, outcome, status, refund["amount"]?.jsonPrimitive?.longOrNull)
    }

    companion object {
        /** A PaymentIntent as a [PaymentResult]. Unknown statuses are PENDING. */
        fun toResult(pi: JsonObject): PaymentResult {
            val status = pi.str("status")
            val err = pi["last_payment_error"]?.let { runCatching { it.jsonObject }.getOrNull() }
            val outcome = when (status) {
                "requires_capture", "succeeded" -> Outcome.APPROVED
                "requires_payment_method" -> if (err != null) Outcome.DECLINED else Outcome.PENDING
                "canceled" -> Outcome.CANCELLED
                else -> Outcome.PENDING
            }
            return PaymentResult(
                terminalRef = pi.str("id") ?: "",
                outcome = outcome,
                rawStatus = status,
                amountCents = pi["amount"]?.jsonPrimitive?.longOrNull,
                captured = status == "succeeded",
                card = cardOf(pi),
                declineCode = err?.let { it.str("decline_code") ?: it.str("code") },
                message = err?.str("message"),
                reference = pi.str("metadata.pos_payment_id"),
                readerPrompt = if (outcome == Outcome.PENDING) "present_card" else null,
            )
        }

        /**
         * Card fields from the charge, when Stripe sent it expanded
         * (`latest_charge` object, or the older `charges.data[0]`). A bare
         * charge id → null: the receipt then just says "Card (Stripe)".
         */
        fun cardOf(pi: JsonObject): CardDetails? {
            val charge = pi["latest_charge"]?.let { runCatching { it.jsonObject }.getOrNull() }
                ?: pi["charges"]?.let { c ->
                    runCatching { c.jsonObject["data"]!!.jsonArray.firstOrNull()?.jsonObject }.getOrNull()
                }
                ?: return null
            val details = charge["payment_method_details"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: return null
            val present = (details["card_present"] ?: details["interac_present"])
                ?.let { runCatching { it.jsonObject }.getOrNull() } ?: return null
            val receipt = present["receipt"]?.let { runCatching { it.jsonObject }.getOrNull() }
            val entry = when (present.str("read_method")) {
                "contactless_emv", "contactless_magstripe_mode" -> EntryMode.TAP
                "contact_emv" -> EntryMode.INSERT
                "magnetic_stripe_track2", "magnetic_stripe_fallback" -> EntryMode.SWIPE
                null -> EntryMode.UNKNOWN
                else -> EntryMode.UNKNOWN
            }
            return CardDetails(
                brand = present.str("brand")?.let(::brandLabel),
                last4 = present.str("last4"),
                entryMode = entry,
                authCode = receipt?.str("authorization_code"),
                aid = receipt?.str("dedicated_file_name"),
                tvr = receipt?.str("terminal_verification_results"),
                tsi = receipt?.str("transaction_status_information"),
                appLabel = receipt?.str("application_preferred_name"),
                cvm = receipt?.str("cardholder_verification_method")?.uppercase()?.replace('_', ' '),
            )
        }

        fun brandLabel(raw: String): String = when (raw.lowercase()) {
            "visa" -> "Visa"
            "mastercard" -> "Mastercard"
            "amex", "american_express" -> "Amex"
            "discover" -> "Discover"
            "interac" -> "Interac"
            "jcb" -> "JCB"
            "diners" -> "Diners"
            "unionpay" -> "UnionPay"
            else -> raw.replaceFirstChar { it.uppercase() }
        }
    }
}
