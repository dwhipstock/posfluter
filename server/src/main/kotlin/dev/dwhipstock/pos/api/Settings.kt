package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.base.SettingsPatch
import dev.dwhipstock.pos.base.SettingsRepository
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/**
 * Owner-tunable venue settings. Manager-session-gated both ways — the client
 * only surfaces the screen to managers, and the server enforces it: this is
 * the venue's money configuration.
 */
fun Route.settingsRoutes(settings: SettingsRepository) {

    get("/settings") {
        requireManagerSession(call)
        call.respond(settings.get())
    }

    patch("/settings") {
        requireManagerSession(call)
        call.respond(settings.update(call.receive<SettingsPatch>()))
    }

    // Any authenticated staff — the pending-order alert knobs drive every
    // terminal's chime/escalation, not the venue's money config, so this subset
    // is readable without a manager session (unlike the full /settings above).
    get("/alert-config") {
        val s = settings.get()
        call.respond(AlertConfig(
            s.pendingAlertsEnabled, s.pendingAlertEscalateSeconds, s.pendingAlertVolume))
    }
}

@Serializable
data class AlertConfig(
    val pendingAlertsEnabled: Boolean,
    val pendingAlertEscalateSeconds: Int,
    val pendingAlertVolume: Int,
)
