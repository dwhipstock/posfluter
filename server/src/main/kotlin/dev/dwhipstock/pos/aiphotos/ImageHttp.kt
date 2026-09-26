package dev.dwhipstock.pos.aiphotos

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * One raw HTTP exchange with an image provider. [headers] carry the API key,
 * so [toString] prints only the method and URL (never a header).
 */
class ImageHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null,
) {
    val bodyText: String get() = body?.toString(Charsets.UTF_8) ?: ""
    override fun toString() = "$method $url"
}

class ImageHttpResponse(val status: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap()) {
    val text: String get() = body.toString(Charsets.UTF_8)
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/**
 * The seam tests replace with a fake. Implementations throw [IOException]
 * (incl. [java.net.SocketTimeoutException]) when the provider cannot be
 * reached; any HTTP answer, error or not, comes back as a response.
 */
fun interface ImageHttp {
    fun send(request: ImageHttpRequest): ImageHttpResponse
}

/**
 * JDK/Android [HttpURLConnection] transport (no extra dependency, so it
 * builds into the tablet APK unchanged, like the Stripe and cloud clients).
 * The read timeout is long: an image can take a minute or more to render.
 * Only a manager waiting on "Generate" ever waits on this.
 */
class UrlImageHttp(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 150_000,
    private val maxResponseBytes: Int = 40 * 1024 * 1024,
) : ImageHttp {
    override fun send(request: ImageHttpRequest): ImageHttpResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = true
            request.headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (request.body != null) {
                connection.doOutput = true
                request.contentType?.let { connection.setRequestProperty("Content-Type", it) }
                connection.outputStream.use { it.write(request.body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxResponseBytes) throw IOException("response too large")
                }
                out.toByteArray()
            } ?: ByteArray(0)
            val headers = connection.headerFields.orEmpty()
                .filterKeys { it != null }
                .mapValues { (_, v) -> v.joinToString(",") }
            return ImageHttpResponse(status, body, headers)
        } finally {
            connection.disconnect()
        }
    }

    override fun toString() = "UrlImageHttp"
}

/** A tiny multipart/form-data body (OpenAI image edits). */
class Multipart {
    val boundary = "----pos-ai-${UUID.randomUUID()}"
    private val out = ByteArrayOutputStream()

    fun field(name: String, value: String) = apply {
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
    }

    fun file(name: String, filename: String, contentType: String, bytes: ByteArray) = apply {
        out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\n" +
            "Content-Type: $contentType\r\n\r\n").toByteArray())
        out.write(bytes)
        out.write("\r\n".toByteArray())
    }

    val contentType: String get() = "multipart/form-data; boundary=$boundary"

    fun build(): ByteArray = out.toByteArray() + "--$boundary--\r\n".toByteArray()
}
