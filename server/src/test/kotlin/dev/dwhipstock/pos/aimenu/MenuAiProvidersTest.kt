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

    @Test
    fun geminiSendsJsonModeAndReadsTheText() {
        val http = Recorder(200, """{"candidates":[{"content":{"parts":[{"text":"{\"ops\":[]}"}]}}]}""")
        val out = GeminiMenuProvider(key, http).complete("sys", "user", photo)
        assertEquals("""{"ops":[]}""", out)
        val req = http.requests.single()
        assertTrue(req.url.endsWith("/v1beta/models/${GeminiMenuProvider.DEFAULT_MODEL}:generateContent"))
        assertEquals(key, req.headers["x-goog-api-key"])
        assertTrue(req.bodyText.contains("\"responseMimeType\":\"application/json\""))
        assertTrue(req.bodyText.contains("inline_data"))
        assertFalse(req.toString().contains(key))
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
    fun abbreviations() {
        assertEquals("CS", abbrev("Caesar Salad"))
        assertEquals("PO", abbrev("Poutine"))
        assertEquals("NEW", abbrev("!!"))
    }
}
