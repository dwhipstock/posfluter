package dev.dwhipstock.pos.aiphotos

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.util.Base64

/**
 * Black Forest Labs FLUX (https://docs.bfl.ai). Asynchronous:
 *
 * 1. `POST {base}/v1/{model}` with header `x-key`, JSON `{prompt, width,
 *    height, output_format, safety_tolerance, seed, input_image?}` →
 *    `{id, polling_url}`. FLUX.2 [pro] both generates and edits: a base64
 *    `input_image` turns the call into an edit of that photo.
 * 2. `GET polling_url` (with `x-key`) until `status` is `Ready` (then
 *    `result.sample` is a signed image URL, valid ~10 minutes), or a terminal
 *    `Request Moderated` / `Content Moderated` (refusal) / `Error` / `Failed`.
 * 3. `GET result.sample` (no key) for the bytes.
 *
 * One image per request, so candidates are separate requests with different
 * seeds. The key is only ever sent to BFL's own hosts: a polling URL on any
 * other host is refused.
 */
class FluxProvider(
    private val apiKey: String,
    private val http: ImageHttp,
    override val model: String = DEFAULT_MODEL,
    private val baseUrl: String = "https://api.bfl.ai",
    private val pollIntervalMs: Long = 750,
    private val deadlineMs: Long = 120_000,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val now: () -> Long = System::currentTimeMillis,
    private val seeds: (Int) -> Long = { i -> (System.nanoTime() + i * 7919L) and 0x7fffffff },
) : ImageProvider {
    override val id = "flux"
    override val host: String get() = URI(baseUrl).host

    companion object {
        const val DEFAULT_MODEL = "flux-2-pro"
        private val PENDING = setOf("Pending", "Queued", "Processing", "Task not found")
        private val MODERATED = setOf("Request Moderated", "Content Moderated")
    }

    override fun costPerImageUsd(edit: Boolean) = if (edit) 0.045 else 0.03

    override fun generate(prompt: String, count: Int) = fanOut(count) { i ->
        run(buildJsonObject {
            put("prompt", prompt)
            put("width", 1024)
            put("height", 1024)
            put("output_format", "jpeg")
            put("safety_tolerance", 2)
            put("seed", seeds(i))
        })
    }

    override fun enhance(photo: ByteArray, contentType: String, prompt: String, count: Int) = fanOut(count) { i ->
        // no width/height: the edit keeps the input photo's proportions
        run(buildJsonObject {
            put("prompt", prompt)
            put("input_image", Base64.getEncoder().encodeToString(photo))
            put("output_format", "jpeg")
            put("safety_tolerance", 2)
            put("seed", seeds(i))
        })
    }

    private fun headers() = mapOf("x-key" to apiKey, "accept" to "application/json")

    private fun send(req: ImageHttpRequest): ImageHttpResponse = try {
        http.send(req)
    } catch (e: IOException) {
        throw ImageGenException.unreachable("FLUX", e)
    }

    private fun run(body: JsonObject): GeneratedImage {
        val submit = send(ImageHttpRequest("POST", "$baseUrl/v1/$model", headers(),
            body.toString().toByteArray(), "application/json"))
        val submitted = parseJsonObject(submit.text)
        if (submit.status !in 200..299) throw httpError(submit, submitted)
        val pollingUrl = submitted?.get("polling_url").str()
            ?: submitted?.get("id").str()?.let { "$baseUrl/v1/get_result?id=$it" }
            ?: throw ImageGenException(502, ImageGenException.ERROR, "FLUX: no task id in the reply")
        requireBflHost(pollingUrl)

        val deadline = now() + deadlineMs
        var interval = pollIntervalMs
        while (true) {
            if (now() > deadline) throw ImageGenException(504, ImageGenException.TIMEOUT,
                "FLUX did not finish within ${deadlineMs / 1000}s")
            sleep(interval)
            val poll = send(ImageHttpRequest("GET", pollingUrl, headers()))
            val result = parseJsonObject(poll.text)
            if (poll.status == 429) { interval = (interval * 2).coerceAtMost(5_000); continue }
            if (poll.status !in 200..299) throw httpError(poll, result)
            val status = result?.get("status").str() ?: "Pending"
            when {
                status == "Ready" -> {
                    val sample = runCatching { result!!["result"]!!.jsonObject["sample"].str() }.getOrNull()
                        ?: throw ImageGenException(502, ImageGenException.ERROR, "FLUX: Ready without an image URL")
                    return download(sample)
                }
                status in MODERATED -> throw ImageGenException.refused("FLUX", status)
                status in PENDING -> interval = (interval + 250).coerceAtMost(2_000)
                else -> throw ImageGenException(502, ImageGenException.ERROR, "FLUX task $status")
            }
        }
    }

    private fun download(url: String): GeneratedImage {
        require(url.startsWith("https://")) { "FLUX: image URL is not https" }
        // signed delivery URL: no key goes with it
        val res = send(ImageHttpRequest("GET", url))
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
        if (!okHost || !okScheme)
            throw ImageGenException(502, ImageGenException.ERROR, "FLUX: unexpected polling host")
    }

    private fun httpError(res: ImageHttpResponse, body: JsonObject?): ImageGenException {
        val detail = body?.get("detail")?.toString()?.take(300) ?: "HTTP ${res.status}"
        commonHttpError("FLUX", res, detail)?.let { return it }
        if (detail.contains("moderat", ignoreCase = true)) return ImageGenException.refused("FLUX")
        return ImageGenException(502, ImageGenException.ERROR, "FLUX error ${res.status}: $detail")
    }

    override fun toString() = "FluxProvider($model)"
}
