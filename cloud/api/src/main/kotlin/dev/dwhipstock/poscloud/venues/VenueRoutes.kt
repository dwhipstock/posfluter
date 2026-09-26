package dev.dwhipstock.poscloud.venues

import dev.dwhipstock.poscloud.BadRequestException
import dev.dwhipstock.poscloud.CloudConfig
import dev.dwhipstock.poscloud.NotFoundException
import dev.dwhipstock.poscloud.auth.requirePortal
import dev.dwhipstock.poscloud.auth.sha256Hex
import dev.dwhipstock.poscloud.catalog.Catalog
import dev.dwhipstock.poscloud.catalog.Scope
import dev.dwhipstock.poscloud.db.Devices
import dev.dwhipstock.poscloud.db.PairingCodes
import dev.dwhipstock.poscloud.db.Venues
import dev.dwhipstock.poscloud.portalScopeAndPrincipal
import dev.dwhipstock.poscloud.portalScopes
import dev.dwhipstock.poscloud.portalVenueScope
import dev.dwhipstock.poscloud.store.STORE_FRESH_MINUTES
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.SecureRandom
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Venue directory + terminal pairing + device registry (M8).
 *
 * The owner mints a short-lived single-use pairing code here for ONE venue; the
 * terminal presents it to that venue's store container, which claims it back
 * through POST /v1/store/pairing/claim (see StoreRoutes) and mints the actual
 * device token locally — tokens never exist cloud-side. The registry shown here
 * is the store's own summary, reported on every heartbeat; a revoke records the
 * intent and rides the changes feed down (kind "device_revocation"), and the
 * store's next heartbeat confirms it.
 */

// Unambiguous Crockford-style alphabet: no 0/O, 1/I/L, U — owners read these out
// loud. This deliberately mirrors the BackupCodes generate/normalize shape in
// auth/PortalAuth.kt (a distinct human-readable-code system for a different
// purpose); keep the two conventions in sync if either changes.
private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789"
const val PAIRING_CODE_LENGTH = 8
private val CODE_TTL: Duration = Duration.ofMinutes(15)
private val random = SecureRandom()

// One store-freshness policy shared with the /staff-endpoint redirect, so the
// portal's "online" answer and the redirect's can never disagree.
private val STORE_FRESH: Duration = Duration.ofMinutes(STORE_FRESH_MINUTES)

/** "abcd-efgh " → "ABCDEFGH": what gets hashed; display adds the dash back. */
fun normalizePairingCode(raw: String): String = raw.uppercase().filter { it in CODE_ALPHABET }

@Serializable
data class VenueDto(
    val id: String, val name: String, val timezone: String, val subdomain: String?,
    val storeUrl: String?, val storeOnline: Boolean, val storeSeenAt: String?,
    /** ISO 4217 / 3166 codes and restaurant | retail (017). */
    val currency: String = "CAD", val country: String = "CA", val kind: String = "restaurant",
)

/**
 * The tenant's stores plus how its money reads across them: the reporting
 * currency "All stores" converts into, and the fixed rates it uses.
 */
@Serializable
data class VenueListResponse(
    val venues: List<VenueDto>,
    val reportingCurrency: String = "CAD",
    val rates: List<dev.dwhipstock.poscloud.Fx.RateDto> = emptyList(),
)

@Serializable
data class PairingCodeRequest(val label: String? = null)

@Serializable
data class PairingCodeResponse(
    val code: String, val expiresAt: String,
    /** The venue store's public base URL, when it has one — the QR payload the terminal parses. */
    val url: String?,
)

@Serializable
data class DeviceDto(
    val deviceId: String, val name: String, val pairedAt: String?, val lastSeenAt: String?,
    val revoked: Boolean, val revokeRequestedAt: String?,
)

@Serializable
data class DeviceListResponse(val devices: List<DeviceDto>)

/**
 * A store's POS as the Devices page shows it: its own heartbeat liveness plus the
 * device registry that heartbeat mirrors. This is the everyday picture (one POS
 * per store syncing with its store API key); pairing extra terminals is optional.
 */
@Serializable
data class StorePosDto(
    val venueId: String, val venueName: String,
    /** "online" | "stale" | "offline" — see [storeLinkStatus]. */
    val status: String,
    val lastSeenAt: String?,
    /** Whole seconds since the last heartbeat at response time; null = never. */
    val secondsSinceSeen: Long?,
    val lanUrl: String?,
    val publicUrl: String?,
    val installId: String?,
    val appVersion: String?,
    val contractVersion: Int?,
    val devices: List<DeviceDto>,
)

@Serializable
data class StorePosListResponse(
    val stores: List<StorePosDto>,
    val onlineSeconds: Long = STORE_ONLINE_SECONDS,
    val staleMinutes: Long = STORE_FRESH_MINUTES,
)

/** A heartbeat younger than this → online. The store beats every sync tick (~10s). */
const val STORE_ONLINE_SECONDS = 60L

/**
 * Liveness from the last heartbeat: online up to [STORE_ONLINE_SECONDS]; stale
 * (amber) up to the shared [STORE_FRESH_MINUTES] freshness window, where the
 * /staff-app redirect still trusts the LAN address; offline beyond it or never.
 */
fun storeLinkStatus(seenAt: OffsetDateTime?, now: OffsetDateTime): String {
    if (seenAt == null) return "offline"
    val age = Duration.between(seenAt, now)
    return when {
        age <= Duration.ofSeconds(STORE_ONLINE_SECONDS) -> "online"
        age <= STORE_FRESH -> "stale"
        else -> "offline"
    }
}

fun Route.venueRoutes(config: CloudConfig) {

    /** Every venue of the signed-in tenant — the portal's venue picker. */
    get("/venues") {
        val principal = requirePortal(call)
        val response = transaction {
            val venues = Venues.selectAll().where { Venues.tenantId eq principal.tenantId }
                .orderBy(Venues.id).map { venueDto(it, config) }
            val reporting = dev.dwhipstock.poscloud.reportingCurrencyOf(principal.tenantId)
            val rates = venues.map { it.currency }.distinct().filter { it != reporting }.mapNotNull { c ->
                config.fxRates.rate(c, reporting)?.let {
                    dev.dwhipstock.poscloud.Fx.RateDto(c, reporting, it.stripTrailingZeros().toPlainString())
                }
            }
            VenueListResponse(venues, reporting, rates)
        }
        call.respond(response)
    }

    /** Mint a single-use pairing code for this venue (15-minute TTL). */
    post("/venues/{venueId}/pairing-codes") {
        // one auth pass: scope + principal together (404s unless {venueId} belongs
        // to the session's tenant). principal.email is the code's createdBy.
        val (principal, scope) = portalScopeAndPrincipal(call)
        val req = runCatching { call.receive<PairingCodeRequest>() }.getOrDefault(PairingCodeRequest())
        val code = StringBuilder().apply {
            repeat(PAIRING_CODE_LENGTH) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) }
        }.toString()
        val now = dev.dwhipstock.poscloud.CloudTime.now()
        val expiresAt = now.plus(CODE_TTL)
        val (url, zone) = transaction {
            // hygiene: long-expired codes have no forensic value beyond a day
            PairingCodes.deleteWhere { PairingCodes.expiresAt less now.minusDays(1) }
            PairingCodes.insert {
                it[tenantId] = scope.tenantId
                it[venueId] = scope.venueId
                it[codeSha256] = sha256Hex(code)
                it[label] = req.label?.trim().orEmpty()
                it[createdBy] = principal.email
                it[createdAt] = now
                it[PairingCodes.expiresAt] = expiresAt
            }
            venueRow(scope).let { publicStoreUrl(it, config) to dev.dwhipstock.poscloud.CloudTime.zone(it[Venues.timezone]) }
        }
        call.respond(HttpStatusCode.Created, PairingCodeResponse(
            code = "${code.take(4)}-${code.drop(4)}",
            expiresAt = dev.dwhipstock.poscloud.CloudTime.iso(expiresAt, zone),
            url = url,
        ))
    }

    /**
     * Each in-scope store's POS (PortalScope: `?venue=<id>` → that store, none →
     * every store of the tenant): heartbeat liveness, LAN address, install id,
     * reported versions, and the device registry its heartbeat mirrors.
     */
    get("/devices") {
        val (_, venues) = portalScopes(call)
        val now = dev.dwhipstock.poscloud.CloudTime.now()
        val stores = transaction {
            venues.map { vs ->
                val row = venueRow(vs.scope)
                fun iso(t: OffsetDateTime?) = t?.let { dev.dwhipstock.poscloud.CloudTime.iso(it, vs.zone) }
                val seenAt = row[Venues.storeSeenAt]
                StorePosDto(
                    venueId = vs.venueId,
                    venueName = vs.name,
                    status = storeLinkStatus(seenAt, now),
                    lastSeenAt = iso(seenAt),
                    secondsSinceSeen = seenAt?.let { Duration.between(it, now).seconds.coerceAtLeast(0) },
                    lanUrl = row[Venues.storeLanUrl],
                    publicUrl = publicStoreUrl(row, config),
                    installId = row[Venues.storeInstallId],
                    appVersion = row[Venues.storeAppVersion],
                    contractVersion = row[Venues.storeContractVersion],
                    devices = deviceRows(vs.scope, ::iso),
                )
            }
        }
        call.respond(StorePosListResponse(stores))
    }

    /** The venue's device registry, as last reported by its store container. */
    get("/venues/{venueId}/devices") {
        val scope = portalVenueScope(call)
        val devices = transaction {
            val zone = dev.dwhipstock.poscloud.CloudTime.venueZone(scope.tenantId, scope.venueId)
            deviceRows(scope) { t -> t?.let { dev.dwhipstock.poscloud.CloudTime.iso(it, zone) } }
        }
        call.respond(DeviceListResponse(devices))
    }

    /**
     * Revoke a device: record the intent and push a device_revocation change down
     * this venue's feed. The store applies it within a sync tick and its next
     * heartbeat flips `revoked` here — until then the portal shows "revoking…".
     */
    post("/venues/{venueId}/devices/{deviceId}/revoke") {
        val scope = portalVenueScope(call)
        val deviceId = call.parameters["deviceId"]!!
        transaction {
            val updated = Devices.update({
                (Devices.tenantId eq scope.tenantId) and (Devices.venueId eq scope.venueId) and
                    (Devices.deviceId eq deviceId)
            }) {
                it[revokeRequestedAt] = dev.dwhipstock.poscloud.CloudTime.now()
                it[updatedAt] = dev.dwhipstock.poscloud.CloudTime.now()
            }
            if (updated == 0) throw NotFoundException("no device '$deviceId' at this venue", "bad_device")
            Catalog.appendChange(scope, "device_revocation", deviceId, "upsert",
                buildJsonObject { put("deviceId", deviceId) })
        }
        call.respond(mapOf("ok" to true))
    }

    /**
     * Remove a device from the registry entirely. This is the escape hatch for a
     * GHOST row — a device the store no longer reports (its DB was swapped/restored),
     * which the heartbeat mirror upserts but never deletes and which would otherwise
     * sit stuck in "revoking…" forever. Only removes the cloud projection; if a live
     * store still holds the device, its next heartbeat re-creates the row, so this is
     * safe to use freely.
     */
    delete("/venues/{venueId}/devices/{deviceId}") {
        val scope = portalVenueScope(call)
        val deviceId = call.parameters["deviceId"]!!
        val removed = transaction {
            Devices.deleteWhere {
                (Devices.tenantId eq scope.tenantId) and (Devices.venueId eq scope.venueId) and
                    (Devices.deviceId eq deviceId)
            }
        }
        if (removed == 0) throw NotFoundException("no device '$deviceId' at this venue", "bad_device")
        call.respond(mapOf("ok" to true))
    }
}

/** One venue's mirrored device registry (call inside a transaction). */
private fun deviceRows(scope: Scope, iso: (OffsetDateTime?) -> String?): List<DeviceDto> =
    Devices.selectAll().where {
        (Devices.tenantId eq scope.tenantId) and (Devices.venueId eq scope.venueId)
    }.orderBy(Devices.name).map {
        DeviceDto(
            it[Devices.deviceId], it[Devices.name],
            iso(it[Devices.pairedAt]), iso(it[Devices.lastSeenAt]),
            it[Devices.revoked], iso(it[Devices.revokeRequestedAt]),
        )
    }

private fun venueRow(scope: Scope) =Venues.selectAll().where {
    (Venues.tenantId eq scope.tenantId) and (Venues.id eq scope.venueId)
}.firstOrNull() ?: throw NotFoundException("no venue for tenant")

/** https://<subdomain>.<base domain> when the venue has a cloud store, else null. */
private fun publicStoreUrl(row: org.jetbrains.exposed.sql.ResultRow, config: CloudConfig): String? {
    val subdomain = row[Venues.subdomain] ?: return null
    val base = config.publicBaseDomain ?: return null
    return "https://$subdomain.$base"
}

private fun venueDto(row: org.jetbrains.exposed.sql.ResultRow, config: CloudConfig): VenueDto {
    val seenAt = row[Venues.storeSeenAt]
    return VenueDto(
        id = row[Venues.id],
        name = row[Venues.name],
        timezone = row[Venues.timezone],
        subdomain = row[Venues.subdomain],
        storeUrl = publicStoreUrl(row, config),
        storeOnline = seenAt != null && Duration.between(seenAt, dev.dwhipstock.poscloud.CloudTime.now()) <= STORE_FRESH,
        storeSeenAt = seenAt?.let { dev.dwhipstock.poscloud.CloudTime.iso(it, dev.dwhipstock.poscloud.CloudTime.zone(row[Venues.timezone])) },
        currency = row[Venues.currency],
        country = row[Venues.country],
        kind = row[Venues.kind],
    )
}
