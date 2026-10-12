package dev.dwhipstock.poscloud.menuprint

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The fixed words on a printed menu, in the portal's languages (Thai too), and its
 * money: always North American ("$1,234.56", "-$5.00") whatever the language
 * (the owner's rule, as lib/format.ts in the portal). Day names and the
 * specials' own words are the store's (server/.../i18n/messages_*.properties:
 * day.*, special.*), so a receipt and a printed menu say the same thing.
 */
object PrintWords {
    val LANGS = listOf("en", "fr", "es", "de", "af", "th")

    fun lang(raw: String?): String = raw?.trim()?.lowercase()?.take(2)?.takeIf { it in LANGS } ?: "en"

    private fun pick(map: Map<String, String>, lang: String) = map[lang] ?: map.getValue("en")

    // --- the menu kinds' own titles (the fallback title when the AI writes none) ---

    private val TITLES = mapOf(
        MenuKind.FULL to mapOf("en" to "Menu", "fr" to "Menu", "es" to "Menú", "de" to "Speisekarte", "af" to "Spyskaart", "th" to "เมนู"),
        MenuKind.TODAY to mapOf("en" to "Today's menu", "fr" to "Menu du jour", "es" to "Menú del día", "de" to "Tageskarte", "af" to "Vandag se spyskaart", "th" to "เมนูวันนี้"),
        MenuKind.DRINKS to mapOf("en" to "Drinks", "fr" to "Boissons", "es" to "Bebidas", "de" to "Getränke", "af" to "Drankies", "th" to "เครื่องดื่ม"),
        MenuKind.HIGHLIGHTS to mapOf("en" to "Highlights", "fr" to "Nos incontournables", "es" to "Destacados", "de" to "Empfehlungen", "af" to "Hoogtepunte", "th" to "เมนูแนะนำ"),
        MenuKind.FLYER to mapOf("en" to "Happy hour & specials", "fr" to "Happy hour et spéciaux", "es" to "Hora feliz y especiales",
            "de" to "Happy Hour & Angebote", "af" to "Happy hour & spesiale", "th" to "แฮปปี้อาวร์และโปรโมชัน"),
    )

    fun title(kind: MenuKind, lang: String) = pick(TITLES.getValue(kind), lang)

    private val SPECIALS = mapOf("en" to "Specials", "fr" to "Spéciaux", "es" to "Especiales", "de" to "Angebote", "af" to "Spesiale", "th" to "โปรโมชัน")
    fun specials(lang: String) = pick(SPECIALS, lang)

    private val FOOTER = mapOf("en" to "Enjoy!", "fr" to "Bon appétit !", "es" to "¡Buen provecho!", "de" to "Guten Appetit!", "af" to "Geniet dit!", "th" to "ขอให้อร่อยกับมื้อนี้!")
    private val RESPONSIBLY = mapOf(
        "en" to "Enjoy! Please drink responsibly.", "fr" to "Bon appétit ! À consommer avec modération.",
        "es" to "¡Buen provecho! Bebe con moderación.", "de" to "Guten Appetit! Bitte trinken Sie verantwortungsvoll.",
        "af" to "Geniet dit! Drink asseblief verantwoordelik.",
        "th" to "ขอให้อร่อยกับมื้อนี้! โปรดดื่มอย่างรับผิดชอบ",
    )
    /** The footer when the AI's is not usable: a friendly line (and responsible drinking with alcohol on the page). */
    fun footer(lang: String, alcohol: Boolean) = pick(if (alcohol) RESPONSIBLY else FOOTER, lang(lang))

    private val TODAY_PREFIX = mapOf("en" to "Today", "fr" to "Aujourd'hui", "es" to "Hoy", "de" to "Heute", "af" to "Vandag", "th" to "วันนี้")
    /** "Today" — the label of today's specials box. */
    fun today(lang: String) = pick(TODAY_PREFIX, lang)

    private val PAGE = mapOf("en" to "Page", "fr" to "Page", "es" to "Página", "de" to "Seite", "af" to "Bladsy", "th" to "หน้า")
    fun page(lang: String) = pick(PAGE, lang)

    // --- days (the store's words) ---

    private val DAY_NAMES = mapOf(
        "en" to listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"),
        "fr" to listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche"),
        "es" to listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"),
        "de" to listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"),
        "af" to listOf("Maandag", "Dinsdag", "Woensdag", "Donderdag", "Vrydag", "Saterdag", "Sondag"),
        "th" to listOf("วันจันทร์", "วันอังคาร", "วันพุธ", "วันพฤหัสบดี", "วันศุกร์", "วันเสาร์", "วันอาทิตย์"),
    )
    private val SHORT_DAYS = mapOf(
        "en" to listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
        "fr" to listOf("lun", "mar", "mer", "jeu", "ven", "sam", "dim"),
        "es" to listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom"),
        "de" to listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"),
        "af" to listOf("Ma", "Di", "Wo", "Do", "Vr", "Sa", "So"),
        "th" to listOf("จ.", "อ.", "พ.", "พฤ.", "ศ.", "ส.", "อา."),
    )
    private val AND = mapOf("en" to " & ", "fr" to " et ", "es" to " y ", "de" to " & ", "af" to " en ", "th" to " และ ")
    private val EVERY_DAY = mapOf("en" to "Every day", "fr" to "Tous les jours", "es" to "Todos los días", "de" to "Jeden Tag", "af" to "Elke dag", "th" to "ทุกวัน")
    private val HAPPY_HOUR = mapOf("en" to "Happy hour", "fr" to "Happy hour", "es" to "Hora feliz", "de" to "Happy Hour", "af" to "Happy hour", "th" to "แฮปปี้อาวร์")
    private val DAY_SPECIAL = mapOf("en" to "{0} special", "fr" to "Spécial du {0}", "es" to "Especial del {0}", "de" to "{0}sangebot", "af" to "{0}-spesiaal", "th" to "โปรโมชัน{0}")
    private val GENERIC = mapOf("en" to "Special", "fr" to "Spécial", "es" to "Especial", "de" to "Angebot", "af" to "Spesiaal", "th" to "โปรโมชัน")
    /** "{days} only": an item sold on some days. */
    private val ONLY = mapOf("en" to "{0} only", "fr" to "{0} seulement", "es" to "Solo {0}", "de" to "Nur {0}", "af" to "Net {0}", "th" to "เฉพาะ {0}")

    fun dayName(code: String, lang: String): String =
        TodayRules.DAYS.indexOf(code).takeIf { it >= 0 }?.let { DAY_NAMES.getValue(lang(lang))[it] } ?: code

    /** "Mon–Fri", "Fri & Sat", "Every day". */
    fun days(days: List<String>, lang: String): String {
        val l = lang(lang)
        val idx = days.map { TodayRules.DAYS.indexOf(it) }.filter { it >= 0 }.distinct().sorted()
        if (idx.isEmpty() || idx.size == 7) return pick(EVERY_DAY, l)
        val names = SHORT_DAYS.getValue(l)
        if (idx.size >= 3 && idx.last() - idx.first() == idx.size - 1) return names[idx.first()] + "–" + names[idx.last()]
        val n = idx.map { names[it] }
        return if (n.size == 1) n[0] else n.dropLast(1).joinToString(", ") + pick(AND, l) + n.last()
    }

    /** "Fri & Sat only" — the tag on a day-only item. */
    fun onlyOn(days: List<String>, lang: String): String = pick(ONLY, lang(lang)).replace("{0}", days(days, lang))

    /** A special's name: its own label, else "Happy hour" (a time window) or "Tuesday special". */
    fun specialName(label: String?, days: List<String>, from: String?, lang: String): String {
        val l = lang(lang)
        label?.takeIf { it.isNotBlank() }?.let { return it }
        return when {
            from != null -> pick(HAPPY_HOUR, l)
            days.size == 1 -> pick(DAY_SPECIAL, l).replace("{0}", dayName(days[0], l))
            else -> pick(GENERIC, l)
        }
    }

    // --- times and dates ---

    /** "4–6 pm", "11:30 am–2 pm" (en); "16:00–18:00 น." (th); "16:00–18:00" elsewhere. */
    fun window(from: String, to: String, lang: String): String {
        if (lang(lang) == "th") return "$from–$to น."
        if (lang(lang) != "en") return "$from–$to"
        fun parts(t: String) = t.split(':').map { it.toInt() }.let { (h, m) -> Triple(h, m, if (h < 12) "am" else "pm") }
        fun clock(h: Int, m: Int) = (if (h % 12 == 0) 12 else h % 12).toString() + (if (m == 0) "" else ":%02d".format(m))
        val (fh, fm, fa) = parts(from); val (th, tm, ta) = parts(to)
        return if (fa == ta) "${clock(fh, fm)}–${clock(th, tm)} $ta" else "${clock(fh, fm)} $fa–${clock(th, tm)} $ta"
    }

    /** When a special runs: "Mon–Fri · 4–6 pm", "Tue", "Every day · 4–6 pm". */
    fun whenText(days: List<String>, from: String?, to: String?, lang: String): String =
        days(days, lang) + (if (from != null && to != null) " · " + window(from, to, lang) else "")

    private val DATE_PATTERNS = mapOf(
        "en" to "EEEE, MMMM d", "fr" to "EEEE d MMMM", "es" to "EEEE d 'de' MMMM", "de" to "EEEE, d. MMMM", "af" to "EEEE d MMMM",
    )

    /** Thai month names (the JDK's Thai locale data varies between versions). */
    val THAI_MONTHS = listOf(
        "มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน", "พฤษภาคม", "มิถุนายน",
        "กรกฎาคม", "สิงหาคม", "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม",
    )

    /** The year as printed in [lang]: Thai counts the Buddhist Era (CE + 543), 2026 → 2569. */
    fun year(d: LocalDate, lang: String): Int = if (lang(lang) == "th") d.year + 543 else d.year

    /** "2 ตุลาคม 2569" — a Thai date with its Buddhist Era year (for a dated line, when one is printed). */
    fun dateWithYear(d: LocalDate, lang: String): String =
        if (lang(lang) == "th") "${d.dayOfMonth} ${THAI_MONTHS[d.monthValue - 1]} ${year(d, lang)}"
        else date(d, lang) + ", " + d.year

    /** "Friday, October 2" / "vendredi 2 octobre" / "Freitag, 2. Oktober" / "วันศุกร์ที่ 2 ตุลาคม". */
    fun date(d: LocalDate, lang: String): String {
        val l = lang(lang)
        if (l == "th") return dayName(TodayRules.code(d.dayOfWeek), l) + "ที่ " + d.dayOfMonth + " " + THAI_MONTHS[d.monthValue - 1]
        val text = DateTimeFormatter.ofPattern(DATE_PATTERNS.getValue(l), Locale.forLanguageTag(l)).format(d)
        // the weekday is the store's word (the JDK's locale data differs between versions)
        val jdkDay = DateTimeFormatter.ofPattern("EEEE", Locale.forLanguageTag(l)).format(d)
        return text.replaceFirst(jdkDay, dayName(TodayRules.code(d.dayOfWeek), l))
    }

    // --- money ---

    /** The symbol a currency is shown with on a one-store menu (lib/format.ts currencySymbol). */
    fun symbol(currency: String): String = when (currency.uppercase()) {
        "CAD", "USD", "AUD", "NZD" -> "$"
        "EUR" -> "€"
        "GBP" -> "£"
        "THB" -> "฿"
        else -> currency.uppercase() + " "
    }

    /**
     * "$1,234.56" in every language: symbol first, comma thousands, period
     * decimals, always the cents — except baht, whose satang show only when
     * there are some ("฿1,234", "฿1,234.50").
     */
    fun money(cents: Long, currency: String = "CAD"): String {
        val neg = cents < 0
        val abs = kotlin.math.abs(cents)
        val whole = "%,d".format(Locale.US, abs / 100)
        val frac = if (currency.uppercase() == "THB" && abs % 100 == 0L) "" else "." + "%02d".format(abs % 100)
        return (if (neg) "-" else "") + symbol(currency) + whole + frac
    }
}
