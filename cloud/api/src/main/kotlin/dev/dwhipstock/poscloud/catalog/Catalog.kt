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
            it[barcode] = item.str("barcode")
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
            it[createdAt] = dev.dwhipstock.poscloud.CloudTime.now()
        } get CatalogChanges.version
    }
}
