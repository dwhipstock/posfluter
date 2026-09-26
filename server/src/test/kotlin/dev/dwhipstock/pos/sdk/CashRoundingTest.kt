package dev.dwhipstock.pos.sdk

import dev.dwhipstock.pos.base.SettingsRepository
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternConfig
import dev.dwhipstock.pos.customers.sagepoppy.SagePoppyConfig
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DB-free: no tax, no fees — the cash math alone. */
private class RoundingTestConfig(override val roundingPolicy: RoundingPolicy) : CustomerConfig {
    override val customerId = "test"
    override val displayName = "Test Store"
    override val taxPolicy = TaxPolicy.NoTax
    override val fees = emptyList<Fee>()
    override val authPolicy = AuthPolicy.PinLogin(4)
    override val receiptPolicy = ReceiptPolicy.Standard("t", emptyList(), "t", showTax = false)
    override val printer = PrinterAdapter.VirtualPrinter("build/tmp/receipts")
    override val electronicTenders = emptyList<TenderMethod>()
    override val publicBaseUrl = "http://localhost:8080"
}

/**
 * Cash rounding to the nickel: the rule on every last cent digit, the tender
 * math (settle, partial, change from the rounded amount), refunds, the
 * cash.rounding switch and the receipt / bill lines.
 */
class CashRoundingTest {

    private val nickel = RoundingPolicy.NICKEL
    private val cash = RoundingTestConfig(nickel)

    @Test
    fun everyLastDigitRoundsToTheNearestNickel() {
        // 1–2 → down to 0, 3–4 → up to 5, 6–7 → down to 5, 8–9 → up to 10; 0 and 5 stay
        val expected = listOf(1000L, 1000, 1000, 1005, 1005, 1005, 1005, 1005, 1010, 1010)
        for (digit in 0..9) {
            val exact = Money(1000L + digit)
            assertEquals(Money(expected[digit]), nickel.roundCashDue(exact), "10.0$digit")
            assertEquals(Money(expected[digit] - 1000L - digit), nickel.cashAdjustment(exact), "10.0$digit adjustment")
        }
        // the adjustment is never more than 2¢ either way
        for (c in 0L..500L) assertTrue(kotlin.math.abs(nickel.cashAdjustment(Money(c)).cents) <= 2, "$c")
    }

    @Test
    fun smallAndOddTotals() {
        assertEquals(Money.ZERO, nickel.roundCashDue(Money(1)))    // 0.01 → 0.00
        assertEquals(Money.ZERO, nickel.roundCashDue(Money(2)))    // 0.02 → 0.00
        assertEquals(Money(5), nickel.roundCashDue(Money(3)))      // 0.03 → 0.05
        assertEquals(Money(1005), nickel.roundCashDue(Money(1007))) // 10.07 → 10.05
        assertEquals(Money(-2), nickel.cashAdjustment(Money(1007)))
        assertEquals(Money.ZERO, nickel.roundCashDue(Money.ZERO))
    }

    @Test
    fun roundingIsSymmetricForNegativeAmounts() {
        assertEquals(Money(-1005), nickel.roundCashDue(Money(-1007)))
        assertEquals(Money(-1010), nickel.roundCashDue(Money(-1008)))
        assertEquals(Money(-5), nickel.roundCashDue(Money(-3)))
    }

    @Test
    fun cashSettlesAtTheRoundedAmountAndChangeComesFromIt() {
        // 10.07 due, a $20 note: pays 10.05, 9.95 change, −0.02 rounding; the bill clears exactly
        val r = TransactionPipeline.tenderCash(Money(1007), Money(2000), cash)
        assertEquals(Money(1007), r.amountApplied)
        assertEquals(Money(-2), r.roundingAdjustment)
        assertEquals(Money(995), r.change)
        // exact rounded cash: no change
        val exact = TransactionPipeline.tenderCash(Money(1008), Money(1010), cash)
        assertEquals(Money(2), exact.roundingAdjustment)
        assertEquals(Money.ZERO, exact.change)
    }

    @Test
    fun aOneCentBalanceSettlesWithNoCoins() {
        val r = TransactionPipeline.tenderCash(Money(1), Money.ZERO, cash)
        assertEquals(Money(1), r.amountApplied)
        assertEquals(Money(-1), r.roundingAdjustment)
        assertEquals(Money.ZERO, r.change)
        // 0.03 needs a nickel, so nothing tendered is refused
        assertFailsWith<IllegalArgumentException> { TransactionPipeline.tenderCash(Money(3), Money.ZERO, cash) }
        assertFailsWith<IllegalArgumentException> { TransactionPipeline.tenderCash(Money(1), Money(-5), cash) }
    }

    @Test
    fun partialCashAppliesAtFaceValueAndTheLastCashPaymentRounds() {
        // $10 toward 23.47: face value, no rounding, no change
        val first = TransactionPipeline.tenderCash(Money(2347), Money(1000), cash)
        assertEquals(Money(1000), first.amountApplied)
        assertEquals(Money.ZERO, first.roundingAdjustment)
        // the remaining 13.47 in cash rounds to 13.45
        val last = TransactionPipeline.tenderCash(Money(1347), Money(1500), cash)
        assertEquals(Money(-2), last.roundingAdjustment)
        assertEquals(Money(155), last.change)
    }

    @Test
    fun electronicTendersAreNeverRounded() {
        assertEquals(Money(1007), TransactionPipeline.tenderElectronic(Money(1007), Money(1007)))
        assertEquals(Money(2000), TransactionPipeline.tenderElectronic(Money(2347), Money(2000)))
    }

    @Test
    fun offChargesCashToTheCent() {
        val off = RoundingTestConfig(CashRounding.OFF.policy)
        val r = TransactionPipeline.tenderCash(Money(1007), Money(2000), off)
        assertEquals(Money.ZERO, r.roundingAdjustment)
        assertEquals(Money(993), r.change)
        assertEquals(Money.ZERO, off.roundingPolicy.cashAdjustment(Money(1007)))
    }

    @Test
    fun theSwitchParsesAndDefaultsToNickel() {
        assertEquals(CashRounding.NICKEL, CashRounding.parse(" Nickel "))
        assertEquals(CashRounding.OFF, CashRounding.parse("OFF"))
        assertNull(CashRounding.parse("penny"))
        assertEquals(CashRounding.NICKEL, CashRounding.resolve(null, "x").rounding)
        assertEquals(CashRounding.OFF, CashRounding.resolve("off", "x").rounding)
        val bad = CashRounding.resolve("dime", "x")
        assertEquals(CashRounding.NICKEL, bad.rounding)
        assertNotNull(bad.warning)
        // env wins; else the config file; else nickel
        val dir = Files.createTempDirectory("cash-rounding").toFile()
        val file = File(dir, "store.properties").apply { writeText("print.receipts=digital\ncash.rounding=off\n") }
        assertEquals(CashRounding.OFF, CashRounding.fromEnv { if (it == "POS_CONFIG_FILE") file.path else null }.rounding)
        assertEquals(CashRounding.NICKEL, CashRounding.fromEnv {
            when (it) { "POS_CASH_ROUNDING" -> "nickel"; "POS_CONFIG_FILE" -> file.path; else -> null }
        }.rounding)
        assertEquals(CashRounding.NICKEL, CashRounding.fromEnv { null }.rounding)
        assertEquals(CashRounding.NICKEL, CashRounding.fromFile(File(dir, "missing.properties")).rounding)
        assertEquals(RoundingPolicy.NoRounding, CashRounding.OFF.policy)
        assertEquals(RoundingPolicy.NICKEL, CashRounding.NICKEL.policy)
    }

    @Test
    fun bothStoresRoundCashByDefaultAndEitherCanTurnItOff() {
        val settings = SettingsRepository()
        val printer = PrinterAdapter.VirtualPrinter("build/tmp/receipts")
        val pub = CopperLanternConfig(settings = settings, printer = printer, publicBaseUrl = "http://x")
        val shop = SagePoppyConfig(settings = settings, printer = printer, publicBaseUrl = "http://x")
        assertEquals("CAD", pub.profile.currency)
        assertEquals("USD", shop.profile.currency)
        for (config in listOf(pub, shop)) assertEquals(Money(1005), config.roundingPolicy.roundCashDue(Money(1007)))
        val shopOff = SagePoppyConfig(settings = settings, printer = printer, publicBaseUrl = "http://x",
            cashRounding = CashRounding.OFF)
        val pubOff = CopperLanternConfig(settings = settings, printer = printer, publicBaseUrl = "http://x",
            cashRounding = CashRounding.OFF)
        for (config in listOf(pubOff, shopOff)) assertEquals(Money(1007), config.roundingPolicy.roundCashDue(Money(1007)))
    }

    private fun receipt(tenders: List<ReceiptTender>, cashDue: Money? = null, cashRounding: Money = Money.ZERO) = Receipt(
        checkId = 7, tableLabel = "5", openedAt = LocalDateTime.of(2026, 9, 25, 18, 0),
        closedAt = LocalDateTime.of(2026, 9, 25, 19, 0),
        items = listOf(ReceiptItem("Frites", "Fries", null, null, 1, Money(1007), Money(1007), null)),
        fees = emptyList(), grandTotal = Money(1007), taxIncluded = Money.ZERO, taxRatePercent = null,
        tenders = tenders, cashDue = cashDue, cashRounding = cashRounding,
    )

    private fun kv(lines: List<PrintLine>) = PrinterAdapter.renderText(lines).lines()
        .map { it.trim().replace(Regex(" {2,}"), " | ") }

    @Test
    fun theReceiptShowsTheRoundingAndTheCashTotal() {
        val tender = ReceiptTender("Espèces", "Cash", Money(2000), Money(1007), Money(-2), Money(995), "CASH")
        val policy = ReceiptPolicy.Standard("t", emptyList(), "merci", showTax = false, locale = dev.dwhipstock.pos.sdk.i18n.LocaleCode.EN)
        val text = kv(ReceiptRenderer.render(receipt(listOf(tender)), policy))
        assertTrue("Total | 10.07" in text, text.joinToString("\n")) // the sale stays exact
        assertTrue("Rounding | -0.02" in text, text.joinToString("\n"))
        assertTrue("Cash total | 10.05" in text, text.joinToString("\n"))
        assertTrue("Cash | 20" in text && "Change | 9.95" in text, text.joinToString("\n"))
        // French and Spanish name it too; a positive adjustment carries its sign
        val up = ReceiptTender("Espèces", "Cash", Money(1010), Money(1008), Money(2), Money.ZERO, "CASH")
        val fr = kv(ReceiptRenderer.render(receipt(listOf(up)), policy.withLocale(dev.dwhipstock.pos.sdk.i18n.LocaleCode.FR)))
        assertTrue("Arrondi | +0,02" in fr && "Total comptant | 10,10" in fr, fr.joinToString("\n"))
        val es = kv(ReceiptRenderer.render(receipt(listOf(up)), policy.withLocale(dev.dwhipstock.pos.sdk.i18n.LocaleCode.ES)))
        assertTrue("Redondeo | +0.02" in es && "Total en efectivo | 10.10" in es, es.joinToString("\n"))
        // a card payment is exact: no rounding lines at all
        val card = ReceiptTender("Carte", "Card", Money(1007), Money(1007), Money.ZERO, Money.ZERO, "CARD")
        val cardText = kv(ReceiptRenderer.render(receipt(listOf(card)), policy))
        assertTrue(cardText.none { it.startsWith("Rounding") || it.startsWith("Cash total") }, cardText.joinToString("\n"))
    }

    @Test
    fun theBillShowsWhatPayingCashComesTo() {
        val policy = ReceiptPolicy.Standard("t", emptyList(), "merci", showTax = false, locale = dev.dwhipstock.pos.sdk.i18n.LocaleCode.EN)
        val bill = kv(ReceiptRenderer.render(receipt(emptyList(), Money(1005), Money(-2)), policy, ReceiptKind.PROVISIONAL))
        assertTrue("Total | 10.07" in bill && "Rounding | -0.02" in bill && "Cash total | 10.05" in bill, bill.joinToString("\n"))
        // nothing to round → nothing extra on the bill
        val even = kv(ReceiptRenderer.render(receipt(emptyList(), Money(1007), Money.ZERO), policy, ReceiptKind.PROVISIONAL))
        assertTrue(even.none { it.startsWith("Rounding") || it.startsWith("Cash total") }, even.joinToString("\n"))
    }
}
