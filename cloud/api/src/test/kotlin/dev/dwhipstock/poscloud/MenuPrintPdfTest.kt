package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.menuprint.ArtPicture
import dev.dwhipstock.poscloud.menuprint.MenuKind
import dev.dwhipstock.poscloud.menuprint.MenuPdf
import dev.dwhipstock.poscloud.menuprint.Paper
import dev.dwhipstock.poscloud.menuprint.PrintBrand
import dev.dwhipstock.poscloud.menuprint.PrintBrandInput
import dev.dwhipstock.poscloud.menuprint.PrintCatalog
import dev.dwhipstock.poscloud.menuprint.PrintDoc
import dev.dwhipstock.poscloud.menuprint.PrintSelect
import dev.dwhipstock.poscloud.menuprint.PrintStyles
import dev.dwhipstock.poscloud.menuprint.PrintWords
import dev.dwhipstock.poscloud.menuprint.TodayRules
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The PDF itself: every menu kind in every language and style renders, and
 * the text read back out of it has the store's names and prices (and none
 * of the switched-off item). Set MENU_PRINT_SAMPLES_DIR to keep the PDFs.
 */
class MenuPrintPdfTest {
    private val zone = ZoneId.of("America/New_York")
    /** Tuesday 2026-10-06, 5 p.m. at the store: happy hour and the Tuesday burger. */
    private val tuesday = TodayRules.moment(ZonedDateTime.of(2026, 10, 6, 17, 0, 0, 0, zone).toInstant(), zone)
    private val samples = System.getenv("MENU_PRINT_SAMPLES_DIR")?.takeIf { it.isNotBlank() }?.let { File(it).apply { mkdirs() } }
    private val brand = PrintBrand.of(PrintBrandInput(name = "Test Tavern", primary = "#17456E", accent = "#8C4A1C", text = "#1C2733", muted = "#3E362D", font = "inter"))

    private fun doc(kind: MenuKind, lang: String, style: String, photos: Boolean = false, art: Map<String, ArtPicture> = emptyMap(),
                    paper: Paper = Paper.LETTER): PrintDoc {
        val c = PrintCatalog.of(MenuPrintFixtures.menu, lang)
        val cands = PrintSelect.candidates(kind, c, tuesday.day)
        val plan = PrintSelect.plain(kind, c, cands, lang)
        val pics = if (photos) cands.associate { it.id to MenuPrintFixtures.picture(400, 300) } else emptyMap()
        return PrintDoc(kind, lang, paper, PrintStyles.of(style, brand), brand, "Test Tavern — Riverside", "CAD", plan, c, tuesday, photos, pics, art)
    }

    /** The PDF's text, lowercased (headings and tags print in capitals). */
    private fun text(pdf: ByteArray): String = Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }.lowercase()

    private fun save(name: String, pdf: ByteArray) { samples?.let { File(it, name).writeBytes(pdf) } }

    @Test
    fun everyKindRendersInEveryLanguageWithTheStoresNamesAndPrices() {
        for (lang in PrintWords.LANGS) for (kind in MenuKind.entries) {
            val r = MenuPdf.render(doc(kind, lang, PrintStyles.MODERN))
            val t = text(r.pdf)
            assertTrue(r.pages >= 1)
            assertFalse("retired stew" in t || "ragoût retiré" in t, "$kind/$lang printed a switched-off item")
            // prices are North American in every language
            assertFalse(Regex("""\d+,\d{2}\s?\$""").containsMatchIn(t), "$kind/$lang: money not in \$1,234.56 form")
            if (kind == MenuKind.FULL) {
                val burger = if (lang == "fr") "burger du pub" else if (lang == "de") "pub-burger" else "pub burger"
                assertTrue(burger in t, "$kind/$lang: $burger missing")
                assertTrue("$16.95" in t && "$34.95" in t && "$24.00" in t, "$kind/$lang: prices missing")
            }
            save("${kind.code}-$lang.pdf", r.pdf)
        }
    }

    @Test
    fun theFullMenuTagsDayOnlyItemsAndBoxesTheSpecials() {
        val en = text(MenuPdf.render(doc(MenuKind.FULL, "en", PrintStyles.CLASSIC)).pdf)
        assertTrue("fri & sat only" in en, en)
        assertTrue("specials" in en && "happy hour" in en && "mon–fri · 4–6 pm" in en, en)
        assertTrue("wing wednesday" in en && "$8.95" in en, en)
        val de = text(MenuPdf.render(doc(MenuKind.FULL, "de", PrintStyles.CLASSIC)).pdf)
        assertTrue("nur fr & sa" in de, de)
        assertTrue("hochrippe" in de && "hähnchenflügel" in de && "angebote" in de, de)
    }

    @Test
    fun todaysMenuShowsTodaysSpecialsStruckThroughAndHidesDayOnlyItems() {
        val r = MenuPdf.render(doc(MenuKind.TODAY, "en", PrintStyles.CHALKBOARD))
        val t = text(r.pdf)
        save("today-en-chalkboard.pdf", r.pdf)
        // the dateline is letter-spaced: its extracted text has gaps
        assertTrue("tuesday,october6" in t.replace(" ", ""), t)
        assertFalse("prime rib" in t, "Prime Rib is sold Fri & Sat only")
        // the Tuesday burger: regular struck through, special price
        assertTrue("$16.95" in t && "$9.95" in t, t)
        assertTrue("happy hour 4–6 pm" in t && "$5.00" in t, t)
        // Wednesday's wings special is not today's
        assertFalse("$8.95" in t, t)
        val de = text(MenuPdf.render(doc(MenuKind.TODAY, "de", PrintStyles.CHALKBOARD)).pdf)
        assertTrue("dienstag,6.oktober" in de.replace(" ", ""), de)
        assertTrue("happy hour 16:00–18:00" in de || "happy hour 16:00–18:00" in de, de)
    }

    @Test
    fun theFlyerIsOnePageAndListsTheSpecials() {
        for (style in PrintStyles.KEYS) for (paper in Paper.entries) {
            val r = MenuPdf.render(doc(MenuKind.FLYER, "en", style, paper = paper))
            assertEquals(1, r.pages, "$style/$paper flyer")
            val t = text(r.pdf)
            assertTrue("house lager" in t && "$5.00" in t && "$9.95" in t, t)
            save("flyer-$style-${paper.name.lowercase()}.pdf", r.pdf)
        }
    }

    @Test
    fun aMultiPageMenuWithPhotosAndArtStaysASaneSize() {
        val art = mapOf(
            "hero" to ArtPicture(MenuPrintFixtures.picture(1536, 576), "image/jpeg", "fake", false),
            "deco:cat:beer" to ArtPicture(MenuPrintFixtures.picture(768, 768), "image/jpeg", "fake", false),
        )
        val d = doc(MenuKind.FULL, "fr", PrintStyles.AUTUMN, photos = true, art = art, paper = Paper.A4)
        // the AI's header and the beer section's picture are on the page; other sections wear the style's ornament
        val html = MenuPdf.html(d)
        assertEquals(1, Regex("class=\"deco\"").findAll(html).count())
        assertTrue("class=\"hero\"" in html && "class=\"orn\"" in html)
        val r = MenuPdf.render(d)
        assertTrue(r.pdf.size < 10 * 1024 * 1024, "${r.pdf.size} bytes")
        assertTrue(r.pages >= 1)
        save("full-fr-autumn-photos-a4.pdf", r.pdf)
        val prev = MenuPdf.previews(r.pdf)
        assertEquals(r.pages.coerceAtMost(6), prev.size)
        assertTrue(prev.all { it.startsWith("data:image/jpeg;base64,") })
    }

    @Test
    fun everythingIsEscaped() {
        val evil = MenuPrintFixtures.menu.copy(items = MenuPrintFixtures.menu.items.map {
            if (it.id == "nachos") it.copy(nameEn = "<script>alert(1)</script> & \"Nachos\"", descriptionEn = "<b>bold</b>") else it
        })
        val c = PrintCatalog.of(evil, "en")
        val plan = PrintSelect.plain(MenuKind.FULL, c, c.items, "en")
        val d = PrintDoc(MenuKind.FULL, "en", Paper.LETTER, PrintStyles.of(PrintStyles.MODERN, brand),
            PrintBrand.of(PrintBrandInput(name = "A <i>B</i> & C")), "Store <x>", "CAD", plan, c, tuesday, false)
        val html = MenuPdf.html(d)
        assertFalse("<script>" in html || "<b>bold" in html || "<i>B" in html || "<x>" in html)
        assertTrue("&lt;script&gt;" in html)
        assertTrue("<script>alert(1)</script>" in text(MenuPdf.render(d).pdf))
    }
}
