package dev.dwhipstock.pos

import dev.dwhipstock.pos.restaurant.CheckLines
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.boolean
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RED TEAM (branch redteam/inputs): guest / kiosk / staff ordering inputs.
 * Every test here describes how the store SHOULD behave and FAILS on main
 * (97e0d94). They are evidence, not fixes. Run:
 *   cd server && ./gradlew test --tests 'dev.dwhipstock.pos.RedTeamInputsTest'
 */
class RedTeamInputsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-redteam").resolve("pos.db").toString()
    private fun obj(t: String): JsonObject = json.parseToJsonElement(t).jsonObject

    private suspend fun HttpClient.postJson(path: String, body: String, deviceToken: String? = null) = post(path) {
        contentType(ContentType.Application.Json); setBody(body)
        deviceToken?.let { header("X-Device-Token", it) }
    }

    private fun line(qty: Long = 1, note: String? = null) =
        """{"itemId":"poutine","variantId":"poutine:regular","qty":$qty${note?.let { ",\"note\":" + kotlinx.serialization.json.JsonPrimitive(it).toString() } ?: ""}}"""

    private fun storedNotes(): List<String> = transaction {
        CheckLines.selectAll().mapNotNull { it[CheckLines.note] }
    }

    // ------------------------------------------------------------ guest QR (/m/t/{token})

    @Test
    fun `a guest QR line cannot ask for two billion poutines`() = testApplication {
        application { module(dbPath = tempDb()) }
        val path = customerPath("t5")
        val res = client.postJson("$path/pending-lines", """{"lines":[${line(Int.MAX_VALUE.toLong())}]}""")
        // today: 201 — a $27.9 billion pending line lands on the table's check
        assertEquals(HttpStatusCode.BadRequest, res.status, res.bodyAsText().take(200))
    }

    @Test
    fun `one unauthenticated guest request cannot freeze a table's payment with thousands of pending lines`() = testApplication {
        application { module(dbPath = tempDb()) }
        val path = customerPath("t5")
        val basket = (1..5000).joinToString(",", """{"lines":[""", "]}") { line() }
        val res = client.postJson("$path/pending-lines", basket)
        // today: 201 — 5,000 PENDING lines; tender and bill print are refused
        // (pending_lines_unresolved) until staff reject them ONE BY ONE
        assertEquals(HttpStatusCode.BadRequest, res.status, "status ${res.status}")
    }

    @Test
    fun `a table QR token is rate limited`() = testApplication {
        application { module(dbPath = tempDb()) }
        val path = customerPath("t5")
        val statuses = (1..200).map { client.postJson("$path/pending-lines", """{"lines":[${line()}]}""").status }
        // today: 200 x 201 Created, no limit at all
        assertTrue(HttpStatusCode.TooManyRequests in statuses, "no request was throttled: ${statuses.groupingBy { it }.eachCount()}")
    }

    @Test
    fun `a guest note cannot carry NUL or control characters (they wedge cloud sync)`() = testApplication {
        application { module(dbPath = tempDb()) }
        val path = customerPath("t5")
        val res = client.postJson("$path/pending-lines", """{"lines":[${line(note = "gravy\u0000\u001b[31m‮")}]}""")
        // either refused, or stored clean; today it is stored verbatim (and pushed to Postgres JSONB, which rejects \u0000)
        val dirty = storedNotes().filter { n -> n.any { it == '\u0000' || (it.isISOControl() && it != '\n') } }
        assertTrue(res.status == HttpStatusCode.BadRequest || dirty.isEmpty(), "stored: ${dirty.map { it.toByteArray().toList() }}")
    }

    @Test
    fun `a malformed guest basket is a 400, not a 500`() = testApplication {
        application { module(dbPath = tempDb()) }
        val path = customerPath("t5")
        for (body in listOf("garbage", "{}", """{"lines":[{"itemId":"poutine","variantId":"poutine:regular","qty":1.5}]}""")) {
            val res = client.postJson("$path/pending-lines", body)
            assertEquals(HttpStatusCode.BadRequest, res.status, "$body -> ${res.bodyAsText()}")
        }
    }

    // ------------------------------------------------------------ staff: open items

    @Test
    fun `a server's open item cannot overflow into a negative line total`() = testApplication {
        application { module(dbPath = tempDb()) }
        val server = loginClient("9999") // SERVER role: no discount / comp grant
        val check = obj(server.post("/tables/t6/checks").bodyAsText())["id"]!!.jsonPrimitive.content
        server.postJson("/checks/$check/lines", """{"itemId":"poutine","variantId":"poutine:regular"}""")
        // 2 x (2^63 - 600) wraps to -1200: the line reads "-$12.00", an unapproved $12 comp
        val res = server.postJson("/checks/$check/open-lines",
            """{"name":"Gift card","unitPriceCents":${Long.MAX_VALUE - 599},"qty":2}""")
        if (res.status.isSuccess()) {
            val lines = obj(res.bodyAsText())["lines"]!!.jsonArray.map { it.jsonObject["lineTotalCents"]!!.jsonPrimitive.long }
            assertTrue(lines.all { it >= 0 }, "line totals: $lines")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    // ------------------------------------------------------------ kiosk (/kiosk/*)

    private suspend fun ApplicationTestBuilder.pairKiosk(): String {
        val manager = loginClient()
        val code = obj(manager.post("/counter/kiosk-code").bodyAsText())["code"]!!.jsonPrimitive.content
        return obj(client.postJson("/kiosk/pair", """{"code":"$code","deviceName":"Door"}""").bodyAsText())["deviceToken"]!!.jsonPrimitive.content
    }

    private val burger = """{"itemId":"lantern-burger","variantId":"lantern-burger:regular"}"""

    @Test
    fun `a kiosk token cannot place hundreds of orders a second`() = testApplication {
        application { module(dbPath = tempDb(), venueId = "express", physicalPrinterEnabled = false) }
        val token = pairKiosk()
        val statuses = (1..150).map {
            client.postJson("/kiosk/orders", """{"serviceMode":"TAKE_OUT","lines":[$burger]}""", token).status
        }
        // today: 150 x 200, 150 order numbers taken, 150 tickets queued on the receipt printer
        assertTrue(HttpStatusCode.TooManyRequests in statuses, "no request was throttled: ${statuses.groupingBy { it }.eachCount()}")
    }

    @Test
    fun `a kiosk note cannot carry NUL or control characters`() = testApplication {
        application { module(dbPath = tempDb(), venueId = "express", physicalPrinterEnabled = false) }
        val token = pairKiosk()
        val res = client.postJson("/kiosk/orders",
            """{"serviceMode":"TAKE_OUT","lines":[{"itemId":"brownie","variantId":"brownie:regular","note":"warm\u0000"}]}""", token)
        val dirty = storedNotes().filter { n -> n.any { it.isISOControl() } }
        assertTrue(res.status == HttpStatusCode.BadRequest || dirty.isEmpty(), "stored: ${dirty.map { it.toByteArray().toList() }}")
    }

    @Test
    fun `paying a kiosk beer order needs an ID check on the store, not just a badge`() = testApplication {
        application { module(dbPath = tempDb(), venueId = "express", physicalPrinterEnabled = false) }
        val token = pairKiosk()
        val placed = obj(client.postJson("/kiosk/orders",
            """{"serviceMode":"TAKE_OUT","lines":[{"itemId":"north-ipa","variantId":"north-ipa:16oz","qty":20}]}""", token).bodyAsText())
        assertTrue(placed["idCheckAtCounter"]!!.jsonPrimitive.boolean)
        val check = obj(loginClient("9999").get("/checks/${placed["checkId"]!!.jsonPrimitive.content}").bodyAsText())
        // today: false — 20 IPAs can be tendered and finalized with no ID check recorded anywhere
        assertTrue(check["ageCheckRequired"]!!.jsonPrimitive.boolean, "ageCheckRequired is false on a 20-beer kiosk order")
    }
}
