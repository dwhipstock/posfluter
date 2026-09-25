package dev.dwhipstock.poscloud.store

import dev.dwhipstock.poscloud.BadRequestException
import dev.dwhipstock.poscloud.ConflictException
import dev.dwhipstock.poscloud.NotFoundException
import dev.dwhipstock.poscloud.PayloadTooLargeException
import dev.dwhipstock.poscloud.UnauthorizedException
import dev.dwhipstock.poscloud.auth.sha256Hex
import dev.dwhipstock.poscloud.CloudConfig
import dev.dwhipstock.poscloud.catalog.Catalog
import dev.dwhipstock.poscloud.catalog.Scope
import dev.dwhipstock.poscloud.db.CatalogChanges
import dev.dwhipstock.poscloud.db.CatalogItems
import dev.dwhipstock.poscloud.db.Devices
import dev.dwhipstock.poscloud.db.Events
import dev.dwhipstock.poscloud.db.ItemPhotos
import dev.dwhipstock.poscloud.db.PairingCodes
import dev.dwhipstock.poscloud.db.StoreApiKeys
import dev.dwhipstock.poscloud.db.Venues
import dev.dwhipstock.poscloud.venues.PAIRING_CODE_LENGTH
import dev.dwhipstock.poscloud.venues.normalizePairingCode
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import java.time.LocalDateTime

private const val MAX_PHOTO_BYTES = 2 * 1024 * 1024
private val ALLOWED_PHOTO_TYPES = setOf("image/jpeg", "image/png")
private const val CHANGES_PAGE = 200
/** The one change kind the cloud still sends down (remote lock of a lost terminal). */
const val REVOCATION_KIND = "device_revocation"
// A heartbeat older than this → the store is treated as offline (its LAN IP is
// not served for the /staff-app redirect). The store beats every sync tick (~10s).
// shared with the portal venue picker (venues/VenueRoutes.kt) so "store online"
// means the same thing in both places
internal const val STORE_FRESH_MINUTES = 10L

// Only a PRIVATE/loopback IPv4 host is ever accepted as a store's reported base.
// This is the guard that makes the portal /staff-app redirect safe: a store-key
// holder can never point staff phones at an arbitrary external host (phishing) —
// only at a LAN address that is meaningless off the venue network.
private val PRIVATE_LAN_HOST = Regex(
    "^(?:10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|" +
        "172\\.(?:1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}|" +
        "192\\.168\\.\\d{1,3}\\.\\d{1,3}|" +
        "127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|localhost)$")

/** Canonical private-LAN origin (scheme://host[:port]) or null if not a private
 *  http(s) LAN address. Path/query/userinfo are dropped — the portal always fixes
 *  the /staff-app path itself, so the store only ever chooses the LAN host. */
private fun saneLanBaseUrl(raw: String): String? {
    val uri = runCatching { java.net.URI(raw.trim()) }.getOrNull() ?: return null
    if (uri.scheme != "http" && uri.scheme != "https") return null
    if (uri.userInfo != null) return null
    val host = uri.host ?: return null
    if (!PRIVATE_LAN_HOST.matches(host)) return null
    val port = if (uri.port == -1) "" else ":${uri.port}"
    return "${uri.scheme}://$host$port"
}

/** The venue's own public origin (https://<subdomain>.<base domain>) or null.
 *  Only that exact host is accepted — the registry, not the store, names it. */
private fun sanePublicBaseUrl(raw: String, subdomain: String?, baseDomain: String?): String? {
    if (subdomain == null || baseDomain == null) return null
    val uri = runCatching { java.net.URI(raw.trim()) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1) return null
    return if (uri.host == "$subdomain.$baseDomain") "https://${uri.host}" else null
}

@Serializable
data class PairingClaimRequest(val code: String)

/** Bearer store key → (tenant, venue). Keys are looked up by SHA-256, never logged. */
fun requireStore(call: ApplicationCall): Scope {
    val header = call.request.headers[HttpHeaders.Authorization]
        ?: throw UnauthorizedException("missing api key", "bad_api_key")
    val key = header.removePrefix("Bearer ").trim()
    return transaction {
        val hash = sha256Hex(key)
        val row = StoreApiKeys.selectAll().where { StoreApiKeys.keySha256 eq hash }.firstOrNull()
            ?: throw UnauthorizedException("unknown api key", "bad_api_key")
        StoreApiKeys.update({ StoreApiKeys.keySha256 eq hash }) { it[lastSeenAt] = LocalDateTime.now() }
        Scope(row[StoreApiKeys.tenantId], row[StoreApiKeys.venueId])
    }
}

@Serializable
data class IngestEvent(
    val eventId: String, val seq: Long, val eventType: String,
    val aggregateType: String, val aggregateId: String,
    val createdAt: String, val payload: JsonObject)

@Serializable
data class IngestRequest(val events: List<IngestEvent>, val installId: String? = null)

@Serializable
data class IngestResponse(val accepted: Int, val duplicates: Int, val highWaterMark: Long)

@Serializable
data class HeartbeatDevice(
    val id: String, val name: String,
    val pairedAt: String? = null, val lastSeenAt: String? = null, val revoked: Boolean = false,
)

@Serializable
data class HeartbeatRequest(
    val installId: String? = null, val lanBaseUrl: String,
    /** The store's device-registry summary (M8); the cloud mirrors it for the portal. */
    val devices: List<HeartbeatDevice>? = null,
)

@Serializable
data class StaffEndpointResponse(val base: String, val seenAt: String)

@Serializable
data class ChangeDto(val version: Long, val kind: String, val op: String, val data: JsonElement)

@Serializable
data class ChangesResponse(val cursor: Long, val changes: List<ChangeDto>)

// config is required (no default): the heartbeat's public-host check keys off
// config.publicBaseDomain, and a CloudConfig() default would silently read it
// from the process env of whatever machine a call site ran on.
fun Route.storeRoutes(config: CloudConfig) {

    /**
     * At-least-once idempotent ingest (CONTRACT §1): one transaction per batch,
     * dedup on (tenant, event_id); only newly inserted events are projected,
     * in seq order.
     */
    post("/ingest") {
        val scope = requireStore(call)
        val req = call.receive<IngestRequest>()
        var accepted = 0
        var duplicates = 0
        transaction {
            requireKnownInstall(scope, req.installId)
            for (event in req.events.sortedBy { it.seq }) {
                val inserted = Events.insertIgnore {
                    it[tenantId] = scope.tenantId
                    it[venueId] = scope.venueId
                    it[eventId] = event.eventId
                    it[eventType] = event.eventType
                    it[aggregateType] = event.aggregateType
                    it[aggregateId] = event.aggregateId
                    it[payload] = event.payload.toString()
                    it[storeSeq] = event.seq
                    it[storeCreatedAt] = parseCreatedAt(event.createdAt)
                    it[receivedAt] = LocalDateTime.now()
                }.insertedCount
                if (inserted == 0) {
                    duplicates++
                } else {
                    accepted++
                    Projections.apply(scope, event)
                }
            }
        }
        call.respond(IngestResponse(accepted, duplicates, req.events.maxOfOrNull { it.seq } ?: 0))
    }

    /** Photo sideband (CONTRACT §3): binary for an already-ingested item.photo_uploaded. */
    post("/ingest/photos/{itemId}") {
        val scope = requireStore(call)
        val itemId = call.parameters["itemId"]!!
        val (bytes, contentType) = receivePhoto(call)
        val version = storePhoto(scope, itemId, bytes, contentType)
        call.respond(mapOf("itemId" to itemId, "photoVersion" to version.toString()))
    }

    /**
     * Revocation feed (CONTRACT §4) — the ONLY cloud → store data (one-way sync:
     * menu and staff are tablet-owned). Cursor = last version in the page, or
     * `since` when empty. The legacy `/store/catalog/changes` path serves the
     * same revocation-only feed so a not-yet-updated tablet keeps its remote lock.
     */
    val revocations: suspend RoutingContext.() -> Unit = {
        val scope = requireStore(call)
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0
        val response = transaction {
            // scoped to the key's VENUE, not just its tenant — each store receives
            // exactly its own devices' revocations
            val rows = CatalogChanges.selectAll()
                .where {
                    (CatalogChanges.tenantId eq scope.tenantId) and
                        (CatalogChanges.venueId eq scope.venueId) and
                        (CatalogChanges.kind eq REVOCATION_KIND) and
                        (CatalogChanges.version greater since)
                }
                .orderBy(CatalogChanges.version)
                .limit(CHANGES_PAGE)
                .toList()
            ChangesResponse(
                cursor = rows.lastOrNull()?.get(CatalogChanges.version) ?: since,
                changes = rows.map {
                    ChangeDto(
                        it[CatalogChanges.version], it[CatalogChanges.kind], it[CatalogChanges.op],
                        Json.parseToJsonElement(it[CatalogChanges.data]),
                    )
                },
            )
        }
        call.respond(response)
    }
    get("/store/revocations", revocations)
    get("/store/catalog/changes", revocations)

    /**
     * Store liveness + reachable LAN address (M7 / CONTRACT §8). The store posts
     * its current base URL every sync tick; the portal reads it to redirect staff
     * phones to the in-store ordering app. Unlike ingest this never *pins* the
     * install id (it writes no projected history), but it still refuses a
     * mismatched one so a rogue reset DB can't hijack the venue's redirect.
     */
    post("/store/heartbeat") {
        val scope = requireStore(call)
        val req = call.receive<HeartbeatRequest>()
        transaction {
            val venue = Venues.selectAll().where {
                (Venues.tenantId eq scope.tenantId) and (Venues.id eq scope.venueId)
            }.firstOrNull() ?: throw NotFoundException("no venue for key")
            // A base URL may be either a private-LAN origin (on-prem store; the portal
            // 302s staff phones to it) or the venue's OWN registered public host
            // (cloud-hosted container). Anything else is rejected before it can reach
            // the DB — a store-key holder must never point staff at an arbitrary host.
            val lanBase = saneLanBaseUrl(req.lanBaseUrl)
            val accepted = lanBase != null ||
                sanePublicBaseUrl(req.lanBaseUrl, venue[Venues.subdomain], config.publicBaseDomain) != null
            if (!accepted) throw BadRequestException(
                "lanBaseUrl must be a private LAN origin or this venue's public base", "bad_lan_url")
            val known = venue[Venues.storeInstallId]
            if (req.installId != null && known != null && known != req.installId)
                throw ConflictException(
                    "store install id does not match this venue's recorded store database",
                    "install_mismatch")
            val now = LocalDateTime.now()
            Venues.update({ (Venues.tenantId eq scope.tenantId) and (Venues.id eq scope.venueId) }) {
                // store_lan_url is the /staff-app redirect target and means "a private-LAN
                // origin" — only an on-prem store populates it. A cloud venue is reached
                // directly at its public host (derived from subdomain), so it leaves this
                // NULL rather than overloading the column with a public origin.
                it[storeLanUrl] = lanBase
                it[storeSeenAt] = now
            }
            // Mirror the store's device registry for the portal. revoke_requested_at is
            // portal intent and deliberately not touched here; `revoked` is store truth.
            req.devices?.forEach { device ->
                Devices.upsert {
                    it[tenantId] = scope.tenantId
                    it[venueId] = scope.venueId
                    it[deviceId] = device.id
                    it[name] = device.name
                    it[pairedAt] = device.pairedAt?.let { p -> runCatching { LocalDateTime.parse(p) }.getOrNull() }
                    it[lastSeenAt] = device.lastSeenAt?.let { s -> runCatching { LocalDateTime.parse(s) }.getOrNull() }
                    it[revoked] = device.revoked
                    it[updatedAt] = now
                }
            }
        }
        call.respond(mapOf("ok" to "true"))
    }

    /**
     * Claim a pairing code (M8). The terminal handed the code to the store
     * container; the store redeems it here under its venue-bound key, then mints
     * the device token locally. used_at flips under the row lock — losing racer
     * sees zero rows updated, so a code can never pair two devices. Unknown,
     * foreign-venue, used, and expired codes are indistinguishable on purpose.
     */
    post("/store/pairing/claim") {
        val scope = requireStore(call)
        val req = call.receive<PairingClaimRequest>()
        val normalized = normalizePairingCode(req.code)
        if (normalized.length != PAIRING_CODE_LENGTH)
            throw NotFoundException("unknown or expired pairing code", "bad_pairing_code")
        val label = transaction {
            val hash = dev.dwhipstock.poscloud.auth.sha256Hex(normalized)
            val now = LocalDateTime.now()
            val updated = PairingCodes.update({
                (PairingCodes.codeSha256 eq hash) and
                    (PairingCodes.tenantId eq scope.tenantId) and (PairingCodes.venueId eq scope.venueId) and
                    PairingCodes.usedAt.isNull() and (PairingCodes.expiresAt greater now)
            }) { it[usedAt] = now }
            if (updated == 0) throw NotFoundException("unknown or expired pairing code", "bad_pairing_code")
            PairingCodes.selectAll().where { PairingCodes.codeSha256 eq hash }.first()[PairingCodes.label]
        }
        call.respond(mapOf("ok" to "true", "label" to label))
    }
}

/**
 * PUBLIC (no store key): the current in-store staff-app base URL for the single
 * venue, if a store has heartbeated recently. The portal's /staff-app route reads
 * this and 302-redirects staff phones to <base>/staff-app. Single-venue
 * assumption (like the catalog's menuScope) — returns the freshest live venue. A
 * stale/absent heartbeat → 404 (`store_offline`) so the portal shows an offline
 * page instead of bouncing phones to a dead IP. Only a private LAN address is
 * exposed, which is meaningless off the venue network.
 */
fun Route.staffEndpointRoute() {
    get("/staff-endpoint") {
        val fresh = transaction {
            Venues.selectAll()
                .where { Venues.storeLanUrl.isNotNull() and Venues.storeSeenAt.isNotNull() }
                .map { Triple(it[Venues.tenantId], it[Venues.storeLanUrl]!!, it[Venues.storeSeenAt]!!) }
        }.filter { java.time.Duration.between(it.third, LocalDateTime.now()).toMinutes() <= STORE_FRESH_MINUTES }
            // only ON-PREM stores (private-LAN base) participate: cloud-hosted venues
            // report their public host and are reached directly at it, never via this
            // single-tenant redirect — and must not trip its one-live-tenant guard
            .filter { saneLanBaseUrl(it.second) != null }
        // This public, unauthenticated endpoint carries no tenant context, so it can
        // only answer safely when exactly ONE tenant is live: zero → offline; more
        // than one → refuse, rather than risk sending a tenant's staff to another
        // tenant's store. A venue remains isolated; multi-tenant access fails safe here.
        val tenants = fresh.map { it.first }.distinct()
        if (tenants.size != 1) throw NotFoundException(
            if (tenants.isEmpty()) "no store has reported a LAN address recently"
            else "multiple live tenants; cannot resolve staff endpoint without tenant context",
            "store_offline")
        val newest = fresh.maxByOrNull { it.third }!!
        call.respond(StaffEndpointResponse(base = newest.second, seenAt = newest.third.toString()))
    }
}

/**
 * Store check/shift ids are SQLite autoincrements — a recreated store DB
 * restarts them at 1 and would silently upsert over projected history. First
 * push pins the install id; a mismatch is a loud 409 for an operator, never a
 * silent rewrite. Absent id (older store) skips the check.
 */
private fun requireKnownInstall(scope: Scope, installId: String?) {
    if (installId == null) return
    val known = Venues.selectAll().where {
        (Venues.tenantId eq scope.tenantId) and (Venues.id eq scope.venueId)
    }.firstOrNull()?.get(Venues.storeInstallId)
    when (known) {
        null -> Venues.update({ (Venues.tenantId eq scope.tenantId) and (Venues.id eq scope.venueId) }) {
            it[storeInstallId] = installId
        }
        installId -> {}
        else -> throw ConflictException(
            "store install id does not match this venue's recorded store database; " +
                "see infra README troubleshooting", "install_mismatch")
    }
}

// a malformed timestamp must not wedge the sync loop in a 400-retry cycle;
// receive time is an honest fallback for an audit column
private fun parseCreatedAt(value: String): LocalDateTime =
    runCatching { LocalDateTime.parse(value) }.getOrElse { LocalDateTime.now() }

suspend fun receivePhoto(call: ApplicationCall): Pair<ByteArray, String> {
    var bytes: ByteArray? = null
    var contentType: String? = null
    call.receiveMultipart().forEachPart { part ->
        if (part is PartData.FileItem && part.name == "photo") {
            contentType = part.contentType?.toString()?.lowercase()
            // Enforce the cap WHILE streaming so an oversized body is never fully buffered
            // onto the (512MB) heap — abort with 413 the moment it passes MAX_PHOTO_BYTES.
            bytes = part.provider().readCapped(MAX_PHOTO_BYTES)
        }
        part.dispose()
    }
    val data = bytes ?: throw BadRequestException("multipart field 'photo' required", "photo_required")
    if (contentType !in ALLOWED_PHOTO_TYPES)
        throw BadRequestException("only JPEG or PNG photos are supported", "photo_type_unsupported")
    return data to contentType!!
}

/**
 * Read the whole channel but refuse to buffer more than [max] bytes: the moment the
 * running total passes the cap we stop reading and reject (413), so an oversized
 * upload never lands whole on the heap.
 */
private suspend fun ByteReadChannel.readCapped(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    while (!exhausted()) {
        val read = readAvailable(chunk, 0, chunk.size)
        if (read <= 0) continue
        if (out.size().toLong() + read > max)
            throw PayloadTooLargeException("photo too large (max 2MB)", "photo_too_large")
        out.write(chunk, 0, read)
    }
    return out.toByteArray()
}

/** Upsert the store-pushed binary for portal display (nothing is redistributed). */
fun storePhoto(scope: Scope, itemId: String, bytes: ByteArray, contentType: String): Long = transaction {
    val version = System.currentTimeMillis()
    ItemPhotos.upsert {
        it[tenantId] = scope.tenantId
        it[venueId] = scope.venueId
        it[ItemPhotos.itemId] = itemId
        it[content] = bytes
        it[ItemPhotos.contentType] = contentType
        it[ItemPhotos.version] = version
        it[updatedAt] = LocalDateTime.now()
    }
    CatalogItems.update({
        (CatalogItems.tenantId eq scope.tenantId) and (CatalogItems.venueId eq scope.venueId) and
            (CatalogItems.id eq itemId)
    }) { it[photoVersion] = version }
    version
}
