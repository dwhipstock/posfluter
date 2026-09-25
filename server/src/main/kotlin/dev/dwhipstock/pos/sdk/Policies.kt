package dev.dwhipstock.pos.sdk

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

    // TODO: ExclusiveTax (added on top, itemised as GST / TPS and QST / TVQ) when a customer needs it
    data object NoTax : TaxPolicy {
        override fun assess(taxableBase: Money) = TaxAssessment(Money.ZERO, Money.ZERO, showOnReceipt = false)
    }
}

data class TaxAssessment(
    val taxIncluded: Money, // part of the total already
    val taxAdded: Money,    // on top of the total (always ZERO for inclusive tax)
    val showOnReceipt: Boolean,
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
