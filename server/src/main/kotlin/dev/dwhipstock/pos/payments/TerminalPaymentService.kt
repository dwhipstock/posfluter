package dev.dwhipstock.pos.payments

import dev.dwhipstock.pos.db.SyncState
import dev.dwhipstock.pos.payments.simulator.HttpSimulatorLink
import dev.dwhipstock.pos.payments.simulator.InProcessSimulatorLink
import dev.dwhipstock.pos.payments.simulator.SimulatedTerminalDevice
import dev.dwhipstock.pos.payments.simulator.SimulatorLink
import dev.dwhipstock.pos.payments.terminal.CardDetails
import dev.dwhipstock.pos.payments.terminal.Outcome
import dev.dwhipstock.pos.payments.terminal.PaymentRequest
import dev.dwhipstock.pos.payments.terminal.PaymentResult
import dev.dwhipstock.pos.payments.terminal.PaymentTerminal
import dev.dwhipstock.pos.payments.terminal.ReaderState
import dev.dwhipstock.pos.payments.terminal.ReaderStatus
import dev.dwhipstock.pos.payments.terminal.TerminalException
import dev.dwhipstock.pos.payments.terminal.TerminalKind
import dev.dwhipstock.pos.payments.terminal.TerminalRefundRequest
import dev.dwhipstock.pos.payments.terminal.TipMode
import dev.dwhipstock.pos.restaurant.CheckService
import dev.dwhipstock.pos.restaurant.CheckView
import dev.dwhipstock.pos.restaurant.ConflictException
import dev.dwhipstock.pos.restaurant.NotFoundException
import dev.dwhipstock.pos.restaurant.RefundLineRequest
import dev.dwhipstock.pos.restaurant.RefundResult
import dev.dwhipstock.pos.restaurant.TenderView
import dev.dwhipstock.pos.restaurant.TerminalPayments
import dev.dwhipstock.pos.restaurant.decodeCard
import dev.dwhipstock.pos.restaurant.encodeCard
import dev.dwhipstock.pos.sdk.PaymentTerminalConfig
import dev.dwhipstock.pos.sdk.TenderType
import dev.dwhipstock.pos.sdk.VenueClock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** GET /payments/terminal — which card terminal this store has and whether it can take a card now. */
@Serializable
data class TerminalStatusView(
    /** stripe | simulator | jpmorgan | external | off */
    val kind: String,
    /** The POS drives the terminal (stripe, simulator, jpmorgan). */
    val integrated: Boolean,
    /** Ready to take a card right now. */
    val available: Boolean,
    /** Machine code when not available (terminal_unavailable, terminal_not_paired, stripe_not_configured…). */
    val reason: String? = null,
    /** idle | busy | not_paired | offline | external */
    val readerState: String,
    val readerName: String? = null,
    /** host:port of a LAN terminal. */
    val address: String? = null,
    /** The simulator runs inside this store: the tablet can play the reader (sheet). */
    val embedded: Boolean = false,
    /** A LAN terminal that needs the pairing code from its screen. */
    val pairingRequired: Boolean = false,
    /** The reader can ask for a tip. */
    val tipOnReader: Boolean = false,
    val timeoutSeconds: Int = PaymentTerminalConfig.DEFAULT_TIMEOUT_SECONDS,
    val currency: String,
)

@Serializable
data class TerminalPaymentView(
    val paymentId: String,
    /** PENDING | RECORDED | DECLINED | CANCELED | TIMEOUT | FAILED */
    val status: String,
    val amountCents: Long,
    val tipCents: Long = 0,
    val currency: String,
    /** While PENDING: what the reader is asking for (choose_tip, present_card, enter_pin, processing). */
    val prompt: String? = null,
    val card: CardDetails? = null,
    val declineCode: String? = null,
    /** Machine code for FAILED / a store-side cancel (terminal_amount_exceeds_due, terminal_payment_reversed…). */
    val errorCode: String? = null,
    val message: String? = null,
    /** The terminal didn't answer this poll (LAN down); the payment stays PENDING. */
    val readerOffline: Boolean = false,
    val embedded: Boolean = false,
    val tender: TenderView? = null,
    val check: CheckView? = null,
)

/**
 * Card payments on an integrated terminal other than Stripe — the built-in
 * simulator or J.P. Morgan — through the [PaymentTerminal] contract. Stripe
 * keeps its own [StripeService] (the tablet SDK flow); this service reports its
 * status too so the POS asks one place which terminal the store has.
 *
 * Offline-first, like Stripe: nothing here runs unless someone presses "Charge
 * card", nothing blocks startup, login or another tender, and every terminal
 * call is outside a database transaction. A dead terminal is a coded 503 and
 * the check stays payable by cash or the hand-keyed card tender.
 *
 * Flow: [start] locks the amount due and starts a sale on the reader → the POS
 * polls [refresh] while the customer taps / inserts / swipes → on approval
 * [refresh] re-checks what is still due, captures, and records a TERMINAL
 * tender (a check paid another way meanwhile gets the approval voided, not a
 * double charge) → [cancel] stops it. [refund] refunds on the terminal first
 * and records only what the terminal refunded.
 */
class TerminalPaymentService(
    val kind: TerminalKind,
    private val terminal: PaymentTerminal?,
    private val checks: CheckService,
    private val currency: String,
    private val storeName: String,
    private val config: PaymentTerminalConfig.Resolved = PaymentTerminalConfig.DEFAULT,
    /** Stripe stores: /payments/terminal reports Stripe's own status. */
    private val stripe: StripeService? = null,
    /** Simulator stores: which simulator (built-in or the paired LAN one). */
    val simulators: SimulatorHub? = null,
) {
    companion object {
        private val log = LoggerFactory.getLogger(TerminalPaymentService::class.java)
        private val FINAL = setOf("RECORDED", "DECLINED", "CANCELED", "TIMEOUT", "FAILED")
    }

    private val moneyLock = ReentrantLock()

    /** This store takes cards on a terminal this service drives. */
    val drivesPayments: Boolean get() = terminal != null && kind != TerminalKind.STRIPE

    fun status(): TerminalStatusView {
        val base = TerminalStatusView(kind = kind.wire, integrated = kind.integrated, available = false,
            readerState = ReaderState.OFFLINE.wire, currency = currency, timeoutSeconds = config.timeoutSeconds)
        return when (kind) {
            TerminalKind.EXTERNAL -> base.copy(available = true, readerState = ReaderState.EXTERNAL.wire,
                readerName = "External card terminal")
            TerminalKind.OFF -> base.copy(reason = TerminalException.OFF)
            TerminalKind.STRIPE -> {
                val st = stripe?.status()
                base.copy(available = st?.available == true, reason = if (st?.available == true) null else st?.reason ?: "stripe_not_configured",
                    readerState = ReaderState.EXTERNAL.wire, readerName = "Stripe Terminal")
            }
            else -> {
                val t = terminal ?: return base.copy(reason = TerminalException.OFF)
                val r = t.status()
                base.copy(
                    available = r.ready, reason = r.reason ?: if (r.ready) null else when (r.state) {
                        ReaderState.BUSY -> TerminalException.BUSY
                        ReaderState.NOT_PAIRED -> TerminalException.NOT_PAIRED
                        else -> TerminalException.UNAVAILABLE
                    },
                    readerState = r.state.wire, readerName = r.name, address = r.address ?: simulators?.remoteAddress(),
                    embedded = r.embedded, pairingRequired = r.state == ReaderState.NOT_PAIRED,
                    tipOnReader = kind == TerminalKind.SIMULATOR || kind == TerminalKind.JPMORGAN,
                    // a busy reader can still resume this store's own pending payment
                ).let { if (r.state == ReaderState.BUSY) it.copy(available = true, reason = null) else it }
            }
        }
    }

    /** Pair with a LAN terminal ([host] = "ip[:port]") using the code on its screen; "" host = back to the built-in one. */
    fun pair(host: String?, code: String?): TerminalStatusView {
        val hub = simulators ?: throw ConflictException("this store's terminal (${kind.wire}) doesn't pair from the POS", "terminal_pairing_unsupported")
        hub.pair(host, code)
        return status()
    }

    fun unpair(): TerminalStatusView {
        simulators?.unpair() ?: throw ConflictException("nothing to unpair", "terminal_pairing_unsupported")
        return status()
    }

    private fun requireTerminal(): PaymentTerminal = terminal?.takeIf { drivesPayments }
        ?: throw TerminalException(409, TerminalException.OFF, "this store has no integrated card terminal (${kind.wire})")

    // --- payments ----------------------------------------------------------

    fun start(checkId: Int, groupId: Int?, amountCents: Long?, tipMode: TipMode): TerminalPaymentView {
        val t = requireTerminal()
        // a payment for this check still on the reader (tablet restarted, double tap): resume it
        pendingFor(checkId, groupId).forEach { pid -> runCatching { refresh(pid) } }
        pendingFor(checkId, groupId).firstOrNull()?.let { return refresh(it) }
        val outstanding = checks.lockForElectronicPayment(checkId, groupId).cents
        val amount = amountCents ?: outstanding
        require(amount in 1..outstanding) { "amount must be within outstanding balance" }
        val publicId = UUID.randomUUID().toString()
        val now = VenueClock.now()
        transaction {
            TerminalPayments.insert {
                it[TerminalPayments.publicId] = publicId
                it[provider] = kind.wire
                it[TerminalPayments.checkId] = checkId
                it[billGroupId] = groupId
                it[TerminalPayments.amountCents] = amount
                it[TerminalPayments.currency] = this@TerminalPaymentService.currency
                it[status] = "CREATING"
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        val started = try {
            t.startPayment(PaymentRequest(
                reference = publicId, amountCents = amount, currency = currency, tipMode = tipMode,
                description = "$storeName — check #$checkId" + (groupId?.let { " / group $it" } ?: ""),
                metadata = listOfNotNull("check_id" to checkId.toString(), groupId?.let { "group_id" to it.toString() }),
            ))
        } catch (e: TerminalException) {
            mark(publicId, "FAILED", errorCode = e.code, error = e.message)
            throw e
        }
        mark(publicId, "PENDING", terminalRef = started.terminalRef, prompt = "present_card")
        log.info("Card terminal (${kind.wire}): payment ${started.terminalRef} started for check #$checkId: $amount $currency")
        return refresh(publicId)
    }

    /**
     * Where the payment is now. Polled by the POS while the customer pays; on
     * approval this is where the tender gets recorded (idempotent: a repeat
     * returns the recorded tender). A terminal that doesn't answer leaves it
     * PENDING with [TerminalPaymentView.readerOffline].
     */
    fun refresh(paymentId: String): TerminalPaymentView = moneyLock.withLock {
        val row = row(paymentId)
        if (row.status in FINAL) return view(row)
        val ref = row.terminalRef ?: return view(row)
        val t = requireTerminal()
        val res = try {
            t.result(ref)
        } catch (e: TerminalException) {
            if (e.unreachable) return view(row, readerOffline = true)
            throw e
        }
        when (res.outcome) {
            Outcome.PENDING -> {
                if (res.readerPrompt != row.prompt) mark(paymentId, "PENDING", prompt = res.readerPrompt)
                view(row(paymentId))
            }
            Outcome.APPROVED -> finalize(row, res, t)
            Outcome.DECLINED -> end(paymentId, "DECLINED", declineCode = res.declineCode ?: "card_declined",
                error = res.message, card = res.card)
            Outcome.CANCELLED -> end(paymentId, "CANCELED", error = res.message)
            Outcome.TIMEOUT -> end(paymentId, "TIMEOUT", error = res.message)
            Outcome.ERROR -> end(paymentId, "FAILED", errorCode = TerminalException.ERROR, error = res.message)
        }
    }

    private fun finalize(row: Row, res: PaymentResult, t: PaymentTerminal): TerminalPaymentView {
        val paymentId = row.publicId
        val ref = row.terminalRef!!
        // recorded already (a crash between the tender and the mark)?
        checks.tenderIdForTerminalRef(ref)?.let { tid -> return end(paymentId, "RECORDED", tenderId = tid) }
        if (res.reference != null && res.reference != paymentId) {
            log.warn("Card terminal $ref belongs to ${res.reference}, not $paymentId; voiding")
            runCatching { t.cancel(ref, "pos-cancel-$paymentId") }
            return end(paymentId, "FAILED", errorCode = "terminal_mismatch", error = "terminal payment does not match")
        }
        val tip = res.tipCents
        if (res.amountCents != null && res.amountCents - tip != row.amount) {
            runCatching { t.cancel(ref, "pos-cancel-$paymentId") }
            return end(paymentId, "FAILED", errorCode = "terminal_mismatch", error = "terminal amount ${res.amountCents} != ${row.amount} + tip $tip")
        }
        // still owed? (the check may have been paid another way meanwhile)
        val due = runCatching { checks.lockForElectronicPayment(row.checkId, row.groupId).cents }.getOrDefault(0L)
        if (due < row.amount) {
            release(ref, paymentId, res, t)
            return end(paymentId, "CANCELED", errorCode = "terminal_amount_exceeds_due",
                error = "the check no longer owes this amount; the card was not charged")
        }
        val captured = if (res.captured) res else try {
            t.capture(ref, "pos-capture-$paymentId")
        } catch (e: TerminalException) {
            if (e.unreachable) return view(row(paymentId), readerOffline = true) // approved; capture on the next poll
            release(ref, paymentId, res, t)
            return end(paymentId, "FAILED", errorCode = e.code, error = e.message)
        }
        val card = (captured.card ?: res.card)?.copy(tipCents = tip)
        val tender = try {
            checks.recordTerminalTender(row.checkId, row.amount, ref, kind.wire, row.groupId, card)
        } catch (e: Exception) {
            // money was taken but the check can't take it (voided meanwhile…): give it straight back
            log.warn("Card terminal $ref captured but not recordable on check #${row.checkId} (${e.message}); refunding")
            val back = runCatching {
                t.refund(TerminalRefundRequest(ref, null, "pos-autorefund-$paymentId", processorRef = card?.processorRef))
            }.getOrNull()
            if (back?.outcome != Outcome.APPROVED)
                log.error("Card terminal $ref captured, not recorded, and the refund failed — refund it on the terminal")
            return end(paymentId, "FAILED", errorCode = "terminal_payment_reversed",
                error = "the card payment could not be applied to this check and was refunded", card = card, tip = tip)
        }
        log.info("Card terminal $ref recorded as tender #${tender.id} on check #${row.checkId}")
        return end(paymentId, "RECORDED", tenderId = tender.id, card = card, tip = tip)
    }

    /** Give back an approval the store won't keep: void a hold, refund a sale. */
    private fun release(ref: String, paymentId: String, res: PaymentResult, t: PaymentTerminal) {
        runCatching {
            if (res.captured) t.refund(TerminalRefundRequest(ref, null, "pos-autorefund-$paymentId"))
            else t.cancel(ref, "pos-cancel-$paymentId")
        }.onFailure { log.error("Card terminal $ref: could not release an unwanted approval (${it.message}) — void it on the terminal") }
    }

    /**
     * Stop a payment: cancel it on the reader, record nothing. A terminal that
     * can't be reached still closes the attempt here (the check stays
     * payable); a card already being processed answers 409 and keeps going.
     */
    fun cancel(paymentId: String): TerminalPaymentView = moneyLock.withLock {
        val row = row(paymentId)
        if (row.status == "RECORDED")
            throw ConflictException("card payment $paymentId is already recorded; refund it instead", "terminal_already_recorded")
        if (row.status in FINAL) return view(row)
        val ref = row.terminalRef ?: return end(paymentId, "CANCELED")
        val t = requireTerminal()
        try {
            val res = t.cancel(ref, "pos-cancel-$paymentId")
            if (res.outcome == Outcome.APPROVED && res.captured) {
                // a sale-only terminal finished just before the cancel: give it back
                release(ref, paymentId, res, t)
            }
        } catch (e: TerminalException) {
            if (!e.unreachable) throw e
            log.warn("Card terminal unreachable while cancelling $ref; closed here (check it on the terminal)")
            return end(paymentId, "CANCELED", errorCode = e.code, error = e.message)
        }
        end(paymentId, "CANCELED", error = "Cancelled")
    }

    // --- refunds -----------------------------------------------------------

    /**
     * Refund a check back to the card on the terminal: validate like any
     * refund, refund on the terminal (one payment at a time), then record.
     * Terminal unreachable or refusing → nothing is recorded. The idempotency
     * key is (payment, already refunded, this amount), so a retry can't refund twice.
     */
    fun refund(
        checkId: Int, amountCents: Long?, lines: List<RefundLineRequest>?, reason: String, managerId: String,
    ): RefundResult = moneyLock.withLock {
        val plan = checks.planRefund(checkId, amountCents, lines, TenderType.TERMINAL.name, reason, managerId)
        val t = requireTerminal()
        val capacity = checks.terminalRefundCapacity(checkId)
        if (capacity.isEmpty()) throw ConflictException("check $checkId has no terminal card payment", "terminal_no_card_tender")
        val pick = capacity.firstOrNull { it.remainingCents >= plan.gross }
            ?: if (capacity.sumOf { it.remainingCents } >= plan.gross)
                throw ConflictException("refund each card payment separately (max ${capacity.maxOf { it.remainingCents }} cents)", "terminal_refund_split_required")
            else throw ConflictException("refund exceeds what was paid by card on the terminal (${capacity.sumOf { it.remainingCents }} cents left)", "terminal_refund_exceeds_card")
        val ref = pick.terminalRef
        val key = "pos-refund-$ref-${pick.paidCents - pick.remainingCents}-${plan.gross}"
        val refund = t.refund(TerminalRefundRequest(ref, plan.gross, key,
            listOf("check_id" to checkId.toString(), "refunded_by" to managerId), processorRef = pick.processorRef))
        if (refund.outcome != Outcome.APPROVED)
            throw TerminalException(502, "terminal_refund_failed", refund.message ?: "the terminal did not refund (${refund.rawStatus})")
        log.info("Card terminal refund ${refund.refundRef}: ${plan.gross} on $ref (check #$checkId)")
        checks.recordRefund(plan, terminalPaymentRef = ref, terminalRefundRef = refund.refundRef, terminalProvider = kind.wire)
    }

    // --- local rows --------------------------------------------------------

    private data class Row(
        val publicId: String, val checkId: Int, val groupId: Int?, val amount: Long, val tip: Long, val currency: String,
        val terminalRef: String?, val status: String, val prompt: String?, val cardJson: String?,
        val declineCode: String?, val errorCode: String?, val lastError: String?, val tenderId: Int?,
    )

    private fun view(row: Row, readerOffline: Boolean = false): TerminalPaymentView {
        val recorded = row.tenderId?.takeIf { row.status == "RECORDED" }
        return TerminalPaymentView(
            paymentId = row.publicId, status = row.status, amountCents = row.amount, tipCents = row.tip,
            currency = row.currency.uppercase(), prompt = row.prompt.takeIf { row.status == "PENDING" },
            card = decodeCard(row.cardJson), declineCode = row.declineCode, errorCode = row.errorCode,
            message = row.lastError, readerOffline = readerOffline,
            embedded = simulators?.embeddedInUse() == true,
            tender = recorded?.let { checks.tenderView(it) },
            check = recorded?.let { checks.getCheck(row.checkId) },
        )
    }

    private fun end(
        paymentId: String, status: String, tenderId: Int? = null, declineCode: String? = null,
        errorCode: String? = null, error: String? = null, card: CardDetails? = null, tip: Long? = null,
    ): TerminalPaymentView {
        mark(paymentId, status, tenderId = tenderId, declineCode = declineCode, errorCode = errorCode,
            error = error, card = card, tip = tip, prompt = null)
        return view(row(paymentId))
    }

    private fun pendingFor(checkId: Int, groupId: Int?): List<String> = transaction {
        TerminalPayments.selectAll()
            .where { (TerminalPayments.checkId eq checkId) and (TerminalPayments.status eq "PENDING") }
            .orderBy(TerminalPayments.id to SortOrder.ASC)
            .filter { it[TerminalPayments.billGroupId] == groupId }
            .map { it[TerminalPayments.publicId] }
    }

    private fun row(paymentId: String): Row = transaction {
        TerminalPayments.selectAll().where { TerminalPayments.publicId eq paymentId }.firstOrNull()?.let {
            Row(it[TerminalPayments.publicId], it[TerminalPayments.checkId], it[TerminalPayments.billGroupId],
                it[TerminalPayments.amountCents], it[TerminalPayments.tipCents], it[TerminalPayments.currency],
                it[TerminalPayments.terminalRef], it[TerminalPayments.status], it[TerminalPayments.prompt],
                it[TerminalPayments.cardJson], it[TerminalPayments.declineCode], it[TerminalPayments.errorCode],
                it[TerminalPayments.lastError], it[TerminalPayments.tenderId])
        }
    } ?: throw NotFoundException("card payment $paymentId not found", "terminal_payment_not_found")

    private fun mark(
        paymentId: String, status: String, terminalRef: String? = null, tenderId: Int? = null,
        prompt: String? = null, declineCode: String? = null, errorCode: String? = null, error: String? = null,
        card: CardDetails? = null, tip: Long? = null,
    ) = transaction {
        TerminalPayments.update({ TerminalPayments.publicId eq paymentId }) {
            it[TerminalPayments.status] = status
            terminalRef?.let { r -> it[TerminalPayments.terminalRef] = r }
            tenderId?.let { t -> it[TerminalPayments.tenderId] = t }
            it[TerminalPayments.prompt] = prompt
            declineCode?.let { d -> it[TerminalPayments.declineCode] = d }
            errorCode?.let { c -> it[TerminalPayments.errorCode] = c }
            error?.let { e -> it[lastError] = e.take(300) }
            card?.let { c -> it[cardJson] = encodeCard(c) }
            tip?.let { t -> it[tipCents] = t }
            it[updatedAt] = VenueClock.now()
        }
    }
}

/**
 * Which simulator a simulator store talks to: the stand-alone one on the LAN
 * when a host is known (`payment.terminal.host`, or paired from the POS), else
 * the built-in [embedded] one. The pairing token and host live in sync_state
 * (local only, never synced).
 */
class SimulatorHub(
    val embedded: SimulatedTerminalDevice,
    private val config: PaymentTerminalConfig.Resolved,
    private val linkFactory: (host: String, port: Int, token: () -> String?) -> SimulatorLink =
        { h, p, tok -> HttpSimulatorLink(h, p, tok) },
) {
    companion object {
        const val KEY_HOST = "terminal.simulator.host"
        const val KEY_TOKEN = "terminal.simulator.token"
    }

    private val inProcess = InProcessSimulatorLink(embedded)
    @Volatile private var cached: Pair<String, SimulatorLink>? = null

    /** host:port of the LAN terminal in use, null = the built-in one. */
    fun remoteAddress(): String? = transaction { SyncState.get(KEY_HOST) }?.takeIf { it.isNotBlank() }
        ?: config.host?.let { "$it:${config.port ?: SimulatedTerminalDevice.DEFAULT_PORT}" }

    fun embeddedInUse(): Boolean = remoteAddress() == null

    fun link(): SimulatorLink {
        val address = remoteAddress() ?: return inProcess
        cached?.takeIf { it.first == address }?.let { return it.second }
        val (h, p) = PaymentTerminalConfig.parseHost(address)
        val l = linkFactory(h ?: address, p ?: SimulatedTerminalDevice.DEFAULT_PORT) { transaction { SyncState.get(KEY_TOKEN) }?.takeIf { it.isNotBlank() } }
        cached = address to l
        return l
    }

    /** [host] blank → forget the LAN terminal and use the built-in one. */
    fun pair(host: String?, code: String?) {
        val raw = host?.trim().orEmpty()
        if (raw.isEmpty() && config.host == null) { unpair(); return }
        val (h, p) = PaymentTerminalConfig.parseHost(raw.ifEmpty { remoteAddress() })
        if (h == null) throw ConflictException("enter the terminal's IP address, like 192.168.1.50:8090", "terminal_bad_host")
        val port = p ?: SimulatedTerminalDevice.DEFAULT_PORT
        val c = code?.trim().orEmpty()
        if (c.isEmpty()) throw ConflictException("enter the pairing code shown on the terminal", "terminal_pairing_code_required")
        val res = linkFactory(h, port) { null }.pair(c)
        transaction {
            SyncState.set(KEY_HOST, "$h:$port")
            SyncState.set(KEY_TOKEN, res.token)
        }
        cached = null
    }

    fun unpair() {
        transaction {
            SyncState.set(KEY_HOST, "")
            SyncState.set(KEY_TOKEN, "")
        }
        cached = null
    }
}
