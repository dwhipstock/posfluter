package dev.dwhipstock.pos

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.application.Application

/**
 * Desktop / container entry point. Everything is env-driven (see Application.module):
 * POS_VENUE picks the store (vieux-port | plateau), POS_DB its own database,
 * CLOUD_SYNC_URL + CLOUD_SYNC_API_KEY its (optional) cloud. POS_PORT, default 8080.
 */
fun main() {
    val port = System.getenv("POS_PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
}
