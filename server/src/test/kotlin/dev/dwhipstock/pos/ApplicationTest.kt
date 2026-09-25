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
import kotlinx.serialization.json.long
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {

    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    @Test
    fun healthEndpointReturnsOk() = testApplication {
        application { module(dbPath = tempDb()) }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        // pairingRequired (M8) tells terminals whether to show the pairing screen
        // venue names the store on the sign-in screen
        assertEquals(
            """{"status":"ok","pairingRequired":false,"venue":"Copper Lantern — Vieux-Port"}""",
            response.bodyAsText(),
        )
    }

    /**
     * The whole money path: open → add lines → corkage → card for $30
     * (partial) → confirm → cash for the remainder → finalize → receipt file
     * + full outbox sequence.
     */
    @Test
    fun fullCheckLifecycleWithSplitTenderAndReceipt() = testApplication {
        val receiptsDir = Files.createTempDirectory("pos-receipts").toString()
        application { module(dbPath = tempDb(), receiptsDir = receiptsDir) }
        val c = loginClient()
        val json = Json { ignoreUnknownKeys = true }

        // tendering requires an open shift (drawer math needs a home)
        c.post("/shifts") {
            contentType(ContentType.Application.Json)
            setBody("""{"openingFloatCents":100000,"managerPin":"1234"}""")
        }.let { assertEquals(HttpStatusCode.Created, it.status) }

        // open a check on sub-table 5-5
        val opened = c.post("/tables/t5-5/checks") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"manager"}""")
        }
        assertEquals(HttpStatusCode.Created, opened.status)
        val checkId = json.parseToJsonElement(opened.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.int

        // add: Lantern House Lager tower $450 + Robust Porter bottle $550
        c.post("/checks/$checkId/lines") {
            contentType(ContentType.Application.Json)
            setBody("""{"itemId":"lantern-lager","variantId":"lantern-lager:pitcher","qty":1}""")
        }.let { assertEquals(HttpStatusCode.Created, it.status) }
        c.post("/checks/$checkId/lines") {
            contentType(ContentType.Application.Json)
            setBody("""{"itemId":"porter-can","variantId":"porter-can:regular","qty":1,"note":"Je ne veux pas de glace."}""")
        }.let { assertEquals(HttpStatusCode.Created, it.status) }

        // 1 brought-in bottle → $200 corkage
        c.post("/checks/$checkId/corkage") {
            contentType(ContentType.Application.Json)
            setBody("""{"bottles":1}""")
        }.let { assertEquals(HttpStatusCode.OK, it.status) }

        // subtotal: 450 + 550 + 200 = $1200, tax included ≈ $78.50 hidden
        val check = json.parseToJsonElement(c.get("/checks/$checkId").bodyAsText()).jsonObject
        assertEquals(5625L, check["grandTotalCents"]!!.jsonPrimitive.long)
        assertEquals(0L, check["taxIncludedCents"]!!.jsonPrimitive.long)

        // Put $30 on the generic card terminal; totals lock at initiation.
        val initiated = c.post("/checks/$checkId/tenders/initiate") {
            contentType(ContentType.Application.Json)
            setBody("""{"type":"CARD","amountCents":3000}""")
        }
        assertEquals(HttpStatusCode.OK, initiated.status)
        assertTrue("Manual card terminal" in initiated.bodyAsText())

        // staff saw the transfer land → confirm
        val confirmed = c.post("/checks/$checkId/tenders/confirm") {
            contentType(ContentType.Application.Json)
            setBody("""{"type":"CARD","amountCents":3000}""")
        }
        assertEquals(HttpStatusCode.Created, confirmed.status)
        val afterCard = json.parseToJsonElement(confirmed.bodyAsText()).jsonObject["check"]!!.jsonObject
        assertEquals(3000L, afterCard["paidCents"]!!.jsonPrimitive.long)
        assertEquals(2625L, afterCard["outstandingCents"]!!.jsonPrimitive.long)

        // finalize refused while outstanding
        assertEquals(HttpStatusCode.Conflict, c.post("/checks/$checkId/finalize").status)

        // Cash settles the remaining $26.25 and returns $3.75 change.
        val tendered = c.post("/checks/$checkId/tenders") {
            contentType(ContentType.Application.Json)
            setBody("""{"type":"CASH","amountTenderedCents":3000}""")
        }
        assertEquals(HttpStatusCode.Created, tendered.status)
        val tender = json.parseToJsonElement(tendered.bodyAsText()).jsonObject["tender"]!!.jsonObject
        assertEquals(375L, tender["changeCents"]!!.jsonPrimitive.long)
        assertEquals(2625L, tender["amountAppliedCents"]!!.jsonPrimitive.long)

        val closed = c.post("/checks/$checkId/finalize")
        assertEquals(HttpStatusCode.OK, closed.status)
        assertEquals("CLOSED", json.parseToJsonElement(closed.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)

        // receipt: written to file by the virtual printer + fetchable for preview
        val receiptFiles = File(receiptsDir).listFiles()!!
        assertEquals(1, receiptFiles.size)
        val receiptText = json.parseToJsonElement(c.get("/checks/$checkId/receipt").bodyAsText())
            .jsonObject["text"]!!.jsonPrimitive.content
        assertEquals(receiptFiles[0].readText(), receiptText)
        assertTrue("Copper Lantern — Vieux-Port" in receiptText)
        assertTrue("Lantern House Lager (60 oz pitcher) ×1" in receiptText)
        // exactly ONE Lantern House Lager line — guards against variant-join fan-out
        assertEquals(1, receiptText.lines().count { it.startsWith("Lantern House Lager") })
        assertTrue("Je ne veux pas de glace." in receiptText)
        assertTrue("Corkage" in receiptText)
        assertTrue("Total" in receiptText && "56.25" in receiptText)
        assertTrue("2026" in receiptText)          // four-digit year
        assertTrue("impôt" !in receiptText)          // tax hidden for CopperLantern
        assertTrue("Card" in receiptText && "Cash" in receiptText)
        assertTrue("Change" in receiptText)

        // every mutation wrote an outbox event, in order (migration 012's one-off
        // table.relabeled/table.removed seeding events are infrastructure, not lifecycle)
        val eventTypes = transaction {
            SyncOutbox.selectAll().orderBy(SyncOutbox.id).map { it[SyncOutbox.eventType] }
                .filterNot { it == "table.relabeled" || it == "table.removed" }
        }
        assertEquals(
            listOf(
                "catalog.seeded", "auth.login", "shift.opened", "check.opened", "check.line_added", "check.line_added",
                "check.corkage_set", "check.total_locked", "check.tender_initiated",
                "check.tender_confirmed", "check.tendered", "check.closed", "receipt.printed",
            ),
            eventTypes,
        )
    }

    @Test
    fun voidRequiresManagerAndWritesOutbox() = testApplication {
        application { module(dbPath = tempDb()) }
        // a server acts: it lacks the `void` grant, so a void needs manager approval (CONTRACT §7)
        val c = loginClient("9999")

        val opened = c.post("/tables/t1/checks") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"server1"}""")
        }
        val checkId = Json.parseToJsonElement(opened.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.int

        // the server's own PIN can't approve a void (no void grant) → 403
        c.post("/checks/$checkId/void") {
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"Le client a changé d'avis.","managerPin":"9999"}""")
        }.let { assertEquals(HttpStatusCode.Forbidden, it.status) }

        val voided = c.post("/checks/$checkId/void") {
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"Le client a changé d'avis.","managerPin":"1234"}""")
        }
        assertEquals(HttpStatusCode.OK, voided.status)
        assertEquals("VOID",
            Json.parseToJsonElement(voided.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)

        val types = transaction { SyncOutbox.selectAll().map { it[SyncOutbox.eventType] } }
        assertTrue("check.voided" in types)
    }

    @Test
    fun removeLineOnlyWhileOpenAndWritesOutbox() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val json = Json { ignoreUnknownKeys = true }

        val checkId = json.parseToJsonElement(c.post("/tables/t2/checks") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"manager"}""")
        }.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.int

        val withLine = json.parseToJsonElement(c.post("/checks/$checkId/lines") {
            contentType(ContentType.Application.Json)
            setBody("""{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":1}""")
        }.bodyAsText()).jsonObject
        val lineId = withLine["lines"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.int

        val afterRemove = c.delete("/checks/$checkId/lines/$lineId")
        assertEquals(HttpStatusCode.OK, afterRemove.status)
        val check = json.parseToJsonElement(afterRemove.bodyAsText()).jsonObject
        assertEquals(0, check["lines"]!!.jsonArray.size)
        assertEquals(0L, check["grandTotalCents"]!!.jsonPrimitive.long)
        // removing the last line strips the check to empty → it auto-cancels (no void,
        // no reason). See OrphanCheckCancelTest for the full free-the-table behavior.
        assertEquals("CANCELLED", check["status"]!!.jsonPrimitive.content)

        // the check is no longer OPEN, so a further line edit is refused (409, not 404) —
        // "remove only while open" still holds
        assertEquals(HttpStatusCode.Conflict, c.delete("/checks/$checkId/lines/$lineId").status)
        val events = transaction { SyncOutbox.selectAll().map { it[SyncOutbox.eventType] } }
        assertTrue("check.line_removed" in events)
        assertTrue("check.cancelled" in events)
    }

    /** Money movement needs a shift to land in — otherwise Z-report drawer math is blind to it. */
    @Test
    fun tenderRequiresOpenShift() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val json = Json { ignoreUnknownKeys = true }

        val checkId = json.parseToJsonElement(c.post("/tables/t1/checks") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.int
        c.post("/checks/$checkId/lines") {
            contentType(ContentType.Application.Json)
            setBody("""{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":1}""")
        }.let { assertEquals(HttpStatusCode.Created, it.status) }

        // cash, initiate, confirm — all refused with no_open_shift
        val cash = c.post("/checks/$checkId/tenders") {
            contentType(ContentType.Application.Json)
            setBody("""{"type":"CASH","amountTenderedCents":20000}""")
        }
        assertEquals(HttpStatusCode.Conflict, cash.status)
        assertTrue("no_open_shift" in cash.bodyAsText())
        val initiate = c.post("/checks/$checkId/tenders/initiate") {
            contentType(ContentType.Application.Json)
            setBody("""{"type":"CARD"}""")
        }
        assertEquals(HttpStatusCode.Conflict, initiate.status)
        assertTrue("no_open_shift" in initiate.bodyAsText())

        // basket stays OPEN and editable — only money movement is gated
        val check = json.parseToJsonElement(c.get("/checks/$checkId").bodyAsText()).jsonObject
        assertEquals("OPEN", check["status"]!!.jsonPrimitive.content)

        // open a shift → the same tender goes through
        c.post("/shifts") {
            contentType(ContentType.Application.Json)
            setBody("""{"openingFloatCents":0,"managerPin":"1234"}""")
        }.let { assertEquals(HttpStatusCode.Created, it.status) }
        c.post("/checks/$checkId/tenders") {
            contentType(ContentType.Application.Json)
            setBody("""{"type":"CASH","amountTenderedCents":20000}""")
        }.let { assertEquals(HttpStatusCode.Created, it.status) }
    }

    @Test
    fun eightySixedItemDisappearsFromCatalog() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()

        c.post("/items/lantern-lager/availability") {
            contentType(ContentType.Application.Json)
            setBody("""{"active":false,"managerPin":"1234"}""")
        }.let { assertEquals(HttpStatusCode.OK, it.status) }

        val items = Json.parseToJsonElement(c.get("/items").bodyAsText()).jsonArray
        assertTrue(items.none { it.jsonObject["id"]!!.jsonPrimitive.content == "lantern-lager" })
    }
}
