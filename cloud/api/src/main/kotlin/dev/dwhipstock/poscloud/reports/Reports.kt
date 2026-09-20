package dev.dwhipstock.poscloud.reports

import dev.dwhipstock.poscloud.BadRequestException
import dev.dwhipstock.poscloud.NotFoundException
import dev.dwhipstock.poscloud.auth.requirePortal
import dev.dwhipstock.poscloud.catalog.Scope
import dev.dwhipstock.poscloud.db.CashMovements
import dev.dwhipstock.poscloud.db.CatalogCategories
import dev.dwhipstock.poscloud.db.CheckLines
import dev.dwhipstock.poscloud.db.CheckTenders
import dev.dwhipstock.poscloud.db.Checks
import dev.dwhipstock.poscloud.db.Refunds
import dev.dwhipstock.poscloud.db.Shifts
import dev.dwhipstock.poscloud.db.Venues
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
import java.time.ZoneId

/**
 * Report projections over store-computed cents figures. Grep-able invariant:
 * no arithmetic on money beyond SUM/COUNT, net = gross − vat, avg = sum/count.
 * The store decomposed the VAT at sale time; the cloud only adds it up.
 */

private val lenientJson = Json { ignoreUnknownKeys = true }
private val log = LoggerFactory.getLogger("reports")

private data class ReportCtx(val scope: Scope, val from: LocalDate, val to: LocalDate)

/** Session → tenant scope + inclusive venue-local date range (default today/today).
 *  Venue-aware since M8: ?venue=<id> picks the venue, default is the primary one. */
private fun reportCtx(call: ApplicationCall): ReportCtx {
    val scope = dev.dwhipstock.poscloud.portalVenueScope(call)
    return transaction {
        val zone = venueZone(scope.tenantId, scope.venueId)
        val (from, to) = resolveRange(call, zone)
        ReportCtx(scope, from, to)
    }
}

/** The venue's timezone (falls back to the system default), inside a transaction. */
private fun venueZone(tenantId: String, venueId: String): ZoneId {
    val venue = Venues.selectAll().where {
        (Venues.tenantId eq tenantId) and (Venues.id eq venueId)
    }.first()
    return runCatching { ZoneId.of(venue[Venues.timezone]) }.getOrDefault(ZoneId.systemDefault())
}

/** ?from/?to (default today/today in [zone]); shared by reportCtx and /reports/by-venue
 *  so every report family resolves and validates the range identically. */
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

private fun SqlExpressionBuilder.inRange(ctx: ReportCtx): Op<Boolean> =
    (Checks.tenantId eq ctx.scope.tenantId) and (Checks.venueId eq ctx.scope.venueId) and
        (Checks.closedAt greaterEq ctx.from.atStartOfDay()) and
        (Checks.closedAt less ctx.to.plusDays(1).atStartOfDay())

private fun closedChecks(ctx: ReportCtx): List<ResultRow> =
    Checks.selectAll().where { inRange(ctx) and (Checks.status eq "CLOSED") }.toList()

private fun voidChecks(ctx: ReportCtx): List<ResultRow> =
    Checks.selectAll().where { inRange(ctx) and (Checks.status eq "VOID") }.toList()

private fun gross(row: ResultRow): Long = row[Checks.grandTotalCents] ?: 0
private fun vat(row: ResultRow): Long = row[Checks.taxIncludedCents] ?: 0

/**
 * Refunds posted in the range, keyed off refund date (a refund is recognised
 * when issued, not when the original bill sold). The store already decomposed
 * gross/net/VAT; the cloud only sums. Netted out of sales + VAT everywhere.
 */
private fun refundsInRange(ctx: ReportCtx): List<ResultRow> =
    Refunds.selectAll().where {
        (Refunds.tenantId eq ctx.scope.tenantId) and (Refunds.venueId eq ctx.scope.venueId) and
            (Refunds.createdAt greaterEq ctx.from.atStartOfDay()) and
            (Refunds.createdAt less ctx.to.plusDays(1).atStartOfDay())
    }.toList()

private fun rGross(row: ResultRow): Long = row[Refunds.grossCents] ?: 0
private fun rNet(row: ResultRow): Long = row[Refunds.netCents] ?: 0
private fun rVat(row: ResultRow): Long = row[Refunds.taxIncludedCents] ?: 0

/**
 * A closed check with a grand total but no decomposed VAT is an incomplete
 * projection — it counts toward gross while contributing 0 VAT, so VAT/net
 * undercount. The cloud can't fabricate the tax (store is authoritative), but
 * it must not undercount *silently*: log the count so the gap is visible until
 * the store's report backfill lands. Post-backfill this is always 0.
 */
private fun warnIfUndercounting(ctx: ReportCtx, closed: List<ResultRow>) {
    val incomplete = closed.count { (it[Checks.grandTotalCents] ?: 0L) > 0L && it[Checks.taxIncludedCents] == null }
    if (incomplete > 0) log.warn(
        "reports[${ctx.scope.tenantId}/${ctx.scope.venueId}] ${ctx.from}..${ctx.to}: $incomplete closed " +
            "check(s) carry a grand total but no decomposed VAT — VAT/net undercount until the store's " +
            "report backfill reaches the cloud")
}

@Serializable
data class DayRow(
    val date: String, val grossCents: Long, val netCents: Long,
    val vatCents: Long, val checkCount: Int)

/** Per day: closed sales less refunds issued that day, so days sum to the totals. */
private fun byDay(closed: List<ResultRow>, refunds: List<ResultRow>): List<DayRow> {
    val closedByDay = closed.groupBy { it[Checks.closedAt]!!.toLocalDate() }
    val refundByDay = refunds.groupBy { it[Refunds.createdAt]!!.toLocalDate() }
    return (closedByDay.keys + refundByDay.keys).toSortedSet().map { date ->
        val crows = closedByDay[date].orEmpty()
        val rrows = refundByDay[date].orEmpty()
        val g = crows.sumOf(::gross) - rrows.sumOf(::rGross)
        val v = crows.sumOf(::vat) - rrows.sumOf(::rVat)
        DayRow(date.toString(), g, g - v, v, crows.size)
    }
}

@Serializable
data class SummaryResponse(
    val grossCents: Long, val netCents: Long, val vatCents: Long,
    val checkCount: Int, val avgCheckCents: Long,
    val voidCount: Int, val voidAmountCents: Long,
    val refundCount: Int, val refundAmountCents: Long,
    val corkageCents: Long, val serviceChargeCents: Long,
    val byDay: List<DayRow>)

@Serializable
data class VenueSummaryRow(val venueId: String, val venueName: String, val grossCents: Long, val checkCount: Int)

@Serializable
data class ByVenueResponse(val venues: List<VenueSummaryRow>, val grossCents: Long, val checkCount: Int)

@Serializable
data class VatResponse(val ratePercent: Int, val rows: List<DayRow>, val totals: VatTotals)

@Serializable
data class VatTotals(val grossCents: Long, val netCents: Long, val vatCents: Long, val checkCount: Int)

@Serializable
data class PaymentRow(val type: String, val amountCents: Long, val count: Int)

@Serializable
data class PaymentsResponse(val rows: List<PaymentRow>, val totalCents: Long)

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
    val grossCents: Long, val checkCount: Int)

@Serializable
data class TableRow(
    val zoneId: String?, val zoneNameEn: String?, val tableId: String?, val tableLabel: String?,
    val grossCents: Long, val checkCount: Int)

@Serializable
data class TablesResponse(val byZone: List<ZoneRow>, val byTable: List<TableRow>)

@Serializable
data class VoidRow(
    val checkId: Int, val voidedAt: String?, val tableLabel: String?,
    val amountCents: Long, val reason: String?, val voidedBy: String?)

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
    val expectedCashCents: Long?, val closingCountCents: Long?, val overShortCents: Long?)

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
    val tenderTypes: List<String>, val lines: List<JournalLine>)

@Serializable
data class JournalResponse(val total: Long, val rows: List<JournalRow>)

@Serializable
data class RefundReasonRow(
    val reason: String, val count: Int,
    val grossCents: Long, val netCents: Long, val vatCents: Long)

@Serializable
data class RefundListRow(
    val refundId: Long, val checkId: Int?, val createdAt: String?,
    val tableLabel: String?, val tenderType: String?, val reason: String?,
    val grossCents: Long, val netCents: Long, val vatCents: Long)

@Serializable
data class RefundsResponse(
    val count: Int, val grossCents: Long, val netCents: Long, val vatCents: Long,
    val byReason: List<RefundReasonRow>, val byTender: List<TenderTypeRow>,
    val rows: List<RefundListRow>)

@Serializable
data class CashMovementListRow(
    val movementId: Long, val createdAt: String?, val direction: String,
    val amountCents: Long, val reason: String?, val user: String?)

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
            val vatTotal = closed.sumOf(::vat) - refunds.sumOf(::rVat)
            SummaryResponse(
                grossCents = grossTotal,
                netCents = grossTotal - vatTotal,
                vatCents = vatTotal,
                checkCount = closed.size,
                avgCheckCents = if (closed.isEmpty()) 0 else closedGross / closed.size,
                voidCount = voids.size,
                voidAmountCents = voids.sumOf(::gross),
                refundCount = refunds.size,
                refundAmountCents = refunds.sumOf(::rGross),
                corkageCents = closed.sumOf { it[Checks.corkageCents] ?: 0 },
                serviceChargeCents = closed.sumOf { it[Checks.serviceChargeCents] ?: 0 },
                byDay = byDay(closed, refunds),
            )
        }
        call.respond(response)
    }

    /**
     * Group/combined view (M8): one row per venue of the tenant over the same
     * date range, each interpreted in that venue's own timezone. Same arithmetic
     * as /reports/summary's headline: gross net of refunds, SUM/COUNT only.
     */
    get("/reports/by-venue") {
        val principal = requirePortal(call)
        val response = transaction {
            val venues = Venues.selectAll().where { Venues.tenantId eq principal.tenantId }
                .orderBy(Venues.id).toList()
            if (venues.isEmpty()) throw NotFoundException("no venue for tenant")
            val rows = venues.map { v ->
                // each venue interpreted in ITS OWN timezone, but the same shared
                // range resolution/validation as every other report family
                val zone = runCatching { ZoneId.of(v[Venues.timezone]) }.getOrDefault(ZoneId.systemDefault())
                val (from, to) = resolveRange(call, zone)
                val ctx = ReportCtx(Scope(principal.tenantId, v[Venues.id]), from, to)
                val closed = closedChecks(ctx)
                val refunds = refundsInRange(ctx)
                VenueSummaryRow(
                    venueId = v[Venues.id],
                    venueName = v[Venues.name],
                    grossCents = closed.sumOf(::gross) - refunds.sumOf(::rGross),
                    checkCount = closed.size,
                )
            }
            ByVenueResponse(rows, rows.sumOf { it.grossCents }, rows.sumOf { it.checkCount })
        }
        call.respond(response)
    }

    get("/reports/vat") {
        val ctx = reportCtx(call)
        val response = transaction {
            val closed = closedChecks(ctx)
            val refunds = refundsInRange(ctx)
            warnIfUndercounting(ctx, closed)
            val grossTotal = closed.sumOf(::gross) - refunds.sumOf(::rGross)
            val vatTotal = closed.sumOf(::vat) - refunds.sumOf(::rVat)
            VatResponse(
                ratePercent = 13,
                rows = byDay(closed, refunds),
                totals = VatTotals(grossTotal, grossTotal - vatTotal, vatTotal, closed.size),
            )
        }
        call.respond(response)
    }

    get("/reports/refunds") {
        val ctx = reportCtx(call)
        val response = transaction {
            val refunds = refundsInRange(ctx)
            val byReason = refunds.groupBy { it[Refunds.reason] ?: "—" }.map { (reason, rows) ->
                RefundReasonRow(reason, rows.size, rows.sumOf(::rGross), rows.sumOf(::rNet), rows.sumOf(::rVat))
            }.sortedByDescending { it.grossCents }
            val byTender = refunds.groupBy { it[Refunds.tenderType] ?: "CASH" }.map { (type, rows) ->
                TenderTypeRow(type, rows.sumOf(::rGross), rows.size)
            }.sortedByDescending { it.amountCents }
            val rows = refunds.sortedByDescending { it[Refunds.createdAt] }.map { r ->
                RefundListRow(
                    r[Refunds.refundId], r[Refunds.checkId], r[Refunds.createdAt]?.toString(),
                    r[Refunds.tableLabel], r[Refunds.tenderType], r[Refunds.reason],
                    rGross(r), rNet(r), rVat(r))
            }
            RefundsResponse(
                refunds.size, refunds.sumOf(::rGross), refunds.sumOf(::rNet), refunds.sumOf(::rVat),
                byReason, byTender, rows)
        }
        call.respond(response)
    }

    get("/reports/cash-movements") {
        val ctx = reportCtx(call)
        val response = transaction {
            val movements = CashMovements.selectAll().where {
                (CashMovements.tenantId eq ctx.scope.tenantId) and
                    (CashMovements.venueId eq ctx.scope.venueId) and
                    (CashMovements.createdAt greaterEq ctx.from.atStartOfDay()) and
                    (CashMovements.createdAt less ctx.to.plusDays(1).atStartOfDay())
            }.toList()
            val ins = movements.filter { it[CashMovements.direction] == "IN" }
            val outs = movements.filter { it[CashMovements.direction] == "OUT" }
            val paidIn = ins.sumOf { it[CashMovements.amountCents] ?: 0 }
            val paidOut = outs.sumOf { it[CashMovements.amountCents] ?: 0 }
            val rows = movements.sortedByDescending { it[CashMovements.createdAt] }.map { m ->
                CashMovementListRow(
                    m[CashMovements.movementId], m[CashMovements.createdAt]?.toString(),
                    m[CashMovements.direction] ?: "", m[CashMovements.amountCents] ?: 0,
                    m[CashMovements.reason], m[CashMovements.createdBy])
            }
            CashMovementsResponse(paidIn, paidOut, paidIn - paidOut, ins.size, outs.size, rows)
        }
        call.respond(response)
    }

    get("/reports/payments") {
        val ctx = reportCtx(call)
        val response = transaction {
            val checkIds = closedChecks(ctx).map { it[Checks.checkId] }
            val tenders = if (checkIds.isEmpty()) emptyList() else CheckTenders.selectAll().where {
                (CheckTenders.tenantId eq ctx.scope.tenantId) and
                    (CheckTenders.venueId eq ctx.scope.venueId) and (CheckTenders.checkId inList checkIds)
            }.toList()
            val rows = tenders.groupBy { it[CheckTenders.type] }.map { (type, group) ->
                PaymentRow(type, group.sumOf { it[CheckTenders.amountAppliedCents] ?: 0 }, group.size)
            }.sortedByDescending { it.amountCents }
            PaymentsResponse(rows, rows.sumOf { it.amountCents })
        }
        call.respond(response)
    }

    get("/reports/items") {
        val ctx = reportCtx(call)
        val response = transaction {
            val categoryNames = categoryNames(ctx.scope)
            val rows = linesOfClosedChecks(ctx)
                .groupBy { it[CheckLines.itemId] }
                .map { (itemId, group) ->
                    val first = group.first()
                    ItemRow(
                        itemId = itemId,
                        nameFr = if (itemId == null) "Open item" else first[CheckLines.nameFr],
                        nameEn = if (itemId == null) "Open item" else first[CheckLines.nameEn],
                        categoryId = first[CheckLines.categoryId],
                        categoryNameFr = categoryNames[first[CheckLines.categoryId]]?.first,
                        categoryNameEn = categoryNames[first[CheckLines.categoryId]]?.second,
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
            val categoryNames = categoryNames(ctx.scope)
            val rows = linesOfClosedChecks(ctx)
                .groupBy { it[CheckLines.categoryId] }
                .map { (categoryId, group) ->
                    CategoryRow(
                        categoryId = categoryId,
                        nameFr = if (categoryId == null) "Open item" else categoryNames[categoryId]?.first,
                        nameEn = if (categoryId == null) "Open item" else categoryNames[categoryId]?.second,
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
            val byHour = closedChecks(ctx).groupBy { it[Checks.closedAt]!!.hour }
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
            val byZone = closed.groupBy { it[Checks.zoneId] }.map { (zoneId, group) ->
                val first = group.first()
                ZoneRow(
                    zoneId, first[Checks.zoneNameFr], first[Checks.zoneNameEn],
                    group.sumOf(::gross), group.size,
                )
            }.sortedByDescending { it.grossCents }
            val byTable = closed.groupBy { it[Checks.tableId] }.map { (tableId, group) ->
                val first = group.first()
                TableRow(
                    first[Checks.zoneId], first[Checks.zoneNameEn], tableId, first[Checks.tableLabel],
                    group.sumOf(::gross), group.size,
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
                    VoidRow(
                        it[Checks.checkId], it[Checks.closedAt]?.toString(), it[Checks.tableLabel],
                        gross(it), it[Checks.voidReason], it[Checks.voidedBy],
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
                (Shifts.tenantId eq ctx.scope.tenantId) and (Shifts.venueId eq ctx.scope.venueId) and
                    (((Shifts.openedAt greaterEq ctx.from.atStartOfDay()) and
                        (Shifts.openedAt less ctx.to.plusDays(1).atStartOfDay())) or
                        (Shifts.status eq "OPEN"))
            }.orderBy(Shifts.shiftId, SortOrder.DESC).map(::shiftDto)
            ShiftsResponse(rows)
        }
        call.respond(response)
    }

    get("/reports/shifts/{id}") {
        val ctx = reportCtx(call)
        val id = call.parameters["id"]?.toLongOrNull()
            ?: throw BadRequestException("shift id must be a number")
        val response = transaction {
            Shifts.selectAll().where {
                (Shifts.tenantId eq ctx.scope.tenantId) and (Shifts.venueId eq ctx.scope.venueId) and
                    (Shifts.shiftId eq id)
            }.firstOrNull()?.let(::shiftDto)
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
                var op = inRange(ctx) and (Checks.status inList listOf("CLOSED", "VOID"))
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
                .orderBy(Checks.closedAt, SortOrder.DESC)
                .limit(limit).offset(offset)
                .toList()
            val checkIds = page.map { it[Checks.checkId] }
            val linesByCheck = if (checkIds.isEmpty()) emptyMap() else CheckLines.selectAll().where {
                (CheckLines.tenantId eq ctx.scope.tenantId) and (CheckLines.venueId eq ctx.scope.venueId) and
                    (CheckLines.checkId inList checkIds)
            }.groupBy { it[CheckLines.checkId] }
            val tendersByCheck = if (checkIds.isEmpty()) emptyMap() else CheckTenders.selectAll().where {
                (CheckTenders.tenantId eq ctx.scope.tenantId) and (CheckTenders.venueId eq ctx.scope.venueId) and
                    (CheckTenders.checkId inList checkIds)
            }.groupBy { it[CheckTenders.checkId] }
            JournalResponse(
                total = total,
                rows = page.map { check ->
                    val checkId = check[Checks.checkId]
                    JournalRow(
                        checkId = checkId,
                        status = check[Checks.status],
                        closedAt = check[Checks.closedAt]?.toString(),
                        tableLabel = check[Checks.tableLabel],
                        zoneNameEn = check[Checks.zoneNameEn],
                        grandTotalCents = gross(check),
                        taxIncludedCents = vat(check),
                        tenderTypes = tendersByCheck[checkId].orEmpty()
                            .map { it[CheckTenders.type] }.distinct(),
                        lines = linesByCheck[checkId].orEmpty().map {
                            JournalLine(
                                it[CheckLines.nameFr] ?: it[CheckLines.displayName],
                                it[CheckLines.nameEn] ?: it[CheckLines.displayName],
                                it[CheckLines.qty], it[CheckLines.unitPriceCents],
                                it[CheckLines.lineTotalCents],
                            )
                        },
                    )
                },
            )
        }
        call.respond(response)
    }
}

// --- helpers (call inside a transaction) ---

private fun linesOfClosedChecks(ctx: ReportCtx): List<ResultRow> {
    val checkIds = closedChecks(ctx).map { it[Checks.checkId] }
    if (checkIds.isEmpty()) return emptyList()
    return CheckLines.selectAll().where {
        (CheckLines.tenantId eq ctx.scope.tenantId) and (CheckLines.venueId eq ctx.scope.venueId) and
            (CheckLines.checkId inList checkIds)
    }.toList()
}

private fun categoryNames(scope: Scope): Map<String, Pair<String, String>> =
    CatalogCategories.selectAll().where {
        (CatalogCategories.tenantId eq scope.tenantId) and (CatalogCategories.venueId eq scope.venueId)
    }.associate {
        it[CatalogCategories.id] to (it[CatalogCategories.nameFr] to it[CatalogCategories.nameEn])
    }

private fun shiftDto(row: ResultRow) = ShiftDto(
    shiftId = row[Shifts.shiftId],
    status = row[Shifts.status],
    openedAt = row[Shifts.openedAt]?.toString(),
    closedAt = row[Shifts.closedAt]?.toString(),
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
)
