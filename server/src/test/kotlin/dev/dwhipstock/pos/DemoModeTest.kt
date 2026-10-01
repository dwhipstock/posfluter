package dev.dwhipstock.pos

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.dwhipstock.pos.api.DemoSheet
import dev.dwhipstock.pos.api.demoSheetLines
import dev.dwhipstock.pos.sdk.DemoMode
import dev.dwhipstock.pos.sdk.GuestWifi
import dev.dwhipstock.pos.sdk.PrintLine
import dev.dwhipstock.pos.sdk.StaffAppMfa
import dev.dwhipstock.pos.sdk.ThermalLayout
import dev.dwhipstock.pos.sdk.ThermalReceiptRenderer
import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.insert
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Properties
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** demo.mode: the MFA override, the Venue settings flag and the demo QR sheet. */
class DemoModeTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    private val password = "Pr3senter-Only-Secret"
    private val portal = "https://portal.example.com"
    private fun demoOn(withPortal: Boolean = true) = DemoMode.fromProperties(Properties().apply {
        setProperty("demo.mode", "on")
        if (withPortal) {
            setProperty("demo.portal.url", portal)
            setProperty("demo.portal.user", "owner@example.com")
            setProperty("demo.portal.password", password)
        }
    })
    private val mfaOn = StaffAppMfa.Resolved(StaffAppMfa.ON, "test")

    private suspend fun ApplicationTestBuilder.postJson(path: String, body: String) =
        client.post(path) { contentType(ContentType.Application.Json); setBody(body) }
    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    // --- config --------------------------------------------------------------

    @Test
    fun `demo mode is off unless store properties say on`() {
        assertFalse(DemoMode.fromProperties(null).on)
        assertFalse(DemoMode.fromProperties(Properties()).on)
        assertFalse(DemoMode.fromEnv { null }.on)
        assertFalse(DemoMode.fromProperties(Properties().apply { setProperty("demo.mode", "off") }).on)
        val bad = DemoMode.fromProperties(Properties().apply { setProperty("demo.mode", "yes") })
        assertFalse(bad.on)
        assertNotNull(bad.warning)
        val on = demoOn()
        assertTrue(on.on)
        assertEquals(portal, on.portalUrl)
        assertEquals("owner@example.com", on.portalUser)
        assertEquals(password, on.portalPassword)
        // env wins on desktop / docker
        val env = mapOf("POS_DEMO_MODE" to " ON ", "POS_DEMO_PORTAL_URL" to portal)
        val fromEnv = DemoMode.fromEnv { env[it] }
        assertTrue(fromEnv.on)
        assertEquals(portal, fromEnv.portalUrl)
        assertNull(fromEnv.portalPassword)
    }

    @Test
    fun `demo mode forces staff app MFA off, otherwise the explicit setting stands`() {
        assertEquals(StaffAppMfa.OFF, demoOn().staffAppMfa(mfaOn).mfa)
        assertEquals(StaffAppMfa.ON, DemoMode.OFF.staffAppMfa(mfaOn).mfa)
        val explicitOff = StaffAppMfa.Resolved(StaffAppMfa.OFF, "store.properties")
        assertEquals(StaffAppMfa.OFF, DemoMode.OFF.staffAppMfa(explicitOff).mfa)
    }

    @Test
    fun `the portal password is never in a log string`() {
        val on = demoOn()
        assertFalse(password in on.toString())
        assertFalse(password in on.describe())
        assertFalse("owner@example.com" in on.describe())
    }

    @Test
    fun `demo mode on - the staff app signs in by PIN only even with MFA set on`() = testApplication {
        application { module(dbPath = tempDb(), staffAppMfa = mfaOn, demoMode = demoOn()) }
        val login = postJson("/staff-app/login", """{"pin":"9999"}""").obj()
        assertEquals("ok", login["status"]!!.jsonPrimitive.content)
        assertTrue(login["user"]!!.jsonObject["token"]!!.jsonPrimitive.content.isNotEmpty())
        assertNull(login["secret"]?.jsonPrimitive?.contentOrNull)
    }

    /** Red-team: with the PIN-only staff app, the manager PIN must not make a guest phone a manager. */
    @Test
    fun `demo mode on - a manager PIN is refused on the staff app and kitchen sign-in`() = testApplication {
        application { module(dbPath = tempDb(), staffAppMfa = mfaOn, demoMode = demoOn()) }
        val res = postJson("/staff-app/login", """{"pin":"1234"}""")
        assertEquals(HttpStatusCode.Forbidden, res.status)
        assertEquals("manager_pos_only", res.obj()["code"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Forbidden, postJson("/staff-app/totp", """{"pin":"1234","code":"000000"}""").status)
        // the POS tablet itself (loopback here) still signs the manager in
        assertEquals(HttpStatusCode.OK, postJson("/login", """{"pin":"1234"}""").status)
    }

    @Test
    fun `demo mode on - a manager PIN from a phone on the Wi-Fi neither signs in nor approves`() {
        dev.dwhipstock.pos.db.initDatabase(tempDb())
        org.jetbrains.exposed.sql.transactions.transaction {
            for ((id, role, pin) in listOf(Triple("m", "MANAGER", "1234"), Triple("s", "SERVER", "9999"))) {
                dev.dwhipstock.pos.base.Users.insert {
                    it[dev.dwhipstock.pos.base.Users.id] = id; it[name] = id; it[dev.dwhipstock.pos.base.Users.role] = role
                    it[dev.dwhipstock.pos.base.Users.pin] = dev.dwhipstock.pos.base.AuthService.hashPin(pin)
                }
            }
        }
        val auth = dev.dwhipstock.pos.base.AuthService(demoMode = true)
        val phone = dev.dwhipstock.pos.base.PinClient("ip:192.168.1.77", trusted = false)
        kotlin.test.assertFailsWith<dev.dwhipstock.pos.base.ManagerOnPosOnlyException> { auth.login("1234", client = phone) }
        kotlin.test.assertFailsWith<dev.dwhipstock.pos.base.ManagerOnPosOnlyException> { auth.verifyManagerPin("1234", phone) }
        kotlin.test.assertFailsWith<dev.dwhipstock.pos.base.ManagerOnPosOnlyException> {
            auth.verifyApproverPin("1234", dev.dwhipstock.pos.base.Permissions.VOID, phone)
        }
        assertNotNull(auth.login("9999", client = phone), "the server PIN still works from a phone")
        assertNotNull(auth.login("1234"), "the tablet itself signs the manager in")
        assertNotNull(auth.verifyManagerPin("1234"), "manager approval on the tablet")
    }

    @Test
    fun `demo mode off - staff app MFA as configured`() = testApplication {
        application { module(dbPath = tempDb(), staffAppMfa = mfaOn, demoMode = DemoMode.OFF) }
        assertEquals("enroll", postJson("/staff-app/login", """{"pin":"9999"}""").obj()["status"]!!.jsonPrimitive.content)
    }

    // --- Venue settings + the print route ------------------------------------

    @Test
    fun `settings tell the manager whether demo mode is on`() {
        testApplication {
            application { module(dbPath = tempDb(), demoMode = demoOn()) }
            val s = json.parseToJsonElement(loginClient().get("/settings").bodyAsText()).jsonObject
            assertTrue(s["demoMode"]!!.jsonPrimitive.boolean)
            assertFalse(password in s.toString(), "the portal password is never sent to the POS")
        }
        testApplication {
            application { module(dbPath = tempDb(), demoMode = DemoMode.OFF) }
            val s = json.parseToJsonElement(loginClient().get("/settings").bodyAsText()).jsonObject
            assertFalse(s["demoMode"]!!.jsonPrimitive.boolean)
        }
    }

    @Test
    fun `demo sheet - 409 with demo mode off, manager only`() {
        testApplication {
            application { module(dbPath = tempDb(), demoMode = DemoMode.OFF) }
            val res = loginClient().post("/printer/demo-sheet/print")
            assertEquals(HttpStatusCode.Conflict, res.status)
            assertEquals("demo_mode_off", res.obj()["code"]!!.jsonPrimitive.content)
        }
        testApplication {
            application { module(dbPath = tempDb(), demoMode = demoOn()) }
            assertEquals(HttpStatusCode.Forbidden, loginClient("9999").post("/printer/demo-sheet/print").status)
        }
    }

    @Test
    fun `demo sheet prints one job to the receipt printer and never logs the password`() = testApplication {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        root.addAppender(appender)
        try {
            application { module(dbPath = tempDb(), demoMode = demoOn()) }
            val c = loginClient()
            val unset = c.post("/printer/demo-sheet/print?lang=fr").obj()
            assertFalse(unset["configured"]!!.jsonPrimitive.boolean)

            val server = ServerSocket(0)
            var bytes = 0
            val reader = thread { server.accept().use { s -> bytes = s.getInputStream().readBytes().size } }
            c.patch("/settings") {
                contentType(ContentType.Application.Json)
                setBody("""{"printerIp":"127.0.0.1","printerPort":${server.localPort}}""")
            }
            val r = c.post("/printer/demo-sheet/print?lang=fr")
            reader.join(5_000)
            server.close()
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.obj()["online"]!!.jsonPrimitive.boolean)
            assertTrue(bytes > 1000, "an ESC/POS raster job reached the printer")
            assertFalse(appender.list.any { password in it.formattedMessage }, "password in a log line")
        } finally {
            root.detachAppender(appender)
        }
    }

    // --- the slip -------------------------------------------------------------

    private val base = "http://192.168.1.50:8080"
    private val tableUrl = "$base/m/t/AbCdEfGhIjKlMnOpQrStUv"
    private val wifi = GuestWifi("Lantern Guests", "Sup3r-Secret-Pass")
    private fun sheet(
        demo: DemoMode = demoOn(), table: Boolean = true, kitchen: Boolean = true,
        pickup: Boolean = true, wifi: GuestWifi? = this.wifi,
    ) = DemoSheet("Copper Lantern — Glenwood South", base, if (table) "U-1" to tableUrl else null,
        kitchen, pickup, demo, wifi)

    private fun texts(lines: List<PrintLine>) = lines.mapNotNull {
        when (it) {
            is PrintLine.Header -> it.text
            is PrintLine.Text -> it.text
            is PrintLine.Large -> it.text
            else -> null
        }
    }
    private fun qrs(lines: List<PrintLine>) = lines.filterIsInstance<PrintLine.QrCode>().map { it.data }

    private fun decodeAll(img: BufferedImage): Set<String> {
        val source = com.google.zxing.client.j2se.BufferedImageLuminanceSource(img)
        val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
        return com.google.zxing.multi.qrcode.QRCodeMultiReader().decodeMultiple(bitmap).map { it.text }.toSet()
    }

    @Test
    fun `the slip has the header, the note and every block, each QR the URL printed under it`() {
        val lines = demoSheetLines(sheet())
        val t = texts(lines)
        assertEquals("DEMO MODE — demo QR codes", lines.filterIsInstance<PrintLine.Header>().first().text)
        assertTrue("Printed for the demo so guests can try every app. Not printed in normal use. MFA (two-step sign-in) is turned off in demo mode." in t)
        for (title in listOf("Guest ordering at a table", "Staff phone app", "Kitchen screen", "Pickup board",
            "Manager portal", "Guest Wi-Fi")) assertTrue(title in t, title)
        val urls = listOf(tableUrl, "$base/staff-app", "$base/kitchen", "$base/pickup", portal)
        assertEquals(urls + wifi.qrPayload(), qrs(lines))
        // each link's QR is followed by the same URL in text
        for (url in urls) {
            val i = lines.indexOf(PrintLine.QrCode(url))
            assertEquals(url, (lines[i + 1] as PrintLine.Text).text)
        }
        assertTrue("Sign in: PIN 9999 (server). Manager sign-in stays on the POS tablet." in t)
        // red-team: the manager PIN is never on the slip, in any language
        for (locale in listOf(LocaleCode.FR, LocaleCode.EN, LocaleCode.ES, LocaleCode.DE, LocaleCode.AF)) {
            assertFalse(texts(demoSheetLines(sheet(), locale)).any { "1234" in it }, "manager PIN on the $locale slip")
        }
        assertTrue("Username: owner@example.com" in t)
        assertTrue("Password: $password" in t)
        // no pairing blocks
        assertFalse(t.any { "pair" in it.lowercase() || "kiosk" in it.lowercase() })
    }

    @Test
    fun `the printed QRs scan to exactly those URLs and everything fits 80mm`() {
        val lines = demoSheetLines(sheet())
        // one block at a time (a reader finds one or two codes per frame, like a phone)
        val blocks = lines.fold(mutableListOf(mutableListOf<PrintLine>())) { acc, l ->
            if (l == PrintLine.Divider) acc.add(mutableListOf()) else acc.last().add(l); acc
        }
        val scanned = blocks.filter { b -> b.any { it is PrintLine.QrCode } }
            .flatMap { decodeAll(ThermalReceiptRenderer.renderImage(it)) }
        assertEquals(qrs(lines).toSet(), scanned.toSet())
        for (row in ThermalReceiptRenderer.layout(lines)) {
            assertTrue(ThermalReceiptRenderer.rowWidth(row) <= ThermalLayout.CONTENT, "row wider than the paper: $row")
        }
    }

    @Test
    fun `quick-serve skips the table block, no portal sign-in asks the presenter, no Wi-Fi no block`() {
        val lines = demoSheetLines(sheet(demo = demoOn(withPortal = false), table = false, kitchen = false, wifi = null))
        val t = texts(lines)
        assertFalse("Guest ordering at a table" in t)
        assertFalse("Kitchen screen" in t)
        assertFalse("Guest Wi-Fi" in t)
        assertTrue("Address: ask the presenter" in t)
        assertTrue("Username: ask the presenter" in t)
        assertTrue("Password: ask the presenter" in t)
        assertEquals(listOf("$base/staff-app", "$base/pickup"), qrs(lines))
    }

    @Test
    fun `the slip prints in the app language, every language complete`() {
        val en = texts(demoSheetLines(sheet(), LocaleCode.EN))
        for (lang in listOf(LocaleCode.FR, LocaleCode.ES, LocaleCode.DE, LocaleCode.AF)) {
            val t = texts(demoSheetLines(sheet(), lang))
            assertFalse(t.any { it.startsWith("demo.") }, "$lang: a raw key")
            assertTrue(t[0] != en[0] && t[1] != en[1], "$lang is translated")
        }
        assertEquals("MODE DÉMO — codes QR de démonstration",
            demoSheetLines(sheet(), LocaleCode.FR).filterIsInstance<PrintLine.Header>().first().text)
    }
}
