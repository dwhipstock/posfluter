package dev.dwhipstock.pos

import dev.dwhipstock.pos.api.CatalogOps
import dev.dwhipstock.pos.api.ItemPatchRequest
import dev.dwhipstock.pos.base.ItemSchedules
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternExpressSeed
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternSeed
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternSpecials
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue
import dev.dwhipstock.pos.db.SyncState
import dev.dwhipstock.pos.db.initDatabase
import dev.dwhipstock.pos.sdk.MenuSpecials
import dev.dwhipstock.pos.sync.CloudSync
import dev.dwhipstock.pos.sync.Hlc
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Express counter's demo specials (a Tuesday Double Cheeseburger, weekday
 * happy hour on the lager and the Pinot Noir) on a tablet that is linked to
 * the cloud: it starts with seedMode=none (its menu comes from the portal),
 * and the specials used to be added only inside the seed branch, so the
 * Express tablet never got them. Glenwood (a Windows store, seeded) did.
 */
class ExpressSpecialsSeedTest {

    private val weekdays = listOf("mon", "tue", "wed", "thu", "fri")
    private val burger = MenuSpecials.Special(listOf("tue"), null, null, mapOf("double-cheeseburger:regular" to 995L))
    private val lager = MenuSpecials.Special(weekdays, "16:00", "18:00", mapOf("lantern-lager:16oz" to 500L))
    private val pinot = MenuSpecials.Special(weekdays, "16:00", "18:00", mapOf("pinot-noir:glass" to 750L))

    private fun specials(id: String) = transaction { ItemSchedules.of(id).specials }

    /** As migration 064 left an existing store: the two new item fields "set before sync" (empty stamp). */
    private fun asUpgradedTo064() = transaction {
        exec("DELETE FROM menu_sync_clocks WHERE entity = 'item' AND field IN ('availableDays', 'specials')")
        exec("INSERT OR IGNORE INTO menu_sync_clocks (entity, entity_id, field, value, hlc) SELECT 'item', id, 'availableDays', 'null', '' FROM items")
        exec("INSERT OR IGNORE INTO menu_sync_clocks (entity, entity_id, field, value, hlc) SELECT 'item', id, 'specials', 'null', '' FROM items")
    }

    /** The menu events queued for the cloud after [after], by item: the specials they carry and their stamp. */
    private fun specialsSentUp(after: Int): Map<String, Pair<List<JsonObject>, String>> =
        outboxSince(after).filter { it.first.startsWith("item.") }.mapNotNull { (_, p) ->
            val item = p["item"]?.jsonObject ?: return@mapNotNull null
            val sp = item["specials"]?.takeIf { it is kotlinx.serialization.json.JsonArray }?.jsonArray?.map { it.jsonObject }
                ?: return@mapNotNull null
            item["id"]!!.jsonPrimitive.content to (sp to item["clock"]!!.jsonObject["specials"]!!.jsonPrimitive.content)
        }.toMap()

    @Test
    fun `an Express tablet linked to the cloud, upgraded to 064, gets its specials on startup and sends them up`() {
        val db = File(Files.createTempDirectory("pos-express-specials").toFile(), "pos.db").path
        // yesterday's build: the Express store seeded, synced to the cloud before
        testApplication {
            application { module(dbPath = db, venueId = "express", physicalPrinterEnabled = false, demoSpecials = false) }
            startApplication()
        }
        transaction {
            SyncState.set(CloudSync.INSTALL_ID, "express-install")
            dev.dwhipstock.pos.api.writeChunkedCatalogSnapshot()
        }
        asUpgradedTo064()
        val before = lastOutboxId()
        // today's install: the tablet is linked to the cloud, so its store starts with seedMode=none
        testApplication {
            application { module(dbPath = db, venueId = "express", seedMode = "none", physicalPrinterEnabled = false, demoSpecials = true) }
            startApplication()
        }
        assertEquals(listOf(burger), specials("double-cheeseburger"))
        assertEquals(listOf(lager), specials("lantern-lager"))
        assertEquals(listOf(pinot), specials("pinot-noir"))
        assertEquals("1", transaction { SyncState.get(CopperLanternSpecials.SEEDED_KEY) })
        // the menu is the store's own (never wiped: it has synced before)
        assertTrue(transaction { Items.selectAll().count() } > 10)

        // they go up like a manager's edit: a fresh stamp beats the cloud's "before sync" null
        val up = specialsSentUp(before)
        assertEquals(setOf("double-cheeseburger", "lantern-lager", "pinot-noir"), up.keys)
        up.forEach { (id, v) ->
            assertEquals(1, v.first.size, id)
            assertNotEquals(Hlc.LEGACY, v.second, id)
            assertTrue(Hlc.physical(v.second)!! > System.currentTimeMillis() - 60_000, id)
        }
        assertEquals(500L, up.getValue("lantern-lager").first.single()["prices"]!!.jsonObject["lantern-lager:16oz"]!!.jsonPrimitive.long)
        assertEquals(750L, up.getValue("pinot-noir").first.single()["prices"]!!.jsonObject["pinot-noir:glass"]!!.jsonPrimitive.long)
        assertEquals(995L, up.getValue("double-cheeseburger").first.single()["prices"]!!.jsonObject["double-cheeseburger:regular"]!!.jsonPrimitive.long)

        // and the sync loop pushes them to the cloud
        val t = FakeTransport()
        CloudSync(t, InMemoryPhotoStore()).drainOnce(capable = true)
        val pushed = t.batches.flatten().filter { it.eventType.startsWith("item.") }
            .mapNotNull { e -> e.payload["item"]?.jsonObject?.takeIf { it["specials"] is kotlinx.serialization.json.JsonArray } }
            .map { it["id"]!!.jsonPrimitive.content }.toSet()
        assertTrue(pushed.containsAll(setOf("double-cheeseburger", "lantern-lager", "pinot-noir")), pushed.toString())

        // a second boot changes nothing: no duplicates, nothing new sent up
        val afterFirst = lastOutboxId()
        testApplication {
            application { module(dbPath = db, venueId = "express", seedMode = "none", physicalPrinterEnabled = false, demoSpecials = true) }
            startApplication()
        }
        assertEquals(listOf(lager), specials("lantern-lager"))
        assertEquals(emptyMap(), specialsSentUp(afterFirst))
    }

    @Test
    fun `a store that set the v1 flag without its specials gets only what is missing, never over a manager's`() {
        initDatabase(Files.createTempDirectory("pos-express-v1").resolve("pos.db").toString())
        CopperLanternExpressSeed.seedIfEmpty()
        asUpgradedTo064()
        // a manager put their own special on the burger, and cleared the lager's (a real stamp, no special)
        val own = MenuSpecials.Special(listOf("wed"), null, null, mapOf("double-cheeseburger:regular" to 1095L))
        CatalogOps.patchItem("double-cheeseburger", ItemPatchRequest(specials = listOf(own)))
        CatalogOps.patchItem("lantern-lager", ItemPatchRequest(specials = listOf(lager)))
        CatalogOps.patchItem("lantern-lager", ItemPatchRequest(specials = emptyList()))
        transaction { SyncState.set(CopperLanternSpecials.LEGACY_KEY, "1") }

        assertTrue(CopperLanternSpecials.seed(CopperLanternVenue.EXPRESS, enabled = true))
        assertEquals(listOf(own), specials("double-cheeseburger"))
        assertEquals(emptyList(), specials("lantern-lager"))
        assertEquals(listOf(pinot), specials("pinot-noir"))
        // twice: the same
        transaction { exec("DELETE FROM sync_state WHERE key = '${CopperLanternSpecials.SEEDED_KEY}'") }
        CopperLanternSpecials.seed(CopperLanternVenue.EXPRESS, enabled = true)
        assertEquals(listOf(pinot), specials("pinot-noir"))
        assertEquals(listOf(own), specials("double-cheeseburger"))
    }

    @Test
    fun `Glenwood, already seeded by v1, is left exactly as it is`() {
        initDatabase(Files.createTempDirectory("pos-glenwood").resolve("pos.db").toString())
        CopperLanternSeed.seedIfEmpty(CopperLanternVenue.VIEUX_PORT)
        asUpgradedTo064()
        CopperLanternSpecials.seed(CopperLanternVenue.VIEUX_PORT, enabled = true)
        // as today's build left it: the v1 flag, not v2
        transaction {
            exec("DELETE FROM sync_state WHERE key = '${CopperLanternSpecials.SEEDED_KEY}'")
            SyncState.set(CopperLanternSpecials.LEGACY_KEY, "1")
        }
        val schedules = transaction { ItemSchedules.all() }
        val items = transaction { Items.selectAll().count() }
        val before = lastOutboxId()

        assertTrue(CopperLanternSpecials.seed(CopperLanternVenue.VIEUX_PORT, enabled = true))
        assertEquals(schedules, transaction { ItemSchedules.all() })
        assertEquals(items, transaction { Items.selectAll().count() })
        assertEquals(1, transaction { Items.selectAll().count { it[Items.id] == "prime-rib" } })
        assertEquals(emptyList(), outboxSince(before).filter { it.first.startsWith("item.") })
        assertEquals("1", transaction { SyncState.get(CopperLanternSpecials.SEEDED_KEY) })
        // the pub's own specials, not Express's
        assertEquals(500L, schedules.getValue("lantern-lager").specials.single().prices["lantern-lager:pint"])
    }

    @Test
    fun `a store without the demo menu yet tries again after the next menu pull`() {
        initDatabase(Files.createTempDirectory("pos-express-empty").resolve("pos.db").toString())
        transaction { exec("DELETE FROM item_variants"); exec("DELETE FROM items") }
        assertEquals(false, CopperLanternSpecials.seed(CopperLanternVenue.EXPRESS, enabled = true))
        var calls = 0
        val t = MenuFeedTransport()
        val sync = CloudSync(t, InMemoryPhotoStore(), afterMenuPull = { calls++ })
        sync.pullMenuOnce()
        assertEquals(1, calls)
        // offline: nothing caught up, nothing tried
        t.offline = true
        sync.pullMenuOnce()
        assertEquals(1, calls)
    }
}
