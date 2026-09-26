package dev.dwhipstock.pos.sdk

import dev.dwhipstock.pos.customers.pronghorn.Pronghorn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The promotions engine's math, on Pronghorn's three deals. */
class PromotionsTest {
    private var nextLine = 0

    private fun item(
        itemId: String, price: Long, qty: Int = 1, sub: String? = null, size: String? = null,
        variant: String? = null, taxable: Boolean = true, fuel: Long? = null, category: String? = null,
    ) = PromoItem(++nextLine, itemId, category, sub, size, variant, qty, price, taxable, fuel)

    private fun energy(price: Long, qty: Int = 1) = item("energy-$price", price, qty, sub = "Energy", size = "16 oz can")
    private val hotDog get() = item("ph-hot-dog", 179, sub = "Roller Grill")
    private fun fountain(price: Long, variant: String = "Medium 32 oz") = item("ph-fountain-drink", price, sub = "Fountain", variant = variant)
    private fun coffee(price: Long) = item("ph-coffee", price, sub = "Coffee", variant = "Medium 16 oz")
    private fun fuel(volumeMilli: Long) = item("fuel-reg", 2899, category = "fuel", taxable = false, fuel = volumeMilli)

    private fun apply(vararg items: PromoItem) = Promotions.apply(Pronghorn.PROMOTIONS, items.toList())

    @Test
    fun twoForFiveOnEnergyDrinksPairsTheDearestCansFirst() {
        // 3.49 + 3.29 → 5.00 (1.78 off); the 2.99 has no partner
        val hits = apply(energy(349), energy(299), energy(329))
        assertEquals(1, hits.size)
        assertEquals("energy-2for5", hits[0].code)
        assertEquals(178, hits[0].amount.cents)
        assertEquals(178, hits[0].taxableAmount.cents, "energy drinks are taxable")
        // two pairs out of one line of 4
        val four = apply(energy(329, qty = 4))
        assertEquals(2, four.size)
        assertEquals(2 * (658 - 500), four.sumOf { it.amount.cents })
        // a single can: no deal
        assertTrue(apply(energy(349)).isEmpty())
    }

    @Test
    fun theComboIsAHotDogAndAFountainDrinkForThree() {
        val hits = apply(hotDog, fountain(189))
        assertEquals(listOf("hotdog-fountain"), hits.map { it.code })
        assertEquals(179 + 189 - 300, hits.single().amount.cents)
        // two hot dogs, one drink: one combo
        assertEquals(1, apply(hotDog, hotDog, fountain(159)).size)
        // a refill doesn't make a combo
        assertTrue(apply(hotDog, fountain(79, variant = "Refill")).isEmpty())
        // no drink, no combo
        assertTrue(apply(hotDog).isEmpty())
    }

    @Test
    fun aDollarOffCoffeeNeedsEightGallonsOfFuel() {
        assertTrue(apply(coffee(179), fuel(7_999)).isEmpty(), "7.999 gal isn't enough")
        val hit = apply(coffee(179), fuel(8_000)).single()
        assertEquals("coffee-fuel", hit.code)
        assertEquals(100, hit.amount.cents)
        // once per sale, on the dearest cup
        val two = apply(coffee(149), coffee(209), fuel(12_000))
        assertEquals(1, two.size)
        assertEquals(two.single().lineIds.size, 1)
        // no fuel line: no deal; fuel itself is never discounted
        assertTrue(apply(coffee(179)).isEmpty())
        assertTrue(apply(fuel(20_000)).isEmpty())
    }

    @Test
    fun aUnitIsUsedByOneDealOnly() {
        // the fountain drink goes to the combo; the energy pair still applies
        val hits = apply(hotDog, fountain(159), energy(299), energy(299), coffee(179), fuel(9_000))
        assertEquals(setOf("energy-2for5", "hotdog-fountain", "coffee-fuel"), hits.map { it.code }.toSet())
        assertEquals(98 + (179 + 159 - 300) + 100, hits.sumOf { it.amount.cents })
    }

    @Test
    fun promotionsComeOffBeforeTaxOnTheirOwnGoods() {
        val config = FakeConfig()
        // $6.58 of energy drinks (taxable) + $3.00 of chips (not): 2 for $5 → tax on $5.00
        val lines = listOf(
            BasketLine(Money(329), 2, taxable = true),
            BasketLine(Money(300), 1, taxable = false),
        )
        val hit = PromoHit("energy-2for5", "2 for \$5", "2 por \$5", Money(158), Money(158), listOf(1))
        val t = TransactionPipeline.computeTotals(lines, 0, config, promotions = listOf(hit))
        assertEquals(Money(500), t.taxableBase)
        assertEquals(Money(158), t.discount)
        assertEquals(Money(958 - 158 + 41), t.grandTotal) // 8.25% of 5.00 = 0.4125 → 0.41
    }

    /** Just enough config for the pipeline: Texas sales tax, no fees. */
    private class FakeConfig : CustomerConfig {
        override val customerId = "test"
        override val displayName = "test"
        override val taxPolicy = TaxPolicy.AddedTaxes(listOf(Pronghorn.salesTax()))
        override val roundingPolicy: RoundingPolicy = RoundingPolicy.NoRounding
        override val fees = emptyList<Fee>()
        override val authPolicy = AuthPolicy.PinLogin()
        override val receiptPolicy: ReceiptPolicy = ReceiptPolicy.Standard("", emptyList(), "", false)
        override val printer: PrinterAdapter get() = error("no printer")
        override val electronicTenders = emptyList<TenderMethod>()
        override val publicBaseUrl = ""
    }
}
