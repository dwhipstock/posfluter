package dev.dwhipstock.pos.sdk

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class VenueClockTest {
    private val ny = ZoneId.of("America/New_York")

    @Test
    fun `venue day and wall time are independent of the device timezone`() {
        val previous = TimeZone.getDefault()
        val previousZone = VenueClock.zone
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT-12")) // device clock a day ahead
            VenueClock.use(ny)
            val instant = Instant.parse("2026-09-23T23:30:00Z")
            val clock = Clock.fixed(instant, ZoneId.of("UTC"))
            // stored value is the instant itself
            assertEquals(instant, VenueClock.now(clock))
            assertEquals("2026-09-23T19:30", VenueClock.local(instant).toString())
            assertEquals(LocalDate.of(2026, 9, 23), VenueClock.today(clock))
            assertEquals("2026-09-23T19:30:00.000-04:00", VenueClock.iso(instant))
        } finally {
            TimeZone.setDefault(previous)
            VenueClock.use(previousZone)
        }
    }

    @Test
    fun `the repeated fall-back hour keeps its order and shows its offset`() {
        val previousZone = VenueClock.zone
        try {
            VenueClock.use(ny)
            // 2026-11-01: 01:30 EDT (05:30Z), then clocks fall back, then 01:30 EST (06:30Z)
            val first = Instant.parse("2026-11-01T05:30:00Z")
            val second = Instant.parse("2026-11-01T06:30:00Z")
            assertEquals(VenueClock.local(first), VenueClock.local(second)) // same wall clock…
            assertEquals("2026-11-01T01:30:00.000-04:00", VenueClock.iso(first)) // …different offsets
            assertEquals("2026-11-01T01:30:00.000-05:00", VenueClock.iso(second))
            assertEquals(listOf(first, second), listOf(second, first).sorted())
            // the business day spans 25 hours
            val start = VenueClock.startOfDay(LocalDate.of(2026, 11, 1))
            val end = VenueClock.startOfDay(LocalDate.of(2026, 11, 2))
            assertEquals(25, java.time.Duration.between(start, end).toHours())
        } finally {
            VenueClock.use(previousZone)
        }
    }

    @Test
    fun `a zone-less wall time in the repeated hour resolves to its first occurrence`() {
        val local = LocalDateTime.of(2026, 11, 1, 1, 30)
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), VenueClock.fromLocal(local, ny))
        // a spring-forward gap time moves forward by the gap
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"),
            VenueClock.fromLocal(LocalDateTime.of(2026, 3, 8, 2, 30), ny))
    }
}
