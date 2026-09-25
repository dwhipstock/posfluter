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
 * Refunds net out of the summary + VAT reports, and drive the Refunds and Cash
 * movements reports. The store decomposed gross/net/VAT; the cloud only sums —
 * and its projections are idempotent by refund_id / movement_id.
 */
class RefundAndCashMovementReportTest {

    private val key = "store-key-refund"
    private lateinit var session: String

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("copperlantern")
        seedStoreKey("copperlantern", "vieux-port", key)
        session = seedSession("copperlantern", seedUser("copperlantern", "refund@test.dev", "password-x"))
    }

    private fun refund(
        id: Long, checkId: Int, gross: Long, net: Long, tax: Long,
        tender: String, reason: String, at: String,
    ) = buildJsonObject {
        put("refundId", id)
        put("checkId", checkId)
        put("grossCents", gross)
        put("netCents", net)
        put("taxIncludedCents", tax)
        put("tenderType", tender)
        put("reason", reason)
        put("createdAt", at)
    }

    private fun cashMovement(id: Long, direction: String, amount: Long, reason: String, at: String) =
        buildJsonObject {
            put("movementId", id)
            put("direction", direction)
            put("amountCents", amount)
            put("reason", reason)
            put("user", "manager")
            put("createdAt", at)
        }

    @Test
    fun refundsReduceSalesAndVatAndDriveTheNewReports() = testApplication {
        application { module(TestSupport.config) }

        val gross = 10000L
        val tax = storeTax(gross) // 654
        // refund $40 of the $100 bill, cash; store-decomposed VAT within it
        val rGross = 4000L
        val rTax = storeTax(rGross) // 262
        val rNet = rGross - rTax

        ingest(
            key,
            event("check.closed", checkClosedPayload(
                1, gross, tax, "2026-07-10T19:00:00",
                tenders = buildJsonArray { add(buildJsonObject {
                    put("tenderId", 1); put("type", "CASH"); put("amountAppliedCents", gross)
                }) },
            ), seq = 1),
            event("refund.created",
                refund(1, 1, rGross, rNet, rTax, "CASH", "Il y a un problème avec le produit.", "2026-07-10T20:00:00"),
                seq = 2, aggregateType = "refund"),
            event("cash.movement",
                cashMovement(1, "IN", 50000, "Ajouter un changement", "2026-07-10T18:00:00"),
                seq = 3, aggregateType = "cash"),
            event("cash.movement",
                cashMovement(2, "OUT", 20000, "acheter de la glace", "2026-07-10T18:30:00"),
                seq = 4, aggregateType = "cash"),
        )

        val range = "from=2026-07-10&to=2026-07-10"

        // summary: sales + VAT are net of the refund; check count unchanged;
        // avg check is the sale-time figure (pre-refund)
        val summary = testJson.parseToJsonElement(
            getWithCookie("/v1/reports/summary?$range", session).bodyAsText()).jsonObject
        assertEquals(gross - rGross, summary["grossCents"]!!.jsonPrimitive.content.toLong())
        assertEquals(tax - rTax, summary["vatCents"]!!.jsonPrimitive.content.toLong())
        assertEquals((gross - rGross) - (tax - rTax), summary["netCents"]!!.jsonPrimitive.content.toLong())
        assertEquals(1, summary["checkCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(gross, summary["avgCheckCents"]!!.jsonPrimitive.content.toLong())
        assertEquals(1, summary["refundCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(rGross, summary["refundAmountCents"]!!.jsonPrimitive.content.toLong())

        // vat report totals reconcile the same way
        val vat = testJson.parseToJsonElement(
            getWithCookie("/v1/reports/vat?$range", session).bodyAsText()
        ).jsonObject["totals"]!!.jsonObject
        assertEquals(gross - rGross, vat["grossCents"]!!.jsonPrimitive.content.toLong())
        assertEquals(tax - rTax, vat["vatCents"]!!.jsonPrimitive.content.toLong())

        // refunds report: totals + by-reason + by-tender
        val refunds = testJson.parseToJsonElement(
            getWithCookie("/v1/reports/refunds?$range", session).bodyAsText()).jsonObject
        assertEquals(1, refunds["count"]!!.jsonPrimitive.content.toInt())
        assertEquals(rGross, refunds["grossCents"]!!.jsonPrimitive.content.toLong())
        assertEquals(rNet, refunds["netCents"]!!.jsonPrimitive.content.toLong())
        assertEquals(rTax, refunds["vatCents"]!!.jsonPrimitive.content.toLong())
        val byReason = refunds["byReason"]!!.jsonArray.map { it.jsonObject }
        assertEquals("Il y a un problème avec le produit.", byReason.single()["reason"]!!.jsonPrimitive.content)
        assertEquals("CASH", refunds["byTender"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)

        // cash movements report: pay-in / pay-out with the net
        val cash = testJson.parseToJsonElement(
            getWithCookie("/v1/reports/cash-movements?$range", session).bodyAsText()).jsonObject
        assertEquals(50000L, cash["paidInCents"]!!.jsonPrimitive.content.toLong())
        assertEquals(20000L, cash["paidOutCents"]!!.jsonPrimitive.content.toLong())
        assertEquals(30000L, cash["netCents"]!!.jsonPrimitive.content.toLong())
        assertEquals(2, cash["rows"]!!.jsonArray.size)

        // idempotent projection: re-ingesting the same refund_id (new event id) does not double-count
        ingest(
            key,
            event("refund.created",
                refund(1, 1, rGross, rNet, rTax, "CASH", "Il y a un problème avec le produit.", "2026-07-10T20:00:00"),
                seq = 5, aggregateType = "refund"),
        )
        val refunds2 = testJson.parseToJsonElement(
            getWithCookie("/v1/reports/refunds?$range", session).bodyAsText()).jsonObject
        assertEquals(1, refunds2["count"]!!.jsonPrimitive.content.toInt())
        assertEquals(rGross, refunds2["grossCents"]!!.jsonPrimitive.content.toLong())
    }
}
