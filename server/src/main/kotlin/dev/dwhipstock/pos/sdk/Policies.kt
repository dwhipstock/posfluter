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
     * A tax baked into the shelf price (a VAT): the price the guest sees is
     * what they pay, and the tax inside it is taken out of the taxable base
     * once per check, half-up to the cent: base × rate / (100 + rate). It
     * never changes the total ([TaxAssessment.taxAdded] is zero).
     *
     * [component] names it for receipts, reports and sync ("VAT", 7%): the
     * amount comes back as an included [TaxLine] ([TaxLine.included]), so the
     * store's reports and the cloud's per-tax report carry it like an added
     * tax, while totals never add it again.
     *
     * [showOnReceipt]: true prints one "VAT 7% included" line under the total;
     * false keeps it off guest paper (reporting only).
     */
    data class InclusiveTax(val component: TaxComponent, val showOnReceipt: Boolean) : TaxPolicy {
        /** The older shape: an unnamed included sales tax at a whole-number rate. */
        constructor(ratePercent: Int, showOnReceipt: Boolean) : this(
            TaxComponent(INCLUDED_TAX_CODE, "Taxe de vente", "Sales tax", BigDecimal(ratePercent), registrationNumber = ""),
            showOnReceipt,
        )

        init {
            require(component.ratePercent.signum() >= 0) { "an included tax rate is not negative" }
        }

        val ratePercent: BigDecimal get() = component.ratePercent

        override fun assess(taxableBase: Money): TaxAssessment {
            val tax = includedIn(taxableBase)
            return TaxAssessment(
                taxIncluded = tax, taxAdded = Money.ZERO, showOnReceipt = showOnReceipt,
                includedLines = includedLines(tax),
            )
        }

        /** The tax inside [gross]: gross × rate / (100 + rate), half-up to the cent (symmetric for negatives). */
        fun includedIn(gross: Money): Money {
            val r = component.ratePercent
            val tax = BigDecimal(gross.cents).multiply(r).divide(BigDecimal(100).add(r), 0, RoundingMode.HALF_UP)
            return Money(tax.longValueExact())
        }

        override fun includedLines(included: Money): List<TaxLine> =
            listOf(TaxLine(component, included, included = true))
    }

    /**
     * Taxes added on top of pre-tax prices, itemised per [components] (e.g.
     * GST / TPS and QST / TVQ). Each component is its own rate on the SAME
     * taxable base — never compounded, never per line. Always printed: it
     * changes the total.
     *
     * Rounding, once per check (never per line), is [rounding]:
     * - [TaxRounding.PER_COMPONENT] (the default): each component rounds
     *   half-up to the cent on its own (Québec's GST and QST are two taxes
     *   on the receipt).
     * - [TaxRounding.COMBINED]: the COMBINED rate is rounded half-up once —
     *   the bill's tax is exactly round(base × 8.25%) — and that amount is
     *   then split into the components for remittance by the largest
     *   remainder of their exact shares (ties to the first). The components
     *   always add up to the combined amount, so the back-office split
     *   reconciles to the receipts to the cent; a component is at most 1¢
     *   away from rounding it on its own.
     *
     * [display] is what guests see (receipts, bills, the pay screen, the
     * kiosk ticket, the table-QR bill): every component on its own line
     * ([TaxDisplay.Itemized]) or one line at the combined rate
     * ([TaxDisplay.Combined], "Tax (8.25%)"). Back-office reports always
     * show the components (with [TaxComponent.remitTo]).
     */
    data class AddedTaxes(
        val components: List<TaxComponent>,
        val rounding: TaxRounding = TaxRounding.PER_COMPONENT,
        val display: TaxDisplay = TaxDisplay.Itemized,
    ) : TaxPolicy {
        init {
            require(components.map { it.code }.toSet().size == components.size) { "tax codes must be unique" }
        }

        /** The rate a combined line shows: every component's added up ("8.25"). */
        val combinedRatePercent: BigDecimal get() = dev.dwhipstock.pos.sdk.combinedRate(components)

        override fun assess(taxableBase: Money): TaxAssessment {
            val combined = rounding == TaxRounding.COMBINED && components.size > 1 && taxableBase.cents > 0
            val lines = if (combined) combinedLines(taxableBase)
            else components.map { TaxLine(it, it.on(taxableBase)) }
            return TaxAssessment(
                taxIncluded = Money.ZERO,
                taxAdded = Money(lines.sumOf { it.amount.cents }),
                showOnReceipt = true,
                lines = lines,
            )
        }

        /** One half-up rounding of the combined rate, shared into the components (largest remainder). */
        private fun combinedLines(base: Money): List<TaxLine> {
            val hundred = BigDecimal(100)
            val b = BigDecimal(base.cents)
            val total = b.multiply(combinedRatePercent).divide(hundred, 0, RoundingMode.HALF_UP).longValueExact()
            // exact shares in cents: floor each, then the leftover cents go to the largest fractions
            val exact = components.map { b.multiply(it.ratePercent).divide(hundred, 12, RoundingMode.HALF_UP) }
            val cents = exact.map { it.setScale(0, RoundingMode.FLOOR).longValueExact() }.toMutableList()
            val byFraction = exact.indices.sortedWith(
                compareByDescending<Int> { exact[it] - BigDecimal(cents[it]) }.thenBy { it },
            )
            // the floors are at most components.size − 1 short of the rounded total, never over
            var left = total - cents.sum()
            var k = 0
            while (left > 0) { cents[byFraction[k % byFraction.size]]++; left--; k++ }
            return components.mapIndexed { i, c -> TaxLine(c, Money(cents[i])) }
        }
    }

    /** How guests see the taxes ([AddedTaxes.display]); Itemized for any other policy. */
    val guestDisplay: TaxDisplay get() = (this as? AddedTaxes)?.display ?: TaxDisplay.Itemized

    /**
     * The tax lines inside a figure that already carries [included] of
     * included tax (a check, a bill share, a refund): one per included
     * component, [TaxLine.included] set. Empty for every policy that adds
     * its taxes on top.
     */
    fun includedLines(included: Money): List<TaxLine> = emptyList()

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
    /** Who this tax is paid to ("NCDOR", "Wake County"): back-office reports only, never on a guest's bill. */
    val remitTo: String = "",
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

/**
 * One assessed tax: which component, and how much. [included] = the amount
 * is inside the price ([TaxPolicy.InclusiveTax]), already part of the total;
 * false = added on top of the subtotal.
 */
data class TaxLine(val component: TaxComponent, val amount: Money, val included: Boolean = false)

/** The code of [TaxPolicy.InclusiveTax]'s unnamed tax (the older constructor). */
const val INCLUDED_TAX_CODE = "INCLUDED_TAX"

/** How [TaxPolicy.AddedTaxes] rounds to the cent (once per check either way). */
enum class TaxRounding {
    /** Each component half-up on its own (GST and QST). */
    PER_COMPONENT,

    /** The combined rate half-up once, split into the components by largest remainder. */
    COMBINED,
}

/** What guests see of [TaxPolicy.AddedTaxes] (reports always itemise). */
sealed interface TaxDisplay {
    /** One line per component: "GST/TPS 5%", "TVQ/QST 9.975%". */
    data object Itemized : TaxDisplay

    /**
     * One line at the combined rate: "Tax (8.25%)". [label] overrides the
     * localised word ("Tax", "Taxes", "Impuesto", …); null = the locale's.
     */
    data class Combined(val label: String? = null) : TaxDisplay

    /** The wire name ([dev.dwhipstock.pos] `/health` `taxDisplay`). */
    val wire: String get() = if (this is Combined) "combined" else "itemized"
}

/** Every rate added up, exact ("7.25" + "1" = "8.25"). */
fun combinedRate(components: List<TaxComponent>): BigDecimal =
    components.fold(BigDecimal.ZERO) { a, c -> a + c.ratePercent }

/**
 * What a guest-facing surface prints for [lines] under [display]: the lines
 * as they are ([TaxDisplay.Itemized], or a single tax), or one synthetic
 * line (code "TAX") at the summed rate and amount — the sum of the very
 * amounts the reports itemise, so the two always reconcile.
 */
fun guestTaxLines(lines: List<TaxLine>, display: TaxDisplay): List<TaxLine> {
    if (display !is TaxDisplay.Combined || lines.size < 2) return lines
    val label = display.label.orEmpty()
    return listOf(
        TaxLine(
            TaxComponent(COMBINED_TAX_CODE, label, label, combinedRate(lines.map { it.component }), registrationNumber = ""),
            Money(lines.sumOf { it.amount.cents }),
        ),
    )
}

/** The code of [guestTaxLines]' one combined line (its labels may be empty: the locale's word for "Tax"). */
const val COMBINED_TAX_CODE = "TAX"

data class TaxAssessment(
    val taxIncluded: Money, // part of the total already
    val taxAdded: Money,    // on top of the total (always ZERO for inclusive tax)
    val showOnReceipt: Boolean,
    /** Itemised added taxes (empty for inclusive / no tax); sums to [taxAdded]. */
    val lines: List<TaxLine> = emptyList(),
    /** Included taxes ([TaxLine.included]); sums to [taxIncluded]. Empty for added / no tax. */
    val includedLines: List<TaxLine> = emptyList(),
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
            // symmetric around zero: a refund rounds exactly like a sale
            val abs = kotlin.math.abs(exact.cents)
            val rounded = when (mode) {
                Mode.DOWN -> (abs / u) * u
                Mode.NEAREST -> ((abs + u / 2) / u) * u
            }
            return Money(if (exact.cents < 0) -rounded else rounded)
        }
    }

    data object NoRounding : RoundingPolicy {
        override fun roundCashDue(exact: Money) = exact
    }

    /** What rounding [exact] to cash adds (signed: −0.02, +0.01, 0). */
    fun cashAdjustment(exact: Money): Money = roundCashDue(exact) - exact

    companion object {
        /**
         * Nearest 5¢ on the last cent digit: 1–2 → 0, 3–4 → 5, 6–7 → 5,
         * 8–9 → 10; 0 and 5 unchanged ([CashRounding.NICKEL]).
         */
        val NICKEL: RoundingPolicy = RoundToUnit(Money(5), RoundToUnit.Mode.NEAREST)

        /**
         * Down to the whole unit of the currency: the minor units are dropped
         * (฿1,234.75 → ฿1,234), never rounded up ([CashRounding.UNIT_DOWN]).
         */
        val WHOLE_UNIT_DOWN: RoundingPolicy = RoundToUnit(Money(100), RoundToUnit.Mode.DOWN)
    }
}

sealed interface AuthPolicy {
    // PinLogin is live (bearer sessions + rate limit); the others await customers that want them.
    data object NoLogin : AuthPolicy
    data class PinLogin(val pinLength: Int = 4) : AuthPolicy
    data object IdScanLogin : AuthPolicy // magstripe/RFID, driver TBD (M2 hardware bring-up)
}
