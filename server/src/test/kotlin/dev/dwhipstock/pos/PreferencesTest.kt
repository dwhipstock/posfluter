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

class PreferencesTest {

    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    @Test
    fun olderClientSendingRetiredCalendarFieldIsAccepted() = testApplication {
        application { module(dbPath = tempDb(), receiptsDir = Files.createTempDirectory("rc").toString()) }
        val manager = loginClient()

        // pre-removal clients still PATCH {"calendar":"CE"}; the field is ignored
        val patched = manager.patch("/me/preferences") {
            contentType(ContentType.Application.Json)
            setBody("""{"languageCode":"fr","calendar":"CE"}""")
        }
        assertEquals(HttpStatusCode.OK, patched.status)
        val body = Json.parseToJsonElement(patched.bodyAsText()).jsonObject
        assertEquals("fr", body["languageCode"]!!.jsonPrimitive.content)
        assertTrue("calendar" !in body)
        val me = Json.parseToJsonElement(manager.get("/me").bodyAsText()).jsonObject
        assertEquals("fr", me["languageCode"]!!.jsonPrimitive.content)
    }

    @Test
    fun preferencesPersistAndDriveReceiptLanguage() = testApplication {
        application { module(dbPath = tempDb(), receiptsDir = Files.createTempDirectory("rc").toString()) }
        val manager = loginClient()

        // default is English; the profile carries no retired preference fields
        var me = Json.parseToJsonElement(manager.get("/me").bodyAsText()).jsonObject
        assertEquals("en", me["languageCode"]!!.jsonPrimitive.content)
        assertTrue("calendar" !in me, "retired calendar field still on /me: $me")

        // switch to French — persisted on the user row
        val patched = manager.patch("/me/preferences") {
            contentType(ContentType.Application.Json)
            setBody("""{"languageCode":"fr"}""")
        }
        assertEquals(HttpStatusCode.OK, patched.status)
        me = Json.parseToJsonElement(manager.get("/me").bodyAsText()).jsonObject
        assertEquals("fr", me["languageCode"]!!.jsonPrimitive.content)

        // invalid values rejected
        assertEquals(HttpStatusCode.BadRequest, manager.patch("/me/preferences") {
            contentType(ContentType.Application.Json)
            setBody("""{"languageCode":"de"}""")
        }.status)

        // tendering needs an open shift
        manager.post("/shifts") {
            contentType(ContentType.Application.Json)
            setBody("""{"openingFloatCents":0,"managerPin":"1234"}""")
        }.let { assertEquals(HttpStatusCode.Created, it.status) }

        // check opened by this French-preferring manager prints a French receipt
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
        assertTrue("Ouverture" in receipt, "expected French receipt, got:\n$receipt")
        assertTrue("Open" !in receipt, "English label leaked into French receipt")

        // error bodies carry machine codes for client-side translation
        val conflict = manager.post("/checks/$checkId/lines") {
            contentType(ContentType.Application.Json)
            setBody("""{"itemId":"lantern-lager","variantId":"lantern-lager:pint","qty":1}""")
        }
        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertEquals("check_not_open",
            Json.parseToJsonElement(conflict.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
    }
}
