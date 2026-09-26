package dev.dwhipstock.pos

import dev.dwhipstock.pos.db.Migrations
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.db.initDatabase
import dev.dwhipstock.pos.restaurant.DiningTables
import dev.dwhipstock.pos.restaurant.TableTokens
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unguessable customer links: each table is reached only through its random
 * token (/m/t/{token}); ids and floor-plan numbers no longer resolve, and a
 * manager can rotate a token so old slips stop working.
 */
class TableLinkTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    private suspend fun HttpClient.postJson(path: String, body: String) =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    private val order =
        """{"lines":[{"itemId":"lantern-lager","variantId":"lantern-lager:pint","qty":2}]}"""

    private fun decodeQr(png: ByteArray): String {
        val img = ImageIO.read(ByteArrayInputStream(png))
        val source = com.google.zxing.client.j2se.BufferedImageLuminanceSource(img)
        val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
        return com.google.zxing.MultiFormatReader().decode(bitmap).text
    }

    /** Rebuild the bitmap from a printed ESC/POS job (ESC @, then GS v 0 bands). */
    private fun rasterToImage(job: ByteArray): java.awt.image.BufferedImage {
        val rows = mutableListOf<ByteArray>()
        var i = 2 // ESC @
        var bytesPerRow = 0
        while (i + 8 <= job.size && job[i] == 0x1D.toByte() && job[i + 1] == 'v'.code.toByte()) {
            bytesPerRow = (job[i + 4].toInt() and 0xFF) or ((job[i + 5].toInt() and 0xFF) shl 8)
            val n = (job[i + 6].toInt() and 0xFF) or ((job[i + 7].toInt() and 0xFF) shl 8)
            i += 8
            repeat(n) { rows += job.copyOfRange(i, i + bytesPerRow); i += bytesPerRow }
        }
        val img = java.awt.image.BufferedImage(bytesPerRow * 8, rows.size, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for ((y, row) in rows.withIndex()) for (x in 0 until bytesPerRow * 8) {
            val black = (row[x / 8].toInt() shr (7 - x % 8)) and 1 == 1
            img.setRGB(x, y, if (black) 0x000000 else 0xFFFFFF)
        }
        return img
    }

    private fun allTokens(): List<String?> =
        transaction { DiningTables.selectAll().map { it[DiningTables.publicToken] } }

    // --- tokens ------------------------------------------------------------

    @Test
    fun `tokens are 128-bit url-safe and distinct`() {
        val many = (1..2000).map { TableTokens.newToken() }
        assertEquals(many.size, many.toSet().size)
        assertTrue(many.all(TableTokens::looksValid))
        assertEquals(16, Base64.getUrlDecoder().decode(many.first()).size)
    }

    @Test
    fun `every seeded table gets its own token`() = testApplication {
        application { module(dbPath = tempDb()) }
        client.get("/health")
        val tokens = allTokens()
        assertTrue(tokens.size > 10)
        assertTrue(tokens.all { it != null && TableTokens.looksValid(it) })
        assertEquals(tokens.size, tokens.toSet().size)
    }

    @Test
    fun `migration backfills tokens for tables that existed before it`() {
        val path = tempDb()
        val db = initDatabase(path)
        // rewind to a pre-033 schema with a table the token column never saw
        transaction(db) {
            exec("DROP INDEX idx_dining_tables_public_token")
            exec("ALTER TABLE dining_tables DROP COLUMN public_token")
            exec("DELETE FROM schema_migrations WHERE version = 33")
            exec("INSERT INTO zones (id, name_fr, name_en) VALUES ('old', 'Ancien', 'Old')")
            exec("INSERT INTO dining_tables (id, zone_id, label) VALUES ('old-1', 'old', '1'), ('old-2', 'old', '2')")
        }
        Migrations.run(db)
        val tokens = transaction(db) {
            DiningTables.selectAll().associate { it[DiningTables.id] to it[DiningTables.publicToken] }
        }
        assertTrue(tokens.values.all { it != null && TableTokens.looksValid(it) })
        assertEquals(tokens.size, tokens.values.toSet().size)
        assertNotEquals(tokens["old-1"], tokens["old-2"])
    }

    @Test
    fun `new tables and sub-tables get tokens`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val parent = json.parseToJsonElement(c.postJson("/zones/lower/tables",
            """{"x":10,"y":10,"managerPin":"1234"}""").bodyAsText()).jsonObject
        val child = json.parseToJsonElement(c.postJson("/zones/lower/tables",
            """{"x":20,"y":20,"parentTableId":"${parent["id"]!!.jsonPrimitive.content}","managerPin":"1234"}""")
            .bodyAsText()).jsonObject
        val paths = listOf(parent, child).map { it["menuPath"]!!.jsonPrimitive.content }
        assertTrue(paths.all { it.startsWith("/m/t/") && TableTokens.looksValid(it.removePrefix("/m/t/")) })
        assertNotEquals(paths[0], paths[1])
        assertEquals(HttpStatusCode.OK, client.get(paths[1]).status)
    }

    // --- customer routes ---------------------------------------------------

    @Test
    fun `guessable urls no longer reach a table`() = testApplication {
        application { module(dbPath = tempDb()) }
        // retired forms: internal id, zone + number, id-based bill
        for (path in listOf("/m/t5", "/m/l13", "/m/lower/8", "/m/lower/9", "/m/t5/bill", "/m/t/t5",
                "/m/t/AAAAAAAAAAAAAAAAAAAAAA", "/m/t/AAAAAAAAAAAAAAAAAAAAAA/bill")) {
            val res = client.get(path)
            assertEquals(HttpStatusCode.NotFound, res.status, path)
            assertFalse("{{" in res.bodyAsText() || "lantern-lager" in res.bodyAsText(), path)
        }
        val page = client.get("/m/lower/8").bodyAsText()
        assertTrue("Please scan the QR code at your table." in page)
        assertTrue("Veuillez balayer le code QR sur votre table." in page)
        // the old unauthenticated order submit by table id is gone
        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/tables/t5/pending-lines", order).status)
        assertEquals(HttpStatusCode.NotFound, client.postJson("/m/t/AAAAAAAAAAAAAAAAAAAAAA/pending-lines", order).status)
    }

    @Test
    fun `token url works end to end for ordering`() = testApplication {
        application { module(dbPath = tempDb()) }
        val link = customerPath("l13") // Lower L-8
        val page = client.get(link)
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        assertTrue("L-8" in html)
        assertTrue(tableToken("l13") in html)          // the page keys its calls off the token
        assertFalse("\"l13\"" in html, "internal id must not reach the guest's page")
        assertTrue("/m/t/\${TABLE_TOKEN}/pending-lines" in html)

        assertEquals(false, json.parseToJsonElement(client.get("$link/bill").bodyAsText())
            .jsonObject["open"]!!.jsonPrimitive.content.toBoolean())
        val submitted = client.postJson("$link/pending-lines", order)
        assertEquals(HttpStatusCode.Created, submitted.status)
        val bill = json.parseToJsonElement(client.get("$link/bill").bodyAsText()).jsonObject
        assertEquals("true", bill["open"]!!.jsonPrimitive.content)
        assertEquals(1, bill["pendingLines"]!!.jsonArray.size)

        // staff see it on the right table
        val zones = json.parseToJsonElement(loginClient().get("/zones").bodyAsText()).jsonArray
        val l13 = zones.flatMap { it.jsonObject["tables"]!!.jsonArray }
            .first { it.jsonObject["id"]!!.jsonPrimitive.content == "l13" }.jsonObject
        assertEquals("1", l13["pendingCount"]!!.jsonPrimitive.content)
        assertEquals(link, l13["menuPath"]!!.jsonPrimitive.content)
    }

    @Test
    fun `html in a name override is escaped on the customer menu`() = testApplication {
        application { module(dbPath = tempDb()) }
        val mgr = loginClient()
        mgr.patch("/tables/t8") {
            contentType(ContentType.Application.Json)
            setBody("""{"nameOverride":"<script>alert(1)</script>","managerPin":"1234"}""")
        }
        val body = client.get(customerPath("t8")).bodyAsText()
        assertTrue("&lt;script&gt;alert(1)&lt;/script&gt;" in body)
        assertFalse("<script>alert(1)</script>" in body)
    }

    // --- regenerate --------------------------------------------------------

    @Test
    fun `regenerate invalidates the old token`() = testApplication {
        application { module(dbPath = tempDb()) }
        val old = customerPath("t5")
        assertEquals(HttpStatusCode.OK, client.get(old).status)

        assertEquals(HttpStatusCode.Forbidden, loginClient("9999").post("/tables/t5/link/regenerate").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/tables/t5/link/regenerate").status)

        val mgr = loginClient()
        val res = mgr.post("/tables/t5/link/regenerate")
        assertEquals(HttpStatusCode.OK, res.status)
        val fresh = json.parseToJsonElement(res.bodyAsText()).jsonObject["menuPath"]!!.jsonPrimitive.content
        assertNotEquals(old, fresh)
        assertEquals(customerPath("t5"), fresh)

        assertEquals(HttpStatusCode.NotFound, client.get(old).status)
        assertEquals(HttpStatusCode.NotFound, client.get("$old/bill").status)
        assertEquals(HttpStatusCode.NotFound, client.postJson("$old/pending-lines", order).status)
        assertEquals(HttpStatusCode.OK, client.get(fresh).status)
        assertEquals(HttpStatusCode.Created, client.postJson("$fresh/pending-lines", order).status)
        assertEquals(HttpStatusCode.NotFound, mgr.post("/tables/nope/link/regenerate").status)

        // audited without the token
        val rows = transaction { SyncOutbox.selectAll().map { it[SyncOutbox.eventType] to it[SyncOutbox.payload] } }
        assertTrue(rows.any { it.first == "table.link_regenerated" })
        val tokens = listOf(old, fresh).map { it.removePrefix("/m/t/") }
        assertTrue(rows.none { r -> tokens.any { it in r.second } }, "a table token reached the outbox")
    }

    // --- QR / slips --------------------------------------------------------

    @Test
    fun `table QR and slips carry the token url`() = testApplication {
        application { module(dbPath = tempDb()) }
        val mgr = loginClient()
        val link = customerPath("l13")

        val qr = decodeQr(mgr.get("/tables/l13/qr").readRawBytes())
        assertTrue(qr.endsWith(link), "QR should encode $link, was: $qr")

        // printable HTML slip: inline QR decodes to the token url
        val html = client.get("/tables/l13/slip?ticket=${mgr.slipTicket()}").bodyAsText()
        val b64 = Regex("data:image/png;base64,([A-Za-z0-9+/=]+)").find(html)!!.groupValues[1]
        assertTrue(decodeQr(Base64.getDecoder().decode(b64)).endsWith(link))
        assertFalse("/m/lower/" in html)

        // the slip actually printed on the thermal printer: decode its raster
        mgr.patch("/settings") {
            contentType(ContentType.Application.Json)
            setBody("""{"wifiSsid":"Lantern Guests","wifiPassword":"pass1234","wifiSecurity":"WPA"}""")
        }
        val server = java.net.ServerSocket(0)
        var printed = ByteArray(0)
        val reader = kotlin.concurrent.thread { server.accept().use { printed = it.getInputStream().readBytes() } }
        mgr.patch("/settings") {
            contentType(ContentType.Application.Json)
            setBody("""{"printerIp":"127.0.0.1","printerPort":${server.localPort}}""")
        }
        assertEquals(HttpStatusCode.OK, mgr.post("/tables/l13/slip/print").status)
        reader.join(5_000)
        server.close()
        val img = rasterToImage(printed)
        val source = com.google.zxing.client.j2se.BufferedImageLuminanceSource(img)
        val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
        val codes = com.google.zxing.multi.qrcode.QRCodeMultiReader().decodeMultiple(bitmap).map { it.text }
        assertEquals(2, codes.size, "expected the Wi-Fi and menu QRs, got $codes")
        assertTrue(codes.any { it == "WIFI:T:WPA;S:Lantern Guests;P:pass1234;;" })
        assertTrue(codes.any { it.endsWith(link) }, "printed slip should encode $link, got $codes")
    }
}
