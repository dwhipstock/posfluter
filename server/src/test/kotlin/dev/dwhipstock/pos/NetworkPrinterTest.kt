package dev.dwhipstock.pos

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end coverage of the network thermal printer through the real HTTP app:
 * printer IP/port persist as venue settings, a test print delivers raw ESC/POS
 * over TCP, an offline printer degrades gracefully (no exception), and — the
 * hard rule — an offline printer never blocks a sale from closing.
 */
class NetworkPrinterTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()
    private suspend fun io.ktor.client.HttpClient.postJson(path: String, body: String) =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    /** A closed port on loopback: connecting there is refused fast. */
    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun printerSettingsRoundTrip() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        c.patch("/settings") {
            contentType(ContentType.Application.Json)
            setBody("""{"printerIp":"10.0.0.7","printerPort":9100}""")
        }
        val s = json.parseToJsonElement(c.get("/settings").bodyAsText()).jsonObject
        assertEquals("10.0.0.7", s["printerIp"]!!.jsonPrimitive.content)
        assertEquals(9100, s["printerPort"]!!.jsonPrimitive.int)
    }

    @Test
    fun invalidPrinterIpRejected() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val res = c.patch("/settings") {
            contentType(ContentType.Application.Json)
            setBody("""{"printerIp":"not a host!!"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun testPrintDeliversEscPosOverTcp() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()

        // A fake printer: accept one connection, drain it into a buffer.
        val server = ServerSocket(0)
        val received = ArrayList<Byte>()
        val reader = thread {
            server.accept().use { sock ->
                val ins = sock.getInputStream()
                while (true) { val b = ins.read(); if (b < 0) break; received.add(b.toByte()) }
            }
        }

        c.patch("/settings") {
            contentType(ContentType.Application.Json)
            setBody("""{"printerIp":"127.0.0.1","printerPort":${server.localPort}}""")
        }
        val status = json.parseToJsonElement(c.post("/printer/test").bodyAsText()).jsonObject
        assertTrue(status["configured"]!!.jsonPrimitive.boolean)
        assertTrue(status["online"]!!.jsonPrimitive.boolean, "printer answered → online")

        reader.join(3000)
        server.close()
        val bytes = received.toByteArray()
        assertTrue(bytes.size > 100, "a raster receipt is many bytes, got ${bytes.size}")
        assertEquals(0x1B.toByte(), bytes[0]) // ESC
        assertEquals('@'.code.toByte(), bytes[1]) // @  → ESC @ init
        // ends with GS V 0 full cut
        val tail = bytes.copyOfRange(bytes.size - 3, bytes.size)
        assertTrue(tail.contentEquals(byteArrayOf(0x1D, 0x56, 0x00)), "ends with GS V 0 cut")
    }

    @Test
    fun testPrintOfflineReportsWithoutThrowing() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        c.patch("/settings") {
            contentType(ContentType.Application.Json)
            setBody("""{"printerIp":"127.0.0.1","printerPort":${closedPort()}}""")
        }
        val res = c.post("/printer/test")
        assertEquals(HttpStatusCode.OK, res.status) // graceful — not a 500
        val status = json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertTrue(status["configured"]!!.jsonPrimitive.boolean)
        assertFalse(status["online"]!!.jsonPrimitive.boolean, "unreachable printer → offline")
    }

    @Test
    fun unconfiguredPrinterIsNotConfigured() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        // default printerIp is empty
        val status = json.parseToJsonElement(c.get("/printer/status").bodyAsText()).jsonObject
        assertFalse(status["configured"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun offlinePrinterNeverBlocksTheSale() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        // point at a dead port — the async print will fail, but the sale must close
        c.patch("/settings") {
            contentType(ContentType.Application.Json)
            setBody("""{"printerIp":"127.0.0.1","printerPort":${closedPort()}}""")
        }
        c.postJson("/shifts", """{"openingFloatCents":100000,"managerPin":"1234"}""")
        val checkId = json.parseToJsonElement(c.post("/tables/t3/checks").bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$checkId/lines", """{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":1}""")
        c.postJson("/checks/$checkId/tenders", """{"type":"CASH","amountTenderedCents":100000}""")
        val closed = c.post("/checks/$checkId/finalize")
        assertEquals(HttpStatusCode.OK, closed.status)
        assertEquals("CLOSED",
            json.parseToJsonElement(closed.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)
    }
}
