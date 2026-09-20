package dev.dwhipstock.pos.api

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

private const val MAX_PHOTO_BYTES = 2 * 1024 * 1024
private val ALLOWED_TYPES = setOf("image/jpeg", "image/png")

/**
 * Item photos (M5): upload is staff-side and manager-gated; serving is open
 * because the customer scan-to-order menu on guests' phones loads the images.
 */
fun Route.photoRoutes(photos: PhotoStore, auth: AuthService) {

    /** Multipart: `photo` file part + `managerPin` form field. Replaces any existing photo. */
    post("/items/{itemId}/photo") {
        val itemId = call.parameters["itemId"]!!
        transaction {
            Items.selectAll().where { Items.id eq itemId }.firstOrNull()
        } ?: throw NotFoundException("item $itemId not found")

        var managerPin: String? = null
        var bytes: ByteArray? = null
        var contentType: String? = null
        call.receiveMultipart().forEachPart { part ->
            when (part) {
                is PartData.FormItem -> if (part.name == "managerPin") managerPin = part.value
                is PartData.FileItem -> {
                    contentType = part.contentType?.toString()?.lowercase()
                    bytes = part.provider().toByteArray()
                }
                else -> {}
            }
            part.dispose()
        }
        requireManagerApproval(auth, managerPin)

        var data = bytes ?: throw IllegalArgumentException("photo file part required")
        require(contentType in ALLOWED_TYPES) { "only JPEG or PNG photos are supported" }
        require(data.size <= MAX_PHOTO_BYTES) { "photo too large (max 2MB)" }
        // portrait camera shots: bake EXIF rotation into the pixels once, here
        if (contentType == "image/jpeg") data = dev.dwhipstock.pos.sdk.Images.normalizeJpegOrientation(data)

        val path = photos.save(itemId, data, contentType!!)
        transaction {
            Items.update({ Items.id eq itemId }) { it[photoPath] = path }
            Outbox.write("item.photo_uploaded", "item", itemId, buildJsonObject {
                put("itemId", itemId)
                put("path", path)
                put("bytes", data.size)
                put("item", itemSnapshotJson(itemId, photoVersion = photos.version(itemId)))
            })
        }
        call.respond(HttpStatusCode.Created,
            mapOf("itemId" to itemId, "photoVersion" to (photos.version(itemId) ?: 0L).toString()))
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

/** Nearest not-smaller thumbnail bucket (capped at the largest). */
private fun snapWidth(w: Int): Int = listOf(128, 256, 512).firstOrNull { it >= w } ?: 512
