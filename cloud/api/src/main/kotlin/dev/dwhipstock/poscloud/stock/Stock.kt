package dev.dwhipstock.poscloud.stock

import dev.dwhipstock.poscloud.BadRequestException
import dev.dwhipstock.poscloud.CloudTime
import dev.dwhipstock.poscloud.NotFoundException
import dev.dwhipstock.poscloud.VenueScope
import dev.dwhipstock.poscloud.db.CatalogItems
import dev.dwhipstock.poscloud.db.CheckLines
import dev.dwhipstock.poscloud.db.Checks
import dev.dwhipstock.poscloud.db.StockLevels
import dev.dwhipstock.poscloud.db.StockMovements
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
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert

/**
 * Stock for retail stores, owned by the cloud (migration 018). A store sells
 * regardless of stock — offline too, and it may go negative — and never
 * tracks it: on hand is computed here, per product, as
 *
 *     received − sold ± adjustments
 *
 * "sold" = the qty on every CLOSED sale synced from that store (voided sales
 * never close; refunds do not put stock back — a returned bottle is counted
 * back in with an adjustment). Deliveries and adjustments are recorded in the
 * portal and stay in the cloud (sync is one-way). The catalog stays
 * tablet-owned: the products listed are the store's mirrored catalog.
 *
 * Scoped like every portal page: one store (`?venue=`) or all of the tenant's
 * RETAIL stores (restaurants don't track stock). Recording needs one store.
 */
object StockMath {
    /** On hand from the three sums. */
    fun onHand(received: Long, sold: Long, adjusted: Long): Long = received - sold + adjusted

    /** Low at or below the reorder level; no level = never low. */
    fun low(onHand: Long, reorderLevel: Int?): Boolean = reorderLevel != null && onHand <= reorderLevel
}

@Serializable
data class StockRow(
    val venueId: String,
    val itemId: String,
    val name: String,
    val categoryId: String,
    val barcode: String? = null,
    val active: Boolean = true,
    val received: Long,
    val sold: Long,
    val adjusted: Long,
    val onHand: Long,
    val reorderLevel: Int? = null,
    val low: Boolean,
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
)

@Serializable
data class MovementRequest(val itemId: String, val kind: String, val qty: Int, val note: String = "")

@Serializable
data class ReorderRequest(val itemId: String, val reorderLevel: Int? = null)

@Serializable
data class MovementDto(
    val id: Long, val venueId: String, val itemId: String, val kind: String, val qty: Int,
    val note: String, val createdBy: String, val createdAt: String)

@Serializable
data class MovementsResponse(val movements: List<MovementDto>)

/** The stock figures of [venues] (call inside a transaction). */
internal fun stockOf(tenantId: String, venues: List<VenueScope>): StockResponse {
    val retail = venues.filter { it.retail }
    if (retail.isEmpty()) return StockResponse(emptyList(), emptyList(), 0, 0, retail = false)
    val ids = retail.map { it.venueId }
    val items = CatalogItems.selectAll().where {
        (CatalogItems.tenantId eq tenantId) and (CatalogItems.venueId inList ids) and (CatalogItems.deleted eq false)
    }.toList()

    // sold: every CLOSED sale's lines, per (venue, item)
    val lines = Join(CheckLines, Checks, JoinType.INNER, additionalConstraint = {
        (CheckLines.tenantId eq Checks.tenantId) and (CheckLines.venueId eq Checks.venueId) and
            (CheckLines.checkId eq Checks.checkId)
    })
    val qtySum = CheckLines.qty.sum()
    val sold = lines.select(CheckLines.venueId, CheckLines.itemId, qtySum).where {
        (CheckLines.tenantId eq tenantId) and (CheckLines.venueId inList ids) and
            (Checks.status eq "CLOSED") and CheckLines.itemId.isNotNull()
    }.groupBy(CheckLines.venueId, CheckLines.itemId)
        .associate { (it[CheckLines.venueId] to it[CheckLines.itemId]!!) to (it[qtySum] ?: 0).toLong() }

    val moveSum = StockMovements.qty.sum()
    val moves = StockMovements.select(StockMovements.venueId, StockMovements.itemId, StockMovements.kind, moveSum).where {
        (StockMovements.tenantId eq tenantId) and (StockMovements.venueId inList ids)
    }.groupBy(StockMovements.venueId, StockMovements.itemId, StockMovements.kind)
        .associate { Triple(it[StockMovements.venueId], it[StockMovements.itemId], it[StockMovements.kind]) to (it[moveSum] ?: 0).toLong() }

    val levels = StockLevels.selectAll().where {
        (StockLevels.tenantId eq tenantId) and (StockLevels.venueId inList ids)
    }.associate { (it[StockLevels.venueId] to it[StockLevels.itemId]) to it[StockLevels.reorderLevel] }

    val order = ids.withIndex().associate { it.value to it.index }
    val rows = items.map { item ->
        val v = item[CatalogItems.venueId]
        val id = item[CatalogItems.id]
        val received = moves[Triple(v, id, "RECEIVED")] ?: 0
        val adjusted = moves[Triple(v, id, "ADJUSTMENT")] ?: 0
        val s = sold[v to id] ?: 0
        val onHand = StockMath.onHand(received, s, adjusted)
        val level = levels[v to id]
        StockRow(
            venueId = v, itemId = id, name = item[CatalogItems.nameEn].ifBlank { item[CatalogItems.nameFr] },
            categoryId = item[CatalogItems.categoryId], barcode = item[CatalogItems.barcode],
            active = item[CatalogItems.active],
            received = received, sold = s, adjusted = adjusted, onHand = onHand,
            reorderLevel = level, low = StockMath.low(onHand, level),
        )
    }.sortedWith(compareBy({ order[it.venueId] ?: 0 }, { !it.low }, { it.categoryId }, { it.name }))

    val byVenue = retail.map { v ->
        val r = rows.filter { it.venueId == v.venueId }
        VenueStockRow(v.venueId, v.name, r.size, r.sumOf { it.onHand }, r.count { it.low })
    }
    return StockResponse(rows, byVenue, rows.sumOf { it.onHand }, rows.count { it.low }, retail = true)
}

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

fun Route.stockRoutes() {

    get("/stock") {
        val (principal, venues) = portalScopes(call)
        call.respond(transaction { stockOf(principal.tenantId, venues) })
    }

    /** A delivery (RECEIVED, qty > 0) or an adjustment (ADJUSTMENT, qty ≠ 0: a count, breakage, a return). */
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
            stockOf(principal.tenantId, listOf(store)).rows.first { it.itemId == req.itemId }
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
            stockOf(principal.tenantId, listOf(store)).rows.first { it.itemId == req.itemId }
        }
        call.respond(response)
    }

    /** One store's recent movements, optionally for one product. */
    get("/stock/movements") {
        val (principal, venues) = portalScopes(call)
        val store = singleRetailStore(venues)
        val itemId = call.request.queryParameters["itemId"]?.takeIf { it.isNotBlank() }
        val response = transaction {
            MovementsResponse(StockMovements.selectAll().where {
                var op = (StockMovements.tenantId eq principal.tenantId) and (StockMovements.venueId eq store.venueId)
                if (itemId != null) op = op and (StockMovements.itemId eq itemId)
                op
            }.orderBy(StockMovements.id, SortOrder.DESC).limit(100).map {
                MovementDto(
                    it[StockMovements.id], it[StockMovements.venueId], it[StockMovements.itemId],
                    it[StockMovements.kind], it[StockMovements.qty], it[StockMovements.note],
                    it[StockMovements.createdBy], CloudTime.iso(it[StockMovements.createdAt], store.zone),
                )
            })
        }
        call.respond(response)
    }
}
