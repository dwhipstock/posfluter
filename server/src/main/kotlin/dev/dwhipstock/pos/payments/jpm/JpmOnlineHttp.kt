package dev.dwhipstock.pos.payments.jpm

import dev.dwhipstock.pos.payments.terminal.TerminalException
import dev.dwhipstock.pos.sdk.PaymentTerminalConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** One raw HTTP exchange with J.P. Morgan. Tests replace it with a fake. */
fun interface JpmHttp {
    /** Throws [IOException] when J.P. Morgan can't be reached; any HTTP answer is returned. */
    fun send(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String>
}

/** JDK [HttpURLConnection] transport (no extra dependency; builds into the tablet APK). */
class UrlJpmHttp(private val connectTimeoutMs: Int = 5_000, private val readTimeoutMs: Int = 20_000) : JpmHttp {
    override fun send(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            // HttpURLConnection has no PATCH (J.P. Morgan's void is PATCH /payments/{id}):
            // send POST with the override header. TODO(owner access): confirm on the
            // test host that it honours the override; a failed void only means the
            // authorization lapses on its own, never a charge.
            if (method == "PATCH") {
                c.requestMethod = "POST"
                c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else c.requestMethod = method
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = c.responseCode
            val stream = if (status in 200..399) c.inputStream else c.errorStream
            return status to (stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "")
        } finally {
            c.disconnect()
        }
    }
}

/**
 * J.P. Morgan **Online Payments** (Commerce, API v2) over HTTPS — sandbox only.
 * Documented calls (developer.payments.jpmorgan.com → Online Payments):
 *  - `POST /payments` with `captureMethod: MANUAL` → `transactionState: AUTHORIZED`
 *  - `POST /payments/{id}/captures` `{captureMethod: NOW}` → `CLOSED`
 *  - `PATCH /payments/{id}` `{isVoid: true}` → `VOIDED`
 *  - `POST /refunds` `{paymentMethodType.transactionReference.transactionReferenceId}`
 * Headers on every call: `Authorization: Bearer`, `merchant-id`, `request-id`
 * (unique; derived from our idempotency key, so a retry reuses it).
 *
 * Hosts: the mock `https://api-mock.payments.jpmorgan.com/api/v2` (the
 * default: stateless canned answers, no auth check, can't decline) or the
 * client-testing host `https://api-ms-test.payments.jpmorgan.com/api/v2`, which
 * needs the certificate-based credentials J.P. Morgan issues at onboarding
 * (docs/payments-terminals.md). Production hosts are refused.
 *
 * The OAuth client-credentials token is cached until a minute before it
 * expires. Neither the secret nor the token is ever logged or stored.
 */
class JpmOnlineHttp(
    private val creds: PaymentTerminalConfig.JpmCredentials,
    val baseUrl: String = creds.baseUrl?.trimEnd('/') ?: MOCK_BASE,
    private val http: JpmHttp = UrlJpmHttp(),
    private val clock: () -> Long = System::currentTimeMillis,
) : JpmOnlineApi {
    companion object {
        const val MOCK_BASE = "https://api-mock.payments.jpmorgan.com/api/v2"
        const val TEST_BASE = "https://api-ms-test.payments.jpmorgan.com/api/v2"
        /** The merchant id in J.P. Morgan's own spec examples; the mock accepts any. */
        const val MOCK_MERCHANT_ID = "998804938256"

        /** null when there's nothing to talk to (no credentials and not the mock). */
        fun from(creds: PaymentTerminalConfig.JpmCredentials): JpmOnlineHttp? {
            val base = creds.baseUrl?.trimEnd('/') ?: MOCK_BASE
            if (base != MOCK_BASE && !creds.complete) return null
            return JpmOnlineHttp(creds, base)
        }

        /**
         * Documented US sandbox test cards. Declines are triggered by amount on
         * specific cards: 52100 → INSUFFICIENT_FUNDS (Mastercard …5114),
         * 53000 → DO_NOT_HONOR (Visa …4113). Any other amount approves.
         */
        fun sandboxCard(brand: String, scenario: String): JpmTestCard = when (scenario) {
            "insufficient_funds" -> JpmTestCard("5112345112345114", 12, 2030, "Mastercard", triggerAmountCents = 52100)
            "do_not_honour", "do_not_honor" -> JpmTestCard("4112344112344113", 12, 2030, "Visa", triggerAmountCents = 53000)
            else -> when (brand.lowercase()) {
                "mastercard" -> JpmTestCard("5112345112345114", 12, 2030, "Mastercard")
                "amex" -> JpmTestCard("371144371144376", 12, 2030, "Amex")
                "discover" -> JpmTestCard("6011016011016011", 12, 2030, "Discover")
                else -> JpmTestCard("4112344112344113", 12, 2030, "Visa")
            }
        }
    }

    init {
        require(baseUrl == MOCK_BASE || baseUrl.startsWith("https://api-ms-test.") || baseUrl.startsWith("http://127.0.0.1") ||
            baseUrl.startsWith("http://localhost")) {
            "J.P. Morgan: sandbox hosts only (got $baseUrl)"
        }
    }

    override val label: String get() = if (baseUrl == MOCK_BASE) "J.P. Morgan sandbox (mock)" else "J.P. Morgan sandbox"
    override val canDecline: Boolean get() = baseUrl != MOCK_BASE
    override fun testCard(brand: String, scenario: String) = sandboxCard(brand, scenario)

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var token: Pair<String, Long>? = null
    private val merchantId: String get() = creds.merchantId ?: if (baseUrl == MOCK_BASE) MOCK_MERCHANT_ID
        else throw TerminalException(409, "jpm_merchant_id_missing", "set JPM_MERCHANT_ID (payment.jpmorgan.merchantId)")

    /** Client-credentials token, cached. null = no credentials (the mock doesn't check). */
    private fun bearer(): String? {
        token?.takeIf { clock() < it.second }?.let { return it.first }
        if (!creds.complete) return null
        val form = listOfNotNull(
            "grant_type" to "client_credentials",
            "client_id" to creds.clientId!!,
            "client_secret" to creds.secret()!!,
            creds.scope?.let { "scope" to it },
        ).joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }
        val (status, body) = try {
            http.send("POST", creds.tokenUrl!!, mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"), form)
        } catch (e: IOException) {
            throw TerminalException(503, TerminalException.UNAVAILABLE, "J.P. Morgan sign-in unreachable (${e.javaClass.simpleName})", cause = e)
        }
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val access = o?.get("access_token")?.jsonPrimitive?.contentOrNull
        if (status !in 200..299 || access == null)
            throw TerminalException(502, "jpm_auth_failed", "J.P. Morgan sign-in refused (HTTP $status)")
        val ttl = o["expires_in"]?.jsonPrimitive?.longOrNull ?: 300
        token = access to (clock() + (ttl - 60).coerceAtLeast(30) * 1000)
        return access
    }

    private fun call(method: String, path: String, body: JsonObject?, key: String): JsonObject {
        val headers = buildMap {
            put("Content-Type", "application/json")
            put("Accept", "application/json")
            put("merchant-id", merchantId)
            // unique per operation, stable across retries of it
            put("request-id", UUID.nameUUIDFromBytes(key.toByteArray()).toString())
            bearer()?.let { put("Authorization", "Bearer $it") }
        }
        val (status, text) = try {
            http.send(method, baseUrl + path, headers, body?.toString())
        } catch (e: IOException) {
            throw TerminalException(503, TerminalException.UNAVAILABLE, "J.P. Morgan unreachable (${e.javaClass.simpleName})", cause = e)
        }
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (status in 200..299 && o != null) return o
        val msg = o?.let { str(it, "responseMessage") ?: str(it, "message") }
            ?: o?.get("responseStatus")?.toString() ?: "HTTP $status"
        if (status >= 500 || status == 429) throw TerminalException(503, TerminalException.UNAVAILABLE, "J.P. Morgan temporarily unavailable: $msg")
        if (status == 401 || status == 403) throw TerminalException(502, "jpm_not_entitled", "J.P. Morgan refused these credentials (HTTP $status): $msg")
        throw TerminalException(502, "jpm_error", "J.P. Morgan error $status: $msg")
    }

    private fun str(o: JsonObject, k: String) = o[k]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private fun merchantBlock() = buildJsonObject {
        putJsonObject("merchantSoftware") {
            put("companyName", "Copper Lantern POS demo")
            put("productName", "POS")
            put("version", "0.1")
        }
    }

    private fun toPayment(o: JsonObject, ok: (String?) -> Boolean) = JpmPayment(
        transactionId = str(o, "transactionId") ?: "",
        approved = str(o, "responseStatus") == "SUCCESS" && ok(str(o, "transactionState")),
        state = str(o, "transactionState"),
        responseCode = str(o, "responseCode"),
        message = str(o, "responseMessage"),
        approvalCode = str(o, "approvalCode"),
    )

    override fun authorize(requestId: String, amountCents: Long, currency: String, card: JpmTestCard, orderRef: String): JpmPayment {
        val body = buildJsonObject {
            put("captureMethod", "MANUAL")
            put("amount", amountCents)
            put("currency", currency.uppercase())
            put("merchantOrderNumber", orderRef.filter(Char::isLetterOrDigit).take(20))
            put("merchant", merchantBlock())
            putJsonObject("paymentMethodType") {
                putJsonObject("card") {
                    put("accountNumber", card.number)
                    putJsonObject("expiry") { put("month", card.expiryMonth); put("year", card.expiryYear) }
                }
            }
            put("initiatorType", "CARDHOLDER")
            put("accountOnFile", "NOT_STORED")
            put("isAmountFinal", true)
        }
        return toPayment(call("POST", "/payments", body, requestId)) { it == "AUTHORIZED" }
    }

    override fun capture(transactionId: String, amountCents: Long, requestId: String): JpmPayment =
        toPayment(call("POST", "/payments/$transactionId/captures", buildJsonObject { put("captureMethod", "NOW") }, requestId)) {
            it == "CLOSED" || it == "COMPLETED"
        }

    override fun void(transactionId: String, requestId: String): JpmPayment =
        toPayment(call("PATCH", "/payments/$transactionId", buildJsonObject { put("isVoid", true) }, requestId)) { it == "VOIDED" }

    override fun refund(transactionId: String, amountCents: Long, currency: String, requestId: String): JpmPayment {
        val body = buildJsonObject {
            put("merchant", merchantBlock())
            put("amount", amountCents)
            put("currency", currency.uppercase())
            putJsonObject("paymentMethodType") {
                putJsonObject("transactionReference") { put("transactionReferenceId", transactionId) }
            }
        }
        return toPayment(call("POST", "/refunds", body, requestId)) { it != "DECLINED" && it != "ERROR" }
    }

    override fun toString() = "JpmOnlineHttp($baseUrl)"
}
