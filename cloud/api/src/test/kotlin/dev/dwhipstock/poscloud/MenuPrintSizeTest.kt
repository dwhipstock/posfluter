package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.menu.MenuItemDto
import dev.dwhipstock.poscloud.menu.MenuVariantDto
import dev.dwhipstock.poscloud.menuprint.ArtPicture
import dev.dwhipstock.poscloud.menuprint.MenuKind
import dev.dwhipstock.poscloud.menuprint.MenuPdf
import dev.dwhipstock.poscloud.menuprint.MenuPlan
import dev.dwhipstock.poscloud.menuprint.Paper
import dev.dwhipstock.poscloud.menuprint.PrintBrand
import dev.dwhipstock.poscloud.menuprint.PrintBrandInput
import dev.dwhipstock.poscloud.menuprint.PrintCatalog
import dev.dwhipstock.poscloud.menuprint.PrintDoc
import dev.dwhipstock.poscloud.menuprint.PrintSelect
import dev.dwhipstock.poscloud.menuprint.PrintStyles
import dev.dwhipstock.poscloud.menuprint.TodayRules
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory
import java.time.Instant
import java.time.ZoneId
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.assertTrue

/**
 * How big a printed menu gets: a 14-item pub menu with AI-length copy, a
 * header and a picture per section fits on two Letter pages; the worst
 * realistic print (60 items, every one with a big photo, all the art) stays
 * well inside the API's 512 MB heap (this suite runs with -Xmx512m, like the
 * container) and under 10 MB of PDF.
 */
class MenuPrintSizeTest {
    private val zone = ZoneId.of("America/New_York")
    private val moment = TodayRules.moment(Instant.parse("2026-10-06T21:00:00Z"), zone)
    private val brand = PrintBrand.of(PrintBrandInput(name = "Test Tavern", font = "inter"))

    /** Camera-like noise: the worst case for JPEG size and decode work. */
    private fun noisy(w: Int, h: Int, seed: Int): ByteArray {
        val r = Random(seed)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, (r.nextInt(80) + x * 150 / w shl 16) or (r.nextInt(80) + y * 150 / h shl 8) or r.nextInt(255))
        return ByteArrayOutputStream().use { ImageIO.write(img, "jpg", it); it.toByteArray() }
    }

    @Test
    fun aFourteenItemMenuWithAiCopyAndArtFitsOnTwoPages() {
        val c = PrintCatalog.of(MenuPrintFixtures.menu, "en")
        assertTrue(c.items.size == 14)
        val plain = PrintSelect.plain(MenuKind.FULL, c, c.items, "en")
        // AI-length copy: an intro per section, a full blurb per item, tagline and footer
        val plan = MenuPlan("The House Menu", "Hearty pub classics, local pours and a warm welcome every night of the week.",
            plain.sections.map { it.copy(intro = "Made fresh in our kitchen every day, with the best of what the season brings in.") },
            c.items.associate { it.id to "A generous, honest plate made with care, full of flavour and just right with a cold pint." },
            "Please tell your server about any allergies before you order. Thank you for joining us.")
        val art = mapOf("hero" to ArtPicture(MenuPrintFixtures.picture(1536, 576), "image/jpeg", "fake", false)) +
            plan.sections.associate { "deco:cat:${it.categoryId}" to ArtPicture(MenuPrintFixtures.picture(768, 768), "image/jpeg", "fake", false) }
        for (style in listOf(PrintStyles.CLASSIC, PrintStyles.AUTUMN, PrintStyles.MODERN)) {
            val r = MenuPdf.render(PrintDoc(MenuKind.FULL, "en", Paper.LETTER, PrintStyles.of(style, brand), brand, "Test Tavern", "CAD",
                plan, c, moment, false, emptyMap(), art))
            System.getenv("MENU_PRINT_SAMPLES_DIR")?.takeIf { it.isNotBlank() }?.let { java.io.File(it, "fit-$style.pdf").writeBytes(r.pdf) }
            assertTrue(r.pages <= 2, "$style: ${r.pages} pages")
        }
    }

    @Test
    fun theBiggestPrintFitsTheContainersHeap() {
        val base = MenuPrintFixtures.menu
        val items = (1..60).map { n ->
            MenuItemDto("x$n", "Plat $n", "Dish number $n", "", "A dish with a long and tasty description, number $n.",
                base.categories[n % base.categories.size].id, null, false, true, 1L,
                listOf(MenuVariantDto("x$n:r", "Régulier", "Regular", 1000L + n * 25, 0)), "vieux-port")
        }
        val c = PrintCatalog.of(base.copy(items = items), "en")
        val plan = PrintSelect.plain(MenuKind.FULL, c, c.items, "en")
        val photos = c.items.withIndex().associate { (i, it) -> it.id to noisy(1024, 1024, i) }
        val art = mapOf("hero" to ArtPicture(noisy(1536, 576, 99), "image/jpeg", "fake", false)) +
            plan.sections.associate { "deco:cat:${it.categoryId}" to ArtPicture(noisy(1024, 1024, it.title.hashCode()), "image/jpeg", "fake", false) }
        val doc = PrintDoc(MenuKind.FULL, "en", Paper.LETTER, PrintStyles.of(PrintStyles.CLASSIC, brand), brand, "Test Tavern", "CAD",
            plan, c, moment, true, photos, art)

        val mem = ManagementFactory.getMemoryMXBean()
        System.gc()
        val before = mem.heapMemoryUsage.used
        var peak = before
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = Thread { while (running.get()) { peak = maxOf(peak, mem.heapMemoryUsage.used); Thread.sleep(2) } }.apply { isDaemon = true; start() }
        val r = MenuPdf.render(doc)
        val previews = MenuPdf.previews(r.pdf)
        running.set(false); sampler.join()
        val max = mem.heapMemoryUsage.max
        val peakMb = (peak - before) / (1024 * 1024)
        println("MEMORY biggest print: ${r.pages} pages, ${r.pdf.size / 1024} KB PDF, ${previews.size} previews, " +
            "heap +$peakMb MB at peak (max heap ${max / (1024 * 1024)} MB)")
        assertTrue(max <= 600L * 1024 * 1024, "this suite must run in a container-sized heap")
        assertTrue(r.pdf.size < 10 * 1024 * 1024, "${r.pdf.size} bytes")
        assertTrue(peakMb < 256, "peak +$peakMb MB")
    }
}
