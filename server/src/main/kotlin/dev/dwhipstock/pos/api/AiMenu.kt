package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.aimenu.MenuAiService
import dev.dwhipstock.pos.aimenu.MenuImage
import dev.dwhipstock.pos.base.AuthService
import dev.dwhipstock.pos.sdk.Images
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.serialization.Serializable

@Serializable
data class MenuAiChatRequest(val managerPin: String? = null, val text: String)

@Serializable
data class MenuAiTranslateRequest(val managerPin: String? = null)

@Serializable
data class MenuAiApplyRequest(val managerPin: String? = null, val proposalId: String, val changeIds: List<String>)

@Serializable
data class MenuAiRevertRequest(val managerPin: String? = null, val force: Boolean = false)

private const val MAX_MENU_PHOTO_BYTES = 12 * 1024 * 1024

/**
 * AI menu setup (add-on, online only). Manager session plus a manager PIN on
 * every call that spends money or changes the menu.
 *
 *   GET  /menu-ai/status    configured? available? online? undo? (never the key)
 *   POST /menu-ai/photos    multipart photo(s) + managerPin (+ note) → a proposal (nothing changes)
 *   POST /floor-objects/ai-suggest  multipart one photo + managerPin → a CUSTOM floor-object suggestion
 *   POST /menu-ai/chat      JSON {managerPin, text} → a proposal (nothing changes)
 *   POST /menu-ai/translate JSON {managerPin} → set_name proposal for missing es / de names (nothing changes)
 *   POST /menu-ai/apply     JSON {managerPin, proposalId, changeIds} → applied through CatalogOps, saved as a change set
 *   GET  /menu-ai/history   the last 20 applied change sets (who, when, source, reverted?)
 *   POST /menu-ai/history/{setId}/revert   JSON {managerPin, force?} → the before state put back;
 *                           409 menu_ai_revert_conflict {titles} when a later edit touched the same things (resend with force)
 */
fun Route.menuAiRoutes(ai: MenuAiService, auth: AuthService) {
    get("/menu-ai/status") { call.respond(onIo { ai.status() }) }

    post("/menu-ai/photos") {
        requireManagerSession(call)
        var managerPin: String? = null
        var note: String? = null
        val images = mutableListOf<MenuImage>()
        call.receiveMultipart(formFieldLimit = MAX_MENU_PHOTO_BYTES.toLong()).forEachPart { part ->
            when (part) {
                is PartData.FormItem -> when (part.name) {
                    "managerPin" -> managerPin = part.value
                    "note" -> note = part.value
                }
                is PartData.FileItem -> {
                    val type = part.contentType?.toString()?.lowercase()
                    val bytes = part.provider().toByteArray()
                    require(type in ALLOWED_TYPES) { "only JPEG or PNG photos are supported" }
                    require(bytes.size <= MAX_MENU_PHOTO_BYTES) { "photo too large (max 12MB)" }
                    require(images.size < MenuAiService.MAX_PHOTOS) { "at most ${MenuAiService.MAX_PHOTOS} photos at a time" }
                    val upright = if (type == "image/jpeg") Images.normalizeJpegOrientation(bytes) else bytes
                    // small print needs more pixels than a dish photo
                    val sent = Images.downscaleToJpeg(upright, 2048)
                    images += if (sent != null) MenuImage(sent, "image/jpeg") else MenuImage(upright, type!!)
                }
                else -> {}
            }
            part.dispose()
        }
        requireManagerApproval(auth, managerPin)
        call.respond(onIo { ai.fromPhotos(images, note) })
    }

    // Floor-plan "Add from photo": one photo → a CUSTOM object suggestion
    // (nothing is created, the photo is dropped after the call).
    post("/floor-objects/ai-suggest") {
        requireManagerSession(call)
        var managerPin: String? = null
        var image: MenuImage? = null
        call.receiveMultipart(formFieldLimit = MAX_MENU_PHOTO_BYTES.toLong()).forEachPart { part ->
            when (part) {
                is PartData.FormItem -> if (part.name == "managerPin") managerPin = part.value
                is PartData.FileItem -> {
                    val type = part.contentType?.toString()?.lowercase()
                    val bytes = part.provider().toByteArray()
                    require(type in ALLOWED_TYPES) { "only JPEG or PNG photos are supported" }
                    require(bytes.size <= MAX_MENU_PHOTO_BYTES) { "photo too large (max 12MB)" }
                    val upright = if (type == "image/jpeg") Images.normalizeJpegOrientation(bytes) else bytes
                    val sent = Images.downscaleToJpeg(upright, 1024)
                    image = if (sent != null) MenuImage(sent, "image/jpeg") else MenuImage(upright, type!!)
                }
                else -> {}
            }
            part.dispose()
        }
        requireManagerApproval(auth, managerPin)
        val photo = requireNotNull(image) { "a photo is required" }
        call.respond(onIo { ai.suggestRoomObject(photo) })
    }

    post("/menu-ai/chat") {
        requireManagerSession(call)
        val req = call.receive<MenuAiChatRequest>()
        requireManagerApproval(auth, req.managerPin)
        call.respond(onIo { ai.chat(req.text) })
    }

    post("/menu-ai/translate") {
        requireManagerSession(call)
        val req = call.receive<MenuAiTranslateRequest>()
        requireManagerApproval(auth, req.managerPin)
        call.respond(onIo { ai.translate() })
    }

    post("/menu-ai/apply") {
        requireManagerSession(call)
        val req = call.receive<MenuAiApplyRequest>()
        val approver = requireManagerApproval(auth, req.managerPin)
        val user = call.sessionUser().userId
        call.respond(onIo { ai.apply(req.proposalId, req.changeIds, user, approver) })
    }

    get("/menu-ai/history") {
        requireManagerSession(call)
        call.respond(onIo { ai.history() })
    }

    post("/menu-ai/history/{setId}/revert") {
        requireManagerSession(call)
        val req = runCatching { call.receive<MenuAiRevertRequest>() }.getOrDefault(MenuAiRevertRequest())
        requireManagerApproval(auth, req.managerPin)
        val user = call.sessionUser().userId
        val n = onIo { ai.revert(call.parameters["setId"]!!, user, req.force) }
        call.respond(mapOf("reverted" to n))
    }
}
