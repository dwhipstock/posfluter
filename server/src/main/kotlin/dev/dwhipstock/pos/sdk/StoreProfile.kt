package dev.dwhipstock.pos.sdk

import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import java.io.File
import java.util.Properties

/**
 * Where a store is and how it talks money and language: its country, its
 * currency, the languages its staff can pick (the first is the default) and
 * its IANA zone. Data, not code: every store's config names its own profile,
 * and nothing in the engine assumes a country or a currency.
 *
 * [kind] says which screens the terminal shows (table service or a retail
 * counter). [legalAge] is the store's default minimum age for age-restricted
 * items; `POS_LEGAL_AGE` / `legal.age` overrides it ([LegalAge]).
 */
data class StoreProfile(
    val country: String,
    val currency: String,
    val locales: List<LocaleCode>,
    val timeZone: String,
    val kind: Kind = Kind.RESTAURANT,
    val legalAge: Int = 18,
) {
    enum class Kind(val wire: String) { RESTAURANT("restaurant"), RETAIL("retail") }

    init {
        require(country.length == 2 && country == country.uppercase()) { "country is an ISO 3166 alpha-2 code" }
        require(currency.length == 3 && currency == currency.uppercase()) { "currency is an ISO 4217 code" }
        require(locales.isNotEmpty()) { "a store speaks at least one language" }
    }

    val defaultLocale: LocaleCode get() = locales.first()

    /** "en-US", "fr-CA": the BCP 47 tag of [locale] in this store's country. */
    fun tag(locale: LocaleCode = defaultLocale): String = "${locale.tag}-$country"

    /** Money in this store's currency, formatted for [locale] ([MoneyFormat]). */
    fun format(amount: Money, locale: LocaleCode = defaultLocale): String =
        MoneyFormat.format(amount, currency, tag(locale))

    companion object {
        /** The Montréal pubs: Canada, CAD, French and English (the historical default). */
        val QUEBEC_PUB = StoreProfile(
            country = "CA", currency = "CAD",
            locales = listOf(LocaleCode.FR, LocaleCode.EN),
            timeZone = VenueClock.DEFAULT_ZONE,
        )
    }
}

/**
 * Locale-correct money text for people (receipts keep the bare [Money.format]
 * figures they always printed). Covers the currencies and languages the
 * stores use; the symbol goes where the language puts it:
 *
 *  - `en-US` / `es-US` USD: `$12.99`, `-$1,234.50`
 *  - `en-CA` CAD: `$12.99`; `fr-CA` CAD: `12,99 $`, `1 234,50 $`
 *
 * [unambiguous] adds the country to a dollar sign (`US$12.99`, `CA$12.99`) for
 * places that show several currencies side by side.
 */
object MoneyFormat {
    private const val NBSP = '\u00A0'
    private const val NNBSP = '\u202F' // narrow no-break space: French thousands

    fun format(amount: Money, currency: String, localeTag: String, unambiguous: Boolean = false): String {
        val lang = localeTag.substringBefore('-').lowercase()
        val cents = amount.cents
        val abs = kotlin.math.abs(cents)
        val whole = abs / 100
        val frac = (abs % 100).toString().padStart(2, '0')
        val sign = if (cents < 0) "-" else ""
        val symbol = symbol(currency, unambiguous)
        return if (lang == "fr") {
            "$sign${group(whole, NNBSP)},$frac$NBSP$symbol"
        } else {
            "$sign$symbol${group(whole, ',')}.$frac"
        }
    }

    fun symbol(currency: String, unambiguous: Boolean = false): String = when (currency.uppercase()) {
        "USD" -> if (unambiguous) "US$" else "$"
        "CAD" -> if (unambiguous) "CA$" else "$"
        "EUR" -> "€"
        else -> currency.uppercase() + NBSP
    }

    private fun group(n: Long, sep: Char): String =
        n.toString().reversed().chunked(3).joinToString(sep.toString()).reversed()
}

/**
 * The store's minimum age for age-restricted items: `POS_LEGAL_AGE` (desktop /
 * docker) or `legal.age` in the tablet's store.properties, else the store
 * profile's default (21 in the US, 18 in Québec). Local config only; a missing
 * or bad value falls back to the default and never fails startup.
 */
object LegalAge {
    const val ENV = "POS_LEGAL_AGE"
    const val KEY = "legal.age"
    private val SANE = 16..25

    fun resolve(default: Int, raw: String?): Int =
        raw?.trim()?.toIntOrNull()?.takeIf { it in SANE } ?: default

    fun fromEnv(default: Int, env: (String) -> String? = System::getenv): Int {
        env(ENV)?.takeIf { it.isNotBlank() }?.let { return resolve(default, it) }
        val file = env(ReceiptPrintMode.ENV_CONFIG_FILE)?.takeIf { it.isNotBlank() }?.let(::File)
        return fromFile(default, file)
    }

    fun fromFile(default: Int, file: File?): Int {
        if (file == null || !file.exists()) return default
        val props = runCatching { Properties().apply { file.inputStream().use(::load) } }.getOrNull() ?: return default
        return resolve(default, props.getProperty(KEY))
    }
}

/**
 * Stamp a money-carrying sync payload with the store's currency and country
 * (CONTRACT §2): the cloud never has to guess which currency a figure is in.
 */
fun kotlinx.serialization.json.JsonObjectBuilder.putMoneyContext(profile: StoreProfile) {
    put("currency", kotlinx.serialization.json.JsonPrimitive(profile.currency))
    put("country", kotlinx.serialization.json.JsonPrimitive(profile.country))
}
