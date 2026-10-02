package dev.dwhipstock.poscloud.menuai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.Base64

/** A short voice clip for the model (16 kHz mono WAV from the portal, or another type Gemini takes). */
class AiAudio(val bytes: ByteArray, val contentType: String)

/**
 * The model behind the portal's menu assistant. [complete] blocks and returns
 * the model's raw reply text (the JSON change set; the service validates it),
 * or throws [MenuAiException]. Tests pass a fake; nothing in CI goes online.
 */
interface MenuAiModel {
    val id: String
    val model: String
    fun complete(system: String, user: String, audio: AiAudio?): String
}

/**
 * A coded AI failure. The message is the cloud's own short English text —
 * never the provider's reply body — and is scrubbed of anything key-shaped.
 */
class MenuAiException(
    val status: Int, val code: String, message: String, val retryAfterSeconds: Long? = null, cause: Throwable? = null,
) : RuntimeException(Scrub.clean(message), cause)

/** The one HTTP call the provider makes (a seam for tests). */
fun interface AiHttp {
    /** POST [body] to [url] with [headers]; (status, body text). */
    fun post(url: String, headers: Map<String, String>, body: ByteArray, timeoutMs: Long): Pair<Int, String>
}

/** The JDK client (the cloud API is a plain JVM service). */
class JdkAiHttp : AiHttp {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    override fun post(url: String, headers: Map<String, String>, body: ByteArray, timeoutMs: Long): Pair<Int, String> {
        val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMillis(timeoutMs.coerceAtLeast(1)))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).header("Content-Type", "application/json")
        headers.forEach { (k, v) -> req.header(k, v) }
        val res = client.send(req.build(), HttpResponse.BodyHandlers.ofString())
        return res.statusCode() to res.body()
    }
}

/**
 * Gemini Interactions API (`POST /v1beta/interactions`), as the store's
 * GeminiMenuProvider (server/.../aimenu/MenuAiProvider.kt, PORTED): JSON
 * output, temperature 0, low thinking; a 503 is retried once after a short
 * pause, then a 503 / 429 is tried once on [FALLBACK_MODEL]. The key goes in
 * the `x-goog-api-key` header only, and no error carries the provider's text.
 */
class GeminiMenuModel(
    private val apiKey: String,
    override val model: String = DEFAULT_MODEL,
    private val http: AiHttp = JdkAiHttp(),
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    private val thinkingLevel: String = "low",
    private val budgetMs: Long = 60_000L,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    /** 0 for menu edits (the same request gives the same proposal); printed menus want fresh wording. */
    private val temperature: Double = 0.0,
    /** A cap on the reply's length (tokens); null = the provider's default. */
    private val maxOutputTokens: Int? = null,
) : MenuAiModel {
    override val id = "gemini"

    init { Scrub.register(apiKey) }

    companion object {
        /** The store's default too: 2–6 s a menu edit (docs/ai-menu.md). */
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        const val FALLBACK_MODEL = "gemini-3.5-flash"
        const val RETRY_PAUSE_MS = 1_500L
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    override fun complete(system: String, user: String, audio: AiAudio?): String {
        var thinking = thinkingLevel
        val body = { m: String ->
            buildJsonObject {
                put("model", m)
                put("system_instruction", system)
                putJsonArray("input") {
                    if (audio != null) addJsonObject {
                        put("type", "audio"); put("mime_type", audio.contentType)
                        put("data", Base64.getEncoder().encodeToString(audio.bytes))
                    }
                    addJsonObject { put("type", "text"); put("text", user) }
                }
                // JSON mode without a schema: an empty {"type":"object"} schema makes the model answer "{}"
                putJsonObject("response_format") { put("type", "text"); put("mime_type", "application/json") }
                putJsonObject("generation_config") { put("temperature", if (temperature == 0.0) 0 else temperature); put("thinking_level", thinking)
                    maxOutputTokens?.let { put("max_output_tokens", it) }
                }
            }.toString().toByteArray()
        }
        val deadline = System.currentTimeMillis() + budgetMs
        fun left() = deadline - System.currentTimeMillis()
        val post = { m: String ->
            if (left() <= 0) throw MenuAiException(504, "menu_ai_timeout", "the AI did not answer in time")
            try {
                http.post("$baseUrl/v1beta/interactions", mapOf("x-goog-api-key" to apiKey), body(m), left())
            } catch (e: HttpTimeoutException) {
                throw MenuAiException(504, "menu_ai_timeout", "the AI did not answer in time", cause = e)
            } catch (e: IOException) {
                throw MenuAiException(503, "menu_ai_unavailable", "the AI service is unreachable (${e.javaClass.simpleName})", cause = e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw MenuAiException(503, "menu_ai_unavailable", "interrupted", cause = e)
            }
        }
        var (status, text) = post(model)
        // a model without the asked-for thinking level (the printed menus' "minimal") says 400: once more with "low"
        if (status == 400 && thinking != "low") { thinking = "low"; post(model).let { status = it.first; text = it.second } }
        if (status == 503 && left() > RETRY_PAUSE_MS) { pause(RETRY_PAUSE_MS); post(model).let { status = it.first; text = it.second } }
        if ((status == 503 || status == 429) && left() > 0) {
            val fallback = if (model == FALLBACK_MODEL) DEFAULT_MODEL else FALLBACK_MODEL
            post(fallback).let { status = it.first; text = it.second }
        }
        when {
            status == 401 || status == 403 -> throw MenuAiException(502, "menu_ai_auth", "the AI service rejected the cloud's key")
            status == 429 -> throw MenuAiException(429, "menu_ai_quota", "the AI service's quota is used up; try later")
            status >= 500 -> throw MenuAiException(503, "menu_ai_unavailable", "the AI service is busy; try again")
            status !in 200..299 -> throw MenuAiException(502, "menu_ai_error", "the AI service answered $status")
        }
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        val out = (root?.get("steps") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "model_output" }
            ?.flatMap { (it["content"] as? JsonArray).orEmpty() }
            ?.mapNotNull { c ->
                (c as? JsonObject)?.takeIf { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
                    ?.let { (it["text"] as? JsonPrimitive)?.contentOrNull }
            }
            ?.joinToString("")
        if (out.isNullOrBlank()) throw MenuAiException(422, "menu_ai_refused", "the AI declined this request")
        return out
    }

    override fun toString() = "GeminiMenuModel($model)"
}

/**
 * Voice: the clip goes to the model with the request, one call transcribes
 * and interprets (PORTED from the store's AiVoice, server/.../aimenu/FloorEditAi.kt).
 */
object AiVoice {
    const val REQUEST = "(spoken: the attached audio clip — transcribe it word for word in the language spoken, never translated)"

    fun prompt(languages: String): String = """
        Voice: the manager's request is the attached audio clip, spoken in one of these languages: $languages.
        Add "transcript": "<the words exactly as spoken>" as the FIRST field of your JSON object, written before
        anything else. The transcript is VERBATIM, word
        for word, in the language actually spoken in the clip: German speech gives a German transcript, Spanish
        speech a Spanish one, Afrikaans speech an Afrikaans one. NEVER translate the transcript — not into
        French, not into English, not into any other language — and never paraphrase or "correct" it; the
        French and English name fields in the data are storage slots and say nothing about the spoken language.
        Understand the request in the language it was spoken in; it is the request and follows the same rules
        as a typed one. If the clip has no speech, reply with "transcript": "" and nothing to change.""".trimIndent()

    fun replyLanguage(codes: Collection<String>, fallback: String): String = """
        Language: add "language": "<${codes.joinToString("|")}>" to your JSON object — the language the manager's
        request is written or spoken in. Write "summary" in that SAME language (a German request gets a German
        summary, an English one an English summary), whatever language the data is in. Only when the request's
        language is unclear, use $fallback.""".trimIndent()

    private val json = Json { isLenient = true }

    private fun root(reply: String): JsonObject? {
        val text = reply.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
    }

    /** The model's `transcript` (quoted, at most 300 characters), or null. */
    fun heard(reply: String): String? {
        val t = (root(reply)?.get("transcript") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return null
        return AiGuard.quote(t, 300)
    }

    /** The request's language as the model reported it ("de"), when it is one of [allowed]; else null. */
    fun language(reply: String, allowed: Collection<String>): String? {
        val l = (root(reply)?.get("language") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.trim()?.lowercase()?.take(2) ?: return null
        return l.takeIf { it in allowed }
    }

    /** A transcript is refused like typed text: injection, code, links, blocked words. */
    fun safe(heard: String) = !AiGuard.offTopic(heard) && AiGuard.checkText(heard) == null

    /**
     * Audio types Gemini takes (WAV, MP3, AAC, OGG, FLAC). The portal converts
     * the browser's recording (webm/opus, mp4/aac) to 16 kHz mono WAV first;
     * anything else is refused (415) rather than guessed at.
     */
    fun normalizeType(type: String?): String? = when (type?.lowercase()?.substringBefore(';')?.trim()) {
        "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "audio/wav"
        "audio/aac", "audio/x-aac" -> "audio/aac"
        "audio/mpeg", "audio/mp3" -> "audio/mp3"
        "audio/ogg" -> "audio/ogg"
        "audio/flac", "audio/x-flac" -> "audio/flac"
        else -> null
    }

    /** 30 s of 16 kHz mono 16-bit WAV is under 1 MB; this leaves room for other encodings. */
    const val MAX_BYTES = 5 * 1024 * 1024
}
