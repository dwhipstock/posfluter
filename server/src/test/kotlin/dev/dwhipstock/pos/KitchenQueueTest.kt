package dev.dwhipstock.pos

import dev.dwhipstock.pos.restaurant.KitchenPrintQueue
import dev.dwhipstock.pos.restaurant.KitchenTcpTransport
import dev.dwhipstock.pos.restaurant.PaperOutException
import dev.dwhipstock.pos.sdk.PrinterTarget
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The persistent print queue: waits while a printer is down, then prints in order, once. */
class KitchenQueueTest {

    private fun KitchenFixture.ownPrinters() {
        for (s in kitchen.stations()) kitchen.saveStation(s.copy(printerHost = "${s.id}.lan"))
    }

    @Test
    fun `an offline printer's tickets wait and print in order when it is back`() {
        val f = KitchenFixture()
        f.ownPrinters()
        f.transport.down += "kitchen.lan"
        val tables = listOf("t3", "t5", "t6")
        for (t in tables) {
            val c = f.open(t)
            f.add(c, "lantern-burger", note = "table $t")
            f.kitchen.send(c)
        }
        assertEquals(0, f.kitchen.queue.processDue())
        val status = f.kitchen.queueStatus()
        assertEquals(3, status.waiting)
        assertTrue(status.failing)
        assertEquals(false, status.stations.first { it.stationId == "kitchen" }.online)

        // still down after the backoff: tried again, still waiting, nothing lost
        f.nowMs += 60_000
        assertEquals(0, f.kitchen.queue.processDue())
        // back: all three print, oldest first
        f.transport.down.clear()
        f.nowMs += 60_000
        assertEquals(3, f.kitchen.queue.processDue())
        val printed = f.transport.to("kitchen.lan").map { it.text }
        assertEquals(tables.map { "table $it" }, printed.map { Regex("table t[0-9]+").find(it)!!.value })
        assertEquals(0, f.kitchen.queueStatus().waiting)
    }

    @Test
    fun `a retry after a partial failure prints only what is missing`() {
        val f = KitchenFixture()
        f.ownPrinters()
        f.transport.down += "bar.lan"
        val c = f.open()
        f.add(c, "lantern-burger")
        f.add(c, "amber-ale", "amber-ale:pint")
        f.kitchen.send(c)
        assertEquals(1, f.kitchen.queue.processDue()) // the kitchen printed, the bar did not
        f.transport.down.clear()
        f.kitchen.queue.retryNow()
        assertEquals(1, f.kitchen.queue.processDue())
        // and again: nothing is ever sent twice
        f.nowMs += 600_000
        f.kitchen.queue.retryNow()
        assertEquals(0, f.kitchen.queue.processDue())
        assertEquals(1, f.transport.to("kitchen.lan").size)
        assertEquals(1, f.transport.to("bar.lan").size)
        assertEquals(listOf("DONE", "DONE"), f.jobStatuses())
    }

    @Test
    fun `backoff grows and a later ticket waits behind the head of its station`() {
        assertEquals(2_000, KitchenPrintQueue.backoffMs(1))
        assertEquals(4_000, KitchenPrintQueue.backoffMs(2))
        assertEquals(8_000, KitchenPrintQueue.backoffMs(3))
        assertEquals(KitchenPrintQueue.MAX_BACKOFF_MS, KitchenPrintQueue.backoffMs(30))

        val f = KitchenFixture()
        f.ownPrinters()
        f.transport.down += "kitchen.lan"
        val a = f.open("t3"); f.add(a, "lantern-burger"); f.kitchen.send(a)
        f.kitchen.queue.processDue()
        f.transport.down.clear()
        val b = f.open("t5"); f.add(b, "wings"); f.kitchen.send(b)
        // within the head's backoff the newer ticket must not jump the queue
        f.nowMs += 500
        assertEquals(0, f.kitchen.queue.processDue())
        f.nowMs += 2_000
        assertEquals(2, f.kitchen.queue.processDue())
        val texts = f.transport.to("kitchen.lan").map { it.text }
        assertTrue("Burger" in texts[0] && "Ailes" in texts[1])
    }

    @Test
    fun `cancel drops waiting tickets`() {
        val f = KitchenFixture()
        f.ownPrinters()
        f.transport.down += "kitchen.lan"
        val c = f.open(); f.add(c, "lantern-burger"); f.kitchen.send(c)
        f.kitchen.queue.processDue()
        assertEquals(1, f.kitchen.queue.cancel(null))
        f.transport.down.clear()
        f.kitchen.queue.retryNow()
        assertEquals(0, f.kitchen.queue.processDue())
        assertEquals(listOf("CANCELLED"), f.jobStatuses())
    }

    @Test
    fun `no printer set up means tickets wait, the sale is untouched`() {
        val f = KitchenFixture()
        f.settings.update(dev.dwhipstock.pos.base.SettingsPatch(printerIp = ""))
        val c = f.open(); f.add(c, "lantern-burger")
        f.kitchen.send(c)
        f.kitchen.queue.processDue()
        assertEquals("printer_not_configured", f.kitchen.queueStatus().lastError)
        // paying is unaffected
        dev.dwhipstock.pos.restaurant.ShiftService(f.config).openShift("manager", 0)
        val view = f.checks.tenderCash(c, 100_000)
        assertTrue(view.amountAppliedCents > 0)
    }

    @Test
    fun `paper out is detected with the printer's status reply`() {
        fun printer(statusByte: Int): Pair<ServerSocket, java.util.concurrent.atomic.AtomicInteger> {
            val server = ServerSocket(0)
            val payload = java.util.concurrent.atomic.AtomicInteger(-1)
            thread(isDaemon = true) {
                server.accept().use { s ->
                    val input = s.getInputStream()
                    repeat(3) { input.read() }        // DLE EOT 4
                    s.getOutputStream().write(statusByte); s.getOutputStream().flush()
                    payload.set(input.readBytes().size)
                }
            }
            return server to payload
        }
        val (empty, _) = printer(0x72) // bits 5–6: paper end
        empty.use {
            assertFailsWith<PaperOutException> {
                KitchenTcpTransport().send(PrinterTarget("127.0.0.1", it.localPort), byteArrayOf(1, 2, 3))
            }
        }
        val (ok, got) = printer(0x12)
        ok.use {
            KitchenTcpTransport().send(PrinterTarget("127.0.0.1", it.localPort), byteArrayOf(1, 2, 3))
            val deadline = System.currentTimeMillis() + 3000
            while (got.get() < 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(3, got.get())
        }
    }
}
