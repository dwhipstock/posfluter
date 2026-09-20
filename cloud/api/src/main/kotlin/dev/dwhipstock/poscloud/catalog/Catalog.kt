package dev.dwhipstock.poscloud.catalog

import dev.dwhipstock.poscloud.db.CatalogCategories
import dev.dwhipstock.poscloud.db.CatalogChanges
import dev.dwhipstock.poscloud.db.CatalogItems
import dev.dwhipstock.poscloud.db.CatalogVariants
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.upsert
import java.time.LocalDateTime

/** (tenant, venue) resolved from a store API key or a portal session. */
data class Scope(val tenantId: String, val venueId: String)

// tolerant JSON accessors — legacy payloads carry whatever keys they carry
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
fun JsonObject.dateTime(key: String): LocalDateTime? =
    str(key)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

/**
 * Cloud-authoritative catalog mirror: full-snapshot upserts (CONTRACT §2/§4)
 * from both the ingest path (POS edits) and the portal menu editor, plus the
 * catalog_changes append that feeds redistribution to the store.
 */
object Catalog {

    /** Apply an item snapshot (incl. soft-deleted variants). No-op without an id. */
    fun applyItemSnapshot(scope: Scope, item: JsonObject) {
        val itemId = item.str("id") ?: return
        // photoVersion is an optional hint most POS snapshots omit — the upsert
        // covers every column, so an absent key must not wipe the stored version
        // (portal thumbnails would vanish on any POS edit of the item)
        val photoVer = item.long("photoVersion")
            ?: CatalogItems.selectAll().where {
                (CatalogItems.tenantId eq scope.tenantId) and (CatalogItems.venueId eq scope.venueId) and
                    (CatalogItems.id eq itemId)
            }.firstOrNull()?.get(CatalogItems.photoVersion)
        CatalogItems.upsert {
            it[tenantId] = scope.tenantId
            it[venueId] = scope.venueId
            it[id] = itemId
            it[nameFr] = item.str("nameFr") ?: ""
            it[nameEn] = item.str("nameEn") ?: ""
            it[descriptionFr] = item.str("descriptionFr") ?: ""
            it[descriptionEn] = item.str("descriptionEn") ?: ""
            it[categoryId] = item.str("categoryId") ?: ""
            it[abbrev] = item.str("abbrev")
            it[isAlcohol] = item.bool("isAlcohol") ?: false
            it[active] = item.bool("active") ?: true
            it[deleted] = item.bool("deleted") ?: false
            it[photoVersion] = photoVer
        }
        item.arr("variants")?.forEach { element ->
            val variant = element as? JsonObject ?: return@forEach
            val variantId = variant.str("id") ?: return@forEach
            CatalogVariants.upsert {
                it[tenantId] = scope.tenantId
                it[venueId] = scope.venueId
                it[id] = variantId
                it[CatalogVariants.itemId] = itemId
                it[labelFr] = variant.str("labelFr") ?: ""
                it[labelEn] = variant.str("labelEn") ?: ""
                it[priceCents] = variant.long("priceCents") ?: 0
                it[sortOrder] = variant.int("sortOrder") ?: 0
                it[deleted] = variant.bool("deleted") ?: false
            }
        }
    }

    fun applyCategorySnapshot(scope: Scope, category: JsonObject) {
        val categoryId = category.str("id") ?: return
        CatalogCategories.upsert {
            it[tenantId] = scope.tenantId
            it[venueId] = scope.venueId
            it[id] = categoryId
            it[nameFr] = category.str("nameFr") ?: ""
            it[nameEn] = category.str("nameEn") ?: ""
            it[sortOrder] = category.int("sortOrder") ?: 0
            it[deleted] = category.bool("deleted") ?: false
        }
    }

    /**
     * Append to the distribution stream; returns the new change version.
     * The per-tenant advisory lock serializes writers so versions become
     * visible in order — without it a slow transaction could commit a lower
     * version AFTER the store's cursor already passed it (change lost forever).
     */
    fun appendChange(scope: Scope, kind: String, entityId: String, op: String, data: JsonElement): Long {
        TransactionManager.current().exec(
            "SELECT pg_advisory_xact_lock(${scope.tenantId.hashCode().toLong()})")
        return CatalogChanges.insert {
            it[tenantId] = scope.tenantId
            it[venueId] = scope.venueId
            it[CatalogChanges.kind] = kind
            it[CatalogChanges.entityId] = entityId
            it[CatalogChanges.op] = op
            it[CatalogChanges.data] = data.toString()
            it[createdAt] = LocalDateTime.now()
        } get CatalogChanges.version
    }

    /** Full item snapshot from the mirror — the §2 shape the store applies. */
    fun itemSnapshot(scope: Scope, itemId: String): JsonObject {
        val row = CatalogItems.selectAll().where {
            (CatalogItems.tenantId eq scope.tenantId) and (CatalogItems.venueId eq scope.venueId) and
                (CatalogItems.id eq itemId)
        }.first()
        val variants = CatalogVariants.selectAll().where {
            (CatalogVariants.tenantId eq scope.tenantId) and (CatalogVariants.venueId eq scope.venueId) and
                (CatalogVariants.itemId eq itemId)
        }.orderBy(CatalogVariants.sortOrder).toList()
        return buildJsonObject {
            put("id", row[CatalogItems.id])
            put("nameFr", row[CatalogItems.nameFr])
            put("nameEn", row[CatalogItems.nameEn])
            put("descriptionFr", row[CatalogItems.descriptionFr])
            put("descriptionEn", row[CatalogItems.descriptionEn])
            put("categoryId", row[CatalogItems.categoryId])
            put("abbrev", row[CatalogItems.abbrev])
            put("isAlcohol", row[CatalogItems.isAlcohol])
            put("active", row[CatalogItems.active])
            put("deleted", row[CatalogItems.deleted])
            row[CatalogItems.photoVersion]?.let { put("photoVersion", it) }
            put("variants", buildJsonArray {
                variants.forEach { v ->
                    add(buildJsonObject {
                        put("id", v[CatalogVariants.id])
                        put("labelFr", v[CatalogVariants.labelFr])
                        put("labelEn", v[CatalogVariants.labelEn])
                        put("priceCents", v[CatalogVariants.priceCents])
                        put("sortOrder", v[CatalogVariants.sortOrder])
                        put("deleted", v[CatalogVariants.deleted])
                    })
                }
            })
        }
    }

    fun categorySnapshot(scope: Scope, categoryId: String): JsonObject {
        val row = CatalogCategories.selectAll().where {
            (CatalogCategories.tenantId eq scope.tenantId) and
                (CatalogCategories.venueId eq scope.venueId) and (CatalogCategories.id eq categoryId)
        }.first()
        return buildJsonObject {
            put("id", row[CatalogCategories.id])
            put("nameFr", row[CatalogCategories.nameFr])
            put("nameEn", row[CatalogCategories.nameEn])
            put("sortOrder", row[CatalogCategories.sortOrder])
            put("deleted", row[CatalogCategories.deleted])
        }
    }
}

/** "Lantern House Lager Tower" → "lantern-lager-tower"; French-only names fall back to [fallback]. Same rule as the store. */
fun uniqueSlug(source: String, fallback: String = "item", taken: (String) -> Boolean): String {
    val base = source.trim().lowercase()
        .replace(Regex("[^a-z0-9]+"), "-").trim('-')
        .ifBlank { fallback }
    if (!taken(base)) return base
    var n = 2
    while (taken("$base-$n")) n++
    return "$base-$n"
}
