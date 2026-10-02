package dev.dwhipstock.poscloud.menu

import dev.dwhipstock.poscloud.CloudTime
import dev.dwhipstock.poscloud.catalog.Catalog
import dev.dwhipstock.poscloud.catalog.Scope
import dev.dwhipstock.poscloud.db.CatalogCategories
import dev.dwhipstock.poscloud.db.CatalogItems
import dev.dwhipstock.poscloud.db.CatalogNames
import dev.dwhipstock.poscloud.db.CatalogVariants
import dev.dwhipstock.poscloud.db.MenuFeed
import dev.dwhipstock.poscloud.db.MenuHlc
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchUpsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.update

/**
 * Two-way menu sync, the cloud's half (CONTRACT §10).
 *
 * Every synced menu field is a last-write-wins register: the value in the
 * catalog mirror's own column plus the stamp of the write that set it in the
 * row's `clock` (027). Stamps are hybrid logical clocks,
 * `<13-digit ms>-<4-digit counter>-<node>`, compared as plain strings; the
 * empty stamp means "set before two-way sync". The cloud's stamps come from
 * [CloudHlc] (its own clock, never the portal browser's); a store's come from
 * the store, corrected by the offset it learns on every feed pull, and are
 * clamped here when implausibly far ahead.
 */
object Hlc {
    const val LEGACY = ""
    const val MAX_COUNTER = 9999
    fun of(physicalMs: Long, counter: Int, node: String): String =
        "%013d-%04d-%s".format(physicalMs, counter.coerceIn(0, MAX_COUNTER), node)
    /** The stamp right after (physicalMs, counter): past 9,999 the time part moves on 1 ms (never a tie). */
    fun next(physicalMs: Long, counter: Int, node: String): String =
        if (counter >= MAX_COUNTER) of(physicalMs + 1, 0, node) else of(physicalMs, counter + 1, node)
    fun physical(stamp: String): Long? = stamp.substringBefore('-', "").toLongOrNull()
    fun counter(stamp: String): Int? = stamp.split('-').getOrNull(1)?.toIntOrNull()
    fun max(a: String, b: String): String = if (a >= b) a else b
}

/** The cloud's clock: one per tenant, persisted (menu_hlc) so a restart never goes backwards. */
object CloudHlc {
    const val NODE = "cloud"
    /** A store stamp further ahead of the cloud's clock than this is a broken store clock: re-stamped. */
    const val MAX_FUTURE_MS = 2 * 60_000L

    /** A fresh stamp after every stamp issued or observed for [tenantId]. Inside a transaction (row-locked). */
    fun now(tenantId: String): String {
        val last = lockedLast(tenantId)
        val pt = System.currentTimeMillis()
        val lastPt = last?.let(Hlc::physical)
        val stamp = if (last == null || lastPt == null || lastPt < pt) Hlc.of(pt, 0, NODE)
            else Hlc.next(lastPt, Hlc.counter(last) ?: 0, NODE)
        store(tenantId, stamp)
        return stamp
    }

    /**
     * Take the tenant's menu lock (the clock row, FOR UPDATE) until the
     * transaction ends. Every menu writer — portal edits and store ingest —
     * takes it BEFORE reading the menu, so no one merges into a copy another
     * writer is about to replace (a lost update).
     */
    fun lock(tenantId: String) {
        lockedLast(tenantId)
    }

    /** Later cloud stamps sort after [remote] (callers clamp implausible ones first). */
    fun observe(tenantId: String, remote: String) {
        if (Hlc.physical(remote) == null) return
        val last = lockedLast(tenantId)
        if (last == null || remote.substringBeforeLast('-') > last.substringBeforeLast('-'))
            store(tenantId, Hlc.of(Hlc.physical(remote)!!, Hlc.counter(remote) ?: 0, NODE))
    }

    fun plausible(stamp: String): Boolean {
        val pt = Hlc.physical(stamp) ?: return true
        return pt <= System.currentTimeMillis() + MAX_FUTURE_MS
    }

    private fun lockedLast(tenantId: String): String? {
        TransactionManager.current().exec(
            "INSERT INTO menu_hlc (tenant_id, last) VALUES (?, '') ON CONFLICT (tenant_id) DO NOTHING",
            listOf(org.jetbrains.exposed.sql.TextColumnType() to tenantId))
        var last: String? = null
        TransactionManager.current().exec(
            "SELECT last FROM menu_hlc WHERE tenant_id = ? FOR UPDATE",
            listOf(org.jetbrains.exposed.sql.TextColumnType() to tenantId)) { rs -> if (rs.next()) last = rs.getString(1) }
        return last?.takeIf { it.isNotEmpty() }
    }

    private fun store(tenantId: String, stamp: String) {
        MenuHlc.update({ MenuHlc.tenantId eq tenantId }) { it[last] = stamp }
    }
}

/** The synced fields (plus `names.<lang>`), the same lists as the store's. */
object MenuFields {
    const val ITEM = "item"
    const val VARIANT = "variant"
    const val CATEGORY = "category"
    val ITEM_FIELDS = listOf(
        "nameFr", "nameEn", "descriptionFr", "descriptionEn", "categoryId", "abbrev", "isAlcohol", "active", "deleted",
        // menu specials (036): each one whole value (null = every day / none), canonical JSON ([MenuSpecials])
        "availableDays", "specials")
    val VARIANT_FIELDS = listOf("labelFr", "labelEn", "priceCents", "sortOrder", "deleted")
    val CATEGORY_FIELDS = listOf("nameFr", "nameEn", "sortOrder", "deleted")
    const val NAMES = "names."

    fun base(entity: String) = when (entity) {
        ITEM -> ITEM_FIELDS
        VARIANT -> VARIANT_FIELDS
        else -> CATEGORY_FIELDS
    }

    fun canon(v: JsonElement?): String = (v ?: JsonNull).toString()

    fun clock(obj: JsonObject): Map<String, String>? =
        (obj["clock"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap()
}

/** One menu thing's registers: field → value, field → stamp. Names are `names.<lang>` fields (null = none). */
class Regs(val id: String) {
    val fields = LinkedHashMap<String, JsonElement>()
    val clock = LinkedHashMap<String, String>()

    fun str(f: String): String? = (fields[f] as? JsonPrimitive)?.contentOrNull
    fun bool(f: String): Boolean? = (fields[f] as? JsonPrimitive)?.booleanOrNull
    fun long(f: String): Long? = (fields[f] as? JsonPrimitive)?.longOrNull
    fun int(f: String): Int? = (fields[f] as? JsonPrimitive)?.intOrNull
    val deleted: Boolean get() = bool("deleted") == true
    fun stamp(f: String): String = clock[f] ?: Hlc.LEGACY

    fun names(): Map<String, String> = fields.filterKeys { it.startsWith(MenuFields.NAMES) }
        .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { k.removePrefix(MenuFields.NAMES) to it } }
        .toMap()

    /** A write made now: the value, and [stamp] for it. */
    fun set(f: String, v: JsonElement, stamp: String) { fields[f] = v; clock[f] = stamp }

    /** The newest stamp among the non-`deleted` fields. */
    fun maxEdit(): String = clock.filterKeys { it != "deleted" }.values.maxOrNull() ?: Hlc.LEGACY

    /** A delete older than a later edit of the same thing is undone by that edit (the store runs the same rule). */
    fun canonicalize(alsoEdited: String = Hlc.LEGACY) {
        if (!deleted) return
        val m = Hlc.max(maxEdit(), alsoEdited)
        if (m > stamp("deleted")) set("deleted", JsonPrimitive(false), m)
    }

    /** Wire shape (the store's snapshot keys) with `names` and `clock`. */
    fun wire(entity: String): LinkedHashMap<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        out["id"] = JsonPrimitive(id)
        for (f in MenuFields.base(entity)) out[f] = fields[f] ?: JsonNull
        out["names"] = JsonObject(names().toSortedMap().mapValues { JsonPrimitive(it.value) })
        out["clock"] = JsonObject(clock.toSortedMap().mapValues { JsonPrimitive(it.value) })
        return out
    }
}

/** The store-owned facts the portal only displays (never edited there, never synced down). */
data class ItemExtras(
    val photoVersion: Long? = null, val photoSource: String? = null, val barcode: String? = null,
    val brand: String? = null, val subcategory: String? = null, val sizeLabel: String? = null,
    val costCents: Long? = null,
)

class ItemState(val item: Regs, val variants: LinkedHashMap<String, Regs> = LinkedHashMap(), var extras: ItemExtras = ItemExtras()) {
    /** Canonical deletion: sizes first, then the item (a later size edit counts as an edit of the item). */
    fun canonicalize() {
        variants.values.forEach { it.canonicalize() }
        item.canonicalize(variants.values.map { it.maxEdit() }.maxOrNull() ?: Hlc.LEGACY)
    }

    fun wire(): JsonObject {
        val out = item.wire(MenuFields.ITEM)
        out["variants"] = JsonArray(variants.values.sortedBy { it.int("sortOrder") ?: 0 }.map { JsonObject(it.wire(MenuFields.VARIANT)) })
        return JsonObject(out)
    }
}

object MenuState {
    private val SPECIAL_FIELDS = listOf("availableDays", "specials")

    // --- reading wire snapshots ---

    /**
     * A wire snapshot's registers. [names]: the snapshot's `names` (absent → no
     * name fields at all, an older store: keep what is stored). With a `clock`
     * (a current store) each field carries its stamp; without one the stamps
     * are left out and [legacyStamp] decides.
     */
    fun regsOf(entity: String, obj: JsonObject, id: String): Regs {
        val r = Regs(id)
        for (f in MenuFields.base(entity)) if (f in obj) r.fields[f] = obj[f]!!
        if (entity == MenuFields.ITEM) for (f in SPECIAL_FIELDS) {
            // a store leaves them out when null; an older store (no clock) never sends them: keep what is stored
            if (f in obj || MenuFields.clock(obj)?.containsKey(f) == true) r.fields[f] = MenuSpecials.canonical(f, obj[f])
        }
        (obj["names"] as? JsonObject)?.forEach { (lang, v) ->
            val code = lang.trim().lowercase()
            val text = (v as? JsonPrimitive)?.contentOrNull?.trim()
            if (code.isNotEmpty()) r.fields[MenuFields.NAMES + code] = if (text.isNullOrEmpty()) JsonNull else JsonPrimitive(text)
        }
        MenuFields.clock(obj)?.let { clock -> r.clock.putAll(clock) }
        return r
    }

    /**
     * Merge [incoming] into [target], last write wins per field. A field
     * whose stamp is greater wins; two empty stamps (both sides "before
     * two-way sync") let the store's value through, as the one-way mirror
     * always did. [incomingNamesComplete]: incoming lists every name it has
     * (a store snapshot), so a stored name it lacks was removed there.
     */
    fun merge(target: Regs, incoming: Regs, incomingNamesComplete: Boolean) {
        val keys = LinkedHashSet(incoming.fields.keys)
        if (incomingNamesComplete) {
            keys += incoming.clock.keys.filter { it.startsWith(MenuFields.NAMES) }
            keys += target.fields.keys.filter { it.startsWith(MenuFields.NAMES) }
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

    /**
     * An older store's snapshot (no `clock`): what it says is current, so each
     * field whose value differs from the stored one is stamped [now] (the
     * one-way behaviour: the store's edit lands), the rest keep their stamps.
     * A new thing keeps empty stamps.
     */
    fun stampLegacy(existing: Regs?, incoming: Regs, namesComplete: Boolean, now: () -> String) {
        if (existing == null) return
        val keys = LinkedHashSet(incoming.fields.keys)
        if (namesComplete) keys += existing.fields.keys.filter { it.startsWith(MenuFields.NAMES) }
        for (f in keys) {
            val v = incoming.fields[f] ?: JsonNull
            if (MenuFields.canon(v) != MenuFields.canon(existing.fields[f])) {
                incoming.fields[f] = v
                incoming.clock[f] = now()
            } else existing.clock[f]?.let { incoming.clock[f] = it }
        }
    }

    // --- loading and saving the mirror ---

    private fun clockOf(raw: String?): Map<String, String> = runCatching {
        (Json.parseToJsonElement(raw ?: "{}") as JsonObject).mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())

    private fun clockJson(clock: Map<String, String>): String =
        JsonObject(clock.toSortedMap().mapValues { JsonPrimitive(it.value) }).toString()

    private fun namesFor(scope: Scope, entity: String, ids: Collection<String>): Map<String, Map<String, String>> {
        val out = HashMap<String, MutableMap<String, String>>()
        ids.chunked(1000).forEach { chunk ->
            CatalogNames.selectAll().where {
                (CatalogNames.tenantId eq scope.tenantId) and (CatalogNames.venueId eq scope.venueId) and
                    (CatalogNames.entity eq entity) and (CatalogNames.entityId inList chunk)
            }.forEach { out.getOrPut(it[CatalogNames.entityId]) { sortedMapOf() }[it[CatalogNames.lang]] = it[CatalogNames.value] }
        }
        return out
    }

    /** Stored items (with all their sizes, names and clocks) of [ids] at one store. */
    fun loadItems(scope: Scope, ids: Collection<String>): Map<String, ItemState> {
        if (ids.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, ItemState>()
        ids.distinct().chunked(1000).forEach { chunk ->
            CatalogItems.selectAll().where {
                (CatalogItems.tenantId eq scope.tenantId) and (CatalogItems.venueId eq scope.venueId) and (CatalogItems.id inList chunk)
            }.forEach { row ->
                val r = Regs(row[CatalogItems.id])
                r.fields["nameFr"] = JsonPrimitive(row[CatalogItems.nameFr])
                r.fields["nameEn"] = JsonPrimitive(row[CatalogItems.nameEn])
                r.fields["descriptionFr"] = JsonPrimitive(row[CatalogItems.descriptionFr])
                r.fields["descriptionEn"] = JsonPrimitive(row[CatalogItems.descriptionEn])
                r.fields["categoryId"] = JsonPrimitive(row[CatalogItems.categoryId])
                r.fields["abbrev"] = row[CatalogItems.abbrev]?.let(::JsonPrimitive) ?: JsonNull
                r.fields["isAlcohol"] = JsonPrimitive(row[CatalogItems.isAlcohol])
                r.fields["active"] = JsonPrimitive(row[CatalogItems.active])
                r.fields["deleted"] = JsonPrimitive(row[CatalogItems.deleted])
                r.fields["availableDays"] = MenuSpecials.parse("availableDays", row[CatalogItems.availableDays])
                r.fields["specials"] = MenuSpecials.parse("specials", row[CatalogItems.specials])
                r.clock.putAll(clockOf(row[CatalogItems.clock]))
                out[r.id] = ItemState(r, extras = ItemExtras(
                    row[CatalogItems.photoVersion], row[CatalogItems.photoSource], row[CatalogItems.barcode],
                    row[CatalogItems.brand], row[CatalogItems.subcategory], row[CatalogItems.sizeLabel],
                    row[CatalogItems.costCents]))
            }
        }
        if (out.isEmpty()) return out
        out.keys.toList().chunked(1000).forEach { chunk ->
            CatalogVariants.selectAll().where {
                (CatalogVariants.tenantId eq scope.tenantId) and (CatalogVariants.venueId eq scope.venueId) and
                    (CatalogVariants.itemId inList chunk)
            }.orderBy(CatalogVariants.sortOrder).forEach { row ->
                val v = Regs(row[CatalogVariants.id])
                v.fields["labelFr"] = JsonPrimitive(row[CatalogVariants.labelFr])
                v.fields["labelEn"] = JsonPrimitive(row[CatalogVariants.labelEn])
                v.fields["priceCents"] = JsonPrimitive(row[CatalogVariants.priceCents])
                v.fields["sortOrder"] = JsonPrimitive(row[CatalogVariants.sortOrder])
                v.fields["deleted"] = JsonPrimitive(row[CatalogVariants.deleted])
                v.clock.putAll(clockOf(row[CatalogVariants.clock]))
                out[row[CatalogVariants.itemId]]?.variants?.put(v.id, v)
            }
        }
        namesFor(scope, "item", out.keys).forEach { (id, names) ->
            names.forEach { (lang, text) -> out[id]?.item?.fields?.put(MenuFields.NAMES + lang, JsonPrimitive(text)) }
        }
        val variantOwner = out.values.flatMap { s -> s.variants.keys.map { it to s } }.toMap()
        namesFor(scope, "variant", variantOwner.keys).forEach { (vid, names) ->
            names.forEach { (lang, text) -> variantOwner[vid]?.variants?.get(vid)?.fields?.put(MenuFields.NAMES + lang, JsonPrimitive(text)) }
        }
        return out
    }

    /** Write [items] back (one batched upsert of items, one of sizes, names replaced). */
    fun saveItems(scope: Scope, items: Collection<ItemState>) {
        if (items.isEmpty()) return
        CatalogItems.batchUpsert(items, shouldReturnGeneratedValues = false) { s ->
            val r = s.item
            this[CatalogItems.tenantId] = scope.tenantId
            this[CatalogItems.venueId] = scope.venueId
            this[CatalogItems.id] = r.id
            this[CatalogItems.nameFr] = r.str("nameFr") ?: ""
            this[CatalogItems.nameEn] = r.str("nameEn") ?: ""
            this[CatalogItems.descriptionFr] = r.str("descriptionFr") ?: ""
            this[CatalogItems.descriptionEn] = r.str("descriptionEn") ?: ""
            this[CatalogItems.categoryId] = r.str("categoryId") ?: ""
            this[CatalogItems.abbrev] = r.str("abbrev")
            this[CatalogItems.isAlcohol] = r.bool("isAlcohol") ?: false
            this[CatalogItems.active] = r.bool("active") ?: true
            this[CatalogItems.deleted] = r.bool("deleted") ?: false
            this[CatalogItems.photoVersion] = s.extras.photoVersion
            this[CatalogItems.photoSource] = s.extras.photoSource
            this[CatalogItems.barcode] = s.extras.barcode
            this[CatalogItems.brand] = s.extras.brand
            this[CatalogItems.subcategory] = s.extras.subcategory
            this[CatalogItems.sizeLabel] = s.extras.sizeLabel
            this[CatalogItems.costCents] = s.extras.costCents
            this[CatalogItems.clock] = clockJson(r.clock)
            this[CatalogItems.availableDays] = MenuSpecials.text(MenuSpecials.canonical("availableDays", r.fields["availableDays"]))
            this[CatalogItems.specials] = MenuSpecials.text(MenuSpecials.canonical("specials", r.fields["specials"]))
        }
        val variants = items.flatMap { s -> s.variants.values.map { s.item.id to it } }
        if (variants.isNotEmpty()) CatalogVariants.batchUpsert(variants, shouldReturnGeneratedValues = false) { (itemId, v) ->
            this[CatalogVariants.tenantId] = scope.tenantId
            this[CatalogVariants.venueId] = scope.venueId
            this[CatalogVariants.id] = v.id
            this[CatalogVariants.itemId] = itemId
            this[CatalogVariants.labelFr] = v.str("labelFr") ?: ""
            this[CatalogVariants.labelEn] = v.str("labelEn") ?: ""
            this[CatalogVariants.priceCents] = v.long("priceCents") ?: 0
            this[CatalogVariants.sortOrder] = v.int("sortOrder") ?: 0
            this[CatalogVariants.deleted] = v.bool("deleted") ?: false
            this[CatalogVariants.clock] = clockJson(v.clock)
        }
        Catalog.replaceNames(scope, "item", items.associate { it.item.id to it.item.names() })
        Catalog.replaceNames(scope, "variant", variants.associate { it.second.id to it.second.names() })
    }

    fun loadCategories(scope: Scope, ids: Collection<String>? = null): Map<String, Regs> {
        val out = LinkedHashMap<String, Regs>()
        CatalogCategories.selectAll().where {
            val base = (CatalogCategories.tenantId eq scope.tenantId) and (CatalogCategories.venueId eq scope.venueId)
            if (ids == null) base else base and (CatalogCategories.id inList ids.toList())
        }.orderBy(CatalogCategories.sortOrder).forEach { row ->
            val r = Regs(row[CatalogCategories.id])
            r.fields["nameFr"] = JsonPrimitive(row[CatalogCategories.nameFr])
            r.fields["nameEn"] = JsonPrimitive(row[CatalogCategories.nameEn])
            r.fields["sortOrder"] = JsonPrimitive(row[CatalogCategories.sortOrder])
            r.fields["deleted"] = JsonPrimitive(row[CatalogCategories.deleted])
            r.clock.putAll(clockOf(row[CatalogCategories.clock]))
            out[r.id] = r
        }
        namesFor(scope, "category", out.keys).forEach { (id, names) ->
            names.forEach { (lang, text) -> out[id]?.fields?.put(MenuFields.NAMES + lang, JsonPrimitive(text)) }
        }
        return out
    }

    fun saveCategories(scope: Scope, categories: Collection<Regs>) {
        if (categories.isEmpty()) return
        CatalogCategories.batchUpsert(categories, shouldReturnGeneratedValues = false) { r ->
            this[CatalogCategories.tenantId] = scope.tenantId
            this[CatalogCategories.venueId] = scope.venueId
            this[CatalogCategories.id] = r.id
            this[CatalogCategories.nameFr] = r.str("nameFr") ?: ""
            this[CatalogCategories.nameEn] = r.str("nameEn") ?: ""
            this[CatalogCategories.sortOrder] = r.int("sortOrder") ?: 0
            this[CatalogCategories.deleted] = r.bool("deleted") ?: false
            this[CatalogCategories.clock] = clockJson(r.clock)
        }
        Catalog.replaceNames(scope, "category", categories.associate { it.id to it.names() })
    }

    // --- the feed ---

    /**
     * Append one thing's full state to the store's feed. The per-store
     * advisory lock serializes writers so seqs become visible in order (a slow
     * transaction must not commit a lower seq after the store's cursor passed it).
     */
    fun appendFeed(scope: Scope, entity: String, id: String, data: JsonObject, origin: String) {
        TransactionManager.current().exec(
            "SELECT pg_advisory_xact_lock(${("menu:" + scope.tenantId + ":" + scope.venueId).hashCode().toLong()})")
        MenuFeed.insert {
            it[tenantId] = scope.tenantId
            it[venueId] = scope.venueId
            it[MenuFeed.entity] = entity
            it[entityId] = id
            it[MenuFeed.data] = data.toString()
            it[MenuFeed.origin] = origin
            it[createdAt] = CloudTime.now()
        }
    }

    /**
     * This database's feed epoch (029): `<database oid>-<random id>`. A
     * restore into a new database changes the oid; a reset that empties the
     * tables re-creates the id. Inside a transaction.
     */
    fun feedEpoch(): String {
        val tx = TransactionManager.current()
        var oid = ""
        tx.exec("SELECT oid::text FROM pg_database WHERE datname = current_database()") { rs -> if (rs.next()) oid = rs.getString(1) }
        tx.exec("INSERT INTO menu_feed_epoch (id, epoch) VALUES (1, md5(random()::text || clock_timestamp()::text)) " +
            "ON CONFLICT (id) DO NOTHING")
        var id = ""
        tx.exec("SELECT epoch FROM menu_feed_epoch WHERE id = 1") { rs -> if (rs.next()) id = rs.getString(1) }
        return "$oid-$id"
    }

    data class FeedRow(val seq: Long, val entity: String, val id: String, val data: String)

    /**
     * One page of a store's feed after [since], in seq order. An entry is the
     * thing's FULL merged state, so an entry with a newer one of the same
     * thing after it is superseded and left out (the newer one carries all of
     * it): a store catching up — or replaying the whole feed after a restore —
     * applies each thing once, not once per edit. Restamp entries are always
     * kept (their `restamp` flag changes how the store merges).
     */
    fun feedRows(scope: Scope, since: Long, limit: Int): List<FeedRow> {
        val out = mutableListOf<FeedRow>()
        TransactionManager.current().exec(
            """SELECT f.seq, f.entity, f.entity_id, f.data::text FROM menu_feed f
               WHERE f.tenant_id = ? AND f.venue_id = ? AND f.seq > ?
                 AND (f.origin = 'restamp' OR NOT EXISTS (
                   SELECT 1 FROM menu_feed g
                   WHERE g.tenant_id = f.tenant_id AND g.venue_id = f.venue_id
                     AND g.entity = f.entity AND g.entity_id = f.entity_id AND g.seq > f.seq))
               ORDER BY f.seq LIMIT $limit""",
            listOf(org.jetbrains.exposed.sql.TextColumnType() to scope.tenantId,
                org.jetbrains.exposed.sql.TextColumnType() to scope.venueId,
                org.jetbrains.exposed.sql.LongColumnType() to since)) { rs ->
            while (rs.next()) out += FeedRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))
        }
        return out
    }

    /** The newest feed entry for one store (0: none). */
    fun newestFeedSeq(scope: Scope): Long {
        var newest = 0L
        TransactionManager.current().exec(
            "SELECT COALESCE(MAX(seq), 0) FROM menu_feed WHERE tenant_id = ? AND venue_id = ?",
            listOf(org.jetbrains.exposed.sql.TextColumnType() to scope.tenantId,
                org.jetbrains.exposed.sql.TextColumnType() to scope.venueId)) { rs -> if (rs.next()) newest = rs.getLong(1) }
        return newest
    }

    /**
     * Where a store's feed pull really starts: from the start (0) when the
     * store's cursor belongs to another epoch (this database was restored or
     * reset since) or is past the newest entry (the feed went back) — a
     * replay is a merge, so starting over is always safe; otherwise [since].
     */
    fun feedStart(scope: Scope, since: Long, storeEpoch: String?, epoch: String): Long =
        if ((storeEpoch != null && storeEpoch != epoch) || since > newestFeedSeq(scope)) 0L else since

    // --- store snapshots in (ingest) ---

    /**
     * Item snapshots from a store, merged per field (last write wins). Batched
     * for a big `catalog.snapshot` chunk: one load and one upsert. A store
     * stamp too far in the future (a broken store clock) is re-stamped with
     * the cloud's clock and the store is told (a `restamp` feed entry).
     */
    fun ingestItems(scope: Scope, snapshots: List<JsonObject>) {
        val byId = LinkedHashMap<String, JsonObject>()
        for (s in snapshots) ((s["id"] as? JsonPrimitive)?.contentOrNull)?.let { byId[it] = s }
        if (byId.isEmpty()) return
        CloudHlc.lock(scope.tenantId) // before reading: a portal edit committing meanwhile is not overwritten
        val stored = loadItems(scope, byId.keys)
        val restamped = mutableListOf<String>()
        val corrected = mutableListOf<String>()
        var eventStamp: String? = null
        val now = { eventStamp ?: CloudHlc.now(scope.tenantId).also { eventStamp = it } }
        val out = byId.map { (id, snap) ->
            val existing = stored[id]
            val state = existing ?: ItemState(Regs(id))
            val clocked = MenuFields.clock(snap) != null
            val namesComplete = "names" in snap
            var clamped = false
            val inItem = regsOf(MenuFields.ITEM, snap, id)
            if (clocked) clamped = clamp(scope, inItem, now) || clamped
            else stampLegacy(existing?.item, inItem, namesComplete, now)
            merge(state.item, inItem, namesComplete)
            val inVariants = mutableListOf<Pair<Regs, Regs>>()
            (snap["variants"] as? JsonArray)?.filterIsInstance<JsonObject>()?.forEach { v ->
                val vid = (v["id"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                val target = state.variants.getOrPut(vid) { Regs(vid) }
                val existingVariant = existing?.variants?.get(vid)
                val inV = regsOf(MenuFields.VARIANT, v, vid)
                if (clocked) clamped = clamp(scope, inV, now) || clamped
                else stampLegacy(existingVariant, inV, "names" in v, now)
                merge(target, inV, "names" in v)
                inVariants += inV to target
            }
            state.canonicalize()
            state.extras = extrasOf(snap, existing?.extras)
            if (clamped) restamped += id
            else if (clocked && existing != null &&
                (lost(inItem, state.item) || inVariants.any { (inV, target) -> lost(inV, target) })) corrected += id
            state
        }
        saveItems(scope, out)
        for (id in restamped) appendFeed(scope, MenuFields.ITEM, id,
            JsonObject(out.first { it.item.id == id }.wire() + ("restamp" to JsonPrimitive(true))), "restamp")
        for (id in corrected) appendFeed(scope, MenuFields.ITEM, id, out.first { it.item.id == id }.wire(), "correction")
    }

    /**
     * Did a store write lose here? A field the store stamped (non-empty
     * stamp) whose merged value is not the store's: an older write than one
     * the cloud has — e.g. a store whose clock was set back. The store must
     * be told the winner (a `correction` feed entry with the merged state), or
     * it keeps its own value forever: the cloud never sends what it already
     * had, and the store never re-sends what it already pushed.
     */
    private fun lost(incoming: Regs, merged: Regs): Boolean = incoming.clock.any { (f, stamp) ->
        stamp.isNotEmpty() && MenuFields.canon(incoming.fields[f]) != MenuFields.canon(merged.fields[f])
    }

    fun ingestCategories(scope: Scope, snapshots: List<JsonObject>) {
        val byId = LinkedHashMap<String, JsonObject>()
        for (s in snapshots) ((s["id"] as? JsonPrimitive)?.contentOrNull)?.let { byId[it] = s }
        if (byId.isEmpty()) return
        CloudHlc.lock(scope.tenantId) // before reading (see ingestItems)
        val stored = loadCategories(scope, byId.keys)
        var eventStamp: String? = null
        val now = { eventStamp ?: CloudHlc.now(scope.tenantId).also { eventStamp = it } }
        val restamped = mutableListOf<Regs>()
        val corrected = mutableListOf<Regs>()
        val out = byId.map { (id, snap) ->
            val existing = stored[id]
            val target = existing ?: Regs(id)
            val inC = regsOf(MenuFields.CATEGORY, snap, id)
            val namesComplete = "names" in snap
            var clamped = false
            val clocked = MenuFields.clock(snap) != null
            if (clocked) clamped = clamp(scope, inC, now)
            else stampLegacy(existing, inC, namesComplete, now)
            merge(target, inC, namesComplete)
            target.canonicalize()
            if (clamped) restamped += target
            else if (clocked && existing != null && lost(inC, target)) corrected += target
            target
        }
        saveCategories(scope, out)
        for (c in restamped) appendFeed(scope, MenuFields.CATEGORY, c.id,
            JsonObject(c.wire(MenuFields.CATEGORY) + ("restamp" to JsonPrimitive(true))), "restamp")
        for (c in corrected) appendFeed(scope, MenuFields.CATEGORY, c.id, JsonObject(c.wire(MenuFields.CATEGORY)), "correction")
    }

    /** Observe a store's stamps; re-stamp the implausible ones. True when any was. */
    private fun clamp(scope: Scope, r: Regs, now: () -> String): Boolean {
        var clamped = false
        for ((f, stamp) in r.clock.toMap()) {
            if (stamp.isEmpty()) continue
            if (CloudHlc.plausible(stamp)) CloudHlc.observe(scope.tenantId, stamp)
            else { r.clock[f] = now(); clamped = true }
        }
        return clamped
    }

    /** Store-owned display facts from a snapshot; an absent photo hint keeps the stored one. */
    private fun extrasOf(snap: JsonObject, stored: ItemExtras?): ItemExtras {
        fun s(k: String) = (snap[k] as? JsonPrimitive)?.contentOrNull
        fun l(k: String) = (snap[k] as? JsonPrimitive)?.longOrNull
        return ItemExtras(
            photoVersion = l("photoVersion") ?: stored?.photoVersion,
            photoSource = s("photoSource")?.takeIf { it in Catalog.PHOTO_SOURCES } ?: stored?.photoSource,
            barcode = s("barcode"),
            brand = s("brand")?.trim()?.takeIf { it.isNotEmpty() },
            subcategory = s("subcategory")?.trim()?.takeIf { it.isNotEmpty() },
            sizeLabel = s("size")?.trim()?.takeIf { it.isNotEmpty() },
            costCents = l("costCents"),
        )
    }
}
