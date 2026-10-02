package dev.dwhipstock.poscloud.menuprint

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The fixed words on a printed menu, in the portal's five languages, and its
 * money: always North American ("$1,234.56", "-$5.00") whatever the language
 * (the owner's rule, as lib/format.ts in the portal). Day names and the
 * specials' own words are the store's (server/.../i18n/messages_*.properties:
 * day.*, special.*), so a receipt and a printed menu say the same thing.
 */
object PrintWords {
    val LANGS = listOf("en", "fr", "es", "de", "af")

    fun lang(raw: String?): String = raw?.trim()?.lowercase()?.take(2)?.takeIf { it in LANGS } ?: "en"

    private fun pick(map: Map<String, String>, lang: String) = map[lang] ?: map.getValue("en")

    // --- the menu kinds' own titles (the fallback title when the AI writes none) ---

    private val TITLES = mapOf(
        MenuKind.FULL to mapOf("en" to "Menu", "fr" to "Menu", "es" to "Menú", "de" to "Speisekarte", "af" to "Spyskaart"),
        MenuKind.TODAY to mapOf("en" to "Today's menu", "fr" to "Menu du jour", "es" to "Menú del día", "de" to "Tageskarte", "af" to "Vandag se spyskaart"),
        MenuKind.DRINKS to mapOf("en" to "Drinks", "fr" to "Boissons", "es" to "Bebidas", "de" to "Getränke", "af" to "Drankies"),
        MenuKind.HIGHLIGHTS to mapOf("en" to "Highlights", "fr" to "Nos incontournables", "es" to "Destacados", "de" to "Empfehlungen", "af" to "Hoogtepunte"),
        MenuKind.FLYER to mapOf("en" to "Happy hour & specials", "fr" to "Happy hour et spéciaux", "es" to "Hora feliz y especiales",
            "de" to "Happy Hour & Angebote", "af" to "Happy hour & spesiale"),
    )

    fun title(kind: MenuKind, lang: String) = pick(TITLES.getValue(kind), lang)

    private val SPECIALS = mapOf("en" to "Specials", "fr" to "Spéciaux", "es" to "Especiales", "de" to "Angebote", "af" to "Spesiale")
    fun specials(lang: String) = pick(SPECIALS, lang)

    private val TODAY_PREFIX = mapOf("en" to "Today", "fr" to "Aujourd'hui", "es" to "Hoy", "de" to "Heute", "af" to "Vandag")
    /** "Today" — the label of today's specials box. */
    fun today(lang: String) = pick(TODAY_PREFIX, lang)

    private val PAGE = mapOf("en" to "Page", "fr" to "Page", "es" to "Página", "de" to "Seite", "af" to "Bladsy")
    fun page(lang: String) = pick(PAGE, lang)

    // --- days (the store's words) ---

    private val DAY_NAMES = mapOf(
        "en" to listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"),
        "fr" to listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche"),
        "es" to listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"),
        "de" to listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"),
        "af" to listOf("Maandag", "Dinsdag", "Woensdag", "Donderdag", "Vrydag", "Saterdag", "Sondag"),
    )
    private val SHORT_DAYS = mapOf(
        "en" to listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
        "fr" to listOf("lun", "mar", "mer", "jeu", "ven", "sam", "dim"),
        "es" to listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom"),
        "de" to listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"),
        "af" to listOf("Ma", "Di", "Wo", "Do", "Vr", "Sa", "So"),
    )
    private val AND = mapOf("en" to " & ", "fr" to " et ", "es" to " y ", "de" to " & ", "af" to " en ")
    private val EVERY_DAY = mapOf("en" to "Every day", "fr" to "Tous les jours", "es" to "Todos los días", "de" to "Jeden Tag", "af" to "Elke dag")
    private val HAPPY_HOUR = mapOf("en" to "Happy hour", "fr" to "Happy hour", "es" to "Hora feliz", "de" to "Happy Hour", "af" to "Happy hour")
    private val DAY_SPECIAL = mapOf("en" to "{0} special", "fr" to "Spécial du {0}", "es" to "Especial del {0}", "de" to "{0}sangebot", "af" to "{0}-spesiaal")
    private val GENERIC = mapOf("en" to "Special", "fr" to "Spécial", "es" to "Especial", "de" to "Angebot", "af" to "Spesiaal")
    /** "{days} only": an item sold on some days. */
    private val ONLY = mapOf("en" to "{0} only", "fr" to "{0} seulement", "es" to "Solo {0}", "de" to "Nur {0}", "af" to "Net {0}")

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

    /** "4–6 pm", "11:30 am–2 pm" (en); "16:00–18:00" elsewhere. */
    fun window(from: String, to: String, lang: String): String {
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

    /** "Friday, October 2" / "vendredi 2 octobre" / "Freitag, 2. Oktober". */
    fun date(d: LocalDate, lang: String): String {
        val l = lang(lang)
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
        else -> currency.uppercase() + " "
    }

    /** "$1,234.56" in every language: symbol first, comma thousands, period decimals, always the cents. */
    fun money(cents: Long, currency: String = "CAD"): String {
        val neg = cents < 0
        val abs = kotlin.math.abs(cents)
        val whole = "%,d".format(Locale.US, abs / 100)
        return (if (neg) "-" else "") + symbol(currency) + whole + "." + "%02d".format(abs % 100)
    }
}
