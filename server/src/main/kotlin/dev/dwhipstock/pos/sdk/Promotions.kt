package dev.dwhipstock.pos.sdk

/**
 * A small promotions engine: deals a store runs on its shelf, applied to the
 * basket before tax (pipeline stage 2) and shown as discount lines on the
 * basket and the receipt. Three kinds, which cover a c-store's counter:
 *
 * - [MixAndMatch]: any [MixAndMatch.qty] of the matching products for a set
 *   price ("2 for $5" energy drinks), the dearest units first;
 * - [Combo]: one of each part for a set price (a hot dog + any fountain drink);
 * - [WithFuel]: money off a product when the sale has at least so many gallons
 *   of fuel on it ("$1 off a coffee with 8+ gallons").
 *
 * Deterministic and order-stable: promotions apply in the store's order, and a
 * unit a promotion used is not used again. Each hit says how much of it came
 * off taxable goods, so sales tax is on what the customer actually pays.
 */
sealed interface Promotion {
    val code: String
    val label: String
    val labelEs: String

    /** The hits this promotion makes on [units] not yet [used] (unit keys). */
    fun apply(units: List<PromoUnit>, used: MutableSet<String>, fuelVolumeMilli: Long): List<PromoHit>

    data class MixAndMatch(
        override val code: String,
        override val label: String,
        override val labelEs: String,
        val qty: Int,
        val priceCents: Long,
        val matches: (PromoItem) -> Boolean,
    ) : Promotion {
        init { require(qty >= 2 && priceCents > 0) }

        override fun apply(units: List<PromoUnit>, used: MutableSet<String>, fuelVolumeMilli: Long): List<PromoHit> {
            val pool = units.filter { it.key !in used && matches(it.item) }.sortedByDescending { it.item.unitPriceCents }
            return pool.chunked(qty).filter { it.size == qty }.mapNotNull { group ->
                val gross = group.sumOf { it.item.unitPriceCents }
                if (gross <= priceCents) return@mapNotNull null
                used += group.map { it.key }
                PromoHit.of(this, group, gross - priceCents)
            }
        }
    }

    data class Combo(
        override val code: String,
        override val label: String,
        override val labelEs: String,
        val priceCents: Long,
        val parts: List<(PromoItem) -> Boolean>,
    ) : Promotion {
        init { require(parts.size >= 2 && priceCents > 0) }

        override fun apply(units: List<PromoUnit>, used: MutableSet<String>, fuelVolumeMilli: Long): List<PromoHit> {
            val hits = mutableListOf<PromoHit>()
            while (true) {
                val taken = mutableListOf<PromoUnit>()
                for (part in parts) {
                    val u = units.filter { it.key !in used && it !in taken && part(it.item) }
                        .maxByOrNull { it.item.unitPriceCents } ?: return hits
                    taken += u
                }
                val gross = taken.sumOf { it.item.unitPriceCents }
                if (gross <= priceCents) return hits
                used += taken.map { it.key }
                hits += PromoHit.of(this, taken, gross - priceCents)
            }
        }
    }

    data class WithFuel(
        override val code: String,
        override val label: String,
        override val labelEs: String,
        val minVolumeMilli: Long,
        val offCents: Long,
        val maxUses: Int = 1,
        val matches: (PromoItem) -> Boolean,
    ) : Promotion {
        override fun apply(units: List<PromoUnit>, used: MutableSet<String>, fuelVolumeMilli: Long): List<PromoHit> {
            if (fuelVolumeMilli < minVolumeMilli) return emptyList()
            return units.filter { it.key !in used && matches(it.item) }
                .sortedByDescending { it.item.unitPriceCents }.take(maxUses)
                .map { u ->
                    used += u.key
                    PromoHit.of(this, listOf(u), minOf(offCents, u.item.unitPriceCents))
                }
        }
    }
}

/** A basket line as promotions see it: what it is and what one costs. */
data class PromoItem(
    val lineId: Int,
    val itemId: String?,
    val category: String?,
    val subcategory: String?,
    val size: String?,
    val variantLabel: String?,
    val qty: Int,
    val unitPriceCents: Long,
    val taxable: Boolean,
    /** A fuel line's gallons (thousandths); null on anything else. */
    val fuelVolumeMilli: Long? = null,
)

/** One unit of a line (a line of 3 is three units). */
data class PromoUnit(val item: PromoItem, val n: Int) {
    val key: String get() = "${item.lineId}#$n"
}

/** A promotion that applied: its discount and how much of it was on taxable goods. */
data class PromoHit(
    val code: String,
    val label: String,
    val labelEs: String,
    val amount: Money,
    val taxableAmount: Money,
    val lineIds: List<Int>,
) {
    companion object {
        /** [discount] spread over [units] by price; the taxable share is what fell on taxable units. */
        fun of(p: Promotion, units: List<PromoUnit>, discount: Long): PromoHit {
            val weights = units.map { it.item.unitPriceCents }
            val shares = TransactionPipeline.allocate(discount, weights)
            val taxable = units.indices.filter { units[it].item.taxable }.sumOf { shares[it] }
            return PromoHit(p.code, p.label, p.labelEs, Money(discount), Money(taxable), units.map { it.item.lineId }.distinct())
        }
    }
}

object Promotions {
    /** Every promotion in [promos] against [items], in order. Fuel lines count only towards [Promotion.WithFuel]. */
    fun apply(promos: List<Promotion>, items: List<PromoItem>): List<PromoHit> {
        if (promos.isEmpty()) return emptyList()
        val fuel = items.sumOf { it.fuelVolumeMilli ?: 0 }
        val units = items.filter { it.fuelVolumeMilli == null && it.category != "fuel" && it.qty > 0 }
            .flatMap { i -> (1..i.qty).map { PromoUnit(i, it) } }
        val used = mutableSetOf<String>()
        return promos.flatMap { it.apply(units, used, fuel) }
    }
}
