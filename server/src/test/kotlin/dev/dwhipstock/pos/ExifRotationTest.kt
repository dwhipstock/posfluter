package dev.dwhipstock.pos

import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phone cameras store portrait shots as landscape pixels + EXIF orientation.
 * The server must bake the rotation in on upload so every consumer (POS grid,
 * customer menu, browsers that ignore EXIF) sees the photo upright.
 */
class ExifRotationTest {

    private fun tempDir(prefix: String) = Files.createTempDirectory(prefix).toString()

    /** 32×16 JPEG, left half red / right half blue, tagged orientation 6 (needs 90° CW to display). */
    private fun portraitTaggedJpeg(): ByteArray {
        val img = BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 32) for (y in 0 until 16) {
            img.setRGB(x, y, if (x < 16) 0xFF0000 else 0x0000FF) // left red, right blue
        }
        val plain = ByteArrayOutputStream().use { ImageIO.write(img, "jpg", it); it.toByteArray() }

        // minimal EXIF APP1: TIFF little-endian, IFD0 with one entry (0x0112 orientation = 6)
        val tiff = byteArrayOf(
            0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00, // II*\0 + IFD0 offset 8
            0x01, 0x00,                                     // 1 entry
            0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, // tag 0x0112, SHORT, count 1
            0x06, 0x00, 0x00, 0x00,                         // value 6
            0x00, 0x00, 0x00, 0x00,                         // no next IFD
        )
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte()) +
            byteArrayOf(((payload.size + 2) shr 8).toByte(), ((payload.size + 2) and 0xFF).toByte()) +
            payload
        // splice after SOI (FFD8)
        return plain.copyOfRange(0, 2) + app1 + plain.copyOfRange(2, plain.size)
    }

    @Test
    fun uploadNormalizesExifOrientation() = testApplication {
        application {
            module(dbPath = tempDir("pos-exif") + "/pos.db", photosDir = tempDir("photos-exif"))
        }
        val manager = loginClient()

        val tagged = portraitTaggedJpeg()
        // sanity: the fixture itself is landscape pixels
        ImageIO.read(ByteArrayInputStream(tagged)).let {
            assertEquals(32, it.width); assertEquals(16, it.height)
        }

        assertEquals(HttpStatusCode.Created, manager.post("/items/lantern-lager/photo") {
            setBody(MultiPartFormDataContent(formData {
                append("managerPin", "1234")
                append("photo", tagged, Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"p.jpg\"")
                })
            }))
        }.status)

        // served photo: dimensions swapped (16×32), red now on top — upright without EXIF
        val served = ImageIO.read(ByteArrayInputStream(client.get("/photos/lantern-lager").readRawBytes()))
        assertEquals(16, served.width, "width should be 16 after 90° CW normalize")
        assertEquals(32, served.height, "height should be 32 after 90° CW normalize")
        val top = served.getRGB(8, 4)      // well inside the top half
        val bottom = served.getRGB(8, 28)  // well inside the bottom half
        // JPEG is lossy — compare dominant channel, not exact values
        assertTrue((top shr 16 and 0xFF) > (top and 0xFF), "top pixel should be red-dominant")
        assertTrue((bottom and 0xFF) > (bottom shr 16 and 0xFF), "bottom pixel should be blue-dominant")
    }
}
