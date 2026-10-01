package dev.dwhipstock.pos

import dev.dwhipstock.pos.restaurant.CheckLines
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RED TEAM (redteam/inputs, 87e2489): the kiosk and malformed-body findings.
 * Each test here failed on main 97e0d94. (The guest QR qty / line count /
 * rate limit / note and staff open-item findings are fixed separately, in
 * CheckService.)
 */
class RedTeamKioskInputsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-redteam").resolve("pos.db").toString()
    private fun obj(t: String): JsonObject = json.parseToJsonElement(t).jsonObject

    private suspend fun HttpClient.postJson(path: String, body: String, deviceToken: String? = null) = post(path) {
        contentType(ContentType.Application.Json); setBody(body)
        deviceToken?.let { header("X-Device-Token", it) }
    }

    private fun storedNotes(): List<String> = transaction {
        CheckLines.selectAll().mapNotNull { it[CheckLines.note] }
    }

    // ------------------------------------------------------------ malformed bodies

    @Test
    fun `a malformed guest basket is a 400, not a 500`() = testApplication {
        application { module(dbPath = tempDb()) }
        val path = customerPath("t5")
        for (body in listOf("garbage", "{}", """{"lines":[{"itemId":"poutine","variantId":"poutine:regular","qty":1.5}]}""")) {
            val res = client.postJson("$path/pending-lines", body)
            assertEquals(HttpStatusCode.BadRequest, res.status, "$body -> ${res.bodyAsText()}")
            assertEquals("bad_body", obj(res.bodyAsText())["code"]!!.jsonPrimitive.content)
        }
        // an app newer than the store may send fields it does not know: still taken
        val extra = client.postJson("$path/pending-lines",
            """{"lines":[{"itemId":"poutine","variantId":"poutine:regular","qty":1,"spice":"hot"}],"tip":5}""")
        assertEquals(HttpStatusCode.Created, extra.status, extra.bodyAsText())
    }

    @Test
    fun `malformed kiosk bodies are a 400, not a 500`() = testApplication {
        application { module(dbPath = tempDb(), venueId = "express", physicalPrinterEnabled = false) }
        val token = pairKiosk()
        for ((path, body) in listOf(
            "/kiosk/orders" to "garbage", "/kiosk/orders" to "{}", "/kiosk/orders" to """{"serviceMode":"TAKE_OUT","lines":[{"itemId":1}]}""",
            "/kiosk/upsell" to "[1,2", "/kiosk/pair" to "nope",
        )) {
            val res = client.postJson(path, body, token)
            assertEquals(HttpStatusCode.BadRequest, res.status, "$path $body -> ${res.bodyAsText()}")
            assertEquals("bad_body", obj(res.bodyAsText())["code"]!!.jsonPrimitive.content)
        }
        // a staff route that reads its body the usual way: Ktor's own conversion error is a 400 too
        val staff = loginClient()
        val res = staff.post("/counter/orders/1/mode") { contentType(ContentType.Application.Json); setBody("{") }
        assertEquals(HttpStatusCode.BadRequest, res.status, res.bodyAsText())
    }

    // ------------------------------------------------------------ kiosk (/kiosk/*)

    private suspend fun ApplicationTestBuilder.pairKiosk(name: String = "Door"): String {
        val manager = loginClient()
        val code = obj(manager.post("/counter/kiosk-code").bodyAsText())["code"]!!.jsonPrimitive.content
        return obj(client.postJson("/kiosk/pair", """{"code":"$code","deviceName":"$name"}""").bodyAsText())["deviceToken"]!!.jsonPrimitive.content
    }

    private val burger = """{"itemId":"lantern-burger","variantId":"lantern-burger:regular"}"""

    @Test
    fun `a kiosk token cannot place hundreds of orders a second`() = testApplication {
        application { module(dbPath = tempDb(), venueId = "express", physicalPrinterEnabled = false) }
        val token = pairKiosk()
        val statuses = (1..150).map {
            client.postJson("/kiosk/orders", """{"serviceMode":"TAKE_OUT","lines":[$burger]}""", token).status
        }
        assertTrue(HttpStatusCode.TooManyRequests in statuses, "no request was throttled: ${statuses.groupingBy { it }.eachCount()}")
        assertEquals(10, statuses.count { it == HttpStatusCode.OK })
        // per kiosk: another kiosk still orders
        val other = pairKiosk("Door 2")
        assertEquals(HttpStatusCode.OK, client.postJson("/kiosk/orders", """{"serviceMode":"TAKE_OUT","lines":[$burger]}""", other).status)
        val limited = client.postJson("/kiosk/orders", """{"serviceMode":"TAKE_OUT","lines":[$burger]}""", token)
        assertEquals("rate_limited", obj(limited.bodyAsText())["code"]!!.jsonPrimitive.content)
        assertTrue((limited.headers[HttpHeaders.RetryAfter]?.toInt() ?: 0) >= 1)
    }

    @Test
    fun `a double tap with the same order id places one order`() = testApplication {
        application { module(dbPath = tempDb(), venueId = "express", physicalPrinterEnabled = false) }
        val token = pairKiosk()
        val body = """{"serviceMode":"TAKE_OUT","lines":[$burger],"clientOrderId":"k-7f3a"}"""
        val first = obj(client.postJson("/kiosk/orders", body, token).bodyAsText())
        val again = obj(client.postJson("/kiosk/orders", body, token).bodyAsText())
        assertEquals(first["checkId"]!!.jsonPrimitive.int, again["checkId"]!!.jsonPrimitive.int)
        assertEquals(first["orderNumber"]!!.jsonPrimitive.int, again["orderNumber"]!!.jsonPrimitive.int)
        val waiting = json.parseToJsonElement(loginClient().get("/counter/waiting").bodyAsText()).jsonArray
        assertEquals(1, waiting.size)
        // a new tap (a new id) is a new order
        val next = obj(client.postJson("/kiosk/orders", body.replace("k-7f3a", "k-8b21"), token).bodyAsText())
        assertEquals(first["orderNumber"]!!.jsonPrimitive.int + 1, next["orderNumber"]!!.jsonPrimitive.int)
        // an id that is not an id
        assertEquals(HttpStatusCode.BadRequest,
            client.postJson("/kiosk/orders", body.replace("k-7f3a", "a b"), token).status)
    }

    @Test
    fun `a kiosk note cannot carry NUL or control characters`() = testApplication {
        application { module(dbPath = tempDb(), venueId = "express", physicalPrinterEnabled = false) }
        val token = pairKiosk("Door\u0000\u001b[31m")
        val res = client.postJson("/kiosk/orders",
            """{"serviceMode":"TAKE_OUT","lines":[{"itemId":"brownie","variantId":"brownie:regular","note":"warm\u0000‮\nplease"}]}""", token)
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val dirty = storedNotes().filter { n -> n.any { it.isISOControl() } }
        assertTrue(dirty.isEmpty(), "stored: ${dirty.map { it.toByteArray().toList() }}")
        assertEquals(listOf("warm please"), storedNotes())
        // the device name too (it rides the heartbeat to the cloud)
        val names = transaction { dev.dwhipstock.pos.base.Devices.selectAll().map { it[dev.dwhipstock.pos.base.Devices.name] } }
        assertTrue(names.none { n -> n.any { it.isISOControl() } }, "$names")
    }

    @Test
    fun `paying a kiosk beer order needs an ID check on the store, not just a badge`() = testApplication {
        application { module(dbPath = tempDb(), venueId = "express", physicalPrinterEnabled = false) }
        val token = pairKiosk()
        val placed = obj(client.postJson("/kiosk/orders",
            """{"serviceMode":"TAKE_OUT","lines":[{"itemId":"north-ipa","variantId":"north-ipa:16oz","qty":20}]}""", token).bodyAsText())
        assertTrue(placed["idCheckAtCounter"]!!.jsonPrimitive.boolean)
        val checkId = placed["checkId"]!!.jsonPrimitive.content
        val cashier = loginClient("9999")
        val check = obj(cashier.get("/checks/$checkId").bodyAsText())
        assertTrue(check["ageCheckRequired"]!!.jsonPrimitive.boolean, "ageCheckRequired is false on a 20-beer kiosk order")
        assertFalse(check["ageCleared"]!!.jsonPrimitive.boolean)

        // no ID check: the money is refused
        loginClient().postJson("/shifts", """{"openingFloatCents":0,"managerPin":"1234"}""")
        val refused = cashier.postJson("/checks/$checkId/tenders", """{"type":"CASH","amountTenderedCents":100000}""")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals("age_check_required", obj(refused.bodyAsText())["code"]!!.jsonPrimitive.content)

        // under 21: still refused
        val young = obj(cashier.postJson("/retail/sales/$checkId/age-check",
            """{"method":"MANUAL","dateOfBirth":"2010-01-01","cashierSawId":true}""").bodyAsText())
        assertFalse(young["passed"]!!.jsonPrimitive.boolean)
        assertEquals(21, young["legalAge"]!!.jsonPrimitive.int)
        assertEquals(HttpStatusCode.Conflict,
            cashier.postJson("/checks/$checkId/tenders", """{"type":"CASH","amountTenderedCents":100000}""").status)

        // the date of birth without "I have seen the ID": not a pass
        val unseen = obj(cashier.postJson("/retail/sales/$checkId/age-check",
            """{"method":"MANUAL","dateOfBirth":"1990-01-01","cashierSawId":false}""").bodyAsText())
        assertFalse(unseen["passed"]!!.jsonPrimitive.boolean)

        // an adult, ID seen: recorded, and the order is paid
        val ok = obj(cashier.postJson("/retail/sales/$checkId/age-check",
            """{"method":"MANUAL","dateOfBirth":"1990-01-01","cashierSawId":true}""").bodyAsText())
        assertTrue(ok["passed"]!!.jsonPrimitive.boolean)
        assertTrue(cashier.postJson("/checks/$checkId/tenders", """{"type":"CASH","amountTenderedCents":100000}""").status.isSuccess())
        assertEquals(HttpStatusCode.OK, cashier.post("/checks/$checkId/finalize").status)
        val checks = transaction { dev.dwhipstock.pos.base.AgeChecks.selectAll().count() }
        assertEquals(3L, checks)
    }

    @Test
    fun `a counter order with no alcohol never meets the ID gate`() = testApplication {
        application { module(dbPath = tempDb(), venueId = "express", physicalPrinterEnabled = false) }
        val token = pairKiosk()
        val placed = obj(client.postJson("/kiosk/orders", """{"serviceMode":"TAKE_OUT","lines":[$burger]}""", token).bodyAsText())
        assertFalse(placed["idCheckAtCounter"]!!.jsonPrimitive.boolean)
        val check = obj(loginClient("9999").get("/checks/${placed["checkId"]!!.jsonPrimitive.content}").bodyAsText())
        assertFalse(check["ageCheckRequired"]!!.jsonPrimitive.boolean)
    }

    // ------------------------------------------------------------ unpairing a kiosk

    @Test
    fun `a manager unpairs a lost kiosk at the store, and its token stops working`() = testApplication {
        application { module(dbPath = tempDb(), venueId = "express", physicalPrinterEnabled = false) }
        val token = pairKiosk("Door 1")
        pairKiosk("Door 2")
        val manager = loginClient()
        assertEquals(HttpStatusCode.Forbidden, loginClient("9999").get("/counter/kiosks").status)
        val kiosks = json.parseToJsonElement(manager.get("/counter/kiosks").bodyAsText()).jsonArray.map { it.jsonObject }
        assertEquals(listOf("Kiosk — Door 1", "Kiosk — Door 2"), kiosks.map { it["name"]!!.jsonPrimitive.content })
        val lost = kiosks.first()["deviceId"]!!.jsonPrimitive.content

        assertEquals(HttpStatusCode.Forbidden, loginClient("9999").post("/counter/kiosks/$lost/unpair").status)
        val left = json.parseToJsonElement(manager.post("/counter/kiosks/$lost/unpair").bodyAsText()).jsonArray
        assertEquals(listOf("Kiosk — Door 2"), left.map { it.jsonObject["name"]!!.jsonPrimitive.content })

        val res = client.postJson("/kiosk/orders", """{"serviceMode":"TAKE_OUT","lines":[$burger]}""", token)
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/kiosk/config") { header("X-Device-Token", token) }.status)
        // not a kiosk (a terminal's id, or nothing)
        assertEquals(HttpStatusCode.NotFound, manager.post("/counter/kiosks/nope/unpair").status)
    }

    @Test
    fun `a kiosk token is not a staff terminal on a device-gated store`() = testApplication {
        application {
            module(dbPath = tempDb(), venueId = "express", physicalPrinterEnabled = false, requireDeviceTokenOverride = true)
        }
        // a real terminal (paired) signs in; it hands out the kiosk code
        client.get("/health")
        val terminal = dev.dwhipstock.pos.base.DeviceRegistry.pair("Counter").second
        assertEquals(HttpStatusCode.OK, client.get("/staff") { header("X-Device-Token", terminal) }.status)
        val session = obj(client.postJson("/login", """{"pin":"1234"}""", terminal).bodyAsText())["token"]!!.jsonPrimitive.content
        val code = obj(client.post("/counter/kiosk-code") {
            header("X-Device-Token", terminal); header(HttpHeaders.Authorization, "Bearer $session")
        }.bodyAsText())["code"]!!.jsonPrimitive.content
        val kiosk = obj(client.postJson("/kiosk/pair", """{"code":"$code","deviceName":"Door"}""").bodyAsText())["deviceToken"]!!.jsonPrimitive.content
        // the kiosk's token opens the kiosk routes...
        assertEquals(HttpStatusCode.OK, client.get("/kiosk/config") { header("X-Device-Token", kiosk) }.status)
        // ...but never the staff tiles or the PIN login
        assertEquals(HttpStatusCode.Unauthorized, client.get("/staff") { header("X-Device-Token", kiosk) }.status)
        val login = client.postJson("/login", """{"pin":"1234"}""", kiosk)
        assertEquals(HttpStatusCode.Unauthorized, login.status)
        assertEquals("device_required", obj(login.bodyAsText())["code"]!!.jsonPrimitive.content)
    }
}
