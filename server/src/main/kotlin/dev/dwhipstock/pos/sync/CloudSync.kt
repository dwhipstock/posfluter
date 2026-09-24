package dev.dwhipstock.pos.sync

import dev.dwhipstock.pos.sdk.VenueClock

import dev.dwhipstock.pos.api.allCategoriesJson
import dev.dwhipstock.pos.api.allLiveItemsJson
import dev.dwhipstock.pos.api.categorySnapshotJson
import dev.dwhipstock.pos.api.itemSnapshotJson
import dev.dwhipstock.pos.base.Categories
import dev.dwhipstock.pos.base.GrantsRepo
import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.Sessions
import dev.dwhipstock.pos.base.StaffTotp
import dev.dwhipstock.pos.base.TrustedDevices
import dev.dwhipstock.pos.base.Users
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.db.SyncState
import dev.dwhipstock.pos.sdk.Outbox
import dev.dwhipstock.pos.sdk.PhotoStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * The store⇄cloud sync loop (CONTRACT.md §1, §3, §4): drains the outbox up,
 * pulls catalog changes down. The outbox stays the ONLY up-sync source; the
 * cloud stays authoritative for the catalog. Never constructed unless
 * CLOUD_SYNC_URL + CLOUD_SYNC_API_KEY are set — offline-first is the default.
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
        const val CATALOG_CURSOR = "catalog_cursor"
        const val CATALOG_SNAPSHOT_SEQ = "catalog_snapshot_seq"
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
        heartbeatOnce()
        drainOnce()
        pullOnce()
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
        val toSend = if (digest == lastDeviceDigest) emptyList() else devices
        val result = runCatching { transport.heartbeat(installId(), url, toSend) }
            .getOrElse { PushResult(false, it.message ?: "transport error") }
        if (result.ok) lastDeviceDigest = digest // only mark clean on a delivered beat
        else log.warn("heartbeat failed (${result.detail}); retries next tick")
    }

    // --- push (§1) ---

    /**
     * One full drain pass: bootstrap snapshot if needed, then push ≤200-event
     * batches ordered by seq until the outbox is drained or a push fails.
     * The HWM only advances on a 200; failed batches simply retry next tick.
     */
    fun drainOnce() {
        ensureCatalogSnapshot()
        ensureReportBackfill()
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
                            createdAt = row[SyncOutbox.createdAt].toString(),
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
        val seq = SyncOutbox.selectAll().orderBy(SyncOutbox.id, SortOrder.DESC).limit(1)
            .first()[SyncOutbox.id].value
        SyncState.set(CATALOG_SNAPSHOT_SEQ, seq.toString())
    }

    /**
     * One-time backfill: checks closed before the report-complete payload
     * existed synced thin (grand total only), so cloud VAT/payment-mix
     * undercount. Re-emit report-complete `check.closed` events from the
     * store's own history; they ride the normal drain and the cloud upsert
     * backfills tax + tenders in place. Gated by a SyncState marker so it runs
     * exactly once per store database, like the catalog snapshot.
     */
    private fun ensureReportBackfill() {
        if (transaction { SyncState.get(REPORT_BACKFILL_SEQ) } != null) return // already run once
        val emitted = reEmitReportHistory()
        val seq = transaction {
            SyncOutbox.selectAll().orderBy(SyncOutbox.id, SortOrder.DESC).limit(1)
                .firstOrNull()?.get(SyncOutbox.id)?.value ?: 0
        }
        setState(REPORT_BACKFILL_SEQ, seq.toString())
        if (emitted > 0) log.info("report backfill: re-emitted $emitted report-complete check.closed event(s)")
    }

    /** §3 photo sideband, after the batch acks. Best-effort — never blocks the HWM. */
    private fun pushPhotosFor(batch: List<PushEvent>) {
        val itemIds = batch.asSequence()
            .filter { it.eventType == "item.photo_uploaded" }
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

    // --- pull (§4) ---

    fun pullOnce() {
        val since = stateLong(CATALOG_CURSOR) ?: 0L
        val page = runCatching { transport.fetchChanges(since) }
            .getOrElse { log.warn("catalog pull failed: ${it.message}"); return }
        if (page.changes.isEmpty()) return // cursor unchanged
        applyChanges(page)
    }

    /**
     * Apply a change-batch in version order inside ONE transaction; the cursor
     * persists with it, so a mid-batch crash re-applies the whole page (safe —
     * applying is idempotent). Each applied change writes the matching outbox
     * event with origin:"cloud" — audit trail + cloud ack without an echo loop.
     */
    fun applyChanges(page: ChangesPage) {
        // photo binaries fetched BEFORE the write transaction: SQLite is
        // single-writer, and holding the write lock across an HTTP round-trip
        // would stall POS order-taking. A transport throw aborts here — no
        // write happened, cursor untouched, the page retries next tick.
        val photos = mutableMapOf<String, FetchedPhoto?>()
        for (change in page.changes) {
            if (change.kind != "item.photo") continue
            val itemId = change.data.str("itemId") ?: continue
            photos[itemId] = transport.fetchPhoto(itemId)
        }
        transaction {
            for (change in page.changes.sortedBy { it.version }) {
                when (change.kind) {
                    "category" -> applyCategory(change)
                    "item" -> applyItem(change)
                    "item.photo" -> applyItemPhoto(change, photos)
                    "staff" -> applyStaff(change)
                    "role_grants" -> GrantsRepo.applyRoleGrants(change.data["roles"] as? JsonObject ?: buildJsonObject {})
                    // portal revoke (M8): flag the device + kill its sessions at once
                    "device_revocation" -> dev.dwhipstock.pos.base.DeviceRegistry.applyRevocation(change.data.str("deviceId"))
                    else -> log.warn("unknown change kind '${change.kind}' (v${change.version}); skipped")
                }
            }
            SyncState.set(CATALOG_CURSOR, page.cursor.toString())
        }
    }

    private fun applyCategory(change: CatalogChange) {
        val d = change.data
        val id = d.str("id") ?: return log.warn("category change v${change.version} has no id; skipped")
        val deleted = change.op == "delete" || d.bool("deleted") == true
        val exists = Categories.selectAll().where { Categories.id eq id }.any()
        if (deleted) {
            if (!exists) return // already converged
            // same guard as the API: a category with live items can't go
            val liveItems = Items.selectAll()
                .where { (Items.categoryId eq id) and Items.deletedAt.isNull() }.count()
            if (liveItems > 0) {
                log.warn("cloud deleted category $id but $liveItems live item(s) reference it; skipped")
                return
            }
            val snapshot = categorySnapshotJson(id, deleted = true)
            Categories.deleteWhere { Categories.id eq id }
            writeCloudEcho("category.deleted", "category", id) {
                put("categoryId", id)
                put("category", snapshot)
            }
            return
        }
        if (exists) {
            Categories.update({ Categories.id eq id }) {
                d.str("nameFr")?.let { v -> it[nameFr] = v }
                d.str("nameEn")?.let { v -> it[nameEn] = v }
                d.int("sortOrder")?.let { v -> it[sortOrder] = v }
            }
        } else {
            Categories.insert {
                it[Categories.id] = id
                it[nameFr] = d.str("nameFr") ?: id
                it[nameEn] = d.str("nameEn") ?: id
                it[sortOrder] = d.int("sortOrder") ?: 0
            }
        }
        writeCloudEcho(if (exists) "category.updated" else "category.created", "category", id) {
            put("categoryId", id)
            put("category", categorySnapshotJson(id))
        }
    }

    private fun applyItem(change: CatalogChange) {
        val d = change.data
        val id = d.str("id") ?: return log.warn("item change v${change.version} has no id; skipped")
        val exists = Items.selectAll().where { Items.id eq id }.any()
        if (change.op == "delete") {
            if (!exists) return
            Items.update({ Items.id eq id }) {
                it[active] = false
                it[deletedAt] = VenueClock.now()
            }
            writeCloudEcho("item.deleted", "item", id) {
                put("itemId", id)
                put("item", itemSnapshotJson(id))
            }
            return
        }
        // categories sort earlier by version, so a missing one here is a cloud-side
        // gap: skip WITHOUT a placeholder and let the cursor advance past it
        val categoryId = d.str("categoryId")
        if (categoryId == null || Categories.selectAll().where { Categories.id eq categoryId }.none()) {
            log.warn("item change v${change.version} ($id) references unknown category '$categoryId'; skipped")
            return
        }
        val deleted = d.bool("deleted") == true
        if (exists) {
            Items.update({ Items.id eq id }) {
                d.str("nameFr")?.let { v -> it[nameFr] = v }
                d.str("nameEn")?.let { v -> it[nameEn] = v }
                it[Items.categoryId] = categoryId
                d.str("abbrev")?.let { v -> it[abbrev] = v }
                d.bool("isAlcohol")?.let { v -> it[isAlcohol] = v }
                it[active] = if (deleted) false else d.bool("active") ?: true
                it[deletedAt] = if (deleted) VenueClock.now() else null
            }
        } else {
            Items.insert {
                it[Items.id] = id
                it[nameFr] = d.str("nameFr") ?: id
                it[nameEn] = d.str("nameEn") ?: id
                it[Items.categoryId] = categoryId
                it[abbrev] = (d.str("abbrev") ?: id).take(4)
                it[isAlcohol] = d.bool("isAlcohol") ?: false
                it[active] = if (deleted) false else d.bool("active") ?: true
                if (deleted) it[deletedAt] = VenueClock.now()
            }
        }
        applyVariants(id, change)
        writeCloudEcho(if (exists) "item.updated" else "item.created", "item", id) {
            put("itemId", id)
            put("item", itemSnapshotJson(id))
        }
    }

    /** Converge variants to exactly the snapshot: create missing, update, soft-delete. */
    private fun applyVariants(itemId: String, change: CatalogChange) {
        val snapshots = change.data["variants"]?.jsonArray?.map { it.jsonObject } ?: return
        val snapshotIds = mutableSetOf<String>()
        for (v in snapshots) {
            val vid = v.str("id") ?: continue
            snapshotIds += vid
            val deleted = v.bool("deleted") == true
            val exists = ItemVariants.selectAll().where { ItemVariants.id eq vid }.any()
            if (exists) {
                ItemVariants.update({ ItemVariants.id eq vid }) {
                    v.str("labelFr")?.let { s -> it[labelFr] = s }
                    v.str("labelEn")?.let { s -> it[labelEn] = s }
                    v.long("priceCents")?.let { s -> it[priceCents] = s }
                    v.int("sortOrder")?.let { s -> it[sortOrder] = s }
                    it[deletedAt] = if (deleted) VenueClock.now() else null
                }
            } else {
                ItemVariants.insert {
                    it[id] = vid
                    it[ItemVariants.itemId] = itemId
                    it[labelFr] = v.str("labelFr") ?: vid
                    it[labelEn] = v.str("labelEn") ?: vid
                    it[priceCents] = v.long("priceCents") ?: 0L
                    it[sortOrder] = v.int("sortOrder") ?: 0
                    if (deleted) it[deletedAt] = VenueClock.now()
                }
            }
        }
        // live local variants the snapshot doesn't know → soft-delete (converge;
        // closed-check history keeps the rows, same as an API delete)
        ItemVariants.update({
            (ItemVariants.itemId eq itemId) and ItemVariants.deletedAt.isNull() and
                (ItemVariants.id notInList snapshotIds)
        }) { it[deletedAt] = VenueClock.now() }
    }

    private fun applyItemPhoto(change: CatalogChange, photos: Map<String, FetchedPhoto?>) {
        val itemId = change.data.str("itemId")
            ?: return log.warn("item.photo change v${change.version} has no itemId; skipped")
        if (Items.selectAll().where { Items.id eq itemId }.none()) {
            log.warn("item.photo change v${change.version} for unknown item $itemId; skipped")
            return
        }
        // prefetched before the transaction; null = 404, the photo is gone
        // cloud-side — nothing to converge to
        val photo = photos[itemId] ?: return
        val path = photoStore.save(itemId, photo.bytes, photo.contentType)
        Items.update({ Items.id eq itemId }) { it[photoPath] = path }
        writeCloudEcho("item.photo_uploaded", "item", itemId) {
            put("itemId", itemId)
            put("path", path)
            put("item", itemSnapshotJson(itemId, photoVersion = photoStore.version(itemId)))
        }
    }

    /**
     * Apply a cloud staff snapshot (CONTRACT §7) into the local `users` table +
     * per-staff grant overrides. Staff are cloud-authoritative and down-only, so —
     * unlike catalog changes — nothing is echoed back to the outbox. A delete
     * soft-deletes (row kept for session/check FKs) and clears the overrides.
     */
    private fun applyStaff(change: CatalogChange) {
        val d = change.data
        val id = d.str("id") ?: return log.warn("staff change v${change.version} has no id; skipped")
        val exists = Users.selectAll().where { Users.id eq id }.any()
        if (change.op == "delete" || d.bool("deleted") == true) {
            if (exists) Users.update({ Users.id eq id }) {
                it[active] = false
                it[deletedAt] = VenueClock.now()
            }
            GrantsRepo.applyStaffOverrides(id, buildJsonObject {})
            revokeAccess(id) // a deleted staff's live session/devices must not outlive them
            StaffTotp.deleteWhere { StaffTotp.userId eq id } // and re-enroll 2FA if ever restored
            return
        }
        val name = d.str("name") ?: id
        val role = d.str("role") ?: "SERVER"
        val pinHash = d.str("pinHash")
        val active = d.bool("active") ?: true
        val lang = d.str("languageCode") ?: "en"
        val cal = d.str("calendar") ?: "CE"
        if (exists) {
            Users.update({ Users.id eq id }) {
                it[Users.name] = name
                it[Users.role] = role
                pinHash?.let { h -> it[pin] = h } // never wipe a live PIN if the snapshot omits it
                it[Users.active] = active
                // languageCode/calendar are store-local display preferences (PATCH
                // /me/preferences); the cloud snapshot only ever carries the creation
                // defaults, so applying them here would reset a staff member's language
                // on every portal edit. Insert-only, like the PIN rule above.
                it[deletedAt] = null
            }
        } else {
            Users.insert {
                it[Users.id] = id
                it[Users.name] = name
                it[Users.role] = role
                it[pin] = pinHash ?: "" // no hash yet → can't log in until one arrives
                it[Users.active] = active
                it[languageCode] = lang
                it[calendar] = cal
            }
        }
        GrantsRepo.applyStaffOverrides(id, d["overrides"] as? JsonObject ?: buildJsonObject {})
        if (!active) revokeAccess(id) // deactivation ends the live session + device trust too
    }

    /** Revoke a staff member's live session AND trusted 2FA devices so a cloud
     *  deactivate/delete takes effect at once — a reactivated staff must clear TOTP
     *  afresh rather than slide back in PIN-only on a pre-deactivation device. */
    private fun revokeAccess(userId: String) {
        Sessions.update({ (Sessions.userId eq userId) and Sessions.revokedAt.isNull() }) {
            it[revokedAt] = VenueClock.now()
        }
        TrustedDevices.deleteWhere { TrustedDevices.userId eq userId }
    }

    // --- shared ---

    /** §2 echo tag: origin:"cloud" tells the cloud to store but not re-distribute. */
    private fun writeCloudEcho(
        eventType: String, aggregateType: String, aggregateId: String,
        build: JsonObjectBuilder.() -> Unit,
    ) {
        Outbox.write(eventType, aggregateType, aggregateId, buildJsonObject {
            build()
            put("origin", "cloud")
        })
    }

    private fun stateLong(key: String): Long? = transaction { SyncState.get(key)?.toLongOrNull() }

    private fun setState(key: String, value: String) = transaction { SyncState.set(key, value) }
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
