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
}

enum class FeeScope { LINE, TRANSACTION }

data class FeeContext(
    val itemsSubtotal: Money,
    /** Bottles brought in by the customer, set on the check by staff. */
    val corkageBottles: Int,
)

data class FeeLine(
    val code: String,
    val labelFr: String,
    val labelEn: String,
    val amount: Money,
    val taxable: Boolean,
    val receiptVisible: Boolean,
)
