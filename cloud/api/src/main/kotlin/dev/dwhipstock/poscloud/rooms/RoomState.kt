package dev.dwhipstock.poscloud.rooms

import dev.dwhipstock.poscloud.CloudTime
import dev.dwhipstock.poscloud.ConflictException
import dev.dwhipstock.poscloud.catalog.Scope
import dev.dwhipstock.poscloud.db.FloorThings
import dev.dwhipstock.poscloud.db.Venues
import dev.dwhipstock.poscloud.menu.CloudHlc
import dev.dwhipstock.poscloud.menu.Hlc
import dev.dwhipstock.poscloud.menu.MenuState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchUpsert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

/**
 * Two-way room sync, the cloud's half (CONTRACT §11): each store's rooms,
 * dining tables and floor objects as last-write-wins registers — the menu's
 * rule (§10), clock ([CloudHlc], the tenant's menu lock) and feed (menu_feed:
 * the store pulls room entries with its menu changes). The store pushes its
 * floor as `floor.snapshot` events ([ingest]); the portal's edits ([write])
 * are stamped by the cloud's clock and appended to the store's feed. Open
 * bills never leave the store: it only says which tables may not be touched
 * (`locked`), and refuses such a change itself if the portal's view was stale.
 */
object RoomFields {
    const val ROOM = "room"
    const val TABLE = "table"
    const val OBJECT = "floor_object"
    val ENTITIES = listOf(ROOM, TABLE, OBJECT)

    val ROOM_FIELDS = listOf("nameFr", "nameEn", "sortOrder", "labelPrefix", "deleted")
    val TABLE_FIELDS = listOf(
        "zoneId", "label", "parentTableId", "x", "y", "width", "height", "rotation", "shape", "seats", "deleted")
    val OBJECT_FIELDS = listOf(
        "zoneId", "type", "x", "y", "width", "height", "rotation", "labelFr", "labelEn", "icon", "shape", "deleted")

    /** A table with an open bill keeps everything but its seats (the store's rule). */
    val LOCKED_TABLE_EDITABLE = setOf("seats")

    const val NAMES = "names."

    fun base(entity: String) = when (entity) {
        ROOM -> ROOM_FIELDS
        TABLE -> TABLE_FIELDS
        else -> OBJECT_FIELDS
    }

    fun hasNames(entity: String) = entity != TABLE

    fun canon(v: JsonElement?): String = (v ?: JsonNull).toString()
}

/** One floor thing's registers: field → value, field → stamp (`names.<lang>` for extra-language names). */
class Thing(val entity: String, val id: String) {
    val fields = LinkedHashMap<String, JsonElement>()
    val clock = LinkedHashMap<String, String>()
    var locked = false

    fun str(f: String): String? = (fields[f] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    fun int(f: String): Int? = (fields[f] as? JsonPrimitive)?.intOrNull
    val deleted: Boolean get() = (fields["deleted"] as? JsonPrimitive)?.booleanOrNull == true
    fun stamp(f: String): String = clock[f] ?: Hlc.LEGACY
    val zoneId: String? get() = if (entity == RoomFields.ROOM) id else str("zoneId")

    fun set(f: String, v: JsonElement, stamp: String) { fields[f] = v; clock[f] = stamp }

    fun names(): Map<String, String> = fields.filterKeys { it.startsWith(RoomFields.NAMES) }
        .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { k.removePrefix(RoomFields.NAMES) to it } }
        .toMap()

    fun maxEdit(): String = clock.filterKeys { it != "deleted" }.values.maxOrNull() ?: Hlc.LEGACY

    /** A delete older than a later edit of the same thing is undone by that edit (the store's rule too). */
    fun canonicalize() {
        if (!deleted) return
        val m = maxEdit()
        if (m > stamp("deleted")) set("deleted", JsonPrimitive(false), m)
    }

    /** The wire shape the store merges: every field, `names`, `clock`. */
    fun wire(): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        out["id"] = JsonPrimitive(id)
        for (f in RoomFields.base(entity)) out[f] = fields[f] ?: JsonNull
        if (RoomFields.hasNames(entity)) out["names"] = JsonObject(names().toSortedMap().mapValues { JsonPrimitive(it.value) })
        out["clock"] = JsonObject(clock.toSortedMap().mapValues { JsonPrimitive(it.value) })
        return JsonObject(out)
    }

    fun copy(): Thing = Thing(entity, id).also { c -> c.fields.putAll(fields); c.clock.putAll(clock); c.locked = locked }
}

// --- what the portal reads ---

@Serializable
data class FloorTableDto(
    val id: String, val label: String, val number: Int?, val x: Int, val y: Int, val width: Int, val height: Int,
    val rotation: Int, val shape: String, val seats: Int, val parentTableId: String?, val locked: Boolean,
)

@Serializable
data class FloorObjectDto(
    val id: String, val type: String, val x: Int, val y: Int, val width: Int, val height: Int, val rotation: Int,
    val labelEn: String?, val labelFr: String?, val names: Map<String, String>, val icon: String?, val shape: String?,
)

@Serializable
data class RoomDto(
    val id: String, val nameEn: String, val nameFr: String, val names: Map<String, String>, val sortOrder: Int,
    val labelPrefix: String, val tables: List<FloorTableDto>, val objects: List<FloorObjectDto>,
)

@Serializable
data class RoomsResponse(
    val venueId: String, val venueName: String,
    /** The store applies room changes (it pulls the feed with `rooms=1`): the portal may change its floor. */
    val editable: Boolean,
    val lastPullAt: String?,
    /** This user may change rooms (owner / manager). */
    val canEdit: Boolean,
    val rooms: List<RoomDto>,
)

/** One room as the AI planner sees it: live tables (numbered), which are locked, the sub-table links, objects. */
class RoomModel(
    val zoneId: String, val name: String, val prefix: String,
    val tables: List<RoomTableDto>, val parents: Map<String, String?>, val locked: Set<String>,
    val objects: List<RoomObjectDto>,
) {
    /** Numbers in use by this room's live tables, except [except] (numbering is per room). */
    fun usedNumbers(except: Set<String> = emptySet()): Set<Int> =
        tables.filter { it.id !in except }.mapNotNull { number(it.label) }.toSet()

    companion object {
        fun number(label: String) = Regex("(\\d+)$").find(label.trim())?.value?.toIntOrNull()
    }
}

object RoomState {
    private val json = Json { ignoreUnknownKeys = true }

    private fun obj(raw: String): Map<String, JsonElement> = runCatching { json.parseToJsonElement(raw) as JsonObject }.getOrNull().orEmpty()

    // --- loading and saving (inside a transaction) ---

    /** Every floor thing of one store (deleted ones too: they are tombstones). Keyed (entity, id). */
    fun load(scope: Scope): LinkedHashMap<Pair<String, String>, Thing> {
        val out = LinkedHashMap<Pair<String, String>, Thing>()
        FloorThings.selectAll().where { (FloorThings.tenantId eq scope.tenantId) and (FloorThings.venueId eq scope.venueId) }
            .forEach { row ->
                val t = Thing(row[FloorThings.entity], row[FloorThings.id])
                obj(row[FloorThings.values]).forEach { (k, v) -> t.fields[k] = v }
                obj(row[FloorThings.clock]).forEach { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { t.clock[k] = it } }
                t.locked = row[FloorThings.locked]
                out[t.entity to t.id] = t
            }
        return out
    }

    fun save(scope: Scope, things: Collection<Thing>) {
        if (things.isEmpty()) return
        val now = CloudTime.now()
        FloorThings.batchUpsert(things, shouldReturnGeneratedValues = false) { t ->
            this[FloorThings.tenantId] = scope.tenantId
            this[FloorThings.venueId] = scope.venueId
            this[FloorThings.entity] = t.entity
            this[FloorThings.id] = t.id
            this[FloorThings.zoneId] = t.zoneId
            this[FloorThings.values] = JsonObject(t.fields).toString()
            this[FloorThings.clock] = JsonObject(t.clock.toSortedMap().mapValues { JsonPrimitive(it.value) }).toString()
            this[FloorThings.deleted] = t.deleted
            this[FloorThings.locked] = t.locked
            this[FloorThings.updatedAt] = now
        }
    }

    // --- the store's floor in (ingest of `floor.snapshot`) ---

    private fun regsOf(entity: String, snap: JsonObject, id: String): Thing {
        val t = Thing(entity, id)
        for (f in RoomFields.base(entity)) if (f in snap) t.fields[f] = snap[f]!!
        if (RoomFields.hasNames(entity)) (snap["names"] as? JsonObject)?.forEach { (lang, v) ->
            val code = lang.trim().lowercase()
            val text = (v as? JsonPrimitive)?.contentOrNull?.trim()
            if (code.isNotEmpty()) t.fields[RoomFields.NAMES + code] = if (text.isNullOrEmpty()) JsonNull else JsonPrimitive(text)
        }
        (snap["clock"] as? JsonObject)?.forEach { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { t.clock[k] = it } }
        return t
    }

    /** The menu's merge: a greater stamp wins; two "before sync" stamps let the store's value through. */
    private fun merge(target: Thing, incoming: Thing, namesComplete: Boolean) {
        val keys = LinkedHashSet(incoming.fields.keys)
        if (namesComplete) {
            keys += incoming.clock.keys.filter { it.startsWith(RoomFields.NAMES) }
            keys += target.fields.keys.filter { it.startsWith(RoomFields.NAMES) }
        }
        for (f in keys) {
            val inStamp = incoming.stamp(f)
            val exStamp = target.stamp(f)
            if (inStamp > exStamp || (inStamp == Hlc.LEGACY && exStamp == Hlc.LEGACY)) {
                target.fields[f] = incoming.fields[f] ?: JsonNull
                if (inStamp == Hlc.LEGACY) target.clock.remove(f) else target.clock[f] = inStamp
            }
        }
    }

    /** A stamped store field whose merged value is not the store's: it lost here and must be told (`correction`). */
    private fun lost(incoming: Thing, merged: Thing): Boolean = incoming.clock.any { (f, stamp) ->
        stamp.isNotEmpty() && RoomFields.canon(incoming.fields[f]) != RoomFields.canon(merged.fields[f])
    }

    /**
     * A store's `floor.snapshot`: its changed rooms, tables and objects with
     * their clocks, merged per field (implausibly future stamps re-stamped and
     * the store told, losing store writes corrected), and the tables it says
     * may not be touched (`locked`, the full list).
     */
    fun ingest(scope: Scope, payload: JsonObject) {
        CloudHlc.lock(scope.tenantId) // before reading: a portal edit committing meanwhile is not overwritten
        val stored = load(scope)
        var eventStamp: String? = null
        val now = { eventStamp ?: CloudHlc.now(scope.tenantId).also { eventStamp = it } }
        val out = LinkedHashMap<Pair<String, String>, Thing>()
        val restamped = mutableListOf<Thing>()
        val corrected = mutableListOf<Thing>()
        for ((key, entity) in listOf("rooms" to RoomFields.ROOM, "tables" to RoomFields.TABLE, "objects" to RoomFields.OBJECT)) {
            (payload[key] as? JsonArray)?.filterIsInstance<JsonObject>()?.forEach { snap ->
                val id = (snap["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= 160 } ?: return@forEach
                val existing = stored[entity to id]
                val target = existing?.copy() ?: Thing(entity, id)
                val incoming = regsOf(entity, snap, id)
                var clamped = false
                for ((f, st) in incoming.clock.toMap()) {
                    if (st.isEmpty()) continue
                    if (CloudHlc.plausible(st)) CloudHlc.observe(scope.tenantId, st)
                    else { incoming.clock[f] = now(); clamped = true }
                }
                merge(target, incoming, "names" in snap || !RoomFields.hasNames(entity))
                target.canonicalize()
                if (clamped) restamped += target
                else if (existing != null && lost(incoming, target)) corrected += target
                out[entity to id] = target
            }
        }
        // the locked list is complete: every other table of the store is free
        (payload["locked"] as? JsonArray)?.let { arr ->
            val ids = arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
            for ((k, t) in stored) if (k.first == RoomFields.TABLE && t.locked != (t.id in ids)) {
                val target = out.getOrPut(k) { t.copy() }
                target.locked = t.id in ids
            }
            for ((k, t) in out) if (k.first == RoomFields.TABLE) t.locked = t.id in ids
        }
        save(scope, out.values)
        for (t in restamped) MenuState.appendFeed(scope, t.entity, t.id, JsonObject(t.wire() + ("restamp" to JsonPrimitive(true))), "restamp")
        for (t in corrected) MenuState.appendFeed(scope, t.entity, t.id, t.wire(), "correction")
    }

    // --- the portal's edits ---

    /** Whether the store applies room changes (it pulled the feed saying `rooms=1`). Inside a transaction. */
    fun editable(scope: Scope): Boolean =
        Venues.selectAll().where { (Venues.tenantId eq scope.tenantId) and (Venues.id eq scope.venueId) }
            .firstOrNull()?.get(Venues.roomsSyncAt) != null

    fun requireEditable(scope: Scope) {
        if (!editable(scope)) throw ConflictException(
            "this store's point of sale does not take room changes from the portal yet (update it first)", "store_not_upgraded")
    }

    /**
     * One portal write: [changes] set on [thing] with [stamp] (the cloud's
     * clock), canonicalized, saved and appended to the store's feed. A table
     * with an open bill refuses everything but its seats (409 table_locked).
     */
    fun write(scope: Scope, thing: Thing, changes: Map<String, JsonElement>, stamp: String) {
        if (thing.entity == RoomFields.TABLE && thing.locked && (changes.keys - RoomFields.LOCKED_TABLE_EDITABLE).isNotEmpty())
            throw ConflictException("table ${thing.str("label") ?: thing.id} has an open bill at the store: it stays where it is", "table_locked")
        for ((f, v) in changes) thing.set(f, v, stamp)
        thing.canonicalize()
        save(scope, listOf(thing))
        MenuState.appendFeed(scope, thing.entity, thing.id, thing.wire(), "portal")
    }

    // --- views ---

    private fun tableDto(t: Thing) = FloorTableDto(
        t.id, t.str("label") ?: "", t.str("label")?.let(RoomModel::number),
        t.int("x") ?: 0, t.int("y") ?: 0, t.int("width") ?: 100, t.int("height") ?: 100, t.int("rotation") ?: 0,
        t.str("shape") ?: "SQUARE", t.int("seats") ?: 4, t.str("parentTableId"), t.locked,
    )

    private fun objectDto(t: Thing) = FloorObjectDto(
        t.id, t.str("type") ?: "CUSTOM", t.int("x") ?: 0, t.int("y") ?: 0, t.int("width") ?: 100, t.int("height") ?: 100,
        t.int("rotation") ?: 0, t.str("labelEn"), t.str("labelFr"), t.names(), t.str("icon"), t.str("shape"),
    )

    /** The live rooms of one store with their live tables and objects, in the store's order. */
    fun rooms(things: Map<Pair<String, String>, Thing>): List<RoomDto> {
        val live = things.values.filter { !it.deleted }
        val tables = live.filter { it.entity == RoomFields.TABLE }.groupBy { it.str("zoneId") }
        val objects = live.filter { it.entity == RoomFields.OBJECT }.groupBy { it.str("zoneId") }
        return live.filter { it.entity == RoomFields.ROOM }
            .sortedWith(compareBy<Thing> { it.int("sortOrder") ?: 0 }.thenBy { it.id })
            .map { r ->
                val en = r.str("nameEn") ?: r.str("nameFr") ?: r.id
                RoomDto(r.id, en, r.str("nameFr") ?: en, r.names(), r.int("sortOrder") ?: 0,
                    r.str("labelPrefix")?.ifBlank { null } ?: r.id.take(1).uppercase(),
                    tables[r.id].orEmpty().sortedWith(compareBy<Thing>({ RoomModel.number(it.str("label") ?: "") ?: Int.MAX_VALUE }, { it.str("label") ?: "" })).map(::tableDto),
                    objects[r.id].orEmpty().sortedBy { it.id }.map(::objectDto))
            }
    }

    /** One live room as the AI planner sees it, or null. */
    fun model(things: Map<Pair<String, String>, Thing>, zoneId: String): RoomModel? {
        val room = rooms(things).firstOrNull { it.id == zoneId } ?: return null
        val tables = room.tables.map {
            RoomTableDto(id = it.id, label = it.label, x = it.x, y = it.y, width = it.width, height = it.height,
                rotation = it.rotation, shape = it.shape, seats = it.seats, number = it.number)
        }
        val live = room.tables.associateBy { it.id }
        // a sub-table's anchor stays with it: locked when the sub-table is (the store sends that already)
        val locked = room.tables.filter { it.locked }.map { it.id }.toMutableSet()
        val parents = room.tables.associate { it.id to it.parentTableId?.takeIf { p -> p in live } }
        return RoomModel(room.id, room.nameEn.ifBlank { room.nameFr }, room.labelPrefix, tables, parents, locked,
            room.objects.map {
                RoomObjectDto(id = it.id, type = it.type, x = it.x, y = it.y, width = it.width, height = it.height,
                    rotation = it.rotation, labelFr = it.labelFr, labelEn = it.labelEn, icon = it.icon, shape = it.shape)
            })
    }

    fun markRoomsPull(scope: Scope) {
        Venues.update({ (Venues.tenantId eq scope.tenantId) and (Venues.id eq scope.venueId) }) {
            it[roomsSyncAt] = CloudTime.now()
        }
    }
}
