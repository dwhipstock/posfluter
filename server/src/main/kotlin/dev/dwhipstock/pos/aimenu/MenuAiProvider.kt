package dev.dwhipstock.pos.aimenu

import dev.dwhipstock.pos.aiphotos.ImageGenException
import dev.dwhipstock.pos.aiphotos.ImageHttp
import dev.dwhipstock.pos.aiphotos.ImageHttpRequest
import dev.dwhipstock.pos.aiphotos.ImageHttpResponse
import dev.dwhipstock.pos.aiphotos.Scrub
import dev.dwhipstock.pos.aiphotos.UrlImageHttp
import dev.dwhipstock.pos.aiphotos.commonHttpError
import dev.dwhipstock.pos.aiphotos.parseJsonObject
import dev.dwhipstock.pos.aiphotos.str
import dev.dwhipstock.pos.sdk.MenuAiConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.URI
import java.util.Base64

/** A photo of a paper menu, already downscaled. */
class MenuImage(val bytes: ByteArray, val contentType: String)

/**
 * A text model behind the `menu.ai.provider` switch. [complete] blocks and
 * returns the model's raw reply text (expected to be the JSON change set; the
 * service validates it), or throws [ImageGenException] with the shared codes
 * (unavailable / timeout / rate limited / quota / refused / auth / error).
 * The transport is the AI photos' [ImageHttp] seam, so tests use a fake.
 */
interface MenuAiProvider {
    val id: String
    val model: String
    /** Host checked by the "are we online?" probe. */
    val host: String
    fun complete(system: String, user: String, images: List<MenuImage>): String
}

private fun send(provider: String, http: ImageHttp, req: ImageHttpRequest): ImageHttpResponse = try {
    http.send(req)
} catch (e: IOException) {
    throw ImageGenException.unreachable(provider, e)
}

private fun failure(provider: String, res: ImageHttpResponse, json: JsonObject?): ImageGenException {
    val err = json?.get("error")
    val message = ((err as? JsonObject)?.get("message").str() ?: err.str())?.take(300) ?: "HTTP ${res.status}"
    // OpenAI answers an empty balance with a 429: that is billing, not "slow down"
    if (res.status == 429 && Regex("(?i)insufficient_quota|no credits|credit balance").containsMatchIn(res.text))
        return ImageGenException(402, ImageGenException.QUOTA, "$provider: out of credits ($message)")
    return commonHttpError(provider, res, message)
        ?: ImageGenException(502, ImageGenException.ERROR, "$provider error ${res.status}: $message")
}

private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

/**
 * Gemini Interactions API (`POST /v1beta/interactions`): new keys get a 404 on
 * the old `generateContent` endpoint. JSON output via `response_format`.
 * A 503 ("high demand") is retried once after a short pause, then tried once on
 * [FALLBACK_MODEL] before the usual error is thrown.
 */
class GeminiMenuProvider(
    private val apiKey: String,
    private val http: ImageHttp,
    override val model: String = DEFAULT_MODEL,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) : MenuAiProvider {
    override val id = "gemini"
    override val host: String get() = URI(baseUrl).host

    companion object {
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        const val FALLBACK_MODEL = "gemini-3.5-flash"
        const val RETRY_PAUSE_MS = 1_500L
    }

    override fun complete(system: String, user: String, images: List<MenuImage>): String {
        val body = { m: String ->
            buildJsonObject {
                put("model", m)
                put("system_instruction", system)
                putJsonArray("input") {
                    images.forEach { img ->
                        addJsonObject { put("type", "image"); put("mime_type", img.contentType); put("data", b64(img.bytes)) }
                    }
                    addJsonObject { put("type", "text"); put("text", user) }
                }
                putJsonObject("response_format") {
                    put("type", "text")
                    put("mime_type", "application/json")
                    putJsonObject("schema") { put("type", "object") }
                }
                putJsonObject("generation_config") { put("temperature", 0) }
            }.toString().toByteArray()
        }
        val post = { m: String ->
            send("Gemini", http, ImageHttpRequest("POST", "$baseUrl/v1beta/interactions",
                mapOf("x-goog-api-key" to apiKey), body(m), "application/json"))
        }
        var res = post(model)
        if (res.status == 503) { pause(RETRY_PAUSE_MS); res = post(model) }
        if (res.status == 503 && model != FALLBACK_MODEL) res = post(FALLBACK_MODEL)
        val json = parseJsonObject(res.text)
        if (res.status !in 200..299) throw failure("Gemini", res, json)
        val status = json?.get("status").str()
        val text = (json?.get("steps") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.filter { it["type"].str() == "model_output" }
            ?.flatMap { (it["content"] as? JsonArray).orEmpty() }
            ?.mapNotNull { c -> (c as? JsonObject)?.takeIf { it["type"].str() == "text" }?.get("text").str() }
            ?.joinToString("")
        if (text.isNullOrBlank()) throw ImageGenException.refused("Gemini", status?.takeIf { it != "completed" })
        return text
    }

    override fun toString() = "GeminiMenuProvider($model)"
}

/** OpenAI Chat Completions with `response_format: json_object`. */
class OpenAiMenuProvider(
    private val apiKey: String,
    private val http: ImageHttp,
    override val model: String = DEFAULT_MODEL,
    private val baseUrl: String = "https://api.openai.com",
) : MenuAiProvider {
    override val id = "openai"
    override val host: String get() = URI(baseUrl).host

    companion object { const val DEFAULT_MODEL = "gpt-5.4-mini" }

    override fun complete(system: String, user: String, images: List<MenuImage>): String {
        val body = buildJsonObject {
            put("model", model)
            putJsonObject("response_format") { put("type", "json_object") }
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", system) }
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        images.forEach { img ->
                            addJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") { put("url", "data:${img.contentType};base64,${b64(img.bytes)}") }
                            }
                        }
                        addJsonObject { put("type", "text"); put("text", user) }
                    }
                }
            }
        }
        val res = send("OpenAI", http, ImageHttpRequest("POST", "$baseUrl/v1/chat/completions",
            mapOf("Authorization" to "Bearer $apiKey"), body.toString().toByteArray(), "application/json"))
        val json = parseJsonObject(res.text)
        if (res.status !in 200..299) throw failure("OpenAI", res, json)
        val message = ((json?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
        message?.get("refusal").str()?.let { throw ImageGenException.refused("OpenAI", it) }
        return message?.get("content").str()?.takeIf { it.isNotBlank() }
            ?: throw ImageGenException(502, ImageGenException.ERROR, "OpenAI: empty reply")
    }

    override fun toString() = "OpenAiMenuProvider($model)"
}

/** Anthropic Messages API (raw HTTP, like the other providers: nothing extra in the APK). */
class AnthropicMenuProvider(
    private val apiKey: String,
    private val http: ImageHttp,
    override val model: String = DEFAULT_MODEL,
    private val baseUrl: String = "https://api.anthropic.com",
) : MenuAiProvider {
    override val id = "anthropic"
    override val host: String get() = URI(baseUrl).host

    companion object { const val DEFAULT_MODEL = "claude-opus-5" }

    override fun complete(system: String, user: String, images: List<MenuImage>): String {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 16000)
            put("system", system)
            // a structured transcription: little reasoning needed
            putJsonObject("output_config") { put("effort", "low") }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        images.forEach { img ->
                            addJsonObject {
                                put("type", "image")
                                putJsonObject("source") {
                                    put("type", "base64"); put("media_type", img.contentType); put("data", b64(img.bytes))
                                }
                            }
                        }
                        addJsonObject { put("type", "text"); put("text", user) }
                    }
                }
            }
        }
        val res = send("Anthropic", http, ImageHttpRequest("POST", "$baseUrl/v1/messages",
            mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"),
            body.toString().toByteArray(), "application/json"))
        val json = parseJsonObject(res.text)
        if (res.status == 529) throw ImageGenException(503, ImageGenException.UNAVAILABLE, "Anthropic overloaded")
        if (res.status !in 200..299) throw failure("Anthropic", res, json)
        if (json?.get("stop_reason").str() == "refusal") throw ImageGenException.refused("Anthropic")
        val text = (json?.get("content") as? JsonArray)
            ?.mapNotNull { b -> (b as? JsonObject)?.takeIf { it["type"].str() == "text" }?.get("text").str() }
            ?.joinToString("")
        return text?.takeIf { it.isNotBlank() }
            ?: throw ImageGenException(502, ImageGenException.ERROR, "Anthropic: empty reply")
    }

    override fun toString() = "AnthropicMenuProvider($model)"
}

/** Builds the configured provider (null when AI menu is off). */
object MenuAiProviders {
    fun from(config: MenuAiConfig.Resolved, http: ImageHttp = UrlImageHttp(readTimeoutMs = 180_000)): MenuAiProvider? {
        val key = config.apiKey ?: return null
        Scrub.register(key)
        return when (config.provider) {
            MenuAiConfig.Provider.GEMINI -> GeminiMenuProvider(key, http, config.model ?: GeminiMenuProvider.DEFAULT_MODEL)
            MenuAiConfig.Provider.OPENAI -> OpenAiMenuProvider(key, http, config.model ?: OpenAiMenuProvider.DEFAULT_MODEL)
            MenuAiConfig.Provider.ANTHROPIC -> AnthropicMenuProvider(key, http, config.model ?: AnthropicMenuProvider.DEFAULT_MODEL)
            MenuAiConfig.Provider.OFF -> null
        }
    }
}
