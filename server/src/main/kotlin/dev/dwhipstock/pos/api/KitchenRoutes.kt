package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.StoreAssets
import dev.dwhipstock.pos.restaurant.ConflictException
import dev.dwhipstock.pos.restaurant.KitchenQueueStatus
import dev.dwhipstock.pos.restaurant.KitchenService
import dev.dwhipstock.pos.restaurant.KitchenSettingsDto
import dev.dwhipstock.pos.restaurant.KitchenStationDto
import dev.dwhipstock.pos.restaurant.NotFoundException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class KitchenGuestsRequest(val guests: Int? = null)

@Serializable
data class KitchenRouteRequest(val kind: String, val refId: String, val stationId: String? = null)

@Serializable
data class KitchenCancelRequest(val jobId: String? = null)

@Serializable
data class KitchenBumpRequest(val checkId: Int, val stationId: String)

@Serializable
data class KitchenRecallRequest(val bumpId: String? = null, val stationId: String? = null)

private val kitchenJson = Json { ignoreUnknownKeys = true }

/**
 * Kitchen tickets and the kitchen screen (kitchen.printing=on). With it off,
 * [kitchen] is null: /kitchen/status says `enabled: false`, the page is 404
 * and every other route answers 409 `kitchen_off`. All of these are behind
 * the staff sign-in except the page shell itself (it signs in inside, like
 * /staff-app); station setup is manager-only.
 */
fun Route.kitchenRoutes(kitchen: KitchenService?) {
    fun k(): KitchenService = kitchen ?: throw ConflictException("kitchen tickets are off", "kitchen_off")

    // the page is read once: it is baked into the jar / APK assets
    val page by lazy { StoreAssets.readText("kitchen.html") }

    /** The LAN kitchen screen (any browser on the venue Wi-Fi). Open shell; signs in inside. */
    get("/kitchen") {
        if (kitchen == null) throw NotFoundException("kitchen screen is off")
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(page, ContentType.Text.Html)
    }

    get("/kitchen/status") {
        call.respond(kitchen?.queueStatus() ?: KitchenQueueStatus(enabled = false))
    }

    // --- the check screen: send, reprint, guests, what's unsent
    get("/checks/{id}/kitchen") {
        call.respond(k().state(kitchenCheckId(call)))
    }
    post("/checks/{id}/kitchen/send") {
        call.respond(k().send(kitchenCheckId(call), call.sessionUser().name))
    }
    post("/checks/{id}/kitchen/reprint") {
        call.respond(k().reprint(kitchenCheckId(call), call.sessionUser().name))
    }
    post("/checks/{id}/kitchen/guests") {
        val req = kitchenJson.decodeFromString<KitchenGuestsRequest>(call.receiveText().ifBlank { "{}" })
        call.respond(k().setGuests(kitchenCheckId(call), req.guests))
    }

    // --- the print queue: the tablet's "printer offline" banner
    post("/kitchen/queue/retry") {
        call.respond(mapOf("retried" to k().queue.retryNow()))
    }
    post("/kitchen/queue/cancel") {
        val req = kitchenJson.decodeFromString<KitchenCancelRequest>(call.receiveText().ifBlank { "{}" })
        call.respond(mapOf("cancelled" to k().queue.cancel(req.jobId)))
    }

    // --- station setup (manager)
    get("/kitchen/config") {
        requireManagerSession(call)
        call.respond(k().configView())
    }
    put("/kitchen/settings") {
        requireManagerSession(call)
        call.respond(k().updateSettings(call.receive<KitchenSettingsDto>()))
    }
    post("/kitchen/stations") {
        requireManagerSession(call)
        call.respond(k().saveStation(call.receive<KitchenStationDto>()))
    }
    delete("/kitchen/stations/{stationId}") {
        requireManagerSession(call)
        k().deleteStation(call.parameters["stationId"]!!)
        call.respond(k().configView())
    }
    put("/kitchen/routes") {
        requireManagerSession(call)
        val req = call.receive<KitchenRouteRequest>()
        k().setRoute(req.kind, req.refId, req.stationId)
        call.respond(k().configView())
    }
    post("/kitchen/stations/{stationId}/test") {
        requireManagerSession(call)
        call.respond(k().testPrint(call.parameters["stationId"]!!))
    }

    // --- the kitchen screen (POS Kitchen view and the /kitchen page)
    get("/kitchen/board") {
        val station = call.request.queryParameters["station"]?.takeIf { it.isNotBlank() }
        call.respond(k().board(station))
    }
    post("/kitchen/board/bump") {
        val req = call.receive<KitchenBumpRequest>()
        call.respond(mapOf("bumpId" to k().bump(req.checkId, req.stationId)))
    }
    post("/kitchen/board/recall") {
        val req = kitchenJson.decodeFromString<KitchenRecallRequest>(call.receiveText().ifBlank { "{}" })
        call.respond(mapOf("recalled" to k().recall(req.bumpId, req.stationId)))
    }
}

private fun kitchenCheckId(call: io.ktor.server.application.ApplicationCall): Int =
    call.parameters["id"]?.toIntOrNull() ?: throw IllegalArgumentException("invalid check id")
