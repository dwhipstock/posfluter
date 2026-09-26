package dev.dwhipstock.pos

import dev.dwhipstock.pos.api.tableSlipLines
import dev.dwhipstock.pos.api.wifiSlipLines
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.sdk.GuestWifi
import dev.dwhipstock.pos.sdk.ThermalReceiptRenderer
import dev.dwhipstock.pos.sdk.WifiQr
import dev.dwhipstock.pos.sdk.WifiSecurity
import io.ktor.client.HttpClient
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.awt.image.BufferedImage
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Guest Wi-Fi: settings, password confinement, the join slip and the two-step table slip. */
class GuestWifiTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()
    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    private suspend fun HttpClient.patchJson(path: String, body: String) =
        patch(path) { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.postJson(path: String, body: String) =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.configureWifi(password: String = SECRET) = patchJson("/settings",
        """{"wifiSsid":"Lantern Guests","wifiPassword":"$password","wifiSecurity":"WPA","wifiHidden":false}""")

    // --- QR payload --------------------------------------------------------

    @Test
    fun `payload follows the WIFI QR format and escapes special characters`() {
        assertEquals("WIFI:T:WPA;S:Lantern;P:pass1234;;", WifiQr.payload("Lantern", "pass1234", "WPA", false))
        assertEquals("WIFI:T:WEP;S:Lantern;P:abcde;H:true;;", WifiQr.payload("Lantern", "abcde", "WEP", true))
        assertEquals("WIFI:T:nopass;S:Open Net;;", WifiQr.payload("Open Net", "", "nopass", false))
        assertEquals("""WIFI:T:WPA;S:a\;b\,c\:d;P:q\"w\\e\;r;;""",
            WifiQr.payload("a;b,c:d", "q\"w\\e;r", "WPA", false))
    }

    @Test
    fun `configured needs a name plus a password unless open`() {
        assertFalse(GuestWifi("", "pass1234").configured)
        assertFalse(GuestWifi("Net", "", WifiSecurity.WPA).configured)
        assertTrue(GuestWifi("Net", "", WifiSecurity.NOPASS).configured)
        assertTrue(GuestWifi("Net", "pass1234").configured)
    }

    // --- settings ----------------------------------------------------------

    @Test
    fun `settings round-trip for a manager on the terminal`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val before = json.parseToJsonElement(c.get("/settings").bodyAsText()).jsonObject
        assertEquals("", before["wifiSsid"]!!.jsonPrimitive.content)
        assertEquals("WPA", before["wifiSecurity"]!!.jsonPrimitive.content)
        assertFalse(before["wifiHidden"]!!.jsonPrimitive.boolean)

        val res = c.patchJson("/settings",
            """{"wifiSsid":"Lantern Guests","wifiPassword":"$SECRET","wifiSecurity":"wpa","wifiHidden":true}""")
        assertEquals(HttpStatusCode.OK, res.status)
        val s = json.parseToJsonElement(c.get("/settings").bodyAsText()).jsonObject
        assertEquals("Lantern Guests", s["wifiSsid"]!!.jsonPrimitive.content)
        assertEquals(SECRET, s["wifiPassword"]!!.jsonPrimitive.content)
        assertEquals("WPA", s["wifiSecurity"]!!.jsonPrimitive.content) // normalized
        assertTrue(s["wifiHidden"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `invalid wifi settings are rejected`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        assertEquals(HttpStatusCode.BadRequest,
            c.patchJson("/settings", """{"wifiSsid":"Net","wifiPassword":"short","wifiSecurity":"WPA"}""").status)
        assertEquals(HttpStatusCode.BadRequest,
            c.patchJson("/settings", """{"wifiSsid":"Net","wifiSecurity":"WPA3-ENT"}""").status)
        assertEquals(HttpStatusCode.BadRequest,
            c.patchJson("/settings", """{"wifiSsid":"${"x".repeat(33)}","wifiSecurity":"nopass"}""").status)
        // open network needs no password
        assertEquals(HttpStatusCode.OK,
            c.patchJson("/settings", """{"wifiSsid":"Open","wifiPassword":"","wifiSecurity":"nopass"}""").status)
    }

    @Test
    fun `staff cannot read settings and the staff app never sees the password`() = testApplication {
        application { module(dbPath = tempDb(), staffAppMfa = dev.dwhipstock.pos.sdk.StaffAppMfa.Resolved(dev.dwhipstock.pos.sdk.StaffAppMfa.OFF, "test")) }
        val mgr = loginClient()
        mgr.configureWifi()

        // a server (non-manager) is refused the full settings outright
        val staff = loginClient("9999")
        assertEquals(HttpStatusCode.Forbidden, staff.get("/settings").status)
        assertFalse(SECRET in staff.get("/alert-config").bodyAsText())

        // a manager signed in on the staff phone app gets the rest, password redacted
        val login = client.postJson("/staff-app/login", """{"pin":"1234"}""")
        val token = json.parseToJsonElement(login.bodyAsText()).jsonObject["user"]!!
            .jsonObject["token"]!!.jsonPrimitive.content
        val phone = createClient { install(DefaultRequest) { header(HttpHeaders.Authorization, "Bearer $token") } }
        val body = phone.get("/settings").bodyAsText()
        assertFalse(SECRET in body, "password leaked to a staff-app session")
        val s = json.parseToJsonElement(body).jsonObject
        assertEquals(JsonNull, s["wifiPassword"])
        assertEquals("Lantern Guests", s["wifiSsid"]!!.jsonPrimitive.content)
        // and a PATCH answer from the phone is redacted too
        assertFalse(SECRET in phone.patchJson("/settings", """{"wifiHidden":true}""").bodyAsText())
        // the redacted PATCH did not wipe the stored password
        assertTrue(SECRET in mgr.get("/settings").bodyAsText())
    }

    @Test
    fun `the wifi password never reaches the sync outbox`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        assertEquals(HttpStatusCode.OK, c.configureWifi().status)
        assertEquals(HttpStatusCode.OK, c.patchJson("/settings", """{"wifiPassword":"$SECRET-2"}""").status)
        val rows = transaction { SyncOutbox.selectAll().map { it[SyncOutbox.payload] } }
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.none { SECRET in it }, "wifi password found in an outbox row")
        assertTrue(rows.any { "wifiPassword" in it }, "the changed key NAME is still recorded")
    }

    // --- POST /printer/wifi/print -----------------------------------------

    @Test
    fun `wifi slip is manager only and 409 until configured`() = testApplication {
        application { module(dbPath = tempDb()) }
        val staff = loginClient("9999")
        assertEquals(HttpStatusCode.Forbidden, staff.post("/printer/wifi/print").status)

        val c = loginClient()
        val res = c.post("/printer/wifi/print")
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertEquals("wifi_not_configured",
            json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `wifi slip copies are validated`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        c.configureWifi()
        assertEquals(HttpStatusCode.BadRequest, c.postJson("/printer/wifi/print", """{"copies":0}""").status)
        assertEquals(HttpStatusCode.BadRequest, c.postJson("/printer/wifi/print", """{"copies":21}""").status)
    }

    @Test
    fun `wifi slip reports an unset or offline printer like the other prints`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        c.configureWifi()
        val unset = json.parseToJsonElement(c.post("/printer/wifi/print").bodyAsText()).jsonObject
        assertFalse(unset["configured"]!!.jsonPrimitive.boolean)
        assertEquals(0, unset["printed"]!!.jsonPrimitive.int)

        c.patchJson("/settings", """{"printerIp":"127.0.0.1","printerPort":${closedPort()}}""")
        val res = c.postJson("/printer/wifi/print", """{"copies":3}""")
        assertEquals(HttpStatusCode.OK, res.status)
        val off = json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertTrue(off["configured"]!!.jsonPrimitive.boolean)
        assertFalse(off["online"]!!.jsonPrimitive.boolean)
        assertEquals(0, off["printed"]!!.jsonPrimitive.int)
        assertEquals(3, off["copies"]!!.jsonPrimitive.int)
    }

    @Test
    fun `wifi slip prints the requested copies to the printer`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        c.configureWifi()
        val server = ServerSocket(0)
        var jobs = 0
        val reader = thread {
            repeat(2) {
                server.accept().use { s -> s.getInputStream().readBytes(); jobs++ }
            }
        }
        c.patchJson("/settings", """{"printerIp":"127.0.0.1","printerPort":${server.localPort}}""")
        val r = json.parseToJsonElement(c.postJson("/printer/wifi/print", """{"copies":2}""").bodyAsText()).jsonObject
        reader.join(5_000)
        server.close()
        assertTrue(r["online"]!!.jsonPrimitive.boolean)
        assertEquals(2, r["printed"]!!.jsonPrimitive.int)
        assertEquals(2, jobs)
    }

    // --- rendered slips scan ------------------------------------------------

    private val wifi = GuestWifi("Lantern Guests", SECRET, WifiSecurity.WPA, hidden = true)
    private val menuUrl = "http://192.168.1.50:8080/m/t/0123456789abcdef0123456789abcdef"

    private fun decodeAll(img: BufferedImage): Set<String> {
        val source = com.google.zxing.client.j2se.BufferedImageLuminanceSource(img)
        val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
        return com.google.zxing.multi.qrcode.QRCodeMultiReader().decodeMultiple(bitmap).map { it.text }.toSet()
    }

    @Test
    fun `wifi slip renders a scannable join QR`() {
        val lines = wifiSlipLines("Copper Lantern", wifi)
        val texts = lines.filterIsInstance<dev.dwhipstock.pos.sdk.PrintLine.Header>().map { it.text } +
            lines.filterIsInstance<dev.dwhipstock.pos.sdk.PrintLine.Text>().map { it.text }
        assertTrue("Free Wi-Fi / Wi-Fi gratuit" in texts)
        assertTrue("Scan to connect" in texts && "Balayez pour vous connecter" in texts)
        assertTrue("Lantern Guests" in texts && SECRET in texts)
        val img = ThermalReceiptRenderer.renderImage(lines)
        assertEquals(setOf("WIFI:T:WPA;S:Lantern Guests;P:$SECRET;H:true;;"), decodeAll(img))
    }

    @Test
    fun `table slip with wifi stacks both scannable QRs`() {
        val lines = tableSlipLines("Copper Lantern", "L-8", "Bas / Lower", menuUrl, wifi)
        val texts = lines.filterIsInstance<dev.dwhipstock.pos.sdk.PrintLine.Text>().map { it.text }
        assertTrue("1. Join Wi-Fi" in texts && "1. Connectez-vous au Wi-Fi" in texts)
        assertTrue("2. Scan to order" in texts && "2. Balayez pour commander" in texts)
        val qrs = lines.filterIsInstance<dev.dwhipstock.pos.sdk.PrintLine.QrCode>().map { it.data }
        assertEquals(listOf(wifi.qrPayload(), menuUrl), qrs) // Wi-Fi first, stacked
        val img = ThermalReceiptRenderer.renderImage(lines)
        assertEquals(dev.dwhipstock.pos.sdk.EscPos.DOTS_WIDTH, img.width) // fits the head
        assertEquals(setOf(wifi.qrPayload(), menuUrl), decodeAll(img))
    }

    @Test
    fun `table slip without wifi is unchanged`() {
        val lines = tableSlipLines("Copper Lantern", "L-8", "Bas / Lower", menuUrl, null)
        assertEquals(1, lines.count { it is dev.dwhipstock.pos.sdk.PrintLine.QrCode })
        val texts = lines.filterIsInstance<dev.dwhipstock.pos.sdk.PrintLine.Text>().map { it.text }
        assertTrue(texts.none { "Wi-Fi" in it })
        assertTrue("Scan to order" in texts)
    }

    private companion object {
        const val SECRET = "Sup3r-Secret-Pass"
    }
}
