package dev.dwhipstock.pos.base

import dev.dwhipstock.pos.sdk.VenueClock

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.StatementInterceptor
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Paired terminal devices (M8). The device token is 32 random bytes
 * (base64url); only its SHA-256 is stored, so the database alone can never
 * impersonate a terminal. Revocation keeps the row (audit + heartbeat
 * reporting) but fails validation — and kills the sessions minted on it.
 */
object DeviceRegistry {

    data class PairedDevice(val id: String, val name: String, val revoked: Boolean)

    @Serializable
    data class DeviceSummary(
        val id: String, val name: String,
        val pairedAt: String, val lastSeenAt: String?, val revoked: Boolean,
    )

    private val random = SecureRandom()
    // last-seen writes throttled in memory: one UPDATE per device per minute, not
    // per request. The map holds the last WRITE time, not the last request time.
    private val lastTouch = ConcurrentHashMap<String, Instant>()
    private const val TOUCH_SECONDS = 60L
    // token-hash → device, populated on a successful lookup and cleared on any
    // revocation (revocations are in-process, so latency stays zero). The auth gate
    // calls byToken() on every request that carries a device token — including open
    // routes and photo bursts — so this keeps that off the DB. Only HITS are cached,
    // so a flood of bogus tokens can't grow the map.
    private val deviceCache = ConcurrentHashMap<String, PairedDevice>()

    /** Lowercase-hex SHA-256. NOTE: kept byte-identical to the cloud's
     *  `poscloud.auth.sha256Hex` — the two Gradle modules share no library, but
     *  both feed *_sha256 equality columns and MUST agree. Change them together. */
    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Mint a device row + token. Returns (deviceId, plaintext token — shown once). */
    fun pair(name: String): Pair<String, String> = transaction {
        val id = UUID.randomUUID().toString()
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        Devices.insert {
            it[Devices.id] = id
            it[Devices.name] = name.trim().ifBlank { "Terminal" }.take(100)
            it[tokenSha256] = sha256Hex(token)
            it[pairedAt] = VenueClock.now()
        }
        id to token
    }

    /** The device holding [token], or null. Revoked devices ARE returned (flagged)
     *  so callers can answer 401 device_revoked instead of a generic miss. Hits are
     *  cached in memory; a revocation clears the cache so the flag can never go stale. */
    fun byToken(token: String): PairedDevice? {
        val hash = sha256Hex(token)
        deviceCache[hash]?.let { return it }
        return transaction {
            Devices.selectAll().where { Devices.tokenSha256 eq hash }.firstOrNull()?.let {
                PairedDevice(it[Devices.id], it[Devices.name], it[Devices.revokedAt] != null)
            }
        }?.also { deviceCache[hash] = it }
    }

    /** Throttled last-seen bump for an authenticated device: at most one DB write
     *  per device per minute. The map records the last WRITE, so steady traffic
     *  keeps last_seen_at fresh (comparing against the last request time instead
     *  would slide the window forever and only ever write after a >60s idle gap). */
    fun touch(deviceId: String) {
        val now = Instant.now()
        val previous = lastTouch[deviceId]
        if (previous != null && previous.isAfter(now.minusSeconds(TOUCH_SECONDS))) return
        lastTouch[deviceId] = now
        transaction {
            Devices.update({ Devices.id eq deviceId }) { it[lastSeenAt] = VenueClock.now() }
        }
    }

    /**
     * Apply a cloud device_revocation (changes feed, kind "device_revocation"):
     * flag the device AND revoke every session minted on it, so the terminal is
     * cut off on its next request — not at its next login.
     */
    fun applyRevocation(deviceId: String?) {
        if (deviceId == null) return
        transaction { // joins CloudSync's pull transaction
            val now = VenueClock.now()
            Devices.update({ (Devices.id eq deviceId) and Devices.revokedAt.isNull() }) { it[revokedAt] = now }
            Sessions.update({ (Sessions.deviceId eq deviceId) and Sessions.revokedAt.isNull() }) { it[revokedAt] = now }
            // Invalidate the cache only once the enclosing pull transaction COMMITS. Clearing
            // eagerly lets a concurrent read (still seeing the pre-commit live row) re-cache the
            // device as live after our clear, and would wrongly drop cache state if the pull rolls
            // back. afterCommit fires on the real commit; rollback skips it.
            registerInterceptor(object : StatementInterceptor {
                override fun afterCommit(transaction: Transaction) {
                    deviceCache.clear() // the flagged device's cached entry must not linger
                }
            })
        }
    }

    /** Registry summary for the sync heartbeat — the portal's device list. */
    fun summaries(): List<DeviceSummary> = transaction {
        Devices.selectAll().orderBy(Devices.pairedAt).map {
            DeviceSummary(
                id = it[Devices.id],
                name = it[Devices.name],
                pairedAt = VenueClock.iso(it[Devices.pairedAt]),
                lastSeenAt = it[Devices.lastSeenAt]?.let(VenueClock::iso),
                revoked = it[Devices.revokedAt] != null,
            )
        }
    }
}
