package dev.dwhipstock.pos

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.dwhipstock.pos.sync.HttpCloudTransport
import dev.dwhipstock.pos.sync.PushEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpCloudTransportTest {
    @Test
    fun storeWireWorksWithUrlConnection() {
        val ingest = AtomicReference<String>()
        val photoUpload = AtomicReference<ByteArray>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            assertEquals("Bearer test-key", exchange.requestHeaders.getFirst("Authorization"))
            when (exchange.requestURI.path) {
                "/v1/ingest" -> {
                    ingest.set(exchange.requestBody.bufferedReader().use { it.readText() })
                    exchange.reply(200, "{}")
                }
                "/v1/store/heartbeat" -> exchange.reply(200, "{}")
                "/v1/store/revocations" -> exchange.reply(200, """{"cursor":7,"changes":[]}""")
                "/v1/ingest/photos/item-1" -> {
                    photoUpload.set(exchange.requestBody.use { it.readBytes() })
                    exchange.reply(201, "{}")
                }
                "/v1/store/pairing/claim" -> exchange.reply(400, """{"code":"bad_pairing_code"}""")
                else -> exchange.reply(404, "{}")
            }
        }
        server.start()
        try {
            val transport = HttpCloudTransport("http://127.0.0.1:${server.address.port}", "test-key")
            val event = PushEvent("event-1", 4, "check.closed", "check", "13",
                "2026-09-23T12:00:00", buildJsonObject { put("checkId", 13) })
            assertTrue(transport.push("install-1", listOf(event)).ok)
            val posted = Json.parseToJsonElement(ingest.get()).jsonObject
            assertEquals("install-1", posted["installId"]?.jsonPrimitive?.content)
            assertEquals("event-1", posted["events"]?.jsonArray?.single()?.jsonObject
                ?.get("eventId")?.jsonPrimitive?.content)
            assertTrue(transport.heartbeat("install-1", "http://192.168.1.2:8080").ok)
            assertEquals(7L, transport.fetchRevocations(0).cursor)
            assertTrue(transport.pushPhoto("item-1", "photo".toByteArray(), "image/jpeg").ok)
            assertTrue(photoUpload.get().toString(Charsets.UTF_8).contains("photo"))
            val refusal = transport.claimPairing("wrong")
            assertFalse(refusal.ok)
            assertEquals("bad_pairing_code", refusal.detail)
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.reply(status: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
