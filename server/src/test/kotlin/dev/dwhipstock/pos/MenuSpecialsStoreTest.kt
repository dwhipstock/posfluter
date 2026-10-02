package dev.dwhipstock.pos

import dev.dwhipstock.pos.api.CatalogOps
import dev.dwhipstock.pos.api.ItemPatchRequest
import dev.dwhipstock.pos.base.ItemSchedules
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternSpecials
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.restaurant.LineRejectedException
import dev.dwhipstock.pos.sdk.MenuSpecials
import dev.dwhipstock.pos.sdk.VenueClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Menu specials in a seeded Copper Lantern pub (the demo's own specials,
 * [CopperLanternSpecials]): prices by business day and time, day-only dishes,
 * the price kept on the line, and the receipt.
 */
class MenuSpecialsStoreTest {

    @AfterTest
    fun resetClock() { ItemSchedules.clock = Clock.systemUTC() }

    /** The specials' clock at a venue wall time (Raleigh). */
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) {
        ItemSchedules.clock = Clock.fixed(VenueClock.fromLocal(LocalDateTime.of(y, mo, d, h, mi)), ZoneOffset.UTC)
    }

    private fun store(): KitchenFixture = KitchenFixture().also { CopperLanternSpecials.seed(CopperLanternVenue.PLATEAU, enabled = true) }

    private val tables = ArrayDeque(listOf("t1", "t2", "t4", "t5", "t6", "t7", "t8", "b1", "b2", "b3", "b4", "u3", "u4", "u5",
        "o3", "o4", "l9", "l10", "l11", "l12", "l13") + (1..11).map { "s$it" })

    /** What one of [variant] rings at now (on a fresh check). */
    private fun price(f: KitchenFixture, item: String, variant: String): Long =
        f.checks.addLine(f.open(tables.removeFirst()), item, variant, 1, null).lines.single().unitPriceCents

    @Test
    fun `happy hour is 4 to 6 pm on weekdays and 6_01 pm is the menu price`() {
        val f = store()
        at(2026, 10, 5, 15, 59); assertEquals(750, price(f, "lantern-lager", "lantern-lager:pint"))
        at(2026, 10, 5, 16, 0); assertEquals(500, price(f, "lantern-lager", "lantern-lager:pint"))
        at(2026, 10, 5, 17, 59); assertEquals(500, price(f, "lantern-lager", "lantern-lager:pint"))
        at(2026, 10, 5, 18, 0); assertEquals(750, price(f, "lantern-lager", "lantern-lager:pint"))
        at(2026, 10, 5, 18, 1); assertEquals(750, price(f, "lantern-lager", "lantern-lager:pint"))
        // a pitcher has no happy-hour price; Saturday has no happy hour
        at(2026, 10, 5, 17); assertEquals(2025, price(f, "lantern-lager", "lantern-lager:pitcher"))
        at(2026, 10, 3, 17); assertEquals(750, price(f, "lantern-lager", "lantern-lager:pint"))
        // the house red's glass, not its bottle
        at(2026, 10, 9, 16, 30)
        assertEquals(700, price(f, "cab-merlot", "cab-merlot:glass"))
        assertEquals(3950, price(f, "cab-merlot", "cab-merlot:bottle"))
    }

    @Test
    fun `the price is decided when the line is rung and kept`() {
        val f = store()
        at(2026, 10, 5, 17, 30)
        val check = f.open()
        val line = f.checks.addLine(check, "lantern-lager", "lantern-lager:pint", 2, null).lines.single()
        assertEquals(500, line.unitPriceCents)
        assertEquals(750, line.regularUnitPriceCents)
        assertEquals(MenuSpecials.Tag(listOf("mon", "tue", "wed", "thu", "fri"), "16:00", "18:00"), line.special)
        // happy hour ends: the line keeps its price, more of it too
        at(2026, 10, 5, 18, 30)
        assertEquals(500, f.checks.setLineQty(check, line.id, 3).lines.single().unitPriceCents)
        // a new pint now is its own line at the menu price (never folded into the cheap one)
        val lines = f.checks.addLine(check, "lantern-lager", "lantern-lager:pint", 1, null).lines
        assertEquals(listOf(500L to 3, 750L to 1), lines.map { it.unitPriceCents to it.qty })
        assertNull(lines.last().special)
        // tax is on what the guest pays: the special price is the base
        val view = f.checks.getCheck(check)
        assertEquals(3 * 500L + 750L, view.subtotalCents)
        // the sync hears the price rung and the menu price it replaced
        transaction {
            val added = SyncOutbox.selectAll().filter { it[SyncOutbox.eventType] == "check.line_added" }
                .map { Json.parseToJsonElement(it[SyncOutbox.payload]).jsonObject }
            val first = added.first { it["lineId"]!!.jsonPrimitive.long == line.id.toLong() }
            assertEquals(500, first["unitPriceCents"]!!.jsonPrimitive.long)
            assertEquals(750, first["regularUnitPriceCents"]!!.jsonPrimitive.long)
        }
    }

    @Test
    fun `burgers are cheaper on Tuesdays and the bill says so in its language`() {
        val f = store()
        at(2026, 10, 6, 12)
        val check = f.open()
        f.checks.addLine(check, "lantern-burger", "lantern-burger:regular", 1, null)
        f.checks.addLine(check, "lantern-lager", "lantern-lager:pint", 1, null)
        assertEquals(listOf(1495L, 750L), f.checks.getCheck(check).lines.map { it.unitPriceCents })
        val en = f.checks.printBill(check, lang = "en")
        assertTrue("Tuesday special" in en, en)
        assertTrue("Spécial du mardi" in f.checks.printBill(check, lang = "fr"))
        assertTrue("Dienstagsangebot" in f.checks.printBill(check, lang = "de"))
        // Wednesday: the menu price
        at(2026, 10, 7, 12)
        assertEquals(1925, price(f, "lantern-burger", "lantern-burger:regular"))
        // a sale at 1 am on Wednesday is still Tuesday night
        at(2026, 10, 7, 1)
        assertEquals(1495, price(f, "lantern-burger", "lantern-burger:regular"))
    }

    @Test
    fun `happy hour prints as Happy hour on the bill`() {
        val f = store()
        at(2026, 10, 8, 16, 45)
        val check = f.open()
        f.checks.addLine(check, "cab-merlot", "cab-merlot:glass", 1, null)
        val bill = f.checks.printBill(check, lang = "en")
        assertTrue("Happy hour" in bill, bill)
        assertTrue("Hora feliz" in f.checks.printBill(check, lang = "es"))
    }

    @Test
    fun `prime rib is sold on Friday and Saturday nights only`() {
        val f = store()
        at(2026, 10, 2, 19) // Friday
        assertEquals(3495, price(f, "prime-rib", "prime-rib:regular"))
        at(2026, 10, 4, 1) // Sunday 1 am: still Saturday night
        assertEquals(3495, price(f, "prime-rib", "prime-rib:regular"))
        at(2026, 10, 4, 12) // Sunday
        val check = f.open()
        val e = assertFailsWith<LineRejectedException> { f.checks.addLine(check, "prime-rib", "prime-rib:regular", 1, null) }
        assertEquals("item_unavailable", e.code)
        assertEquals(listOf("fri", "sat"), e.rejected.single().availableDays)
        // and the Sunday roast is on
        assertEquals(2495, price(f, "sunday-roast", "sunday-roast:regular"))
        at(2026, 10, 5, 12)
        assertFailsWith<LineRejectedException> { f.checks.addLine(check, "sunday-roast", "sunday-roast:regular", 1, null) }
    }

    @Test
    fun `a guest QR order is refused the day-only dish and priced at the special`() {
        val f = store()
        at(2026, 10, 6, 17) // Tuesday, happy hour
        val view = f.checks.submitPendingLines("t3", listOf(
            dev.dwhipstock.pos.restaurant.PendingLineRequest("lantern-lager", "lantern-lager:pint"),
            dev.dwhipstock.pos.restaurant.PendingLineRequest("prime-rib", "prime-rib:regular"),
            dev.dwhipstock.pos.restaurant.PendingLineRequest("lantern-burger", "lantern-burger:regular", expectedPriceCents = 1495),
        ))
        assertEquals(listOf(500L, 1495L), view.pendingLines.map { it.unitPriceCents })
        assertEquals(listOf("prime-rib"), view.rejected.map { it.itemId })
    }

    @Test
    fun `the price the screen showed is checked against the special`() {
        val f = store()
        at(2026, 10, 5, 18, 1) // happy hour just ended
        val check = f.open()
        val e = assertFailsWith<LineRejectedException> {
            f.checks.addLine(check, "lantern-lager", "lantern-lager:pint", 1, null, expectedPriceCents = 500)
        }
        assertEquals("price_changed", e.code)
        assertEquals(750, e.rejected.single().priceCents)
        // a screen still showing the menu price while happy hour is on: rung at the (lower) special
        at(2026, 10, 5, 17)
        assertEquals(500, f.checks.addLine(check, "lantern-lager", "lantern-lager:pint", 1, null, expectedPriceCents = 750).lines.single().unitPriceCents)
    }

    @Test
    fun `editing specials validates sizes and replaces the whole list`() {
        val f = store()
        assertFailsWith<IllegalArgumentException> {
            CatalogOps.patchItem("lantern-burger", ItemPatchRequest(specials = listOf(
                MenuSpecials.Special(listOf("tue"), prices = mapOf("amber-ale:pint" to 100L)))))
        }
        CatalogOps.patchItem("lantern-burger", ItemPatchRequest(availableDays = listOf("sat", "fri"), specials = emptyList()))
        transaction {
            assertEquals(MenuSpecials.Schedule(listOf("fri", "sat"), emptyList()), ItemSchedules.of("lantern-burger"))
        }
        // all seven days = every day; nothing left = no row
        CatalogOps.patchItem("lantern-burger", ItemPatchRequest(availableDays = MenuSpecials.DAYS))
        transaction { assertTrue(ItemSchedules.of("lantern-burger").isEmpty) }
        // the snapshot the portal gets says what the item has
        val snap = transaction { dev.dwhipstock.pos.api.itemSnapshotJson("prime-rib") }
        assertEquals("""["fri","sat"]""", snap["availableDays"].toString())
        assertNull(snap["specials"])
        assertTrue(f.checks.getCheck(f.open()).lines.isEmpty())
    }

    @Test
    fun `the demo specials are seeded once and never over a manager's own`() {
        val f = store()
        CatalogOps.patchItem("lantern-lager", ItemPatchRequest(specials = emptyList()))
        transaction { dev.dwhipstock.pos.db.SyncState.set(CopperLanternSpecials.SEEDED_KEY, "") }
        CopperLanternSpecials.seed(CopperLanternVenue.PLATEAU, enabled = true) // a second boot: no-op
        transaction {
            assertTrue(ItemSchedules.of("lantern-lager").specials.isEmpty())
            assertEquals(1, dev.dwhipstock.pos.base.Items.selectAll().count { it[dev.dwhipstock.pos.base.Items.id] == "prime-rib" })
            // names in the other languages, for the guest page and the kiosk
            assertEquals("Sonntagsbraten", dev.dwhipstock.pos.base.Translations.get("item", "sunday-roast", "de"))
        }
        f.open()
    }
}
