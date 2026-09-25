package dev.dwhipstock.poscloud.reports

import dev.dwhipstock.poscloud.BadRequestException
import dev.dwhipstock.poscloud.CloudTime
import dev.dwhipstock.poscloud.NotFoundException
import dev.dwhipstock.poscloud.VenueScope
import dev.dwhipstock.poscloud.db.CashMovements
import dev.dwhipstock.poscloud.db.CatalogCategories
import dev.dwhipstock.poscloud.db.CheckLines
import dev.dwhipstock.poscloud.db.CheckTenders
import dev.dwhipstock.poscloud.db.Checks
import dev.dwhipstock.poscloud.db.Refunds
import dev.dwhipstock.poscloud.db.Shifts
import dev.dwhipstock.poscloud.portalScopes
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Report projections over store-computed cents figures. Grep-able invariant:
 * no arithmetic on money beyond SUM/COUNT, net = gross − tax, avg = sum/count.
 * The store decomposed the tax at sale time; the cloud only adds it up.
 *
 * Multi-store: every report covers the stores the request is scoped to — one
 * store (`?venue=<id>`) or all of the tenant's stores (no venue). Each store's
 * rows are selected over ITS OWN business days (its zone's midnights), so an
 * "All stores" day is the sum of each store's local day. Rows that come from a
 * specific store carry its `venueId`; combined views also get a per-store
 * breakdown (`byVenue`).
 */

private val lenientJson = Json { ignoreUnknownKeys = true }
private val log = LoggerFactory.getLogger("reports")

/** One store over the requested dates, as instants of its own business days. */
private class VenueRange(val venue: VenueScope, val from: LocalDate, val to: LocalDate) {
    val id: String get() = venue.venueId
    val zone: ZoneId get() = venue.zone
    val start: OffsetDateTime = CloudTime.startOfDay(from, zone)
    val end: OffsetDateTime = CloudTime.startOfDay(to.plusDays(1), zone)
}

private class ReportCtx(val tenantId: String, val venues: List<VenueRange>) {
    private val byId = venues.associateBy { it.id }
    fun zoneOf(venueId: String): ZoneId = byId[venueId]?.zone ?: CloudTime.zone(null)
    fun iso(t: OffsetDateTime?, venueId: String): String? = t?.let { CloudTime.iso(it, zoneOf(venueId)) }
    val label: String get() = "$tenantId/${venues.joinToString(",") { it.id }} ${venues.first().from}..${venues.first().to}"
}

/** Session → the in-scope stores + inclusive business-day range (default: each store's today). */
private fun reportCtx(call: ApplicationCall): ReportCtx {
    val (principal, venues) = portalScopes(call)
    return ReportCtx(principal.tenantId, venues.map { v ->
        val (from, to) = resolveRange(call, v.zone)
        VenueRange(v, from, to)
    })
}

/** ?from/?to (default today/today in [zone]), validated identically for every report. */
private fun resolveRange(call: ApplicationCall, zone: ZoneId): Pair<LocalDate, LocalDate> {
    val today = LocalDate.now(zone)
    val from = call.request.queryParameters["from"]?.let(::parseDate) ?: today
    val to = call.request.queryParameters["to"]?.let(::parseDate) ?: today
    if (to.isBefore(from)) throw BadRequestException("to must not be before from")
    return from to to
}

private fun parseDate(value: String): LocalDate =
    runCatching { LocalDate.parse(value) }.getOrElse {
        throw BadRequestException("'$value' is not a YYYY-MM-DD date")
    }

private fun anyOf(ops: List<Op<Boolean>>): Op<Boolean> = ops.reduce { a, b -> a or b }

/** tenant AND (venue₁ within its days OR venue₂ within its days …). */
private fun SqlExpressionBuilder.inScope(
    ctx: ReportCtx, tenant: Column<String>, venue: Column<String>, time: Column<OffsetDateTime?>,
): Op<Boolean> = (tenant eq ctx.tenantId) and anyOf(ctx.venues.map { v ->
    (venue eq v.id) and (time greaterEq v.start) and (time less v.end)
})

private fun SqlExpressionBuilder.checksInRange(ctx: ReportCtx) =
    inScope(ctx, Checks.tenantId, Checks.venueId, Checks.closedAt)

private fun closedChecks(ctx: ReportCtx): List<ResultRow> =
    Checks.selectAll().where { checksInRange(ctx) and (Checks.status eq "CLOSED") }.toList()

private fun voidChecks(ctx: ReportCtx): List<ResultRow> =
    Checks.selectAll().where { checksInRange(ctx) and (Checks.status eq "VOID") }.toList()

private fun gross(row: ResultRow): Long = row[Checks.grandTotalCents] ?: 0
private fun checkTax(row: ResultRow): Long = row[Checks.taxIncludedCents] ?: 0

/**
 * Refunds posted in the range, keyed off refund date (a refund is recognised
 * when issued, not when the original bill sold). The store already decomposed
 * gross/net/tax; the cloud only sums. Netted out of sales + tax everywhere.
 */
private fun refundsInRange(ctx: ReportCtx): List<ResultRow> =
    Refunds.selectAll().where { inScope(ctx, Refunds.tenantId, Refunds.venueId, Refunds.createdAt) }.toList()

private fun rGross(row: ResultRow): Long = row[Refunds.grossCents] ?: 0
private fun rNet(row: ResultRow): Long = row[Refunds.netCents] ?: 0
private fun refundTax(row: ResultRow): Long = row[Refunds.taxIncludedCents] ?: 0

/** Rows of a per-check child table for exactly these (venue, check) pairs — check ids repeat across stores. */
private fun childRowsOf(
    ctx: ReportCtx, checks: List<ResultRow>,
    table: org.jetbrains.exposed.sql.Table, tenant: Column<String>, venue: Column<String>, checkId: Column<Int>,
): List<ResultRow> {
    val idsByVenue = checks.groupBy({ it[Checks.venueId] }, { it[Checks.checkId] })
    if (idsByVenue.isEmpty()) return emptyList()
    return table.selectAll().where {
        (tenant eq ctx.tenantId) and anyOf(idsByVenue.map { (v, ids) -> (venue eq v) and (checkId inList ids) })
    }.toList()
}

private fun linesOf(ctx: ReportCtx, checks: List<ResultRow>) =
    childRowsOf(ctx, checks, CheckLines, CheckLines.tenantId, CheckLines.venueId, CheckLines.checkId)

private fun tendersOf(ctx: ReportCtx, checks: List<ResultRow>) =
    childRowsOf(ctx, checks, CheckTenders, CheckTenders.tenantId, CheckTenders.venueId, CheckTenders.checkId)

/**
 * A closed check with a grand total but no decomposed tax is an incomplete
 * projection — it counts toward gross while contributing 0 tax, so tax/net
 * undercount. The cloud can't fabricate the tax (store is authoritative), but
 * it must not undercount *silently*: log the count so the gap is visible until
 * the store's report backfill lands. Post-backfill this is always 0.
 */
private fun warnIfUndercounting(ctx: ReportCtx, closed: List<ResultRow>) {
    val incomplete = closed.count { (it[Checks.grandTotalCents] ?: 0L) > 0L && it[Checks.taxIncludedCents] == null }
    if (incomplete > 0) log.warn(
        "reports[${ctx.label}]: $incomplete closed " +
            "check(s) carry a grand total but no decomposed tax — tax/net undercount until the store's " +
            "report backfill reaches the cloud")
}

@Serializable
data class DayRow(
    val date: String, val grossCents: Long, val netCents: Long,
    val taxCents: Long, val checkCount: Int)

/** Per business day (each row's own store zone): closed sales less refunds issued that day. */
private fun byDay(ctx: ReportCtx, closed: List<ResultRow>, refunds: List<ResultRow>): List<DayRow> {
    val closedByDay = closed.groupBy { CloudTime.localDate(it[Checks.closedAt]!!, ctx.zoneOf(it[Checks.venueId])) }
    val refundByDay = refunds.groupBy { CloudTime.localDate(it[Refunds.createdAt]!!, ctx.zoneOf(it[Refunds.venueId])) }
    return (closedByDay.keys + refundByDay.keys).toSortedSet().map { date ->
        val crows = closedByDay[date].orEmpty()
        val rrows = refundByDay[date].orEmpty()
        val g = crows.sumOf(::gross) - rrows.sumOf(::rGross)
        val v = crows.sumOf(::checkTax) - rrows.sumOf(::refundTax)
        DayRow(date.toString(), g, g - v, v, crows.size)
    }
}

/** One store's headline figures — the per-store comparison in combined views. */
@Serializable
data class VenueSummaryRow(
    val venueId: String, val venueName: String,
    val grossCents: Long, val netCents: Long, val taxCents: Long,
    val checkCount: Int, val avgCheckCents: Long,
    val voidCount: Int, val refundAmountCents: Long)

private fun venueSummaries(
    ctx: ReportCtx, closed: List<ResultRow>, voids: List<ResultRow>, refunds: List<ResultRow>,
): List<VenueSummaryRow> {
    val closedBy = closed.groupBy { it[Checks.venueId] }
    val voidsBy = voids.groupBy { it[Checks.venueId] }
    val refundsBy = refunds.groupBy { it[Refunds.venueId] }
    return ctx.venues.map { v ->
        val c = closedBy[v.id].orEmpty()
        val r = refundsBy[v.id].orEmpty()
        val closedGross = c.sumOf(::gross)
        val g = closedGross - r.sumOf(::rGross)
        val tax = c.sumOf(::checkTax) - r.sumOf(::refundTax)
        VenueSummaryRow(
            venueId = v.id, venueName = v.venue.name,
            grossCents = g, netCents = g - tax, taxCents = tax,
            checkCount = c.size, avgCheckCents = if (c.isEmpty()) 0 else closedGross / c.size,
            voidCount = voidsBy[v.id].orEmpty().size, refundAmountCents = r.sumOf(::rGross),
        )
    }
}

@Serializable
data class SummaryResponse(
    val grossCents: Long, val netCents: Long, val taxCents: Long,
    val checkCount: Int, val avgCheckCents: Long,
    val voidCount: Int, val voidAmountCents: Long,
    val refundCount: Int, val refundAmountCents: Long,
    val corkageCents: Long, val serviceChargeCents: Long,
    val byDay: List<DayRow>,
    /** One row per in-scope store (a single row when one store is selected). */
    val byVenue: List<VenueSummaryRow>)

@Serializable
data class ByVenueResponse(val venues: List<VenueSummaryRow>, val grossCents: Long, val checkCount: Int)

@Serializable
data class TaxReportResponse(
    val ratePercent: Int, val rows: List<DayRow>, val totals: TaxTotals, val byVenue: List<VenueSummaryRow>)

@Serializable
data class TaxTotals(val grossCents: Long, val netCents: Long, val taxCents: Long, val checkCount: Int)

@Serializable
data class PaymentRow(val type: String, val amountCents: Long, val count: Int)

@Serializable
data class VenuePayments(val venueId: String, val totalCents: Long, val rows: List<PaymentRow>)

@Serializable
data class PaymentsResponse(val rows: List<PaymentRow>, val totalCents: Long, val byVenue: List<VenuePayments>)

@Serializable
data class ItemRow(
    val itemId: String?, val nameFr: String?, val nameEn: String?,
    val categoryId: String?, val categoryNameFr: String?, val categoryNameEn: String?,
    val qty: Int, val revenueCents: Long)

@Serializable
data class CategoryRow(
    val categoryId: String?, val nameFr: String?, val nameEn: String?,
    val qty: Int, val revenueCents: Long)

@Serializable
data class HourRow(val hour: Int, val grossCents: Long, val checkCount: Int)

@Serializable
data class ZoneRow(
    val zoneId: String?, val zoneNameFr: String?, val zoneNameEn: String?,
    val grossCents: Long, val checkCount: Int, val venueId: String)

@Serializable
data class TableRow(
    val zoneId: String?, val zoneNameEn: String?, val tableId: String?, val tableLabel: String?,
    val grossCents: Long, val checkCount: Int, val venueId: String)

@Serializable
data class TablesResponse(val byZone: List<ZoneRow>, val byTable: List<TableRow>)

@Serializable
data class VoidRow(
    val checkId: Int, val voidedAt: String?, val tableLabel: String?,
    val amountCents: Long, val reason: String?, val voidedBy: String?, val venueId: String)

@Serializable
data class ExceptionsResponse(
    val voids: List<VoidRow>, val voidCount: Int, val voidAmountCents: Long, val corkageCents: Long)

@Serializable
data class TenderTypeRow(val type: String, val amountCents: Long, val count: Int)

@Serializable
data class ShiftDto(
    val shiftId: Long, val status: String,
    val openedAt: String?, val closedAt: String?,
    val openedBy: String?, val closedBy: String?,
    val openingFloatCents: Long?, val revenueCents: Long?,
    val transactionCount: Int?, val avgCheckCents: Long?, val corkageCents: Long?,
    val tenderBreakdown: List<TenderTypeRow>,
    val expectedCashCents: Long?, val closingCountCents: Long?, val overShortCents: Long?,
    val venueId: String)

@Serializable
data class ShiftsResponse(val rows: List<ShiftDto>)

@Serializable
data class JournalLine(
    val nameFr: String?, val nameEn: String?, val qty: Int,
    val unitPriceCents: Long, val lineTotalCents: Long)

@Serializable
data class JournalRow(
    val checkId: Int, val status: String, val closedAt: String?,
    val tableLabel: String?, val zoneNameEn: String?,
    val grandTotalCents: Long, val taxIncludedCents: Long,
    val tenderTypes: List<String>, val lines: List<JournalLine>, val venueId: String)

@Serializable
data class JournalResponse(val total: Long, val rows: List<JournalRow>)

@Serializable
data class RefundReasonRow(
    val reason: String, val count: Int,
    val grossCents: Long, val netCents: Long, val taxCents: Long)

@Serializable
data class RefundListRow(
    val refundId: Long, val checkId: Int?, val createdAt: String?,
    val tableLabel: String?, val tenderType: String?, val reason: String?,
    val grossCents: Long, val netCents: Long, val taxCents: Long, val venueId: String)

@Serializable
data class RefundsResponse(
    val count: Int, val grossCents: Long, val netCents: Long, val taxCents: Long,
    val byReason: List<RefundReasonRow>, val byTender: List<TenderTypeRow>,
    val rows: List<RefundListRow>)

@Serializable
data class CashMovementListRow(
    val movementId: Long, val createdAt: String?, val direction: String,
    val amountCents: Long, val reason: String?, val user: String?, val venueId: String)

@Serializable
data class CashMovementsResponse(
    val paidInCents: Long, val paidOutCents: Long, val netCents: Long,
    val inCount: Int, val outCount: Int, val rows: List<CashMovementListRow>)

fun Route.reportRoutes() {

    get("/reports/summary") {
        val ctx = reportCtx(call)
        val response = transaction {
            val closed = closedChecks(ctx)
            val voids = voidChecks(ctx)
            val refunds = refundsInRange(ctx)
            warnIfUndercounting(ctx, closed)
            val closedGross = closed.sumOf(::gross)
            // net sales after refunds; avg check stays a sale-time figure (pre-refund)
            val grossTotal = closedGross - refunds.sumOf(::rGross)
            val taxTotal = closed.sumOf(::checkTax) - refunds.sumOf(::refundTax)
            SummaryResponse(
                grossCents = grossTotal,
                netCents = grossTotal - taxTotal,
                taxCents = taxTotal,
                checkCount = closed.size,
                avgCheckCents = if (closed.isEmpty()) 0 else closedGross / closed.size,
                voidCount = voids.size,
                voidAmountCents = voids.sumOf(::gross),
                refundCount = refunds.size,
                refundAmountCents = refunds.sumOf(::rGross),
                corkageCents = closed.sumOf { it[Checks.corkageCents] ?: 0 },
                serviceChargeCents = closed.sumOf { it[Checks.serviceChargeCents] ?: 0 },
                byDay = byDay(ctx, closed, refunds),
                byVenue = venueSummaries(ctx, closed, voids, refunds),
            )
        }
        call.respond(response)
    }

    /**
     * The per-store comparison: one row per in-scope store over the same dates,
     * each read over its own business days. Same arithmetic as the summary
     * headline (gross net of refunds, SUM/COUNT only).
     */
    get("/reports/by-venue") {
        val ctx = reportCtx(call)
        val response = transaction {
            val rows = venueSummaries(ctx, closedChecks(ctx), voidChecks(ctx), refundsInRange(ctx))
            ByVenueResponse(rows, rows.sumOf { it.grossCents }, rows.sumOf { it.checkCount })
        }
        call.respond(response)
    }

    get("/reports/tax") {
        val ctx = reportCtx(call)
        val response = transaction {
            val closed = closedChecks(ctx)
            val voids = voidChecks(ctx)
            val refunds = refundsInRange(ctx)
            warnIfUndercounting(ctx, closed)
            val grossTotal = closed.sumOf(::gross) - refunds.sumOf(::rGross)
            val taxTotal = closed.sumOf(::checkTax) - refunds.sumOf(::refundTax)
            TaxReportResponse(
                ratePercent = 13,
                rows = byDay(ctx, closed, refunds),
                totals = TaxTotals(grossTotal, grossTotal - taxTotal, taxTotal, closed.size),
                byVenue = venueSummaries(ctx, closed, voids, refunds),
            )
        }
        call.respond(response)
    }

    get("/reports/refunds") {
        val ctx = reportCtx(call)
        val response = transaction {
            val refunds = refundsInRange(ctx)
            val byReason = refunds.groupBy { it[Refunds.reason] ?: "—" }.map { (reason, rows) ->
                RefundReasonRow(reason, rows.size, rows.sumOf(::rGross), rows.sumOf(::rNet), rows.sumOf(::refundTax))
            }.sortedByDescending { it.grossCents }
            val byTender = refunds.groupBy { it[Refunds.tenderType] ?: "CASH" }.map { (type, rows) ->
                TenderTypeRow(type, rows.sumOf(::rGross), rows.size)
            }.sortedByDescending { it.amountCents }
            val rows = refunds.sortedByDescending { it[Refunds.createdAt] }.map { r ->
                val venueId = r[Refunds.venueId]
                RefundListRow(
                    r[Refunds.refundId], r[Refunds.checkId], ctx.iso(r[Refunds.createdAt], venueId),
                    r[Refunds.tableLabel], r[Refunds.tenderType], r[Refunds.reason],
                    rGross(r), rNet(r), refundTax(r), venueId)
            }
            RefundsResponse(
                refunds.size, refunds.sumOf(::rGross), refunds.sumOf(::rNet), refunds.sumOf(::refundTax),
                byReason, byTender, rows)
        }
        call.respond(response)
    }

    get("/reports/cash-movements") {
        val ctx = reportCtx(call)
        val response = transaction {
            val movements = CashMovements.selectAll().where {
                inScope(ctx, CashMovements.tenantId, CashMovements.venueId, CashMovements.createdAt)
            }.toList()
            val ins = movements.filter { it[CashMovements.direction] == "IN" }
            val outs = movements.filter { it[CashMovements.direction] == "OUT" }
            val paidIn = ins.sumOf { it[CashMovements.amountCents] ?: 0 }
            val paidOut = outs.sumOf { it[CashMovements.amountCents] ?: 0 }
            val rows = movements.sortedByDescending { it[CashMovements.createdAt] }.map { m ->
                val venueId = m[CashMovements.venueId]
                CashMovementListRow(
                    m[CashMovements.movementId], ctx.iso(m[CashMovements.createdAt], venueId),
                    m[CashMovements.direction] ?: "", m[CashMovements.amountCents] ?: 0,
                    m[CashMovements.reason], m[CashMovements.createdBy], venueId)
            }
            CashMovementsResponse(paidIn, paidOut, paidIn - paidOut, ins.size, outs.size, rows)
        }
        call.respond(response)
    }

    get("/reports/payments") {
        val ctx = reportCtx(call)
        val response = transaction {
            val tenders = tendersOf(ctx, closedChecks(ctx))
            fun rowsOf(group: List<ResultRow>) = group.groupBy { it[CheckTenders.type] }.map { (type, g) ->
                PaymentRow(type, g.sumOf { it[CheckTenders.amountAppliedCents] ?: 0 }, g.size)
            }.sortedByDescending { it.amountCents }
            val rows = rowsOf(tenders)
            val byVenue = tenders.groupBy { it[CheckTenders.venueId] }.let { grouped ->
                ctx.venues.map { v ->
                    val vr = rowsOf(grouped[v.id].orEmpty())
                    VenuePayments(v.id, vr.sumOf { it.amountCents }, vr)
                }
            }
            PaymentsResponse(rows, rows.sumOf { it.amountCents }, byVenue)
        }
        call.respond(response)
    }

    get("/reports/items") {
        val ctx = reportCtx(call)
        val response = transaction {
            val categoryNames = categoryNames(ctx)
            val rows = linesOf(ctx, closedChecks(ctx))
                .groupBy { it[CheckLines.itemId] }
                .map { (itemId, group) ->
                    val first = group.first()
                    val category = categoryNames.lookup(first[CheckLines.venueId], first[CheckLines.categoryId])
                    ItemRow(
                        itemId = itemId,
                        nameFr = if (itemId == null) "Open item" else first[CheckLines.nameFr],
                        nameEn = if (itemId == null) "Open item" else first[CheckLines.nameEn],
                        categoryId = first[CheckLines.categoryId],
                        categoryNameFr = category?.first,
                        categoryNameEn = category?.second,
                        qty = group.sumOf { it[CheckLines.qty] },
                        revenueCents = group.sumOf { it[CheckLines.lineTotalCents] },
                    )
                }.sortedByDescending { it.revenueCents }
            mapOf("rows" to rows)
        }
        call.respond(response)
    }

    get("/reports/categories") {
        val ctx = reportCtx(call)
        val response = transaction {
            val categoryNames = categoryNames(ctx)
            val rows = linesOf(ctx, closedChecks(ctx))
                .groupBy { it[CheckLines.categoryId] }
                .map { (categoryId, group) ->
                    val category = categoryNames.lookup(group.first()[CheckLines.venueId], categoryId)
                    CategoryRow(
                        categoryId = categoryId,
                        nameFr = if (categoryId == null) "Open item" else category?.first,
                        nameEn = if (categoryId == null) "Open item" else category?.second,
                        qty = group.sumOf { it[CheckLines.qty] },
                        revenueCents = group.sumOf { it[CheckLines.lineTotalCents] },
                    )
                }.sortedByDescending { it.revenueCents }
            mapOf("rows" to rows)
        }
        call.respond(response)
    }

    get("/reports/hourly") {
        val ctx = reportCtx(call)
        val response = transaction {
            // each check in its own store's local hour
            val byHour = closedChecks(ctx).groupBy {
                CloudTime.localHour(it[Checks.closedAt]!!, ctx.zoneOf(it[Checks.venueId]))
            }
            val rows = (0..23).map { hour ->
                val group = byHour[hour].orEmpty()
                HourRow(hour, group.sumOf(::gross), group.size)
            }
            mapOf("rows" to rows)
        }
        call.respond(response)
    }

    get("/reports/tables") {
        val ctx = reportCtx(call)
        val response = transaction {
            val closed = closedChecks(ctx)
            // zone and table ids repeat across stores: group within a store
            val byZone = closed.groupBy { it[Checks.venueId] to it[Checks.zoneId] }.map { (key, group) ->
                val first = group.first()
                ZoneRow(
                    key.second, first[Checks.zoneNameFr], first[Checks.zoneNameEn],
                    group.sumOf(::gross), group.size, key.first,
                )
            }.sortedByDescending { it.grossCents }
            val byTable = closed.groupBy { it[Checks.venueId] to it[Checks.tableId] }.map { (key, group) ->
                val first = group.first()
                TableRow(
                    first[Checks.zoneId], first[Checks.zoneNameEn], key.second, first[Checks.tableLabel],
                    group.sumOf(::gross), group.size, key.first,
                )
            }.sortedByDescending { it.grossCents }
            TablesResponse(byZone, byTable)
        }
        call.respond(response)
    }

    get("/reports/exceptions") {
        val ctx = reportCtx(call)
        val response = transaction {
            val voids = voidChecks(ctx).sortedByDescending { it[Checks.closedAt] }
            ExceptionsResponse(
                voids = voids.map {
                    val venueId = it[Checks.venueId]
                    VoidRow(
                        it[Checks.checkId], ctx.iso(it[Checks.closedAt], venueId), it[Checks.tableLabel],
                        gross(it), it[Checks.voidReason], it[Checks.voidedBy], venueId,
                    )
                },
                voidCount = voids.size,
                voidAmountCents = voids.sumOf(::gross),
                corkageCents = closedChecks(ctx).sumOf { it[Checks.corkageCents] ?: 0 },
            )
        }
        call.respond(response)
    }

    get("/reports/shifts") {
        val ctx = reportCtx(call)
        val response = transaction {
            val rows = Shifts.selectAll().where {
                (Shifts.tenantId eq ctx.tenantId) and anyOf(ctx.venues.map { v ->
                    (Shifts.venueId eq v.id) and (
                        ((Shifts.openedAt greaterEq v.start) and (Shifts.openedAt less v.end)) or
                            (Shifts.status eq "OPEN"))
                })
            }.orderBy(Shifts.openedAt to SortOrder.DESC_NULLS_LAST, Shifts.shiftId to SortOrder.DESC)
                .map { shiftDto(it, ctx) }
            ShiftsResponse(rows)
        }
        call.respond(response)
    }

    /** One shift. Shift ids repeat across stores, so an all-stores scope must name the store. */
    get("/reports/shifts/{id}") {
        val ctx = reportCtx(call)
        val id = call.parameters["id"]?.toLongOrNull()
            ?: throw BadRequestException("shift id must be a number")
        val venue = ctx.venues.singleOrNull()
            ?: throw BadRequestException("pick a store (?venue=) to open a shift", "venue_required")
        val response = transaction {
            Shifts.selectAll().where {
                (Shifts.tenantId eq ctx.tenantId) and (Shifts.venueId eq venue.id) and (Shifts.shiftId eq id)
            }.firstOrNull()?.let { shiftDto(it, ctx) }
        } ?: throw NotFoundException("shift $id not found")
        call.respond(response)
    }

    get("/reports/journal") {
        val ctx = reportCtx(call)
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0).coerceAtLeast(0)
        val q = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotEmpty() }
        val response = transaction {
            val condition: SqlExpressionBuilder.() -> Op<Boolean> = {
                var op = checksInRange(ctx) and (Checks.status inList listOf("CLOSED", "VOID"))
                if (q != null) {
                    var match: Op<Boolean> =
                        Checks.tableLabel.lowerCase() like "%${q.lowercase().replace("%", "\\%")}%"
                    q.toIntOrNull()?.let { match = match or (Checks.checkId eq it) }
                    // exact CAD amount → cents: query-param conversion, not report math
                    runCatching { BigDecimal(q).movePointRight(2).longValueExact() }.getOrNull()
                        ?.let { match = match or (Checks.grandTotalCents eq it) }
                    op = op and match
                }
                op
            }
            val total = Checks.selectAll().where(condition).count()
            val page = Checks.selectAll().where(condition)
                .orderBy(Checks.closedAt to SortOrder.DESC, Checks.venueId to SortOrder.ASC)
                .limit(limit).offset(offset)
                .toList()
            val linesByCheck = linesOf(ctx, page).groupBy { it[CheckLines.venueId] to it[CheckLines.checkId] }
            val tendersByCheck = tendersOf(ctx, page).groupBy { it[CheckTenders.venueId] to it[CheckTenders.checkId] }
            JournalResponse(
                total = total,
                rows = page.map { check ->
                    val venueId = check[Checks.venueId]
                    val key = venueId to check[Checks.checkId]
                    JournalRow(
                        checkId = check[Checks.checkId],
                        status = check[Checks.status],
                        closedAt = ctx.iso(check[Checks.closedAt], venueId),
                        tableLabel = check[Checks.tableLabel],
                        zoneNameEn = check[Checks.zoneNameEn],
                        grandTotalCents = gross(check),
                        taxIncludedCents = checkTax(check),
                        tenderTypes = tendersByCheck[key].orEmpty().map { it[CheckTenders.type] }.distinct(),
                        lines = linesByCheck[key].orEmpty().map {
                            JournalLine(
                                it[CheckLines.nameFr] ?: it[CheckLines.displayName],
                                it[CheckLines.nameEn] ?: it[CheckLines.displayName],
                                it[CheckLines.qty], it[CheckLines.unitPriceCents],
                                it[CheckLines.lineTotalCents],
                            )
                        },
                        venueId = venueId,
                    )
                },
            )
        }
        call.respond(response)
    }
}

// --- helpers (call inside a transaction) ---

/** (venue, category) → (fr, en); a combined view falls back to any store's names for the id. */
private class CategoryNames(
    private val byVenue: Map<Pair<String, String>, Pair<String, String>>,
    private val anyVenue: Map<String, Pair<String, String>>,
) {
    fun lookup(venueId: String, categoryId: String?): Pair<String, String>? =
        categoryId?.let { byVenue[venueId to it] ?: anyVenue[it] }
}

private fun categoryNames(ctx: ReportCtx): CategoryNames {
    val rows = CatalogCategories.selectAll().where {
        (CatalogCategories.tenantId eq ctx.tenantId) and (CatalogCategories.venueId inList ctx.venues.map { it.id })
    }.toList()
    fun names(r: ResultRow) = r[CatalogCategories.nameFr] to r[CatalogCategories.nameEn]
    return CategoryNames(
        rows.associate { (it[CatalogCategories.venueId] to it[CatalogCategories.id]) to names(it) },
        rows.associate { it[CatalogCategories.id] to names(it) },
    )
}

private fun shiftDto(row: ResultRow, ctx: ReportCtx): ShiftDto {
    val venueId = row[Shifts.venueId]
    return ShiftDto(
        shiftId = row[Shifts.shiftId],
        status = row[Shifts.status],
        openedAt = ctx.iso(row[Shifts.openedAt], venueId),
        closedAt = ctx.iso(row[Shifts.closedAt], venueId),
        openedBy = row[Shifts.openedBy],
        closedBy = row[Shifts.closedBy],
        openingFloatCents = row[Shifts.openingFloatCents],
        revenueCents = row[Shifts.revenueCents],
        transactionCount = row[Shifts.transactionCount],
        avgCheckCents = row[Shifts.avgCheckCents],
        corkageCents = row[Shifts.corkageCents],
        tenderBreakdown = row[Shifts.tenderBreakdown]?.let {
            runCatching { lenientJson.decodeFromString<List<TenderTypeRow>>(it) }.getOrNull()
        }.orEmpty(),
        expectedCashCents = row[Shifts.expectedCashCents],
        closingCountCents = row[Shifts.closingCountCents],
        overShortCents = row[Shifts.overShortCents],
        venueId = venueId,
    )
}
