package dev.dwhipstock.pos

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.dwhipstock.pos.aiphotos.GeneratedImage
import dev.dwhipstock.pos.aiphotos.ImageGenException
import dev.dwhipstock.pos.aiphotos.ImageProvider
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.sdk.FilesystemPhotoStore
import dev.dwhipstock.pos.sdk.ImageGenConfig
import dev.dwhipstock.pos.sync.CloudSync
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A provider that returns canned pictures and remembers the prompts it saw. */
class FakeImageProvider(
    override val id: String = "fake",
    private val fail: ImageGenException? = null,
) : ImageProvider {
    override val model = "fake-1"
    override val host = "images.example.test"
    val prompts = mutableListOf<String>()
    val enhanced = mutableListOf<ByteArray>()
    override fun generate(prompt: String, count: Int): List<GeneratedImage> {
        prompts += prompt
        fail?.let { throw it }
        return (0 until count).map { GeneratedImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), it.toByte(), 0xFF.toByte(), 0xD9.toByte()), "image/jpeg") }
    }
    override fun enhance(photo: ByteArray, contentType: String, prompt: String, count: Int): List<GeneratedImage> {
        prompts += prompt
        enhanced += photo
        fail?.let { throw it }
        return (0 until count).map { GeneratedImage(photo + byteArrayOf(it.toByte()), contentType) }
    }
    override fun costPerImageUsd(edit: Boolean) = 0.01
}

class AiPhotoRoutesTest {
    private val key = "sk-live-SECRET-never-leaves-0123456789"
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")

    private fun tempDir(prefix: String) = Files.createTempDirectory(prefix).toString()

    private fun on(provider: String = "openai") = ImageGenConfig.fromProperties(Properties().apply {
        setProperty("image.generation", "on")
        setProperty("image.provider", provider)
        setProperty("image.openai.apiKey", key)
    })

    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    private fun outboxPayloads(): List<String> = transaction {
        SyncOutbox.selectAll().map { it[SyncOutbox.eventType] + " " + it[SyncOutbox.payload] }
    }

    @Test
    fun offByDefaultHidesTheFeatureAndRefusesCalls() = testApplication {
        application { module(dbPath = tempDir("pos-ai") + "/pos.db", photosDir = tempDir("photos"),
            imageGenConfig = ImageGenConfig.OFF) }
        val manager = loginClient()
        val status = obj(manager.get("/ai-photos/status").bodyAsText())
        assertEquals("false", status.s("configured"))
        assertEquals("false", status.s("available"))
        assertEquals("image_generation_off", status.s("reason"))
        val res = manager.post("/items/poutine/ai-photo/generate") {
            contentType(ContentType.Application.Json); setBody("""{"managerPin":"1234"}""")
        }
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertEquals("image_disabled", obj(res.bodyAsText()).s("code"))
    }

    @Test
    fun offlineDisablesButNothingElseIsAffected() = testApplication {
        val fake = FakeImageProvider()
        application { module(dbPath = tempDir("pos-ai") + "/pos.db", photosDir = tempDir("photos"),
            imageGenConfig = on(), imageProvider = fake, imageReachable = { false }) }
        val manager = loginClient()
        val status = obj(manager.get("/ai-photos/status").bodyAsText())
        assertEquals("true", status.s("configured"))
        assertEquals("false", status.s("available"))
        assertEquals("false", status.s("online"))
        assertEquals("image_offline", status.s("reason"))
        // the rest of the store works as ever: menu, a check, a sale line
        assertEquals(HttpStatusCode.OK, manager.get("/items").status)
        assertEquals(HttpStatusCode.OK, manager.get("/zones").status)
    }

    @Test
    fun providerUnreachableMidCallIsA503AndMarksOffline() = testApplication {
        val fake = FakeImageProvider(fail = ImageGenException(503, ImageGenException.UNAVAILABLE, "fake unreachable"))
        application { module(dbPath = tempDir("pos-ai") + "/pos.db", photosDir = tempDir("photos"),
            imageGenConfig = on(), imageProvider = fake, imageReachable = { true }) }
        val manager = loginClient()
        assertEquals("true", obj(manager.get("/ai-photos/status").bodyAsText()).s("available"))
        val res = manager.post("/items/poutine/ai-photo/generate") {
            contentType(ContentType.Application.Json); setBody("""{"managerPin":"1234"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
        assertEquals("image_unavailable", obj(res.bodyAsText()).s("code"))
        assertEquals("image_offline", obj(manager.get("/ai-photos/status").bodyAsText()).s("reason"))
    }

    @Test
    fun generateChooseStoresProvenanceAndSyncsIt() = testApplication {
        val fake = FakeImageProvider()
        val photosDir = tempDir("photos")
        application { module(dbPath = tempDir("pos-ai") + "/pos.db", photosDir = photosDir,
            imageGenConfig = on(), imageProvider = fake, imageReachable = { true }) }
        val manager = loginClient()

        // manager PIN required (it spends money)
        assertEquals(HttpStatusCode.Forbidden, manager.post("/items/poutine/ai-photo/generate") {
            contentType(ContentType.Application.Json); setBody("""{"count":3}""")
        }.status)
        assertEquals(HttpStatusCode.NotFound, manager.post("/items/nope/ai-photo/generate") {
            contentType(ContentType.Application.Json); setBody("""{"managerPin":"1234"}""")
        }.status)

        val gen = manager.post("/items/poutine/ai-photo/generate") {
            contentType(ContentType.Application.Json); setBody("""{"managerPin":"1234","count":3}""")
        }
        assertEquals(HttpStatusCode.OK, gen.status)
        val body = obj(gen.bodyAsText())
        assertEquals("ai_generated", body.s("source"))
        val candidates = body["candidates"]!!.jsonArray
        assertEquals(3, candidates.size)
        // the pub's house style reached the prompt, with the item's own text
        val prompt = fake.prompts.single()
        assertTrue(prompt.contains("Classic Poutine") && prompt.contains("cheese curds") && prompt.contains("wooden table"), prompt)

        // counts are clamped to 2..4
        val many = manager.post("/items/poutine/ai-photo/generate") {
            contentType(ContentType.Application.Json); setBody("""{"managerPin":"1234","count":9}""")
        }
        assertEquals(4, obj(many.bodyAsText())["candidates"]!!.jsonArray.size)

        val pick = candidates[1].jsonObject
        val chosen = manager.post("/items/poutine/ai-photo/choose") {
            contentType(ContentType.Application.Json)
            setBody("""{"managerPin":"1234","candidateId":"${pick.s("id")}"}""")
        }
        assertEquals(HttpStatusCode.Created, chosen.status)
        assertEquals("ai_generated", obj(chosen.bodyAsText()).s("photoSource"))
        // served like any photo, the chosen bytes
        val served = client.get("/photos/poutine").readRawBytes()
        assertTrue(served.contentEquals(Base64.getDecoder().decode(pick.s("dataBase64"))))
        // a candidate is single-use
        assertEquals(HttpStatusCode.NotFound, manager.post("/items/poutine/ai-photo/choose") {
            contentType(ContentType.Application.Json)
            setBody("""{"managerPin":"1234","candidateId":"${pick.s("id")}"}""")
        }.status)

        // stored and in the menu API
        val stored = transaction {
            dev.dwhipstock.pos.base.Items.selectAll().where { dev.dwhipstock.pos.base.Items.id eq "poutine" }
                .first()[dev.dwhipstock.pos.base.Items.photoSource]
        }
        assertEquals("ai_generated", stored)
        val items = Json.parseToJsonElement(manager.get("/items").bodyAsText()).jsonArray
        assertEquals("ai_generated", items.first { it.jsonObject.s("id") == "poutine" }.jsonObject.s("photoSource"))

        // outbox → cloud: the photo event's snapshot carries the provenance
        val event = outboxPayloads().last { it.startsWith("item.photo_uploaded") }
        assertTrue(event.contains("\"source\":\"ai_generated\"") && event.contains("\"photoSource\":\"ai_generated\""), event)
        val transport = FakeTransport()
        CloudSync(transport, FilesystemPhotoStore(File(photosDir))).drainOnce()
        val pushed = transport.batches.flatten().last { it.eventType == "item.photo_uploaded" }
        assertEquals("ai_generated", pushed.payload["item"]!!.jsonObject.s("photoSource"))
        assertTrue("poutine" in transport.photoUploads)

        // a manager's own upload afterwards is recorded as original
        assertEquals(HttpStatusCode.Created, manager.post("/items/poutine/photo") {
            setBody(MultiPartFormDataContent(formData {
                append("managerPin", "1234")
                append("photo", png, Headers.build {
                    append(HttpHeaders.ContentType, "image/png")
                    append(HttpHeaders.ContentDisposition, "filename=\"p.png\"")
                })
            }))
        }.status)
        val after = Json.parseToJsonElement(manager.get("/items").bodyAsText()).jsonArray
        assertEquals("original", after.first { it.jsonObject.s("id") == "poutine" }.jsonObject.s("photoSource"))
    }

    @Test
    fun snapAndEnhanceSendsTheRealPhotoAndRecordsEnhanced() = testApplication {
        val fake = FakeImageProvider()
        application { module(dbPath = tempDir("pos-ai") + "/pos.db", photosDir = tempDir("photos"),
            imageGenConfig = on(), imageProvider = fake, imageReachable = { true }) }
        val manager = loginClient()
        val res = manager.post("/items/lantern-burger/ai-photo/enhance") {
            setBody(MultiPartFormDataContent(formData {
                append("managerPin", "1234")
                append("count", "2")
                append("photo", png, Headers.build {
                    append(HttpHeaders.ContentType, "image/png")
                    append(HttpHeaders.ContentDisposition, "filename=\"dish.png\"")
                })
            }))
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val body = obj(res.bodyAsText())
        assertEquals("ai_enhanced", body.s("source"))
        assertTrue(fake.enhanced.single().contentEquals(png), "the manager's photo is what the provider edits")
        assertTrue(fake.prompts.single().contains("do not add, remove"))
        val id = body["candidates"]!!.jsonArray[0].jsonObject.s("id")
        // a candidate can't be saved onto a different item
        assertEquals(HttpStatusCode.NotFound, manager.post("/items/poutine/ai-photo/choose") {
            contentType(ContentType.Application.Json); setBody("""{"managerPin":"1234","candidateId":"$id"}""")
        }.status)
        val chosen = manager.post("/items/lantern-burger/ai-photo/choose") {
            contentType(ContentType.Application.Json); setBody("""{"managerPin":"1234","candidateId":"$id"}""")
        }
        assertEquals(HttpStatusCode.Created, chosen.status)
        assertEquals("ai_enhanced", obj(chosen.bodyAsText()).s("photoSource"))
    }

    @Test
    fun theKeyNeverLeaksToLogsSyncPayloadsOrResponses() = testApplication {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        root.addAppender(appender)
        try {
            // the provider fails with an error that echoes the key back
            val leaky = FakeImageProvider(fail = ImageGenException(502, ImageGenException.ERROR, "bad key $key"))
            val good = FakeImageProvider()
            var current: FakeImageProvider = leaky
            val switching = object : ImageProvider by good {
                override fun generate(prompt: String, count: Int) = current.generate(prompt, count)
            }
            application { module(dbPath = tempDir("pos-ai") + "/pos.db", photosDir = tempDir("photos"),
                imageGenConfig = on(), imageProvider = switching, imageReachable = { true }) }
            val manager = loginClient()
            val bodies = mutableListOf<String>()
            bodies += manager.get("/ai-photos/status").bodyAsText()
            bodies += manager.post("/items/poutine/ai-photo/generate") {
                contentType(ContentType.Application.Json); setBody("""{"managerPin":"1234"}""")
            }.bodyAsText()
            current = good
            val ok = manager.post("/items/poutine/ai-photo/generate") {
                contentType(ContentType.Application.Json); setBody("""{"managerPin":"1234"}""")
            }.bodyAsText().also { bodies += it }
            val id = obj(ok)["candidates"]!!.jsonArray[0].jsonObject.s("id")
            bodies += manager.post("/items/poutine/ai-photo/choose") {
                contentType(ContentType.Application.Json); setBody("""{"managerPin":"1234","candidateId":"$id"}""")
            }.bodyAsText()
            bodies += manager.get("/items").bodyAsText()
            bodies += client.get("/health").bodyAsText()

            bodies.forEach { assertFalse(it.contains(key), "key in a response: $it") }
            assertTrue(bodies[1].contains("image_error"))
            outboxPayloads().forEach { assertFalse(it.contains(key), "key in the outbox: $it") }
            val logged = appender.list.joinToString("\n") { it.formattedMessage + (it.throwableProxy?.message ?: "") }
            assertTrue(logged.contains("AI photos: on (openai"), "the startup line is logged")
            assertFalse(logged.contains(key), "key in the logs")
        } finally {
            root.detachAppender(appender)
        }
    }

    @Test
    fun sagePoppyGetsTheStudioStyle() = testApplication {
        val fake = FakeImageProvider()
        application { module(dbPath = tempDir("pos-ai") + "/pos.db", photosDir = tempDir("photos"),
            venueId = "sage-poppy", imageGenConfig = on(), imageProvider = fake, imageReachable = { true }) }
        val manager = loginClient()
        val itemId = Json.parseToJsonElement(manager.get("/items?limit=1").bodyAsText()).jsonArray[0].jsonObject.s("id")
        val res = manager.post("/items/$itemId/ai-photo/generate") {
            contentType(ContentType.Application.Json); setBody("""{"managerPin":"1234"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertTrue(fake.prompts.single().contains("sage-green"), fake.prompts.single())
        assertNotNull(obj(res.bodyAsText())["candidates"])
    }
}
