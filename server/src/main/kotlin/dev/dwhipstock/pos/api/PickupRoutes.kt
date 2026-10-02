package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.StoreAssets
import dev.dwhipstock.pos.orders.PickupOrders
import dev.dwhipstock.pos.restaurant.CarryOutCustomerRequest
import dev.dwhipstock.pos.restaurant.CarryOutService
import dev.dwhipstock.pos.restaurant.CarryOutSettingsUpdate
import dev.dwhipstock.pos.restaurant.NewCarryOutRequest
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
import kotlinx.serialization.json.Json

/**
 * The pickup board (any store with numbered orders and the board switched
 * on): open, like the customer menu, order numbers only. `GET /pickup` is the
 * page for a TV, `GET /pickup/board` its data.
 */
fun Route.pickupBoardRoutes(orders: PickupOrders) {
    val page by lazy { StoreAssets.readText("pickup.html") }
    get("/pickup") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(page, ContentType.Text.Html)
    }
    get("/pickup/board") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(orders.board())
    }
}

private val carryJson = Json { ignoreUnknownKeys = true }

/**
 * Carry-out at a table-service restaurant (staff, signed in). Payment, the
 * kitchen send, the bill and splits are the usual check routes on the
 * order's check id.
 *
 *  - `GET /carryout/summary`: open count, spots on the floor, header button;
 *  - `GET /carryout/orders` (open ones), `POST /carryout/orders` (a new one,
 *    optional customer name / phone), `GET /carryout/orders/{id}`;
 *  - `PUT /carryout/orders/{id}/customer`, `POST .../status` (READY,
 *    PICKED_UP once paid, PREPARING), `POST .../discard` (an empty one);
 *  - `GET /carryout/settings`, `PUT` (manager): the header button.
 */
fun Route.carryOutRoutes(carry: CarryOutService) {
    get("/carryout/summary") { call.respond(carry.summary()) }
    get("/carryout/orders") { call.respond(carry.list()) }
    post("/carryout/orders") {
        val text = call.receiveText()
        val req = if (text.isBlank()) NewCarryOutRequest() else carryJson.decodeFromString<NewCarryOutRequest>(text)
        call.respond(HttpStatusCode.Created, carry.create(call.sessionUser().userId, req))
    }
    get("/carryout/orders/{id}") { call.respond(carry.view(carryOutId(call))) }
    put("/carryout/orders/{id}/customer") {
        call.respond(carry.setCustomer(carryOutId(call), call.receive<CarryOutCustomerRequest>()))
    }
    post("/carryout/orders/{id}/status") {
        call.respond(carry.setStatus(carryOutId(call), call.receive<CounterOrderStatusRequest>().status))
    }
    post("/carryout/orders/{id}/discard") {
        carry.discard(carryOutId(call))
        call.respond(mapOf("discarded" to true))
    }
    get("/carryout/settings") { call.respond(carry.settings()) }
    put("/carryout/settings") {
        requireManagerSession(call)
        call.respond(carry.updateSettings(call.receive<CarryOutSettingsUpdate>()))
    }
}

private fun carryOutId(call: ApplicationCall): Int =
    call.parameters["id"]?.toIntOrNull() ?: throw IllegalArgumentException("invalid order id")
