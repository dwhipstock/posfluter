package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.StoreAssets
import dev.dwhipstock.pos.restaurant.CounterSettings
import dev.dwhipstock.pos.restaurant.KioskOrderLine
import dev.dwhipstock.pos.restaurant.KioskOrderRequest
import dev.dwhipstock.pos.restaurant.KioskUpsellRequest
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
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A new order is stored with its first item (an empty order never is). */
@Serializable
data class NewCounterOrderRequest(
    val serviceMode: String = "TAKE_OUT",
    val itemId: String,
    val variantId: String,
    val qty: Int = 1,
    val note: String? = null,
)

@Serializable
data class CounterModeRequest(val serviceMode: String)

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
 *  - staff (signed in): a new order (with its first item), dine in / take
 *    out, discard, the kiosk orders waiting to pay, the Orders panel (today's
 *    paid orders) and their status, the counter settings, and a kiosk pairing
 *    code (manager). Payment is the usual check tender / finalize: the order
 *    is committed (numbered, sent to the kitchen) when its check closes;
 *  - the pickup board (open, like the customer menu: order numbers only):
 *    `GET /pickup` (the page for a TV) and `GET /pickup/board`;
 *  - the self-order kiosks (open routes, each checked here): `POST /kiosk/pair`
 *    with the code, then `X-Device-Token` on `GET /kiosk/config`,
 *    `POST /kiosk/upsell` (the "Add a drink?" rows for a cart) and
 *    `POST /kiosk/orders`.
 */
fun Route.quickServeRoutes(qs: QuickServeService, storeName: String, venueId: String, currency: String, locales: List<String>) {
    val page by lazy { StoreAssets.readText("pickup.html") }

    get("/counter/orders") { call.respond(qs.list()) }
    get("/counter/waiting") { call.respond(qs.waiting()) }
    post("/counter/orders") {
        val req = qsJson.decodeFromString<NewCounterOrderRequest>(call.receiveText())
        call.respond(qs.createAtPos(req.serviceMode, KioskOrderLine(req.itemId, req.variantId, req.qty, req.note),
            call.sessionUser().userId))
    }
    get("/counter/orders/{id}") { call.respond(qs.view(orderId(call))) }
    post("/counter/orders/{id}/mode") {
        call.respond(qs.setMode(orderId(call), call.receive<CounterModeRequest>().serviceMode))
    }
    post("/counter/orders/{id}/discard") {
        qs.discard(orderId(call))
        call.respond(mapOf("discarded" to true))
    }
    get("/counter/settings") { call.respond(qs.settings()) }
    put("/counter/settings") {
        requireManagerSession(call)
        call.respond(qs.updateSettings(call.receive<CounterSettings>()))
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
    post("/kiosk/upsell") {
        kioskDevice(call, qs) ?: return@post
        call.respond(qs.kioskUpsell(qsJson.decodeFromString<KioskUpsellRequest>(call.receiveText())))
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
