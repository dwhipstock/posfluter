package dev.dwhipstock.pos.sdk

import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream

/**
 * ESC/POS payload builder for an 80mm network thermal printer (Xprinter
 * XP-C300H, confirmed working over raw TCP :9100).
 *
 * The whole receipt is rendered as ONE monochrome raster bitmap and pushed via
 * the GS v 0 raster command. We do NOT rely on the printer's resident code
 * pages: the XP-C300H self-test lists none with French, so a text-mode receipt
 * would print Ventes / $ / menu names as boxes. Rasterizing sidesteps code
 * pages entirely — anything Java2D can draw (French, mixed French+English, $, the
 * CAD sign, digits) prints exactly as laid out. See [ThermalReceiptRenderer].
 */
object EscPos {
    // 80mm printable area = 512 dots (64 bytes/row) on the XP-C300H. A wider
    // 576-dot head just leaves a blank right margin, so 512 is the safe target.
    const val DOTS_WIDTH = 512
    private const val BYTES_PER_ROW = DOTS_WIDTH / 8 // 64

    private val ESC = 0x1B.toByte()
    private val GS = 0x1D.toByte()

    /** ESC @ — reset formatting/buffer to a known state. */
    val INIT = byteArrayOf(ESC, '@'.code.toByte())

    /** ESC d n — feed n blank lines (clear the cut zone before shearing). */
    fun feed(lines: Int) = byteArrayOf(ESC, 'd'.code.toByte(), lines.toByte())

    /** GS V 0 — full cut (function A). Confirmed working on the XP-C300H. */
    val FULL_CUT = byteArrayOf(GS, 'V'.code.toByte(), 0)

    /**
     * Full receipt job: init, the bitmap, a short feed so the artwork clears the
     * blade, then a full cut. [image] must be [DOTS_WIDTH] wide (narrower images
     * are left-padded to the head width).
     */
    fun receiptJob(image: BufferedImage): ByteArray = ByteArrayOutputStream().apply {
        write(INIT)
        write(rasterImage(image))
        write(feed(4))
        write(FULL_CUT)
    }.toByteArray()

    /**
     * GS v 0 raster bit image. Emitted in horizontal bands so a tall receipt
     * never exceeds the printer's line buffer (the XP-C300H, like most, chokes
     * on an unbounded single raster). 1 bit = one dot, MSB is the leftmost dot,
     * 1 = black.
     */
    fun rasterImage(image: BufferedImage, bandRows: Int = 128): ByteArray {
        val w = minOf(image.width, DOTS_WIDTH)
        val h = image.height
        val out = ByteArrayOutputStream()
        var y0 = 0
        while (y0 < h) {
            val rows = minOf(bandRows, h - y0)
            // GS v 0 m xL xH yL yH
            out.write(byteArrayOf(GS, 'v'.code.toByte(), '0'.code.toByte(), 0))
            out.write(byteArrayOf((BYTES_PER_ROW and 0xFF).toByte(), (BYTES_PER_ROW shr 8).toByte()))
            out.write(byteArrayOf((rows and 0xFF).toByte(), (rows shr 8).toByte()))
            for (dy in 0 until rows) {
                val y = y0 + dy
                for (bx in 0 until BYTES_PER_ROW) {
                    var b = 0
                    for (bit in 0 until 8) {
                        val x = bx * 8 + bit
                        if (x < w && isBlack(image.getRGB(x, y))) b = b or (0x80 shr bit)
                    }
                    out.write(b)
                }
            }
            y0 += rows
        }
        return out.toByteArray()
    }

    /** Luminance threshold. Antialiased edges land grey; keep the stroke core. */
    private fun isBlack(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 30 + g * 59 + b * 11) / 100 < 160
    }
}

/**
 * Renders the device-independent [PrintLine] list into a monochrome bitmap for
 * the thermal printer — the raster counterpart to [PrinterAdapter.renderText].
 * Same layout intent (centered headers, left/right key-value rows, dividers),
 * drawn with a French-capable font so French menu names render as real glyphs.
 */
object ThermalReceiptRenderer {
    private const val W = EscPos.DOTS_WIDTH
    private const val MARGIN = 8
    private const val CONTENT = W - MARGIN * 2
    private const val BODY = 26      // body text px
    private const val HEADER = 40    // emphasized header px (≈ double-height)
    private const val LINE_PAD = 8   // vertical padding around each row

    // Resolved once: a font that can draw French + Latin + the $ sign. On the
    // venue's macOS/Android host the logical SansSerif composite already covers
    // French; we still prefer a real Unicode family when the host exposes one.
    private val bodyFont: Font by lazy { resolveFrenchFont(BODY) }
    private val boldFont: Font by lazy { bodyFont.deriveFont(Font.BOLD) }
    private val headerFont: Font by lazy { bodyFont.deriveFont(Font.BOLD, HEADER.toFloat()) }

    private fun resolveFrenchFont(size: Int): Font {
        val probe = "\$A5"
        fun covers(f: Font) = probe.all { f.canDisplay(it) }
        // Prefer a dedicated Unicode family for crisp thermal glyphs; fall back to
        // the logical SansSerif composite (covers French on macOS/Android/JRE).
        val preferred = listOf("Noto Sans", "Arial", "Helvetica", "Tahoma")
        val available = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        }.getOrDefault(emptySet())
        for (name in preferred) {
            if (name in available) {
                val f = Font(name, Font.PLAIN, size)
                if (covers(f)) return f
            }
        }
        return Font(Font.SANS_SERIF, Font.PLAIN, size)
    }

    /** ESC/POS payload (init + raster + cut) for the whole receipt. */
    fun toEscPos(lines: List<PrintLine>): ByteArray = EscPos.receiptJob(renderImage(lines))

    /**
     * Lay the receipt out as a single tall bitmap. Each [PrintLine] becomes a
     * full-width horizontal strip; strips stack top-to-bottom. Two passes:
     * measure every strip's height, then draw into one image of the total.
     */
    fun renderImage(lines: List<PrintLine>): BufferedImage {
        val fm = scratchGraphics()
        val strips = lines.map { measure(it, fm) }
        val total = strips.sumOf { it.height } + MARGIN * 2
        val img = BufferedImage(W, total.coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, W, total)
        g.color = Color.BLACK
        var y = MARGIN
        for ((line, strip) in lines.zip(strips)) {
            draw(line, g, y, strip.height)
            y += strip.height
        }
        g.dispose()
        return img
    }

    private class Strip(val height: Int)

    private fun scratchGraphics() = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics().also {
        it.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }

    private fun rowHeight(font: Font, g: java.awt.Graphics2D): Int {
        val m = g.getFontMetrics(font)
        return m.ascent + m.descent + LINE_PAD
    }

    private const val QR_SIZE = 360 // dots — big enough to scan off thermal paper

    private fun measure(line: PrintLine, g: java.awt.Graphics2D): Strip = when (line) {
        is PrintLine.Header -> Strip(rowHeight(headerFont, g))
        is PrintLine.LogoPlaceholder -> Strip(rowHeight(headerFont, g))
        is PrintLine.Text -> Strip(rowHeight(bodyFont, g))
        is PrintLine.KeyValue -> Strip(rowHeight(if (line.emphasized) boldFont else bodyFont, g))
        is PrintLine.QrCode -> Strip(QR_SIZE + LINE_PAD * 2 + (line.caption?.let { rowHeight(bodyFont, g) } ?: 0))
        PrintLine.Divider -> Strip(BODY / 2 + LINE_PAD)
        PrintLine.Blank -> Strip(BODY / 2)
    }

    private fun draw(line: PrintLine, g: java.awt.Graphics2D, top: Int, height: Int) {
        when (line) {
            is PrintLine.Header -> centered(g, line.text, headerFont, top)
            is PrintLine.LogoPlaceholder -> centered(g, line.fallbackText, headerFont, top)
            is PrintLine.Text -> when (line.align) {
                Align.LEFT -> at(g, line.text, bodyFont, MARGIN, top)
                Align.CENTER -> centered(g, line.text, bodyFont, top)
                Align.RIGHT -> {
                    val m = g.getFontMetrics(bodyFont)
                    at(g, line.text, bodyFont, W - MARGIN - m.stringWidth(line.text), top)
                }
            }
            is PrintLine.KeyValue -> {
                val f = if (line.emphasized) boldFont else bodyFont
                val m = g.getFontMetrics(f)
                at(g, line.left, f, MARGIN, top)
                at(g, line.right, f, W - MARGIN - m.stringWidth(line.right), top)
            }
            is PrintLine.QrCode -> {
                var yy = top + LINE_PAD
                line.caption?.let {
                    centered(g, it, bodyFont, yy)
                    yy += g.getFontMetrics(bodyFont).let { m -> m.ascent + m.descent }
                }
                val qr = qrBitmap(line.data, QR_SIZE)
                g.drawImage(qr, (W - qr.width) / 2, yy, null)
            }
            PrintLine.Divider -> {
                val yy = top + height / 2
                // dashed rule ≈ the text printer's row of '-'
                var x = MARGIN
                while (x < W - MARGIN) {
                    g.fillRect(x, yy, 8, 2)
                    x += 14
                }
            }
            PrintLine.Blank -> { /* whitespace strip */ }
        }
    }

    /** QR code (ZXing) as a black-on-white bitmap sized to [size] dots. */
    private fun qrBitmap(data: String, size: Int): BufferedImage {
        val hints = mapOf(com.google.zxing.EncodeHintType.MARGIN to 1)
        val matrix = com.google.zxing.MultiFormatWriter()
            .encode(data, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
        return com.google.zxing.client.j2se.MatrixToImageWriter.toBufferedImage(matrix)
    }

    private fun at(g: java.awt.Graphics2D, text: String, font: Font, x: Int, top: Int) {
        g.font = font
        g.drawString(text, x, top + g.getFontMetrics(font).ascent)
    }

    private fun centered(g: java.awt.Graphics2D, text: String, font: Font, top: Int) {
        val m = g.getFontMetrics(font)
        at(g, text, font, ((W - m.stringWidth(text)) / 2).coerceAtLeast(MARGIN), top)
    }
}
