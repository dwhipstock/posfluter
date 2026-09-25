package dev.dwhipstock.pos

import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.db.SyncState
import dev.dwhipstock.pos.db.initDatabase
import dev.dwhipstock.pos.sdk.Outbox
import dev.dwhipstock.pos.sdk.PhotoStore
import dev.dwhipstock.pos.base.DeviceRegistry
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.Users
import dev.dwhipstock.pos.sync.ChangesPage
import dev.dwhipstock.pos.sync.CloudChange
import dev.dwhipstock.pos.sync.CloudSync
import dev.dwhipstock.pos.sync.CloudCapabilities
import dev.dwhipstock.pos.sync.CloudTransport
import dev.dwhipstock.pos.sync.PushEvent
import dev.dwhipstock.pos.sync.PushResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fake wire for the drain/apply core; the java.net.http adapter stays untested plumbing. */
class FakeTransport : CloudTransport {
    val batches = mutableListOf<List<PushEvent>>()
    val installIds = mutableListOf<String>()
    val photoUploads = mutableListOf<String>()
    var failPushes = false
    val pages = mutableMapOf<Long, ChangesPage>()
    var caps: CloudCapabilities = CloudCapabilities(2, CloudCapabilities.INSTANT)
    var capabilityCalls = 0
    val heartbeats = mutableListOf<List<DeviceRegistry.DeviceSummary>>()

    override fun capabilities(): CloudCapabilities { capabilityCalls++; return caps }

    override fun heartbeat(
        installId: String, lanBaseUrl: String, devices: List<DeviceRegistry.DeviceSummary>,
    ): PushResult { heartbeats += devices; return PushResult(true) }

    override fun push(installId: String, events: List<PushEvent>): PushResult {
        if (failPushes) return PushResult(false, "simulated outage")
        installIds += installId
        batches += events
        return PushResult(true)
    }

    override fun fetchRevocations(since: Long): ChangesPage =
        pages[since] ?: ChangesPage(since, emptyList())

    override fun pushPhoto(itemId: String, bytes: ByteArray, contentType: String): PushResult {
        if (failPushes) return PushResult(false, "simulated outage")
        photoUploads += itemId
        return PushResult(true)
    }
}

class InMemoryPhotoStore : PhotoStore {
    val stored = mutableMapOf<String, Pair<ByteArray, String>>()
    override fun read(itemId: String): PhotoStore.StoredPhoto? =
        stored[itemId]?.let { PhotoStore.StoredPhoto(it.first, it.second, 1L) }
    override fun readScaled(itemId: String, maxWidth: Int): PhotoStore.StoredPhoto? = read(itemId)
    override fun save(itemId: String, bytes: ByteArray, contentType: String): String {
        stored[itemId] = bytes to contentType
        return "mem/$itemId"
    }
    override fun version(itemId: String): Long? = if (itemId in stored) 1L else null
}

class CloudSyncTest {

    private fun freshDb() =
        initDatabase(Files.createTempDirectory("pos-sync-test").resolve("pos.db").toString())

    private fun seedEvent(type: String, aggregateId: String, origin: String? = null) = transaction {
        Outbox.write(type, "check", aggregateId, buildJsonObject {
            put("checkId", aggregateId)
            origin?.let { put("origin", it) }
        })
    }

    private fun state(key: String): String? = transaction { SyncState.get(key) }

    @Test
    fun drainPushesInSeqOrderAdvancesHwmAndSnapshotsOnce() {
        freshDb()
        seedEvent("check.opened", "1")
        seedEvent("check.line_added", "1")
        seedEvent("check.closed", "1")

        val t = FakeTransport()
        val sync = CloudSync(t, InMemoryPhotoStore())
        sync.drainOnce()

        val events = t.batches.flatten()
        // bootstrap snapshot rides the normal drain, exactly once
        assertEquals(1, events.count { it.eventType == "catalog.snapshot" })
        val snapshot = events.first { it.eventType == "catalog.snapshot" }.payload
        // migrations pre-seed categories + items, so the bootstrap is non-empty
        assertTrue(snapshot["categories"]!!.jsonArray.isNotEmpty())
        assertTrue("items" in snapshot)
        assertEquals(events.map { it.seq }.sorted(), events.map { it.seq })
        assertTrue(events.map { it.eventType }.containsAll(
            listOf("check.opened", "check.line_added", "check.closed")))
        assertEquals(events.last().seq.toString(), state(CloudSync.PUSH_HWM))
        // one install id, minted once and persisted — the cloud pins the venue to it
        assertEquals(1, t.installIds.toSet().size)
        assertEquals(t.installIds.first(), state(CloudSync.INSTALL_ID))

        // fully drained: a second pass pushes nothing and re-snapshots nothing
        val batchesBefore = t.batches.size
        sync.drainOnce()
        assertEquals(batchesBefore, t.batches.size)
    }

    @Test
    fun reportBackfillRunsExactlyOnceAndItsEventsDrain() {
        freshDb()
        seedEvent("check.closed", "1")

        val t = FakeTransport()
        var backfillCalls = 0
        // stand-in for CheckService.backfillReportCompleteClosedEvents(): re-emit
        // a report-complete check.closed the way the real backfill would
        val sync = CloudSync(t, InMemoryPhotoStore(), reEmitReportHistory = {
            backfillCalls++
            transaction {
                Outbox.write("check.closed", "check", "1", buildJsonObject {
                    put("checkId", "1"); put("grandTotalCents", 45000); put("taxIncludedCents", 2944)
                })
            }
            1
        })

        sync.drainOnce()
        assertEquals(1, backfillCalls)
        assertEquals(2, t.batches.flatten().count { it.eventType == "check.closed" }) // thin seed + backfill
        assertTrue(state(CloudSync.REPORT_BACKFILL_SEQ) != null)

        // a second drain must not re-run the backfill (gated once per DB)
        sync.drainOnce()
        assertEquals(1, backfillCalls)
    }

    @Test
    fun failedPushRetainsRowsForRetry() {
        freshDb()
        seedEvent("check.opened", "7")
        seedEvent("check.closed", "7")

        val t = FakeTransport().apply { failPushes = true }
        val sync = CloudSync(t, InMemoryPhotoStore())
        sync.drainOnce()
        assertEquals(0, t.batches.size)
        assertEquals(null, state(CloudSync.PUSH_HWM)) // untouched on failure

        // outage over: the same rows (plus the snapshot) go out and the hwm lands
        t.failPushes = false
        sync.drainOnce()
        val events = t.batches.flatten()
        assertEquals(1, events.count { it.eventType == "catalog.snapshot" })
        assertTrue(events.any { it.eventType == "check.opened" })
        assertTrue(events.any { it.eventType == "check.closed" })
        assertEquals(events.last().seq.toString(), state(CloudSync.PUSH_HWM))

        // nothing left behind
        sync.drainOnce()
        assertEquals(events.size, t.batches.flatten().size)
    }

    @Test
    fun drainLoopsInBatchesOfTwoHundred() {
        freshDb()
        repeat(205) { seedEvent("check.opened", it.toString()) }

        val t = FakeTransport()
        CloudSync(t, InMemoryPhotoStore()).drainOnce()

        // migrations pre-seed some outbox rows (e.g. table.relabeled), so the
        // expected batch split derives from the actual row count
        val total = transaction { SyncOutbox.selectAll().count() }.toInt()
        assertEquals((total + 199) / 200, t.batches.size)
        assertTrue(t.batches.dropLast(1).all { it.size == 200 })
        val all = t.batches.flatten()
        assertEquals(total, all.size)
        assertEquals(all.map { it.seq }.sorted(), all.map { it.seq })
        assertEquals(all.last().seq.toString(), state(CloudSync.PUSH_HWM))
    }

    @Test
    fun photoSidebandUploadsAfterAckButSkipsCloudOriginEvents() {
        freshDb()
        val photoStore = InMemoryPhotoStore()
        photoStore.save("poutine", byteArrayOf(1, 2, 3), "image/jpeg")
        photoStore.save("lantern-lager", byteArrayOf(4, 5), "image/jpeg")
        transaction {
            Outbox.write("item.photo_uploaded", "item", "poutine",
                buildJsonObject { put("itemId", "poutine") })
            // cloud-originated echo — must NOT bounce back up
            Outbox.write("item.photo_uploaded", "item", "lantern-lager",
                buildJsonObject { put("itemId", "lantern-lager"); put("origin", "cloud") })
            // no binary in the store → nothing to upload
            Outbox.write("item.photo_uploaded", "item", "amber-ale",
                buildJsonObject { put("itemId", "amber-ale") })
        }

        val t = FakeTransport()
        CloudSync(t, photoStore).drainOnce()
        assertEquals(listOf("poutine"), t.photoUploads)
    }

    @Test
    fun onlyRevocationsArePulledAndCatalogOrStaffChangesAreIgnored() {
        freshDb()
        val itemsBefore = transaction { Items.selectAll().count() }
        val staffBefore = transaction { Users.selectAll().map { it[Users.name] } }
        val deviceId = DeviceRegistry.pair("Bar tablet").first
        val outboxBefore = transaction { SyncOutbox.selectAll().count() }

        val t = FakeTransport()
        val sync = CloudSync(t, InMemoryPhotoStore())
        sync.pullOnce()
        assertEquals(null, state(CloudSync.CHANGES_CURSOR)) // empty page: cursor untouched

        // an older cloud may still serve catalog/staff rows: they must not land
        t.pages[0L] = ChangesPage(5, listOf(
            CloudChange(3, "item", "upsert", buildJsonObject {
                put("id", "cloud-only-item"); put("nameFr", "x"); put("nameEn", "x"); put("categoryId", "mains-salads")
            }),
            CloudChange(4, "staff", "upsert", buildJsonObject {
                put("id", "intruder"); put("name", "Intruder"); put("role", "MANAGER"); put("pinHash", "\$2a\$10\$x")
            }),
            CloudChange(5, "device_revocation", "upsert", buildJsonObject { put("deviceId", deviceId) }),
        ))
        sync.pullOnce()
        assertEquals("5", state(CloudSync.CHANGES_CURSOR))
        assertEquals(itemsBefore, transaction { Items.selectAll().count() })
        assertEquals(staffBefore, transaction { Users.selectAll().map { it[Users.name] } })
        // the revocation applied
        assertTrue(DeviceRegistry.summaries().single { it.id == deviceId }.revoked)
        // and nothing was echoed back into the outbox
        assertEquals(outboxBefore, transaction { SyncOutbox.selectAll().count() })
    }

    @Test
    fun firstDrainPushesOneStaffSnapshotWithoutPinHashes() {
        freshDb()
        dev.dwhipstock.pos.customers.copperlantern.CopperLanternSeed.seedIfEmpty()
        val t = FakeTransport()
        val sync = CloudSync(t, InMemoryPhotoStore())
        sync.drainOnce()
        val snapshots = t.batches.flatten().filter { it.eventType == "staff.snapshot" }
        assertEquals(1, snapshots.size)
        val payload = snapshots.single().payload
        val ids = payload["staff"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue("manager" in ids && "server1" in ids)
        assertTrue("MANAGER" in payload["roles"]!!.jsonObject)
        assertTrue("\$2a\$" !in payload.toString(), "PIN hashes must never leave the tablet")

        sync.drainOnce()
        assertEquals(1, t.batches.flatten().count { it.eventType == "staff.snapshot" })
    }

    @Test
    fun anOldCloudGetsNoInstantsUntilItConfirmsThemAndNothingIsDropped() {
        freshDb()
        seedEvent("check.closed", "1")
        DeviceRegistry.pair("Bar tablet")
        val t = FakeTransport()
        t.caps = CloudCapabilities.LEGACY // cloud predates the handshake (404)
        val sync = CloudSync(t, InMemoryPhotoStore(), lanBaseUrl = { "http://192.168.1.2:8080" })

        sync.tick()
        sync.tick()
        // held: nothing pushed, the HWM untouched, the device registry not mirrored
        assertTrue(t.batches.isEmpty())
        assertEquals(null, state(CloudSync.PUSH_HWM))
        assertEquals(2, t.heartbeats.size)
        assertTrue(t.heartbeats.all { it.isEmpty() })
        // one handshake per tick while unconfirmed
        assertEquals(2, t.capabilityCalls)

        // the cloud is upgraded: the held outbox drains in full on the next tick
        t.caps = CloudCapabilities(2, CloudCapabilities.INSTANT)
        sync.tick()
        val pushed = t.batches.flatten().map { it.eventType }
        assertTrue("check.closed" in pushed && "catalog.snapshot" in pushed)
        assertEquals(t.batches.flatten().last().seq.toString(), state(CloudSync.PUSH_HWM))
        assertEquals(1, t.heartbeats.last().size)
        // confirmed once per process — no further handshakes
        sync.tick()
        assertEquals(3, t.capabilityCalls)
    }

    @Test
    fun aFailedHandshakeHoldsPushesAndRetriesNextTick() {
        freshDb()
        seedEvent("check.closed", "1")
        var offline = true
        val t = object : CloudTransport by FakeTransport() {
            val inner = FakeTransport()
            override fun capabilities(): CloudCapabilities =
                if (offline) throw java.io.IOException("no route to host") else inner.caps
            override fun push(installId: String, events: List<PushEvent>) = inner.push(installId, events)
        }
        val sync = CloudSync(t, InMemoryPhotoStore())
        sync.tick()
        assertTrue(t.inner.batches.isEmpty())
        offline = false
        sync.tick()
        assertTrue(t.inner.batches.flatten().any { it.eventType == "check.closed" })
    }
}
