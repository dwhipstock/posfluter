package dev.dwhipstock.pos.aimenu

import dev.dwhipstock.pos.aiphotos.ImageGenException
import dev.dwhipstock.pos.aiphotos.ImageHttp
import dev.dwhipstock.pos.aiphotos.ImageHttpRequest
import dev.dwhipstock.pos.aiphotos.ImageHttpResponse
import dev.dwhipstock.pos.sdk.MenuAiConfig
import java.io.IOException
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MenuAiProvidersTest {
    private val key = "sk-test-KEY-0123456789abcdef"
    private val photo = listOf(MenuImage(byteArrayOf(1, 2, 3), "image/jpeg"))

    private class Recorder(val status: Int, val body: String) : ImageHttp {
        val requests = mutableListOf<ImageHttpRequest>()
        override fun send(request: ImageHttpRequest): ImageHttpResponse {
            requests += request
            return ImageHttpResponse(status, body.toByteArray())
        }
    }

    private val geminiOk = """{"status":"completed","steps":[{"type":"thought","signature":"abc"},""" +
        """{"type":"model_output","content":[{"type":"text","text":"{\"ops\":[]}"}]}],"usage":{"total_tokens":9}}"""

    @Test
    fun geminiSendsAnInteractionAndReadsTheText() {
        val http = Recorder(200, geminiOk)
        val out = GeminiMenuProvider(key, http).complete("sys", "user", photo)
        assertEquals("""{"ops":[]}""", out)
        val req = http.requests.single()
        assertTrue(req.url.endsWith("/v1beta/interactions"))
        assertEquals(key, req.headers["x-goog-api-key"])
        assertTrue(req.bodyText.contains("\"model\":\"${GeminiMenuProvider.DEFAULT_MODEL}\""))
        assertTrue(req.bodyText.contains("\"system_instruction\":\"sys\""))
        assertTrue(req.bodyText.contains("\"mime_type\":\"application/json\""))
        assertTrue(req.bodyText.contains("\"type\":\"image\",\"mime_type\":\"image/jpeg\""))
        assertFalse(req.toString().contains(key))
    }

    @Test
    fun geminiSendsVoiceAsAnAudioPartAndTheOthersSayNo() {
        val http = Recorder(200, geminiOk)
        GeminiMenuProvider(key, http).complete("sys", "user", listOf(MenuImage(ByteArray(2000), "audio/wav")))
        assertTrue(http.requests.single().bodyText.contains("\"type\":\"audio\",\"mime_type\":\"audio/wav\""))
        val voice = listOf(MenuImage(ByteArray(2000), "audio/wav"))
        val other = Recorder(200, "{}")
        assertEquals("menu_ai_voice_unsupported",
            assertFailsWith<ImageGenException> { OpenAiMenuProvider(key, other).complete("s", "u", voice) }.code)
        assertEquals("menu_ai_voice_unsupported",
            assertFailsWith<ImageGenException> { AnthropicMenuProvider(key, other).complete("s", "u", voice) }.code)
        assertTrue(other.requests.isEmpty())
    }

    /** Scripted replies, one per request. */
    private class Script(vararg val replies: Pair<Int, String>) : ImageHttp {
        val requests = mutableListOf<ImageHttpRequest>()
        override fun send(request: ImageHttpRequest): ImageHttpResponse {
            val (status, body) = replies[requests.size]
            requests += request
            return ImageHttpResponse(status, body.toByteArray())
        }
    }

    @Test
    fun geminiRetriesHighDemandThenFallsBackOnce() {
        val busy = 503 to """{"error":{"message":"This model is currently experiencing high demand."}}"""
        val pauses = mutableListOf<Long>()
        val http = Script(busy, busy, 200 to geminiOk)
        assertEquals("""{"ops":[]}""", GeminiMenuProvider(key, http, pause = { pauses += it }).complete("s", "u", emptyList()))
        assertEquals(listOf(GeminiMenuProvider.DEFAULT_MODEL, GeminiMenuProvider.DEFAULT_MODEL, GeminiMenuProvider.FALLBACK_MODEL),
            http.requests.map { Regex("\"model\":\"([^\"]+)\"").find(it.bodyText)!!.groupValues[1] })
        assertEquals(1, pauses.size)
        val allBusy = Script(busy, busy, busy)
        assertEquals(ImageGenException.UNAVAILABLE, assertFailsWith<ImageGenException> {
            GeminiMenuProvider(key, allBusy, pause = {}).complete("s", "u", emptyList())
        }.code)
        assertEquals(3, allBusy.requests.size)
    }

    @Test
    fun geminiQuotaUsedUpFallsBackWithoutPausing() {
        val quota = 429 to """{"error":{"message":"Quota exceeded for metric generate_content_free_tier_requests"}}"""
        val pauses = mutableListOf<Long>()
        val http = Script(quota, 200 to geminiOk)
        assertEquals("""{"ops":[]}""", GeminiMenuProvider(key, http, pause = { pauses += it }).complete("s", "u", emptyList()))
        assertEquals(listOf(GeminiMenuProvider.DEFAULT_MODEL, GeminiMenuProvider.FALLBACK_MODEL),
            http.requests.map { Regex("\"model\":\"([^\"]+)\"").find(it.bodyText)!!.groupValues[1] })
        assertEquals(0, pauses.size)
    }

    @Test
    fun openAiSendsJsonObjectModeAndReadsTheMessage() {
        val http = Recorder(200, """{"choices":[{"message":{"content":"{\"ops\":[]}"}}]}""")
        assertEquals("""{"ops":[]}""", OpenAiMenuProvider(key, http).complete("sys", "user", photo))
        val req = http.requests.single()
        assertTrue(req.url.endsWith("/v1/chat/completions"))
        assertTrue(req.bodyText.contains("\"json_object\""))
        assertTrue(req.bodyText.contains("data:image/jpeg;base64,"))
    }

    @Test
    fun anthropicSendsMessagesAndHandlesRefusal() {
        val http = Recorder(200, """{"content":[{"type":"text","text":"{\"ops\":[]}"}],"stop_reason":"end_turn"}""")
        assertEquals("""{"ops":[]}""", AnthropicMenuProvider(key, http).complete("sys", "user", photo))
        val req = http.requests.single()
        assertTrue(req.url.endsWith("/v1/messages"))
        assertEquals("2023-06-01", req.headers["anthropic-version"])
        assertTrue(req.bodyText.contains("\"media_type\":\"image/jpeg\""))
        val refused = Recorder(200, """{"content":[],"stop_reason":"refusal"}""")
        assertEquals(ImageGenException.REFUSED,
            assertFailsWith<ImageGenException> { AnthropicMenuProvider(key, refused).complete("s", "u", emptyList()) }.code)
    }

    @Test
    fun httpErrorsMapToTheSharedCodes() {
        assertEquals(ImageGenException.AUTH, assertFailsWith<ImageGenException> {
            OpenAiMenuProvider(key, Recorder(401, """{"error":{"message":"bad key $key"}}""")).complete("s", "u", emptyList())
        }.code)
        assertEquals(ImageGenException.RATE_LIMITED, assertFailsWith<ImageGenException> {
            GeminiMenuProvider(key, Recorder(429, "{}")).complete("s", "u", emptyList())
        }.code)
        val offline = ImageHttp { throw IOException("no route") }
        assertEquals(ImageGenException.UNAVAILABLE, assertFailsWith<ImageGenException> {
            AnthropicMenuProvider(key, offline).complete("s", "u", emptyList())
        }.code)
    }

    @Test
    fun configIsOffByDefaultAndNeverPrintsTheKey() {
        assertEquals(MenuAiConfig.Disabled.MENU_AI_OFF, MenuAiConfig.fromProperties(Properties()).disabled)
        val on = MenuAiConfig.fromProperties(Properties().apply {
            setProperty("menu.ai", "on"); setProperty("menu.ai.provider", "gemini")
            setProperty("menu.ai.gemini.apiKey", key)
        })
        assertTrue(on.enabled)
        assertFalse(on.describe().contains(key))
        val noKey = MenuAiConfig.fromProperties(Properties().apply {
            setProperty("menu.ai", "on"); setProperty("menu.ai.provider", "openai")
        })
        assertEquals(MenuAiConfig.Disabled.KEY_MISSING, noKey.disabled)
        assertNull(MenuAiProviders.from(noKey))
        // env wins; only the selected provider's key is read
        val env = MenuAiConfig.resolve({ null }, mapOf("POS_MENU_AI" to "on", "POS_MENU_AI_PROVIDER" to "anthropic",
            "ANTHROPIC_API_KEY" to key)::get, source = "env")
        assertEquals(MenuAiConfig.Provider.ANTHROPIC, env.provider)
        assertTrue(env.enabled)
    }

    @Test
    fun roomLayoutsUseTheLayoutModelWithMoreThinking() {
        fun cfg(vararg kv: Pair<String, String>) = MenuAiConfig.fromProperties(Properties().apply {
            setProperty("menu.ai", "on"); setProperty("menu.ai.provider", "gemini"); setProperty("menu.ai.gemini.apiKey", key)
            kv.forEach { (k, v) -> setProperty(k, v) }
        })
        val http = Recorder(200, geminiOk)
        MenuAiProviders.layout(cfg(), http)!!.complete("sys", "user", photo)
        MenuAiProviders.from(cfg(), http)!!.complete("sys", "user", photo)
        val (layout, chat) = http.requests.map { it.bodyText }
        assertTrue(layout.contains("\"model\":\"${GeminiMenuProvider.LAYOUT_MODEL}\"") && layout.contains("\"thinking_level\":\"medium\""))
        assertTrue(chat.contains("\"model\":\"${GeminiMenuProvider.DEFAULT_MODEL}\"") && chat.contains("\"thinking_level\":\"low\""))
        assertEquals("gemini-x", MenuAiProviders.layout(cfg("menu.ai.layoutModel" to "gemini-x"))!!.model)
    }

    @Test
    fun abbreviations() {
        assertEquals("CS", abbrev("Caesar Salad"))
        assertEquals("PO", abbrev("Poutine"))
        assertEquals("NEW", abbrev("!!"))
    }
}
