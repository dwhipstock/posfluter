package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.reports.maxReportSales
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Many stores × many days (load test, docs/load-test-report.md): the dashboard
 * is summed in Postgres, so it answers for any range; the reports that still
 * add up individual sales in the API refuse a scope too big for its memory
 * with a 413 instead of taking the API down for everyone.
 */
class ReportScaleTest {

    private val key = "store-key-scale"
    private lateinit var session: String

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("copperlantern")
        seedStoreKey("copperlantern", "vieux-port", key)
        session = seedSession("copperlantern", seedUser("copperlantern", "scale@test.dev", "password-x"))
    }

    @After
    fun tearDown() {
        maxReportSales = 150_000
    }

    private fun line(id: Long, item: String, qty: Int, total: Long) = buildJsonObject {
        put("lineId", id); put("itemId", item); put("categoryId", "beer"); put("nameEn", item); put("nameFr", item)
        put("qty", qty); put("unitPriceCents", total / qty); put("lineTotalCents", total)
    }

    private fun tender(id: Long, type: String, applied: Long) = buildJsonObject {
        put("tenderId", id); put("type", type); put("amountAppliedCents", applied)
    }

    @Test
    fun `the dashboard is summed in the database and big row reports refuse politely`() = testApplication {
        application { module(TestSupport.config) }
        ingest(key,
            event("check.closed", checkClosedPayload(1, 1130, 130, "2026-07-01T19:05:00",
                lines = buildJsonArray { add(line(1, "lager", 2, 1000)) },
                tenders = buildJsonArray { add(tender(1, "CASH", 1130)) }), seq = 1),
            event("check.closed", checkClosedPayload(2, 2260, 260, "2026-07-01T19:40:00",
                lines = buildJsonArray { add(line(2, "lager", 1, 500)); add(line(3, "stout", 3, 1500)) },
                tenders = buildJsonArray { add(tender(2, "CARD", 2260)) }), seq = 2),
            event("check.closed", checkClosedPayload(3, 565, 65, "2026-07-02T12:10:00",
                lines = buildJsonArray { add(line(4, "stout", 1, 500)) },
                tenders = buildJsonArray { add(tender(3, "CARD", 565)) }), seq = 3),
        )
        maxReportSales = 2 // three sales in range: too many for a row report now
        val range = "from=2026-07-01&to=2026-07-02"
        fun json(path: String) = suspend { testJson.parseToJsonElement(getWithCookie(path, session).bodyAsText()).jsonObject }

        val summary = json("/v1/reports/summary?$range")()
        assertEquals(3955L, summary["grossCents"]!!.jsonPrimitive.long)
        assertEquals(3, summary["checkCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf("2026-07-01" to 3390L, "2026-07-02" to 565L),
            summary["byDay"]!!.jsonArray.map { it.jsonObject["date"]!!.jsonPrimitive.content to it.jsonObject["grossCents"]!!.jsonPrimitive.long })

        val pay = json("/v1/reports/payments?$range")()
        assertEquals(3955L, pay["totalCents"]!!.jsonPrimitive.long)
        assertEquals(listOf("CARD" to 2825L, "CASH" to 1130L),
            pay["rows"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content to it.jsonObject["amountCents"]!!.jsonPrimitive.long })

        val items = json("/v1/reports/items?$range")()
        assertEquals(listOf(Triple("stout", 4, 2000L), Triple("lager", 3, 1500L)), items["rows"]!!.jsonArray.map {
            val o = it.jsonObject
            Triple(o["itemId"]!!.jsonPrimitive.content, o["qty"]!!.jsonPrimitive.content.toInt(), o["revenueCents"]!!.jsonPrimitive.long)
        })

        val hourly = json("/v1/reports/hourly?$range")()["rows"]!!.jsonArray.map { it.jsonObject }
        assertEquals(24, hourly.size)
        assertEquals(3955L, hourly.sumOf { it["grossCents"]!!.jsonPrimitive.long })

        assertEquals(HttpStatusCode.OK, getWithCookie("/v1/reports/by-venue?$range", session).status)
        assertEquals(HttpStatusCode.OK, getWithCookie("/v1/reports/tax?$range", session).status)

        val refused = getWithCookie("/v1/reports/categories?$range", session)
        assertEquals(HttpStatusCode.PayloadTooLarge, refused.status)
        assertEquals("report_too_large", testJson.parseToJsonElement(refused.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
        // one day is within the limit
        assertEquals(HttpStatusCode.OK, getWithCookie("/v1/reports/categories?from=2026-07-02&to=2026-07-02", session).status)
    }
}
