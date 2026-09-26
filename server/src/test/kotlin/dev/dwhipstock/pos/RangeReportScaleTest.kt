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
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A year of sales on the tablet (load test, docs/load-test-report.md): the
 * X-report layout over a date range used to load every sale of the range and
 * pass their ids to SQLite as a list, which ran the store out of memory at
 * 100,000 sales. It is summed in SQLite now; this checks the sums at scale
 * (the load test's bigdb scenario checks the memory).
 */
class RangeReportScaleTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a range report over 40,000 sales adds up`() = testApplication {
        application { module(dbPath = Files.createTempDirectory("pos-range").resolve("pos.db").toString()) }
        val c = loginClient()
        c.post("/shifts") { contentType(ContentType.Application.Json); setBody("""{"openingFloatCents":0,"managerPin":"1234"}""") }
        val items = json.parseToJsonElement(c.get("/items").bodyAsText()).jsonArray.map { it.jsonObject }
        val item = items.first()
        val variant = item["variants"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content
        // one real sale: a line, a cash tender, closed
        val check = json.parseToJsonElement(c.post("/tables/t5/checks").bodyAsText()).jsonObject
        val id = check["id"]!!.jsonPrimitive.int
        c.post("/checks/$id/lines") {
            contentType(ContentType.Application.Json)
            setBody("""{"itemId":"${item["id"]!!.jsonPrimitive.content}","variantId":"$variant","qty":2}""")
        }
        val total = json.parseToJsonElement(c.get("/checks/$id").bodyAsText()).jsonObject["grandTotalCents"]!!.jsonPrimitive.long
        c.post("/checks/$id/tenders") {
            contentType(ContentType.Application.Json); setBody("""{"type":"CASH","amountTenderedCents":${total + 1000}}""")
        }
        c.post("/checks/$id/finalize")

        // …copied 39,999 times, same day, with its tender and its line
        val copies = 39_999
        transaction {
            exec("""
                WITH RECURSIVE n(k) AS (SELECT 1 UNION ALL SELECT k + 1 FROM n WHERE k < $copies)
                INSERT INTO checks (id, table_id, status, opened_by, opened_at, closed_at, corkage_bottles,
                    locked_grand_total_cents, locked_tax_included_cents, shift_id, locked_fees_json,
                    locked_tax_added_cents, locked_taxes_json, locked_discounts_json)
                SELECT c.id + k, c.table_id, c.status, c.opened_by, c.opened_at, c.closed_at, c.corkage_bottles,
                    c.locked_grand_total_cents, c.locked_tax_included_cents, c.shift_id, c.locked_fees_json,
                    c.locked_tax_added_cents, c.locked_taxes_json, c.locked_discounts_json
                FROM checks c, n WHERE c.id = $id""")
            exec("""
                INSERT INTO tenders (transaction_id, type, amount_tendered_cents, amount_applied_cents,
                    rounding_adjustment_cents, change_cents, created_at)
                SELECT c.id, t.type, t.amount_tendered_cents, t.amount_applied_cents, t.rounding_adjustment_cents,
                    t.change_cents, t.created_at
                FROM tenders t JOIN checks c ON c.id > $id WHERE t.transaction_id = $id""")
            exec("""
                INSERT INTO check_lines (check_id, item_id, variant_id, qty, unit_price_cents, status, created_at)
                SELECT c.id, l.item_id, l.variant_id, l.qty, l.unit_price_cents, l.status, l.created_at
                FROM check_lines l JOIN checks c ON c.id > $id WHERE l.check_id = $id""")
        }

        val today = dev.dwhipstock.pos.sdk.VenueClock.today().toString()
        val res = c.get("/reports/range?from=$today&to=$today")
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText().take(300))
        val range = json.parseToJsonElement(res.bodyAsText()).jsonObject
        val n = copies + 1L
        assertEquals(n, range["transactionCount"]!!.jsonPrimitive.long)
        assertEquals(total * n, range["revenueCents"]!!.jsonPrimitive.long)
        val tender = range["tenderBreakdown"]!!.jsonArray.single().jsonObject
        assertEquals("CASH", tender["type"]!!.jsonPrimitive.content)
        assertEquals(n, tender["count"]!!.jsonPrimitive.long)
        val mix = range["itemMix"]!!.jsonArray.single().jsonObject
        assertEquals(2 * n, mix["qty"]!!.jsonPrimitive.long)

        // the shift's X report reads the same way
        val x = json.parseToJsonElement(c.get("/shifts/current/report").bodyAsText()).jsonObject
        assertEquals(total * n, x["revenueCents"]!!.jsonPrimitive.long)
    }
}
