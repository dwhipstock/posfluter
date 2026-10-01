package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.aiphotos.PhotoSource
import dev.dwhipstock.pos.base.AuthService
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.restaurant.NotFoundException
import dev.dwhipstock.pos.sdk.Outbox
import dev.dwhipstock.pos.sdk.PhotoStore
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

internal const val MAX_PHOTO_BYTES = 2 * 1024 * 1024
internal val ALLOWED_TYPES = setOf("image/jpeg", "image/png")
/** The photo plus its form fields and multipart framing. */
private const val MAX_PHOTO_BODY_BYTES = MAX_PHOTO_BYTES + 64L * 1024

/**
 * Read the whole channel but refuse to hold more than [max] bytes: the moment
 * the running total passes the cap, stop reading and reject the upload.
 */
private suspend fun ByteReadChannel.readCapped(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    while (!exhausted()) { // same loop as the cloud's photo ingest (StoreRoutes.receivePhoto)
        val read = readAvailable(chunk, 0, chunk.size)
        if (read <= 0) continue
        if (out.size().toLong() + read > max) throw IllegalArgumentException("photo too large (max 2MB)")
        out.write(chunk, 0, read)
    }
    return out.toByteArray()
}

/**
 * Item photos (M5): upload is staff-side and manager-gated; serving is open
 * because the customer scan-to-order menu on guests' phones loads the images.
 */
fun Route.photoRoutes(photos: PhotoStore, auth: AuthService) {

    /**
     * Multipart: `photo` file part + `managerPin` form field. Replaces any existing photo.
     * Optional `source` (original | ai_generated | ai_enhanced, default original): copying an AI
     * photo from another store (scripts/ai-menu-photos.py --copy-to) keeps its provenance badge.
     */
    post("/items/{itemId}/photo") {
        val itemId = call.parameters["itemId"]!!
        transaction {
            Items.selectAll().where { Items.id eq itemId }.firstOrNull()
        } ?: throw NotFoundException("item $itemId not found")

        // a body far past the cap is refused before a byte of it is read
        call.request.contentLength()?.let { if (it > MAX_PHOTO_BODY_BYTES) throw IllegalArgumentException("photo too large (max 2MB)") }
        var managerPin: String? = null
        var source: String? = null
        var bytes: ByteArray? = null
        var contentType: String? = null
        // Ktor's per-part limit (default 50MB; it covers file parts too). Set just past the
        // photo cap as a backstop for a chunked body with no declared length: readCapped
        // below normally refuses first, with a clean 400
        call.receiveMultipart(formFieldLimit = MAX_PHOTO_BODY_BYTES).forEachPart { part ->
            when (part) {
                is PartData.FormItem -> when (part.name) {
                    "managerPin" -> managerPin = part.value
                    "source" -> source = part.value
                }
                is PartData.FileItem -> {
                    contentType = part.contentType?.toString()?.lowercase()
                    // the cap is enforced WHILE streaming (red-team): an oversized upload
                    // is refused as soon as it passes MAX_PHOTO_BYTES, never buffered whole
                    bytes = part.provider().readCapped(MAX_PHOTO_BYTES)
                }
                else -> {}
            }
            part.dispose()
        }
        requireManagerApproval(auth, managerPin, call)
        val photoSource = source?.let { raw ->
            PhotoSource.entries.firstOrNull { it.wire == raw } ?: throw IllegalArgumentException("unknown photo source")
        } ?: PhotoSource.ORIGINAL

        var data = bytes ?: throw IllegalArgumentException("photo file part required")
        require(contentType in ALLOWED_TYPES) { "only JPEG or PNG photos are supported" }
        require(data.size <= MAX_PHOTO_BYTES) { "photo too large (max 2MB)" }
        // portrait camera shots: bake EXIF rotation into the pixels once, here
        if (contentType == "image/jpeg") data = dev.dwhipstock.pos.sdk.Images.normalizeJpegOrientation(data)

        call.respond(HttpStatusCode.Created, savePhoto(photos, itemId, data, contentType!!, photoSource))
    }

    /** Open route: streams the photo with cache headers; ?v= busts on replace.
     *  ?w= serves a downscaled grid variant — the originals are ~1000px/170KB,
     *  a menu tile needs a fraction of that. Requested widths snap to fixed
     *  buckets so the on-disk thumbnail cache stays bounded. */
    get("/photos/{itemId}") {
        val itemId = call.parameters["itemId"]!!
        val width = call.request.queryParameters["w"]?.toIntOrNull()?.let(::snapWidth)
        val photo = (if (width != null) photos.readScaled(itemId, width) else photos.read(itemId))
            ?: throw NotFoundException("no photo for item $itemId")
        val etag = "\"${photo.version}${if (width != null) "-w$width" else ""}\""
        if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) {
            call.respond(HttpStatusCode.NotModified)
            return@get
        }
        call.response.header(HttpHeaders.ETag, etag)
        call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
        call.respondBytes(photo.bytes, ContentType.parse(photo.contentType))
    }
}

/**
 * The one photo pipeline: store the bytes, record where they came from, and
 * write the item.photo_uploaded event (its snapshot carries photoSource, and
 * CloudSync sends the binary up like any photo). Used by the manager upload
 * and by a chosen AI candidate.
 */
internal fun savePhoto(
    photos: PhotoStore, itemId: String, data: ByteArray, contentType: String, source: PhotoSource,
): Map<String, String> {
    val path = photos.save(itemId, data, contentType)
    transaction {
        Items.update({ Items.id eq itemId }) {
            it[photoPath] = path
            it[photoSource] = source.wire
        }
        Outbox.write("item.photo_uploaded", "item", itemId, buildJsonObject {
            put("itemId", itemId)
            put("path", path)
            put("bytes", data.size)
            put("source", source.wire)
            put("item", itemSnapshotJson(itemId, photoVersion = photos.version(itemId)))
        })
    }
    return mapOf("itemId" to itemId, "photoVersion" to (photos.version(itemId) ?: 0L).toString(),
        "photoSource" to source.wire)
}

/** Nearest not-smaller thumbnail bucket (capped at the largest). */
private fun snapWidth(w: Int): Int = listOf(128, 256, 512).firstOrNull { it >= w } ?: 512
