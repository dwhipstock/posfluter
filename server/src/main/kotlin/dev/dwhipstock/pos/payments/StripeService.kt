package dev.dwhipstock.pos.payments

import dev.dwhipstock.pos.db.SyncState
import dev.dwhipstock.pos.restaurant.CheckService
import dev.dwhipstock.pos.restaurant.CheckView
import dev.dwhipstock.pos.restaurant.ConflictException
import dev.dwhipstock.pos.restaurant.NotFoundException
import dev.dwhipstock.pos.restaurant.RefundLineRequest
import dev.dwhipstock.pos.restaurant.RefundResult
import dev.dwhipstock.pos.restaurant.StripePayments
import dev.dwhipstock.pos.restaurant.TenderView
import dev.dwhipstock.pos.sdk.StripeConfig
import dev.dwhipstock.pos.sdk.TenderType
import dev.dwhipstock.pos.sdk.VenueClock
import dev.dwhipstock.pos.payments.terminal.PaymentRequest
import dev.dwhipstock.pos.payments.terminal.PaymentTerminal
import dev.dwhipstock.pos.payments.terminal.TerminalRefundRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** GET /stripe/status — what the tender screen needs to enable "Card (Stripe)". */
@Serializable
data class StripeStatus(
    /** A key is set (valid or not). false → the client hides the option. */
    val configured: Boolean,
    /** Ready to take a card right now (test key, Stripe reachable, location). */
    val available: Boolean,
    /** Machine code when not available (stripe_not_configured, stripe_live_key_refused, stripe_unavailable…). */
    val reason: String? = null,
    /** ISO code PaymentIntents are created in: the Stripe account's default currency. */
    val currency: String? = null,
    val venueCurrency: String = StripeService.VENUE_CURRENCY.uppercase(),
    val locationId: String? = null,
    val testMode: Boolean = true,
    val simulatedReader: Boolean = true,
)

@Serializable
data class StripeIntentView(
    val paymentId: String,
    val paymentIntentId: String,
    /** For the Terminal SDK on this tablet only; never stored or synced. */
    val clientSecret: String,
    val amountCents: Long,
    val currency: String,
    val locationId: String?,
)

@Serializable
data class StripePaymentView(
    val paymentId: String,
    val status: String,
    val paymentIntentId: String? = null,
    val stripeStatus: String? = null,
    val amountCents: Long,
    val currency: String,
    val tenderId: Int? = null,
)

@Serializable
data class StripeTenderResponse(val tender: TenderView, val check: CheckView, val paymentId: String)

/**
 * Stripe Terminal card payments for the store (TEST MODE, simulated reader).
 *
 * Offline-first: nothing here runs unless someone picks "Card (Stripe)", and
 * nothing here can block startup, login, or another tender. Every Stripe call
 * is outside a database transaction, and a failure leaves the check exactly as
 * payable (by cash or anything else) as before.
 *
 * Flow — capture_method=manual (authorize on the reader, capture on the store):
 *  1. [createIntent] locks the check total and creates a card_present
 *     PaymentIntent for (up to) the amount due, in the Stripe account's currency.
 *  2. The tablet's Terminal SDK collects + authorizes it on the simulated reader.
 *  3. [confirm] re-checks what is still due, captures, then records a STRIPE
 *     tender through the ordinary tender path. Manual capture means money only
 *     moves once the store has confirmed the check still owes it: a check paid
 *     another way meanwhile gets the authorization released, not a double charge.
 *  4. [cancel] releases the PaymentIntent; nothing is recorded.
 * Refunds ([refund]) are made at Stripe first and recorded only if Stripe made them.
 *
 * The secret key lives only in [config] → [UrlStripeHttp]; it is never logged,
 * stored in the database, or written to the sync outbox.
 */
class StripeService(
    private val config: StripeConfig.Resolved,
    private val checks: CheckService,
    /** Stable store id (POS_VENUE) — tags the Terminal Location this store reuses. */
    private val storeKey: String,
    private val storeName: String,
    http: StripeHttp? = null,
) {
    companion object {
        const val VENUE_CURRENCY = "cad"
        private val log = LoggerFactory.getLogger(StripeService::class.java)
        private const val ACCOUNT_TTL_MS = 10 * 60_000L
        private const val FAILURE_BACKOFF_MS = 10_000L

        /** Fictional Montréal address for an auto-created Terminal Location. */
        val DEMO_ADDRESS = listOf("line1" to "100 Rue de la Commune Ouest", "city" to "Montréal",
            "state" to "QC", "postal_code" to "H2Y 2C6", "country" to "CA")
    }

    private val client: StripeClient? =
        if (config.enabled) StripeClient(http ?: UrlStripeHttp(config.secretKey!!)) else null

    /** Every money call goes through the [PaymentTerminal] contract (the Stripe Terminal adapter). */
    private val terminal: StripeTerminalAdapter? = client?.let(::StripeTerminalAdapter)

    /** The Stripe adapter, for callers that speak the generic terminal contract (null = Stripe off). */
    val adapter: PaymentTerminal? get() = terminal

    private data class Account(val id: String, val country: String?, val currency: String, val fetchedAt: Long)

    @Volatile private var account: Account? = null
    @Volatile private var locationId: String? = config.locationId
    @Volatile private var lastFailureAt = 0L
    @Volatile private var lastFailure: StripeException? = null
    @Volatile private var warnedCurrency = false
    /** One Stripe money operation at a time: a double-tapped confirm must not record twice. */
    private val moneyLock = ReentrantLock()

    val enabled: Boolean get() = client != null

    /**
     * Startup: log the config, then (daemon thread, never blocking or failing
     * startup) fetch the account so the currency is known before the first sale.
     */
    fun start() {
        if (config.disabled == StripeConfig.Disabled.NOT_TEST_KEY) log.warn(config.describe())
        else log.info(config.describe())
        if (client == null) return
        Thread({ runCatching { ensureAccount() }.onFailure { log.info("Stripe account not reachable yet: ${it.message}") } },
            "stripe-account").apply { isDaemon = true }.start()
    }

    // --- status / setup ----------------------------------------------------

    fun status(): StripeStatus {
        if (client == null) return StripeStatus(
            configured = config.disabled != StripeConfig.Disabled.NOT_CONFIGURED,
            available = false, reason = config.disabled?.code ?: "stripe_not_configured")
        return try {
            val acct = ensureAccount()
            val loc = ensureLocation(acct)
            StripeStatus(true, true, null, acct.currency.uppercase(), locationId = loc)
        } catch (e: StripeException) {
            StripeStatus(true, false, e.code, account?.currency?.uppercase(), locationId = locationId)
        }
    }

    private fun requireClient(): StripeClient = client
        ?: throw StripeException(409, config.disabled?.code ?: "stripe_not_configured", "Stripe is disabled")

    private fun requireTerminal(): StripeTerminalAdapter = terminal
        ?: throw StripeException(409, config.disabled?.code ?: "stripe_not_configured", "Stripe is disabled")

    private fun <T> remember(block: () -> T): T = try {
        block().also { lastFailure = null }
    } catch (e: StripeException) {
        if (e.unreachable) { lastFailure = e; lastFailureAt = System.currentTimeMillis() }
        throw e
    }

    private fun ensureAccount(): Account {
        val c = requireClient()
        account?.takeIf { System.currentTimeMillis() - it.fetchedAt < ACCOUNT_TTL_MS }
            ?.let { requireVenueCurrency(it); return it }
        // offline: don't hammer (or wait on) Stripe on every tender-screen open
        lastFailure?.takeIf { System.currentTimeMillis() - lastFailureAt < FAILURE_BACKOFF_MS }?.let { cached ->
            account?.let { requireVenueCurrency(it); return it }
            throw cached
        }
        val a = remember { c.account() }
        val currency = a.str("default_currency")?.lowercase() ?: VENUE_CURRENCY
        val acct = Account(a.str("id") ?: "acct", a.str("country"), currency, System.currentTimeMillis())
        account = acct
        return acct.also(::requireVenueCurrency)
    }

    /**
     * The store sells in CAD: an account in any other currency disables Stripe
     * (logged once, reported by /stripe/status), never a converted charge.
     */
    private fun requireVenueCurrency(acct: Account) {
        if (acct.currency == VENUE_CURRENCY) return
        if (!warnedCurrency) {
            warnedCurrency = true
            log.warn("Stripe: DISABLED — the Stripe account's currency is ${acct.currency.uppercase()} " +
                "(country ${acct.country}), but the store sells in ${VENUE_CURRENCY.uppercase()}. " +
                "Use a Canadian (CAD) Stripe account.")
        }
        throw StripeException(409, "stripe_currency_mismatch",
            "Stripe account currency ${acct.currency.uppercase()} is not ${VENUE_CURRENCY.uppercase()}")
    }

    /**
     * The Terminal Location readers register to: STRIPE_LOCATION_ID if set, else
     * the one this store created earlier (remembered per Stripe account in
     * sync_state, local only), else one found by its `pos_store` metadata, else
     * a new one (idempotency key per store + account).
     */
    private fun ensureLocation(acct: Account): String {
        locationId?.let { return it }
        val c = requireClient()
        val stateKey = "stripe_location:${acct.id}"
        transaction { SyncState.get(stateKey) }?.let { locationId = it; return it }
        val existing = remember { c.listLocations() }["data"]?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .firstOrNull { it["metadata"]?.jsonObject?.str("pos_store") == storeKey }
            ?.str("id")
        val id = existing ?: run {
            val address = DEMO_ADDRESS
            val params = listOf("display_name" to "$storeName (POS test)", "metadata[pos_store]" to storeKey) +
                address.map { (k, v) -> "address[$k]" to v }
            remember { c.createLocation(params, "pos-location-$storeKey-${acct.id}") }.str("id")
                ?: throw StripeException(502, StripeException.ERROR, "Stripe returned no location id")
        }
        transaction { SyncState.set(stateKey, id) }
        log.info("Stripe Terminal location: $id (${if (existing != null) "reused" else "created"})")
        locationId = id
        return id
    }

    fun connectionToken(): String {
        val c = requireClient()
        val loc = ensureLocation(ensureAccount())
        return remember { c.connectionToken(loc) }.str("secret")
            ?: throw StripeException(502, StripeException.ERROR, "Stripe returned no connection token")
    }

    // --- payments ----------------------------------------------------------

    fun createIntent(checkId: Int, groupId: Int?, amountCents: Long?): StripeIntentView {
        val t = requireTerminal()
        val acct = ensureAccount()
        val loc = ensureLocation(acct)
        val outstanding = checks.lockForElectronicPayment(checkId, groupId).cents
        val amount = amountCents ?: outstanding
        require(amount in 1..outstanding) { "amount must be within outstanding balance" }
        val publicId = UUID.randomUUID().toString()
        val now = VenueClock.now()
        transaction {
            StripePayments.insert {
                it[StripePayments.publicId] = publicId
                it[StripePayments.checkId] = checkId
                it[billGroupId] = groupId
                it[StripePayments.amountCents] = amount
                it[currency] = acct.currency
                it[status] = "CREATING"
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        val request = PaymentRequest(
            reference = publicId,
            amountCents = amount,
            currency = acct.currency,
            description = "$storeName — check #$checkId" + (groupId?.let { " / group $it" } ?: ""),
            metadata = listOfNotNull(
                "pos_payment_id" to publicId,
                "check_id" to checkId.toString(),
                groupId?.let { "group_id" to it.toString() },
                "tender" to TenderType.STRIPE.name,
                "pos_store" to storeKey,
            ),
        )
        val pi = try {
            remember { t.startPayment(request) }
        } catch (e: StripeException) {
            mark(publicId, "FAILED", error = e.message)
            throw e
        }
        val piId = pi.terminalRef
        mark(publicId, "CREATED", piId = piId)
        log.info("Stripe PaymentIntent $piId created for check #$checkId: $amount ${acct.currency}")
        return StripeIntentView(publicId, piId, pi.clientSecret ?: "", amount, acct.currency.uppercase(), loc)
    }

    fun payment(paymentId: String): StripePaymentView {
        val row = row(paymentId)
        val stripeStatus = row.pi?.let { pi -> runCatching { requireTerminal().result(pi).rawStatus }.getOrNull() }
        return row.view(stripeStatus)
    }

    /**
     * Capture the authorized PaymentIntent and record the tender. Idempotent:
     * a repeat returns the tender already recorded. Stripe unreachable/timeout →
     * 503, nothing recorded (safe to retry — capture uses a fixed idempotency key).
     */
    fun confirm(paymentId: String): StripeTenderResponse = moneyLock.withLock {
        val t = requireTerminal()
        val row = row(paymentId)
        row.tenderId?.let { return StripeTenderResponse(checks.tenderView(it), checks.getCheck(row.checkId), paymentId) }
        if (row.status == "CANCELED") throw ConflictException("Stripe payment $paymentId was canceled", "stripe_payment_canceled")
        val piId = row.pi ?: throw ConflictException("Stripe payment $paymentId has no PaymentIntent", "stripe_not_ready")
        // already recorded for this PI (e.g. a crash after the tender, before the mark)?
        checks.tenderIdForPaymentIntent(piId)?.let { tid ->
            mark(paymentId, "RECORDED", tenderId = tid)
            return StripeTenderResponse(checks.tenderView(tid), checks.getCheck(row.checkId), paymentId)
        }
        var pi = remember { t.result(piId) }
        if (pi.reference != null && pi.reference != paymentId)
            throw ConflictException("PaymentIntent does not belong to this payment", "stripe_mismatch")
        if ((pi.amountCents ?: row.amount) != row.amount)
            throw ConflictException("PaymentIntent amount changed", "stripe_mismatch")
        when (val st = pi.rawStatus) {
            "requires_capture" -> {
                // still owed? (the check may have been paid another way meanwhile)
                val due = runCatching { checks.lockForElectronicPayment(row.checkId, row.groupId).cents }.getOrDefault(0L)
                if (due < row.amount) {
                    runCatching { t.cancel(piId, "pos-cancel-$paymentId") }
                    mark(paymentId, "CANCELED", error = "no longer due")
                    throw ConflictException("the check no longer owes this amount; the card was not charged", "stripe_amount_exceeds_due")
                }
                pi = try {
                    remember { t.capture(piId, "pos-capture-$paymentId") }
                } catch (e: StripeException) {
                    mark(paymentId, row.status, error = e.message)
                    throw e
                }
                if (pi.rawStatus != "succeeded")
                    throw StripeException(409, "stripe_not_ready", "PaymentIntent is ${pi.rawStatus} after capture")
                mark(paymentId, "CAPTURED")
            }
            "succeeded" -> {} // captured earlier; the tender just wasn't recorded
            "requires_payment_method" ->
                throw StripeException(402, StripeException.DECLINED, pi.message ?: "card declined", declineCode = pi.declineCode)
            "canceled" -> {
                mark(paymentId, "CANCELED")
                throw ConflictException("Stripe payment $paymentId was canceled", "stripe_payment_canceled")
            }
            else -> throw StripeException(409, "stripe_not_ready", "PaymentIntent is $st")
        }
        val tender = try {
            checks.recordStripeTender(row.checkId, row.amount, piId, row.groupId, card = pi.card)
        } catch (e: Exception) {
            // money was taken but the check can't take it (paid meanwhile, voided…):
            // give it straight back rather than keep an unrecorded payment
            log.warn("Stripe $piId captured but not recordable on check #${row.checkId} (${e.message}); refunding")
            try {
                t.refund(TerminalRefundRequest(piId, null, "pos-autorefund-$paymentId", listOf("pos_payment_id" to paymentId)))
                mark(paymentId, "FAILED", error = "refunded: ${e.message}")
            } catch (re: StripeException) {
                mark(paymentId, "CAPTURED", error = "NOT RECORDED, refund failed: ${re.message}")
                log.error("Stripe $piId captured, not recorded, and the refund failed — refund it in the Stripe dashboard")
            }
            throw ConflictException("the card payment could not be applied to this check and was refunded", "stripe_payment_reversed")
        }
        mark(paymentId, "RECORDED", tenderId = tender.id)
        log.info("Stripe $piId recorded as tender #${tender.id} on check #${row.checkId}")
        StripeTenderResponse(tender, checks.getCheck(row.checkId), paymentId)
    }

    /**
     * Cancel an attempt: release the PaymentIntent at Stripe, record nothing.
     * If Stripe can't be reached the attempt is still closed locally (an
     * uncaptured authorization lapses on its own) and the check stays payable.
     */
    fun cancel(paymentId: String): StripePaymentView = moneyLock.withLock {
        val row = row(paymentId)
        if (row.tenderId != null || row.status == "RECORDED")
            throw ConflictException("Stripe payment $paymentId is already recorded; refund it instead", "stripe_already_recorded")
        if (row.status == "CANCELED") return row.view(null)
        var stripeStatus: String? = null
        var error: String? = null
        row.pi?.let { pi ->
            try {
                stripeStatus = terminal?.cancel(pi, "pos-cancel-$paymentId")?.rawStatus
            } catch (e: StripeException) {
                error = e.message
                // already canceled / never authorized is fine; captured means the
                // tender path failed after capture — give the money back
                val current = runCatching { terminal?.result(pi) }.getOrNull()
                stripeStatus = current?.rawStatus
                if (stripeStatus == "succeeded") {
                    runCatching {
                        terminal?.refund(TerminalRefundRequest(pi, null, "pos-autorefund-$paymentId",
                            listOf("pos_payment_id" to paymentId)))
                    }.onFailure { log.error("Stripe $pi captured but canceled at the POS; refund failed — refund it in the Stripe dashboard") }
                }
            }
        }
        mark(paymentId, "CANCELED", error = error)
        row(paymentId).view(stripeStatus)
    }

    // --- refunds -----------------------------------------------------------

    /**
     * Refund a check back to its Stripe card: validate like any refund, refund
     * at Stripe (one PaymentIntent at a time), then record. Stripe unreachable
     * or refusing → 503/502 and NOTHING is recorded — never a local refund that
     * Stripe didn't make. The idempotency key is derived from (PI, amount
     * already refunded on it, this amount), so a retry after a timeout cannot
     * refund twice.
     */
    fun refund(
        checkId: Int, amountCents: Long?, lines: List<RefundLineRequest>?, reason: String, managerId: String,
    ): RefundResult = moneyLock.withLock {
        val plan = checks.planRefund(checkId, amountCents, lines, TenderType.STRIPE.name, reason, managerId)
        val t = requireTerminal()
        val capacity = checks.stripeRefundCapacity(checkId)
        if (capacity.isEmpty()) throw ConflictException("check $checkId has no Stripe card payment", "stripe_no_card_tender")
        val pick = capacity.firstOrNull { it.remainingCents >= plan.gross }
            ?: if (capacity.sumOf { it.remainingCents } >= plan.gross)
                throw ConflictException("refund each card payment separately (max ${capacity.maxOf { it.remainingCents }} cents)", "stripe_refund_split_required")
            else throw ConflictException("refund exceeds what was paid by Stripe card (${capacity.sumOf { it.remainingCents }} cents left)", "stripe_refund_exceeds_card")
        val piId = pick.paymentIntentId
        val key = "pos-refund-$piId-${pick.paidCents - pick.remainingCents}-${plan.gross}"
        val refund = remember {
            t.refund(TerminalRefundRequest(piId, plan.gross, key, listOf(
                "check_id" to checkId.toString(),
                "refunded_by" to managerId,
            )))
        }
        val refundId = refund.refundRef
        if (refund.rawStatus in setOf("failed", "canceled"))
            throw StripeException(502, "stripe_refund_failed", "Stripe refund $refundId is ${refund.rawStatus}")
        log.info("Stripe refund $refundId: ${plan.gross} on $piId (check #$checkId)")
        checks.recordRefund(plan, stripePaymentIntentId = piId, stripeRefundId = refundId)
    }

    // --- local rows --------------------------------------------------------

    private data class Row(
        val publicId: String, val checkId: Int, val groupId: Int?, val amount: Long, val currency: String,
        val pi: String?, val status: String, val tenderId: Int?,
    ) {
        fun view(stripeStatus: String?) = StripePaymentView(publicId, status, pi, stripeStatus, amount, currency.uppercase(), tenderId)
    }

    private fun row(paymentId: String): Row = transaction {
        StripePayments.selectAll().where { StripePayments.publicId eq paymentId }.firstOrNull()?.let {
            Row(it[StripePayments.publicId], it[StripePayments.checkId], it[StripePayments.billGroupId],
                it[StripePayments.amountCents], it[StripePayments.currency], it[StripePayments.paymentIntentId],
                it[StripePayments.status], it[StripePayments.tenderId])
        }
    } ?: throw NotFoundException("Stripe payment $paymentId not found", "stripe_payment_not_found")

    private fun mark(paymentId: String, status: String, piId: String? = null, tenderId: Int? = null, error: String? = null) =
        transaction {
            StripePayments.update({ StripePayments.publicId eq paymentId }) {
                it[StripePayments.status] = status
                piId?.let { p -> it[paymentIntentId] = p }
                tenderId?.let { t -> it[StripePayments.tenderId] = t }
                it[lastError] = error?.take(300)
                it[updatedAt] = VenueClock.now()
            }
        }
}

/** `a.b` walks nested objects; null when missing or not a string. */
internal fun JsonObject.str(path: String): String? {
    var cur: JsonObject = this
    val parts = path.split('.')
    for (p in parts.dropLast(1)) cur = cur[p]?.let { runCatching { it.jsonObject }.getOrNull() } ?: return null
    return cur[parts.last()]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
}
