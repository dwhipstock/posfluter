package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.Aamva
import dev.dwhipstock.pos.base.AgeMath
import dev.dwhipstock.pos.base.AgeVerdict
import dev.dwhipstock.pos.base.IdDates
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AAMVA licence barcodes and age math. Every sample below is made up — the
 * fields, names and numbers of no real card — in the shapes a keyboard-wedge
 * 2D scanner types them.
 */
class AamvaAgeTest {

    private val lf = "\n"
    private val rs = "\u001E"
    private val cr = "\r"

    /** A US licence (MMDDCCYY), with the real control characters. */
    private fun usCard(dob: String, expires: String) =
        "@$lf$rs${cr}ANSI 636014090002DL00410278ZC03190024DLDAQD1234562$lf" +
            "DCSSAMPLE$lf" + "DDEN$lf" + "DACALEX$lf" + "DDFN$lf" + "DADQUINN$lf" + "DDGN$lf" +
            "DCAC$lf" + "DCBNONE$lf" + "DCDNONE$lf" + "DBD01012024$lf" +
            "DBB$dob$lf" + "DBA$expires$lf" + "DBC2$lf" + "DAU069 IN$lf" + "DAYBRO$lf" +
            "DAG123 MAIN ST$lf" + "DAILOS ANGELES$lf" + "DAJCA$lf" + "DAK900260000  $lf" +
            "DCF00000000000000000000$lf" + "DCGUSA$lf" + "DDAF$lf" + "DDB08292017$cr" +
            "ZCZCAY$lf" + "ZCBCORRECTIVE LENSES$cr"

    /** A Canadian card: dates are CCYYMMDD. */
    private fun canadianCard(dob: String, expires: String) =
        "@$lf$rs${cr}ANSI 636012080102DL00410266ZO03070012DLDCAG$lf" +
            "DCBNONE$lf" + "DCDNONE$lf" + "DBA$expires$lf" + "DCSEXEMPLE$lf" + "DACCAMILLE$lf" +
            "DBD20210315$lf" + "DBB$dob$lf" + "DBC2$lf" + "DAQS1234-56789-01234$lf" +
            "DCGCAN$lf" + "DAJQC$lf" + "DAK H2X 1Y4  $cr"

    @Test
    fun readsDateOfBirthAndExpiryFromAUsLicence() {
        val d = Aamva.parse(usCard("07041998", "07042030"))!!
        assertEquals(LocalDate.of(1998, 7, 4), d.dateOfBirth)
        assertEquals(LocalDate.of(2030, 7, 4), d.expires)
    }

    @Test
    fun readsCanadianYearFirstDates() {
        val d = Aamva.parse(canadianCard("20010215", "20290215"))!!
        assertEquals(LocalDate.of(2001, 2, 15), d.dateOfBirth)
        assertEquals(LocalDate.of(2029, 2, 15), d.expires)
    }

    @Test
    fun survivesAScannerThatTurnsSeparatorsIntoEnterOrDropsThem() {
        // separators typed as Enter (\r\n), RS lost
        val enter = usCard("12312000", "12312029").replace(rs, "").replace(lf, "\r\n")
        assertEquals(LocalDate.of(2000, 12, 31), Aamva.parse(enter)!!.dateOfBirth)
        // every separator dropped: one long line
        val flat = usCard("12312000", "12312029").replace(lf, "").replace(cr, "").replace(rs, "")
        val d = Aamva.parse(flat)!!
        assertEquals(LocalDate.of(2000, 12, 31), d.dateOfBirth)
        assertEquals(LocalDate.of(2029, 12, 31), d.expires)
    }

    @Test
    fun anythingElseIsUnreadable() {
        assertNull(Aamva.parse(null))
        assertNull(Aamva.parse(""))
        assertNull(Aamva.parse("487230001017")) // a product barcode, not an ID
        assertNull(Aamva.parse("@\nANSI 636014090002DL00410278DLDAQD1234\nDCSSAMPLE\n")) // no date of birth
        assertNull(Aamva.parse(usCard("13452000", "01012030"))) // month 13: not a date
    }

    @Test
    fun ageCountsTheBirthdayItself() {
        val today = LocalDate.of(2026, 9, 25)
        assertEquals(21, AgeMath.ageOn(LocalDate.of(2005, 9, 25), today)) // 21st birthday today
        assertEquals(20, AgeMath.ageOn(LocalDate.of(2005, 9, 26), today)) // tomorrow
        assertEquals(21, AgeMath.ageOn(LocalDate.of(2005, 9, 24), today))
        assertEquals(-1, AgeMath.ageOn(LocalDate.of(2027, 1, 1), today)) // not born yet
    }

    @Test
    fun aLeapDayBirthdayFallsOnTheFirstOfMarchInOtherYears() {
        val born = LocalDate.of(2004, 2, 29)
        assertEquals(20, AgeMath.ageOn(born, LocalDate.of(2025, 2, 28)))
        assertEquals(21, AgeMath.ageOn(born, LocalDate.of(2025, 3, 1)))
        assertEquals(24, AgeMath.ageOn(born, LocalDate.of(2028, 2, 29))) // a leap year: the day itself
    }

    @Test
    fun theLegalAgeDecides21Or18() {
        val today = LocalDate.of(2026, 9, 25)
        val nineteen = IdDates(LocalDate.of(2007, 6, 1), expires = LocalDate.of(2030, 6, 1))
        val us = AgeVerdict.of(nineteen, legalAge = 21, today = today)
        assertFalse(us.passed)
        assertEquals("under_age", us.reason)
        assertEquals(19, us.ageYears)
        val qc = AgeVerdict.of(nineteen, legalAge = 18, today = today)
        assertTrue(qc.passed)
        assertNull(qc.reason)
    }

    @Test
    fun anExpiredIdFailsEvenWhenOldEnough() {
        val today = LocalDate.of(2026, 9, 25)
        val expired = AgeVerdict.of(IdDates(LocalDate.of(1980, 1, 1), LocalDate.of(2026, 9, 24)), 21, today)
        assertEquals("expired", expired.reason)
        assertFalse(expired.passed)
        // valid through its expiry day
        assertTrue(AgeVerdict.of(IdDates(LocalDate.of(1980, 1, 1), today), 21, today).passed)
        // typed date (no expiry) needs the cashier to confirm they saw the ID
        assertEquals("not_confirmed",
            AgeVerdict.of(IdDates(LocalDate.of(1980, 1, 1), null), 21, today, cashierSawId = false).reason)
        assertEquals("unreadable", AgeVerdict.of(null, 21, today).reason)
    }
}
