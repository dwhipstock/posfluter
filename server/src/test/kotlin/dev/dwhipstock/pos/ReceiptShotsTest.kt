package dev.dwhipstock.pos

import dev.dwhipstock.pos.customers.copperlantern.CopperLanternConfig
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue
import dev.dwhipstock.pos.customers.sagepoppy.SagePoppy
import dev.dwhipstock.pos.sdk.Align
import dev.dwhipstock.pos.sdk.Money
import dev.dwhipstock.pos.sdk.PrintLine
import dev.dwhipstock.pos.sdk.Receipt
import dev.dwhipstock.pos.sdk.ReceiptFee
import dev.dwhipstock.pos.sdk.ReceiptItem
import dev.dwhipstock.pos.sdk.ReceiptKind
import dev.dwhipstock.pos.sdk.ReceiptPolicy
import dev.dwhipstock.pos.sdk.ReceiptRenderer
import dev.dwhipstock.pos.sdk.ReceiptTender
import dev.dwhipstock.pos.sdk.TaxLine
import dev.dwhipstock.pos.sdk.ThermalReceiptRenderer
import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import java.io.File
import java.time.LocalDateTime
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A Raleigh pub receipt and bill (English, the default, and a French reprint)
 * and a Spanish Sage & Poppy receipt, through
 * the production thermal raster path. Always checks the text; with
 * RECEIPT_SHOTS_DIR set it also writes the bitmaps a printer would print:
 *
 *   RECEIPT_SHOTS_DIR=$PWD/../docs/screenshots/language-pass ./gradlew test --tests '*ReceiptShotsTest'
 */
class ReceiptShotsTest {

    private val pubReceipt = Receipt(
        checkId = 1207, tableLabel = "B-4",
        openedAt = LocalDateTime.of(2026, 9, 25, 19, 5), closedAt = LocalDateTime.of(2026, 9, 25, 21, 12),
        items = listOf(
            ReceiptItem("Lager de la Lanterne", "Lantern House Lager", "Pichet 60 oz", "60 oz pitcher", 1, Money(2025), Money(2025), null),
            ReceiptItem("Poutine classique", "Classic Poutine", null, null, 2, Money(1300), Money(2600), "Sauce à part"),
            ReceiptItem("Ailes de poulet", "Chicken Wings", null, null, 1, Money(1675), Money(1675), null),
        ),
        fees = emptyList(),
        // 73.00 + NC sales tax 6.75% (4.9275 → 4.93) + Wake 1% (0.73) = 78.66;
        // cash rounds to the nickel: 78.65
        grandTotal = Money(7300 + 493 + 73),
        taxIncluded = Money.ZERO, taxRatePercent = null,
        tenders = listOf(ReceiptTender("Comptant", "Cash", Money(10000), Money(7866), Money(-1), Money(2135), "CASH")),
        taxes = listOf(TaxLine(CopperLanternConfig.NC_SALES_TAX, Money(493)), TaxLine(CopperLanternConfig.WAKE_FOOD_TAX, Money(73))),
        cashDue = Money(7865), cashRounding = Money(-1),
    )
    private val pubPolicy = ReceiptPolicy.Standard(
        CopperLanternVenue.VIEUX_PORT.displayName,
        listOf(CopperLanternVenue.VIEUX_PORT.address),
        "Thank you for visiting!",
        showTax = false, locale = LocaleCode.EN, phone = CopperLanternVenue.VIEUX_PORT.phone, usDates = true,
    )

    private val spReceipt = Receipt(
        checkId = 1042, tableLabel = "1",
        openedAt = LocalDateTime.of(2026, 9, 25, 17, 41), closedAt = LocalDateTime.of(2026, 9, 25, 17, 43),
        items = listOf(
            ReceiptItem("Golden Hour Lager 6-pack 12 oz cans", "Golden Hour Lager 6-pack 12 oz cans", null, null, 2, Money(999), Money(1998), null),
            ReceiptItem("Coastal Ridge Cabernet Sauvignon 750 ml", "Coastal Ridge Cabernet Sauvignon 750 ml", null, null, 1, Money(1899), Money(1899), null),
            ReceiptItem("Sea Salt Potato Chips 8 oz", "Sea Salt Potato Chips 8 oz", null, null, 1, Money(449), Money(449), null),
        ),
        fees = listOf(ReceiptFee("CRV", "CRV", Money(60), "crv")),
        grandTotal = Money(1998 + 1899 + 449 + 60 + 370),
        taxIncluded = Money.ZERO, taxRatePercent = null,
        tenders = listOf(ReceiptTender("Card", "Card", Money(4776), Money(4776), Money.ZERO, Money.ZERO, "CARD")),
        taxes = listOf(TaxLine(SagePoppy.salesTax(), Money(370))),
        ageVerifiedAt = 21,
    )
    private val spPolicy = ReceiptPolicy.Standard(
        "SAGE & POPPY — BOTTLE SHOP",
        listOf(SagePoppy.ADDRESS),
        "Thank you! · ¡Gracias! · 21+ for alcohol / 21+ para alcohol",
        showTax = false, locale = LocaleCode.ES, retail = true, usDates = true, headerRule = true,
        phone = SagePoppy.PHONE,
    )

    private fun shoot(name: String, lines: List<PrintLine>) {
        val dir = System.getenv("RECEIPT_SHOTS_DIR")?.takeIf { it.isNotBlank() } ?: return
        val img = ThermalReceiptRenderer.renderImage(lines)
        File(dir).mkdirs()
        ImageIO.write(img, "png", File(dir, name))
    }

    private fun texts(lines: List<PrintLine>): List<String> = lines.flatMap {
        when (it) {
            is PrintLine.KeyValue -> listOf(it.left, it.right)
            is PrintLine.Text -> listOf(it.text)
            is PrintLine.Header -> listOf(it.text)
            is PrintLine.Large -> listOf(it.text)
            else -> emptyList()
        }
    }

    @Test
    fun raleighPubReceiptAndBillAreEnglishOnly() {
        val receipt = ReceiptRenderer.render(pubReceipt, pubPolicy, ReceiptKind.FINAL)
        val bill = ReceiptRenderer.render(pubReceipt, pubPolicy, ReceiptKind.PROVISIONAL)
        val kv = receipt.filterIsInstance<PrintLine.KeyValue>().associate { it.left to it.right }
        assertEquals("73.00", kv["Subtotal"])
        assertEquals("4.93", kv["NC sales tax 6.75%"])
        assertEquals("0.73", kv["Wake prepared food tax 1%"])
        assertEquals("78.66", kv["Total"])
        // cash still rounds to the nickel, and the receipt says so
        assertEquals("-0.01", kv["Rounding"])
        assertEquals("78.65", kv["Cash total"])
        assertEquals("21.35", kv["Change"])
        assertEquals(PrintLine.Text("412 Lantern Row, Raleigh, NC 27601", Align.CENTER), receipt[1])
        assertEquals(PrintLine.Text("Tel. (919) 555-0142", Align.CENTER), receipt[2])
        assertTrue(bill.any { it is PrintLine.Header && it.text == "*** CUSTOMER BILL ***" })
        assertTrue(bill.any { it is PrintLine.Header && it.text == "*** NOT A RECEIPT ***" })
        // no French, no GST/QST, no registration numbers
        val all = texts(receipt) + texts(bill)
        for (gone in listOf("GST", "QST", "TPS", "TVQ", " no. ", "ADDITION", "REÇU", "Merci", "Montréal", "Sous-total")) {
            assertTrue(all.none { gone in it }, "'$gone' on an English receipt: $all")
        }
        shoot("receipt-en.png", receipt)
        shoot("bill-en.png", bill)
    }

    @Test
    fun aFrenchReprintStillWorks() {
        val fr = pubPolicy.withLocale(LocaleCode.FR)
        val receipt = ReceiptRenderer.render(pubReceipt, fr, ReceiptKind.FINAL)
        val bill = ReceiptRenderer.render(pubReceipt, fr, ReceiptKind.PROVISIONAL)
        val kv = receipt.filterIsInstance<PrintLine.KeyValue>().associate { it.left to it.right }
        assertEquals("73.00", kv["Sous-total"])
        assertEquals("4.93", kv["Taxe de vente (C.-N.) 6,75\u00A0%"])
        assertEquals("0.73", kv["Taxe sur les repas (Wake) 1\u00A0%"])
        assertEquals("21.35", kv["Monnaie rendue"])
        assertTrue(bill.any { it is PrintLine.Header && it.text.startsWith("*** ADDITION") })
        assertEquals(PrintLine.Text("Tél. (919) 555-0142", Align.CENTER), receipt[2])
        // and Spanish, German, Afrikaans name the taxes too
        for ((lang, label) in listOf("es" to "Impuesto sobre las ventas de NC 6.75%", "de" to "Umsatzsteuer NC 6.75 %",
            "af" to "NC-verkoopbelasting 6.75%")) {
            val lines = ReceiptRenderer.render(pubReceipt, pubPolicy.withLocale(LocaleCode.of(lang)), ReceiptKind.FINAL)
            assertTrue(lines.filterIsInstance<PrintLine.KeyValue>().any { it.left == label && it.right == "4.93" },
                "$lang: ${texts(lines)}")
        }
        shoot("receipt-fr.png", receipt)
        shoot("bill-fr.png", bill)
    }

    @Test
    fun sagePoppyReceiptInSpanishWearsTheLetterhead() {
        val lines = ReceiptRenderer.render(spReceipt, spPolicy, ReceiptKind.FINAL)
        assertEquals(PrintLine.LogoPlaceholder("SAGE & POPPY — BOTTLE SHOP"), lines.first())
        // address, phone, then the rule under the letterhead
        assertEquals(PrintLine.Text("Tel. ${SagePoppy.PHONE}", Align.CENTER), lines[2])
        assertEquals(PrintLine.Divider, lines[3])
        val kv = lines.filterIsInstance<PrintLine.KeyValue>().associate { it.left to it.right }
        assertEquals("47.76", kv["Total"])
        assertEquals("3.70", kv["Impuesto sobre las ventas 9.5%"])
        assertTrue("Caja 1" in kv.keys)
        shoot("receipt-sp-es.png", lines)
    }
}
