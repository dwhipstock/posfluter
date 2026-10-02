package dev.dwhipstock.pos

import dev.dwhipstock.pos.db.SyncState
import dev.dwhipstock.pos.db.initDatabase
import dev.dwhipstock.pos.restaurant.Checks
import dev.dwhipstock.pos.restaurant.DiningTables
import dev.dwhipstock.pos.restaurant.FloorObjects
import dev.dwhipstock.pos.restaurant.Zones
import dev.dwhipstock.pos.sync.Hlc
import dev.dwhipstock.pos.sync.MenuChange
import dev.dwhipstock.pos.sync.MenuClock
import dev.dwhipstock.pos.sync.MenuPage
import dev.dwhipstock.pos.sync.MenuSync
import dev.dwhipstock.pos.sync.RoomClock
import dev.dwhipstock.pos.sync.RoomFields
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two-way room sync, the store's side (CONTRACT §11): the floor goes up as
 * stamped `floor.snapshot` events (first a full baseline copy, then only what
 * changed); the portal's room changes come down the menu feed and are merged
 * per field (last write wins, deletes as tombstones); nothing applied from the
 * cloud echoes back; a table with an open bill is never moved, reshaped or
 * removed from the portal — the store's state is re-stamped and wins.
 */
class RoomSyncStoreTest {
    private var seq = 0L
    private var counter = 0

    private fun freshDb() {
        initDatabase(Files.createTempDirectory("pos-roomsync").resolve("pos.db").toString())
        dev.dwhipstock.pos.customers.copperlantern.CopperLanternSeed.seedIfEmpty(
            dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue.VIEUX_PORT)
        RoomClock.invalidate()
    }

    private fun cloudStamp(deltaMs: Long = 0) = "%013d-%04d-cloud".format(System.currentTimeMillis() + deltaMs, counter++ % 10000)

    private fun reconcile(): Int = transaction { RoomClock.reconcile() }

    private fun floorEvents(after: Int) = outboxSince(after).filter { it.first == "floor.snapshot" }.map { it.second }

    private fun things(events: List<JsonObject>, key: String) = events.flatMap { it[key]!!.jsonArray.map { e -> e.jsonObject } }

    private fun table(id: String) = transaction { DiningTables.selectAll().where { DiningTables.id eq id }.firstOrNull() }

    /** The thing as the store last told the cloud (its registers), with [fields] set by a portal edit at [stamp]. */
    private fun change(entity: String, id: String, fields: Map<String, JsonElement>, stamp: String = cloudStamp()): MenuChange {
        val regs = transaction { MenuClock.regs(entity, id) }
        val data = LinkedHashMap<String, JsonElement>()
        val clock = LinkedHashMap<String, JsonElement>()
        val names = LinkedHashMap<String, JsonElement>()
        data["id"] = JsonPrimitive(id)
        for ((f, r) in regs) {
            val v = kotlinx.serialization.json.Json.parseToJsonElement(r.value)
            if (f.startsWith("names.")) names[f.removePrefix("names.")] = v else data[f] = v
            clock[f] = JsonPrimitive(r.hlc)
        }
        for ((f, v) in fields) {
            if (f.startsWith("names.")) names[f.removePrefix("names.")] = v else data[f] = v
            clock[f] = JsonPrimitive(stamp)
        }
        data["names"] = JsonObject(names)
        data["clock"] = JsonObject(clock)
        return MenuChange(++seq, entity, id, JsonObject(data))
    }

    private fun apply(vararg changes: MenuChange) =
        MenuSync.applyPage(MenuPage(changes.maxOf { it.seq }, System.currentTimeMillis(), changes.toList()))

    private fun n(v: Int) = JsonPrimitive(v)
    private fun s(v: String) = JsonPrimitive(v)
    private fun b(v: Boolean) = JsonPrimitive(v)

    private fun openBillOn(tableId: String) = transaction {
        Checks.insertAndGetId {
            it[Checks.tableId] = tableId
            it[status] = "OPEN"
            it[openedBy] = "u1"
            it[openedAt] = java.time.Instant.now()
        }.value
    }

    @Test
    fun theFirstReconcileSendsTheWholeFloorAsABaselineThenOnlyChanges() {
        freshDb()
        val before = lastOutboxId()
        assertTrue(reconcile() > 0)
        val first = floorEvents(before)
        assertTrue(first.isNotEmpty())
        val rooms = things(first, "rooms")
        val tables = things(first, "tables")
        assertTrue(rooms.any { it["id"]!!.jsonPrimitive.content == "upper" })
        val t5 = tables.first { it["id"]!!.jsonPrimitive.content == "t5" }
        assertEquals("U-1", t5["label"]!!.jsonPrimitive.content)
        // baseline: "before sync" stamps, so the portal's first edit wins over them
        assertEquals(Hlc.LEGACY, t5["clock"]!!.jsonObject["x"]!!.jsonPrimitive.content)
        // the sub-table link travels
        assertEquals("t5", tables.first { it["id"]!!.jsonPrimitive.content == "t5-5" }["parentTableId"]!!.jsonPrimitive.content)
        // off-floor sale locations (carry-out) are not rooms
        assertFalse(rooms.any { dev.dwhipstock.pos.orders.SaleLocations.isOffFloor(it["id"]!!.jsonPrimitive.content) })

        // nothing changed: nothing goes up
        val mid = lastOutboxId()
        assertEquals(0, reconcile())
        assertTrue(floorEvents(mid).isEmpty())

        // a tablet edit (any code path: here a raw row write) goes up alone, freshly stamped
        transaction { DiningTables.update({ DiningTables.id eq "t6" }) { it[x] = 333 } }
        RoomClock.invalidate()
        val edit = lastOutboxId()
        assertEquals(1, reconcile())
        val sent = things(floorEvents(edit), "tables").single()
        assertEquals("t6", sent["id"]!!.jsonPrimitive.content)
        assertEquals(333, sent["x"]!!.jsonPrimitive.content.toInt())
        assertTrue(sent["clock"]!!.jsonObject["x"]!!.jsonPrimitive.content > cloudStamp(-60_000).take(13))
        assertEquals(Hlc.LEGACY, sent["clock"]!!.jsonObject["y"]!!.jsonPrimitive.content)
    }

    @Test
    fun aPortalEditLandsThroughTheFeedAndDoesNotEcho() {
        freshDb()
        reconcile()
        val before = lastOutboxId()
        apply(change(RoomFields.TABLE, "t6", mapOf("x" to n(600), "shape" to s("SQUARE"), "seats" to n(4))))
        val t6 = table("t6")!!
        assertEquals(600, t6[DiningTables.x])
        assertEquals("SQUARE", t6[DiningTables.shape])
        assertEquals(4, t6[DiningTables.seats])
        // the store's state equals the merged registers: nothing goes back up
        assertTrue(floorEvents(before).isEmpty(), "applied portal edits must not echo")
        // a replay changes nothing
        apply(change(RoomFields.TABLE, "t6", emptyMap()))
        assertEquals(600, table("t6")!![DiningTables.x])
        assertTrue(floorEvents(before).isEmpty())
    }

    @Test
    fun theLaterWriteWinsBothWays() {
        freshDb()
        reconcile()
        // the tablet moves t6 now; a portal edit stamped a minute ago arrives later: it loses
        transaction { DiningTables.update({ DiningTables.id eq "t6" }) { it[x] = 111 } }
        RoomClock.invalidate()
        val stale = change(RoomFields.TABLE, "t6", mapOf("x" to n(900)), cloudStamp(-60_000))
        reconcile()
        apply(stale)
        assertEquals(111, table("t6")!![DiningTables.x])
        // a newer portal edit wins
        apply(change(RoomFields.TABLE, "t6", mapOf("x" to n(700)), cloudStamp(5_000)))
        assertEquals(700, table("t6")!![DiningTables.x])
    }

    @Test
    fun aPortalMadeRoomWithTablesAndObjectsIsCreatedAtTheStore() {
        freshDb()
        reconcile()
        val st = cloudStamp()
        val room = change(RoomFields.ROOM, "patio-x1", mapOf("nameEn" to s("Patio"), "nameFr" to s("Terrasse"),
            "sortOrder" to n(9), "labelPrefix" to s("P"), "deleted" to b(false), "names.es" to s("Terraza")), st)
        val table = change(RoomFields.TABLE, "patio-x1-p-1-ab", mapOf("zoneId" to s("patio-x1"), "label" to s("P-1"),
            "parentTableId" to JsonNull, "x" to n(100), "y" to n(100), "width" to n(100), "height" to n(100),
            "rotation" to n(0), "shape" to s("ROUND"), "seats" to n(4), "deleted" to b(false)), st)
        val obj = change(RoomFields.OBJECT, "patio-x1-custom-cd", mapOf("zoneId" to s("patio-x1"), "type" to s("CUSTOM"),
            "x" to n(800), "y" to n(20), "width" to n(120), "height" to n(80), "rotation" to n(0), "labelEn" to s("Jukebox"),
            "labelFr" to s("Juke-box"), "icon" to s("music"), "shape" to s("ROUND"), "deleted" to b(false)), st)
        val before = lastOutboxId()
        // the table comes before its room in this page: retried at the end of the page
        apply(table, obj, room)
        transaction {
            val z = Zones.selectAll().where { Zones.id eq "patio-x1" }.single()
            assertEquals("Patio", z[Zones.nameEn]); assertEquals("Terrasse", z[Zones.nameFr]); assertEquals("P", z[Zones.labelPrefix])
            assertEquals("Terraza", dev.dwhipstock.pos.base.Translations.get("zone", "patio-x1", "es"))
            assertEquals(0, MenuSync.failedCount())
            val o = FloorObjects.selectAll().where { FloorObjects.id eq "patio-x1-custom-cd" }.single()
            assertEquals("music", o[FloorObjects.icon])
        }
        assertEquals("P-1", table("patio-x1-p-1-ab")!![DiningTables.label])
        assertNotNull(table("patio-x1-p-1-ab")!![DiningTables.publicToken])
        assertTrue(floorEvents(before).isEmpty(), "a portal-made room does not echo")

        // the portal's undo: everything deleted (tombstones), the room last
        val st2 = cloudStamp(1_000)
        apply(change(RoomFields.TABLE, "patio-x1-p-1-ab", mapOf("deleted" to b(true)), st2),
            change(RoomFields.OBJECT, "patio-x1-custom-cd", mapOf("deleted" to b(true)), st2),
            change(RoomFields.ROOM, "patio-x1", mapOf("deleted" to b(true)), st2))
        transaction { assertTrue(Zones.selectAll().where { Zones.id eq "patio-x1" }.empty()) }
        assertNotNull(table("patio-x1-p-1-ab")!![DiningTables.deletedAt]) // soft delete: closed checks keep their table
        transaction { assertEquals("true", MenuClock.regs(RoomFields.ROOM, "patio-x1")["deleted"]!!.value) }
        // and nothing is re-sent: the store agrees
        assertEquals(0, reconcile())
    }

    @Test
    fun aTableWithAnOpenBillIsNeverMovedOrRemovedFromThePortal() {
        freshDb()
        reconcile()
        openBillOn("t6")
        RoomClock.invalidate()
        reconcile() // the locked list goes up
        val before = lastOutboxId()
        apply(change(RoomFields.TABLE, "t6", mapOf("x" to n(900), "shape" to s("BAR"), "seats" to n(6))))
        val t6 = table("t6")!!
        assertEquals(310, t6[DiningTables.x], "an open bill: not moved")
        assertEquals("ROUND", t6[DiningTables.shape], "an open bill: not reshaped")
        assertEquals(6, t6[DiningTables.seats], "the seats may change")
        // the store's own values go back up, freshly stamped, so they win on the portal too
        val sent = things(floorEvents(before), "tables").single { it["id"]!!.jsonPrimitive.content == "t6" }
        assertEquals(310, sent["x"]!!.jsonPrimitive.content.toInt())
        assertEquals("ROUND", sent["shape"]!!.jsonPrimitive.content)
        assertTrue(sent["clock"]!!.jsonObject["x"]!!.jsonPrimitive.content.endsWith("cloud").not())
        assertTrue(floorEvents(before).last()["locked"]!!.jsonArray.any { it.jsonPrimitive.content == "t6" })

        // a removal is refused too
        val mid = lastOutboxId()
        apply(change(RoomFields.TABLE, "t6", mapOf("deleted" to b(true)), cloudStamp(2_000)))
        assertNull(table("t6")!![DiningTables.deletedAt])
        assertTrue(things(floorEvents(mid), "tables").any { it["id"]!!.jsonPrimitive.content == "t6" && it["deleted"]!!.jsonPrimitive.content == "false" })

        // an anchor with a live sub-table is not removed either; a free table is
        apply(change(RoomFields.TABLE, "t5", mapOf("deleted" to b(true)), cloudStamp(3_000)))
        assertNull(table("t5")!![DiningTables.deletedAt])
        apply(change(RoomFields.TABLE, "b4", mapOf("deleted" to b(true)), cloudStamp(4_000)))
        assertNotNull(table("b4")!![DiningTables.deletedAt])
        // a later edit brings a deleted table back (last write wins over the tombstone)
        apply(change(RoomFields.TABLE, "b4", mapOf("seats" to n(2)), cloudStamp(5_000)))
        assertNull(table("b4")!![DiningTables.deletedAt])
        assertEquals(2, table("b4")!![DiningTables.seats])
    }

    @Test
    fun aBadPayloadIsKeptForRetryNotDropped() {
        freshDb()
        reconcile()
        apply(change(RoomFields.TABLE, "t6", mapOf("shape" to s("HEXAGON"))))
        assertEquals(1, transaction { MenuSync.failedCount() })
        assertEquals("ROUND", table("t6")!![DiningTables.shape])
        // a table in a room the store never had waits too
        apply(change(RoomFields.TABLE, "ghost-1", mapOf("zoneId" to s("nowhere"), "label" to s("N-1"), "deleted" to b(false))))
        assertNull(table("ghost-1"))
        // the refused shape is settled by the store's own (re-stamped) value; the ghost table still waits
        assertEquals(1, transaction { MenuSync.failedCount() })
        assertEquals("ROUND", table("t6")!![DiningTables.shape])
    }

    @Test
    fun aRoomThatStillHoldsTablesIsNotRemoved() {
        freshDb()
        reconcile()
        apply(change(RoomFields.ROOM, "upper", mapOf("deleted" to b(true))))
        transaction { assertFalse(Zones.selectAll().where { Zones.id eq "upper" }.empty()) }
        // the store re-stamped "not deleted": the portal gets the room back
        transaction { assertEquals("false", MenuClock.regs(RoomFields.ROOM, "upper")["deleted"]!!.value) }
    }

    @Test
    fun aResetCloudGetsTheWholeFloorAgain() {
        freshDb()
        reconcile()
        transaction { RoomClock.resendAll() }
        val before = lastOutboxId()
        assertTrue(reconcile() > 10)
        val t5 = things(floorEvents(before), "tables").first { it["id"]!!.jsonPrimitive.content == "t5" }
        assertEquals(Hlc.LEGACY, t5["clock"]!!.jsonObject["x"]!!.jsonPrimitive.content) // its own stamps, not new ones
        transaction { assertNotNull(SyncState.get(RoomClock.BASELINE_KEY)) }
        assertTrue(JsonArray(emptyList()).isEmpty())
    }
}
