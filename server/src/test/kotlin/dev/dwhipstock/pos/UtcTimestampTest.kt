package dev.dwhipstock.pos

import dev.dwhipstock.pos.db.Migrations
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.db.initDatabase
import dev.dwhipstock.pos.restaurant.Checks
import dev.dwhipstock.pos.restaurant.Shifts
import dev.dwhipstock.pos.restaurant.ShiftService
import dev.dwhipstock.pos.sdk.VenueClock
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Timestamps are UTC instants + the store's zone (venue_settings.timezone).
 * Covers the one-time conversion of old zone-less rows (migration 031) and the
 * fall-back hour, and holds whatever the JVM's own timezone is.
 */
class UtcTimestampTest {

    private fun tempDb() = Files.createTempDirectory("pos-utc-test").resolve("pos.db").toString()

    private fun text(db: Database, sql: String): String? = transaction(db) {
        var v: String? = null
        exec(sql) { rs -> if (rs.next()) v = rs.getString(1) }
        v
    }

    @Test
    fun `old zone-less venue-local rows convert to UTC and the zone is persisted`() {
        val previous = TimeZone.getDefault()
        try {
            // the conversion must not care about the machine it runs on
            TimeZone.setDefault(TimeZone.getTimeZone("America/Vancouver"))
            val db = initDatabase(tempDb())
            transaction(db) {
                // rows exactly as the pre-031 store wrote them (venue-local, no zone)
                exec("INSERT INTO shifts (status, opened_at, opened_by, opening_float_cents, closed_at) " +
                    "VALUES ('CLOSED', '2026-07-11 18:02:11.123', 'manager', 0, '2026-11-01 01:30:00.000')")
                exec("INSERT INTO sync_outbox (event_id, event_type, aggregate_type, aggregate_id, payload, created_at) " +
                    "VALUES ('e-old', 'check.closed', 'check', '1', '{}', '2026-01-15 09:00:00.000')")
                // pretend 031 has not run yet
                exec("DELETE FROM schema_migrations WHERE version = 31")
                exec("UPDATE venue_settings SET timezone = '' WHERE id = 1")
            }
            Migrations.run(db)

            // July: EDT (-4); January: EST (-5); the repeated 01:30 → first occurrence (EDT)
            assertEquals("2026-07-11T22:02:11.123Z", text(db, "SELECT opened_at FROM shifts WHERE opened_by = 'manager'"))
            assertEquals("2026-11-01T05:30:00.000Z", text(db, "SELECT closed_at FROM shifts WHERE opened_by = 'manager'"))
            assertEquals("2026-01-15T14:00:00.000Z", text(db, "SELECT created_at FROM sync_outbox WHERE event_id = 'e-old'"))
            assertEquals("America/New_York", text(db, "SELECT timezone FROM venue_settings WHERE id = 1"))

            // and Exposed reads them back as instants
            val opened = transaction(db) { Shifts.selectAll().first()[Shifts.openedAt] }
            assertEquals(Instant.parse("2026-07-11T22:02:11.123Z"), opened)

            // re-running is a no-op (already converted values are left alone)
            transaction(db) { exec("DELETE FROM schema_migrations WHERE version = 31") }
            Migrations.run(db)
            assertEquals("2026-07-11T22:02:11.123Z", text(db, "SELECT opened_at FROM shifts WHERE opened_by = 'manager'"))
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun `checks closed in the repeated fall-back hour order correctly and land on one business day`() {
        initDatabase(tempDb())
        VenueClock.use(ZoneId.of("America/New_York"))
        // 01:40 EDT, then the clocks fall back, then 01:10 EST — later, despite the earlier wall clock
        val firstPass = Instant.parse("2026-11-01T05:40:00Z")
        val secondPass = Instant.parse("2026-11-01T06:10:00Z")
        transaction {
            for ((id, closedAt) in listOf("t1" to secondPass, "t2" to firstPass)) {
                Checks.insertAndGetId {
                    it[tableId] = id
                    it[status] = "CLOSED"
                    it[openedBy] = "manager"
                    it[openedAt] = closedAt.minusSeconds(600)
                    it[this.closedAt] = closedAt
                    it[lockedGrandTotalCents] = 1000
                    it[lockedTaxIncludedCents] = 0
                    it[lockedFeesJson] = "[]"
                }
            }
        }
        val order = transaction {
            Checks.selectAll().orderBy(Checks.closedAt, SortOrder.ASC).map { it[Checks.closedAt] }
        }
        assertEquals(listOf(firstPass, secondPass), order)
        assertEquals("01:40", VenueClock.local(firstPass).toLocalTime().toString())
        assertEquals("01:10", VenueClock.local(secondPass).toLocalTime().toString())

        val report = ShiftService(testConfig()).rangeReport(
            LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1))
        assertEquals(2, report.transactionCount)
        assertEquals(0, ShiftService(testConfig()).rangeReport(
            LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 31)).transactionCount)

        // new outbox rows carry the offset, so the cloud gets an unambiguous instant
        transaction { dev.dwhipstock.pos.sdk.Outbox.write("x", "x", "1", kotlinx.serialization.json.buildJsonObject {}) }
        val stored = transaction {
            SyncOutbox.selectAll().orderBy(SyncOutbox.id, SortOrder.DESC).first()[SyncOutbox.createdAt]
        }
        assert(VenueClock.iso(stored).matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}[-+]\d{2}:\d{2}""")))
    }
}

private fun testConfig() = dev.dwhipstock.pos.customers.copperlantern.CopperLanternConfig(
    settings = dev.dwhipstock.pos.base.SettingsRepository(),
    printer = dev.dwhipstock.pos.sdk.PrinterAdapter.VirtualPrinter(
        Files.createTempDirectory("r").toString(), Files.createTempDirectory("b").toString()),
    publicBaseUrl = "http://test",
)
