package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.payments.TerminalPaymentService
import dev.dwhipstock.pos.payments.terminal.TipMode
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class TerminalPaymentRequest(
    val amountCents: Long? = null,
    val groupId: Int? = null,
    /** none | on_reader */
    val tipMode: String? = null,
)

@Serializable
data class TerminalPairRequest(val host: String? = null, val code: String? = null)

/**
 * The store's card terminal, whatever it is (payment.terminal). Behind the
 * normal session gate; none of it is on the path of startup, login or another
 * tender. With no terminal or no LAN the answers are coded errors and nothing
 * changes.
 *
 *   GET  /payments/terminal                  kind, available?, reader state (every store)
 *   POST /payments/terminal/pair             {host, code} pair a LAN terminal (simulator)
 *   POST /payments/terminal/unpair           back to the built-in simulator
 *   POST /checks/{id}/terminal/payments      start a card payment for the amount due
 *   GET  /terminal/payments/{paymentId}      poll; records the tender on approval
 *   POST /terminal/payments/{paymentId}/cancel
 * Refunds use the ordinary POST /checks/{id}/refund with tenderType=TERMINAL.
 * Stripe stores keep their /stripe/... routes (the tablet SDK flow).
 */
fun Route.terminalRoutes(terminals: TerminalPaymentService) {
    get("/payments/terminal") { call.respond(onIo { terminals.status() }) }
    post("/payments/terminal/pair") {
        // pairing changes where card payments go: a manager's job (the settings screen is manager-only)
        requireManagerSession(call)
        val req = runCatching { call.receive<TerminalPairRequest>() }.getOrDefault(TerminalPairRequest())
        call.respond(onIo { terminals.pair(req.host, req.code) })
    }
    post("/payments/terminal/unpair") {
        requireManagerSession(call)
        call.respond(onIo { terminals.unpair() })
    }
    post("/checks/{id}/terminal/payments") {
        val id = call.parameters["id"]?.toIntOrNull() ?: throw IllegalArgumentException("bad check id")
        val req = runCatching { call.receive<TerminalPaymentRequest>() }.getOrDefault(TerminalPaymentRequest())
        call.respond(HttpStatusCode.Created, onIo { terminals.start(id, req.groupId, req.amountCents, TipMode.parse(req.tipMode)) })
    }
    get("/terminal/payments/{pid}") { call.respond(onIo { terminals.refresh(call.parameters["pid"]!!) }) }
    post("/terminal/payments/{pid}/cancel") { call.respond(onIo { terminals.cancel(call.parameters["pid"]!!) }) }
}
