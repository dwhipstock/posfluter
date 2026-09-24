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
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime

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
            put("openedAt", now.toString())
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
            put("createdAt", now.toString())
        })
        CashMovementResult(
            movement = CashMovementView(id, shift, dir, amountCents, reason, managerId, now.toString()),
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
                    it[CashMovements.createdBy], it[CashMovements.createdAt].toString(),
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
    private fun renderCashSlip(dir: String, amount: Long, reason: String, userId: String, now: LocalDateTime): String {
        val policy = policyFor(userId)
        fun msg(key: MessageKey) = Messages.get(key, policy.locale)
        val title = if (dir == "IN") msg(CASH_IN_HEADER) else msg(CASH_OUT_HEADER)
        val lines = buildList {
            add(PrintLine.LogoPlaceholder(policy.logoFallbackText))
            policy.headerLines.forEach { add(PrintLine.Text(it, Align.CENTER)) }
            add(PrintLine.Blank)
            add(PrintLine.Header(title))
            add(PrintLine.Blank)
            add(PrintLine.KeyValue(msg(SLIP_TIME), policy.formatDate(now)))
            add(PrintLine.Divider)
            add(PrintLine.KeyValue(
                if (dir == "IN") msg(CASH_IN) else msg(CASH_OUT),
                Money(amount).format(), emphasized = true,
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

    /** Z-report: computes the X content + cash reconciliation, then closes the shift. */
    fun closeShift(userId: String, closingCountCents: Long): ShiftReport = transaction {
        val shift = Shifts.selectAll().where { Shifts.status eq "OPEN" }.firstOrNull()
            ?: throw ConflictException("no open shift", "no_open_shift")
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
            put("revenueCents", report.revenueCents)
            put("expectedCashCents", report.expectedCashCents)
            put("closingCountCents", closingCountCents)
            put("overShortCents", report.overShortCents)
            put("openedAt", report.openedAt)
            put("closedAt", now.toString())
            put("openedBy", report.openedBy)
            put("openingFloatCents", report.openingFloatCents)
            put("transactionCount", report.transactionCount)
            put("avgCheckCents", report.avgCheckCents)
            put("corkageCents", report.corkageCents)
            put("cashPaidInCents", report.cashPaidInCents)
            put("cashPaidOutCents", report.cashPaidOutCents)
            put("cashRefundCents", report.cashRefundCents)
            put("refundTotalCents", report.refundTotalCents)
            putJsonArray("tenderBreakdown") {
                report.tenderBreakdown.forEach { t ->
                    addJsonObject {
                        put("type", t.type)
                        put("amountCents", t.amountCents)
                        put("count", t.count)
                    }
                }
            }
        })
        report.copy(shiftStatus = "CLOSED")
    }

    /**
     * X-report layout over an arbitrary closed-at date range (inclusive).
     * Same aggregates as a shift report; no cash reconciliation — the drawer
     * count is a shift concept, Z-close stays shift-only on purpose.
     */
    fun rangeReport(from: java.time.LocalDate, to: java.time.LocalDate): ShiftReport = transaction {
        require(!to.isBefore(from)) { "to must not be before from" }
        val start = from.atStartOfDay()
        val end = to.plusDays(1).atStartOfDay()
        val closed = Checks.selectAll().where {
            (Checks.status eq "CLOSED") and
                (Checks.closedAt greaterEq start) and (Checks.closedAt less end)
        }.toList()
        val voids = Checks.selectAll().where {
            (Checks.status eq "VOID") and
                (Checks.closedAt greaterEq start) and (Checks.closedAt less end)
        }.map { VoidEntry(it[Checks.id].value, it[Checks.voidReason] ?: "-", it[Checks.voidedBy] ?: "-") }
        val agg = aggregate(closed)
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
        )
    }

    private class Aggregates(
        val revenue: Long, val count: Int, val tenderBreakdown: List<TenderSummary>,
        val itemMix: List<ItemMixEntry>, val corkage: Long,
        val cashIn: Long, val changeOut: Long,
    )

    /** Shared X/Z/range math over a set of CLOSED check rows. */
    private fun aggregate(closed: List<ResultRow>): Aggregates {
        val closedIds = closed.map { it[Checks.id].value }
        val revenue = closed.sumOf { it[Checks.lockedGrandTotalCents] ?: 0 }

        val tenderRows = if (closedIds.isEmpty()) emptyList()
        else Tenders.selectAll().where { Tenders.transactionId inList closedIds }.toList()
        val tenderBreakdown = tenderRows.groupBy { it[Tenders.type] }.map { (type, rows) ->
            TenderSummary(type, rows.sumOf { it[Tenders.amountAppliedCents] }, rows.size)
        }.sortedByDescending { it.amountCents }

        // item mix over ACTIVE lines of closed checks. TODO: separate top-by-qty view
        val itemMix = if (closedIds.isEmpty()) emptyList()
        else (CheckLines innerJoin Items).selectAll()
            .where { (CheckLines.checkId inList closedIds) and (CheckLines.status eq "ACTIVE") }
            .groupBy { it[CheckLines.itemId] }
            .map { (itemId, rows) ->
                ItemMixEntry(
                    // inner join to Items → never null here; open lines (null
                    // item_id) drop out of the mix on purpose (revenue still
                    // counts them via the locked grand total)
                    itemId = itemId!!,
                    nameFr = rows.first()[Items.nameFr],
                    nameEn = rows.first()[Items.nameEn],
                    qty = rows.sumOf { it[CheckLines.qty] },
                    revenueCents = rows.sumOf { it[CheckLines.unitPriceCents] * it[CheckLines.qty] },
                )
            }.sortedByDescending { it.revenueCents }.take(10)

        // corkage collected: the lock-time fee lines (021) are what the locked
        // grand totals actually charged — a per-bottle price change mid-shift
        // must not re-price earlier checks. Pre-021 rows fall back to re-assessment.
        val corkage = closed.sumOf { row ->
            row[Checks.lockedFeesJson]?.let { json ->
                Json.parseToJsonElement(json).jsonArray
                    .filter { it.jsonObject["code"]?.jsonPrimitive?.content == "corkage" }
                    .sumOf { it.jsonObject["amountCents"]?.jsonPrimitive?.long ?: 0L }
            } ?: run {
                val ctx = FeeContext(itemsSubtotal = Money.ZERO, corkageBottles = row[Checks.corkageBottles])
                config.fees.filterIsInstance<Fee.Corkage>().sumOf { it.assess(ctx)?.amount?.cents ?: 0 }
            }
        }

        val cashRows = tenderRows.filter { it[Tenders.type] == "CASH" }
        return Aggregates(
            revenue = revenue,
            count = closed.size,
            tenderBreakdown = tenderBreakdown,
            itemMix = itemMix,
            corkage = corkage,
            cashIn = cashRows.sumOf { it[Tenders.amountTenderedCents] },
            changeOut = cashRows.sumOf { it[Tenders.changeCents] },
        )
    }

    private fun buildReport(shift: ResultRow, closingCountCents: Long?): ShiftReport {
        val shiftId = shift[Shifts.id].value
        val closed = Checks.selectAll()
            .where { (Checks.shiftId eq shiftId) and (Checks.status eq "CLOSED") }.toList()
        val agg = aggregate(closed)

        val voids = Checks.selectAll()
            .where { (Checks.shiftId eq shiftId) and (Checks.status eq "VOID") }
            .map { VoidEntry(it[Checks.id].value, it[Checks.voidReason] ?: "-", it[Checks.voidedBy] ?: "-") }

        // non-sale cash movements and refunds posted to this shift
        val movements = CashMovements.selectAll().where { CashMovements.shiftId eq shiftId }.toList()
        val cashPaidIn = movements.filter { it[CashMovements.direction] == "IN" }.sumOf { it[CashMovements.amountCents] }
        val cashPaidOut = movements.filter { it[CashMovements.direction] == "OUT" }.sumOf { it[CashMovements.amountCents] }
        val refunds = Refunds.selectAll().where { Refunds.shiftId eq shiftId }.toList()
        val refundTotal = refunds.sumOf { it[Refunds.grossCents] }
        // only CASH refunds leave the drawer; Card/transfer refunds don't
        val cashRefund = refunds.filter { it[Refunds.tenderType] == "CASH" }.sumOf { it[Refunds.grossCents] }

        // cash drawer math: opening float + cash sales - change given
        //   + non-sale cash in - non-sale cash out - cash refunds paid out
        val expected = if (closingCountCents != null)
            shift[Shifts.openingFloatCents] + agg.cashIn - agg.changeOut + cashPaidIn - cashPaidOut - cashRefund
        else null

        return ShiftReport(
            shiftId = shiftId,
            shiftStatus = shift[Shifts.status],
            openedAt = shift[Shifts.openedAt].toString(),
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
            expectedCashCents = expected,
            closingCountCents = closingCountCents,
            overShortCents = if (expected != null && closingCountCents != null) closingCountCents - expected else null,
        )
    }

    private fun toView(row: ResultRow) = ShiftView(
        id = row[Shifts.id].value,
        status = row[Shifts.status],
        openedAt = row[Shifts.openedAt].toString(),
        openedBy = row[Shifts.openedBy],
        openingFloatCents = row[Shifts.openingFloatCents],
        closedAt = row[Shifts.closedAt]?.toString(),
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
data class TenderSummary(val type: String, val amountCents: Long, val count: Int)

@Serializable
data class ItemMixEntry(val itemId: String, val nameFr: String, val nameEn: String = "", val qty: Int, val revenueCents: Long)

@Serializable
data class VoidEntry(val checkId: Int, val reason: String, val voidedBy: String)

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
    val cashRefundCents: Long = 0, // cash refunds only — what actually left the drawer
    val refundTotalCents: Long = 0, // all refunds this shift (any tender), informational
    // Z-only fields (null on X-report)
    val expectedCashCents: Long? = null,
    val closingCountCents: Long? = null,
    val overShortCents: Long? = null,
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
