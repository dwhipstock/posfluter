package dev.dwhipstock.pos.sdk

import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * North Carolina style taxes ([TaxRounding.COMBINED], [TaxDisplay.Combined]):
 * NC sales tax 7.25% + Wake prepared food tax 1%, shown to the guest as one
 * "Tax (8.25%)" line, rounded once at 8.25% and split for remittance. The
 * Québec-style default (each tax on its own) is unchanged.
 */
class CombinedTaxTest {

    private val nc = listOf(
        TaxComponent("NC_SALES", "NC sales tax", "NC sales tax", BigDecimal("7.25"), "", remitTo = "NCDOR"),
        TaxComponent("WAKE_FOOD", "Wake prepared food tax", "Wake prepared food tax", BigDecimal("1"), "", remitTo = "Wake County"),
    )
    private val combined = TaxPolicy.AddedTaxes(nc, rounding = TaxRounding.COMBINED, display = TaxDisplay.Combined())

    private val quebec = TaxPolicy.AddedTaxes(listOf(
        TaxComponent("GST", "TPS", "GST", BigDecimal("5"), "123456789 RT0001"),
        TaxComponent("QST", "TVQ", "QST", BigDecimal("9.975"), "1234567890 TQ0001"),
    ))

    private fun halfUp825(cents: Long): Long =
        BigDecimal(cents).multiply(BigDecimal("8.25")).divide(BigDecimal(100), 0, RoundingMode.HALF_UP).toLong()

    @Test
    fun combinedRoundingRoundsTheSummedRateOnceAndSplitsByLargestRemainder() {
        // 50¢: 8.25% = 4.125 → 4¢ once; rounding each tax on its own would charge 5¢ (3.625 → 4, 0.5 → 1)
        val half = combined.assess(Money(50))
        assertEquals(listOf("NC_SALES" to 4L, "WAKE_FOOD" to 0L), half.lines.map { it.component.code to it.amount.cents })
        assertEquals(Money(4), half.taxAdded)
        assertEquals(Money(5), TaxPolicy.AddedTaxes(nc).assess(Money(50)).taxAdded)
        // $10.00: 0.825 → 0.83 = 0.73 + 0.10; $20.25: 1.670625 → 1.67 = 1.47 + 0.20
        assertEquals(listOf(73L, 10L), combined.assess(Money(1000)).lines.map { it.amount.cents })
        assertEquals(listOf(147L, 20L), combined.assess(Money(2025)).lines.map { it.amount.cents })
        // nothing taxable, nothing owed
        assertEquals(listOf(0L, 0L), combined.assess(Money.ZERO).lines.map { it.amount.cents })
        assertEquals("8.25", combined.combinedRatePercent.stripTrailingZeros().toPlainString())
    }

    @Test
    fun combinedComponentsAlwaysSumToTheRoundedCombinedTaxAndStayWithinACentEach() {
        val rng = Random(20261001)
        repeat(5000) {
            val base = rng.nextLong(0, 10_000_000)
            val a = combined.assess(Money(base))
            assertEquals(halfUp825(base), a.taxAdded.cents, "base $base")
            assertEquals(a.taxAdded.cents, a.lines.sumOf { it.amount.cents })
            for (line in a.lines) {
                val own = line.component.on(Money(base)).cents
                assertTrue(kotlin.math.abs(own - line.amount.cents) <= 1, "base $base ${line.component.code}: $own vs ${line.amount}")
            }
        }
    }

    @Test
    fun perComponentRoundingIsTheDefaultAndUnchanged() {
        assertEquals(TaxRounding.PER_COMPONENT, quebec.rounding)
        assertEquals(TaxDisplay.Itemized, quebec.display)
        assertEquals(TaxDisplay.Itemized, quebec.guestDisplay)
        assertEquals(TaxDisplay.Itemized, TaxPolicy.NoTax.guestDisplay)
        // 40.90: GST 2.045 → 2.05, QST 4.079775 → 4.08, each on its own
        assertEquals(listOf(205L, 408L), quebec.assess(Money(4090)).lines.map { it.amount.cents })
        assertEquals(listOf(50L, 100L), quebec.assess(Money(1000)).lines.map { it.amount.cents })
    }

    @Test
    fun guestTaxLinesCollapseToOneCombinedLineOnlyWhenTheDisplayAsks() {
        val lines = combined.assess(Money(2025)).lines
        val one = guestTaxLines(lines, TaxDisplay.Combined())
        assertEquals(1, one.size)
        assertEquals(COMBINED_TAX_CODE, one[0].component.code)
        assertEquals("8.25", one[0].component.rateText)
        assertEquals(167L, one[0].amount.cents)
        assertEquals(lines.sumOf { it.amount.cents }, one[0].amount.cents)
        assertEquals("", one[0].component.remitTo, "the guest never sees the remittance split")
        // itemised: as they are; a single tax is never relabelled
        assertEquals(lines, guestTaxLines(lines, TaxDisplay.Itemized))
        assertEquals(lines.take(1), guestTaxLines(lines.take(1), TaxDisplay.Combined()))
        assertEquals(emptyList(), guestTaxLines(emptyList(), TaxDisplay.Combined()))
        assertEquals("combined", TaxDisplay.Combined().wire)
        assertEquals("itemized", TaxDisplay.Itemized.wire)
    }

    @Test
    fun theCombinedLineReadsTaxAtTheSummedRateInEveryLanguage() {
        val one = guestTaxLines(combined.assess(Money(1000)).lines, TaxDisplay.Combined()).single().component
        assertEquals("Tax (8.25%)", ReceiptRenderer.taxLineLabel(one, LocaleCode.EN))
        assertEquals("Taxes (8,25 %)", ReceiptRenderer.taxLineLabel(one, LocaleCode.FR))
        assertEquals("Impuesto (8.25%)", ReceiptRenderer.taxLineLabel(one, LocaleCode.ES))
        assertEquals("Steuer (8.25 %)", ReceiptRenderer.taxLineLabel(one, LocaleCode.DE))
        assertEquals("Belasting (8.25%)", ReceiptRenderer.taxLineLabel(one, LocaleCode.AF))
        // a store's own word for it
        val named = guestTaxLines(combined.assess(Money(1000)).lines, TaxDisplay.Combined("Sales tax")).single().component
        assertEquals("Sales tax (8.25%)", ReceiptRenderer.taxLineLabel(named, LocaleCode.EN))
    }

    @Test
    fun aCombinedReceiptPrintsOneTaxLineAndTheTotalIsSubtotalPlusIt() {
        val t = TransactionPipeline.computeTotals(listOf(BasketLine(Money(2050), 1)), 0, NcConfig(combined))
        val receipt = Receipt(
            checkId = 8, tableLabel = "U-1",
            openedAt = LocalDateTime.of(2026, 10, 1, 18, 0), closedAt = LocalDateTime.of(2026, 10, 1, 19, 0),
            items = listOf(ReceiptItem("Poutine", "Poutine", null, null, 1, Money(2050), Money(2050), null)),
            fees = emptyList(), grandTotal = t.grandTotal, taxIncluded = Money.ZERO, taxRatePercent = null,
            tenders = emptyList(), taxes = t.taxLines, taxDisplay = combined.display,
        )
        val rows = PrinterAdapter.renderText(ReceiptRenderer.render(receipt,
            ReceiptPolicy.Standard("Copper Lantern", emptyList(), "Thanks", showTax = false)))
            .lines().map { it.trim().replace(Regex(" {2,}"), " | ") }
        // 20.50 × 8.25% = 1.69125 → 1.69; 20.50 + 1.69 = 22.19
        assertTrue("Subtotal | 20.50" in rows && "Tax (8.25%) | 1.69" in rows && "Total | 22.19" in rows, rows.joinToString("\n"))
        assertTrue(rows.none { "NC sales tax" in it || "Wake" in it }, rows.joinToString("\n"))
        assertEquals(Money(2050 + 169), t.grandTotal)
        assertEquals(listOf(149L, 20L), t.taxLines.map { it.amount.cents })
        // the same receipt itemised: both taxes, each with its rate
        val itemised = PrinterAdapter.renderText(ReceiptRenderer.render(receipt.copy(taxDisplay = TaxDisplay.Itemized),
            ReceiptPolicy.Standard("Copper Lantern", emptyList(), "Thanks", showTax = false)))
            .lines().map { it.trim().replace(Regex(" {2,}"), " | ") }
        assertTrue("NC sales tax 7.25% | 1.49" in itemised && "Wake prepared food tax 1% | 0.20" in itemised, itemised.joinToString("\n"))
    }

    @Test
    fun splitGroupsUnderCombinedRoundingStillAddUpToTheCheck() {
        val cfg = NcConfig(combined)
        val rng = Random(825)
        repeat(300) {
            val groups = (1..rng.nextInt(2, 6)).map {
                TransactionPipeline.computeTotals(
                    (1..rng.nextInt(1, 4)).map { BasketLine(Money(rng.nextLong(1, 5000)), rng.nextInt(1, 3)) },
                    corkageBottles = 0, config = cfg,
                )
            }
            val shared = TransactionPipeline.apportionTax(groups, cfg)
            val base = groups.sumOf { it.taxableBase.cents }
            val whole = combined.assess(Money(base))
            for ((j, line) in whole.lines.withIndex()) assertEquals(line.amount.cents, shared.sumOf { it.taxLines[j].amount.cents })
            assertEquals(halfUp825(base), shared.sumOf { it.taxAdded.cents })
        }
    }
}

/** DB-free config with a given tax policy. */
private class NcConfig(override val taxPolicy: TaxPolicy) : CustomerConfig {
    override val customerId = "test-nc"
    override val displayName = "Test NC"
    override val fees: List<Fee> = emptyList()
    override val roundingPolicy = RoundingPolicy.RoundToUnit(Money(5), RoundingPolicy.RoundToUnit.Mode.NEAREST)
    override val authPolicy = AuthPolicy.PinLogin(4)
    override val receiptPolicy = ReceiptPolicy.Standard("t", emptyList(), "t", showTax = false)
    override val printer = PrinterAdapter.VirtualPrinter("build/tmp/receipts")
    override val electronicTenders = emptyList<TenderMethod>()
    override val publicBaseUrl = "http://localhost:8080"
}
