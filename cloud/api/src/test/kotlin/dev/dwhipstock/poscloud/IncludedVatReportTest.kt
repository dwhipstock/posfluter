package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.Checks
import dev.dwhipstock.poscloud.db.Refunds
import dev.dwhipstock.poscloud.db.Venues
import dev.dwhipstock.poscloud.menuprint.PrintWords
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A store in baht whose prices include a 7% VAT: its sales arrive with the
 * VAT as a tax line flagged `included` (inside taxIncludedCents, never on top
 * of the subtotal). The portal's reports show the exact gross (what the
 * guests paid), the VAT, the net before tax, the VAT on its own row of the
 * per-tax report less refunds, and the cash rounded down to the whole baht.
 */
class IncludedVatReportTest {

    private val key = "vat-key"
    private lateinit var session: String
    private val q = "from=2026-07-21&to=2026-07-21&venue=vat-store"
    private val day = "2026-07-21T19:00:00.000+07:00"

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("vattest", "vat-store", "VAT test store")
        transaction {
            Venues.update({ (Venues.tenantId eq "vattest") and (Venues.id eq "vat-store") }) {
                it[currency] = "THB"; it[country] = "TH"; it[timezone] = "Etc/GMT-7"
            }
        }
        seedStoreKey("vattest", "vat-store", key)
        session = seedSession("vattest", seedUser("vattest", "owner@vat.test", "password-vat"))
    }

    private suspend fun ApplicationTestBuilder.get(path: String): JsonObject {
        val res = client.get(path) { header(HttpHeaders.Cookie, "pos_portal_session=$session") }
        assertEquals(HttpStatusCode.OK, res.status, "$path → ${res.bodyAsText()}")
        return testJson.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    private fun JsonObject.long(key: String) = this[key]!!.jsonPrimitive.content.toLong()
    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.arr(key: String): List<JsonObject> = this[key]!!.jsonArray.map { it.jsonObject }

    private fun vat(cents: Long) = buildJsonArray {
        add(buildJsonObject {
            put("code", "VAT"); put("labelFr", "TVA"); put("labelEn", "VAT"); put("ratePercent", "7")
            put("registrationNumber", ""); put("amountCents", cents); put("included", true)
        })
    }

    /** A sale as the store sends it: VAT inside the total, a cash or card tender. */
    private fun sale(checkId: Int, total: Long, vatCents: Long, rounding: Long, type: String = "CASH") = buildJsonObject {
        put("currency", "THB"); put("country", "TH")
        put("checkId", checkId); put("grandTotalCents", total)
        put("taxIncludedCents", vatCents)            // every tax inside the total
        put("subtotalCents", total)                  // nothing added on top
        put("taxes", vat(vatCents))
        put("closedAt", day); put("shiftId", 1)
        put("tenders", buildJsonArray {
            add(buildJsonObject {
                put("tenderId", checkId); put("type", type)
                put("amountTenderedCents", total + rounding); put("amountAppliedCents", total)
                put("roundingAdjustmentCents", rounding); put("changeCents", 0)
            })
        })
    }

    private suspend fun ApplicationTestBuilder.seed() {
        ingest(key,
            // ฿1,100.55 (VAT 72.00) paid ฿1,100 in cash: −55 satang
            event("check.closed", sale(1, 110055, 7200, -55), seq = 1, aggregateId = "1"),
            // ฿123.45 (VAT 8.08) on a card: exact
            event("check.closed", sale(2, 12345, 808, 0, "CARD"), seq = 2, aggregateId = "2"),
            // ฿500 of the first sale back in cash, its VAT reversed
            event("refund.created", buildJsonObject {
                put("currency", "THB"); put("refundId", 1); put("checkId", 1)
                put("grossCents", 50000); put("taxIncludedCents", 3271); put("netCents", 50000 - 3271)
                put("taxes", vat(3271)); put("tenderType", "CASH"); put("roundingAdjustmentCents", 0)
                put("reason", "x"); put("createdAt", "2026-07-21T20:00:00.000+07:00")
            }, seq = 3),
        )
    }

    private val gross = 110055L + 12345 - 50000
    private val tax = 7200L + 808 - 3271

    @Test
    fun theVatArrivesAsAnIncludedTaxLine() = testApplication {
        application { module(TestSupport.config) }
        seed()
        transaction {
            val c = Checks.selectAll().where { (Checks.venueId eq "vat-store") and (Checks.checkId eq 1) }.single()
            assertEquals("THB", c[Checks.currency])
            assertEquals(7200L, c[Checks.taxIncludedCents])
            assertTrue(c[Checks.taxes]!!.contains("\"included\""), c[Checks.taxes])
            // no GST / QST for a VAT store
            assertEquals(0L, c[Checks.gstCents] ?: 0L)
            assertEquals(0L, c[Checks.qstCents] ?: 0L)
            val r = Refunds.selectAll().where { Refunds.venueId eq "vat-store" }.single()
            assertEquals(3271L, r[Refunds.taxIncludedCents])
        }
    }

    @Test
    fun theTaxReportShowsGrossNetAndTheVatOnItsOwnRow() = testApplication {
        application { module(TestSupport.config) }
        seed()
        val body = get("/v1/reports/tax?$q")
        val totals = body["totals"]!!.jsonObject
        assertEquals(gross, totals.long("grossCents"))   // what the guests paid, less the refund
        assertEquals(tax, totals.long("taxCents"))
        assertEquals(gross - tax, totals.long("netCents")) // net before tax
        assertEquals(0L, totals.long("gstCents"))
        assertEquals(0L, totals.long("qstCents"))
        assertEquals(-55L, totals.long("cashRoundingCents"))

        val row = body.arr("byTax").single()
        assertEquals("VAT", row.str("code"))
        assertEquals("7", row.str("ratePercent"))
        assertEquals("THB", row.str("currency"))
        assertEquals(tax, row.long("amountCents"))
        assertTrue(row["included"]!!.jsonPrimitive.boolean)
        // the per-tax rows add up to the report's tax
        assertEquals(totals.long("taxCents"), body.arr("byTax").sumOf { it.long("amountCents") })
        val rate = body.arr("rates").single()
        assertEquals("VAT", rate.str("code"))
        assertTrue(rate["included"]!!.jsonPrimitive.boolean)
        // the store's own per-tax breakdown carries it too
        assertEquals(tax, body.arr("byVenue").single().arr("taxes").single().long("amountCents"))
    }

    @Test
    fun theSummaryIsInBahtWithTheCashRoundingBesideIt() = testApplication {
        application { module(TestSupport.config) }
        seed()
        val s = get("/v1/reports/summary?$q")
        assertEquals(gross, s.long("grossCents"))
        assertEquals(-55L, s.long("cashRoundingCents"))
        assertEquals("THB", s["money"]!!.jsonObject.str("currency"))
    }

    @Test
    fun printedMenusWriteBahtWithoutEmptySatang() {
        assertEquals("฿1,234", PrintWords.money(123400, "THB"))
        assertEquals("฿1,234.50", PrintWords.money(123450, "THB"))
        assertEquals("-฿5", PrintWords.money(-500, "THB"))
        // dollars keep their cents
        assertEquals("$1,234.00", PrintWords.money(123400, "USD"))
        assertEquals("$12.99", PrintWords.money(1299, "CAD"))
    }
}
