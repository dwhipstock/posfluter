package dev.dwhipstock.pos.sdk

/**
 * Fees are a first-class SDK concept alongside items, discounts and taxes.
 * Each implementation decides its own trigger + math; the pipeline just asks
 * every configured fee to assess itself against the transaction context.
 */
sealed interface Fee {
    val code: String
    val labelFr: String
    val labelEn: String
    val appliesAt: FeeScope
    val taxable: Boolean
    val receiptVisible: Boolean
    val waivableByManager: Boolean

    /** Return null when the fee doesn't apply to this transaction. */
    fun assess(ctx: FeeContext): FeeLine?

    /** Transaction-level percentage, e.g. 10% service charge. */
    data class ServiceCharge(
        val percent: Int,
        override val taxable: Boolean = true,
        override val receiptVisible: Boolean = true,
        override val waivableByManager: Boolean = true,
    ) : Fee {
        override val code = "service_charge"
        override val labelFr = "Frais de service"
        override val labelEn = "Service charge"
        override val appliesAt = FeeScope.TRANSACTION

        override fun assess(ctx: FeeContext): FeeLine? {
            if (ctx.itemsSubtotal.isZero) return null
            val amount = Money(ctx.itemsSubtotal.cents * percent / 100)
            return FeeLine(code, labelFr, labelEn, amount, taxable, receiptVisible)
        }
    }

    /** Flat per-bottle corkage for brought-in bottles. */
    data class Corkage(
        val perBottle: Money,
        override val taxable: Boolean = true,
        override val receiptVisible: Boolean = true,
        override val waivableByManager: Boolean = true,
        // TODO: size-based corkage = a new Fee implementation, not a knob here
    ) : Fee {
        override val code = "corkage"
        override val labelFr = "Frais de bouchon de bouteille"
        override val labelEn = "Corkage"
        override val appliesAt = FeeScope.TRANSACTION

        override fun assess(ctx: FeeContext): FeeLine? {
            if (ctx.corkageBottles <= 0) return null
            return FeeLine(code, labelFr, labelEn, perBottle * ctx.corkageBottles, taxable, receiptVisible)
        }
    }

    /**
     * A beverage container deposit — California Redemption Value (CRV). Each
     * line carries its own deposit per unit ([Crv.perUnit]); this sums them
     * into one receipt line. Not taxed: a simplifying assumption (California
     * does not tax CRV on most beverages; this store treats it as never taxed).
     */
    data class ContainerDeposit(
        override val code: String = "crv",
        override val labelFr: String = "CRV",
        override val labelEn: String = "CRV",
        override val taxable: Boolean = false,
        override val receiptVisible: Boolean = true,
        override val waivableByManager: Boolean = false,
    ) : Fee {
        override val appliesAt = FeeScope.LINE

        override fun assess(ctx: FeeContext): FeeLine? {
            if (ctx.deposits.cents <= 0) return null
            return FeeLine(code, labelFr, labelEn, ctx.deposits, taxable, receiptVisible)
        }
    }
}

/**
 * California Redemption Value per unit sold: 5¢ per container under 24 oz,
 * 10¢ per container of 24 oz or more, times the containers in the pack
 * (a 6-pack of 12 oz cans = 6 × 5¢ = 30¢). Wine and spirits carry none.
 */
object Crv {
    enum class Size(val perContainerCents: Long) { NONE(0), SMALL(5), LARGE(10) }

    fun size(raw: String?): Size = Size.entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: Size.NONE

    /** A container of [fluidOunces]: SMALL under 24 oz, LARGE at 24 oz or more. */
    fun sizeFor(fluidOunces: Double): Size = if (fluidOunces >= 24.0) Size.LARGE else Size.SMALL

    fun perUnit(size: Size, packUnits: Int): Money = Money(size.perContainerCents * packUnits.coerceAtLeast(1))
}

enum class FeeScope { LINE, TRANSACTION }

data class FeeContext(
    val itemsSubtotal: Money,
    /** Bottles brought in by the customer, set on the check by staff. */
    val corkageBottles: Int,
    /** Container deposits of the basket's lines (per unit × qty), e.g. California CRV. */
    val deposits: Money = Money.ZERO,
)

data class FeeLine(
    val code: String,
    val labelFr: String,
    val labelEn: String,
    val amount: Money,
    val taxable: Boolean,
    val receiptVisible: Boolean,
)
