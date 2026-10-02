package dev.dwhipstock.pos.sync

import dev.dwhipstock.pos.aiphotos.PhotoSource
import dev.dwhipstock.pos.api.MAX_PHOTO_BYTES
import dev.dwhipstock.pos.api.savePhoto
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.db.SyncState
import dev.dwhipstock.pos.sdk.PhotoStore
import kotlinx.serialization.json.Json
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
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * Photos from the manager portal (CONTRACT §10 "Photos from the portal"): an
 * AI photo a manager accepted there, or the photo an Undo put back. The menu
 * feed carries a `photo` entry (item id, version, type, size, sha-256 — never
 * the bytes); the page that brings it queues it here in the same transaction
 * as its cursor ([queue]), and [drain] then fetches the binary
 * (`GET /v1/store/menu/photos/{itemId}`) OUTSIDE any transaction.
 *
 * The store trusts nothing it receives: the photo is applied only if the
 * cloud's current photo is still the one the entry names (else a newer write
 * — this store's own upload, or a later portal photo — superseded it), it is
 * at most 2 MB, its bytes are a real JPEG or PNG of a sane size whatever the
 * Content-Type says, and its size and sha-256 match the entry. Applied through
 * the one photo pipeline ([savePhoto]) while [MenuClock.applyingCloud], so it
 * is not sent back up as the store's own upload.
 *
 * Offline or a cloud error: the entry stays queued and is retried every pull.
 */
object PhotoSync {
    private val log = LoggerFactory.getLogger(PhotoSync::class.java)
    const val ENTITY = "photo"
    const val PENDING = "menu_photos_pending"
    /** Tries per entry before it is dropped (e.g. an item this store never got). */
    private const val MAX_TRIES = 30
    private const val MAX_PENDING = 500
    private const val MIN_SIDE = 16
    private const val MAX_SIDE = 8192

    /** Queue the feed entry for [itemId] (inside the page's transaction); a newer entry replaces an older one. */
    fun queue(itemId: String, data: JsonObject) {
        val pending = load()
        pending.remove(itemId)
        pending[itemId] = buildJsonObject {
            data.forEach { (k, v) -> if (k != "tries") put(k, v) }
            put("tries", 0)
        }
        save(pending)
    }

    fun pendingCount(): Int = transaction { load().size }

    /** Fetch and apply every queued photo. Never throws: a failure is logged and retried next time. */
    fun drain(transport: CloudTransport, photos: PhotoStore) {
        val pending = transaction { load() }
        if (pending.isEmpty()) return
        for ((itemId, entry) in pending.toList()) {
            val outcome = runCatching { applyOne(transport, photos, itemId, entry) }
                .getOrElse { Outcome.Retry(it.message ?: it.javaClass.simpleName) }
            transaction {
                val now = load()
                // a newer entry may have been queued meanwhile: only settle the one handled here
                if (now[itemId] != entry) return@transaction
                when (outcome) {
                    is Outcome.Done -> now.remove(itemId)
                    is Outcome.Drop -> {
                        log.warn("photo from the portal for $itemId not applied: ${outcome.why}")
                        now.remove(itemId)
                    }
                    is Outcome.Retry -> {
                        val tries = ((entry["tries"] as? JsonPrimitive)?.intOrNull ?: 0) + 1
                        if (tries >= MAX_TRIES) {
                            log.warn("photo from the portal for $itemId dropped after $tries tries: ${outcome.why}")
                            now.remove(itemId)
                        } else {
                            if (tries == 1) log.info("photo from the portal for $itemId: ${outcome.why}; retried next sync")
                            now[itemId] = JsonObject(entry + ("tries" to JsonPrimitive(tries)))
                        }
                    }
                }
                save(now)
            }
        }
    }

    private sealed class Outcome {
        object Done : Outcome()
        class Drop(val why: String) : Outcome()
        class Retry(val why: String) : Outcome()
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull

    private fun applyOne(transport: CloudTransport, photos: PhotoStore, itemId: String, entry: JsonObject): Outcome {
        val exists = transaction { Items.selectAll().where { Items.id eq itemId }.any() }
        if (!exists) return Outcome.Retry("the item is not on this store yet")
        val deleted = (entry["deleted"] as? JsonPrimitive)?.booleanOrNull == true
        val res = transport.fetchMenuPhoto(itemId, MAX_PHOTO_BYTES) ?: return Outcome.Retry("this cloud can't send photos")
        if (deleted) {
            return when (res.status) {
                404 -> {
                    MenuClock.applyingCloud {
                        photos.delete(itemId)
                        transaction {
                            Items.update({ Items.id eq itemId }) {
                                it[photoPath] = null
                                it[photoSource] = null
                            }
                        }
                    }
                    log.info("photo from the portal: $itemId has no photo again (undone there)")
                    Outcome.Done
                }
                200 -> Outcome.Done // a photo was set again since: its own entry brings it
                else -> Outcome.Retry("HTTP ${res.status}")
            }
        }
        when (res.status) {
            200 -> {}
            404 -> return Outcome.Done // removed since: a later entry says so
            else -> return Outcome.Retry("HTTP ${res.status}")
        }
        val want = entry.l("version")
        if (want == null || res.version != want) return Outcome.Done // superseded by a newer photo
        val problem = check(res.bytes, res.contentType, entry)
        if (problem != null) return Outcome.Drop(problem)
        val type = sniff(res.bytes)!!
        val source = PhotoSource.parse(res.source ?: entry.s("source"))
        MenuClock.applyingCloud { savePhoto(photos, itemId, res.bytes, type, source) }
        log.info("photo from the portal applied to $itemId (${res.bytes.size} bytes, ${source.wire})")
        return Outcome.Done
    }

    /** Why [bytes] must not become a photo, or null when it is a real JPEG / PNG matching [entry]. */
    internal fun check(bytes: ByteArray, contentType: String?, entry: JsonObject): String? {
        if (bytes.isEmpty()) return "empty"
        if (bytes.size > MAX_PHOTO_BYTES) return "larger than 2 MB"
        val type = sniff(bytes) ?: return "not a JPEG or PNG"
        val declared = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (declared != null && declared != type) return "sent as $declared but the bytes are $type"
        entry.s("contentType")?.let { if (it != type) return "the feed says $it but the bytes are $type" }
        entry.l("bytes")?.let { if (it != bytes.size.toLong()) return "size differs from the feed entry" }
        entry.s("sha256")?.let { want ->
            val got = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (!got.equals(want, ignoreCase = true)) return "checksum differs from the feed entry"
        }
        val (w, h) = dimensions(bytes, type) ?: return "unreadable $type header"
        if (w < MIN_SIDE || h < MIN_SIDE || w > MAX_SIDE || h > MAX_SIDE) return "odd size ${w}x$h"
        return null
    }

    /** "image/jpeg" | "image/png" from the magic bytes, or null. */
    internal fun sniff(b: ByteArray): String? = when {
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size > 24 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() &&
            b[3] == 'G'.code.toByte() && b[4] == 0x0D.toByte() && b[5] == 0x0A.toByte() &&
            b[6] == 0x1A.toByte() && b[7] == 0x0A.toByte() -> "image/png"
        else -> null
    }

    /**
     * Width and height from the header alone (no image decoder: this runs on
     * the Android tablet too, which has no ImageIO): PNG's IHDR, a JPEG's
     * first SOF marker.
     */
    internal fun dimensions(b: ByteArray, type: String): Pair<Int, Int>? {
        fun u16(i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        fun u32(i: Int) = (u16(i) shl 16) or u16(i + 2)
        if (type == "image/png") {
            if (String(b, 12, 4, Charsets.US_ASCII) != "IHDR") return null
            return u32(16) to u32(20)
        }
        var i = 2
        while (i + 9 < b.size) {
            if (b[i] != 0xFF.toByte()) return null
            val m = b[i + 1].toInt() and 0xFF
            when {
                m == 0xFF -> { i++; continue } // fill byte
                m == 0xD8 || m in 0xD0..0xD7 || m == 0x01 -> { i += 2; continue }
                m == 0xD9 || m == 0xDA -> return null // end / scan before any frame header
                m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC -> return u16(i + 7) to u16(i + 5)
                else -> {
                    val len = u16(i + 2)
                    if (len < 2) return null
                    i += 2 + len
                }
            }
        }
        return null
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun load(): LinkedHashMap<String, JsonObject> {
        val out = LinkedHashMap<String, JsonObject>()
        val raw = SyncState.get(PENDING) ?: return out
        runCatching { json.parseToJsonElement(raw) as JsonObject }.getOrNull()?.forEach { (k, v) ->
            (v as? JsonObject)?.let { out[k] = it }
        }
        return out
    }

    private fun save(pending: Map<String, JsonObject>) {
        val kept = pending.entries.toList().takeLast(MAX_PENDING)
        if (kept.size < pending.size) log.warn("photos from the portal: ${pending.size - kept.size} queued photo(s) dropped (over $MAX_PENDING)")
        SyncState.set(PENDING, JsonObject(kept.associate { it.key to it.value }).toString())
    }
}
