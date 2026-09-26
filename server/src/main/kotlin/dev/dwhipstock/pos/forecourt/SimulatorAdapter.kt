package dev.dwhipstock.pos.forecourt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration

/**
 * [ForecourtAdapter] for the forecourt simulator (`forecourt/simulator`): its
 * FDC-like JSON API over HTTP on the store's LAN (`/fdc/v1/…`, see
 * docs/forecourt.md). Each FDC message is one request; a real FDC adapter would
 * send the same messages as IFSF XML over its TCP channels instead.
 *
 * Timeouts are short on purpose: the store polls several times a second and a
 * cashier's tap must fail fast when the controller is down.
 */
class SimulatorAdapter(
    baseUrl: String,
    private val connectTimeout: Duration = Duration.ofMillis(800),
    private val requestTimeout: Duration = Duration.ofMillis(1500),
) : ForecourtAdapter {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    override val description: String get() = "forecourt simulator at $base"

    override fun snapshot(): ForecourtSnapshot {
        val o = call("GET", "/fdc/v1/status")
        return ForecourtSnapshot(
            pumps = o.arr("pumps").map { pump(it.jsonObject) }.sortedBy { it.pump },
            transactions = o.arr("transactions").map { trx(it.jsonObject) },
            grades = o.arr("grades").map { g ->
                val go = g.jsonObject
                FuelGrade(go.str("grade") ?: "?", go.str("name") ?: "?", go.str("nameEs") ?: go.str("name") ?: "?",
                    go.long("priceMills") ?: 0)
            },
        )
    }

    override fun authorise(pump: Int, mode: FuelMode, maxAmountCents: Long?, posRef: String): String {
        val o = call("POST", "/fdc/v1/pumps/$pump/authorise", buildJsonObject {
            put("mode", mode.name)
            maxAmountCents?.let { put("maxAmountCents", it) }
            put("posRef", posRef)
        })
        return o.str("authId") ?: throw ForecourtUnavailable("authorise: no authId in the reply")
    }

    override fun free(pump: Int) { call("POST", "/fdc/v1/pumps/$pump/free") }
    override fun stop(pump: Int) { call("POST", "/fdc/v1/pumps/$pump/stop") }
    override fun resume(pump: Int) { call("POST", "/fdc/v1/pumps/$pump/resume") }
    override fun emergencyStop(pump: Int?) {
        call("POST", if (pump == null) "/fdc/v1/emergency-stop" else "/fdc/v1/pumps/$pump/emergency-stop")
    }
    override fun reset(pump: Int) { call("POST", "/fdc/v1/pumps/$pump/reset") }

    override fun lock(trxId: String, posRef: String): FuelTrx =
        trx(call("POST", "/fdc/v1/transactions/${enc(trxId)}/lock", buildJsonObject { put("posRef", posRef) }).obj("transaction"))

    override fun unlock(trxId: String): FuelTrx =
        trx(call("POST", "/fdc/v1/transactions/${enc(trxId)}/unlock").obj("transaction"))

    override fun clear(trxId: String): FuelTrx =
        trx(call("POST", "/fdc/v1/transactions/${enc(trxId)}/clear").obj("transaction"))

    override fun setPrices(prices: Map<String, Long>) {
        call("POST", "/fdc/v1/prices", buildJsonObject {
            put("prices", buildJsonArray {
                prices.forEach { (grade, mills) -> add(buildJsonObject { put("grade", grade); put("priceMills", mills) }) }
            })
        })
    }

    // ---- wire ----

    // HttpURLConnection, not java.net.http: the same store code runs embedded on
    // Android, which has no java.net.http.
    private fun call(method: String, path: String, body: JsonObject? = null): JsonObject {
        val (status, text) = try {
            val conn = URL(base + path).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = connectTimeout.toMillis().toInt()
                conn.readTimeout = requestTimeout.toMillis().toInt()
                conn.requestMethod = method
                conn.setRequestProperty("Accept", "application/json")
                if (method != "GET") {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write((body ?: JsonObject(emptyMap())).toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                code to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
            } finally {
                conn.disconnect()
            }
        } catch (e: java.io.IOException) {
            throw ForecourtUnavailable("${e.javaClass.simpleName}: ${e.message ?: "no answer"}", e)
        }
        val parsed = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        if (status in 200..299) {
            return parsed ?: throw ForecourtUnavailable("HTTP $status: not JSON")
        }
        val err = parsed?.get("error") as? JsonObject
        val code = err?.str("code")
        if (status in 400..499 && code != null) {
            throw ForecourtRefused(code, err.str("message") ?: code)
        }
        throw ForecourtUnavailable("HTTP $status${code?.let { " $it" } ?: ""}")
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun pump(o: JsonObject): Pump {
        val auth = o["authorisation"] as? JsonObject
        val cur = o["current"] as? JsonObject
        val display = o["display"] as? JsonObject
        return Pump(
            pump = o.int("pump") ?: 0,
            state = o.str("state")?.let { s -> PumpState.entries.firstOrNull { it.name == s } } ?: PumpState.ERROR,
            nozzleUp = o.int("nozzleUp"),
            flowing = o.bool("flowing") ?: false,
            authorisation = auth?.let {
                PumpAuthorisation(it.str("authId") ?: "", mode(it.str("mode")), it.long("maxAmountCents"), it.str("posRef"))
            },
            current = cur?.let {
                LiveSale(it.str("trxId") ?: "", it.int("nozzle"), it.str("grade"), it.long("priceMills") ?: 0,
                    it.long("volumeMilli") ?: 0, it.long("amountCents") ?: 0, it.long("maxAmountCents"),
                    it.bool("limitReached") ?: false)
            },
            displayGrade = display?.str("grade"),
            displayPriceMills = display?.long("priceMills") ?: 0,
            displayVolumeMilli = display?.long("volumeMilli") ?: 0,
            displayAmountCents = display?.long("amountCents") ?: 0,
            error = o.str("error"),
        )
    }

    private fun trx(o: JsonObject) = FuelTrx(
        trxId = o.str("trxId") ?: "",
        pump = o.int("pump") ?: 0,
        nozzle = o.int("nozzle"),
        grade = o.str("grade") ?: "?",
        gradeName = o.str("gradeName") ?: o.str("grade") ?: "?",
        priceMills = o.long("priceMills") ?: 0,
        volumeMilli = o.long("volumeMilli") ?: 0,
        amountCents = o.long("amountCents") ?: 0,
        state = o.str("state")?.let { s -> TrxState.entries.firstOrNull { it.name == s } } ?: TrxState.PAYABLE,
        mode = mode(o.str("mode")),
        maxAmountCents = o.long("maxAmountCents"),
        posRef = o.str("posRef"),
        lockedBy = o.str("lockedBy"),
        reason = o.str("reason"),
    )

    private fun mode(s: String?) = if (s == "PREPAY") FuelMode.PREPAY else FuelMode.POSTPAY
}

private fun JsonObject.prim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }
private fun JsonObject.str(key: String): String? = prim(key)?.contentOrNull
private fun JsonObject.long(key: String): Long? = prim(key)?.longOrNull
private fun JsonObject.int(key: String): Int? = prim(key)?.intOrNull
private fun JsonObject.bool(key: String): Boolean? = prim(key)?.booleanOrNull
private fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray) ?: emptyList()
private fun JsonObject.obj(key: String): JsonObject =
    this[key] as? JsonObject ?: throw ForecourtUnavailable("no '$key' in the reply")
