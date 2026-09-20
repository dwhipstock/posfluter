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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * java.net.http adapter for the CONTRACT.md wire — no extra dependencies.
 * Errors surface as !ok results or throws; CloudSync retries next tick.
 */
class HttpCloudTransport(baseUrl: String, private val apiKey: String) : CloudTransport {

    private val base = baseUrl.trimEnd('/')
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    private fun request(path: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create("$base$path"))
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofSeconds(30))

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
        val res = http.send(
            request("/v1/ingest")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return if (res.statusCode() == 200) PushResult(true)
        // body carries the machine code (e.g. install_mismatch) — the loop keys off it
        else PushResult(false, "HTTP ${res.statusCode()} ${res.body().take(200)}")
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
        val res = http.send(
            request("/v1/store/heartbeat")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return if (res.statusCode() == 200) PushResult(true)
        else PushResult(false, "HTTP ${res.statusCode()} ${res.body().take(200)}")
    }

    override fun claimPairing(code: String): PushResult {
        val body = buildJsonObject { put("code", code) }
        val res = http.send(
            request("/v1/store/pairing/claim")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        // detail = the cloud's machine `code` (e.g. bad_pairing_code) so PairingService
        // can classify refusal vs transport error on a stable value, not raw HTTP text.
        return if (res.statusCode() == 200) PushResult(true)
        else PushResult(false, errorCode(res.body()) ?: "http_${res.statusCode()}")
    }

    /** Pull the `{ "code": ... }` machine code out of a cloud error body, or null. */
    private fun errorCode(body: String): String? =
        runCatching { Json.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.content }.getOrNull()

    override fun fetchChanges(since: Long): ChangesPage {
        val res = http.send(
            request("/v1/store/catalog/changes?since=$since").GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(res.statusCode() == 200) { "HTTP ${res.statusCode()} from catalog changes" }
        val obj = Json.parseToJsonElement(res.body()).jsonObject
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
        val res = http.send(
            request("/v1/store/photos/$itemId").GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        if (res.statusCode() == 404) return null
        check(res.statusCode() == 200) { "HTTP ${res.statusCode()} fetching photo $itemId" }
        return FetchedPhoto(
            bytes = res.body(),
            contentType = res.headers().firstValue("Content-Type").orElse("image/jpeg"),
        )
    }

    override fun pushPhoto(itemId: String, bytes: ByteArray, contentType: String): PushResult {
        val boundary = "----pos-photo-${UUID.randomUUID()}"
        val ext = if (contentType == "image/png") "png" else "jpg"
        val head = ("--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"photo\"; filename=\"$itemId.$ext\"\r\n" +
            "Content-Type: $contentType\r\n\r\n").toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val res = http.send(
            request("/v1/ingest/photos/$itemId")
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArrays(listOf(head, bytes, tail)))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return if (res.statusCode() in 200..299) PushResult(true)
        else PushResult(false, "HTTP ${res.statusCode()}")
    }
}
