package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.db.SyncOutbox
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tapping the same menu item twice: one line of qty 2, not two lines of 1 —
 * but only while it is the very same thing still only on the bill: no note,
 * same price and names as rung, not sent to the kitchen, not handed to a
 * split bill.
 */
class LineMergeTest {


    @Test
    fun `the same item tapped twice is one line of qty 2`() {
        val f = KitchenFixture()
        val check = f.open()
        f.add(check, "lantern-burger")
        val view = f.checks.addLine(check, "lantern-burger", "lantern-burger:regular", 1, null)
        val line = view.lines.single()
        assertEquals(2, line.qty)
        assertEquals(2 * line.unitPriceCents, line.lineTotalCents)
        // and again with a qty: 2 + 3
        assertEquals(5, f.checks.addLine(check, "lantern-burger", "lantern-burger:regular", 3, null).lines.single().qty)
        // the sync hears a qty change on the one line, never a second line
        transaction {
            val types = SyncOutbox.selectAll().map { it[SyncOutbox.eventType] }
            assertEquals(1, types.count { it == "check.line_added" })
            assertEquals(2, types.count { it == "check.line_qty_changed" })
        }
    }

    @Test
    fun `a note, another size or another item stays its own line`() {
        val f = KitchenFixture()
        val check = f.open()
        f.add(check, "lantern-burger")
        f.add(check, "lantern-burger", note = "no onions")
        f.add(check, "lantern-burger", note = "no onions") // notes are free text: never folded
        f.add(check, "amber-ale", "amber-ale:pint")
        f.add(check, "amber-ale", "amber-ale:pitcher")
        val lines = f.checks.addLine(check, "lantern-burger", "lantern-burger:regular", 1, null).lines
        assertEquals(5, lines.size, lines.toString())
        assertEquals(2, lines.first { it.itemId == "lantern-burger" && it.note == null }.qty)
        assertEquals(listOf(1, 1), lines.filter { it.note == "no onions" }.map { it.qty })
    }

    @Test
    fun `a line already sent to the kitchen stays as sent`() {
        val f = KitchenFixture()
        val check = f.open()
        f.add(check, "lantern-burger")
        f.kitchen.send(check); f.drain()
        val lines = f.checks.addLine(check, "lantern-burger", "lantern-burger:regular", 1, null).lines
        assertEquals(listOf(1, 1), lines.map { it.qty })
        // the new one merges with later taps until it is sent too
        assertEquals(listOf(1, 2), f.checks.addLine(check, "lantern-burger", "lantern-burger:regular", 1, null).lines.map { it.qty })
    }

    @Test
    fun `a price or name changed between taps keeps the line as rung`() {
        val f = KitchenFixture()
        val check = f.open()
        f.add(check, "lantern-burger")
        transaction { ItemVariants.update({ ItemVariants.id eq "lantern-burger:regular" }) { it[priceCents] = 1999 } }
        val repriced = f.checks.addLine(check, "lantern-burger", "lantern-burger:regular", 1, null).lines
        assertEquals(2, repriced.size)
        assertEquals(listOf(1, 1), repriced.map { it.qty })
        transaction { Items.update({ Items.id eq "lantern-burger" }) { it[nameEn] = "House Burger" } }
        assertEquals(3, f.checks.addLine(check, "lantern-burger", "lantern-burger:regular", 1, null).lines.size)
        // same price and name again: folds into the matching line
        val last = f.checks.addLine(check, "lantern-burger", "lantern-burger:regular", 1, null).lines
        assertEquals(listOf(1, 1, 2), last.map { it.qty })
    }

    @Test
    fun `a line handed out to a split bill stays its own line`() {
        val f = KitchenFixture()
        val check = f.open()
        val lineId = f.add(check, "lantern-burger")
        val split = f.checks.createSplit(check, 2).split!!
        f.checks.assignLineToGroup(check, split.groups.first().id, lineId, 1)
        val lines = f.checks.addLine(check, "lantern-burger", "lantern-burger:regular", 1, null).lines
        assertEquals(listOf(1, 1), lines.map { it.qty })
    }
}
