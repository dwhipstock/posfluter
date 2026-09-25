package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.Venues
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Time rules for the cloud (CONTRACT.md v2): every stored timestamp is an
 * instant (timestamptz, held as a UTC [OffsetDateTime]); a venue's IANA zone
 * (venues.timezone) is applied only to display, business-day grouping and
 * reports. API output carries the venue's offset so its leading wall-clock part
 * is venue-local and the value is still an unambiguous instant.
 */
object CloudTime {
    const val DEFAULT_ZONE = "America/New_York"

    val WIRE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

    fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

    /** `2026-07-11T18:02:11.000-04:00` — the instant, shown in [zone]. */
    fun iso(t: OffsetDateTime, zone: ZoneId): String = t.atZoneSameInstant(zone).format(WIRE)

    /** The instant a business day starts in [zone] (DST-aware midnight). */
    fun startOfDay(date: LocalDate, zone: ZoneId): OffsetDateTime =
        date.atStartOfDay(zone).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC)

    fun localDate(t: OffsetDateTime, zone: ZoneId): LocalDate = t.atZoneSameInstant(zone).toLocalDate()

    fun localHour(t: OffsetDateTime, zone: ZoneId): Int = t.atZoneSameInstant(zone).hour

    /**
     * A store-sent timestamp. Current stores send an offset (or `Z`); older
     * stores sent a zone-less venue-local wall time, which is read in [zone] —
     * the repeated fall-back hour resolves to its first occurrence (the same
     * rule as migration 013). Null when unparseable.
     */
    fun parse(raw: String?, zone: ZoneId): OffsetDateTime? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { return OffsetDateTime.parse(s).withOffsetSameInstant(ZoneOffset.UTC) }
        val local = runCatching { LocalDateTime.parse(s.replace(' ', 'T')) }.getOrNull() ?: return null
        return ZonedDateTime.ofLocal(local, zone, null).withEarlierOffsetAtOverlap()
            .toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC)
    }

    fun zone(id: String?): ZoneId =
        runCatching { ZoneId.of(id ?: DEFAULT_ZONE) }.getOrDefault(ZoneId.of(DEFAULT_ZONE))

    /** The venue's zone (call inside a transaction). */
    fun venueZone(tenantId: String, venueId: String): ZoneId = zone(
        Venues.selectAll().where { (Venues.tenantId eq tenantId) and (Venues.id eq venueId) }
            .firstOrNull()?.get(Venues.timezone)
    )
}
