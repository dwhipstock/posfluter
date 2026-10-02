package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.base.Categories
import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.Translations
import dev.dwhipstock.pos.restaurant.Zones
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.selectAll

/**
 * Full post-mutation catalog snapshots for outbox payloads (CONTRACT.md §2):
 * every item.* and category.* event carries the aggregate's complete state so
 * the cloud never joins back into store internals. Variants include
 * soft-deleted rows with a deleted flag so the cloud can mirror deletions.
 * Call inside the same transaction as the mutation, like Outbox.write.
 */

/**
 * ItemVariants' columns this database has: the older catalog migrations
 * snapshot items before 047 adds `cost_cents`, so it's left out until then.
 */
private fun variantColumns(): List<org.jetbrains.exposed.sql.Expression<*>> {
    var has = false
    org.jetbrains.exposed.sql.transactions.TransactionManager.current()
        .exec("SELECT 1 FROM pragma_table_info('item_variants') WHERE name = 'cost_cents'") { has = it.next() }
    return if (has) ItemVariants.columns else ItemVariants.columns - ItemVariants.costCents
}

internal fun itemSnapshotJson(itemId: String, photoVersion: Long? = null): JsonObject =
    itemRowSnapshot(Items.selectAll().where { Items.id eq itemId }.first(), photoVersion)

/**
 * The names beyond fr/en (es, de…) of [entity], or null on a database from
 * before the translations table (the older catalog migrations): then the
 * snapshot leaves `names` out and the cloud keeps what it has.
 */
private fun namesTable(entity: String): Map<String, Map<String, String>>? =
    if (Translations.present()) Translations.of(entity) else null

private fun names(map: Map<String, String>): JsonObject = buildJsonObject { map.toSortedMap().forEach { (k, v) -> put(k, v) } }

private fun itemRowSnapshot(
    row: ResultRow, photoVersion: Long?,
    variantRows: List<ResultRow> = ItemVariants.select(variantColumns())
        .where { ItemVariants.itemId eq row[Items.id] }
        .orderBy(ItemVariants.sortOrder).toList(),
    itemNames: Map<String, Map<String, String>>? = namesTable(Translations.ITEM),
    variantNames: Map<String, Map<String, String>>? = namesTable(Translations.VARIANT),
): JsonObject {
    val itemId = row[Items.id]
    val variants = variantRows
        .map { v ->
            buildJsonObject {
                put("id", v[ItemVariants.id])
                put("labelFr", v[ItemVariants.labelFr])
                put("labelEn", v[ItemVariants.labelEn])
                put("priceCents", v[ItemVariants.priceCents])
                v.getOrNull(ItemVariants.costCents)?.let { put("costCents", it) }
                put("sortOrder", v[ItemVariants.sortOrder])
                put("deleted", v[ItemVariants.deletedAt] != null)
                variantNames?.let { put("names", names(it[v[ItemVariants.id]].orEmpty())) }
            }
        }
    return buildJsonObject {
        put("id", itemId)
        put("nameFr", row[Items.nameFr])
        put("nameEn", row[Items.nameEn])
        put("descriptionFr", row[Items.descriptionFr])
        put("descriptionEn", row[Items.descriptionEn])
        put("categoryId", row[Items.categoryId])
        put("abbrev", row[Items.abbrev])
        put("isAlcohol", row[Items.isAlcohol])
        put("active", row[Items.active])
        put("deleted", row[Items.deletedAt] != null)
        // optional hint; the binary moves via the photo sideband, never here
        photoVersion?.let { put("photoVersion", it) }
        // provenance of the current photo (044): original | ai_generated | ai_enhanced
        if (row[Items.photoPath] != null) row[Items.photoSource]?.let { put("photoSource", it) }
        // retail shelf facts (038), only where they differ from a pub item
        row[Items.barcode]?.let { put("barcode", it) }
        // what one costs the store (047): the first variant's, for the portal's margins
        variantRows.firstOrNull()?.getOrNull(ItemVariants.costCents)?.let { put("costCents", it) }
        if (row[Items.ageRestricted]) put("ageRestricted", true)
        if (!row[Items.taxable]) put("taxable", false)
        if (row[Items.crvSize] != "NONE") {
            put("crvSize", row[Items.crvSize])
            put("packUnits", row[Items.packUnits])
        }
        // catalog facets (041), when set
        row[Items.brand]?.let { put("brand", it) }
        row[Items.subcategory]?.let { put("subcategory", it) }
        row[Items.sizeLabel]?.let { put("size", it) }
        // the names in the store's other languages (es, de…): always sent, so
        // an empty object clears what the portal had
        itemNames?.let { put("names", names(it[itemId].orEmpty())) }
        put("variants", JsonArray(variants))
    }
}

internal fun categorySnapshotJson(categoryId: String, deleted: Boolean = false): JsonObject =
    categoryRowSnapshot(Categories.selectAll().where { Categories.id eq categoryId }.first(), deleted)

private fun categoryRowSnapshot(
    row: ResultRow, deleted: Boolean,
    categoryNames: Map<String, Map<String, String>>? = namesTable(Translations.CATEGORY),
): JsonObject = buildJsonObject {
    put("id", row[Categories.id])
    put("nameFr", row[Categories.nameFr])
    put("nameEn", row[Categories.nameEn])
    put("sortOrder", row[Categories.sortOrder])
    // categories hard-delete in the store, so a live row is never deleted;
    // the flag is only forced true for the pre-delete snapshot
    put("deleted", deleted)
    categoryNames?.let { put("names", names(it[row[Categories.id]].orEmpty())) }
}

internal fun allCategoriesJson(): JsonArray {
    val categoryNames = namesTable(Translations.CATEGORY)
    return JsonArray(
        Categories.selectAll().orderBy(Categories.sortOrder)
            .map { categoryRowSnapshot(it, deleted = false, categoryNames) }
    )
}

/** Every zone's names in the store's other languages: `[{ id, names }]` (the first catalog.snapshot chunk). */
internal fun allZoneNamesJson(): JsonArray? {
    val zoneNames = namesTable(Translations.ZONE) ?: return null
    return JsonArray(Zones.selectAll().where { Zones.id notInList dev.dwhipstock.pos.orders.SaleLocations.OFF_FLOOR }.orderBy(Zones.sortOrder).map { z ->
        buildJsonObject { put("id", z[Zones.id]); put("names", names(zoneNames[z[Zones.id]].orEmpty())) }
    })
}

/**
 * A name in another language changed ([Translations.set]): send the thing
 * again, so the portal shows it — the item's (or the variant's item's)
 * snapshot, the category's, or the zone's names. Floor-object captions stay
 * in the store. Inside the transaction that changed it.
 */
internal fun syncNamesOf(entity: String, id: String) {
    val outbox = dev.dwhipstock.pos.sdk.Outbox
    when (entity) {
        Translations.ITEM, Translations.VARIANT -> {
            val itemId = if (entity == Translations.ITEM) id
                else ItemVariants.selectAll().where { ItemVariants.id eq id }.firstOrNull()?.get(ItemVariants.itemId)
            if (itemId == null || Items.selectAll().where { Items.id eq itemId }.empty()) return
            outbox.write("item.updated", "item", itemId, buildJsonObject {
                put("itemId", itemId)
                put("item", itemSnapshotJson(itemId))
            })
        }
        Translations.CATEGORY -> {
            if (Categories.selectAll().where { Categories.id eq id }.empty()) return
            outbox.write("category.updated", "category", id, buildJsonObject {
                put("categoryId", id)
                put("category", categorySnapshotJson(id))
            })
        }
        Translations.ZONE -> {
            if (Zones.selectAll().where { Zones.id eq id }.empty()) return
            outbox.write("zone.renamed", "zone", id, buildJsonObject {
                put("zoneId", id)
                put("names", names(Translations.namesOf(Translations.ZONE, id)))
            })
        }
    }
}

/** Live items only — the first-run catalog.snapshot bootstrap payload. */
internal fun allLiveItemsJson(): JsonArray = JsonArray(liveItemSnapshots(null))

/**
 * Snapshots of the live items (all, or just [ids]) in two queries — not one
 * variants query per item, which at 5,000 products is the slow part.
 */
internal fun liveItemSnapshots(ids: Collection<String>?): List<JsonObject> {
    val wanted = ids?.toSet()
    val variantsByItem = ItemVariants.select(variantColumns()).orderBy(ItemVariants.sortOrder).toList()
        .let { rows -> if (wanted == null) rows else rows.filter { it[ItemVariants.itemId] in wanted } }
        .groupBy { it[ItemVariants.itemId] }
    val itemNames = namesTable(Translations.ITEM)
    val variantNames = namesTable(Translations.VARIANT)
    return Items.selectAll().where { Items.deletedAt.isNull() }.orderBy(Items.id).toList()
        .let { rows -> if (wanted == null) rows else rows.filter { it[Items.id] in wanted } }
        .map {
            itemRowSnapshot(it, photoVersion = null, variantRows = variantsByItem[it[Items.id]] ?: emptyList(),
                itemNames = itemNames, variantNames = variantNames)
        }
}

/** Items per `catalog.snapshot` event: a 5,000-product shelf goes up in ~20 small events, not one huge one. */
const val SNAPSHOT_CHUNK_ITEMS = 250

/**
 * Write `catalog.snapshot` events for the live items (all, or [ids]), in
 * chunks of [SNAPSHOT_CHUNK_ITEMS]. The categories ride in the first chunk.
 * The cloud applies each one additively (an upsert per item), so an older
 * cloud takes chunks as it took the single snapshot. Inside a transaction.
 */
internal fun writeChunkedCatalogSnapshot(ids: Collection<String>? = null, reason: String = "bootstrap") {
    val items = liveItemSnapshots(ids)
    val chunks = items.chunked(SNAPSHOT_CHUNK_ITEMS).ifEmpty { listOf(emptyList()) }
    chunks.forEachIndexed { i, chunk ->
        dev.dwhipstock.pos.sdk.Outbox.write("catalog.snapshot", "catalog", "snapshot", buildJsonObject {
            if (i == 0) {
                put("categories", allCategoriesJson())
                allZoneNamesJson()?.let { put("zones", it) }
            }
            put("items", JsonArray(chunk))
            put("chunk", i + 1)
            put("chunks", chunks.size)
            put("reason", reason)
        })
    }
}
