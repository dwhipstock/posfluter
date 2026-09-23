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

/** One cloud catalog change (§4). `data` is a full snapshot for upserts, an id for deletes. */
data class CatalogChange(val version: Long, val kind: String, val op: String, val data: JsonObject)

data class ChangesPage(val cursor: Long, val changes: List<CatalogChange>)

class FetchedPhoto(val bytes: ByteArray, val contentType: String)

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
    /** Report the store's current reachable base URL (M7) + device registry
     *  summary (M8). Default no-op keeps test fakes simple — heartbeat is
     *  best-effort, never gates the sync loop. */
    fun heartbeat(
        installId: String, lanBaseUrl: String,
        devices: List<dev.dwhipstock.pos.base.DeviceRegistry.DeviceSummary> = emptyList(),
    ): PushResult = PushResult(true)
    fun fetchChanges(since: Long): ChangesPage
    /** Null = the cloud has no photo for this item (404). */
    fun fetchPhoto(itemId: String): FetchedPhoto?
    fun pushPhoto(itemId: String, bytes: ByteArray, contentType: String): PushResult
    /** Redeem a portal-minted pairing code for THIS venue (M8). ok=false detail
     *  carries the cloud's machine code (bad_pairing_code) when refused. */
    fun claimPairing(code: String): PushResult = PushResult(false, "pairing unavailable")
}
