package dev.dwhipstock.pos.sdk

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Business time for the store. Every timestamp is stored as a UTC [Instant];
 * the venue's IANA zone (persisted in `venue_settings.timezone`, seeded from
 * `VENUE_TZ`) is applied only to DISPLAY, business-day grouping and reports.
 * Never use the device's (e.g. Android's) default timezone for either.
 */
object VenueClock {
    const val DEFAULT_ZONE = "America/New_York"

    /**
     * The zone a brand-new store starts in when `VENUE_TZ` is unset: its store
     * profile's ([StoreProfile.timeZone]). Set once at startup, before the
     * database opens; an existing store's settings row always wins.
     */
    @Volatile
    var fallbackZone: String = DEFAULT_ZONE

    /** The zone `VENUE_TZ` asks for (or the store's default) — seeds a new store's settings row. */
    fun configuredZone(): ZoneId =
        runCatching { ZoneId.of(System.getenv("VENUE_TZ")?.takeIf { it.isNotBlank() } ?: fallbackZone) }
            .getOrElse { runCatching { ZoneId.of(fallbackZone) }.getOrDefault(ZoneId.of(DEFAULT_ZONE)) }

    /** The venue zone in force; set from the settings row at startup ([use]). */
    @Volatile
    var zone: ZoneId = configuredZone()
        private set

    fun use(zoneId: ZoneId) {
        zone = zoneId
    }

    /** The current instant. Stored as-is; convert with [local]/[iso] only for people. */
    fun now(clock: Clock = Clock.systemUTC()): Instant = clock.instant()

    /** Venue wall time of [instant] — for receipts, day grouping and display. */
    fun local(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, zone)

    fun today(clock: Clock = Clock.systemUTC()): LocalDate = LocalDate.ofInstant(clock.instant(), zone)

    /** The instant a venue business day starts (its midnight, DST-aware). */
    fun startOfDay(date: LocalDate): Instant = date.atStartOfDay(zone).toInstant()

    /**
     * Wire/API form: ISO-8601 with the venue's offset at that instant, e.g.
     * `2026-07-11T18:02:11.000-04:00`. It is an unambiguous instant AND its
     * leading wall-clock part is venue-local, so clients can show it as-is.
     */
    fun iso(instant: Instant): String = OffsetDateTime.ofInstant(instant, zone).format(WIRE)

    val WIRE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

    /**
     * Interpret a zone-less wall time in [zoneId]. For the repeated hour when
     * clocks fall back, the EARLIER instant (the first occurrence, daylight
     * time) is chosen; a wall time inside the spring-forward gap moves forward
     * by the gap. Deterministic, and identical to the cloud migration's rule.
     */
    fun fromLocal(local: LocalDateTime, zoneId: ZoneId = zone): Instant =
        ZonedDateTime.ofLocal(local, zoneId, null).withEarlierOffsetAtOverlap().toInstant()
}
