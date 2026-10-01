package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.SettingsRepository
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternConfig
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternExpressSeed
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.db.initDatabase
import dev.dwhipstock.pos.payments.simulator.SimulatedTerminalDevice
import dev.dwhipstock.pos.restaurant.CheckService
import dev.dwhipstock.pos.restaurant.CounterOrders
import dev.dwhipstock.pos.restaurant.KioskOrderLine
import dev.dwhipstock.pos.restaurant.KioskOrderRequest
import dev.dwhipstock.pos.restaurant.QuickServeService
import dev.dwhipstock.pos.restaurant.Refunds
import dev.dwhipstock.pos.restaurant.ShiftService
import dev.dwhipstock.pos.sdk.PaymentTerminalConfig
import dev.dwhipstock.pos.sdk.PrinterAdapter
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rest of the money red team (2026-10-01) beyond RedTeamMoneyTest: refunds
 * go back the way the guest paid, a partly paid bill can be cancelled by
 * handing the money back, more of an 86'd item is refused, guests with the
 * same items in a by-item split pay the same, card tips are in the reports,
 * and merging a kiosk order keeps one number per guest.
 * Copper Lantern Glenwood South: lager pint $7.50 → $8.09 with tax.
 */
class RedTeamMoneyFixesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-redteam").resolve("pos.db").toString()

    private suspend fun HttpClient.postJson(path: String, body: String = "{}") =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }
    private suspend fun HttpResponse.obj(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.code(): String = obj()["code"]!!.jsonPrimitive.content
    private fun JsonObject.l(k: String) = this[k]!!.jsonPrimitive.long

    private suspend fun HttpClient.open(table: String): Int = postJson("/tables/$table/checks").obj()["id"]!!.jsonPrimitive.int
    private suspend fun HttpClient.lager(id: Int, qty: Int = 1): JsonObject =
        postJson("/checks/$id/lines", """{"itemId":"lantern-lager","variantId":"lantern-lager:pint","qty":$qty}""").obj()
    private suspend fun HttpClient.cash(id: Int, cents: Long, group: Int? = null) =
        postJson("/checks/$id/tenders", """{"type":"CASH","amountTenderedCents":$cents${group?.let { ",\"groupId\":$it" } ?: ""}}""")

    private fun lastPayload(eventType: String): JsonObject = transaction {
        Json.parseToJsonElement(SyncOutbox.selectAll().where { SyncOutbox.eventType eq eventType }
            .orderBy(SyncOutbox.id, SortOrder.DESC).first()[SyncOutbox.payload]).jsonObject
    }

    @Test
    fun `a refund goes back the way the guest paid unless a manager overrides`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        c.postJson("/shifts", """{"openingFloatCents":10000}""")
        val id = c.open("t5"); c.lager(id)
        c.postJson("/checks/$id/tenders/confirm", """{"type":"CARD","amountCents":809}""")
        assertEquals(HttpStatusCode.OK, c.post("/checks/$id/finalize").status)
        // a card sale: not out of the drawer in cash
        val cash = c.postJson("/checks/$id/refund", """{"amountCents":809,"tenderType":"CASH","reason":"x"}""")
        assertEquals(HttpStatusCode.Conflict, cash.status)
        assertEquals("refund_tender_mismatch", cash.code())
        // a manager can approve another way, and it is recorded
        val override = c.postJson("/checks/$id/refund",
            """{"amountCents":300,"tenderType":"CASH","reason":"card declined at the bank","overrideTender":true}""")
        assertEquals(HttpStatusCode.Created, override.status, override.bodyAsText())
        assertEquals("manager", transaction { Refunds.selectAll().first()[Refunds.overrideBy] })
        assertEquals("manager", lastPayload("refund.created")["tenderOverrideBy"]!!.jsonPrimitive.content)
        // back to the card: up to what the card paid
        assertEquals(HttpStatusCode.Created,
            c.postJson("/checks/$id/refund", """{"amountCents":509,"tenderType":"CARD","reason":"x"}""").status)
        assertEquals(HttpStatusCode.Conflict,
            c.postJson("/checks/$id/refund", """{"amountCents":1,"tenderType":"CARD","reason":"x"}""").status)
    }

    @Test
    fun `more of an item that was 86'd is refused, less is fine`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val id = c.open("t5")
        val lineId = c.lager(id, 2)["lines"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.int
        c.postJson("/items/lantern-lager/availability", """{"active":false,"managerPin":"1234"}""")
        val more = c.postJson("/checks/$id/lines/$lineId/qty", """{"qty":3}""")
        assertEquals(HttpStatusCode.Conflict, more.status)
        assertEquals("item_unavailable", more.code())
        val less = c.postJson("/checks/$id/lines/$lineId/qty", """{"qty":1}""")
        assertEquals(HttpStatusCode.OK, less.status)
        assertEquals(1, less.obj()["lines"]!!.jsonArray.single().jsonObject["qty"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a partly paid bill can be cancelled by a manager handing the money back`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val server = loginClient("9999")
        c.postJson("/shifts", """{"openingFloatCents":10000}""")
        val id = server.open("t5"); server.lager(id, 2) // $16.16
        assertEquals(HttpStatusCode.Created, server.cash(id, 500).status)
        // a plain void still refuses: there is money on it
        val plain = c.postJson("/checks/$id/void", """{"reason":"walked out"}""")
        assertEquals("void_has_tenders", plain.code())
        // a server can't hand money back without a manager
        assertEquals(HttpStatusCode.Forbidden,
            server.postJson("/checks/$id/void", """{"reason":"walked out","reverseTenders":true}""").status)
        val voided = server.postJson("/checks/$id/void", """{"reason":"walked out","reverseTenders":true,"managerPin":"1234"}""")
        assertEquals(HttpStatusCode.OK, voided.status, voided.bodyAsText())
        assertEquals("VOID", voided.obj()["status"]!!.jsonPrimitive.content)
        val reversed = lastPayload("check.voided")["reversedTenders"]!!.jsonArray.single().jsonObject
        assertEquals(500L, reversed.l("amountReturnedCents"))
        assertEquals(500L, lastPayload("check.tender_reversed").l("amountReturnedCents"))
        // the $5 came in and went back: the drawer is the float; the report shows the void
        val x = c.get("/shifts/current/report").obj()
        assertEquals(10000L, x.l("expectedCashCents"))
        assertEquals(500L, x["voids"]!!.jsonArray.single().jsonObject.l("reversedCents"))
        // and nothing blocks the Z any more
        assertEquals(HttpStatusCode.OK, c.postJson("/shifts/current/close", """{"closingCountCents":10000}""").status)
    }

    @Test
    fun `guests with the same items in a by-item split pay the same`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val id = c.open("t5")
        val a = c.lager(id, 2)["lines"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.int
        val split = c.postJson("/checks/$id/split", """{"groups":2}""").obj()["split"]!!.jsonObject
        val (g1, g2) = split["groups"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.int }
        c.postJson("/checks/$id/split/groups/$g1/lines", """{"lineId":$a,"qty":1}""")
        val done = c.postJson("/checks/$id/split/groups/$g2/lines", """{"lineId":$a,"qty":1}""").obj()
        // $15.00: NC 6.75% 1.0125 → 1.01, Wake 1% 0.15. Each tax's odd cent used to go to
        // guest 1: $8.09 and $8.07. Now the $1.16 of tax is shared first: $8.08 each
        val totals = done["split"]!!.jsonObject["groups"]!!.jsonArray.map { it.jsonObject.l("grandTotalCents") }
        assertEquals(listOf(808L, 808L), totals)
        assertEquals(done.l("grandTotalCents"), totals.sum())
    }

    @Test
    fun `card tips are on the X report per tender and per server, and in the check closed sync`() = testApplication {
        val device = SimulatedTerminalDevice(clock = AtomicLong(1_000_000L)::get,
            delays = SimulatedTerminalDevice.Delays.INSTANT, random = kotlin.random.Random(7))
        val simulator = PaymentTerminalConfig.resolve({ if (it == PaymentTerminalConfig.KEY) "simulator" else null }, "test")
        application { module(dbPath = tempDb(), paymentTerminal = simulator, terminalDevice = device) }
        val c = loginClient()
        c.postJson("/shifts", """{"openingFloatCents":10000}""")
        val id = c.open("t5-5"); c.lager(id, 4) // $32.33
        val pid = c.postJson("/checks/$id/terminal/payments", """{"tipMode":"on_reader"}""").obj()["paymentId"]!!.jsonPrimitive.content
        c.postJson("/terminal/ui/tip", """{"tipCents":600}""")
        c.postJson("/terminal/ui/present", """{"entry":"tap","card":"visa","outcome":"approve"}""")
        assertEquals("RECORDED", c.get("/terminal/payments/$pid").obj()["status"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, c.post("/checks/$id/finalize").status)
        val x = c.get("/shifts/current/report").obj()
        assertEquals(600L, x.l("tipsCents"))
        val terminal = x["tenderBreakdown"]!!.jsonArray.single().jsonObject
        assertEquals(3233L, terminal.l("amountCents")) // the bill, not the tip
        assertEquals(600L, terminal.l("tipCents"))
        val byServer = x["tipsByServer"]!!.jsonArray.single().jsonObject
        assertEquals("manager", byServer["userId"]!!.jsonPrimitive.content)
        assertEquals(600L, byServer.l("tipCents"))
        assertEquals(600L, lastPayload("check.closed")["tenders"]!!.jsonArray.single().jsonObject.l("tipCents"))
        // the Z sync echo carries them too
        c.postJson("/shifts/current/close", """{"closingCountCents":10000}""")
        val z = lastPayload("shift.closed")
        assertEquals(600L, z.l("tipsCents"))
        assertEquals(600L, z["tenderBreakdown"]!!.jsonArray.single().jsonObject.l("tipCents"))
    }

    // --- quick-serve: one number per guest when orders are merged

    private class Store(val qs: QuickServeService, val checks: CheckService)

    private fun express(): Store {
        val dir = Files.createTempDirectory("pos-express").toFile()
        initDatabase(File(dir, "pos.db").path)
        CopperLanternExpressSeed.seedIfEmpty()
        val config = CopperLanternConfig(
            venue = CopperLanternVenue.EXPRESS, settings = SettingsRepository(),
            printer = PrinterAdapter.VirtualPrinter(File(dir, "r").path, File(dir, "b").path),
            publicBaseUrl = "http://127.0.0.1:8080",
        )
        val checks = CheckService(config)
        ShiftService(config).openShift("manager", 0)
        return Store(QuickServeService(config, checks, today = { LocalDate.of(2026, 10, 8) }).also { it.ensureCounter() }, checks)
    }

    private val burger = KioskOrderLine("lantern-burger", "lantern-burger:regular")
    private val brownie = KioskOrderLine("brownie", "brownie:regular")

    @Test
    fun `merging a kiosk order into a counter order keeps the kiosk number, never a stuck WAITING one`() {
        val s = express()
        val kiosk = s.qs.placeKioskOrder(KioskOrderRequest("TAKE_OUT", listOf(burger)), "Kiosk")
        assertEquals(101, kiosk.orderNumber)
        val counter = s.qs.createAtPos("TAKE_OUT", brownie, "manager")
        s.checks.mergeCheck(kiosk.checkId, counter.checkId)
        // main: #101 stayed WAITING on a MERGED check, and the paid order got #102
        assertTrue(s.qs.waiting().isEmpty())
        assertNull(transaction { CounterOrders.selectAll().where { CounterOrders.checkId eq kiosk.checkId }.firstOrNull() })
        assertEquals(101, s.qs.view(counter.checkId).orderNumber)
        s.checks.tenderCash(counter.checkId, 100_000)
        s.checks.finalizeCheck(counter.checkId)
        val paid = s.qs.view(counter.checkId)
        assertEquals(101, paid.orderNumber, "the number on the guest's ticket")
        assertEquals("PREPARING", paid.status)
        assertEquals(listOf(101), s.qs.board().preparing)

        // two kiosk orders: the destination keeps its own; the other closes out, never WAITING
        val k2 = s.qs.placeKioskOrder(KioskOrderRequest("TAKE_OUT", listOf(burger)), "Kiosk")
        val k3 = s.qs.placeKioskOrder(KioskOrderRequest("TAKE_OUT", listOf(brownie)), "Kiosk")
        s.checks.mergeCheck(k2.checkId, k3.checkId)
        assertEquals(listOf(k3.checkId), s.qs.waiting().map { it.checkId })
        assertEquals("CANCELLED", s.qs.view(k2.checkId).status)
        assertEquals(k3.orderNumber, s.qs.view(k3.checkId).orderNumber)
    }
}
