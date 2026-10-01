package dev.dwhipstock.pos.sdk

/**
 * All money in the engine is integer cents: minor units of the store's own
 * currency ([StoreProfile.currency], CAD or USD). The engine never mixes
 * currencies — one store, one currency — and stays symbol-free; people-facing
 * text with a symbol goes through [MoneyFormat].
 */
@JvmInline
value class Money(val cents: Long) : Comparable<Money> {
    // checked: a sum that would wrap past Long throws (ArithmeticException)
    // instead of silently turning a bill negative. [MoneyLimits] keeps real
    // input far below that; this is the backstop.
    operator fun plus(other: Money) = Money(Math.addExact(cents, other.cents))
    operator fun minus(other: Money) = Money(Math.subtractExact(cents, other.cents))
    operator fun times(qty: Int) = Money(Math.multiplyExact(cents, qty.toLong()))
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

        /** Whole-CAD helper for seed data / tests. */
        fun cad(dollars: Long) = Money(dollars * 100)
    }
}

/** Overflow-safe sum of cents: throws (ArithmeticException) rather than wrapping. */
fun Iterable<Long>.sumExact(): Long = fold(0L) { a, b -> Math.addExact(a, b) }

/** Overflow-safe sumOf for cents. */
inline fun <T> Iterable<T>.sumOfExact(selector: (T) -> Long): Long = fold(0L) { a, t -> Math.addExact(a, selector(t)) }

/**
 * Sane upper bounds on money and quantities the store accepts (red team
 * 2026-10-01). Far below Long's range, so no total, refund or report sum can
 * overflow, and far above anything a pub, counter or c-store rings.
 */
object MoneyLimits {
    /** One unit of a menu or open item: $99,999.99. */
    const val MAX_UNIT_PRICE_CENTS = 9_999_999L
    /** Quantity on one staff line. */
    const val MAX_LINE_QTY = 999
    /** Quantity on one guest (QR) line, like the kiosk. */
    const val MAX_GUEST_LINE_QTY = 20
    /** Lines in one guest (QR) basket, like the kiosk. */
    const val MAX_GUEST_BASKET_LINES = 40
    /** PENDING guest lines waiting on one check. */
    const val MAX_PENDING_LINES_PER_CHECK = 100
    /** Cash handed over beyond what is due (the change the drawer gives back): $1,000. */
    const val MAX_CASH_OVER_DUE_CENTS = 100_000L
    /** One non-sale cash in / out, an opening float or a closing count: $1,000,000. */
    const val MAX_DRAWER_AMOUNT_CENTS = 100_000_000L

    /** Refuses (400 bad_request) a unit price above [MAX_UNIT_PRICE_CENTS]. */
    fun requireUnitPrice(cents: Long) {
        require(cents <= MAX_UNIT_PRICE_CENTS) { "price must be at most $MAX_UNIT_PRICE_CENTS cents" }
    }

    /** Refuses (400 bad_request) a quantity outside 1..[max]. */
    fun requireQty(qty: Int, max: Int = MAX_LINE_QTY) {
        require(qty in 1..max) { "qty must be 1-$max" }
    }
}
