package dev.dwhipstock.pos

import dev.dwhipstock.pos.db.SyncOutbox
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Table ops (move / merge) + open items. Move reassigns a live check to an
 * empty table; merge folds the source's lines into the destination's check
 * (notes + captured prices survive, corkage sums, source closes as MERGED).
 * Open lines are catalog-less (name + price + qty) and flow through the same
 * pipeline as any line.
 */
class TableOpsTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    private suspend fun HttpClient.postJson(path: String, body: String = "{}"): HttpResponse =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.openCheck(tableId: String): Int =
        json.parseToJsonElement(post("/tables/$tableId/checks").bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.int

    private suspend fun HttpClient.check(checkId: Int): JsonObject =
        json.parseToJsonElement(get("/checks/$checkId").bodyAsText()).jsonObject

    private suspend fun errorCode(res: HttpResponse): String =
        json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content

    @Test
    fun moveCheckToEmptyTable() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val checkId = c.openCheck("t3")
        c.postJson("/checks/$checkId/lines", """{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":2}""")

        // destination occupied → refused with a pointer toward merge
        val otherId = c.openCheck("t4")
        val occupied = c.postJson("/checks/$checkId/move", """{"tableId":"t4"}""")
        assertEquals(HttpStatusCode.Conflict, occupied.status)
        assertEquals("table_occupied", errorCode(occupied))
        // moving onto its own table is a no-op → refused
        assertEquals("same_table", errorCode(c.postJson("/checks/$checkId/move", """{"tableId":"t3"}""")))

        // move to a free table: same check id, new table, lines intact
        val moved = json.parseToJsonElement(
            c.postJson("/checks/$checkId/move", """{"tableId":"t5"}""").bodyAsText()).jsonObject
        assertEquals("t5", moved["tableId"]!!.jsonPrimitive.content)
        assertEquals(2, moved["lines"]!!.jsonArray.single().jsonObject["qty"]!!.jsonPrimitive.int)

        // old table is free again (a new check opens rather than reattaching)
        val reopened = c.openCheck("t3")
        assertTrue(reopened != checkId)

        transaction {
            val moves = SyncOutbox.selectAll().filter { it[SyncOutbox.eventType] == "check.moved" }
            assertEquals(1, moves.size)
        }
        // silence unused warning: otherId's check stays open on t4
        assertTrue(otherId > 0)
    }

    @Test
    fun mergeFoldsLinesSumsCorkageAndClosesSource() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()

        // source: t3 with a noted line + corkage 1; dest: t4 with its own line + corkage 2
        val source = c.openCheck("t3")
        c.postJson("/checks/$source/lines",
            """{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":2,"note":"no ice"}""")
        c.postJson("/checks/$source/corkage", """{"bottles":1}""")
        val dest = c.openCheck("t4")
        c.postJson("/checks/$dest/lines", """{"itemId":"lantern-lager","variantId":"lantern-lager:pint","qty":1}""")
        c.postJson("/checks/$dest/corkage", """{"bottles":2}""")

        assertEquals("same_check", errorCode(c.postJson("/checks/$source/merge", """{"intoCheckId":$source}""")))

        val merged = json.parseToJsonElement(
            c.postJson("/checks/$source/merge", """{"intoCheckId":$dest}""").bodyAsText()).jsonObject
        assertEquals(dest, merged["id"]!!.jsonPrimitive.int)
        // both lines now on dest; note + captured unit price survived the fold
        val lines = merged["lines"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, lines.size)
        val amberAle = lines.single { it["itemId"]!!.jsonPrimitive.content == "amber-ale" }
        assertEquals("no ice", amberAle["note"]!!.jsonPrimitive.content)
        assertEquals(795L, amberAle["unitPriceCents"]!!.jsonPrimitive.long)
        // corkage bottles sum: 1 + 2
        assertEquals(3, merged["corkageBottles"]!!.jsonPrimitive.int)
        // 2 × 7.95 + 7.50 + 3 × 25 corkage = 98.40, + GST 4.92 + QST 9.8154 → 9.82
        assertEquals(11314L, merged["grandTotalCents"]!!.jsonPrimitive.long)

        // source closed as MERGED, its table is free, and it refuses further edits
        assertEquals("MERGED", c.check(source)["status"]!!.jsonPrimitive.content)
        assertTrue(c.openCheck("t3") != source)
        assertEquals("check_not_open",
            errorCode(c.postJson("/checks/$source/lines", """{"itemId":"amber-ale","variantId":"amber-ale:pint"}""")))

        transaction {
            val event = SyncOutbox.selectAll().single { it[SyncOutbox.eventType] == "check.merged" }
            val payload = json.parseToJsonElement(event[SyncOutbox.payload]).jsonObject
            assertEquals(source, payload["sourceCheckId"]!!.jsonPrimitive.int)
            assertEquals(dest, payload["destCheckId"]!!.jsonPrimitive.int)
            assertEquals(1, payload["lineCount"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun moveMergeGuards() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        c.postJson("/shifts", """{"openingFloatCents":0,"managerPin":"1234"}""")

        // tendered (TOTAL_LOCKED) source refuses both ops
        val locked = c.openCheck("t3")
        c.postJson("/checks/$locked/lines", """{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":1}""")
        c.postJson("/checks/$locked/tenders", """{"type":"CASH","amountTenderedCents":5000}""") // partial
        assertEquals("check_not_open", errorCode(c.postJson("/checks/$locked/move", """{"tableId":"t5"}""")))

        // mid-split source refuses
        val split = c.openCheck("t4")
        c.postJson("/checks/$split/lines", """{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":2}""")
        c.postJson("/checks/$split/split", """{"groups":2}""")
        assertEquals("clear_split_first", errorCode(c.postJson("/checks/$split/move", """{"tableId":"t5"}""")))
        // and a split DESTINATION refuses a merge into it
        val src = c.openCheck("t5")
        c.postJson("/checks/$src/lines", """{"itemId":"lantern-lager","variantId":"lantern-lager:pint","qty":1}""")
        assertEquals("clear_split_first", errorCode(c.postJson("/checks/$src/merge", """{"intoCheckId":$split}""")))

        // pending QR lines must be resolved first
        val qr = json.parseToJsonElement(client.postJson("${customerPath("t5-5")}/pending-lines",
            """{"lines":[{"itemId":"lantern-lager","variantId":"lantern-lager:pint","qty":1}]}""").bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.int
        assertEquals("pending_lines_unresolved", errorCode(c.postJson("/checks/$qr/move", """{"tableId":"t1"}""")))

        // closed destination zone refuses a move (t1 = U-1, free, in "upper")
        c.patch("/zones/upper/status") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"CLOSED","managerPin":"1234"}""")
        }
        assertEquals("zone_closed", errorCode(c.postJson("/checks/$src/move", """{"tableId":"t1"}""")))
    }

    @Test
    fun openLineFlowsThroughPipelineBillAndReceipt() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        c.postJson("/shifts", """{"openingFloatCents":0,"managerPin":"1234"}""")
        val checkId = c.openCheck("t3")

        // catalog line + open line side by side
        c.postJson("/checks/$checkId/lines", """{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":1}""")
        val res = c.postJson("/checks/$checkId/open-lines",
            """{"name":"Birthday cake","unitPriceCents":35000,"qty":1,"note":"from the fridge"}""")
        assertEquals(HttpStatusCode.Created, res.status)
        val view = json.parseToJsonElement(res.bodyAsText()).jsonObject
        val open = view["lines"]!!.jsonArray.map { it.jsonObject }
            .single { it["itemId"] == null || it["itemId"]!!.jsonPrimitive.contentOrNull == null }
        assertEquals("Birthday cake", open["nameEn"]!!.jsonPrimitive.content)
        assertEquals("Birthday cake", open["nameFr"]!!.jsonPrimitive.content)
        // totals through the pipeline: 7.95 + 350 = 357.95, + GST 17.8975 → 17.90 + QST 35.7055 → 35.71
        assertEquals(41156L, view["grandTotalCents"]!!.jsonPrimitive.long)

        // guards: blank name / zero price
        assertEquals(HttpStatusCode.BadRequest,
            c.postJson("/checks/$checkId/open-lines", """{"name":"  ","unitPriceCents":1000}""").status)
        assertEquals(HttpStatusCode.BadRequest,
            c.postJson("/checks/$checkId/open-lines", """{"name":"x","unitPriceCents":0}""").status)

        // renders by name on the provisional bill…
        val bill = json.parseToJsonElement(c.post("/checks/$checkId/bill").bodyAsText())
            .jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("Birthday cake" in bill)
        assertTrue("411.56" in bill && "357.95" in bill)

        // …and on the final receipt after a normal tender + finalize
        c.postJson("/checks/$checkId/tenders", """{"type":"CASH","amountTenderedCents":41156}""")
        c.post("/checks/$checkId/finalize")
        val receipt = json.parseToJsonElement(c.get("/checks/$checkId/receipt").bodyAsText())
            .jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("Birthday cake" in receipt)

        transaction {
            assertEquals(1, SyncOutbox.selectAll()
                .count { it[SyncOutbox.eventType] == "check.line_open_added" })
        }
    }
}
