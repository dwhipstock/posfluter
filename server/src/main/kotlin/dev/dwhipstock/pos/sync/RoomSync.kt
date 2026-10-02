package dev.dwhipstock.pos.sync

import dev.dwhipstock.pos.aimenu.RoomLayoutAi
import dev.dwhipstock.pos.api.FLOOR_OBJECT_ICONS
import dev.dwhipstock.pos.api.FLOOR_OBJECT_SHAPES
import dev.dwhipstock.pos.api.FLOOR_OBJECT_TYPES
import dev.dwhipstock.pos.api.TABLE_SHAPES
import dev.dwhipstock.pos.base.Translations
import dev.dwhipstock.pos.db.SyncState
import dev.dwhipstock.pos.orders.SaleLocations
import dev.dwhipstock.pos.restaurant.DiningTables
import dev.dwhipstock.pos.restaurant.FloorObjects
import dev.dwhipstock.pos.restaurant.Zones
import dev.dwhipstock.pos.sdk.Outbox
import dev.dwhipstock.pos.sdk.VenueClock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

/**
 * Two-way ROOM sync (CONTRACT §11), the store's half: rooms (zones), their
 * dining tables and floor objects, merged field by field (last write wins)
 * with the manager portal's edits — the same registers, clock and feed as the
 * menu (§10). Registers live in `menu_sync_clocks` under the entities `room`,
 * `table` and `floor_object`; stamps come from [MenuClock] (one clock for the
 * whole store, so they never go backwards); the portal's edits come down the
 * menu feed as entries of those entities ([MenuSync.applyPage] hands them to
 * [RoomSync.apply]).
 *
 * Up: every code path that changes the floor (the floor-plan editor, the AI
 * room set-up and floor assistant, a seed, a revert) writes rows; [RoomClock.reconcile]
 * compares the floor with its registers on every sync tick (and around every
 * feed page) and stamps exactly the fields that changed — a `floor.snapshot`
 * outbox event carries the changed things with their clocks, plus the tables
 * that may not be touched (an open bill on them or one of their sub-tables).
 *
 * The store stays the authority for open bills: bills never leave the store,
 * and a portal change that would move, reshape, renumber, re-room or remove a
 * table with an open bill (or remove a table other live tables anchor to, or
 * a room that still holds things) is refused here. The reconcile that follows
 * then stamps the store's own values fresh, so they win everywhere and the
 * portal shows the table where it really is. The seats of a busy table may
 * change (the floor assistant's rule).
 */
object RoomFields {
    const val ROOM = "room"
    const val TABLE = "table"
    const val OBJECT = "floor_object"
    val ENTITIES = setOf(ROOM, TABLE, OBJECT)

    val ROOM_FIELDS = listOf("nameFr", "nameEn", "sortOrder", "labelPrefix", "deleted")
    val TABLE_FIELDS = listOf(
        "zoneId", "label", "parentTableId", "x", "y", "width", "height", "rotation", "shape", "seats", "deleted")
    val OBJECT_FIELDS = listOf(
        "zoneId", "type", "x", "y", "width", "height", "rotation", "labelFr", "labelEn", "icon", "shape", "deleted")

    /** The fields of a table with an open bill that the portal may still change. */
    val LOCKED_TABLE_EDITABLE = setOf("seats")

    fun base(entity: String) = when (entity) {
        ROOM -> ROOM_FIELDS
        TABLE -> TABLE_FIELDS
        else -> OBJECT_FIELDS
    }

    /** Rooms and floor objects carry extra-language names (`names.<lang>`), tables don't. */
    fun hasNames(entity: String) = entity != TABLE

    /** A snapshot's synced fields (absent → null), plus `names.<lang>` (a name known before but gone → null). */
    fun flat(entity: String, obj: JsonObject, priorNames: Collection<String> = emptyList()): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        for (f in base(entity)) out[f] = obj[f] ?: JsonNull
        if (!hasNames(entity)) return out
        val names = obj["names"] as? JsonObject ?: return out
        for (f in priorNames) if (f.startsWith(MenuFields.NAMES)) out[f] = JsonNull
        for ((lang, v) in names) {
            val text = (v as? JsonPrimitive)?.contentOrNull?.trim()
            out[MenuFields.NAMES + lang] = if (text.isNullOrEmpty()) JsonNull else JsonPrimitive(text)
        }
        return out
    }

    fun regsOf(entity: String, obj: JsonObject): Map<String, MenuMerge.Reg> {
        val clock = MenuFields.clock(obj)
        return flat(entity, obj, clock.keys.filter { it.startsWith(MenuFields.NAMES) })
            .mapValues { (f, v) -> MenuMerge.Reg(v, clock[f] ?: Hlc.LEGACY) }
    }
}

/** The floor as it is now and its registers: stamping and the `floor.snapshot` event. */
object RoomClock {
    private val log = LoggerFactory.getLogger(RoomClock::class.java)

    /** Set once the existing floor was baselined (first reconcile: every field gets the empty stamp). */
    const val BASELINE_KEY = "rooms_baseline"
    /** The ids of the tables the portal may not move (open bills), as last sent (comma separated, sorted). */
    const val LOCKED_KEY = "rooms_locked"
    /** At most this many things per `floor.snapshot` event (a big floor goes up in a few). */
    const val CHUNK = 250

    /** Digest of the floor at the last reconcile: an unchanged floor skips the register scan. */
    @Volatile private var lastDigest: String? = null

    /** Forget the digest: the next reconcile compares everything (after a cloud apply, a reset). */
    fun invalidate() { lastDigest = null }

    // --- the floor now (inside a transaction) ---

    private fun s(v: String?) = v?.let(::JsonPrimitive) ?: JsonNull

    fun roomJson(row: ResultRow, names: Map<String, String>): JsonObject = JsonObject(linkedMapOf(
        "id" to JsonPrimitive(row[Zones.id]),
        "nameFr" to JsonPrimitive(row[Zones.nameFr]),
        "nameEn" to JsonPrimitive(row[Zones.nameEn]),
        "sortOrder" to JsonPrimitive(row[Zones.sortOrder]),
        "labelPrefix" to JsonPrimitive(row[Zones.labelPrefix]),
        "deleted" to JsonPrimitive(false),
        "names" to JsonObject(names.toSortedMap().mapValues { JsonPrimitive(it.value) }),
    ))

    fun tableJson(row: ResultRow): JsonObject = JsonObject(linkedMapOf(
        "id" to JsonPrimitive(row[DiningTables.id]),
        "zoneId" to JsonPrimitive(row[DiningTables.zoneId]),
        "label" to JsonPrimitive(row[DiningTables.label]),
        "parentTableId" to s(row[DiningTables.parentTableId]),
        "x" to JsonPrimitive(row[DiningTables.x]),
        "y" to JsonPrimitive(row[DiningTables.y]),
        "width" to JsonPrimitive(row[DiningTables.width]),
        "height" to JsonPrimitive(row[DiningTables.height]),
        "rotation" to JsonPrimitive(row[DiningTables.rotation]),
        "shape" to JsonPrimitive(row[DiningTables.shape]),
        "seats" to JsonPrimitive(row[DiningTables.seats]),
        "deleted" to JsonPrimitive(row[DiningTables.deletedAt] != null),
    ))

    fun objectJson(row: ResultRow, names: Map<String, String>): JsonObject = JsonObject(linkedMapOf(
        "id" to JsonPrimitive(row[FloorObjects.id]),
        "zoneId" to JsonPrimitive(row[FloorObjects.zoneId]),
        "type" to JsonPrimitive(row[FloorObjects.type]),
        "x" to JsonPrimitive(row[FloorObjects.x]),
        "y" to JsonPrimitive(row[FloorObjects.y]),
        "width" to JsonPrimitive(row[FloorObjects.width]),
        "height" to JsonPrimitive(row[FloorObjects.height]),
        "rotation" to JsonPrimitive(row[FloorObjects.rotation]),
        "labelFr" to s(row[FloorObjects.labelFr]),
        "labelEn" to s(row[FloorObjects.labelEn]),
        "icon" to s(row[FloorObjects.icon]),
        "shape" to s(row[FloorObjects.shape]),
        "deleted" to JsonPrimitive(false),
        "names" to JsonObject(names.toSortedMap().mapValues { JsonPrimitive(it.value) }),
    ))

    private fun names(entity: String): Map<String, Map<String, String>> =
        if (Translations.present()) Translations.of(entity) else emptyMap()

    /** Every synced thing at the store now: entity → id → snapshot (soft-deleted tables as deleted). */
    fun current(): Map<String, Map<String, JsonObject>> {
        val zoneNames = names(Translations.ZONE)
        val objectNames = names(Translations.FLOOR_OBJECT)
        val rooms = Zones.selectAll().filterNot { SaleLocations.isOffFloor(it[Zones.id]) }
            .associate { it[Zones.id] to roomJson(it, zoneNames[it[Zones.id]].orEmpty()) }
        val tables = DiningTables.selectAll().filterNot { SaleLocations.isOffFloor(it[DiningTables.zoneId]) }
            .associate { it[DiningTables.id] to tableJson(it) }
        val objects = FloorObjects.selectAll().filterNot { SaleLocations.isOffFloor(it[FloorObjects.zoneId]) }
            .associate { it[FloorObjects.id] to objectJson(it, objectNames[it[FloorObjects.id]].orEmpty()) }
        return mapOf(RoomFields.ROOM to rooms, RoomFields.TABLE to tables, RoomFields.OBJECT to objects)
    }

    /**
     * Tables the portal must not move, reshape, renumber or remove: an open
     * bill on it or on one of its sub-tables (the floor assistant's rule,
     * [RoomLayoutAi.Room.protectedIds]).
     */
    fun lockedTables(): Set<String> =
        Zones.selectAll().map { it[Zones.id] }.filterNot(SaleLocations::isOffFloor)
            .flatMap { RoomLayoutAi.room(it).protectedIds }.toSortedSet()

    /** Ids that have registers of [entity] (things the store had once, deleted or not). */
    private fun registered(entity: String): Set<String> =
        MenuClocks.select(MenuClocks.entityId).where { MenuClocks.entity eq entity }.withDistinct()
            .map { it[MenuClocks.entityId] }.toSet()

    // --- stamping ---

    private class Stamped(val obj: JsonObject, val minted: Boolean)

    /**
     * The menu's stamping rule ([MenuClock.stampOne]) for a floor thing: a
     * field whose value differs from its register gets a fresh stamp; a
     * never-seen field is "before sync" in a [baseline]; a deleted thing is
     * frozen (only `deleted` is stamped: its other registers may hold the
     * portal's newer values that a deleted row cannot take).
     */
    private fun stamp(entity: String, id: String, obj: JsonObject, regs: Map<String, MenuClock.Reg>, baseline: Boolean): Stamped {
        val fields = RoomFields.flat(entity, obj, regs.keys)
        val frozen = (obj["deleted"] as? JsonPrimitive)?.contentOrNull == "true"
        val clock = LinkedHashMap<String, String>()
        val changes = LinkedHashMap<String, MenuClock.Reg>()
        var minted = false
        for ((field, v) in fields) {
            val canon = MenuFields.canon(v)
            val reg = regs[field]
            val st = when {
                reg == null && v is JsonNull && field.startsWith(MenuFields.NAMES) -> continue
                frozen && field != "deleted" -> reg?.hlc ?: continue
                reg == null && baseline -> Hlc.LEGACY.also { changes[field] = MenuClock.Reg(canon, it) }
                reg == null || reg.value != canon -> MenuClock.now().also { changes[field] = MenuClock.Reg(canon, it); minted = true }
                else -> reg.hlc
            }
            clock[field] = st
        }
        if (changes.isNotEmpty()) MenuClock.write(entity, id, changes, regs)
        // the wire snapshot: the register values for a frozen thing's other fields
        val out = LinkedHashMap<String, JsonElement>()
        out["id"] = JsonPrimitive(id)
        for (f in RoomFields.base(entity)) out[f] = if (frozen && f != "deleted")
            regs[f]?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it.value) }.getOrNull() } ?: JsonNull
            else fields[f] ?: JsonNull
        if (RoomFields.hasNames(entity)) {
            val names = if (frozen) regs.filterKeys { it.startsWith(MenuFields.NAMES) }.mapValues {
                runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it.value.value) }.getOrDefault(JsonNull)
            } else fields.filterKeys { it.startsWith(MenuFields.NAMES) }
            out["names"] = JsonObject(names.filterValues { it !is JsonNull }.mapKeys { it.key.removePrefix(MenuFields.NAMES) })
        }
        out["clock"] = JsonObject(clock.mapValues { JsonPrimitive(it.value) })
        return Stamped(JsonObject(out), minted)
    }

    /**
     * Compare the floor with its registers and send what changed (inside a
     * transaction). The first time ever, every field is baselined with the
     * empty stamp and the whole floor goes up (the portal's starting copy);
     * after a cloud reset ([resendAll]) the whole floor goes up again with
     * its own stamps. Returns how many things went up.
     */
    fun reconcile(): Int {
        if (!MenuClock.present()) return 0
        val now = current()
        val locked = lockedTables()
        val digest = (now.values.flatMap { m -> m.values.map { it.toString() } } + locked.joinToString(",")).hashCode()
            .toString() + ":" + now.values.sumOf { it.size }
        val baseline = SyncState.get(BASELINE_KEY) == null
        if (!baseline && digest == lastDigest) return 0
        val changed = mutableMapOf<String, MutableList<JsonObject>>()
        for (entity in listOf(RoomFields.ROOM, RoomFields.TABLE, RoomFields.OBJECT)) {
            val cur = now.getValue(entity)
            val ids = (cur.keys + registered(entity)).toSortedSet()
            val regs = MenuClock.load(entity, ids)
            for (id in ids) {
                val obj = cur[id] ?: JsonObject(mapOf("id" to JsonPrimitive(id), "deleted" to JsonPrimitive(true)))
                val r = regs[id].orEmpty()
                // a thing gone from the store that the registers already know as deleted: nothing to say
                if (id !in cur && r.isEmpty()) continue
                val st = stamp(entity, id, obj, r, baseline)
                if (st.minted || baseline) changed.getOrPut(entity) { mutableListOf() } += st.obj
            }
        }
        val lockedNow = locked.joinToString(",")
        val lockedChanged = SyncState.get(LOCKED_KEY) != lockedNow
        val total = changed.values.sumOf { it.size }
        if (total > 0 || lockedChanged) emit(changed, locked)
        if (lockedChanged) SyncState.set(LOCKED_KEY, lockedNow)
        if (baseline) SyncState.set(BASELINE_KEY, "1")
        lastDigest = digest
        if (total > 0) log.info("room sync: ${total} floor change(s) queued for the portal" + if (baseline) " (first full copy)" else "")
        return total
    }

    /** One `floor.snapshot` per [CHUNK] things; every event carries the full locked list. */
    private fun emit(changed: Map<String, List<JsonObject>>, locked: Set<String>) {
        val all = listOf(RoomFields.ROOM, RoomFields.TABLE, RoomFields.OBJECT)
            .flatMap { e -> changed[e].orEmpty().map { e to it } }
        val chunks = if (all.isEmpty()) listOf(emptyList()) else all.chunked(CHUNK)
        for (chunk in chunks) {
            Outbox.write("floor.snapshot", "floor", "rooms", buildJsonObject {
                put("rooms", JsonArray(chunk.filter { it.first == RoomFields.ROOM }.map { it.second }))
                put("tables", JsonArray(chunk.filter { it.first == RoomFields.TABLE }.map { it.second }))
                put("objects", JsonArray(chunk.filter { it.first == RoomFields.OBJECT }.map { it.second }))
                put("locked", JsonArray(locked.map(::JsonPrimitive)))
            })
        }
    }

    /** The cloud's feed was reset: the next reconcile re-sends the whole floor with its stamps. */
    fun resendAll() {
        SyncState.deleteWhere { SyncState.key eq BASELINE_KEY }
        SyncState.deleteWhere { SyncState.key eq LOCKED_KEY }
        invalidate()
    }
}

/**
 * Applies the portal's room changes (menu feed entries of the floor
 * entities): merge per field, record, then bring the rows to the merged
 * state — unless an open bill (or a sub-table anchor, or a room's contents)
 * says no; then the store's state stays and the reconcile after the page
 * re-stamps it fresh, so it wins everywhere.
 */
object RoomSync {
    private val log = LoggerFactory.getLogger(RoomSync::class.java)

    fun isRoomEntity(entity: String) = entity in RoomFields.ENTITIES

    private fun localRegs(entity: String, id: String): Map<String, MenuMerge.Reg>? =
        MenuClock.regs(entity, id).takeIf { it.isNotEmpty() }?.mapValues { (_, r) ->
            MenuMerge.Reg(runCatching { kotlinx.serialization.json.Json.parseToJsonElement(r.value) }.getOrDefault(JsonNull), r.hlc)
        }

    private fun record(entity: String, id: String, regs: Map<String, MenuMerge.Reg>) =
        MenuClock.write(entity, id, regs.mapValues { (_, r) -> MenuClock.Reg(MenuFields.canon(r.value), r.hlc) })

    /** One feed entry (inside the page's transaction). Throws when it can't be applied yet (kept and retried). */
    fun apply(entity: String, id: String, data: JsonObject) {
        if (entity == RoomFields.ROOM && SaleLocations.isOffFloor(id)) return
        MenuFields.clock(data).values.forEach(MenuClock::observe)
        val restamp = (data["restamp"] as? JsonPrimitive)?.contentOrNull == "true"
        val merged = MenuMerge.canonicalize(MenuMerge.merge(localRegs(entity, id), RoomFields.regsOf(entity, data), restamp))
        record(entity, id, merged)
        RoomClock.invalidate()
        MenuClock.applyingCloud {
            when (entity) {
                RoomFields.ROOM -> bringRoom(id, merged)
                RoomFields.TABLE -> bringTable(id, merged)
                else -> bringObject(id, merged)
            }
        }
    }

    private fun str(m: Map<String, MenuMerge.Reg>, f: String) = MenuMerge.str(m, f)
    private fun int(m: Map<String, MenuMerge.Reg>, f: String) = MenuMerge.int(m, f)
    private fun given(m: Map<String, MenuMerge.Reg>, f: String) = m[f] != null && m[f]!!.value !is JsonNull

    private fun names(m: Map<String, MenuMerge.Reg>): Map<String, String?> =
        m.filterKeys { it.startsWith(MenuFields.NAMES) }
            .map { (f, r) -> f.removePrefix(MenuFields.NAMES) to (r.value as? JsonPrimitive)?.contentOrNull }.toMap()

    private fun applyNames(entity: String, id: String, want: Map<String, String?>) {
        if (!Translations.present()) return
        val have = Translations.namesOf(entity, id)
        for ((lang, text) in want) {
            if (lang in Translations.SLOTS || !Regex("^[a-z]{2,8}$").matches(lang)) continue
            if ((text?.trim().orEmpty()) != (have[lang] ?: "")) Translations.set(entity, id, lang, text, sync = false)
        }
    }

    private fun geometry(m: Map<String, MenuMerge.Reg>, f: String, min: Int, max: Int, def: Int) =
        (int(m, f) ?: def).coerceIn(min, max)

    private fun zoneExists(id: String) = Zones.selectAll().where { Zones.id eq id }.any()

    // --- rooms ---

    private fun bringRoom(id: String, m: Map<String, MenuMerge.Reg>) {
        val row = Zones.selectAll().where { Zones.id eq id }.firstOrNull()
        val wantDeleted = MenuMerge.deleted(m)
        val en = str(m, "nameEn")?.trim()?.take(100)?.takeIf { it.isNotEmpty() }
        val fr = str(m, "nameFr")?.trim()?.take(100)?.takeIf { it.isNotEmpty() }
        if (row == null) {
            if (wantDeleted) return
            val nameEn = en ?: fr ?: error("room $id has no name")
            Zones.insert {
                it[Zones.id] = id
                it[Zones.nameEn] = nameEn
                it[Zones.nameFr] = fr ?: nameEn
                it[sortOrder] = int(m, "sortOrder") ?: ((Zones.selectAll().maxOfOrNull { r -> r[Zones.sortOrder] } ?: -1) + 1)
                it[status] = "OPEN"
                it[labelPrefix] = prefix(str(m, "labelPrefix"), nameEn)
            }
            applyNames(Translations.ZONE, id, names(m))
            log.info("room sync: room $id created from the portal")
            return
        }
        val newEn = en?.takeIf { it != row[Zones.nameEn] }
        val newFr = fr?.takeIf { it != row[Zones.nameFr] }
        val newSort = int(m, "sortOrder")?.takeIf { it != row[Zones.sortOrder] }
        val newPrefix = str(m, "labelPrefix")?.let { prefix(it, row[Zones.nameEn]) }?.takeIf { it != row[Zones.labelPrefix] }
        if (listOf(newEn, newFr, newSort, newPrefix).any { it != null }) Zones.update({ Zones.id eq id }) {
            newEn?.let { v -> it[Zones.nameEn] = v }
            newFr?.let { v -> it[Zones.nameFr] = v }
            newSort?.let { v -> it[sortOrder] = v }
            newPrefix?.let { v -> it[labelPrefix] = v }
        }
        applyNames(Translations.ZONE, id, names(m))
        if (wantDeleted) {
            val liveTables = DiningTables.selectAll().where { (DiningTables.zoneId eq id) and DiningTables.deletedAt.isNull() }.count()
            val objects = FloorObjects.selectAll().where { FloorObjects.zoneId eq id }.count()
            if (liveTables > 0 || objects > 0) {
                log.info("room sync: kept room $id ($liveTables table(s), $objects object(s) still in it); the store's copy wins")
                return
            }
            Zones.deleteWhere { Zones.id eq id }
            log.info("room sync: room $id removed from the portal")
        }
    }

    private fun prefix(requested: String?, nameEn: String): String =
        requested?.trim()?.uppercase()?.filter { it.isLetter() }?.take(4)?.ifBlank { null }
            ?: nameEn.trim().firstOrNull { it.isLetter() }?.uppercaseChar()?.toString() ?: "Z"

    // --- tables ---

    private fun bringTable(id: String, m: Map<String, MenuMerge.Reg>) {
        val row = DiningTables.selectAll().where { DiningTables.id eq id }.firstOrNull()
        val wantDeleted = MenuMerge.deleted(m)
        val shape = str(m, "shape")?.takeIf { it in TABLE_SHAPES }
        if (given(m, "shape") && shape == null) error("table $id: unknown shape '${str(m, "shape")}'")
        val w = geometry(m, "width", 20, 1000, 100)
        val h = geometry(m, "height", 20, 1000, 100)
        if (row == null) {
            if (wantDeleted) return
            val zoneId = str(m, "zoneId") ?: error("table $id has no room")
            if (SaleLocations.isOffFloor(zoneId)) error("table $id: room $zoneId is not on the floor")
            if (!zoneExists(zoneId)) error("table $id is in room $zoneId, which this store doesn't have (yet)")
            val label = str(m, "label")?.trim()?.take(32)?.takeIf { it.isNotEmpty() } ?: error("table $id has no label")
            val sort = (DiningTables.selectAll().where { DiningTables.zoneId eq zoneId }.maxOfOrNull { it[DiningTables.sortOrder] } ?: 0) + 1
            DiningTables.insert {
                it[DiningTables.id] = id
                it[DiningTables.zoneId] = zoneId
                it[DiningTables.label] = label
                it[parentTableId] = str(m, "parentTableId")
                it[sortOrder] = sort
                it[x] = geometry(m, "x", 0, 1000, 0)
                it[y] = geometry(m, "y", 0, 1000, 0)
                it[width] = w
                it[height] = h
                it[rotation] = Math.floorMod(int(m, "rotation") ?: 0, 360)
                it[DiningTables.shape] = shape ?: "SQUARE"
                it[seats] = (int(m, "seats") ?: 4).coerceIn(0, 50)
            }
            return
        }
        val zoneId = row[DiningTables.zoneId]
        val locked = id in RoomLayoutAi.room(zoneId).protectedIds
        val anchors = DiningTables.selectAll().where {
            (DiningTables.parentTableId eq id) and DiningTables.deletedAt.isNull()
        }.count()
        val wasDeleted = row[DiningTables.deletedAt] != null
        if (wasDeleted && wantDeleted) return
        val targetZone = str(m, "zoneId")?.takeIf { it != zoneId }
        if (targetZone != null && (SaleLocations.isOffFloor(targetZone) || !zoneExists(targetZone)))
            error("table $id: room $targetZone is not on this store's floor")
        // what the portal wants that differs from the row
        val want = mapOf<String, Any?>(
            "zoneId" to targetZone,
            "label" to str(m, "label")?.trim()?.take(32)?.takeIf { it.isNotEmpty() && it != row[DiningTables.label] },
            "parentTableId" to (if (m["parentTableId"] != null && str(m, "parentTableId") != row[DiningTables.parentTableId]) (str(m, "parentTableId") ?: "") else null),
            "x" to int(m, "x")?.coerceIn(0, 1000)?.takeIf { it != row[DiningTables.x] },
            "y" to int(m, "y")?.coerceIn(0, 1000)?.takeIf { it != row[DiningTables.y] },
            "width" to int(m, "width")?.let { w }?.takeIf { it != row[DiningTables.width] },
            "height" to int(m, "height")?.let { h }?.takeIf { it != row[DiningTables.height] },
            "rotation" to int(m, "rotation")?.let { Math.floorMod(it, 360) }?.takeIf { it != row[DiningTables.rotation] },
            "shape" to shape?.takeIf { it != row[DiningTables.shape] },
            "seats" to int(m, "seats")?.coerceIn(0, 50)?.takeIf { it != row[DiningTables.seats] },
        ).filterValues { it != null }
        val refused = if (locked) want.keys - RoomFields.LOCKED_TABLE_EDITABLE else emptySet()
        if (refused.isNotEmpty()) log.info("room sync: table ${row[DiningTables.label]} has an open bill: kept its ${refused.joinToString(", ")}")
        val take = want - refused
        if (wasDeleted) DiningTables.update({ DiningTables.id eq id }) { it[deletedAt] = null } // a later edit brought it back
        if (take.isNotEmpty()) DiningTables.update({ DiningTables.id eq id }) {
            take["zoneId"]?.let { v -> it[DiningTables.zoneId] = v as String }
            take["label"]?.let { v -> it[label] = v as String }
            take["parentTableId"]?.let { v -> it[parentTableId] = (v as String).ifEmpty { null } }
            take["x"]?.let { v -> it[x] = v as Int }
            take["y"]?.let { v -> it[y] = v as Int }
            take["width"]?.let { v -> it[width] = v as Int }
            take["height"]?.let { v -> it[height] = v as Int }
            take["rotation"]?.let { v -> it[rotation] = v as Int }
            take["shape"]?.let { v -> it[DiningTables.shape] = v as String }
            take["seats"]?.let { v -> it[seats] = v as Int }
        }
        if (wantDeleted && !wasDeleted) when {
            locked -> log.info("room sync: table ${row[DiningTables.label]} has an open bill: not removed; the store's copy wins")
            anchors > 0 -> log.info("room sync: table ${row[DiningTables.label]} has sub-tables: not removed; the store's copy wins")
            else -> DiningTables.update({ DiningTables.id eq id }) { it[deletedAt] = VenueClock.now() }
        }
    }

    // --- floor objects ---

    private fun bringObject(id: String, m: Map<String, MenuMerge.Reg>) {
        val row = FloorObjects.selectAll().where { FloorObjects.id eq id }.firstOrNull()
        val wantDeleted = MenuMerge.deleted(m)
        if (wantDeleted) {
            if (row != null) FloorObjects.deleteWhere { FloorObjects.id eq id }
            return
        }
        val type = str(m, "type")?.takeIf { it in FLOOR_OBJECT_TYPES } ?: error("object $id: unknown type '${str(m, "type")}'")
        val zoneId = str(m, "zoneId") ?: error("object $id has no room")
        if (SaleLocations.isOffFloor(zoneId) || !zoneExists(zoneId)) error("object $id is in room $zoneId, which this store doesn't have (yet)")
        val custom = type == "CUSTOM"
        val icon = if (custom) str(m, "icon")?.takeIf { it in FLOOR_OBJECT_ICONS } ?: "star" else null
        val shape = if (custom) str(m, "shape")?.takeIf { it in FLOOR_OBJECT_SHAPES } ?: "RECT" else null
        val labelFr = str(m, "labelFr")?.trim()?.take(64)?.ifBlank { null }
        val labelEn = str(m, "labelEn")?.trim()?.take(64)?.ifBlank { null }
        val w = geometry(m, "width", 20, 1000, 100)
        val h = geometry(m, "height", 20, 1000, 100)
        val write: (org.jetbrains.exposed.sql.statements.UpdateBuilder<*>) -> Unit = {
            it[FloorObjects.zoneId] = zoneId
            it[FloorObjects.type] = type
            it[FloorObjects.x] = geometry(m, "x", 0, 1000, 0)
            it[FloorObjects.y] = geometry(m, "y", 0, 1000, 0)
            it[FloorObjects.width] = w
            it[FloorObjects.height] = h
            it[FloorObjects.rotation] = Math.floorMod(int(m, "rotation") ?: 0, 360)
            it[FloorObjects.labelFr] = labelFr
            it[FloorObjects.labelEn] = labelEn
            it[FloorObjects.icon] = icon
            it[FloorObjects.shape] = shape
        }
        if (row == null) FloorObjects.insert { it[FloorObjects.id] = id; write(it) }
        else FloorObjects.update({ FloorObjects.id eq id }) { write(it) }
        applyNames(Translations.FLOOR_OBJECT, id, names(m))
    }
}
