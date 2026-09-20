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
import kotlin.test.assertTrue

class ScanToOrderAndShiftTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    private suspend fun io.ktor.client.HttpClient.postJson(path: String, body: String) =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    @Test
    fun customerMenuServesHtmlAndQrServesPng() = testApplication {
        application { module(dbPath = tempDb()) }
        val page = client.get("/m/t5-5")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue("Commande depuis un téléphone portable" in page.bodyAsText())
        assertTrue("5-5" in page.bodyAsText())

        assertEquals(HttpStatusCode.NotFound, client.get("/m/no-such-table").status)

        val qr = client.get("/tables/t5-5/qr")
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
        val submitted = client.postJson("/tables/t5-5/pending-lines",
            """{"lines":[{"itemId":"lantern-lager","variantId":"lantern-lager:pitcher","qty":1},
                         {"itemId":"late-fries","variantId":"late-fries:regular","qty":1,"note":"plus léger aussi"}]}""")
        assertEquals(HttpStatusCode.Created, submitted.status)
        val check = json.parseToJsonElement(submitted.bodyAsText()).jsonObject
        val checkId = check["id"]!!.jsonPrimitive.int
        assertEquals(0L, check["grandTotalCents"]!!.jsonPrimitive.long)
        assertEquals(2, check["pendingLines"]!!.jsonArray.size)

        // pending lines block tendering
        assertEquals(HttpStatusCode.Conflict,
            c.postJson("/checks/$checkId/tenders", """{"type":"CASH","amountTenderedCents":100000}""").status)

        // c. staff accepts the pitcher and rejects the late-night snack
        val pending = check["pendingLines"]!!.jsonArray.map { it.jsonObject }
        val towerLine = pending.first { it["itemId"]!!.jsonPrimitive.content == "lantern-lager" }["id"]!!.jsonPrimitive.int
        val cigLine = pending.first { it["itemId"]!!.jsonPrimitive.content == "late-fries" }["id"]!!.jsonPrimitive.int
        c.post("/checks/$checkId/pending-lines/$towerLine/accept").let { assertEquals(HttpStatusCode.OK, it.status) }
        val afterReject = c.post("/checks/$checkId/pending-lines/$cigLine/reject")
        val cleaned = json.parseToJsonElement(afterReject.bodyAsText()).jsonObject
        assertEquals(2250L, cleaned["grandTotalCents"]!!.jsonPrimitive.long)
        assertEquals(0, cleaned["pendingLines"]!!.jsonArray.size)

        // d. add poutine manually and split the $37 total between card and cash
        c.postJson("/checks/$checkId/lines", """{"itemId":"poutine","variantId":"poutine:regular","qty":1}""")
        c.postJson("/checks/$checkId/tenders/initiate", """{"type":"CARD","amountCents":2000}""")
        c.postJson("/checks/$checkId/tenders/confirm", """{"type":"CARD","amountCents":2000}""")
        c.postJson("/checks/$checkId/tenders", """{"type":"CASH","amountTenderedCents":1700}""")
        c.post("/checks/$checkId/finalize").let { assertEquals(HttpStatusCode.OK, it.status) }

        // e. void a different check with a reason
        val other = json.parseToJsonElement(c.postJson("/tables/t6/checks", """{"userId":"manager"}""")
            .bodyAsText()).jsonObject["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$other/lines", """{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":2}""")
        c.postJson("/checks/$other/void", """{"reason":"J'ai commandé la mauvaise table","managerPin":"1234"}""")
            .let { assertEquals(HttpStatusCode.OK, it.status) }

        // f. X-report snapshot
        val x = json.parseToJsonElement(c.get("/shifts/current/report").bodyAsText()).jsonObject
        assertEquals(3700L, x["revenueCents"]!!.jsonPrimitive.long)
        assertEquals(1, x["transactionCount"]!!.jsonPrimitive.int)
        val tenderTypes = x["tenderBreakdown"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertTrue("CASH" in tenderTypes && "CARD" in tenderTypes)
        val voids = x["voids"]!!.jsonArray
        assertEquals(1, voids.size)
        assertEquals("J'ai commandé la mauvaise table", voids[0].jsonObject["reason"]!!.jsonPrimitive.content)
        assertTrue(x["itemMix"]!!.jsonArray.isNotEmpty())

        // g. counted cash equals the opening float plus the cash portion of the sale
        val z = json.parseToJsonElement(
            c.postJson("/shifts/current/close", """{"closingCountCents":101700,"managerPin":"1234"}""")
                .bodyAsText()).jsonObject
        assertEquals(101700L, z["expectedCashCents"]!!.jsonPrimitive.long)
        assertEquals(0L, z["overShortCents"]!!.jsonPrimitive.long)
        assertEquals("CLOSED", z["shiftStatus"]!!.jsonPrimitive.content)

        // shift really closed
        assertEquals(HttpStatusCode.NotFound, c.get("/shifts/current").status)
        assertEquals(HttpStatusCode.Conflict, c.get("/shifts/current/report").status)

        // h. range report: today sees the same revenue, no cash reconciliation fields
        val today = java.time.LocalDate.now().toString()
        val range = json.parseToJsonElement(
            c.get("/reports/range?from=$today&to=$today").bodyAsText()).jsonObject
        assertEquals("RANGE", range["shiftStatus"]!!.jsonPrimitive.content)
        assertEquals(3700L, range["revenueCents"]!!.jsonPrimitive.long)
        assertEquals(1, range["voids"]!!.jsonArray.size)
        assertTrue(range["expectedCashCents"] == null ||
            range["expectedCashCents"] is kotlinx.serialization.json.JsonNull)

        // yesterday is empty; bad dates are rejected
        val yesterday = java.time.LocalDate.now().minusDays(1).toString()
        val empty = json.parseToJsonElement(
            c.get("/reports/range?from=$yesterday&to=$yesterday").bodyAsText()).jsonObject
        assertEquals(0L, empty["revenueCents"]!!.jsonPrimitive.long)
        assertEquals(HttpStatusCode.BadRequest, c.get("/reports/range?from=not-a-date").status)
        assertEquals(HttpStatusCode.BadRequest,
            c.get("/reports/range?from=$today&to=$yesterday").status)
    }
}
