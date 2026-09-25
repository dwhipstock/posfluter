package dev.dwhipstock.pos.sdk

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * SDK-tier policy interfaces. Behavioral variation between customers is expressed
 * as typed implementations of these — never config trees, never customer branching.
 */

sealed interface TaxPolicy {
    /** Tax assessed on a (post-discount) taxable base. */
    fun assess(taxableBase: Money): TaxAssessment

    /**
     * Sales tax baked into the shelf price. Tax is computed out of the base
     * (base * rate / (100 + rate)) and never changes the total.
     * [showOnReceipt] = false → computed internally for reporting only.
     */
    data class InclusiveTax(val ratePercent: Int, val showOnReceipt: Boolean) : TaxPolicy {
        override fun assess(taxableBase: Money): TaxAssessment {
            // rounds half-up at the cents; fine at this precision
            val tax = (taxableBase.cents * ratePercent * 2 + (100 + ratePercent)) / ((100 + ratePercent) * 2)
            return TaxAssessment(taxIncluded = Money(tax), taxAdded = Money.ZERO, showOnReceipt = showOnReceipt)
        }
    }

    /**
     * Taxes added on top of pre-tax prices, itemised per [components] (e.g.
     * GST / TPS and QST / TVQ). Each component is its own rate on the SAME
     * taxable base, rounded half-up to the cent once per check — never
     * compounded, never per line. Always printed: it changes the total.
     */
    data class AddedTaxes(val components: List<TaxComponent>) : TaxPolicy {
        init {
            require(components.map { it.code }.toSet().size == components.size) { "tax codes must be unique" }
        }

        override fun assess(taxableBase: Money): TaxAssessment {
            val lines = components.map { TaxLine(it, it.on(taxableBase)) }
            return TaxAssessment(
                taxIncluded = Money.ZERO,
                taxAdded = Money(lines.sumOf { it.amount.cents }),
                showOnReceipt = true,
                lines = lines,
            )
        }
    }

    data object NoTax : TaxPolicy {
        override fun assess(taxableBase: Money) = TaxAssessment(Money.ZERO, Money.ZERO, showOnReceipt = false)
    }
}

/**
 * One tax added on top of the price: data, not code. [code] is the stable key
 * reports and sync use ("GST", "QST"); the labels are what receipts print;
 * [registrationNumber] is the venue's number for this tax, printed on receipts.
 */
data class TaxComponent(
    val code: String,
    val labelFr: String,
    val labelEn: String,
    val ratePercent: BigDecimal,
    val registrationNumber: String,
) {
    /** This component on [base], half-up to the cent. */
    fun on(base: Money): Money =
        Money(BigDecimal(base.cents).multiply(ratePercent).divide(HUNDRED, 0, RoundingMode.HALF_UP).longValueExact())

    /** "5", "9.975": no trailing zeros; callers localise the decimal mark. */
    val rateText: String get() = ratePercent.stripTrailingZeros().toPlainString()

    private companion object {
        val HUNDRED = BigDecimal(100)
    }
}

/** One assessed tax: which component, and how much. */
data class TaxLine(val component: TaxComponent, val amount: Money)

data class TaxAssessment(
    val taxIncluded: Money, // part of the total already
    val taxAdded: Money,    // on top of the total (always ZERO for inclusive tax)
    val showOnReceipt: Boolean,
    /** Itemised added taxes (empty for inclusive / no tax); sums to [taxAdded]. */
    val lines: List<TaxLine> = emptyList(),
)

sealed interface RoundingPolicy {
    /**
     * Rounding is a tender-time concern: applied to the CASH amount due only.
     * The grand total stays exact; electronic tenders charge exact cents.
     */
    fun roundCashDue(exact: Money): Money

    data class RoundToUnit(val unit: Money, val mode: Mode) : RoundingPolicy {
        enum class Mode { NEAREST, DOWN }

        override fun roundCashDue(exact: Money): Money {
            val u = unit.cents
            return when (mode) {
                Mode.DOWN -> Money((exact.cents / u) * u)
                Mode.NEAREST -> Money(((exact.cents + u / 2) / u) * u)
            }
        }
    }

    data object NoRounding : RoundingPolicy {
        override fun roundCashDue(exact: Money) = exact
    }
}

sealed interface AuthPolicy {
    // PinLogin is live (bearer sessions + rate limit); the others await customers that want them.
    data object NoLogin : AuthPolicy
    data class PinLogin(val pinLength: Int = 4) : AuthPolicy
    data object IdScanLogin : AuthPolicy // magstripe/RFID, driver TBD (M2 hardware bring-up)
}
