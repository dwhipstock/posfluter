package dev.dwhipstock.pos.tools

import dev.dwhipstock.pos.detectLanIpv4
import dev.dwhipstock.pos.payments.simulator.SimulatedTerminalDevice
import dev.dwhipstock.pos.payments.simulator.simulatorRoutes
import dev.dwhipstock.pos.payments.terminal.TerminalException
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing

/**
 * The stand-alone card terminal simulator: a pretend countertop reader on
 * this machine (the Mac), reached by the POS over the store Wi-Fi by IP, the
 * way a real terminal is. Open http://localhost:8090/ full screen as the
 * customer-facing reader; the tablet store points `payment.terminal.host` at
 * this machine and pairs with the code on its screen.
 *
 * Env: TERMINAL_PORT (default 8090), TERMINAL_NAME. No internet, no card data.
 * Run: scripts/demo-terminal.sh (or `./gradlew runTerminal` in server/).
 */
fun main() {
    val port = System.getenv("TERMINAL_PORT")?.toIntOrNull() ?: SimulatedTerminalDevice.DEFAULT_PORT
    val device = SimulatedTerminalDevice(
        name = System.getenv("TERMINAL_NAME")?.takeIf { it.isNotBlank() } ?: "Counter terminal",
        requirePairing = true,
    )
    val lan = detectLanIpv4()
    println(
        """
        |
        |  Card terminal simulator  (${device.terminalId})
        |  ------------------------------------------------------------
        |  Reader screen (open full screen):  http://localhost:$port/
        |  On the store Wi-Fi this terminal is: ${lan?.let { "$it:$port" } ?: "(no LAN address found)"}
        |  Pairing code (also on the reader screen): ${device.pairingCode}
        |
        |  Tablet store.properties:
        |      payment.terminal=simulator
        |      payment.terminal.host=${lan ?: "<this Mac's IP>"}:$port
        |  then on the POS: Settings → Card terminal → Pair, enter the code.
        |
        """.trimMargin(),
    )
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        install(ContentNegotiation) { json() }
        install(StatusPages) {
            exception<TerminalException> { call, e ->
                call.respond(HttpStatusCode.fromValue(e.status), mapOf("error" to (e.message ?: "terminal error"), "code" to e.code))
            }
            exception<IllegalArgumentException> { call, e ->
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "bad request"), "code" to "bad_request"))
            }
        }
        routing { simulatorRoutes(device, "", api = true) }
    }.start(wait = true)
}
