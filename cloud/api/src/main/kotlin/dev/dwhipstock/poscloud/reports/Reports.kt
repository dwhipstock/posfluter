package dev.dwhipstock.poscloud.reports

import dev.dwhipstock.poscloud.BadRequestException
import dev.dwhipstock.poscloud.CloudTime
import dev.dwhipstock.poscloud.Fx
import dev.dwhipstock.poscloud.MoneyScope
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
import dev.dwhipstock.poscloud.reportingCurrencyOf
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
 *
 * Multi-currency: each store sells in its own currency and per-store figures
 * are always exact, in that currency (`currency` on every per-store row).
 * Money is never added across currencies: when the scope spans several, the
 * combined figures are per-currency sums each converted into the tenant's
 * reporting currency at a fixed configured rate and flagged approximate
 * (`money`, a [MoneyScope]), and `byCurrency` carries the exact totals.
 */

private val lenientJson = Json { ignoreUnknownKeys = true }
private val log = LoggerFactory.getLogger("reports")

/** One store over the requested dates, as instants of its own business days. */
private class VenueRange(val venue: VenueScope, val from: LocalDate, val to: LocalDate) {
    val id: String get() = venue.venueId
    val zone: ZoneId get() = venue.zone
    val currency: String get() = venue.currency
    val start: OffsetDateTime = CloudTime.startOfDay(from, zone)
    val end: OffsetDateTime = CloudTime.startOfDay(to.plusDays(1), zone)
}

private class ReportCtx(
    val tenantId: String,
    val venues: List<VenueRange>,
    val reporting: String,
    val fx: Fx.Rates,
) {
    private val byId = venues.associateBy { it.id }
    fun zoneOf(venueId: String): ZoneId = byId[venueId]?.zone ?: CloudTime.zone(null)
    fun iso(t: OffsetDateTime?, venueId: String): String? = t?.let { CloudTime.iso(it, zoneOf(venueId)) }
    val label: String get() = "$tenantId/${venues.joinToString(",") { it.id }} ${venues.first().from}..${venues.first().to}"

    fun currencyOf(venueId: String): String = byId[venueId]?.currency ?: "CAD"

    /** The distinct currencies in scope, sorted (reporting currency first). */
    val currencies: List<String> = venues.map { it.currency }.distinct()
        .sortedWith(compareBy({ it != reporting }, { it }))
    val mixed: Boolean get() = currencies.size > 1

    /** What the combined figures are in: the scope's one currency, else the reporting one. */
    val currency: String get() = if (mixed) reporting else currencies.single()

    private val convertible: Boolean = currencies.all { fx.rate(it, reporting) != null }

    fun money(): MoneyScope = MoneyScope(
        currency = currency,
        approximate = mixed,
        reportingCurrency = reporting,
        currencies = currencies,
        rates = if (!mixed) emptyList() else currencies.filter { it != reporting }.mapNotNull { c ->
            fx.rate(c, reporting)?.let { Fx.RateDto(c, reporting, it.stripTrailingZeros().toPlainString()) }
        },
        convertible = !mixed || convertible,
    )

    /**
     * [value] summed over [rows] as ONE figure in [currency]: a plain sum when
     * the scope has one currency; otherwise each currency's exact sum converted
     * into the reporting currency, then added (approximate). A currency with no
     * configured rate contributes 0 there — its exact total is in `byCurrency`.
     */
    fun <R> total(rows: List<R>, venueOf: (R) -> String, value: (R) -> Long): Long {
        if (!mixed) return rows.sumOf(value)
        return rows.groupBy { currencyOf(venueOf(it)) }.entries.sumOf { (c, group) ->
            fx.convert(group.sumOf(value), c, reporting) ?: 0L
        }
    }

    /** One figure (e.g. a row's revenue) in [currency]: converted when mixed. */
    fun one(venueId: String, cents: Long): Long =
        if (!mixed) cents else fx.convert(cents, currencyOf(venueId), reporting) ?: 0L

    /** The same request narrowed to the stores selling in [currency]. */
    fun only(currency: String): ReportCtx = ReportCtx(tenantId, venues.filter { it.currency == currency }, reporting, fx)

    fun venueIds(): Set<String> = venues.map { it.id }.toSet()
}

// combined sums of each row kind, in the scope's currency
private fun ReportCtx.checks(rows: List<ResultRow>, value: (ResultRow) -> Long) = total(rows, { it[Checks.venueId] }, value)
private fun ReportCtx.refunds(rows: List<ResultRow>, value: (ResultRow) -> Long) = total(rows, { it[Refunds.venueId] }, value)
private fun ReportCtx.lines(rows: List<ResultRow>, value: (ResultRow) -> Long) = total(rows, { it[CheckLines.venueId] }, value)
private fun ReportCtx.tenders(rows: List<ResultRow>, value: (ResultRow) -> Long) = total(rows, { it[CheckTenders.venueId] }, value)
private fun ReportCtx.cash(rows: List<ResultRow>, value: (ResultRow) -> Long) = total(rows, { it[CashMovements.venueId] }, value)

/** Session → the in-scope stores + inclusive business-day range (default: each store's today). */
private fun reportCtx(call: ApplicationCall, fx: Fx.Rates): ReportCtx {
    val (principal, venues) = portalScopes(call)
    val reporting = transaction { reportingCurrencyOf(principal.tenantId) }
    return ReportCtx(principal.tenantId, venues.map { v ->
        val (from, to) = resolveRange(call, v.zone)
        VenueRange(v, from, to)
    }, reporting, fx)
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
// per-tax amounts as the store charged them; a sale without a breakdown counts 0
private fun checkGst(row: ResultRow): Long = row[Checks.gstCents] ?: 0
private fun checkQst(row: ResultRow): Long = row[Checks.qstCents] ?: 0

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
private fun refundGst(row: ResultRow): Long = row[Refunds.gstCents] ?: 0
private fun refundQst(row: ResultRow): Long = row[Refunds.qstCents] ?: 0

/** GST and QST of closed sales less refunds: (gst, qst). Canadian stores only, so CAD. */
private fun gstQst(ctx: ReportCtx, closed: List<ResultRow>, refunds: List<ResultRow>): Pair<Long, Long> =
    (ctx.checks(closed, ::checkGst) - ctx.refunds(refunds, ::refundGst)) to
        (ctx.checks(closed, ::checkQst) - ctx.refunds(refunds, ::refundQst))

/**
 * The taxes the in-scope sales were charged at, as the store stamped them
 * (code, labels, rate) — no rate is assumed here. Empty when no sale in the
 * range carries a breakdown.
 */
private fun taxRates(closed: List<ResultRow>): List<TaxRateRow> =
    closed.mapNotNull { row -> row[Checks.taxes]?.let { row to it } }
        .flatMap { (row, json) ->
            runCatching { lenientJson.decodeFromString<List<TaxRateRow>>(json) }.getOrDefault(emptyList())
                .map { it.copy(currency = row[Checks.currency] ?: "") }
        }
        .distinctBy { Triple(it.code, it.ratePercent, it.currency) }
        .sortedWith(compareBy({ it.code }, { it.ratePercent }))

@Serializable
private data class TaxAmount(val code: String, val labelFr: String = "", val labelEn: String = "",
                             val ratePercent: String = "", val amountCents: Long = 0)

private fun taxesOf(json: String?): List<TaxAmount> =
    json?.let { runCatching { lenientJson.decodeFromString<List<TaxAmount>>(it) }.getOrNull() }.orEmpty()

/** A refund's reversed taxes: its breakdown, else (older rows) its GST / QST columns. */
private fun refundTaxes(row: ResultRow): List<TaxAmount> =
    row[Refunds.taxes]?.let(::taxesOf) ?: listOfNotNull(
        row[Refunds.gstCents]?.let { TaxAmount("GST", amountCents = it) },
        row[Refunds.qstCents]?.let { TaxAmount("QST", amountCents = it) },
    )

/**
 * Every tax code charged in scope, per currency, exact: the sales' breakdowns
 * less the refunds'. A US sales tax and a Québec QST sit side by side, each in
 * its own currency — never summed together.
 */
private fun taxCodeTotals(ctx: ReportCtx, closed: List<ResultRow>, refunds: List<ResultRow>): List<TaxCodeRow> {
    data class Key(val code: String, val currency: String)
    val labels = mutableMapOf<Key, TaxAmount>()
    val sums = linkedMapOf<Key, Long>()
    closed.forEach { row ->
        val c = ctx.currencyOf(row[Checks.venueId])
        taxesOf(row[Checks.taxes]).forEach { t ->
            val k = Key(t.code, c)
            labels.putIfAbsent(k, t)
            sums[k] = (sums[k] ?: 0L) + t.amountCents
        }
    }
    refunds.forEach { row ->
        val c = ctx.currencyOf(row[Refunds.venueId])
        refundTaxes(row).forEach { t ->
            val k = Key(t.code, c)
            if (t.labelEn.isNotEmpty()) labels.putIfAbsent(k, t)
            sums[k] = (sums[k] ?: 0L) - t.amountCents
        }
    }
    return sums.map { (k, cents) ->
        val l = labels[k]
        TaxCodeRow(k.code, l?.labelFr.orEmpty(), l?.labelEn.orEmpty(), l?.ratePercent.orEmpty(), k.currency, cents)
    }.sortedWith(compareBy({ ctx.currencies.indexOf(it.currency) }, { it.code }))
}

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
    val taxCents: Long, val checkCount: Int,
    /** The same day split by store (one entry per in-scope store, zeros included). Exact, in each store's currency. */
    val byVenue: List<VenueDayRow> = emptyList(),
    /** GST and QST inside [taxCents], as charged (0 for sales without a breakdown). */
    val gstCents: Long = 0, val qstCents: Long = 0)

@Serializable
data class VenueDayRow(
    val venueId: String, val grossCents: Long, val netCents: Long, val taxCents: Long, val checkCount: Int,
    val gstCents: Long = 0, val qstCents: Long = 0, val currency: String = "CAD")

/** Per business day (each row's own store zone): closed sales less refunds issued that day. */
private fun byDay(ctx: ReportCtx, closed: List<ResultRow>, refunds: List<ResultRow>): List<DayRow> {
    val closedByDay = closed.groupBy { CloudTime.localDate(it[Checks.closedAt]!!, ctx.zoneOf(it[Checks.venueId])) }
    val refundByDay = refunds.groupBy { CloudTime.localDate(it[Refunds.createdAt]!!, ctx.zoneOf(it[Refunds.venueId])) }
    return (closedByDay.keys + refundByDay.keys).toSortedSet().map { date ->
        val crows = closedByDay[date].orEmpty()
        val rrows = refundByDay[date].orEmpty()
        val g = ctx.checks(crows, ::gross) - ctx.refunds(rrows, ::rGross)
        val v = ctx.checks(crows, ::checkTax) - ctx.refunds(rrows, ::refundTax)
        val cBy = crows.groupBy { it[Checks.venueId] }
        val rBy = rrows.groupBy { it[Refunds.venueId] }
        val (gst, qst) = gstQst(ctx, crows, rrows)
        DayRow(date.toString(), g, g - v, v, crows.size, ctx.venues.map { venue ->
            val vc = cBy[venue.id].orEmpty()
            val vr = rBy[venue.id].orEmpty()
            val vg = vc.sumOf(::gross) - vr.sumOf(::rGross)
            val vt = vc.sumOf(::checkTax) - vr.sumOf(::refundTax)
            val vgst = vc.sumOf(::checkGst) - vr.sumOf(::refundGst)
            val vqst = vc.sumOf(::checkQst) - vr.sumOf(::refundQst)
            VenueDayRow(venue.id, vg, vg - vt, vt, vc.size, vgst, vqst, venue.currency)
        }, gst, qst)
    }
}

/** One store's headline figures — the per-store comparison in combined views. Exact, in [currency]. */
@Serializable
data class VenueSummaryRow(
    val venueId: String, val venueName: String,
    val grossCents: Long, val netCents: Long, val taxCents: Long,
    val checkCount: Int, val avgCheckCents: Long,
    val voidCount: Int, val refundAmountCents: Long,
    val gstCents: Long = 0, val qstCents: Long = 0,
    val currency: String = "CAD",
    /** Every tax code the store charged (sales less refunds), exact. */
    val taxes: List<TaxCodeRow> = emptyList())

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
            gstCents = c.sumOf(::checkGst) - r.sumOf(::refundGst),
            qstCents = c.sumOf(::checkQst) - r.sumOf(::refundQst),
            currency = v.currency,
            taxes = taxCodeTotals(ctx, c, r),
        )
    }
}

/** Exact headline figures of the stores selling in one currency (the "All stores" per-currency rows). */
@Serializable
data class CurrencySummaryRow(
    val currency: String, val venueIds: List<String>,
    val grossCents: Long, val netCents: Long, val taxCents: Long,
    val checkCount: Int, val avgCheckCents: Long,
    val voidCount: Int, val voidAmountCents: Long,
    val refundCount: Int, val refundAmountCents: Long,
    /** [grossCents] in the reporting currency at the fixed rate; null = no rate configured. */
    val grossReportingCents: Long? = null)

private fun currencySummaries(
    ctx: ReportCtx, closed: List<ResultRow>, voids: List<ResultRow>, refunds: List<ResultRow>,
): List<CurrencySummaryRow> = ctx.currencies.map { c ->
    val sub = ctx.only(c)
    val ids = sub.venueIds()
    val cl = closed.filter { it[Checks.venueId] in ids }
    val vo = voids.filter { it[Checks.venueId] in ids }
    val re = refunds.filter { it[Refunds.venueId] in ids }
    val closedGross = cl.sumOf(::gross)
    val g = closedGross - re.sumOf(::rGross)
    val tax = cl.sumOf(::checkTax) - re.sumOf(::refundTax)
    CurrencySummaryRow(
        currency = c, venueIds = sub.venues.map { it.id },
        grossCents = g, netCents = g - tax, taxCents = tax,
        checkCount = cl.size, avgCheckCents = if (cl.isEmpty()) 0 else closedGross / cl.size,
        voidCount = vo.size, voidAmountCents = vo.sumOf(::gross),
        refundCount = re.size, refundAmountCents = re.sumOf(::rGross),
        grossReportingCents = ctx.fx.convert(g, c, ctx.reporting),
    )
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
    val byVenue: List<VenueSummaryRow>,
    /** Exact totals per currency (one row unless the scope spans countries). */
    val byCurrency: List<CurrencySummaryRow> = emptyList(),
    val money: MoneyScope? = null)

@Serializable
data class ByVenueResponse(
    val venues: List<VenueSummaryRow>, val grossCents: Long, val checkCount: Int,
    val byCurrency: List<CurrencySummaryRow> = emptyList(), val money: MoneyScope? = null)

@Serializable
data class TaxReportResponse(
    /** The taxes (and rates) the in-range sales were charged, from the store's own breakdown. */
    val rates: List<TaxRateRow>,
    val rows: List<DayRow>, val totals: TaxTotals, val byVenue: List<VenueSummaryRow>,
    /** Every tax code, per currency, exact (sales less refunds). */
    val byTax: List<TaxCodeRow> = emptyList(),
    val byCurrency: List<CurrencySummaryRow> = emptyList(),
    val money: MoneyScope? = null)

@Serializable
data class TaxTotals(
    val grossCents: Long, val netCents: Long, val taxCents: Long, val checkCount: Int,
    val gstCents: Long = 0, val qstCents: Long = 0)

/** One tax as the store labelled it; [ratePercent] is a decimal string ("9.975"). */
@Serializable
data class TaxRateRow(
    val code: String, val labelFr: String = "", val labelEn: String = "", val ratePercent: String = "",
    val currency: String = "")

/** One tax code's net amount in one currency. */
@Serializable
data class TaxCodeRow(
    val code: String, val labelFr: String, val labelEn: String, val ratePercent: String,
    val currency: String, val amountCents: Long)

@Serializable
data class PaymentRow(val type: String, val amountCents: Long, val count: Int)

@Serializable
data class VenuePayments(
    val venueId: String, val totalCents: Long, val rows: List<PaymentRow>, val venueName: String = "",
    val currency: String = "CAD")

/** The payment mix of the stores selling in one currency, exact. */
@Serializable
data class CurrencyPayments(val currency: String, val totalCents: Long, val rows: List<PaymentRow>)

@Serializable
data class PaymentsResponse(
    val rows: List<PaymentRow>, val totalCents: Long, val byVenue: List<VenuePayments>,
    val byCurrency: List<CurrencyPayments> = emptyList(), val money: MoneyScope? = null)

/** One store's share of an item or category (stores that sold none are left out). */
@Serializable
data class VenueQtyRow(val venueId: String, val qty: Int, val revenueCents: Long, val currency: String = "CAD")

/** An item as sold in ONE currency (the same id in two currencies is two rows). Exact. */
@Serializable
data class ItemRow(
    val itemId: String?, val nameFr: String?, val nameEn: String?,
    val categoryId: String?, val categoryNameFr: String?, val categoryNameEn: String?,
    val qty: Int, val revenueCents: Long,
    val byVenue: List<VenueQtyRow> = emptyList(),
    val currency: String = "CAD")

@Serializable
data class CategoryRow(
    val categoryId: String?, val nameFr: String?, val nameEn: String?,
    val qty: Int, val revenueCents: Long,
    val byVenue: List<VenueQtyRow> = emptyList(),
    val currency: String = "CAD")

@Serializable
data class VenueHourRow(val venueId: String, val grossCents: Long, val checkCount: Int, val currency: String = "CAD")

@Serializable
data class HourRow(
    val hour: Int, val grossCents: Long, val checkCount: Int,
    /** One entry per in-scope store, zeros included, so charts can stack by store. */
    val byVenue: List<VenueHourRow> = emptyList())

/** Totals of one store: the per-store split of the items, categories, hourly and tables reports. */
@Serializable
data class VenueTotalRow(
    val venueId: String, val venueName: String, val grossCents: Long, val checkCount: Int, val qty: Int,
    val currency: String = "CAD")

@Serializable
data class ItemsResponse(val rows: List<ItemRow>, val byVenue: List<VenueTotalRow>, val money: MoneyScope? = null)

@Serializable
data class CategoriesResponse(val rows: List<CategoryRow>, val byVenue: List<VenueTotalRow>, val money: MoneyScope? = null)

@Serializable
data class HourlyResponse(val rows: List<HourRow>, val byVenue: List<VenueTotalRow>, val money: MoneyScope? = null)

@Serializable
data class ZoneRow(
    val zoneId: String?, val zoneNameFr: String?, val zoneNameEn: String?,
    val grossCents: Long, val checkCount: Int, val venueId: String, val currency: String = "CAD")

@Serializable
data class TableRow(
    val zoneId: String?, val zoneNameEn: String?, val tableId: String?, val tableLabel: String?,
    val grossCents: Long, val checkCount: Int, val venueId: String, val currency: String = "CAD")

@Serializable
data class TablesResponse(
    val byZone: List<ZoneRow>, val byTable: List<TableRow>, val byVenue: List<VenueTotalRow>,
    val money: MoneyScope? = null)

@Serializable
data class VoidRow(
    val checkId: Int, val voidedAt: String?, val tableLabel: String?,
    val amountCents: Long, val reason: String?, val voidedBy: String?, val venueId: String,
    val currency: String = "CAD")

@Serializable
data class VenueExceptionsRow(
    val venueId: String, val venueName: String, val voidCount: Int, val voidAmountCents: Long, val corkageCents: Long,
    val currency: String = "CAD")

@Serializable
data class ExceptionsResponse(
    val voids: List<VoidRow>, val voidCount: Int, val voidAmountCents: Long, val corkageCents: Long,
    val byVenue: List<VenueExceptionsRow>, val money: MoneyScope? = null)

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
    val venueId: String,
    val currency: String = "CAD")

@Serializable
data class VenueShiftsRow(
    val venueId: String, val venueName: String, val shiftCount: Int, val openCount: Int,
    val revenueCents: Long, val transactionCount: Int, val overShortCents: Long,
    val currency: String = "CAD")

@Serializable
data class ShiftsResponse(val rows: List<ShiftDto>, val byVenue: List<VenueShiftsRow>, val money: MoneyScope? = null)

@Serializable
data class JournalLine(
    val nameFr: String?, val nameEn: String?, val qty: Int,
    val unitPriceCents: Long, val lineTotalCents: Long)

@Serializable
data class JournalRow(
    val checkId: Int, val status: String, val closedAt: String?,
    val tableLabel: String?, val zoneNameEn: String?,
    val grandTotalCents: Long, val taxIncludedCents: Long,
    val tenderTypes: List<String>, val lines: List<JournalLine>, val venueId: String,
    val currency: String = "CAD")

/** One store's share of the journal's filtered checks (the whole range, not just the page). */
@Serializable
data class VenueJournalRow(
    val venueId: String, val venueName: String, val closedCount: Int, val voidCount: Int, val closedCents: Long,
    val currency: String = "CAD")

@Serializable
data class JournalResponse(
    val total: Long, val rows: List<JournalRow>, val byVenue: List<VenueJournalRow>, val money: MoneyScope? = null)

@Serializable
data class RefundReasonRow(
    val reason: String, val count: Int,
    val grossCents: Long, val netCents: Long, val taxCents: Long)

@Serializable
data class RefundListRow(
    val refundId: Long, val checkId: Int?, val createdAt: String?,
    val tableLabel: String?, val tenderType: String?, val reason: String?,
    val grossCents: Long, val netCents: Long, val taxCents: Long, val venueId: String,
    val currency: String = "CAD")

@Serializable
data class VenueRefundsRow(
    val venueId: String, val venueName: String, val count: Int,
    val grossCents: Long, val netCents: Long, val taxCents: Long,
    val currency: String = "CAD")

@Serializable
data class RefundsResponse(
    val count: Int, val grossCents: Long, val netCents: Long, val taxCents: Long,
    val byReason: List<RefundReasonRow>, val byTender: List<TenderTypeRow>,
    val rows: List<RefundListRow>, val byVenue: List<VenueRefundsRow>, val money: MoneyScope? = null)

@Serializable
data class CashMovementListRow(
    val movementId: Long, val createdAt: String?, val direction: String,
    val amountCents: Long, val reason: String?, val user: String?, val venueId: String,
    val currency: String = "CAD")

@Serializable
data class VenueCashRow(
    val venueId: String, val venueName: String, val paidInCents: Long, val paidOutCents: Long,
    val netCents: Long, val inCount: Int, val outCount: Int,
    val currency: String = "CAD")

@Serializable
data class CashMovementsResponse(
    val paidInCents: Long, val paidOutCents: Long, val netCents: Long,
    val inCount: Int, val outCount: Int, val rows: List<CashMovementListRow>,
    val byVenue: List<VenueCashRow>, val money: MoneyScope? = null)

fun Route.reportRoutes(fx: Fx.Rates = Fx.Rates.NONE) {

    get("/reports/summary") {
        val ctx = reportCtx(call, fx)
        val response = transaction {
            val closed = closedChecks(ctx)
            val voids = voidChecks(ctx)
            val refunds = refundsInRange(ctx)
            warnIfUndercounting(ctx, closed)
            val closedGross = ctx.checks(closed, ::gross)
            // net sales after refunds; avg check stays a sale-time figure (pre-refund)
            val grossTotal = closedGross - ctx.refunds(refunds, ::rGross)
            val taxTotal = ctx.checks(closed, ::checkTax) - ctx.refunds(refunds, ::refundTax)
            SummaryResponse(
                grossCents = grossTotal,
                netCents = grossTotal - taxTotal,
                taxCents = taxTotal,
                checkCount = closed.size,
                avgCheckCents = if (closed.isEmpty()) 0 else closedGross / closed.size,
                voidCount = voids.size,
                voidAmountCents = ctx.checks(voids, ::gross),
                refundCount = refunds.size,
                refundAmountCents = ctx.refunds(refunds, ::rGross),
                corkageCents = ctx.checks(closed) { it[Checks.corkageCents] ?: 0 },
                serviceChargeCents = ctx.checks(closed) { it[Checks.serviceChargeCents] ?: 0 },
                byDay = byDay(ctx, closed, refunds),
                byVenue = venueSummaries(ctx, closed, voids, refunds),
                byCurrency = currencySummaries(ctx, closed, voids, refunds),
                money = ctx.money(),
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
        val ctx = reportCtx(call, fx)
        val response = transaction {
            val closed = closedChecks(ctx)
            val voids = voidChecks(ctx)
            val refunds = refundsInRange(ctx)
            val rows = venueSummaries(ctx, closed, voids, refunds)
            ByVenueResponse(
                rows, ctx.total(rows, { it.venueId }, { it.grossCents }), rows.sumOf { it.checkCount },
                currencySummaries(ctx, closed, voids, refunds), ctx.money())
        }
        call.respond(response)
    }

    get("/reports/tax") {
        val ctx = reportCtx(call, fx)
        val response = transaction {
            val closed = closedChecks(ctx)
            val voids = voidChecks(ctx)
            val refunds = refundsInRange(ctx)
            warnIfUndercounting(ctx, closed)
            val grossTotal = ctx.checks(closed, ::gross) - ctx.refunds(refunds, ::rGross)
            val taxTotal = ctx.checks(closed, ::checkTax) - ctx.refunds(refunds, ::refundTax)
            val (gst, qst) = gstQst(ctx, closed, refunds)
            TaxReportResponse(
                rates = taxRates(closed),
                rows = byDay(ctx, closed, refunds),
                totals = TaxTotals(grossTotal, grossTotal - taxTotal, taxTotal, closed.size, gst, qst),
                byVenue = venueSummaries(ctx, closed, voids, refunds),
                byTax = taxCodeTotals(ctx, closed, refunds),
                byCurrency = currencySummaries(ctx, closed, voids, refunds),
                money = ctx.money(),
            )
        }
        call.respond(response)
    }

    get("/reports/refunds") {
        val ctx = reportCtx(call, fx)
        val response = transaction {
            val refunds = refundsInRange(ctx)
            val byReason = refunds.groupBy { it[Refunds.reason] ?: "—" }.map { (reason, rows) ->
                RefundReasonRow(reason, rows.size, ctx.refunds(rows, ::rGross), ctx.refunds(rows, ::rNet), ctx.refunds(rows, ::refundTax))
            }.sortedByDescending { it.grossCents }
            val byTender = refunds.groupBy { it[Refunds.tenderType] ?: "CASH" }.map { (type, rows) ->
                TenderTypeRow(type, ctx.refunds(rows, ::rGross), rows.size)
            }.sortedByDescending { it.amountCents }
            val rows = refunds.sortedByDescending { it[Refunds.createdAt] }.map { r ->
                val venueId = r[Refunds.venueId]
                RefundListRow(
                    r[Refunds.refundId], r[Refunds.checkId], ctx.iso(r[Refunds.createdAt], venueId),
                    r[Refunds.tableLabel], r[Refunds.tenderType], r[Refunds.reason],
                    rGross(r), rNet(r), refundTax(r), venueId, ctx.currencyOf(venueId))
            }
            val refundsBy = refunds.groupBy { it[Refunds.venueId] }
            val byVenue = ctx.venues.map { v ->
                val r = refundsBy[v.id].orEmpty()
                VenueRefundsRow(v.id, v.venue.name, r.size, r.sumOf(::rGross), r.sumOf(::rNet), r.sumOf(::refundTax), v.currency)
            }
            RefundsResponse(
                refunds.size, ctx.refunds(refunds, ::rGross), ctx.refunds(refunds, ::rNet), ctx.refunds(refunds, ::refundTax),
                byReason, byTender, rows, byVenue, ctx.money())
        }
        call.respond(response)
    }

    get("/reports/cash-movements") {
        val ctx = reportCtx(call, fx)
        val response = transaction {
            val movements = CashMovements.selectAll().where {
                inScope(ctx, CashMovements.tenantId, CashMovements.venueId, CashMovements.createdAt)
            }.toList()
            fun ins(g: List<ResultRow>) = g.filter { it[CashMovements.direction] == "IN" }
            fun outs(g: List<ResultRow>) = g.filter { it[CashMovements.direction] == "OUT" }
            fun amount(r: ResultRow) = r[CashMovements.amountCents] ?: 0
            fun sum(g: List<ResultRow>) = g.sumOf(::amount)
            val paidIn = ctx.cash(ins(movements), ::amount)
            val paidOut = ctx.cash(outs(movements), ::amount)
            val movementsBy = movements.groupBy { it[CashMovements.venueId] }
            val byVenue = ctx.venues.map { v ->
                val g = movementsBy[v.id].orEmpty()
                val vi = sum(ins(g))
                val vo = sum(outs(g))
                VenueCashRow(v.id, v.venue.name, vi, vo, vi - vo, ins(g).size, outs(g).size, v.currency)
            }
            val rows = movements.sortedByDescending { it[CashMovements.createdAt] }.map { m ->
                val venueId = m[CashMovements.venueId]
                CashMovementListRow(
                    m[CashMovements.movementId], ctx.iso(m[CashMovements.createdAt], venueId),
                    m[CashMovements.direction] ?: "", amount(m),
                    m[CashMovements.reason], m[CashMovements.createdBy], venueId, ctx.currencyOf(venueId))
            }
            CashMovementsResponse(
                paidIn, paidOut, paidIn - paidOut, ins(movements).size, outs(movements).size, rows, byVenue, ctx.money())
        }
        call.respond(response)
    }

    get("/reports/payments") {
        val ctx = reportCtx(call, fx)
        val response = transaction {
            val tenders = tendersOf(ctx, closedChecks(ctx))
            fun applied(r: ResultRow) = r[CheckTenders.amountAppliedCents] ?: 0
            fun exactRows(group: List<ResultRow>) = group.groupBy { it[CheckTenders.type] }.map { (type, g) ->
                PaymentRow(type, g.sumOf(::applied), g.size)
            }.sortedByDescending { it.amountCents }
            val rows = tenders.groupBy { it[CheckTenders.type] }.map { (type, g) ->
                PaymentRow(type, ctx.tenders(g, ::applied), g.size)
            }.sortedByDescending { it.amountCents }
            val grouped = tenders.groupBy { it[CheckTenders.venueId] }
            val byVenue = ctx.venues.map { v ->
                val vr = exactRows(grouped[v.id].orEmpty())
                VenuePayments(v.id, vr.sumOf { it.amountCents }, vr, v.venue.name, v.currency)
            }
            val byCurrency = ctx.currencies.map { c ->
                val ids = ctx.only(c).venueIds()
                val cr = exactRows(tenders.filter { it[CheckTenders.venueId] in ids })
                CurrencyPayments(c, cr.sumOf { it.amountCents }, cr)
            }
            PaymentsResponse(rows, ctx.tenders(tenders, ::applied), byVenue, byCurrency, ctx.money())
        }
        call.respond(response)
    }

    get("/reports/items") {
        val ctx = reportCtx(call, fx)
        val response = transaction {
            val categoryNames = categoryNames(ctx)
            val closed = closedChecks(ctx)
            val lines = linesOf(ctx, closed)
            val rows = lines
                // an item is one row per currency: never add CAD and USD revenue
                .groupBy { it[CheckLines.itemId] to ctx.currencyOf(it[CheckLines.venueId]) }
                .map { (key, group) ->
                    val (itemId, currency) = key
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
                        byVenue = lineSplit(ctx, group),
                        currency = currency,
                    )
                }.sortedByDescending { comparable(ctx, it.currency, it.revenueCents) }
            ItemsResponse(rows, lineTotals(ctx, closed, lines), ctx.money())
        }
        call.respond(response)
    }

    get("/reports/categories") {
        val ctx = reportCtx(call, fx)
        val response = transaction {
            val categoryNames = categoryNames(ctx)
            val closed = closedChecks(ctx)
            val lines = linesOf(ctx, closed)
            val rows = lines
                .groupBy { it[CheckLines.categoryId] to ctx.currencyOf(it[CheckLines.venueId]) }
                .map { (key, group) ->
                    val (categoryId, currency) = key
                    val category = categoryNames.lookup(group.first()[CheckLines.venueId], categoryId)
                    CategoryRow(
                        categoryId = categoryId,
                        nameFr = if (categoryId == null) "Open item" else category?.first,
                        nameEn = if (categoryId == null) "Open item" else category?.second,
                        qty = group.sumOf { it[CheckLines.qty] },
                        revenueCents = group.sumOf { it[CheckLines.lineTotalCents] },
                        byVenue = lineSplit(ctx, group),
                        currency = currency,
                    )
                }.sortedByDescending { comparable(ctx, it.currency, it.revenueCents) }
            CategoriesResponse(rows, lineTotals(ctx, closed, lines), ctx.money())
        }
        call.respond(response)
    }

    get("/reports/hourly") {
        val ctx = reportCtx(call, fx)
        val response = transaction {
            // each check in its own store's local hour
            val closed = closedChecks(ctx)
            val byHour = closed.groupBy {
                CloudTime.localHour(it[Checks.closedAt]!!, ctx.zoneOf(it[Checks.venueId]))
            }
            val rows = (0..23).map { hour ->
                val group = byHour[hour].orEmpty()
                val byV = group.groupBy { it[Checks.venueId] }
                HourRow(hour, ctx.checks(group, ::gross), group.size, ctx.venues.map { v ->
                    val g = byV[v.id].orEmpty()
                    VenueHourRow(v.id, g.sumOf(::gross), g.size, v.currency)
                })
            }
            HourlyResponse(rows, checkTotals(ctx, closed), ctx.money())
        }
        call.respond(response)
    }

    get("/reports/tables") {
        val ctx = reportCtx(call, fx)
        val response = transaction {
            val closed = closedChecks(ctx)
            // zone and table ids repeat across stores: group within a store
            val byZone = closed.groupBy { it[Checks.venueId] to it[Checks.zoneId] }.map { (key, group) ->
                val first = group.first()
                ZoneRow(
                    key.second, first[Checks.zoneNameFr], first[Checks.zoneNameEn],
                    group.sumOf(::gross), group.size, key.first, ctx.currencyOf(key.first),
                )
            }.sortedByDescending { comparable(ctx, it.currency, it.grossCents) }
            val byTable = closed.groupBy { it[Checks.venueId] to it[Checks.tableId] }.map { (key, group) ->
                val first = group.first()
                TableRow(
                    first[Checks.zoneId], first[Checks.zoneNameEn], key.second, first[Checks.tableLabel],
                    group.sumOf(::gross), group.size, key.first, ctx.currencyOf(key.first),
                )
            }.sortedByDescending { comparable(ctx, it.currency, it.grossCents) }
            TablesResponse(byZone, byTable, checkTotals(ctx, closed), ctx.money())
        }
        call.respond(response)
    }

    get("/reports/exceptions") {
        val ctx = reportCtx(call, fx)
        val response = transaction {
            val voids = voidChecks(ctx).sortedByDescending { it[Checks.closedAt] }
            val closed = closedChecks(ctx)
            val voidsBy = voids.groupBy { it[Checks.venueId] }
            val closedBy = closed.groupBy { it[Checks.venueId] }
            ExceptionsResponse(
                voids = voids.map {
                    val venueId = it[Checks.venueId]
                    VoidRow(
                        it[Checks.checkId], ctx.iso(it[Checks.closedAt], venueId), it[Checks.tableLabel],
                        gross(it), it[Checks.voidReason], it[Checks.voidedBy], venueId, ctx.currencyOf(venueId),
                    )
                },
                voidCount = voids.size,
                voidAmountCents = ctx.checks(voids, ::gross),
                corkageCents = ctx.checks(closed) { it[Checks.corkageCents] ?: 0 },
                byVenue = ctx.venues.map { v ->
                    val vv = voidsBy[v.id].orEmpty()
                    VenueExceptionsRow(
                        v.id, v.venue.name, vv.size, vv.sumOf(::gross),
                        closedBy[v.id].orEmpty().sumOf { it[Checks.corkageCents] ?: 0 }, v.currency)
                },
                money = ctx.money(),
            )
        }
        call.respond(response)
    }

    get("/reports/shifts") {
        val ctx = reportCtx(call, fx)
        val response = transaction {
            val rows = Shifts.selectAll().where {
                (Shifts.tenantId eq ctx.tenantId) and anyOf(ctx.venues.map { v ->
                    (Shifts.venueId eq v.id) and (
                        ((Shifts.openedAt greaterEq v.start) and (Shifts.openedAt less v.end)) or
                            (Shifts.status eq "OPEN"))
                })
            }.orderBy(Shifts.openedAt to SortOrder.DESC_NULLS_LAST, Shifts.shiftId to SortOrder.DESC)
                .map { shiftDto(it, ctx) }
            val rowsBy = rows.groupBy { it.venueId }
            ShiftsResponse(rows, ctx.venues.map { v ->
                val r = rowsBy[v.id].orEmpty()
                VenueShiftsRow(
                    v.id, v.venue.name, r.size, r.count { it.status == "OPEN" },
                    r.sumOf { it.revenueCents ?: 0 }, r.sumOf { it.transactionCount ?: 0 },
                    r.sumOf { it.overShortCents ?: 0 }, v.currency)
            }, ctx.money())
        }
        call.respond(response)
    }

    /** One shift. Shift ids repeat across stores, so an all-stores scope must name the store. */
    get("/reports/shifts/{id}") {
        val ctx = reportCtx(call, fx)
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
        val ctx = reportCtx(call, fx)
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
                    // an exact amount → cents (in whichever currency): query-param conversion, not report math
                    runCatching { BigDecimal(q).movePointRight(2).longValueExact() }.getOrNull()
                        ?.let { match = match or (Checks.grandTotalCents eq it) }
                    op = op and match
                }
                op
            }
            val total = Checks.selectAll().where(condition).count()
            // per-store counts over the whole filtered range (not just this page)
            val allBy = Checks.select(Checks.venueId, Checks.status, Checks.grandTotalCents).where(condition)
                .toList().groupBy { it[Checks.venueId] }
            val byVenue = ctx.venues.map { v ->
                val r = allBy[v.id].orEmpty()
                val closedRows = r.filter { it[Checks.status] == "CLOSED" }
                VenueJournalRow(v.id, v.venue.name, closedRows.size, r.size - closedRows.size, closedRows.sumOf(::gross), v.currency)
            }
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
                        currency = check[Checks.currency] ?: ctx.currencyOf(venueId),
                    )
                },
                byVenue = byVenue,
                money = ctx.money(),
            )
        }
        call.respond(response)
    }
}

// --- helpers (call inside a transaction) ---

/** A sort key comparable across currencies: the figure in the scope's currency (approximate when mixed). */
private fun comparable(ctx: ReportCtx, currency: String, cents: Long): Long =
    if (!ctx.mixed) cents else ctx.fx.convert(cents, currency, ctx.reporting) ?: cents

/** Per-store qty/revenue of a group of check lines; stores without a line are left out. */
private fun lineSplit(ctx: ReportCtx, lines: List<ResultRow>): List<VenueQtyRow> {
    val by = lines.groupBy { it[CheckLines.venueId] }
    return ctx.venues.mapNotNull { v ->
        by[v.id]?.let { g ->
            VenueQtyRow(v.id, g.sumOf { it[CheckLines.qty] }, g.sumOf { it[CheckLines.lineTotalCents] }, v.currency)
        }
    }
}

/** One row per in-scope store: line revenue, closed checks and quantity sold. */
private fun lineTotals(ctx: ReportCtx, closed: List<ResultRow>, lines: List<ResultRow>): List<VenueTotalRow> {
    val linesBy = lines.groupBy { it[CheckLines.venueId] }
    val checksBy = closed.groupBy { it[Checks.venueId] }
    return ctx.venues.map { v ->
        val l = linesBy[v.id].orEmpty()
        VenueTotalRow(
            v.id, v.venue.name, l.sumOf { it[CheckLines.lineTotalCents] }, checksBy[v.id].orEmpty().size,
            l.sumOf { it[CheckLines.qty] }, v.currency)
    }
}

/** One row per in-scope store: closed-check gross and count (qty = checks). */
private fun checkTotals(ctx: ReportCtx, closed: List<ResultRow>): List<VenueTotalRow> {
    val by = closed.groupBy { it[Checks.venueId] }
    return ctx.venues.map { v ->
        val c = by[v.id].orEmpty()
        VenueTotalRow(v.id, v.venue.name, c.sumOf(::gross), c.size, c.size, v.currency)
    }
}

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
        currency = row[Shifts.currency] ?: ctx.currencyOf(venueId),
    )
}
