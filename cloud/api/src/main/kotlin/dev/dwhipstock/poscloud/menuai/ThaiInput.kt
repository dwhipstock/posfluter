package dev.dwhipstock.poscloud.menuai

import java.math.BigDecimal

/**
 * Thai as an AI INPUT language (typed and spoken), for every assistant: menu
 * chat, the floor assistant, room from photo. The cloud keeps an identical
 * copy (cloud/api menuai/ThaiInput.kt; ThaiInputParityTest).
 *
 * - [asciiDigits]: Thai digits (๐–๙) in the manager's words become 0–9 before
 *   the guard and the model see them, so "โต๊ะ ๓" is table 3 and "๙๐ บาท" is 90;
 * - [amounts]: every baht amount in a request ("90 บาท", "฿90", "๙๐ บาท",
 *   "1,250.50 บาท") read to minor units, handed to the model as a fact next to
 *   the request so a price is never off by the 100 satang;
 * - [prompt] / [voicePrompt]: the extra rules a store that speaks Thai gets.
 *   Stores without Thai never get them, so their prompts are unchanged.
 */
object ThaiInput {
    private const val ZERO = '๐' // ๐

    /** "โต๊ะ ๑๒" → "โต๊ะ 12"; everything else as it is. */
    fun asciiDigits(s: String): String =
        if (s.none { it in ZERO..'๙' }) s else s.map { if (it in ZERO..'๙') '0' + (it - ZERO) else it }.joinToString("")

    /** Any Thai letter or digit in [s]. */
    fun hasThai(s: String): Boolean = s.any { it in '฀'..'๿' }

    private val BAHT = Regex(
        """฿\s*(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{1,2}))?|(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{1,2}))?\s*(?:บาท|บ\.)""")

    /** One amount the request names: its text as written and its value in minor units. */
    data class Amount(val text: String, val minor: Long)

    /**
     * Every baht amount in [text] (Thai digits too), in minor units of a currency
     * with [fractionDigits] decimals: "90 บาท" → 9000, "฿12.50" → 1250, "๙๐ บาท" → 9000.
     */
    fun amounts(text: String, fractionDigits: Int = 2): List<Amount> =
        BAHT.findAll(asciiDigits(text)).mapNotNull { m ->
            val whole = (m.groupValues[1].ifEmpty { m.groupValues[3] }).replace(",", "")
            val frac = m.groupValues[2].ifEmpty { m.groupValues[4] }
            val value = runCatching { BigDecimal(if (frac.isEmpty()) whole else "$whole.$frac") }.getOrNull()
                ?: return@mapNotNull null
            Amount(m.value.trim(), value.movePointRight(fractionDigits.coerceAtLeast(0)).toLong())
        }.toList()

    /** The first baht amount in [text] in minor units, or null. */
    fun bahtMinor(text: String, fractionDigits: Int = 2): Long? = amounts(text, fractionDigits).firstOrNull()?.minor

    /**
     * The request's baht amounts as facts for the model (nothing when it names
     * none): "<amounts>\n90 บาท = 9000 minor units\n</amounts>" — a price, or an amount to
     * add or take off a price; the request says which.
     */
    fun amountsNote(text: String, fractionDigits: Int): String {
        val list = amounts(text, fractionDigits).distinctBy { it.text }.take(10)
        if (list.isEmpty()) return ""
        return "\n<amounts>\n" + list.joinToString("\n") { "${AiGuard.quote(it.text, 40)} = ${it.minor} minor units" } + "\n</amounts>"
    }

    /**
     * The rules for a store whose languages include Thai, appended to every
     * assistant's system prompt. [currency] / [fractionDigits]: the store's money.
     */
    fun prompt(currency: String, fractionDigits: Int): String {
        val unit = "1" + "0".repeat(fractionDigits.coerceAtLeast(0))
        return """
            Thai: the manager may write or speak Thai (or mix Thai and English). Understand Thai requests as fully as
            English ones ("เพิ่ม" = add, "ลบ" / "เอาออก" = remove, "ลดราคา" = lower the price, "ขึ้นราคา" = raise it,
            "ย้าย" = move, "ข้าง" / "ติด" = next to, "หมวด" = category, "โต๊ะ" = table, "ที่นั่ง" = seats,
            "หมดแล้ว" / "ปิดขาย" = sold out). Days: "วันจันทร์" … "วันอาทิตย์" = Monday … Sunday, "ทุกวันอังคาร" = every
            Tuesday. A price or discount tied to a day or a time ("ลด 10 บาท วันอังคาร", "50 บาท ช่วง 16:00–18:00") is a
            special on those days (set_specials), never a change to the everyday price.
            Thai digits (๐–๙) are the digits 0–9. "บาท" and "฿" are the store's money ($currency): "90 บาท", "฿90" and
            "๙๐ บาท" all mean 90, which is priceMinor ${90 * unit.toLong()} ($unit minor units per 1 $currency). When an
            <amounts> block follows the request it gives those amounts already converted to minor units (a price, or
            how much to add or take off one, as the request says): use those numbers.
            Thai text in names is plain menu text: keep Thai script as written, never romanise it.""".trimIndent()
    }

    /** The voice part for a store that speaks Thai (after the store's voice rules). */
    const val VOICE = "Thai speech gives a Thai transcript in Thai script (never romanised, never translated into " +
        "English); numbers spoken in Thai (\"เก้าสิบ\") are written as digits (90) in the transcript; \"language\" is \"th\"."
}
