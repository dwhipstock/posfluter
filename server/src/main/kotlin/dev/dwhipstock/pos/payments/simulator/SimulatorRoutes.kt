package dev.dwhipstock.pos.payments.simulator

import dev.dwhipstock.pos.StoreAssets
import dev.dwhipstock.pos.payments.terminal.EntryMode
import dev.dwhipstock.pos.payments.terminal.TerminalException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable data class SimPairRequest(val code: String)
@Serializable data class SimPairResponse(val token: String, val terminalId: String, val name: String)
@Serializable data class SimStartRequest(
    val reference: String,
    val amountCents: Long,
    val currency: String,
    val tipOnReader: Boolean = false,
    val timeoutSeconds: Int = 90,
    val label: String? = null,
    /** A real processor decides: the reader waits for POST .../host after the card. */
    val hostAuthorization: Boolean = false,
)
@Serializable data class SimRefundRequest(val amountCents: Long? = null, val key: String)
@Serializable data class SimPresentRequest(val entry: String, val card: String? = null, val outcome: String? = null)
@Serializable data class SimPinRequest(val pin: String)
@Serializable data class SimTipRequest(val tipCents: Long)

/**
 * The simulator's two faces.
 *
 * Reader (customer side), always mounted at [base]:
 *   GET  {base}                 the full-screen reader page (terminal-sim.html)
 *   GET  {base}/ui/state        what the screen shows (polled)
 *   POST {base}/ui/present      tap / insert / swipe a test card with an outcome
 *   POST {base}/ui/pin          PIN after an insert
 *   POST {base}/ui/tip          tip choice (tip-on-reader payments)
 *   POST {base}/ui/cancel       the red key
 *
 * POS API, mounted only when [api] (the stand-alone terminal on the LAN; the
 * store's built-in simulator is called in-process). Bearer token from pairing:
 *   GET  {base}/api/status                        (no token needed)
 *   POST {base}/api/pair            {code}        → token
 *   POST {base}/api/transactions    {reference, amountCents, currency, tipOnReader, timeoutSeconds}
 *   GET  {base}/api/transactions/{id}
 *   POST {base}/api/transactions/{id}/cancel
 *   POST {base}/api/transactions/{id}/capture
 *   POST {base}/api/transactions/{id}/refunds  {amountCents?, key}
 *   POST {base}/api/transactions/{id}/host     {approved, authCode?, …} (host-authorized payments)
 *
 * Errors answer `{error, code}` with the [TerminalException] status.
 */
fun Route.simulatorRoutes(device: SimulatedTerminalDevice, base: String, api: Boolean) {
    val page = StoreAssets.readText("terminal-sim.html").replace("__BASE__", base)
    get(base.ifEmpty { "/" }) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(page, ContentType.Text.Html)
    }
    get("$base/ui/state") { call.guard { device.screen() } }
    post("$base/ui/present") {
        val req = call.receive<SimPresentRequest>()
        val entry = when (req.entry.trim().lowercase()) {
            "tap" -> EntryMode.TAP
            "insert", "chip" -> EntryMode.INSERT
            "swipe" -> EntryMode.SWIPE
            else -> throw TerminalException(400, "terminal_bad_entry", "entry must be tap, insert or swipe")
        }
        call.guard {
            device.present(entry, SimulatedTerminalDevice.TestCard.parse(req.card), SimulatedTerminalDevice.Scenario.parse(req.outcome))
        }
    }
    post("$base/ui/pin") { val req = call.receive<SimPinRequest>(); call.guard { device.enterPin(req.pin) } }
    post("$base/ui/tip") { val req = call.receive<SimTipRequest>(); call.guard { device.chooseTip(req.tipCents) } }
    post("$base/ui/cancel") { call.guard { device.customerCancel() } }

    if (!api) return
    get("$base/api/status") { call.guard { device.status() } }
    post("$base/api/pair") {
        val req = call.receive<SimPairRequest>()
        call.guard { SimPairResponse(device.pair(req.code), device.terminalId, device.name) }
    }
    post("$base/api/transactions") {
        call.requireToken(device)
        val req = call.receive<SimStartRequest>()
        call.guard(HttpStatusCode.Created) {
            device.start(req.reference, req.amountCents, req.currency, req.tipOnReader,
                req.timeoutSeconds.coerceIn(15, 600), req.label, req.hostAuthorization)
        }
    }
    post("$base/api/transactions/{id}/host") {
        call.requireToken(device)
        val req = call.receive<SimHostRequest>()
        call.guard { device.hostResponse(call.parameters["id"]!!, req.toDecision()) }
    }
    get("$base/api/transactions/{id}") { call.requireToken(device); call.guard { device.get(call.parameters["id"]!!) } }
    post("$base/api/transactions/{id}/cancel") { call.requireToken(device); call.guard { device.cancel(call.parameters["id"]!!) } }
    post("$base/api/transactions/{id}/capture") { call.requireToken(device); call.guard { device.capture(call.parameters["id"]!!) } }
    post("$base/api/transactions/{id}/refunds") {
        call.requireToken(device)
        val req = call.receive<SimRefundRequest>()
        call.guard(HttpStatusCode.Created) { device.refund(call.parameters["id"]!!, req.amountCents, req.key) }
    }
}

fun SimHostRequest.toDecision() = SimulatedTerminalDevice.HostDecision(approved, authCode, declineCode, message, processorRef, processor)

private fun ApplicationCall.requireToken(device: SimulatedTerminalDevice) {
    val token = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
    if (!device.authorized(token))
        throw TerminalException(401, TerminalException.NOT_PAIRED, "pair with this terminal first (code on its screen)")
}

private suspend inline fun <reified T : Any> ApplicationCall.guard(ok: HttpStatusCode = HttpStatusCode.OK, block: () -> T) {
    val result = try {
        block()
    } catch (e: TerminalException) {
        respond(HttpStatusCode.fromValue(e.status), mapOf("error" to (e.message ?: "terminal error"), "code" to e.code))
        return
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "bad request"), "code" to "bad_request"))
        return
    }
    respond(ok, result)
}
