package dev.dwhipstock.pos

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanToOrderAndShiftTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    private suspend fun io.ktor.client.HttpClient.postJson(path: String, body: String) =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    @Test
    fun customerMenuServesHtmlAndQrServesPng() = testApplication {
        application { module(dbPath = tempDb()) }
        val page = client.get(customerPath("t5-5"))
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        assertTrue("commandez de votre téléphone" in html)
        assertTrue("U-2" in html) // label shown; the internal id "t5-5" is not
        assertTrue("\"t5-5\"" !in html)
        assertTrue("--accent: #1565c0" in html)
        assertTrue("const cad =" in html)
        assertTrue("const CAD =" !in html)

        assertEquals(HttpStatusCode.NotFound, client.get("/m/no-such-table").status)

        val qr = loginClient().get("/tables/t5-5/qr")
        assertEquals(HttpStatusCode.OK, qr.status)
        assertEquals(ContentType.Image.PNG, qr.contentType())
        assertTrue(qr.readRawBytes().size > 100)
    }

    /** Demo path: shift open → QR order → accept/reject → manual line → split tender → close → Z. */
    @Test
    fun fullShiftLifecycleWithScanToOrder() = testApplication {
        val receiptsDir = Files.createTempDirectory("pos-receipts").toString()
        application { module(dbPath = tempDb(), receiptsDir = receiptsDir) }
        val c = loginClient()

        // a. open shift with $1,000 float; second open is rejected
        val shift = c.postJson("/shifts", """{"openingFloatCents":100000,"managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Created, shift.status)
        assertEquals(HttpStatusCode.Conflict,
            c.postJson("/shifts", """{"openingFloatCents":0,"managerPin":"1234"}""").status)

        // b. customer submits 2 items via QR — check auto-opens, nothing on the bill yet
        val submitted = client.postJson("${customerPath("t5-5")}/pending-lines",
            """{"lines":[{"itemId":"lantern-lager","variantId":"lantern-lager:pitcher","qty":1},
                         {"itemId":"late-fries","variantId":"late-fries:regular","qty":1,"note":"plus léger aussi"}]}""")
        assertEquals(HttpStatusCode.Created, submitted.status)
        val check = json.parseToJsonElement(submitted.bodyAsText()).jsonObject
        // the guest's reply is their own bill (no ids, no tenders); staff see the check
        assertNull(check["id"])
        val staffView = c.staffCheckAt("t5-5")
        val checkId = staffView["id"]!!.jsonPrimitive.int
        assertEquals(0L, check["grandTotalCents"]!!.jsonPrimitive.long)
        assertEquals(2, check["pendingLines"]!!.jsonArray.size)

        // pending lines block tendering
        assertEquals(HttpStatusCode.Conflict,
            c.postJson("/checks/$checkId/tenders", """{"type":"CASH","amountTenderedCents":100000}""").status)

        // c. staff accepts the pitcher and rejects the late-night snack
        val pending = staffView["pendingLines"]!!.jsonArray.map { it.jsonObject }
        val towerLine = pending.first { it["itemId"]!!.jsonPrimitive.content == "lantern-lager" }["id"]!!.jsonPrimitive.int
        val cigLine = pending.first { it["itemId"]!!.jsonPrimitive.content == "late-fries" }["id"]!!.jsonPrimitive.int
        c.post("/checks/$checkId/pending-lines/$towerLine/accept").let { assertEquals(HttpStatusCode.OK, it.status) }
        val afterReject = c.post("/checks/$checkId/pending-lines/$cigLine/reject")
        val cleaned = json.parseToJsonElement(afterReject.bodyAsText()).jsonObject
        assertEquals(2192L, cleaned["grandTotalCents"]!!.jsonPrimitive.long)
        assertEquals(0, cleaned["pendingLines"]!!.jsonArray.size)

        // d. add poutine manually and split the $35.99 total (33.25 + Tax 8.25% 2.74 = NC sales
        //    tax 2.41 + Wake 0.33) between card and cash; the $15.99 cash due rounds to $16.00
        c.postJson("/checks/$checkId/lines", """{"itemId":"poutine","variantId":"poutine:regular","qty":1}""")
        c.postJson("/checks/$checkId/tenders/initiate", """{"type":"CARD","amountCents":2000}""")
        c.postJson("/checks/$checkId/tenders/confirm", """{"type":"CARD","amountCents":2000}""")
        c.postJson("/checks/$checkId/tenders", """{"type":"CASH","amountTenderedCents":1600}""")
        c.post("/checks/$checkId/finalize").let { assertEquals(HttpStatusCode.OK, it.status) }

        // e. void a different check with a reason
        val other = json.parseToJsonElement(c.postJson("/tables/t6/checks", """{"userId":"manager"}""")
            .bodyAsText()).jsonObject["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$other/lines", """{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":2}""")
        c.postJson("/checks/$other/void", """{"reason":"J'ai commandé la mauvaise table","managerPin":"1234"}""")
            .let { assertEquals(HttpStatusCode.OK, it.status) }

        // f. X-report snapshot
        val x = json.parseToJsonElement(c.get("/shifts/current/report").bodyAsText()).jsonObject
        assertEquals(3599L, x["revenueCents"]!!.jsonPrimitive.long)
        // the taxes for remittance: each NC tax on its own row, with who it is paid to;
        // together they are the receipt's one "Tax (8.25%)" line, to the cent
        val xTaxes = x["taxes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("NC_SALES", "WAKE_FOOD"), xTaxes.map { it["code"]!!.jsonPrimitive.content })
        assertEquals(listOf("7.25", "1"), xTaxes.map { it["ratePercent"]!!.jsonPrimitive.content })
        assertEquals(listOf("NCDOR", "Wake County"), xTaxes.map { it["remitTo"]!!.jsonPrimitive.content })
        assertEquals(listOf(241L, 33L), xTaxes.map { it["amountCents"]!!.jsonPrimitive.long })
        val receiptText = json.parseToJsonElement(c.get("/checks/$checkId/receipt").bodyAsText())
            .jsonObject["text"]!!.jsonPrimitive.content
        val receiptTax = receiptText.lines().map { it.trim().replace(Regex(" {2,}"), " | ") }
            .single { it.startsWith("Tax (8.25%)") }
        assertEquals("Tax (8.25%) | 2.74", receiptTax)
        assertEquals(274L, xTaxes.sumOf { it["amountCents"]!!.jsonPrimitive.long })
        assertEquals(1, x["transactionCount"]!!.jsonPrimitive.int)
        val tenderTypes = x["tenderBreakdown"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertTrue("CASH" in tenderTypes && "CARD" in tenderTypes)
        val voids = x["voids"]!!.jsonArray
        assertEquals(1, voids.size)
        assertEquals("J'ai commandé la mauvaise table", voids[0].jsonObject["reason"]!!.jsonPrimitive.content)
        assertTrue(x["itemMix"]!!.jsonArray.isNotEmpty())

        // g. counted cash equals the opening float plus the cash portion of the sale
        val z = json.parseToJsonElement(
            c.postJson("/shifts/current/close", """{"closingCountCents":101600,"managerPin":"1234"}""")
                .bodyAsText()).jsonObject
        assertEquals(101600L, z["expectedCashCents"]!!.jsonPrimitive.long)
        assertEquals(0L, z["overShortCents"]!!.jsonPrimitive.long)
        assertEquals("CLOSED", z["shiftStatus"]!!.jsonPrimitive.content)

        // shift really closed
        assertEquals(HttpStatusCode.NotFound, c.get("/shifts/current").status)
        assertEquals(HttpStatusCode.Conflict, c.get("/shifts/current/report").status)

        // h. range report: today sees the same revenue, no cash reconciliation fields
        // the venue's business day, not the machine's: they differ in the evening (e.g. CI on UTC)
        val today = dev.dwhipstock.pos.sdk.VenueClock.today().toString()
        val range = json.parseToJsonElement(
            c.get("/reports/range?from=$today&to=$today").bodyAsText()).jsonObject
        assertEquals("RANGE", range["shiftStatus"]!!.jsonPrimitive.content)
        assertEquals(3599L, range["revenueCents"]!!.jsonPrimitive.long)
        assertEquals(listOf(241L, 33L), range["taxes"]!!.jsonArray.map { it.jsonObject["amountCents"]!!.jsonPrimitive.long })
        assertEquals(1, range["voids"]!!.jsonArray.size)
        assertTrue(range["expectedCashCents"] == null ||
            range["expectedCashCents"] is kotlinx.serialization.json.JsonNull)

        // yesterday is empty; bad dates are rejected
        val yesterday = dev.dwhipstock.pos.sdk.VenueClock.today().minusDays(1).toString()
        val empty = json.parseToJsonElement(
            c.get("/reports/range?from=$yesterday&to=$yesterday").bodyAsText()).jsonObject
        assertEquals(0L, empty["revenueCents"]!!.jsonPrimitive.long)
        assertEquals(0, empty["taxes"]?.jsonArray?.size ?: 0)
        assertEquals(HttpStatusCode.BadRequest, c.get("/reports/range?from=not-a-date").status)
        assertEquals(HttpStatusCode.BadRequest,
            c.get("/reports/range?from=$today&to=$yesterday").status)
    }
}
