package dev.dwhipstock.pos.sync

import dev.dwhipstock.pos.db.SyncState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.update

/**
 * Two-way menu sync, the store's half of the clock (CONTRACT §10).
 *
 * Every synced menu field is a last-write-wins register: a value and the
 * stamp of the write that set it. Stamps are hybrid logical clocks,
 * `<13-digit ms>-<4-digit counter>-<node>`, so they compare as plain strings:
 * the wall-clock part (corrected by the offset to the cloud's clock learned
 * on every menu pull), then a counter for writes in the same millisecond or
 * after observing a newer remote stamp, then the writer's node id so two
 * writers never tie. The empty stamp is "before two-way sync existed".
 */
object Hlc {
    const val LEGACY = ""

    fun of(physicalMs: Long, counter: Int, node: String): String =
        "%013d-%04d-%s".format(physicalMs, counter.coerceIn(0, 9999), node)

    fun physical(stamp: String): Long? = stamp.substringBefore('-', "").toLongOrNull()

    fun counter(stamp: String): Int? = stamp.split('-').getOrNull(1)?.toIntOrNull()

    fun max(a: String, b: String): String = if (a >= b) a else b
}

/** One register per (entity, id, field): migration 057. */
object MenuClocks : Table("menu_sync_clocks") {
    val entity = varchar("entity", 16)
    val entityId = varchar("entity_id", 160)
    val field = varchar("field", 48)
    val value = text("value")
    val hlc = varchar("hlc", 64)
    override val primaryKey = PrimaryKey(entity, entityId, field)
}

/** The synced fields of each menu thing (plus `names.<lang>` for every extra-language name). */
object MenuFields {
    const val ITEM = "item"
    const val VARIANT = "variant"
    const val CATEGORY = "category"

    val ITEM_FIELDS = listOf(
        "nameFr", "nameEn", "descriptionFr", "descriptionEn", "categoryId", "abbrev", "isAlcohol", "active", "deleted")
    val VARIANT_FIELDS = listOf("labelFr", "labelEn", "priceCents", "sortOrder", "deleted")
    val CATEGORY_FIELDS = listOf("nameFr", "nameEn", "sortOrder", "deleted")

    const val NAMES = "names."

    fun base(entity: String) = when (entity) {
        ITEM -> ITEM_FIELDS
        VARIANT -> VARIANT_FIELDS
        else -> CATEGORY_FIELDS
    }

    /**
     * A snapshot's synced fields: the base ones (absent → JSON null) and, when
     * it carries `names`, one `names.<lang>` per name. A language that was
     * known before ([priorNames]) but is gone now is a null (a removed name).
     */
    fun flat(entity: String, obj: JsonObject, priorNames: Collection<String> = emptyList()): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        for (f in base(entity)) out[f] = obj[f] ?: JsonNull
        val names = obj["names"] as? JsonObject ?: return out
        for (f in priorNames) if (f.startsWith(NAMES)) out[f] = JsonNull
        for ((lang, v) in names) {
            val text = (v as? JsonPrimitive)?.contentOrNull?.trim()
            out[NAMES + lang] = if (text.isNullOrEmpty()) JsonNull else JsonPrimitive(text)
        }
        return out
    }

    /** The `clock` map of a wire snapshot. */
    fun clock(obj: JsonObject): Map<String, String> =
        (obj["clock"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
            ?.toMap().orEmpty()

    fun canon(v: JsonElement?): String = (v ?: JsonNull).toString()
}

object MenuClock {
    const val HLC_KEY = "menu_hlc"
    const val NODE_KEY = "menu_node"
    const val OFFSET_KEY = "menu_clock_offset_ms"

    /** A stamp further ahead of (corrected) now than this is a broken clock, not a real write. */
    const val MAX_FUTURE_MS = 10 * 60_000L

    /** How a snapshot's fields get their stamps. */
    enum class Mode {
        /** An edit: a changed or never-seen field gets a fresh stamp. */
        EDIT,
        /** A catalog bootstrap: a never-seen field is "before sync" (empty stamp); a changed one is fresh. */
        BASELINE,
        /** Applying the cloud's change: report the registers as they are, write nothing. */
        APPLY,
    }

    private val applying = ThreadLocal.withInitial { false }

    /** True while [applyingCloud] runs: menu events then carry `origin: cloud` and mint nothing. */
    val isApplying: Boolean get() = applying.get()

    fun <T> applyingCloud(block: () -> T): T {
        val before = applying.get()
        applying.set(true)
        try { return block() } finally { applying.set(before) }
    }

    /** Whether this database has the clock table yet (older migrations write menu events before 057). */
    fun present(): Boolean {
        var has = false
        TransactionManager.current()
            .exec("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'menu_sync_clocks'") { has = it.next() }
        return has
    }

    // --- the clock (call inside a transaction) ---

    private fun node(): String = SyncState.get(NODE_KEY)
        ?: ("s" + java.util.UUID.randomUUID().toString().replace("-", "").take(10)).also { SyncState.set(NODE_KEY, it) }

    private fun physicalNow(): Long = System.currentTimeMillis() + (SyncState.get(OFFSET_KEY)?.toLongOrNull() ?: 0L)

    /** A fresh stamp, after every stamp this store has issued or observed. */
    fun now(): String {
        val pt = physicalNow()
        val last = SyncState.get(HLC_KEY)
        val lastPt = last?.let(Hlc::physical)
        val stamp = if (last == null || lastPt == null || lastPt < pt || lastPt > pt + MAX_FUTURE_MS) {
            // (a remembered stamp far in the future came from a broken clock: start again from now)
            Hlc.of(pt, 0, node())
        } else {
            Hlc.of(lastPt, (Hlc.counter(last) ?: 0) + 1, node())
        }
        SyncState.set(HLC_KEY, stamp)
        return stamp
    }

    /** A stamp seen from the cloud: later local stamps sort after it (unless it is implausibly far ahead). */
    fun observe(remote: String) {
        val rpt = Hlc.physical(remote) ?: return
        if (rpt > physicalNow() + MAX_FUTURE_MS) return
        val last = SyncState.get(HLC_KEY)
        if (last == null || remote.substringBeforeLast('-') > last.substringBeforeLast('-')) {
            SyncState.set(HLC_KEY, Hlc.of(rpt, Hlc.counter(remote) ?: 0, node()))
        }
    }

    /** The cloud's clock minus ours, measured on a menu pull. */
    fun setOffset(ms: Long) = SyncState.set(OFFSET_KEY, ms.toString())

    // --- registers ---

    data class Reg(val value: String, val hlc: String)

    /** Registers of the given ids of [entity], keyed (id, field). Chunked IN lists. */
    fun load(entity: String, ids: Collection<String>): Map<String, Map<String, Reg>> {
        val out = HashMap<String, MutableMap<String, Reg>>()
        ids.distinct().chunked(500).forEach { chunk ->
            MenuClocks.selectAll().where { (MenuClocks.entity eq entity) and (MenuClocks.entityId inList chunk) }
                .forEach { out.getOrPut(it[MenuClocks.entityId]) { HashMap() }[it[MenuClocks.field]] = Reg(it[MenuClocks.value], it[MenuClocks.hlc]) }
        }
        return out
    }

    fun regs(entity: String, id: String): Map<String, Reg> = load(entity, listOf(id))[id].orEmpty()

    /** Set registers (insert or overwrite). */
    fun write(entity: String, id: String, regs: Map<String, Reg>, existing: Map<String, Reg> = regs(entity, id)) {
        if (regs.isEmpty()) return
        val inserts = mutableListOf<Pair<String, Reg>>()
        for ((field, reg) in regs) {
            if (field in existing) {
                if (existing[field] != reg) MenuClocks.update({
                    (MenuClocks.entity eq entity) and (MenuClocks.entityId eq id) and (MenuClocks.field eq field)
                }) { it[value] = reg.value; it[hlc] = reg.hlc }
            } else inserts += field to reg
        }
        if (inserts.isNotEmpty()) MenuClocks.batchInsert(inserts, shouldReturnGeneratedValues = false) { (field, reg) ->
            this[MenuClocks.entity] = entity
            this[MenuClocks.entityId] = id
            this[MenuClocks.field] = field
            this[MenuClocks.value] = reg.value
            this[MenuClocks.hlc] = reg.hlc
        }
    }

    // --- stamping outbox payloads ---

    const val VERSION_KEY = "menu_version"

    /** The menu's change counter (GET /menu/version): bumped with every menu event. Inside a transaction. */
    fun menuVersion(): Long = SyncState.get(VERSION_KEY)?.toLongOrNull() ?: 0L

    /** Snapshot keys of each menu event: catalog snapshots are baselines, everything else an edit. */
    fun stampPayload(eventType: String, payload: JsonObject): JsonObject {
        val menu = eventType.startsWith("item.") || eventType.startsWith("category.") ||
            eventType == "categories.reordered" || eventType == "catalog.snapshot"
        if (!menu) return payload
        SyncState.set(VERSION_KEY, (menuVersion() + 1).toString())
        if (!present()) return payload
        val mode = when {
            isApplying -> Mode.APPLY
            eventType == "catalog.snapshot" -> Mode.BASELINE
            else -> Mode.EDIT
        }
        val out = LinkedHashMap(payload)
        (payload["item"] as? JsonObject)?.let { out["item"] = stampItems(listOf(it), mode).first() }
        (payload["category"] as? JsonObject)?.let { out["category"] = stampCategories(listOf(it), mode).first() }
        (payload["categories"] as? JsonArray)?.let { arr ->
            out["categories"] = JsonArray(stampCategories(arr.filterIsInstance<JsonObject>(), mode))
        }
        (payload["items"] as? JsonArray)?.let { arr ->
            out["items"] = JsonArray(stampItems(arr.filterIsInstance<JsonObject>(), mode))
        }
        if (mode == Mode.APPLY) out["origin"] = JsonPrimitive("cloud")
        return JsonObject(out)
    }

    /** Result of stamping: the snapshot with its `clock`, and whether any field got a fresh stamp. */
    private class Stamped(val obj: JsonObject, val minted: Boolean)

    fun stampItems(items: List<JsonObject>, mode: Mode): List<JsonObject> = stampItemsTracked(items, mode).map { it.obj }

    private fun stampItemsTracked(items: List<JsonObject>, mode: Mode): List<Stamped> {
        val itemIds = items.mapNotNull { (it["id"] as? JsonPrimitive)?.contentOrNull }
        val variantIds = items.flatMap { item ->
            (item["variants"] as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }
        }
        val itemRegs = load(MenuFields.ITEM, itemIds)
        val variantRegs = load(MenuFields.VARIANT, variantIds)
        return items.map { item ->
            val id = (item["id"] as? JsonPrimitive)?.contentOrNull ?: return@map Stamped(item, false)
            var minted = false
            val itemDeleted = (item["deleted"] as? JsonPrimitive)?.contentOrNull == "true"
            val variants = (item["variants"] as? JsonArray)?.map { el ->
                val v = el as? JsonObject ?: return@map el
                val vid = (v["id"] as? JsonPrimitive)?.contentOrNull ?: return@map v
                val s = stampOne(MenuFields.VARIANT, vid, v, variantRegs[vid].orEmpty(), mode, parentDeleted = itemDeleted)
                minted = minted || s.minted
                s.obj
            }
            val base = if (variants == null) item else JsonObject(item + ("variants" to JsonArray(variants)))
            val s = stampOne(MenuFields.ITEM, id, base, itemRegs[id].orEmpty(), mode)
            Stamped(s.obj, minted || s.minted)
        }
    }

    fun stampCategories(categories: List<JsonObject>, mode: Mode): List<JsonObject> {
        val ids = categories.mapNotNull { (it["id"] as? JsonPrimitive)?.contentOrNull }
        val regs = load(MenuFields.CATEGORY, ids)
        return categories.map { c ->
            val id = (c["id"] as? JsonPrimitive)?.contentOrNull ?: return@map c
            stampOne(MenuFields.CATEGORY, id, c, regs[id].orEmpty(), mode).obj
        }
    }

    /**
     * A deleted thing (or a size of a deleted item) is frozen: only its
     * `deleted` field is stamped. Its other registers may hold the other
     * side's newer values that a deleted row can't take; stamping the row's
     * old values over them would be a phantom edit that undoes the delete.
     */
    private fun stampOne(
        entity: String, id: String, obj: JsonObject, regs: Map<String, Reg>, mode: Mode, parentDeleted: Boolean = false,
    ): Stamped {
        val fields = MenuFields.flat(entity, obj, regs.keys)
        val frozen = parentDeleted || (obj["deleted"] as? JsonPrimitive)?.contentOrNull == "true"
        val clock = LinkedHashMap<String, String>()
        val changes = LinkedHashMap<String, Reg>()
        var minted = false
        for ((field, v) in fields) {
            val canon = MenuFields.canon(v)
            val reg = regs[field]
            val stamp = when {
                mode == Mode.APPLY -> reg?.hlc ?: Hlc.LEGACY
                reg == null && v is JsonNull && field.startsWith(MenuFields.NAMES) -> continue
                frozen && field != "deleted" -> reg?.hlc ?: Hlc.LEGACY.also { changes[field] = Reg(canon, it) }
                reg == null && mode == Mode.BASELINE -> Hlc.LEGACY.also { changes[field] = Reg(canon, it) }
                reg == null || reg.value != canon -> now().also { changes[field] = Reg(canon, it); minted = true }
                else -> reg.hlc
            }
            clock[field] = stamp
        }
        if (changes.isNotEmpty()) write(entity, id, changes, regs)
        val out = LinkedHashMap(obj)
        out["clock"] = JsonObject(clock.mapValues { JsonPrimitive(it.value) })
        return Stamped(JsonObject(out), minted)
    }

    /**
     * After a cloud change was applied: stamp what the store actually ended up
     * with. A field that differs from its register (a delete the store had to
     * refuse, a name it could not take) gets a fresh stamp — the store's state
     * then wins everywhere, so both sides agree again. True when anything did.
     * A field never seen before (a store that hasn't sent its first catalog
     * snapshot yet) is a baseline, not an edit: it must not beat the portal.
     */
    internal fun reconcileItem(snapshot: JsonObject): Boolean = stampItemsTracked(listOf(snapshot), Mode.BASELINE).first().minted

    internal fun reconcileCategory(snapshot: JsonObject): Boolean {
        val id = (snapshot["id"] as? JsonPrimitive)?.contentOrNull ?: return false
        return stampOne(MenuFields.CATEGORY, id, snapshot, regs(MenuFields.CATEGORY, id), Mode.BASELINE).minted
    }
}
