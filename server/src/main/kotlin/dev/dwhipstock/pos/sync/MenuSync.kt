package dev.dwhipstock.pos.sync

import dev.dwhipstock.pos.api.CatalogOps
import dev.dwhipstock.pos.api.CategoryCreateRequest
import dev.dwhipstock.pos.api.CategoryPatchRequest
import dev.dwhipstock.pos.api.ItemCreateRequest
import dev.dwhipstock.pos.api.ItemPatchRequest
import dev.dwhipstock.pos.api.VariantCreateRequest
import dev.dwhipstock.pos.api.VariantPatchRequest
import dev.dwhipstock.pos.api.categorySnapshotJson
import dev.dwhipstock.pos.api.itemSnapshotJson
import dev.dwhipstock.pos.base.Categories
import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.Translations
import dev.dwhipstock.pos.db.SyncState
import dev.dwhipstock.pos.restaurant.ConflictException
import dev.dwhipstock.pos.sdk.Outbox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/** One entry of the cloud's per-store menu feed (CONTRACT §10): an item or category's full state with its clocks. */
data class MenuChange(val seq: Long, val entity: String, val id: String, val data: JsonObject)

/** A page of the menu feed. [serverTimeMs] is the cloud's clock when it answered (for the offset). */
data class MenuPage(val cursor: Long, val serverTimeMs: Long?, val changes: List<MenuChange>)

/**
 * Field-level last-write-wins merge (CONTRACT §10), the same rule the cloud
 * runs: a field takes the write with the greater stamp; a thing is deleted
 * while its `deleted` write is newer than every other write to it (an item
 * counts its sizes' writes too) — a later edit on the other side brings it
 * back, a later delete removes it.
 */
object MenuMerge {
    data class Reg(val value: JsonElement, val hlc: String)

    /** A wire snapshot's registers: its fields, stamped from its `clock` (missing → empty stamp). */
    fun regsOf(entity: String, obj: JsonObject): Map<String, Reg> {
        val clock = MenuFields.clock(obj)
        val fields = MenuFields.flat(entity, obj, clock.keys.filter { it.startsWith(MenuFields.NAMES) })
        return fields.mapValues { (f, v) -> Reg(v, clock[f] ?: Hlc.LEGACY) }
    }

    /**
     * [incoming] over [local]: the greater stamp wins. [restamp] (the cloud
     * corrected an implausible future stamp): an equal value adopts the
     * cloud's stamp even when it is smaller.
     */
    fun merge(local: Map<String, Reg>?, incoming: Map<String, Reg>, restamp: Boolean = false): Map<String, Reg> {
        if (local == null) return incoming
        val out = LinkedHashMap(local)
        for ((f, r) in incoming) {
            val l = out[f]
            if (l == null || r.hlc > l.hlc ||
                (restamp && r.hlc != l.hlc && MenuFields.canon(r.value) == MenuFields.canon(l.value))) out[f] = r
        }
        return out
    }

    /** The newest stamp among the non-`deleted` fields. */
    fun maxEdit(regs: Map<String, Reg>): String =
        regs.filterKeys { it != "deleted" }.values.maxOfOrNull { it.hlc } ?: Hlc.LEGACY

    /** A delete older than a later edit of the same thing is undone by that edit. */
    fun canonicalize(regs: Map<String, Reg>, alsoEdited: String = Hlc.LEGACY): Map<String, Reg> {
        val d = regs["deleted"] ?: return regs
        if ((d.value as? JsonPrimitive)?.booleanOrNull != true) return regs
        val m = Hlc.max(maxEdit(regs), alsoEdited)
        return if (m > d.hlc) regs + ("deleted" to Reg(JsonPrimitive(false), m)) else regs
    }

    fun str(regs: Map<String, Reg>, f: String): String? = (regs[f]?.value as? JsonPrimitive)?.contentOrNull
    fun bool(regs: Map<String, Reg>, f: String): Boolean? = (regs[f]?.value as? JsonPrimitive)?.booleanOrNull
    fun long(regs: Map<String, Reg>, f: String): Long? = (regs[f]?.value as? JsonPrimitive)?.longOrNull
    fun int(regs: Map<String, Reg>, f: String): Int? = (regs[f]?.value as? JsonPrimitive)?.intOrNull
    fun deleted(regs: Map<String, Reg>) = bool(regs, "deleted") == true
}

/**
 * The store's half of two-way menu sync: applies the cloud's menu feed
 * through the same menu code a tablet edit uses ([CatalogOps],
 * [Translations]) so translations, photos, prices and kitchen routing stay
 * consistent. While applying, menu events are tagged `origin: cloud` and get
 * no fresh stamps ([MenuClock.applyingCloud]), so nothing applied from the
 * cloud goes back up as a new edit.
 */
object MenuSync {
    private val log = LoggerFactory.getLogger(MenuSync::class.java)
    const val MENU_CURSOR = "menu_cursor"

    /** Apply one page and persist its cursor, all in one transaction (a crash re-applies it: harmless). */
    fun applyPage(page: MenuPage) = transaction {
        for (change in page.changes.sortedBy { it.seq }) {
            runCatching {
                when (change.entity) {
                    MenuFields.ITEM -> applyItem(change.id, change.data)
                    MenuFields.CATEGORY -> applyCategory(change.id, change.data)
                    else -> log.info("ignoring menu change of kind '${change.entity}' (#${change.seq})")
                }
            }.onFailure { log.warn("menu change #${change.seq} (${change.entity} ${change.id}) not applied: ${it.message}") }
        }
        SyncState.set(MENU_CURSOR, page.cursor.toString())
    }

    private fun restampOf(data: JsonObject) = (data["restamp"] as? JsonPrimitive)?.booleanOrNull == true

    private fun observeAll(data: JsonObject) {
        MenuFields.clock(data).values.forEach(MenuClock::observe)
        (data["variants"] as? JsonArray)?.forEach { v -> (v as? JsonObject)?.let { MenuFields.clock(it).values.forEach(MenuClock::observe) } }
    }

    /** The registers on record for a thing, as values (rows hold canonical JSON). */
    private fun localRegs(entity: String, id: String): Map<String, MenuMerge.Reg>? =
        MenuClock.regs(entity, id).takeIf { it.isNotEmpty() }?.mapValues { (_, r) ->
            MenuMerge.Reg(runCatching { Json.parseToJsonElement(r.value) }.getOrDefault(JsonNull), r.hlc)
        }

    private fun record(entity: String, id: String, regs: Map<String, MenuMerge.Reg>) =
        MenuClock.write(entity, id, regs.mapValues { (_, r) -> MenuClock.Reg(MenuFields.canon(r.value), r.hlc) })

    private fun itemExists(id: String) = Items.selectAll().where { Items.id eq id }.any()
    private fun categoryExists(id: String) = Categories.selectAll().where { Categories.id eq id }.any()

    /** Stamp what the store has now; a field it changed outside the merge goes up as a fresh edit. */
    private fun reconcileItem(id: String) {
        if (!itemExists(id)) return
        val snap = itemSnapshotJson(id)
        if (MenuClock.reconcileItem(snap)) Outbox.write("item.updated", "item", id, buildJsonObject {
            put("itemId", id)
            put("item", snap)
        })
    }

    private fun reconcileCategory(id: String) {
        if (!categoryExists(id)) return
        val snap = categorySnapshotJson(id)
        if (MenuClock.reconcileCategory(snap)) Outbox.write("category.updated", "category", id, buildJsonObject {
            put("categoryId", id)
            put("category", snap)
        })
    }

    // --- items (with their sizes) ---

    fun applyItem(id: String, data: JsonObject) {
        observeAll(data)
        val restamp = restampOf(data)
        reconcileItem(id) // the store's own state is fully stamped before it is compared
        val exists = itemExists(id)
        val localItem = if (exists) localRegs(MenuFields.ITEM, id) else null
        val rowVariantIds = ItemVariants.selectAll().where { ItemVariants.itemId eq id }.map { it[ItemVariants.id] }
        val inVariants = (data["variants"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .mapNotNull { v -> (v["id"] as? JsonPrimitive)?.contentOrNull?.let { it to MenuMerge.regsOf(MenuFields.VARIANT, v) } }
            .toMap()
        val variantIds = (rowVariantIds + inVariants.keys).distinct()
        val mergedVariants = variantIds.associateWith { vid ->
            val local = if (vid in rowVariantIds) localRegs(MenuFields.VARIANT, vid) else null
            val incoming = inVariants[vid]
            MenuMerge.canonicalize(if (incoming == null) local.orEmpty() else MenuMerge.merge(local, incoming, restamp))
        }.filterValues { it.isNotEmpty() }
        val sizesEdited = mergedVariants.values.map(MenuMerge::maxEdit).maxOrNull() ?: Hlc.LEGACY
        val mergedItem = MenuMerge.canonicalize(
            MenuMerge.merge(localItem, MenuMerge.regsOf(MenuFields.ITEM, data), restamp), sizesEdited)

        record(MenuFields.ITEM, id, mergedItem)
        mergedVariants.forEach { (vid, regs) -> record(MenuFields.VARIANT, vid, regs) }
        try {
            MenuClock.applyingCloud { bringItem(id, exists, mergedItem, mergedVariants) }
        } catch (e: Exception) {
            log.warn("menu item $id: the store kept part of its own state (${e.message})")
        }
        reconcileItem(id)
    }

    private fun names(regs: Map<String, MenuMerge.Reg>): Map<String, String?> =
        regs.filterKeys { it.startsWith(MenuFields.NAMES) }
            .map { (f, r) -> f.removePrefix(MenuFields.NAMES) to (r.value as? JsonPrimitive)?.contentOrNull }.toMap()

    private fun applyNames(entity: String, id: String, want: Map<String, String?>) {
        if (!Translations.present()) return
        val have = Translations.namesOf(entity, id)
        for ((lang, text) in want) {
            if (lang in Translations.SLOTS) continue
            if ((text?.trim().orEmpty()) != (have[lang] ?: "")) Translations.set(entity, id, lang, text)
        }
    }

    private fun bringItem(
        id: String, exists: Boolean,
        m: Map<String, MenuMerge.Reg>, variants: Map<String, Map<String, MenuMerge.Reg>>,
    ) {
        val s = { f: String -> MenuMerge.str(m, f) }
        val wantDeleted = MenuMerge.deleted(m)
        val liveVariants = variants.filterValues { !MenuMerge.deleted(it) }.entries
            .sortedBy { MenuMerge.int(it.value, "sortOrder") ?: 0 }
        if (!exists) {
            if (wantDeleted || liveVariants.isEmpty()) return
            val nameEn = s("nameEn") ?: return
            CatalogOps.createItem(ItemCreateRequest(
                nameFr = s("nameFr")?.takeIf { it.isNotBlank() } ?: nameEn, nameEn = nameEn,
                descriptionFr = s("descriptionFr") ?: "", descriptionEn = s("descriptionEn") ?: "",
                categoryId = s("categoryId") ?: return,
                abbrev = s("abbrev")?.takeIf { it.isNotBlank() } ?: nameEn.take(2).uppercase(),
                isAlcohol = MenuMerge.bool(m, "isAlcohol") ?: false,
                variants = liveVariants.map { (_, v) -> variantRequest(v) },
            ), fixedId = id, variantIds = liveVariants.map { it.key })
            if (MenuMerge.bool(m, "active") == false) CatalogOps.patchItem(id, ItemPatchRequest(active = false))
            applyNames(Translations.ITEM, id, names(m))
            liveVariants.forEach { (vid, v) -> applyNames(Translations.VARIANT, vid, names(v)) }
            return
        }
        val row = itemSnapshotJson(id)
        val rowFields = MenuFields.flat(MenuFields.ITEM, row)
        val rowDeleted = (rowFields["deleted"] as? JsonPrimitive)?.booleanOrNull == true
        if (rowDeleted && wantDeleted) return // stays deleted; its registers keep the other side's values
        if (rowDeleted) CatalogOps.restoreItem(id, MenuMerge.bool(m, "active") ?: true)
        // the row is live now: take every field (also before a delete, so the
        // deleted row holds the agreed values and nothing looks edited later)
        fun differs(f: String) = MenuFields.canon(m[f]?.value) != MenuFields.canon(rowFields[f]) && m[f]?.value !is JsonNull
        val patch = ItemPatchRequest(
            nameFr = s("nameFr").takeIf { differs("nameFr") },
            nameEn = s("nameEn").takeIf { differs("nameEn") },
            categoryId = s("categoryId").takeIf { differs("categoryId") },
            descriptionFr = s("descriptionFr").takeIf { differs("descriptionFr") },
            descriptionEn = s("descriptionEn").takeIf { differs("descriptionEn") },
            abbrev = s("abbrev").takeIf { differs("abbrev") },
            isAlcohol = MenuMerge.bool(m, "isAlcohol").takeIf { differs("isAlcohol") },
            active = MenuMerge.bool(m, "active").takeIf { differs("active") && !rowDeleted },
        )
        if (patch != ItemPatchRequest()) CatalogOps.patchItem(id, patch)
        applyNames(Translations.ITEM, id, names(m))

        val rowVariants = (row["variants"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .associateBy { (it["id"] as? JsonPrimitive)?.contentOrNull }
        val deletes = mutableListOf<String>()
        // adds and edits first, so deleting a size never meets "last size"
        for ((vid, v) in variants.entries.sortedBy { MenuMerge.int(it.value, "sortOrder") ?: 0 }) {
            val rv = rowVariants[vid]
            val vDeleted = MenuMerge.deleted(v)
            if (rv == null) {
                if (!vDeleted) {
                    CatalogOps.addVariant(id, variantRequest(v), fixedId = vid)
                    applyNames(Translations.VARIANT, vid, names(v))
                }
                continue
            }
            val rf = MenuFields.flat(MenuFields.VARIANT, rv)
            val rvDeleted = (rf["deleted"] as? JsonPrimitive)?.booleanOrNull == true
            if (rvDeleted && vDeleted) continue
            if (rvDeleted) CatalogOps.restoreVariant(id, vid)
            fun vdiff(f: String) = MenuFields.canon(v[f]?.value) != MenuFields.canon(rf[f]) && v[f]?.value !is JsonNull
            val vp = VariantPatchRequest(
                labelFr = MenuMerge.str(v, "labelFr").takeIf { vdiff("labelFr") },
                labelEn = MenuMerge.str(v, "labelEn").takeIf { vdiff("labelEn") },
                priceCents = MenuMerge.long(v, "priceCents").takeIf { vdiff("priceCents") },
                sortOrder = MenuMerge.int(v, "sortOrder").takeIf { vdiff("sortOrder") },
            )
            if (vp != VariantPatchRequest()) CatalogOps.patchVariant(id, vid, vp)
            applyNames(Translations.VARIANT, vid, names(v))
            if (vDeleted) deletes += vid
        }
        for (vid in deletes) {
            try { CatalogOps.deleteVariant(id, vid, allowInUse = true) } catch (e: ConflictException) {
                log.info("menu sync: kept size $vid of $id (${e.code}); the store's copy wins")
            }
        }
        if (wantDeleted) {
            try { CatalogOps.deleteItem(id, allowInUse = true) } catch (e: ConflictException) {
                log.info("menu sync: kept item $id (${e.code}); the store's copy wins")
            }
        }
    }

    private fun variantRequest(v: Map<String, MenuMerge.Reg>): VariantCreateRequest {
        val en = MenuMerge.str(v, "labelEn")?.takeIf { it.isNotBlank() } ?: "Regular"
        return VariantCreateRequest(
            labelFr = MenuMerge.str(v, "labelFr")?.takeIf { it.isNotBlank() } ?: en, labelEn = en,
            priceCents = MenuMerge.long(v, "priceCents") ?: 0, sortOrder = MenuMerge.int(v, "sortOrder"),
        )
    }

    // --- categories ---

    fun applyCategory(id: String, data: JsonObject) {
        observeAll(data)
        reconcileCategory(id)
        val exists = categoryExists(id)
        // a hard-deleted category still has its registers: they are its tombstone
        val local = localRegs(MenuFields.CATEGORY, id)
        val merged = MenuMerge.canonicalize(MenuMerge.merge(local, MenuMerge.regsOf(MenuFields.CATEGORY, data), restampOf(data)))
        record(MenuFields.CATEGORY, id, merged)
        try {
            MenuClock.applyingCloud { bringCategory(id, exists, merged) }
        } catch (e: Exception) {
            log.warn("menu category $id: the store kept part of its own state (${e.message})")
        }
        reconcileCategory(id)
    }

    private fun bringCategory(id: String, exists: Boolean, m: Map<String, MenuMerge.Reg>) {
        val wantDeleted = MenuMerge.deleted(m)
        if (!exists) {
            if (wantDeleted) return
            val en = MenuMerge.str(m, "nameEn")?.takeIf { it.isNotBlank() } ?: return
            CatalogOps.createCategory(CategoryCreateRequest(
                nameFr = MenuMerge.str(m, "nameFr")?.takeIf { it.isNotBlank() } ?: en, nameEn = en,
                sortOrder = MenuMerge.int(m, "sortOrder"),
            ), fixedId = id)
            applyNames(Translations.CATEGORY, id, names(m))
            return
        }
        val row = MenuFields.flat(MenuFields.CATEGORY, categorySnapshotJson(id))
        fun differs(f: String) = MenuFields.canon(m[f]?.value) != MenuFields.canon(row[f]) && m[f]?.value !is JsonNull
        val patch = CategoryPatchRequest(
            nameFr = MenuMerge.str(m, "nameFr").takeIf { differs("nameFr") },
            nameEn = MenuMerge.str(m, "nameEn").takeIf { differs("nameEn") },
            sortOrder = MenuMerge.int(m, "sortOrder").takeIf { differs("sortOrder") },
        )
        if (patch != CategoryPatchRequest()) CatalogOps.patchCategory(id, patch)
        applyNames(Translations.CATEGORY, id, names(m))
        if (wantDeleted) {
            try { CatalogOps.deleteCategory(id) } catch (e: ConflictException) {
                log.info("menu sync: kept category $id (${e.code}); the store's copy wins")
            }
        }
    }
}
