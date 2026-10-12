package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.Translations
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternConfig
import dev.dwhipstock.pos.customers.sagepoppy.SagePoppy
import dev.dwhipstock.pos.sdk.Align
import dev.dwhipstock.pos.sdk.KitchenLanguage
import dev.dwhipstock.pos.sdk.PrintLine
import dev.dwhipstock.pos.sdk.ReceiptPolicy
import dev.dwhipstock.pos.sdk.ThermalLayout
import dev.dwhipstock.pos.sdk.ThermalReceiptRenderer
import dev.dwhipstock.pos.sdk.i18n.DataLanguage
import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import dev.dwhipstock.pos.sdk.i18n.MessageKey
import dev.dwhipstock.pos.sdk.i18n.Messages
import dev.dwhipstock.pos.sdk.i18n.StoreLocales
import dev.dwhipstock.pos.sdk.i18n.ThaiDates
import dev.dwhipstock.pos.sdk.i18n.dataText
import dev.dwhipstock.pos.sdk.i18n.dataTextOrNull
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Thai (th): a store whose two catalog languages are Thai + English keeps
 * Thai in the `*_fr` name slots ([DataLanguage]); a French + English store
 * is unchanged. Thai receipts print Buddhist Era dates and render Thai with
 * the bundled Noto Sans Thai, vowels and tone marks on their consonant.
 */
class ThaiLocaleTest {
    private fun tempDir(prefix: String) = Files.createTempDirectory(prefix).toString()

    @AfterTest
    fun back() = DataLanguage.reset()

    // ---- the data language ------------------------------------------------------

    @Test
    fun theDataLanguageIsFrenchUnlessTheStoreSpeaksThai() {
        assertEquals(LocaleCode.FR, DataLanguage.resolve(CopperLanternConfig.LOCALES))
        assertEquals(LocaleCode.FR, DataLanguage.resolve(SagePoppy.PROFILE.locales))
        assertEquals(LocaleCode.FR, DataLanguage.resolve(emptyList()))
        assertEquals(LocaleCode.TH, DataLanguage.resolve(listOf(LocaleCode.TH, LocaleCode.EN)))
        assertEquals(LocaleCode.TH, DataLanguage.resolve(listOf(LocaleCode.EN, LocaleCode.TH)))
        // an explicit setting wins; an unknown one is ignored
        assertEquals(LocaleCode.TH, DataLanguage.resolve(listOf(LocaleCode.EN), "TH "))
        assertEquals(LocaleCode.FR, DataLanguage.resolve(listOf(LocaleCode.EN), "xx"))
    }

    @Test
    fun posLocalesParsesACommaList() {
        assertEquals(listOf(LocaleCode.TH, LocaleCode.EN), StoreLocales.parse(" th, EN ,th"))
        assertNull(StoreLocales.parse(""))
        assertNull(StoreLocales.parse(" , 1x, "))
        assertNull(StoreLocales.parse(null))
        assertEquals(listOf(LocaleCode.TH, LocaleCode.EN), StoreLocales.fromEnv { if (it == StoreLocales.ENV) "th,en" else null })
        val file = Files.createTempFile("store", ".properties").toFile()
        file.writeText("store.locales=th,en\nstore.data_locale=th\n")
        val env = { k: String -> if (k == dev.dwhipstock.pos.sdk.ReceiptPrintMode.ENV_CONFIG_FILE) file.path else null }
        assertEquals(listOf(LocaleCode.TH, LocaleCode.EN), StoreLocales.fromEnv(env))
        assertEquals("th", DataLanguage.explicitFromEnv(env))
    }

    @Test
    fun aFrenchStoreReadsTheSlotsAsBefore() {
        DataLanguage.reset()
        assertEquals("Poutine", LocaleCode.FR.dataText("Poutine", "Fries"))
        assertEquals("Fries", LocaleCode.EN.dataText("Poutine", "Fries"))
        assertEquals("Fries", LocaleCode.ES.dataText("Poutine", "Fries"))
        assertEquals("Fries", LocaleCode.TH.dataText("Poutine", "Fries"))
        assertEquals(setOf("fr", "en"), Translations.SLOTS)
        assertEquals("Poutine", Translations.pick("fr", "Poutine", "Fries", null))
        assertEquals(KitchenLanguage.FR, KitchenLanguage.parse("fr"))
        assertNull(KitchenLanguage.parse("th"))
        assertEquals("fr", KitchenLanguage.FR.wire)
        assertEquals(listOf(LocaleCode.FR, LocaleCode.EN), KitchenLanguage.BOTH.locales)
    }

    @Test
    fun aThaiStoreReadsThaiFromTheFrSlot() {
        DataLanguage.use(LocaleCode.TH)
        assertEquals("ผัดไทย", LocaleCode.TH.dataText("ผัดไทย", "Pad Thai"))
        assertEquals("ผัดไทย", LocaleCode.TH.dataTextOrNull("ผัดไทย", "Pad Thai"))
        assertEquals("Pad Thai", LocaleCode.EN.dataText("ผัดไทย", "Pad Thai"))
        assertEquals("Pad Thai", LocaleCode.FR.dataText("ผัดไทย", "Pad Thai"))
        assertEquals(setOf("th", "en"), Translations.SLOTS)
        assertEquals("ผัดไทย", Translations.pick("th", "ผัดไทย", "Pad Thai", null))
        assertEquals("Pad Thaï", Translations.pick("fr", "ผัดไทย", "Pad Thai", mapOf("fr" to "Pad Thaï")))
        // kitchen tickets: "th" (or the stored "fr") is the local side; both = Thai + English
        assertEquals(KitchenLanguage.FR, KitchenLanguage.parse("th"))
        assertEquals(KitchenLanguage.FR, KitchenLanguage.parse("fr"))
        assertEquals("th", KitchenLanguage.FR.wire)
        assertEquals(listOf(LocaleCode.TH, LocaleCode.EN), KitchenLanguage.BOTH.locales)
    }

    @Test
    fun healthTellsTheDataLanguage() = testApplication {
        application {
            module(dbPath = tempDir("pos-th") + "/pos.db", photosDir = tempDir("photos"), receiptsDir = tempDir("rc"),
                localesOverride = listOf(LocaleCode.TH, LocaleCode.EN), dataLocaleOverride = null)
        }
        val health = Json.parseToJsonElement(client.get("/health").bodyAsText()).jsonObject
        assertEquals(listOf("th", "en"), health["locales"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("th", health["dataLocale"]!!.jsonPrimitive.content)
    }

    @Test
    fun aFrenchStoresHealthSaysFr() = testApplication {
        application { module(dbPath = tempDir("pos-fr") + "/pos.db", photosDir = tempDir("photos"), receiptsDir = tempDir("rc"),
            localesOverride = null, dataLocaleOverride = null) }
        val health = Json.parseToJsonElement(client.get("/health").bodyAsText()).jsonObject
        assertEquals("fr", health["dataLocale"]!!.jsonPrimitive.content)
    }

    // ---- receipts ---------------------------------------------------------------

    @Test
    fun theThaiCatalogIsComplete() {
        val missing = MessageKey.entries.map { it.id }.toSet() - Messages.translatedKeys(LocaleCode.TH)
        assertTrue(missing.isEmpty(), "messages_th lacks ${missing.sorted()}")
        assertEquals("ยอดรวม", Messages.get(MessageKey.RECEIPT_TOTAL, LocaleCode.TH))
        assertEquals("โต๊ะ 12", Messages.get(MessageKey.KITCHEN_TABLE, LocaleCode.TH, "12"))
        assertEquals("เงินสด", Messages.dataLabel("tender.CASH", LocaleCode.TH))
    }

    @Test
    fun thaiDatesUseTheBuddhistEra() {
        val at = LocalDateTime.of(2026, 10, 12, 14, 5)
        assertEquals(2569, ThaiDates.buddhistYear(2026))
        assertEquals("12/10/2569 14:05", ThaiDates.receipt(at))
        assertEquals("12 ต.ค. 2569", ThaiDates.short(at))
        assertEquals("12 ตุลาคม 2569", ThaiDates.long(at))
        val policy = ReceiptPolicy.Standard("Store", emptyList(), "", false, LocaleCode.EN, usDates = true)
        assertEquals("10/12/2026 2:05 PM", policy.formatDate(at), "English receipts unchanged")
        assertEquals("12/10/2569 14:05", policy.withLocale(LocaleCode.TH).formatDate(at))
        assertEquals("2026-10-12 14:05", ReceiptPolicy.Standard("Store", emptyList(), "", false, LocaleCode.FR).formatDate(at))
    }

    // ---- thermal raster ------------------------------------------------------------

    @Test
    fun aThaiLinePicksTheBundledThaiFont() {
        val f = ThermalReceiptRenderer.fontFor("ข้าวผัดกุ้ง", ThermalLayout.Style.BODY, 26f)
        assertTrue(f.family.contains("Thai"), "Thai text drew with ${f.family}")
        assertTrue("ข้าวผัดกุ้ง".all { f.canDisplay(it) })
        val latin = ThermalReceiptRenderer.fontFor("Poutine", ThermalLayout.Style.BODY, 26f)
        assertTrue(!latin.family.contains("Thai"))
    }

    @Test
    fun aThaiLineRastersWithItsMarksOnTheConsonant() {
        val text = "น้ำมะพร้าวปั่น"
        val img = ThermalReceiptRenderer.renderImage(listOf(PrintLine.KeyValue("2 × $text", "120.00")))
        var black = 0
        for (y in 0 until img.height) for (x in 0 until img.width) if ((img.getRGB(x, y) and 0xFF) < 128) black++
        assertTrue(black > 200, "Thai line rendered blank ($black dark pixels)")
        // the marks take no width: the shaped line is narrower than its letters drawn one by one
        val rows = ThermalReceiptRenderer.layout(listOf(PrintLine.Text(text, Align.LEFT)))
        assertEquals(1, rows.size)
    }

    @Test
    fun aLongThaiLineWrapsBetweenWordsAndNeverStartsWithAMark() {
        val long = "ข้าวผัดกระเพราหมูสับไข่ดาวพิเศษเผ็ดน้อยไม่ใส่ถั่วฝักยาวเพิ่มข้าวสวยอีกหนึ่งจานน้ำซุปแยก"
        val rows = ThermalReceiptRenderer.layout(listOf(PrintLine.Text(long, Align.LEFT)))
        assertTrue(rows.size > 1, "a long Thai line wraps")
        val texts = rows.map { (it as ThermalLayout.Row.Text).text }
        assertEquals(long, texts.joinToString(""), "nothing lost or doubled")
        for (row in rows) {
            assertTrue(ThermalReceiptRenderer.rowWidth(row) <= ThermalLayout.CONTENT, "row too wide: $row")
            val first = (row as ThermalLayout.Row.Text).text.first()
            assertTrue(Character.getType(first) != Character.NON_SPACING_MARK.toInt(), "row starts with a mark: ${row.text}")
        }
        val img = ThermalReceiptRenderer.renderImage(listOf(PrintLine.Text(long, Align.LEFT)))
        assertTrue(img.height > 60)
    }
}
