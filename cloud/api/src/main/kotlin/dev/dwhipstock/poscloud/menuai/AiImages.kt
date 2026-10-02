package dev.dwhipstock.poscloud.menuai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
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

// PORTED from the store: server/src/main/kotlin/dev/dwhipstock/pos/aiphotos/
// (ImageProvider.kt, FluxProvider.kt, GeminiProvider.kt, ImageHttp.kt). Same
// providers, same request shapes, same error codes; the cloud adds a fallback
// chain (FLUX first, Gemini when FLUX can't answer). Two Gradle builds, so the
// code is copied: change both together.

/** One finished picture from a provider. */
class GeneratedImage(val bytes: ByteArray, val contentType: String)

/**
 * An image API. Every call blocks and returns one image or throws
 * [ImageGenException]. [generate] is text-to-image; [enhance] edits a real
 * photo of the dish (the store's "Snap and enhance").
 */
interface ImageProvider {
    val id: String
    val model: String
    fun generate(prompt: String): GeneratedImage
    fun enhance(photo: ByteArray, contentType: String, prompt: String): GeneratedImage

    /**
     * A picture about [width] × [height] px (a printed menu's header or page
     * background). A provider that can't size its pictures gives its square.
     */
    fun generate(prompt: String, width: Int, height: Int): GeneratedImage = generate(prompt)
}

/**
 * A provider call that produced no photo. [code]s, as on the store:
 * image_unavailable (503), image_timeout (504), image_rate_limited (429),
 * image_quota (402), image_refused (422, content policy), image_auth (502),
 * image_error (502). Messages are the cloud's own, scrubbed of keys.
 */
class ImageGenException(val status: Int, val code: String, message: String, cause: Throwable? = null) :
    RuntimeException(Scrub.clean(message), cause) {
    companion object {
        const val UNAVAILABLE = "image_unavailable"
        const val TIMEOUT = "image_timeout"
        const val RATE_LIMITED = "image_rate_limited"
        const val QUOTA = "image_quota"
        const val REFUSED = "image_refused"
        const val AUTH = "image_auth"
        const val ERROR = "image_error"

        fun unreachable(provider: String, e: IOException) =
            if (e is HttpTimeoutException) ImageGenException(504, TIMEOUT, "$provider timed out", e)
            else ImageGenException(503, UNAVAILABLE, "$provider unreachable (${e.javaClass.simpleName})", e)

        fun refused(provider: String) = ImageGenException(422, REFUSED, "$provider declined this request under its content policy")
    }
}

/** One raw HTTP exchange. [headers] carry the key, so [toString] prints only the method and URL. */
class ImageHttpRequest(
    val method: String, val url: String, val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null, val timeoutMs: Long = 90_000,
) {
    override fun toString() = "$method $url"
}

class ImageHttpResponse(val status: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap()) {
    val text: String get() = body.toString(Charsets.UTF_8)
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** The seam tests replace. Throws [IOException] when the provider can't be reached. */
fun interface ImageHttp {
    fun send(request: ImageHttpRequest): ImageHttpResponse
}

/** JDK client; a response over [maxBytes] is refused (no provider image is near that). */
class JdkImageHttp(private val maxBytes: Int = 20 * 1024 * 1024) : ImageHttp {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL).build()

    override fun send(request: ImageHttpRequest): ImageHttpResponse {
        val b = HttpRequest.newBuilder(URI(request.url)).timeout(Duration.ofMillis(request.timeoutMs.coerceAtLeast(1)))
        if (request.body != null) b.method(request.method, HttpRequest.BodyPublishers.ofByteArray(request.body))
            .header("Content-Type", "application/json")
        else b.method(request.method, HttpRequest.BodyPublishers.noBody())
        request.headers.forEach { (k, v) -> b.header(k, v) }
        val res = try {
            client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("interrupted", e)
        }
        val bytes = res.body().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > maxBytes) throw IOException("response too large")
            }
            out.toByteArray()
        }
        return ImageHttpResponse(res.statusCode(), bytes, res.headers().map().mapValues { it.value.joinToString(",") })
    }
}

private val providerJson = Json { ignoreUnknownKeys = true; isLenient = true }
private fun parseObject(text: String): JsonObject? = runCatching { providerJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

/** The HTTP errors every provider shares. Never quotes the provider's reply. */
private fun commonHttpError(provider: String, status: Int): ImageGenException? = when {
    status == 401 || status == 403 -> ImageGenException(502, ImageGenException.AUTH, "$provider rejected the API key ($status)")
    status == 402 -> ImageGenException(402, ImageGenException.QUOTA, "$provider: out of credits")
    status == 429 -> ImageGenException(429, ImageGenException.RATE_LIMITED, "$provider rate limit")
    status >= 500 -> ImageGenException(503, ImageGenException.UNAVAILABLE, "$provider temporarily unavailable ($status)")
    else -> null
}

/**
 * Black Forest Labs FLUX (the store's FluxProvider): `POST {base}/v1/{model}`
 * with `x-key` → `{polling_url}`; poll until `Ready` (`result.sample`, a signed
 * image URL fetched without the key) or moderated / failed. The key only ever
 * goes to BFL's own hosts.
 */
class FluxImageProvider(
    private val apiKey: String,
    private val http: ImageHttp = JdkImageHttp(),
    override val model: String = DEFAULT_MODEL,
    private val baseUrl: String = "https://api.bfl.ai",
    private val pollIntervalMs: Long = 750,
    private val deadlineMs: Long = 75_000,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> Long = System::currentTimeMillis,
) : ImageProvider {
    override val id = "flux"

    init { Scrub.register(apiKey) }

    companion object {
        const val DEFAULT_MODEL = "flux-2-pro"
        private val PENDING = setOf("Pending", "Queued", "Processing", "Task not found")
        private val MODERATED = setOf("Request Moderated", "Content Moderated")
    }

    private fun seed() = (System.nanoTime() and 0x7fffffff)

    override fun generate(prompt: String) = generate(prompt, 1024, 1024)

    /** FLUX takes any size in steps of 16 (kept within 256–2048 a side). */
    override fun generate(prompt: String, width: Int, height: Int) = run(buildJsonObject {
        put("prompt", prompt); put("width", side(width)); put("height", side(height))
        put("output_format", "jpeg"); put("safety_tolerance", 2); put("seed", seed())
    })

    private fun side(px: Int) = (px.coerceIn(256, 2048) / 16) * 16

    override fun enhance(photo: ByteArray, contentType: String, prompt: String) = run(buildJsonObject {
        put("prompt", prompt); put("input_image", Base64.getEncoder().encodeToString(photo))
        put("output_format", "jpeg"); put("safety_tolerance", 2); put("seed", seed())
    })

    private fun headers() = mapOf("x-key" to apiKey, "accept" to "application/json")

    private fun send(req: ImageHttpRequest): ImageHttpResponse = try {
        http.send(req)
    } catch (e: IOException) {
        throw ImageGenException.unreachable("FLUX", e)
    }

    private fun run(body: JsonObject): GeneratedImage {
        val submit = send(ImageHttpRequest("POST", "$baseUrl/v1/$model", headers(), body.toString().toByteArray(), 30_000))
        val submitted = parseObject(submit.text)
        if (submit.status !in 200..299) throw httpError(submit, submitted)
        val pollingUrl = submitted?.get("polling_url").str()
            ?: submitted?.get("id").str()?.let { "$baseUrl/v1/get_result?id=$it" }
            ?: throw ImageGenException(502, ImageGenException.ERROR, "FLUX: no task id in the reply")
        requireBflHost(pollingUrl)
        val deadline = now() + deadlineMs
        var interval = pollIntervalMs
        while (true) {
            if (now() > deadline) throw ImageGenException(504, ImageGenException.TIMEOUT, "FLUX did not finish within ${deadlineMs / 1000}s")
            sleep(interval)
            val poll = send(ImageHttpRequest("GET", pollingUrl, headers(), timeoutMs = 20_000))
            val result = parseObject(poll.text)
            if (poll.status == 429) { interval = (interval * 2).coerceAtMost(5_000); continue }
            if (poll.status !in 200..299) throw httpError(poll, result)
            val status = result?.get("status").str() ?: "Pending"
            when {
                status == "Ready" -> {
                    val sample = runCatching { result!!["result"]!!.jsonObject["sample"].str() }.getOrNull()
                        ?: throw ImageGenException(502, ImageGenException.ERROR, "FLUX: Ready without an image URL")
                    return download(sample)
                }
                status in MODERATED -> throw ImageGenException.refused("FLUX")
                status in PENDING -> interval = (interval + 250).coerceAtMost(2_000)
                else -> throw ImageGenException(502, ImageGenException.ERROR, "FLUX task failed")
            }
        }
    }

    private fun download(url: String): GeneratedImage {
        if (!url.startsWith("https://")) throw ImageGenException(502, ImageGenException.ERROR, "FLUX: image URL is not https")
        val res = send(ImageHttpRequest("GET", url, timeoutMs = 30_000)) // signed delivery URL: no key goes with it
        if (res.status !in 200..299 || res.body.isEmpty())
            throw ImageGenException(502, ImageGenException.ERROR, "FLUX: image download failed (${res.status})")
        val type = res.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
        return GeneratedImage(res.body, if (type == "image/png") "image/png" else "image/jpeg")
    }

    private fun requireBflHost(url: String) {
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host?.lowercase()
        val base = URI(baseUrl).host.lowercase()
        val okHost = host != null && (host == base || host == "bfl.ai" || host.endsWith(".bfl.ai"))
        val okScheme = uri?.scheme == "https" || url.startsWith(baseUrl)
        if (!okHost || !okScheme) throw ImageGenException(502, ImageGenException.ERROR, "FLUX: unexpected polling host")
    }

    private fun httpError(res: ImageHttpResponse, body: JsonObject?): ImageGenException {
        commonHttpError("FLUX", res.status)?.let { return it }
        if (body?.get("detail")?.toString()?.contains("moderat", ignoreCase = true) == true) return ImageGenException.refused("FLUX")
        return ImageGenException(502, ImageGenException.ERROR, "FLUX error ${res.status}")
    }

    override fun toString() = "FluxImageProvider($model)"
}

/**
 * Gemini native image generation (the store's GeminiProvider): `POST
 * {base}/v1beta/interactions` with `x-goog-api-key`, a text (and for an edit
 * an image) input, `response_format: image`. A reply with no image is a
 * content-policy refusal.
 */
class GeminiImageProvider(
    private val apiKey: String,
    private val http: ImageHttp = JdkImageHttp(),
    override val model: String = DEFAULT_MODEL,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
) : ImageProvider {
    override val id = "gemini"

    init { Scrub.register(apiKey) }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.1-flash-image"
        private val SAFETY = Regex("(?i)safety|blocked|prohibited|policy|harm|recitation")
        private val ASPECTS = listOf("1:1" to 1.0, "3:4" to 0.75, "4:3" to 4 / 3.0, "2:3" to 2 / 3.0, "3:2" to 1.5,
            "9:16" to 9 / 16.0, "16:9" to 16 / 9.0, "21:9" to 21 / 9.0)
    }

    override fun generate(prompt: String) = call(prompt, null, null, aspect = "1:1")
    override fun enhance(photo: ByteArray, contentType: String, prompt: String) = call(prompt, photo, contentType, aspect = null)

    /** Gemini sizes by aspect ratio: the nearest one it offers, at 2K for print. */
    override fun generate(prompt: String, width: Int, height: Int): GeneratedImage {
        val want = width.toDouble() / height.coerceAtLeast(1)
        val aspect = ASPECTS.minBy { (_, r) -> kotlin.math.abs(kotlin.math.ln(r / want)) }.first
        return call(prompt, null, null, aspect = aspect, size = "2K")
    }

    private fun call(prompt: String, photo: ByteArray?, photoType: String?, aspect: String?, size: String = "1K"): GeneratedImage {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("input") {
                addJsonObject { put("type", "text"); put("text", prompt) }
                if (photo != null) addJsonObject {
                    put("type", "image"); put("mime_type", photoType ?: "image/jpeg")
                    put("data", Base64.getEncoder().encodeToString(photo))
                }
            }
            putJsonObject("response_format") {
                put("type", "image"); put("mime_type", "image/jpeg")
                aspect?.let { put("aspect_ratio", it) }
                put("image_size", size)
            }
        }
        val res = try {
            http.send(ImageHttpRequest("POST", "$baseUrl/v1beta/interactions",
                mapOf("x-goog-api-key" to apiKey, "accept" to "application/json"), body.toString().toByteArray(), 90_000))
        } catch (e: IOException) {
            throw ImageGenException.unreachable("Gemini", e)
        }
        val json = parseObject(res.text)
        if (res.status !in 200..299) {
            val err = json?.get("error") as? JsonObject
            val message = err?.get("message").str().orEmpty()
            if (err?.get("status").str() == "RESOURCE_EXHAUSTED")
                throw ImageGenException(429, ImageGenException.RATE_LIMITED, "Gemini quota")
            commonHttpError("Gemini", res.status)?.let { throw it }
            if (SAFETY.containsMatchIn(message)) throw ImageGenException.refused("Gemini")
            throw ImageGenException(502, ImageGenException.ERROR, "Gemini error ${res.status}")
        }
        json ?: throw ImageGenException(502, ImageGenException.ERROR, "Gemini: unreadable reply")
        findImage(json)?.let { return it }
        val why = (json["error"] as? JsonObject)?.get("message").str()
        if (json["status"].str() == "failed" && why != null && !SAFETY.containsMatchIn(why))
            throw ImageGenException(502, ImageGenException.ERROR, "Gemini interaction failed")
        throw ImageGenException.refused("Gemini")
    }

    private fun findImage(json: JsonObject): GeneratedImage? {
        (json["steps"] as? JsonArray)?.forEach { step ->
            ((step as? JsonObject)?.get("content") as? JsonArray)?.forEach { block ->
                val b = block as? JsonObject ?: return@forEach
                if (b["type"].str() == "image") b["data"].str()?.let { return decode(it, b["mime_type"].str()) }
            }
        }
        (json["candidates"] as? JsonArray)?.forEach { c ->
            (((c as? JsonObject)?.get("content") as? JsonObject)?.get("parts") as? JsonArray)?.forEach { p ->
                val inline = ((p as? JsonObject)?.get("inlineData") ?: (p as? JsonObject)?.get("inline_data")) as? JsonObject
                    ?: return@forEach
                inline["data"].str()?.let { return decode(it, inline["mimeType"].str() ?: inline["mime_type"].str()) }
            }
        }
        return null
    }

    private fun decode(data: String, mime: String?): GeneratedImage? {
        val bytes = runCatching { Base64.getDecoder().decode(data) }.getOrNull() ?: return null
        return GeneratedImage(bytes, if (mime == "image/png") "image/png" else "image/jpeg")
    }

    override fun toString() = "GeminiImageProvider($model)"
}

/**
 * The portal's photo maker: [providers] in order (FLUX, then Gemini). A
 * provider that can't answer (offline, busy, out of credits, a bad key, an
 * error) hands over to the next; a content-policy refusal does not — the
 * request itself was declined, and asking another model to draw it anyway is
 * not what a refusal is for.
 */
class ImageGen(val providers: List<ImageProvider>) {
    val enabled: Boolean get() = providers.isNotEmpty()

    /** The image and the provider that made it. */
    fun run(call: (ImageProvider) -> GeneratedImage): Pair<GeneratedImage, ImageProvider> {
        var last: ImageGenException? = null
        for (p in providers) {
            try {
                return call(p) to p
            } catch (e: ImageGenException) {
                if (e.code == ImageGenException.REFUSED) throw e
                last = e
            }
        }
        throw last ?: ImageGenException(409, "menu_ai_photos_disabled", "AI photos are not set up on this portal")
    }

    companion object {
        fun from(bflKey: String?, geminiKey: String?, http: ImageHttp = JdkImageHttp()) = ImageGen(listOfNotNull(
            bflKey?.takeIf { it.isNotBlank() }?.let { FluxImageProvider(it, http) },
            geminiKey?.takeIf { it.isNotBlank() }?.let { GeminiImageProvider(it, http) },
        ))
    }
}
