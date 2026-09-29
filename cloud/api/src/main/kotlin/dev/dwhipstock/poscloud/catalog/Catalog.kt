package dev.dwhipstock.poscloud.catalog

import dev.dwhipstock.poscloud.db.CatalogCategories
import dev.dwhipstock.poscloud.db.CatalogChanges
import dev.dwhipstock.poscloud.db.CatalogItems
import dev.dwhipstock.poscloud.db.CatalogNames
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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.batchUpsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.upsert
import java.time.OffsetDateTime

/** Extra catalog names keyed (entity, venueId, entityId) → {lang: text}; see [Catalog.namesIn]. */
class NameIndex(private val byKey: Map<Triple<String, String, String>, Map<String, String>>) {
    private val anyVenue = byKey.entries.associate { (it.key.first to it.key.third) to it.value }

    /** This store's names for the entity, else another in-scope store's (same id), else none. */
    fun of(entity: String, venueId: String, id: String?): Map<String, String> =
        if (id == null) emptyMap() else byKey[Triple(entity, venueId, id)] ?: anyVenue[entity to id] ?: emptyMap()

    /** Only this store's names (zone ids like "upper" mean different rooms at different stores). */
    fun exact(entity: String, venueId: String, id: String?): Map<String, String> =
        if (id == null) emptyMap() else byKey[Triple(entity, venueId, id)] ?: emptyMap()
}

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
        replaceNames(scope, "item", byId.mapNotNull { (id, item) -> namesOf(item)?.let { id to it } }.toMap())
        val variants = LinkedHashMap<String, Pair<String, JsonObject>>()
        for ((itemId, item) in byId) {
            item.arr("variants")?.forEach { element ->
                val variant = element as? JsonObject ?: return@forEach
                val variantId = variant.str("id") ?: return@forEach
                variants[variantId] = itemId to variant
            }
        }
        if (variants.isEmpty()) return
        replaceNames(scope, "variant",
            variants.mapNotNull { (id, pair) -> namesOf(pair.second)?.let { id to it } }.toMap())
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
        namesOf(category)?.let { replaceNames(scope, "category", mapOf(categoryId to it)) }
    }

    /** A zone's extra names (catalog.snapshot `zones`, zone.* events); no-op without `names`. */
    fun applyZoneNames(scope: Scope, zoneId: String, zone: JsonObject) {
        namesOf(zone)?.let { replaceNames(scope, "zone", mapOf(zoneId to it)) }
    }

    /**
     * A snapshot's `names` ({lang: text}, languages beyond fr/en), or null when
     * the key is absent (an older store: keep what is stored). Blank texts drop.
     */
    fun namesOf(obj: JsonObject): Map<String, String>? {
        val names = obj["names"] as? JsonObject ?: return null
        return names.mapNotNull { (lang, v) ->
            val text = (v as? JsonPrimitive)?.contentOrNull?.trim()
            val code = lang.trim().lowercase()
            if (text.isNullOrEmpty() || code.isEmpty()) null else code to text
        }.toMap()
    }

    /** Replace every stored name of each (entity, id) in [byId]; an empty map clears them. Batched. */
    fun replaceNames(scope: Scope, entity: String, byId: Map<String, Map<String, String>>) {
        if (byId.isEmpty()) return
        byId.keys.chunked(1000).forEach { ids ->
            CatalogNames.deleteWhere {
                (tenantId eq scope.tenantId) and (venueId eq scope.venueId) and
                    (CatalogNames.entity eq entity) and (entityId inList ids)
            }
        }
        val rows = byId.flatMap { (id, names) -> names.map { (lang, text) -> Triple(id, lang, text) } }
        if (rows.isEmpty()) return
        CatalogNames.batchInsert(rows, shouldReturnGeneratedValues = false) { (id, lang, text) ->
            this[CatalogNames.tenantId] = scope.tenantId
            this[CatalogNames.venueId] = scope.venueId
            this[CatalogNames.entity] = entity
            this[CatalogNames.entityId] = id
            this[CatalogNames.lang] = lang
            this[CatalogNames.value] = text
        }
    }

    /** Stored extra names of [entities] across [venueIds], in one query. */
    fun namesIn(tenantId: String, venueIds: List<String>, entities: List<String>): NameIndex {
        if (venueIds.isEmpty()) return NameIndex(emptyMap())
        val out = HashMap<Triple<String, String, String>, MutableMap<String, String>>()
        CatalogNames.selectAll().where {
            (CatalogNames.tenantId eq tenantId) and (CatalogNames.venueId inList venueIds) and
                (CatalogNames.entity inList entities)
        }.forEach {
            out.getOrPut(Triple(it[CatalogNames.entity], it[CatalogNames.venueId], it[CatalogNames.entityId])) {
                sortedMapOf()
            }[it[CatalogNames.lang]] = it[CatalogNames.value]
        }
        return NameIndex(out)
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
