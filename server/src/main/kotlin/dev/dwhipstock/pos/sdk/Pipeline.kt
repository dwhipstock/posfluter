package dev.dwhipstock.pos.sdk

/**
 * The 5-stage transaction pipeline (architecture.md):
 *
 *   1. Basket build     — line entry (owned by the caller / vertical)
 *   2. Price resolution — base price → overrides → promos (promos TODO: sub-pipeline)
 *   3. Tax + fees       — fees per Fee policy, tax on the post-discount taxable base
 *   4. Total lock       — grand total becomes immutable (a status transition, enforced by the caller)
 *   5. Tender           — split payments, cash rounding line, change calc
 *
 * Stages 2–3 are pure functions here; 4–5 are state transitions the persistence
 * layer drives using [tenderCash] for the math.
 */
object TransactionPipeline {

    /** Stage 2. TODO: manual price overrides + promo sub-pipeline (sequence, base, stacking). */
    fun priceLine(unitPrice: Money, qty: Int): Money = unitPrice * qty

    /** Stages 2+3: full totals for a basket. */
    fun computeTotals(lines: List<BasketLine>, corkageBottles: Int, config: CustomerConfig): Totals {
        val itemsSubtotal = lines.fold(Money.ZERO) { acc, l -> acc + priceLine(l.unitPrice, l.qty) }

        val feeCtx = FeeContext(itemsSubtotal = itemsSubtotal, corkageBottles = corkageBottles)
        val feeLines = config.fees.mapNotNull { it.assess(feeCtx) }
        val feesTotal = feeLines.fold(Money.ZERO) { acc, f -> acc + f.amount }

        val taxableBase = itemsSubtotal + feeLines.filter { it.taxable }.fold(Money.ZERO) { a, f -> a + f.amount }
        val tax = config.taxPolicy.assess(taxableBase)

        return Totals(
            itemsSubtotal = itemsSubtotal,
            feeLines = feeLines,
            grandTotal = itemsSubtotal + feesTotal + tax.taxAdded,
            taxIncluded = tax.taxIncluded,
            taxVisibleOnReceipt = tax.showOnReceipt,
        )
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

data class BasketLine(val unitPrice: Money, val qty: Int)

data class Totals(
    val itemsSubtotal: Money,
    val feeLines: List<FeeLine>,
    val grandTotal: Money,
    val taxIncluded: Money,
    val taxVisibleOnReceipt: Boolean,
)

data class CashTenderResult(
    val amountApplied: Money,
    /** Signed; what the rounding line on the receipt shows (DOWN mode → ≤ 0). */
    val roundingAdjustment: Money,
    val change: Money,
)
