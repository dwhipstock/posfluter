package dev.dwhipstock.poscloud.menuai

import dev.dwhipstock.poscloud.BadRequestException
import dev.dwhipstock.poscloud.PayloadTooLargeException
import dev.dwhipstock.poscloud.auth.requirePortal
import dev.dwhipstock.poscloud.menu.requireMenuEditor
import dev.dwhipstock.poscloud.portalScopes
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Menu page's AI assistant (API.md "Menu AI"). ONE store per request
 * (`?venue=` is required), owners and managers only — the same scoping and
 * role check as the portal's menu edits. Off (no MENU_AI_GEMINI_API_KEY):
 * every call but status answers 409 `menu_ai_disabled`.
 */
fun Route.menuAiRoutes(service: MenuAiService) {

    /** Whether the assistant exists here and this user may use it (the portal hides the button otherwise). */
    get("/menu-ai/status") {
        val principal = requirePortal(call)
        call.respond(AiStatusDto(service.enabled, principal.canEditMenu, service.modelName.takeIf { principal.canEditMenu },
            photos = service.photos.enabled))
    }

    post("/menu-ai/chat") {
        val req = call.receive<AiChatRequest>()
        val who = aiCaller(call, service, req.lang)
        call.respond(withContext(Dispatchers.IO) { service.chat(who, req.text) })
    }

    /** multipart: `audio` (a WAV / OGG / AAC / MP3 / FLAC clip, ≤ 5 MB) and an optional `lang` field. */
    post("/menu-ai/chat/voice") {
        // the role and scope first: a viewer's upload is never read
        aiCaller(call, service, null)
        var bytes: ByteArray? = null
        var type: String? = null
        var lang: String? = null
        call.receiveMultipart(formFieldLimit = 8L * 1024 * 1024).forEachPart { part ->
            when {
                part is PartData.FileItem && part.name == "audio" -> {
                    type = part.contentType?.toString()
                    bytes = part.provider().readCapped(AiVoice.MAX_BYTES)
                }
                part is PartData.FormItem && part.name == "lang" -> lang = part.value.take(8)
            }
            part.dispose()
        }
        val who = aiCaller(call, service, lang)
        val audio = bytes?.takeIf { it.isNotEmpty() } ?: throw BadRequestException("record something first", "menu_ai_no_audio")
        val mime = AiVoice.normalizeType(type)
            ?: throw MenuAiException(415, "menu_ai_audio_type", "send the clip as WAV, OGG, AAC, MP3 or FLAC")
        call.respond(withContext(Dispatchers.IO) { service.voice(who, AiAudio(audio, mime)) })
    }

    post("/menu-ai/apply") {
        val req = call.receive<AiApplyRequest>()
        val who = aiCaller(call, service, null)
        call.respond(withContext(Dispatchers.IO) { service.apply(who, req) })
    }

    post("/menu-ai/revert/{applyId}") {
        val applyId = call.parameters["applyId"]!!.take(64)
        val who = aiCaller(call, service, null)
        call.respond(withContext(Dispatchers.IO) { service.revert(who, applyId) })
    }

    // --- AI item photos (the assistant's picture requests and the item sheet's "Generate photo") ---

    /** {itemId, mode: generate | enhance} → a preview; nothing changes until it is accepted. */
    post("/menu-ai/photos/generate") {
        val req = call.receive<AiPhotoRequest>()
        val who = aiCaller(call, service, req.lang, photos = true)
        call.respond(withContext(Dispatchers.IO) { service.photos.generate(who, req) })
    }

    /** Make the preview the item's photo (the store gets it on its next sync). */
    post("/menu-ai/photos/{photoId}/accept") {
        val id = call.parameters["photoId"]!!.take(64)
        val who = aiCaller(call, service, null, photos = true)
        // ?everyStore=1 ("All stores" in the portal): also every other store that carries the item
        val everyStore = call.request.queryParameters["everyStore"].let { it == "1" || it == "true" }
        call.respond(withContext(Dispatchers.IO) { service.photos.accept(who, id, everyStore) })
    }

    post("/menu-ai/photos/{photoId}/discard") {
        val id = call.parameters["photoId"]!!.take(64)
        val who = aiCaller(call, service, null, photos = true)
        withContext(Dispatchers.IO) { service.photos.discard(who, id) }
        call.respond(mapOf("photoId" to id, "status" to "discarded"))
    }

    /** Put back the photo an accepted one replaced (or remove it again). */
    post("/menu-ai/photos/{photoId}/undo") {
        val id = call.parameters["photoId"]!!.take(64)
        val who = aiCaller(call, service, null, photos = true)
        call.respond(withContext(Dispatchers.IO) { service.photos.undo(who, id) })
    }
}

/**
 * Signed in (401), an owner or manager (403), the assistant on (409) — or for
 * [photos], AI photos on — exactly one of the tenant's stores (400 / 404).
 */
private fun aiCaller(call: ApplicationCall, service: MenuAiService, lang: String?, photos: Boolean = false): AiCaller {
    val requested = call.request.queryParameters["venue"]?.takeIf { it.isNotBlank() }
    val (principal, venues) = portalScopes(call)
    requireMenuEditor(principal)
    if (photos && !service.photos.enabled)
        throw MenuAiException(409, "menu_ai_photos_disabled", "AI photos are not set up on this portal")
    if (!photos && !service.enabled) throw MenuAiException(409, "menu_ai_disabled", "the AI assistant is not set up on this portal")
    if (requested == null || venues.size != 1) throw BadRequestException("pick one store for the AI assistant", "venue_required")
    return AiCaller(principal, venues.single(), MenuAiService.lang(lang))
}

private suspend fun ByteReadChannel.readCapped(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    while (!exhausted()) {
        val read = readAvailable(chunk, 0, chunk.size)
        if (read <= 0) continue
        if (out.size().toLong() + read > max) throw PayloadTooLargeException("that recording is too long", "menu_ai_audio_too_large")
        out.write(chunk, 0, read)
    }
    return out.toByteArray()
}
