package dev.dwhipstock.pos

import dev.dwhipstock.pos.db.SyncOutbox
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Orphan zero-balance checks (session 2026-07-08 friction fix): a check that a
 * mutation strips to zero ACTIVE + zero PENDING lines (and zero balance) auto-cancels
 * to CANCELLED — no void, no reason, no manager gate — and its table frees up.
 */
class OrphanCheckCancelTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()
    private suspend fun io.ktor.client.HttpClient.postJson(path: String, body: String) =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    /** The open check on a table, as the tables/zones screen sees it (null once freed). */
    private suspend fun io.ktor.client.HttpClient.tableStatus(tableId: String): String? =
        json.parseToJsonElement(get("/zones").bodyAsText()).jsonArray
            .flatMap { it.jsonObject["tables"]!!.jsonArray }
            .first { it.jsonObject["id"]!!.jsonPrimitive.content == tableId }
            .jsonObject["openCheckStatus"]?.jsonPrimitive?.contentOrNull

    @Test
    fun deletingTheLastLineAutoCancelsAndFreesTheTable() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()

        // staff opens a check on t3 and rings one amber ale
        val checkId = json.parseToJsonElement(c.post("/tables/t3/checks").bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.int
        val lineId = json.parseToJsonElement(
            c.postJson("/checks/$checkId/lines", """{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":2}""")
                .bodyAsText()).jsonObject["lines"]!!.jsonArray.first()
            .jsonObject["id"]!!.jsonPrimitive.int

        // table reads as occupied (OPEN) while the line is there
        assertEquals("OPEN", c.tableStatus("t3"))

        // delete the only line → check auto-cancels, nothing to pay
        val after = json.parseToJsonElement(
            c.delete("/checks/$checkId/lines/$lineId").bodyAsText()).jsonObject
        assertEquals("CANCELLED", after["status"]!!.jsonPrimitive.content)
        assertEquals(0, after["lines"]!!.jsonArray.size)

        // table is free again (occupancy read only counts OPEN|TOTAL_LOCKED)
        assertNull(c.tableStatus("t3"))

        // outbox carries check.cancelled with reason "empty"
        transaction {
            val payloads = SyncOutbox.selectAll()
                .filter { it[SyncOutbox.eventType] == "check.cancelled" }
                .map { it[SyncOutbox.payload] }
            assertEquals(1, payloads.size)
            assertTrue("\"reason\":\"empty\"" in payloads.first())
            assertTrue("\"checkId\":$checkId" in payloads.first())
        }
    }

    @Test
    fun deletingOneOfTwoLinesLeavesTheCheckOpen() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val checkId = json.parseToJsonElement(c.post("/tables/t3/checks").bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$checkId/lines", """{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":1}""")
        val second = json.parseToJsonElement(
            c.postJson("/checks/$checkId/lines", """{"itemId":"lantern-lager","variantId":"lantern-lager:pint","qty":1}""")
                .bodyAsText()).jsonObject["lines"]!!.jsonArray.last()
            .jsonObject["id"]!!.jsonPrimitive.int

        val after = json.parseToJsonElement(
            c.delete("/checks/$checkId/lines/$second").bodyAsText()).jsonObject
        assertEquals("OPEN", after["status"]!!.jsonPrimitive.content)
        assertEquals(1, after["lines"]!!.jsonArray.size)
        assertEquals("OPEN", c.tableStatus("t3"))
    }

    @Test
    fun rejectingTheLastPendingQrOrderAutoCancels() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()

        // customer scan-to-order auto-opens a qr-customer check with one pending line
        val submitted = json.parseToJsonElement(client.postJson("${customerPath("t5-5")}/pending-lines",
            """{"lines":[{"itemId":"lantern-lager","variantId":"lantern-lager:pitcher","qty":1}]}""").bodyAsText()).jsonObject
        val checkId = submitted["id"]!!.jsonPrimitive.int
        val pendingId = submitted["pendingLines"]!!.jsonArray.first()
            .jsonObject["id"]!!.jsonPrimitive.int
        assertEquals("OPEN", c.tableStatus("t5-5"))

        // staff rejects it → nothing active, nothing pending → auto-cancel
        val after = json.parseToJsonElement(
            c.post("/checks/$checkId/pending-lines/$pendingId/reject").bodyAsText()).jsonObject
        assertEquals("CANCELLED", after["status"]!!.jsonPrimitive.content)
        assertNull(c.tableStatus("t5-5"))
    }

    @Test
    fun corkageOnlyCheckIsNotCancelled() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val checkId = json.parseToJsonElement(c.post("/tables/t3/checks").bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.int
        // ring a bottle, add corkage, then delete the bottle — the $200 corkage still owes
        val lineId = json.parseToJsonElement(
            c.postJson("/checks/$checkId/lines", """{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":1}""")
                .bodyAsText()).jsonObject["lines"]!!.jsonArray.first()
            .jsonObject["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$checkId/corkage", """{"bottles":1}""")

        val after = json.parseToJsonElement(
            c.delete("/checks/$checkId/lines/$lineId").bodyAsText()).jsonObject
        assertEquals("OPEN", after["status"]!!.jsonPrimitive.content)
        assertTrue(after["grandTotalCents"]!!.jsonPrimitive.int > 0) // corkage still on the bill
        assertEquals("OPEN", c.tableStatus("t3"))
    }
}
