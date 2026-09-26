package dev.dwhipstock.pos.aiphotos

import dev.dwhipstock.pos.sdk.ImageGenConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** One finished picture from a provider. */
class GeneratedImage(val bytes: ByteArray, val contentType: String)

/**
 * An image API behind the `image.provider` switch. Every call blocks (run it
 * off the engine threads) and either returns at least one image or throws
 * [ImageGenException]. [generate] is text-to-image; [enhance] is the edit /
 * image-to-image mode that improves a real photo of the dish.
 */
interface ImageProvider {
    /** `flux` | `gemini` | `openai` (or a test fake). */
    val id: String
    val model: String
    /** Host checked by the "are we online?" probe. */
    val host: String

    fun generate(prompt: String, count: Int): List<GeneratedImage>
    fun enhance(photo: ByteArray, contentType: String, prompt: String, count: Int): List<GeneratedImage>

    /** Ballpark USD per image at ~1 megapixel (docs/ai-photos.md), for the manager and the bake-off. */
    fun costPerImageUsd(edit: Boolean): Double
}

/**
 * A provider call that did not produce a photo. [status]/[code] go straight to
 * the client (HTTP status + machine code for a translated message):
 * - `image_unavailable` (503): the provider could not be reached (offline).
 * - `image_timeout` (504): it took too long (FLUX polling deadline, read timeout).
 * - `image_rate_limited` (429): slow down; [retryAfterSeconds] when known.
 * - `image_quota` (402): out of credits / billing not set up.
 * - `image_refused` (422): the provider's content policy declined the request.
 * - `image_auth` (502): the provider rejected the key.
 * - `image_error` (502): anything else the provider answered.
 * - `image_disabled` (409): the feature is off on this store.
 * Messages are scrubbed of anything that looks like a key.
 */
class ImageGenException(
    val status: Int,
    val code: String,
    message: String,
    val retryAfterSeconds: Long? = null,
    cause: Throwable? = null,
) : RuntimeException(Scrub.clean(message), cause) {
    companion object {
        const val UNAVAILABLE = "image_unavailable"
        const val TIMEOUT = "image_timeout"
        const val RATE_LIMITED = "image_rate_limited"
        const val QUOTA = "image_quota"
        const val REFUSED = "image_refused"
        const val AUTH = "image_auth"
        const val ERROR = "image_error"
        const val DISABLED = "image_disabled"

        fun unreachable(provider: String, e: IOException) =
            if (e is SocketTimeoutException) ImageGenException(504, TIMEOUT, "$provider timed out", cause = e)
            else ImageGenException(503, UNAVAILABLE, "$provider unreachable (${e.javaClass.simpleName})", cause = e)

        fun refused(provider: String, detail: String? = null) = ImageGenException(422, REFUSED,
            "$provider declined this request under its content policy" + (detail?.let { ": $it" } ?: ""))
    }
}

/** Strips anything key-shaped out of text that might reach a log or a response. */
object Scrub {
    private val patterns = listOf(
        Regex("""sk-[A-Za-z0-9_\-]{8,}"""),          // OpenAI
        Regex("""AIza[0-9A-Za-z_\-]{20,}"""),        // Google
        Regex("""(?i)(x-key|x-goog-api-key|authorization|api[_-]?key|bearer)(["'=:\s]+)[A-Za-z0-9_\-.]{8,}"""),
    )
    @Volatile private var secrets: Set<String> = emptySet()

    /** Register a live key so it is scrubbed by value too, whatever its shape. */
    fun register(secret: String?) {
        if (!secret.isNullOrBlank() && secret.length >= 6) secrets = secrets + secret
    }

    fun clean(s: String): String {
        var out = s
        for (secret in secrets) out = out.replace(secret, "***")
        out = patterns[0].replace(out, "sk-***")
        out = patterns[1].replace(out, "AIza***")
        out = patterns[2].replace(out) { "${it.groupValues[1]}${it.groupValues[2]}***" }
        return out
    }
}

internal val providerJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun parseJsonObject(text: String): JsonObject? =
    runCatching { providerJson.parseToJsonElement(text) as? JsonObject }.getOrNull()

internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

/** Seconds from a Retry-After header, when it is a plain number. */
internal fun retryAfter(res: ImageHttpResponse): Long? = res.header("Retry-After")?.trim()?.toLongOrNull()

/** The shared mapping of HTTP errors every provider has in common. */
internal fun commonHttpError(provider: String, res: ImageHttpResponse, detail: String): ImageGenException? = when {
    res.status == 401 || res.status == 403 ->
        ImageGenException(502, ImageGenException.AUTH, "$provider rejected the API key (${res.status})")
    res.status == 402 -> ImageGenException(402, ImageGenException.QUOTA, "$provider: out of credits ($detail)")
    res.status == 429 -> ImageGenException(429, ImageGenException.RATE_LIMITED,
        "$provider rate limit: $detail", retryAfterSeconds = retryAfter(res))
    res.status >= 500 -> ImageGenException(503, ImageGenException.UNAVAILABLE,
        "$provider temporarily unavailable (${res.status}): $detail")
    else -> null
}

/**
 * Run [count] single-image requests side by side (providers that return one
 * image per call). Keeps whatever succeeded; if all fail, throws the most
 * telling failure (a refusal or rate limit beats a generic error).
 */
internal fun fanOut(count: Int, one: (Int) -> GeneratedImage): List<GeneratedImage> {
    if (count <= 1) return listOf(one(0))
    val pool = Executors.newFixedThreadPool(count.coerceAtMost(4)) { r ->
        Thread(r, "ai-photo").apply { isDaemon = true }
    }
    try {
        val futures = (0 until count).map { i -> pool.submit(Callable { one(i) }) }
        val ok = mutableListOf<GeneratedImage>()
        val errors = mutableListOf<Throwable>()
        for (f in futures) {
            try { ok += f.get() } catch (e: ExecutionException) { errors += e.cause ?: e }
        }
        if (ok.isNotEmpty()) return ok
        val gen = errors.filterIsInstance<ImageGenException>()
        throw gen.firstOrNull { it.code == ImageGenException.REFUSED }
            ?: gen.firstOrNull { it.code == ImageGenException.RATE_LIMITED }
            ?: gen.firstOrNull()
            ?: ImageGenException(502, ImageGenException.ERROR, "image generation failed")
    } finally {
        pool.shutdownNow()
    }
}

/** Builds the configured provider (null when AI photos are off). */
object ImageProviders {
    fun from(config: ImageGenConfig.Resolved, http: ImageHttp = UrlImageHttp()): ImageProvider? {
        val key = config.apiKey ?: return null
        Scrub.register(key)
        return when (config.provider) {
            ImageGenConfig.Provider.FLUX -> FluxProvider(key, http, model = config.model ?: FluxProvider.DEFAULT_MODEL)
            ImageGenConfig.Provider.GEMINI -> GeminiProvider(key, http, model = config.model ?: GeminiProvider.DEFAULT_MODEL)
            ImageGenConfig.Provider.OPENAI -> OpenAiProvider(key, http, model = config.model ?: OpenAiProvider.DEFAULT_MODEL)
            ImageGenConfig.Provider.OFF -> null
        }
    }
}
