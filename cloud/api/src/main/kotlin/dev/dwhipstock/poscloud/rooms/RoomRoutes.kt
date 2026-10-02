package dev.dwhipstock.poscloud.rooms

import dev.dwhipstock.poscloud.BadRequestException
import dev.dwhipstock.poscloud.CloudTime
import dev.dwhipstock.poscloud.PayloadTooLargeException
import dev.dwhipstock.poscloud.db.Venues
import dev.dwhipstock.poscloud.menu.requireMenuEditor
import dev.dwhipstock.poscloud.menuai.AiAudio
import dev.dwhipstock.poscloud.menuai.AiCaller
import dev.dwhipstock.poscloud.menuai.MenuAiException
import dev.dwhipstock.poscloud.menuai.MenuAiService
import dev.dwhipstock.poscloud.portalScopes
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * The portal's Rooms page (API.md "Rooms"): ONE store per request (`?venue=`
 * required). Anyone signed in may look; owners and managers may use the AI
 * room assistant (a new room from a photo, a room edited by text or voice),
 * whose changes reach the store through the room sync (CONTRACT §11).
 */
fun Route.roomRoutes(service: RoomAiService) {

    get("/rooms") {
        val (principal, venues) = portalScopes(call)
        if (call.request.queryParameters["venue"].isNullOrBlank() || venues.size != 1)
            throw BadRequestException("pick one store", "venue_required")
        val v = venues.single()
        val response = transaction {
            val row = Venues.selectAll().where { (Venues.tenantId eq principal.tenantId) and (Venues.id eq v.venueId) }.first()
            RoomsResponse(
                venueId = v.venueId, venueName = v.name,
                editable = row[Venues.roomsSyncAt] != null,
                lastPullAt = row[Venues.roomsSyncAt]?.let { CloudTime.iso(it, v.zone) },
                canEdit = principal.canEditMenu,
                rooms = RoomState.rooms(RoomState.load(v.scope)),
            )
        }
        call.respond(response)
    }

    /** multipart: `image` (a phone photo, ≤ 12 MB; resized here), optional `name` and `lang`. */
    post("/room-ai/photo") {
        roomCaller(call, service, null)
        var bytes: ByteArray? = null
        var type: String? = null
        var name: String? = null
        var lang: String? = null
        call.receiveMultipart(formFieldLimit = RoomPhoto.MAX_BYTES + 1024L * 1024).forEachPart { part ->
            when {
                part is PartData.FileItem && part.name == "image" -> {
                    type = part.contentType?.toString()
                    bytes = part.provider().readCapped(RoomPhoto.MAX_BYTES, "that picture is too big", "room_ai_image_too_large")
                }
                part is PartData.FormItem && part.name == "name" -> name = part.value.take(200)
                part is PartData.FormItem && part.name == "lang" -> lang = part.value.take(8)
            }
            part.dispose()
        }
        val who = roomCaller(call, service, lang)
        val raw = bytes?.takeIf { it.isNotEmpty() } ?: throw BadRequestException("take a photo first", "room_ai_no_image")
        call.respond(withContext(Dispatchers.IO) { service.photo(who, RoomPhoto.prepare(raw, type), name) })
    }

    post("/room-ai/photo/apply") {
        val req = call.receive<RoomPhotoApplyRequest>()
        val who = roomCaller(call, service, null)
        call.respond(withContext(Dispatchers.IO) { service.applyPhoto(who, req) })
    }

    post("/room-ai/chat") {
        val req = call.receive<FloorChatRequest>()
        val who = roomCaller(call, service, req.lang)
        val room = roomParam(call)
        call.respond(withContext(Dispatchers.IO) { service.chat(who, room, req.text) })
    }

    /** multipart: `audio` (WAV / OGG / AAC / MP3 / FLAC, ≤ 5 MB) and an optional `lang`. */
    post("/room-ai/chat/voice") {
        roomCaller(call, service, null)
        val room = roomParam(call)
        var bytes: ByteArray? = null
        var type: String? = null
        var lang: String? = null
        call.receiveMultipart(formFieldLimit = 8L * 1024 * 1024).forEachPart { part ->
            when {
                part is PartData.FileItem && part.name == "audio" -> {
                    type = part.contentType?.toString()
                    bytes = part.provider().readCapped(AiVoice.MAX_BYTES, "that recording is too long", "menu_ai_audio_too_large")
                }
                part is PartData.FormItem && part.name == "lang" -> lang = part.value.take(8)
            }
            part.dispose()
        }
        val who = roomCaller(call, service, lang)
        val audio = bytes?.takeIf { it.isNotEmpty() } ?: throw BadRequestException("record something first", "menu_ai_no_audio")
        val mime = AiVoice.normalizeType(type)
            ?: throw MenuAiException(415, "menu_ai_audio_type", "send the clip as WAV, OGG, AAC, MP3 or FLAC")
        call.respond(withContext(Dispatchers.IO) { service.voice(who, room, AiAudio(audio, mime)) })
    }

    post("/room-ai/apply") {
        val req = call.receive<FloorApplyRequest>()
        val who = roomCaller(call, service, null)
        call.respond(withContext(Dispatchers.IO) { service.apply(who, req) })
    }

    post("/room-ai/revert/{applyId}") {
        val applyId = call.parameters["applyId"]!!.take(64)
        val who = roomCaller(call, service, null)
        call.respond(withContext(Dispatchers.IO) { service.revert(who, applyId) })
    }
}

private fun roomParam(call: ApplicationCall): String =
    call.request.queryParameters["room"]?.trim()?.takeIf { it.isNotEmpty() && it.length <= 160 }
        ?: throw BadRequestException("which room?", "room_required")

/** Signed in (401), an owner or manager (403), the assistant on (409), exactly one of the tenant's stores (400 / 404). */
private fun roomCaller(call: ApplicationCall, service: RoomAiService, lang: String?): AiCaller {
    val requested = call.request.queryParameters["venue"]?.takeIf { it.isNotBlank() }
    val (principal, venues) = portalScopes(call)
    requireMenuEditor(principal)
    if (!service.enabled) throw MenuAiException(409, "menu_ai_disabled", "the AI assistant is not set up on this portal")
    if (requested == null || venues.size != 1) throw BadRequestException("pick one store for the AI assistant", "venue_required")
    return AiCaller(principal, venues.single(), MenuAiService.lang(lang))
}

private suspend fun ByteReadChannel.readCapped(max: Int, message: String, code: String): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    while (!exhausted()) {
        val read = readAvailable(chunk, 0, chunk.size)
        if (read <= 0) continue
        if (out.size().toLong() + read > max) throw PayloadTooLargeException(message, code)
        out.write(chunk, 0, read)
    }
    return out.toByteArray()
}
