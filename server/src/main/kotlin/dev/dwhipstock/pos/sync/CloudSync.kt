package dev.dwhipstock.pos.sync

import dev.dwhipstock.pos.api.allCategoriesJson
import dev.dwhipstock.pos.api.allLiveItemsJson
import dev.dwhipstock.pos.base.StaffSnapshots
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.db.SyncState
import dev.dwhipstock.pos.sdk.Outbox
import dev.dwhipstock.pos.sdk.PhotoStore
import dev.dwhipstock.pos.sdk.VenueClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * The store → cloud sync loop (CONTRACT.md §1, §3, §4). Sync is ONE-WAY: the
 * tablet owns its menu, staff and grants and drains its outbox up so the portal
 * can report on and display them. The only thing pulled down is device
 * revocations (remote lock of a lost terminal). The outbox stays the ONLY
 * up-sync source. Never constructed unless CLOUD_SYNC_URL + CLOUD_SYNC_API_KEY
 * are set — offline-first is the default, and a failing tick never blocks a
 * sale, a login or startup.
 */
class CloudSync(
    private val transport: CloudTransport,
    private val photoStore: PhotoStore,
    private val intervalSeconds: Long = 10,
    // One-time re-emit of report-complete history (see reEmitReportHistory usage).
    // Defaulted to a no-op so tests and callers that don't need it stay simple.
    private val reEmitReportHistory: () -> Int = { 0 },
    // The store's current reachable LAN base URL (M7), re-evaluated each tick so a
    // DHCP change is picked up. Null (no LAN / airplane-mode dev) → skip the beat.
    private val lanBaseUrl: () -> String? = { null },
) {
    private val log = LoggerFactory.getLogger(CloudSync::class.java)

    companion object {
        const val PUSH_HWM = "push_hwm"
        // Historical key name (it used to track the catalog feed); it now tracks
        // the revocation feed, which shares the same version sequence.
        const val CHANGES_CURSOR = "catalog_cursor"
        const val CATALOG_SNAPSHOT_SEQ = "catalog_snapshot_seq"
        const val STAFF_SNAPSHOT_SEQ = "staff_snapshot_seq"
        const val REPORT_BACKFILL_SEQ = "report_backfill_seq"
        const val INSTALL_ID = "install_id"
        const val BATCH_LIMIT = 200
    }

    /** Stable id for THIS store database, minted on first sync. */
    private fun installId(): String = transaction {
        SyncState.get(INSTALL_ID)
            ?: java.util.UUID.randomUUID().toString().also { SyncState.set(INSTALL_ID, it) }
    }

    fun start(scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
        log.info("cloud sync started (interval ${intervalSeconds}s)")
        while (isActive) {
            runCatching { tick() }.onFailure { log.warn("sync tick failed: ${it.message}") }
            delay(intervalSeconds * 1000)
        }
    }

    fun tick() {
        val capable = checkCapabilities()
        heartbeatOnce()
        drainOnce(capable)
        pullOnce()
    }

    /**
     * Capability handshake (CONTRACT §0). Every timestamp this store sends is an
     * offset-carrying instant; a cloud that predates contract v2 would misread
     * them (and fall back to "now"), so nothing timestamped leaves the store
     * until the cloud says it understands instants. The outbox simply holds —
     * nothing is dropped, and selling is never affected. Confirmed once per
     * process; an unconfirmed cloud is asked again every tick.
     */
    @Volatile private var instantsConfirmed = false
    private var holdLogged = false

    fun checkCapabilities(): Boolean {
        if (instantsConfirmed) return true
        val caps = runCatching { transport.capabilities() }
            .getOrElse { log.warn("cloud capabilities check failed (${it.message}); retries next tick"); return false }
        if (caps.understandsInstants) {
            instantsConfirmed = true
            if (holdLogged) log.info("cloud now accepts instant timestamps (contract v${caps.contractVersion}); " +
                "resuming held pushes")
            holdLogged = false
            return true
        }
        if (!holdLogged) {
            log.warn("cloud speaks contract v${caps.contractVersion} (timestamps '${caps.timestampFormat}') and " +
                "would misread this store's instant timestamps — holding outbox pushes until the cloud " +
                "is upgraded. Nothing is dropped; selling is unaffected.")
            holdLogged = true
        }
        return false
    }

    /**
     * §8 heartbeat: report the store's reachable LAN base URL so the portal can
     * redirect staff phones. Best-effort and isolated — a heartbeat failure must
     * never stall the drain/pull that follows it.
     */
    private var lastDeviceDigest: String? = null

    fun heartbeatOnce() {
        val url = lanBaseUrl() ?: return
        val devices = runCatching { dev.dwhipstock.pos.base.DeviceRegistry.summaries() }.getOrDefault(emptyList())
        // ship the registry only when it changed — otherwise the cloud re-upserts
        // every device row every 10s tick for nothing. last_seen moves at most once
        // per minute (touch throttle), so an active device still refreshes ~1/min.
        val digest = devices.joinToString("|") { "${it.id},${it.name},${it.revoked},${it.lastSeenAt}" }
        // device timestamps are instants too: an unconfirmed cloud only gets the
        // LAN URL, and the registry follows once the handshake succeeds
        val confirmed = instantsConfirmed
        val toSend = if (!confirmed || digest == lastDeviceDigest) emptyList() else devices
        val result = runCatching { transport.heartbeat(installId(), url, toSend) }
            .getOrElse { PushResult(false, it.message ?: "transport error") }
        if (result.ok) { if (confirmed) lastDeviceDigest = digest } // only mark clean on a delivered registry
        else log.warn("heartbeat failed (${result.detail}); retries next tick")
    }

    // --- push (§1) ---

    /**
     * One full drain pass: bootstrap snapshots if needed, then push ≤200-event
     * batches ordered by seq until the outbox is drained or a push fails.
     * The HWM only advances on a 200; failed batches simply retry next tick.
     */
    fun drainOnce(capable: Boolean = checkCapabilities()) {
        ensureCatalogSnapshot()
        ensureStaffSnapshot()
        ensureReportBackfill()
        if (!capable) return // held, not dropped: the HWM stays put
        while (true) {
            val hwm = stateLong(PUSH_HWM) ?: 0L
            val batch = transaction {
                SyncOutbox.selectAll().where { SyncOutbox.id greater hwm.toInt() }
                    .orderBy(SyncOutbox.id).limit(BATCH_LIMIT)
                    .map { row ->
                        PushEvent(
                            eventId = row[SyncOutbox.eventId],
                            seq = row[SyncOutbox.id].value.toLong(),
                            eventType = row[SyncOutbox.eventType],
                            aggregateType = row[SyncOutbox.aggregateType],
                            aggregateId = row[SyncOutbox.aggregateId],
                            createdAt = VenueClock.iso(row[SyncOutbox.createdAt]),
                            payload = parsePayload(row[SyncOutbox.payload]),
                        )
                    }
            }
            if (batch.isEmpty()) return
            val result = runCatching { transport.push(installId(), batch) }
                .getOrElse { PushResult(false, it.message ?: "transport error") }
            if (!result.ok) {
                if ("install_mismatch" in result.detail) {
                    // this database is not the one the cloud knows for this key —
                    // pushing would overwrite projected history. Needs an operator
                    // (restore the right DB, or reset the cloud's install id).
                    log.error("cloud refused push: install id mismatch — sync halted until resolved " +
                        "(see cloud/infra/README.md troubleshooting)")
                } else {
                    log.warn("push failed at hwm $hwm (${result.detail}); retrying next tick")
                }
                return
            }
            setState(PUSH_HWM, batch.last().seq.toString())
            pushPhotosFor(batch)
            if (batch.size < BATCH_LIMIT) return
        }
    }

    /**
     * First-run bootstrap: the seed only wrote a thin catalog.seeded event, so
     * the first sync writes ONE full-catalog snapshot event. It rides the
     * normal drain — the outbox stays the only up-sync source.
     */
    private fun ensureCatalogSnapshot() = transaction {
        if (SyncState.get(CATALOG_SNAPSHOT_SEQ) != null) return@transaction
        Outbox.write("catalog.snapshot", "catalog", "snapshot", buildJsonObject {
            put("categories", allCategoriesJson())
            put("items", allLiveItemsJson())
        })
        SyncState.set(CATALOG_SNAPSHOT_SEQ, lastOutboxSeq().toString())
    }

    /**
     * One-time staff bootstrap (one-way sync): staff and the role→grant matrix
     * used to flow cloud → store, so no store ever pushed them. Emit ONE full
     * staff snapshot per database (also on stores that synced before this
     * existed); every later staff edit carries its own snapshot. PIN hashes
     * never leave the tablet.
     */
    private fun ensureStaffSnapshot() = transaction {
        if (SyncState.get(STAFF_SNAPSHOT_SEQ) != null) return@transaction
        Outbox.write("staff.snapshot", "staff", "snapshot", StaffSnapshots.fullSnapshot())
        SyncState.set(STAFF_SNAPSHOT_SEQ, lastOutboxSeq().toString())
    }

    private fun lastOutboxSeq(): Int =
        SyncOutbox.selectAll().orderBy(SyncOutbox.id, SortOrder.DESC).limit(1)
            .firstOrNull()?.get(SyncOutbox.id)?.value ?: 0

    /**
     * One-time backfill: checks closed before the report-complete payload
     * existed synced thin (grand total only), so cloud tax/payment-mix
     * undercount. Re-emit report-complete `check.closed` events from the
     * store's own history; they ride the normal drain and the cloud upsert
     * backfills tax + tenders in place. Gated by a SyncState marker so it runs
     * exactly once per store database, like the catalog snapshot.
     */
    private fun ensureReportBackfill() {
        if (transaction { SyncState.get(REPORT_BACKFILL_SEQ) } != null) return // already run once
        val emitted = reEmitReportHistory()
        val seq = transaction { lastOutboxSeq() }
        setState(REPORT_BACKFILL_SEQ, seq.toString())
        if (emitted > 0) log.info("report backfill: re-emitted $emitted report-complete check.closed event(s)")
    }

    /** §3 photo sideband, after the batch acks. Best-effort — never blocks the HWM. */
    private fun pushPhotosFor(batch: List<PushEvent>) {
        val itemIds = batch.asSequence()
            .filter { it.eventType == "item.photo_uploaded" }
            // legacy echoes of cloud-applied photos (pre one-way sync) stay down
            .filterNot { it.payload["origin"]?.jsonPrimitive?.contentOrNull == "cloud" }
            .map { it.aggregateId }.distinct().toList()
        for (itemId in itemIds) {
            val photo = photoStore.read(itemId) ?: continue
            val result = runCatching { transport.pushPhoto(itemId, photo.bytes, photo.contentType) }
                .getOrElse { PushResult(false, it.message ?: "transport error") }
            if (!result.ok) log.warn("photo upload for $itemId failed (${result.detail}); " +
                "retries on the next photo event")
        }
    }

    private fun parsePayload(raw: String): JsonObject =
        runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrElse { buildJsonObject {} }

    // --- revocation pull (§4) ---

    /**
     * The single thing the store still pulls: DEVICE REVOCATIONS — the owner's
     * remote lock for a lost terminal. A failed pull is logged and retried next
     * tick; it never blocks anything local.
     */
    fun pullOnce() {
        val since = stateLong(CHANGES_CURSOR) ?: 0L
        val page = runCatching { transport.fetchRevocations(since) }
            .getOrElse { log.warn("revocation pull failed: ${it.message}"); return }
        if (page.changes.isEmpty()) return // cursor unchanged
        applyChanges(page)
    }

    /**
     * Apply a page in version order inside ONE transaction; the cursor persists
     * with it, so a mid-page crash re-applies the page (revocation is idempotent).
     * Any other change kind — catalog/staff rows an older cloud may still serve —
     * is ignored on purpose: the tablet owns that data.
     */
    fun applyChanges(page: ChangesPage) {
        transaction {
            for (change in page.changes.sortedBy { it.version }) {
                when (change.kind) {
                    // portal revoke (M8): flag the device + kill its sessions at once
                    "device_revocation" -> dev.dwhipstock.pos.base.DeviceRegistry.applyRevocation(change.data.str("deviceId"))
                    else -> log.info("ignoring cloud change kind '${change.kind}' (v${change.version}); " +
                        "menu and staff are owned by this tablet")
                }
            }
            SyncState.set(CHANGES_CURSOR, page.cursor.toString())
        }
    }

    // --- shared ---

    private fun stateLong(key: String): Long? = transaction { SyncState.get(key)?.toLongOrNull() }

    private fun setState(key: String, value: String) = transaction { SyncState.set(key, value) }
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
