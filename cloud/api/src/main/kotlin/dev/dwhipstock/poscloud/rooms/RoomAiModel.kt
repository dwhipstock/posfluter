package dev.dwhipstock.poscloud.rooms

import dev.dwhipstock.poscloud.menuai.AiAudio
import dev.dwhipstock.poscloud.menuai.AiHttp
import dev.dwhipstock.poscloud.menuai.JdkAiHttp
import dev.dwhipstock.poscloud.menuai.MenuAiException
import dev.dwhipstock.poscloud.menuai.Scrub
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
import java.net.http.HttpTimeoutException
import java.util.Base64

/** A picture for the model (a phone photo of a room, resized here first). */
class AiImage(val bytes: ByteArray, val contentType: String)

/**
 * The model behind the portal's room assistant: like the menu's
 * ([dev.dwhipstock.poscloud.menuai.MenuAiModel]) plus pictures. Tests pass a fake.
 */
interface RoomAiModel {
    val id: String
    val model: String
    fun complete(system: String, user: String, audio: AiAudio?, images: List<AiImage>): String
}

/**
 * Gemini Interactions API for the room assistant: the menu's GeminiMenuModel
 * (menuai/MenuAiModel.kt — same request, retry and fallback, same key
 * handling) with image parts, and the store's floor-plan settings
 * (server/.../aimenu/MenuAiProvider.kt: LAYOUT_MODEL flash-lite, LAYOUT_THINKING
 * "medium" for room from picture, typed and spoken floor edits; a longer wait).
 */
class GeminiRoomModel(
    private val apiKey: String,
    override val model: String = DEFAULT_MODEL,
    private val http: AiHttp = JdkAiHttp(),
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    private val thinkingLevel: String = "medium",
    private val budgetMs: Long = 150_000L,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) : RoomAiModel {
    override val id = "gemini"

    init { Scrub.register(apiKey) }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        const val FALLBACK_MODEL = "gemini-3.5-flash"
        /** A new room from several photos (the store's GeminiMenuProvider.MULTI_VIEW_MODEL). */
        const val MULTI_VIEW_MODEL = "gemini-3.5-flash"
        const val RETRY_PAUSE_MS = 1_500L
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    override fun complete(system: String, user: String, audio: AiAudio?, images: List<AiImage>): String {
        val body = { m: String ->
            buildJsonObject {
                put("model", m)
                put("system_instruction", system)
                putJsonArray("input") {
                    images.forEach { img ->
                        addJsonObject {
                            put("type", "image"); put("mime_type", img.contentType)
                            put("data", Base64.getEncoder().encodeToString(img.bytes))
                        }
                    }
                    if (audio != null) addJsonObject {
                        put("type", "audio"); put("mime_type", audio.contentType)
                        put("data", Base64.getEncoder().encodeToString(audio.bytes))
                    }
                    addJsonObject { put("type", "text"); put("text", user) }
                }
                putJsonObject("response_format") { put("type", "text"); put("mime_type", "application/json") }
                putJsonObject("generation_config") { put("temperature", 0); put("thinking_level", thinkingLevel) }
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
        // a dropped connection (live: Gemini cut busy calls at ~60 s with an EOF) counts as busy:
        // straight to the fallback model, no second wait on the same one
        var dropped = false
        var (status, text) = try { post(model) } catch (e: MenuAiException) {
            if (e.code != "menu_ai_unavailable" || left() <= 0) throw e
            dropped = true
            503 to ""
        }
        if (!dropped && status == 503 && left() > RETRY_PAUSE_MS) { pause(RETRY_PAUSE_MS); post(model).let { status = it.first; text = it.second } }
        // a model that is gone (a preview pulled: 404) also falls back, so a room photo never just fails
        if ((status == 503 || status == 429 || status == 404) && left() > 0) {
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

    override fun toString() = "GeminiRoomModel($model)"
}

/**
 * A phone photo for the model: decoded and scaled down to [MAX_SIDE] px on the
 * longest side as JPEG (a 12 MP photo is 3–5 MB; the model needs far less).
 * What the JVM can't decode (HEIC, WebP) goes as is when the model takes the
 * type; anything else is refused.
 */
object RoomPhoto {
    const val MAX_SIDE = 1600
    const val MAX_BYTES = 12 * 1024 * 1024
    /** "New room from photo": up to this many views of one room in one request, in one model call. */
    const val MAX_PHOTOS = 4
    /** All of one request's photos together, as uploaded (before the resize). */
    const val MAX_TOTAL_BYTES = 32 * 1024 * 1024
    private val PASS_THROUGH = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")

    fun normalizeType(type: String?): String? = when (val t = type?.lowercase()?.substringBefore(';')?.trim()) {
        "image/jpg", "image/pjpeg" -> "image/jpeg"
        in PASS_THROUGH -> t
        else -> null
    }

    fun prepare(bytes: ByteArray, type: String?): AiImage {
        System.setProperty("java.awt.headless", "true") // a server: no display, ever
        val decoded = runCatching { javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)) }.getOrNull()
        if (decoded == null) {
            val t = normalizeType(type) ?: throw MenuAiException(415, "room_ai_image_type", "send the picture as a JPEG, PNG, WebP or HEIC photo")
            return AiImage(bytes, t)
        }
        val side = maxOf(decoded.width, decoded.height)
        val k = if (side > MAX_SIDE) MAX_SIDE.toDouble() / side else 1.0
        val w = maxOf(1, (decoded.width * k).toInt())
        val h = maxOf(1, (decoded.height * k).toInt())
        val out = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, w, h)
            g.drawImage(decoded, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        val buf = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(out, "jpg", buf)
        return AiImage(buf.toByteArray(), "image/jpeg")
    }
}
