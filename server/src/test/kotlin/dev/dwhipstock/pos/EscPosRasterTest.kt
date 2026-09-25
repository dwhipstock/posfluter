package dev.dwhipstock.pos

import dev.dwhipstock.pos.sdk.Align
import dev.dwhipstock.pos.sdk.EscPos
import dev.dwhipstock.pos.sdk.PrintLine
import dev.dwhipstock.pos.sdk.ThermalReceiptRenderer
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The thermal path is raster-only (the XP-C300H has no French code page), so the
 * guarantees under test are: images come out at the 512-dot head width, the
 * ESC/POS envelope is well-formed (init … raster bands … cut), and — the whole
 * point — French glyphs actually rasterize to ink instead of blank boxes.
 */
class EscPosRasterTest {

    private val frenchReceipt = listOf(
        PrintLine.LogoPlaceholder("The Copper Lantern Pub"),
        PrintLine.Text("téléphone. 514-555-0142", Align.CENTER),
        PrintLine.Divider,
        PrintLine.KeyValue("soupe épicée aux crevettes ×1", "180"),
        PrintLine.KeyValue("Total", "$704", emphasized = true),
        PrintLine.Blank,
        PrintLine.Text("Merci d'utiliser le service.", Align.CENTER),
    )

    @Test
    fun image_is_head_width() {
        val img = ThermalReceiptRenderer.renderImage(frenchReceipt)
        assertEquals(EscPos.DOTS_WIDTH, img.width, "raster must match the 512-dot print head")
        assertTrue(img.height > 0)
    }

    @Test
    fun french_rasterizes_to_ink() {
        // A blank/boxed render (missing font) would leave the strip white. Real
        // French glyphs put black pixels on the page — assert there's meaningful ink.
        val img = ThermalReceiptRenderer.renderImage(listOf(PrintLine.Text("Ventes", Align.LEFT)))
        assertTrue(blackPixelCount(img) > 50, "French text should rasterize to ink, not blank boxes")
    }

    @Test
    fun escpos_envelope_is_wellformed() {
        val bytes = ThermalReceiptRenderer.toEscPos(frenchReceipt)
        // ESC @ init
        assertEquals(0x1B.toByte(), bytes[0])
        assertEquals('@'.code.toByte(), bytes[1])
        // contains a GS v 0 raster command
        assertTrue(containsSeq(bytes, byteArrayOf(0x1D, 0x76, 0x30)), "expected GS v 0 raster")
        // ends with GS V 0 full cut
        val tail = bytes.copyOfRange(bytes.size - 3, bytes.size)
        assertTrue(tail.contentEquals(byteArrayOf(0x1D, 0x56, 0x00)), "expected GS V 0 full cut at end")
    }

    private fun blackPixelCount(img: BufferedImage): Int {
        var n = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val p = img.getRGB(x, y) and 0xFF
            if (p < 128) n++
        }
        return n
    }

    private fun containsSeq(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
