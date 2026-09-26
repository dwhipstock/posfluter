package dev.dwhipstock.pos.payments.simulator

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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** How the store reaches a simulator: in-process (built-in) or over the LAN (stand-alone). */
interface SimulatorLink {
    val embedded: Boolean
    val address: String?
    fun status(): SimStatusView
    fun pair(code: String): SimPairResponse
    fun start(req: SimStartRequest): SimTxnView
    fun get(id: String): SimTxnView
    fun cancel(id: String): SimTxnView
    fun capture(id: String): SimTxnView
    fun refund(id: String, req: SimRefundRequest): SimRefundView
    fun host(id: String, req: SimHostRequest): SimTxnView
}

/** The built-in simulator, called directly. */
class InProcessSimulatorLink(val device: SimulatedTerminalDevice) : SimulatorLink {
    override val embedded = true
    override val address: String? = null
    override fun status() = device.status()
    override fun pair(code: String) = SimPairResponse(device.pair(code), device.terminalId, device.name)
    override fun start(req: SimStartRequest) =
        device.start(req.reference, req.amountCents, req.currency, req.tipOnReader, req.timeoutSeconds, req.label, req.hostAuthorization)
    override fun host(id: String, req: SimHostRequest) = device.hostResponse(id, req.toDecision())
    override fun get(id: String) = device.get(id)
    override fun cancel(id: String) = device.cancel(id)
    override fun capture(id: String) = device.capture(id)
    override fun refund(id: String, req: SimRefundRequest) = device.refund(id, req.amountCents, req.key)
}

/**
 * The stand-alone simulator on another machine (the Mac), over plain HTTP on
 * the store LAN, the way a countertop terminal is reached by IP. Short
 * timeouts: an unreachable terminal is a 503 terminal_unavailable in a few
 * seconds and nothing else in the POS waits on it.
 */
class HttpSimulatorLink(
    val host: String,
    val port: Int,
    private val token: () -> String?,
    private val connectTimeoutMs: Int = 3_000,
    private val readTimeoutMs: Int = 8_000,
) : SimulatorLink {
    override val embedded = false
    override val address: String get() = "$host:$port"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun status() = call("GET", "/api/status", null, SimStatusView.serializer())
    override fun pair(code: String) = call("POST", "/api/pair",
        json.encodeToString(SimPairRequest.serializer(), SimPairRequest(code)), SimPairResponse.serializer())
    override fun start(req: SimStartRequest) = call("POST", "/api/transactions",
        json.encodeToString(SimStartRequest.serializer(), req), SimTxnView.serializer())
    override fun get(id: String) = call("GET", "/api/transactions/$id", null, SimTxnView.serializer())
    override fun cancel(id: String) = call("POST", "/api/transactions/$id/cancel", "{}", SimTxnView.serializer())
    override fun capture(id: String) = call("POST", "/api/transactions/$id/capture", "{}", SimTxnView.serializer())
    override fun refund(id: String, req: SimRefundRequest) = call("POST", "/api/transactions/$id/refunds",
        json.encodeToString(SimRefundRequest.serializer(), req), SimRefundView.serializer())
    override fun host(id: String, req: SimHostRequest) = call("POST", "/api/transactions/$id/host",
        json.encodeToString(SimHostRequest.serializer(), req), SimTxnView.serializer())

    private fun <T> call(method: String, path: String, body: String?, serializer: KSerializer<T>): T {
        val connection = try {
            URL("http://$host:$port$path").openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw unreachable(e)
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Accept", "application/json")
            token()?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (status in 200..299) return json.decodeFromString(serializer, text)
            val err = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            val code = err?.get("code")?.jsonPrimitive?.contentOrNull ?: TerminalException.ERROR
            val msg = err?.get("error")?.jsonPrimitive?.contentOrNull ?: "terminal answered HTTP $status"
            throw TerminalException(if (status == 401) 409 else status, code, msg)
        } catch (e: IOException) {
            throw unreachable(e)
        } finally {
            connection.disconnect()
        }
    }

    private fun unreachable(e: IOException) = TerminalException(503, TerminalException.UNAVAILABLE,
        "card terminal at $host:$port can't be reached (${e.javaClass.simpleName})", cause = e)
}

/**
 * The simulator behind the [PaymentTerminal] contract. [link] may change at
 * runtime (pairing from the POS picks a new host), hence the provider.
 */
class SimulatorTerminal(
    private val link: () -> SimulatorLink?,
    private val timeoutSeconds: Int,
) : PaymentTerminal {
    override val kind = TerminalKind.SIMULATOR

    override fun status(): ReaderStatus {
        val l = link() ?: return ReaderStatus(ReaderState.NOT_PAIRED, "Card terminal (simulator)",
            reason = TerminalException.NOT_PAIRED)
        return try {
            val s = l.status()
            when {
                !s.paired && s.requiresPairing -> ReaderStatus(ReaderState.NOT_PAIRED, s.name, l.address,
                    TerminalException.NOT_PAIRED, l.embedded)
                s.state == "busy" -> ReaderStatus(ReaderState.BUSY, s.name, l.address, null, l.embedded)
                else -> ReaderStatus(ReaderState.IDLE, s.name, l.address, null, l.embedded)
            }
        } catch (e: TerminalException) {
            ReaderStatus(if (e.code == TerminalException.NOT_PAIRED) ReaderState.NOT_PAIRED else ReaderState.OFFLINE,
                "Card terminal (simulator)", l.address, e.code, l.embedded)
        }
    }

    /** Pairing is done by [dev.dwhipstock.pos.payments.TerminalPaymentService] (it keeps the token). */
    override fun connect(host: String?, pairingCode: String?): ReaderStatus = status()

    override fun startPayment(request: PaymentRequest): TerminalPayment {
        val l = requireLink()
        val txn = l.start(SimStartRequest(
            reference = request.reference, amountCents = request.amountCents, currency = request.currency.uppercase(),
            tipOnReader = request.tipMode == TipMode.ON_READER, timeoutSeconds = timeoutSeconds,
            label = request.description,
        ))
        return TerminalPayment(txn.id, null, request.amountCents)
    }

    override fun result(terminalRef: String) = toResult(requireLink().get(terminalRef))

    override fun capture(terminalRef: String, idempotencyKey: String): PaymentResult {
        val l = requireLink()
        val cur = l.get(terminalRef)
        return toResult(if (cur.captured) cur else l.capture(terminalRef))
    }

    override fun cancel(terminalRef: String, idempotencyKey: String) = toResult(requireLink().cancel(terminalRef))

    override fun refund(request: TerminalRefundRequest): TerminalRefundResult {
        val r = requireLink().refund(request.terminalRef, SimRefundRequest(request.amountCents, request.idempotencyKey))
        return TerminalRefundResult(r.refundId, if (r.approved) Outcome.APPROVED else Outcome.DECLINED,
            if (r.approved) "approved" else "declined", r.amountCents)
    }

    private fun requireLink(): SimulatorLink = link()
        ?: throw TerminalException(409, TerminalException.NOT_PAIRED, "no card terminal paired; pair one in Settings")

    companion object {
        fun toResult(t: SimTxnView): PaymentResult {
            val (outcome, prompt) = when (t.state) {
                "TIP" -> Outcome.PENDING to "choose_tip"
                "PRESENT_CARD" -> Outcome.PENDING to "present_card"
                "ENTER_PIN" -> Outcome.PENDING to "enter_pin"
                "PROCESSING" -> Outcome.PENDING to "processing"
                "APPROVED" -> Outcome.APPROVED to null
                "VOIDED", "CANCELLED" -> Outcome.CANCELLED to null
                "DECLINED" -> Outcome.DECLINED to null
                "TIMEOUT" -> Outcome.TIMEOUT to null
                else -> Outcome.ERROR to null
            }
            return PaymentResult(
                terminalRef = t.id, outcome = outcome, rawStatus = t.state,
                amountCents = t.totalCents, tipCents = t.tipCents, captured = t.captured,
                card = t.card, declineCode = t.declineCode, message = t.message,
                reference = t.reference, readerPrompt = prompt,
            )
        }
    }
}
