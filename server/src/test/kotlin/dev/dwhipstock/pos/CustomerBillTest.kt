package dev.dwhipstock.pos

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** GET /m/{tableId}/bill — the guest-phone running bill. */
class CustomerBillTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    private suspend fun io.ktor.client.HttpClient.postJson(path: String, body: String) =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun ApplicationTestBuilder.bill(tableId: String) =
        json.parseToJsonElement(client.get("${customerPath(tableId)}/bill").bodyAsText()).jsonObject

    /**
     * The hard rule: the bill is strictly the table's CURRENT open check. No
     * check ever → empty; check CLOSED → empty again — a previous party's bill
     * must never leak to the next scan.
     */
    @Test
    fun emptyWhenNoOpenCheckAndNeverAClosedOne() = testApplication {
        application { module(dbPath = tempDb(), receiptsDir = Files.createTempDirectory("pos-r").toString()) }
        val c = loginClient()

        // never any check on this table
        val empty = bill("t5-5")
        assertFalse(empty["open"]!!.jsonPrimitive.boolean)
        assertEquals(0, empty["lines"]!!.jsonArray.size)
        assertEquals(0L, empty["grandTotalCents"]!!.jsonPrimitive.long)

        // unknown table is a 404, not an empty bill
        assertEquals(HttpStatusCode.NotFound, client.get("/m/no-such-table/bill").status)

        // ring + pay a full check, then scan again → empty, not the closed bill
        c.postJson("/shifts", """{"openingFloatCents":100000,"managerPin":"1234"}""")
        val checkId = json.parseToJsonElement(
            c.postJson("/tables/t5-5/checks", "{}").bodyAsText()).jsonObject["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$checkId/lines", """{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":2}""")
        assertTrue(bill("t5-5")["open"]!!.jsonPrimitive.boolean)
        c.postJson("/checks/$checkId/tenders", """{"type":"CASH","amountTenderedCents":22000}""")
        c.post("/checks/$checkId/finalize")

        val after = bill("t5-5")
        assertFalse(after["open"]!!.jsonPrimitive.boolean)
        assertEquals(0, after["lines"]!!.jsonArray.size)
        assertEquals(0L, after["grandTotalCents"]!!.jsonPrimitive.long)
    }

    /** QR order lands as pending (not in total), accept moves it onto the bill; corkage shows as a fee. */
    @Test
    fun pendingThenAcceptedWithFeesAndNoInternalIds() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()

        client.postJson("${customerPath("t6")}/pending-lines",
            """{"lines":[{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":2,"note":"Cool."}]}""")

        val pendingBill = bill("t6")
        assertTrue(pendingBill["open"]!!.jsonPrimitive.boolean)
        assertEquals(0, pendingBill["lines"]!!.jsonArray.size)
        assertEquals(0L, pendingBill["grandTotalCents"]!!.jsonPrimitive.long)
        val pending = pendingBill["pendingLines"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, pending.size)
        assertEquals("Copper Amber Ale", pending[0]["nameEn"]!!.jsonPrimitive.content)
        assertEquals(2, pending[0]["qty"]!!.jsonPrimitive.int)
        assertEquals(1750L, pending[0]["lineTotalCents"]!!.jsonPrimitive.long)

        // customer DTO carries display fields only — no line/item/check ids
        for (line in pending) assertTrue(line.keys.none { it in setOf("id", "itemId", "variantId", "unitPriceCents") },
            "leaked internal field in ${line.keys}")
        assertTrue("checkId" !in pendingBill.keys && "id" !in pendingBill.keys)

        // staff accepts + adds corkage → line and fee on the bill, total is the pipeline's
        val checkId = json.parseToJsonElement(c.get("/zones").bodyAsText()).jsonArray
            .flatMap { it.jsonObject["tables"]!!.jsonArray }
            .first { it.jsonObject["id"]!!.jsonPrimitive.content == "t6" }
            .jsonObject["openCheckId"]!!.jsonPrimitive.int
        val lineId = json.parseToJsonElement(c.get("/checks/$checkId").bodyAsText())
            .jsonObject["pendingLines"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.int
        c.post("/checks/$checkId/pending-lines/$lineId/accept")
        c.postJson("/checks/$checkId/corkage", """{"bottles":1}""")

        val accepted = bill("t6")
        assertEquals(0, accepted["pendingLines"]!!.jsonArray.size)
        val lines = accepted["lines"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, lines.size)
        assertEquals(1750L, lines[0]["lineTotalCents"]!!.jsonPrimitive.long)
        val fees = accepted["fees"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, fees.size)
        assertEquals("Corkage", fees[0]["labelEn"]!!.jsonPrimitive.content)
        assertEquals(2500L, fees[0]["amountCents"]!!.jsonPrimitive.long)
        assertEquals(4250L, accepted["grandTotalCents"]!!.jsonPrimitive.long)
        assertFalse(accepted["locked"]!!.jsonPrimitive.boolean)
        // hidden VAT: the customer payload never carries a tax field
        assertTrue(accepted.keys.none { "tax" in it.lowercase() })
    }
}
