package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.base.DeviceRegistry
import dev.dwhipstock.pos.base.LoginRateLimiter
import dev.dwhipstock.pos.sync.CloudTransport
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Terminal pairing (M8): the terminal presents a portal-minted code; this store
 * redeems it against the cloud under its own venue-bound API key (the code can
 * only ever belong to THIS venue) and mints a local device token. The token is
 * returned exactly once — thereafter only its hash exists server-side.
 *
 * /pair is open to the whole internet on a cloud venue (an unpaired terminal has
 * nothing else), so the rate limiter is keyed PER CLIENT IP: an attacker flooding
 * bad codes locks only their own address, never the owner's tablet. Codes are
 * single-use, 15-minute, 8 chars from a 30-char alphabet (~6e11 space), so brute
 * force is hopeless regardless — the limiter is abuse protection, not the wall.
 */

@Serializable
data class PairRequest(val code: String, val deviceName: String = "")

@Serializable
data class PairResponse(val deviceId: String, val deviceToken: String, val deviceName: String)

class PairingService(private val transport: CloudTransport) {
    private val log = LoggerFactory.getLogger(PairingService::class.java)
    // one limiter per client IP; bounded so a rotating-IP flood can't grow it forever
    private val limiters = ConcurrentHashMap<String, LoginRateLimiter>()
    private companion object { const val MAX_LIMITERS = 10_000 }

    private fun limiterFor(clientIp: String): LoginRateLimiter {
        if (limiters.size > MAX_LIMITERS) limiters.clear() // crude but safe reset under flood
        return limiters.computeIfAbsent(clientIp) { LoginRateLimiter() }
    }

    /** Claim [code] with the cloud, then mint the device. Throws PairingRefusedException. */
    fun pair(code: String, deviceName: String, clientIp: String): PairResponse {
        val rateLimiter = limiterFor(clientIp)
        rateLimiter.checkNotLocked()
        val result = runCatching { transport.claimPairing(code.trim()) }
            .getOrElse { e ->
                log.warn("pairing claim transport error: ${e.message}")
                throw PairingUnavailableException()
            }
        if (!result.ok) {
            // transport-level failures (cloud down) must not count toward lockout —
            // only an actual refusal does, matched on the cloud's stable machine code.
            // (A retried already-used code also refuses; that's an accepted edge — a
            // few honest retries from the owner's IP won't lock it out at 5/window.)
            if (result.detail == "bad_pairing_code") {
                rateLimiter.recordFailure()
                throw PairingRefusedException()
            }
            log.warn("pairing claim failed: ${result.detail}")
            throw PairingUnavailableException()
        }
        rateLimiter.recordSuccess()
        val (deviceId, token) = DeviceRegistry.pair(deviceName)
        log.info("device paired: $deviceId ('${deviceName.ifBlank { "Terminal" }}')")
        return PairResponse(deviceId, token, deviceName.trim().ifBlank { "Terminal" })
    }
}

class PairingRefusedException : RuntimeException("unknown or expired pairing code")
class PairingUnavailableException : RuntimeException("cannot reach the cloud to verify the pairing code")

fun Route.pairingRoutes(pairing: PairingService?) {
    post("/pair") {
        if (pairing == null) {
            return@post call.respond(HttpStatusCode.ServiceUnavailable,
                mapOf("error" to "this store has no cloud connection; pairing is unavailable",
                    "code" to "pairing_unavailable"))
        }
        val req = call.receive<PairRequest>()
        // Client IP keys the rate limiter per device instead of store-wide. Behind
        // Caddy the peer is the proxy, so prefer the client it records in
        // X-Forwarded-For (first hop); fall back to the direct peer on the LAN.
        val clientIp = call.request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: call.request.origin.remoteHost
        val response = try {
            pairing.pair(req.code, req.deviceName, clientIp)
        } catch (e: PairingRefusedException) {
            return@post call.respond(HttpStatusCode.NotFound,
                mapOf("error" to e.message.orEmpty(), "code" to "bad_pairing_code"))
        } catch (e: PairingUnavailableException) {
            return@post call.respond(HttpStatusCode.ServiceUnavailable,
                mapOf("error" to e.message.orEmpty(), "code" to "cloud_unreachable"))
        }
        call.respond(response)
    }
}
