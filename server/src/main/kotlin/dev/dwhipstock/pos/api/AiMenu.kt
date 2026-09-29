package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.aimenu.AiCaller
import dev.dwhipstock.pos.aimenu.MenuAiService
import dev.dwhipstock.pos.aimenu.MenuImage
import dev.dwhipstock.pos.aimenu.RoomLayoutApplyRequest
import dev.dwhipstock.pos.base.AuthService
import dev.dwhipstock.pos.sdk.Images
import io.ktor.http.content.*
import io.ktor.server.application.*
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
data class MenuAiApplyRequest(
    val managerPin: String? = null, val proposalId: String, val changeIds: List<String>,
    /** The manager confirmed a bulk change (more than 10 removals or price changes). */
    val confirmed: Boolean = false,
)

@Serializable
data class MenuAiRevertRequest(val managerPin: String? = null, val force: Boolean = false)

private const val MAX_MENU_PHOTO_BYTES = 12 * 1024 * 1024

/** The session's user and device and the approving manager: rate limit key and AI log line. */
private fun ApplicationCall.aiCaller(approverId: String) = sessionUser().let {
    AiCaller(it.userId, approverId, it.deviceId, it.languageCode)
}

/**
 * AI menu setup (add-on, online only). Manager session plus a manager PIN on
 * every call that spends money or changes the menu.
 *
 *   GET  /menu-ai/status    configured? available? online? undo? (never the key)
 *   POST /menu-ai/photos    multipart photo(s) + managerPin (+ note) → a proposal (nothing changes)
 *   POST /floor-objects/ai-suggest  multipart one photo + managerPin → a CUSTOM floor-object suggestion
 *   POST /zones/{zoneId}/ai-layout  multipart 1–4 pictures + managerPin → a room layout to preview (nothing changes)
 *   POST /zones/{zoneId}/ai-layout/apply  JSON {managerPin, proposalId, mode replace|merge, tables, objects} → saved as a "room" change set
 *   POST /menu-ai/chat      JSON {managerPin, text} → a proposal (nothing changes)
 *   POST /menu-ai/translate JSON {managerPin} → set_name proposal for missing es / de names (nothing changes)
 *   POST /menu-ai/apply     JSON {managerPin, proposalId, changeIds, confirmed?} → applied through CatalogOps, saved as a change set
 *                           (a bulk proposal needs confirmed: true, else 409 menu_ai_confirm_required)
 *   GET  /menu-ai/requests  the manager's AI log: who, device, when, kind, outcome (no prompts, photos or keys)
 *   GET  /menu-ai/history   (?source=room: the floor-plan ones) the last 20 applied change sets (who, when, source, reverted?)
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
        val who = call.aiCaller(requireManagerApproval(auth, managerPin))
        call.respond(onIo { ai.fromPhotos(images, note, who) })
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
        val who = call.aiCaller(requireManagerApproval(auth, managerPin))
        val photo = requireNotNull(image) { "a photo is required" }
        call.respond(onIo { ai.suggestRoomObject(photo, who) })
    }

    // Floor-plan "Set up from picture": 1–4 pictures → a layout to preview (nothing changes,
    // the pictures are dropped after the call); apply saves a "room" change set.
    post("/zones/{zoneId}/ai-layout") {
        requireManagerSession(call)
        val zoneId = call.parameters["zoneId"]!!
        var managerPin: String? = null
        val images = mutableListOf<MenuImage>()
        call.receiveMultipart(formFieldLimit = MAX_MENU_PHOTO_BYTES.toLong()).forEachPart { part ->
            when (part) {
                is PartData.FormItem -> if (part.name == "managerPin") managerPin = part.value
                is PartData.FileItem -> {
                    val type = part.contentType?.toString()?.lowercase()
                    val bytes = part.provider().toByteArray()
                    require(type in ALLOWED_TYPES) { "only JPEG or PNG pictures are supported" }
                    require(bytes.size <= MAX_MENU_PHOTO_BYTES) { "picture too large (max 12MB)" }
                    require(images.size < MenuAiService.MAX_ROOM_PHOTOS) { "at most ${MenuAiService.MAX_ROOM_PHOTOS} pictures at a time" }
                    val upright = if (type == "image/jpeg") Images.normalizeJpegOrientation(bytes) else bytes
                    // table numbers on a printed plan need the pixels
                    val sent = Images.downscaleToJpeg(upright, 2048)
                    images += if (sent != null) MenuImage(sent, "image/jpeg") else MenuImage(upright, type!!)
                }
                else -> {}
            }
            part.dispose()
        }
        val who = call.aiCaller(requireManagerApproval(auth, managerPin))
        call.respond(onIo { ai.roomFromPhotos(zoneId, images, who) })
    }

    post("/zones/{zoneId}/ai-layout/apply") {
        requireManagerSession(call)
        val req = call.receive<RoomLayoutApplyRequest>()
        val approver = requireManagerApproval(auth, req.managerPin)
        val user = call.sessionUser().userId
        call.respond(onIo { ai.applyRoom(req, user, approver) })
    }

    post("/menu-ai/chat") {
        requireManagerSession(call)
        val req = call.receive<MenuAiChatRequest>()
        val who = call.aiCaller(requireManagerApproval(auth, req.managerPin))
        call.respond(onIo { ai.chat(req.text, who) })
    }

    post("/menu-ai/translate") {
        requireManagerSession(call)
        val req = call.receive<MenuAiTranslateRequest>()
        val who = call.aiCaller(requireManagerApproval(auth, req.managerPin))
        call.respond(onIo { ai.translate(who) })
    }

    post("/menu-ai/apply") {
        requireManagerSession(call)
        val req = call.receive<MenuAiApplyRequest>()
        val approver = requireManagerApproval(auth, req.managerPin)
        val user = call.sessionUser().userId
        call.respond(onIo { ai.apply(req.proposalId, req.changeIds, user, approver, req.confirmed) })
    }

    get("/menu-ai/requests") {
        requireManagerSession(call)
        call.respond(onIo { ai.requests() })
    }

    get("/menu-ai/history") {
        requireManagerSession(call)
        call.respond(onIo { ai.history(rooms = call.request.queryParameters["source"] == "room") })
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
