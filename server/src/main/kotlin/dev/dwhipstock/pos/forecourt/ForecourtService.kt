package dev.dwhipstock.pos.forecourt

import dev.dwhipstock.pos.base.Tenders
import dev.dwhipstock.pos.restaurant.CheckLines
import dev.dwhipstock.pos.restaurant.CheckService
import dev.dwhipstock.pos.restaurant.CheckView
import dev.dwhipstock.pos.restaurant.Checks
import dev.dwhipstock.pos.restaurant.ConflictException
import dev.dwhipstock.pos.restaurant.NotFoundException
import dev.dwhipstock.pos.sdk.CustomerConfig
import dev.dwhipstock.pos.sdk.Money
import dev.dwhipstock.pos.sdk.Outbox
import dev.dwhipstock.pos.sdk.TenderType
import dev.dwhipstock.pos.sdk.VenueClock
import dev.dwhipstock.pos.sdk.putMoneyContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** What [CheckService] tells the forecourt: a sale closed, lost a line, or was voided / cancelled. */
interface ForecourtHook {
    fun checkChanged(checkId: Int)
}

/**
 * The forecourt on the counter. Polls the controller ([ForecourtAdapter])
 * a few times a second for the pump grid, and keeps the store's own books
 * ([FuelSales]) in step with it:
 *
 * - **postpay**: the cashier authorises a pump; the customer fills up and
 *   comes in; the completed transaction goes onto the sale as a fuel line
 *   (locked at the controller so no other till takes it) and is cleared
 *   when the sale closes.
 * - **prepay**: "$40 on pump 3" goes on the sale as a prepay line; when the
 *   sale closes the pump is authorised up to $40; when the pump finishes,
 *   whatever wasn't pumped is refunded on the sale, on the tender it was
 *   paid with, and the tile shows the change to hand back.
 *
 * Fuel lines carry no added sales tax (the fuel taxes are in the pump
 * price). Everything here is best effort towards the controller and never
 * in the way of the shop: if it can't be reached the tiles say "offline",
 * in-store sales go on, and every step is retried from [reconcile] once it
 * answers again. The store's rows are the truth for money; the controller's
 * are the truth for what the pump dispensed.
 */
class ForecourtService(
    private val config: CustomerConfig,
    private val checks: CheckService,
    private val adapter: ForecourtAdapter,
    /** The store's grades and prices; pushed to the controller when it (re)connects. */
    private val grades: List<FuelGrade>,
    private val pumpCount: Int,
    /** The most a prepay can be, in cents. */
    private val maxPrepayCents: Long = 250_00,
) : ForecourtHook {
    private val log = LoggerFactory.getLogger(ForecourtService::class.java)
    private val lock = Any()

    @Volatile private var last: ForecourtSnapshot? = null
    @Volatile private var online = false
    @Volatile private var lastError: String? = null
    @Volatile private var lastOkAt: java.time.Instant? = null
    @Volatile private var pricesPushed = false
    private var executor: ScheduledExecutorService? = null

    val isOnline: Boolean get() = online

    fun start(intervalMillis: Long = 300) {
        if (executor != null) return
        executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "forecourt-poll").apply { isDaemon = true }
        }.also {
            it.scheduleWithFixedDelay({
                try { tick() } catch (e: Throwable) { log.warn("forecourt tick failed: ${e.message}") }
            }, 0, intervalMillis, TimeUnit.MILLISECONDS)
        }
        log.info("Forecourt: ${adapter.description}, $pumpCount pumps")
    }

    fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    /** One poll + reconcile. The background thread calls this; tests call it directly. */
    fun tick() {
        poll()
        reconcile()
    }

    private fun poll() {
        try {
            val snap = adapter.snapshot()
            last = snap
            if (!online) log.info("Forecourt: online (${adapter.description})")
            online = true
            lastError = null
            lastOkAt = VenueClock.now()
            if (!pricesPushed) {
                runCatching { adapter.setPrices(grades.associate { it.code to it.priceMills }) }
                    .onFailure { log.warn("Forecourt: could not set prices: ${it.message}") }
                pricesPushed = true
            }
        } catch (e: Exception) {
            if (online || lastError == null) log.warn("Forecourt: offline — ${e.message}")
            online = false
            lastError = e.message ?: e.javaClass.simpleName
            pricesPushed = false
        }
    }

    // ------------------------------------------------------------------ the counter's view

    fun view(): ForecourtView {
        val snap = last
        val fdcPumps = if (online && snap != null) snap.pumps.associateBy { it.pump } else emptyMap()
        val trx = if (online && snap != null) snap.transactions else emptyList()
        val rows = transaction {
            FuelSales.selectAll().where {
                (FuelSales.status inList listOf(FuelStatus.IN_BASKET, FuelStatus.AUTHORISED, FuelStatus.AUTH_FAILED)) or
                    ((FuelSales.changeGiven eq false) and (FuelSales.refundCents greater 0L))
            }.orderBy(FuelSales.id, SortOrder.DESC).map { it }
        }
        val gradeNames = grades.associateBy { it.code }
        val pumps = (1..maxOf(pumpCount, fdcPumps.keys.maxOrNull() ?: 0)).map { n ->
            val p = fdcPumps[n]
            val prepay = rows.firstOrNull {
                it[FuelSales.pump] == n && it[FuelSales.mode] == FuelMode.PREPAY.name &&
                    it[FuelSales.status] in setOf(FuelStatus.IN_BASKET, FuelStatus.AUTHORISED, FuelStatus.AUTH_FAILED)
            }
            val change = rows.firstOrNull {
                it[FuelSales.pump] == n && !it[FuelSales.changeGiven] && (it[FuelSales.refundCents] ?: 0) > 0
            }
            val grade = p?.current?.grade ?: p?.displayGrade
            PumpView(
                pump = n,
                state = p?.state?.name ?: PumpState.OFFLINE.name,
                nozzleUp = p?.nozzleUp,
                flowing = p?.flowing ?: false,
                grade = grade,
                gradeName = grade?.let { gradeNames[it]?.name ?: it },
                priceMills = p?.current?.priceMills ?: p?.displayPriceMills ?: 0,
                volumeMilli = p?.current?.volumeMilli ?: p?.displayVolumeMilli ?: 0,
                amountCents = p?.current?.amountCents ?: p?.displayAmountCents ?: 0,
                live = p?.current != null,
                mode = p?.authorisation?.mode?.name,
                limitCents = p?.authorisation?.maxAmountCents ?: p?.current?.maxAmountCents,
                limitReached = p?.current?.limitReached ?: false,
                payable = trx.filter { it.pump == n && it.mode == FuelMode.POSTPAY && it.volumeMilli > 0 }
                    .map { t ->
                        TrxView(
                            trxId = t.trxId, grade = t.grade, gradeName = gradeNames[t.grade]?.name ?: t.gradeName,
                            volumeMilli = t.volumeMilli, priceMills = t.priceMills, amountCents = t.amountCents,
                            state = t.state.name,
                            saleId = t.lockedBy?.removePrefix("sale-")?.toIntOrNull(),
                        )
                    },
                prepay = prepay?.let {
                    PrepayView(it[FuelSales.id].value, it[FuelSales.status], it[FuelSales.prepaidCents] ?: 0,
                        it[FuelSales.checkId], it[FuelSales.error])
                },
                change = change?.let {
                    ChangeView(it[FuelSales.id].value, it[FuelSales.refundCents] ?: 0, it[FuelSales.checkId],
                        it[FuelSales.amountCents] ?: 0, it[FuelSales.prepaidCents] ?: 0)
                },
                error = p?.error,
            )
        }
        return ForecourtView(
            online = online,
            message = if (online) null else (lastError ?: "not connected yet"),
            controller = adapter.description,
            updatedAt = lastOkAt?.let(VenueClock::iso),
            grades = grades.map { GradeView(it.code, it.name, it.nameEs, it.priceMills) },
            pumps = pumps,
        )
    }

    // ------------------------------------------------------------------ pump commands

    fun authorisePostpay(pump: Int) = command(pump) { adapter.authorise(pump, FuelMode.POSTPAY, null, "postpay") }
    fun stopPump(pump: Int) = command(pump) { adapter.stop(pump) }
    fun resumePump(pump: Int) = command(pump) { adapter.resume(pump) }
    fun resetPump(pump: Int) = command(pump) { adapter.reset(pump) }
    fun emergencyStop(pump: Int?) {
        if (pump != null) requirePump(pump)
        try { adapter.emergencyStop(pump) } catch (e: Exception) { throw translate(e) }
        poll()
    }

    private fun command(pump: Int, block: () -> Unit) {
        requirePump(pump)
        requireOnline()
        try { block() } catch (e: Exception) { throw translate(e) }
        poll()
    }

    private fun requirePump(pump: Int) {
        if (pump !in 1..maxOf(pumpCount, last?.pumps?.maxOfOrNull { it.pump } ?: 0))
            throw NotFoundException("no pump $pump", "no_such_pump")
    }

    private fun requireOnline() {
        if (!online) throw ConflictException("the forecourt controller is offline: ${lastError ?: "no answer"}", "forecourt_offline")
    }

    private fun translate(e: Exception): RuntimeException = when (e) {
        is ForecourtRefused -> ConflictException(e.message ?: e.code, "fdc_" + e.code.lowercase())
        is ForecourtUnavailable -> {
            online = false
            lastError = e.message
            ConflictException("the forecourt controller is offline: ${e.message}", "forecourt_offline")
        }
        is RuntimeException -> e
        else -> RuntimeException(e)
    }

    // ------------------------------------------------------------------ onto the sale

    /** A completed postpay fuelling → a fuel line on [checkId], locked at the controller. */
    fun addPostpayToSale(checkId: Int, trxId: String): CheckView = synchronized(lock) {
        requireOnline()
        requireOpen(checkId)
        val t = last?.transactions?.firstOrNull { it.trxId == trxId }
            ?: throw NotFoundException("no payable fuel sale $trxId", "fuel_trx_not_found")
        if (t.mode != FuelMode.POSTPAY) throw ConflictException("$trxId was prepaid", "fuel_trx_prepaid")
        if (t.volumeMilli <= 0) throw ConflictException("$trxId dispensed nothing", "fuel_trx_empty")
        val taken = transaction {
            FuelSales.selectAll().where {
                (FuelSales.fdcTrxId eq trxId) and (FuelSales.status inList listOf(FuelStatus.IN_BASKET, FuelStatus.SETTLED))
            }.firstOrNull()
        }
        if (taken != null) {
            if (taken[FuelSales.checkId] == checkId) return checks.getCheck(checkId)
            throw ConflictException("$trxId is already on sale ${taken[FuelSales.checkId]}", "fuel_trx_taken")
        }
        try { adapter.lock(trxId, "sale-$checkId") } catch (e: Exception) { throw translate(e) }
        try {
            transaction {
                val id = FuelSales.insertAndGetId {
                    it[pump] = t.pump
                    it[mode] = FuelMode.POSTPAY.name
                    it[status] = FuelStatus.IN_BASKET
                    it[FuelSales.checkId] = checkId
                    it[fdcTrxId] = t.trxId
                    it[nozzle] = t.nozzle
                    it[grade] = t.grade
                    it[gradeName] = gradeName(t.grade, t.gradeName)
                    it[volumeMilli] = t.volumeMilli
                    it[priceMills] = t.priceMills
                    it[amountCents] = t.amountCents
                    it[createdAt] = VenueClock.now()
                    it[completedAt] = VenueClock.now()
                }.value
                val item = FuelItems.gradeItemId(t.grade)
                checks.addFuelLine(checkId, item, FuelItems.gradeVariantId(t.grade), t.amountCents, id)
            }
        } catch (e: Exception) {
            runCatching { adapter.unlock(trxId) }
            throw e
        }
        poll()
        return checks.getCheck(checkId)
    }

    /** "$[amountCents] on pump [pump]": a prepay line on [checkId]; the pump starts once the sale is paid. */
    fun addPrepayToSale(checkId: Int, pump: Int, amountCents: Long): CheckView = synchronized(lock) {
        requirePump(pump)
        requireOnline()
        requireOpen(checkId)
        if (amountCents !in 1..maxPrepayCents)
            throw ConflictException("a prepay is between $0.01 and ${Money(maxPrepayCents).formatCents()}", "prepay_amount")
        val p = last?.pumps?.firstOrNull { it.pump == pump }
        if (p != null && p.state in setOf(PumpState.OFFLINE, PumpState.ERROR, PumpState.EMERGENCY_STOP))
            throw ConflictException("pump $pump is ${p.state.name.lowercase()}", "pump_unavailable")
        transaction {
            val active = FuelSales.selectAll().where {
                (FuelSales.pump eq pump) and (FuelSales.mode eq FuelMode.PREPAY.name) and
                    (FuelSales.status inList listOf(FuelStatus.IN_BASKET, FuelStatus.AUTHORISED, FuelStatus.AUTH_FAILED))
            }.any()
            if (active) throw ConflictException("pump $pump already has a prepay", "pump_has_prepay")
            val id = FuelSales.insertAndGetId {
                it[FuelSales.pump] = pump
                it[mode] = FuelMode.PREPAY.name
                it[status] = FuelStatus.IN_BASKET
                it[FuelSales.checkId] = checkId
                it[prepaidCents] = amountCents
                it[createdAt] = VenueClock.now()
            }.value
            checks.addFuelLine(checkId, FuelItems.PREPAY_ITEM, FuelItems.PREPAY_VARIANT, amountCents, id)
        }
        return checks.getCheck(checkId)
    }

    /** Take back a paid prepay before any fuel flows: the pump is freed and the whole amount refunded. */
    fun cancelPrepay(fuelSaleId: Int): ForecourtView = synchronized(lock) {
        val row = transaction { FuelSales.selectAll().where { FuelSales.id eq fuelSaleId }.firstOrNull() }
            ?: throw NotFoundException("no prepay $fuelSaleId")
        if (row[FuelSales.mode] != FuelMode.PREPAY.name || row[FuelSales.status] !in setOf(FuelStatus.AUTHORISED, FuelStatus.AUTH_FAILED))
            throw ConflictException("prepay $fuelSaleId is ${row[FuelSales.status]}", "prepay_not_cancellable")
        val pump = row[FuelSales.pump]
        if (row[FuelSales.status] == FuelStatus.AUTHORISED) {
            requireOnline()
            val p = last?.pumps?.firstOrNull { it.pump == pump }
            if (p?.current != null) throw ConflictException("pump $pump is already fuelling", "prepay_in_use")
            try { adapter.free(pump) } catch (e: Exception) { throw translate(e) }
        }
        val prepaid = row[FuelSales.prepaidCents] ?: 0
        transaction {
            val refundId = refund(row[FuelSales.checkId]!!, prepaid, "Prepay cancelled", fuelSaleId)
            FuelSales.update({ FuelSales.id eq fuelSaleId }) {
                it[status] = FuelStatus.CANCELLED
                it[amountCents] = 0
                it[volumeMilli] = 0
                it[refundCents] = prepaid
                it[FuelSales.refundId] = refundId
                it[settledAt] = VenueClock.now()
                it[fdcCleared] = true
            }
        }
        poll()
        view()
    }

    /** The cashier handed back a prepay's change: the tile stops asking. */
    fun changeGiven(fuelSaleId: Int): ForecourtView {
        val n = transaction { FuelSales.update({ FuelSales.id eq fuelSaleId }) { it[changeGiven] = true } }
        if (n == 0) throw NotFoundException("no fuel sale $fuelSaleId")
        return view()
    }

    private fun requireOpen(checkId: Int) {
        val status = transaction { Checks.selectAll().where { Checks.id eq checkId }.firstOrNull()?.get(Checks.status) }
            ?: throw NotFoundException("sale $checkId not found")
        if (status != "OPEN") throw ConflictException("sale $checkId is $status", "check_not_open")
    }

    // ------------------------------------------------------------------ keeping the books in step

    override fun checkChanged(checkId: Int) {
        try { reconcile(checkId) } catch (e: Exception) { log.warn("forecourt: sale $checkId: ${e.message}") }
    }

    /**
     * Bring the store's fuel rows and the controller into line. Idempotent;
     * safe to run on every poll. [onlyCheck] limits the store-side pass to one
     * sale (the hook after a sale changes).
     */
    fun reconcile(onlyCheck: Int? = null) = synchronized(lock) {
        val rows = transaction {
            FuelSales.selectAll().where {
                val open = (FuelSales.status inList listOf(FuelStatus.IN_BASKET, FuelStatus.AUTHORISED, FuelStatus.AUTH_FAILED)) or
                    ((FuelSales.status eq FuelStatus.SETTLED) and (FuelSales.fdcCleared eq false))
                if (onlyCheck != null) open and (FuelSales.checkId eq onlyCheck) else open
            }.orderBy(FuelSales.id).toList()
        }
        for (row in rows) {
            try { reconcileRow(row) } catch (e: Exception) { log.warn("forecourt: fuel sale ${row[FuelSales.id]}: ${e.message}") }
        }
        if (onlyCheck == null && online) sweepController()
    }

    private fun reconcileRow(row: ResultRow) {
        val id = row[FuelSales.id].value
        val status = row[FuelSales.status]
        val prepay = row[FuelSales.mode] == FuelMode.PREPAY.name
        val checkId = row[FuelSales.checkId]
        val (checkStatus, lineActive) = transaction {
            val cs = checkId?.let { c -> Checks.selectAll().where { Checks.id eq c }.firstOrNull()?.get(Checks.status) }
            val la = row[FuelSales.lineId]?.let { l ->
                CheckLines.selectAll().where { (CheckLines.id eq l) and (CheckLines.status eq "ACTIVE") }.any()
            } ?: false
            cs to la
        }
        when (status) {
            FuelStatus.IN_BASKET -> when {
                checkStatus == "CLOSED" -> if (prepay) authorisePrepay(row) else settlePostpay(row)
                checkStatus in setOf("VOID", "CANCELLED", "MERGED") || checkStatus == null || !lineActive -> release(row)
                else -> {} // still being rung up / paid
            }
            FuelStatus.AUTH_FAILED -> authorisePrepay(row)
            FuelStatus.AUTHORISED -> {
                val t = last?.transactions?.firstOrNull { it.posRef == "prepay-$id" }
                if (online && t != null) settlePrepay(row, t)
            }
            FuelStatus.SETTLED -> clearAtController(id, row[FuelSales.fdcTrxId])
        }
    }

    private fun release(row: ResultRow) {
        val id = row[FuelSales.id].value
        transaction { FuelSales.update({ FuelSales.id eq id }) { it[status] = FuelStatus.RELEASED } }
        row[FuelSales.fdcTrxId]?.let { trx -> runCatching { adapter.unlock(trx) } }
    }

    private fun settlePostpay(row: ResultRow) {
        val id = row[FuelSales.id].value
        transaction {
            FuelSales.update({ FuelSales.id eq id }) {
                it[status] = FuelStatus.SETTLED
                it[settledAt] = VenueClock.now()
            }
            emitFuelSale(id)
        }
        clearAtController(id, row[FuelSales.fdcTrxId])
    }

    private fun authorisePrepay(row: ResultRow) {
        val id = row[FuelSales.id].value
        val pump = row[FuelSales.pump]
        val posRef = "prepay-$id"
        // a previous attempt may have reached the controller after all
        last?.pumps?.firstOrNull { it.pump == pump }?.authorisation?.takeIf { it.posRef == posRef }?.let { auth ->
            markAuthorised(id, auth.authId); return
        }
        if (last?.transactions?.any { it.posRef == posRef } == true) { markAuthorised(id, null); return }
        val result = try {
            if (!online) throw ForecourtUnavailable(lastError ?: "offline")
            Result.success(adapter.authorise(pump, FuelMode.PREPAY, row[FuelSales.prepaidCents], posRef))
        } catch (e: Exception) {
            Result.failure(e)
        }
        result.onSuccess { markAuthorised(id, it) }.onFailure { e ->
            val why = when (e) {
                is ForecourtRefused -> e.code
                else -> "OFFLINE"
            }
            if (row[FuelSales.status] != FuelStatus.AUTH_FAILED || row[FuelSales.error] != why)
                log.info("forecourt: prepay $id on pump $pump waits: $why")
            transaction {
                FuelSales.update({ FuelSales.id eq id }) {
                    it[status] = FuelStatus.AUTH_FAILED
                    it[error] = why
                }
            }
        }
    }

    private fun markAuthorised(id: Int, authId: String?) = transaction {
        FuelSales.update({ FuelSales.id eq id }) {
            it[status] = FuelStatus.AUTHORISED
            if (authId != null) it[fdcAuthId] = authId
            it[error] = null
        }
    }

    /**
     * The pump finished a prepay: record what it dispensed, refund the rest
     * on the sale that paid for it (the same tender, cash to the nickel), and
     * send the fuel sale up. Then clear it at the controller.
     */
    private fun settlePrepay(row: ResultRow, t: FuelTrx) {
        val id = row[FuelSales.id].value
        val prepaid = row[FuelSales.prepaidCents] ?: 0
        val dispensed = t.amountCents.coerceIn(0, prepaid)
        val change = prepaid - dispensed
        transaction {
            val refundId = if (change > 0) refund(row[FuelSales.checkId]!!, change, "Prepay change", id) else null
            FuelSales.update({ FuelSales.id eq id }) {
                it[status] = FuelStatus.SETTLED
                it[fdcTrxId] = t.trxId
                it[nozzle] = t.nozzle
                it[grade] = t.grade
                it[gradeName] = gradeName(t.grade, t.gradeName)
                it[volumeMilli] = t.volumeMilli
                it[priceMills] = t.priceMills
                it[amountCents] = dispensed
                it[refundCents] = change
                it[FuelSales.refundId] = refundId
                it[completedAt] = VenueClock.now()
                it[settledAt] = VenueClock.now()
            }
            emitFuelSale(id)
        }
        clearAtController(id, t.trxId)
    }

    /**
     * Money back on [checkId] for fuel not taken: an ordinary refund, on the
     * tender the sale was paid with (cash first), cash rounded to the nickel.
     * No sales tax to reverse: fuel carries none. Returns the refund id.
     */
    private fun refund(checkId: Int, cents: Long, reason: String, fuelSaleId: Int): Int {
        val check = Checks.selectAll().where { Checks.id eq checkId }.first()
        val types = Tenders.selectAll().where { Tenders.transactionId eq checkId }.map { it[Tenders.type] }
        val type = when {
            "CASH" in types -> TenderType.CASH
            types.isNotEmpty() -> runCatching { TenderType.valueOf(types.first()) }.getOrDefault(TenderType.CASH)
            else -> TenderType.CASH
        }
        val rounding = if (type == TenderType.CASH) config.roundingPolicy.cashAdjustment(Money(cents)).cents else 0L
        val plan = CheckService.RefundPlan(
            checkId = checkId, gross = cents, net = cents, tax = 0, tenderType = type, reason = reason,
            managerId = check[Checks.openedBy], linesJson = null, taxLines = emptyList(), rounding = rounding,
        )
        return checks.recordRefund(plan, fuelSaleId = fuelSaleId).refund.id
    }

    private fun clearAtController(id: Int, trxId: String?) {
        if (trxId == null) {
            transaction { FuelSales.update({ FuelSales.id eq id }) { it[fdcCleared] = true } }
            return
        }
        val ok = try {
            adapter.clear(trxId)
            true
        } catch (e: ForecourtRefused) {
            e.code == "NOT_FOUND" // already gone from the controller's buffer
        } catch (e: Exception) {
            false // unreachable: the next reconcile tries again
        }
        if (ok) transaction { FuelSales.update({ FuelSales.id eq id }) { it[fdcCleared] = true } }
    }

    /** Controller-side leftovers: empty postpay sales, locks for sales that dropped the fuel, settled prepays. */
    private fun sweepController() {
        val snap = last ?: return
        val known = transaction {
            FuelSales.selectAll().where { FuelSales.fdcTrxId.isNotNull() }
                .associate { it[FuelSales.fdcTrxId]!! to it[FuelSales.status] }
        }
        for (t in snap.transactions) {
            try {
                val prepayId = t.posRef?.removePrefix("prepay-")?.takeIf { t.posRef.startsWith("prepay-") }?.toIntOrNull()
                when {
                    // a prepay the store no longer waits for (already settled, or cancelled)
                    prepayId != null -> {
                        val st = transaction { FuelSales.selectAll().where { FuelSales.id eq prepayId }.firstOrNull()?.get(FuelSales.status) }
                        if (st == null || st == FuelStatus.SETTLED || st == FuelStatus.CANCELLED) adapter.clear(t.trxId)
                    }
                    // nothing dispensed: nothing to pay for
                    t.mode == FuelMode.POSTPAY && t.volumeMilli == 0L && t.state == TrxState.PAYABLE -> adapter.clear(t.trxId)
                    // locked for a sale that no longer carries it
                    t.state == TrxState.LOCKED && t.lockedBy?.startsWith("sale-") == true &&
                        known[t.trxId] !in setOf(FuelStatus.IN_BASKET, FuelStatus.SETTLED) -> adapter.unlock(t.trxId)
                    known[t.trxId] == FuelStatus.SETTLED -> adapter.clear(t.trxId)
                }
            } catch (e: Exception) {
                log.debug("forecourt sweep ${t.trxId}: ${e.message}")
            }
        }
    }

    private fun gradeName(code: String?, fallback: String?): String =
        grades.firstOrNull { it.code == code }?.name ?: fallback ?: code ?: "?"

    /** The `fuel.sale` sync event (CONTRACT §2, Fuel): one per settled fuelling. Inside a transaction. */
    private fun emitFuelSale(id: Int) {
        val r = FuelSales.selectAll().where { FuelSales.id eq id }.first()
        Outbox.write("fuel.sale", "fuel_sale", id.toString(), buildJsonObject {
            putMoneyContext(config.profile)
            put("fuelSaleId", id)
            r[FuelSales.checkId]?.let { put("checkId", it) }
            put("pump", r[FuelSales.pump])
            r[FuelSales.nozzle]?.let { put("nozzle", it) }
            put("grade", r[FuelSales.grade])
            put("gradeName", r[FuelSales.gradeName])
            put("volumeMilli", r[FuelSales.volumeMilli] ?: 0)
            put("priceMills", r[FuelSales.priceMills] ?: 0)
            put("amountCents", r[FuelSales.amountCents] ?: 0)
            put("mode", r[FuelSales.mode])
            if (r[FuelSales.mode] == FuelMode.PREPAY.name) {
                put("prepaidCents", r[FuelSales.prepaidCents] ?: 0)
                put("refundCents", r[FuelSales.refundCents] ?: 0)
                r[FuelSales.refundId]?.let { put("refundId", it) }
            }
            put("fdcTransactionId", r[FuelSales.fdcTrxId])
            put("completedAt", VenueClock.iso(r[FuelSales.completedAt] ?: VenueClock.now()))
        })
    }
}

/** The catalog items fuel is rung up as: one per grade, and the prepay. Sold at the pump, never from the shelf. */
object FuelItems {
    const val CATEGORY = "fuel"
    const val PREPAY_ITEM = "fuel-prepay"
    const val PREPAY_VARIANT = "fuel-prepay:each"
    fun gradeItemId(grade: String) = "fuel-" + grade.lowercase()
    fun gradeVariantId(grade: String) = gradeItemId(grade) + ":gal"
}

// ---- wire views ----

@Serializable
data class ForecourtView(
    val online: Boolean,
    /** Why it's offline (for the tile tooltip / logs); null when online. */
    val message: String? = null,
    val controller: String = "",
    val updatedAt: String? = null,
    val grades: List<GradeView>,
    val pumps: List<PumpView>,
)

@Serializable
data class GradeView(val code: String, val name: String, val nameEs: String, val priceMills: Long)

@Serializable
data class PumpView(
    val pump: Int,
    /** IDLE | CALLING | AUTHORISED | FUELLING | SUSPENDED | EMERGENCY_STOP | ERROR | OFFLINE */
    val state: String,
    val nozzleUp: Int? = null,
    val flowing: Boolean = false,
    val grade: String? = null,
    val gradeName: String? = null,
    val priceMills: Long = 0,
    val volumeMilli: Long = 0,
    val amountCents: Long = 0,
    /** A sale is being pumped right now (the figures are ticking). */
    val live: Boolean = false,
    /** PREPAY | POSTPAY while authorised. */
    val mode: String? = null,
    val limitCents: Long? = null,
    val limitReached: Boolean = false,
    /** Completed postpay fuellings waiting to be paid (saleId = on that sale already). */
    val payable: List<TrxView> = emptyList(),
    /** A prepay for this pump, not finished yet. */
    val prepay: PrepayView? = null,
    /** A finished prepay's change still to hand back. */
    val change: ChangeView? = null,
    val error: String? = null,
)

@Serializable
data class TrxView(
    val trxId: String,
    val grade: String,
    val gradeName: String,
    val volumeMilli: Long,
    val priceMills: Long,
    val amountCents: Long,
    val state: String,
    val saleId: Int? = null,
)

@Serializable
data class PrepayView(val fuelSaleId: Int, val status: String, val prepaidCents: Long, val saleId: Int?, val error: String? = null)

@Serializable
data class ChangeView(val fuelSaleId: Int, val refundCents: Long, val saleId: Int?, val dispensedCents: Long, val prepaidCents: Long)

@Serializable
data class PrepayRequest(val pump: Int, val amountCents: Long)

@Serializable
data class FuelTrxRequest(val trxId: String)
