package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.Checks
import dev.dwhipstock.poscloud.db.Refunds
import io.ktor.client.statement.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TaxReportTest {

    private val key = "store-key-tax"
    private val plateauKey = "store-key-tax-plateau"
    private lateinit var session: String

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("copperlantern")
        seedTenant("copperlantern", "plateau", "Copper Lantern — Plateau")
        seedStoreKey("copperlantern", "vieux-port", key)
        seedStoreKey("copperlantern", "plateau", plateauKey)
        session = seedSession("copperlantern", seedUser("copperlantern", "tax@test.dev", "password-x"))
    }

    private suspend fun ApplicationTestBuilder.report(query: String): JsonObject = testJson.parseToJsonElement(
        getWithCookie("/v1/reports/tax?$query", session).bodyAsText()).jsonObject

    private fun JsonObject.long(key: String) = this[key]!!.jsonPrimitive.content.toLong()
    private fun JsonObject.byVenue(field: String) =
        this["byVenue"]!!.jsonArray.associate { it.jsonObject["venueId"]!!.jsonPrimitive.content to it.jsonObject.long(field) }

    @Test
    fun taxReportSumsStoreComputedFiguresExactly() = testApplication {
        application { module(TestSupport.config) }
        // report-complete fixtures: tax follows the store formula exactly
        ingest(
            key,
            event("check.closed", checkClosedPayload(1, 53500, storeTax(53500), "2026-07-01T19:00:00"), seq = 1),
            event("check.closed", checkClosedPayload(2, 10000, storeTax(10000), "2026-07-01T21:30:00"), seq = 2),
            event("check.closed", checkClosedPayload(3, 25000, storeTax(25000), "2026-07-02T20:15:00"), seq = 3),
            // legacy thin payload: no tax, no closedAt → event createdAt buckets the day
            event("check.closed", buildJsonObject {
                put("checkId", 4)
                put("grandTotalCents", 15000)
                put("shiftId", 3)
            }, seq = 4, createdAt = "2026-07-02T23:00:00"),
        )

        val body = report("venue=vieux-port&from=2026-07-01&to=2026-07-02")
        // older payloads carry no per-tax breakdown: no rate is assumed, GST/QST stay 0
        assertEquals(0, body["rates"]!!.jsonArray.size)

        val rows = body["rows"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, rows.size)

        val day1Gross = 53500L + 10000L
        val day1Tax = storeTax(53500) + storeTax(10000)
        assertEquals("2026-07-01", rows[0]["date"]!!.jsonPrimitive.content)
        assertEquals(day1Gross, rows[0].long("grossCents"))
        assertEquals(day1Tax, rows[0].long("taxCents"))
        assertEquals(day1Gross - day1Tax, rows[0].long("netCents"))
        assertEquals(2, rows[0]["checkCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(0L, rows[0].long("gstCents"))
        assertEquals(0L, rows[0].long("qstCents"))

        // legacy check contributes gross with tax treated as 0
        val day2Gross = 25000L + 15000L
        val day2Tax = storeTax(25000)
        assertEquals("2026-07-02", rows[1]["date"]!!.jsonPrimitive.content)
        assertEquals(day2Gross, rows[1].long("grossCents"))
        assertEquals(day2Tax, rows[1].long("taxCents"))
        assertEquals(day2Gross - day2Tax, rows[1].long("netCents"))
        assertEquals(2, rows[1]["checkCount"]!!.jsonPrimitive.content.toInt())

        val totals = body["totals"]!!.jsonObject
        assertEquals(day1Gross + day2Gross, totals.long("grossCents"))
        assertEquals(day1Tax + day2Tax, totals.long("taxCents"))
        assertEquals((day1Gross + day2Gross) - (day1Tax + day2Tax), totals.long("netCents"))
        assertEquals(4, totals["checkCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(0L, totals.long("gstCents"))
        assertEquals(0L, totals.long("qstCents"))
    }

    /**
     * GST and QST come from the per-sale amounts the stores charged, per store
     * and across all stores, net of the tax refunds reversed — and a sale
     * without a breakdown adds 0, never an estimate.
     */
    @Test
    fun gstAndQstSumPerStoreAndAcrossAllStores() = testApplication {
        application { module(TestSupport.config) }
        // Vieux-Port: 10.00 → GST 0.50 + QST 1.00; 40.90 → GST 2.05 + QST 4.08; a legacy sale
        ingest(
            key,
            event("check.closed", qcCheckClosedPayload(1, 1000, "2026-07-01T19:00:00.000-04:00"), seq = 1),
            event("check.closed", qcCheckClosedPayload(2, 4090, "2026-07-01T20:00:00.000-04:00"), seq = 2),
            event("check.closed", checkClosedPayload(3, 5000, closedAt = "2026-07-01T21:00:00.000-04:00"), seq = 3),
            // the 10.00 sale refunded in full: its GST and QST come back out
            event("refund.created", buildJsonObject {
                put("refundId", 1); put("checkId", 1)
                put("grossCents", 1150); put("netCents", 1000); put("taxIncludedCents", 150)
                put("tenderType", "CASH"); put("reason", "Order error")
                put("createdAt", "2026-07-01T22:00:00.000-04:00")
                put("taxes", qcTaxesJson(1000))
            }, seq = 4),
        )
        // Plateau: 25.00 → GST 1.25 + QST 2.49375 → 2.49
        ingest(plateauKey,
            event("check.closed", qcCheckClosedPayload(1, 2500, "2026-07-01T19:30:00.000-04:00"), seq = 1))
        assertEquals(125L to 249L, qcTaxes(2500))

        val q = "from=2026-07-01&to=2026-07-01"
        val all = report(q)
        val vp = report("venue=vieux-port&$q")
        val pl = report("venue=plateau&$q")

        // the rates shown come from the stores' own breakdown
        assertEquals(listOf("GST" to "5", "QST" to "9.975"), all["rates"]!!.jsonArray.map {
            it.jsonObject["code"]!!.jsonPrimitive.content to it.jsonObject["ratePercent"]!!.jsonPrimitive.content })

        // per store: Vieux-Port 0.50 + 2.05 − 0.50 GST, 1.00 + 4.08 − 1.00 QST; the legacy sale adds 0
        val vpTotals = vp["totals"]!!.jsonObject
        assertEquals(205L, vpTotals.long("gstCents"))
        assertEquals(408L, vpTotals.long("qstCents"))
        assertEquals(613L, vpTotals.long("taxCents"))
        assertEquals(1150L + 4703L + 5000L - 1150L, vpTotals.long("grossCents"))
        val plTotals = pl["totals"]!!.jsonObject
        assertEquals(125L, plTotals.long("gstCents"))
        assertEquals(249L, plTotals.long("qstCents"))

        // all stores: the sum of both, with a per-store breakdown that adds up to it
        val allTotals = all["totals"]!!.jsonObject
        assertEquals(330L, allTotals.long("gstCents"))
        assertEquals(657L, allTotals.long("qstCents"))
        assertEquals(mapOf("vieux-port" to 205L, "plateau" to 125L), all.byVenue("gstCents"))
        assertEquals(mapOf("vieux-port" to 408L, "plateau" to 249L), all.byVenue("qstCents"))
        val day = all["rows"]!!.jsonArray.single().jsonObject
        assertEquals(330L, day.long("gstCents"))
        assertEquals(657L, day.long("qstCents"))
        assertEquals(mapOf("vieux-port" to 205L, "plateau" to 125L), day.byVenue("gstCents"))
        // a single store's report holds only that store
        assertEquals(listOf("plateau"), pl["byVenue"]!!.jsonArray.map { it.jsonObject["venueId"]!!.jsonPrimitive.content })
    }

    /** An older store sends no tax fields at all; its events still ingest, and store NULL, not 0. */
    @Test
    fun oldPayloadsWithoutTaxFieldsStillIngest() = testApplication {
        application { module(TestSupport.config) }
        val res = ingest(
            key,
            event("check.closed", checkClosedPayload(1, 2250, 0, "2026-07-01T19:00:00"), seq = 1),
            event("check.voided", buildJsonObject {
                put("checkId", 2); put("reason", "x"); put("amountCents", 900); put("taxIncludedCents", 0)
            }, seq = 2),
            event("refund.created", buildJsonObject {
                put("refundId", 1); put("checkId", 1)
                put("grossCents", 2250); put("netCents", 2250); put("taxIncludedCents", 0)
                put("tenderType", "CASH"); put("reason", "x"); put("createdAt", "2026-07-01T20:00:00")
            }, seq = 3),
        )
        assertEquals(3, res["accepted"]!!.jsonPrimitive.content.toInt())
        transaction {
            Checks.selectAll().forEach {
                assertNull(it[Checks.gstCents]); assertNull(it[Checks.qstCents]); assertNull(it[Checks.taxes])
            }
            Refunds.selectAll().forEach { assertNull(it[Refunds.gstCents]); assertNull(it[Refunds.qstCents]) }
        }
        val totals = report("from=2026-07-01&to=2026-07-01")["totals"]!!.jsonObject
        assertEquals(0L, totals.long("grossCents")) // 22.50 sold, 22.50 refunded
        assertEquals(0L, totals.long("gstCents"))
        assertEquals(0L, totals.long("qstCents"))
    }
}
