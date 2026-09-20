package dev.dwhipstock.pos.sdk

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Photo normalization at upload time. Phone cameras store portrait shots as
 * landscape pixels + an EXIF orientation tag; ImageIO (and some browsers/old
 * clients) ignore the tag, so we bake the rotation into the pixels once on
 * upload and serve plain upright images. Display code never compensates.
 */
object Images {

    /**
     * Returns JPEG bytes with EXIF orientation applied (and thereby stripped —
     * re-encoding drops metadata). Orientation 1/absent returns input unchanged.
     * PNG has no EXIF; callers should pass JPEGs only.
     */
    fun normalizeJpegOrientation(bytes: ByteArray): ByteArray {
        val orientation = runCatching {
            ImageMetadataReader.readMetadata(ByteArrayInputStream(bytes))
                .getFirstDirectoryOfType(ExifIFD0Directory::class.java)
                ?.getInt(ExifIFD0Directory.TAG_ORIENTATION)
        }.getOrNull() ?: 1
        if (orientation == 1) return bytes

        val src = ImageIO.read(ByteArrayInputStream(bytes)) ?: return bytes
        val (w, h) = src.width to src.height
        val swap = orientation in listOf(5, 6, 7, 8)
        val t = AffineTransform()
        when (orientation) {
            2 -> { t.scale(-1.0, 1.0); t.translate(-w.toDouble(), 0.0) }
            3 -> { t.translate(w.toDouble(), h.toDouble()); t.quadrantRotate(2) }
            4 -> { t.scale(1.0, -1.0); t.translate(0.0, -h.toDouble()) }
            5 -> { t.quadrantRotate(1); t.scale(1.0, -1.0) }
            6 -> { t.translate(h.toDouble(), 0.0); t.quadrantRotate(1) }
            7 -> { t.scale(-1.0, 1.0); t.translate(-h.toDouble(), 0.0); t.translate(0.0, w.toDouble()); t.quadrantRotate(3) }
            8 -> { t.translate(0.0, w.toDouble()); t.quadrantRotate(3) }
            else -> return bytes
        }
        val dst = BufferedImage(if (swap) h else w, if (swap) w else h, BufferedImage.TYPE_INT_RGB)
        // Graphics2D, not AffineTransformOp — the op throws ImagingOpException
        // on some transform/size combos (e.g. tiny images with interpolation)
        dst.createGraphics().apply {
            drawImage(src, t, null)
            dispose()
        }

        return ByteArrayOutputStream().use { out ->
            ImageIO.write(dst, "jpg", out)
            out.toByteArray()
        }
    }

    /**
     * Downscale to [maxWidth] and re-encode as JPEG — the grid-thumbnail
     * variant behind /photos?w=. Returns null when the source is already
     * narrow enough (serve the original; re-encoding would only lose quality)
     * or unreadable (caller falls back to the original bytes).
     */
    fun downscaleToJpeg(bytes: ByteArray, maxWidth: Int): ByteArray? {
        val src = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        if (src.width <= maxWidth) return null
        val h = (src.height.toLong() * maxWidth / src.width).toInt().coerceAtLeast(1)
        val dst = BufferedImage(maxWidth, h, BufferedImage.TYPE_INT_RGB)
        dst.createGraphics().apply {
            // PNG alpha lands on TYPE_INT_RGB as black; white reads as "no photo edge"
            color = java.awt.Color.WHITE
            fillRect(0, 0, maxWidth, h)
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(src, 0, 0, maxWidth, h, null)
            dispose()
        }
        return ByteArrayOutputStream().use { out ->
            ImageIO.write(dst, "jpg", out)
            out.toByteArray()
        }
    }
}
