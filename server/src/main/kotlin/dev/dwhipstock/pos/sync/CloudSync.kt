package dev.dwhipstock.pos.sync

import dev.dwhipstock.pos.api.writeChunkedCatalogSnapshot
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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * The store ⇄ cloud sync loop (CONTRACT.md §1, §3, §4, §10). Sales, staff and
 * everything else go ONE-WAY up: the outbox is drained so the portal can
 * report on and display them. The MENU syncs both ways (§10): the store's menu
 * edits go up through the same outbox, and the manager portal's menu edits
 * come down from the cloud's per-store menu feed, merged field by field (last
 * write wins). Device revocations (remote lock of a lost terminal) are pulled
 * too. The outbox stays the ONLY up-sync source. Never constructed unless
 * CLOUD_SYNC_URL + CLOUD_SYNC_API_KEY are set — offline-first is the default,
 * and a failing tick never blocks a sale, a login or startup.
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
    // Retail only: where the cloud's on-hand per product lands (the count
    // screen's "expected" hint, CONTRACT §9). Null → never pulled.
    private val stock: dev.dwhipstock.pos.retail.StockService? = null,
    private val stockIntervalSeconds: Long = 300,
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
        /** A push also stops at about this much payload (a catalog chunk is ~100 KB). */
        const val BATCH_MAX_BYTES = 900_000
        /** Seqs of outbox events the cloud kept refusing while later ones went through (comma separated). */
        const val PUSH_QUARANTINE = "push_quarantine"
        /** Refused this many ticks running (about 30 s), a batch is checked one event at a time. */
        const val ISOLATE_AFTER_FAILURES = 3
    }

    /** Set by [capBatchBytes]: the last batch was cut by size, so more may be waiting. */
    private var batchWasCapped = false

    /** The longest prefix of [rows] under [BATCH_MAX_BYTES] of payload (always at least one row). */
    private fun capBatchBytes(rows: List<org.jetbrains.exposed.sql.ResultRow>): List<org.jetbrains.exposed.sql.ResultRow> {
        var bytes = 0
        var n = 0
        for (row in rows) {
            bytes += row[SyncOutbox.payload].length + 200
            if (n > 0 && bytes > BATCH_MAX_BYTES) break
            n++
        }
        batchWasCapped = n < rows.size
        return if (batchWasCapped) rows.take(n) else rows
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
        pullMenuOnce()
        if (stock != null && System.nanoTime() >= nextStockPullNanos) {
            // due again after the interval whatever the outcome: a slow hint, never a retry storm
            nextStockPullNanos = System.nanoTime() + stockIntervalSeconds * 1_000_000_000
            pullStockOnce()
        }
    }

    @Volatile private var nextStockPullNanos = Long.MIN_VALUE
    @Volatile private var nextMenuPullNanos = Long.MIN_VALUE

    /**
     * The menu pull (§10): the manager portal's menu edits for THIS store,
     * applied through the tablet's own menu code and merged field by field
     * (last write wins). Pages until caught up. An older cloud (404) has no
     * feed: asked again in 5 minutes. Offline: nothing happens, and the store
     * catches up from its cursor once it is back.
     */
    fun pullMenuOnce() {
        if (System.nanoTime() < nextMenuPullNanos) return
        var pages = 0
        while (pages++ < 50) {
            val since = stateLong(MenuSync.MENU_CURSOR) ?: 0L
            val epoch = transaction { SyncState.get(MenuSync.MENU_EPOCH) }
            val failed = transaction { MenuSync.failedCount() }
            val sentAt = System.currentTimeMillis()
            val page = runCatching { transport.fetchMenuChanges(since, epoch, failed) }
                .getOrElse { log.warn("menu pull failed: ${it.message}"); return }
            if (page == null) {
                nextMenuPullNanos = System.nanoTime() + 300L * 1_000_000_000
                return
            }
            val receivedAt = System.currentTimeMillis()
            page.serverTimeMs?.let { server ->
                // the cloud's clock minus ours (half the round trip each way)
                setState(MenuClock.OFFSET_KEY, (server - (sentAt + receivedAt) / 2).toString())
            }
            val newEpoch = page.epoch != null && epoch != null && page.epoch != epoch
            if (newEpoch) transaction {
                // the cloud's database was restored or reset: it serves its feed from
                // the start (replays are harmless), and it may have lost what this
                // store pushed since its backup — send the whole menu again (it
                // carries the store's stamps, so only newer values land)
                log.warn("menu sync: the cloud's menu feed was reset (epoch $epoch -> ${page.epoch}); " +
                    "re-reading it from the start and re-sending this store's menu")
                SyncState.set(MenuSync.MENU_CURSOR, "0")
                SyncState.deleteWhere { SyncState.key eq CATALOG_SNAPSHOT_SEQ }
            }
            // also an empty page: the cursor / epoch it carries, and a retry of what failed before
            MenuSync.applyPage(page)
            // photos from the portal: queued by the page (with its cursor), fetched outside its transaction
            PhotoSync.drain(transport, photoStore)
            if (page.changes.isEmpty()) return
            log.info("menu sync: applied ${page.changes.size} change(s) from the portal (cursor ${page.cursor})")
            if (page.cursor <= since && !newEpoch) return
        }
    }

    /**
     * The on-hand pull (§9): the second, and last, thing the store reads from
     * the cloud — best-effort and read-only, like revocations. The figures
     * cover the events the cloud has acknowledged, so they are stamped with
     * the instant just before the first one it has not; the count screen then
     * applies this store's own moves since. An older cloud (no route) or no
     * internet → the hint says "no expected qty" and counting goes on.
     */
    fun pullStockOnce() {
        val service = stock ?: return
        val page = runCatching { transport.fetchOnHand() }
            .getOrElse { log.warn("on-hand pull failed: ${it.message}"); return } ?: return
        val asOf = service.cloudCaughtUpAt(stateLong(PUSH_HWM) ?: 0L)
        runCatching { service.applyCloudOnHand(asOf, page) }
            .onFailure { log.warn("on-hand cache update failed: ${it.message}") }
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
                    .toList()
                    .let(::capBatchBytes)
                    .map { row ->
                        PushEvent(
                            eventId = row[SyncOutbox.eventId],
                            seq = row[SyncOutbox.id].value.toLong(),
                            eventType = row[SyncOutbox.eventType],
                            aggregateType = row[SyncOutbox.aggregateType],
                            aggregateId = row[SyncOutbox.aggregateId],
                            createdAt = VenueClock.iso(row[SyncOutbox.createdAt]),
                            // printable text only: one control character (a guest's
                            // note with U+0000) once failed every batch it rode in
                            payload = cleanPayload(parsePayload(row[SyncOutbox.payload])),
                        )
                    }
            }
            if (batch.isEmpty()) return
            val result = push(batch)
            if (!result.ok) {
                if ("install_mismatch" in result.detail) {
                    // this database is not the one the cloud knows for this key —
                    // pushing would overwrite projected history. Needs an operator
                    // (restore the right DB, or reset the cloud's install id).
                    log.error("cloud refused push: install id mismatch — sync halted until resolved " +
                        "(see cloud/infra/README.md troubleshooting)")
                    return
                }
                log.warn("push failed at hwm $hwm (${result.detail}); retrying next tick")
                failedTicks = if (failedAtHwm == hwm) failedTicks + 1 else 1
                failedAtHwm = hwm
                // the cloud answers but keeps refusing this batch: find out whether
                // one event is to blame, so it cannot hold back every later sale
                if (result.status != null && failedTicks >= ISOLATE_AFTER_FAILURES && isolate(batch)) continue
                return
            }
            failedTicks = 0
            setState(PUSH_HWM, batch.last().seq.toString())
            pushPhotosFor(batch)
            if (batch.size < BATCH_LIMIT && !batchWasCapped) return
        }
    }

    private fun push(events: List<PushEvent>): PushResult =
        runCatching { transport.push(installId(), events) }
            .getOrElse { PushResult(false, it.message ?: "transport error") }

    /** Consecutive failed ticks at [failedAtHwm] (the same batch refused again and again). */
    private var failedTicks = 0
    private var failedAtHwm = -1L

    /**
     * The cloud answered but refused the same batch [ISOLATE_AFTER_FAILURES]
     * ticks running: its events are sent one at a time. One delivered alone →
     * on to the next. One refused alone → the event after it is sent alone,
     * and only if THAT one goes through is the refused one to blame: it is set
     * aside ([PUSH_QUARANTINE], logged as an error, still in the outbox to
     * replay) and the drain goes on past it. If the next one is refused too,
     * the cloud itself is in trouble and nothing is set aside: the outbox
     * waits, as always. Returns true when the HWM moved.
     */
    private fun isolate(batch: List<PushEvent>): Boolean {
        var moved = false
        var i = 0
        while (i < batch.size) {
            val event = batch[i]
            val alone = push(listOf(event))
            if (alone.ok) {
                delivered(listOf(event)); moved = true; i++
                continue
            }
            val next = batch.getOrNull(i + 1) ?: break // nothing to compare with yet: wait for the next event
            if (!push(listOf(next)).ok) break
            transaction {
                val before = SyncState.get(PUSH_QUARANTINE)?.takeIf { it.isNotBlank() }
                SyncState.set(PUSH_QUARANTINE, listOfNotNull(before, event.seq.toString()).joinToString(","))
            }
            log.error("sync: event seq ${event.seq} (${event.eventType} ${event.aggregateType}/${event.aggregateId}) " +
                "is refused by the cloud (${alone.detail.take(200)}) while the events after it go through; set aside " +
                "(sync_state '$PUSH_QUARANTINE') so the rest keep syncing")
            delivered(listOf(next)); moved = true; i += 2
        }
        if (moved) log.info("push: refused batch sent one event at a time; hwm now ${stateLong(PUSH_HWM)}")
        return moved
    }

    private fun delivered(events: List<PushEvent>) {
        failedTicks = 0
        setState(PUSH_HWM, events.last().seq.toString())
        pushPhotosFor(events)
    }

    /**
     * Every string in [payload], however deep, without what Postgres cannot
     * store (U+0000, lone surrogates: [dev.dwhipstock.pos.base.CleanText.storable]).
     * Keys too. Everything else goes up exactly as written.
     */
    private fun cleanPayload(payload: JsonObject): JsonObject = cleanJson(payload) as JsonObject

    private fun cleanJson(e: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement = when (e) {
        is JsonObject -> JsonObject(e.entries.associate { (k, v) ->
            dev.dwhipstock.pos.base.CleanText.storable(k) to cleanJson(v)
        })
        is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(e.map(::cleanJson))
        is kotlinx.serialization.json.JsonPrimitive ->
            if (e.isString) dev.dwhipstock.pos.base.CleanText.storable(e.content).let {
                if (it === e.content) e else kotlinx.serialization.json.JsonPrimitive(it)
            } else e
        else -> e
    }

    /**
     * First-run bootstrap: the seed only wrote a thin catalog.seeded event, so
     * the first sync writes ONE full-catalog snapshot event. It rides the
     * normal drain — the outbox stays the only up-sync source.
     */
    private fun ensureCatalogSnapshot() = transaction {
        if (SyncState.get(CATALOG_SNAPSHOT_SEQ) != null) return@transaction
        // chunked: a 5,000-product shelf is ~20 events of 250 items, each a
        // small upsert on the cloud (an older cloud applies them the same way)
        writeChunkedCatalogSnapshot()
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
     * DEVICE REVOCATIONS (one of the store's pulls, with the menu feed) — the owner's
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
     * is ignored on purpose: staff are the tablet's, and portal menu edits come
     * down through the menu feed ([pullMenuOnce]), never this one.
     */
    fun applyChanges(page: ChangesPage) {
        transaction {
            for (change in page.changes.sortedBy { it.version }) {
                when (change.kind) {
                    // portal revoke (M8): flag the device + kill its sessions at once
                    "device_revocation" -> dev.dwhipstock.pos.base.DeviceRegistry.applyRevocation(change.data.str("deviceId"))
                    else -> log.info("ignoring cloud change kind '${change.kind}' (v${change.version}); " +
                        "staff are owned by this tablet and the menu comes down through its own feed")
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
