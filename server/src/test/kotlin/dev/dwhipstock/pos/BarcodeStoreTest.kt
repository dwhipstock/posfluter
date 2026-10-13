package dev.dwhipstock.pos

import dev.dwhipstock.pos.aiphotos.HouseStyle
import dev.dwhipstock.pos.customers.barcode.Barcode
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Barcode Pub (Bangkok): its own brand, Thai + English, THB, and a BLANK store. */
class BarcodeStoreTest {
    private fun tempDb() = Files.createTempDirectory("pos-barcode").resolve("pos.db").toString()

    private fun ApplicationTestBuilder.store(seedMode: String = "copperlantern") = application {
        module(
            dbPath = tempDb(), receiptsDir = Files.createTempDirectory("rc").toString(),
            venueId = Barcode.VENUE_ID, barcode = true, seedMode = seedMode,
            physicalPrinterEnabled = false,
        )
    }

    @Test
    fun healthDescribesABangkokPub() = testApplication {
        store()
        val h = Json.parseToJsonElement(client.get("/health").bodyAsText()).jsonObject
        assertEquals("barcode", h["brand"]!!.jsonPrimitive.content)
        assertEquals("barcode-pub", h["venueId"]!!.jsonPrimitive.content)
        assertEquals("Barcode Pub", h["venue"]!!.jsonPrimitive.content)
        assertEquals("restaurant", h["kind"]!!.jsonPrimitive.content)
        assertEquals("TH", h["country"]!!.jsonPrimitive.content)
        assertEquals("THB", h["currency"]!!.jsonPrimitive.content)
        assertEquals(listOf("th", "en"), h["locales"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(20, h["legalAge"]!!.jsonPrimitive.content.toInt())
        // THB: cash rounds down to the whole baht
        assertEquals("unit-down", h["cashRounding"]!!.jsonPrimitive.content)
    }

    @Test
    fun startsBlankWhateverTheSeedModeWithOneManager() {
        for (mode in listOf("copperlantern", "none")) testApplication {
            store(mode)
            val mgr = loginClient("1234")
            assertEquals("[]", mgr.get("/items").bodyAsText().replace(" ", ""), "items ($mode)")
            assertEquals("[]", mgr.get("/categories").bodyAsText().replace(" ", ""), "categories ($mode)")
            assertEquals("[]", mgr.get("/zones").bodyAsText().replace(" ", ""), "zones ($mode)")
            val s = Json.parseToJsonElement(mgr.get("/settings").bodyAsText()).jsonObject
            assertEquals(Barcode.PHONE, s["venuePhone"]!!.jsonPrimitive.content)
            assertEquals(Barcode.ADDRESS, s["venueAddress"]!!.jsonPrimitive.content)
            assertTrue("ขอบคุณที่ใช้บริการ" in s["receiptFooter"]!!.jsonPrimitive.content)
            assertEquals(0, s["serviceChargePercent"]!!.jsonPrimitive.content.toInt())
            assertEquals(20_000L, s["corkagePerBottleCents"]!!.jsonPrimitive.long)
        }
    }

    @Test
    fun receiptAddressIsThaiOnAThaiReceiptEnglishOtherwise() {
        val byLocale = Barcode.addressLines(Barcode.ADDRESS)
        val en = dev.dwhipstock.pos.sdk.ReceiptPolicy.Standard(
            logoFallbackText = Barcode.DISPLAY_NAME, headerLines = byLocale[dev.dwhipstock.pos.sdk.i18n.LocaleCode.EN]!!,
            footerText = "", showTax = false, headerLinesByLocale = byLocale,
        )
        assertEquals(listOf(Barcode.ADDRESS), en.headerLines)
        val th = en.withLocale(Barcode.TH)
        assertEquals(listOf(Barcode.ADDRESS_TH), th.headerLines)
        assertEquals(listOf(Barcode.ADDRESS), th.withLocale(dev.dwhipstock.pos.sdk.i18n.LocaleCode.EN).headerLines)
        // an address the owner typed prints as typed, in every language
        assertTrue(Barcode.addressLines("Soi 31/3, Bangkok").isEmpty())
    }

    @Test
    fun receiptsPrintInThaiByDefaultWithBothNames() {
        // the policy the store builds from its settings: Thai, the Thai address, both names
        val policy = dev.dwhipstock.pos.sdk.ReceiptPolicy.Standard(
            logoFallbackText = Barcode.RECEIPT_NAME,
            headerLines = Barcode.addressLines(Barcode.ADDRESS)[Barcode.TH]!!,
            footerText = "", showTax = false, locale = Barcode.TH,
            headerLinesByLocale = Barcode.addressLines(Barcode.ADDRESS),
        )
        assertEquals(dev.dwhipstock.pos.sdk.i18n.LocaleCode.TH, policy.locale)
        assertEquals(listOf(Barcode.ADDRESS_TH), policy.headerLines)
        assertTrue("บาร์โค้ด" in Barcode.RECEIPT_NAME && "Barcode Pub" in Barcode.RECEIPT_NAME)
        // the receipt raster picks the Thai font for the name line
        assertTrue(dev.dwhipstock.pos.sdk.ThermalReceiptRenderer.renderImage(
            listOf(dev.dwhipstock.pos.sdk.PrintLine.Text(Barcode.RECEIPT_NAME, dev.dwhipstock.pos.sdk.Align.CENTER)),
        ).height > 0)
    }

    @Test
    fun vatIsSevenPercentInsideThePricesAndNotPrinted() {
        val vat = Barcode.VAT
        assertEquals("VAT", vat.code)
        assertEquals(0, vat.ratePercent.compareTo(java.math.BigDecimal("7")))
        val policy = dev.dwhipstock.pos.sdk.TaxPolicy.InclusiveTax(vat, showOnReceipt = false)
        // ฿107.00 holds ฿7.00 of VAT, and the total stays ฿107.00
        val a = policy.assess(dev.dwhipstock.pos.sdk.Money(10_700))
        assertEquals(700L, a.taxIncluded.cents)
        assertEquals(0L, a.taxAdded.cents)
        assertFalse(a.showOnReceipt)
    }

    @Test
    fun staffAppWearsTheBarcodeSkin() = testApplication {
        store()
        val page = client.get("/staff-app").bodyAsText()
        assertTrue("<body class=\"brand-bc\">" in page)
        assertTrue("<title>Barcode Pub — Staff</title>" in page)
        assertFalse("Copper Lantern POS</div>" in page)
    }

    @Test
    fun aiPhotosHaveTheirOwnBarStyle() {
        val style = HouseStyle.forBrand("barcode")
        assertEquals("barcode", style.brand)
        assertTrue("Bangkok" in style.scene && "yellow LED" in style.scene)
        assertEquals(HouseStyle.COPPER_LANTERN, HouseStyle.forBrand("copper-lantern"))
    }
}
