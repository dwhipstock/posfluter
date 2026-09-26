package dev.dwhipstock.pos.sdk

/**
 * All money in the engine is integer cents: minor units of the store's own
 * currency ([StoreProfile.currency], CAD or USD). The engine never mixes
 * currencies — one store, one currency — and stays symbol-free; people-facing
 * text with a symbol goes through [MoneyFormat].
 */
@JvmInline
value class Money(val cents: Long) : Comparable<Money> {
    operator fun plus(other: Money) = Money(cents + other.cents)
    operator fun minus(other: Money) = Money(cents - other.cents)
    operator fun times(qty: Int) = Money(cents * qty)
    override fun compareTo(other: Money) = cents.compareTo(other.cents)

    val isZero get() = cents == 0L

    /** Receipt figures: "1,010", "-0.50", "215.50". Currency symbol is the caller's call. */
    fun format(): String {
        val sign = if (cents < 0) "-" else ""
        val abs = kotlin.math.abs(cents)
        val whole = (abs / 100).toString().reversed().chunked(3).joinToString(",").reversed()
        val frac = abs % 100
        return if (frac == 0L) "$sign$whole" else "$sign$whole.%02d".format(frac)
    }

    /** Always two decimals: "1,010.00", "-0.50" (US shelf and receipt style). */
    fun formatCents(): String {
        val sign = if (cents < 0) "-" else ""
        val abs = kotlin.math.abs(cents)
        val whole = (abs / 100).toString().reversed().chunked(3).joinToString(",").reversed()
        return "$sign$whole.%02d".format(abs % 100)
    }

    companion object {
        val ZERO = Money(0)

        /** "1,010.50" → "1 010,50" (no-break space thousands, decimal comma). */
        fun frenchFigure(figure: String): String =
            figure.replace(',', '\u00A0').replace('.', ',')
        /** Whole-CAD helper for seed data / tests. */
        fun cad(dollars: Long) = Money(dollars * 100)
    }
}
