package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.base.AuthService
import dev.dwhipstock.pos.base.Permissions
import dev.dwhipstock.pos.forecourt.ForecourtService
import dev.dwhipstock.pos.forecourt.FuelTrxRequest
import dev.dwhipstock.pos.forecourt.PrepayRequest
import dev.dwhipstock.pos.base.ConflictException
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The forecourt on the counter (a gas station; off everywhere else: 409
 * `forecourt_off`). The pump grid polls GET /forecourt; the rest are the
 * cashier's taps. Fuel goes onto the sale through the retail sale routes;
 * payment is the ordinary check flow.
 *
 * Red-team: every pump command needs the POS terminal's own session
 * ([requireTerminalSession]) — a staff-app / kitchen-screen bearer, i.e. a
 * PIN-only phone on the Wi-Fi, can't release, stop or refund a pump (403
 * `pos_terminal_required`). Cancelling a paid prepay is a refund, so it also
 * needs the `refund` grant or a manager's PIN (`{"managerPin":"…"}`). Emergency
 * stop and the routine postpay release deliberately need no PIN: a cashier
 * must never wait for a manager to stop a pump, and every postpay customer is
 * released this way.
 */
fun Route.forecourtRoutes(forecourt: ForecourtService?, auth: AuthService) {
    fun fc(): ForecourtService = forecourt ?: throw ConflictException("this store has no forecourt", "forecourt_off")

    /** A pump command: the POS terminal only. */
    fun Route.command(path: String, body: suspend RoutingContext.() -> Unit) = post(path) {
        requireTerminalSession(call)
        body()
    }

    /** Every pump's live state, what's waiting to be paid, and whether the controller answers. */
    get("/forecourt") { call.respond(fc().view()) }

    /** Postpay: release the pump; the customer pays inside afterwards. */
    command("/forecourt/pumps/{n}/authorise") { fc().authorisePostpay(pump(call)); call.respond(fc().view()) }
    command("/forecourt/pumps/{n}/stop") { fc().stopPump(pump(call)); call.respond(fc().view()) }
    command("/forecourt/pumps/{n}/resume") { fc().resumePump(pump(call)); call.respond(fc().view()) }
    command("/forecourt/pumps/{n}/reset") { fc().resetPump(pump(call)); call.respond(fc().view()) }
    command("/forecourt/pumps/{n}/emergency-stop") { fc().emergencyStop(pump(call)); call.respond(fc().view()) }
    /** Every pump, at once. */
    command("/forecourt/emergency-stop") { fc().emergencyStop(null); call.respond(fc().view()) }

    /** A paid prepay not started yet: free the pump and refund it in full (a refund: the grant or a manager PIN). */
    command("/forecourt/prepays/{id}/cancel") {
        val pin = optionalJson.decodeFromString<ForecourtApproval>(call.receiveText().ifBlank { "{}" }).managerPin
        requireGrant(auth, call, Permissions.REFUND, pin?.takeIf { it.isNotBlank() })
        call.respond(fc().cancelPrepay(id(call)))
    }
    /** The prepay's change was handed back. */
    command("/forecourt/prepays/{id}/change-given") { call.respond(fc().changeGiven(id(call))) }

    /** A completed postpay fuelling onto the sale. */
    command("/retail/sales/{id}/fuel") {
        val req = call.receive<FuelTrxRequest>()
        call.respond(fc().addPostpayToSale(id(call), req.trxId))
    }

    /** "$X on pump N": a prepay line; the pump starts when the sale is paid. */
    command("/retail/sales/{id}/prepay") {
        val req = call.receive<PrepayRequest>()
        call.respond(fc().addPrepayToSale(id(call), req.pump, req.amountCents))
    }
}

private fun pump(call: ApplicationCall): Int =
    call.parameters["n"]?.toIntOrNull() ?: throw IllegalArgumentException("pump must be a number")

private fun id(call: ApplicationCall): Int =
    call.parameters["id"]?.toIntOrNull() ?: throw IllegalArgumentException("id must be a number")

@Serializable
private data class ForecourtApproval(val managerPin: String? = null)

private val optionalJson = Json { ignoreUnknownKeys = true }
