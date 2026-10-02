package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.ConflictException
import dev.dwhipstock.pos.base.SettingsRepository
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternConfig
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternExpressSeed
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue
import dev.dwhipstock.pos.db.initDatabase
import dev.dwhipstock.pos.restaurant.CheckService
import dev.dwhipstock.pos.restaurant.KioskOrderLine
import dev.dwhipstock.pos.restaurant.KioskOrderRequest
import dev.dwhipstock.pos.restaurant.QuickServeService
import dev.dwhipstock.pos.sdk.PrinterAdapter
import dev.dwhipstock.pos.sdk.UnpaidExpiry
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An unpaid order open on a POS screen is never expired under the cashier
 * (bug: kiosk #101 expired while on the Express counter, then Pay and the bin
 * both failed). Idle orders still expire; the time is store config.
 */
class StaleOrderExpiryTest {
    private val burger = KioskOrderLine("lantern-burger", "lantern-burger:regular")
    private val day = LocalDate.of(2026, 10, 8)

    private class Store(val qs: QuickServeService, val checks: CheckService)

    private fun store(clock: () -> Instant, expireAfter: Duration = Duration.ofMinutes(30)): Store {
        val dir = Files.createTempDirectory("pos-stale").toFile()
        initDatabase(File(dir, "pos.db").path)
        CopperLanternExpressSeed.seedIfEmpty()
        val config = CopperLanternConfig(
            venue = CopperLanternVenue.EXPRESS, settings = SettingsRepository(),
            printer = PrinterAdapter.VirtualPrinter(File(dir, "r").path, File(dir, "b").path),
            publicBaseUrl = "http://127.0.0.1:8080",
        )
        val checks = CheckService(config)
        dev.dwhipstock.pos.restaurant.ShiftService(config).openShift("manager", 0)
        val qs = QuickServeService(config, checks, today = { day }, clock = clock, expireAfter = expireAfter)
        qs.ensureCounter()
        return Store(qs, checks)
    }

    @Test
    fun `an order open on a screen does not expire, an idle one still does`() {
        var now = Instant.parse("2026-10-08T16:00:00Z")
        val s = store({ now })
        val onScreen = s.qs.placeKioskOrder(KioskOrderRequest("TAKE_OUT", listOf(burger)), "Kiosk")
        val idle = s.qs.placeKioskOrder(KioskOrderRequest("TAKE_OUT", listOf(burger)), "Kiosk")
        val draft = s.qs.createAtPos("TAKE_OUT", burger, "manager")

        // the counter has the kiosk order and the draft open: its check screen polls them
        for (minute in 1..40) {
            now = now.plusSeconds(60)
            s.checks.touch(onScreen.checkId)
            s.checks.touch(draft.checkId)
            s.qs.expire()
        }
        assertEquals("OPEN", s.checks.getCheck(onScreen.checkId).status, "on the counter: kept")
        assertEquals("OPEN", s.checks.getCheck(draft.checkId).status, "being rung: kept")
        assertEquals("CANCELLED", s.checks.getCheck(idle.checkId).status, "nobody came: dropped")
        assertEquals(listOf(onScreen.checkId), s.qs.waiting().map { it.checkId })

        // the screen moves on: 30 minutes after its last look, it goes too
        now = now.plusSeconds(29 * 60)
        assertEquals(0, s.qs.expire())
        now = now.plusSeconds(2 * 60)
        assertEquals(2, s.qs.expire())
        assertEquals("CANCELLED", s.checks.getCheck(onScreen.checkId).status)
        assertEquals("CANCELLED", s.checks.getCheck(draft.checkId).status)
    }

    @Test
    fun `listing the waiting orders does not keep them alive`() {
        var now = Instant.parse("2026-10-08T16:00:00Z")
        val s = store({ now })
        val k = s.qs.placeKioskOrder(KioskOrderRequest("DINE_IN", listOf(burger)), "Kiosk")
        // the counter polls the strip every few seconds; that is not "open on a screen"
        repeat(35) { now = now.plusSeconds(60); s.qs.waiting(); s.qs.view(k.checkId) }
        assertEquals("CANCELLED", s.checks.getCheck(k.checkId).status)
    }

    @Test
    fun `the expiry time is store config`() {
        var now = Instant.parse("2026-10-08T16:00:00Z")
        val s = store({ now }, expireAfter = UnpaidExpiry.resolve("10", "store.properties").duration)
        val k = s.qs.placeKioskOrder(KioskOrderRequest("TAKE_OUT", listOf(burger)), "Kiosk")
        now = now.plusSeconds(9 * 60)
        assertEquals(0, s.qs.expire())
        now = now.plusSeconds(2 * 60)
        assertEquals(1, s.qs.expire())
        assertEquals("CANCELLED", s.checks.getCheck(k.checkId).status)
    }

    @Test
    fun `orders unpaidExpireMinutes parses, defaults to 30 and ignores bad values`() {
        assertEquals(30, UnpaidExpiry.resolve(null, "x").minutes)
        assertEquals(30, UnpaidExpiry.resolve(" ", "x").minutes)
        assertEquals(45, UnpaidExpiry.resolve(" 45 ", "store.properties").minutes)
        for (bad in listOf("0", "-5", "abc", "100000")) {
            val r = UnpaidExpiry.resolve(bad, "store.properties")
            assertEquals(30, r.minutes, bad)
            assertNotNull(r.warning, bad)
        }
        val dir = Files.createTempDirectory("unpaid-expiry").toFile()
        val file = File(dir, "store.properties").apply { writeText("print.receipts=digital\norders.unpaidExpireMinutes=15\n") }
        assertEquals(15, UnpaidExpiry.fromFile(file).minutes)
        assertEquals(20, UnpaidExpiry.fromEnv { if (it == UnpaidExpiry.ENV) "20" else null }.minutes)
        assertEquals(15, UnpaidExpiry.fromEnv { if (it == "POS_CONFIG_FILE") file.path else null }.minutes)
    }

    @Test
    fun `a check that expired or was paid elsewhere answers check_not_open, and discarding it again is a no-op`() {
        var now = Instant.parse("2026-10-08T16:00:00Z")
        val s = store({ now })
        val k = s.qs.placeKioskOrder(KioskOrderRequest("TAKE_OUT", listOf(burger)), "Kiosk")
        now = now.plusSeconds(31 * 60)
        assertEquals(1, s.qs.expire())
        assertEquals("check_not_open", assertFailsWith<ConflictException> { s.checks.tenderCash(k.checkId, 2000) }.code)
        assertEquals("check_not_open", assertFailsWith<ConflictException> { s.qs.setMode(k.checkId, "DINE_IN") }.code)
        s.qs.discard(k.checkId) // the bin on a stale screen: just clears
        s.qs.discard(k.checkId)

        // paid on another device: Pay here says so, the bin still refuses (the order is real)
        val other = s.qs.placeKioskOrder(KioskOrderRequest("TAKE_OUT", listOf(burger)), "Kiosk")
        s.checks.tenderCash(other.checkId, 100_000)
        s.checks.finalizeCheck(other.checkId)
        assertEquals("check_not_open", assertFailsWith<ConflictException> { s.checks.tenderCash(other.checkId, 2000) }.code)
        assertEquals("order_paid", assertFailsWith<ConflictException> { s.qs.discard(other.checkId) }.code)
        assertTrue(s.qs.waiting().isEmpty())
    }
}
