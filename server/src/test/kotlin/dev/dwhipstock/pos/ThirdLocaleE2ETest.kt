package dev.dwhipstock.pos

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end proof that a language added as a resource drop is selectable and
 * prints: the "zh" catalog exists only in test resources (partial, three keys),
 * yet a zh-preferring user gets a receipt with zh labels where translated and
 * default-locale (English) labels everywhere else.
 */
class ThirdLocaleE2ETest {

    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    @Test
    fun droppedInLocaleDrivesReceiptLanguageEndToEnd() = testApplication {
        application { module(dbPath = tempDb(), receiptsDir = Files.createTempDirectory("rc").toString()) }
        val manager = loginClient()

        // zh became a valid preference purely because messages_zh.properties is on the classpath
        val patched = manager.patch("/me/preferences") {
            contentType(ContentType.Application.Json)
            setBody("""{"languageCode":"zh","calendar":"CE"}""")
        }
        assertEquals(HttpStatusCode.OK, patched.status)

        manager.post("/shifts") {
            contentType(ContentType.Application.Json)
            setBody("""{"openingFloatCents":0,"managerPin":"1234"}""")
        }.let { assertEquals(HttpStatusCode.Created, it.status) }

        val checkId = Json.parseToJsonElement(manager.post("/tables/t5/checks") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"manager"}""")
        }.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        manager.post("/checks/$checkId/lines") {
            contentType(ContentType.Application.Json)
            setBody("""{"itemId":"lantern-lager","variantId":"lantern-lager:pint","qty":1}""")
        }
        manager.post("/checks/$checkId/tenders") {
            contentType(ContentType.Application.Json)
            setBody("""{"type":"CASH","amountTenderedCents":10000}""")
        }
        manager.post("/checks/$checkId/finalize")
        val receipt = Json.parseToJsonElement(manager.get("/checks/$checkId/receipt").bodyAsText())
            .jsonObject["text"]!!.jsonPrimitive.content

        assertTrue("总计" in receipt, "zh-translated total label expected, got:\n$receipt")
        assertTrue("Total" !in receipt, "default-locale total should be replaced by the zh one")
        assertTrue("Open" in receipt, "keys zh does not translate must fall back to the default locale")
    }
}
