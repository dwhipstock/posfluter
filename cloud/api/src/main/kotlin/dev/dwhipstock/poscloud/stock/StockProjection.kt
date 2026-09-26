package dev.dwhipstock.poscloud.stock

import dev.dwhipstock.poscloud.catalog.Scope
import dev.dwhipstock.poscloud.catalog.arr
import dev.dwhipstock.poscloud.catalog.instant
import dev.dwhipstock.poscloud.catalog.int
import dev.dwhipstock.poscloud.catalog.long
import dev.dwhipstock.poscloud.catalog.str
import dev.dwhipstock.poscloud.db.CheckLines
import dev.dwhipstock.poscloud.db.RefundLines
import dev.dwhipstock.poscloud.db.StockCountLines
import dev.dwhipstock.poscloud.db.StockCounts
import dev.dwhipstock.poscloud.db.StockMovements
import dev.dwhipstock.poscloud.db.StockReceipts
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * The store's stock events → the ledger (CONTRACT §2, Stock). Called from
 * the ingest projection: inside the batch transaction, first insert of the
 * event, in seq order — so every sale closed before a count is already here
 * when the count arrives, and the count's variance is against the right
 * figure. Idempotent anyway (a re-emitted event is a no-op).
 */
object StockProjection {

    /**
     * `stock.counted`: each line sets that product's on hand to its counted
     * qty as of its count time. The expected qty is the ledger at that moment
     * (before this count), stored for the portal's variance view.
     */
    fun counted(scope: Scope, p: JsonObject, createdAt: OffsetDateTime, zone: ZoneId) {
        val countId = p.str("countId")?.takeIf { it.isNotBlank() } ?: return
        val submittedAt = p.instant("submittedAt", zone) ?: createdAt
        val inserted = StockCounts.insertIgnore {
            it[tenantId] = scope.tenantId
            it[venueId] = scope.venueId
            it[StockCounts.countId] = countId
            it[name] = p.str("name")?.take(100) ?: ""
            it[startedBy] = p.str("startedBy")
            it[submittedBy] = p.str("submittedBy")
            it[submittedByName] = p.str("submittedByName")
            it[approvedBy] = p.str("approvedBy")
            it[startedAt] = p.instant("startedAt", zone)
            it[StockCounts.submittedAt] = submittedAt
        }.insertedCount
        if (inserted == 0) return
        val ref = "count:$countId"
        val lines = p.arr("lines")?.filterIsInstance<JsonObject>().orEmpty().mapNotNull { l ->
            val itemId = l.str("itemId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val qty = l.int("countedQty") ?: return@mapNotNull null
            Triple(itemId, qty, (l.instant("countedAt", zone) ?: submittedAt).let { if (it.isAfter(submittedAt)) submittedAt else it }) to
                l.int("expectedQty")
        }.distinctBy { it.first.first }
        // expected first, for every line, before any of this count's rows land
        val expected = lines.associate { (line, _) ->
            val (itemId, _, at) = line
            itemId to stockLedger(scope.tenantId, listOf(scope.venueId), asOf = at, itemIds = listOf(itemId), excludeRef = ref)[scope.venueId to itemId]?.onHand
        }
        val who = p.str("submittedByName") ?: p.str("submittedBy") ?: "store"
        for ((line, storeExpected) in lines) {
            val (itemId, qty, at) = line
            StockCountLines.insertIgnore {
                it[tenantId] = scope.tenantId
                it[venueId] = scope.venueId
                it[StockCountLines.countId] = countId
                it[StockCountLines.itemId] = itemId
                it[counted] = qty
                it[StockCountLines.expected] = expected[itemId]?.toInt()
                it[StockCountLines.storeExpected] = storeExpected
                it[countedAt] = at
            }
            StockMovements.insertIgnore {
                it[tenantId] = scope.tenantId
                it[venueId] = scope.venueId
                it[StockMovements.itemId] = itemId
                it[kind] = "COUNT"
                it[StockMovements.qty] = qty
                it[note] = (p.str("name") ?: "").take(300)
                it[createdBy] = who
                it[StockMovements.createdAt] = at
                it[origin] = "store"
                it[sourceRef] = ref
            }
        }
    }

    /** `stock.received`: a delivery scanned at the store — RECEIVED movements at the receiving time. */
    fun received(scope: Scope, p: JsonObject, createdAt: OffsetDateTime, zone: ZoneId) {
        val receiptId = p.str("receiptId")?.takeIf { it.isNotBlank() } ?: return
        val at = p.instant("receivedAt", zone) ?: createdAt
        val supplier = p.str("supplier")?.take(100) ?: ""
        val reference = p.str("reference")?.take(100) ?: ""
        val inserted = StockReceipts.insertIgnore {
            it[tenantId] = scope.tenantId
            it[venueId] = scope.venueId
            it[StockReceipts.receiptId] = receiptId
            it[StockReceipts.supplier] = supplier
            it[StockReceipts.reference] = reference
            it[receivedBy] = p.str("receivedBy")
            it[receivedByName] = p.str("receivedByName")
            it[receivedAt] = at
        }.insertedCount
        if (inserted == 0) return
        val note = listOf(supplier, reference).filter { it.isNotBlank() }.joinToString(" · ")
        val who = p.str("receivedByName") ?: p.str("receivedBy") ?: "store"
        p.arr("lines")?.filterIsInstance<JsonObject>()?.forEach { l ->
            val itemId = l.str("itemId")?.takeIf { it.isNotBlank() } ?: return@forEach
            val qty = l.int("qty")?.takeIf { it > 0 } ?: return@forEach
            StockMovements.insertIgnore {
                it[tenantId] = scope.tenantId
                it[venueId] = scope.venueId
                it[StockMovements.itemId] = itemId
                it[kind] = "RECEIVED"
                it[StockMovements.qty] = qty
                it[StockMovements.note] = note.take(300)
                it[createdBy] = who
                it[StockMovements.createdAt] = at
                it[origin] = "store"
                it[sourceRef] = "receipt:$receiptId"
            }
        }
    }

    /**
     * A by-line refund's products go back on hand at the refund time. The
     * store names each line's product (`itemId`); an older store only sent the
     * line id, which the sale's own lines resolve. Amount-only refunds carry
     * no lines and change no stock.
     */
    fun refundLines(scope: Scope, p: JsonObject, refundId: Long, at: OffsetDateTime) {
        val checkId = p.int("checkId")
        p.arr("lines")?.filterIsInstance<JsonObject>()?.forEach { l ->
            val lineId = l.long("lineId") ?: return@forEach
            val qty = l.int("qty")?.takeIf { it > 0 } ?: return@forEach
            val itemId = l.str("itemId") ?: checkId?.let { c ->
                CheckLines.selectAll().where {
                    (CheckLines.tenantId eq scope.tenantId) and (CheckLines.venueId eq scope.venueId) and
                        (CheckLines.checkId eq c) and (CheckLines.lineId eq lineId)
                }.firstOrNull()?.get(CheckLines.itemId)
            }
            RefundLines.insertIgnore {
                it[tenantId] = scope.tenantId
                it[venueId] = scope.venueId
                it[RefundLines.refundId] = refundId
                it[RefundLines.lineId] = lineId
                it[RefundLines.itemId] = itemId
                it[RefundLines.qty] = qty
                it[createdAt] = at
            }
        }
    }
}
