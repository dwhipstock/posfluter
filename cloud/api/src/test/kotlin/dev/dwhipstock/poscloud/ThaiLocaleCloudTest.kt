package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.menu.DataLang
import dev.dwhipstock.poscloud.menu.MenuCategoryDto
import dev.dwhipstock.poscloud.menu.MenuItemDto
import dev.dwhipstock.poscloud.menu.MenuResponse
import dev.dwhipstock.poscloud.menu.MenuVariantDto
import dev.dwhipstock.poscloud.menuai.AiGuard
import dev.dwhipstock.poscloud.menuai.MenuAiService
import dev.dwhipstock.poscloud.menuprint.MenuKind
import dev.dwhipstock.poscloud.menuprint.MenuPdf
import dev.dwhipstock.poscloud.menuprint.Paper
import dev.dwhipstock.poscloud.menuprint.Policies
import dev.dwhipstock.poscloud.menuprint.PrintBrand
import dev.dwhipstock.poscloud.menuprint.PrintBrandInput
import dev.dwhipstock.poscloud.menuprint.PrintCatalog
import dev.dwhipstock.poscloud.menuprint.PrintDoc
import dev.dwhipstock.poscloud.menuprint.PrintSelect
import dev.dwhipstock.poscloud.menuprint.PrintStyles
import dev.dwhipstock.poscloud.menuprint.PrintWords
import dev.dwhipstock.poscloud.menuprint.TodayRules
import dev.dwhipstock.poscloud.rooms.AiText
import dev.dwhipstock.poscloud.rooms.RoomReplies
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Thai locale in the cloud: the printed menu's words, the data-language
 * slot pick (a French store unchanged, a Thai store reading its `*_fr` slot as
 * Thai), Thai glyphs in the PDF, and the assistants' fixed Thai replies.
 */
class ThaiLocaleCloudTest {
    private val thai = Regex("[\\u0E00-\\u0E7F]")
    private fun isThai(s: String) = thai.containsMatchIn(s)

    // --- the printed menu's words ---

    @Test
    fun everyPrintedWordHasThai() {
        assertTrue("th" in PrintWords.LANGS)
        for (kind in MenuKind.entries) assertTrue(isThai(PrintWords.title(kind, "th")), "title $kind")
        for (s in listOf(
            PrintWords.specials("th"), PrintWords.footer("th", alcohol = true), PrintWords.footer("th", alcohol = false),
            PrintWords.today("th"), PrintWords.page("th"), PrintWords.days(emptyList(), "th"),
            PrintWords.specialName(null, listOf("mon"), "16:00", "th"), PrintWords.specialName(null, emptyList(), null, "th"),
        )) assertTrue(isThai(s), s)
        for (d in TodayRules.DAYS) assertTrue(isThai(PrintWords.dayName(d, "th")), d)
        assertEquals("โปรโมชันวันอังคาร", PrintWords.specialName(null, listOf("tue"), null, "th"))
        assertEquals("จ.–ศ.", PrintWords.days(listOf("mon", "tue", "wed", "thu", "fri"), "th"))
        assertEquals("เฉพาะ ศ. และ ส.", PrintWords.onlyOn(listOf("fri", "sat"), "th"))
        assertEquals("16:00–18:00 น.", PrintWords.window("16:00", "18:00", "th"))
        // the footers are friendly lines, never a "policy" the print strips
        assertFalse(Policies.statesPolicy(PrintWords.footer("th", true)))
        assertTrue(Policies.statesPolicy("เฉพาะลูกค้าที่นั่งทานที่ร้านเท่านั้น"))
    }

    @Test
    fun thaiDatesUseThaiMonthsAndTheBuddhistEra() {
        val d = LocalDate.of(2026, 10, 9)
        assertEquals("วันศุกร์ที่ 9 ตุลาคม", PrintWords.date(d, "th"))
        assertEquals(2569, PrintWords.year(d, "th"))
        assertEquals(2026, PrintWords.year(d, "en"))
        assertEquals("9 ตุลาคม 2569", PrintWords.dateWithYear(d, "th"))
        assertEquals("1 มกราคม 2570", PrintWords.dateWithYear(LocalDate.of(2027, 1, 1), "th"))
        // the other languages are unchanged
        assertEquals("Friday, October 9", PrintWords.date(d, "en"))
        assertEquals("vendredi 9 octobre", PrintWords.date(d, "fr"))
    }

    @Test
    fun bahtPrintsWithItsSign() {
        assertEquals("฿1,234.50", PrintWords.money(123450, "THB"))
        assertEquals("$5.00", PrintWords.money(500, "CAD"))
    }

    // --- the data language ---

    @Test
    fun theDataLanguageRule() {
        assertEquals("fr", DataLang.resolve(null, listOf("en", "fr", "es", "de", "af")))
        assertNull(DataLang.resolve(null, listOf("en", "es")))
        assertEquals("fr", DataLang.of(null, listOf("en", "es")) { emptySequence() })
        assertEquals("th", DataLang.resolve(null, listOf("th", "en")))
        assertEquals("th", DataLang.resolve("th", listOf("en", "fr")))
        assertEquals("fr", DataLang.resolve("xx", listOf("en", "fr")))
        assertEquals("th", DataLang.detect(sequenceOf("Burger", "ต้มยำกุ้ง")))
        assertEquals("fr", DataLang.detect(sequenceOf("Burger du pub", "Entrées")))
        assertEquals(setOf("es", "de", "af", "th"), MenuAiService.extraLangs("fr"))
        assertEquals(setOf("es", "de", "af"), MenuAiService.extraLangs("th"))
        assertEquals("Thai", MenuAiService.LANGUAGE_NAMES["th"])
    }

    private val thaiMenu = MenuResponse(
        categories = listOf(
            MenuCategoryDto("mains", "อาหารจานหลัก", "Mains", 0),
            MenuCategoryDto("drinks", "เครื่องดื่ม", "Drinks", 1),
        ),
        items = listOf(
            MenuItemDto("padthai", "ผัดไทยกุ้งสด", "Pad Thai with Prawns", "เส้นจันท์ผัดกับกุ้งสด ถั่วงอก และกุยช่าย",
                "Rice noodles, fresh prawns, bean sprouts, chives.", "mains", null, false, true, null,
                listOf(MenuVariantDto("padthai:r", "ธรรมดา", "Regular", 18000, 0), MenuVariantDto("padthai:l", "พิเศษ", "Large", 22000, 1)), "v1"),
            MenuItemDto("tomyum", "ต้มยำกุ้งน้ำข้น", "Tom Yum Goong", "", "Hot and sour prawn soup.", "mains", null, false, true, null,
                listOf(MenuVariantDto("tomyum:r", "ถ้วย", "Bowl", 25000, 0)), "v1"),
            MenuItemDto("tea", "ชาไทยเย็น", "Thai Iced Tea", "", "", "drinks", null, false, true, null,
                listOf(MenuVariantDto("tea:r", "แก้ว", "Glass", 6500, 0)), "v1"),
        ),
    )

    @Test
    fun aThaiStoreReadsItsLocalSlotAsThai() {
        val th = PrintCatalog.of(thaiMenu, "th", "th")
        assertEquals("ผัดไทยกุ้งสด", th.item("padthai")!!.name)
        assertEquals("พิเศษ", th.item("padthai")!!.sizes[1].label)
        assertEquals("อาหารจานหลัก", th.categoryName("mains"))
        assertTrue(isThai(th.item("padthai")!!.description))
        // English facts for the image prompts stay English
        assertEquals("Pad Thai with Prawns", th.item("padthai")!!.enName)
        val en = PrintCatalog.of(thaiMenu, "en", "th")
        assertEquals("Pad Thai with Prawns", en.item("padthai")!!.name)
        assertEquals("Mains", en.categoryName("mains"))
        // without the data language (an older caller) Thai has no slot: English
        assertEquals("Pad Thai with Prawns", PrintCatalog.of(thaiMenu, "th").item("padthai")!!.name)
    }

    @Test
    fun aFrenchStoreIsUnchanged() {
        for (lang in PrintWords.LANGS) {
            val before = PrintCatalog.of(MenuPrintFixtures.menu, lang)
            val after = PrintCatalog.of(MenuPrintFixtures.menu, lang, "fr")
            assertEquals(before.items.map { it.name to it.description }, after.items.map { it.name to it.description }, lang)
            assertEquals(before.categories.map { it.name }, after.categories.map { it.name }, lang)
        }
        val fr = PrintCatalog.of(MenuPrintFixtures.menu, "fr", "fr")
        assertEquals("Burger du pub", fr.item("burger")!!.name)
        // Thai at a French store: no translation there, so English
        assertEquals("Pub Burger", PrintCatalog.of(MenuPrintFixtures.menu, "th", "fr").item("burger")!!.name)
    }

    // --- the PDF ---

    @Test
    fun aThaiMenuEmbedsTheThaiFont() {
        val zone = ZoneId.of("Asia/Bangkok")
        val moment = TodayRules.moment(ZonedDateTime.of(2026, 10, 6, 17, 0, 0, 0, zone).toInstant(), zone)
        val brand = PrintBrand.of(PrintBrandInput(name = "Test Kitchen", primary = "#17456E", accent = "#8C4A1C", font = "inter"))
        val c = PrintCatalog.of(thaiMenu, "th", "th")
        val cands = PrintSelect.candidates(MenuKind.FULL, c, moment.day)
        val plan = PrintSelect.plain(MenuKind.FULL, c, cands, "th")
        for (style in listOf(PrintStyles.MODERN, PrintStyles.CLASSIC)) {
            val doc = PrintDoc(MenuKind.FULL, "th", Paper.A4, PrintStyles.of(style, brand), brand, "Test Kitchen", "THB", plan, c, moment, false)
            val pdf = MenuPdf.render(doc).pdf
            System.getenv("MENU_PRINT_SAMPLES_DIR")?.takeIf { it.isNotBlank() }
                ?.let { java.io.File(it).apply { mkdirs() }.resolve("thai-$style.pdf").writeBytes(pdf) }
            Loader.loadPDF(pdf).use { d ->
                val fonts = d.pages.flatMap { p -> p.resources.fontNames.map { p.resources.getFont(it).name.orEmpty() } }
                assertTrue(fonts.any { "NotoSansThai" in it }, "$style: $fonts")
                val text = PDFTextStripper().getText(d)
                assertTrue("ผัดไทยกุ้งสด" in text, text)
                assertTrue("ต้มยำกุ้งน้ำข้น" in text, text)
                assertTrue("฿180.00" in text, text)
            }
        }
        // a Latin-only menu still uses the brand's font for its text
        val en = PrintCatalog.of(thaiMenu, "en", "th")
        val enPlan = PrintSelect.plain(MenuKind.FULL, en, PrintSelect.candidates(MenuKind.FULL, en, moment.day), "en")
        val enPdf = MenuPdf.render(PrintDoc(MenuKind.FULL, "en", Paper.A4, PrintStyles.of(PrintStyles.MODERN, brand), brand,
            "Test Kitchen", "THB", enPlan, en, moment, false)).pdf
        Loader.loadPDF(enPdf).use { d ->
            val fonts = d.pages.flatMap { p -> p.resources.fontNames.map { p.resources.getFont(it).name.orEmpty() } }
            assertTrue(fonts.any { "Inter" in it }, fonts.toString())
        }
    }

    // --- the assistants' fixed replies ---

    @Test
    fun theAssistantsAnswerInThai() {
        for (r in AiGuard.Refusal.entries) {
            val t = AiGuard.reply(r, "th")
            assertTrue(isThai(t), r.name)
            assertNotEquals(AiGuard.reply(r, "en"), t)
        }
        for (r in RoomReplies.R.entries) assertTrue(isThai(RoomReplies.reply(r, "th")), r.name)
        assertTrue(isThai(AiText.locked(listOf("5"), "th")))
        assertTrue("th" in MenuAiService.LANGS)
    }
}
