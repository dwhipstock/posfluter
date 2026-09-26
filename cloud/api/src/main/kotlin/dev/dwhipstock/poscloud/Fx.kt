package dev.dwhipstock.poscloud

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Currencies across the tenant's stores. Each store sells in one currency; the
 * cloud keeps every figure in the currency it was taken in and never adds two
 * currencies together. For the "All stores" view it can express a total in
 * the tenant's reporting currency at a FIXED rate from config (no live rates):
 * always labelled approximate, with the rate shown next to it.
 */
object Fx {

    /** FX_<FROM>_<TO> rates. The reverse direction is derived when only one is set. */
    class Rates(private val rates: Map<Pair<String, String>, BigDecimal>) {

        /** Units of [to] per unit of [from]; null when no rate is configured. */
        fun rate(from: String, to: String): BigDecimal? {
            val f = from.uppercase()
            val t = to.uppercase()
            if (f == t) return BigDecimal.ONE
            rates[f to t]?.let { return it }
            return rates[t to f]?.takeIf { it.signum() > 0 }?.let { BigDecimal.ONE.divide(it, 10, RoundingMode.HALF_UP) }
        }

        /** [cents] of [from] in [to] (half-up to the cent); null without a rate. */
        fun convert(cents: Long, from: String, to: String): Long? =
            rate(from, to)?.let { BigDecimal(cents).multiply(it).setScale(0, RoundingMode.HALF_UP).longValueExact() }

        /** The configured rates, as set (for display). */
        fun listed(): List<RateDto> = rates.entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .map { (k, v) -> RateDto(k.first, k.second, v.stripTrailingZeros().toPlainString()) }

        companion object {
            private val KEY = Regex("""FX_([A-Z]{3})_([A-Z]{3})""")

            val NONE = Rates(emptyMap())

            /** Every FX_<FROM>_<TO> entry of [env] with a positive decimal value; others are ignored. */
            fun fromEnv(env: Map<String, String>): Rates = Rates(env.mapNotNull { (k, v) ->
                val m = KEY.matchEntire(k.trim().uppercase()) ?: return@mapNotNull null
                val rate = v.trim().toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return@mapNotNull null
                (m.groupValues[1] to m.groupValues[2]) to rate
            }.toMap())

            fun of(vararg pairs: Pair<String, String>): Rates = fromEnv(pairs.toMap())
        }
    }

    @Serializable
    data class RateDto(val from: String, val to: String, val rate: String)
}

/**
 * How a report's money reads (every report response carries one):
 *  - one currency in scope → [currency] is it, [approximate] false, figures exact;
 *  - several (the "All stores" view across countries) → the combined figures
 *    are in the tenant's [reportingCurrency], converted at [rates], and
 *    [approximate] is true. Per-store rows always stay exact, in their own
 *    currency. [convertible] is false when a needed rate is missing: the
 *    combined figures are then 0 and only the per-currency totals are real.
 */
@Serializable
data class MoneyScope(
    val currency: String,
    val approximate: Boolean,
    val reportingCurrency: String,
    val currencies: List<String>,
    val rates: List<Fx.RateDto> = emptyList(),
    val convertible: Boolean = true,
)
