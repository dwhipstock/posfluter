package dev.dwhipstock.pos

import dev.dwhipstock.pos.sdk.Align
import dev.dwhipstock.pos.sdk.PrintLine
import dev.dwhipstock.pos.sdk.ThermalLayout
import dev.dwhipstock.pos.sdk.ThermalReceiptRenderer
import java.awt.image.BufferedImage
import kotlin.test.Test
import org.junit.Assume.assumeTrue
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * RED TEAM (printing): hostile names/notes typed by execs at a live demo, fed
 * through the real thermal pipeline. Tests named `HOLDS -` pass today and guard
 * what is safe; the others FAIL on main and are the repro for a finding.
 */
class RedTeamPrintTest {

    // ---------------------------------------------------------------- held up

    /**
     * The whole job must decompose into: ESC @, then GS v 0 bands whose payload
     * length is exactly declared, then ESC d 4, GS V 0. Any command byte from
     * user data outside a declared raster payload would break the parse.
     */
    @Test
    fun `HOLDS - control bytes in names never reach the printer as commands`() {
        val evil = "Burger\u001B@\u001DV\u0000\u001Bp\u0000\u0019ú\u0010\u0014\u0001\u0000\u0005 cut-here"
        val lines = listOf(
            PrintLine.LogoPlaceholder("Venue$evil — Room$evil"),
            PrintLine.Header(evil),
            PrintLine.Text(evil, Align.CENTER),
            PrintLine.KeyValue(evil, "1.00$evil"),
            PrintLine.Large(evil),
            PrintLine.Banner(evil),
            PrintLine.Huge(evil),
        )
        val bytes = ThermalReceiptRenderer.toEscPos(lines)
        var i = 0
        fun expect(vararg b: Int) {
            for (x in b) { assertEquals(x.toByte(), bytes[i], "unexpected byte at $i"); i++ }
        }
        expect(0x1B, 0x40)
        var bands = 0
        while (bytes[i] == 0x1D.toByte() && bytes[i + 1] == 'v'.code.toByte()) {
            expect(0x1D, 0x76, 0x30, 0x00)
            val xb = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
            val yb = (bytes[i + 2].toInt() and 0xFF) or ((bytes[i + 3].toInt() and 0xFF) shl 8)
            i += 4
            i += xb * yb
            bands++
        }
        assertTrue(bands > 0)
        expect(0x1B, 'd'.code, 4, 0x1D, 'V'.code, 0)
        assertEquals(bytes.size, i, "trailing bytes after the cut")
    }

    private fun decodeAll(img: BufferedImage): Set<String> {
        val source = com.google.zxing.client.j2se.BufferedImageLuminanceSource(img)
        val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
        val hints = mapOf(com.google.zxing.DecodeHintType.TRY_HARDER to true)
        return com.google.zxing.multi.qrcode.QRCodeMultiReader().decodeMultiple(bitmap, hints).map { it.text }.toSet()
    }

    /** Worst-case Wi-Fi: max-length name/password made of every character the format must escape. */
    @Test
    fun `HOLDS - hostile wifi name and password survive the printed join QR`() {
        val ssid = "\\;,:\"".repeat(6) + "ab" // 32 bytes
        val password = ";,:\"\\ ".repeat(10) + "x=1" // 63 chars
        val emoji = dev.dwhipstock.pos.sdk.GuestWifi("Café 🍺 Ñandú", password)
        for (wifi in listOf(dev.dwhipstock.pos.sdk.GuestWifi(ssid, password), emoji)) {
            val payload = wifi.qrPayload()
            val img = ThermalReceiptRenderer.renderImage(listOf(PrintLine.QrCode(payload)))
            assertEquals(setOf(payload), decodeAll(img))
            // and the payload parses back to the same fields
            val fields = mutableMapOf<Char, String>()
            var i = "WIFI:".length
            while (i < payload.length && payload[i] != ';') {
                val key = payload[i]; i += 2
                val sb = StringBuilder()
                while (payload[i] != ';') { if (payload[i] == '\\') i++; sb.append(payload[i]); i++ }
                fields[key] = sb.toString(); i++
            }
            assertEquals(wifi.ssid, fields['S']); assertEquals(wifi.password, fields['P'])
        }
    }

    // --------------------------------------------------------------- findings

    /** A name with a line break (pasted into the portal) must not print a missing-glyph box. */
    @Test
    fun `HOLDS - a line break inside a name does not print a box`() {
        val plain = render("AB"); val broken = render("A\nB"); val tab = render("A\tB")
        fun ink(img: BufferedImage): Int { var n = 0; for (y in 0 until img.height) for (x in 0 until img.width) if (img.getRGB(x, y) and 0xFF < 128) n++; return n }
        assertTrue(ink(broken) <= ink(plain) * 1.15, "\\n prints as a visible box: ink ${ink(broken)} vs ${ink(plain)}")
        assertTrue(ink(tab) <= ink(plain) * 1.15, "\\t prints as a visible box: ink ${ink(tab)} vs ${ink(plain)}")
    }

    /** Ink of [text] rendered alone as a body line. */
    private fun render(text: String): BufferedImage =
        ThermalReceiptRenderer.renderImage(listOf(PrintLine.Text(text, Align.LEFT)))

    private fun same(a: BufferedImage, b: BufferedImage): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getRGB(x, y) != b.getRGB(x, y)) return false
        return true
    }

    /**
     * Two DIFFERENT characters that rasterize to identical pixels are the
     * missing-glyph box (tofu): the font the renderer picked can't draw them.
     */
    private fun assertRealGlyphs(script: String, a: String, b: String) {
        // only meaningful where the OS has a font for this script (the tablet and Mac do; a bare CI box may not)
        val fonts = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().allFonts
        assumeTrue("no installed font can draw $script", fonts.any { it.canDisplayUpTo(a + b) == -1 })
        if (same(render(a), render(b)))
            fail("$script: '$a' and '$b' print as the identical missing-glyph box — the receipt shows □ instead of the name")
    }

    @Test
    fun `CJK item names print as real glyphs`() = assertRealGlyphs("CJK", "寿", "司")

    @Test
    fun `Arabic item names print as real glyphs`() = assertRealGlyphs("Arabic", "ش", "ع")

    @Test
    fun `Hebrew item names print as real glyphs`() = assertRealGlyphs("Hebrew", "ש", "ל")

    @Test
    fun `emoji item names print as real glyphs`() = assertRealGlyphs("emoji", "🍔", "🍺")

    private fun rowTexts(lines: List<PrintLine>): List<String> = ThermalReceiptRenderer.layout(lines).mapNotNull {
        when (it) {
            is ThermalLayout.Row.Text -> it.text
            is ThermalLayout.Row.Pair -> it.left
            is ThermalLayout.Row.Banner -> it.text
            else -> null
        }
    }

    /**
     * A long name with no spaces is broken "between characters" — but by UTF-16
     * unit, so an emoji (two units) is cut in half: each half is an unpaired
     * surrogate that prints as garbage on both lines.
     */
    @Test
    fun `a long emoji name is never cut inside a character`() {
        val name = "🍔🍟🌮🍣🍕".repeat(30)
        for (line in listOf(PrintLine.KeyValue("$name ×1", "12.50"), PrintLine.Large("1 × $name"), PrintLine.Header(name, exact = true))) {
            for (row in rowTexts(listOf(line))) {
                val first = row.first(); val last = row.last()
                if (Character.isLowSurrogate(first) || Character.isHighSurrogate(last))
                    fail("row starts/ends inside an emoji (unpaired surrogate) for ${line::class.simpleName}: ${row.take(8)}…${row.takeLast(8)}")
            }
        }
    }

    /**
     * Same check against the shared [ThermalLayout] itself (the tablet's Android
     * renderer uses it too) with a measurer where a half emoji draws nothing —
     * the breaker walks UTF-16 units, so whether it splits an emoji depends only
     * on how the platform font measures a broken half. Java2D on the Mac happens
     * to measure the half as a wide box and dodges it; this measurer doesn't.
     */
    @Test
    fun `the shared line breaker never cuts inside a character whatever the font`() {
        val m = ThermalLayout.TextMeasurer { text, _, size ->
            var w = 0f; var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                w += if (Character.isSurrogate(text[i]) && Character.charCount(cp) == 1) 0f else size * 0.8f
                i += Character.charCount(cp)
            }
            w
        }
        val rows = ThermalLayout.exact("🍔".repeat(100), ThermalLayout.Style.BODY, Align.LEFT, m)
            .map { (it as ThermalLayout.Row.Text).text }
        val broken = rows.filter { Character.isLowSurrogate(it.first()) || Character.isHighSurrogate(it.last()) }
        assertTrue(broken.isEmpty(), "${broken.size} of ${rows.size} rows start or end with half an emoji")
    }

    /** Zalgo / decomposed accents: a break must not strand combining marks at the start of a row. */
    @Test
    fun `a long accented word is never cut between a letter and its accent`() {
        val word = "é̂̃".repeat(150) // é̂̃ x150, decomposed, no spaces
        for (row in rowTexts(listOf(PrintLine.KeyValue(word, "1.00")))) {
            val t = Character.getType(row.first())
            if (t == Character.NON_SPACING_MARK.toInt() || t == Character.ENCLOSING_MARK.toInt())
                fail("row begins with an orphaned combining mark U+%04X".format(row.first().code))
        }
    }

    /**
     * Messages.get substitutes {0}, {1}… one after another into the already
     * substituted text, so a name that contains "{1}" picks up the next argument.
     */
    @Test
    fun `a tax name containing a placeholder is printed literally`() {
        val tax = dev.dwhipstock.pos.sdk.TaxComponent("x", "Taxe {1}", "Tax {1}", java.math.BigDecimal("5"), "")
        val label = dev.dwhipstock.pos.sdk.ReceiptRenderer.taxLineLabel(tax, dev.dwhipstock.pos.sdk.i18n.LocaleCode.EN)
        // both names print, the print locale's first ("GST/TPS"); the point is the literal {1}
        assertEquals("Tax {1}/Taxe {1} 5%", label)
    }
}
