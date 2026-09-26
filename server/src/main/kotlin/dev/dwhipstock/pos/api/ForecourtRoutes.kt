package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.base.AuthService
import dev.dwhipstock.pos.forecourt.ForecourtService
import dev.dwhipstock.pos.forecourt.FuelTrxRequest
import dev.dwhipstock.pos.forecourt.PrepayRequest
import dev.dwhipstock.pos.restaurant.ConflictException
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * The forecourt on the counter (a gas station; off everywhere else: 409
 * `forecourt_off`). Session-gated like every POS route. The pump grid polls
 * GET /forecourt; the rest are the cashier's taps. Fuel goes onto the sale
 * through the retail sale routes; payment is the ordinary check flow.
 */
fun Route.forecourtRoutes(forecourt: ForecourtService?, @Suppress("UNUSED_PARAMETER") auth: AuthService) {
    fun fc(): ForecourtService = forecourt ?: throw ConflictException("this store has no forecourt", "forecourt_off")

    /** Every pump's live state, what's waiting to be paid, and whether the controller answers. */
    get("/forecourt") { call.respond(fc().view()) }

    /** Postpay: release the pump; the customer pays inside afterwards. */
    post("/forecourt/pumps/{n}/authorise") { fc().authorisePostpay(pump(call)); call.respond(fc().view()) }
    post("/forecourt/pumps/{n}/stop") { fc().stopPump(pump(call)); call.respond(fc().view()) }
    post("/forecourt/pumps/{n}/resume") { fc().resumePump(pump(call)); call.respond(fc().view()) }
    post("/forecourt/pumps/{n}/reset") { fc().resetPump(pump(call)); call.respond(fc().view()) }
    post("/forecourt/pumps/{n}/emergency-stop") { fc().emergencyStop(pump(call)); call.respond(fc().view()) }
    /** Every pump, at once. */
    post("/forecourt/emergency-stop") { fc().emergencyStop(null); call.respond(fc().view()) }

    /** A paid prepay not started yet: free the pump and refund it in full. */
    post("/forecourt/prepays/{id}/cancel") { call.respond(fc().cancelPrepay(id(call))) }
    /** The prepay's change was handed back. */
    post("/forecourt/prepays/{id}/change-given") { call.respond(fc().changeGiven(id(call))) }

    /** A completed postpay fuelling onto the sale. */
    post("/retail/sales/{id}/fuel") {
        val req = call.receive<FuelTrxRequest>()
        call.respond(fc().addPostpayToSale(id(call), req.trxId))
    }

    /** "$X on pump N": a prepay line; the pump starts when the sale is paid. */
    post("/retail/sales/{id}/prepay") {
        val req = call.receive<PrepayRequest>()
        call.respond(fc().addPrepayToSale(id(call), req.pump, req.amountCents))
    }
}

private fun pump(call: ApplicationCall): Int =
    call.parameters["n"]?.toIntOrNull() ?: throw IllegalArgumentException("pump must be a number")

private fun id(call: ApplicationCall): Int =
    call.parameters["id"]?.toIntOrNull() ?: throw IllegalArgumentException("id must be a number")
