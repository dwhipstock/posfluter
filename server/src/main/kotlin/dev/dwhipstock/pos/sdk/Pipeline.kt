package dev.dwhipstock.pos.sdk

/**
 * The 5-stage transaction pipeline (architecture.md):
 *
 *   1. Basket build     — line entry (owned by the caller / vertical)
 *   2. Price resolution — base price → overrides → promos (promos TODO: sub-pipeline)
 *   3. Tax + fees       — fees per Fee policy, tax on the post-discount taxable base
 *                         (added taxes: half-up per tax, once per check)
 *   4. Total lock       — grand total becomes immutable (a status transition, enforced by the caller)
 *   5. Tender           — split payments, cash rounding line, change calc
 *
 * Stages 2–3 are pure functions here; 4–5 are state transitions the persistence
 * layer drives using [tenderCash] for the math.
 */
object TransactionPipeline {

    /** Stage 2. TODO: manual price overrides + promo sub-pipeline (sequence, base, stacking). */
    fun priceLine(unitPrice: Money, qty: Int): Money = unitPrice * qty

    /**
     * Stages 2+3: full totals for a basket. [discount] (discounts + comps) comes
     * off the items before fees and tax, so tax is on what the guest actually
     * pays for. Tips are never part of a basket, so they are never taxed.
     */
    fun computeTotals(
        lines: List<BasketLine>, corkageBottles: Int, config: CustomerConfig, discount: Money = Money.ZERO,
    ): Totals {
        val itemsSubtotal = lines.fold(Money.ZERO) { acc, l -> acc + priceLine(l.unitPrice, l.qty) }
        require(discount >= Money.ZERO) { "discount must not be negative" }
        val discountApplied = minOf(discount, itemsSubtotal)
        val discounted = itemsSubtotal - discountApplied
        // tax-exempt lines (retail snacks, ice) stay out of the taxable base;
        // a discount comes off the taxable goods first. Every pub line is
        // taxable, so there this is exactly the discounted items.
        val exempt = lines.filterNot { it.taxable }.fold(Money.ZERO) { acc, l -> acc + priceLine(l.unitPrice, l.qty) }
        val taxableItems = maxOf(Money.ZERO, discounted - exempt)
        // bottle deposits (California CRV): per unit sold, never discounted
        val deposits = lines.fold(Money.ZERO) { acc, l -> acc + l.depositPerUnit * l.qty }

        val feeCtx = FeeContext(itemsSubtotal = discounted, corkageBottles = corkageBottles, deposits = deposits)
        val feeLines = config.fees.mapNotNull { it.assess(feeCtx) }
        val feesTotal = feeLines.fold(Money.ZERO) { acc, f -> acc + f.amount }

        val taxableBase = taxableItems + feeLines.filter { it.taxable }.fold(Money.ZERO) { a, f -> a + f.amount }
        val tax = config.taxPolicy.assess(taxableBase)

        return Totals(
            itemsSubtotal = itemsSubtotal,
            feeLines = feeLines,
            grandTotal = discounted + feesTotal + tax.taxAdded,
            taxIncluded = tax.taxIncluded,
            taxVisibleOnReceipt = tax.showOnReceipt,
            discount = discountApplied,
            taxableBase = taxableBase,
            taxLines = tax.lines,
        )
    }

    /**
     * Tax on a by-item split. Each group's basket and fees go through
     * [computeTotals] on their own, but tax is a CHECK-level figure: it is
     * assessed once on the summed taxable base (one half-up rounding per tax,
     * like the unsplit check), then shared out to the groups in proportion to
     * each group's taxable base (largest remainder). The group totals therefore
     * sum exactly to the check total.
     */
    fun apportionTax(groups: List<Totals>, config: CustomerConfig): List<Totals> {
        if (groups.isEmpty()) return groups
        val check = config.taxPolicy.assess(Money(groups.sumOf { it.taxableBase.cents }))
        val weights = groups.map { it.taxableBase.cents }
        val included = allocate(check.taxIncluded.cents, weights)
        val perLine = check.lines.map { line -> allocate(line.amount.cents, weights) }
        return groups.mapIndexed { i, g ->
            val lines = check.lines.mapIndexed { j, line -> TaxLine(line.component, Money(perLine[j][i])) }
            val added = Money(lines.sumOf { it.amount.cents })
            g.copy(
                grandTotal = g.preTaxTotal + added,
                taxIncluded = Money(included[i]),
                taxVisibleOnReceipt = check.showOnReceipt,
                taxLines = lines,
            )
        }
    }

    /**
     * The tax inside each of a set of fixed shares of [check]'s grand total
     * (an even ÷N split): the check's taxes shared out in proportion to the
     * shares (largest remainder), so they add back up exactly.
     */
    fun taxOfShares(check: Totals, shares: List<Money>): List<Pair<Money, List<TaxLine>>> {
        val weights = shares.map { it.cents }
        val included = allocate(check.taxIncluded.cents, weights)
        val perLine = check.taxLines.map { line -> allocate(line.amount.cents, weights) }
        return shares.indices.map { i ->
            Money(included[i]) to check.taxLines.mapIndexed { j, line -> TaxLine(line.component, Money(perLine[j][i])) }
        }
    }

    /**
     * Split [total] cents across [weights] proportionally; the result always
     * sums to [total]. Floors first, then the leftover cents go to the largest
     * fractional remainders (ties: the earlier entry). All-zero weights → the
     * whole amount lands on the first entry.
     */
    fun allocate(total: Long, weights: List<Long>): List<Long> {
        if (weights.isEmpty()) return emptyList()
        require(weights.all { it >= 0 }) { "weights must not be negative" }
        val sum = weights.sum()
        if (sum == 0L) return weights.indices.map { if (it == 0) total else 0L }
        val exact = weights.map { java.math.BigInteger.valueOf(total).multiply(java.math.BigInteger.valueOf(it)) }
        val divisor = java.math.BigInteger.valueOf(sum)
        val floors = exact.map { it.divide(divisor).toLong() }.toMutableList()
        var left = total - floors.sum()
        val order = weights.indices.sortedWith(
            compareByDescending<Int> { exact[it].mod(divisor) }.thenBy { it })
        for (i in order) {
            if (left == 0L) break
            floors[i] += 1
            left -= 1
        }
        return floors
    }

    /**
     * Stage 5 for a cash tender. Rounding applies HERE and only here — the grand
     * total stays exact; electronic tenders settle exact cents.
     *
     * Split-tender rule: rounding fires only when this cash payment SETTLES the
     * check (covers the rounded outstanding). A partial cash payment applies at
     * face value — the eventual final payment, whatever its type, deals with
     * the remainder (and gets the rounding if it's cash).
     */
    fun tenderCash(outstanding: Money, amountTendered: Money, config: CustomerConfig): CashTenderResult {
        require(amountTendered > Money.ZERO) { "cash amount must be positive" }
        val roundedDue = config.roundingPolicy.roundCashDue(outstanding)
        if (amountTendered < roundedDue) {
            // partial payment toward the balance: exact cents, no rounding, no change
            return CashTenderResult(amountApplied = amountTendered, roundingAdjustment = Money.ZERO, change = Money.ZERO)
        }
        return CashTenderResult(
            amountApplied = outstanding, // clears the exact outstanding balance
            roundingAdjustment = roundedDue - outstanding,
            change = amountTendered - roundedDue,
        )
    }

    /** Stage 5 for confirmed electronic tenders: exact cents, never rounded, no change. */
    fun tenderElectronic(outstanding: Money, amount: Money): Money {
        require(amount > Money.ZERO) { "tender amount must be positive" }
        require(amount <= outstanding) { "electronic tender exceeds outstanding balance" }
        return amount
    }
}

/**
 * One basket line. [taxable] false keeps it out of the tax base (a
 * tax-exempt food item); [depositPerUnit] is its container deposit (CRV) per
 * unit sold, charged through a [Fee.ContainerDeposit].
 */
data class BasketLine(
    val unitPrice: Money,
    val qty: Int,
    val taxable: Boolean = true,
    val depositPerUnit: Money = Money.ZERO,
)

data class Totals(
    val itemsSubtotal: Money,
    val feeLines: List<FeeLine>,
    val grandTotal: Money,
    val taxIncluded: Money,
    val taxVisibleOnReceipt: Boolean,
    /** Discounts + comps taken off the items (never more than the items). */
    val discount: Money = Money.ZERO,
    /** What tax was assessed on: discounted items + taxable fees. */
    val taxableBase: Money = Money.ZERO,
    /** Taxes added on top, one per component; empty for inclusive / no tax. */
    val taxLines: List<TaxLine> = emptyList(),
) {
    /** Taxes added on top of [subtotal]. */
    val taxAdded: Money get() = Money(taxLines.sumOf { it.amount.cents })

    /** Pre-tax: discounted items + fees. subtotal + taxAdded = grandTotal. */
    val subtotal: Money get() = grandTotal - taxAdded

    internal val preTaxTotal: Money get() = itemsSubtotal - discount + Money(feeLines.sumOf { it.amount.cents })
}

data class CashTenderResult(
    val amountApplied: Money,
    /** Signed; what the rounding line on the receipt shows (DOWN mode → ≤ 0). */
    val roundingAdjustment: Money,
    val change: Money,
)
