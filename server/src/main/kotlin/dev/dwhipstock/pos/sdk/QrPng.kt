package dev.dwhipstock.pos.sdk

import com.google.zxing.common.BitMatrix
import com.google.zxing.client.j2se.MatrixToImageWriter
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Desktop QR rendering; Android supplies its own bitmap-backed implementation. */
object QrPng {
    fun encode(matrix: BitMatrix): ByteArray = ByteArrayOutputStream().use { out ->
        ImageIO.write(MatrixToImageWriter.toBufferedImage(matrix), "png", out)
        out.toByteArray()
    }
}
