package dev.dwhipstock.pos

import dev.dwhipstock.pos.restaurant.KitchenPrintJobs
import dev.dwhipstock.pos.restaurant.KitchenSentLines
import dev.dwhipstock.pos.restaurant.KitchenStations
import dev.dwhipstock.pos.restaurant.KitchenTickets
import dev.dwhipstock.pos.sdk.KitchenPrinting
import dev.dwhipstock.pos.sdk.StaffAppMfa
import io.ktor.client.HttpClient
import io.ktor.client.plugins.DefaultRequest
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
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Kitchen tickets through the store app: off by default, on by config, the web screen's sign-in. */
class KitchenAppTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDir(): File = Files.createTempDirectory("pos-kitchen-app").toFile()
    private fun obj(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    // --- the switch ---

    @Test
    fun `the switch parses like the other store switches and defaults to off`() {
        assertEquals(KitchenPrinting.OFF, KitchenPrinting.fromEnv { null }.mode)
        assertEquals(KitchenPrinting.ON, KitchenPrinting.fromEnv { if (it == KitchenPrinting.ENV) " On " else null }.mode)
        val bad = KitchenPrinting.resolve("maybe", "test")
        assertEquals(KitchenPrinting.OFF, bad.mode)
        assertNotNull(bad.warning)
        val file = File(tempDir(), "store.properties").apply { writeText("kitchen.printing=on\n") }
        assertEquals(KitchenPrinting.ON, KitchenPrinting.fromFile(file).mode)
        assertEquals(KitchenPrinting.ON,
            KitchenPrinting.fromEnv { if (it == "POS_CONFIG_FILE") file.path else null }.mode)
        assertEquals(KitchenPrinting.OFF, KitchenPrinting.fromFile(File(tempDir(), "absent")).mode)
    }

    private suspend fun ApplicationTestBuilder.order(c: HttpClient, table: String = "t3"): Int {
        val id = obj(c.post("/tables/$table/checks").bodyAsText())["id"]!!.jsonPrimitive.int
        c.post("/checks/$id/lines") { contentType(ContentType.Application.Json)
            setBody("""{"itemId":"lantern-burger","variantId":"lantern-burger:regular","qty":2}""") }
        c.post("/checks/$id/lines") { contentType(ContentType.Application.Json)
            setBody("""{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":1}""") }
        return id
    }

    @Test
    fun `off by default - the store behaves exactly as before`() = testApplication {
        val fake = FakeKitchenTransport()
        application { module(dbPath = File(tempDir(), "pos.db").path, kitchenTransport = fake) }
        val c = loginClient()
        val health = obj(client.get("/health").bodyAsText())
        assertNull(health["kitchenPrinting"], "/health answers exactly as before")
        assertFalse(obj(c.get("/kitchen/status").bodyAsText())["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(HttpStatusCode.NotFound, client.get("/kitchen").status)

        c.patch("/settings") { contentType(ContentType.Application.Json); setBody("""{"printerIp":"10.0.0.9"}""") }
        val id = order(c)
        val send = c.post("/checks/$id/kitchen/send")
        assertEquals(HttpStatusCode.Conflict, send.status)
        assertEquals("kitchen_off", obj(send.bodyAsText())["code"]!!.jsonPrimitive.content)

        // the check itself carries nothing new, and a remove / void / pay run as always
        val check = obj(c.get("/checks/$id").bodyAsText())
        assertTrue(check.keys.none { it.contains("kitchen", ignoreCase = true) || it == "guests" })
        val line = check["lines"]!!.jsonArray.last().jsonObject["id"]!!.jsonPrimitive.int
        assertEquals(HttpStatusCode.OK, c.delete("/checks/$id/lines/$line").status)
        assertEquals(HttpStatusCode.OK, c.post("/checks/$id/void") {
            contentType(ContentType.Application.Json); setBody("""{"reason":"test","managerPin":"1234"}""") }.status)
        val paid = order(c, "t5")
        c.post("/shifts") { contentType(ContentType.Application.Json); setBody("""{"openingFloatCents":0,"managerPin":"1234"}""") }
        c.post("/checks/$paid/tenders") { contentType(ContentType.Application.Json); setBody("""{"type":"CASH","amountTenderedCents":100000}""") }
        assertEquals(HttpStatusCode.OK, c.post("/checks/$paid/finalize").status)

        Thread.sleep(300)
        assertEquals(0, fake.attempts.get(), "no station printer is ever contacted")
        transaction {
            assertEquals(0, KitchenStations.selectAll().count(), "no stations seeded while off")
            assertEquals(0, KitchenTickets.selectAll().count())
            assertEquals(0, KitchenSentLines.selectAll().count())
            assertEquals(0, KitchenPrintJobs.selectAll().count())
        }
    }

    @Test
    fun `on - send prints through the worker and the board shows it`() = testApplication {
        val fake = FakeKitchenTransport()
        application {
            module(dbPath = File(tempDir(), "pos.db").path,
                kitchenPrinting = KitchenPrinting.Resolved(KitchenPrinting.ON, "test"),
                kitchenTransport = fake)
        }
        val manager = loginClient()
        assertTrue(obj(client.get("/health").bodyAsText())["kitchenPrinting"]!!.jsonPrimitive.boolean)
        manager.patch("/settings") { contentType(ContentType.Application.Json); setBody("""{"printerIp":"10.0.0.9"}""") }
        val config = obj(manager.get("/kitchen/config").bodyAsText())
        assertEquals(listOf("kitchen", "bar"), config["stations"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })

        val server = loginClient("9999")
        val id = order(server)
        assertEquals(3, obj(server.get("/checks/$id/kitchen").bodyAsText())["unsent"]!!.jsonPrimitive.int)
        val sent = obj(server.post("/checks/$id/kitchen/send").bodyAsText())
        assertEquals(2, sent["tickets"]!!.jsonPrimitive.int)
        val deadline = System.currentTimeMillis() + 10_000
        while (fake.sent.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(2, fake.sent.size, "the worker printed both station tickets")
        assertTrue(fake.sent.all { it.target == "10.0.0.9:9100" }, "blank station printer = the receipt printer")
        assertEquals(0, obj(server.get("/kitchen/status").bodyAsText())["waiting"]!!.jsonPrimitive.int)

        val board = obj(server.get("/kitchen/board").bodyAsText())
        assertEquals(2, board["cards"]!!.jsonArray.size)
        val bump = server.post("/kitchen/board/bump") { contentType(ContentType.Application.Json)
            setBody("""{"checkId":$id,"stationId":"kitchen"}""") }
        assertEquals(HttpStatusCode.OK, bump.status)
        assertEquals(1, obj(server.get("/kitchen/board?station=bar").bodyAsText())["cards"]!!.jsonArray.size)
        assertEquals(0, obj(server.get("/kitchen/board?station=kitchen").bodyAsText())["cards"]!!.jsonArray.size)
        assertEquals(HttpStatusCode.OK, server.post("/kitchen/board/recall").status)
        assertEquals(1, obj(server.get("/kitchen/board?station=kitchen").bodyAsText())["cards"]!!.jsonArray.size)

        // station setup is manager-only
        assertEquals(HttpStatusCode.Forbidden, server.get("/kitchen/config").status)
        assertEquals(HttpStatusCode.OK, manager.post("/kitchen/stations/kitchen/test").status)
    }

    @Test
    fun `the web kitchen screen signs in like the staff app`() = testApplication {
        application {
            module(dbPath = File(tempDir(), "pos.db").path,
                kitchenPrinting = KitchenPrinting.Resolved(KitchenPrinting.ON, "test"),
                kitchenTransport = FakeKitchenTransport(),
                staffAppMfa = StaffAppMfa.Resolved(StaffAppMfa.OFF, "test"))
        }
        // the page shell is open; the data is not
        val page = client.get("/kitchen")
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        assertTrue("/staff-app/login" in html && "/kitchen/board" in html)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/kitchen/board").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/kitchen/board/bump") {
            contentType(ContentType.Application.Json); setBody("""{"checkId":1,"stationId":"kitchen"}""") }.status)
        // a wrong PIN gets nothing
        assertEquals(HttpStatusCode.Unauthorized, client.post("/staff-app/login") {
            contentType(ContentType.Application.Json); setBody("""{"pin":"0000"}""") }.status)
        // the staff PIN (MFA off here) gives the bearer the page polls with
        val login = obj(client.post("/staff-app/login") {
            contentType(ContentType.Application.Json); setBody("""{"pin":"9999"}""") }.bodyAsText())
        assertEquals("ok", login["status"]!!.jsonPrimitive.content)
        val token = login["user"]!!.jsonObject["token"]!!.jsonPrimitive.content
        val phone = createClient { install(DefaultRequest) { header(HttpHeaders.Authorization, "Bearer $token") } }
        val board = obj(phone.get("/kitchen/board").bodyAsText())
        assertEquals(0, board["cards"]!!.jsonArray.size)
        assertNull(board["error"])
    }
}
