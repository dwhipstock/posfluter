package dev.dwhipstock.pos

import dev.dwhipstock.pos.restaurant.KitchenSettingsDto
import dev.dwhipstock.pos.restaurant.NotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The kitchen screen reads the same ticket events as the printers. */
class KitchenScreenTest {

    @Test
    fun `sends land as one card per check per station, filtered by station`() {
        val f = KitchenFixture()
        val c = f.open()
        f.add(c, "lantern-burger", qty = 2, note = "sans oignons")
        f.add(c, "amber-ale", "amber-ale:pint")
        f.kitchen.send(c, "Demo Server")
        val all = f.kitchen.board()
        assertEquals(setOf("kitchen", "bar"), all.cards.map { it.stationId }.toSet())
        val kitchen = f.kitchen.board("kitchen").cards.single()
        assertEquals("Demo Server", kitchen.serverName)
        assertEquals(c, kitchen.checkId)
        val burger = kitchen.items.single()
        assertEquals(2, burger.qty)
        assertEquals("sans oignons", burger.note)
        assertFalse(burger.add || burger.voided)
        // printer-only stations stay off the screen; screen stations are offered as filters
        f.kitchen.saveStation(f.kitchen.stations().first { it.id == "bar" }.copy(output = "printer"))
        assertEquals(listOf("kitchen", "sushi"), f.kitchen.board().stations.map { it.id })
    }

    @Test
    fun `later additions are marked ADD and voided items are struck through`() {
        val f = KitchenFixture()
        val c = f.open()
        val burger = f.add(c, "lantern-burger", qty = 2)
        val wings = f.add(c, "wings")
        f.kitchen.send(c)
        f.add(c, "pretzel")
        f.checks.removeLine(c, wings)
        f.checks.setLineQty(c, burger, 1)
        f.kitchen.send(c)
        val items = f.kitchen.board("kitchen").cards.single().items.associateBy { it.nameEn }
        val b = items.getValue("Copper Lantern Burger")
        assertEquals(2, b.qty); assertEquals(1, b.voidedQty); assertFalse(b.voided) // one of two still to make
        assertTrue(items.getValue("Chicken Wings").voided)
        assertTrue(items.getValue("Giant Pub Pretzel").add)
        assertFalse(items.getValue("Giant Pub Pretzel").voided)
    }

    @Test
    fun `bump clears a card, recall brings it back, the latest first`() {
        val f = KitchenFixture()
        val a = f.open("t3"); f.add(a, "lantern-burger"); f.kitchen.send(a)
        val b = f.open("t5"); f.add(b, "wings"); f.kitchen.send(b)
        assertEquals(2, f.kitchen.board("kitchen").cards.size)
        f.nowMs += 1_000
        val first = f.kitchen.bump(a, "kitchen")
        f.nowMs += 1_000
        f.kitchen.bump(b, "kitchen")
        val board = f.kitchen.board("kitchen")
        assertTrue(board.cards.isEmpty())
        assertEquals(listOf(b, a), board.recent.map { it.checkId })
        // recall with no id = the last bump
        f.kitchen.recall(null, "kitchen")
        assertEquals(listOf(b), f.kitchen.board("kitchen").cards.map { it.checkId })
        f.kitchen.recall(first)
        assertEquals(setOf(a, b), f.kitchen.board("kitchen").cards.map { it.checkId }.toSet())
        assertFailsWith<NotFoundException> { f.kitchen.recall("kb-nope") }

        // a send after a bump is a fresh card with only the new items
        f.kitchen.bump(a, "kitchen")
        f.add(a, "pretzel"); f.kitchen.send(a)
        val card = f.kitchen.board("kitchen").cards.single { it.checkId == a }
        assertEquals(listOf("Giant Pub Pretzel"), card.items.map { it.nameEn })
        assertTrue(card.items.single().add)
    }

    @Test
    fun `the timer turns yellow then red at the configured minutes`() {
        val f = KitchenFixture()
        f.kitchen.updateSettings(KitchenSettingsDto(defaultStationId = "kitchen", warnMinutes = 5, lateMinutes = 12))
        val c = f.open(); f.add(c, "lantern-burger"); f.kitchen.send(c)
        fun level() = f.kitchen.board("kitchen").cards.single().level
        assertEquals("ok", level())
        f.nowMs += 4 * 60_000 + 59_000
        assertEquals("ok", level())
        f.nowMs += 1_000
        assertEquals("warn", level())
        f.nowMs += 7 * 60_000
        assertEquals("late", level())
        assertEquals(12 * 60L, f.kitchen.board("kitchen").cards.single().elapsedSeconds)
        assertEquals("warn", f.kitchen.timerLevel(600, 10, 20))
        assertEquals("ok", f.kitchen.timerLevel(599, 10, 20))
        assertEquals("late", f.kitchen.timerLevel(1200, 10, 20))
        assertFailsWith<IllegalArgumentException> {
            f.kitchen.updateSettings(KitchenSettingsDto(warnMinutes = 20, lateMinutes = 10))
        }
    }

    @Test
    fun `a new order raises the board's latest ticket (the chime)`() {
        val f = KitchenFixture()
        val before = f.kitchen.board().latestTicket
        val c = f.open(); f.add(c, "lantern-burger"); f.kitchen.send(c)
        assertTrue(f.kitchen.board().latestTicket > before)
        assertTrue(f.kitchen.board().sound)
    }
}
