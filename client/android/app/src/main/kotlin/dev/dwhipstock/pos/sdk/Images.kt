package dev.dwhipstock.pos.sdk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Android image codec for the shared photo-store contract. */
object Images {
    fun normalizeJpegOrientation(bytes: ByteArray): ByteArray {
        val orientation = runCatching {
            ImageMetadataReader.readMetadata(ByteArrayInputStream(bytes))
                .getFirstDirectoryOfType(ExifIFD0Directory::class.java)
                ?.getInt(ExifIFD0Directory.TAG_ORIENTATION)
        }.getOrNull() ?: 1
        if (orientation == 1) return bytes
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val transform = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(270f); postScale(-1f, 1f) }
                8 -> setRotate(270f)
                else -> return bytes
            }
        }
        return try {
            val upright = Bitmap.createBitmap(source, 0, 0, source.width, source.height, transform, true)
            try { jpeg(upright) } finally { if (upright !== source) upright.recycle() }
        } finally {
            source.recycle()
        }
    }

    fun downscaleToJpeg(bytes: ByteArray, maxWidth: Int): ByteArray? {
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            if (source.width <= maxWidth) return null
            val height = (source.height.toLong() * maxWidth / source.width).toInt().coerceAtLeast(1)
            val result = Bitmap.createBitmap(maxWidth, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(result)
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(source, null,
                    android.graphics.Rect(0, 0, maxWidth, height), Paint(Paint.FILTER_BITMAP_FLAG))
                jpeg(result)
            } finally {
                result.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    private fun jpeg(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out))
        out.toByteArray()
    }
}
