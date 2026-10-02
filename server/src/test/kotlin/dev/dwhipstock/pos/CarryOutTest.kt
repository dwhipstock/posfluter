package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.ConflictException
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.orders.PickupOrders
import dev.dwhipstock.pos.orders.SaleLocations
import dev.dwhipstock.pos.restaurant.CarryOutCustomerRequest
import dev.dwhipstock.pos.restaurant.CarryOutService
import dev.dwhipstock.pos.restaurant.NewCarryOutRequest
import dev.dwhipstock.pos.restaurant.ShiftService
import dev.dwhipstock.pos.sdk.Capability
import dev.dwhipstock.pos.sdk.StoreProfile
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
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Carry-out at the full-service pub (Glenwood South): the quick-serve
 * counter's numbered orders, reused. Numbered when taken, a normal check off
 * the floor, TO GO on the kitchen ticket, paid now or at pickup, on the
 * pickup board once at the kitchen, picked up only once paid.
 */
class CarryOutTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun obj(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private class Store(val f: KitchenFixture, val orders: PickupOrders, val carry: CarryOutService)

    /** A seeded pub with kitchen tickets, wired the way Application wires it. */
    private fun store(day: LocalDate = LocalDate.of(2026, 10, 8)): Store {
        val f = KitchenFixture(CopperLanternVenue.VIEUX_PORT)
        val orders = PickupOrders(f.config, f.checks, today = { day })
        orders.kitchen = f.kitchen
        f.kitchen.holdSend = orders::holdKitchen
        f.kitchen.ticketLabel = orders::ticketLabel
        f.kitchen.onCheckDone = orders::kitchenDone
        f.kitchen.onSent = orders::kitchenSent
        val carry = CarryOutService(f.checks, orders).also { it.ensureLocation() }
        ShiftService(f.config).openShift("manager", 0)
        return Store(f, orders, carry)
    }

    private fun Store.pay(checkId: Int) {
        f.checks.tenderCash(checkId, 100_000)
        f.checks.finalizeCheck(checkId)
    }

    @Test
    fun `a restaurant has carry-out and the pickup board by default and quick serve keeps the counter`() {
        assertEquals(setOf(Capability.CARRY_OUT, Capability.PICKUP_BOARD), Capability.defaultsFor(StoreProfile.Kind.RESTAURANT))
        assertEquals(setOf(Capability.COUNTER_ORDERS, Capability.KIOSKS, Capability.PICKUP_BOARD),
            Capability.defaultsFor(StoreProfile.Kind.QUICK_SERVE))
        assertTrue(Capability.defaultsFor(StoreProfile.Kind.RETAIL).isEmpty())
    }

    @Test
    fun `orders are numbered from 101 per store and day when taken, with the customer`() {
        val s = store()
        val a = s.carry.create("server1", NewCarryOutRequest("  Sam ", "(919) 555-0100"))
        val b = s.carry.create("server1")
        assertEquals(101, a.orderNumber)
        assertEquals(102, b.orderNumber)
        assertEquals("Sam", a.customerName)
        assertEquals("(919) 555-0100", a.customerPhone)
        assertEquals("CARRY_OUT", a.source)
        assertEquals("OPEN", a.status)
        assertEquals(listOf(101, 102), s.carry.list().map { it.orderNumber })
        assertEquals(2, s.carry.summary().openCount)
        // another store (its own database) starts at 101 again
        val other = store()
        assertEquals(101, other.carry.create("server1").orderNumber)
        // a new business day starts again
        val tomorrow = store(LocalDate.of(2026, 10, 9))
        assertEquals(101, tomorrow.carry.create("server1").orderNumber)
        assertFailsWith<IllegalArgumentException> { s.carry.create("server1", NewCarryOutRequest(customerPhone = "call me")) }
    }

    @Test
    fun `pay at pickup - kitchen gets TO GO, ready unpaid, picked up only once paid`() {
        val s = store()
        val o = s.carry.create("server1", NewCarryOutRequest("Sam"))
        s.f.add(o.checkId, "lantern-burger")
        // nothing at the kitchen yet: not on the board
        assertTrue(s.orders.board().preparing.isEmpty())
        val sent = s.f.kitchen.send(o.checkId)
        assertTrue(sent.tickets > 0, "an unpaid carry-out order goes to the kitchen on send")
        val printed = s.f.drain().joinToString("\n") { it.text }
        assertTrue("#101 · TO GO · Sam" in printed || "#101 · À EMPORTER" in printed, printed)
        assertEquals("PREPARING", s.carry.view(o.checkId).status)
        assertEquals(listOf(101), s.orders.board().preparing)
        assertEquals(listOf(101), s.orders.board().takeOut)

        assertEquals("READY", s.carry.setStatus(o.checkId, "READY").status)
        assertEquals(listOf(101), s.orders.board().ready)
        val e = assertFailsWith<ConflictException> { s.carry.setStatus(o.checkId, "PICKED_UP") }
        assertEquals("order_not_paid", e.code)

        s.pay(o.checkId)
        val paid = s.carry.view(o.checkId)
        assertEquals("CLOSED", paid.checkStatus)
        assertEquals("READY", paid.status, "paying does not move it")
        assertEquals("PICKED_UP", s.carry.setStatus(o.checkId, "PICKED_UP").status)
        assertTrue(s.orders.board().ready.isEmpty())
        assertTrue(s.carry.list().isEmpty())
    }

    @Test
    fun `pay now before anything was sent - the kitchen gets it on payment`() {
        val s = store()
        val o = s.carry.create("server1")
        s.f.add(o.checkId, "lantern-burger")
        s.pay(o.checkId)
        assertEquals("PREPARING", s.carry.view(o.checkId).status)
        assertTrue(s.f.drain().any { "TO GO" in it.text || "À EMPORTER" in it.text })
        assertEquals(listOf(101), s.orders.board().preparing)
        // the kitchen screen's last bump makes it ready
        s.orders.kitchenDone(o.checkId)
        assertEquals("READY", s.carry.view(o.checkId).status)
    }

    @Test
    fun `the bill and receipt say Order 101 Carry-out, never a table`() {
        val s = store()
        val o = s.carry.create("server1", NewCarryOutRequest("Sam"))
        s.f.add(o.checkId, "lantern-burger")
        val bill = s.f.checks.printBill(o.checkId, lang = "en")
        assertTrue("Order #101 · Carry-out" in bill, bill)
        assertTrue("For: Sam" in bill, bill)
        assertFalse("Table Carry-out" in bill, bill)
        s.pay(o.checkId)
        val receipt = s.f.checks.receiptText(o.checkId, lang = "fr")
        assertTrue("Commande n° 101 · À emporter" in receipt, receipt)
    }

    @Test
    fun `carry-out sits off the floor - no opening by table, no move, no merge`() {
        val s = store()
        val e = assertFailsWith<ConflictException> { s.f.checks.openCheck(SaleLocations.CARRY_OUT_TABLE, "server1") }
        assertEquals("not_a_table", e.code)
        val o = s.carry.create("server1")
        s.f.add(o.checkId, "lantern-burger")
        assertEquals("not_a_table", assertFailsWith<ConflictException> { s.f.checks.moveCheck(o.checkId, "t3") }.code)
        val table = s.f.open("t3")
        s.f.add(table, "lantern-burger")
        assertEquals("not_a_table", assertFailsWith<ConflictException> { s.f.checks.moveCheck(table, SaleLocations.CARRY_OUT_TABLE) }.code)
        assertEquals("not_a_table", assertFailsWith<ConflictException> { s.f.checks.mergeCheck(o.checkId, table) }.code)
        assertEquals("not_a_table", assertFailsWith<ConflictException> { s.f.checks.mergeCheck(table, o.checkId) }.code)
        // the table's own check is no carry-out order
        assertNull(s.orders.ticketLabel(table, dev.dwhipstock.pos.sdk.KitchenLanguage.EN))
    }

    @Test
    fun `an empty order is discarded, a customer can be changed`() {
        val s = store()
        val o = s.carry.create("server1")
        assertEquals("Lee", s.carry.setCustomer(o.checkId, CarryOutCustomerRequest("Lee", null)).customerName)
        s.carry.discard(o.checkId)
        assertTrue(s.carry.list().isEmpty())
        val full = s.carry.create("server1")
        s.f.add(full.checkId, "lantern-burger")
        assertEquals("order_has_items", assertFailsWith<ConflictException> { s.carry.discard(full.checkId) }.code)
        // removing its last item cancels it like any bill, and it leaves the list
        val line = s.f.checks.getCheck(full.checkId).lines.single().id
        s.f.checks.removeLine(full.checkId, line)
        assertTrue(s.carry.list().isEmpty())
    }

    @Test
    fun `a paid carry-out syncs as an ordinary check at Carry-out and counts in the shift report`() {
        val s = store()
        val o = s.carry.create("server1", NewCarryOutRequest("Sam"))
        s.f.add(o.checkId, "lantern-burger")
        s.pay(o.checkId)
        val payload = transaction {
            SyncOutbox.selectAll().where { SyncOutbox.eventType eq "check.closed" }
                .orderBy(SyncOutbox.id to SortOrder.DESC).first()[SyncOutbox.payload]
        }.let(::obj)
        assertEquals(o.checkId, payload["checkId"]!!.jsonPrimitive.int)
        assertEquals("Carry-out", payload["tableLabel"]!!.jsonPrimitive.content)
        assertEquals("carry-out", payload["zoneId"]!!.jsonPrimitive.content)
        assertEquals("Carry-out", payload["zoneNameEn"]!!.jsonPrimitive.content)
        assertEquals("À emporter", payload["zoneNameFr"]!!.jsonPrimitive.content)
        assertTrue(payload["lines"]!!.jsonArray.isNotEmpty())
        // the customer's name stays in the store
        assertFalse("Sam" in payload.toString())
        val x = ShiftService(s.f.config).xReport()
        assertEquals(1, x.carryOutCount)
        assertEquals(0, x.takeOutCount)
        assertEquals(1, x.transactionCount)
    }

    private fun tempDb() = Files.createTempDirectory("pos-carryout").resolve("pos.db").toString()

    private suspend fun io.ktor.client.HttpClient.postJson(path: String, body: String = "{}") =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    @Test
    fun `over HTTP - a Carry-out spot on the floor, the summary, a new order and the board`() = testApplication {
        application { module(dbPath = tempDb(), venue = CopperLanternVenue.VIEUX_PORT) }
        val c = loginClient()
        // the carry-out location is never a room on the floor
        val zones = json.parseToJsonElement(c.get("/zones").bodyAsText()).jsonArray.map { it.jsonObject }
        assertFalse(zones.any { it["id"]!!.jsonPrimitive.content == SaleLocations.CARRY_OUT_ZONE })
        assertEquals(HttpStatusCode.NotFound, c.postJson("/zones/${SaleLocations.CARRY_OUT_ZONE}/objects",
            """{"type":"STAGE","x":10,"y":10,"managerPin":"1234"}""").status)

        var summary = obj(c.get("/carryout/summary").bodyAsText())
        assertEquals(0, summary["spots"]!!.jsonPrimitive.int)
        val zone = zones.first()["id"]!!.jsonPrimitive.content
        val made = c.postJson("/zones/$zone/objects", """{"type":"CARRY_OUT","x":40,"y":900,"width":120,"height":60,"managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Created, made.status, made.bodyAsText())
        summary = obj(c.get("/carryout/summary").bodyAsText())
        assertEquals(1, summary["spots"]!!.jsonPrimitive.int)

        val order = c.postJson("/carryout/orders", """{"customerName":"Sam"}""")
        assertEquals(HttpStatusCode.Created, order.status, order.bodyAsText())
        val o = obj(order.bodyAsText())
        assertEquals(101, o["orderNumber"]!!.jsonPrimitive.int)
        val id = o["checkId"]!!.jsonPrimitive.int
        // the normal check routes work on it
        assertEquals(HttpStatusCode.Created, c.postJson("/checks/$id/lines",
            """{"itemId":"lantern-burger","variantId":"lantern-burger:regular","qty":1}""").status)
        assertEquals(1, obj(c.get("/carryout/summary").bodyAsText())["openCount"]!!.jsonPrimitive.int)
        assertEquals(1, json.parseToJsonElement(c.get("/carryout/orders").bodyAsText()).jsonArray.size)
        // the occupied count on the floor is unchanged: no table holds it
        val after = json.parseToJsonElement(c.get("/zones").bodyAsText()).jsonArray.map { it.jsonObject }
        assertFalse(after.flatMap { it["tables"]!!.jsonArray }.any { t ->
            t.jsonObject["openCheckId"]?.jsonPrimitive?.content == id.toString()
        })
        // the open pickup board exists on the restaurant too
        assertEquals(HttpStatusCode.OK, client.get("/pickup/board").status)
        // header button setting (manager)
        assertEquals(false, obj(c.get("/carryout/settings").bodyAsText())["headerButton"]?.jsonPrimitive?.content?.toBoolean() ?: false)
        val put = c.put("/carryout/settings") { contentType(ContentType.Application.Json); setBody("""{"headerButton":true}""") }
        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
        assertEquals("true", obj(c.get("/carryout/summary").bodyAsText())["headerButton"]!!.jsonPrimitive.content)
        assertNotNull(o["createdAt"])
    }
}
