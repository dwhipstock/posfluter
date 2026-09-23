package dev.dwhipstock.pos.sdk

import android.graphics.Bitmap
import com.google.zxing.common.BitMatrix
import java.io.ByteArrayOutputStream

/** Android bitmap implementation of the shared QR response contract. */
object QrPng {
    fun encode(matrix: BitMatrix): ByteArray {
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) 0xff000000.toInt()
            else 0xffffffff.toInt()
        }
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
            ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
