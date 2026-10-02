package dev.dwhipstock.pos

import dev.dwhipstock.pos.api.CatalogOps
import dev.dwhipstock.pos.api.ItemPatchRequest
import dev.dwhipstock.pos.base.ItemSchedules
import dev.dwhipstock.pos.db.Migrations
import dev.dwhipstock.pos.db.initDatabase
import dev.dwhipstock.pos.sdk.MenuSpecials
import dev.dwhipstock.pos.sync.Hlc
import dev.dwhipstock.pos.sync.MenuClock
import dev.dwhipstock.pos.sync.MenuFields
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Menu specials in two-way menu sync (CONTRACT §10 "Specials"): the store's
 * edits go up stamped in the canonical form, the portal's come down and land
 * through the menu code, the later write wins either way, and an upgraded
 * store's empty values never beat a portal edit.
 */
class MenuSpecialsSyncTest {
    private val happy = """[{"days":["mon","tue","wed","thu","fri"],"from":"16:00","to":"18:00","prices":{"lantern-lager:pint":500}}]"""

    private fun freshDb() {
        initDatabase(Files.createTempDirectory("pos-specials-sync").resolve("pos.db").toString())
        dev.dwhipstock.pos.customers.copperlantern.CopperLanternSeed.seedIfEmpty(
            dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue.VIEUX_PORT)
        transaction { dev.dwhipstock.pos.api.writeChunkedCatalogSnapshot() }
    }

    private fun schedule(id: String) = transaction { ItemSchedules.of(id) }
    private fun reg(id: String, f: String) = transaction { MenuClock.regs(MenuFields.ITEM, id)[f] }

    @Test
    fun `a tablet edit goes up stamped in the canonical form`() {
        freshDb()
        val before = lastOutboxId()
        CatalogOps.patchItem("lantern-lager", ItemPatchRequest(
            availableDays = listOf("sat", "fri"),
            specials = listOf(MenuSpecials.Special(listOf("fri", "mon", "tue", "wed", "thu"), "16:00", "18:00", mapOf("lantern-lager:pint" to 500L)))))
        val (type, payload) = outboxSince(before).single()
        assertEquals("item.updated", type)
        val item = payload["item"]!!.jsonObject
        assertEquals("""["fri","sat"]""", item["availableDays"].toString())
        assertEquals(happy, item["specials"].toString())
        val clock = item["clock"]!!.jsonObject
        assertTrue(clock["specials"]!!.jsonPrimitive.content.isNotEmpty())
        assertTrue(clock["availableDays"]!!.jsonPrimitive.content.isNotEmpty())
        // a later name edit leaves the specials' stamp alone (field-level)
        val stamp = reg("lantern-lager", "specials")!!.hlc
        CatalogOps.patchItem("lantern-lager", ItemPatchRequest(nameEn = "Lantern Lager"))
        assertEquals(stamp, reg("lantern-lager", "specials")!!.hlc)
        // clearing goes up as the key left out (= null)
        CatalogOps.patchItem("lantern-lager", ItemPatchRequest(specials = emptyList()))
        val cleared = outboxSince(before).last().second["item"]!!.jsonObject
        assertNull(cleared["specials"])
        assertEquals("null", reg("lantern-lager", "specials")!!.value)
    }

    @Test
    fun `a portal edit comes down through the menu code and is not echoed`() {
        freshDb()
        val before = lastOutboxId()
        CloudMenu.apply(CloudMenu.item("lantern-lager", fields = mapOf(
            "availableDays" to Json.parseToJsonElement("""["fri","sat"]"""),
            "specials" to Json.parseToJsonElement(happy))))
        assertEquals(listOf("fri", "sat"), schedule("lantern-lager").availableDays)
        assertEquals(mapOf("lantern-lager:pint" to 500L), schedule("lantern-lager").specials.single().prices)
        // nothing applied from the cloud goes back up
        assertTrue(outboxSince(before).none { it.first.startsWith("item.") })
        // a replay changes nothing
        CloudMenu.apply(CloudMenu.item("lantern-lager"))
        assertEquals(listOf("fri", "sat"), schedule("lantern-lager").availableDays)
        // the portal clears them: null
        CloudMenu.apply(CloudMenu.item("lantern-lager", fields = mapOf("availableDays" to JsonNull, "specials" to JsonNull)))
        assertTrue(schedule("lantern-lager").isEmpty)
    }

    @Test
    fun `keys in another order (the cloud's JSONB) are not an edit`() {
        freshDb()
        val before = lastOutboxId()
        CloudMenu.apply(CloudMenu.item("lantern-lager", fields = mapOf("specials" to Json.parseToJsonElement(
            """[{"prices":{"lantern-lager:pint":500},"to":"18:00","from":"16:00","days":["mon","tue","wed","thu","fri"]}]"""))))
        assertEquals(happy, reg("lantern-lager", "specials")!!.value)
        assertTrue(outboxSince(before).none { it.first.startsWith("item.") })
    }

    @Test
    fun `the later write wins both ways`() {
        freshDb()
        // the tablet sets happy hour now; an older portal edit loses
        CatalogOps.patchItem("lantern-lager", ItemPatchRequest(specials = MenuSpecials.specialsOf(Json.parseToJsonElement(happy))))
        CloudMenu.apply(CloudMenu.item("lantern-lager", fields = mapOf("specials" to JsonNull), stamp = CloudMenu.stamp(-60_000)))
        assertEquals(1, schedule("lantern-lager").specials.size)
        // a newer portal edit wins
        val tue = """[{"days":["tue"],"prices":{"lantern-lager:pint":600}}]"""
        CloudMenu.apply(CloudMenu.item("lantern-lager", fields = mapOf("specials" to Json.parseToJsonElement(tue)), stamp = CloudMenu.stamp(60_000)))
        assertEquals(listOf("tue"), schedule("lantern-lager").specials.single().days)
        // and the portal's days and the tablet's specials, edited at once, both land
        CatalogOps.patchItem("lantern-lager", ItemPatchRequest(specials = emptyList()))
        CloudMenu.apply(CloudMenu.item("lantern-lager", fields = mapOf("availableDays" to Json.parseToJsonElement("""["sun"]""")), stamp = CloudMenu.stamp(120_000)))
        assertEquals(MenuSpecials.Schedule(listOf("sun"), emptyList()), schedule("lantern-lager"))
    }

    @Test
    fun `a portal special for a size this store never had is dropped and the store's state goes back up`() {
        freshDb()
        val before = lastOutboxId()
        CloudMenu.apply(CloudMenu.item("lantern-lager", fields = mapOf("specials" to Json.parseToJsonElement(
            """[{"days":["tue"],"prices":{"lantern-lager:ghost":100,"lantern-lager:pint":600}},{"days":["wed"],"prices":{"nope":1}}]"""))))
        assertEquals(listOf(mapOf("lantern-lager:pint" to 600L)), schedule("lantern-lager").specials.map { it.prices })
        // the store's own (corrected) state is stamped fresh and sent, so both sides agree
        val up = outboxSince(before).filter { it.first == "item.updated" }
        assertEquals("""[{"days":["tue"],"prices":{"lantern-lager:pint":600}}]""", up.single().second["item"]!!.jsonObject["specials"].toString())
    }

    @Test
    fun `a portal create with specials lands whole`() {
        freshDb()
        val stamp = CloudMenu.stamp()
        val data = Json.parseToJsonElement("""{"id":"tacos-x1","nameFr":"Tacos","nameEn":"Tacos","descriptionFr":"","descriptionEn":"",
            "categoryId":"mains-salads","abbrev":"TA","isAlcohol":false,"active":true,"deleted":false,"names":{},
            "availableDays":["tue"],"specials":[{"days":["tue"],"label":"Taco Tuesday","prices":{"tacos-x1:regular":300}}],
            "clock":{"nameFr":"$stamp","nameEn":"$stamp","descriptionFr":"$stamp","descriptionEn":"$stamp","categoryId":"$stamp","abbrev":"$stamp",
              "isAlcohol":"$stamp","active":"$stamp","deleted":"$stamp","availableDays":"$stamp","specials":"$stamp"},
            "variants":[{"id":"tacos-x1:regular","labelFr":"Standard","labelEn":"Regular","priceCents":450,"sortOrder":0,"deleted":false,"names":{},
              "clock":{"labelFr":"$stamp","labelEn":"$stamp","priceCents":"$stamp","sortOrder":"$stamp","deleted":"$stamp"}}]}""").jsonObject
        CloudMenu.apply(dev.dwhipstock.pos.sync.MenuChange(9_000, MenuFields.ITEM, "tacos-x1", data))
        assertEquals(MenuSpecials.Schedule(listOf("tue"), listOf(MenuSpecials.Special(listOf("tue"), prices = mapOf("tacos-x1:regular" to 300L), label = "Taco Tuesday"))),
            schedule("tacos-x1"))
    }

    @Test
    fun `an upgraded store baselines the new fields so a portal edit made before still wins`() {
        val path = Files.createTempDirectory("pos-specials-upgrade").resolve("pos.db").toString()
        val db = Database.connect("jdbc:sqlite:$path", driver = "org.sqlite.JDBC")
        Migrations.run(db, through = 63)
        transaction(db) { exec("INSERT INTO categories (id, sort_order, name_fr, name_en) VALUES ('c', 0, 'C', 'C')") }
        transaction(db) { exec("INSERT INTO items (id, name_fr, name_en, category_id, abbrev) VALUES ('old', 'Vieux', 'Old', 'c', 'OL')") }
        Migrations.run(db)
        transaction(db) {
            val r = MenuClock.regs(MenuFields.ITEM, "old")
            assertEquals(Hlc.LEGACY, r["specials"]!!.hlc)
            assertEquals("null", r["availableDays"]!!.value)
        }
    }
}
