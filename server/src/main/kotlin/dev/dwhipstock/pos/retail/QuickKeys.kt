package dev.dwhipstock.pos.retail

import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.QuickKeyPins
import dev.dwhipstock.pos.restaurant.CheckLines
import dev.dwhipstock.pos.restaurant.Checks
import dev.dwhipstock.pos.restaurant.ConflictException
import dev.dwhipstock.pos.restaurant.NotFoundException
import dev.dwhipstock.pos.sdk.VenueClock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Clock
import java.time.Duration
import kotlin.math.ceil

/**
 * The counter's fast lanes at a 5,000-product shop, computed on the store
 * from its own sales (no cloud involved, works offline):
 *
 * - **Quick keys**: a small grid (default 36) of one-tap tiles — what a
 *   manager pinned, the products that have no barcode (a paper bag, a lime),
 *   then the fastest sellers of the last [WINDOW_DAYS] days. A store without
 *   enough sales yet fills the rest from the catalog's popularity weights.
 * - **Top sellers**: the ranked top [TOP_PERCENT]% of the live catalog (the
 *   80/20 set: at 5,000 products that is ~1,000, far too many for keys but a
 *   good scrolling list), best first.
 */
class QuickKeys(private val clock: Clock = Clock.systemUTC()) {
    companion object {
        const val WINDOW_DAYS = 28
        const val DEFAULT_KEYS = 36
        const val MAX_KEYS = 48
        const val MAX_PINS = 48
        const val MAX_UNSCANNABLE = 6
        const val TOP_PERCENT = 20
    }

    /** Units sold per product on closed sales in the last [days] days. */
    fun unitsSold(days: Int = WINDOW_DAYS): Map<String, Long> = transaction {
        val since = VenueClock.now(clock).minus(Duration.ofDays(days.toLong()))
        val units = CheckLines.qty.sum()
        (CheckLines innerJoin Checks)
            .select(CheckLines.itemId, units)
            .where {
                (Checks.status eq "CLOSED") and (Checks.closedAt greaterEq since) and
                    (CheckLines.status eq "ACTIVE") and CheckLines.itemId.isNotNull()
            }
            .groupBy(CheckLines.itemId)
            .associate { it[CheckLines.itemId]!! to (it[units]?.toLong() ?: 0L) }
    }

    private data class Live(val id: String, val weight: Int, val barcodeless: Boolean)

    private fun liveItems(): List<Live> = Items.selectAll()
        .where { Items.deletedAt.isNull() and (Items.active eq true) }
        .map { Live(it[Items.id], it[Items.salesWeight], it[Items.barcode].isNullOrBlank()) }

    /** Every live product, best seller first: units in the window, then popularity weight, then id. */
    private fun ranked(units: Map<String, Long>, live: List<Live>): List<Live> =
        live.sortedWith(
            compareByDescending<Live> { units[it.id] ?: 0L }
                .thenByDescending { it.weight }
                .thenBy { it.id },
        )

    fun quickKeys(limit: Int = DEFAULT_KEYS): QuickKeysView = transaction {
        val n = limit.coerceIn(1, MAX_KEYS)
        val units = unitsSold()
        val live = liveItems()
        val liveIds = live.associateBy { it.id }
        val out = LinkedHashMap<String, QuickKey>()
        QuickKeyPins.selectAll().orderBy(QuickKeyPins.sortOrder).orderBy(QuickKeyPins.pinnedAt).forEach {
            val id = it[QuickKeyPins.itemId]
            if (id in liveIds && out.size < n) out[id] = QuickKey(id, true, units[id] ?: 0, "pin")
        }
        val order = ranked(units, live)
        order.filter { it.barcodeless }.take(MAX_UNSCANNABLE).forEach {
            if (out.size < n && it.id !in out) out[it.id] = QuickKey(it.id, false, units[it.id] ?: 0, "unscannable")
        }
        for (p in order) {
            if (out.size >= n) break
            if (p.id in out) continue
            val sold = units[p.id] ?: 0
            out[p.id] = QuickKey(p.id, false, sold, if (sold > 0) "velocity" else "popular")
        }
        QuickKeysView(out.values.toList(), WINDOW_DAYS, VenueClock.iso(VenueClock.now(clock)))
    }

    fun topSellers(days: Int = WINDOW_DAYS, percent: Int = TOP_PERCENT): TopSellersView = transaction {
        val units = unitsSold(days.coerceIn(1, 365))
        val live = liveItems()
        val take = ceil(live.size * percent.coerceIn(1, 100) / 100.0).toInt()
        val rows = ranked(units, live).take(take).mapIndexed { i, p -> TopSeller(p.id, i + 1, units[p.id] ?: 0) }
        TopSellersView(rows, live.size, days, percent)
    }

    fun pin(itemId: String, userId: String): QuickKeysView {
        transaction {
            Items.selectAll().where { (Items.id eq itemId) and Items.deletedAt.isNull() }.firstOrNull()
                ?: throw NotFoundException("item $itemId not found")
            val count = QuickKeyPins.selectAll().count()
            val already = QuickKeyPins.selectAll().where { QuickKeyPins.itemId eq itemId }.any()
            if (!already && count >= MAX_PINS) throw ConflictException("at most $MAX_PINS pinned keys", "too_many_pins")
            val next = (QuickKeyPins.selectAll().maxOfOrNull { it[QuickKeyPins.sortOrder] } ?: -1) + 1
            QuickKeyPins.insertIgnore {
                it[QuickKeyPins.itemId] = itemId
                it[sortOrder] = next
                it[pinnedBy] = userId
                it[pinnedAt] = VenueClock.now(clock)
            }
        }
        return quickKeys()
    }

    fun unpin(itemId: String): QuickKeysView {
        transaction { QuickKeyPins.deleteWhere { QuickKeyPins.itemId eq itemId } }
        return quickKeys()
    }
}

@Serializable
data class QuickKey(
    val itemId: String,
    val pinned: Boolean,
    /** Units sold in the window. */
    val units: Long,
    /** pin | unscannable | velocity | popular (no sales yet: the catalog's popularity) */
    val source: String,
)

@Serializable
data class QuickKeysView(val keys: List<QuickKey>, val windowDays: Int, val generatedAt: String)

@Serializable
data class TopSeller(val itemId: String, val rank: Int, val units: Long)

@Serializable
data class TopSellersView(val items: List<TopSeller>, val catalogSize: Int, val windowDays: Int, val percent: Int)

@Serializable
data class PinRequest(val itemId: String, val managerPin: String? = null)
