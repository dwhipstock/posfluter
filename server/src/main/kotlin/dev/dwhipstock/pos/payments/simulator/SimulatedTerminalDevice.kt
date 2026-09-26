package dev.dwhipstock.pos.payments.simulator

import dev.dwhipstock.pos.payments.terminal.CardDetails
import dev.dwhipstock.pos.payments.terminal.EntryMode
import dev.dwhipstock.pos.payments.terminal.TerminalException
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.UUID
import kotlin.random.Random

/**
 * The built-in card terminal simulator: the "firmware" of a pretend countertop
 * reader. No network, no card data, no internet. It runs either inside the
 * store (reader page at /terminal, or a sheet on the tablet) or stand-alone on
 * another machine on the LAN (tools/TerminalSimulatorMain, scripts/demo-terminal.sh),
 * reached by IP like a real terminal.
 *
 * Two sides, like the real thing:
 *  - the POS side ([start], [get], [cancel], [capture], [refund], [pair]):
 *    what a store sends over the LAN;
 *  - the customer side ([present], [enterPin], [chooseTip], [customerCancel],
 *    [screen]): the buttons on the reader page.
 *
 * Time moves lazily: every read advances the current transaction by the clock
 * (realistic "Processing…" delays, the card timeout), so tests drive it with a
 * fake clock instead of sleeping. Thread-safe (one lock).
 */
class SimulatedTerminalDevice(
    val name: String = "Counter terminal (simulator)",
    /** Stand-alone terminals make the POS pair with the code on screen; the built-in one doesn't. */
    val requirePairing: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random(SecureRandom().nextLong()),
    val delays: Delays = Delays(),
) {
    /** How long each step "takes" on the reader, in ms. */
    data class Delays(
        val tapMs: Long = 1_400,
        val swipeMs: Long = 1_900,
        val chipMs: Long = 2_600,
        /** Up to this much extra, at random, so it doesn't feel canned. */
        val jitterMs: Long = 700,
        /** The result stays on the reader this long, then back to the idle screen. */
        val resultScreenMs: Long = 6_000,
    ) {
        companion object { val INSTANT = Delays(0, 0, 0, 0, 0) }
    }

    enum class State { TIP, PRESENT_CARD, ENTER_PIN, PROCESSING, APPROVED, DECLINED, CANCELLED, TIMEOUT }

    /** What the customer's card will do (the reader page's outcome buttons). */
    enum class Scenario(val wire: String) {
        APPROVE("approve"),
        INSUFFICIENT_FUNDS("insufficient_funds"),
        DO_NOT_HONOUR("do_not_honour"),
        TIMEOUT("timeout"),
        CANCEL("cancel");

        companion object {
            fun parse(raw: String?): Scenario = entries.firstOrNull { it.wire == raw?.trim()?.lowercase() }
                ?: when (raw?.trim()?.lowercase()) {
                    "decline", "declined" -> DO_NOT_HONOUR
                    "do_not_honor" -> DO_NOT_HONOUR
                    "cancelled", "canceled" -> CANCEL
                    else -> APPROVE
                }
        }
    }

    /** Test cards: brand, masked last 4 and the EMV application they carry. */
    enum class TestCard(val wire: String, val brand: String, val last4: String, val aid: String, val appLabel: String) {
        VISA("visa", "Visa", "4242", "A0000000031010", "VISA CREDIT"),
        MASTERCARD("mastercard", "Mastercard", "4444", "A0000000041010", "MASTERCARD"),
        AMEX("amex", "Amex", "8431", "A00000002501", "AMERICAN EXPRESS"),
        DISCOVER("discover", "Discover", "1117", "A0000001523010", "DISCOVER"),
        INTERAC("interac", "Interac", "0002", "A0000002771010", "INTERAC");

        companion object {
            fun parse(raw: String?): TestCard = entries.firstOrNull { it.wire == raw?.trim()?.lowercase() } ?: VISA
        }
    }

    private class Txn(
        val id: String,
        val reference: String,
        val amountCents: Long,
        val currency: String,
        val timeoutMs: Long,
        var state: State,
        var waitingSince: Long,
        val label: String?,
    ) {
        var tipCents = 0L
        var entry: EntryMode? = null
        var card: TestCard? = null
        var scenario: Scenario = Scenario.APPROVE
        var processingUntil = 0L
        var finishedAt = 0L
        var authCode: String? = null
        var declineCode: String? = null
        var message: String? = null
        var captured = false
        var voided = false
        var refundedCents = 0L
        /** A real processor decides (J.P. Morgan sandbox): the store posts [hostDecision]. */
        var hostAuth = false
        var processingSince = 0L
        var hostDecision: HostDecision? = null
        val total: Long get() = amountCents + tipCents
        val awaitingHost: Boolean get() = state == State.PROCESSING && hostAuth && hostDecision == null
    }

    /** The processor's answer for a host-authorized payment. */
    data class HostDecision(
        val approved: Boolean,
        val authCode: String? = null,
        val declineCode: String? = null,
        val message: String? = null,
        val processorRef: String? = null,
        val processor: String? = null,
    )

    private data class Refund(val id: String, val txnId: String, val key: String, val amountCents: Long, val authCode: String)

    private val lock = Any()
    private val txns = LinkedHashMap<String, Txn>()
    private val refunds = mutableListOf<Refund>()
    private var currentId: String? = null
    private val tokens = mutableSetOf<String>()
    /** Shown on the reader while unpaired; a new one after each pairing. */
    var pairingCode: String = newPairingCode()
        private set
    val terminalId: String = "SIM-" + (100000 + random.nextInt(900000))

    private fun newPairingCode() = (100000 + random.nextInt(900000)).toString()
    private fun now() = clock()

    // --- pairing -------------------------------------------------------------

    val paired: Boolean get() = synchronized(lock) { tokens.isNotEmpty() }

    /** Pair a POS with the code on the reader's screen. Returns its bearer token. */
    fun pair(code: String): String = synchronized(lock) {
        if (code.trim() != pairingCode)
            throw TerminalException(401, "terminal_pairing_code_wrong", "That pairing code is not the one on the terminal")
        val token = UUID.randomUUID().toString().replace("-", "")
        tokens += token
        pairingCode = newPairingCode()
        token
    }

    fun authorized(token: String?): Boolean = !requirePairing || synchronized(lock) { token != null && token in tokens }

    // --- POS side --------------------------------------------------------------

    fun status(): SimStatusView = synchronized(lock) {
        val cur = current()
        SimStatusView(terminalId, name, MODEL, if (cur != null) "busy" else "idle", paired || !requirePairing, requirePairing)
    }

    /**
     * A new sale on the reader. The same [reference] again returns the same
     * transaction (a retried request can't charge twice); a different one while
     * another is in progress → 409 terminal_busy.
     */
    fun start(reference: String, amountCents: Long, currency: String, tipOnReader: Boolean,
              timeoutSeconds: Int, label: String? = null, hostAuthorization: Boolean = false): SimTxnView = synchronized(lock) {
        require(amountCents > 0) { "amount must be positive" }
        txns.values.firstOrNull { it.reference == reference }?.let { return view(advance(it)) }
        current()?.let { throw TerminalException(409, TerminalException.BUSY, "the terminal is busy with another payment") }
        val t = Txn(
            id = "sim_" + UUID.randomUUID().toString().replace("-", "").take(20),
            reference = reference, amountCents = amountCents, currency = currency.uppercase(),
            timeoutMs = timeoutSeconds * 1000L,
            state = if (tipOnReader) State.TIP else State.PRESENT_CARD,
            waitingSince = now(), label = label,
        )
        t.hostAuth = hostAuthorization
        txns[t.id] = t
        currentId = t.id
        trim()
        view(t)
    }

    fun get(id: String): SimTxnView = synchronized(lock) { view(advance(txn(id))) }

    /**
     * Stop a payment still waiting for the customer (→ CANCELLED), or void an
     * approval that was never captured. Once the card is being processed it
     * can't be stopped (like a real terminal talking to the host); a captured
     * payment needs a refund.
     */
    fun cancel(id: String): SimTxnView = synchronized(lock) {
        val t = advance(txn(id))
        when (t.state) {
            State.TIP, State.PRESENT_CARD, State.ENTER_PIN -> finish(t, State.CANCELLED, message = "Cancelled by the POS")
            State.APPROVED -> {
                if (t.captured) throw TerminalException(409, "terminal_already_captured", "payment already captured; refund it instead")
                t.voided = true
                t.message = "Voided"
            }
            State.PROCESSING -> throw TerminalException(409, "terminal_cancel_unavailable", "the card is being processed; it can't be cancelled now")
            else -> {}
        }
        view(t)
    }

    /**
     * The processor's answer for a host-authorized payment (the store asked J.P.
     * Morgan). Only while the reader is waiting for it; a repeat is ignored.
     */
    fun hostResponse(id: String, decision: HostDecision): SimTxnView = synchronized(lock) {
        val t = advance(txn(id))
        if (t.hostAuth && t.hostDecision == null && t.state == State.PROCESSING) {
            t.hostDecision = decision
            advance(t)
        }
        view(t)
    }

    fun capture(id: String): SimTxnView = synchronized(lock) {
        val t = advance(txn(id))
        if (t.state != State.APPROVED || t.voided)
            throw TerminalException(409, "terminal_not_approved", "payment is ${t.state.name.lowercase()}; nothing to capture")
        t.captured = true
        view(t)
    }

    /** Refund a captured payment, full ([amountCents] null) or partial. Idempotent on [key]. */
    fun refund(id: String, amountCents: Long?, key: String): SimRefundView = synchronized(lock) {
        refunds.firstOrNull { it.key == key }?.let { return SimRefundView(it.id, it.txnId, it.amountCents, true, it.authCode) }
        val t = advance(txn(id))
        if (t.state != State.APPROVED || !t.captured || t.voided)
            throw TerminalException(409, "terminal_not_refundable", "only a captured payment can be refunded")
        val left = t.total - t.refundedCents
        val amount = amountCents ?: left
        if (amount <= 0 || amount > left)
            throw TerminalException(409, "terminal_refund_exceeds_payment", "refund exceeds what is left on this payment ($left cents)")
        val r = Refund("simre_" + UUID.randomUUID().toString().replace("-", "").take(16), t.id, key, amount, authCode())
        refunds += r
        t.refundedCents += amount
        SimRefundView(r.id, t.id, amount, true, r.authCode)
    }

    // --- customer side (the reader page) --------------------------------------

    /** What the reader shows right now. */
    fun screen(): SimScreenView = synchronized(lock) {
        val t = currentOrRecent()
        val needsPairing = requirePairing && tokens.isEmpty()
        if (t == null) return SimScreenView(
            screen = if (needsPairing) "pairing" else "idle", name = name, terminalId = terminalId,
            pairingCode = if (requirePairing) pairingCode else null, paired = !needsPairing,
        )
        SimScreenView(
            screen = when (t.state) {
                State.TIP -> "tip"
                State.PRESENT_CARD -> "present"
                State.ENTER_PIN -> "pin"
                State.PROCESSING -> "processing"
                State.APPROVED -> if (t.voided) "cancelled" else "approved"
                State.DECLINED -> "declined"
                State.CANCELLED -> "cancelled"
                State.TIMEOUT -> "timeout"
            },
            name = name, terminalId = terminalId,
            pairingCode = if (requirePairing) pairingCode else null, paired = !needsPairing,
            txnId = t.id, amountCents = t.amountCents, tipCents = t.tipCents, totalCents = t.total,
            currency = t.currency, label = t.label,
            entryMode = t.entry?.wire, brand = t.card?.brand, last4 = t.card?.last4,
            authCode = t.authCode, message = t.message,
            secondsLeft = if (t.state in WAITING) ((t.timeoutMs - (now() - t.waitingSince)) / 1000).coerceAtLeast(0) else null,
        )
    }

    fun chooseTip(tipCents: Long): SimScreenView = synchronized(lock) {
        val t = requireCurrent(State.TIP)
        require(tipCents in 0..t.amountCents * 2) { "tip out of range" }
        t.tipCents = tipCents
        t.state = State.PRESENT_CARD
        t.waitingSince = now()
        screen()
    }

    /**
     * The customer taps, inserts or swipes [card]; [scenario] is what the
     * issuer will say. Insert asks for a PIN first. "timeout" and "cancel"
     * end the payment right away (the reader timed out / the customer
     * pressed the red key).
     */
    fun present(entry: EntryMode, card: TestCard, scenario: Scenario): SimScreenView = synchronized(lock) {
        val t = requireCurrent(State.PRESENT_CARD)
        t.entry = entry
        t.card = card
        t.scenario = scenario
        when (scenario) {
            Scenario.TIMEOUT -> finish(t, State.TIMEOUT, message = "Timed out")
            Scenario.CANCEL -> finish(t, State.CANCELLED, message = "Cancelled on the terminal")
            else -> if (entry == EntryMode.INSERT) {
                t.state = State.ENTER_PIN
                t.waitingSince = now()
            } else process(t)
        }
        screen()
    }

    fun enterPin(pin: String): SimScreenView = synchronized(lock) {
        val t = requireCurrent(State.ENTER_PIN)
        if (!pin.matches(Regex("\\d{4,6}"))) throw TerminalException(400, "terminal_pin_invalid", "PIN must be 4 to 6 digits")
        process(t)
        screen()
    }

    /** The red key: cancels anything still waiting for the customer. */
    fun customerCancel(): SimScreenView = synchronized(lock) {
        current()?.takeIf { it.state in WAITING }?.let { finish(it, State.CANCELLED, message = "Cancelled on the terminal") }
        screen()
    }

    // --- internals -------------------------------------------------------------

    private fun process(t: Txn) {
        val base = when (t.entry) {
            EntryMode.TAP -> delays.tapMs
            EntryMode.SWIPE -> delays.swipeMs
            else -> delays.chipMs
        }
        t.state = State.PROCESSING
        t.processingSince = now()
        t.processingUntil = now() + base + if (delays.jitterMs > 0) random.nextLong(delays.jitterMs) else 0
        advance(t)
    }

    /** Move [t] along by the clock: finish processing, or time out a waiting reader. */
    private fun advance(t: Txn): Txn {
        val now = now()
        if (t.state in WAITING && now - t.waitingSince >= t.timeoutMs) finish(t, State.TIMEOUT, message = "Timed out")
        if (t.state == State.PROCESSING && t.hostAuth) {
            val d = t.hostDecision
            if (d == null && now - t.processingSince >= HOST_TIMEOUT_MS)
                finish(t, State.TIMEOUT, message = "No answer from the card processor")
            else if (d != null && now >= t.processingUntil) {
                if (d.approved) { t.authCode = d.authCode ?: authCode(); finish(t, State.APPROVED, message = d.message ?: "Approved") }
                else finish(t, State.DECLINED, d.declineCode ?: "card_declined", d.message ?: "Declined")
            }
            return t
        }
        if (t.state == State.PROCESSING && now >= t.processingUntil) {
            when (t.scenario) {
                Scenario.INSUFFICIENT_FUNDS -> finish(t, State.DECLINED, "insufficient_funds", "Declined — insufficient funds")
                Scenario.DO_NOT_HONOUR -> finish(t, State.DECLINED, "do_not_honor", "Declined — do not honour")
                else -> { t.authCode = authCode(); finish(t, State.APPROVED, message = "Approved") }
            }
        }
        return t
    }

    private fun finish(t: Txn, state: State, declineCode: String? = null, message: String? = null) {
        t.state = state
        t.declineCode = declineCode
        t.message = message
        t.finishedAt = now()
        if (currentId == t.id) currentId = null
    }

    private fun authCode() = (0 until 6).map { random.nextInt(10) }.joinToString("")

    private fun current(): Txn? = currentId?.let { txns[it] }?.let(::advance)?.takeIf { it.state in ACTIVE }

    /** The active transaction, else the last one while its result is still on screen. */
    private fun currentOrRecent(): Txn? = current()
        ?: txns.values.lastOrNull()?.takeIf { now() - it.finishedAt < delays.resultScreenMs }

    private fun requireCurrent(state: State): Txn = current()?.takeIf { it.state == state }
        ?: throw TerminalException(409, "terminal_wrong_step", "the terminal isn't waiting for that right now")

    private fun txn(id: String): Txn = txns[id] ?: throw TerminalException(404, "terminal_payment_not_found", "no such payment on the terminal")

    /** Keep the last 200 transactions (a demo terminal, not a ledger). */
    private fun trim() {
        while (txns.size > 200) txns.remove(txns.keys.first())
        while (refunds.size > 400) refunds.removeAt(0)
    }

    private fun view(t: Txn) = SimTxnView(
        id = t.id, reference = t.reference,
        state = if (t.state == State.APPROVED && t.voided) "VOIDED" else t.state.name,
        amountCents = t.amountCents, tipCents = t.tipCents, totalCents = t.total, currency = t.currency,
        card = t.card?.takeIf { t.state in setOf(State.APPROVED, State.DECLINED) || t.awaitingHost }?.let { card -> cardDetails(t, card) },
        declineCode = t.declineCode, message = t.message, captured = t.captured, refundedCents = t.refundedCents,
        awaitingHost = t.awaitingHost,
        scenario = t.scenario.wire.takeIf { t.awaitingHost },
        cardKey = t.card?.wire.takeIf { t.awaitingHost },
    )

    private fun cardDetails(t: Txn, card: TestCard): CardDetails {
        val entry = t.entry ?: EntryMode.UNKNOWN
        val emv = entry == EntryMode.TAP || entry == EntryMode.INSERT
        return CardDetails(
            brand = card.brand, last4 = card.last4, entryMode = entry, authCode = t.authCode,
            aid = if (emv) card.aid else null,
            // placeholders shaped like a real EMV slip (all-zero TVR = no issues found)
            tvr = if (emv) "0000000000" else null,
            tsi = when (entry) { EntryMode.INSERT -> "E800"; EntryMode.TAP -> "0000"; else -> null },
            appLabel = if (emv) card.appLabel else null,
            cvm = when (entry) { EntryMode.INSERT -> "PIN VERIFIED"; EntryMode.TAP -> "NO CVM"; EntryMode.SWIPE -> "SIGNATURE"; else -> null },
            tipCents = t.tipCents,
            processorRef = t.hostDecision?.processorRef,
            processor = t.hostDecision?.processor,
        )
    }

    companion object {
        const val MODEL = "POS-SIM 1"
        /** A host-authorized payment gives up on the processor after this long. */
        const val HOST_TIMEOUT_MS = 60_000L
        const val DEFAULT_PORT = 8090
        private val WAITING = setOf(State.TIP, State.PRESENT_CARD, State.ENTER_PIN)
        private val ACTIVE = WAITING + State.PROCESSING
    }
}

@Serializable
data class SimStatusView(
    val terminalId: String,
    val name: String,
    val model: String,
    /** idle | busy */
    val state: String,
    val paired: Boolean,
    val requiresPairing: Boolean,
)

@Serializable
data class SimTxnView(
    val id: String,
    val reference: String,
    /** TIP | PRESENT_CARD | ENTER_PIN | PROCESSING | APPROVED | VOIDED | DECLINED | CANCELLED | TIMEOUT */
    val state: String,
    val amountCents: Long,
    val tipCents: Long,
    val totalCents: Long,
    val currency: String,
    val card: CardDetails? = null,
    val declineCode: String? = null,
    val message: String? = null,
    val captured: Boolean = false,
    val refundedCents: Long = 0,
    /** Host-authorized: the card is read and the reader waits for the processor's answer. */
    val awaitingHost: Boolean = false,
    /** While [awaitingHost]: the outcome button pressed (approve, insufficient_funds, do_not_honour) and the test card. */
    val scenario: String? = null,
    val cardKey: String? = null,
)

@Serializable
data class SimHostRequest(
    val approved: Boolean,
    val authCode: String? = null,
    val declineCode: String? = null,
    val message: String? = null,
    val processorRef: String? = null,
    val processor: String? = null,
)

@Serializable
data class SimRefundView(val refundId: String, val txnId: String, val amountCents: Long, val approved: Boolean, val authCode: String? = null)

/** The reader page's whole state (polled by terminal-sim.html and the tablet sheet). */
@Serializable
data class SimScreenView(
    /** pairing | idle | tip | present | pin | processing | approved | declined | cancelled | timeout */
    val screen: String,
    val name: String,
    val terminalId: String,
    val pairingCode: String? = null,
    val paired: Boolean = true,
    val txnId: String? = null,
    val amountCents: Long? = null,
    val tipCents: Long? = null,
    val totalCents: Long? = null,
    val currency: String? = null,
    val label: String? = null,
    val entryMode: String? = null,
    val brand: String? = null,
    val last4: String? = null,
    val authCode: String? = null,
    val message: String? = null,
    val secondsLeft: Long? = null,
)
