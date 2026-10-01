package dev.dwhipstock.pos

import dev.dwhipstock.pos.api.categorySnapshotJson
import dev.dwhipstock.pos.api.itemSnapshotJson
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.sync.MenuChange
import dev.dwhipstock.pos.sync.MenuClock
import dev.dwhipstock.pos.sync.MenuFields
import dev.dwhipstock.pos.sync.MenuPage
import dev.dwhipstock.pos.sync.MenuSync
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * A stand-in for the cloud's side of two-way menu sync (CONTRACT §10) in
 * store tests: it builds feed entries exactly as the cloud does — the thing's
 * full state with per-field clocks, the edited fields stamped by the cloud's
 * clock ("…-cloud") — from what the store last told it.
 */
object CloudMenu {
    private var seq = 0L
    private var counter = 0

    /** A cloud stamp [deltaMs] from now. */
    fun stamp(deltaMs: Long = 0): String = "%013d-%04d-cloud".format(System.currentTimeMillis() + deltaMs, counter++ % 10000)

    private fun withClock(obj: JsonObject, entity: String): JsonObject = transaction {
        when (entity) {
            MenuFields.ITEM -> MenuClock.stampItems(listOf(obj), MenuClock.Mode.APPLY).first()
            else -> MenuClock.stampCategories(listOf(obj), MenuClock.Mode.APPLY).first()
        }
    }

    private fun edit(obj: JsonObject, fields: Map<String, JsonElement>, stamp: String): JsonObject {
        val out = LinkedHashMap(obj)
        val clock = LinkedHashMap((obj["clock"] as? JsonObject).orEmpty())
        val names = LinkedHashMap((obj["names"] as? JsonObject).orEmpty())
        for ((f, v) in fields) {
            if (f.startsWith(MenuFields.NAMES)) names[f.removePrefix(MenuFields.NAMES)] = v else out[f] = v
            clock[f] = JsonPrimitive(stamp)
        }
        out["names"] = JsonObject(names)
        out["clock"] = JsonObject(clock)
        return JsonObject(out)
    }

    /**
     * The store's item as the cloud has it, with [fields] (item) and
     * [variants] (variant id → fields) set by a portal edit stamped [stamp].
     */
    fun item(
        id: String, fields: Map<String, JsonElement> = emptyMap(),
        variants: Map<String, Map<String, JsonElement>> = emptyMap(), stamp: String = stamp(),
    ): MenuChange {
        val base = withClock(transaction { itemSnapshotJson(id) }, MenuFields.ITEM)
        return itemFrom(id, base, fields, variants, stamp)
    }

    fun itemFrom(
        id: String, base: JsonObject, fields: Map<String, JsonElement> = emptyMap(),
        variants: Map<String, Map<String, JsonElement>> = emptyMap(), stamp: String = stamp(),
    ): MenuChange {
        val vs = (base["variants"] as JsonArray).map { v ->
            val vo = v.jsonObject
            val vid = (vo["id"] as JsonPrimitive).content
            variants[vid]?.let { edit(vo, it, stamp) } ?: vo
        } + variants.filterKeys { vid -> (base["variants"] as JsonArray).none { (it.jsonObject["id"] as JsonPrimitive).content == vid } }
            .map { (vid, f) -> edit(JsonObject(mapOf("id" to JsonPrimitive(vid))), f, stamp) }
        val item = edit(JsonObject(base + ("variants" to JsonArray(vs))), fields, stamp)
        return MenuChange(++seq, MenuFields.ITEM, id, item)
    }

    fun category(id: String, fields: Map<String, JsonElement>, stamp: String = stamp()): MenuChange {
        val base = transaction { runCatching { categorySnapshotJson(id) }.getOrNull() }
            ?.let { withClock(it, MenuFields.CATEGORY) } ?: JsonObject(mapOf("id" to JsonPrimitive(id)))
        return MenuChange(++seq, MenuFields.CATEGORY, id, edit(base, fields, stamp))
    }

    fun page(vararg changes: MenuChange) = MenuPage(changes.maxOf { it.seq }, System.currentTimeMillis(), changes.toList())

    fun apply(vararg changes: MenuChange) = MenuSync.applyPage(page(*changes))

    fun s(v: String) = JsonPrimitive(v)
    fun b(v: Boolean) = JsonPrimitive(v)
    fun n(v: Long) = JsonPrimitive(v)
}

/** Outbox rows after [afterId]: (eventType, payload). */
fun outboxSince(afterId: Int): List<Pair<String, JsonObject>> = transaction {
    SyncOutbox.selectAll().where { SyncOutbox.id greater afterId }.orderBy(SyncOutbox.id, SortOrder.ASC)
        .map { it[SyncOutbox.eventType] to Json.parseToJsonElement(it[SyncOutbox.payload]).jsonObject }
}

fun lastOutboxId(): Int = transaction {
    SyncOutbox.selectAll().orderBy(SyncOutbox.id, SortOrder.DESC).limit(1).firstOrNull()?.get(SyncOutbox.id)?.value ?: 0
}
