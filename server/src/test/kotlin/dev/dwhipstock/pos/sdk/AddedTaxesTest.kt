package dev.dwhipstock.pos.sdk

import dev.dwhipstock.pos.customers.copperlantern.CopperLanternConfig
import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import java.time.LocalDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** DB-free config with Copper Lantern's Québec taxes (GST 5% + QST 9.975% on top). */
private class QuebecConfig(override val fees: List<Fee> = listOf(Fee.Corkage(Money.cad(25)))) : CustomerConfig {
    override val customerId = "test"
    override val displayName = "Test Venue"
    override val taxPolicy = TaxPolicy.AddedTaxes(CopperLanternConfig.QUEBEC_TAXES)
    override val roundingPolicy = RoundingPolicy.RoundToUnit(Money(5), RoundingPolicy.RoundToUnit.Mode.NEAREST)
    override val authPolicy = AuthPolicy.PinLogin(4)
    override val receiptPolicy = ReceiptPolicy.Standard("t", emptyList(), "t", showTax = false)
    override val printer = PrinterAdapter.VirtualPrinter("build/tmp/receipts")
    override val electronicTenders = emptyList<TenderMethod>()
    override val publicBaseUrl = "http://localhost:8080"
}

class AddedTaxesTest {

    private val config = QuebecConfig()

    private fun totals(vararg cents: Long, corkage: Int = 0, discount: Money = Money.ZERO) =
        TransactionPipeline.computeTotals(cents.map { BasketLine(Money(it), 1) }, corkage, config, discount)

    private fun Totals.taxCents() = taxLines.map { it.component.code to it.amount.cents }

    @Test
    fun tenDollarsGivesGstFiftyCentsQstOneDollarTotalElevenFifty() {
        val t = totals(1000)
        assertEquals(Money(1000), t.subtotal)
        // GST 5% = 0.50; QST 9.975% = 0.9975 → 1.00 (half-up at the cent)
        assertEquals(listOf("GST" to 50L, "QST" to 100L), t.taxCents())
        assertEquals(Money(150), t.taxAdded)
        assertEquals(Money(1150), t.grandTotal)
        assertEquals(Money.ZERO, t.taxIncluded)
        assertTrue(t.taxVisibleOnReceipt)
    }

    @Test
    fun eachTaxRoundsHalfUpOnceOnTheWholeCheckNeverPerLine() {
        // 40.90: GST 2.045 → 2.05 (exact half rounds up), QST 4.079775 → 4.08
        assertEquals(listOf("GST" to 205L, "QST" to 408L), totals(4090).taxCents())
        // three 0.10 lines: per-line rounding would give 3 × 0.01 QST; the check gives 0.03 (0.029925)
        assertEquals(listOf("GST" to 2L, "QST" to 3L), totals(10, 10, 10).taxCents())
        // one cent: GST 0.0005 → 0, QST 0.0009975 → 0
        assertEquals(listOf("GST" to 0L, "QST" to 0L), totals(1).taxCents())
        // QST is on the pre-tax base, never on GST (no compounding)
        assertEquals(listOf("GST" to 500L, "QST" to 998L), totals(10000).taxCents())
    }

    @Test
    fun discountComesOffBeforeTaxAndNeverBelowZero() {
        val t = totals(1500, 500, discount = Money(500))
        assertEquals(Money(2000), t.itemsSubtotal)
        assertEquals(Money(500), t.discount)
        assertEquals(Money(1500), t.taxableBase)
        // tax on 15.00, not 20.00: GST 0.75, QST 1.49625 → 1.50
        assertEquals(listOf("GST" to 75L, "QST" to 150L), t.taxCents())
        assertEquals(Money(1500), t.subtotal)
        assertEquals(Money(1725), t.grandTotal)

        // a comp of the whole basket: nothing taxable, nothing owed
        val comped = totals(1500, discount = Money(9999))
        assertEquals(Money(1500), comped.discount)
        assertEquals(Money.ZERO, comped.grandTotal)
        assertFailsWith<IllegalArgumentException> { totals(1500, discount = Money(-1)) }
    }

    @Test
    fun feesAreTaxedButATipLeftFromCashIsNot() {
        // corkage is a taxable fee: 10.00 + 25.00 corkage = 35.00 taxable
        val t = totals(1000, corkage = 1)
        assertEquals(Money(3500), t.taxableBase)
        assertEquals(listOf("GST" to 175L, "QST" to 349L), t.taxCents()) // 3.49125 → 3.49
        assertEquals(Money(4024), t.grandTotal)

        // the guest hands over $50 and leaves the change as a tip: the tip is
        // tender-side money (change), never part of the basket, so tax is unchanged
        val cash = TransactionPipeline.tenderCash(t.grandTotal, Money(5000), config)
        assertEquals(t.grandTotal, cash.amountApplied)
        assertEquals(Money(1), cash.roundingAdjustment) // 40.24 → 40.25 at the nickel
        assertEquals(Money(975), cash.change)
        assertEquals(listOf("GST" to 175L, "QST" to 349L), totals(1000, corkage = 1).taxCents())
    }

    @Test
    fun splitGroupsShareTheCheckTaxAndSumExactlyToTheCheckTotal() {
        val rng = Random(20260925)
        repeat(500) {
            val groups = (1..rng.nextInt(2, 7)).map {
                TransactionPipeline.computeTotals(
                    (1..rng.nextInt(0, 5)).map { BasketLine(Money(rng.nextLong(1, 5000)), rng.nextInt(1, 4)) },
                    corkageBottles = 0, config = config,
                )
            }
            val shared = TransactionPipeline.apportionTax(groups, config)
            val whole = config.taxPolicy.assess(Money(groups.sumOf { it.taxableBase.cents }))
            // each tax: the groups' shares add up to the check-level tax, to the cent
            for ((j, line) in whole.lines.withIndex()) {
                assertEquals(line.amount.cents, shared.sumOf { it.taxLines[j].amount.cents })
            }
            assertEquals(
                groups.sumOf { it.subtotal.cents } + whole.taxAdded.cents,
                shared.sumOf { it.grandTotal.cents },
            )
            shared.forEach { assertEquals(it.grandTotal, it.subtotal + it.taxAdded) }
        }
    }

    @Test
    fun evenSharesCarryTheirPartOfTheTaxAndAddBackUp() {
        val check = totals(3135) // 31.35 → GST 1.57, QST 3.13, total 36.05
        assertEquals(Money(3605), check.grandTotal)
        val shares = listOf(Money(1203), Money(1201), Money(1201))
        val parts = TransactionPipeline.taxOfShares(check, shares)
        assertEquals(listOf(listOf(53L, 105L), listOf(52L, 104L), listOf(52L, 104L)),
            parts.map { (_, lines) -> lines.map { it.amount.cents } })
    }

    @Test
    fun allocateAlwaysSumsAndFavoursTheLargestRemainder() {
        assertEquals(listOf(34L, 33L, 33L), TransactionPipeline.allocate(100, listOf(1, 1, 1)))
        assertEquals(listOf(205L, 77L), TransactionPipeline.allocate(282, listOf(4090, 1545)))
        assertEquals(listOf(7L, 0L), TransactionPipeline.allocate(7, listOf(0, 0)))
        assertEquals(emptyList(), TransactionPipeline.allocate(7, emptyList()))
        val rng = Random(7)
        repeat(1000) {
            val weights = List(rng.nextInt(1, 8)) { rng.nextLong(0, 100_000) }
            val total = rng.nextLong(0, 1_000_000)
            assertEquals(total, TransactionPipeline.allocate(total, weights).sum())
        }
    }

    @Test
    fun taxCodesMustBeUnique() {
        val gst = CopperLanternConfig.QUEBEC_TAXES.first()
        assertFailsWith<IllegalArgumentException> { TaxPolicy.AddedTaxes(listOf(gst, gst)) }
    }

    @Test
    fun receiptItemisesEachTaxWithItsRateAndRegistrationNumberInBothLanguages() {
        val t = totals(1000)
        val receipt = Receipt(
            checkId = 7, tableLabel = "U-1",
            openedAt = LocalDateTime.of(2026, 9, 25, 18, 0), closedAt = LocalDateTime.of(2026, 9, 25, 19, 0),
            items = listOf(ReceiptItem("Poutine classique", "Classic Poutine", null, null, 1, Money(1000), Money(1000), null)),
            fees = emptyList(), grandTotal = t.grandTotal, taxIncluded = Money.ZERO, taxRatePercent = null,
            tenders = emptyList(), taxes = t.taxLines,
        )
        val policy = ReceiptPolicy.Standard("Copper Lantern", emptyList(), "Merci", showTax = false)
        fun rows(locale: LocaleCode) = PrinterAdapter.renderText(ReceiptRenderer.render(receipt, policy.withLocale(locale)))
            .lines().map { it.trim().replace(Regex(" {2,}"), " | ") }

        val en = rows(LocaleCode.EN)
        for (row in listOf("Subtotal | 10", "GST/TPS 5% | 0.50", "QST/TVQ 9.975% | 1", "Total | 11.50",
                "GST/TPS no. 123456789 RT0001", "QST/TVQ no. 1234567890 TQ0001")) {
            assertTrue(row in en, "missing '$row' in\n${en.joinToString("\n")}")
        }
        // subtotal, taxes, then the total, in that order
        assertTrue(en.indexOf("Subtotal | 10") < en.indexOf("GST/TPS 5% | 0.50"))
        assertTrue(en.indexOf("QST/TVQ 9.975% | 1") < en.indexOf("Total | 11.50"))

        val fr = rows(LocaleCode.FR)
        for (row in listOf("Sous-total | 10", "TPS/GST 5\u00A0% | 0,50", "TVQ/QST 9,975\u00A0% | 1", "Total | 11,50",
                "N°\u00A0TPS/GST\u00A0: 123456789 RT0001", "N°\u00A0TVQ/QST\u00A0: 1234567890 TQ0001")) {
            assertTrue(row in fr, "missing '$row' in\n${fr.joinToString("\n")}")
        }
    }
}
