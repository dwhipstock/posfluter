package dev.dwhipstock.poscloud

import io.ktor.client.statement.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class VatReportTest {

    private val key = "store-key-vat"
    private lateinit var session: String

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("copperlantern")
        seedStoreKey("copperlantern", "main", key)
        session = seedSession("copperlantern", seedUser("copperlantern", "vat@test.dev", "password-x"))
    }

    @Test
    fun vatReportSumsStoreComputedFiguresExactly() = testApplication {
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

        val body = testJson.parseToJsonElement(
            getWithCookie("/v1/reports/vat?from=2026-07-01&to=2026-07-02", session).bodyAsText()
        ).jsonObject
        assertEquals(13, body["ratePercent"]!!.jsonPrimitive.content.toInt())

        val rows = body["rows"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, rows.size)

        val day1Gross = 53500L + 10000L
        val day1Vat = storeTax(53500) + storeTax(10000)
        assertEquals("2026-07-01", rows[0]["date"]!!.jsonPrimitive.content)
        assertEquals(day1Gross, rows[0]["grossCents"]!!.jsonPrimitive.content.toLong())
        assertEquals(day1Vat, rows[0]["vatCents"]!!.jsonPrimitive.content.toLong())
        assertEquals(day1Gross - day1Vat, rows[0]["netCents"]!!.jsonPrimitive.content.toLong())
        assertEquals(2, rows[0]["checkCount"]!!.jsonPrimitive.content.toInt())

        // legacy check contributes gross with vat treated as 0
        val day2Gross = 25000L + 15000L
        val day2Vat = storeTax(25000)
        assertEquals("2026-07-02", rows[1]["date"]!!.jsonPrimitive.content)
        assertEquals(day2Gross, rows[1]["grossCents"]!!.jsonPrimitive.content.toLong())
        assertEquals(day2Vat, rows[1]["vatCents"]!!.jsonPrimitive.content.toLong())
        assertEquals(day2Gross - day2Vat, rows[1]["netCents"]!!.jsonPrimitive.content.toLong())
        assertEquals(2, rows[1]["checkCount"]!!.jsonPrimitive.content.toInt())

        val totals = body["totals"]!!.jsonObject
        assertEquals(day1Gross + day2Gross, totals["grossCents"]!!.jsonPrimitive.content.toLong())
        assertEquals(day1Vat + day2Vat, totals["vatCents"]!!.jsonPrimitive.content.toLong())
        assertEquals(
            (day1Gross + day2Gross) - (day1Vat + day2Vat),
            totals["netCents"]!!.jsonPrimitive.content.toLong())
        assertEquals(4, totals["checkCount"]!!.jsonPrimitive.content.toInt())
    }
}
