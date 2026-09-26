package dev.dwhipstock.poscloud.stock

import dev.dwhipstock.poscloud.BadRequestException
import dev.dwhipstock.poscloud.CloudTime
import dev.dwhipstock.poscloud.NotFoundException
import dev.dwhipstock.poscloud.VenueScope
import dev.dwhipstock.poscloud.catalog.CatalogFacets
import dev.dwhipstock.poscloud.catalog.CatalogQuery
import dev.dwhipstock.poscloud.db.CatalogItems
import dev.dwhipstock.poscloud.db.CheckLines
import dev.dwhipstock.poscloud.db.Checks
import dev.dwhipstock.poscloud.db.StockCountLines
import dev.dwhipstock.poscloud.db.StockCounts
import dev.dwhipstock.poscloud.db.StockLevels
import dev.dwhipstock.poscloud.db.StockMovements
import dev.dwhipstock.poscloud.db.StockReceipts
import dev.dwhipstock.poscloud.portalScopes
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Join
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Stock for retail stores, owned by the cloud (migrations 018, 020). A store
 * sells regardless of stock — offline too, and it may go negative — and
 * never keeps an on-hand figure itself: on hand is computed here, per
 * product, from one ledger:
 *
 *     on hand = last count + received − sold ± adjustments + returned
 *
 * where everything after "last count" is what happened AFTER that count
 * (a count sets on hand as of its count time; no count = start from 0).
 * - counts and deliveries come from the store (the stock app / counter:
 *   `stock.counted`, `stock.received`) or, for deliveries and adjustments,
 *   from the portal;
 * - "sold" = the qty on every CLOSED sale synced from that store, dated by
 *   its close time (voided sales never close);
 * - "returned" = the products of by-line refunds, dated by the refund.
 *
 * The catalog stays tablet-owned: the products listed are the store's
 * mirrored catalog. Scoped like every portal page: one store (`?venue=`) or
 * all of the tenant's RETAIL stores (restaurants don't track stock).
 * Quantities only — stock never mixes currencies because it has none.
 */
object StockMath {
    /** On hand from the ledger's parts ([counted] = the last count, if any). */
    fun onHand(received: Long, sold: Long, adjusted: Long, returned: Long = 0, counted: Long? = null): Long =
        (counted ?: 0) + received - sold + adjusted + returned

    /** Low at or below the reorder level; no level = never low. */
    fun low(onHand: Long, reorderLevel: Int?): Boolean = reorderLevel != null && onHand <= reorderLevel

    /** Average units sold per day over [days]. */
    fun avgDaily(soldInWindow: Long, days: Int): Double = if (days <= 0) 0.0 else soldInWindow.toDouble() / days

    /**
     * Order enough to cover [coverDays] (lead time + days of cover) at the
     * recent rate: target = ⌈avg daily × cover days⌉, suggested = target − on
     * hand, never below 0. Negative on hand (sold before a delivery was
     * recorded) is taken as it is.
     */
    fun target(avgDaily: Double, coverDays: Int): Long = kotlin.math.ceil(avgDaily * coverDays - 1e-9).toLong().coerceAtLeast(0)

    fun suggested(avgDaily: Double, coverDays: Int, onHand: Long): Long =
        (target(avgDaily, coverDays) - onHand).coerceAtLeast(0)
}

/** One product's ledger: the last count (if any) and every move after it. */
data class LedgerRow(
    val countedQty: Long? = null,
    val countedAt: OffsetDateTime? = null,
    val received: Long = 0,
    val sold: Long = 0,
    val adjusted: Long = 0,
    val returned: Long = 0,
) {
    val onHand: Long get() = StockMath.onHand(received, sold, adjusted, returned, countedQty)
}

/**
 * The ledger of [venueIds] (call inside a transaction), optionally as of
 * [asOf] (only what happened at or before it), for [itemIds] only, and
 * leaving out one count ([excludeRef], e.g. the count being compared).
 * Products with nothing at all are absent.
 */
internal fun stockLedger(
    tenantId: String,
    venueIds: List<String>,
    asOf: OffsetDateTime? = null,
    itemIds: Collection<String>? = null,
    excludeRef: String? = null,
): Map<Pair<String, String>, LedgerRow> {
    if (venueIds.isEmpty() || itemIds?.isEmpty() == true) return emptyMap()
    fun lit(s: String) = "'" + s.replace("'", "''") + "'"
    fun ts(t: OffsetDateTime) = lit(t.withOffsetSameInstant(ZoneOffset.UTC).toString()) + "::timestamptz"
    val venues = venueIds.joinToString(",", transform = ::lit)
    val items = itemIds?.joinToString(",", transform = ::lit)
    val tenant = lit(tenantId)
    val lastCount = """
        lc AS (SELECT DISTINCT ON (venue_id, item_id) venue_id, item_id, qty, created_at AS since
               FROM stock_movements
               WHERE tenant_id = $tenant AND venue_id IN ($venues) AND kind = 'COUNT'
               ${if (items != null) "AND item_id IN ($items)" else ""}
               ${if (asOf != null) "AND created_at <= ${ts(asOf)}" else ""}
               ${if (excludeRef != null) "AND source_ref IS DISTINCT FROM ${lit(excludeRef)}" else ""}
               ORDER BY venue_id, item_id, created_at DESC, id DESC)
    """.trimIndent()
    val rows = HashMap<Pair<String, String>, LedgerRow>()
    val tx = TransactionManager.current()
    // the last counts
    val select = org.jetbrains.exposed.sql.statements.StatementType.SELECT
    tx.exec("WITH $lastCount SELECT venue_id, item_id, qty, since FROM lc", explicitStatementType = select) { rs ->
        while (rs.next()) {
            val at = rs.getObject(4, OffsetDateTime::class.java)
            rows[rs.getString(1) to rs.getString(2)] = LedgerRow(countedQty = rs.getLong(3), countedAt = at)
        }
    }
    fun after(timeCol: String) = "(lc.since IS NULL OR $timeCol > lc.since)" +
        (if (asOf != null) " AND $timeCol <= ${ts(asOf)}" else "")
    fun merge(sql: String, apply: (LedgerRow, Long) -> LedgerRow) = tx.exec(sql, explicitStatementType = select) { rs ->
        while (rs.next()) {
            val key = rs.getString(1) to rs.getString(2)
            rows[key] = apply(rows[key] ?: LedgerRow(), rs.getLong(3))
        }
    }
    // deliveries and adjustments after the count
    for ((kind, apply) in listOf<Pair<String, (LedgerRow, Long) -> LedgerRow>>(
        "RECEIVED" to { r, n -> r.copy(received = n) },
        "ADJUSTMENT" to { r, n -> r.copy(adjusted = n) },
    )) {
        merge("""
            WITH $lastCount
            SELECT m.venue_id, m.item_id, SUM(m.qty) FROM stock_movements m
            LEFT JOIN lc ON lc.venue_id = m.venue_id AND lc.item_id = m.item_id
            WHERE m.tenant_id = $tenant AND m.venue_id IN ($venues) AND m.kind = '$kind'
            ${if (items != null) "AND m.item_id IN ($items)" else ""}
            AND ${after("m.created_at")}
            GROUP BY m.venue_id, m.item_id
        """.trimIndent(), apply)
    }
    // sales closed after the count
    merge("""
        WITH $lastCount
        SELECT l.venue_id, l.item_id, SUM(l.qty) FROM check_lines l
        JOIN checks c ON c.tenant_id = l.tenant_id AND c.venue_id = l.venue_id AND c.check_id = l.check_id
        LEFT JOIN lc ON lc.venue_id = l.venue_id AND lc.item_id = l.item_id
        WHERE l.tenant_id = $tenant AND l.venue_id IN ($venues) AND c.status = 'CLOSED' AND l.item_id IS NOT NULL
        ${if (items != null) "AND l.item_id IN ($items)" else ""}
        AND ${if (asOf == null) "(lc.since IS NULL OR c.closed_at > lc.since)" else after("c.closed_at")}
        GROUP BY l.venue_id, l.item_id
    """.trimIndent()) { r, n -> r.copy(sold = n) }
    // products refunded (by line) after the count come back on hand
    merge("""
        WITH $lastCount
        SELECT r.venue_id, r.item_id, SUM(r.qty) FROM refund_lines r
        LEFT JOIN lc ON lc.venue_id = r.venue_id AND lc.item_id = r.item_id
        WHERE r.tenant_id = $tenant AND r.venue_id IN ($venues) AND r.item_id IS NOT NULL
        ${if (items != null) "AND r.item_id IN ($items)" else ""}
        AND ${after("r.created_at")}
        GROUP BY r.venue_id, r.item_id
    """.trimIndent()) { r, n -> r.copy(returned = n) }
    return rows
}

@Serializable
data class StockRow(
    val venueId: String,
    val itemId: String,
    val name: String,
    val categoryId: String,
    val barcode: String? = null,
    val active: Boolean = true,
    /** Since the last count (all time when never counted). */
    val received: Long,
    val sold: Long,
    val adjusted: Long,
    val returned: Long = 0,
    /** The last count's qty and time; null = never counted. */
    val countedQty: Long? = null,
    val countedAt: String? = null,
    val onHand: Long,
    val reorderLevel: Int? = null,
    val low: Boolean,
    // retail shelf facts (021), for the filters
    val brand: String? = null,
    val subcategory: String? = null,
    val size: String? = null,
)

@Serializable
data class VenueStockRow(
    val venueId: String, val venueName: String, val products: Int, val onHand: Long, val lowCount: Int)

@Serializable
data class StockResponse(
    val rows: List<StockRow>,
    val byVenue: List<VenueStockRow>,
    /** Units on hand across every in-scope retail store. */
    val totalOnHand: Long,
    val lowCount: Int,
    /** False when no store in scope is retail (restaurants don't track stock). */
    val retail: Boolean,
    /**
     * Paged (`limit`, `q`, `category`, `subcategory`, `size`, `low=true`):
     * [rows] is one page of the [total] matching rows and [facets] the filter
     * values present; the KPIs above stay the whole scope's. Unpaged: every
     * row, [total] = rows.size.
     */
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int? = null,
    val facets: CatalogFacets? = null,
)

@Serializable
data class LowCountResponse(val lowCount: Int, val retail: Boolean)

@Serializable
data class MovementRequest(val itemId: String, val kind: String, val qty: Int, val note: String = "")

@Serializable
data class ReorderRequest(val itemId: String, val reorderLevel: Int? = null)

@Serializable
data class MovementDto(
    val id: Long, val venueId: String, val itemId: String, val kind: String, val qty: Int,
    val note: String, val createdBy: String, val createdAt: String, val source: String = "portal")

@Serializable
data class MovementsResponse(val movements: List<MovementDto>)

private fun StockRow.facts() = CatalogQuery.Facts(
    names = listOf(name), categoryId = categoryId, brand = brand, subcategory = subcategory, size = size, barcode = barcode,
)

/**
 * The stock figures of [venues] (call inside a transaction), optionally one
 * page of them under [query] (+ [lowOnly]); [itemIds] limits the read to
 * those products (one product's refresh after an edit).
 */
internal fun stockOf(
    tenantId: String, venues: List<VenueScope>, query: CatalogQuery = CatalogQuery(), lowOnly: Boolean = false,
    itemIds: Collection<String>? = null,
): StockResponse {
    val full = stockRowsOf(tenantId, venues, itemIds)
    if (!full.retail || (!query.paged && !lowOnly)) return full.copy(total = full.rows.size)
    val matching = full.rows.filter { (!lowOnly || it.low) && query.matches(it.facts()) }
    return full.copy(
        rows = query.page(matching), total = matching.size, offset = query.offset, limit = query.limit,
        facets = query.facets(full.rows.filter { !lowOnly || it.low }) { it.facts() },
    )
}

private fun stockRowsOf(tenantId: String, venues: List<VenueScope>, itemIds: Collection<String>?): StockResponse {
    val retail = venues.filter { it.retail }
    if (retail.isEmpty()) return StockResponse(emptyList(), emptyList(), 0, 0, retail = false)
    val ids = retail.map { it.venueId }
    val zones = retail.associate { it.venueId to it.zone }
    val items = CatalogItems.selectAll().where {
        var op = (CatalogItems.tenantId eq tenantId) and (CatalogItems.venueId inList ids) and (CatalogItems.deleted eq false)
        if (itemIds != null) op = op and (CatalogItems.id inList itemIds)
        op
    }.toList()
    val ledger = stockLedger(tenantId, ids, itemIds = itemIds)
    val levels = StockLevels.selectAll().where {
        var op = (StockLevels.tenantId eq tenantId) and (StockLevels.venueId inList ids)
        if (itemIds != null) op = op and (StockLevels.itemId inList itemIds)
        op
    }.associate { (it[StockLevels.venueId] to it[StockLevels.itemId]) to it[StockLevels.reorderLevel] }

    val order = ids.withIndex().associate { it.value to it.index }
    val rows = items.map { item ->
        val v = item[CatalogItems.venueId]
        val id = item[CatalogItems.id]
        val l = ledger[v to id] ?: LedgerRow()
        val level = levels[v to id]
        StockRow(
            venueId = v, itemId = id, name = item[CatalogItems.nameEn].ifBlank { item[CatalogItems.nameFr] },
            categoryId = item[CatalogItems.categoryId], barcode = item[CatalogItems.barcode],
            active = item[CatalogItems.active],
            received = l.received, sold = l.sold, adjusted = l.adjusted, returned = l.returned,
            countedQty = l.countedQty, countedAt = l.countedAt?.let { CloudTime.iso(it, zones.getValue(v)) },
            onHand = l.onHand, reorderLevel = level, low = StockMath.low(l.onHand, level),
            brand = item[CatalogItems.brand], subcategory = item[CatalogItems.subcategory],
            size = item[CatalogItems.sizeLabel],
        )
    }.sortedWith(compareBy({ order[it.venueId] ?: 0 }, { !it.low }, { it.categoryId }, { it.name }))

    val byVenue = retail.map { v ->
        val r = rows.filter { it.venueId == v.venueId }
        VenueStockRow(v.venueId, v.name, r.size, r.sumOf { it.onHand }, r.count { it.low })
    }
    return StockResponse(rows, byVenue, rows.sumOf { it.onHand }, rows.count { it.low }, retail = true)
}

// ---- counts and deliveries from the store (portal read side) ----

@Serializable
data class CountLineDto(
    val itemId: String, val name: String, val counted: Int,
    /** What the cloud expected at the count time; null = no history for the product. */
    val expected: Int? = null,
    val variance: Int? = null,
    val countedAt: String,
)

@Serializable
data class CountDto(
    val venueId: String, val venueName: String, val countId: String, val name: String,
    val submittedBy: String? = null, val approvedBy: String? = null,
    val startedAt: String? = null, val submittedAt: String,
    val products: Int, val units: Long,
    /** Products whose count differed from the expected qty, and the net units. */
    val varianceLines: Int, val varianceUnits: Long,
    val lines: List<CountLineDto> = emptyList(),
)

@Serializable
data class CountsResponse(val counts: List<CountDto>, val retail: Boolean)

@Serializable
data class ReceiptLineDto(val itemId: String, val name: String, val qty: Int)

@Serializable
data class ReceiptDto(
    val venueId: String, val venueName: String, val receiptId: String, val supplier: String, val reference: String,
    val receivedBy: String? = null, val receivedAt: String, val units: Long, val lines: List<ReceiptLineDto>,
)

@Serializable
data class ReceiptsResponse(val receipts: List<ReceiptDto>, val retail: Boolean)

private fun names(tenantId: String, ids: List<String>): Map<Pair<String, String>, String> =
    CatalogItems.selectAll().where { (CatalogItems.tenantId eq tenantId) and (CatalogItems.venueId inList ids) }
        .associate { (it[CatalogItems.venueId] to it[CatalogItems.id]) to it[CatalogItems.nameEn].ifBlank { it[CatalogItems.nameFr] } }

internal fun countsOf(tenantId: String, venues: List<VenueScope>, limit: Int = 50): CountsResponse {
    val retail = venues.filter { it.retail }
    if (retail.isEmpty()) return CountsResponse(emptyList(), retail = false)
    val ids = retail.map { it.venueId }
    val byId = retail.associateBy { it.venueId }
    val names = names(tenantId, ids)
    val heads = StockCounts.selectAll().where { (StockCounts.tenantId eq tenantId) and (StockCounts.venueId inList ids) }
        .orderBy(StockCounts.submittedAt, SortOrder.DESC).limit(limit).toList()
    val lines = if (heads.isEmpty()) emptyMap() else StockCountLines.selectAll().where {
        (StockCountLines.tenantId eq tenantId) and (StockCountLines.venueId inList ids) and
            (StockCountLines.countId inList heads.map { it[StockCounts.countId] })
    }.toList().groupBy { it[StockCountLines.venueId] to it[StockCountLines.countId] }
    return CountsResponse(heads.map { h ->
        val v = byId.getValue(h[StockCounts.venueId])
        val ls = lines[v.venueId to h[StockCounts.countId]].orEmpty().map { l ->
            val counted = l[StockCountLines.counted]
            val expected = l[StockCountLines.expected]
            CountLineDto(
                l[StockCountLines.itemId], names[v.venueId to l[StockCountLines.itemId]] ?: l[StockCountLines.itemId],
                counted, expected, expected?.let { counted - it }, CloudTime.iso(l[StockCountLines.countedAt], v.zone),
            )
        }.sortedWith(compareBy({ -(kotlin.math.abs(it.variance ?: 0)) }, { it.name }))
        CountDto(
            venueId = v.venueId, venueName = v.name, countId = h[StockCounts.countId], name = h[StockCounts.name],
            submittedBy = h[StockCounts.submittedByName] ?: h[StockCounts.submittedBy],
            approvedBy = h[StockCounts.approvedBy],
            startedAt = h[StockCounts.startedAt]?.let { CloudTime.iso(it, v.zone) },
            submittedAt = CloudTime.iso(h[StockCounts.submittedAt], v.zone),
            products = ls.size, units = ls.sumOf { it.counted.toLong() },
            varianceLines = ls.count { (it.variance ?: 0) != 0 }, varianceUnits = ls.sumOf { (it.variance ?: 0).toLong() },
            lines = ls,
        )
    }, retail = true)
}

internal fun receiptsOf(tenantId: String, venues: List<VenueScope>, limit: Int = 50): ReceiptsResponse {
    val retail = venues.filter { it.retail }
    if (retail.isEmpty()) return ReceiptsResponse(emptyList(), retail = false)
    val ids = retail.map { it.venueId }
    val byId = retail.associateBy { it.venueId }
    val names = names(tenantId, ids)
    val heads = StockReceipts.selectAll().where { (StockReceipts.tenantId eq tenantId) and (StockReceipts.venueId inList ids) }
        .orderBy(StockReceipts.receivedAt, SortOrder.DESC).limit(limit).toList()
    val refs = heads.map { "receipt:" + it[StockReceipts.receiptId] }
    val lines = if (refs.isEmpty()) emptyMap() else StockMovements.selectAll().where {
        (StockMovements.tenantId eq tenantId) and (StockMovements.venueId inList ids) and (StockMovements.sourceRef inList refs)
    }.toList().groupBy { it[StockMovements.venueId] to it[StockMovements.sourceRef]!! }
    return ReceiptsResponse(heads.map { h ->
        val v = byId.getValue(h[StockReceipts.venueId])
        val ls = lines[v.venueId to "receipt:" + h[StockReceipts.receiptId]].orEmpty().map {
            ReceiptLineDto(it[StockMovements.itemId], names[v.venueId to it[StockMovements.itemId]] ?: it[StockMovements.itemId], it[StockMovements.qty])
        }.sortedBy { it.name }
        ReceiptDto(
            v.venueId, v.name, h[StockReceipts.receiptId], h[StockReceipts.supplier], h[StockReceipts.reference],
            h[StockReceipts.receivedByName] ?: h[StockReceipts.receivedBy], CloudTime.iso(h[StockReceipts.receivedAt], v.zone),
            ls.sumOf { it.qty.toLong() }, ls,
        )
    }, retail = true)
}

// ---- reorder suggestions ----

@Serializable
data class ReorderRow(
    val venueId: String, val venueName: String, val itemId: String, val name: String,
    val categoryId: String, val barcode: String? = null,
    val onHand: Long, val soldInWindow: Long, val avgDaily: Double,
    val target: Long, val suggested: Long, val reorderLevel: Int? = null, val low: Boolean,
    val brand: String? = null, val subcategory: String? = null, val size: String? = null,
)

@Serializable
data class ReorderResponse(
    val rows: List<ReorderRow>,
    /** The sales window (days) and the days of stock to cover (lead time + cover). */
    val days: Int, val coverDays: Int,
    /** Products with something to order, and the units in total. */
    val toOrder: Int, val units: Long,
    val retail: Boolean,
    /** Paged / filtered (see [StockResponse]); toOrder and units stay the whole scope's. */
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int? = null,
    val facets: CatalogFacets? = null,
)

object ReorderDefaults {
    const val DAYS = 28
    const val COVER_DAYS = 14
    val DAYS_RANGE = 14..28
    val COVER_RANGE = 1..120
}

internal fun reorderOf(
    tenantId: String, venues: List<VenueScope>, days: Int, coverDays: Int, now: OffsetDateTime = CloudTime.now(),
    query: CatalogQuery = CatalogQuery(), onlyToOrder: Boolean = false,
): ReorderResponse {
    val stock = stockOf(tenantId, venues)
    if (!stock.retail) return ReorderResponse(emptyList(), days, coverDays, 0, 0, retail = false)
    val ids = stock.byVenue.map { it.venueId }
    val names = stock.byVenue.associate { it.venueId to it.venueName }
    val since = now.minusDays(days.toLong())
    val lines = Join(CheckLines, Checks, JoinType.INNER, additionalConstraint = {
        (CheckLines.tenantId eq Checks.tenantId) and (CheckLines.venueId eq Checks.venueId) and
            (CheckLines.checkId eq Checks.checkId)
    })
    val qtySum = CheckLines.qty.sum()
    val sold = lines.select(CheckLines.venueId, CheckLines.itemId, qtySum).where {
        (CheckLines.tenantId eq tenantId) and (CheckLines.venueId inList ids) and (Checks.status eq "CLOSED") and
            CheckLines.itemId.isNotNull() and (Checks.closedAt greaterEq since) and (Checks.closedAt lessEq now)
    }.groupBy(CheckLines.venueId, CheckLines.itemId)
        .associate { (it[CheckLines.venueId] to it[CheckLines.itemId]!!) to (it[qtySum] ?: 0).toLong() }
    val rows = stock.rows.filter { it.active }.map { r ->
        val s = sold[r.venueId to r.itemId] ?: 0
        val avg = StockMath.avgDaily(s, days)
        ReorderRow(
            r.venueId, names[r.venueId] ?: r.venueId, r.itemId, r.name, r.categoryId, r.barcode,
            onHand = r.onHand, soldInWindow = s, avgDaily = Math.round(avg * 100) / 100.0,
            target = StockMath.target(avg, coverDays), suggested = StockMath.suggested(avg, coverDays, r.onHand),
            reorderLevel = r.reorderLevel, low = r.low,
            brand = r.brand, subcategory = r.subcategory, size = r.size,
        )
    }.sortedWith(compareBy({ -it.suggested }, { !it.low }, { it.name }))
    val toOrder = rows.count { it.suggested > 0 }
    val units = rows.sumOf { it.suggested }
    if (!query.paged && !onlyToOrder)
        return ReorderResponse(rows, days, coverDays, toOrder, units, retail = true, total = rows.size)
    fun facts(r: ReorderRow) = CatalogQuery.Facts(
        names = listOf(r.name), categoryId = r.categoryId, brand = r.brand, subcategory = r.subcategory,
        size = r.size, barcode = r.barcode,
    )
    val scoped = rows.filter { !onlyToOrder || it.suggested > 0 }
    val matching = scoped.filter { query.matches(facts(it)) }
    return ReorderResponse(
        query.page(matching), days, coverDays, toOrder, units, retail = true,
        total = matching.size, offset = query.offset, limit = query.limit, facets = query.facets(scoped, ::facts),
    )
}

// ---- routes ----

/** Recording needs exactly one retail store: `?venue=`. */
private fun singleRetailStore(venues: List<VenueScope>): VenueScope {
    val v = venues.singleOrNull() ?: throw BadRequestException("pick a store (?venue=) to record stock", "venue_required")
    if (!v.retail) throw BadRequestException("${v.venueId} doesn't track stock", "not_retail")
    return v
}

private fun requireItem(tenantId: String, venueId: String, itemId: String) {
    CatalogItems.selectAll().where {
        (CatalogItems.tenantId eq tenantId) and (CatalogItems.venueId eq venueId) and (CatalogItems.id eq itemId)
    }.firstOrNull() ?: throw NotFoundException("no product $itemId at $venueId", "unknown_item")
}

private fun ApplicationCall.intParam(name: String, default: Int, range: IntRange): Int {
    val raw = request.queryParameters[name]?.takeIf { it.isNotBlank() } ?: return default
    val n = raw.toIntOrNull() ?: throw BadRequestException("$name must be a number", "bad_param")
    if (n !in range) throw BadRequestException("$name must be ${range.first}-${range.last}", "bad_param")
    return n
}

fun Route.stockRoutes() {

    get("/stock") {
        val (principal, venues) = portalScopes(call)
        val query = CatalogQuery.from(call)
        val lowOnly = call.request.queryParameters["low"] == "true"
        call.respond(transaction { stockOf(principal.tenantId, venues, query, lowOnly) })
    }

    /** For the nav badge: how many products are at or below their reorder level. */
    get("/stock/low-count") {
        val (principal, venues) = portalScopes(call)
        val stock = transaction { stockOf(principal.tenantId, venues) }
        call.respond(LowCountResponse(stock.lowCount, stock.retail))
    }

    /** Counts submitted at the store(s): who, when, and the variances. */
    get("/stock/counts") {
        val (principal, venues) = portalScopes(call)
        call.respond(transaction { countsOf(principal.tenantId, venues) })
    }

    /** Deliveries received at the store(s). */
    get("/stock/receipts") {
        val (principal, venues) = portalScopes(call)
        call.respond(transaction { receiptsOf(principal.tenantId, venues) })
    }

    /**
     * Reorder suggestions: average daily sales over the last `days` (14-28,
     * default 28) × `cover` days (lead time + days of cover, default 14),
     * less on hand.
     */
    get("/stock/reorder-suggestions") {
        val (principal, venues) = portalScopes(call)
        val days = call.intParam("days", ReorderDefaults.DAYS, ReorderDefaults.DAYS_RANGE)
        val cover = call.intParam("cover", ReorderDefaults.COVER_DAYS, ReorderDefaults.COVER_RANGE)
        val query = CatalogQuery.from(call)
        val onlyToOrder = call.request.queryParameters["only"] == "to-order"
        call.respond(transaction { reorderOf(principal.tenantId, venues, days, cover, query = query, onlyToOrder = onlyToOrder) })
    }

    /** A delivery (RECEIVED, qty > 0) or an adjustment (ADJUSTMENT, qty ≠ 0: breakage, a return). */
    post("/stock/movements") {
        val (principal, venues) = portalScopes(call)
        val store = singleRetailStore(venues)
        val req = call.receive<MovementRequest>()
        val kind = req.kind.trim().uppercase()
        when (kind) {
            "RECEIVED" -> if (req.qty <= 0) throw BadRequestException("a delivery adds a positive quantity", "bad_qty")
            "ADJUSTMENT" -> if (req.qty == 0) throw BadRequestException("an adjustment changes the quantity", "bad_qty")
            else -> throw BadRequestException("kind must be RECEIVED or ADJUSTMENT", "bad_kind")
        }
        if (req.qty !in -100_000..100_000) throw BadRequestException("quantity out of range", "bad_qty")
        val response = transaction {
            requireItem(principal.tenantId, store.venueId, req.itemId)
            StockMovements.insert {
                it[tenantId] = principal.tenantId
                it[venueId] = store.venueId
                it[itemId] = req.itemId
                it[StockMovements.kind] = kind
                it[qty] = req.qty
                it[note] = req.note.trim().take(300)
                it[createdBy] = principal.email
                it[createdAt] = CloudTime.now()
            }
            stockOf(principal.tenantId, listOf(store), itemIds = listOf(req.itemId)).rows.first { it.itemId == req.itemId }
        }
        call.respond(HttpStatusCode.Created, response)
    }

    /** Set (or clear, null) a product's reorder level. */
    put("/stock/reorder") {
        val (principal, venues) = portalScopes(call)
        val store = singleRetailStore(venues)
        val req = call.receive<ReorderRequest>()
        if (req.reorderLevel != null && req.reorderLevel !in 0..100_000)
            throw BadRequestException("reorder level out of range", "bad_qty")
        val response = transaction {
            requireItem(principal.tenantId, store.venueId, req.itemId)
            if (req.reorderLevel == null) {
                StockLevels.deleteWhere {
                    (tenantId eq principal.tenantId) and (venueId eq store.venueId) and (itemId eq req.itemId)
                }
            } else {
                StockLevels.upsert {
                    it[tenantId] = principal.tenantId
                    it[venueId] = store.venueId
                    it[itemId] = req.itemId
                    it[reorderLevel] = req.reorderLevel
                    it[updatedAt] = CloudTime.now()
                }
            }
            stockOf(principal.tenantId, listOf(store), itemIds = listOf(req.itemId)).rows.first { it.itemId == req.itemId }
        }
        call.respond(response)
    }

    /** One store's recent movements (portal entries, store counts and deliveries), optionally for one product. */
    get("/stock/movements") {
        val (principal, venues) = portalScopes(call)
        val store = singleRetailStore(venues)
        val itemId = call.request.queryParameters["itemId"]?.takeIf { it.isNotBlank() }
        val response = transaction {
            MovementsResponse(StockMovements.selectAll().where {
                var op = (StockMovements.tenantId eq principal.tenantId) and (StockMovements.venueId eq store.venueId)
                if (itemId != null) op = op and (StockMovements.itemId eq itemId)
                op
            }.orderBy(StockMovements.createdAt to SortOrder.DESC, StockMovements.id to SortOrder.DESC).limit(100).map {
                MovementDto(
                    it[StockMovements.id], it[StockMovements.venueId], it[StockMovements.itemId],
                    it[StockMovements.kind], it[StockMovements.qty], it[StockMovements.note],
                    it[StockMovements.createdBy], CloudTime.iso(it[StockMovements.createdAt], store.zone),
                    it[StockMovements.origin],
                )
            })
        }
        call.respond(response)
    }
}
