package dev.dwhipstock.pos.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * JDK/Android HTTP adapter for the CONTRACT.md wire — no extra dependencies.
 * Errors surface as !ok results or throws; CloudSync retries next tick.
 */
class HttpCloudTransport(baseUrl: String, private val apiKey: String) : CloudTransport {

    private val base = baseUrl.trimEnd('/')
    private data class Response(val status: Int, val bytes: ByteArray, val contentType: String?) {
        val text: String get() = bytes.toString(Charsets.UTF_8)
    }

    private fun request(
        path: String, method: String = "GET", body: ByteArray? = null,
        contentType: String? = null,
    ): Response {
        val connection = URL("$base$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            if (contentType != null) connection.setRequestProperty("Content-Type", contentType)
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val bytes = (if (status in 200..399) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes() } ?: byteArrayOf()
            return Response(status, bytes, connection.contentType)
        } finally {
            connection.disconnect()
        }
    }

    private fun postJson(path: String, body: String): Response =
        request(path, "POST", body.toByteArray(Charsets.UTF_8), "application/json")

    override fun push(installId: String, events: List<PushEvent>): PushResult {
        val body = buildJsonObject {
            put("installId", installId)
            putJsonArray("events") {
                events.forEach { e ->
                    addJsonObject {
                        put("eventId", e.eventId)
                        put("seq", e.seq)
                        put("eventType", e.eventType)
                        put("aggregateType", e.aggregateType)
                        put("aggregateId", e.aggregateId)
                        put("createdAt", e.createdAt)
                        put("payload", e.payload)
                    }
                }
            }
        }
        val res = postJson("/v1/ingest", body.toString())
        return if (res.status == 200) PushResult(true)
        // body carries the machine code (e.g. install_mismatch) — the loop keys off it
        else PushResult(false, "HTTP ${res.status} ${res.text.take(200)}")
    }

    override fun heartbeat(
        installId: String, lanBaseUrl: String,
        devices: List<dev.dwhipstock.pos.base.DeviceRegistry.DeviceSummary>,
    ): PushResult {
        val body = buildJsonObject {
            put("installId", installId)
            put("lanBaseUrl", lanBaseUrl)
            putJsonArray("devices") {
                devices.forEach { d ->
                    addJsonObject {
                        put("id", d.id)
                        put("name", d.name)
                        put("pairedAt", d.pairedAt)
                        d.lastSeenAt?.let { put("lastSeenAt", it) }
                        put("revoked", d.revoked)
                    }
                }
            }
        }
        val res = postJson("/v1/store/heartbeat", body.toString())
        return if (res.status == 200) PushResult(true)
        else PushResult(false, "HTTP ${res.status} ${res.text.take(200)}")
    }

    override fun claimPairing(code: String): PushResult {
        val body = buildJsonObject { put("code", code) }
        val res = postJson("/v1/store/pairing/claim", body.toString())
        // detail = the cloud's machine `code` (e.g. bad_pairing_code) so PairingService
        // can classify refusal vs transport error on a stable value, not raw HTTP text.
        return if (res.status == 200) PushResult(true)
        else PushResult(false, errorCode(res.text) ?: "http_${res.status}")
    }

    /** Pull the `{ "code": ... }` machine code out of a cloud error body, or null. */
    private fun errorCode(body: String): String? =
        runCatching { Json.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.content }.getOrNull()

    override fun fetchChanges(since: Long): ChangesPage {
        val res = request("/v1/store/catalog/changes?since=$since")
        check(res.status == 200) { "HTTP ${res.status} from catalog changes" }
        val obj = Json.parseToJsonElement(res.text).jsonObject
        return ChangesPage(
            cursor = obj["cursor"]!!.jsonPrimitive.long,
            changes = (obj["changes"]?.jsonArray ?: emptyList()).map { el ->
                val c = el.jsonObject
                CatalogChange(
                    version = c["version"]!!.jsonPrimitive.long,
                    kind = c["kind"]!!.jsonPrimitive.content,
                    op = c["op"]!!.jsonPrimitive.content,
                    data = c["data"]!!.jsonObject,
                )
            },
        )
    }

    override fun fetchPhoto(itemId: String): FetchedPhoto? {
        val res = request("/v1/store/photos/$itemId")
        if (res.status == 404) return null
        check(res.status == 200) { "HTTP ${res.status} fetching photo $itemId" }
        return FetchedPhoto(
            bytes = res.bytes,
            contentType = res.contentType ?: "image/jpeg",
        )
    }

    override fun pushPhoto(itemId: String, bytes: ByteArray, contentType: String): PushResult {
        val boundary = "----pos-photo-${UUID.randomUUID()}"
        val ext = if (contentType == "image/png") "png" else "jpg"
        val head = ("--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"photo\"; filename=\"$itemId.$ext\"\r\n" +
            "Content-Type: $contentType\r\n\r\n").toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val payload = ByteArray(head.size + bytes.size + tail.size)
        head.copyInto(payload)
        bytes.copyInto(payload, head.size)
        tail.copyInto(payload, head.size + bytes.size)
        val res = request("/v1/ingest/photos/$itemId", "POST", payload,
            "multipart/form-data; boundary=$boundary")
        return if (res.status in 200..299) PushResult(true)
        else PushResult(false, "HTTP ${res.status}")
    }
}
