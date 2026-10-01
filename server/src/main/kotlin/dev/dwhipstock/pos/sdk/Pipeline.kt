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
        /** Promotions that applied (stage 2): taken off before tax, each on its own goods. */
        promotions: List<PromoHit> = emptyList(),
    ): Totals {
        val itemsSubtotal = lines.fold(Money.ZERO) { acc, l -> acc + priceLine(l.unitPrice, l.qty) }
        require(discount >= Money.ZERO) { "discount must not be negative" }
        val promoOff = Money(promotions.sumOfExact { it.amount.cents })
        val promoTaxableOff = Money(promotions.sumOfExact { it.taxableAmount.cents })
        val discountApplied = minOf(discount + promoOff, itemsSubtotal)
        val discounted = itemsSubtotal - discountApplied
        // tax-exempt lines (retail snacks, ice) stay out of the taxable base;
        // a plain discount comes off the taxable goods first; a promotion
        // comes off the goods it was on (its taxable share). Every pub line is
        // taxable, so there this is exactly the discounted items.
        val exempt = lines.filterNot { it.taxable }.fold(Money.ZERO) { acc, l -> acc + priceLine(l.unitPrice, l.qty) }
        val taxableGross = itemsSubtotal - exempt
        val taxableItems = maxOf(Money.ZERO, taxableGross - promoTaxableOff - minOf(discount, taxableGross))
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
            promotions = promotions,
        )
    }

    /**
     * Tax on a by-item split. Each group's basket and fees go through
     * [computeTotals] on their own, but tax is a CHECK-level figure: it is
     * assessed once on the summed taxable base (one half-up rounding per tax,
     * like the unsplit check), then shared out to the groups in proportion to
     * each group's taxable base (largest remainder). The group totals therefore
     * sum exactly to the check total.
     *
     * The groups' TOTAL tax is shared out first and each group's total split
     * into the taxes after ([allocateTaxLines]): two guests with the same
     * items pay the same, not 1¢ apart because each tax's leftover cent went
     * to the first guest (red team 2026-10-01).
     */
    fun apportionTax(groups: List<Totals>, config: CustomerConfig): List<Totals> {
        if (groups.isEmpty()) return groups
        val check = config.taxPolicy.assess(Money(groups.sumOfExact { it.taxableBase.cents }))
        val weights = groups.map { it.taxableBase.cents }
        val included = allocate(check.taxIncluded.cents, weights)
        val perLine = allocateTaxLines(check.lines.map { it.amount.cents }, weights)
        return groups.mapIndexed { i, g ->
            val lines = check.lines.mapIndexed { j, line -> TaxLine(line.component, Money(perLine[j][i])) }
            val added = Money(lines.sumOfExact { it.amount.cents })
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
     * Several taxes ([taxes], one total each) shared over [weights]: result
     * [tax][group]. Each tax's row sums to that tax, and each group's taxes
     * sum to its share of the combined tax (largest remainder over the
     * combined total), so equal weights get equal tax whenever the combined
     * total divides. The last tax takes what is left of each group's share;
     * if that would ever go below zero, every tax is shared on its own.
     */
    fun allocateTaxLines(taxes: List<Long>, weights: List<Long>): List<List<Long>> {
        if (taxes.size < 2) return taxes.map { allocate(it, weights) }
        val perGroup = allocate(taxes.sumExact(), weights)
        val head = taxes.dropLast(1).map { allocate(it, weights) }
        val last = weights.indices.map { i -> perGroup[i] - head.sumOfExact { it[i] } }
        if (last.any { it < 0 }) return taxes.map { allocate(it, weights) }
        return head + listOf(last)
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
        val sum = weights.sumExact()
        if (sum == 0L) return weights.indices.map { if (it == 0) total else 0L }
        val exact = weights.map { java.math.BigInteger.valueOf(total).multiply(java.math.BigInteger.valueOf(it)) }
        val divisor = java.math.BigInteger.valueOf(sum)
        val floors = exact.map { it.divide(divisor).toLong() }.toMutableList()
        var left = total - floors.sumExact()
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
     * total stays exact; electronic tenders settle exact cents. The store's
     * [CustomerConfig.roundingPolicy] is the nickel ([CashRounding]) unless
     * cash rounding is off.
     *
     * Split-tender rule: rounding fires only when this cash payment SETTLES the
     * check (covers the rounded outstanding). A partial cash payment applies at
     * face value — the eventual final payment, whatever its type, deals with
     * the remainder (and gets the rounding if it's cash).
     */
    fun tenderCash(
        outstanding: Money, amountTendered: Money, config: CustomerConfig,
        /** What settling [outstanding] in cash takes, when the caller knows better (an even split's share). */
        cashDue: Money? = null,
    ): CashTenderResult {
        val roundedDue = cashDue ?: config.roundingPolicy.roundCashDue(outstanding)
        // the drawer gives at most MAX_CASH_OVER_DUE in change: a fat-fingered
        // amount is refused, never recorded (it broke the X / Z reports)
        require(amountTendered.cents <= roundedDue.cents + MoneyLimits.MAX_CASH_OVER_DUE_CENTS) {
            "cash tendered is more than ${MoneyLimits.MAX_CASH_OVER_DUE_CENTS} cents over the amount due"
        }
        // a balance of 0.01 or 0.02 rounds to nothing in cash: settling it takes no coins
        require(amountTendered > Money.ZERO || (amountTendered.isZero && roundedDue.isZero)) {
            "cash amount must be positive"
        }
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

    /**
     * The cash each guest of an even ÷N split pays: the bill is rounded ONCE.
     * Share i pays round(shares 1..i) − round(shares 1..i−1), so every cash
     * share is payable in nickels, stays within a few cents of the exact
     * share, and the cash shares add up to exactly what paying the whole bill
     * in cash would take — never more (red team 2026-10-01: a 7-way split
     * collected 15¢ more than the bill when each guest rounded on their own).
     */
    fun evenSplitCashDue(shares: List<Money>, policy: RoundingPolicy): List<Money> {
        var running = 0L
        var roundedBefore = 0L
        return shares.map { s ->
            running = Math.addExact(running, s.cents)
            val roundedNow = policy.roundCashDue(Money(running)).cents
            Money(roundedNow - roundedBefore).also { roundedBefore = roundedNow }
        }
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
    /** Promotions inside [discount] (their discount lines). */
    val promotions: List<PromoHit> = emptyList(),
) {
    /** Taxes added on top of [subtotal]. */
    val taxAdded: Money get() = Money(taxLines.sumOfExact { it.amount.cents })

    /** Pre-tax: discounted items + fees. subtotal + taxAdded = grandTotal. */
    val subtotal: Money get() = grandTotal - taxAdded

    internal val preTaxTotal: Money get() = itemsSubtotal - discount + Money(feeLines.sumOfExact { it.amount.cents })
}

data class CashTenderResult(
    val amountApplied: Money,
    /** Signed; what the rounding line on the receipt shows (DOWN mode → ≤ 0). */
    val roundingAdjustment: Money,
    val change: Money,
)
