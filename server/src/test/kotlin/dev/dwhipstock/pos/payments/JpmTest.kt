package dev.dwhipstock.pos.payments

import dev.dwhipstock.pos.payments.jpm.JpmHttp
import dev.dwhipstock.pos.payments.jpm.JpmOnlineHttp
import dev.dwhipstock.pos.payments.jpm.MiniWebSocket
import dev.dwhipstock.pos.payments.terminal.TerminalException
import dev.dwhipstock.pos.sdk.PaymentTerminalConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume
import java.io.IOException
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import javax.net.SocketFactory
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JpmOnlineHttpTest {
    private val secret = "unit-" + "secret-value"
    private val creds = PaymentTerminalConfig.JpmCredentials("client-abc", secret,
        "https://id.example.test/oauth2/access_token", "jpm:payments:sandbox", null, null)

    private class Fake : JpmHttp {
        data class Req(val method: String, val url: String, val headers: Map<String, String>, val body: String?)
        val reqs = mutableListOf<Req>()
        var answer: (Req) -> Pair<Int, String> = { r ->
            when {
                "access_token" in r.url -> 200 to """{"access_token":"tok-1","token_type":"Bearer","expires_in":3599}"""
                r.url.endsWith("/payments") -> 200 to """{"transactionId":"tx-1","responseStatus":"SUCCESS","responseCode":"APPROVED",
                    "transactionState":"AUTHORIZED","approvalCode":"tst506","responseMessage":"Transaction approved"}"""
                r.url.endsWith("/captures") -> 200 to """{"transactionId":"tx-1","responseStatus":"SUCCESS","responseCode":"ACCEPTED","transactionState":"CLOSED"}"""
                r.url.endsWith("/refunds") -> 200 to """{"transactionId":"re-1","responseStatus":"SUCCESS","responseCode":"ACCEPTED","transactionState":"CLOSED"}"""
                r.method == "PATCH" -> 200 to """{"transactionId":"tx-1","responseStatus":"SUCCESS","transactionState":"VOIDED"}"""
                else -> 404 to "{}"
            }
        }
        override fun send(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
            val r = Req(method, url, headers, body)
            reqs += r
            return answer(r)
        }
    }

    @Test
    fun `authorize, capture, void and refund follow the documented shapes, and the token is fetched once`() {
        val fake = Fake()
        var now = 0L
        val api = JpmOnlineHttp(creds, JpmOnlineHttp.MOCK_BASE, fake) { now }
        val card = JpmOnlineHttp.sandboxCard("amex", "approve")
        val auth = api.authorize("pos-auth-sim_1", 2328, "usd", card, "ref-9f8e")
        assertTrue(auth.approved)
        assertEquals("tx-1", auth.transactionId)
        assertEquals("tst506", auth.approvalCode)
        assertTrue(api.capture("tx-1", 2328, "pos-capture-1").approved)
        assertTrue(api.void("tx-1", "pos-cancel-1").approved)
        assertTrue(api.refund("tx-1", 500, "USD", "pos-refund-1").approved)

        assertEquals(1, fake.reqs.count { "access_token" in it.url }, "token cached")
        val tokenReq = fake.reqs.first { "access_token" in it.url }
        assertTrue("grant_type=client_credentials" in tokenReq.body!! && "scope=jpm%3Apayments%3Asandbox" in tokenReq.body!!)

        val pay = fake.reqs.first { it.url.endsWith("/payments") }
        assertEquals("POST", pay.method)
        assertEquals("Bearer tok-1", pay.headers["Authorization"])
        assertEquals(JpmOnlineHttp.MOCK_MERCHANT_ID, pay.headers["merchant-id"])
        assertNotNull(pay.headers["request-id"])
        val body = Json.parseToJsonElement(pay.body!!).jsonObject
        assertEquals("MANUAL", body["captureMethod"]!!.jsonPrimitive.content)
        assertEquals("2328", body["amount"]!!.jsonPrimitive.content)
        assertEquals("USD", body["currency"]!!.jsonPrimitive.content)
        assertEquals("371144371144376", body["paymentMethodType"]!!.jsonObject["card"]!!.jsonObject["accountNumber"]!!.jsonPrimitive.content)
        val refund = Json.parseToJsonElement(fake.reqs.first { it.url.endsWith("/refunds") }.body!!).jsonObject
        assertEquals("tx-1", refund["paymentMethodType"]!!.jsonObject["transactionReference"]!!.jsonObject["transactionReferenceId"]!!.jsonPrimitive.content)
        assertTrue(fake.reqs.any { it.url.endsWith("/payments/tx-1/captures") })
        assertTrue(fake.reqs.any { it.method == "PATCH" && it.url.endsWith("/payments/tx-1") })

        // the same idempotency key → the same request-id (a retry is safe); different ops differ
        val ids = fake.reqs.filter { "access_token" !in it.url }.map { it.headers["request-id"] }
        assertEquals(ids.size, ids.toSet().size)

        // the token is refreshed once it expires
        now += 3_600_000
        api.capture("tx-1", 2328, "pos-capture-2")
        assertEquals(2, fake.reqs.count { "access_token" in it.url })
    }

    @Test
    fun `the secret never shows up in config, logs or errors`() {
        assertFalse(secret in creds.toString())
        val cfg = PaymentTerminalConfig.resolve({ mapOf("payment.terminal" to "jpmorgan", "payment.jpmorgan.clientSecret" to secret)[it] }, "t")
        assertFalse(secret in cfg.toString())
        assertFalse(secret in cfg.jpm.toString())
        val fake = Fake().apply { answer = { 401 to """{"responseMessage":"Invalid issuer"}""" } }
        val e = assertFailsWith<TerminalException> { JpmOnlineHttp(creds, JpmOnlineHttp.MOCK_BASE, fake).capture("tx", 1, "k") }
        assertFalse(secret in (e.message ?: ""))
    }

    @Test
    fun `unreachable or 5xx is terminal_unavailable, and a decline is not approved`() {
        val down = JpmHttp { _, _, _, _ -> throw IOException("no route") }
        val e = assertFailsWith<TerminalException> { JpmOnlineHttp(creds, JpmOnlineHttp.MOCK_BASE, down).capture("tx", 1, "k") }
        assertTrue(e.unreachable)
        val busy = Fake().apply { answer = { r -> if ("access_token" in r.url) 200 to """{"access_token":"t","expires_in":60}""" else 503 to "{}" } }
        assertTrue(assertFailsWith<TerminalException> { JpmOnlineHttp(creds, JpmOnlineHttp.MOCK_BASE, busy).capture("tx", 1, "k") }.unreachable)
        val declined = Fake().apply { answer = { r -> if ("access_token" in r.url) 200 to """{"access_token":"t","expires_in":60}"""
            else 200 to """{"transactionId":"tx-2","responseStatus":"DENIED","responseCode":"DO_NOT_HONOR","transactionState":"DECLINED"}""" } }
        val p = JpmOnlineHttp(creds, JpmOnlineHttp.MOCK_BASE, declined).authorize("k", 53000, "USD", JpmOnlineHttp.sandboxCard("visa", "do_not_honour"), "r")
        assertFalse(p.approved)
        assertEquals("DO_NOT_HONOR", p.responseCode)
    }

    @Test
    fun `sandbox hosts only, and decline scenarios use the documented trigger amounts`() {
        assertFailsWith<IllegalArgumentException> { JpmOnlineHttp(creds, "https://api-ms.payments.jpmorgan.com/api/v2") }
        JpmOnlineHttp(creds, JpmOnlineHttp.TEST_BASE) // the client-testing host is allowed
        assertEquals(52100L, JpmOnlineHttp.sandboxCard("visa", "insufficient_funds").triggerAmountCents)
        assertEquals(53000L, JpmOnlineHttp.sandboxCard("visa", "do_not_honour").triggerAmountCents)
        assertEquals("4112344112344113", JpmOnlineHttp.sandboxCard("visa", "approve").number)
        assertEquals(null, JpmOnlineHttp.sandboxCard("mastercard", "approve").triggerAmountCents)
        // the mock needs no credentials; a real host does
        assertNotNull(JpmOnlineHttp.from(PaymentTerminalConfig.JpmCredentials(null, null, null, null, null, null)))
        assertEquals(null, JpmOnlineHttp.from(PaymentTerminalConfig.JpmCredentials(null, null, null, null, null, JpmOnlineHttp.TEST_BASE)))
    }

    /**
     * One real call to J.P. Morgan's sandbox MOCK host (never in CI): set
     * JPM_LIVE_SANDBOX=1, plus JPM_CLIENT_ID / JPM_CLIENT_SECRET / JPM_TOKEN_URL
     * / JPM_SCOPE from the gitignored .env. Prints statuses and transaction ids only.
     */
    @Test
    fun `live sandbox round trip (opt-in)`() {
        Assume.assumeTrue(System.getenv("JPM_LIVE_SANDBOX") == "1")
        // JPM_ENV_FILE: read the JPM_* lines of a .env file (values may be quoted) instead of the environment
        val dotenv = System.getenv("JPM_ENV_FILE")?.let { java.io.File(it) }?.takeIf { it.exists() }?.let { f ->
            java.util.Properties().apply { f.inputStream().use(::load) }
        }
        val cfg = PaymentTerminalConfig.fromEnv { k ->
            dotenv?.getProperty(k)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: System.getenv(k)
        }
        val api = assertNotNull(JpmOnlineHttp.from(cfg.jpm))
        val auth = api.authorize("live-auth-${System.nanoTime()}", 1234, "USD", JpmOnlineHttp.sandboxCard("visa", "approve"), "livetest")
        println("JPM sandbox authorize: approved=${auth.approved} state=${auth.state} id=${auth.transactionId}")
        val cap = api.capture(auth.transactionId, 1234, "live-cap-${System.nanoTime()}")
        println("JPM sandbox capture: approved=${cap.approved} state=${cap.state} id=${cap.transactionId}")
        val re = api.refund(auth.transactionId, 1234, "USD", "live-re-${System.nanoTime()}")
        println("JPM sandbox refund: approved=${re.approved} state=${re.state} id=${re.transactionId}")
        assertTrue(auth.approved)
    }
}

/** The dependency-free WebSocket client against a tiny RFC 6455 server. */
class MiniWebSocketTest {
    @Test
    fun `handshake, masked text out, ping answered, text in, close`() {
        val server = ServerSocket(0)
        val seen = mutableListOf<String>()
        val t = thread {
            server.accept().use { s ->
                val input = s.getInputStream()
                val out = s.getOutputStream()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                val key = Regex("Sec-WebSocket-Key: (.+)\r\n").find(head)!!.groupValues[1].trim()
                val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                // read one masked client frame
                val b0 = input.read(); val b1 = input.read()
                check(b0 == 0x81 && b1 and 0x80 != 0) { "client frames must be masked text" }
                val len = b1 and 0x7F
                val mask = ByteArray(4) { input.read().toByte() }
                val payload = ByteArray(len) { i -> (input.read() xor mask[i % 4].toInt()).toByte() }
                synchronized(seen) { seen += String(payload) }
                // a ping (expects a pong), then an unmasked text answer
                out.write(byteArrayOf(0x89.toByte(), 0x02, 'h'.code.toByte(), 'i'.code.toByte()))
                val msg = """{"operation":"GetInformation","result":"0"}""".toByteArray()
                out.write(byteArrayOf(0x81.toByte(), msg.size.toByte())); out.write(msg)
                out.flush()
                val pong = input.read()
                synchronized(seen) { seen += "pong:${pong == 0x8A}" }
                Thread.sleep(100)
            }
        }
        val ws = MiniWebSocket.connect("127.0.0.1", server.localPort, "/", SocketFactory.getDefault())
        ws.send("""{"operation":"GetInformation"}""")
        assertEquals("""{"operation":"GetInformation","result":"0"}""", ws.receive(2_000))
        t.join(2_000)
        ws.close()
        server.close()
        assertEquals(listOf("""{"operation":"GetInformation"}""", "pong:true"), synchronized(seen) { seen.toList() })
    }

    @Test
    fun `a refused upgrade is an IOException`() {
        val server = ServerSocket(0)
        thread { server.accept().use { s ->
            val input = s.getInputStream(); val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
            s.getOutputStream().write("HTTP/1.1 403 Forbidden\r\n\r\n".toByteArray())
        } }
        assertFailsWith<IOException> { MiniWebSocket.connect("127.0.0.1", server.localPort, "/", SocketFactory.getDefault()) }
        server.close()
    }
}

class PaymentTerminalConfigTest {
    private fun of(vararg kv: Pair<String, String>) = PaymentTerminalConfig.resolve({ k -> kv.toMap()[k] }, "test")

    @Test
    fun `kinds, host, timeout and the store default`() {
        assertEquals(dev.dwhipstock.pos.payments.terminal.TerminalKind.SIMULATOR, of("payment.terminal" to " Simulator ").kind)
        assertEquals(dev.dwhipstock.pos.payments.terminal.TerminalKind.JPMORGAN, of("payment.terminal" to "jpmorgan").kind)
        assertEquals(dev.dwhipstock.pos.payments.terminal.TerminalKind.OFF, of("payment.terminal" to "off").kind)
        val bad = of("payment.terminal" to "square")
        assertEquals(null, bad.kind)
        assertTrue(bad.warnings.single().contains("invalid payment.terminal"))
        assertEquals(dev.dwhipstock.pos.payments.terminal.TerminalKind.STRIPE,
            bad.resolveFor(dev.dwhipstock.pos.payments.terminal.TerminalKind.STRIPE))
        val h = of("payment.terminal.host" to "http://192.168.1.50:8090/")
        assertEquals("192.168.1.50", h.host); assertEquals(8090, h.port)
        assertEquals("mac.local", of("payment.terminal.host" to "mac.local").host)
        assertTrue(of("payment.terminal.host" to "not a host!").warnings.isNotEmpty())
        assertEquals(120, of("payment.terminal.timeoutSeconds" to "120").timeoutSeconds)
        assertEquals(90, of("payment.terminal.timeoutSeconds" to "5").timeoutSeconds)
        assertEquals(PaymentTerminalConfig.JpmMode.INSTORE, of("payment.jpmorgan.mode" to "instore").jpmMode)
        assertEquals(PaymentTerminalConfig.JpmMode.ONLINE, of().jpmMode)
    }

    @Test
    fun `env wins, and the J P Morgan portal's own variable names are read`() {
        val env = mapOf("POS_PAYMENT_TERMINAL" to "jpmorgan", "JPM_CLIENT_ID" to "cid", "JPM_CLIENT_SECRET" to "s3cr3t",
            "JPM_TOKEN_URL" to "https://id.example.test/token", "JPM_SCOPE" to "jpm:payments:sandbox")
        val r = PaymentTerminalConfig.fromEnv { env[it] }
        assertEquals(dev.dwhipstock.pos.payments.terminal.TerminalKind.JPMORGAN, r.kind)
        assertEquals("POS_PAYMENT_TERMINAL", r.source)
        assertTrue(r.jpm.complete)
        assertEquals("cid", r.jpm.clientId)
        assertFalse("s3cr3t" in r.jpm.toString())
    }
}
