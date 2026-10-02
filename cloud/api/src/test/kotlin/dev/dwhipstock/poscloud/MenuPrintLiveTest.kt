package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.menuai.GeminiMenuModel
import dev.dwhipstock.poscloud.menuai.ImageGen
import dev.dwhipstock.poscloud.menuai.MenuAiService
import dev.dwhipstock.poscloud.menuprint.ArtMaker
import dev.dwhipstock.poscloud.menuprint.ArtPrompts
import dev.dwhipstock.poscloud.menuprint.MenuKind
import dev.dwhipstock.poscloud.menuprint.MenuPdf
import dev.dwhipstock.poscloud.menuprint.Paper
import dev.dwhipstock.poscloud.menuprint.PrintAi
import dev.dwhipstock.poscloud.menuprint.PrintBrand
import dev.dwhipstock.poscloud.menuprint.PrintBrandInput
import dev.dwhipstock.poscloud.menuprint.PrintCatalog
import dev.dwhipstock.poscloud.menuprint.PrintDoc
import dev.dwhipstock.poscloud.menuprint.PrintSelect
import dev.dwhipstock.poscloud.menuprint.PrintStyles
import dev.dwhipstock.poscloud.menuprint.PrintWords
import dev.dwhipstock.poscloud.menuprint.TodayRules
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in, online: the real Gemini writer and image service on the test
 * menu. Off unless MENU_PRINT_LIVE=1 and MENU_AI_GEMINI_API_KEY are set
 * (MENU_AI_BFL_API_KEY too for FLUX art). PDFs go to MENU_PRINT_SAMPLES_DIR.
 * Never runs in CI.
 */
class MenuPrintLiveTest {
    @Test
    fun liveSamples() {
        Assume.assumeTrue(System.getenv("MENU_PRINT_LIVE") == "1")
        // keys from env, or from a dotenv file named by MENU_PRINT_KEYS_FILE (GEMINI_API_KEY / BFL_API_KEY lines)
        val file = System.getenv("MENU_PRINT_KEYS_FILE")?.let(::File)?.takeIf { it.isFile }?.readLines().orEmpty()
            .mapNotNull { l -> l.substringBefore('=', "").trim().takeIf { it.isNotEmpty() }?.let { it to l.substringAfter('=').trim() } }.toMap()
        fun secret(vararg names: String) = names.firstNotNullOfOrNull { n -> System.getenv(n)?.takeIf { it.isNotBlank() } ?: file[n]?.takeIf { it.isNotBlank() } }
        val key = secret("MENU_AI_GEMINI_API_KEY", "GEMINI_API_KEY")
        Assume.assumeTrue(key != null)
        TestSupport.reset()
        val out = File(System.getenv("MENU_PRINT_SAMPLES_DIR") ?: "build/menu-print-live").apply { mkdirs() }
        val model = GeminiMenuModel(key!!, System.getenv("MENU_PRINT_MODEL") ?: "gemini-3.5-flash", budgetMs = 20_000, temperature = 0.9)
        val images = ImageGen.from(secret("MENU_AI_BFL_API_KEY", "BFL_API_KEY"), key)
        val maker = ArtMaker(images)
        val zone = ZoneId.of("America/New_York")
        // the real clock in the store's zone, as a real print
        val friday = TodayRules.moment(java.time.Instant.now(), zone)
        val brand = PrintBrand.of(PrintBrandInput(name = "Test Tavern", primary = "#17456E", accent = "#8C4A1C", text = "#1C2733", muted = "#3E362D", font = "inter"))
        data class Case(val name: String, val kind: MenuKind, val lang: String, val style: String?, val notes: String?, val photos: Boolean)
        val cases = (System.getenv("MENU_PRINT_LIVE_CASES") ?: "full-classic,flyer,today-de,highlights").split(',').map { it.trim() }
        val all = listOf(
            Case("full-classic", MenuKind.FULL, "en", PrintStyles.CLASSIC, "cosy neighbourhood pub, mention the fireplace", false),
            Case("flyer", MenuKind.FLYER, "en", null, "fall theme, mention the patio", false),
            Case("today-de", MenuKind.TODAY, "de", null, null, false),
            Case("full-autumn", MenuKind.FULL, "en", PrintStyles.AUTUMN, "fall theme", false),
            Case("highlights", MenuKind.HIGHLIGHTS, "en", null, "bright summer patio", true),
        ).filter { it.name in cases }
        for (case in all) {
            val c = PrintCatalog.of(MenuPrintFixtures.menu, case.lang)
            val cands = PrintSelect.candidates(case.kind, c, friday.day)
            val notes = PrintAi.notes(case.notes)
            val t0 = System.currentTimeMillis()
            val reply = model.complete(
                PrintAi.system(case.kind, case.lang, MenuAiService.LANGUAGE_NAMES.getValue(case.lang), PrintWords.dayName(friday.day, "en"), PrintSelect.flyerHasSpecials(c)),
                PrintAi.user(PrintAi.menuData(c, cands, case.lang), notes), null)
            val aiMs = System.currentTimeMillis() - t0
            File(out, "${case.name}-reply.json").writeText(reply)
            val ai = PrintAi.parse(reply, case.kind, c, cands, case.lang, listOf("Test Tavern", "Test Tavern — Riverside"))
            assertNotNull(ai, reply)
            val (styleKey, by) = PrintStyles.choose(case.style, ai.style, case.notes, case.kind)
            val style = PrintStyles.of(styleKey, brand)
            val slots = ArtPrompts.slots("live", case.kind, style, ai.plan, c, ai.artScene, notes, false, case.photos, Paper.LETTER.ratio)
            val t1 = System.currentTimeMillis()
            val made = maker.make("live", slots, fresh = false)
            val artMs = System.currentTimeMillis() - t1
            val photos = made.pictures.filterKeys { it.startsWith("photo:") }.mapKeys { it.key.removePrefix("photo:") }.mapValues { it.value.bytes }
            val doc = PrintDoc(case.kind, case.lang, Paper.LETTER, style, brand, "Test Tavern — Riverside", "CAD", ai.plan, c, friday, case.photos, photos, made.pictures)
            val t2 = System.currentTimeMillis()
            val r = MenuPdf.render(doc)
            File(out, "${case.name}.pdf").writeBytes(r.pdf)
            println("LIVE ${case.name}: style $styleKey (${by.code}), ai ${aiMs}ms, art ${made.made} made/${made.reused} reused/${made.failed} failed in ${artMs}ms, " +
                "pdf ${r.pages}p ${r.pdf.size / 1024}KB in ${System.currentTimeMillis() - t2}ms; title '${ai.plan.title}'")
            assertTrue(r.pdf.size < 10 * 1024 * 1024)
        }
    }
}
