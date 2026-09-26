package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.Tenders
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.payments.simulator.InProcessSimulatorLink
import dev.dwhipstock.pos.payments.simulator.SimulatedTerminalDevice
import dev.dwhipstock.pos.payments.simulator.SimulatorLink
import dev.dwhipstock.pos.payments.terminal.TerminalException
import dev.dwhipstock.pos.restaurant.Refunds
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The built-in card terminal simulator end to end through the store's HTTP
 * API: start a payment, play the reader (/terminal/ui/...), poll until the
 * store records the tender, then receipts and refunds. A fake clock drives
 * the reader's delays and timeout: no sleeping.
 */
class TerminalPaymentsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-terminal-test").resolve("pos.db").toString()
    private val simulator = PaymentTerminalConfig.resolve({ if (it == PaymentTerminalConfig.KEY) "simulator" else null }, "test")

    private val clock = AtomicLong(1_000_000L)
    private fun device(delays: SimulatedTerminalDevice.Delays = SimulatedTerminalDevice.Delays.INSTANT) =
        SimulatedTerminalDevice(clock = clock::get, delays = delays, random = kotlin.random.Random(7))

    private suspend fun HttpClient.postJson(path: String, body: String = "{}") =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }
    private suspend fun HttpResponse.obj(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    /** Manager session, open shift, a $23.28 check. */
    private suspend fun ApplicationTestBuilder.checkOf(table: String = "t5-5"): Pair<HttpClient, Int> {
        val c = loginClient()
        c.postJson("/shifts", """{"openingFloatCents":10000,"managerPin":"1234"}""")
        val id = c.postJson("/tables/$table/checks", """{"userId":"manager"}""").obj()["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$id/lines", """{"itemId":"lantern-lager","variantId":"lantern-lager:pitcher","qty":1}""")
        return c to id
    }

    private suspend fun HttpClient.start(checkId: Int, body: String = "{}"): JsonObject =
        postJson("/checks/$checkId/terminal/payments", body).also {
            assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText())
        }.obj()

    private suspend fun HttpClient.poll(pid: String): JsonObject = get("/terminal/payments/$pid").also {
        assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText())
    }.obj()

    private suspend fun HttpClient.present(entry: String, card: String = "visa", outcome: String = "approve") =
        postJson("/terminal/ui/present", """{"entry":"$entry","card":"$card","outcome":"$outcome"}""")

    private suspend fun HttpClient.payByTerminal(checkId: Int, entry: String = "tap", card: String = "visa"): JsonObject {
        val pid = start(checkId).s("paymentId")
        assertEquals(HttpStatusCode.OK, present(entry, card).status)
        if (entry == "insert") assertEquals(HttpStatusCode.OK, postJson("/terminal/ui/pin", """{"pin":"1234"}""").status)
        val done = poll(pid)
        assertEquals("RECORDED", done.s("status"), done.toString())
        return done
    }

    private fun outboxPayloads(): List<String> = transaction { SyncOutbox.selectAll().map { it[SyncOutbox.payload] } }

    // --- config selection ----------------------------------------------------

    @Test
    fun `Copper Lantern defaults to Stripe, Sage and Poppy to the simulator`() = testApplication {
        application { module(dbPath = tempDb(), stripeConfig = StripeConfig.OFF, paymentTerminal = PaymentTerminalConfig.DEFAULT) }
        val st = loginClient().get("/payments/terminal").obj()
        assertEquals("stripe", st.s("kind"))
        assertFalse(st["available"]!!.jsonPrimitive.content.toBoolean(), "no Stripe key → not available")
    }

    @Test
    fun `Sage and Poppy defaults to the built-in simulator`() = testApplication {
        application {
            module(dbPath = tempDb(), venueId = "sage-poppy",
                paymentTerminal = PaymentTerminalConfig.DEFAULT, terminalDevice = device())
        }
        val st = loginClient().get("/payments/terminal").obj()
        assertEquals("simulator", st.s("kind"))
        assertEquals("true", st.s("available"))
        assertEquals("true", st.s("embedded"))
        assertEquals("idle", st.s("readerState"))
        // the reader page is served on the LAN without a session
        val page = client.get("/terminal")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue("Tap, insert or swipe" in page.bodyAsText())
        assertEquals("idle", client.get("/terminal/ui/state").obj().s("screen"))
    }

    @Test
    fun `payment_terminal=simulator on Copper Lantern turns Stripe off and the simulator on`() = testApplication {
        application {
            module(dbPath = tempDb(), stripeConfig = StripeConfig.of("sk_test_" + "unit", null, "test"),
                stripeHttp = FakeStripe(), paymentTerminal = simulator, terminalDevice = device())
        }
        val c = loginClient()
        assertEquals("simulator", c.get("/payments/terminal").obj().s("kind"))
        assertEquals("false", c.get("/stripe/status").obj().s("configured"))
    }

    @Test
    fun `off removes every card tender, external keeps the hand-keyed card`() {
        testApplication {
            val off = PaymentTerminalConfig.resolve({ if (it == PaymentTerminalConfig.KEY) "off" else null }, "test")
            application { module(dbPath = tempDb(), paymentTerminal = off) }
            val (c, id) = checkOf()
            assertEquals("off", c.get("/payments/terminal").obj().s("kind"))
            val card = c.postJson("/checks/$id/tenders/confirm", """{"type":"CARD","amountCents":2328}""")
            assertEquals(HttpStatusCode.Conflict, card.status, card.bodyAsText())
            assertEquals(HttpStatusCode.Conflict, c.postJson("/checks/$id/terminal/payments").status)
        }
        testApplication {
            val ext = PaymentTerminalConfig.resolve({ if (it == PaymentTerminalConfig.KEY) "external" else null }, "test")
            application { module(dbPath = tempDb(), paymentTerminal = ext) }
            val c = loginClient()
            val st = c.get("/payments/terminal").obj()
            assertEquals("external", st.s("kind"))
            assertEquals("false", st.s("integrated"))
        }
    }

    // --- payments ------------------------------------------------------------

    @Test
    fun `tap approve - recorded as a TERMINAL tender with the card on the receipt`() = testApplication {
        application { module(dbPath = tempDb(), paymentTerminal = simulator, terminalDevice = device()) }
        val (c, id) = checkOf()
        val started = c.start(id)
        assertEquals("PENDING", started.s("status"))
        assertEquals("present_card", started.s("prompt"))
        assertEquals(2328L, started["amountCents"]!!.jsonPrimitive.long)
        // the reader shows the amount
        val screen = c.get("/terminal/ui/state").obj()
        assertEquals("present", screen.s("screen"))
        assertEquals(2328L, screen["totalCents"]!!.jsonPrimitive.long)

        assertEquals(HttpStatusCode.OK, c.present("tap", "mastercard").status)
        val done = c.poll(started.s("paymentId"))
        assertEquals("RECORDED", done.s("status"))
        assertEquals("TERMINAL", done["tender"]!!.jsonObject.s("type"))
        assertEquals(0L, done["check"]!!.jsonObject["outstandingCents"]!!.jsonPrimitive.long)
        val card = done["card"]!!.jsonObject
        assertEquals("Mastercard", card.s("brand"))
        assertEquals("4444", card.s("last4"))
        assertEquals("TAP", card.s("entryMode"))
        assertEquals(6, card.s("authCode").length)

        // polling again is idempotent: the same tender
        assertEquals(done["tender"]!!.jsonObject["id"], c.poll(started.s("paymentId"))["tender"]!!.jsonObject["id"])
        assertEquals(1, transaction { Tenders.selectAll().where { Tenders.transactionId eq id }.count() })

        assertEquals(HttpStatusCode.OK, c.post("/checks/$id/finalize").status)
        val receipt = c.get("/checks/$id/receipt").obj().s("text")
        assertTrue("MASTERCARD **** 4444" in receipt, receipt)
        assertTrue("Contactless" in receipt, receipt)
        assertTrue("AID" in receipt && "A0000000041010" in receipt, receipt)
        assertTrue("TVR 0000000000" in receipt, receipt)
        assertTrue(card.s("authCode") in receipt, receipt)
        assertTrue("APPROVED" in receipt, receipt)
        // synced: brand + last 4 only
        val tendered = outboxPayloads().map { json.parseToJsonElement(it).jsonObject }
            .single { it["type"]?.jsonPrimitive?.content == "TERMINAL" && it["processor"] != null }
        assertEquals("simulator", tendered.s("processor"))
        assertEquals("4444", tendered.s("cardLast4"))
    }

    @Test
    fun `insert asks for a PIN, then approves with chip EMV fields`() = testApplication {
        application { module(dbPath = tempDb(), paymentTerminal = simulator, terminalDevice = device()) }
        val (c, id) = checkOf()
        val pid = c.start(id).s("paymentId")
        c.present("insert", "visa")
        assertEquals("enter_pin", c.poll(pid).s("prompt"))
        assertEquals("pin", c.get("/terminal/ui/state").obj().s("screen"))
        assertEquals(HttpStatusCode.BadRequest, c.postJson("/terminal/ui/pin", """{"pin":"12"}""").status)
        c.postJson("/terminal/ui/pin", """{"pin":"4321"}""")
        val done = c.poll(pid)
        assertEquals("RECORDED", done.s("status"))
        assertEquals("INSERT", done["card"]!!.jsonObject.s("entryMode"))
        assertEquals("PIN VERIFIED", done["card"]!!.jsonObject.s("cvm"))
        assertEquals("E800", done["card"]!!.jsonObject.s("tsi"))
    }

    @Test
    fun `realistic delay - processing first, approved once the reader is done`() = testApplication {
        val d = device(SimulatedTerminalDevice.Delays(tapMs = 1500, swipeMs = 2000, chipMs = 2500, jitterMs = 0, resultScreenMs = 5000))
        application { module(dbPath = tempDb(), paymentTerminal = simulator, terminalDevice = d) }
        val (c, id) = checkOf()
        val pid = c.start(id).s("paymentId")
        c.present("swipe")
        assertEquals("processing", c.poll(pid).s("prompt"))
        clock.addAndGet(2_000)
        val done = c.poll(pid)
        assertEquals("RECORDED", done.s("status"))
        assertEquals("SWIPE", done["card"]!!.jsonObject.s("entryMode"))
        assertTrue(done["card"]!!.jsonObject["aid"].let { it == null || it is kotlinx.serialization.json.JsonNull }, "a swipe has no EMV application")
        assertEquals("approved", c.get("/terminal/ui/state").obj().s("screen"))
        clock.addAndGet(6_000)
        assertEquals("idle", c.get("/terminal/ui/state").obj().s("screen"))
    }

    @Test
    fun `declines - insufficient funds and do not honour, nothing recorded, cash still pays`() = testApplication {
        application { module(dbPath = tempDb(), paymentTerminal = simulator, terminalDevice = device()) }
        val (c, id) = checkOf()
        val p1 = c.start(id).s("paymentId")
        c.present("tap", outcome = "insufficient_funds")
        val d1 = c.poll(p1)
        assertEquals("DECLINED", d1.s("status"))
        assertEquals("insufficient_funds", d1.s("declineCode"))
        val p2 = c.start(id).s("paymentId")
        c.present("tap", outcome = "do_not_honour")
        assertEquals("do_not_honor", c.poll(p2).s("declineCode"))
        assertEquals(0, transaction { Tenders.selectAll().where { Tenders.transactionId eq id }.count() })
        assertEquals(HttpStatusCode.Created,
            c.postJson("/checks/$id/tenders", """{"type":"CASH","amountTenderedCents":2330}""").status)
    }

    @Test
    fun `timeout - no card within the timeout, and the reader's timeout button`() = testApplication {
        application { module(dbPath = tempDb(), paymentTerminal = simulator, terminalDevice = device()) }
        val (c, id) = checkOf()
        val p1 = c.start(id).s("paymentId")
        clock.addAndGet(91_000) // default 90 s
        assertEquals("TIMEOUT", c.poll(p1).s("status"))
        val p2 = c.start(id).s("paymentId")
        c.present("tap", outcome = "timeout")
        assertEquals("TIMEOUT", c.poll(p2).s("status"))
        assertEquals(0, transaction { Tenders.selectAll().where { Tenders.transactionId eq id }.count() })
    }

    @Test
    fun `cancelled - by the POS, and by the customer on the reader`() = testApplication {
        application { module(dbPath = tempDb(), paymentTerminal = simulator, terminalDevice = device()) }
        val (c, id) = checkOf()
        val p1 = c.start(id).s("paymentId")
        val canceled = c.postJson("/terminal/payments/$p1/cancel").obj()
        assertEquals("CANCELED", canceled.s("status"))
        assertEquals("idle", c.get("/terminal/ui/state").obj().s("screen").let { if (it == "cancelled") "idle" else it })
        val p2 = c.start(id).s("paymentId")
        c.present("tap", outcome = "cancel")
        assertEquals("CANCELED", c.poll(p2).s("status"))
        val p3 = c.start(id).s("paymentId")
        c.postJson("/terminal/ui/cancel")
        assertEquals("CANCELED", c.poll(p3).s("status"))
        // a recorded payment can't be cancelled, only refunded
        val done = c.payByTerminal(id)
        assertEquals(HttpStatusCode.Conflict, c.postJson("/terminal/payments/${done.s("paymentId")}/cancel").status)
    }

    @Test
    fun `tip on the reader is charged on top and printed`() = testApplication {
        application { module(dbPath = tempDb(), paymentTerminal = simulator, terminalDevice = device()) }
        val (c, id) = checkOf()
        val pid = c.start(id, """{"tipMode":"on_reader"}""").s("paymentId")
        assertEquals("choose_tip", c.poll(pid).s("prompt"))
        c.postJson("/terminal/ui/tip", """{"tipCents":400}""")
        assertEquals(2728L, c.get("/terminal/ui/state").obj()["totalCents"]!!.jsonPrimitive.long)
        c.present("tap")
        val done = c.poll(pid)
        assertEquals("RECORDED", done.s("status"))
        assertEquals(400L, done["tipCents"]!!.jsonPrimitive.long)
        assertEquals(2328L, done["tender"]!!.jsonObject["amountAppliedCents"]!!.jsonPrimitive.long)
        c.post("/checks/$id/finalize")
        val receipt = c.get("/checks/$id/receipt").obj().s("text")
        assertTrue("Tip" in receipt && "Total charged" in receipt && "27.28" in receipt, receipt)
    }

    @Test
    fun `paid another way while on the reader - the approval is voided, not recorded`() = testApplication {
        application { module(dbPath = tempDb(), paymentTerminal = simulator, terminalDevice = device()) }
        val (c, id) = checkOf()
        val pid = c.start(id).s("paymentId")
        assertEquals(HttpStatusCode.Created,
            c.postJson("/checks/$id/tenders", """{"type":"CASH","amountTenderedCents":2330}""").status)
        c.present("tap")
        val res = c.poll(pid)
        assertEquals("CANCELED", res.s("status"))
        assertEquals("terminal_amount_exceeds_due", res.s("errorCode"))
        assertEquals(1, transaction { Tenders.selectAll().where { Tenders.transactionId eq id }.count() })
    }

    @Test
    fun `a double tap on Charge resumes the payment already on the reader`() = testApplication {
        application { module(dbPath = tempDb(), paymentTerminal = simulator, terminalDevice = device()) }
        val (c, id) = checkOf()
        val a = c.start(id).s("paymentId")
        val b = c.start(id).s("paymentId")
        assertEquals(a, b)
    }

    // --- refunds -------------------------------------------------------------

    @Test
    fun `refunds on the terminal - partial, capped, then the rest`() = testApplication {
        val d = device()
        application { module(dbPath = tempDb(), paymentTerminal = simulator, terminalDevice = d) }
        val (c, id) = checkOf()
        val done = c.payByTerminal(id)
        c.post("/checks/$id/finalize")
        assertEquals(2328L, c.get("/checks/$id/refunds").obj()["terminalRefundableCents"]!!.jsonPrimitive.long)

        val r1 = c.postJson("/checks/$id/refund", """{"amountCents":1000,"tenderType":"TERMINAL","reason":"wrong item","managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Created, r1.status, r1.bodyAsText())
        assertEquals("TERMINAL", r1.obj()["refund"]!!.jsonObject.s("tenderType"))
        val row = transaction { Refunds.selectAll().where { Refunds.checkId eq id }.single() }
        assertNotNull(row[Refunds.terminalRefundRef])
        assertEquals(transaction { Tenders.selectAll().where { Tenders.transactionId eq id }.single()[Tenders.terminalPaymentRef] },
            row[Refunds.terminalPaymentRef])
        assertEquals(1328L, c.get("/checks/$id/refunds").obj()["terminalRefundableCents"]!!.jsonPrimitive.long)

        val over = c.postJson("/checks/$id/refund", """{"amountCents":2000,"tenderType":"TERMINAL","reason":"x","managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Conflict, over.status)
        assertEquals(HttpStatusCode.Created, c.postJson("/checks/$id/refund",
            """{"amountCents":1328,"tenderType":"TERMINAL","reason":"rest","managerPin":"1234"}""").status)
        assertEquals(0L, c.get("/checks/$id/refunds").obj()["refundableCents"]!!.jsonPrimitive.long)
        assertTrue(outboxPayloads().any { "\"refundId\"" in it && "\"TERMINAL\"" in it && "terminalRefundRef" in it })
        assertNotNull(done)
    }

    @Test
    fun `TERMINAL refund on a check paid in cash is refused`() = testApplication {
        application { module(dbPath = tempDb(), paymentTerminal = simulator, terminalDevice = device()) }
        val (c, id) = checkOf()
        c.postJson("/checks/$id/tenders", """{"type":"CASH","amountTenderedCents":2330}""")
        c.post("/checks/$id/finalize")
        val res = c.postJson("/checks/$id/refund", """{"amountCents":500,"tenderType":"TERMINAL","reason":"x","managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertEquals("terminal_no_card_tender", res.obj().s("code"))
    }

    @Test
    fun `TERMINAL can't be confirmed by hand`() = testApplication {
        application { module(dbPath = tempDb(), paymentTerminal = simulator, terminalDevice = device()) }
        val (c, id) = checkOf()
        val res = c.postJson("/checks/$id/tenders/confirm", """{"type":"TERMINAL","amountCents":2328}""")
        assertEquals(HttpStatusCode.Conflict, res.status, res.bodyAsText())
    }

    // --- the stand-alone terminal on the LAN ---------------------------------

    /** A LAN link that can be unplugged. */
    private class Unpluggable(val inner: SimulatorLink) : SimulatorLink by inner {
        @Volatile var down = false
        private fun check() { if (down) throw TerminalException(503, TerminalException.UNAVAILABLE, "card terminal at 10.0.0.9:8090 can't be reached") }
        override val embedded = false
        override val address = "10.0.0.9:8090"
        override fun status() = check().let { inner.status() }
        override fun pair(code: String) = check().let { inner.pair(code) }
        override fun start(req: dev.dwhipstock.pos.payments.simulator.SimStartRequest) = check().let { inner.start(req) }
        override fun get(id: String) = check().let { inner.get(id) }
        override fun cancel(id: String) = check().let { inner.cancel(id) }
        override fun capture(id: String) = check().let { inner.capture(id) }
    }

    @Test
    fun `LAN terminal - pair with the code on its screen, then pay, and unplugged is a clean 503`() = testApplication {
        val mac = SimulatedTerminalDevice(requirePairing = true, clock = clock::get, delays = SimulatedTerminalDevice.Delays.INSTANT)
        val lan = Unpluggable(InProcessSimulatorLink(mac))
        val cfg = PaymentTerminalConfig.resolve({
            when (it) { PaymentTerminalConfig.KEY -> "simulator"; PaymentTerminalConfig.KEY_HOST -> "10.0.0.9:8090"; else -> null }
        }, "test")
        application {
            module(dbPath = tempDb(), paymentTerminal = cfg, terminalDevice = device(),
                simulatorLinkFactory = { h, p, _ -> assertEquals("10.0.0.9" to 8090, h to p); lan })
        }
        val (c, id) = checkOf()
        val st = c.get("/payments/terminal").obj()
        assertEquals("not_paired", st.s("readerState"))
        assertEquals("true", st.s("pairingRequired"))
        assertEquals("10.0.0.9:8090", st.s("address"))

        val wrong = c.postJson("/payments/terminal/pair", """{"host":"10.0.0.9:8090","code":"000000"}""")
        assertTrue(wrong.status.value in 400..499, wrong.bodyAsText())
        val paired = c.postJson("/payments/terminal/pair", """{"host":"10.0.0.9:8090","code":"${mac.pairingCode}"}""")
        assertEquals(HttpStatusCode.OK, paired.status, paired.bodyAsText())
        assertEquals("idle", paired.obj().s("readerState"))
        assertEquals("false", paired.obj().s("embedded"))

        // the Mac's reader screen plays the card; the tablet just polls
        val pid = c.start(id).s("paymentId")
        mac.present(dev.dwhipstock.pos.payments.terminal.EntryMode.TAP, SimulatedTerminalDevice.TestCard.AMEX, SimulatedTerminalDevice.Scenario.APPROVE)
        lan.down = true
        val offline = c.poll(pid)
        assertEquals("PENDING", offline.s("status"))
        assertEquals("true", offline.s("readerOffline"))
        lan.down = false
        assertEquals("RECORDED", c.poll(pid).s("status"))

        // unplugged: status says so, a new payment is a coded 503, cash still pays
        val (c2, id2) = checkOf("t3")
        lan.down = true
        val down = c2.get("/payments/terminal").obj()
        assertEquals("offline", down.s("readerState"))
        assertEquals("false", down.s("available"))
        val start = c2.postJson("/checks/$id2/terminal/payments")
        assertEquals(HttpStatusCode.ServiceUnavailable, start.status)
        assertEquals(TerminalException.UNAVAILABLE, start.obj().s("code"))
        assertEquals(HttpStatusCode.Created,
            c2.postJson("/checks/$id2/tenders", """{"type":"CASH","amountTenderedCents":2330}""").status)
        assertEquals(HttpStatusCode.OK, c2.post("/checks/$id2/finalize").status)
    }

    // --- J.P. Morgan online: the simulated reader in front of the (mocked) sandbox ---

    private val jpm = PaymentTerminalConfig.resolve({ if (it == PaymentTerminalConfig.KEY) "jpmorgan" else null }, "test")

    @Test
    fun `jpmorgan - the reader's card goes to J P Morgan, and the transaction id is on the tablet, the receipt and the refund`() = testApplication {
        val api = dev.dwhipstock.pos.payments.FakeJpmOnline()
        application { module(dbPath = tempDb(), paymentTerminal = jpm, terminalDevice = device(), jpmOnline = api) }
        val (c, id) = checkOf()
        val st = c.get("/payments/terminal").obj()
        assertEquals("jpmorgan", st.s("kind"))
        assertEquals("true", st.s("embedded"))
        val done = c.payByTerminal(id, entry = "tap", card = "visa")
        assertEquals("jpm-1", done["card"]!!.jsonObject.s("processorRef"))
        assertEquals(listOf("authorize", "capture"), api.calls.map { it.op })
        assertEquals(2328L, api.calls.first().amount)
        c.post("/checks/$id/finalize")
        val receipt = c.get("/checks/$id/receipt").obj().s("text")
        assertTrue("J.P. Morgan sandbox (test)" in receipt && "jpm-1" in receipt, receipt)

        val r = c.postJson("/checks/$id/refund", """{"amountCents":800,"tenderType":"TERMINAL","reason":"x","managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        val refund = api.calls.single { it.op == "refund" }
        assertEquals("jpm-1", refund.id)
        assertEquals(800L, refund.amount)
    }

    @Test
    fun `jpmorgan unreachable - the payment fails cleanly, cash and the counter card still work`() = testApplication {
        val api = dev.dwhipstock.pos.payments.FakeJpmOnline().apply { down = true }
        application { module(dbPath = tempDb(), paymentTerminal = jpm, terminalDevice = device(), jpmOnline = api) }
        val (c, id) = checkOf()
        val pid = c.start(id).s("paymentId")
        c.present("tap")
        val res = c.poll(pid)
        assertEquals("DECLINED", res.s("status"))
        assertEquals("processor_unavailable", res.s("declineCode"))
        assertEquals(HttpStatusCode.Created,
            c.postJson("/checks/$id/tenders/confirm", """{"type":"CARD","amountCents":2328}""").status)
    }

    @Test
    fun `jpmorgan decline buttons use the sandbox's trigger amounts`() = testApplication {
        val api = dev.dwhipstock.pos.payments.FakeJpmOnline()
        application { module(dbPath = tempDb(), paymentTerminal = jpm, terminalDevice = device(), jpmOnline = api) }
        val (c, id) = checkOf()
        val pid = c.start(id).s("paymentId")
        c.present("tap", outcome = "insufficient_funds")
        val res = c.poll(pid)
        assertEquals("DECLINED", res.s("status"))
        assertEquals("insufficient_funds", res.s("declineCode"))
        assertEquals(52100L, api.calls.single().amount)
    }

    @Test
    fun `pairing is manager-only`() = testApplication {
        application { module(dbPath = tempDb(), paymentTerminal = simulator, terminalDevice = device()) }
        val staff = loginClient("9999")
        val res = staff.postJson("/payments/terminal/pair", """{"host":"10.0.0.9","code":"123456"}""")
        assertEquals(HttpStatusCode.Forbidden, res.status, res.bodyAsText())
    }

    @Test
    fun `the POS side of the terminal stays behind the session gate`() = testApplication {
        application { module(dbPath = tempDb(), paymentTerminal = simulator, terminalDevice = device()) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/payments/terminal").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/checks/1/terminal/payments").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/terminal/payments/x").status)
        // the reader's own screen and buttons are public (a browser on the LAN plays the customer)
        val screen = client.get("/terminal/ui/state")
        assertEquals(HttpStatusCode.OK, screen.status)
        assertNotNull(screen.obj()["screen"])
    }
}
