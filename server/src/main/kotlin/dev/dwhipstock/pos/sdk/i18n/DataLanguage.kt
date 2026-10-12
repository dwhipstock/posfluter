package dev.dwhipstock.pos.sdk.i18n

import dev.dwhipstock.pos.sdk.ReceiptPrintMode
import java.io.File
import java.util.Properties

/**
 * The store's local DATA language: the language held in the catalog's `*_fr`
 * slots (name_fr, label_fr, labelFr...). The column names stay; their meaning
 * is "the store's local language". A French + English store keeps French
 * there (the default, today's behaviour); a Thai + English store keeps Thai
 * there. English always lives in the `*_en` slots, and every other language
 * in the translations table (migration 053).
 *
 * Rule ([resolve]): an explicit setting (`POS_DATA_LOCALE` / `store.data_locale`)
 * if valid, else the first of the store's locales that is a slot language
 * ([SLOT_LANGUAGES]), else French. Set once at boot ([use]); tests may set it
 * and must [reset] it.
 */
object DataLanguage {
    /** Languages that can live in the `*_fr` slot. */
    val SLOT_LANGUAGES: Set<String> = setOf("fr", "th")

    const val ENV = "POS_DATA_LOCALE"
    const val KEY = "store.data_locale"

    @Volatile
    var locale: LocaleCode = LocaleCode.FR
        private set

    val tag: String get() = locale.tag

    fun use(value: LocaleCode) { locale = value }

    fun reset() { locale = LocaleCode.FR }

    /** The local language named for an AI prompt: "French" or "Thai". */
    val promptName: String get() = if (tag == "th") "Thai" else "French"

    /** The same, with the variety the menus are written in: "Québec French" or "Thai". */
    val promptNameRegional: String get() = if (tag == "th") "Thai" else "Québec French"

    /** Whether [lang] reads the `*_fr` slot. */
    fun readsLocalSlot(lang: String): Boolean = lang.trim().lowercase() == tag

    fun resolve(locales: List<LocaleCode>, explicit: String? = null): LocaleCode {
        explicit?.trim()?.lowercase()?.takeIf { it in SLOT_LANGUAGES }?.let { return LocaleCode(it) }
        return locales.firstOrNull { it.tag in SLOT_LANGUAGES } ?: LocaleCode.FR
    }

    /** The explicit setting from the env or the store.properties file, or null. */
    fun explicitFromEnv(env: (String) -> String? = System::getenv): String? =
        StoreLocales.setting(ENV, KEY, env)
}

/**
 * A store's languages from local config: `POS_LOCALES` (desktop / docker) or
 * `store.locales` in the tablet's store.properties, a comma list ("th,en",
 * the first is the default). It replaces the store profile's locales; a
 * missing, blank or unusable value keeps the profile's.
 */
object StoreLocales {
    const val ENV = "POS_LOCALES"
    const val KEY = "store.locales"
    private val TAG = Regex("^[a-z]{2,8}$")

    /** "th, EN ,th" → [th, en]; null when nothing usable. */
    fun parse(raw: String?): List<LocaleCode>? {
        val tags = raw?.split(',')?.map { it.trim().lowercase() }?.filter { TAG.matches(it) }?.distinct().orEmpty()
        return tags.takeIf { it.isNotEmpty() }?.map(::LocaleCode)
    }

    fun fromEnv(env: (String) -> String? = System::getenv): List<LocaleCode>? = parse(setting(ENV, KEY, env))

    internal fun setting(envName: String, key: String, env: (String) -> String?): String? {
        env(envName)?.takeIf { it.isNotBlank() }?.let { return it }
        val file = env(ReceiptPrintMode.ENV_CONFIG_FILE)?.takeIf { it.isNotBlank() }?.let(::File)
        if (file == null || !file.exists()) return null
        val props = runCatching { Properties().apply { file.inputStream().use(::load) } }.getOrNull() ?: return null
        return props.getProperty(key)?.takeIf { it.isNotBlank() }
    }
}
