package dev.dwhipstock.pos.payments.terminal

import kotlinx.serialization.Serializable

/**
 * Card-present payment terminal, as the store sees it. One adapter per kind of
 * terminal (Stripe Terminal, the built-in simulator, J.P. Morgan…), selected per
 * store by `payment.terminal` (see [dev.dwhipstock.pos.sdk.PaymentTerminalConfig]).
 *
 * The adapter only talks to the terminal / processor. It knows nothing about
 * checks, tenders or refunds rows: [dev.dwhipstock.pos.payments.TerminalPaymentService]
 * (and, for Stripe, [dev.dwhipstock.pos.payments.StripeService]) own that.
 *
 * Flow — authorize on the terminal, then finalize on the store:
 *  1. [startPayment] asks the terminal to take [PaymentRequest.amountCents].
 *  2. The customer taps / inserts / swipes. [result] reports progress and, at
 *     the end, APPROVED / DECLINED / CANCELLED / TIMEOUT / ERROR.
 *  3. [capture] finalizes an approval the store still wants (a terminal that
 *     only does sales returns the same approved result).
 *  4. [cancel] stops a payment still in progress, or releases / voids an
 *     approval the store no longer wants.
 *  5. [refund] gives money back against a finished payment, full or partial.
 *
 * Every call may throw [TerminalException]. Implementations must be quick to
 * fail when the terminal is unreachable: nothing else in the POS waits on them.
 */
interface PaymentTerminal {
    val kind: TerminalKind

    /** What the reader is doing right now. Never throws: unreachable → [ReaderState.OFFLINE]. */
    fun status(): ReaderStatus

    /** Pair / connect to the reader. [pairingCode] is what the reader shows, when it needs one. */
    fun connect(host: String? = null, pairingCode: String? = null): ReaderStatus

    fun startPayment(request: PaymentRequest): TerminalPayment

    /** Current state of a payment started with [startPayment]. */
    fun result(terminalRef: String): PaymentResult

    fun capture(terminalRef: String, idempotencyKey: String): PaymentResult

    fun cancel(terminalRef: String, idempotencyKey: String): PaymentResult

    fun refund(request: TerminalRefundRequest): TerminalRefundResult
}

@Serializable
enum class TerminalKind {
    STRIPE, SIMULATOR, JPMORGAN, EXTERNAL, OFF;

    val wire: String get() = name.lowercase()

    /** The POS drives this terminal itself (a payment shows its progress and records on approval). */
    val integrated: Boolean get() = this == STRIPE || this == SIMULATOR || this == JPMORGAN

    companion object {
        fun parse(raw: String?): TerminalKind? = when (raw?.trim()?.lowercase()) {
            "stripe" -> STRIPE
            "simulator", "sim" -> SIMULATOR
            "jpmorgan", "jpm", "j.p.morgan" -> JPMORGAN
            "external" -> EXTERNAL
            "off", "none" -> OFF
            else -> null
        }
    }
}

enum class TipMode {
    /** The amount is final. */
    NONE,
    /** The reader asks the customer for a tip before the card. */
    ON_READER;

    val wire: String get() = name.lowercase()

    companion object {
        fun parse(raw: String?): TipMode = when (raw?.trim()?.lowercase()) {
            "on_reader", "reader", "prompt" -> ON_READER
            else -> NONE
        }
    }
}

@Serializable
enum class EntryMode {
    TAP, INSERT, SWIPE, KEYED, UNKNOWN;

    val wire: String get() = name.lowercase()
}

enum class Outcome {
    /** Still on the reader (waiting for a card, a PIN, the host…). */
    PENDING,
    APPROVED,
    DECLINED,
    CANCELLED,
    TIMEOUT,
    ERROR;

    val final: Boolean get() = this != PENDING
}

enum class ReaderState {
    /** Connected, nothing in progress. */
    IDLE,
    /** A payment or refund is on the reader. */
    BUSY,
    /** Needs pairing before it can take a payment. */
    NOT_PAIRED,
    /** Can't be reached right now (LAN down, reader off, processor offline). */
    OFFLINE,
    /** The reader lives elsewhere (Stripe: the tablet's SDK connects it). */
    EXTERNAL;

    val wire: String get() = name.lowercase()
}

data class ReaderStatus(
    val state: ReaderState,
    /** Human label for the reader ("Mac terminal (simulator)", "AXIUM DX8000"…). */
    val name: String? = null,
    /** Where the store reaches it (host:port), when it's on the LAN. */
    val address: String? = null,
    /** Machine code when not usable (terminal_unreachable, terminal_not_paired…). */
    val reason: String? = null,
    /** The reader runs inside this store (built-in simulator): the tablet may play it. */
    val embedded: Boolean = false,
) {
    val ready: Boolean get() = state == ReaderState.IDLE || state == ReaderState.EXTERNAL
}

data class PaymentRequest(
    /** The store's own id for this attempt; also the terminal-side idempotency key. */
    val reference: String,
    val amountCents: Long,
    /** ISO 4217, upper or lower case. */
    val currency: String,
    val tipMode: TipMode = TipMode.NONE,
    val description: String? = null,
    /** Extra tags the processor stores with the payment (ordered). */
    val metadata: List<Pair<String, String>> = emptyList(),
)

data class TerminalPayment(
    /** The terminal/processor's id for this payment (Stripe PaymentIntent, simulator txn…). */
    val terminalRef: String,
    /** Stripe only: the PaymentIntent secret the tablet's Terminal SDK collects with. Never stored. */
    val clientSecret: String? = null,
    val amountCents: Long,
)

/** What the card said. Only the masked last four digits, never a full number. */
@Serializable
data class CardDetails(
    val brand: String? = null,
    val last4: String? = null,
    val entryMode: EntryMode = EntryMode.UNKNOWN,
    val authCode: String? = null,
    /** EMV application id (chip / tap), e.g. A0000000031010. */
    val aid: String? = null,
    /** EMV terminal verification results / transaction status info. */
    val tvr: String? = null,
    val tsi: String? = null,
    /** EMV application label ("VISA CREDIT"). */
    val appLabel: String? = null,
    /** Cardholder verification ("PIN VERIFIED", "NO CVM", "SIGNATURE"). */
    val cvm: String? = null,
    /** Tip the customer added on the reader, charged on top of the tender amount. */
    val tipCents: Long = 0,
    /** The processor's transaction id (J.P. Morgan transactionId…), shown on the tablet and receipt. */
    val processorRef: String? = null,
    /** Who processed it, for the receipt ("J.P. Morgan sandbox"). */
    val processor: String? = null,
)

data class PaymentResult(
    val terminalRef: String,
    val outcome: Outcome,
    /** The processor's own status word (Stripe: requires_capture, succeeded…). */
    val rawStatus: String? = null,
    /** Amount the card was authorized for, tip included. */
    val amountCents: Long? = null,
    val tipCents: Long = 0,
    /** Money has moved (captured / sale), not just a hold. */
    val captured: Boolean = false,
    val card: CardDetails? = null,
    /** Machine decline reason (insufficient_funds, do_not_honor, card_declined…). */
    val declineCode: String? = null,
    val message: String? = null,
    /** The store's reference the terminal holds for this payment, if it echoes one. */
    val reference: String? = null,
    /** What the reader is showing, for the POS's progress line (present_card, enter_pin, processing…). */
    val readerPrompt: String? = null,
)

data class TerminalRefundRequest(
    val terminalRef: String,
    /** null = the whole remaining amount. */
    val amountCents: Long?,
    val idempotencyKey: String,
    val metadata: List<Pair<String, String>> = emptyList(),
    /** The processor's transaction id saved with the tender ([CardDetails.processorRef]), when there is one. */
    val processorRef: String? = null,
)

data class TerminalRefundResult(
    val refundRef: String,
    val outcome: Outcome,
    val rawStatus: String? = null,
    val amountCents: Long? = null,
    val message: String? = null,
)

/**
 * A terminal call that did not do what was asked. [status]/[code] go straight to
 * the client (HTTP status + machine code). `terminal_unavailable` (503) means the
 * terminal or processor could not be reached: nothing was recorded and the check
 * stays payable by any other tender.
 */
open class TerminalException(
    val status: Int,
    val code: String,
    message: String,
    val declineCode: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    open val unreachable: Boolean get() = code == UNAVAILABLE

    companion object {
        const val UNAVAILABLE = "terminal_unavailable"
        const val DECLINED = "terminal_declined"
        const val ERROR = "terminal_error"
        const val NOT_PAIRED = "terminal_not_paired"
        const val BUSY = "terminal_busy"
        const val OFF = "terminal_off"
    }
}
