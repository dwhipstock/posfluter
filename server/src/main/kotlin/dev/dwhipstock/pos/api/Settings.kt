package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.base.AuthUser
import dev.dwhipstock.pos.base.SessionSurface
import dev.dwhipstock.pos.base.Settings
import dev.dwhipstock.pos.base.SettingsPatch
import dev.dwhipstock.pos.base.SettingsRepository
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Owner-tunable venue settings. Manager-session-gated both ways — the client
 * only surfaces the screen to managers, and the server enforces it: this is
 * the venue's money configuration.
 */
fun Route.settingsRoutes(settings: SettingsRepository) {

    get("/settings") {
        requireManagerSession(call)
        call.respond(settingsResponse(settings.get(), call.sessionUser()))
    }

    patch("/settings") {
        requireManagerSession(call)
        val updated = settings.update(call.receive<SettingsPatch>())
        call.respond(settingsResponse(updated, call.sessionUser()))
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

/**
 * The guest Wi-Fi password is read back only by a manager on the POS terminal.
 * A manager signed in on the staff phone app gets the rest of the settings with
 * `wifiPassword: null` (redacted, distinct from "" = no password set).
 */
internal fun canReadWifiPassword(user: AuthUser): Boolean =
    user.role == "MANAGER" && user.surface == SessionSurface.POS

private fun settingsResponse(s: Settings, user: AuthUser): JsonObject {
    val json = Json.encodeToJsonElement(s).jsonObject
    return if (canReadWifiPassword(user)) json
    else JsonObject(json + ("wifiPassword" to JsonNull))
}

@Serializable
data class AlertConfig(
    val pendingAlertsEnabled: Boolean,
    val pendingAlertEscalateSeconds: Int,
    val pendingAlertVolume: Int,
)
