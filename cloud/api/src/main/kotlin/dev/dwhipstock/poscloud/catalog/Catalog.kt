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
import org.jetbrains.exposed.sql.batchUpsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.upsert
import java.time.OffsetDateTime

/** (tenant, venue) resolved from a store API key or a portal session. */
data class Scope(val tenantId: String, val venueId: String)

// tolerant JSON accessors — legacy payloads carry whatever keys they carry
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
/** A store-sent timestamp (offset or legacy zone-less venue-local) → instant; see CloudTime.parse. */
fun JsonObject.instant(key: String, zone: java.time.ZoneId): java.time.OffsetDateTime? =
    dev.dwhipstock.poscloud.CloudTime.parse(str(key), zone)

/**
 * Display mirror of each store's catalog: full-snapshot upserts (CONTRACT §2)
 * from the ingest path only — the tablet owns its menu (one-way sync). The
 * change feed ([appendChange]) now only carries device revocations down.
 */
object Catalog {
    /** The provenance values a store may send (anything else is ignored). */
    val PHOTO_SOURCES = setOf("original", "ai_generated", "ai_enhanced")

    /** Apply an item snapshot (incl. soft-deleted variants). No-op without an id. */
    fun applyItemSnapshot(scope: Scope, item: JsonObject) = applyItemSnapshots(scope, listOf(item))

    /**
     * Apply many item snapshots at once (a `catalog.snapshot` chunk of a few
     * hundred products): one read of the stored photo versions, one batched
     * upsert of the items and one of their variants — not three statements
     * per product. Additive: products absent from [items] are left as they are
     * (a snapshot is one chunk of the catalog, never "the whole menu").
     * A product listed twice keeps its last snapshot.
     */
    fun applyItemSnapshots(scope: Scope, items: List<JsonObject>) {
        val byId = LinkedHashMap<String, JsonObject>()
        for (item in items) item.str("id")?.let { byId[it] = item }
        if (byId.isEmpty()) return
        // photoVersion is an optional hint most POS snapshots omit — the upsert
        // covers every column, so an absent key must not wipe the stored version
        // (portal thumbnails would vanish on any POS edit of the item)
        // (photoSource, the photo's provenance, rides with it and is kept the same way)
        val missingPhoto = byId.filterValues { it.long("photoVersion") == null || it.str("photoSource") !in PHOTO_SOURCES }.keys
        val stored = if (missingPhoto.isEmpty()) emptyMap() else
            missingPhoto.chunked(1000).flatMap { ids ->
                CatalogItems.select(CatalogItems.id, CatalogItems.photoVersion, CatalogItems.photoSource).where {
                    (CatalogItems.tenantId eq scope.tenantId) and (CatalogItems.venueId eq scope.venueId) and
                        (CatalogItems.id inList ids) and CatalogItems.photoVersion.isNotNull()
                }.map { it[CatalogItems.id] to (it[CatalogItems.photoVersion] to it[CatalogItems.photoSource]) }
            }.toMap()
        CatalogItems.batchUpsert(byId.entries, shouldReturnGeneratedValues = false) { (itemId, item) ->
            this[CatalogItems.tenantId] = scope.tenantId
            this[CatalogItems.venueId] = scope.venueId
            this[CatalogItems.id] = itemId
            this[CatalogItems.nameFr] = item.str("nameFr") ?: ""
            this[CatalogItems.nameEn] = item.str("nameEn") ?: ""
            this[CatalogItems.descriptionFr] = item.str("descriptionFr") ?: ""
            this[CatalogItems.descriptionEn] = item.str("descriptionEn") ?: ""
            this[CatalogItems.categoryId] = item.str("categoryId") ?: ""
            this[CatalogItems.abbrev] = item.str("abbrev")
            this[CatalogItems.isAlcohol] = item.bool("isAlcohol") ?: false
            this[CatalogItems.active] = item.bool("active") ?: true
            this[CatalogItems.deleted] = item.bool("deleted") ?: false
            this[CatalogItems.photoVersion] = item.long("photoVersion") ?: stored[itemId]?.first
            this[CatalogItems.photoSource] = item.str("photoSource")?.takeIf { it in PHOTO_SOURCES }
                ?: stored[itemId]?.second
            this[CatalogItems.barcode] = item.str("barcode")
            this[CatalogItems.brand] = item.str("brand")?.trim()?.takeIf { it.isNotEmpty() }
            this[CatalogItems.subcategory] = item.str("subcategory")?.trim()?.takeIf { it.isNotEmpty() }
            this[CatalogItems.sizeLabel] = item.str("size")?.trim()?.takeIf { it.isNotEmpty() }
            this[CatalogItems.costCents] = item.long("costCents")
        }
        val variants = LinkedHashMap<String, Pair<String, JsonObject>>()
        for ((itemId, item) in byId) {
            item.arr("variants")?.forEach { element ->
                val variant = element as? JsonObject ?: return@forEach
                val variantId = variant.str("id") ?: return@forEach
                variants[variantId] = itemId to variant
            }
        }
        if (variants.isEmpty()) return
        CatalogVariants.batchUpsert(variants.entries, shouldReturnGeneratedValues = false) { (variantId, pair) ->
            val (itemId, variant) = pair
            this[CatalogVariants.tenantId] = scope.tenantId
            this[CatalogVariants.venueId] = scope.venueId
            this[CatalogVariants.id] = variantId
            this[CatalogVariants.itemId] = itemId
            this[CatalogVariants.labelFr] = variant.str("labelFr") ?: ""
            this[CatalogVariants.labelEn] = variant.str("labelEn") ?: ""
            this[CatalogVariants.priceCents] = variant.long("priceCents") ?: 0
            this[CatalogVariants.sortOrder] = variant.int("sortOrder") ?: 0
            this[CatalogVariants.deleted] = variant.bool("deleted") ?: false
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
            it[createdAt] = dev.dwhipstock.poscloud.CloudTime.now()
        } get CatalogChanges.version
    }
}
