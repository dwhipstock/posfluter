package dev.dwhipstock.pos.base

import dev.dwhipstock.pos.sdk.MenuSpecials
import dev.dwhipstock.pos.sdk.VenueClock
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.upsert
import java.time.Instant

/**
 * An item's specials and selling days (migration 064), in a side table so the
 * shared `items` table keeps its shape. One row per item that has any; no row
 * = sold every day at its menu price. `available_days` is "fri,sat" (NULL =
 * every day); `specials_json` the canonical JSON list of
 * [MenuSpecials.Special] (NULL = none).
 */
object ItemSpecials : Table("item_specials") {
    val itemId = varchar("item_id", 64).references(Items.id)
    val availableDays = varchar("available_days", 40).nullable()
    val specialsJson = text("specials_json").nullable()
    override val primaryKey = PrimaryKey(itemId)
}

/** Read and write [ItemSpecials] (inside a transaction), and price a line with them. */
object ItemSchedules {
    private val json = Json { ignoreUnknownKeys = true }

    /** Whether this database has the table yet (older code migrations snapshot items before 064). */
    fun present(): Boolean {
        var has = false
        TransactionManager.current()
            .exec("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'item_specials'") { has = it.next() }
        return has
    }

    private fun scheduleOf(row: ResultRow) = MenuSpecials.Schedule(
        availableDays = row[ItemSpecials.availableDays]?.split(',')?.filter { it.isNotBlank() }
            ?.let { runCatching { MenuSpecials.normalizeDays(it) }.getOrDefault(emptyList()) }.orEmpty(),
        specials = row[ItemSpecials.specialsJson]
            ?.let { runCatching { MenuSpecials.specialsOf(Json.parseToJsonElement(it)) }.getOrNull() }.orEmpty(),
    )

    fun of(itemId: String): MenuSpecials.Schedule {
        if (!present()) return MenuSpecials.Schedule()
        return ItemSpecials.selectAll().where { ItemSpecials.itemId eq itemId }.firstOrNull()?.let(::scheduleOf)
            ?: MenuSpecials.Schedule()
    }

    /** Every item's schedule that has one. */
    fun all(): Map<String, MenuSpecials.Schedule> {
        if (!present()) return emptyMap()
        return ItemSpecials.selectAll().associate { it[ItemSpecials.itemId] to scheduleOf(it) }
            .filterValues { !it.isEmpty }
    }

    /** Replace [itemId]'s schedule (already normalized). An empty one removes the row. */
    fun set(itemId: String, schedule: MenuSpecials.Schedule) {
        if (schedule.isEmpty) {
            ItemSpecials.deleteWhere { ItemSpecials.itemId eq itemId }
            return
        }
        ItemSpecials.upsert {
            it[ItemSpecials.itemId] = itemId
            it[availableDays] = schedule.availableDays.takeIf { d -> d.isNotEmpty() }?.joinToString(",")
            it[specialsJson] = schedule.specials.takeIf { s -> s.isNotEmpty() }
                ?.let { s -> MenuSpecials.specialsJson(s).toString() }
        }
    }

    /** The venue's business moment now (or at [at]). */
    fun moment(at: Instant = VenueClock.now(clock)): MenuSpecials.Moment = MenuSpecials.moment(at, VenueClock.zone)

    /** Test seam: the clock specials are priced by (a Tuesday, 5:59 pm…). */
    @Volatile
    var clock: java.time.Clock = java.time.Clock.systemUTC()

    /** A line's price as rung now: the unit price, and the special it came from (null = the menu price). */
    data class Priced(val unitPriceCents: Long, val regularCents: Long, val special: MenuSpecials.Special?) {
        val tag: MenuSpecials.Tag? get() = special?.let { MenuSpecials.Tag(it.days, it.from, it.to, it.label) }
    }

    fun price(itemId: String, variantId: String, regularCents: Long, at: MenuSpecials.Moment = moment()): Priced {
        val (p, sp) = MenuSpecials.priceAt(of(itemId), variantId, regularCents, at)
        return Priced(p, regularCents, sp)
    }

    /**
     * 0–999, a fingerprint of what the schedules show at [at]: which day-only
     * items are on sale and which specials are in force. It changes when a
     * special starts or ends, so `GET /menu/version` moves and the clients
     * reload their menu (prices) without a menu edit.
     */
    fun stateKey(at: MenuSpecials.Moment = moment()): Long {
        val all = all()
        if (all.isEmpty()) return 0
        val sig = all.toSortedMap().entries.joinToString("|") { (id, s) ->
            id + ":" + (if (MenuSpecials.available(s, at)) "1" else "0") +
                s.specials.joinToString("") { if (MenuSpecials.inForce(it, at)) "1" else "0" }
        }
        return Math.floorMod(sig.hashCode(), 1000).toLong()
    }

    fun tagJson(tag: MenuSpecials.Tag): String = json.encodeToString(MenuSpecials.Tag.serializer(), tag)

    fun tagOf(raw: String?): MenuSpecials.Tag? =
        raw?.let { runCatching { json.decodeFromString(MenuSpecials.Tag.serializer(), it) }.getOrNull() }
}
