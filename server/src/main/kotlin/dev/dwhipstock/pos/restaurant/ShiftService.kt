package dev.dwhipstock.pos.restaurant

import dev.dwhipstock.pos.sdk.VenueClock

import dev.dwhipstock.pos.base.GrantsRepo
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.Permissions
import dev.dwhipstock.pos.base.Tenders
import dev.dwhipstock.pos.base.Users
import dev.dwhipstock.pos.sdk.Align
import dev.dwhipstock.pos.sdk.CustomerConfig
import dev.dwhipstock.pos.sdk.Fee
import dev.dwhipstock.pos.sdk.FeeContext
import dev.dwhipstock.pos.sdk.Money
import dev.dwhipstock.pos.sdk.MoneyLimits
import dev.dwhipstock.pos.sdk.sumExact
import dev.dwhipstock.pos.sdk.sumOfExact
import dev.dwhipstock.pos.sdk.Outbox
import dev.dwhipstock.pos.sdk.PrintLine
import dev.dwhipstock.pos.sdk.PrinterAdapter
import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import dev.dwhipstock.pos.sdk.i18n.MessageKey
import dev.dwhipstock.pos.sdk.i18n.MessageKey.CASH_IN
import dev.dwhipstock.pos.sdk.i18n.MessageKey.CASH_IN_HEADER
import dev.dwhipstock.pos.sdk.i18n.MessageKey.CASH_OUT
import dev.dwhipstock.pos.sdk.i18n.MessageKey.CASH_OUT_HEADER
import dev.dwhipstock.pos.sdk.i18n.MessageKey.SLIP_REASON
import dev.dwhipstock.pos.sdk.i18n.MessageKey.SLIP_TIME
import dev.dwhipstock.pos.sdk.i18n.Messages
import dev.dwhipstock.pos.sdk.ReceiptPolicy
import dev.dwhipstock.pos.sdk.putMoneyContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.LongColumnType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.castTo
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.min
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** The check/void services stamp closing checks with this. Call inside a transaction. */
fun currentOpenShiftId(): Int? =
    Shifts.selectAll().where { Shifts.status eq "OPEN" }.firstOrNull()?.get(Shifts.id)?.value

/**
 * Business day = a shift; single-open per terminal (CopperLantern is single-terminal).
 * Float and closing count are bookkeeping only — no drawer hardware.
 */
class ShiftService(private val config: CustomerConfig) {

    fun currentShift(): ShiftView? = transaction {
        Shifts.selectAll().where { Shifts.status eq "OPEN" }.firstOrNull()?.let { toView(it) }
    }

    fun openShift(userId: String, openingFloatCents: Long): ShiftView = transaction {
        require(openingFloatCents >= 0) { "float must be >= 0" }
        if (openingFloatCents > MoneyLimits.MAX_DRAWER_AMOUNT_CENTS)
            throw BadRequestException("float is more than ${MoneyLimits.MAX_DRAWER_AMOUNT_CENTS} cents", "cash_amount_too_high")
        if (currentOpenShiftId() != null) throw ConflictException("a shift is already open", "shift_already_open")
        val now = VenueClock.now()
        val id = Shifts.insertAndGetId {
            it[status] = "OPEN"
            it[openedAt] = now
            it[openedBy] = userId
            it[Shifts.openingFloatCents] = openingFloatCents
        }.value
        Outbox.write("shift.opened", "shift", id.toString(), buildJsonObject {
            put("shiftId", id)
            put("openedBy", userId)
            put("openingFloatCents", openingFloatCents)
            put("openedAt", VenueClock.iso(now))
        })
        toView(Shifts.selectAll().where { Shifts.id eq id }.first())
    }

    /**
     * Record a non-sale cash movement against the open shift. IN = float top-up /
     * change added; OUT = petty cash / supplier paid in cash / owner draw.
     * Manager-gated (the route verifies the PIN; we re-check the role), reason
     * required. Folded into the shift's expected-cash math at X/Z close.
     */
    fun recordCashMovement(direction: String, amountCents: Long, reason: String, managerId: String): CashMovementResult = transaction {
        require(reason.isNotBlank()) { "cash movement reason is required" }
        val dir = direction.uppercase()
        if (dir != "IN" && dir != "OUT")
            throw BadRequestException("direction must be IN or OUT", "cash_bad_direction")
        if (amountCents <= 0)
            throw BadRequestException("amount must be positive", "cash_non_positive")
        if (amountCents > MoneyLimits.MAX_DRAWER_AMOUNT_CENTS)
            throw BadRequestException("amount is more than ${MoneyLimits.MAX_DRAWER_AMOUNT_CENTS} cents", "cash_amount_too_high")
        // defense-in-depth: the route already authorized this; re-check the grant on the authorizer
        if (!GrantsRepo.has(managerId, Permissions.CASH_MOVEMENT))
            throw ConflictException("cash movement requires the cash_movement grant or a manager's approval", "manager_approval_required")
        val shift = currentOpenShiftId()
            ?: throw ConflictException("no open shift to post a cash movement to", "no_open_shift")

        val now = VenueClock.now()
        val id = CashMovements.insertAndGetId {
            it[shiftId] = shift
            it[CashMovements.direction] = dir
            it[CashMovements.amountCents] = amountCents
            it[CashMovements.reason] = reason
            it[createdBy] = managerId
            it[createdAt] = now
        }.value
        Outbox.write("cash.movement", "cash_movement", id.toString(), buildJsonObject {
            put("movementId", id)
            put("shiftId", shift)
            put("direction", dir)
            put("amountCents", amountCents)
            put("reason", reason)
            put("user", managerId)
            put("createdAt", VenueClock.iso(now))
            putMoneyContext(config.profile)
        })
        CashMovementResult(
            movement = CashMovementView(id, shift, dir, amountCents, reason, managerId, VenueClock.iso(now)),
            slipText = renderCashSlip(dir, amountCents, reason, managerId, now),
        )
    }

    /** Cash movements on the current open shift (the till log). Empty if no shift. */
    fun currentShiftCashMovements(): List<CashMovementView> = transaction {
        val shift = currentOpenShiftId() ?: return@transaction emptyList()
        CashMovements.selectAll().where { CashMovements.shiftId eq shift }
            .orderBy(CashMovements.id to SortOrder.DESC)
            .map {
                CashMovementView(
                    it[CashMovements.id].value, it[CashMovements.shiftId], it[CashMovements.direction],
                    it[CashMovements.amountCents], it[CashMovements.reason],
                    it[CashMovements.createdBy], VenueClock.iso(it[CashMovements.createdAt]),
                )
            }
    }

    /** Same owner-preference rule as receipts: honored when a catalog for it is loaded. */
    private fun policyFor(userId: String): ReceiptPolicy {
        val locale = Users.selectAll().where { Users.id eq userId }.firstOrNull()
            ?.get(Users.languageCode)?.let { LocaleCode.of(it) }
        return if (locale != null && Messages.supports(locale)) config.receiptPolicy.withLocale(locale)
        else config.receiptPolicy
    }

    /** 42-col till slip for a cash-in/out, same virtual printer as receipts. */
    private fun renderCashSlip(dir: String, amount: Long, reason: String, userId: String, now: java.time.Instant): String {
        val policy = policyFor(userId)
        fun msg(key: MessageKey) = Messages.get(key, policy.locale)
        val title = if (dir == "IN") msg(CASH_IN_HEADER) else msg(CASH_OUT_HEADER)
        val lines = buildList {
            add(PrintLine.LogoPlaceholder(policy.logoFallbackText))
            policy.header().forEach { add(PrintLine.Text(it, Align.CENTER)) }
            add(PrintLine.Blank)
            add(PrintLine.Header(title))
            add(PrintLine.Blank)
            add(PrintLine.KeyValue(msg(SLIP_TIME), policy.formatDate(VenueClock.local(now))))
            add(PrintLine.Divider)
            add(PrintLine.KeyValue(
                if (dir == "IN") msg(CASH_IN) else msg(CASH_OUT),
                policy.money(Money(amount)), emphasized = true,
            ))
            add(PrintLine.Blank)
            add(PrintLine.Text(msg(SLIP_REASON) + " " + reason))
            add(PrintLine.Blank)
            add(PrintLine.Text(policy.footerText, Align.CENTER))
        }
        return PrinterAdapter.renderText(lines)
    }

    /** X-report: snapshot of the open shift, no reset. */
    fun xReport(): ShiftReport = transaction {
        val shift = Shifts.selectAll().where { Shifts.status eq "OPEN" }.firstOrNull()
            ?: throw ConflictException("no open shift", "no_open_shift")
        buildReport(shift, closingCountCents = null)
    }

    /**
     * Z-report: computes the X content + cash reconciliation, then closes the shift.
     *
     * Refused (409 shift_has_paid_open_bills, with `bills`: id + table label +
     * what is paid) while any bill that is still open has money on it: the Z
     * counts a bill's cash when the bill closes, so cash taken on a bill that
     * stays open would be in the drawer but in no Z report (red team
     * 2026-10-01). The simple safe rule, the owner's choice: finish those bills
     * (or cancel them, handing the money back) before closing the day.
     */
    fun closeShift(userId: String, closingCountCents: Long): ShiftReport = transaction {
        if (closingCountCents !in 0..MoneyLimits.MAX_DRAWER_AMOUNT_CENTS)
            throw BadRequestException("closing count must be 0-${MoneyLimits.MAX_DRAWER_AMOUNT_CENTS} cents", "cash_amount_too_high")
        val shift = Shifts.selectAll().where { Shifts.status eq "OPEN" }.firstOrNull()
            ?: throw ConflictException("no open shift", "no_open_shift")
        val paidOpen = paidOpenBills()
        if (paidOpen.isNotEmpty())
            throw ConflictException(
                "bills still open with money on them: ${paidOpen.joinToString { "#${it.checkId} (${it.tableLabel})" }}",
                "shift_has_paid_open_bills",
                buildJsonObject {
                    putJsonArray("bills") {
                        paidOpen.forEach { b ->
                            addJsonObject {
                                put("checkId", b.checkId)
                                put("tableLabel", b.tableLabel)
                                put("paidCents", b.paidCents)
                            }
                        }
                    }
                },
            )
        val report = buildReport(shift, closingCountCents)
        val now = VenueClock.now()
        Shifts.update({ Shifts.id eq shift[Shifts.id].value }) {
            it[status] = "CLOSED"
            it[closedAt] = now
            it[closedBy] = userId
            it[Shifts.closingCountCents] = closingCountCents
            it[expectedCashCents] = report.expectedCashCents!!
            it[overShortCents] = report.overShortCents!!
        }
        // Z-report echo (CONTRACT.md §2): the cloud renders these figures, never recomputes
        Outbox.write("shift.closed", "shift", shift[Shifts.id].value.toString(), buildJsonObject {
            put("shiftId", shift[Shifts.id].value)
            put("closedBy", userId)
            putMoneyContext(config.profile)
            put("revenueCents", report.revenueCents)
            put("expectedCashCents", report.expectedCashCents)
            put("closingCountCents", closingCountCents)
            put("overShortCents", report.overShortCents)
            put("openedAt", report.openedAt)
            put("closedAt", VenueClock.iso(now))
            put("openedBy", report.openedBy)
            put("openingFloatCents", report.openingFloatCents)
            put("transactionCount", report.transactionCount)
            put("avgCheckCents", report.avgCheckCents)
            put("corkageCents", report.corkageCents)
            put("cashPaidInCents", report.cashPaidInCents)
            put("cashPaidOutCents", report.cashPaidOutCents)
            put("cashRefundCents", report.cashRefundCents)
            put("refundTotalCents", report.refundTotalCents)
            put("cashRoundingCents", report.cashRoundingCents)
            put("tipsCents", report.tipsCents)
            putJsonArray("tipsByServer") {
                report.tipsByServer.forEach { s ->
                    addJsonObject {
                        put("userId", s.userId)
                        put("name", s.name)
                        put("tipCents", s.tipCents)
                        put("count", s.count)
                    }
                }
            }
            putJsonArray("tenderBreakdown") {
                report.tenderBreakdown.forEach { t ->
                    addJsonObject {
                        put("type", t.type)
                        put("amountCents", t.amountCents)
                        put("count", t.count)
                        put("tipCents", t.tipCents)
                    }
                }
            }
        })
        report.copy(shiftStatus = "CLOSED")
    }

    private data class PaidOpenBill(val checkId: Int, val tableLabel: String, val paidCents: Long)

    /** Live (OPEN / TOTAL_LOCKED) bills with money applied, oldest first. Inside a transaction. */
    private fun paidOpenBills(): List<PaidOpenBill> {
        val live = Checks.selectAll().where { Checks.status inList listOf("OPEN", "TOTAL_LOCKED") }
            .orderBy(Checks.id).toList()
        if (live.isEmpty()) return emptyList()
        val paid = Tenders.selectAll()
            .where { (Tenders.transactionId inList live.map { it[Checks.id].value }) and Tenders.reversedAt.isNull() }
            .groupBy({ it[Tenders.transactionId] }, { it[Tenders.amountAppliedCents] })
            .mapValues { it.value.sumExact() }
        return live.mapNotNull { c ->
            val id = c[Checks.id].value
            val cents = paid[id] ?: 0L
            if (cents == 0L) return@mapNotNull null
            val table = DiningTables.selectAll().where { DiningTables.id eq c[Checks.tableId] }.firstOrNull()
            PaidOpenBill(id, table?.let { it[DiningTables.nameOverride] ?: it[DiningTables.label] } ?: c[Checks.tableId], cents)
        }
    }

    /**
     * X-report layout over an arbitrary closed-at date range (inclusive).
     * Same aggregates as a shift report; no cash reconciliation — the drawer
     * count is a shift concept, Z-close stays shift-only on purpose.
     */
    fun rangeReport(from: java.time.LocalDate, to: java.time.LocalDate): ShiftReport = transaction {
        require(!to.isBefore(from)) { "to must not be before from" }
        // venue business days (DST-aware midnights) as UTC instants
        val start = VenueClock.startOfDay(from)
        val end = VenueClock.startOfDay(to.plusDays(1))
        val voids = Checks.selectAll().where {
            (Checks.status eq "VOID") and
                (Checks.closedAt greaterEq start) and (Checks.closedAt less end)
        }.map { voidEntry(it) }
        val agg = aggregate { (Checks.closedAt greaterEq start) and (Checks.closedAt less end) }
        val rangeRefunds = Refunds.select(Refunds.taxesJson)
            .where { (Refunds.createdAt greaterEq start) and (Refunds.createdAt less end) and Refunds.taxesJson.isNotNull() }
        val taxes = taxBreakdown({ (Checks.closedAt greaterEq start) and (Checks.closedAt less end) }, rangeRefunds)
        ShiftReport(
            shiftId = 0,
            shiftStatus = "RANGE",
            openedAt = from.toString(),
            openedBy = to.toString(), // range end travels here; client renders dates, not a user
            openingFloatCents = 0,
            revenueCents = agg.revenue,
            transactionCount = agg.count,
            avgCheckCents = if (agg.count > 0) agg.revenue / agg.count else 0,
            tenderBreakdown = agg.tenderBreakdown,
            itemMix = agg.itemMix,
            voids = voids,
            corkageCents = agg.corkage,
            cashRoundingCents = agg.cashRounding,
            dineInCount = agg.modes["DINE_IN"] ?: 0,
            takeOutCount = agg.modes["TAKE_OUT"] ?: 0,
            tipsCents = agg.tips,
            tipsByServer = agg.tipsByServer,
            taxes = taxes,
        )
    }

    private class Aggregates(
        val revenue: Long, val count: Int, val tenderBreakdown: List<TenderSummary>,
        val itemMix: List<ItemMixEntry>, val corkage: Long,
        val cashIn: Long, val changeOut: Long,
        /** Nickel rounding on the cash tenders (signed). */
        val cashRounding: Long,
        /** Quick-serve orders by service mode (DINE_IN / TAKE_OUT). */
        val modes: Map<String, Int> = emptyMap(),
        /** Card tips on top of the bills (reader / Stripe), all tenders. */
        val tips: Long = 0,
        val tipsByServer: List<ServerTips> = emptyList(),
    )

    /**
     * Shared X/Z/range math over the CLOSED checks matching [where].
     *
     * Summed by SQLite, over a subquery: this used to load every check, tender
     * and line of the range and pass the check ids as a list, which ran the
     * store out of memory on a year's range report (load test, 100,000 sales:
     * docs/load-test-report.md). Only the checks that charged corkage are read
     * one by one.
     */
    private fun aggregate(where: SqlExpressionBuilder.() -> Op<Boolean>): Aggregates {
        val closedOnly: SqlExpressionBuilder.() -> Op<Boolean> = { where() and (Checks.status eq "CLOSED") }
        val ids = Checks.select(Checks.id).where(closedOnly)
        val revenueSum = Checks.lockedGrandTotalCents.sum()
        val checkCount = Checks.id.count()
        val head = Checks.select(revenueSum, checkCount).where(closedOnly).first()
        val revenue = head[revenueSum] ?: 0L

        val applied = Tenders.amountAppliedCents.sum()
        val tendered = Tenders.amountTenderedCents.sum()
        val change = Tenders.changeCents.sum()
        val rounding = Tenders.roundingAdjustmentCents.sum()
        val tips = Tenders.tipCents.sum()
        val tenderCount = Tenders.id.count()
        val firstTender = Tenders.id.min()
        val tenderRows = Tenders.select(Tenders.type, applied, tendered, change, rounding, tips, tenderCount, firstTender)
            .where { Tenders.transactionId inSubQuery ids }
            .groupBy(Tenders.type)
            .toList()
            .sortedBy { it[firstTender]?.value ?: 0 } // first seen first, as the tenders were listed
        val tenderBreakdown = tenderRows.map {
            TenderSummary(it[Tenders.type], it[applied] ?: 0L, it[tenderCount].toInt(), tipCents = it[tips] ?: 0L)
        }.sortedByDescending { it.amountCents }

        // card tips (reader / Stripe) per server: whoever opened the bill
        val serverTips = Tenders.tipCents.sum()
        val tippedCount = Tenders.id.count()
        val tipsByServer = Tenders.join(Checks, org.jetbrains.exposed.sql.JoinType.INNER, Tenders.transactionId, Checks.id)
            .select(Checks.openedBy, serverTips, tippedCount)
            .where { (Tenders.transactionId inSubQuery ids) and (Tenders.tipCents greater 0L) }
            .groupBy(Checks.openedBy)
            .map { Triple(it[Checks.openedBy], it[serverTips] ?: 0L, it[tippedCount].toInt()) }
        val names = if (tipsByServer.isEmpty()) emptyMap() else
            Users.selectAll().where { Users.id inList tipsByServer.map { it.first } }.associate { it[Users.id] to it[Users.name] }
        val serverTipList = tipsByServer.map { (user, cents, n) -> ServerTips(user, names[user] ?: user, cents, n) }
            .sortedByDescending { it.tipCents }

        // item mix over ACTIVE lines of closed checks. TODO: separate top-by-qty view
        // inner join to Items: open lines (null item_id) drop out of the mix on
        // purpose (revenue still counts them via the locked grand total).
        // Named as rung (LineSnapshot, 059): a renamed or deleted item reports
        // under the name it was sold as.
        val qty = CheckLines.qty.sum()
        val lineRevenue = with(SqlExpressionBuilder) {
            CheckLines.unitPriceCents.times(CheckLines.qty.castTo<Long>(LongColumnType()))
        }.sum()
        val firstLine = CheckLines.id.min()
        val soldFr = org.jetbrains.exposed.sql.Coalesce(CheckLines.nameFr, Items.nameFr)
        val soldEn = org.jetbrains.exposed.sql.Coalesce(CheckLines.nameEn, Items.nameEn)
        val itemMix = (CheckLines innerJoin Items)
            .select(CheckLines.itemId, soldFr, soldEn, qty, lineRevenue, firstLine)
            .where { (CheckLines.checkId inSubQuery ids) and (CheckLines.status eq "ACTIVE") }
            .groupBy(CheckLines.itemId, soldFr, soldEn)
            .toList()
            .sortedBy { it[firstLine]?.value ?: 0 }
            .map {
                ItemMixEntry(
                    itemId = it[CheckLines.itemId]!!,
                    nameFr = it[soldFr] ?: "?",
                    nameEn = it[soldEn] ?: "?",
                    qty = it[qty] ?: 0,
                    revenueCents = it[lineRevenue] ?: 0L,
                )
            }.sortedByDescending { it.revenueCents }.take(10)

        // corkage collected: the lock-time fee lines (021) are what the locked
        // grand totals actually charged — a per-bottle price change mid-shift
        // must not re-price earlier checks. Pre-021 rows fall back to re-assessment.
        val corkageRows = Checks.select(Checks.lockedFeesJson, Checks.corkageBottles).where {
            closedOnly() and ((Checks.lockedFeesJson like "%corkage%") or
                (Checks.lockedFeesJson.isNull() and (Checks.corkageBottles greater 0)))
        }.toList()
        val corkage = corkageRows.sumOf { row ->
            row[Checks.lockedFeesJson]?.let { json ->
                Json.parseToJsonElement(json).jsonArray
                    .filter { it.jsonObject["code"]?.jsonPrimitive?.content == "corkage" }
                    .sumOf { it.jsonObject["amountCents"]?.jsonPrimitive?.long ?: 0L }
            } ?: run {
                val ctx = FeeContext(itemsSubtotal = Money.ZERO, corkageBottles = row[Checks.corkageBottles])
                config.fees.filterIsInstance<Fee.Corkage>().sumOf { it.assess(ctx)?.amount?.cents ?: 0 }
            }
        }

        // quick-serve: how many were eaten in and taken out (no counter orders elsewhere)
        val modeCount = CounterOrders.checkId.count()
        val modes = CounterOrders.select(CounterOrders.serviceMode, modeCount)
            .where { CounterOrders.checkId inSubQuery ids }
            .groupBy(CounterOrders.serviceMode)
            .associate { it[CounterOrders.serviceMode] to it[modeCount].toInt() }

        val cashRows = tenderRows.filter { it[Tenders.type] == "CASH" }
        return Aggregates(
            modes = modes,
            revenue = revenue,
            count = head[checkCount].toInt(),
            tenderBreakdown = tenderBreakdown,
            itemMix = itemMix,
            corkage = corkage,
            // tendered − change = the ROUNDED cash each settling payment took
            cashIn = cashRows.sumOfExact { it[tendered] ?: 0L },
            changeOut = cashRows.sumOfExact { it[change] ?: 0L },
            cashRounding = cashRows.sumOfExact { it[rounding] ?: 0L },
            tips = tenderBreakdown.sumOfExact { it.tipCents },
            tipsByServer = serverTipList,
        )
    }

    private fun buildReport(shift: ResultRow, closingCountCents: Long?): ShiftReport {
        val shiftId = shift[Shifts.id].value
        val agg = aggregate { Checks.shiftId eq shiftId }

        val voids = Checks.selectAll()
            .where { (Checks.shiftId eq shiftId) and (Checks.status eq "VOID") }
            .map { voidEntry(it) }

        // non-sale cash movements and refunds posted to this shift
        val movements = CashMovements.selectAll().where { CashMovements.shiftId eq shiftId }.toList()
        val cashPaidIn = movements.filter { it[CashMovements.direction] == "IN" }.sumOfExact { it[CashMovements.amountCents] }
        val cashPaidOut = movements.filter { it[CashMovements.direction] == "OUT" }.sumOfExact { it[CashMovements.amountCents] }
        val refunds = Refunds.selectAll().where { Refunds.shiftId eq shiftId }.toList()
        val refundTotal = refunds.sumOfExact { it[Refunds.grossCents] }
        // only CASH refunds leave the drawer; Card/transfer refunds don't. The
        // cash that left is the rounded amount: gross + its nickel rounding.
        val cashRefundRows = refunds.filter { it[Refunds.tenderType] == "CASH" }
        val cashRefund = cashRefundRows.sumOfExact { it[Refunds.grossCents] + it[Refunds.roundingAdjustmentCents] }
        val refundRounding = cashRefundRows.sumOfExact { it[Refunds.roundingAdjustmentCents] }

        // cash drawer math (all of it the rounded cash that changed hands):
        //   opening float + cash taken - change given
        //   + non-sale cash in - non-sale cash out - cash refunds paid out
        // The X-report shows the drawer so far; the Z adds the count.
        val expected = shift[Shifts.openingFloatCents] + agg.cashIn - agg.changeOut + cashPaidIn - cashPaidOut - cashRefund

        return ShiftReport(
            shiftId = shiftId,
            shiftStatus = shift[Shifts.status],
            openedAt = VenueClock.iso(shift[Shifts.openedAt]),
            openedBy = shift[Shifts.openedBy],
            openingFloatCents = shift[Shifts.openingFloatCents],
            revenueCents = agg.revenue,
            transactionCount = agg.count,
            avgCheckCents = if (agg.count > 0) agg.revenue / agg.count else 0,
            tenderBreakdown = agg.tenderBreakdown,
            itemMix = agg.itemMix,
            voids = voids,
            corkageCents = agg.corkage,
            cashPaidInCents = cashPaidIn,
            cashPaidOutCents = cashPaidOut,
            cashRefundCents = cashRefund,
            refundTotalCents = refundTotal,
            // what the store gained (+) or gave (−) rounding cash to the nickel
            cashRoundingCents = agg.cashRounding - refundRounding,
            expectedCashCents = expected,
            closingCountCents = closingCountCents,
            overShortCents = closingCountCents?.let { it - expected },
            dineInCount = agg.modes["DINE_IN"] ?: 0,
            takeOutCount = agg.modes["TAKE_OUT"] ?: 0,
            tipsCents = agg.tips,
            tipsByServer = agg.tipsByServer,
            taxes = taxBreakdown({ Checks.shiftId eq shiftId },
                Refunds.select(Refunds.taxesJson).where { (Refunds.shiftId eq shiftId) and Refunds.taxesJson.isNotNull() }),
        )
    }

    /**
     * The added taxes collected, one row per tax AND rate, for remittance:
     * the closed checks matching [where] (their lock-time breakdown, exactly
     * what the receipts charged), less the taxes [refunds] handed back. A
     * guest's one "Tax (8.25%)" line is these rows added up, to the cent.
     * A rate change (6.75% → 7.25%) keeps the old rate on its own row.
     * Read row by row: only the sums are kept.
     */
    private fun taxBreakdown(
        where: SqlExpressionBuilder.() -> Op<Boolean>,
        refunds: org.jetbrains.exposed.sql.Query,
    ): List<ReportTax> {
        data class Key(val code: String, val rate: String)
        val first = linkedMapOf<Key, TaxView>()
        val sums = linkedMapOf<Key, Long>()
        fun add(json: String?, sign: Long) {
            if (json.isNullOrBlank()) return
            for (line in runCatching { taxLinesFromJson(json) }.getOrDefault(emptyList())) {
                val v = line.toView()
                val k = Key(v.code, v.ratePercent)
                first.putIfAbsent(k, v)
                sums[k] = Math.addExact(sums[k] ?: 0L, sign * v.amountCents)
            }
        }
        Checks.select(Checks.lockedTaxesJson)
            .where { where() and (Checks.status eq "CLOSED") and Checks.lockedTaxesJson.isNotNull() }
            .forEach { add(it[Checks.lockedTaxesJson], 1) }
        refunds.forEach { add(it[Refunds.taxesJson], -1) }
        return sums.map { (k, cents) ->
            val v = first.getValue(k)
            ReportTax(v.code, v.labelFr, v.labelEn, v.ratePercent, v.remitTo, cents)
        }
    }

    /** A voided bill on the report, with any payments handed back when it was cancelled. */
    private fun voidEntry(row: ResultRow): VoidEntry {
        val id = row[Checks.id].value
        val back = Tenders.selectAll().where { (Tenders.transactionId eq id) and Tenders.reversedAt.isNotNull() }
            .sumOfExact { it[Tenders.amountAppliedCents] }
        return VoidEntry(id, row[Checks.voidReason] ?: "-", row[Checks.voidedBy] ?: "-", back)
    }

    private fun toView(row: ResultRow) = ShiftView(
        id = row[Shifts.id].value,
        status = row[Shifts.status],
        openedAt = VenueClock.iso(row[Shifts.openedAt]),
        openedBy = row[Shifts.openedBy],
        openingFloatCents = row[Shifts.openingFloatCents],
        closedAt = row[Shifts.closedAt]?.let(VenueClock::iso),
        closingCountCents = row[Shifts.closingCountCents],
        expectedCashCents = row[Shifts.expectedCashCents],
        overShortCents = row[Shifts.overShortCents],
    )
}

@Serializable
data class ShiftView(
    val id: Int,
    val status: String,
    val openedAt: String,
    val openedBy: String,
    val openingFloatCents: Long,
    val closedAt: String?,
    val closingCountCents: Long?,
    val expectedCashCents: Long?,
    val overShortCents: Long?,
)

@Serializable
data class TenderSummary(
    val type: String, val amountCents: Long, val count: Int,
    /** Card tips taken with these tenders, on top of [amountCents] (reader / Stripe). */
    val tipCents: Long = 0,
)

/** Card tips one server took (the bills they opened). */
@Serializable
data class ServerTips(val userId: String, val name: String, val tipCents: Long, val count: Int)

@Serializable
data class ItemMixEntry(val itemId: String, val nameFr: String, val nameEn: String = "", val qty: Int, val revenueCents: Long)

@Serializable
data class VoidEntry(
    val checkId: Int, val reason: String, val voidedBy: String,
    /** Payments handed back when the bill was cancelled (void with reverseTenders); 0 = none. */
    val reversedCents: Long = 0,
)

@Serializable
data class ShiftReport(
    val shiftId: Int,
    val shiftStatus: String,
    val openedAt: String,
    val openedBy: String,
    val openingFloatCents: Long,
    val revenueCents: Long,
    val transactionCount: Int,
    val avgCheckCents: Long,
    val tenderBreakdown: List<TenderSummary>,
    val itemMix: List<ItemMixEntry>,
    val voids: List<VoidEntry>,
    val corkageCents: Long,
    // non-sale cash movements + refunds posted to the shift (feed expected cash)
    val cashPaidInCents: Long = 0,
    val cashPaidOutCents: Long = 0,
    val cashRefundCents: Long = 0, // cash refunds only — what actually left the drawer (rounded)
    val refundTotalCents: Long = 0, // all refunds this shift (any tender), informational
    /** Net nickel rounding on cash: sales' rounding less cash refunds' (signed). */
    val cashRoundingCents: Long = 0,
    // the drawer: X and Z (null on a date-range report)
    val expectedCashCents: Long? = null,
    // Z-only fields (null on X-report)
    val closingCountCents: Long? = null,
    val overShortCents: Long? = null,
    /** Quick-serve: paid counter orders eaten in / taken out (both 0 elsewhere). */
    val dineInCount: Int = 0,
    val takeOutCount: Int = 0,
    /** Card tips on top of the bills (reader / Stripe): not revenue, owed to staff. */
    val tipsCents: Long = 0,
    val tipsByServer: List<ServerTips> = emptyList(),
    /**
     * The added taxes collected (sales less refunds), one row per tax and
     * rate, with who each is paid to: NC sales tax 7.25% (NCDOR) and Wake
     * prepared food tax 1% (Wake County). Empty for a store without added taxes.
     */
    val taxes: List<ReportTax> = emptyList(),
)

/** One added tax on an X / Z / range report: its code, labels, rate ("7.25"), authority and net amount. */
@Serializable
data class ReportTax(
    val code: String,
    val labelFr: String,
    val labelEn: String,
    val ratePercent: String,
    val remitTo: String = "",
    val amountCents: Long,
)

@Serializable
data class CashMovementView(
    val id: Int,
    val shiftId: Int?,
    val direction: String,
    val amountCents: Long,
    val reason: String,
    val createdBy: String,
    val createdAt: String,
)

@Serializable
data class CashMovementResult(val movement: CashMovementView, val slipText: String)
