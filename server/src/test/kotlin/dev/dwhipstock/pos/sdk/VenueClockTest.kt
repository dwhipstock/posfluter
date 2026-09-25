package dev.dwhipstock.pos.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

class VenueClockTest {
    @Test
    fun `venue day is independent of device timezone`() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
            val instant = Instant.parse("2026-09-23T23:30:00Z")
            assertEquals(ZoneId.of("America/New_York"), VenueClock.zone)
            assertEquals("2026-09-23T19:30", VenueClock.now(Clock.fixed(instant, ZoneId.of("UTC"))).toString())
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
