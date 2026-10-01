package dev.dwhipstock.pos

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
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The floor plan's table tile after one bill of a split is paid: GET /zones
 * says what is still owed (and what is paid), not just the whole total. A
 * table with nothing paid answers exactly as before (neither field).
 */
class FloorPartPaidTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-floor-paid").resolve("pos.db").toString()

    private suspend fun HttpClient.postJson(path: String, body: String = "{}") =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }
    private suspend fun HttpResponse.obj(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpClient.table(id: String): JsonObject =
        json.parseToJsonElement(get("/zones").bodyAsText()).jsonArray
            .flatMap { z -> z.jsonObject["tables"]!!.jsonArray.map { it.jsonObject } }
            .single { it["id"]!!.jsonPrimitive.content == id }

    @Test
    fun `a part-paid split shows the balance on the floor, an unpaid check does not`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        c.postJson("/shifts", """{"openingFloatCents":10000}""")

        // t5: two pints ($15.00 + Tax 8.25% 1.24 = $16.24), split evenly in two
        val id = c.postJson("/tables/t5/checks").obj()["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$id/lines", """{"itemId":"lantern-lager","variantId":"lantern-lager:pint","qty":2}""")
        // t6: an open check nobody has paid anything on
        val other = c.postJson("/tables/t6/checks").obj()["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$other/lines", """{"itemId":"lantern-lager","variantId":"lantern-lager:pint","qty":1}""")

        val split = c.postJson("/checks/$id/split", """{"groups":2,"even":true}""").obj()
        val total = split["grandTotalCents"]!!.jsonPrimitive.long
        assertEquals(1624L, total)
        val g1 = split["split"]!!.jsonObject["groups"]!!.jsonArray.first().jsonObject
        val cash = g1["cashDueCents"]!!.jsonPrimitive.long
        val paid = c.postJson("/checks/$id/tenders",
            """{"type":"CASH","amountTenderedCents":$cash,"groupId":${g1["id"]!!.jsonPrimitive.int}}""")
        assertEquals(HttpStatusCode.Created, paid.status, paid.bodyAsText())
        val view = c.get("/checks/$id").obj()
        val paidCents = view["paidCents"]!!.jsonPrimitive.long
        assertEquals(g1["grandTotalCents"]!!.jsonPrimitive.long, paidCents) // the first guest's share: $8.12
        assertEquals(812L, paidCents)

        val t5 = c.table("t5")
        assertEquals(id, t5["openCheckId"]!!.jsonPrimitive.int)
        assertEquals(total, t5["openCheckTotalCents"]!!.jsonPrimitive.long)
        assertEquals(paidCents, t5["openCheckPaidCents"]!!.jsonPrimitive.long)
        assertEquals(total - paidCents, t5["openCheckOutstandingCents"]!!.jsonPrimitive.long)
        assertEquals(view["outstandingCents"]!!.jsonPrimitive.long, t5["openCheckOutstandingCents"]!!.jsonPrimitive.long)

        val t6 = c.table("t6")
        assertEquals(other, t6["openCheckId"]!!.jsonPrimitive.int)
        assertEquals(812L, t6["openCheckTotalCents"]!!.jsonPrimitive.long)
        assertFalse("openCheckPaidCents" in t6, t6.toString())
        assertFalse("openCheckOutstandingCents" in t6, t6.toString())
    }
}
