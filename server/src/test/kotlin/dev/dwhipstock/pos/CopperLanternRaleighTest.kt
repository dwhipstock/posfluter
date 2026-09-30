package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.SettingsRepository
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternConfig
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue
import dev.dwhipstock.pos.db.initDatabase
import dev.dwhipstock.pos.restaurant.KioskTicket
import dev.dwhipstock.pos.sdk.BasketLine
import dev.dwhipstock.pos.sdk.KitchenLanguage
import dev.dwhipstock.pos.sdk.KitchenTicketData
import dev.dwhipstock.pos.sdk.KitchenTicketItem
import dev.dwhipstock.pos.sdk.KitchenTicketKind
import dev.dwhipstock.pos.sdk.KitchenTicketRenderer
import dev.dwhipstock.pos.sdk.LegalAge
import dev.dwhipstock.pos.sdk.Money
import dev.dwhipstock.pos.sdk.PrintLine
import dev.dwhipstock.pos.sdk.PrinterAdapter
import dev.dwhipstock.pos.sdk.Receipt
import dev.dwhipstock.pos.sdk.ReceiptItem
import dev.dwhipstock.pos.sdk.ReceiptKind
import dev.dwhipstock.pos.sdk.ReceiptOrder
import dev.dwhipstock.pos.sdk.ReceiptRenderer
import dev.dwhipstock.pos.sdk.StoreProfile
import dev.dwhipstock.pos.sdk.TaxLine
import dev.dwhipstock.pos.sdk.TransactionPipeline
import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Copper Lantern in Raleigh, North Carolina: USD, NC sales tax 6.75% plus
 * Wake County's 1% prepared food and beverage tax on everything a pub sells,
 * cash to the nickel (card exact), English receipts and tickets, and ID
 * checks at 21.
 */
class CopperLanternRaleighTest {

    private fun config(venue: CopperLanternVenue = CopperLanternVenue.VIEUX_PORT, legalAge: Int = CopperLanternConfig.LEGAL_AGE): CopperLanternConfig {
        initDatabase(Files.createTempDirectory("pos-raleigh-cfg").resolve("pos.db").toString())
        return CopperLanternConfig(venue = venue, settings = SettingsRepository(),
            printer = PrinterAdapter.VirtualPrinter("build/tmp/receipts"), publicBaseUrl = "http://x", legalAge = legalAge)
    }

    private fun totals(config: CopperLanternConfig, vararg cents: Long) =
        TransactionPipeline.computeTotals(cents.map { BasketLine(Money(it), 1) }, 0, config)

    @Test
    fun ncSalesTaxAndWakePreparedFoodTaxOnTopOfTheMenuPrice() {
        val c = config()
        // $10.00 of food: NC 6.75% = 0.675 → 0.68 (half-up), Wake 1% = 0.10 → $10.78
        val food = totals(c, 1000)
        assertEquals(listOf("NC_SALES" to 68L, "WAKE_FOOD" to 10L), food.taxLines.map { it.component.code to it.amount.cents })
        assertEquals(Money(1078), food.grandTotal)
        // a drink is prepared food and beverage too: the $20.25 pitcher pays both
        val drink = totals(c, 2025)
        assertEquals(listOf(137L, 20L), drink.taxLines.map { it.amount.cents })
        assertEquals(Money(2182), drink.grandTotal)
        // 7.75% in all, each tax on the same pre-tax base (never compounded)
        val hundred = totals(c, 10_000)
        assertEquals(listOf(675L, 100L), hundred.taxLines.map { it.amount.cents })
        assertEquals(Money(10_775), hundred.grandTotal)
        assertEquals(listOf("6.75", "1"), CopperLanternConfig.NC_TAXES.map { it.rateText })
        // US receipts print no tax registration number
        assertTrue(CopperLanternConfig.NC_TAXES.all { it.registrationNumber.isEmpty() })
    }

    @Test
    fun cashRoundsToTheNickelAndCardIsExact() {
        val c = config()
        // $10.78 → cash $10.80; $21.82 → cash $21.80; card pays the exact total
        assertEquals(Money(1080), c.roundingPolicy.roundCashDue(totals(c, 1000).grandTotal))
        assertEquals(Money(2180), c.roundingPolicy.roundCashDue(totals(c, 2025).grandTotal))
        assertEquals(Money(2), c.roundingPolicy.cashAdjustment(Money(1078)))
        assertEquals(Money(-2), c.roundingPolicy.cashAdjustment(Money(2182)))
        // a cash total ending in 5 or 0 stays
        assertEquals(Money(10_775), c.roundingPolicy.roundCashDue(Money(10_775)))
    }

    @Test
    fun aUsStoreInEasternTimeSpeakingEnglishFirst() {
        for (venue in CopperLanternVenue.entries) {
            val p = config(venue).profile
            assertEquals("US", p.country)
            assertEquals("USD", p.currency)
            assertEquals("America/New_York", p.timeZone)
            assertEquals(LocaleCode.EN, p.defaultLocale)
            assertEquals(listOf("en", "fr", "es", "de", "af"), p.locales.map { it.tag })
            assertEquals(if (venue.quickServe) StoreProfile.Kind.QUICK_SERVE else StoreProfile.Kind.RESTAURANT, p.kind)
            assertEquals("$12.99", p.format(Money(1299)))
            assertEquals("$1,234.56", p.format(Money(123_456), LocaleCode.FR))
            assertTrue(venue.address.endsWith("Raleigh, NC 27601"), venue.address)
            assertTrue(venue.phone.startsWith("(919) 555-01"), venue.phone)
        }
        assertEquals("Copper Lantern — Glenwood South", CopperLanternVenue.VIEUX_PORT.displayName)
        assertEquals("vieux-port", CopperLanternVenue.VIEUX_PORT.id, "the id devices are configured with stays")
        assertEquals("Copper Lantern — Express", CopperLanternVenue.EXPRESS.displayName)
        assertEquals(CopperLanternVenue.VIEUX_PORT, CopperLanternVenue.of("vieux-port"))
        assertEquals(CopperLanternVenue.VIEUX_PORT, CopperLanternVenue.of(null))
    }

    @Test
    fun idChecksAtTwentyOne() {
        assertEquals(21, config().legalAge)
        assertEquals(21, config(CopperLanternVenue.EXPRESS).legalAge)
        assertEquals(21, CopperLanternConfig.profileFor(CopperLanternVenue.EXPRESS).legalAge)
        // POS_LEGAL_AGE / legal.age still override; nonsense falls back to 21
        assertEquals(19, LegalAge.fromEnv(CopperLanternConfig.LEGAL_AGE) { if (it == LegalAge.ENV) "19" else null })
        assertEquals(21, LegalAge.fromEnv(CopperLanternConfig.LEGAL_AGE) { if (it == LegalAge.ENV) "abc" else null })
        assertEquals(21, LegalAge.fromEnv(CopperLanternConfig.LEGAL_AGE) { null })
    }

    private val bill = Receipt(
        checkId = 12, tableLabel = "U-1",
        openedAt = LocalDateTime.of(2026, 10, 8, 18, 5), closedAt = LocalDateTime.of(2026, 10, 8, 18, 40),
        items = listOf(
            ReceiptItem("Lager de la Lanterne", "Lantern House Lager", "Pinte 20 oz", "20 oz pint", 1, Money(750), Money(750), null,
                names = mapOf("es" to "Lager de la casa Lantern")),
            ReceiptItem("Poutine classique", "Classic Poutine", null, null, 1, Money(1300), Money(1300), null),
        ),
        fees = emptyList(),
        grandTotal = Money(2050 + 138 + 21),
        taxIncluded = Money.ZERO, taxRatePercent = null, tenders = emptyList(),
        taxes = listOf(TaxLine(CopperLanternConfig.NC_SALES_TAX, Money(138)), TaxLine(CopperLanternConfig.WAKE_FOOD_TAX, Money(21))),
        cashDue = Money(2210), cashRounding = Money(1),
        order = ReceiptOrder("#101", takeOut = false),
    )

    private fun text(lines: List<PrintLine>) = PrinterAdapter.renderText(lines)

    @Test
    fun receiptsAreEnglishOnlyByDefaultAndReprintInAnotherLanguage() {
        val policy = config(CopperLanternVenue.EXPRESS).receiptPolicy
        assertEquals(LocaleCode.EN, policy.locale)
        val en = text(ReceiptRenderer.render(bill, policy, ReceiptKind.PROVISIONAL))
        for (want in listOf("#101 · Dine in", "*** CUSTOMER BILL ***", "*** NOT A RECEIPT ***", "NC sales tax 6.75%",
            "Wake prepared food tax 1%", "Rounding", "Cash total", "Lantern House Lager (20 oz pint)")) {
            assertTrue(want in en, "'$want' missing:\n$en")
        }
        for (gone in listOf("Sur place", "ADDITION", "REÇU", "GST", "QST", "TPS", "TVQ", " no. ", "Pinte")) {
            assertTrue(gone !in en, "'$gone' on an English bill:\n$en")
        }
        // press-and-hold reprints: each in its own language, one language at a time
        val fr = text(ReceiptRenderer.render(bill, policy.withLocale(LocaleCode.FR), ReceiptKind.PROVISIONAL))
        assertTrue("#101 · Sur place" in fr && "Dine in" !in fr, fr)
        assertTrue("Taxe de vente (C.-N.)" in fr && "Lager de la Lanterne" in fr, fr)
        val es = text(ReceiptRenderer.render(bill, policy.withLocale(LocaleCode.ES), ReceiptKind.PROVISIONAL))
        assertTrue("#101 · Para comer aquí" in es && "Lager de la casa Lantern" in es && "Impuesto sobre las ventas de NC" in es, es)
        val de = text(ReceiptRenderer.render(bill, policy.withLocale(LocaleCode.DE), ReceiptKind.PROVISIONAL))
        assertTrue("#101 · Hier essen" in de && "Umsatzsteuer NC" in de, de)
        val af = text(ReceiptRenderer.render(bill, policy.withLocale(LocaleCode.AF), ReceiptKind.PROVISIONAL))
        assertTrue("#101 · Eet hier" in af && "NC-verkoopbelasting" in af, af)
        // US dates: month first, 12-hour clock
        assertTrue("10/08/2026 6:05 PM" in en, en)
    }

    @Test
    fun kitchenTicketsAreEnglishByDefault() {
        val t = KitchenTicketData(
            kind = KitchenTicketKind.ORDER, stationNameFr = "Cuisine", stationNameEn = "Kitchen",
            tableLabel = "U-1", serverName = "Demo Server", checkId = 12, sentAt = LocalDateTime.of(2026, 10, 8, 18, 6),
            items = listOf(KitchenTicketItem(2, "Poutine classique", "Classic Poutine")),
        )
        val ticket = text(KitchenTicketRenderer.render(t, KitchenLanguage.EN))
        assertTrue("ORDER" in ticket && "Kitchen" in ticket && "Classic Poutine" in ticket, ticket)
        for (gone in listOf("COMMANDE", "Cuisine", "Poutine classique", " / ")) assertTrue(gone !in ticket, "'$gone':\n$ticket")
        val void = text(KitchenTicketRenderer.render(t.copy(kind = KitchenTicketKind.VOID), KitchenLanguage.EN))
        assertTrue("VOID" in void && "ANNULÉ" !in void, void)
        val add = text(KitchenTicketRenderer.render(t.copy(kind = KitchenTicketKind.ADD), KitchenLanguage.EN))
        assertTrue("ADD" in add && "AJOUT" !in add, add)
        // the store's default kitchen language is its first: English
        assertEquals(LocaleCode.EN, config().profile.defaultLocale)
    }

    @Test
    fun theKioskTicketNamesTheLegalAge() {
        val c = config(CopperLanternVenue.EXPRESS)
        val en = text(KioskTicket.render(bill, 101, takeOut = true, alcohol = true, policy = c.receiptPolicy, currency = "USD", legalAge = c.legalAge))
        assertTrue("Alcohol: 21+ only. Staff will check ID at" in en && "the counter." in en, en)
        assertTrue("Take out" in en && "$22.09" in en && "Pour emporter" !in en, en)
        val fr = text(KioskTicket.render(bill, 101, takeOut = true, alcohol = true,
            policy = c.receiptPolicy.withLocale(LocaleCode.FR), currency = "USD", legalAge = 21))
        assertTrue("21 ans et plus" in fr && "Pour emporter" in fr, fr)
        val noBeer = text(KioskTicket.render(bill, 101, takeOut = false, alcohol = false, policy = c.receiptPolicy, currency = "USD"))
        assertTrue("ID" !in noBeer, noBeer)
    }
}
