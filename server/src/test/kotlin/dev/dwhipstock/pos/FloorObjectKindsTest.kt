package dev.dwhipstock.pos

import dev.dwhipstock.pos.sdk.MenuAiConfig
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.Base64
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Room objects beyond pool/bar/pillar: the five built-ins, CUSTOM by hand, and "Add from photo". */
class FloorObjectKindsTest {
    private val key = "sk-ant-SECRET-never-leaves-0123456789"
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")

    private fun tempDir() = Files.createTempDirectory("pos-room-objects").toString()
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    private fun on() = MenuAiConfig.fromProperties(Properties().apply {
        setProperty("menu.ai", "on")
        setProperty("menu.ai.provider", "anthropic")
        setProperty("menu.ai.anthropic.apiKey", key)
    })

    private suspend fun io.ktor.client.HttpClient.add(body: String) =
        post("/zones/outside/objects") { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun io.ktor.client.HttpClient.suggest(pin: String? = "1234") =
        submitFormWithBinaryData("/floor-objects/ai-suggest", formData {
            if (pin != null) append("managerPin", pin)
            append("photo", png, Headers.build {
                append(HttpHeaders.ContentType, "image/png")
                append(HttpHeaders.ContentDisposition, "filename=\"thing.png\"")
            })
        })

    @Test
    fun `the five new built-in types place, move and delete like the old ones`() = testApplication {
        application { module(dbPath = tempDir() + "/pos.db", photosDir = tempDir()) }
        val c = loginClient()
        for (type in listOf("ENTRANCE", "HOST_STAND", "KITCHEN", "RESTROOMS", "STAGE")) {
            val res = c.add("""{"type":"$type","x":100,"y":100,"width":120,"height":60,"managerPin":"1234"}""")
            assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
            val o = obj(res.bodyAsText())
            assertEquals(type, o.s("type"))
            assertTrue(o["icon"] == null || o["icon"].toString() == "null")
            val moved = c.put("/zones/outside/objects-layout") {
                contentType(ContentType.Application.Json)
                setBody("""{"managerPin":"1234","objects":[{"id":"${o.s("id")}","x":300,"y":200,"width":200,"height":80,"rotation":90}]}""")
            }
            assertEquals(HttpStatusCode.OK, moved.status)
            assertEquals(HttpStatusCode.OK, c.post("/objects/${o.s("id")}/delete") {
                contentType(ContentType.Application.Json); setBody("""{"managerPin":"1234"}""")
            }.status)
        }
    }

    @Test
    fun `a custom object needs a name and a known icon and shape`() = testApplication {
        application { module(dbPath = tempDir() + "/pos.db", photosDir = tempDir()) }
        val c = loginClient()
        assertEquals(HttpStatusCode.BadRequest,
            c.add("""{"type":"CUSTOM","x":100,"y":100,"icon":"music","managerPin":"1234"}""").status)
        assertEquals(HttpStatusCode.BadRequest,
            c.add("""{"type":"CUSTOM","x":100,"y":100,"labelEn":"Jukebox","icon":"dragon","managerPin":"1234"}""").status)
        assertEquals(HttpStatusCode.BadRequest,
            c.add("""{"type":"CUSTOM","x":100,"y":100,"labelEn":"Jukebox","shape":"STAR","managerPin":"1234"}""").status)

        val res = c.add("""{"type":"CUSTOM","x":100,"y":100,"width":60,"height":60,"labelEn":"Jukebox",
            "labelFr":"Juke-box","icon":"music","shape":"ROUND","managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
        val zones = Json.parseToJsonElement(c.get("/zones").bodyAsText()).jsonArray
        val o = zones.first { it.jsonObject.s("id") == "outside" }.jsonObject["objects"]!!.jsonArray
            .map { it.jsonObject }.single { it.s("type") == "CUSTOM" }
        assertEquals("Juke-box", o.s("labelFr"))
        assertEquals("music", o.s("icon"))
        assertEquals("ROUND", o.s("shape"))
        assertEquals(60, o["width"]!!.jsonPrimitive.int)
    }

    @Test
    fun `add from photo suggests a custom object and creates nothing`() = testApplication {
        val fake = FakeMenuProvider("""```json
            {"nameEn":"Jukebox","nameFr":"Juke-box","icon":"music","shape":"rect","width":70,"height":9000}
        ```""")
        application { module(dbPath = tempDir() + "/pos.db", photosDir = tempDir(),
            menuAiConfig = on(), menuAiProvider = fake, imageReachable = { true }) }
        val c = loginClient()
        val before = c.get("/zones").bodyAsText()

        assertEquals(HttpStatusCode.Forbidden, c.suggest(pin = null).status)
        val res = c.suggest()
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val s = obj(res.bodyAsText())
        assertEquals("Jukebox", s.s("labelEn"))
        assertEquals("Juke-box", s.s("labelFr"))
        assertEquals("music", s.s("icon"))
        assertEquals("RECT", s.s("shape"))
        assertEquals(70, s["width"]!!.jsonPrimitive.int)
        assertEquals(400, s["height"]!!.jsonPrimitive.int) // clamped
        assertFalse(res.bodyAsText().contains(key))
        assertEquals(1, fake.images.size)
        assertTrue(fake.prompts.single().contains("music, speaker"))
        assertEquals(before, c.get("/zones").bodyAsText()) // a suggestion only

        // an icon off the list falls back to "star"
        fake.reply = """{"nameEn":"Dragon statue","nameFr":"","icon":"dragon","shape":"ROUND"}"""
        val s2 = obj(c.suggest().bodyAsText())
        assertEquals("star", s2.s("icon"))
        assertEquals("ROUND", s2.s("shape"))
        assertEquals(100, s2["width"]!!.jsonPrimitive.int)

        fake.reply = "no idea"
        assertEquals(HttpStatusCode.BadGateway, c.suggest().status)
    }

    @Test
    fun `add from photo is refused when AI is off`() = testApplication {
        application { module(dbPath = tempDir() + "/pos.db", photosDir = tempDir(), menuAiConfig = MenuAiConfig.OFF) }
        val res = loginClient().suggest()
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertEquals("menu_ai_disabled", obj(res.bodyAsText()).s("code"))
    }
}
