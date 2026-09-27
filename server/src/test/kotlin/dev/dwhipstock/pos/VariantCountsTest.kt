package dev.dwhipstock.pos

import dev.dwhipstock.pos.restaurant.variantCountsOnCheck
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A line shows its size only when its item has more than one. The count covers
 * the check's own items, not the whole catalog (a 5,000-product shelf made every
 * scan read every variant: docs/load-test-report.md).
 */
class VariantCountsTest {

    @Test
    fun `sizes are counted for the items on the check only`() = testApplication {
        application { module(dbPath = Files.createTempDirectory("pos-variants").resolve("pos.db").toString()) }
        val c = loginClient()
        c.post("/shifts") { contentType(ContentType.Application.Json); setBody("""{"openingFloatCents":0,"managerPin":"1234"}""") }
        val items = Json.parseToJsonElement(c.get("/items").bodyAsText()).jsonArray.map { it.jsonObject }
        val sizes = items.associate { it["id"]!!.jsonPrimitive.content to it["variants"]!!.jsonArray.size }
        val multi = items.first { it["variants"]!!.jsonArray.size > 1 }
        val single = items.first { it["variants"]!!.jsonArray.size == 1 }

        val check = Json.parseToJsonElement(c.post("/tables/t5/checks").bodyAsText()).jsonObject
        val id = check["id"]!!.jsonPrimitive.int
        for (item in listOf(multi, single)) {
            val variant = item["variants"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content
            c.post("/checks/$id/lines") {
                contentType(ContentType.Application.Json)
                setBody("""{"itemId":"${item["id"]!!.jsonPrimitive.content}","variantId":"$variant","qty":1}""")
            }
        }
        val multiId = multi["id"]!!.jsonPrimitive.content
        val singleId = single["id"]!!.jsonPrimitive.content
        val counts = transaction { variantCountsOnCheck(id) }
        assertEquals(mapOf(multiId to sizes[multiId], singleId to sizes[singleId]), counts)
        assertEquals(counts, transaction { variantCountsOnCheck(id, liveOnly = true) })
        assertEquals(emptyMap(), transaction { variantCountsOnCheck(id + 1000) })

        // the check view still labels the size of the multi-size item only
        val lines = Json.parseToJsonElement(c.get("/checks/$id").bodyAsText()).jsonObject["lines"]!!.jsonArray
            .map { it.jsonObject }
        val label = { item: String -> lines.first { it["itemId"]!!.jsonPrimitive.content == item }["variantLabelEn"] }
        assertEquals(true, label(multiId).toString() != "null")
        assertEquals("null", label(singleId).toString())
    }
}
