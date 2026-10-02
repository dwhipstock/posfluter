package dev.dwhipstock.poscloud.menu

import dev.dwhipstock.poscloud.CloudTime
import dev.dwhipstock.poscloud.catalog.Scope
import dev.dwhipstock.poscloud.db.CatalogItems
import dev.dwhipstock.poscloud.db.ItemPhotos
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import java.security.MessageDigest

/**
 * A photo the portal gives a store's item (CONTRACT §10 "Photos from the
 * portal"): an accepted AI photo, its Undo, or the photo of an item copied
 * from another store. Written like a store upload, plus a `photo` feed entry
 * the store fetches the binary for. Never the bytes in the feed.
 */
object MenuPhotos {
    const val FEED_ENTITY = "photo"

    /**
     * Make [bytes] the item's photo at [scope] (inside a transaction): item_photos,
     * the item's photo version and source, and the feed entry. The version only
     * ever goes up ([prevVersion] + 1 at least), so the store can tell this photo
     * from a newer one. Answers the version.
     */
    fun write(scope: Scope, itemId: String, bytes: ByteArray, contentType: String, source: String?, prevVersion: Long?, nowMs: Long): Long {
        val version = maxOf(nowMs, (prevVersion ?: 0L) + 1)
        ItemPhotos.upsert {
            it[tenantId] = scope.tenantId
            it[venueId] = scope.venueId
            it[ItemPhotos.itemId] = itemId
            it[content] = bytes
            it[ItemPhotos.contentType] = contentType
            it[ItemPhotos.version] = version
            it[updatedAt] = CloudTime.now()
        }
        CatalogItems.update({
            (CatalogItems.tenantId eq scope.tenantId) and (CatalogItems.venueId eq scope.venueId) and (CatalogItems.id eq itemId)
        }) {
            it[photoVersion] = version
            it[photoSource] = source
        }
        MenuState.appendFeed(scope, FEED_ENTITY, itemId, feedEntry(itemId, version, contentType, bytes, source), "portal")
        return version
    }

    /** The feed entry: what the store needs to fetch and check the binary. */
    fun feedEntry(itemId: String, version: Long, contentType: String, bytes: ByteArray, source: String?): JsonObject =
        buildJsonObject {
            put("itemId", itemId)
            put("deleted", false)
            put("version", version)
            put("contentType", contentType)
            put("bytes", bytes.size)
            put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            source?.let { put("source", it) }
        }
}
