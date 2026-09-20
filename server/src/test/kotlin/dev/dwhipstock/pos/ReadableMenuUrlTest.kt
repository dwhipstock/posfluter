package dev.dwhipstock.pos

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.io.ByteArrayInputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Readable menu URLs (/m/{zone}/{number}) that mirror the floor plan, resolving
 * to the same customer menu the opaque /m/{tableId} fallback serves, plus the
 * QR payload that staff-printed slips encode. Exercised through the real HTTP
 * routes on a freshly-seeded db (Lower L-8 = table id l13, VIP L-2 = t8).
 */
class ReadableMenuUrlTest {

    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    @Test
    fun `readable url resolves to the menu for the right table`() = testApplication {
        application { module(dbPath = tempDb()) }
        val body = client.get("/m/lower/8").bodyAsText()
        // internal id is injected for the page's own bill/pending calls; label shown to the guest
        assertTrue(body.contains("\"l13\""), "expected internal id l13 in page")
        assertTrue(body.contains("L-8"), "expected label L-8 in page")
    }

    @Test
    fun `opaque id fallback still works for already-printed QRs`() = testApplication {
        application { module(dbPath = tempDb()) }
        val res = client.get("/m/t1")
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.bodyAsText().contains("U-1"))
    }

    @Test
    fun `readable url honours the VIP name override for display`() = testApplication {
        application { module(dbPath = tempDb()) }
        val body = client.get("/m/lower/2").bodyAsText()
        assertTrue(body.contains("\"t8\""), "expected internal id t8")
        assertTrue(body.contains("Alex Morgan"), "expected VIP display name, not the L-2 label")
    }

    @Test
    fun `html in a name override is escaped on the customer menu`() = testApplication {
        application { module(dbPath = tempDb()) }
        // A manager sets a VIP name carrying a script payload; the same value the
        // page injects as {{TABLE_LABEL}} must render inert, matching the closed page.
        val mgr = loginClient()
        val patched = mgr.patch("/tables/t8") {
            contentType(ContentType.Application.Json)
            setBody("""{"nameOverride":"<script>alert(1)</script>","managerPin":"1234"}""")
        }
        assertEquals(HttpStatusCode.OK, patched.status)
        val body = client.get("/m/lower/2").bodyAsText()
        assertTrue(body.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), "override should render escaped")
        assertTrue(!body.contains("<script>alert(1)</script>"), "raw script tag must not reach the page")
    }

    @Test
    fun `unknown readable url is a clear 404`() = testApplication {
        application { module(dbPath = tempDb()) }
        assertEquals(HttpStatusCode.NotFound, client.get("/m/lower/999").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/m/nosuchzone/1").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/m/lower/abc").status)
    }

    @Test
    fun `closed zone shows the friendly banner via the readable url too`() = testApplication {
        application { module(dbPath = tempDb()) }
        val mgr = loginClient()
        mgr.patch("/zones/lower/status") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"CLOSED","managerPin":"1234"}""")
        }
        val body = client.get("/m/lower/8").bodyAsText()
        assertTrue(body.contains("temporarily closed"), "expected the closed-section banner")
    }

    @Test
    fun `table QR encodes the readable menu url`() = testApplication {
        application { module(dbPath = tempDb()) }
        val png = client.get("/tables/l13/qr").readRawBytes()
        val img = ImageIO.read(ByteArrayInputStream(png))
        val source = com.google.zxing.client.j2se.BufferedImageLuminanceSource(img)
        val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
        val text = com.google.zxing.MultiFormatReader().decode(bitmap).text
        assertTrue(text.endsWith("/m/lower/8"), "QR should encode /m/lower/8, was: $text")
    }
}
