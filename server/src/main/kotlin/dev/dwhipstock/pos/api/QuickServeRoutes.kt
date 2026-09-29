package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.StoreAssets
import dev.dwhipstock.pos.restaurant.KioskOrderRequest
import dev.dwhipstock.pos.restaurant.QuickServeService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class NewCounterOrderRequest(val serviceMode: String = "TAKE_OUT")

@Serializable
data class CounterOrderStatusRequest(val status: String)

@Serializable
data class KioskPairRequest(val code: String, val deviceName: String = "")

@Serializable
data class KioskConfigResponse(
    val storeName: String,
    val venueId: String,
    val deviceName: String,
    val currency: String,
    val locales: List<String>,
)

private val qsJson = Json { ignoreUnknownKeys = true }

/**
 * The quick-serve counter (mounted only on a quick-serve store):
 *
 *  - staff (signed in): the order list, a new order, place / status, and a
 *    kiosk pairing code (manager);
 *  - the pickup board (open, like the customer menu: order numbers only):
 *    `GET /pickup` (the page for a TV) and `GET /pickup/board`;
 *  - the self-order kiosks (open routes, each checked here): `POST /kiosk/pair`
 *    with the code, then `X-Device-Token` on `GET /kiosk/config` and
 *    `POST /kiosk/orders`.
 */
fun Route.quickServeRoutes(qs: QuickServeService, storeName: String, venueId: String, currency: String, locales: List<String>) {
    val page by lazy { StoreAssets.readText("pickup.html") }

    get("/counter/orders") { call.respond(qs.list()) }
    post("/counter/orders") {
        val req = qsJson.decodeFromString<NewCounterOrderRequest>(call.receiveText().ifBlank { "{}" })
        call.respond(qs.createAtPos(req.serviceMode, call.sessionUser().userId))
    }
    post("/counter/orders/{id}/place") {
        call.respond(qs.place(orderId(call), call.sessionUser().name))
    }
    post("/counter/orders/{id}/status") {
        call.respond(qs.setStatus(orderId(call), call.receive<CounterOrderStatusRequest>().status))
    }
    post("/counter/kiosk-code") {
        requireManagerSession(call)
        call.respond(qs.newPairingCode())
    }

    get("/pickup") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(page, ContentType.Text.Html)
    }
    get("/pickup/board") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(qs.board())
    }

    post("/kiosk/pair") {
        val req = call.receive<KioskPairRequest>()
        call.respond(qs.pairKiosk(req.code, req.deviceName))
    }
    get("/kiosk/config") {
        val device = kioskDevice(call, qs) ?: return@get
        call.respond(KioskConfigResponse(storeName, venueId, device.name, currency, locales))
    }
    post("/kiosk/orders") {
        val device = kioskDevice(call, qs) ?: return@post
        call.respond(qs.placeKioskOrder(call.receive<KioskOrderRequest>(), device.name))
    }
}

/** The paired kiosk making this call, or null after answering 401 `kiosk_not_paired`. */
private suspend fun kioskDevice(call: ApplicationCall, qs: QuickServeService) =
    call.attributes.getOrNull(PairedDeviceKey)?.takeIf { !it.revoked && qs.isKiosk(it.id) }
        ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "this kiosk is not paired", "code" to "kiosk_not_paired"))
            null
        }

private fun orderId(call: ApplicationCall): Int =
    call.parameters["id"]?.toIntOrNull() ?: throw IllegalArgumentException("invalid order id")
