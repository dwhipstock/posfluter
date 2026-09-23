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

/** Android Canvas counterpart of the desktop Java2D receipt renderer. */
object ThermalReceiptRenderer {
    private const val WIDTH = 512
    private const val MARGIN = 8
    private const val QR_SIZE = 360
    private const val PAD = 8

    private val body = paint(27f, false)
    private val bold = paint(27f, true)
    private val header = paint(40f, true)

    private fun paint(size: Float, heavy: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = size
        typeface = Typeface.create("sans-serif", if (heavy) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun rowHeight(paint: Paint): Int =
        (paint.fontMetrics.descent - paint.fontMetrics.ascent).toInt() + PAD

    private fun height(line: PrintLine): Int = when (line) {
        is PrintLine.Header, is PrintLine.LogoPlaceholder -> rowHeight(header)
        is PrintLine.Text -> rowHeight(body)
        is PrintLine.KeyValue -> rowHeight(if (line.emphasized) bold else body)
        is PrintLine.QrCode -> QR_SIZE + PAD * 2 + (if (line.caption != null) rowHeight(body) else 0)
        PrintLine.Divider, PrintLine.Blank -> 22
    }

    fun toEscPos(lines: List<PrintLine>): ByteArray {
        val totalHeight = (lines.sumOf(::height) + MARGIN * 2).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(WIDTH, totalHeight, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            var top = MARGIN
            for (line in lines) {
                val row = height(line)
                when (line) {
                    is PrintLine.Header -> drawText(canvas, line.text, header, top, Align.CENTER)
                    is PrintLine.LogoPlaceholder -> drawText(canvas, line.fallbackText, header, top, Align.CENTER)
                    is PrintLine.Text -> drawText(canvas, line.text, body, top, line.align)
                    is PrintLine.KeyValue -> {
                        val p = if (line.emphasized) bold else body
                        drawText(canvas, line.left, p, top, Align.LEFT)
                        drawText(canvas, line.right, p, top, Align.RIGHT)
                    }
                    is PrintLine.QrCode -> {
                        var qrTop = top + PAD
                        line.caption?.let {
                            drawText(canvas, it, body, top, Align.CENTER)
                            qrTop += rowHeight(body)
                        }
                        val matrix = MultiFormatWriter().encode(line.data, BarcodeFormat.QR_CODE,
                            QR_SIZE, QR_SIZE, mapOf(EncodeHintType.MARGIN to 1))
                        val pixels = IntArray(QR_SIZE * QR_SIZE) { i ->
                            if (matrix[i % QR_SIZE, i / QR_SIZE]) Color.BLACK else Color.WHITE
                        }
                        val qr = Bitmap.createBitmap(QR_SIZE, QR_SIZE, Bitmap.Config.ARGB_8888)
                        try {
                            qr.setPixels(pixels, 0, QR_SIZE, 0, 0, QR_SIZE, QR_SIZE)
                            canvas.drawBitmap(qr, ((WIDTH - QR_SIZE) / 2).toFloat(), qrTop.toFloat(), null)
                        } finally { qr.recycle() }
                    }
                    PrintLine.Divider -> {
                        var x = MARGIN
                        while (x < WIDTH - MARGIN) {
                            canvas.drawRect(x.toFloat(), (top + row / 2).toFloat(),
                                (x + 8).toFloat(), (top + row / 2 + 2).toFloat(), body)
                            x += 14
                        }
                    }
                    PrintLine.Blank -> Unit
                }
                top += row
            }
            return rasterJob(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawText(canvas: Canvas, text: String, paint: Paint, top: Int, align: Align) {
        val width = paint.measureText(text)
        val x = when (align) {
            Align.LEFT -> MARGIN.toFloat()
            Align.CENTER -> ((WIDTH - width) / 2).coerceAtLeast(MARGIN.toFloat())
            Align.RIGHT -> WIDTH - MARGIN - width
        }
        canvas.drawText(text, x, top - paint.fontMetrics.ascent, paint)
    }

    private fun rasterJob(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0x1b, '@'.code.toByte()))
        val pixels = IntArray(WIDTH * 128)
        var y = 0
        while (y < bitmap.height) {
            val rows = minOf(128, bitmap.height - y)
            bitmap.getPixels(pixels, 0, WIDTH, 0, y, WIDTH, rows)
            write(byteArrayOf(0x1d, 'v'.code.toByte(), '0'.code.toByte(), 0,
                64, 0, (rows and 0xff).toByte(), (rows shr 8).toByte()))
            for (row in 0 until rows) for (byteX in 0 until 64) {
                var value = 0
                for (bit in 0 until 8) {
                    val color = pixels[row * WIDTH + byteX * 8 + bit]
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
