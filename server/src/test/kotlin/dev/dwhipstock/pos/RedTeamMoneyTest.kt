package dev.dwhipstock.pos

import dev.dwhipstock.pos.payments.simulator.SimulatedTerminalDevice
import dev.dwhipstock.pos.sdk.PaymentTerminalConfig
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
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Red-team (money & checks), 2026-10-01. Every test here FAILS on main
 * (97e0d94): each one pins the correct behaviour for a bug found by driving
 * the store API. Copper Lantern Glenwood South (NC 6.75% + Wake 1%, nickel cash).
 * Lager pint = $7.50 → $8.09 with tax; amber pint = $7.95 → $8.57.
 */
class RedTeamMoneyTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-redteam").resolve("pos.db").toString()

    private suspend fun HttpClient.postJson(path: String, body: String = "{}") =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }
    private suspend fun HttpResponse.obj(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.l(k: String) = this[k]!!.jsonPrimitive.long

    private suspend fun HttpClient.freeTables(): Iterator<String> =
        json.parseToJsonElement(get("/zones").bodyAsText()).jsonArray
            .flatMap { z -> z.jsonObject["tables"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content } }
            .iterator()

    private suspend fun HttpClient.open(table: String): Int =
        postJson("/tables/$table/checks").obj()["id"]!!.jsonPrimitive.int

    private suspend fun HttpClient.lager(id: Int, qty: Int = 1): JsonObject =
        postJson("/checks/$id/lines", """{"itemId":"lantern-lager","variantId":"lantern-lager:pint","qty":$qty}""").obj()

    private suspend fun HttpClient.cash(id: Int, cents: Long, group: Int? = null) =
        postJson("/checks/$id/tenders", """{"type":"CASH","amountTenderedCents":$cents${group?.let { ",\"groupId\":$it" } ?: ""}}""")

    private suspend fun HttpClient.paidLager(table: String): Int {
        val id = open(table); lager(id); cash(id, 810)
        assertEquals(HttpStatusCode.OK, post("/checks/$id/finalize").status)
        return id
    }

    // CRITICAL: one fat-fingered cash amount kills the X and Z reports for the rest of the shift
    @Test
    fun `a huge cash tender must not break the X and Z reports`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val server = loginClient("9999")
        c.postJson("/shifts", """{"openingFloatCents":10000}""")
        val t = server.freeTables()
        server.paidLager(t.next()) // an ordinary $8.10 cash sale earlier in the shift
        val id = server.open(t.next()); server.lager(id)
        // a plain server (no manager) can tender any amount; change = $92 quadrillion
        val tender = server.cash(id, Long.MAX_VALUE)
        assertEquals(HttpStatusCode.Created, tender.status, tender.bodyAsText())
        assertEquals(HttpStatusCode.OK, server.post("/checks/$id/finalize").status)
        // expected: the tender is refused (sane upper bound) or at least the reports still work
        val x = c.get("/shifts/current/report")
        assertEquals(HttpStatusCode.OK, x.status, "X report: ${x.bodyAsText()}")
        val z = c.postJson("/shifts/current/close", """{"closingCountCents":11620}""")
        assertEquals(HttpStatusCode.OK, z.status, "Z close: ${z.bodyAsText()}")
    }

    // CRITICAL: refund cap is bypassed by Long overflow (already + gross wraps negative)
    @Test
    fun `refunds can never exceed what the check took`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        c.postJson("/shifts", """{"openingFloatCents":10000}""")
        val id = c.paidLager(c.freeTables().next())
        assertEquals(HttpStatusCode.Created,
            c.postJson("/checks/$id/refund", """{"amountCents":1,"tenderType":"CASH","reason":"x"}""").status)
        val huge = c.postJson("/checks/$id/refund", """{"amountCents":${Long.MAX_VALUE},"tenderType":"CASH","reason":"x"}""")
        assertEquals(HttpStatusCode.Conflict, huge.status, "a \$92-quadrillion refund on an \$8.09 check was accepted: ${huge.bodyAsText().take(300)}")
        val info = c.get("/checks/$id/refunds").obj()
        assertTrue(info.l("refundedCents") in 0..809, info.toString().take(300))
    }

    // CRITICAL: an open item's price overflows Long → negative bill, cash "change" in the quadrillions
    @Test
    fun `an open item can never make a check total negative`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val server = loginClient("9999")
        c.postJson("/shifts", """{"openingFloatCents":10000}""")
        val id = server.open(server.freeTables().next())
        val res = server.postJson("/checks/$id/open-lines", """{"name":"x","unitPriceCents":9000000000000000000,"qty":1}""")
        if (res.status.isSuccess()) {
            val check = res.obj()
            assertTrue(check.l("grandTotalCents") >= 0, "grand total went negative: ${check.l("grandTotalCents")}")
            assertTrue(check.l("itemsSubtotalCents") >= 0)
        }
        // and the 2 × 4.6e18 variant (qty multiplication wraps)
        val id2 = server.open(server.freeTables().let { it.next(); it.next() })
        val res2 = server.postJson("/checks/$id2/open-lines", """{"name":"x","unitPriceCents":4611686018427387904,"qty":2}""")
        if (res2.status.isSuccess())
            assertTrue(res2.obj().l("itemsSubtotalCents") >= 0, "items subtotal wrapped negative")
    }

    // HIGH: the same line can be refunded again and again (no per-line refunded qty)
    @Test
    fun `a line refunded once cannot be refunded again`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        c.postJson("/shifts", """{"openingFloatCents":10000}""")
        val id = c.open(c.freeTables().next())
        val amber = c.postJson("/checks/$id/lines", """{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":1}""")
            .obj()["lines"]!!.jsonArray.last().jsonObject["id"]!!.jsonPrimitive.int
        c.lager(id, 2) // $24.73 check: 1 amber + 2 lagers
        c.cash(id, 2475); c.post("/checks/$id/finalize")
        val body = """{"lines":[{"lineId":$amber,"qty":1}],"tenderType":"CASH","reason":"flat"}"""
        assertEquals(HttpStatusCode.Created, c.postJson("/checks/$id/refund", body).status)
        val again = c.postJson("/checks/$id/refund", body)
        // main: 201 again ($8.57), and a third time ($7.59) — the whole check refunded "for one beer"
        assertTrue(again.status.value >= 400, "the one amber was refunded twice: ${again.bodyAsText().take(200)}")
    }

    // HIGH: Z report misstates the drawer when a shift closes over a partly paid check
    @Test
    fun `cash taken on a still-open check counts in the shift that took it`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        c.postJson("/shifts", """{"openingFloatCents":10000}""")
        val id = c.open(c.freeTables().next()); c.lager(id, 2) // $16.16
        assertEquals(HttpStatusCode.Created, c.cash(id, 500).status) // $5 in the drawer
        val z = c.postJson("/shifts/current/close", """{"closingCountCents":10500}""").obj()
        // main: expected 10000, over/short +500 — the $5 really is in the drawer
        assertEquals(10500L, z.l("expectedCashCents"), z.toString())
        assertEquals(0L, z.l("overShortCents"))
    }

    // MEDIUM: finalize / cash refund with no open shift: money moves, no Z report ever shows it
    @Test
    fun `a cash refund always lands in a shift`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        c.postJson("/shifts", """{"openingFloatCents":10000}""")
        val id = c.paidLager(c.freeTables().next())
        c.postJson("/shifts/current/close", """{"closingCountCents":10810}""")
        // no shift open: tenders are refused (no_open_shift) but refunds are not
        val r = c.postJson("/checks/$id/refund", """{"amountCents":809,"tenderType":"CASH","reason":"x"}""")
        assertEquals(HttpStatusCode.Conflict, r.status, "cash left the drawer with no shift to account for it")
    }

    // MEDIUM: even split, each guest's cash rounds up on its own: the table pays more than the bill
    @Test
    fun `an even split paid in cash never costs the table more than paying as one`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        c.postJson("/shifts", """{"openingFloatCents":10000}""")
        val id = c.open(c.freeTables().next())
        c.lager(id, 3)
        val one = c.postJson("/checks/$id/lines", """{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":1}""").obj()
        assertEquals(3281L, one.l("grandTotalCents"))
        val wholeCash = one.l("cashDueCents") // 3280
        val split = c.postJson("/checks/$id/split", """{"groups":7,"even":true}""").obj()
        val groups = split["split"]!!.jsonObject["groups"]!!.jsonArray.map { it.jsonObject }
        val cashSum = groups.sumOf { it.l("cashDueCents") }
        // main: shares 473 + 6 × 468 → cash 475 + 6 × 470 = 3295 (14¢ more than 3280)
        assertTrue(cashSum <= wholeCash + 5, "7-way cash split collects $cashSum for a $wholeCash cash bill")
        // and the remainder all lands on guest 1 (6-way: 551 vs 546) instead of 1¢ each
        val shares = groups.map { it.l("grandTotalCents") }
        assertTrue(shares.max() - shares.min() <= 1, "uneven even split: $shares")
    }

    // HIGH: tips taken on the reader never reach the X / Z report
    @Test
    fun `a tip on the card reader shows on the shift report`() = testApplication {
        val clock = AtomicLong(1_000_000L)
        val device = SimulatedTerminalDevice(clock = clock::get, delays = SimulatedTerminalDevice.Delays.INSTANT,
            random = kotlin.random.Random(7))
        val simulator = PaymentTerminalConfig.resolve({ if (it == PaymentTerminalConfig.KEY) "simulator" else null }, "test")
        application { module(dbPath = tempDb(), paymentTerminal = simulator, terminalDevice = device) }
        val c = loginClient()
        c.postJson("/shifts", """{"openingFloatCents":10000}""")
        val id = c.open("t5-5"); c.lager(id, 4) // $32.33
        val pid = c.postJson("/checks/$id/terminal/payments", """{"tipMode":"on_reader"}""").obj()["paymentId"]!!.jsonPrimitive.content
        c.postJson("/terminal/ui/tip", """{"tipCents":600}""")
        c.postJson("/terminal/ui/present", """{"entry":"tap","card":"visa","outcome":"approve"}""")
        assertEquals("RECORDED", c.get("/terminal/payments/$pid").obj()["status"]!!.jsonPrimitive.content)
        c.post("/checks/$id/finalize")
        val x = c.get("/shifts/current/report").bodyAsText()
        // the card was charged $38.33; the report must account for the $6.00 tip somewhere
        assertTrue(x.contains("tip", ignoreCase = true), "tip missing from the X report: $x")
    }
}
