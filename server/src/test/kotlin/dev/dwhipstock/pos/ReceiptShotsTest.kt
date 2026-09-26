package dev.dwhipstock.pos

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
import dev.dwhipstock.pos.sdk.TaxComponent
import dev.dwhipstock.pos.sdk.TaxLine
import dev.dwhipstock.pos.sdk.ThermalReceiptRenderer
import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import java.io.File
import java.math.BigDecimal
import java.time.LocalDateTime
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A French pub receipt and bill and a Spanish Sage & Poppy receipt, through
 * the production thermal raster path. Always checks the text; with
 * RECEIPT_SHOTS_DIR set it also writes the bitmaps a printer would print:
 *
 *   RECEIPT_SHOTS_DIR=$PWD/../docs/screenshots/language-pass ./gradlew test --tests '*ReceiptShotsTest'
 */
class ReceiptShotsTest {
    private val gst = TaxComponent("GST", "TPS", "GST", BigDecimal("5"), "123456789 RT0001")
    private val qst = TaxComponent("QST", "TVQ", "QST", BigDecimal("9.975"), "1234567890 TQ0001")

    private val pubReceipt = Receipt(
        checkId = 1207, tableLabel = "B-4",
        openedAt = LocalDateTime.of(2026, 9, 25, 19, 5), closedAt = LocalDateTime.of(2026, 9, 25, 21, 12),
        items = listOf(
            ReceiptItem("Lager de la Lanterne", "Lantern House Lager", "Pichet 60 oz", "60 oz pitcher", 1, Money(2025), Money(2025), null),
            ReceiptItem("Poutine classique", "Classic Poutine", null, null, 2, Money(1300), Money(2600), "Sauce à part"),
            ReceiptItem("Ailes de poulet", "Chicken Wings", null, null, 1, Money(1675), Money(1675), null),
        ),
        fees = emptyList(),
        grandTotal = Money(7300 + 365 + 728),
        taxIncluded = Money.ZERO, taxRatePercent = null,
        tenders = listOf(ReceiptTender("Comptant", "Cash", Money(10000), Money(8393), Money(2), Money(1605), "CASH")),
        taxes = listOf(TaxLine(gst, Money(365)), TaxLine(qst, Money(728))),
        cashDue = Money(8395), cashRounding = Money(2),
    )
    private val pubPolicy = ReceiptPolicy.Standard(
        "Copper Lantern — Vieux-Port",
        listOf(CopperLanternVenue.VIEUX_PORT.address),
        "Merci de votre visite ! / Thank you for visiting!",
        showTax = false, locale = LocaleCode.FR, phone = CopperLanternVenue.VIEUX_PORT.phone,
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
        showTax = false, locale = LocaleCode.ES, retail = true, alwaysCents = true, usDates = true, headerRule = true,
        phone = SagePoppy.PHONE,
    )

    private fun shoot(name: String, lines: List<PrintLine>) {
        val dir = System.getenv("RECEIPT_SHOTS_DIR")?.takeIf { it.isNotBlank() } ?: return
        val img = ThermalReceiptRenderer.renderImage(lines)
        File(dir).mkdirs()
        ImageIO.write(img, "png", File(dir, name))
    }

    @Test
    fun frenchPubReceiptAndBill() {
        val receipt = ReceiptRenderer.render(pubReceipt, pubPolicy, ReceiptKind.FINAL)
        val bill = ReceiptRenderer.render(pubReceipt, pubPolicy, ReceiptKind.PROVISIONAL)
        val kv = receipt.filterIsInstance<PrintLine.KeyValue>().associate { it.left to it.right }
        assertEquals("73", kv["Sous-total"])
        assertEquals("3,65", kv["TPS/GST 5 %"])
        assertEquals("7,28", kv["TVQ/QST 9,975 %"])
        assertEquals("83,93", kv["Total"])
        assertEquals("16,05", kv["Monnaie rendue"])
        assertTrue(bill.any { it is PrintLine.Header && it.text == "*** ADDITION / CUSTOMER BILL ***" })
        assertEquals(PrintLine.Text("Tél. +1 514 555 0142", Align.CENTER), receipt[2])
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
