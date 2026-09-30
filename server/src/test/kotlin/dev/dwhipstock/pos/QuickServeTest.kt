package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.SettingsRepository
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternConfig
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternExpressSeed
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue
import dev.dwhipstock.pos.db.initDatabase
import dev.dwhipstock.pos.restaurant.CheckService
import dev.dwhipstock.pos.restaurant.ConflictException
import dev.dwhipstock.pos.restaurant.CounterOrders
import dev.dwhipstock.pos.restaurant.CounterSettings
import dev.dwhipstock.pos.restaurant.KioskOrderLine
import dev.dwhipstock.pos.restaurant.KioskOrderRequest
import dev.dwhipstock.pos.restaurant.QuickServeService
import dev.dwhipstock.pos.sdk.KitchenPrinting
import dev.dwhipstock.pos.sdk.PrinterAdapter
import dev.dwhipstock.pos.sdk.StoreProfile
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Copper Lantern Express, one flow for every order: an order is stored from its
 * first item, numbered and sent to the kitchen only once it is paid; kiosk
 * orders wait to pay; only paid orders can be ready or picked up.
 */
class QuickServeTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDir(): File = Files.createTempDirectory("pos-express").toFile()
    private fun obj(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private fun service(day: () -> LocalDate): Pair<QuickServeService, CheckService> {
        val dir = tempDir()
        initDatabase(File(dir, "pos.db").path)
        CopperLanternExpressSeed.seedIfEmpty()
        val config = CopperLanternConfig(
            venue = CopperLanternVenue.EXPRESS, settings = SettingsRepository(),
            printer = PrinterAdapter.VirtualPrinter(File(dir, "r").path, File(dir, "b").path),
            publicBaseUrl = "http://127.0.0.1:8080",
        )
        val checks = CheckService(config)
        return QuickServeService(config, checks, today = day).also { it.ensureCounter() } to checks
    }

    @Test
    fun `the Express store is a quick-serve counter with a short beer-and-wine menu in four languages`() {
        val (_, _) = service { LocalDate.of(2026, 10, 8) }
        val config = CopperLanternConfig(venue = CopperLanternVenue.EXPRESS, settings = SettingsRepository(),
            printer = PrinterAdapter.VirtualPrinter("r", "b"), publicBaseUrl = "http://x")
        assertEquals(StoreProfile.Kind.QUICK_SERVE, config.profile.kind)
        assertEquals("express", config.venueId)
        assertEquals("copper-lantern", config.brand)
        assertEquals(listOf("en", "fr", "es", "de"), config.profile.locales.map { it.tag })
        assertEquals(StoreProfile.Kind.RESTAURANT,
            CopperLanternConfig(venue = CopperLanternVenue.PLATEAU, settings = SettingsRepository(),
                printer = PrinterAdapter.VirtualPrinter("r", "b"), publicBaseUrl = "http://x").profile.kind)
        transaction {
            val items = dev.dwhipstock.pos.base.Items.selectAll().toList()
            assertTrue(items.size in 15..20, "menu size ${items.size}")
            assertTrue("cocktails" !in dev.dwhipstock.pos.base.Categories.selectAll().map { it[dev.dwhipstock.pos.base.Categories.id] })
            // no floor plan: just the counter
            assertEquals(listOf(QuickServeService.COUNTER_TABLE),
                dev.dwhipstock.pos.restaurant.DiningTables.selectAll().map { it[dev.dwhipstock.pos.restaurant.DiningTables.id] })
            for (id in CopperLanternExpressSeed.menuItemIds) for (lang in listOf("es", "de"))
                assertTrue(dev.dwhipstock.pos.base.Translations.get("item", id, lang) != null, "$id has no $lang name")
        }
        // the dishes the pubs also serve keep the pub ids (their photos copy over as they are)
        assertTrue(CopperLanternExpressSeed.menuItemIds.containsAll(listOf("lantern-burger", "poutine", "late-fries", "wings")))
    }

    private data class Store(val qs: QuickServeService, val checks: CheckService, val config: CopperLanternConfig)

    private fun store(day: () -> LocalDate, clock: () -> Instant = { Instant.now() }): Store {
        val dir = tempDir()
        initDatabase(File(dir, "pos.db").path)
        CopperLanternExpressSeed.seedIfEmpty()
        val config = CopperLanternConfig(
            venue = CopperLanternVenue.EXPRESS, settings = SettingsRepository(),
            printer = PrinterAdapter.VirtualPrinter(File(dir, "r").path, File(dir, "b").path),
            publicBaseUrl = "http://127.0.0.1:8080",
        )
        val checks = CheckService(config)
        dev.dwhipstock.pos.restaurant.ShiftService(config).openShift("manager", 0)
        return Store(QuickServeService(config, checks, today = day, clock = clock).also { it.ensureCounter() }, checks, config)
    }

    private val burger = KioskOrderLine("lantern-burger", "lantern-burger:regular")
    private val brownie = KioskOrderLine("brownie", "brownie:regular")

    /** Cash for the whole order, then close it (what Pay on the POS does). */
    private fun Store.pay(checkId: Int) {
        checks.tenderCash(checkId, 100_000)
        checks.finalizeCheck(checkId)
    }

    @Test
    fun `a counter order is numbered only when it is paid - 101 up, again from 101 the next day`() {
        var day = LocalDate.of(2026, 10, 8)
        val s = store({ day })
        val a = s.qs.createAtPos("TAKE_OUT", burger, "manager")
        val b = s.qs.createAtPos("DINE_IN", brownie, "manager")
        assertNull(a.orderNumber, "no number while it is being rung")
        assertEquals("DRAFT", a.status)
        assertTrue(s.qs.list().isEmpty(), "unpaid orders are not on the Orders panel")
        assertTrue(s.qs.board().preparing.isEmpty(), "nor on the board")
        // paid in the other order: numbered in the order they are paid
        s.pay(b.checkId)
        s.pay(a.checkId)
        assertEquals(101, s.qs.view(b.checkId).orderNumber)
        assertEquals(102, s.qs.view(a.checkId).orderNumber)
        assertEquals("PREPARING", s.qs.view(a.checkId).status)
        assertEquals(listOf(101, 102), s.qs.board().preparing)
        assertEquals(listOf(102), s.qs.board().takeOut)
        // the receipt carries the number
        assertTrue(s.checks.receiptText(a.checkId).contains("#102"), s.checks.receiptText(a.checkId))
        day = day.plusDays(1)
        val c = s.qs.createAtPos("dine-in", burger, "manager")
        s.pay(c.checkId)
        assertEquals(101, s.qs.view(c.checkId).orderNumber)
        assertEquals(listOf(101), s.qs.board().preparing, "the board is today's only")
    }

    @Test
    fun `an empty order is never stored - the first item creates it, removing the last drops it`() {
        val s = store({ LocalDate.of(2026, 10, 8) })
        assertFailsWith<Exception> {
            s.qs.placeKioskOrder(KioskOrderRequest("TAKE_OUT", emptyList()), "Kiosk")
        }
        val a = s.qs.createAtPos("TAKE_OUT", burger, "manager")
        val line = s.checks.getCheck(a.checkId).lines.single()
        s.checks.removeLine(a.checkId, line.id)
        assertEquals("CANCELLED", s.checks.getCheck(a.checkId).status)
        transaction {
            assertTrue(CounterOrders.selectAll().where { CounterOrders.checkId eq a.checkId }.empty(), "no empty order row")
        }
        // discard: an unpaid order with items goes too
        val b = s.qs.createAtPos("TAKE_OUT", burger, "manager")
        s.qs.discard(b.checkId)
        assertEquals("CANCELLED", s.checks.getCheck(b.checkId).status)
        assertTrue(s.qs.list().isEmpty() && s.qs.waiting().isEmpty())
        // the next paid order is still 101
        val c = s.qs.createAtPos("TAKE_OUT", burger, "manager")
        s.pay(c.checkId)
        assertEquals(101, s.qs.view(c.checkId).orderNumber)
    }

    @Test
    fun `an unpaid order can't be ready or picked up, and dine in - take out changes until it is paid`() {
        val s = store({ LocalDate.of(2026, 10, 8) })
        val a = s.qs.createAtPos("TAKE_OUT", burger, "manager")
        for (st in listOf("READY", "PICKED_UP", "PREPARING")) {
            val e = assertFailsWith<ConflictException> { s.qs.setStatus(a.checkId, st) }
            assertEquals("order_not_paid", e.code)
        }
        // the kitchen screen can't make it ready either
        s.qs.kitchenDone(a.checkId)
        assertEquals("DRAFT", s.qs.view(a.checkId).status)
        assertEquals("DINE_IN", s.qs.setMode(a.checkId, "DINE_IN").serviceMode)
        s.pay(a.checkId)
        assertEquals("order_paid", assertFailsWith<ConflictException> { s.qs.setMode(a.checkId, "TAKE_OUT") }.code)
        assertEquals("DINE_IN", s.qs.view(a.checkId).serviceMode)
        assertEquals("READY", s.qs.setStatus(a.checkId, "READY").status)
        assertEquals(listOf(101), s.qs.board().ready)
        assertEquals("PICKED_UP", s.qs.setStatus(a.checkId, "PICKED_UP").status)
        assertTrue(s.qs.board().ready.isEmpty())
        // still on today's Orders panel, to recall or reprint
        assertEquals(listOf(101), s.qs.list().map { it.orderNumber })
    }

    @Test
    fun `a kiosk order waits to pay under its K number, expires after 30 minutes, and is numbered when paid`() {
        var now = Instant.parse("2026-10-08T16:00:00Z")
        val s = store({ LocalDate.of(2026, 10, 8) }, { now })
        val k1 = s.qs.placeKioskOrder(KioskOrderRequest("DINE_IN", listOf(burger, brownie)), "Kiosk")
        val k2 = s.qs.placeKioskOrder(KioskOrderRequest("TAKE_OUT", listOf(brownie)), "Kiosk")
        assertEquals(listOf(1, 2), listOf(k1.orderNumber, k2.orderNumber))
        assertEquals("K1", k1.displayNumber)
        assertEquals(listOf(k1.checkId, k2.checkId), s.qs.waiting().map { it.checkId })
        assertTrue(s.qs.board().preparing.isEmpty(), "unpaid: not on the board")
        assertTrue(s.qs.holdKitchen(k1.checkId), "unpaid: held from the kitchen")
        assertEquals("K1 · Sur place / Dine in",
            s.qs.ticketLabel(k1.checkId, dev.dwhipstock.pos.sdk.KitchenLanguage.BOTH))

        now = now.plusSeconds(10 * 60)
        s.pay(k1.checkId)
        val paid = s.qs.view(k1.checkId)
        assertEquals(101, paid.orderNumber)
        assertEquals(1, paid.kioskNumber)
        assertEquals("PREPARING", paid.status)
        assertFalse(s.qs.holdKitchen(k1.checkId))
        assertEquals(listOf(101), s.qs.board().preparing)
        assertEquals(listOf(k2.checkId), s.qs.waiting().map { it.checkId })

        // K2 never comes to pay
        now = now.plusSeconds(25 * 60)
        assertTrue(s.qs.waiting().isEmpty())
        assertEquals("CANCELLED", s.checks.getCheck(k2.checkId).status)
    }

    @Test
    fun `the old counter's leftovers are cleaned up - empty ones dropped, unpaid ones voided as test orders`() {
        val s = store({ LocalDate.of(2026, 10, 8) })
        // what the first counter left behind: numbered and listed before payment
        fun legacy(status: String, number: Int, withItem: Boolean): Int {
            val id = s.checks.openCounterCheck(QuickServeService.COUNTER_TABLE, "manager")
            if (withItem) s.checks.addLine(id, "lantern-burger", "lantern-burger:regular", 1, null)
            transaction {
                CounterOrders.insert {
                    it[checkId] = id; it[businessDate] = "2026-10-08"; it[orderNumber] = number
                    it[serviceMode] = "TAKE_OUT"; it[orderSource] = "POS"; it[CounterOrders.status] = status
                    it[createdAt] = Instant.now()
                }
            }
            return id
        }
        val empty1 = legacy("NEW", 102, withItem = false)
        val empty2 = legacy("NEW", 104, withItem = false)
        val released = legacy("PICKED_UP", 105, withItem = true)
        assertEquals(3, s.qs.cleanupLegacy())
        assertEquals(0, s.qs.cleanupLegacy(), "once")
        assertEquals("CANCELLED", s.checks.getCheck(empty1).status)
        assertEquals("CANCELLED", s.checks.getCheck(empty2).status)
        val v = s.checks.getCheck(released)
        assertEquals("VOID", v.status)
        transaction {
            assertEquals(QuickServeService.LEGACY_VOID_REASON,
                dev.dwhipstock.pos.restaurant.Checks.selectAll().where { dev.dwhipstock.pos.restaurant.Checks.id eq released }
                    .single()[dev.dwhipstock.pos.restaurant.Checks.voidReason])
            assertEquals(listOf(released), CounterOrders.selectAll().map { it[CounterOrders.checkId] })
        }
        assertTrue(s.qs.list().isEmpty() && s.qs.board().preparing.isEmpty() && s.qs.waiting().isEmpty())
        // numbering carries on past the old numbers
        val a = s.qs.createAtPos("TAKE_OUT", burger, "manager")
        s.pay(a.checkId)
        assertEquals(106, s.qs.view(a.checkId).orderNumber)
    }

    @Test
    fun `the default dine in - take out is a counter setting, and reports count both`() {
        val s = store({ LocalDate.of(2026, 10, 8) })
        assertEquals("TAKE_OUT", s.qs.settings().defaultServiceMode)
        assertEquals("DINE_IN", s.qs.updateSettings(CounterSettings("dine-in")).defaultServiceMode)
        assertEquals("DINE_IN", s.qs.settings().defaultServiceMode)
        assertFailsWith<IllegalArgumentException> { s.qs.updateSettings(CounterSettings("DRIVE_THRU")) }
        for (mode in listOf("DINE_IN", "TAKE_OUT", "TAKE_OUT")) s.pay(s.qs.createAtPos(mode, burger, "manager").checkId)
        s.qs.createAtPos("DINE_IN", burger, "manager") // unpaid: not counted
        val report = dev.dwhipstock.pos.restaurant.ShiftService(s.config).xReport()
        assertEquals(1, report.dineInCount)
        assertEquals(2, report.takeOutCount)
    }

    private suspend fun HttpClient.postJson(path: String, body: String, token: String? = null) = post(path) {
        contentType(ContentType.Application.Json); setBody(body)
        token?.let { header("X-Device-Token", it) }
    }

    @Test
    fun `a kiosk order waits unpaid, the counter takes the money, and only then the kitchen and the board get it`() = testApplication {
        val fake = FakeKitchenTransport()
        application {
            module(dbPath = File(tempDir(), "pos.db").path, venueId = "express", physicalPrinterEnabled = false,
                kitchenPrinting = KitchenPrinting.Resolved(KitchenPrinting.ON, "test"), kitchenTransport = fake)
        }
        val health = obj(client.get("/health").bodyAsText())
        assertEquals("quick-serve", health["kind"]!!.jsonPrimitive.content)
        assertEquals("express", health["venueId"]!!.jsonPrimitive.content)

        // unpaired: refused
        val order = """{"serviceMode":"TAKE_OUT","lines":[{"itemId":"lantern-burger","variantId":"lantern-burger:regular","qty":2},{"itemId":"north-ipa","variantId":"north-ipa:16oz"}]}"""
        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/kiosk/orders", order).status)
        assertEquals(HttpStatusCode.NotFound, client.postJson("/kiosk/pair", """{"code":"000000"}""").status)

        val staff = loginClient()
        assertEquals(HttpStatusCode.Forbidden, loginClient("9999").post("/counter/kiosk-code").status)
        val code = obj(staff.post("/counter/kiosk-code").bodyAsText())["code"]!!.jsonPrimitive.content
        val pair = obj(client.postJson("/kiosk/pair", """{"code":"$code","deviceName":"Door 1"}""").bodyAsText())
        val token = pair["deviceToken"]!!.jsonPrimitive.content
        // one-time code
        assertEquals(HttpStatusCode.NotFound, client.postJson("/kiosk/pair", """{"code":"$code"}""").status)
        // a second kiosk pairs with its own code
        val code2 = obj(staff.post("/counter/kiosk-code").bodyAsText())["code"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.OK, client.postJson("/kiosk/pair", """{"code":"$code2","deviceName":"Door 2"}""").status)
        assertEquals(HttpStatusCode.OK, client.get("/kiosk/config") { header("X-Device-Token", token) }.status)

        // the menu the kiosk shows is the open catalog
        assertTrue(json.parseToJsonElement(client.get("/items").bodyAsText()).jsonArray.size >= 15)

        val placed = client.postJson("/kiosk/orders", order, token)
        assertEquals(HttpStatusCode.OK, placed.status, placed.bodyAsText())
        val res = obj(placed.bodyAsText())
        assertEquals(1, res["orderNumber"]!!.jsonPrimitive.int)
        assertEquals("K1", res["displayNumber"]!!.jsonPrimitive.content)
        assertTrue(res["idCheckAtCounter"]!!.jsonPrimitive.boolean)
        val checkId = res["checkId"]!!.jsonPrimitive.int

        // on the bill, not waiting for approval, and unpaid
        val check = obj(staff.get("/checks/$checkId").bodyAsText())
        assertEquals("OPEN", check["status"]!!.jsonPrimitive.content)
        assertEquals(2, check["lines"]!!.jsonArray.size)
        assertTrue(check["pendingLines"]?.jsonArray?.isEmpty() ?: true)

        // in the counter's kiosk queue; not in the kitchen, not on the board
        val waiting = json.parseToJsonElement(staff.get("/counter/waiting").bodyAsText()).jsonArray.map { it.jsonObject }
        assertEquals(listOf(checkId), waiting.map { it["checkId"]!!.jsonPrimitive.int })
        assertEquals("WAITING", waiting.single()["status"]!!.jsonPrimitive.content)
        fun cards() = kotlinx.coroutines.runBlocking {
            obj(staff.get("/kitchen/board").bodyAsText())["cards"]!!.jsonArray.map { it.jsonObject }
                .filter { it["checkId"]!!.jsonPrimitive.int == checkId }
        }
        // the POS leaving the order (a best-effort send) sends nothing either
        staff.post("/checks/$checkId/kitchen/send")
        assertTrue(cards().isEmpty(), "unpaid: nothing in the kitchen")
        assertTrue(fake.sent.isEmpty(), "no ticket printed")
        fun pickup() = kotlinx.coroutines.runBlocking { obj(client.get("/pickup/board").bodyAsText()) }
        assertTrue(pickup()["preparing"]!!.jsonArray.isEmpty())
        assertEquals(HttpStatusCode.Conflict, staff.postJson("/counter/orders/$checkId/status", """{"status":"READY"}""").status)

        // the counter takes the money: numbered, to the kitchen and the board
        staff.postJson("/shifts", """{"openingFloatCents":0,"managerPin":"1234"}""")
        assertTrue(staff.postJson("/checks/$checkId/tenders", """{"type":"CASH","amountTenderedCents":100000}""").status.isSuccess())
        assertEquals(HttpStatusCode.OK, staff.post("/checks/$checkId/finalize").status)
        val paid = obj(staff.get("/counter/orders/$checkId").bodyAsText())
        assertEquals(101, paid["orderNumber"]!!.jsonPrimitive.int)
        assertEquals("PREPARING", paid["status"]!!.jsonPrimitive.content)
        assertTrue(json.parseToJsonElement(staff.get("/counter/waiting").bodyAsText()).jsonArray.isEmpty())
        assertEquals(setOf("kitchen", "bar"), cards().map { it["stationId"]!!.jsonPrimitive.content }.toSet())
        assertTrue(cards().all { it["tableLabel"]!!.jsonPrimitive.content.startsWith("#101") }, cards().toString())
        assertEquals(HttpStatusCode.OK, client.get("/pickup").status)
        assertEquals(listOf(101), pickup()["preparing"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertEquals(listOf(101), pickup()["takeOut"]!!.jsonArray.map { it.jsonPrimitive.int })

        // the kitchen bumps its card: still preparing until the bar bumps too
        staff.postJson("/kitchen/board/bump", """{"checkId":$checkId,"stationId":"kitchen"}""")
        assertEquals(listOf(101), pickup()["preparing"]!!.jsonArray.map { it.jsonPrimitive.int })
        staff.postJson("/kitchen/board/bump", """{"checkId":$checkId,"stationId":"bar"}""")
        assertEquals(listOf(101), pickup()["ready"]!!.jsonArray.map { it.jsonPrimitive.int })
        staff.postJson("/counter/orders/$checkId/status", """{"status":"PICKED_UP"}""")
        assertTrue(pickup()["ready"]!!.jsonArray.isEmpty())

        // a POS order: stored with its first item, dine in / take out until paid
        assertEquals(HttpStatusCode.BadRequest, staff.postJson("/counter/orders", """{"serviceMode":"DINE_IN"}""").status)
        val pos = obj(staff.postJson("/counter/orders",
            """{"serviceMode":"DINE_IN","itemId":"brownie","variantId":"brownie:regular"}""").bodyAsText())
        val posId = pos["checkId"]!!.jsonPrimitive.int
        assertEquals("DRAFT", pos["status"]!!.jsonPrimitive.content)
        assertNull(pos["orderNumber"]?.jsonPrimitive?.intOrNull)
        assertEquals("TAKE_OUT", obj(staff.postJson("/counter/orders/$posId/mode", """{"serviceMode":"TAKE_OUT"}""").bodyAsText())["serviceMode"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, staff.post("/counter/orders/$posId/discard").status)
        assertEquals("CANCELLED", obj(staff.get("/checks/$posId").bodyAsText())["status"]!!.jsonPrimitive.content)

        // the counter setting: managers only
        assertEquals("TAKE_OUT", obj(staff.get("/counter/settings").bodyAsText())["defaultServiceMode"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Forbidden, loginClient("9999").put("/counter/settings") {
            contentType(ContentType.Application.Json); setBody("""{"defaultServiceMode":"DINE_IN"}""")
        }.status)

        // what syncs to the portal: the Express store's closed check, like any cpr store's
        transaction {
            val types = dev.dwhipstock.pos.db.SyncOutbox.selectAll().map { it[dev.dwhipstock.pos.db.SyncOutbox.eventType] }
            assertTrue("check.closed" in types, types.toString())
            assertTrue("check.pending_line_submitted" !in types, "no QR-style approval step")
        }
    }

    @Test
    fun `the full-service pubs have no counter routes`() = testApplication {
        application { module(dbPath = File(tempDir(), "pos.db").path, venueId = "plateau", physicalPrinterEnabled = false) }
        assertEquals("restaurant", obj(client.get("/health").bodyAsText())["kind"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound, client.get("/pickup/board").status)
        assertEquals(HttpStatusCode.NotFound, client.postJson("/kiosk/orders", "{}").status)
        assertNull(obj(client.get("/health").bodyAsText())["forecourt"])
        assertFalse(loginClient().get("/counter/orders").status == HttpStatusCode.OK)
    }
}
