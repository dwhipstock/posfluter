package dev.dwhipstock.pos.aiphotos

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

/**
 * Google Gemini native image generation ("Nano Banana") through the Gemini API
 * Interactions endpoint (https://ai.google.dev/gemini-api/docs/image-generation):
 *
 * `POST {base}/v1beta/interactions`, header `x-goog-api-key`, JSON
 * `{model, input: [{type: text, text}, {type: image, mime_type, data}?],
 *   response_format: {type: image, mime_type, aspect_ratio?, image_size}}`.
 * Synchronous: the reply is an Interaction `{status, steps: [{type:
 * model_output, content: [{type: image, mime_type, data}]}]}`. The same model
 * edits when an input image is sent with the instruction. One image per call.
 *
 * A reply with no image (or `status: failed` naming safety) is treated as a
 * content-policy refusal. The older `generateContent` reply shape
 * (`candidates[].content.parts[].inlineData`) is accepted too.
 */
class GeminiProvider(
    private val apiKey: String,
    private val http: ImageHttp,
    override val model: String = DEFAULT_MODEL,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
) : ImageProvider {
    override val id = "gemini"
    override val host: String get() = URI(baseUrl).host

    companion object {
        const val DEFAULT_MODEL = "gemini-3.1-flash-image"
        private val SAFETY = Regex("(?i)safety|blocked|prohibited|policy|harm|recitation")
    }

    // $60 per 1M image output tokens: ~$0.067 per 1K image (Sept 2026 price list)
    override fun costPerImageUsd(edit: Boolean) = 0.067

    override fun generate(prompt: String, count: Int) = fanOut(count) { call(prompt, null, null, square = true) }

    override fun enhance(photo: ByteArray, contentType: String, prompt: String, count: Int) =
        fanOut(count) { call(prompt, photo, contentType, square = false) }

    private fun call(prompt: String, photo: ByteArray?, photoType: String?, square: Boolean): GeneratedImage {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("input") {
                addJsonObject { put("type", "text"); put("text", prompt) }
                if (photo != null) addJsonObject {
                    put("type", "image")
                    put("mime_type", photoType ?: "image/jpeg")
                    put("data", Base64.getEncoder().encodeToString(photo))
                }
            }
            putJsonObject("response_format") {
                put("type", "image")
                put("mime_type", "image/jpeg")
                // an edit keeps the photo's own framing
                if (square) put("aspect_ratio", "1:1")
                put("image_size", "1K")
            }
        }
        val res = try {
            http.send(ImageHttpRequest("POST", "$baseUrl/v1beta/interactions",
                mapOf("x-goog-api-key" to apiKey, "accept" to "application/json"),
                body.toString().toByteArray(), "application/json"))
        } catch (e: IOException) {
            throw ImageGenException.unreachable("Gemini", e)
        }
        val json = parseJsonObject(res.text)
        if (res.status !in 200..299) {
            val err = json?.get("error") as? JsonObject
            val message = err?.get("message").str()?.take(300) ?: "HTTP ${res.status}"
            val status = err?.get("status").str()
            if (status == "RESOURCE_EXHAUSTED" && res.status != 429)
                throw ImageGenException(429, ImageGenException.RATE_LIMITED, "Gemini quota: $message", retryAfter(res))
            commonHttpError("Gemini", res, message)?.let { throw it }
            if (SAFETY.containsMatchIn(message)) throw ImageGenException.refused("Gemini", message)
            throw ImageGenException(502, ImageGenException.ERROR, "Gemini error ${res.status}: $message")
        }
        json ?: throw ImageGenException(502, ImageGenException.ERROR, "Gemini: unreadable reply")
        findImage(json)?.let { return it }
        val status = json["status"].str()
        val why = (json["error"] as? JsonObject)?.get("message").str()
        if (status == "failed" && why != null && !SAFETY.containsMatchIn(why))
            throw ImageGenException(502, ImageGenException.ERROR, "Gemini interaction failed: $why")
        // completed with no picture: the model declined (content policy)
        throw ImageGenException.refused("Gemini", why ?: status)
    }

    private fun findImage(json: JsonObject): GeneratedImage? {
        (json["steps"] as? JsonArray)?.forEach { step ->
            val s = step as? JsonObject ?: return@forEach
            (s["content"] as? JsonArray)?.forEach { block ->
                val b = block as? JsonObject ?: return@forEach
                if (b["type"].str() == "image") b["data"].str()?.let { return decode(it, b["mime_type"].str()) }
            }
        }
        // generateContent shape
        (json["candidates"] as? JsonArray)?.forEach { c ->
            val parts = ((c as? JsonObject)?.get("content") as? JsonObject)?.get("parts") as? JsonArray
            parts?.forEach { p ->
                val inline = ((p as? JsonObject)?.get("inlineData") ?: (p as? JsonObject)?.get("inline_data"))
                    as? JsonObject ?: return@forEach
                inline["data"].str()?.let {
                    return decode(it, inline["mimeType"].str() ?: inline["mime_type"].str())
                }
            }
        }
        return null
    }

    private fun decode(data: String, mime: String?): GeneratedImage? {
        val bytes = runCatching { Base64.getDecoder().decode(data) }.getOrNull() ?: return null
        return GeneratedImage(bytes, if (mime == "image/png") "image/png" else "image/jpeg")
    }

    override fun toString() = "GeminiProvider($model)"
}
