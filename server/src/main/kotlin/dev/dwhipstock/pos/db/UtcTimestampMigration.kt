package dev.dwhipstock.pos.db

import dev.dwhipstock.pos.sdk.VenueClock
import org.jetbrains.exposed.sql.Transaction
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Migration 031 (code, not SQL — SQLite has no timezone database): rewrite every
 * business timestamp from a zone-less venue-local wall time
 * (`2026-07-11 18:02:11.123`) to its UTC instant (`2026-07-11T22:02:11.123Z`),
 * interpreting each old value in the venue's zone.
 *
 * The zone is `venue_settings.timezone` if already set, else `VENUE_TZ`, else
 * America/New_York — and it is written to the settings row here, so the store
 * keeps its zone from now on regardless of the environment.
 *
 * Ambiguous hour: when clocks fall back, 01:00–01:59 happens twice and a
 * zone-less value cannot say which. The rule is deterministic: the EARLIER
 * instant (first occurrence, daylight time) — [VenueClock.fromLocal]. A value in
 * the spring-forward gap (which the old clock never produced) moves forward by
 * the gap. The cloud migration (013) applies the same rule.
 *
 * The column list is frozen here on purpose: later migrations must not change
 * what this step touched.
 */
object UtcTimestampMigration {
    const val VERSION = 31
    const val NAME = "031_utc_timestamps (code)"

    val COLUMNS: Map<String, List<String>> = mapOf(
        "items" to listOf("deleted_at"),
        "item_variants" to listOf("deleted_at"),
        "tenders" to listOf("created_at"),
        "users" to listOf("deleted_at"),
        "sessions" to listOf("created_at", "revoked_at", "expires_at", "last_used_at"),
        "devices" to listOf("paired_at", "last_seen_at", "revoked_at"),
        "staff_totp" to listOf("activated_at", "created_at"),
        "trusted_devices" to listOf("created_at", "expires_at", "last_used_at"),
        "dining_tables" to listOf("deleted_at"),
        "checks" to listOf("opened_at", "closed_at"),
        "check_lines" to listOf("created_at"),
        "bill_groups" to listOf("created_at"),
        "refunds" to listOf("created_at"),
        "cash_movements" to listOf("created_at"),
        "shifts" to listOf("opened_at", "closed_at"),
        "sync_outbox" to listOf("created_at"),
    )

    fun run(tx: Transaction) {
        val zone = seedZone(tx)
        for ((table, columns) in COLUMNS) for (column in columns) convert(tx, table, column, zone)
    }

    /** Persist the store's zone (settings row wins; else VENUE_TZ; else default). */
    private fun seedZone(tx: Transaction): ZoneId {
        var stored: String? = null
        tx.exec("SELECT timezone FROM venue_settings WHERE id = 1") { rs -> if (rs.next()) stored = rs.getString(1) }
        val zone = stored?.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: VenueClock.configuredZone()
        tx.exec("UPDATE venue_settings SET timezone = '${zone.id.replace("'", "")}' WHERE id = 1")
        return zone
    }

    private fun convert(tx: Transaction, table: String, column: String, zone: ZoneId) {
        val rows = mutableListOf<Pair<Long, String>>()
        tx.exec("SELECT rowid, $column FROM $table WHERE $column IS NOT NULL") { rs ->
            while (rs.next()) rows += rs.getLong(1) to rs.getString(2)
        }
        val jdbc = tx.connection.connection as java.sql.Connection
        jdbc.prepareStatement("UPDATE $table SET $column = ? WHERE rowid = ?").use { statement ->
            for ((rowid, raw) in rows) {
                val converted = toUtcText(raw, zone) ?: continue
                statement.setString(1, converted)
                statement.setLong(2, rowid)
                statement.executeUpdate()
            }
        }
    }

    /** Old zone-less venue-local text → UTC text; already-converted/unknown values are left alone. */
    internal fun toUtcText(raw: String, zone: ZoneId): String? {
        val s = raw.trim()
        if (s.endsWith("Z") || OFFSET.containsMatchIn(s)) return null // already an instant
        val local = runCatching { LocalDateTime.parse(s.replace(' ', 'T')) }.getOrNull() ?: return null
        return UtcTimestampColumnType.UTC_TEXT.format(VenueClock.fromLocal(local, zone))
    }

    private val OFFSET = Regex("[+-]\\d{2}:\\d{2}$")
}
