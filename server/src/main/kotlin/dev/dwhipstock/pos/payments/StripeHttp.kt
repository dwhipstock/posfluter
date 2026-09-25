package dev.dwhipstock.pos.payments

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One raw Stripe REST exchange. The seam tests replace with a fake. */
data class StripeHttpResponse(val status: Int, val body: String)

/**
 * Minimal transport for Stripe's form-encoded REST API. Implementations throw
 * [IOException] (incl. [java.net.SocketTimeoutException]) when Stripe cannot be
 * reached; any HTTP answer, error or not, is returned as a response.
 */
fun interface StripeHttp {
    fun send(method: String, path: String, params: List<Pair<String, String>>, idempotencyKey: String?): StripeHttpResponse
}

/**
 * JDK/Android [HttpURLConnection] transport (same as the cloud sync client, no
 * extra dependency, so it builds into the tablet APK unchanged). Timeouts are
 * short: a card payment waits on this, and no other POS operation ever does.
 */
class UrlStripeHttp(
    private val secretKey: String,
    private val baseUrl: String = "https://api.stripe.com",
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 20_000,
) : StripeHttp {
    override fun send(method: String, path: String, params: List<Pair<String, String>>, idempotencyKey: String?): StripeHttpResponse {
        val form = params.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
        val url = if (method == "GET" && form.isNotEmpty()) "$baseUrl$path?$form" else "$baseUrl$path"
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Authorization", "Bearer $secretKey")
            connection.setRequestProperty("Accept", "application/json")
            idempotencyKey?.let { connection.setRequestProperty("Idempotency-Key", it) }
            if (method != "GET") {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return StripeHttpResponse(status, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    override fun toString() = "UrlStripeHttp($baseUrl)" // never the key
}

/**
 * A Stripe call that did not do what was asked. [status]/[code] go straight to
 * the client (HTTP status + machine code for a translated message):
 * - `stripe_unavailable` (503): Stripe could not be reached / timed out. Nothing
 *   is recorded; the check stays payable by any other tender.
 * - `stripe_declined` (402): the card was declined ([declineCode] from Stripe).
 * - `stripe_error` (502): Stripe answered with an error.
 * - anything else: a POS-side refusal (disabled, bad state…).
 * Messages are scrubbed of anything that looks like a key.
 */
class StripeException(
    val status: Int,
    val code: String,
    message: String,
    val declineCode: String? = null,
    cause: Throwable? = null,
) : RuntimeException(scrub(message), cause) {
    val unreachable: Boolean get() = code == UNAVAILABLE

    companion object {
        const val UNAVAILABLE = "stripe_unavailable"
        const val DECLINED = "stripe_declined"
        const val ERROR = "stripe_error"
        private val keyLike = Regex("""\b(sk|rk|pk)_(test|live)_[A-Za-z0-9*]+""")
        fun scrub(s: String): String = keyLike.replace(s, "$1_$2_***")
    }
}

/**
 * Typed wrapper over [StripeHttp]: every call returns the parsed JSON object or
 * throws [StripeException]. Writes carry the caller's idempotency key so a
 * retried request (timeout, app restart) cannot charge, capture or refund twice.
 */
class StripeClient(private val http: StripeHttp) {
    private val json = Json { ignoreUnknownKeys = true }

    fun call(method: String, path: String, params: List<Pair<String, String>> = emptyList(), idempotencyKey: String? = null): JsonObject {
        val res = try {
            http.send(method, path, params, idempotencyKey)
        } catch (e: IOException) {
            throw StripeException(503, StripeException.UNAVAILABLE,
                "Stripe unreachable (${e.javaClass.simpleName})", cause = e)
        }
        val body = runCatching { json.parseToJsonElement(res.body).jsonObject }.getOrNull()
        if (res.status in 200..299 && body != null) return body
        val err = body?.get("error")?.let { runCatching { it.jsonObject }.getOrNull() }
        fun f(k: String) = err?.get(k)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        val type = f("type")
        val msg = f("message") ?: "HTTP ${res.status}"
        if (type == "card_error")
            throw StripeException(402, StripeException.DECLINED, msg, declineCode = f("decline_code") ?: f("code"))
        if (res.status >= 500 || res.status == 429)
            throw StripeException(503, StripeException.UNAVAILABLE, "Stripe temporarily unavailable: $msg")
        throw StripeException(502, StripeException.ERROR, "Stripe error ${res.status}: ${f("code") ?: type ?: ""} $msg".trim())
    }

    fun account() = call("GET", "/v1/account")
    fun connectionToken(locationId: String?) = call("POST", "/v1/terminal/connection_tokens",
        listOfNotNull(locationId?.let { "location" to it }))
    fun listLocations() = call("GET", "/v1/terminal/locations", listOf("limit" to "100"))
    fun createLocation(params: List<Pair<String, String>>, idempotencyKey: String) =
        call("POST", "/v1/terminal/locations", params, idempotencyKey)
    fun retrieveLocation(id: String) = call("GET", "/v1/terminal/locations/$id")
    fun createPaymentIntent(params: List<Pair<String, String>>, idempotencyKey: String) =
        call("POST", "/v1/payment_intents", params, idempotencyKey)
    fun retrievePaymentIntent(id: String) = call("GET", "/v1/payment_intents/$id")
    fun capturePaymentIntent(id: String, idempotencyKey: String) =
        call("POST", "/v1/payment_intents/$id/capture", emptyList(), idempotencyKey)
    fun cancelPaymentIntent(id: String, idempotencyKey: String) =
        call("POST", "/v1/payment_intents/$id/cancel", emptyList(), idempotencyKey)
    fun createRefund(params: List<Pair<String, String>>, idempotencyKey: String) =
        call("POST", "/v1/refunds", params, idempotencyKey)
}
