package dev.dwhipstock.pos.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * DB-free stand-in with CopperLantern's policy choices. The pipeline reads only
 * policies + fees, so tests parameterize fees directly instead of standing up
 * a SettingsRepository.
 */
private class PipelineTestConfig(override val fees: List<Fee> = listOf(Fee.Corkage(Money.cad(100)))) : CustomerConfig {
    override val customerId = "test"
    override val displayName = "Test Venue"
    override val taxPolicy = TaxPolicy.InclusiveTax(ratePercent = 13, showOnReceipt = false)
    override val roundingPolicy = RoundingPolicy.RoundToUnit(Money.cad(1), RoundingPolicy.RoundToUnit.Mode.DOWN)
    override val authPolicy = AuthPolicy.PinLogin(4)
    override val receiptPolicy = ReceiptPolicy.Standard("t", emptyList(), "t", showTax = false)
    override val printer = PrinterAdapter.VirtualPrinter("build/tmp/receipts")
    override val electronicTenders = emptyList<TenderMethod>()
    override val publicBaseUrl = "http://localhost:8080"
}

private val CopperLanternLikeConfig = PipelineTestConfig()

class PipelineTest {

    @Test
    fun inclusiveTaxComputesOutOfBaseWithoutChangingTotal() {
        val totals = TransactionPipeline.computeTotals(
            listOf(BasketLine(Money.cad(1000), 1)), corkageBottles = 0, config = CopperLanternLikeConfig,
        )
        assertEquals(Money.cad(1000), totals.grandTotal) // inclusive: total unchanged
        assertEquals(Money(11504), totals.taxIncluded)     // 100000 * 13/113 ≈ 6542 cents
        assertTrue(!totals.taxVisibleOnReceipt)           // hidden for CopperLantern
    }

    @Test
    fun corkageAppliesPerBottleAndServiceChargeIsOffForCopperLantern() {
        val totals = TransactionPipeline.computeTotals(
            listOf(BasketLine(Money.cad(550), 1)), corkageBottles = 2, config = CopperLanternLikeConfig,
        )
        assertEquals(listOf("corkage"), totals.feeLines.map { it.code })
        assertEquals(Money.cad(200), totals.feeLines[0].amount)
        assertEquals(Money.cad(750), totals.grandTotal)
    }

    @Test
    fun serviceChargeImplementationWorksEvenThoughCopperLanternHasItOff() {
        val config = PipelineTestConfig(fees = listOf(Fee.ServiceCharge(percent = 10)))
        val totals = TransactionPipeline.computeTotals(
            listOf(BasketLine(Money.cad(1000), 1)), corkageBottles = 0, config = config,
        )
        assertEquals(Money.cad(100), totals.feeLines.single().amount)
        assertEquals(Money.cad(1100), totals.grandTotal)
    }

    @Test
    fun cashRoundingDropsCentsOnTenderOnlyGrandTotalStaysExact() {
        // $215.50 outstanding, $300 cash: due rounds DOWN to $215, change $85
        val result = TransactionPipeline.tenderCash(Money(21550), Money.cad(300), CopperLanternLikeConfig)
        assertEquals(Money(21550), result.amountApplied)      // exact balance cleared
        assertEquals(Money(-50), result.roundingAdjustment)   // rounding line on the cash tender
        assertEquals(Money.cad(85), result.change)
    }

    @Test
    fun exactWholeCADCashNeedsNoRounding() {
        val result = TransactionPipeline.tenderCash(Money.cad(215), Money.cad(220), CopperLanternLikeConfig)
        assertEquals(Money.ZERO, result.roundingAdjustment)
        assertEquals(Money.cad(5), result.change)
    }

    @Test
    fun partialCashAppliesAtFaceValueNoRoundingNoChange() {
        // $200 toward a $215.50 balance: not a settlement, no rounding fires
        val result = TransactionPipeline.tenderCash(Money(21550), Money.cad(200), CopperLanternLikeConfig)
        assertEquals(Money.cad(200), result.amountApplied)
        assertEquals(Money.ZERO, result.roundingAdjustment)
        assertEquals(Money.ZERO, result.change)
    }

    @Test
    fun electronicTenderIsExactCentsAndBounded() {
        assertEquals(Money(21550), TransactionPipeline.tenderElectronic(Money(21550), Money(21550)))
        assertEquals(Money.cad(100), TransactionPipeline.tenderElectronic(Money(21550), Money.cad(100)))
        assertFailsWith<IllegalArgumentException> {
            TransactionPipeline.tenderElectronic(Money.cad(100), Money.cad(200))
        }
    }

    @Test
    fun nearestModeRoundsHalfUp() {
        val nearest = RoundingPolicy.RoundToUnit(Money.cad(1), RoundingPolicy.RoundToUnit.Mode.NEAREST)
        assertEquals(Money.cad(216), nearest.roundCashDue(Money(21550)))
        assertEquals(Money.cad(215), nearest.roundCashDue(Money(21549)))
    }
    @Test
      fun multiItemMixedQuantityTotalsSumAcrossLines() {
          val totals = TransactionPipeline.computeTotals(
              listOf(
                  BasketLine(Money.cad(120), 3), // $360
                  BasketLine(Money.cad(80), 2),  // $160
                  BasketLine(Money.cad(20), 1),  // $20
              ),
              corkageBottles = 0,
              config = CopperLanternLikeConfig,
          )
          assertEquals(Money.cad(540), totals.itemsSubtotal) // 360 + 160 + 20
          assertEquals(Money.cad(540), totals.grandTotal)    // inclusive tax: total unchanged
          assertEquals(Money(6212), totals.taxIncluded)       // 54000 * 13/113, half-up
          assertTrue(totals.feeLines.isEmpty())               // corkage null at 0 bottles
      }
      @Test
      fun cashTenderRequiresPositiveAmount() {
          assertFailsWith<IllegalArgumentException> {
              TransactionPipeline.tenderCash(Money(21550), Money.ZERO, CopperLanternLikeConfig)
          }
          assertFailsWith<IllegalArgumentException> {
              TransactionPipeline.tenderCash(Money(21550), Money(-500), CopperLanternLikeConfig)
          }
      }
@Test
      fun corkageIsTaxableSoItRaisesTheInclusiveTaxBase() {
          val totals = TransactionPipeline.computeTotals(
              listOf(BasketLine(Money.cad(300), 1)), // $300 food
              corkageBottles = 2,                     // 2 brought-in bottles
              config = CopperLanternLikeConfig,
          )
          assertEquals(Money.cad(300), totals.itemsSubtotal)
          assertEquals(Money.cad(200), totals.feeLines.single().amount) // $100 x 2
          assertEquals(Money.cad(500), totals.grandTotal)               // 300 + 200 corkage
          assertEquals(Money(5752), totals.taxIncluded)                  // 13% included on food and corkage
      }
      @Test
      fun splitTenderRoundsOnlyTheSettlingCashPayment() {
          // $215.50 owed; $100 by Card (exact), remainder settled in cash.
          val electronic = TransactionPipeline.tenderElectronic(Money(21550), Money.cad(100))
          assertEquals(Money.cad(100), electronic) // electronic is exact - no rounding

          val remaining = Money(21550) - electronic // $115.50 still owed
          val cash = TransactionPipeline.tenderCash(remaining, Money.cad(200), CopperLanternLikeConfig)
          assertEquals(Money(11550), cash.amountApplied)    // clears the exact balance
          assertEquals(Money(-50), cash.roundingAdjustment) // 115.50 -> 115, cash line only
          assertEquals(Money.cad(85), cash.change)         // 200 - 115
      }
}
