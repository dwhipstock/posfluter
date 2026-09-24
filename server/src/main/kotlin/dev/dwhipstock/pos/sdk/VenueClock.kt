package dev.dwhipstock.pos.sdk

import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

/** Venue wall time for the store's timezone-less business timestamps.
 * Never use the Android device's timezone for checks, shifts, or outbox events.
 */
object VenueClock {
    val zone: ZoneId = ZoneId.of(System.getenv("VENUE_TZ")?.takeIf { it.isNotBlank() } ?: "America/New_York")

    fun now(clock: Clock = Clock.systemUTC()): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), zone)
}
