package dev.dwhipstock.pos.aiphotos

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Scripted HTTP: answers from [handler], records every request. */
class RecordingHttp(private val handler: (ImageHttpRequest) -> ImageHttpResponse) : ImageHttp {
    val requests = java.util.Collections.synchronizedList(mutableListOf<ImageHttpRequest>())
    override fun send(request: ImageHttpRequest): ImageHttpResponse { requests += request; return handler(request) }
}

private fun json(status: Int, body: String, headers: Map<String, String> = emptyMap()) =
    ImageHttpResponse(status, body.toByteArray(), headers + ("Content-Type" to "application/json"))

private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
private val B64 = Base64.getEncoder().encodeToString(JPEG)

private fun bodyJson(r: ImageHttpRequest) = Json.parseToJsonElement(r.bodyText).jsonObject
private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

class FluxProviderTest {
    private val key = "bfl-test-key-000111222"
    private val clock = java.util.concurrent.atomic.AtomicLong()
    private val sleeps = java.util.Collections.synchronizedList(mutableListOf<Long>())
    private fun flux(http: ImageHttp, deadlineMs: Long = 120_000) = FluxProvider(key, http,
        sleep = { sleeps += it; clock.addAndGet(it) }, now = { clock.get() }, deadlineMs = deadlineMs, seeds = { 1000L + it })

    @Test
    fun generateSubmitsPollsUntilReadyAndDownloads() {
        val polls = java.util.concurrent.atomic.AtomicInteger()
        val http = RecordingHttp { r ->
            when {
                r.method == "POST" -> json(200, """{"id":"task-1","polling_url":"https://api.us.bfl.ai/v1/get_result?id=task-1"}""")
                r.url.contains("get_result") -> if (polls.incrementAndGet() < 3) json(200, """{"id":"task-1","status":"Pending"}""")
                    else json(200, """{"id":"task-1","status":"Ready","result":{"sample":"https://delivery-us1.bfl.ai/results/abc.jpeg?sig=x"}}""")
                r.url.startsWith("https://delivery-us1.bfl.ai/") -> ImageHttpResponse(200, JPEG, mapOf("Content-Type" to "image/jpeg"))
                else -> error("unexpected ${r.url}")
            }
        }
        val out = flux(http).generate("a burger", 1)
        assertEquals(1, out.size)
        assertTrue(out[0].bytes.contentEquals(JPEG))

        val submit = http.requests.first()
        assertEquals("https://api.bfl.ai/v1/flux-2-pro", submit.url)
        assertEquals(key, submit.headers["x-key"])
        assertEquals("application/json", submit.contentType)
        val body = bodyJson(submit)
        assertEquals("a burger", body.s("prompt"))
        assertEquals("1024", body.s("width"))
        assertEquals("1024", body.s("height"))
        assertEquals("jpeg", body.s("output_format"))
        assertEquals("1000", body.s("seed"))
        assertNull(body["input_image"])
        // polled with the key, 3 times; downloaded WITHOUT the key
        val pollReqs = http.requests.filter { it.url.contains("get_result") }
        assertEquals(3, pollReqs.size)
        assertTrue(pollReqs.all { it.headers["x-key"] == key && it.method == "GET" })
        val download = http.requests.last()
        assertTrue(download.headers.isEmpty(), "the signed delivery URL gets no key")
        assertTrue(sleeps.isNotEmpty())
    }

    @Test
    fun enhanceSendsTheBase64PhotoAndNoSize() {
        val http = RecordingHttp { r ->
            when {
                r.method == "POST" -> json(200, """{"id":"t","polling_url":"https://api.bfl.ai/v1/get_result?id=t"}""")
                r.url.contains("get_result") -> json(200, """{"status":"Ready","result":{"sample":"https://delivery-eu1.bfl.ai/x.jpg"}}""")
                else -> ImageHttpResponse(200, JPEG, mapOf("Content-Type" to "image/jpeg"))
            }
        }
        val out = flux(http).enhance(JPEG, "image/jpeg", "improve it", 2)
        assertEquals(2, out.size)
        val submits = http.requests.filter { it.method == "POST" }
        assertEquals(2, submits.size, "one task per candidate")
        submits.forEach {
            val b = bodyJson(it)
            assertEquals(B64, b.s("input_image"))
            assertNull(b["width"])
        }
        assertEquals(setOf("1000", "1001"), submits.map { bodyJson(it).s("seed") }.toSet())
    }

    @Test
    fun moderationIsARefusal() {
        val http = RecordingHttp { r ->
            if (r.method == "POST") json(200, """{"id":"t","polling_url":"https://api.bfl.ai/v1/get_result?id=t"}""")
            else json(200, """{"status":"Content Moderated"}""")
        }
        val e = assertFailsWith<ImageGenException> { flux(http).generate("x", 1) }
        assertEquals(ImageGenException.REFUSED, e.code)
        assertEquals(422, e.status)
    }

    @Test
    fun pollingPastTheDeadlineTimesOut() {
        val http = RecordingHttp { r ->
            if (r.method == "POST") json(200, """{"id":"t","polling_url":"https://api.bfl.ai/v1/get_result?id=t"}""")
            else json(200, """{"status":"Pending"}""")
        }
        val e = assertFailsWith<ImageGenException> { flux(http, deadlineMs = 5_000).generate("x", 1) }
        assertEquals(ImageGenException.TIMEOUT, e.code)
    }

    @Test
    fun rateLimitAndCreditsAndErrors() {
        val limited = RecordingHttp { json(429, """{"detail":"too many active tasks"}""", mapOf("Retry-After" to "7")) }
        val e = assertFailsWith<ImageGenException> { flux(limited).generate("x", 1) }
        assertEquals(ImageGenException.RATE_LIMITED, e.code)
        assertEquals(7L, e.retryAfterSeconds)
        val broke = RecordingHttp { json(402, """{"detail":"insufficient credits"}""") }
        assertEquals(ImageGenException.QUOTA, assertFailsWith<ImageGenException> { flux(broke).generate("x", 1) }.code)
        val failed = RecordingHttp { r ->
            if (r.method == "POST") json(200, """{"id":"t","polling_url":"https://api.bfl.ai/v1/get_result?id=t"}""")
            else json(200, """{"status":"Error"}""")
        }
        assertEquals(ImageGenException.ERROR, assertFailsWith<ImageGenException> { flux(failed).generate("x", 1) }.code)
    }

    @Test
    fun aPollingUrlOffBflHostsIsNeverSentTheKey() {
        val http = RecordingHttp { json(200, """{"id":"t","polling_url":"https://evil.example.com/steal"}""") }
        assertFailsWith<ImageGenException> { flux(http).generate("x", 1) }
        assertTrue(http.requests.none { it.url.contains("evil.example.com") })
    }

    @Test
    fun networkFailuresMapToUnavailableOrTimeout() {
        assertEquals(ImageGenException.UNAVAILABLE, assertFailsWith<ImageGenException> {
            flux(ImageHttp { throw IOException("no route") }).generate("x", 1)
        }.code)
        assertEquals(ImageGenException.TIMEOUT, assertFailsWith<ImageGenException> {
            flux(ImageHttp { throw SocketTimeoutException("read") }).generate("x", 1)
        }.code)
    }
}

class GeminiProviderTest {
    private val key = "AIzaSyTestKeyTestKeyTestKey000"

    private fun imageReply() = json(200, """{"id":"v1_x","object":"interaction","status":"completed",
        "steps":[{"type":"model_output","content":[{"type":"text","text":"Here you go"},
        {"type":"image","mime_type":"image/jpeg","data":"$B64"}]}]}""")

    @Test
    fun generateRequestShape() {
        val http = RecordingHttp { imageReply() }
        val out = GeminiProvider(key, http).generate("a poutine", 3)
        assertEquals(3, out.size, "one call per candidate")
        assertTrue(out.all { it.bytes.contentEquals(JPEG) })
        val r = http.requests.first()
        assertEquals("https://generativelanguage.googleapis.com/v1beta/interactions", r.url)
        assertEquals(key, r.headers["x-goog-api-key"])
        assertFalse(r.url.contains(key), "the key is a header, never in the URL")
        val b = bodyJson(r)
        assertEquals("gemini-3.1-flash-image", b.s("model"))
        val input = b["input"]!!.jsonArray
        assertEquals(1, input.size)
        assertEquals("text", input[0].jsonObject.s("type"))
        assertEquals("a poutine", input[0].jsonObject.s("text"))
        val fmt = b["response_format"]!!.jsonObject
        assertEquals("image", fmt.s("type"))
        assertEquals("image/jpeg", fmt.s("mime_type"))
        assertEquals("1:1", fmt.s("aspect_ratio"))
        assertEquals("1K", fmt.s("image_size"))
    }

    @Test
    fun enhanceSendsTheImageBlockAndKeepsFraming() {
        val http = RecordingHttp { imageReply() }
        GeminiProvider(key, http).enhance(JPEG, "image/jpeg", "improve", 2)
        val b = bodyJson(http.requests.first())
        val img = b["input"]!!.jsonArray[1].jsonObject
        assertEquals("image", img.s("type"))
        assertEquals("image/jpeg", img.s("mime_type"))
        assertEquals(B64, img.s("data"))
        assertNull(b["response_format"]!!.jsonObject["aspect_ratio"])
    }

    @Test
    fun acceptsTheGenerateContentShapeToo() {
        val http = RecordingHttp { json(200, """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"image/png","data":"$B64"}}]}}]}""") }
        val out = GeminiProvider(key, http).generate("x", 2)
        assertEquals("image/png", out[0].contentType)
    }

    @Test
    fun noImageIsARefusalAndErrorsMap() {
        val none = RecordingHttp { json(200, """{"status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"I can't help with that."}]}]}""") }
        assertEquals(ImageGenException.REFUSED, assertFailsWith<ImageGenException> { GeminiProvider(key, none).generate("x", 2) }.code)
        val blocked = RecordingHttp { json(400, """{"error":{"code":400,"message":"Request blocked by safety filters","status":"INVALID_ARGUMENT"}}""") }
        assertEquals(ImageGenException.REFUSED, assertFailsWith<ImageGenException> { GeminiProvider(key, blocked).generate("x", 2) }.code)
        val limited = RecordingHttp { json(429, """{"error":{"code":429,"message":"Resource has been exhausted","status":"RESOURCE_EXHAUSTED"}}""") }
        assertEquals(ImageGenException.RATE_LIMITED, assertFailsWith<ImageGenException> { GeminiProvider(key, limited).generate("x", 2) }.code)
        val badKey = RecordingHttp { json(403, """{"error":{"code":403,"message":"API key not valid: $key","status":"PERMISSION_DENIED"}}""") }
        val e = assertFailsWith<ImageGenException> { GeminiProvider(key, badKey).generate("x", 2) }
        assertEquals(ImageGenException.AUTH, e.code)
        assertFalse(e.message!!.contains(key))
    }
}

class OpenAiProviderTest {
    private val key = "sk-test-openai-000111222333"

    @Test
    fun generateRequestShape() {
        val http = RecordingHttp { json(200, """{"created":1,"data":[{"b64_json":"$B64"},{"b64_json":"$B64"},{"b64_json":"$B64"}]}""") }
        val out = OpenAiProvider(key, http).generate("fish and chips", 3)
        assertEquals(3, out.size)
        assertEquals(1, http.requests.size, "n candidates in one call")
        val r = http.requests.single()
        assertEquals("https://api.openai.com/v1/images/generations", r.url)
        assertEquals("Bearer $key", r.headers["Authorization"])
        val b = bodyJson(r)
        assertEquals("gpt-image-1.5", b.s("model"))
        assertEquals("fish and chips", b.s("prompt"))
        assertEquals("3", b.s("n"))
        assertEquals("1024x1024", b.s("size"))
        assertEquals("medium", b.s("quality"))
        assertEquals("jpeg", b.s("output_format"))
    }

    @Test
    fun enhanceIsAMultipartEditWithHighFidelity() {
        val http = RecordingHttp { json(200, """{"data":[{"b64_json":"$B64"},{"b64_json":"$B64"}]}""") }
        OpenAiProvider(key, http).enhance(JPEG, "image/jpeg", "improve the light", 2)
        val r = http.requests.single()
        assertEquals("https://api.openai.com/v1/images/edits", r.url)
        assertTrue(r.contentType!!.startsWith("multipart/form-data; boundary="))
        val text = String(r.body!!, Charsets.ISO_8859_1)
        assertTrue(text.contains("name=\"image\"; filename=\"dish.jpg\""))
        assertTrue(text.contains("Content-Type: image/jpeg"))
        assertTrue(text.contains("name=\"input_fidelity\"\r\n\r\nhigh"))
        assertTrue(text.contains("name=\"prompt\"\r\n\r\nimprove the light"))
        assertTrue(text.contains("name=\"n\"\r\n\r\n2"))
        assertTrue(text.contains("name=\"model\"\r\n\r\ngpt-image-1.5"))
        // a newer model skips input_fidelity
        val http2 = RecordingHttp { json(200, """{"data":[{"b64_json":"$B64"}]}""") }
        OpenAiProvider(key, http2, model = "gpt-image-2").enhance(JPEG, "image/jpeg", "p", 2)
        assertFalse(String(http2.requests.single().body!!, Charsets.ISO_8859_1).contains("input_fidelity"))
    }

    @Test
    fun errorsMap() {
        fun code(status: Int, body: String) = assertFailsWith<ImageGenException> {
            OpenAiProvider(key, RecordingHttp { json(status, body) }).generate("x", 2)
        }.code
        assertEquals(ImageGenException.REFUSED, code(400, """{"error":{"code":"moderation_blocked","message":"Your request was rejected by the safety system."}}"""))
        assertEquals(ImageGenException.QUOTA, code(429, """{"error":{"code":"insufficient_quota","message":"You exceeded your current quota"}}"""))
        assertEquals(ImageGenException.RATE_LIMITED, code(429, """{"error":{"code":"rate_limit_exceeded","message":"slow down"}}"""))
        assertEquals(ImageGenException.AUTH, code(401, """{"error":{"code":"invalid_api_key","message":"Incorrect API key provided: $key"}}"""))
        assertEquals(ImageGenException.UNAVAILABLE, code(503, """{"error":{"message":"overloaded"}}"""))
        assertEquals(ImageGenException.ERROR, code(200, """{"data":[]}"""))
    }

    @Test
    fun errorMessagesAreScrubbed() {
        Scrub.register(key)
        val e = assertFailsWith<ImageGenException> {
            OpenAiProvider(key, RecordingHttp { json(400, """{"error":{"code":"bad","message":"key $key is bad"}}""") }).generate("x", 2)
        }
        assertFalse(e.message!!.contains(key))
        assertFalse(OpenAiProvider(key, RecordingHttp { json(200, "{}") }).toString().contains(key))
        assertFalse(ImageHttpRequest("POST", "https://x", mapOf("Authorization" to "Bearer $key")).toString().contains(key))
    }
}

class ScrubTest {
    @Test
    fun keyShapesAreMasked() {
        assertEquals("sk-***", Scrub.clean("sk-proj-ABCDEFGHIJKLMN"))
        assertEquals("AIza***", Scrub.clean("AIzaSyAAAAAAAAAAAAAAAAAAAAAAAA"))
        assertEquals("x-key: ***", Scrub.clean("x-key: abcdefgh12345"))
    }
}
