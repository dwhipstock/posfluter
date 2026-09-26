package dev.dwhipstock.pos.sync

import kotlinx.serialization.json.JsonObject

/** One drained sync_outbox row, §1 wire shape. Payload is a parsed object, never a re-encoded string. */
data class PushEvent(
    val eventId: String,
    val seq: Long,
    val eventType: String,
    val aggregateType: String,
    val aggregateId: String,
    val createdAt: String,
    val payload: JsonObject,
)

data class PushResult(val ok: Boolean, val detail: String = "")

/** One cloud → store change (§4). Only `device_revocation` is ever applied. */
data class CloudChange(val version: Long, val kind: String, val op: String, val data: JsonObject)

data class ChangesPage(val cursor: Long, val changes: List<CloudChange>)

/**
 * What the cloud says it understands (CONTRACT §0). A store only pushes
 * offset-carrying instants to a cloud that advertises `timestampFormat:
 * "instant"`; an older cloud would misread them.
 */
data class CloudCapabilities(val contractVersion: Int, val timestampFormat: String) {
    val understandsInstants: Boolean get() = timestampFormat == INSTANT
    companion object {
        const val INSTANT = "instant"
        /** A cloud that predates the capabilities endpoint (404): contract v1. */
        val LEGACY = CloudCapabilities(1, "venue-local")
    }
}

/**
 * Wire seam for the sync loop: tests drive the drain/apply core with a fake;
 * HttpCloudTransport is the thin JDK/Android HTTP adapter. Implementations may
 * throw on transport errors — the loop treats a throw like a failed result.
 */
interface CloudTransport {
    /** [installId] identifies THIS store database (CONTRACT §1) — the cloud
     *  refuses a mismatch (409 install_mismatch) instead of silently letting a
     *  reset DB's restarted ids overwrite projected history. */
    fun push(installId: String, events: List<PushEvent>): PushResult
    /** The cloud's contract capabilities (§0). [CloudCapabilities.LEGACY] when
     *  the cloud predates the endpoint; throws on a transport error. */
    fun capabilities(): CloudCapabilities
    /** Report the store's current reachable base URL (M7) + device registry
     *  summary (M8). Default no-op keeps test fakes simple — heartbeat is
     *  best-effort, never gates the sync loop. */
    fun heartbeat(
        installId: String, lanBaseUrl: String,
        devices: List<dev.dwhipstock.pos.base.DeviceRegistry.DeviceSummary> = emptyList(),
    ): PushResult = PushResult(true)
    /** Device revocations after [since] (§4) — the only cloud → store data. */
    fun fetchRevocations(since: Long): ChangesPage
    fun pushPhoto(itemId: String, bytes: ByteArray, contentType: String): PushResult
    /** Redeem a portal-minted pairing code for THIS venue (M8). ok=false detail
     *  carries the cloud's machine code (bad_pairing_code) when refused. */
    fun claimPairing(code: String): PushResult = PushResult(false, "pairing unavailable")
    /** The cloud's on-hand per product for this retail store (§9); null when
     *  the cloud has no such feed (older cloud). Throws on a transport error. */
    fun fetchOnHand(): List<dev.dwhipstock.pos.retail.CloudOnHand>? = null
}
