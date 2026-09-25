package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.Migrations
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * Cloud migration 013's conversion rule for old zone-less venue-local rows:
 * each is read in its venue's zone; the repeated fall-back hour takes the FIRST
 * occurrence (daylight time), like the store's own migration; a wall time in
 * the spring-forward gap moves forward by the gap.
 */
class TimestampMigrationTest {

    @Before
    fun setUp() = TestSupport.reset()

    private fun convert(local: String, zone: String): String = transaction {
        val sql = File(TestSupport.config.migrationsDir, "013_timestamptz.sql").readText()
        Migrations.statements(sql).filter { it.startsWith("CREATE FUNCTION pg_temp.") }.forEach { exec(it.replaceFirst("CREATE FUNCTION", "CREATE OR REPLACE FUNCTION")) }
        exec("SET LOCAL TIME ZONE 'UTC'")
        var out = ""
        exec("SELECT to_char(pg_temp.venue_instant(timestamp '$local', '$zone'), 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"')") { rs ->
            rs.next(); out = rs.getString(1)
        }
        out
    }

    @Test
    fun ordinaryTimesAreReadInTheVenueZone() {
        assertEquals("2026-07-11T00:00:00Z", convert("2026-07-10 20:00:00", "America/New_York"))
        assertEquals("2026-01-15T17:00:00Z", convert("2026-01-15 12:00:00", "America/New_York"))
        assertEquals("2026-07-11T03:00:00Z", convert("2026-07-10 20:00:00", "America/Vancouver"))
    }

    @Test
    fun theRepeatedFallBackHourTakesTheFirstOccurrence() {
        // 2026-11-01 01:00-01:59 happens twice in New York: EDT (-4) first, then EST (-5)
        assertEquals("2026-11-01T05:30:00Z", convert("2026-11-01 01:30:00", "America/New_York"))
        assertEquals("2026-11-01T05:59:00Z", convert("2026-11-01 01:59:00", "America/New_York"))
        // either side of the repeated hour is unambiguous
        assertEquals("2026-11-01T04:59:00Z", convert("2026-11-01 00:59:00", "America/New_York"))
        assertEquals("2026-11-01T07:00:00Z", convert("2026-11-01 02:00:00", "America/New_York"))
    }

    @Test
    fun aTimeInTheSpringGapMovesForward() {
        // 2026-03-08 02:30 never happens in New York (02:00 → 03:00)
        assertEquals("2026-03-08T07:30:00Z", convert("2026-03-08 02:30:00", "America/New_York"))
    }
}
