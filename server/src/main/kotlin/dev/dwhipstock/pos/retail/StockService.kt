package dev.dwhipstock.pos.retail

import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.Upc
import dev.dwhipstock.pos.base.Users
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.db.utcTimestamp
import dev.dwhipstock.pos.restaurant.BadRequestException
import dev.dwhipstock.pos.restaurant.CheckLines
import dev.dwhipstock.pos.restaurant.Checks
import dev.dwhipstock.pos.restaurant.ConflictException
import dev.dwhipstock.pos.restaurant.NotFoundException
import dev.dwhipstock.pos.sdk.CustomerConfig
import dev.dwhipstock.pos.sdk.Outbox
import dev.dwhipstock.pos.sdk.StoreProfile
import dev.dwhipstock.pos.sdk.VenueClock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import java.time.Instant

// ---- tables (migration 040) ----

object StockCounts : Table("stock_counts") {
    val id = varchar("id", 40)
    val name = varchar("name", 100)
    val status = varchar("status", 12) // OPEN | SUBMITTED | CANCELLED
    val startedBy = varchar("started_by", 64)
    val startedAt = utcTimestamp("started_at")
    val submittedBy = varchar("submitted_by", 64).nullable()
    val approvedBy = varchar("approved_by", 64).nullable()
    val submittedAt = utcTimestamp("submitted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object StockCountLines : Table("stock_count_lines") {
    val countId = varchar("count_id", 40)
    val itemId = varchar("item_id", 64)
    val counterId = varchar("counter_id", 40)
    val qty = integer("qty")
    val countedBy = varchar("counted_by", 64)
    val countedAt = utcTimestamp("counted_at")
    override val primaryKey = PrimaryKey(countId, itemId, counterId)
}

object StockReceipts : Table("stock_receipts") {
    val id = varchar("id", 40)
    val supplier = varchar("supplier", 100)
    val reference = varchar("reference", 100)
    val receivedBy = varchar("received_by", 64)
    val receivedAt = utcTimestamp("received_at")
    override val primaryKey = PrimaryKey(id)
}

object StockReceiptLines : Table("stock_receipt_lines") {
    val receiptId = varchar("receipt_id", 40)
    val itemId = varchar("item_id", 64)
    val qty = integer("qty")
    override val primaryKey = PrimaryKey(receiptId, itemId)
}

/** The cloud's on-hand per product, cached for the "expected" hint (never authoritative). */
object StockExpected : Table("stock_expected") {
    val itemId = varchar("item_id", 64)
    val onHand = integer("on_hand")
    val asOf = utcTimestamp("as_of")
    override val primaryKey = PrimaryKey(itemId)
}

// ---- wire shapes ----

@Serializable
data class StartCountRequest(
    /** Client-minted UUID (offline-safe, idempotent). Omitted → the store mints one. */
    val id: String? = null,
    val name: String? = null,
)

@Serializable
data class CountLineInput(
    val itemId: String? = null,
    /** Alternative to [itemId]: the scanned code (UPC-A / EAN-13 normalized). */
    val barcode: String? = null,
    /** This counter's total for the product (a SET, so a resend is harmless). */
    val qty: Int = 0,
    /** When it was counted (ISO instant); omitted → now. */
    val countedAt: String? = null,
    /** True → forget this counter's line for the product. */
    val remove: Boolean = false,
)

@Serializable
data class CountLinesRequest(
    /** Which phone/tablet counted these (its install id). Sums across counters. */
    val counterId: String,
    val lines: List<CountLineInput>,
)

@Serializable
data class SubmitCountRequest(val managerPin: String? = null)

@Serializable
data class CountLineView(
    val itemId: String,
    val name: String,
    val barcode: String? = null,
    /** Summed over every counter. */
    val counted: Int,
    /** This counter's own share (when the request named one). */
    val mine: Int? = null,
    /** The best-effort expected on hand; null = no expected qty. */
    val expected: Int? = null,
    /** counted − expected; null when there is no expected qty. */
    val variance: Int? = null,
    val countedAt: String,
)

@Serializable
data class CountView(
    val id: String,
    val name: String,
    val status: String,
    val startedBy: String,
    val startedByName: String,
    val startedAt: String,
    val submittedBy: String? = null,
    val approvedBy: String? = null,
    val submittedAt: String? = null,
    val lines: List<CountLineView> = emptyList(),
    /** Lines whose count differs from the expected qty. */
    val varianceLines: Int = 0,
    /** Submitting needs a manager when there is any variance. */
    val needsApproval: Boolean = false,
)

@Serializable
data class CountSummary(
    val id: String, val name: String, val status: String, val startedByName: String,
    val startedAt: String, val submittedAt: String? = null, val products: Int, val units: Int,
)

@Serializable
data class ReceiptLineInput(val itemId: String? = null, val barcode: String? = null, val qty: Int)

@Serializable
data class ReceiveRequest(
    /** Client-minted UUID (offline-safe, idempotent). Omitted → the store mints one. */
    val id: String? = null,
    val supplier: String = "",
    val reference: String = "",
    val lines: List<ReceiptLineInput>,
)

@Serializable
data class ReceiptLineView(val itemId: String, val name: String, val qty: Int)

@Serializable
data class ReceiptView(
    val id: String, val supplier: String, val reference: String, val receivedBy: String,
    val receivedByName: String, val receivedAt: String, val lines: List<ReceiptLineView>, val units: Int,
)

@Serializable
data class ExpectedView(
    /** False when the store has no on-hand from the cloud yet (offline, never synced). */
    val available: Boolean,
    /** The cloud figure's instant, if any. */
    val asOf: String? = null,
    /** itemId → expected on hand. Products with no data are absent. */
    val items: Map<String, Int> = emptyMap(),
)

/** One cloud on-hand row as pulled (CONTRACT §9). */
data class CloudOnHand(val itemId: String, val onHand: Int)

/**
 * Counting and receiving stock in the store (retail only), offline-first.
 * Phones and the counter tablet drive the same endpoints; ids are the
 * client's own UUIDs so every call is safe to repeat. Nothing here ever
 * blocks a sale: stock is advisory, and on hand is the cloud's ledger.
 */
class StockService(private val config: CustomerConfig) {

    companion object {
        const val MAX_QTY = 100_000
        private val ID = Regex("^[A-Za-z0-9-]{8,40}$")
    }

    private fun requireRetail() {
        if (config.profile.kind != StoreProfile.Kind.RETAIL)
            throw ConflictException("this store doesn't count stock", "not_retail")
    }

    private fun cleanId(raw: String?): String {
        val id = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return java.util.UUID.randomUUID().toString()
        if (!ID.matches(id)) throw BadRequestException("id must be 8-40 letters, digits or dashes", "bad_id")
        return id
    }

    private fun parseAt(raw: String?, now: Instant): Instant {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return now
        val at = runCatching { java.time.OffsetDateTime.parse(s).toInstant() }.getOrNull()
            ?: throw BadRequestException("countedAt must be an ISO instant with an offset", "bad_time")
        // a phone clock far ahead must not push a count past later sales
        return if (at.isAfter(now)) now else at
    }

    private fun staffName(id: String): String =
        Users.selectAll().where { Users.id eq id }.firstOrNull()?.get(Users.name) ?: id

    /** itemId → (name, barcode) for live products. */
    private fun products(): Map<String, Pair<String, String?>> =
        Items.selectAll().where { Items.deletedAt.isNull() }
            .associate { it[Items.id] to ((it[Items.nameEn].ifBlank { it[Items.nameFr] }) to it[Items.barcode]) }

    private fun resolveItem(itemId: String?, barcode: String?, products: Map<String, Pair<String, String?>>): String {
        itemId?.trim()?.takeIf { it.isNotEmpty() }?.let { id ->
            if (id !in products) throw NotFoundException("no product $id", "unknown_item")
            return id
        }
        val code = barcode?.let(Upc::normalize)?.takeIf { it.isNotEmpty() }
            ?: throw BadRequestException("a line needs an itemId or a barcode", "bad_line")
        return products.entries.firstOrNull { it.value.second == code }?.key
            ?: throw NotFoundException("no product with barcode $code", "unknown_barcode")
    }

    // ---- count sessions ----

    fun startCount(req: StartCountRequest, userId: String): CountView = transaction {
        requireRetail()
        val id = cleanId(req.id)
        if (StockCounts.selectAll().where { StockCounts.id eq id }.empty()) {
            val now = VenueClock.now()
            val name = req.name?.trim()?.take(100)?.takeIf { it.isNotEmpty() }
                ?: "Count ${VenueClock.local(now).toLocalDate()}"
            StockCounts.insert {
                it[StockCounts.id] = id
                it[StockCounts.name] = name
                it[status] = "OPEN"
                it[startedBy] = userId
                it[startedAt] = now
            }
        }
        countView(id, null)
    }

    /** Open sessions first, then the latest submitted ones. */
    fun listCounts(): List<CountSummary> = transaction {
        requireRetail()
        val sums = StockCountLines.selectAll().toList().groupBy { it[StockCountLines.countId] }
        StockCounts.selectAll().where { StockCounts.status neq "CANCELLED" }
            .orderBy(StockCounts.startedAt, SortOrder.DESC).limit(40).toList()
            .sortedBy { if (it[StockCounts.status] == "OPEN") 0 else 1 }
            .map { r ->
                val lines = sums[r[StockCounts.id]].orEmpty()
                CountSummary(
                    r[StockCounts.id], r[StockCounts.name], r[StockCounts.status], staffName(r[StockCounts.startedBy]),
                    VenueClock.iso(r[StockCounts.startedAt]), r[StockCounts.submittedAt]?.let(VenueClock::iso),
                    products = lines.map { it[StockCountLines.itemId] }.distinct().size,
                    units = lines.sumOf { it[StockCountLines.qty] },
                )
            }
    }

    fun getCount(id: String, counterId: String? = null): CountView = transaction {
        requireRetail()
        countView(id, counterId)
    }

    private fun requireCount(id: String) =
        StockCounts.selectAll().where { StockCounts.id eq id }.firstOrNull()
            ?: throw NotFoundException("no count $id", "count_not_found")

    /** Set this counter's quantities (idempotent: a SET per product, not an increment). */
    fun setLines(id: String, req: CountLinesRequest, userId: String): CountView = transaction {
        requireRetail()
        val count = requireCount(id)
        if (count[StockCounts.status] != "OPEN")
            throw ConflictException("count $id is ${count[StockCounts.status].lowercase()}", "count_closed")
        val counter = req.counterId.trim()
        if (!ID.matches(counter)) throw BadRequestException("counterId must be 8-40 letters, digits or dashes", "bad_id")
        if (req.lines.size > 2000) throw BadRequestException("too many lines at once", "bad_line")
        val products = products()
        val now = VenueClock.now()
        for (line in req.lines) {
            val itemId = resolveItem(line.itemId, line.barcode, products)
            if (line.remove) {
                StockCountLines.deleteWhere {
                    (countId eq id) and (StockCountLines.itemId eq itemId) and (counterId eq counter)
                }
                continue
            }
            if (line.qty !in 0..MAX_QTY) throw BadRequestException("quantity must be 0-$MAX_QTY", "bad_qty")
            val at = parseAt(line.countedAt, now)
            StockCountLines.upsert {
                it[countId] = id
                it[StockCountLines.itemId] = itemId
                it[counterId] = counter
                it[qty] = line.qty
                it[countedBy] = userId
                it[countedAt] = at
            }
        }
        countView(id, counter)
    }

    /** Whether submitting [id] needs a manager: it is still open and some product's count differs from expected. */
    fun needsApproval(id: String): Boolean = transaction {
        requireRetail()
        val count = requireCount(id)
        count[StockCounts.status] == "OPEN" && countView(id, null).varianceLines > 0
    }

    /**
     * Submit a count: it becomes the product's on hand as of each line's
     * count time (the cloud ledger). Idempotent — a submitted count answers
     * as it is. [approvedBy] is the manager who approved a variance, if any.
     */
    fun submit(id: String, userId: String, approvedBy: String?): CountView = transaction {
        requireRetail()
        val count = requireCount(id)
        when (count[StockCounts.status]) {
            "SUBMITTED" -> return@transaction countView(id, null)
            "CANCELLED" -> throw ConflictException("count $id was discarded", "count_closed")
        }
        val view = countView(id, null)
        if (view.lines.isEmpty()) throw BadRequestException("nothing has been counted yet", "count_empty")
        val now = VenueClock.now()
        StockCounts.update({ StockCounts.id eq id }) {
            it[status] = "SUBMITTED"
            it[submittedBy] = userId
            it[StockCounts.approvedBy] = approvedBy
            it[submittedAt] = now
        }
        Outbox.write("stock.counted", "stock_count", id, buildJsonObject {
            put("countId", id)
            put("name", view.name)
            put("startedBy", count[StockCounts.startedBy])
            put("startedAt", VenueClock.iso(count[StockCounts.startedAt]))
            put("submittedBy", userId)
            put("submittedByName", staffName(userId))
            approvedBy?.let { put("approvedBy", it) }
            put("submittedAt", VenueClock.iso(now))
            putJsonArray("lines") {
                view.lines.forEach { l ->
                    addJsonObject {
                        put("itemId", l.itemId)
                        put("countedQty", l.counted)
                        put("countedAt", l.countedAt)
                        l.expected?.let { put("expectedQty", it) }
                    }
                }
            }
        })
        countView(id, null)
    }

    /** Discard an open count (nothing is sent). Idempotent. */
    fun cancel(id: String): CountView = transaction {
        requireRetail()
        val count = requireCount(id)
        if (count[StockCounts.status] == "SUBMITTED")
            throw ConflictException("count $id was already submitted", "count_closed")
        StockCounts.update({ StockCounts.id eq id }) { it[status] = "CANCELLED" }
        countView(id, null)
    }

    private fun countView(id: String, counterId: String?): CountView {
        val count = requireCount(id)
        val products = Items.selectAll().associate {
            it[Items.id] to ((it[Items.nameEn].ifBlank { it[Items.nameFr] }) to it[Items.barcode])
        }
        val rows = StockCountLines.selectAll().where { StockCountLines.countId eq id }.toList()
        // each product is compared with what was expected when it was counted
        val ledger = expectedLedger(excludeCount = id)
        val lines = rows.groupBy { it[StockCountLines.itemId] }.map { (itemId, rs) ->
            val counted = rs.sumOf { it[StockCountLines.qty] }
            val countedAt = rs.maxOf { it[StockCountLines.countedAt] }
            val exp = ledger.at(itemId, countedAt)
            CountLineView(
                itemId = itemId,
                name = products[itemId]?.first ?: itemId,
                barcode = products[itemId]?.second,
                counted = counted,
                mine = counterId?.let { c -> rs.firstOrNull { it[StockCountLines.counterId] == c }?.get(StockCountLines.qty) },
                expected = exp,
                variance = exp?.let { counted - it },
                countedAt = VenueClock.iso(countedAt),
            )
        }.sortedBy { it.name.lowercase() }
        val varianceLines = lines.count { (it.variance ?: 0) != 0 }
        return CountView(
            id = id,
            name = count[StockCounts.name],
            status = count[StockCounts.status],
            startedBy = count[StockCounts.startedBy],
            startedByName = staffName(count[StockCounts.startedBy]),
            startedAt = VenueClock.iso(count[StockCounts.startedAt]),
            submittedBy = count[StockCounts.submittedBy],
            approvedBy = count[StockCounts.approvedBy],
            submittedAt = count[StockCounts.submittedAt]?.let(VenueClock::iso),
            lines = lines,
            varianceLines = varianceLines,
            needsApproval = count[StockCounts.status] == "OPEN" && varianceLines > 0,
        )
    }

    // ---- receiving ----

    /** A delivery: scanned products + quantities. Idempotent by id. */
    fun receive(req: ReceiveRequest, userId: String): ReceiptView = transaction {
        requireRetail()
        val id = cleanId(req.id)
        if (StockReceipts.selectAll().where { StockReceipts.id eq id }.any()) return@transaction receiptView(id)
        if (req.lines.isEmpty()) throw BadRequestException("a delivery needs at least one product", "receipt_empty")
        if (req.lines.size > 2000) throw BadRequestException("too many lines at once", "bad_line")
        val products = products()
        val merged = linkedMapOf<String, Int>()
        for (line in req.lines) {
            if (line.qty !in 1..MAX_QTY) throw BadRequestException("quantity must be 1-$MAX_QTY", "bad_qty")
            val itemId = resolveItem(line.itemId, line.barcode, products)
            merged[itemId] = (merged[itemId] ?: 0) + line.qty
        }
        if (merged.values.any { it > MAX_QTY }) throw BadRequestException("quantity must be 1-$MAX_QTY", "bad_qty")
        val now = VenueClock.now()
        val supplier = req.supplier.trim().take(100)
        val reference = req.reference.trim().take(100)
        StockReceipts.insert {
            it[StockReceipts.id] = id
            it[StockReceipts.supplier] = supplier
            it[StockReceipts.reference] = reference
            it[receivedBy] = userId
            it[receivedAt] = now
        }
        merged.forEach { (itemId, qty) ->
            StockReceiptLines.insert {
                it[receiptId] = id
                it[StockReceiptLines.itemId] = itemId
                it[StockReceiptLines.qty] = qty
            }
        }
        Outbox.write("stock.received", "stock_receipt", id, buildJsonObject {
            put("receiptId", id)
            put("supplier", supplier)
            put("reference", reference)
            put("receivedBy", userId)
            put("receivedByName", staffName(userId))
            put("receivedAt", VenueClock.iso(now))
            putJsonArray("lines") {
                merged.forEach { (itemId, qty) -> addJsonObject { put("itemId", itemId); put("qty", qty) } }
            }
        })
        receiptView(id)
    }

    fun recentReceipts(limit: Int = 30): List<ReceiptView> = transaction {
        requireRetail()
        StockReceipts.selectAll().orderBy(StockReceipts.receivedAt, SortOrder.DESC).limit(limit)
            .map { receiptView(it[StockReceipts.id]) }
    }

    private fun receiptView(id: String): ReceiptView {
        val r = StockReceipts.selectAll().where { StockReceipts.id eq id }.first()
        val names = Items.selectAll().associate { it[Items.id] to it[Items.nameEn].ifBlank { it[Items.nameFr] } }
        val lines = StockReceiptLines.selectAll().where { StockReceiptLines.receiptId eq id }
            .map { ReceiptLineView(it[StockReceiptLines.itemId], names[it[StockReceiptLines.itemId]] ?: it[StockReceiptLines.itemId], it[StockReceiptLines.qty]) }
        return ReceiptView(
            id, r[StockReceipts.supplier], r[StockReceipts.reference], r[StockReceipts.receivedBy],
            staffName(r[StockReceipts.receivedBy]), VenueClock.iso(r[StockReceipts.receivedAt]), lines, lines.sumOf { it.qty },
        )
    }

    // ---- the expected-on-hand hint ----

    fun expected(): ExpectedView = transaction {
        requireRetail()
        val asOf = StockExpected.selectAll().limit(1).firstOrNull()?.get(StockExpected.asOf)
        val ledger = expectedLedger(excludeCount = null)
        val items = ledger.itemIds.associateWith { ledger.at(it)!! }
        ExpectedView(available = asOf != null || items.isNotEmpty(), asOf = asOf?.let(VenueClock::iso), items = items)
    }

    /**
     * Best-effort expected on hand (call in a transaction): start from the
     * cloud's figure (as of when it was computed), or from a count submitted
     * here since then, and apply what this store did after that — sales out,
     * deliveries in. [excludeCount] leaves that count out of the starting
     * points (a count is never compared with itself).
     */
    internal fun expectedLedger(excludeCount: String?): ExpectedLedger {
        val base = HashMap<String, ExpectedLedger.Base>()
        StockExpected.selectAll().forEach {
            base[it[StockExpected.itemId]] = ExpectedLedger.Base(it[StockExpected.onHand], it[StockExpected.asOf])
        }
        // counts submitted here since: their total is on hand as of their count time
        val submitted = StockCounts.selectAll().where { StockCounts.status eq "SUBMITTED" }
            .map { it[StockCounts.id] }.filter { it != excludeCount }
        if (submitted.isNotEmpty()) {
            StockCountLines.selectAll().where { StockCountLines.countId inList submitted }.toList()
                .groupBy { it[StockCountLines.countId] to it[StockCountLines.itemId] }
                .forEach { (key, rs) ->
                    val at = rs.maxOf { it[StockCountLines.countedAt] }
                    val current = base[key.second]
                    if (current == null || at.isAfter(current.at))
                        base[key.second] = ExpectedLedger.Base(rs.sumOf { it[StockCountLines.qty] }, at)
                }
        }
        if (base.isEmpty()) return ExpectedLedger(base, emptyMap())
        val from = base.values.minOf { it.at }
        val deltas = HashMap<String, MutableList<Pair<Instant, Int>>>()
        // sales out since the earliest starting point
        CheckLines.join(Checks, org.jetbrains.exposed.sql.JoinType.INNER, CheckLines.checkId, Checks.id)
            .select(CheckLines.itemId, CheckLines.qty, Checks.closedAt)
            .where {
                (Checks.status eq "CLOSED") and (CheckLines.status eq "ACTIVE") and CheckLines.itemId.isNotNull() and
                    (Checks.closedAt greater from)
            }
            .forEach { r ->
                val at = r[Checks.closedAt] ?: return@forEach
                deltas.getOrPut(r[CheckLines.itemId]!!) { mutableListOf() }.add(at to -r[CheckLines.qty])
            }
        // deliveries in
        StockReceiptLines.join(StockReceipts, org.jetbrains.exposed.sql.JoinType.INNER, StockReceiptLines.receiptId, StockReceipts.id)
            .select(StockReceiptLines.itemId, StockReceiptLines.qty, StockReceipts.receivedAt)
            .where { StockReceipts.receivedAt greater from }
            .forEach { r ->
                deltas.getOrPut(r[StockReceiptLines.itemId]) { mutableListOf() }
                    .add(r[StockReceipts.receivedAt] to r[StockReceiptLines.qty])
            }
        return ExpectedLedger(base, deltas)
    }

    /**
     * Replace the cached cloud figures (the slow pull). [asOf] is the instant
     * up to which the cloud had this store's events. Read-only data.
     */
    fun applyCloudOnHand(asOf: Instant, rows: List<CloudOnHand>) = transaction {
        StockExpected.deleteAll()
        rows.forEach { row ->
            StockExpected.insert {
                it[itemId] = row.itemId
                it[onHand] = row.onHand.coerceIn(-MAX_QTY * 10, MAX_QTY * 10)
                it[StockExpected.asOf] = asOf
            }
        }
    }

    /**
     * The instant the cloud has everything before: just before the first
     * outbox event it has not acknowledged, or now when it has them all.
     */
    fun cloudCaughtUpAt(pushHwm: Long): Instant = transaction {
        SyncOutbox.selectAll().where { SyncOutbox.id greater pushHwm.toInt() }
            .orderBy(SyncOutbox.id).limit(1).firstOrNull()
            ?.get(SyncOutbox.createdAt)?.minusMillis(1)
            ?: VenueClock.now()
    }

}

/**
 * Per product: a starting figure at an instant, plus the timestamped moves
 * this store made since. [at] evaluates it at any instant (a count line is
 * compared with what was expected when it was counted, not later).
 */
class ExpectedLedger internal constructor(
    private val base: Map<String, Base>,
    private val deltas: Map<String, List<Pair<Instant, Int>>>,
) {
    data class Base(val qty: Int, val at: Instant)

    val itemIds: Set<String> get() = base.keys

    /** Expected on hand of [itemId] at [instant] (null = now); null when there is no data. */
    fun at(itemId: String, instant: Instant? = null): Int? {
        val b = base[itemId] ?: return null
        val moves = deltas[itemId] ?: return b.qty
        return b.qty + moves.filter { it.first.isAfter(b.at) && (instant == null || !it.first.isAfter(instant)) }
            .sumOf { it.second }
    }
}
