package dev.dwhipstock.pos.sdk

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import java.io.ByteArrayOutputStream

/**
 * Android Canvas counterpart of the desktop Java2D receipt renderer. Line
 * fitting (venue name split, wrap, shrink) is the shared [ThermalLayout],
 * measured here with the same Paint that draws, so nothing clips at the edge.
 * [toEscPos] takes the head width in dots: 512 (80mm, the default) or 384 (58mm).
 */
object ThermalReceiptRenderer {
    private const val WIDTH = ThermalLayout.WIDTH
    private const val MARGIN = ThermalLayout.MARGIN
    private const val QR_SIZE = 360
    private const val PAD = 8

    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val heavy = Typeface.create("sans-serif", Typeface.BOLD)

    /** A fresh Paint per call: the embedded store may print from several threads. */
    private fun paint(style: ThermalLayout.Style, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = size
        typeface = if (style.bold) heavy else regular
    }

    private val measurer = ThermalLayout.TextMeasurer { text, style, size -> paint(style, size).measureText(text) }

    private fun rowHeight(paint: Paint): Int =
        (paint.fontMetrics.descent - paint.fontMetrics.ascent).toInt() + PAD

    private fun height(row: ThermalLayout.Row): Int = when (row) {
        is ThermalLayout.Row.Text -> rowHeight(paint(row.style, row.size))
        is ThermalLayout.Row.Pair -> rowHeight(paint(row.style, row.size))
        is ThermalLayout.Row.Qr -> QR_SIZE + PAD * 2
        is ThermalLayout.Row.Banner -> rowHeight(paint(ThermalLayout.Style.TITLE, row.size)) + PAD
        ThermalLayout.Row.Divider, ThermalLayout.Row.Blank -> 22
    }

    fun toEscPos(lines: List<PrintLine>, width: Int = WIDTH): ByteArray {
        val rows = ThermalLayout.layout(lines, measurer, ThermalLayout.contentFor(width))
        val totalHeight = (rows.sumOf(::height) + MARGIN * 2).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            var top = MARGIN
            for (row in rows) {
                val h = height(row)
                when (row) {
                    is ThermalLayout.Row.Text -> drawText(canvas, row.text, paint(row.style, row.size), top, row.align, width)
                    is ThermalLayout.Row.Pair -> {
                        val p = paint(row.style, row.size)
                        drawText(canvas, row.left, p, top, Align.LEFT, width)
                        drawText(canvas, row.right, p, top, Align.RIGHT, width)
                    }
                    is ThermalLayout.Row.Qr -> {
                        val hints = buildMap<EncodeHintType, Any> {
                            put(EncodeHintType.MARGIN, 1)
                            // ZXing defaults to ISO-8859-1; a Wi-Fi name like "Café" needs UTF-8
                            if (row.data.any { it.code > 0x7E }) put(EncodeHintType.CHARACTER_SET, "UTF-8")
                        }
                        val matrix = MultiFormatWriter().encode(row.data, BarcodeFormat.QR_CODE,
                            QR_SIZE, QR_SIZE, hints)
                        val pixels = IntArray(QR_SIZE * QR_SIZE) { i ->
                            if (matrix[i % QR_SIZE, i / QR_SIZE]) Color.BLACK else Color.WHITE
                        }
                        val qr = Bitmap.createBitmap(QR_SIZE, QR_SIZE, Bitmap.Config.ARGB_8888)
                        try {
                            qr.setPixels(pixels, 0, QR_SIZE, 0, 0, QR_SIZE, QR_SIZE)
                            canvas.drawBitmap(qr, ((width - QR_SIZE) / 2).toFloat(), (top + PAD).toFloat(), null)
                        } finally { qr.recycle() }
                    }
                    is ThermalLayout.Row.Banner -> {
                        // black bar inside the margins, the text knocked out in white
                        val bar = Paint().apply { color = Color.BLACK }
                        canvas.drawRect(MARGIN.toFloat(), top.toFloat(),
                            (width - MARGIN).toFloat(), (top + h - PAD / 2).toFloat(), bar)
                        val p = paint(ThermalLayout.Style.TITLE, row.size).apply { color = Color.WHITE }
                        drawText(canvas, row.text, p, top + PAD / 2, Align.CENTER, width)
                    }
                    ThermalLayout.Row.Divider -> {
                        val ink = paint(ThermalLayout.Style.BODY, ThermalLayout.Style.BODY.size)
                        var x = MARGIN
                        while (x < width - MARGIN) {
                            canvas.drawRect(x.toFloat(), (top + h / 2).toFloat(),
                                (x + 8).toFloat(), (top + h / 2 + 2).toFloat(), ink)
                            x += 14
                        }
                    }
                    ThermalLayout.Row.Blank -> Unit
                }
                top += h
            }
            return rasterJob(bitmap, width)
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawText(canvas: Canvas, text: String, paint: Paint, top: Int, align: Align, width: Int) {
        val w = paint.measureText(text)
        val x = when (align) {
            Align.LEFT -> MARGIN.toFloat()
            Align.CENTER -> ((width - w) / 2).coerceAtLeast(MARGIN.toFloat())
            Align.RIGHT -> width - MARGIN - w
        }
        canvas.drawText(text, x, top - paint.fontMetrics.ascent, paint)
    }

    private fun rasterJob(bitmap: Bitmap, width: Int): ByteArray = ByteArrayOutputStream().apply {
        val bytesPerRow = width / 8
        write(byteArrayOf(0x1b, '@'.code.toByte()))
        val pixels = IntArray(width * 128)
        var y = 0
        while (y < bitmap.height) {
            val rows = minOf(128, bitmap.height - y)
            bitmap.getPixels(pixels, 0, width, 0, y, width, rows)
            write(byteArrayOf(0x1d, 'v'.code.toByte(), '0'.code.toByte(), 0,
                (bytesPerRow and 0xff).toByte(), (bytesPerRow shr 8).toByte(),
                (rows and 0xff).toByte(), (rows shr 8).toByte()))
            for (row in 0 until rows) for (byteX in 0 until bytesPerRow) {
                var value = 0
                for (bit in 0 until 8) {
                    val color = pixels[row * width + byteX * 8 + bit]
                    val r = Color.red(color)
                    val g = Color.green(color)
                    val b = Color.blue(color)
                    if ((r * 30 + g * 59 + b * 11) / 100 < 160) value = value or (0x80 shr bit)
                }
                write(value)
            }
            y += rows
        }
        write(byteArrayOf(0x1b, 'd'.code.toByte(), 4, 0x1d, 'V'.code.toByte(), 0))
    }.toByteArray()
}
