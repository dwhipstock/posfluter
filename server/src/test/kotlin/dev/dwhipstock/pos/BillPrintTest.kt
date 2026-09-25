package dev.dwhipstock.pos

import dev.dwhipstock.pos.db.SyncOutbox
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Provisional "check please" bill (POST /checks/{id}/bill): renders the check's
 * current state, prints it to the bills/ spool, and leaves the check untouched so
 * it can be reprinted after edits. It is NOT a receipt — no tender section, and a
 * CUSTOMER BILL / NOT A RECEIPT banner.
 */
class BillPrintTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()
    private suspend fun io.ktor.client.HttpClient.postJson(path: String, body: String) =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }
    private suspend fun io.ktor.client.HttpClient.status(checkId: Int): String =
        json.parseToJsonElement(get("/checks/$checkId").bodyAsText())
            .jsonObject["status"]!!.jsonPrimitive.content

    @Test
    fun printBillDoesNotLockCheck() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()

        val checkId = json.parseToJsonElement(c.post("/tables/t3/checks").bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$checkId/lines", """{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":2}""")

        // POST /bill → 200, provisional layout, check untouched
        val bill1 = c.post("/checks/$checkId/bill")
        assertEquals(HttpStatusCode.OK, bill1.status)
        val text1 = json.parseToJsonElement(bill1.bodyAsText()).jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("CUSTOMER BILL" in text1, "bill carries the provisional header")
        assertTrue("NOT A RECEIPT" in text1, "bill carries the not-a-receipt footer")
        assertFalse("Change" in text1 || "changement" in text1, "no tender section on a bill")
        assertEquals("OPEN", c.status(checkId))

        // the basket is still editable after printing — add another line
        c.postJson("/checks/$checkId/lines", """{"itemId":"lantern-lager","variantId":"lantern-lager:pint","qty":1}""")

        // reprint reflects the change and the check is STILL open
        val bill2 = c.post("/checks/$checkId/bill")
        assertEquals(HttpStatusCode.OK, bill2.status)
        assertEquals("OPEN", c.status(checkId))

        // two bill prints → two check.bill_printed events, and NO receipt.printed
        transaction {
            val events = SyncOutbox.selectAll().map { it[SyncOutbox.eventType] }
            assertEquals(2, events.count { it == "check.bill_printed" })
            assertFalse(events.any { it == "receipt.printed" }, "a bill is not a receipt")
        }
    }

    @Test
    fun printBillRefusesWhenNoLongerLive() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()

        // open then delete the only line → the check auto-cancels (CANCELLED)
        val checkId = json.parseToJsonElement(c.post("/tables/t3/checks").bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.int
        val lineId = json.parseToJsonElement(
            c.postJson("/checks/$checkId/lines", """{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":1}""")
                .bodyAsText()).jsonObject["lines"]!!.jsonArray.first()
            .jsonObject["id"]!!.jsonPrimitive.int
        c.delete("/checks/$checkId/lines/$lineId")
        assertEquals("CANCELLED", c.status(checkId))

        val res = c.post("/checks/$checkId/bill")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("check_not_billable",
            json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun printBillRefusesWithPendingQrLines() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()

        // customer scan-to-order leaves a pending line on the auto-opened check
        val checkId = json.parseToJsonElement(client.postJson("${customerPath("t5-5")}/pending-lines",
            """{"lines":[{"itemId":"lantern-lager","variantId":"lantern-lager:pint","qty":1}]}""").bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.int

        val res = c.post("/checks/$checkId/bill")
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertEquals("pending_lines_unresolved",
            json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
    }
}
