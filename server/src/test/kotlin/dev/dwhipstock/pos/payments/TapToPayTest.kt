package dev.dwhipstock.pos.payments

import dev.dwhipstock.pos.FakeStripe
import dev.dwhipstock.pos.loginClient
import dev.dwhipstock.pos.module
import dev.dwhipstock.pos.payments.taptopay.PhoneReaderHub
import dev.dwhipstock.pos.payments.taptopay.PhoneReport
import dev.dwhipstock.pos.payments.taptopay.PhoneTokenStore
import dev.dwhipstock.pos.payments.taptopay.TapToPayAdapter
import dev.dwhipstock.pos.payments.terminal.Outcome
import dev.dwhipstock.pos.payments.terminal.PaymentRequest
import dev.dwhipstock.pos.payments.terminal.ReaderState
import dev.dwhipstock.pos.payments.terminal.TerminalException
import dev.dwhipstock.pos.payments.terminal.TerminalKind
import dev.dwhipstock.pos.customers.sagepoppy.SagePoppy
import dev.dwhipstock.pos.customers.sagepoppy.SagePoppySeed
import dev.dwhipstock.pos.sdk.PaymentTerminalConfig
import dev.dwhipstock.pos.sdk.StripeConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// --- the PaymentTerminal contract: a phone that plays the customer -----------------

class TapToPayContractTest : TerminalContractTest() {
    override fun harness() = object : Harness {
        val clock = AtomicLong(1_000_000L)
        val fake = FakeStripe(country = "US", currency = "usd")
        val hub = PhoneReaderHub(clock::get, PhoneTokenStore.InMemory()).also {
            it.pair(it.pairingCode, "Test phone")
        }
        override val terminal = TapToPayAdapter(hub, { StripeClient(fake) }, { "usd" }, 90, clock::get)
        override fun approve(ref: String) { fake.authorize(ref); hub.report(ref, PhoneReport("collected")) }
        override fun decline(ref: String) {
            fake.decline(ref, "insufficient_funds")
            hub.report(ref, PhoneReport("failed", "insufficient_funds", "Declined", declined = true))
        }
        override fun customerCancel(ref: String) { hub.report(ref, PhoneReport("canceled")) }
        override fun timeout(ref: String) { clock.addAndGet(91_000) }
        override fun goOffline() { clock.addAndGet(PhoneReaderHub.HEARTBEAT_TIMEOUT_MS + 1) }
        override val reportsCard = false // the fake sends no expanded charge
    }
}

class TapToPayTest {
    private val clock = AtomicLong(1_000_000L)
    private fun hub() = PhoneReaderHub(clock::get, PhoneTokenStore.InMemory(), java.util.Random(3))

    // --- config and the currency rule ------------------------------------------

    @Test
    fun `payment_terminal=tap_to_pay parses, simulated by default`() {
        fun of(vararg kv: Pair<String, String>) = PaymentTerminalConfig.resolve({ k -> kv.toMap()[k] }, "test")
        val c = of(PaymentTerminalConfig.KEY to "tap_to_pay")
        assertEquals(TerminalKind.TAP_TO_PAY, c.kind)
        assertEquals("tap_to_pay", TerminalKind.TAP_TO_PAY.wire)
        assertTrue(TerminalKind.TAP_TO_PAY.integrated)
        assertTrue(c.tapToPaySimulated, "Stripe's simulated reader unless told otherwise")
        assertFalse(of(PaymentTerminalConfig.KEY to "phone", PaymentTerminalConfig.KEY_TTP_SIMULATED to "false").tapToPaySimulated)
        assertTrue(of(PaymentTerminalConfig.KEY_TTP_SIMULATED to "maybe").warnings.any { "taptopay" in it })
    }

    @Test
    fun `a USD store takes STRIPE_KEY_US, a CAD store STRIPE_KEY`() {
        val env = mapOf("STRIPE_KEY" to "sk_test_" + "cad1", "STRIPE_KEY_US" to "sk_test_" + "usd1")
        assertEquals("STRIPE_KEY_US", StripeConfig.fromEnv("USD", env::get).source)
        assertEquals("STRIPE_KEY", StripeConfig.fromEnv("CAD", env::get).source)
        assertEquals("STRIPE_KEY", StripeConfig.fromEnv(env = env::get).source)
        // no US key: falls back to STRIPE_KEY (and the currency check then turns it off)
        assertEquals("STRIPE_KEY", StripeConfig.fromEnv("USD", mapOf("STRIPE_KEY" to "sk_test_" + "cad1")::get).source)
        assertEquals(StripeService.DEMO_ADDRESS_US, StripeService.demoAddress("US"))
        assertEquals(StripeService.DEMO_ADDRESS, StripeService.demoAddress("CA"))
    }

    // --- pairing, heartbeat, queue -----------------------------------------------

    @Test
    fun `pairing - the code on the POS gives a token, wrong codes are refused and rotate it`() {
        val h = hub()
        assertFalse(h.paired)
        val code = h.pairingCode
        val e = assertFailsWith<TerminalException> { h.pair("000000", null) }
        assertEquals("terminal_pairing_code_wrong", e.code)
        val token = h.pair(code, "Galaxy")
        assertTrue(h.paired)
        assertTrue(h.authorized(token))
        assertFalse(h.authorized("nope"))
        assertTrue(h.pairingCode != code, "a new code after pairing")
        // five wrong codes → a new code
        val c2 = h.pairingCode
        repeat(PhoneReaderHub.MAX_WRONG_CODES) { runCatching { h.pair("x", null) } }
        assertTrue(h.pairingCode != c2)
        // a second phone replaces the first
        val t2 = h.pair(h.pairingCode, "Other")
        assertTrue(h.authorized(t2))
        assertFalse(h.authorized(token))
        h.unpair()
        assertFalse(h.authorized(t2))
    }

    @Test
    fun `no heartbeat for 15 seconds - the reader is offline and a payment is a clean 503`() {
        val h = hub()
        val fake = FakeStripe(country = "US", currency = "usd")
        val t = TapToPayAdapter(h, { StripeClient(fake) }, { "usd" }, 90, clock::get)
        assertEquals(ReaderState.NOT_PAIRED, t.status().state)
        val token = h.pair(h.pairingCode, "Galaxy")
        assertEquals(ReaderState.IDLE, t.status().state)
        clock.addAndGet(16_000)
        assertEquals(ReaderState.OFFLINE, t.status().state)
        val e = assertFailsWith<TerminalException> { t.startPayment(PaymentRequest("r1", 500, "USD")) }
        assertTrue(e.unreachable)
        assertTrue(fake.callsTo("/v1/payment_intents").isEmpty(), "nothing created at Stripe")
        h.requirePhone(token) // a poll is a heartbeat
        assertEquals(ReaderState.IDLE, t.status().state)
    }

    @Test
    fun `the queue - the phone gets the secret, the tablet never does, a POS cancel reaches the phone`() {
        val h = hub()
        h.pair(h.pairingCode, "Galaxy")
        val fake = FakeStripe(country = "US", currency = "usd")
        val t = TapToPayAdapter(h, { StripeClient(fake) }, { "usd" }, 90, clock::get)
        assertNull(h.next())
        val p = t.startPayment(PaymentRequest("r1", 1234, "USD", description = "check #1"))
        assertNull(p.clientSecret, "the tablet never sees the client secret")
        val job = h.next()!!
        assertEquals(p.terminalRef, job.paymentIntentId)
        assertTrue(job.clientSecret.startsWith(p.terminalRef))
        assertEquals("usd", job.currency)
        val create = fake.callsTo("/v1/payment_intents").first()
        assertEquals("card_present", create.params["payment_method_types[]"])
        assertEquals("manual", create.params["capture_method"])
        assertEquals("waiting_for_phone", t.result(p.terminalRef).readerPrompt)
        h.report(p.terminalRef, PhoneReport("collecting"))
        assertEquals("present_card", t.result(p.terminalRef).readerPrompt)
        assertEquals(ReaderState.BUSY, t.status().state)
        val busy = assertFailsWith<TerminalException> { t.startPayment(PaymentRequest("r2", 100, "USD")) }
        assertEquals(TerminalException.BUSY, busy.code)
        assertEquals(Outcome.CANCELLED, t.cancel(p.terminalRef, "c1").outcome)
        assertEquals("canceled", h.job(p.terminalRef)!!.state)
        assertNull(h.next(), "nothing left for the phone")
        // the phone's late report doesn't revive it
        h.report(p.terminalRef, PhoneReport("collected"))
        assertEquals("canceled", h.job(p.terminalRef)!!.state)
    }

    @Test
    fun `Developer options on - the phone's insecure-environment error says so`() {
        val h = hub()
        h.pair(h.pairingCode, "Galaxy")
        val t = TapToPayAdapter(h, { StripeClient(FakeStripe("US", "usd")) }, { "usd" }, 90, clock::get)
        val p = t.startPayment(PaymentRequest("r1", 500, "USD"))
        h.report(p.terminalRef, PhoneReport("failed", "tapToPayInsecureEnvironment", "insecure"))
        val r = t.result(p.terminalRef)
        assertEquals(Outcome.ERROR, r.outcome)
        assertTrue("Developer options" in r.message!!, r.message)
    }

    @Test
    fun `partial capture - a fuel pre-authorisation charges only what was pumped`() {
        val h = hub()
        h.pair(h.pairingCode, "Galaxy")
        val fake = FakeStripe(country = "US", currency = "usd")
        val t = TapToPayAdapter(h, { StripeClient(fake) }, { "usd" }, 90, clock::get)
        val p = t.startPayment(PaymentRequest("fuel-1", 5000, "USD"))
        fake.authorize(p.terminalRef)
        h.report(p.terminalRef, PhoneReport("collected"))
        val held = t.result(p.terminalRef)
        assertEquals(Outcome.APPROVED, held.outcome)
        assertFalse(held.captured, "a hold until the pump finishes")
        val r = t.captureAmount(p.terminalRef, "fuel-cap", 3217)
        assertTrue(r.captured)
        val cap = fake.callsTo("/v1/payment_intents/${p.terminalRef}/capture").single()
        assertEquals("3217", cap.params["amount_to_capture"])
        assertEquals("fuel-cap", cap.idempotencyKey)
        assertTrue(fake.callsTo("/v1/refunds").isEmpty(), "Stripe captures partially: no refund needed")
    }

    @Test
    fun `the store restarted - an unpaid PaymentIntent goes back on the phone's queue`() {
        val fake = FakeStripe(country = "US", currency = "usd")
        val h1 = hub().also { it.pair(it.pairingCode, "Galaxy") }
        val ref = TapToPayAdapter(h1, { StripeClient(fake) }, { "usd" }, 90, clock::get)
            .startPayment(PaymentRequest("r1", 700, "USD")).terminalRef
        val h2 = hub().also { it.pair(it.pairingCode, "Galaxy") }
        val t2 = TapToPayAdapter(h2, { StripeClient(fake) }, { "usd" }, 90, clock::get)
        assertEquals(Outcome.PENDING, t2.result(ref).outcome)
        assertEquals(ref, h2.next()?.paymentIntentId)
    }

    // --- end to end: a US store, the phone over HTTP ---------------------------------

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-ttp-test").resolve("pos.db").toString()
    private suspend fun HttpClient.postJson(path: String, body: String = "{}") =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }
    private suspend fun HttpResponse.obj(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private val tapToPay = PaymentTerminalConfig.resolve({ if (it == PaymentTerminalConfig.KEY) "tap_to_pay" else null }, "test")
    private val usKey = StripeConfig.of("sk_test_" + "usunit", null, "test")

    private fun ApplicationTestBuilder.shop(fake: FakeStripe, hub: PhoneReaderHub) = application {
        module(
            dbPath = tempDb(), receiptsDir = Files.createTempDirectory("rc").toString(),
            venueId = SagePoppy.VENUE_ID, sagePoppy = true, physicalPrinterEnabled = false,
            stripeConfig = usKey, stripeHttp = fake, paymentTerminal = tapToPay, phoneReader = hub,
        )
    }

    @Test
    fun `Sage and Poppy - the phone pairs, takes the card, and the tablet records the sale`() = testApplication {
        val fake = FakeStripe(country = "US", currency = "usd")
        val hub = PhoneReaderHub(clock::get, PhoneTokenStore.InMemory())
        shop(fake, hub)
        val pos = loginClient()
        val st = pos.get("/payments/terminal").obj()
        assertEquals("tap_to_pay", st.s("kind"))
        assertEquals("not_paired", st.s("readerState"))
        assertEquals("true", st.s("simulated"))
        val code = st.s("phonePairingCode")

        // the phone: no token → refused; the code → a token
        assertEquals(HttpStatusCode.Unauthorized, client.get("/reader/payment").status)
        val paired = client.postJson("/reader/pair", """{"code":"$code","deviceName":"Galaxy S26"}""")
        assertEquals(HttpStatusCode.OK, paired.status, paired.bodyAsText())
        val token = paired.obj().s("token")
        val phone = createClient { }
        suspend fun phoneGet(path: String) = phone.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }
        suspend fun phonePost(path: String, body: String) = phone.post(path) {
            header(HttpHeaders.Authorization, "Bearer $token"); contentType(ContentType.Application.Json); setBody(body)
        }
        val cfg = phoneGet("/reader/config").obj()
        assertEquals("USD", cfg.s("currency"))
        assertEquals("tml_fake", cfg.s("locationId"))
        assertEquals("true", cfg.s("simulated"))
        // the Terminal Location got the fictional US address
        val loc = fake.calls.single { it.path == "/v1/terminal/locations" && it.method == "POST" }
        assertEquals("US", loc.params["address[country]"])
        assertEquals("pst_test_fake", phonePost("/reader/connection-token", "{}").obj().s("secret"))
        assertEquals(HttpStatusCode.NoContent, phoneGet("/reader/payment").status)
        assertEquals("idle", pos.get("/payments/terminal").obj().s("readerState"))

        // a sale on the tablet
        pos.postJson("/shifts", """{"openingFloatCents":20000,"managerPin":"1234"}""")
        val sale = pos.postJson("/retail/sales").obj()["id"]!!.jsonPrimitive.int
        val snack = SagePoppySeed.products.first { !it.ageRestricted }
        assertEquals(HttpStatusCode.OK, pos.postJson("/retail/sales/$sale/scan", """{"barcode":"${snack.barcode}"}""").status)
        val started = pos.postJson("/checks/$sale/terminal/payments")
        assertEquals(HttpStatusCode.Created, started.status, started.bodyAsText())
        val pid = started.obj().s("paymentId")
        assertEquals("waiting_for_phone", pos.get("/terminal/payments/$pid").obj().s("prompt"))
        assertFalse("secret" in started.bodyAsText(), "the tablet never gets the client secret")

        // the phone picks it up, collects, confirms (Stripe answers requires_capture), reports
        val job = phoneGet("/reader/payment").obj()
        val pi = job.s("paymentIntentId")
        assertEquals("usd", job.s("currency"))
        phonePost("/reader/payments/$pi/result", """{"status":"collecting"}""")
        assertEquals("present_card", pos.get("/terminal/payments/$pid").obj().s("prompt"))
        fake.authorize(pi)
        phonePost("/reader/payments/$pi/result", """{"status":"collected"}""")
        val done = pos.get("/terminal/payments/$pid").obj()
        assertEquals("RECORDED", done.s("status"), done.toString())
        assertEquals("succeeded", fake.status(pi), "captured by the store")
        assertTrue(hub.logSince(0).any { "collected" in it.text }, "the transaction monitor saw it")
        assertTrue(hub.logSince(0).none { "secret" in it.text })
    }

    @Test
    fun `a US store with a CAD Stripe account - Stripe refuses, never converts`() = testApplication {
        val fake = FakeStripe(country = "CA", currency = "cad")
        val hub = PhoneReaderHub(clock::get, PhoneTokenStore.InMemory())
        shop(fake, hub)
        val pos = loginClient()
        val status = pos.get("/stripe/status").obj()
        assertEquals("stripe_currency_mismatch", status.s("reason"))
        assertEquals("USD", status.s("venueCurrency"))
    }
}
