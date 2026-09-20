package dev.dwhipstock.pos.sdk

/**
 * All money in the engine is integer cents (1/100 CAD).
 * TODO: currency symbol/code is a per-locale concern — when it lands it belongs
 * in the sdk.i18n message catalog (e.g. a `money.currency` key), not here; for
 * now the engine is currency-agnostic minor units.
 */
@JvmInline
value class Money(val cents: Long) : Comparable<Money> {
    operator fun plus(other: Money) = Money(cents + other.cents)
    operator fun minus(other: Money) = Money(cents - other.cents)
    operator fun times(qty: Int) = Money(cents * qty)
    override fun compareTo(other: Money) = cents.compareTo(other.cents)

    val isZero get() = cents == 0L

    /** Whole-CAD display: "1,010", "-0.50", "215.50". Currency symbol is the caller's call. */
    fun format(): String {
        val sign = if (cents < 0) "-" else ""
        val abs = kotlin.math.abs(cents)
        val whole = (abs / 100).toString().reversed().chunked(3).joinToString(",").reversed()
        val frac = abs % 100
        return if (frac == 0L) "$sign$whole" else "$sign$whole.%02d".format(frac)
    }

    companion object {
        val ZERO = Money(0)
        /** Whole-CAD helper for seed data / tests. */
        fun cad(dollars: Long) = Money(dollars * 100)
    }
}
