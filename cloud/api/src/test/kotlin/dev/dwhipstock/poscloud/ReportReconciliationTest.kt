package dev.dwhipstock.poscloud

import io.ktor.client.statement.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The reconciliation invariant the VAT/payment-mix bug violated: over any
 * multi-check range, a check counted in GROSS must also contribute its VAT and
 * its tenders. Asserts, against complete report-complete events:
 *   - VAT total == Σ per-check store-decomposed tax  (not one check's tax)
 *   - payment-mix total == GROSS                      (every CAD is tendered)
 *   - NET + VAT == GROSS
 */
class ReportReconciliationTest {

    private val key = "store-key-recon"
    private lateinit var session: String

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("copperlantern")
        seedStoreKey("copperlantern", "vieux-port", key)
        session = seedSession("copperlantern", seedUser("copperlantern", "recon@test.dev", "password-x"))
    }

    private fun tender(id: Long, type: String, applied: Long) = buildJsonObject {
        put("tenderId", id)
        put("type", type)
        put("amountAppliedCents", applied)
    }

    @Test
    fun vatPaymentsAndNetReconcileWithGrossOverARange() = testApplication {
        application { module(TestSupport.config) }

        // Three closed checks over two days; every one fully tendered (its
        // tenders sum to its grand total) and carrying its decomposed VAT.
        val gross = listOf(53500L, 10000L, 25000L)
        val tax = gross.map { storeTax(it) }
        ingest(
            key,
            event("check.closed", checkClosedPayload(
                1, gross[0], tax[0], "2026-07-01T19:00:00",
                tenders = buildJsonArray { add(tender(1, "CASH", 53500)) },
            ), seq = 1),
            event("check.closed", checkClosedPayload(
                2, gross[1], tax[1], "2026-07-01T21:30:00",
                tenders = buildJsonArray { add(tender(2, "CARD", 10000)) },
            ), seq = 2),
            // split tender: two rows that together cover the grand total
            event("check.closed", checkClosedPayload(
                3, gross[2], tax[2], "2026-07-02T20:15:00",
                tenders = buildJsonArray {
                    add(tender(3, "CASH", 20000))
                    add(tender(4, "BANK_TRANSFER", 5000))
                },
            ), seq = 3),
        )

        val grossTotal = gross.sum()
        val vatTotal = tax.sum()
        val range = "from=2026-07-01&to=2026-07-02"

        val summary = testJson.parseToJsonElement(
            getWithCookie("/v1/reports/summary?$range", session).bodyAsText()
        ).jsonObject
        assertEquals(grossTotal, summary["grossCents"]!!.jsonPrimitive.content.toLong())
        // VAT is the SUM of every check's tax, not a single detailed check's
        assertEquals(vatTotal, summary["vatCents"]!!.jsonPrimitive.content.toLong())
        assertEquals(grossTotal - vatTotal, summary["netCents"]!!.jsonPrimitive.content.toLong())
        assertEquals(3, summary["checkCount"]!!.jsonPrimitive.content.toInt())

        val vat = testJson.parseToJsonElement(
            getWithCookie("/v1/reports/vat?$range", session).bodyAsText()
        ).jsonObject["totals"]!!.jsonObject
        assertEquals(grossTotal, vat["grossCents"]!!.jsonPrimitive.content.toLong())
        assertEquals(vatTotal, vat["vatCents"]!!.jsonPrimitive.content.toLong())

        val payments = testJson.parseToJsonElement(
            getWithCookie("/v1/reports/payments?$range", session).bodyAsText()
        ).jsonObject
        // payment-mix total reconciles with gross: every CAD was tendered
        assertEquals(grossTotal, payments["totalCents"]!!.jsonPrimitive.content.toLong())
        val rows = payments["rows"]!!.jsonArray.map { it.jsonObject }
        assertEquals(grossTotal, rows.sumOf { it["amountCents"]!!.jsonPrimitive.content.toLong() })
        // and the mix is split across the three tender types the sales used
        val byType = rows.associate {
            it["type"]!!.jsonPrimitive.content to it["amountCents"]!!.jsonPrimitive.content.toLong()
        }
        assertEquals(73500L, byType["CASH"])          // 53500 + 20000
        assertEquals(10000L, byType["CARD"])
        assertEquals(5000L, byType["BANK_TRANSFER"])
    }
}
