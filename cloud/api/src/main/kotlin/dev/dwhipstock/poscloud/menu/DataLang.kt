package dev.dwhipstock.poscloud.menu

/**
 * A venue's DATA language: the language held in the catalog's `*_fr` slots
 * (name_fr, label_fr, description_fr). The column names stay; they mean "the
 * store's local language" (CONTRACT.md, catalog names). A French + English
 * store keeps French there (the default, as always); a Thai + English store
 * keeps Thai there. English is always the `*_en` slot; any other language is
 * a row in catalog_names.
 *
 * Rule ([resolve]): an explicit value if it is a slot language, else the first
 * of the venue's locales that is one, else French. The cloud keeps no locale
 * list per venue, so when the caller sends neither (an older portal) the menu
 * itself says ([detect]): Thai script in the `*_fr` slot means Thai.
 */
object DataLang {
    /** Languages that can live in the `*_fr` slot. */
    val SLOT_LANGS: Set<String> = setOf("fr", "th")
    const val DEFAULT = "fr"

    private fun norm(raw: String?) = raw?.trim()?.lowercase()?.take(2)

    /** The explicit value or the locales' first slot language; null when neither says. */
    fun resolve(explicit: String?, locales: List<String>?): String? =
        norm(explicit)?.takeIf { it in SLOT_LANGS }
            ?: locales?.mapNotNull(::norm)?.firstOrNull { it in SLOT_LANGS }

    private val THAI = Regex("[\\u0E00-\\u0E7F]")

    /** What the `*_fr` slot texts are written in: Thai if any of them is, else French. */
    fun detect(frSlot: Sequence<String>): String = if (frSlot.any { THAI.containsMatchIn(it) }) "th" else DEFAULT

    fun of(explicit: String?, locales: List<String>?, frSlot: () -> Sequence<String>): String =
        resolve(explicit, locales) ?: detect(frSlot())

    /**
     * A data name in [lang]: the `*_fr` slot when [lang] is the data language,
     * the `*_en` slot for English, else its translation, else English, else the
     * local slot. A French venue reads exactly as it always did.
     */
    fun pick(lang: String, dataLang: String, fr: String, en: String, names: Map<String, String> = emptyMap()): String = when (lang) {
        dataLang -> fr.ifBlank { en }
        "en" -> en.ifBlank { fr }
        else -> names[lang]?.takeIf { it.isNotBlank() } ?: en.ifBlank { fr }
    }

    /** A description (no translations): the local slot for the data language, else English first. */
    fun pickText(lang: String, dataLang: String, fr: String, en: String): String =
        if (lang == dataLang) fr.ifBlank { en } else en.ifBlank { fr }

    /** English name of a slot language, for prompts ("the nameFr field holds Thai"). */
    fun englishName(lang: String): String = when (lang) { "th" -> "Thai"; else -> "French" }
}
