package dev.dwhipstock.pos.sdk.i18n

import java.time.LocalDateTime

/**
 * Thai dates: the Buddhist Era year (CE + 543), day first, 24-hour clock —
 * "12/10/2569 14:05" — and the Thai month names for longer forms. Used only
 * when the language is Thai; every other language keeps its own format.
 */
object ThaiDates {
    const val BE_OFFSET = 543

    val MONTHS = listOf(
        "มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน", "พฤษภาคม", "มิถุนายน",
        "กรกฎาคม", "สิงหาคม", "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม",
    )
    val MONTHS_SHORT = listOf(
        "ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.",
        "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค.",
    )

    fun buddhistYear(ceYear: Int): Int = ceYear + BE_OFFSET

    /** "12/10/2569 14:05". */
    fun receipt(dt: LocalDateTime): String =
        "%02d/%02d/%04d %02d:%02d".format(dt.dayOfMonth, dt.monthValue, buddhistYear(dt.year), dt.hour, dt.minute)

    /** "12 ต.ค. 2569". */
    fun short(dt: LocalDateTime): String = "${dt.dayOfMonth} ${MONTHS_SHORT[dt.monthValue - 1]} ${buddhistYear(dt.year)}"

    /** "12 ตุลาคม 2569". */
    fun long(dt: LocalDateTime): String = "${dt.dayOfMonth} ${MONTHS[dt.monthValue - 1]} ${buddhistYear(dt.year)}"
}
