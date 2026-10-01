package dev.dwhipstock.pos

import dev.dwhipstock.pos.restaurant.CheckLines
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Red team (inputs), the check-side findings — from redteam/inputs
 * (RedTeamInputsTest, 87e2489): guest QR baskets bounded like the kiosk, free
 * text cleaned, staff quantities and open-item prices capped, the guest's
 * order reply is the guest's own bill, and staff can turn a flood of guest
 * lines away at once. (Rate limits, kiosk and 400-vs-500 body errors are
 * covered by the other builder's copy of RedTeamInputsTest.)
 */
class RedTeamInputsChecksTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-redteam").resolve("pos.db").toString()
    private fun obj(t: String): JsonObject = json.parseToJsonElement(t).jsonObject

    private suspend fun HttpClient.postJson(path: String, body: String) = post(path) {
        contentType(ContentType.Application.Json); setBody(body)
    }

    private fun line(qty: Long = 1, note: String? = null) =
        """{"itemId":"poutine","variantId":"poutine:regular","qty":$qty${note?.let { ",\"note\":" + kotlinx.serialization.json.JsonPrimitive(it).toString() } ?: ""}}"""

    private fun storedNotes(): List<String> = transaction { CheckLines.selectAll().mapNotNull { it[CheckLines.note] } }

    @Test
    fun `a guest QR line cannot ask for two billion poutines`() = testApplication {
        application { module(dbPath = tempDb()) }
        val path = customerPath("t5")
        val res = client.postJson("$path/pending-lines", """{"lines":[${line(Int.MAX_VALUE.toLong())}]}""")
        assertEquals(HttpStatusCode.BadRequest, res.status, res.bodyAsText().take(200))
        assertEquals("qty_out_of_range", obj(res.bodyAsText())["code"]!!.jsonPrimitive.content)
        // 20 is the kiosk's limit too
        assertEquals(HttpStatusCode.Created, client.postJson("$path/pending-lines", """{"lines":[${line(20)}]}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("$path/pending-lines", """{"lines":[${line(21)}]}""").status)
    }

    @Test
    fun `one unauthenticated guest request cannot freeze a table's payment with thousands of pending lines`() = testApplication {
        application { module(dbPath = tempDb()) }
        val path = customerPath("t5")
        val basket = (1..5000).joinToString(",", """{"lines":[""", "]}") { line() }
        val res = client.postJson("$path/pending-lines", basket)
        assertEquals(HttpStatusCode.BadRequest, res.status, "status ${res.status}")
        assertEquals("too_many_lines", obj(res.bodyAsText())["code"]!!.jsonPrimitive.content)
        // 40 lines a basket (like the kiosk), and at most 100 waiting on one check
        val forty = (1..40).joinToString(",", """{"lines":[""", "]}") { line() }
        repeat(2) { assertEquals(HttpStatusCode.Created, client.postJson("$path/pending-lines", forty).status) }
        val third = client.postJson("$path/pending-lines", forty)
        assertEquals(HttpStatusCode.Conflict, third.status)
        assertEquals("too_many_pending", obj(third.bodyAsText())["code"]!!.jsonPrimitive.content)

        // staff turn them all away at once
        val staff = loginClient()
        val check = staff.staffCheckAt("t5")
        assertEquals(80, check["pendingLines"]!!.jsonArray.size)
        val id = check["id"]!!.jsonPrimitive.int
        val cleared = staff.post("/checks/$id/pending-lines/reject-all")
        assertEquals(HttpStatusCode.OK, cleared.status, cleared.bodyAsText())
        // nothing else was on the bill: it is gone, like rejecting the last line one by one
        assertEquals("CANCELLED", obj(cleared.bodyAsText())["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a guest note cannot carry NUL or control characters (they wedge cloud sync)`() = testApplication {
        application { module(dbPath = tempDb()) }
        val path = customerPath("t5")
        val res = client.postJson("$path/pending-lines", """{"lines":[${line(note = "gravy\u0000\u001b[31m‮ on the side")}]}""")
        assertEquals(HttpStatusCode.Created, res.status)
        assertEquals(listOf("gravy[31m on the side"), storedNotes())
        // a staff note and an open-item name are cleaned the same way
        val staff = loginClient()
        val id = staff.staffCheckAt("t6")["id"]!!.jsonPrimitive.int
        staff.postJson("/checks/$id/lines", """{"itemId":"poutine","variantId":"poutine:regular","note":"no\u0000 onions\nplease"}""")
        val open = obj(staff.postJson("/checks/$id/open-lines", """{"name":"Gift\u0000 card\u0007","unitPriceCents":500}""").bodyAsText())
        assertTrue("no onions please" in storedNotes(), storedNotes().toString())
        assertEquals("Gift card", open["lines"]!!.jsonArray.last().jsonObject["nameEn"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the guest's order reply is the guest's own bill, not the staff view`() = testApplication {
        application { module(dbPath = tempDb()) }
        val res = client.postJson("${customerPath("t5")}/pending-lines", """{"lines":[${line(2, "extra gravy")}]}""")
        assertEquals(HttpStatusCode.Created, res.status)
        val body = obj(res.bodyAsText())
        // same shape as GET /m/t/{token}/bill: no check id, line ids, tenders or table id
        for (k in listOf("id", "tableId", "tenders", "split", "paidCents")) assertNull(body[k], "$k leaked: $body")
        val pending = body["pendingLines"]!!.jsonArray.single().jsonObject
        assertNull(pending["id"])
        assertEquals(2, pending["qty"]!!.jsonPrimitive.int)
        assertEquals("extra gravy", pending["note"]!!.jsonPrimitive.content)
        assertTrue(body["rejected"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `a server's open item cannot overflow into a negative line total`() = testApplication {
        application { module(dbPath = tempDb()) }
        val server = loginClient("9999") // SERVER role: no discount / comp grant
        val check = obj(server.post("/tables/t6/checks").bodyAsText())["id"]!!.jsonPrimitive.content
        server.postJson("/checks/$check/lines", """{"itemId":"poutine","variantId":"poutine:regular"}""")
        // 2 x (2^63 - 600) wrapped to -1200: the line read "-$12.00", an unapproved $12 comp
        val res = server.postJson("/checks/$check/open-lines",
            """{"name":"Gift card","unitPriceCents":${Long.MAX_VALUE - 599},"qty":2}""")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("price_too_high", obj(res.bodyAsText())["code"]!!.jsonPrimitive.content)
        // $99,999.99 is the most one unit can be
        assertEquals(HttpStatusCode.Created, server.postJson("/checks/$check/open-lines",
            """{"name":"Deposit","unitPriceCents":9999999,"qty":1}""").status)
        assertEquals(HttpStatusCode.BadRequest, server.postJson("/checks/$check/open-lines",
            """{"name":"Deposit","unitPriceCents":10000000,"qty":1}""").status)
    }

    @Test
    fun `a staff line cannot ring two billion of anything`() = testApplication {
        application { module(dbPath = tempDb()) }
        val server = loginClient("9999")
        val id = obj(server.post("/tables/t6/checks").bodyAsText())["id"]!!.jsonPrimitive.int
        val huge = server.postJson("/checks/$id/lines", """{"itemId":"poutine","variantId":"poutine:regular","qty":2147483647}""")
        assertEquals(HttpStatusCode.BadRequest, huge.status)
        assertEquals("qty_out_of_range", obj(huge.bodyAsText())["code"]!!.jsonPrimitive.content)
        val ok = obj(server.postJson("/checks/$id/lines", """{"itemId":"poutine","variantId":"poutine:regular","qty":999}""").bodyAsText())
        val lineId = ok["lines"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.int
        assertEquals(999L * ok["lines"]!!.jsonArray.single().jsonObject["unitPriceCents"]!!.jsonPrimitive.long,
            ok["itemsSubtotalCents"]!!.jsonPrimitive.long)
        assertEquals(HttpStatusCode.BadRequest, server.postJson("/checks/$id/lines/$lineId/qty", """{"qty":1000}""").status)
        assertEquals(HttpStatusCode.BadRequest, server.postJson("/checks/$id/open-lines",
            """{"name":"x","unitPriceCents":100,"qty":1000}""").status)
    }
}
