package dev.dwhipstock.pos.payments.jpm

import dev.dwhipstock.pos.payments.terminal.CardDetails
import dev.dwhipstock.pos.payments.terminal.EntryMode
import dev.dwhipstock.pos.payments.terminal.Outcome
import dev.dwhipstock.pos.payments.terminal.PaymentRequest
import dev.dwhipstock.pos.payments.terminal.PaymentResult
import dev.dwhipstock.pos.payments.terminal.PaymentTerminal
import dev.dwhipstock.pos.payments.terminal.ReaderState
import dev.dwhipstock.pos.payments.terminal.ReaderStatus
import dev.dwhipstock.pos.payments.terminal.TerminalException
import dev.dwhipstock.pos.payments.terminal.TerminalKind
import dev.dwhipstock.pos.payments.terminal.TerminalPayment
import dev.dwhipstock.pos.payments.terminal.TerminalRefundRequest
import dev.dwhipstock.pos.payments.terminal.TerminalRefundResult
import dev.dwhipstock.pos.payments.terminal.TipMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.net.SocketFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** One persistent connection to the Payment Terminal Application: JSON text messages. */
interface JpmConnection : Closeable {
    val open: Boolean
    fun send(json: String)
    /** Next message, or null if none within [timeoutMs]. Throws [IOException] when the link drops. */
    fun receive(timeoutMs: Int): String?
}

fun interface JpmConnector {
    fun open(host: String, port: Int): JpmConnection
}

/**
 * J.P. Morgan in-store (card-present) payments: the **Payment Terminal
 * Application** on a J.P. Morgan terminal (Ingenico AXIUM), reached over the
 * store LAN. Written only against J.P. Morgan's public docs
 * (developer.payments.jpmorgan.com → In-Store Payments → Payment Terminal
 * Application; summarised in docs/payments-terminals.md):
 *
 *  - transport: JSON messages over one persistent WebSocket, secured by TLS
 *    (mutual TLS supported), `wss://<terminal-ip>:<port>`. The docs give port
 *    8442 in the simulator how-to and 8443 in its code sample → configurable,
 *    default [DEFAULT_PORT].
 *  - "complex" operations (`Transaction`) answer with several `Status`
 *    notifications, then one final message with the same `operation`; only one
 *    operation runs at a time (result 5 = busy).
 *  - `{"operation":"Transaction","type":"SALE","requestedAmount":"<cents>"}`,
 *    `REFUND`, `VOID` (+ `originalAuthCode`), `{"operation":"Cancel"}`,
 *    `{"operation":"LastTransaction"}` after a dropped connection,
 *    `{"operation":"GetInformation"}` for status. Tip prompt: `parameters`
 *    `[{"key":"TIP","value":"0|1"}]`.
 *  - `result` "0" = the operation completed (not "approved": that's
 *    `approval`); 10 timeout, 11 cancelled by user, 12 cancelled by POS,
 *    13 cancel not available, 17 / 19 declined by card / host, 5 busy, 83
 *    missing credentials.
 *
 * NOT selected by default: `payment.terminal=jpmorgan` + `payment.jpmorgan.mode=instore`.
 * TODO(owner access): this needs a J.P. Morgan test terminal with the Payment
 *   Terminal Application (from the Integration Specialist), its IP, and the
 *   TLS trust material (terminal CA → payment.jpmorgan.truststore; optional
 *   client cert for mutual TLS → payment.jpmorgan.keystore). Untested against a
 *   real terminal: nothing here has been run beyond the fake terminal in tests.
 */
class JpmInStoreAdapter(
    private val host: String?,
    private val port: Int = DEFAULT_PORT,
    private val connector: JpmConnector,
    private val timeoutSeconds: Int = 90,
) : PaymentTerminal {
    override val kind = TerminalKind.JPMORGAN

    companion object {
        const val DEFAULT_PORT = 8442
        private val log = LoggerFactory.getLogger(JpmInStoreAdapter::class.java)
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * wss socket factory from PKCS#12 files. The truststore (the terminal's
         * CA, from J.P. Morgan) is required: no "trust all" mode, ever.
         */
        fun tlsFactory(truststore: String?, truststorePassword: String?, keystore: String?, keystorePassword: String?): SocketFactory {
            val ts = truststore ?: throw TerminalException(409, "jpm_truststore_missing",
                "J.P. Morgan terminal TLS needs payment.jpmorgan.truststore (the terminal's CA certificate)")
            fun load(path: String, pw: String?) = KeyStore.getInstance("PKCS12").apply {
                File(path).inputStream().use { load(it, pw?.toCharArray()) }
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(load(ts, truststorePassword)) }
            val kms = keystore?.let { ks ->
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                    init(load(ks, keystorePassword), keystorePassword?.toCharArray())
                }.keyManagers
            }
            return SSLContext.getInstance("TLS").apply { init(kms, tmf.trustManagers, null) }.socketFactory
        }

        /** The real connector: TLS WebSocket to the terminal. */
        fun wssConnector(factory: () -> SocketFactory) = JpmConnector { h, p ->
            val ws = MiniWebSocket.connect(h, p, "/", factory())
            object : JpmConnection {
                override val open get() = ws.open
                override fun send(json: String) = ws.send(json)
                override fun receive(timeoutMs: Int) = ws.receive(timeoutMs)
                override fun close() = ws.close()
            }
        }

        fun str(o: JsonObject, k: String) = o[k]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

        /** A final `Transaction` response as a [PaymentResult]. */
        fun toResult(ref: String, o: JsonObject): PaymentResult {
            val result = str(o, "result")
            val approved = str(o, "approval").equals("approved", ignoreCase = true)
            val outcome = when {
                result == "0" && approved -> Outcome.APPROVED
                result == "0" || result == "17" || result == "19" || result == "26" || result == "28" -> Outcome.DECLINED
                result == "10" -> Outcome.TIMEOUT
                result == "11" || result == "12" -> Outcome.CANCELLED
                else -> Outcome.ERROR
            }
            fun cents(k: String) = str(o, k)?.toLongOrNull()
            return PaymentResult(
                terminalRef = ref, outcome = outcome, rawStatus = "result=$result approval=${str(o, "approval")}",
                amountCents = cents("totalAmount") ?: cents("authorizedAmount"),
                tipCents = cents("tipAmount") ?: 0,
                captured = outcome == Outcome.APPROVED, // a SALE settles in the batch; no separate capture
                card = cardOf(o),
                declineCode = if (outcome == Outcome.DECLINED) (str(o, "responseCode")?.let { "host_$it" } ?: "result_$result") else null,
                message = str(o, "hostMessage") ?: str(o, "errorMessage"),
                reference = str(o, "uniqueTransactionId")?.takeIf { it.isNotBlank() },
            )
        }

        fun cardOf(o: JsonObject): CardDetails? {
            val account = str(o, "account") ?: return null
            val entry = when (str(o, "entryMode")?.lowercase()) {
                "contactless" -> EntryMode.TAP
                "chip" -> EntryMode.INSERT
                "swiped", "chip_swiped" -> EntryMode.SWIPE
                "manual" -> EntryMode.KEYED
                else -> EntryMode.UNKNOWN
            }
            return CardDetails(
                brand = str(o, "cardBrand")?.let(dev.dwhipstock.pos.payments.StripeTerminalAdapter::brandLabel),
                last4 = account.takeLast(4).takeIf { it.all(Char::isDigit) },
                entryMode = entry, authCode = str(o, "authCode")?.takeIf { it.isNotBlank() },
                aid = str(o, "AID")?.takeIf { it.isNotBlank() }, tvr = str(o, "TVR")?.takeIf { it.isNotBlank() },
                tsi = str(o, "TSI")?.takeIf { it.isNotBlank() }, appLabel = str(o, "preferredName")?.takeIf { it.isNotBlank() },
                cvm = str(o, "cvm"), tipCents = str(o, "tipAmount")?.toLongOrNull() ?: 0,
                processorRef = str(o, "transactionID")?.takeIf { it.isNotBlank() },
            )
        }

        /** `uniqueTransactionId`: alphanumeric, up to 30 characters. */
        fun uniqueId(reference: String) = reference.filter(Char::isLetterOrDigit).take(30)
    }

    /** One sale at a time, like the terminal. */
    private class Op(val ref: String, @Volatile var final: JsonObject? = null, @Volatile var prompt: String = "present_card",
                     @Volatile var error: String? = null, @Volatile var cancelAnswer: JsonObject? = null)

    private val ops = ConcurrentHashMap<String, Op>()
    @Volatile private var conn: JpmConnection? = null
    private val connLock = Any()

    private fun requireHost() = host ?: throw TerminalException(409, TerminalException.NOT_PAIRED,
        "set payment.terminal.host to the J.P. Morgan terminal's IP")

    private fun connection(): JpmConnection = synchronized(connLock) {
        conn?.takeIf { it.open }?.let { return it }
        val c = try {
            connector.open(requireHost(), port)
        } catch (e: TerminalException) {
            throw e
        } catch (e: Exception) {
            throw TerminalException(503, TerminalException.UNAVAILABLE, "J.P. Morgan terminal at $host:$port can't be reached (${e.javaClass.simpleName})", cause = e)
        }
        conn = c
        c
    }

    private fun drop() = synchronized(connLock) { runCatching { conn?.close() }; conn = null }

    /** A simple operation: one request, one response with the same `operation`. */
    private fun simple(request: JsonObject, waitMs: Int = 10_000): JsonObject {
        val op = request["operation"]!!.jsonPrimitive.content
        val c = connection()
        try {
            c.send(request.toString())
            val deadline = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < deadline) {
                val msg = c.receive((deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)) ?: continue
                val o = runCatching { json.parseToJsonElement(msg).jsonObject }.getOrNull() ?: continue
                if (str(o, "operation") == op) return o
            }
            throw TerminalException(503, TerminalException.UNAVAILABLE, "J.P. Morgan terminal did not answer $op")
        } catch (e: IOException) {
            drop()
            throw TerminalException(503, TerminalException.UNAVAILABLE, "lost the J.P. Morgan terminal (${e.message})", cause = e)
        }
    }

    override fun status(): ReaderStatus {
        if (host == null) return ReaderStatus(ReaderState.NOT_PAIRED, "J.P. Morgan terminal", reason = TerminalException.NOT_PAIRED)
        if (ops.values.any { it.final == null && it.error == null }) return ReaderStatus(ReaderState.BUSY, "J.P. Morgan terminal", "$host:$port")
        return try {
            val info = simple(buildJsonObject { put("operation", "GetInformation") }, 5_000)
            val i = info["information"]?.let { runCatching { it.jsonObject }.getOrNull() }
            val name = listOfNotNull(i?.let { str(it, "model") }, i?.let { str(it, "serialNumber") }).joinToString(" ").ifBlank { "J.P. Morgan terminal" }
            if (str(info, "result") == "0") ReaderStatus(ReaderState.IDLE, name, "$host:$port")
            else ReaderStatus(ReaderState.OFFLINE, name, "$host:$port", "jpm_result_${str(info, "result")}")
        } catch (e: TerminalException) {
            ReaderStatus(ReaderState.OFFLINE, "J.P. Morgan terminal", "$host:$port", e.code)
        }
    }

    override fun connect(host: String?, pairingCode: String?) = status()

    override fun startPayment(request: PaymentRequest): TerminalPayment {
        val ref = uniqueId(request.reference)
        ops[ref]?.let { return TerminalPayment(ref, null, request.amountCents) }
        if (ops.values.any { it.final == null && it.error == null })
            throw TerminalException(409, TerminalException.BUSY, "the J.P. Morgan terminal is busy")
        val c = connection()
        val msg = buildJsonObject {
            put("operation", "Transaction")
            put("type", "SALE")
            put("requestedAmount", request.amountCents.toString())
            put("uniqueTransactionId", ref)
            put("printReceipt", "0")
            put("parameters", buildJsonArray {
                add(buildJsonObject { put("key", "TIP"); put("value", if (request.tipMode == TipMode.ON_READER) "1" else "0") })
            })
        }
        val op = Op(ref)
        ops[ref] = op
        try {
            c.send(msg.toString())
        } catch (e: IOException) {
            ops.remove(ref); drop()
            throw TerminalException(503, TerminalException.UNAVAILABLE, "lost the J.P. Morgan terminal (${e.message})", cause = e)
        }
        // the final answer comes after the customer pays: wait for it off the request thread
        Thread({ await(c, op) }, "jpm-sale-$ref").apply { isDaemon = true }.start()
        return TerminalPayment(ref, null, request.amountCents)
    }

    private fun await(c: JpmConnection, op: Op) {
        val deadline = System.currentTimeMillis() + (timeoutSeconds + 60) * 1000L
        try {
            while (System.currentTimeMillis() < deadline && op.final == null) {
                val msg = c.receive(1_000) ?: continue
                val o = runCatching { json.parseToJsonElement(msg).jsonObject }.getOrNull() ?: continue
                when (str(o, "operation")) {
                    "Status" -> op.prompt = if (str(o, "context").equals("Communication", true)) "processing" else "present_card"
                    "Transaction" -> op.final = o
                    // this thread is the only reader while the sale runs: [cancel] waits for this
                    "Cancel" -> op.cancelAnswer = o
                }
            }
            if (op.final == null) op.error = "no answer from the J.P. Morgan terminal"
        } catch (e: IOException) {
            drop()
            // docs: reconnect and ask for LastTransaction; match it by our uniqueTransactionId
            op.final = runCatching { simple(buildJsonObject { put("operation", "LastTransaction") }) }.getOrNull()
                ?.takeIf { str(it, "uniqueTransactionId") == op.ref }
            if (op.final == null) op.error = "lost the J.P. Morgan terminal mid-payment (${e.message}); check it before retrying"
        }
    }

    override fun result(terminalRef: String): PaymentResult {
        val op = ops[terminalRef] ?: return PaymentResult(terminalRef, Outcome.ERROR, message = "unknown J.P. Morgan payment (store restarted?)")
        op.final?.let { return toResult(terminalRef, it) }
        op.error?.let { return PaymentResult(terminalRef, Outcome.ERROR, message = it) }
        return PaymentResult(terminalRef, Outcome.PENDING, readerPrompt = op.prompt)
    }

    override fun capture(terminalRef: String, idempotencyKey: String) = result(terminalRef)

    override fun cancel(terminalRef: String, idempotencyKey: String): PaymentResult {
        val cur = result(terminalRef)
        val op = ops[terminalRef]
        if (cur.outcome == Outcome.PENDING && op != null) {
            // the sale's own thread reads the answers (one reader per connection); we only write
            op.cancelAnswer = null
            try {
                connection().send(buildJsonObject { put("operation", "Cancel") }.toString())
            } catch (e: IOException) {
                drop()
                throw TerminalException(503, TerminalException.UNAVAILABLE, "lost the J.P. Morgan terminal (${e.message})", cause = e)
            }
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && op.cancelAnswer == null && op.final == null) Thread.sleep(20)
            if (op.cancelAnswer?.let { str(it, "result") } == "13")
                throw TerminalException(409, "terminal_cancel_unavailable", "the card is being processed; it can't be cancelled now")
            while (System.currentTimeMillis() < deadline && op.final == null) Thread.sleep(20)
            return result(terminalRef).let { if (it.outcome == Outcome.PENDING) it.copy(outcome = Outcome.CANCELLED) else it }
        }
        if (cur.outcome == Outcome.APPROVED) {
            val auth = cur.card?.authCode ?: throw TerminalException(409, "jpm_void_no_auth_code", "no auth code to void")
            val v = simple(buildJsonObject {
                put("operation", "Transaction"); put("type", "VOID"); put("originalAuthCode", auth)
            }, timeoutSeconds * 1000)
            val approved = str(v, "result") == "0" && str(v, "approval").equals("approved", true)
            if (!approved) throw TerminalException(502, "jpm_void_failed", str(v, "hostMessage") ?: "void refused; refund it instead")
            return cur.copy(outcome = Outcome.CANCELLED, rawStatus = "voided")
        }
        return cur
    }

    /**
     * REFUND is card-present: the terminal prompts for the card. TODO(owner
     * access): the docs also show a tokenized refund (`cardToken` + `expDate` +
     * `storedCredential`) and advise limiting refunds to existing
     * transactions; confirm with J.P. Morgan which one this merchant setup uses.
     */
    override fun refund(request: TerminalRefundRequest): TerminalRefundResult {
        val amount = request.amountCents ?: throw TerminalException(409, "jpm_refund_amount_required", "J.P. Morgan refunds need an amount")
        val r = simple(buildJsonObject {
            put("operation", "Transaction"); put("type", "REFUND")
            put("requestedAmount", amount.toString())
            put("uniqueTransactionId", uniqueId(request.idempotencyKey))
            put("printReceipt", "0")
        }, (timeoutSeconds + 30) * 1000)
        val ok = str(r, "result") == "0" && str(r, "approval").equals("approved", true)
        log.info("J.P. Morgan refund ${str(r, "transactionID")}: result ${str(r, "result")} ${str(r, "approval")}")
        return TerminalRefundResult(str(r, "transactionID") ?: uniqueId(request.idempotencyKey),
            if (ok) Outcome.APPROVED else Outcome.DECLINED, "result=${str(r, "result")}", amount, str(r, "hostMessage"))
    }
}
