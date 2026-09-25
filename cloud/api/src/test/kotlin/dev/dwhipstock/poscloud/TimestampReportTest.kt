package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.Checks
import dev.dwhipstock.poscloud.db.Venues
import io.ktor.client.statement.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals

/**
 * CONTRACT v2 timestamps: the wire carries instants with an offset, the cloud
 * stores timestamptz, and the venue's zone is applied only for day/hour
 * grouping and display.
 */
class TimestampReportTest {

    private val key = "store-key-ts"
    private lateinit var session: String

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("copperlantern", "main")
        seedStoreKey("copperlantern", "main", key)
        session = seedSession("copperlantern", seedUser("copperlantern", "ts@test.dev", "password-t"))
    }

    private suspend fun ApplicationTestBuilder.report(path: String): JsonObject =
        testJson.parseToJsonElement(getWithCookie(path, session).bodyAsText()).jsonObject

    @Test
    fun theRepeatedFallBackHourOrdersByInstantAndStaysOnItsBusinessDay() = testApplication {
        application { module(TestSupport.config) }
        // 01:40 EDT, clocks fall back, then 01:10 EST — later despite the earlier wall clock
        ingest(key,
            event("check.closed", checkClosedPayload(1, 1000, 0, closedAt = "2026-11-01T01:40:00.000-04:00"),
                seq = 1, aggregateId = "1"),
            event("check.closed", checkClosedPayload(2, 2000, 0, closedAt = "2026-11-01T01:10:00.000-05:00"),
                seq = 2, aggregateId = "2"),
        )
        val stored = transaction {
            Checks.selectAll().orderBy(Checks.closedAt).map { it[Checks.checkId] to it[Checks.closedAt]!!.toInstant() }
        }
        assertEquals(listOf(1 to Instant.parse("2026-11-01T05:40:00Z"), 2 to Instant.parse("2026-11-01T06:10:00Z")), stored)

        // newest first, and each shows its own offset (wall clock stays venue-local)
        val journal = report("/v1/reports/journal?from=2026-11-01&to=2026-11-01")
        val rows = journal["rows"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(2, 1), rows.map { it["checkId"]!!.jsonPrimitive.content.toInt() })
        assertEquals("2026-11-01T01:10:00.000-05:00", rows[0]["closedAt"]!!.jsonPrimitive.content)
        assertEquals("2026-11-01T01:40:00.000-04:00", rows[1]["closedAt"]!!.jsonPrimitive.content)

        // one 25-hour business day holds both; nothing leaks into Oct 31
        val day = report("/v1/reports/summary?from=2026-11-01&to=2026-11-01")
        assertEquals(3000, day["grossCents"]!!.jsonPrimitive.content.toLong())
        assertEquals(0, report("/v1/reports/summary?from=2026-10-31&to=2026-10-31")["checkCount"]!!.jsonPrimitive.content.toInt())
        val hourly = report("/v1/reports/hourly?from=2026-11-01&to=2026-11-01")["rows"]!!.jsonArray
        assertEquals(3000, hourly[1].jsonObject["grossCents"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun businessDaysFollowTheVenueZoneNotTheServer() = testApplication {
        application { module(TestSupport.config) }
        startApplication() // Bootstrap re-asserts its own venue settings at boot
        transaction { Venues.update { it[timezone] = "America/Vancouver" } }
        // 23:30 in Vancouver on the 21st is already the 22nd in UTC and in the east
        ingest(key, event("check.closed", checkClosedPayload(1, 5000, 0, closedAt = "2026-07-22T06:30:00Z"),
            seq = 1, aggregateId = "1"))
        assertEquals(5000, report("/v1/reports/summary?from=2026-07-21&to=2026-07-21")["grossCents"]!!.jsonPrimitive.content.toLong())
        assertEquals(0, report("/v1/reports/summary?from=2026-07-22&to=2026-07-22")["checkCount"]!!.jsonPrimitive.content.toInt())
        val row = report("/v1/reports/journal?from=2026-07-21&to=2026-07-21")["rows"]!!.jsonArray[0].jsonObject
        assertEquals("2026-07-21T23:30:00.000-07:00", row["closedAt"]!!.jsonPrimitive.content)
    }
}
