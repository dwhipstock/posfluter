package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.aiphotos.AiPhotoService
import dev.dwhipstock.pos.aiphotos.ItemFacts
import dev.dwhipstock.pos.base.AuthService
import dev.dwhipstock.pos.base.Categories
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.restaurant.NotFoundException
import dev.dwhipstock.pos.sdk.Images
import dev.dwhipstock.pos.sdk.PhotoStore
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class AiGenerateRequest(val managerPin: String? = null, val count: Int? = null)

@Serializable
data class AiChooseRequest(val managerPin: String? = null, val candidateId: String)

/** Largest photo a manager may send to "Snap and enhance" (it is downscaled before it goes out). */
private const val MAX_ENHANCE_INPUT_BYTES = 12 * 1024 * 1024

/**
 * AI menu photos (paid add-on, online only). All behind the session gate;
 * the three that spend money or change the menu also take a manager PIN,
 * like the photo upload.
 *
 *   GET  /ai-photos/status                    configured? available? online? (never the key)
 *   POST /items/{id}/ai-photo/generate        JSON {managerPin, count?} → 2–4 candidates
 *   POST /items/{id}/ai-photo/enhance         multipart photo + managerPin (+ count) → candidates
 *   POST /items/{id}/ai-photo/choose          JSON {managerPin, candidateId} → saved as the photo
 *
 * The chosen candidate is saved through [savePhoto] (the ordinary pipeline:
 * outbox event, portal sync) with its provenance. Nothing here is on the path
 * of selling; offline or off, these answer a coded error and change nothing.
 */
fun Route.aiPhotoRoutes(ai: AiPhotoService, photos: PhotoStore, auth: AuthService) {
    get("/ai-photos/status") { call.respond(onIo { ai.status() }) }

    post("/items/{itemId}/ai-photo/generate") {
        val itemId = call.parameters["itemId"]!!
        val req = runCatching { call.receive<AiGenerateRequest>() }.getOrDefault(AiGenerateRequest())
        requireManagerApproval(auth, req.managerPin)
        val facts = itemFacts(itemId)
        call.respond(onIo { ai.generate(itemId, facts, req.count) })
    }

    post("/items/{itemId}/ai-photo/enhance") {
        val itemId = call.parameters["itemId"]!!
        var managerPin: String? = null
        var count: Int? = null
        var bytes: ByteArray? = null
        var contentType: String? = null
        call.receiveMultipart(formFieldLimit = MAX_ENHANCE_INPUT_BYTES.toLong()).forEachPart { part ->
            when (part) {
                is PartData.FormItem -> when (part.name) {
                    "managerPin" -> managerPin = part.value
                    "count" -> count = part.value.toIntOrNull()
                }
                is PartData.FileItem -> {
                    contentType = part.contentType?.toString()?.lowercase()
                    bytes = part.provider().toByteArray()
                }
                else -> {}
            }
            part.dispose()
        }
        requireManagerApproval(auth, managerPin)
        val facts = itemFacts(itemId)
        var data = bytes ?: throw IllegalArgumentException("photo file part required")
        require(contentType in ALLOWED_TYPES) { "only JPEG or PNG photos are supported" }
        require(data.size <= MAX_ENHANCE_INPUT_BYTES) { "photo too large (max 12MB)" }
        if (contentType == "image/jpeg") data = Images.normalizeJpegOrientation(data)
        // a phone photo is far bigger than the model needs: send ~1.5K px
        val sent = Images.downscaleToJpeg(data, 1536)
        val type = if (sent != null) "image/jpeg" else contentType!!
        call.respond(onIo { ai.enhance(itemId, facts, sent ?: data, type, count) })
    }

    post("/items/{itemId}/ai-photo/choose") {
        val itemId = call.parameters["itemId"]!!
        val req = call.receive<AiChooseRequest>()
        requireManagerApproval(auth, req.managerPin)
        itemFacts(itemId) // 404 for an unknown item
        val (image, source) = ai.take(itemId, req.candidateId)
            ?: throw NotFoundException("that AI photo has expired; generate again")
        // candidates are ~1K px already; shrink anything bigger to the store's usual size
        val data = if (image.bytes.size > MAX_PHOTO_BYTES) Images.downscaleToJpeg(image.bytes, 1200) ?: image.bytes
            else image.bytes
        val type = if (data !== image.bytes) "image/jpeg" else image.contentType
        require(data.size <= MAX_PHOTO_BYTES) { "photo too large (max 2MB)" }
        call.respond(HttpStatusCode.Created, savePhoto(photos, itemId, data, type, source))
    }
}

/** The item's English text for the prompt (French when English is blank). 404 if unknown. */
private fun itemFacts(itemId: String): ItemFacts = transaction {
    val row = Items.selectAll().where { Items.id eq itemId }.firstOrNull()
        ?.takeIf { it[Items.deletedAt] == null }
        ?: throw NotFoundException("item $itemId not found")
    val category = Categories.selectAll().where { Categories.id eq row[Items.categoryId] }.firstOrNull()
    ItemFacts(
        name = row[Items.nameEn].ifBlank { row[Items.nameFr] },
        description = row[Items.descriptionEn].ifBlank { row[Items.descriptionFr] },
        category = category?.let { it[Categories.nameEn].ifBlank { it[Categories.nameFr] } } ?: row[Items.categoryId],
    )
}
