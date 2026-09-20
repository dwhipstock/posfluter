package dev.dwhipstock.pos.api

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/**
 * Read-only cloud info for the client. The store already knows the cloud base
 * (CLOUD_SYNC_URL); the owner reporting portal is served at the ROOT of that same
 * host (Caddy routes /health and the /v1 API prefix to the API, everything else
 * to the Next.js portal), so the portal URL is just scheme://host of
 * CLOUD_SYNC_URL with any API path stripped.
 *
 * Auth-gated by the plugin (not in [isOpenRoute]) and deliberately minimal: it
 * returns ONLY the derived portal URL, never the sync API key or any other
 * secret. Stays per-venue (multi-tenant later) — nothing is hardcoded here.
 */
fun Route.cloudRoutes(portalUrl: String?) {
    get("/cloud/info") {
        call.respond(CloudInfo(portalUrl = portalUrl))
    }
}

@Serializable
data class CloudInfo(
    /** Owner reporting portal, or null when cloud sync isn't configured. */
    val portalUrl: String?,
)

/**
 * Derive the reporting-portal URL from a CLOUD_SYNC_URL: keep scheme + host
 * (+ explicit port), drop any path like /v1. Returns null for a blank or
 * unparseable value so the client hides the QR instead of rendering a broken
 * code.
 */
fun portalUrlFrom(syncUrl: String?): String? {
    if (syncUrl.isNullOrBlank()) return null
    return try {
        val uri = java.net.URI(syncUrl.trim())
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port != -1) ":${uri.port}" else ""
        "$scheme://$host$port"
    } catch (e: Exception) {
        null
    }
}
