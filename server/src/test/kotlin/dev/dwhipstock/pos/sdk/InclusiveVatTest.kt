package dev.dwhipstock.pos.sdk

import dev.dwhipstock.pos.restaurant.taxLinesFromJson
import dev.dwhipstock.pos.restaurant.taxLinesToJson
import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A store in baht with a 7% VAT inside its menu prices: the money format,
 * cash rounded down to the whole baht, and the included VAT as a first-class
 * tax line through service charge, discounts, splits and receipts. Prices
 * and amounts are satang in the cents fields.
 */
class InclusiveVatTest {

    private val vat = TaxComponent("VAT", "TVA", "VAT", BigDecimal("7"), registrationNumber = "")

    private class VatConfig(
        override val taxPolicy: TaxPolicy,
        override val fees: List<Fee> = emptyList(),
    ) : CustomerConfig {
        override val customerId = "vat-test"
        override val displayName = "VAT test store"
        override val roundingPolicy = CashRounding.defaultFor("THB").policy
        override val authPolicy = AuthPolicy.PinLogin(4)
        override val receiptPolicy = ReceiptPolicy.Standard("t", emptyList(), "t", showTax = false)
        override val printer = PrinterAdapter.VirtualPrinter("build/tmp/receipts")
        override val electronicTenders = emptyList<TenderMethod>()
        override val publicBaseUrl = "http://localhost:8080"
        override val profile = StoreProfile(
            country = "TH", currency = "THB", locales = listOf(LocaleCode.EN), timeZone = "Etc/GMT-7",
        )
    }

    private fun config(show: Boolean = false, fees: List<Fee> = emptyList()) =
        VatConfig(TaxPolicy.InclusiveTax(vat, showOnReceipt = show), fees)

    // ---- money format ----

    @Test
    fun bahtShowsTheSymbolFirstAndSatangOnlyWhenThereAreSome() {
        fun thb(c: Long) = MoneyFormat.format(Money(c), "THB", "th-TH")
        assertEquals("฿1,234", thb(123400))
        assertEquals("฿1,234.50", thb(123450))
        assertEquals("฿0", thb(0))
        assertEquals("฿0.05", thb(5))
        assertEquals("-฿5", thb(-500))
        assertEquals("฿1,234,567", thb(123456700))
        assertEquals("฿", MoneyFormat.symbol("THB", unambiguous = true))
        assertEquals("฿1,234", config().profile.format(Money(123400)))
    }

    @Test
    fun dollarsKeepTheirTwoDecimalsInEveryLanguage() {
        assertEquals("$1,234.00", MoneyFormat.format(Money(123400), "USD", "en-US"))
        assertEquals("$1,234.56", MoneyFormat.format(Money(123456), "CAD", "fr-CA"))
        assertEquals("CA$0.00", MoneyFormat.format(Money(0), "CAD", "en-CA", unambiguous = true))
        assertEquals("-$5.00", MoneyFormat.format(Money(-500), "USD", "es-US"))
    }

    // ---- cash rounding ----

    @Test
    fun wholeUnitDownDropsTheSatangAndNeverRoundsUp() {
        val p = RoundingPolicy.WHOLE_UNIT_DOWN
        assertEquals(Money(123400), p.roundCashDue(Money(123475)))
        assertEquals(Money(123400), p.roundCashDue(Money(123499)))
        assertEquals(Money(123400), p.roundCashDue(Money(123400)))
        assertEquals(Money(0), p.roundCashDue(Money(99)))
        // a cash refund rounds like a sale
        assertEquals(Money(-12300), p.roundCashDue(Money(-12350)))
        assertEquals(Money(-75), p.cashAdjustment(Money(123475)))
    }

    @Test
    fun cashRoundingSettingsAndCurrencyDefaults() {
        assertEquals(CashRounding.UNIT_DOWN, CashRounding.parse("unit-down"))
        assertEquals(CashRounding.UNIT_DOWN, CashRounding.parse(" Baht "))
        assertEquals(CashRounding.UNIT_DOWN, CashRounding.parse("whole"))
        assertEquals("unit-down", CashRounding.UNIT_DOWN.wire)
        assertEquals(RoundingPolicy.WHOLE_UNIT_DOWN, CashRounding.UNIT_DOWN.policy)
        // the currency's default when nothing is set
        assertEquals(CashRounding.UNIT_DOWN, CashRounding.defaultFor("THB"))
        assertEquals(CashRounding.NICKEL, CashRounding.defaultFor("CAD"))
        assertEquals(CashRounding.NICKEL, CashRounding.defaultFor("USD"))
        val unset = CashRounding.resolve(null, "test")
        assertFalse(unset.explicit)
        assertEquals(CashRounding.UNIT_DOWN, unset.forCurrency("THB"))
        assertEquals(CashRounding.NICKEL, unset.forCurrency("USD"))
        // a bad value is the default too (with a warning)
        val bad = CashRounding.resolve("dime", "test")
        assertTrue(bad.warning!!.contains("unit-down"))
        assertEquals(CashRounding.UNIT_DOWN, bad.forCurrency("THB"))
        // an explicit setting wins over the currency's default
        assertEquals(CashRounding.NICKEL, CashRounding.resolve("nickel", "env").forCurrency("THB"))
        assertEquals(CashRounding.OFF, CashRounding.resolve("off", "env").forCurrency("THB"))
        assertEquals(CashRounding.UNIT_DOWN, CashRounding.resolve("unit-down", "env").forCurrency("USD"))
        assertEquals("unit-down", CashRounding.wireOf(RoundingPolicy.WHOLE_UNIT_DOWN))
        assertEquals("nickel", CashRounding.wireOf(RoundingPolicy.NICKEL))
        assertEquals("off", CashRounding.wireOf(RoundingPolicy.NoRounding))
    }

    @Test
    fun cashSettlesDownToTheWholeBahtAndCardPaysTheExactAmount() {
        val c = config()
        // ฿107.75 due: ฿107 in cash settles it, the 75 satang are given up
        val settle = TransactionPipeline.tenderCash(Money(10775), Money(10700), c)
        assertEquals(Money(10775), settle.amountApplied)
        assertEquals(Money(-75), settle.roundingAdjustment)
        assertEquals(Money.ZERO, settle.change)
        // a ฿500 note: change from ฿107
        assertEquals(Money(39300), TransactionPipeline.tenderCash(Money(10775), Money(50000), c).change)
        // less than the rounded amount is a partial payment at face value
        val part = TransactionPipeline.tenderCash(Money(10775), Money(5000), c)
        assertEquals(Money(5000), part.amountApplied)
        assertEquals(Money.ZERO, part.roundingAdjustment)
        // a balance under one baht takes no coins in cash
        assertEquals(Money(-50), TransactionPipeline.tenderCash(Money(50), Money.ZERO, c).roundingAdjustment)
        // card is exact
        assertEquals(Money(10775), TransactionPipeline.tenderElectronic(Money(10775), Money(10775)))
        // an even split's cash shares add up to the whole bill rounded once
        val due = TransactionPipeline.evenSplitCashDue(listOf(Money(3592), Money(3592), Money(3591)), c.roundingPolicy)
        assertEquals(c.roundingPolicy.roundCashDue(Money(10775)), Money(due.sumOf { it.cents }))
    }

    // ---- inclusive VAT ----

    @Test
    fun includedVatIsATaxLineInsideTheTotal() {
        val t = TransactionPipeline.computeTotals(listOf(BasketLine(Money(10700), 1)), 0, config())
        assertEquals(Money(10700), t.grandTotal)          // the guest pays the menu price
        assertEquals(Money(700), t.taxIncluded)           // 107.00 × 7 / 107
        assertEquals(Money.ZERO, t.taxAdded)
        assertTrue(t.taxLines.isEmpty())                  // nothing added on top
        val line = t.includedTaxLines.single()
        assertEquals("VAT", line.component.code)
        assertEquals(Money(700), line.amount)
        assertTrue(line.included)
        assertEquals(Money(10700), t.subtotal)            // subtotal = total: no tax on top
        // net before tax + tax = what the guest paid
        assertEquals(t.grandTotal, t.grandTotal - t.taxIncluded + line.amount)
    }

    @Test
    fun includedVatRoundsHalfUpToTheSatangOncePerCheck() {
        val p = TaxPolicy.InclusiveTax(vat, showOnReceipt = false)
        assertEquals(Money(65), p.includedIn(Money(1000)))     // 65.42 → 65
        assertEquals(Money(7), p.includedIn(Money(107)))       // 7.00
        assertEquals(Money(4), p.includedIn(Money(61)))        // 3.99 → 4
        assertEquals(Money(2), p.includedIn(Money(23)))        // 1.5046 → 2
        assertEquals(Money(-65), p.includedIn(Money(-1000)))   // symmetric for a negative figure
        // per check, never per line: three ฿10 lines are taxed on ฿30
        val t = TransactionPipeline.computeTotals(List(3) { BasketLine(Money(1000), 1) }, 0, config())
        assertEquals(p.includedIn(Money(3000)), t.taxIncluded)
        assertEquals(Money(196), t.taxIncluded)                // 196.26 → 196, not 3 × 65 = 195
    }

    @Test
    fun theOlderUnnamedIncludedTaxComputesAsBefore() {
        val old = TaxPolicy.InclusiveTax(ratePercent = 13, showOnReceipt = false)
        for (base in listOf(0L, 1L, 56L, 113L, 999L, 100000L, 123457L)) {
            val legacy = (base * 13 * 2 + 113) / (113 * 2)
            assertEquals(Money(legacy), old.assess(Money(base)).taxIncluded, "base $base")
        }
        assertEquals(INCLUDED_TAX_CODE, old.component.code)
    }

    @Test
    fun aServiceChargeOnVatInclusivePricesCarriesItsOwnVat() {
        // 10% service on the items, VAT 7% on items + service; the menu prices
        // and therefore the service charge are VAT-inclusive
        val c = config(fees = listOf(Fee.ServiceCharge(percent = 10)))
        val t = TransactionPipeline.computeTotals(listOf(BasketLine(Money(100000), 1)), 0, c)
        val service = t.feeLines.single()
        assertEquals("service_charge", service.code)
        assertEquals(Money(10000), service.amount)
        assertEquals(Money(110000), t.grandTotal)         // ฿1,000 + ฿100 service; VAT already inside
        assertEquals(Money(110000), t.taxableBase)
        // 1,100.00 × 7 / 107 = 71.96 → 71.96 (half-up at the satang)
        assertEquals(Money(7196), t.taxIncluded)
        assertEquals(Money(7196), t.includedTaxLines.single().amount)
        // the same as "service on the net price, VAT on net + service":
        // net 934.58 + service 93.46 = 1,028.04; × 7% = 71.96
        val net = BigDecimal(100000).multiply(BigDecimal(100)).divide(BigDecimal(107), 10, java.math.RoundingMode.HALF_UP)
        val viaNet = net.multiply(BigDecimal("1.10")).multiply(BigDecimal("0.07")).setScale(0, java.math.RoundingMode.HALF_UP)
        assertEquals(viaNet.toLong(), t.taxIncluded.cents)
        // a service charge marked not taxable stays out of the VAT base
        val untaxed = TransactionPipeline.computeTotals(listOf(BasketLine(Money(100000), 1)), 0,
            config(fees = listOf(Fee.ServiceCharge(percent = 10, taxable = false))))
        assertEquals(Money(110000), untaxed.grandTotal)
        assertEquals(TaxPolicy.InclusiveTax(vat, false).includedIn(Money(100000)), untaxed.taxIncluded)
    }

    @Test
    fun aDiscountComesOffBeforeTheIncludedVat() {
        val t = TransactionPipeline.computeTotals(listOf(BasketLine(Money(10700), 1)), 0, config(), discount = Money(700))
        assertEquals(Money(10000), t.grandTotal)
        assertEquals(Money(654), t.taxIncluded)           // 100.00 × 7 / 107 = 6.54
        assertEquals(Money(654), t.includedTaxLines.single().amount)
        // a VAT-exempt line stays out of the base
        val mixed = TransactionPipeline.computeTotals(
            listOf(BasketLine(Money(10700), 1), BasketLine(Money(5000), 1, taxable = false)), 0, config())
        assertEquals(Money(15700), mixed.grandTotal)
        assertEquals(Money(700), mixed.taxIncluded)
    }

    @Test
    fun aByItemSplitSharesTheChecksVatAndTheSharesAddUp() {
        val c = config(fees = listOf(Fee.ServiceCharge(percent = 10)))
        val groups = listOf(
            TransactionPipeline.computeTotals(listOf(BasketLine(Money(33333), 1)), 0, c),
            TransactionPipeline.computeTotals(listOf(BasketLine(Money(33333), 1)), 0, c),
            TransactionPipeline.computeTotals(listOf(BasketLine(Money(12345), 1)), 0, c),
        )
        val split = TransactionPipeline.apportionTax(groups, c)
        val whole = c.taxPolicy.assess(Money(groups.sumOf { it.taxableBase.cents }))
        assertEquals(whole.taxIncluded.cents, split.sumOf { it.taxIncluded.cents })
        assertEquals(whole.taxIncluded.cents, split.sumOf { it.includedTaxLines.single().amount.cents })
        for (g in split) {
            assertEquals(g.taxIncluded, g.includedTaxLines.single().amount)
            assertTrue(g.includedTaxLines.single().included)
            assertTrue(g.taxLines.isEmpty())
        }
        // equal baskets pay equal VAT
        assertEquals(split[0].taxIncluded, split[1].taxIncluded)
        // the grand totals are untouched by an included tax
        assertEquals(groups.map { it.grandTotal }, split.map { it.grandTotal })
    }

    @Test
    fun anEvenSplitSharesTheIncludedVat() {
        val whole = TransactionPipeline.computeTotals(listOf(BasketLine(Money(10000), 1)), 0, config())
        val shares = listOf(Money(3334), Money(3333), Money(3333))
        val parts = TransactionPipeline.taxOfShares(whole, shares)
        assertEquals(whole.taxIncluded.cents, parts.sumOf { it.first.cents })
        assertTrue(parts.all { it.second.isEmpty() })
    }

    @Test
    fun addedTaxStoresHaveNoIncludedLines() {
        val gst = TaxComponent("GST", "TPS", "GST", BigDecimal("5"), "")
        val added = TaxPolicy.AddedTaxes(listOf(gst))
        assertTrue(added.assess(Money(1000)).includedLines.isEmpty())
        assertTrue(added.includedLines(Money(50)).isEmpty())
        assertTrue(TaxPolicy.NoTax.includedLines(Money(50)).isEmpty())
    }

    @Test
    fun includedTaxLinesKeepTheirFlagThroughStorageAndSync() {
        val lines = listOf(
            TaxLine(TaxComponent("GST", "TPS", "GST", BigDecimal("5"), "123"), Money(50)),
            TaxLine(vat, Money(700), included = true),
        )
        val json = taxLinesToJson(lines)
        assertNull(json[0].let { (it as kotlinx.serialization.json.JsonObject)["included"] }) // added: unchanged shape
        assertEquals("true", (json[1] as kotlinx.serialization.json.JsonObject)["included"].toString())
        assertEquals(lines, taxLinesFromJson(json.toString()))
    }

    // ---- receipts ----

    private fun receipt(show: Boolean, reg: String = "") = Receipt(
        checkId = 3, tableLabel = "T1",
        openedAt = LocalDateTime.of(2026, 10, 1, 18, 0), closedAt = LocalDateTime.of(2026, 10, 1, 19, 0),
        items = listOf(ReceiptItem("Plat", "Dish", null, null, 1, Money(100000), Money(100000), null)),
        fees = listOf(ReceiptFee("Frais de service", "Service charge", Money(10000), "service_charge")),
        grandTotal = Money(110000), taxIncluded = Money(7196), taxRatePercent = null, tenders = emptyList(),
        includedTaxes = listOf(TaxLine(vat.copy(registrationNumber = reg), Money(7196), included = true)),
        showIncludedTaxes = show,
    )

    private fun rows(r: Receipt) =
        PrinterAdapter.renderText(ReceiptRenderer.render(r, ReceiptPolicy.Standard("t", emptyList(), "t", showTax = false)))
            .lines().map { it.trim().replace(Regex(" {2,}"), " | ") }

    @Test
    fun theReceiptShowsTheIncludedVatUnderTheTotalWhenTheStoreSaysSo() {
        val shown = rows(receipt(show = true, reg = "0123456789012"))
        assertTrue("Total | 1,100.00" in shown, shown.joinToString("\n"))
        assertTrue("VAT 7% included | 71.96" in shown, shown.joinToString("\n"))
        assertTrue(shown.indexOf("Total | 1,100.00") < shown.indexOf("VAT 7% included | 71.96"))
        // no subtotal / added-tax block: the VAT is not on top
        assertTrue(shown.none { it.startsWith("Subtotal") }, shown.joinToString("\n"))
        assertTrue(shown.any { "0123456789012" in it }, shown.joinToString("\n"))

        val hidden = rows(receipt(show = false, reg = "0123456789012"))
        assertTrue("Total | 1,100.00" in hidden)
        assertTrue(hidden.none { "VAT" in it }, hidden.joinToString("\n"))
        assertEquals(Money(110000), receipt(show = true).subtotal)
    }
}
