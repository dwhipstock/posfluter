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
 * pages, so accented French text never depends on which code page the unit
 * ships with or has selected. Rasterizing sidesteps code pages entirely —
 * anything Java2D can draw (French, mixed French+English, $, digits) prints
 * exactly as laid out. See [ThermalReceiptRenderer].
 */
object EscPos {
    // 80mm printable area = 512 dots (64 bytes/row) on the XP-C300H. A wider
    // 576-dot head just leaves a blank right margin, so 512 is the safe target.
    const val DOTS_WIDTH = 512

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
    fun receiptJob(image: BufferedImage, dotsWidth: Int = DOTS_WIDTH): ByteArray = ByteArrayOutputStream().apply {
        write(INIT)
        write(rasterImage(image, dotsWidth = dotsWidth))
        write(feed(4))
        write(FULL_CUT)
    }.toByteArray()

    /**
     * GS v 0 raster bit image. Emitted in horizontal bands so a tall receipt
     * never exceeds the printer's line buffer (the XP-C300H, like most, chokes
     * on an unbounded single raster). 1 bit = one dot, MSB is the leftmost dot,
     * 1 = black.
     */
    fun rasterImage(image: BufferedImage, bandRows: Int = 128, dotsWidth: Int = DOTS_WIDTH): ByteArray {
        // a 58mm head takes 384-dot (48-byte) rows; 80mm the usual 512 (64)
        val bytesPerRow = dotsWidth / 8
        val w = minOf(image.width, dotsWidth)
        val h = image.height
        val out = ByteArrayOutputStream()
        var y0 = 0
        while (y0 < h) {
            val rows = minOf(bandRows, h - y0)
            // GS v 0 m xL xH yL yH
            out.write(byteArrayOf(GS, 'v'.code.toByte(), '0'.code.toByte(), 0))
            out.write(byteArrayOf((bytesPerRow and 0xFF).toByte(), (bytesPerRow shr 8).toByte()))
            out.write(byteArrayOf((rows and 0xFF).toByte(), (rows shr 8).toByte()))
            for (dy in 0 until rows) {
                val y = y0 + dy
                for (bx in 0 until bytesPerRow) {
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
    private const val W = ThermalLayout.WIDTH
    private const val MARGIN = ThermalLayout.MARGIN
    private const val BODY = 26      // body text px (blank/divider strip height)
    private const val LINE_PAD = 8   // vertical padding around each row

    // Resolved once: a font that can draw French + Latin + the $ sign. On the
    // venue's macOS/Android host the logical SansSerif composite already covers
    // French; we still prefer a real Unicode family when the host exposes one.
    private val bodyFont: Font by lazy { resolveFrenchFont(BODY) }
    private val boldFont: Font by lazy { bodyFont.deriveFont(Font.BOLD) }

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

    /**
     * ESC/POS payload (init + raster + cut) for the whole receipt. [width] is
     * the head in dots: [ThermalLayout.WIDTH] (80mm) or [ThermalLayout.WIDTH_58MM].
     */
    fun toEscPos(lines: List<PrintLine>, width: Int = W): ByteArray =
        EscPos.receiptJob(renderImage(lines, width), width)

    private fun font(style: ThermalLayout.Style, size: Float): Font =
        (if (style.bold) boldFont else bodyFont).deriveFont(size)

    /** Java2D widths for the shared [ThermalLayout]. */
    private val measurer = ThermalLayout.TextMeasurer { text, style, size ->
        scratch.getFontMetrics(font(style, size)).stringWidth(text).toFloat()
    }
    private val scratch by lazy { scratchGraphics() }

    /** The fitted rows for [lines] — what [renderImage] draws. */
    fun layout(lines: List<PrintLine>, width: Int = W): List<ThermalLayout.Row> =
        ThermalLayout.layout(lines, measurer, ThermalLayout.contentFor(width))

    /** Width in dots of a laid-out text row (tests: every row must fit [ThermalLayout.CONTENT]). */
    fun rowWidth(row: ThermalLayout.Row): Float = when (row) {
        is ThermalLayout.Row.Text -> measurer.width(row.text, row.style, row.size)
        is ThermalLayout.Row.Pair -> measurer.width(row.left, row.style, row.size) +
            measurer.width("  ", row.style, row.size) + measurer.width(row.right, row.style, row.size)
        is ThermalLayout.Row.Qr -> QR_SIZE.toFloat()
        is ThermalLayout.Row.Banner -> measurer.width(row.text, ThermalLayout.Style.TITLE, row.size)
        else -> 0f
    }

    /**
     * Lay the receipt out as a single tall bitmap: [ThermalLayout] fits every
     * line to the paper width, then each row becomes a full-width horizontal
     * strip stacked top-to-bottom (measure all, then draw into one image).
     */
    fun renderImage(lines: List<PrintLine>, width: Int = W): BufferedImage {
        val rows = layout(lines, width)
        val g0 = scratch
        val heights = rows.map { height(it, g0) }
        val total = heights.sum() + MARGIN * 2
        val img = BufferedImage(width, total.coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, width, total)
        g.color = Color.BLACK
        var y = MARGIN
        for ((row, h) in rows.zip(heights)) {
            draw(row, g, y, h, width)
            y += h
        }
        g.dispose()
        return img
    }

    private fun scratchGraphics() = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics().also {
        it.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }

    private fun rowHeight(font: Font, g: java.awt.Graphics2D): Int {
        val m = g.getFontMetrics(font)
        return m.ascent + m.descent + LINE_PAD
    }

    private const val QR_SIZE = 360 // dots — big enough to scan off thermal paper

    private fun height(row: ThermalLayout.Row, g: java.awt.Graphics2D): Int = when (row) {
        is ThermalLayout.Row.Text -> rowHeight(font(row.style, row.size), g)
        is ThermalLayout.Row.Pair -> rowHeight(font(row.style, row.size), g)
        is ThermalLayout.Row.Qr -> QR_SIZE + LINE_PAD * 2
        is ThermalLayout.Row.Banner -> rowHeight(font(ThermalLayout.Style.TITLE, row.size), g) + LINE_PAD
        ThermalLayout.Row.Divider -> BODY / 2 + LINE_PAD
        ThermalLayout.Row.Blank -> BODY / 2
    }

    private fun draw(row: ThermalLayout.Row, g: java.awt.Graphics2D, top: Int, height: Int, width: Int) {
        when (row) {
            is ThermalLayout.Row.Text -> {
                val f = font(row.style, row.size)
                val w = g.getFontMetrics(f).stringWidth(row.text)
                val x = when (row.align) {
                    Align.LEFT -> MARGIN
                    Align.CENTER -> ((width - w) / 2).coerceAtLeast(MARGIN)
                    Align.RIGHT -> width - MARGIN - w
                }
                at(g, row.text, f, x, top)
            }
            is ThermalLayout.Row.Pair -> {
                val f = font(row.style, row.size)
                val m = g.getFontMetrics(f)
                at(g, row.left, f, MARGIN, top)
                at(g, row.right, f, width - MARGIN - m.stringWidth(row.right), top)
            }
            is ThermalLayout.Row.Qr -> {
                val qr = qrBitmap(row.data, QR_SIZE)
                g.drawImage(qr, (width - qr.width) / 2, top + LINE_PAD, null)
            }
            is ThermalLayout.Row.Banner -> {
                // black bar inside the margins, the text knocked out in white
                g.fillRect(MARGIN, top, width - MARGIN * 2, height - LINE_PAD / 2)
                val f = font(ThermalLayout.Style.TITLE, row.size)
                val w = g.getFontMetrics(f).stringWidth(row.text)
                g.color = Color.WHITE
                at(g, row.text, f, ((width - w) / 2).coerceAtLeast(MARGIN), top + LINE_PAD / 2)
                g.color = Color.BLACK
            }
            ThermalLayout.Row.Divider -> {
                val yy = top + height / 2
                // dashed rule ≈ the text printer's row of '-'
                var x = MARGIN
                while (x < width - MARGIN) {
                    g.fillRect(x, yy, 8, 2)
                    x += 14
                }
            }
            ThermalLayout.Row.Blank -> { /* whitespace strip */ }
        }
    }


    /** QR code (ZXing) as a black-on-white bitmap sized to [size] dots. */
    private fun qrBitmap(data: String, size: Int): BufferedImage {
        val hints = buildMap<com.google.zxing.EncodeHintType, Any> {
            put(com.google.zxing.EncodeHintType.MARGIN, 1)
            // ZXing defaults to ISO-8859-1; a Wi-Fi name like "Café" needs UTF-8.
            // ASCII payloads (menu URLs) keep the default so their codes don't change.
            if (data.any { it.code > 0x7E }) put(com.google.zxing.EncodeHintType.CHARACTER_SET, "UTF-8")
        }
        val matrix = com.google.zxing.MultiFormatWriter()
            .encode(data, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
        return com.google.zxing.client.j2se.MatrixToImageWriter.toBufferedImage(matrix)
    }

    private fun at(g: java.awt.Graphics2D, text: String, font: Font, x: Int, top: Int) {
        g.font = font
        g.drawString(text, x, top + g.getFontMetrics(font).ascent)
    }
}
