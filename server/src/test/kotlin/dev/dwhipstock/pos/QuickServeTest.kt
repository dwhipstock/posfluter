package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.SettingsRepository
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternConfig
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternExpressSeed
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue
import dev.dwhipstock.pos.db.initDatabase
import dev.dwhipstock.pos.restaurant.CheckService
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Copper Lantern Express: numbered counter orders, self-order kiosks, the pickup board. */
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

    @Test
    fun `order numbers start at 101, count up, and restart on the next business day`() {
        var day = LocalDate.of(2026, 10, 8)
        val (qs, _) = service { day }
        assertEquals(listOf(101, 102, 103), (1..3).map { qs.createAtPos("TAKE_OUT", "manager").orderNumber })
        val k = qs.placeKioskOrder(dev.dwhipstock.pos.restaurant.KioskOrderRequest("DINE_IN",
            listOf(dev.dwhipstock.pos.restaurant.KioskOrderLine("late-fries", "late-fries:small"))), "Kiosk")
        assertEquals(104, k.orderNumber)
        day = day.plusDays(1)
        assertEquals(101, qs.createAtPos("dine-in", "manager").orderNumber)
        assertEquals(102, qs.createAtPos("TAKE_OUT", "manager").orderNumber)
        // yesterday's unpaid orders still show on the POS; the board is today's only
        assertEquals(6, qs.list().size)
        assertTrue(qs.board().preparing.isEmpty())
    }

    @Test
    fun `board status flow - preparing, ready, picked up drops off, a void is never called`() {
        val (qs, checks) = service { LocalDate.of(2026, 10, 8) }
        val a = qs.createAtPos("TAKE_OUT", "manager")
        assertTrue(qs.board().preparing.isEmpty(), "an order still being rung is not on the board")
        checks.addLine(a.checkId, "lantern-burger", "lantern-burger:regular", 1, null)
        assertEquals("PREPARING", qs.place(a.checkId, "Demo").status)
        val b = qs.placeKioskOrder(dev.dwhipstock.pos.restaurant.KioskOrderRequest("TAKE_OUT",
            listOf(dev.dwhipstock.pos.restaurant.KioskOrderLine("brownie", "brownie:regular"))), "Kiosk")
        assertEquals(listOf(101, 102), qs.board().preparing)
        qs.setStatus(a.checkId, "READY")
        assertEquals(listOf(102), qs.board().preparing)
        assertEquals(listOf(101), qs.board().ready)
        qs.setStatus(a.checkId, "PICKED_UP")
        assertEquals(emptyList(), qs.board().ready)
        checks.voidCheck(b.checkId, "customer left", "manager")
        assertTrue(qs.board().preparing.isEmpty())
        assertTrue(qs.list().none { it.checkId == b.checkId })
    }

    private suspend fun HttpClient.postJson(path: String, body: String, token: String? = null) = post(path) {
        contentType(ContentType.Application.Json); setBody(body)
        token?.let { header("X-Device-Token", it) }
    }

    @Test
    fun `a kiosk pairs with the POS code, its order goes straight to the kitchen unpaid, and the KDS bump makes it ready`() = testApplication {
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
        assertEquals(101, res["orderNumber"]!!.jsonPrimitive.int)
        assertTrue(res["idCheckAtCounter"]!!.jsonPrimitive.boolean)
        val checkId = res["checkId"]!!.jsonPrimitive.int

        // on the bill, not waiting for approval, and unpaid
        val check = obj(staff.get("/checks/$checkId").bodyAsText())
        assertEquals("OPEN", check["status"]!!.jsonPrimitive.content)
        assertEquals(2, check["lines"]!!.jsonArray.size)
        assertTrue(check["pendingLines"]?.jsonArray?.isEmpty() ?: true)
        assertTrue(check["outstandingCents"]!!.jsonPrimitive.int > 0)

        // straight to the kitchen screen, called by its number
        val board = obj(staff.get("/kitchen/board").bodyAsText())
        val cards = board["cards"]!!.jsonArray.map { it.jsonObject }.filter { it["checkId"]!!.jsonPrimitive.int == checkId }
        assertEquals(setOf("kitchen", "bar"), cards.map { it["stationId"]!!.jsonPrimitive.content }.toSet())
        assertTrue(cards.all { it["tableLabel"]!!.jsonPrimitive.content.startsWith("#101") }, cards.toString())

        // the pickup board (open to any browser) shows it preparing
        assertEquals(HttpStatusCode.OK, client.get("/pickup").status)
        fun pickup() = kotlinx.coroutines.runBlocking { obj(client.get("/pickup/board").bodyAsText()) }
        assertEquals(listOf(101), pickup()["preparing"]!!.jsonArray.map { it.jsonPrimitive.int })

        // the kitchen bumps its card: still preparing until the bar bumps too
        staff.postJson("/kitchen/board/bump", """{"checkId":$checkId,"stationId":"kitchen"}""")
        assertEquals(listOf(101), pickup()["preparing"]!!.jsonArray.map { it.jsonPrimitive.int })
        staff.postJson("/kitchen/board/bump", """{"checkId":$checkId,"stationId":"bar"}""")
        assertEquals(listOf(101), pickup()["ready"]!!.jsonArray.map { it.jsonPrimitive.int })

        // the counter takes the money, hands it over: off the board and off the list
        staff.postJson("/shifts", """{"openingFloatCents":0,"managerPin":"1234"}""")
        assertTrue(staff.postJson("/checks/$checkId/tenders", """{"type":"CASH","amountTenderedCents":100000}""").status.isSuccess())
        assertEquals(HttpStatusCode.OK, staff.post("/checks/$checkId/finalize").status)
        val listed = json.parseToJsonElement(staff.get("/counter/orders").bodyAsText()).jsonArray.map { it.jsonObject }
        assertEquals("CLOSED", listed.single { it["checkId"]!!.jsonPrimitive.int == checkId }["checkStatus"]!!.jsonPrimitive.content)
        staff.postJson("/counter/orders/$checkId/status", """{"status":"PICKED_UP"}""")
        assertTrue(pickup()["ready"]!!.jsonArray.isEmpty())
        assertTrue(json.parseToJsonElement(staff.get("/counter/orders").bodyAsText()).jsonArray.isEmpty())

        // a POS order: new (not on the board) until placed
        val pos = obj(staff.postJson("/counter/orders", """{"serviceMode":"DINE_IN"}""").bodyAsText())
        assertEquals(102, pos["orderNumber"]!!.jsonPrimitive.int)
        assertEquals("NEW", pos["status"]!!.jsonPrimitive.content)

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
