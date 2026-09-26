package dev.dwhipstock.pos.base

import dev.dwhipstock.pos.db.utcTimestamp
import org.jetbrains.exposed.dao.id.IntIdTable
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeParseException

/**
 * The outcome of one ID check on a sale (038) — and nothing else. The store
 * never keeps the customer's name, date of birth, licence number or address:
 * the scanned text and the typed date are used to compute an age, then
 * dropped. Synced up as the same outcome only.
 */
object AgeChecks : IntIdTable("age_checks") {
    val checkId = integer("check_id")
    val method = varchar("method", 8) // SCAN | MANUAL
    val passed = bool("passed")
    val ageYears = integer("age_years").nullable()
    val legalAge = integer("legal_age")
    val reason = varchar("reason", 20).nullable() // under_age | expired | unreadable | not_confirmed
    val checkedBy = varchar("checked_by", 64)
    val checkedAt = utcTimestamp("checked_at")
}

/**
 * What an ID's barcode says, as far as an age check needs: the date of birth
 * and the expiry date. Everything else on the card is ignored.
 */
data class IdDates(val dateOfBirth: LocalDate, val expires: LocalDate?)

/**
 * US and Canadian driver's licences and ID cards carry an AAMVA PDF417
 * barcode; a 2D HID scanner "types" its text. The fields that matter:
 * `DBB` date of birth and `DBA` expiry, each 8 digits — MMDDCCYY on US cards,
 * CCYYMMDD on Canadian ones (`DCG` = USA / CAN names the country).
 *
 * Tolerant on purpose, because a keyboard-wedge scanner mangles the control
 * characters: the record separators may arrive as newlines, carriage returns,
 * RS or nothing, and the first data element sits glued to the subfile header
 * (`…DLDAQ…`). Returns null when the text is not an AAMVA barcode or has no
 * readable date of birth.
 */
object Aamva {
    private val SEPARATORS = Regex("[\\n\\r\\u001E\\u001D]+")
    private val HEADER = Regex("(ANSI |AAMVA)\\s?\\d{6}")
    private val DATE = Regex("\\d{8}")

    fun looksLikeAamva(raw: String): Boolean = HEADER.containsMatchIn(raw.take(64)) || raw.trimStart().startsWith("@")

    fun parse(raw: String?): IdDates? {
        val text = raw ?: return null
        if (!looksLikeAamva(text)) return null
        val fields = elements(text)
        val country = fields["DCG"]?.trim()?.uppercase()
        val dob = fields["DBB"]?.let { date(it, country) } ?: return null
        val expires = fields["DBA"]?.let { date(it, country) }
        return IdDates(dob, expires)
    }

    /** Element id (3 letters) → its value, first occurrence wins. */
    internal fun elements(text: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (piece in text.split(SEPARATORS)) {
            // the header line carries the first element after "DL"/"ID" + offsets
            val line = piece.trim()
            val start = Regex("(?:DL|ID)(D[A-Z]{2})").find(line)?.takeIf { HEADER.containsMatchIn(line) }
            val element = if (start != null) line.substring(start.groups[1]!!.range.first) else line
            if (element.length >= 3 && element[0] == 'D' && element.substring(0, 3).all { it.isUpperCase() }) {
                out.putIfAbsent(element.substring(0, 3), element.substring(3))
            }
        }
        // a scanner that dropped every separator: find the two dates anywhere
        if ("DBB" !in out) Regex("DBB(\\d{8})").find(text)?.let { out["DBB"] = it.groupValues[1] }
        if ("DBA" !in out) Regex("DBA(\\d{8})").find(text)?.let { out["DBA"] = it.groupValues[1] }
        if ("DCG" !in out) Regex("DCG([A-Z]{3})").find(text)?.let { out["DCG"] = it.groupValues[1] }
        return out
    }

    /** MMDDCCYY (USA) or CCYYMMDD (CAN); unknown country: whichever reads as a real date. */
    internal fun date(raw: String, country: String?): LocalDate? {
        val d = DATE.find(raw.trim())?.value ?: return null
        fun mmddyyyy() = of(d.substring(4, 8), d.substring(0, 2), d.substring(2, 4))
        fun yyyymmdd() = of(d.substring(0, 4), d.substring(4, 6), d.substring(6, 8))
        return when (country) {
            "USA" -> mmddyyyy()
            "CAN" -> yyyymmdd()
            else -> if (d.substring(0, 2) in listOf("19", "20") && yyyymmdd() != null) yyyymmdd() else mmddyyyy()
        }
    }

    private fun of(y: String, m: String, d: String): LocalDate? = try {
        LocalDate.of(y.toInt(), m.toInt(), d.toInt())
    } catch (_: Exception) {
        null
    }
}

/** Whole years, the legal way: the birthday itself counts; a 29 February birthday falls on 1 March in other years. */
object AgeMath {
    fun ageOn(dateOfBirth: LocalDate, today: LocalDate): Int =
        if (dateOfBirth.isAfter(today)) -1 else Period.between(dateOfBirth, today).years

    fun parseIsoDate(raw: String?): LocalDate? = try {
        raw?.trim()?.let(LocalDate::parse)
    } catch (_: DateTimeParseException) {
        null
    }
}

/** The verdict of one ID check. [reason] is null when [passed]. */
data class AgeVerdict(val passed: Boolean, val ageYears: Int?, val reason: String?) {
    companion object {
        /**
         * [dates] from a scan (expiry checked) or a typed date of birth
         * (no expiry; the cashier confirmed they saw the ID). [today] is the
         * store's business day in its own zone.
         */
        fun of(dates: IdDates?, legalAge: Int, today: LocalDate, cashierSawId: Boolean = true): AgeVerdict {
            if (dates == null) return AgeVerdict(false, null, "unreadable")
            val age = AgeMath.ageOn(dates.dateOfBirth, today)
            if (age < 0 || age > 130) return AgeVerdict(false, null, "unreadable")
            if (!cashierSawId) return AgeVerdict(false, age, "not_confirmed")
            if (dates.expires != null && dates.expires.isBefore(today)) return AgeVerdict(false, age, "expired")
            if (age < legalAge) return AgeVerdict(false, age, "under_age")
            return AgeVerdict(true, age, null)
        }
    }
}
