package dev.dwhipstock.pos

import dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue
import dev.dwhipstock.pos.restaurant.KitchenSettingsDto
import dev.dwhipstock.pos.restaurant.KitchenStationDto
import dev.dwhipstock.pos.restaurant.KitchenTickets
import dev.dwhipstock.pos.sdk.KitchenLanguage
import dev.dwhipstock.pos.sdk.KitchenTicketData
import dev.dwhipstock.pos.sdk.KitchenTicketItem
import dev.dwhipstock.pos.sdk.KitchenTicketKind
import dev.dwhipstock.pos.sdk.KitchenTicketRenderer
import dev.dwhipstock.pos.sdk.PrintLine
import dev.dwhipstock.pos.sdk.PrinterAdapter
import dev.dwhipstock.pos.sdk.ThermalLayout
import dev.dwhipstock.pos.sdk.ThermalReceiptRenderer
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Station tickets: routing, incremental sends, voids, reprint, language and paper width. */
class KitchenTicketsTest {

    /** Give each station its own printer so the fake can tell them apart. */
    private fun KitchenFixture.ownPrinters() {
        for (s in kitchen.stations()) kitchen.saveStation(s.copy(printerHost = "${s.id}.lan", output = "printer"))
    }

    @Test
    fun `defaults route food to the kitchen, drinks to the bar and sushi to the sushi bar`() {
        val f = KitchenFixture(CopperLanternVenue.PLATEAU)
        assertEquals(listOf("kitchen", "bar", "sushi"), f.kitchen.stations().map { it.id })
        assertEquals("kitchen", f.kitchen.stationForItem("lantern-burger"))
        assertEquals("kitchen", f.kitchen.stationForItem("wings"))
        assertEquals("bar", f.kitchen.stationForItem("amber-ale"))
        assertEquals("sushi", f.kitchen.stationForItem("salmon-maki"))
        // a Plateau special follows the default station, but its cocktail goes to the bar
        assertEquals("kitchen", f.kitchen.stationForItem("smoked-meat-poutine"))
        assertEquals("bar", f.kitchen.stationForItem("yuzu-sour"))
        // a per-item override wins over its category; "" keeps it off every ticket
        f.kitchen.setRoute("item", "wings", "bar")
        assertEquals("bar", f.kitchen.stationForItem("wings"))
        f.kitchen.setRoute("item", "wings", "")
        assertNull(f.kitchen.stationForItem("wings"))
        f.kitchen.setRoute("item", "wings", null)
        assertEquals("kitchen", f.kitchen.stationForItem("wings"))
    }

    @Test
    fun `vieux-port has no sushi bar`() {
        val f = KitchenFixture(CopperLanternVenue.VIEUX_PORT)
        assertEquals(listOf("kitchen", "bar"), f.kitchen.stations().map { it.id })
    }

    @Test
    fun `one ticket per station with only that station's items`() {
        val f = KitchenFixture()
        f.ownPrinters()
        val check = f.open()
        f.add(check, "lantern-burger", qty = 2, note = "sans oignons")
        f.add(check, "amber-ale", "amber-ale:pint")
        f.add(check, "salmon-maki")
        val r = f.kitchen.send(check, "Demo Server")
        assertEquals(3, r.tickets)
        assertEquals(listOf("kitchen", "bar", "sushi"), r.stations)
        f.drain()
        val kitchen = f.transport.to("kitchen.lan").single().text
        assertTrue("Cuisine" in kitchen && "COMMANDE" in kitchen, kitchen)
        assertTrue("2 × Burger de la Lanterne" in kitchen, kitchen)
        assertTrue("» sans oignons" in kitchen, kitchen)
        assertFalse("Ambrée" in kitchen || "Maki" in kitchen, kitchen)
        val bar = f.transport.to("bar.lan").single().text
        assertTrue("1 × Ambrée cuivrée" in bar && "Pinte 20 oz" in bar, bar)
        assertFalse("Burger" in bar, bar)
        val sushi = f.transport.to("sushi.lan").single().text
        assertTrue("Maki au saumon" in sushi && "Bar à sushis" in sushi, sushi)
        // header: table, bill number, server
        assertTrue("Table" in kitchen && "Addition n° $check" in kitchen && "Serveur : Demo Server" in kitchen, kitchen)
    }

    @Test
    fun `a later send prints only the new or increased items as ADD, and a repeat prints nothing`() {
        val f = KitchenFixture()
        f.ownPrinters()
        val check = f.open()
        val burger = f.add(check, "lantern-burger")
        f.add(check, "amber-ale", "amber-ale:pint")
        f.kitchen.send(check); f.drain()
        assertEquals(0, f.kitchen.state(check).unsent)

        f.checks.setLineQty(check, burger, 3)
        f.add(check, "wings")
        assertEquals(3, f.kitchen.state(check).unsent) // +2 burgers, +1 wings
        val r = f.kitchen.send(check)
        assertEquals(listOf("kitchen"), r.stations, "the bar has nothing new")
        val add = f.drain().single().text
        assertTrue("AJOUT" in add && "COMMANDE" !in add, add)
        assertTrue("2 × Burger de la Lanterne" in add && "1 × Ailes de poulet" in add, add)
        assertFalse("3 ×" in add, add)

        // sending again (a double tap, a retried request) changes nothing
        assertEquals(0, f.kitchen.send(check).tickets)
        assertTrue(f.drain().isEmpty())
    }

    @Test
    fun `removed, reduced and voided items print VOID to their station`() {
        val f = KitchenFixture()
        f.ownPrinters()
        val check = f.open()
        val burger = f.add(check, "lantern-burger", qty = 3)
        val beer = f.add(check, "amber-ale", "amber-ale:pint")
        f.add(check, "wings")
        f.kitchen.send(check); f.drain()

        f.checks.setLineQty(check, burger, 1)
        f.checks.removeLine(check, beer)
        assertEquals(3, f.kitchen.state(check).pendingVoids)
        f.kitchen.send(check)
        val out = f.drain()
        val kitchenVoid = out.single { it.target.startsWith("kitchen.lan") }.text
        assertTrue("ANNULÉ" in kitchenVoid && "-2 × Burger de la Lanterne" in kitchenVoid, kitchenVoid)
        val barVoid = out.single { it.target.startsWith("bar.lan") }.text
        assertTrue("ANNULÉ" in barVoid && "-1 × Ambrée cuivrée" in barVoid, barVoid)

        // voiding the whole check voids what's left, at once (no send needed)
        f.checks.voidCheck(check, "test", "manager")
        val last = f.drain().single().text
        assertTrue("ANNULÉ" in last && "-1 × Burger de la Lanterne" in last && "-1 × Ailes de poulet" in last, last)
    }

    @Test
    fun `removing the last item cancels the check and voids it in the kitchen`() {
        val f = KitchenFixture()
        val check = f.open()
        val burger = f.add(check, "lantern-burger")
        f.kitchen.send(check); f.drain()
        assertEquals("CANCELLED", f.checks.removeLine(check, burger).status)
        assertTrue("ANNULÉ" in f.drain().single().text)
    }

    @Test
    fun `a changed note is a void of the old line and an add of the new one`() {
        val f = KitchenFixture()
        val check = f.open()
        val burger = f.add(check, "lantern-burger", note = "saignant")
        f.kitchen.send(check); f.drain()
        transaction {
            dev.dwhipstock.pos.restaurant.CheckLines.update({ dev.dwhipstock.pos.restaurant.CheckLines.id eq burger }) {
                it[note] = "bien cuit"
            }
        }
        f.kitchen.send(check)
        val out = f.drain().map { it.text }
        assertEquals(2, out.size)
        assertTrue("ANNULÉ" in out[0] && "saignant" in out[0])
        assertTrue("AJOUT" in out[1] && "bien cuit" in out[1])
    }

    @Test
    fun `reprint prints what the station already has, marked REPRINT`() {
        val f = KitchenFixture()
        val check = f.open()
        f.add(check, "lantern-burger", qty = 2)
        f.kitchen.send(check); f.drain()
        val r = f.kitchen.reprint(check)
        assertEquals(1, r.tickets)
        val text = f.drain().single().text
        assertTrue("RÉIMPRESSION" in text && "2 × Burger de la Lanterne" in text, text)
        // a reprint is not a new order: nothing unsent, and it never shows on the screen
        assertEquals(0, f.kitchen.state(check).unsent)
        assertTrue(f.kitchen.board().cards.single().items.none { it.qty != 2 })
    }

    @Test
    fun `ticket text follows the store language, English, or both`() {
        val t = KitchenTicketData(
            KitchenTicketKind.VOID, "Cuisine", "Kitchen", "U-3", "Alex", 45, LocalDateTime.of(2026, 9, 26, 19, 42),
            listOf(KitchenTicketItem(2, "Poutine classique", "Classic Poutine", "Grande", "Large", "sans oignons")),
            guests = 4,
        )
        val fr = PrinterAdapter.renderText(KitchenTicketRenderer.render(t, KitchenLanguage.FR))
        assertTrue("Cuisine" in fr && "ANNULÉ" in fr && "Couverts : 4" in fr && "-2 × Poutine classique" in fr && "Grande" in fr, fr)
        assertTrue("19:42" in fr && "Table U-3" in fr)
        assertFalse("Kitchen" in fr || "VOID" in fr)
        val en = PrinterAdapter.renderText(KitchenTicketRenderer.render(t, KitchenLanguage.EN))
        assertTrue("Kitchen" in en && "VOID" in en && "Guests: 4" in en && "Classic Poutine" in en && "Large" in en, en)
        assertFalse("ANNULÉ" in en)
        val both = PrinterAdapter.renderText(KitchenTicketRenderer.render(t, KitchenLanguage.BOTH))
        assertTrue("Cuisine / Kitchen" in both && "ANNULÉ / VOID" in both && "Classic Poutine" in both, both)

        // the store setting drives the queued tickets
        val f = KitchenFixture()
        f.kitchen.updateSettings(KitchenSettingsDto(language = "en", defaultStationId = "kitchen"))
        val check = f.open()
        f.add(check, "lantern-burger")
        f.kitchen.send(check)
        val text = f.drain().single().text
        assertTrue("ORDER" in text && "Copper Lantern Burger" in text && "Kitchen" in text, text)
    }

    @Test
    fun `every row fits the paper at 58mm and 80mm`() {
        val long = KitchenTicketData(
            KitchenTicketKind.ADD, "Bar à sushis du Plateau", "Plateau Sushi Bar", "Terrasse arrière 12", "Alexandrine-Marguerite",
            1234, LocalDateTime.of(2026, 9, 26, 19, 42),
            listOf(
                KitchenTicketItem(12, "Poutine à la viande fumée extra grande avec fromage en grains", "Extra large smoked meat poutine",
                    "Grande assiette à partager", "Large sharing plate",
                    "Allergie aux noix — aucune trace, préparer sur une planche propre s’il vous plaît"),
                KitchenTicketItem(1, "Supercalifragilisticexpialidociousmaki", "Supercalifragilisticexpialidociousmaki"),
            ),
            guests = 12, reference = "1234-99",
        )
        for (language in KitchenLanguage.entries) for (width in listOf(ThermalLayout.WIDTH_58MM, ThermalLayout.WIDTH)) {
            val lines: List<PrintLine> = KitchenTicketRenderer.render(long, language)
            val rows = ThermalReceiptRenderer.layout(lines, width)
            for (row in rows) {
                val w = ThermalReceiptRenderer.rowWidth(row)
                assertTrue(w <= ThermalLayout.contentFor(width), "$language ${width}dots: row too wide ($w): $row")
            }
            val img = ThermalReceiptRenderer.renderImage(lines, width)
            assertEquals(width, img.width)
            // the raster rows are the head's width in bytes
            val bytes = ThermalReceiptRenderer.toEscPos(lines, width)
            assertEquals((width / 8).toByte(), bytes[2 + 4])
        }
        assertEquals(ThermalLayout.WIDTH_58MM, ThermalLayout.widthFor(58))
        assertEquals(ThermalLayout.WIDTH, ThermalLayout.widthFor(80))
    }

    @Test
    fun `a 58mm station gets a 58mm ticket`() {
        val f = KitchenFixture()
        val widths = mutableListOf<Int>()
        val k = dev.dwhipstock.pos.restaurant.KitchenService(
            dev.dwhipstock.pos.customers.copperlantern.CopperLanternConfig(
                CopperLanternVenue.PLATEAU, f.settings,
                PrinterAdapter.VirtualPrinter(f.dir.path + "/r", f.dir.path + "/b"), "http://x"),
            f.settings, f.transport, clockMs = { f.nowMs },
            render = { lines, width -> widths += width; PrinterAdapter.renderText(lines).toByteArray() })
        k.saveStation(k.stations().first { it.id == "bar" }.copy(paperMm = 58))
        f.checks.kitchen = k
        val check = f.open()
        f.add(check, "amber-ale", "amber-ale:pint")
        f.add(check, "lantern-burger")
        k.send(check)
        k.queue.processDue()
        assertEquals(listOf(ThermalLayout.WIDTH, ThermalLayout.WIDTH_58MM), widths)
    }

    @Test
    fun `a merged check's items are not voided and stay with the lines`() {
        val f = KitchenFixture()
        val a = f.open("t3")
        f.add(a, "lantern-burger")
        f.kitchen.send(a); f.drain()
        val b = f.open("t5")
        f.add(b, "wings")
        f.kitchen.send(b); f.drain()
        f.checks.mergeCheck(a, b)
        assertEquals(0, f.kitchen.send(a).tickets)
        assertEquals(0, f.kitchen.send(b).tickets)
        assertTrue(f.drain().isEmpty())
        assertEquals(2, f.kitchen.state(b).sent)
    }

    @Test
    fun `guests show on the ticket header`() {
        val f = KitchenFixture()
        val check = f.open()
        f.kitchen.setGuests(check, 4)
        f.add(check, "lantern-burger")
        f.kitchen.send(check)
        assertTrue("Couverts : 4" in f.drain().single().text)
        assertEquals(4, f.kitchen.state(check).guests)
    }

    @Test
    fun `a screen-only station writes the ticket for the screen and queues no print`() {
        val f = KitchenFixture()
        f.kitchen.saveStation(KitchenStationDto("kitchen", "Cuisine", "Kitchen", output = "screen"))
        val check = f.open()
        f.add(check, "lantern-burger")
        val r = f.kitchen.send(check)
        assertEquals(1, r.tickets)
        assertEquals(0, r.printJobs)
        assertTrue(f.jobStatuses().isEmpty())
        assertEquals(1, transaction { KitchenTickets.selectAll().count() })
        assertEquals("kitchen", f.kitchen.board().cards.single().stationId)
    }
}
