package dev.dwhipstock.pos.aiphotos

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.util.Base64

/**
 * OpenAI GPT Image (https://platform.openai.com/docs/guides/image-generation):
 *
 * - generate: `POST {base}/v1/images/generations`, `Authorization: Bearer`,
 *   JSON `{model, prompt, n, size, quality, output_format}` →
 *   `{data: [{b64_json}]}` (GPT image models always answer base64).
 * - enhance: `POST {base}/v1/images/edits`, multipart/form-data with the photo
 *   as `image` plus the same fields, and `input_fidelity=high` on the models
 *   that support it (keeps the real dish's details).
 *
 * Synchronous; `n` candidates in one call. A content-policy block comes back
 * as HTTP 400 with `error.code = moderation_blocked`.
 */
class OpenAiProvider(
    private val apiKey: String,
    private val http: ImageHttp,
    override val model: String = DEFAULT_MODEL,
    private val baseUrl: String = "https://api.openai.com",
    private val quality: String = "medium",
) : ImageProvider {
    override val id = "openai"
    override val host: String get() = URI(baseUrl).host

    companion object {
        const val DEFAULT_MODEL = "gpt-image-1.5"
    }

    // gpt-image-1.5, medium, 1024×1024: ~$0.034; an edit adds the input image's tokens
    override fun costPerImageUsd(edit: Boolean) = if (edit) 0.04 else 0.034

    private fun auth() = mapOf("Authorization" to "Bearer $apiKey", "accept" to "application/json")

    override fun generate(prompt: String, count: Int): List<GeneratedImage> {
        val body = buildJsonObject {
            put("model", model)
            put("prompt", prompt)
            put("n", count)
            put("size", "1024x1024")
            put("quality", quality)
            put("output_format", "jpeg")
        }
        return images(send(ImageHttpRequest("POST", "$baseUrl/v1/images/generations", auth(),
            body.toString().toByteArray(), "application/json")))
    }

    override fun enhance(photo: ByteArray, contentType: String, prompt: String, count: Int): List<GeneratedImage> {
        val form = Multipart()
            .field("model", model)
            .field("prompt", prompt)
            .field("n", count.toString())
            .field("size", "auto")
            .field("quality", quality)
            .field("output_format", "jpeg")
        // gpt-image-1.x honour input_fidelity; later models keep detail on their own
        if (model.startsWith("gpt-image-1")) form.field("input_fidelity", "high")
        form.file("image", if (contentType == "image/png") "dish.png" else "dish.jpg", contentType, photo)
        return images(send(ImageHttpRequest("POST", "$baseUrl/v1/images/edits", auth(), form.build(), form.contentType)))
    }

    private fun send(req: ImageHttpRequest): ImageHttpResponse = try {
        http.send(req)
    } catch (e: IOException) {
        throw ImageGenException.unreachable("OpenAI", e)
    }

    private fun images(res: ImageHttpResponse): List<GeneratedImage> {
        val json = parseJsonObject(res.text)
        if (res.status !in 200..299) {
            val err = json?.get("error") as? JsonObject
            val code = err?.get("code").str()
            val message = err?.get("message").str()?.take(300) ?: "HTTP ${res.status}"
            if (code == "moderation_blocked" || code == "content_policy_violation")
                throw ImageGenException.refused("OpenAI")
            if (code == "insufficient_quota" || code == "billing_hard_limit_reached")
                throw ImageGenException(402, ImageGenException.QUOTA, "OpenAI: out of credits ($message)")
            commonHttpError("OpenAI", res, message)?.let { throw it }
            throw ImageGenException(502, ImageGenException.ERROR, "OpenAI error ${res.status}: $message")
        }
        val out = (json?.get("data") as? JsonArray).orEmpty().mapNotNull { d ->
            (d as? JsonObject)?.get("b64_json").str()
                ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                ?.let { GeneratedImage(it, "image/jpeg") }
        }
        if (out.isEmpty()) throw ImageGenException(502, ImageGenException.ERROR, "OpenAI: no image in the reply")
        return out
    }

    override fun toString() = "OpenAiProvider($model)"
}
