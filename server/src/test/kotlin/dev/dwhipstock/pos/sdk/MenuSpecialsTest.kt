package dev.dwhipstock.pos.sdk

import dev.dwhipstock.pos.sdk.MenuSpecials.Special
import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure rules of menu specials: business days, windows, prices, canonical JSON, names. */
class MenuSpecialsTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        MenuSpecials.moment(VenueClock.fromLocal(LocalDateTime.of(y, mo, d, h, mi), zone), zone)

    private val happy = Special(listOf("mon", "tue", "wed", "thu", "fri"), "16:00", "18:00", mapOf("lager:pint" to 500L))
    private val tuesday = Special(listOf("tue"), prices = mapOf("burger:regular" to 995L))

    @Test
    fun `a business day runs 4 am to 4 am in the venue's zone`() {
        // Saturday 1 am is still Friday night; Saturday 4 am is Saturday
        assertEquals("fri", at(2026, 10, 3, 1).day)
        assertEquals("fri", at(2026, 10, 3, 3, 59).day)
        assertEquals("sat", at(2026, 10, 3, 4).day)
        assertEquals(25 * 60, at(2026, 10, 3, 1).minute)
        // the zone is the venue's, not the machine's: 02:30 UTC Saturday is 22:30 Friday in Raleigh
        val utc = java.time.Instant.parse("2026-10-03T02:30:00Z")
        assertEquals("fri", MenuSpecials.moment(utc, zone).day)
        assertEquals("sat", MenuSpecials.moment(utc, ZoneId.of("Europe/Berlin")).day)
    }

    @Test
    fun `day availability follows the business day`() {
        val primeRib = MenuSpecials.Schedule(availableDays = listOf("fri", "sat"))
        assertTrue(MenuSpecials.available(primeRib, at(2026, 10, 2, 19)))      // Friday evening
        assertTrue(MenuSpecials.available(primeRib, at(2026, 10, 4, 1)))       // Sunday 1 am = Saturday night
        assertFalse(MenuSpecials.available(primeRib, at(2026, 10, 4, 4)))      // Sunday 4 am
        assertFalse(MenuSpecials.available(primeRib, at(2026, 10, 1, 23)))     // Thursday
        assertTrue(MenuSpecials.available(MenuSpecials.Schedule(), at(2026, 10, 1, 23)))
    }

    @Test
    fun `a happy hour window is half-open on the wall clock`() {
        // Monday 2026-10-05
        assertFalse(MenuSpecials.inForce(happy, at(2026, 10, 5, 15, 59)))
        assertTrue(MenuSpecials.inForce(happy, at(2026, 10, 5, 16, 0)))
        assertTrue(MenuSpecials.inForce(happy, at(2026, 10, 5, 17, 59)))
        assertFalse(MenuSpecials.inForce(happy, at(2026, 10, 5, 18, 0)))
        assertFalse(MenuSpecials.inForce(happy, at(2026, 10, 5, 18, 1))) // 6:01 pm: the menu price
        assertFalse(MenuSpecials.inForce(happy, at(2026, 10, 3, 17)))    // Saturday: not a weekday
        val s = MenuSpecials.Schedule(specials = listOf(happy))
        assertEquals(500L to happy, MenuSpecials.priceAt(s, "lager:pint", 750, at(2026, 10, 5, 17, 59)))
        assertEquals(750L to null, MenuSpecials.priceAt(s, "lager:pint", 750, at(2026, 10, 5, 18, 1)))
        // another size of the same item has no special
        assertEquals(2025L to null, MenuSpecials.priceAt(s, "lager:pitcher", 2025, at(2026, 10, 5, 17)))
    }

    @Test
    fun `a late window runs past midnight on the night it started`() {
        val late = Special(listOf("fri"), "22:00", "02:00", mapOf("x:r" to 100L))
        assertTrue(MenuSpecials.inForce(late, at(2026, 10, 2, 23)))
        assertTrue(MenuSpecials.inForce(late, at(2026, 10, 3, 1, 59)))   // Saturday 1:59 am = Friday night
        assertFalse(MenuSpecials.inForce(late, at(2026, 10, 3, 2, 0)))
        assertFalse(MenuSpecials.inForce(late, at(2026, 10, 3, 23)))     // Saturday night
        val afterMidnight = Special(listOf("fri"), "01:00", "03:00", mapOf("x:r" to 100L))
        assertTrue(MenuSpecials.inForce(afterMidnight, at(2026, 10, 3, 1, 30)))
        assertFalse(MenuSpecials.inForce(afterMidnight, at(2026, 10, 2, 1, 30))) // Friday 1:30 am is Thursday night
    }

    @Test
    fun `the cheapest special wins and a dearer one never raises the price`() {
        val s = MenuSpecials.Schedule(specials = listOf(
            Special(listOf("tue"), prices = mapOf("b:r" to 1100L)), Special(listOf("tue"), prices = mapOf("b:r" to 995L)),
        ))
        assertEquals(995L, MenuSpecials.priceAt(s, "b:r", 1245, at(2026, 10, 6, 12)).first)
        assertEquals(1245L, MenuSpecials.priceAt(s, "b:r", 1245, at(2026, 10, 7, 12)).first)
        val dearer = MenuSpecials.Schedule(specials = listOf(Special(listOf("tue"), prices = mapOf("b:r" to 1500L))))
        assertEquals(1245L to null, MenuSpecials.priceAt(dearer, "b:r", 1245, at(2026, 10, 6, 12)))
    }

    @Test
    fun `the canonical form is the contract's text`() {
        val messy = Special(listOf("FRI", "mon", "wed", "tue", "thu", "mon"), "16:00", "18:00",
            mapOf("lantern-lager:pint" to 500L), label = "  Happy   hour ")
        val clean = MenuSpecials.normalize(messy)
        assertEquals(
            """[{"days":["mon","tue","wed","thu","fri"],"from":"16:00","to":"18:00","label":"Happy hour","prices":{"lantern-lager:pint":500}}]""",
            MenuSpecials.specialsJson(listOf(clean)).toString())
        assertEquals("09:05", MenuSpecials.normalizeTime("9:05"))
        assertEquals("""["fri","sat"]""", MenuSpecials.daysJson(MenuSpecials.normalizeDays(listOf("sat", "fri"))).toString())
        assertEquals("null", MenuSpecials.specialsJson(emptyList()).toString())
        // and back
        assertEquals(listOf(clean), MenuSpecials.specialsOf(MenuSpecials.specialsJson(listOf(clean))))
    }

    @Test
    fun `bad specials are refused with a reason`() {
        assertFailsWith<IllegalArgumentException> { MenuSpecials.normalize(tuesday.copy(days = emptyList())) }
        assertFailsWith<IllegalArgumentException> { MenuSpecials.normalize(tuesday.copy(days = listOf("funday"))) }
        assertFailsWith<IllegalArgumentException> { MenuSpecials.normalize(tuesday.copy(from = "16:00")) }
        assertFailsWith<IllegalArgumentException> { MenuSpecials.normalize(tuesday.copy(from = "16:00", to = "16:00")) }
        assertFailsWith<IllegalArgumentException> { MenuSpecials.normalize(tuesday.copy(from = "25:00", to = "26:00")) }
        assertFailsWith<IllegalArgumentException> { MenuSpecials.normalize(tuesday.copy(prices = emptyMap())) }
        assertFailsWith<IllegalArgumentException> { MenuSpecials.normalize(tuesday.copy(prices = mapOf("burger:regular" to -1L))) }
        assertFailsWith<IllegalArgumentException> { MenuSpecials.normalize(tuesday, variantIds = setOf("burger:large")) }
        // a sync never wedges on a bad entry: it is dropped
        val bad = kotlinx.serialization.json.Json.parseToJsonElement("""[{"days":["xyz"],"prices":{"a":1}},{"days":["tue"],"prices":{"a":1}}]""")
        assertEquals(1, MenuSpecials.specialsOf(bad).size)
        assertNull(MenuSpecials.normalizeTime(" "))
    }

    @Test
    fun `a special's name is the reader's language`() {
        val tag = MenuSpecials.Tag(listOf("tue"))
        val hh = MenuSpecials.Tag(listOf("mon", "fri"), "16:00", "18:00")
        assertEquals("Tuesday special", MenuSpecials.label(tag, LocaleCode.EN))
        assertEquals("Spécial du mardi", MenuSpecials.label(tag, LocaleCode.FR))
        assertEquals("Especial del martes", MenuSpecials.label(tag, LocaleCode.ES))
        assertEquals("Dienstagsangebot", MenuSpecials.label(tag, LocaleCode.DE))
        assertEquals("Dinsdag-spesiaal", MenuSpecials.label(tag, LocaleCode.AF))
        assertEquals("Happy hour", MenuSpecials.label(hh, LocaleCode.EN))
        assertEquals("Hora feliz", MenuSpecials.label(hh, LocaleCode.ES))
        assertEquals("Taco night", MenuSpecials.label(tag.copy(label = "Taco night"), LocaleCode.FR))
        assertEquals("Friday & Saturday", MenuSpecials.daysText(listOf("fri", "sat"), LocaleCode.EN))
        assertEquals("vendredi et samedi", MenuSpecials.daysText(listOf("fri", "sat"), LocaleCode.FR))
        assertEquals("Freitag und Samstag", MenuSpecials.daysText(listOf("fri", "sat"), LocaleCode.DE))
    }
}
