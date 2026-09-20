package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.base.Categories
import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
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

internal fun itemSnapshotJson(itemId: String, photoVersion: Long? = null): JsonObject =
    itemRowSnapshot(Items.selectAll().where { Items.id eq itemId }.first(), photoVersion)

private fun itemRowSnapshot(row: ResultRow, photoVersion: Long?): JsonObject {
    val itemId = row[Items.id]
    val variants = ItemVariants.selectAll()
        .where { ItemVariants.itemId eq itemId }
        .orderBy(ItemVariants.sortOrder)
        .map { v ->
            buildJsonObject {
                put("id", v[ItemVariants.id])
                put("labelFr", v[ItemVariants.labelFr])
                put("labelEn", v[ItemVariants.labelEn])
                put("priceCents", v[ItemVariants.priceCents])
                put("sortOrder", v[ItemVariants.sortOrder])
                put("deleted", v[ItemVariants.deletedAt] != null)
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
        put("variants", JsonArray(variants))
    }
}

internal fun categorySnapshotJson(categoryId: String, deleted: Boolean = false): JsonObject =
    categoryRowSnapshot(Categories.selectAll().where { Categories.id eq categoryId }.first(), deleted)

private fun categoryRowSnapshot(row: ResultRow, deleted: Boolean): JsonObject = buildJsonObject {
    put("id", row[Categories.id])
    put("nameFr", row[Categories.nameFr])
    put("nameEn", row[Categories.nameEn])
    put("sortOrder", row[Categories.sortOrder])
    // categories hard-delete in the store, so a live row is never deleted;
    // the flag is only forced true for the pre-delete snapshot
    put("deleted", deleted)
}

internal fun allCategoriesJson(): JsonArray = JsonArray(
    Categories.selectAll().orderBy(Categories.sortOrder)
        .map { categoryRowSnapshot(it, deleted = false) }
)

/** Live items only — the first-run catalog.snapshot bootstrap payload. */
internal fun allLiveItemsJson(): JsonArray = JsonArray(
    Items.selectAll().where { Items.deletedAt.isNull() }
        .map { itemRowSnapshot(it, photoVersion = null) }
)
