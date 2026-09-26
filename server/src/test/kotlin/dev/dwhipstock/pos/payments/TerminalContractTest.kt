package dev.dwhipstock.pos.payments

import dev.dwhipstock.pos.FakeStripe
import dev.dwhipstock.pos.payments.jpm.JpmConnection
import dev.dwhipstock.pos.payments.jpm.JpmConnector
import dev.dwhipstock.pos.payments.jpm.JpmInStoreAdapter
import dev.dwhipstock.pos.payments.jpm.JpmOnlineAdapter
import dev.dwhipstock.pos.payments.jpm.JpmOnlineApi
import dev.dwhipstock.pos.payments.jpm.JpmOnlineHttp
import dev.dwhipstock.pos.payments.jpm.JpmPayment
import dev.dwhipstock.pos.payments.jpm.JpmTestCard
import dev.dwhipstock.pos.payments.simulator.InProcessSimulatorLink
import dev.dwhipstock.pos.payments.simulator.SimulatedTerminalDevice
import dev.dwhipstock.pos.payments.simulator.SimulatedTerminalDevice.Scenario
import dev.dwhipstock.pos.payments.simulator.SimulatedTerminalDevice.TestCard
import dev.dwhipstock.pos.payments.simulator.SimulatorTerminal
import dev.dwhipstock.pos.payments.terminal.EntryMode
import dev.dwhipstock.pos.payments.terminal.Outcome
import dev.dwhipstock.pos.payments.terminal.PaymentRequest
import dev.dwhipstock.pos.payments.terminal.PaymentResult
import dev.dwhipstock.pos.payments.terminal.PaymentTerminal
import dev.dwhipstock.pos.payments.terminal.ReaderState
import dev.dwhipstock.pos.payments.terminal.TerminalException
import dev.dwhipstock.pos.payments.terminal.TerminalRefundRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The [PaymentTerminal] contract, run against every adapter: the built-in
 * simulator, Stripe (a mocked Stripe API), J.P. Morgan online (the simulator
 * reader + a mocked J.P. Morgan API) and J.P. Morgan in-store (a fake Payment
 * Terminal Application speaking the documented JSON). Each harness knows how
 * to make "the customer" approve, decline, cancel or time out on its reader.
 */
abstract class TerminalContractTest {
    interface Harness {
        val terminal: PaymentTerminal
        fun approve(ref: String)
        fun decline(ref: String)
        fun customerCancel(ref: String)
        /** The reader gives up waiting (or, for a cloud API, the network times out). */
        fun timeout(ref: String)
        /** Cut the link to the terminal / processor. */
        fun goOffline()
        /** This terminal hands back card details (brand, last 4, entry mode) with an approval. */
        val reportsCard: Boolean get() = true
        /** [PaymentTerminal.status] notices [goOffline] (a cloud processor behind a local reader doesn't). */
        val statusSeesOffline: Boolean get() = true
    }

    abstract fun harness(): Harness

    private fun request(amount: Long = 2500) = PaymentRequest(UUID.randomUUID().toString(), amount, "USD", description = "check #1")

    /** Async adapters (in-store) answer on another thread: wait a little for the final result. */
    private fun settle(t: PaymentTerminal, ref: String): PaymentResult {
        val deadline = System.currentTimeMillis() + 3_000
        var r = t.result(ref)
        while (r.outcome == Outcome.PENDING && System.currentTimeMillis() < deadline) { Thread.sleep(20); r = t.result(ref) }
        return r
    }

    @Test
    fun `approve - authorized, captured once, card reported`() {
        val h = harness()
        val p = h.terminal.startPayment(request())
        assertEquals(Outcome.PENDING, h.terminal.result(p.terminalRef).outcome)
        h.approve(p.terminalRef)
        val approved = settle(h.terminal, p.terminalRef)
        assertEquals(Outcome.APPROVED, approved.outcome, approved.toString())
        val captured = h.terminal.capture(p.terminalRef, "cap-1")
        assertEquals(Outcome.APPROVED, captured.outcome)
        assertTrue(captured.captured, "money moved after capture")
        // capture again (a retry) stays approved and captured
        assertTrue(h.terminal.capture(p.terminalRef, "cap-1").captured)
        if (h.reportsCard) {
            val card = assertNotNull(captured.card ?: approved.card)
            assertNotNull(card.last4)
            assertTrue(card.last4!!.length == 4 && card.last4!!.all(Char::isDigit))
            assertNotNull(card.brand)
        }
    }

    @Test
    fun `decline - no money moves, a decline code comes back`() {
        val h = harness()
        val p = h.terminal.startPayment(request())
        h.decline(p.terminalRef)
        val r = settle(h.terminal, p.terminalRef)
        assertEquals(Outcome.DECLINED, r.outcome, r.toString())
        assertNotNull(r.declineCode)
        assertTrue(!r.captured)
    }

    @Test
    fun `cancel from the POS before a card`() {
        val h = harness()
        val p = h.terminal.startPayment(request())
        val r = h.terminal.cancel(p.terminalRef, "cancel-1")
        assertEquals(Outcome.CANCELLED, r.outcome, r.toString())
    }

    @Test
    fun `cancelled by the customer`() {
        val h = harness()
        val p = h.terminal.startPayment(request())
        h.customerCancel(p.terminalRef)
        assertEquals(Outcome.CANCELLED, settle(h.terminal, p.terminalRef).outcome)
    }

    @Test
    fun `timeout - a TIMEOUT result, or a clean unreachable error`() {
        val h = harness()
        val p = h.terminal.startPayment(request())
        h.timeout(p.terminalRef)
        val r = runCatching { settle(h.terminal, p.terminalRef) }
        r.onSuccess { assertEquals(Outcome.TIMEOUT, it.outcome, it.toString()) }
        r.onFailure { e -> assertTrue(e is TerminalException && e.unreachable, "unexpected $e") }
    }

    @Test
    fun `refunds - partial, then the rest`() {
        val h = harness()
        val p = h.terminal.startPayment(request(2500))
        h.approve(p.terminalRef)
        settle(h.terminal, p.terminalRef)
        val cap = h.terminal.capture(p.terminalRef, "cap")
        val processorRef = cap.card?.processorRef
        val r1 = h.terminal.refund(TerminalRefundRequest(p.terminalRef, 1000, "refund-1", processorRef = processorRef))
        assertEquals(Outcome.APPROVED, r1.outcome, r1.toString())
        assertTrue(r1.refundRef.isNotBlank())
        val r2 = h.terminal.refund(TerminalRefundRequest(p.terminalRef, 1500, "refund-2", processorRef = processorRef))
        assertEquals(Outcome.APPROVED, r2.outcome)
    }

    @Test
    fun `offline - status reports it instead of throwing, a payment is a clean 503`() {
        val h = harness()
        h.goOffline()
        val st = h.terminal.status()
        if (h.statusSeesOffline) assertTrue(st.state == ReaderState.OFFLINE || st.state == ReaderState.EXTERNAL, "state ${st.state}")
        val e = runCatching { h.terminal.startPayment(request()) }.exceptionOrNull()
        // a start either fails as unreachable, or (a cloud processor behind a local reader) starts on the reader
        if (e != null) assertTrue(e is TerminalException && e.unreachable, "unexpected $e")
    }
}

// --- the built-in simulator ----------------------------------------------------

class SimulatorContractTest : TerminalContractTest() {
    override fun harness() = object : Harness {
        var now = 0L
        val device = SimulatedTerminalDevice(clock = { now }, delays = SimulatedTerminalDevice.Delays.INSTANT)
        var offline = false
        val link = object : dev.dwhipstock.pos.payments.simulator.SimulatorLink by InProcessSimulatorLink(device) {
            override fun status() = if (offline) throw TerminalException(503, TerminalException.UNAVAILABLE, "down") else device.status()
            override fun start(req: dev.dwhipstock.pos.payments.simulator.SimStartRequest) =
                if (offline) throw TerminalException(503, TerminalException.UNAVAILABLE, "down")
                else device.start(req.reference, req.amountCents, req.currency, req.tipOnReader, req.timeoutSeconds, req.label)
        }
        override val terminal = SimulatorTerminal({ link }, 90)
        override fun approve(ref: String) { device.present(EntryMode.TAP, TestCard.VISA, Scenario.APPROVE) }
        override fun decline(ref: String) { device.present(EntryMode.SWIPE, TestCard.MASTERCARD, Scenario.INSUFFICIENT_FUNDS) }
        override fun customerCancel(ref: String) { device.customerCancel() }
        override fun timeout(ref: String) { now += 91_000 }
        override fun goOffline() { offline = true }
    }
}

// --- Stripe (mocked Stripe API) ------------------------------------------------

class StripeContractTest : TerminalContractTest() {
    override fun harness() = object : Harness {
        val fake = FakeStripe()
        override val terminal = StripeTerminalAdapter(StripeClient(fake))
        override fun approve(ref: String) = fake.authorize(ref)
        override fun decline(ref: String) = fake.decline(ref, "insufficient_funds")
        // Stripe: the tablet SDK cancels collection, then the store cancels the PaymentIntent
        override fun customerCancel(ref: String) { terminal.cancel(ref, "sdk-cancel") }
        override fun timeout(ref: String) { fake.failOn = { SocketTimeoutException("read timed out") } }
        override fun goOffline() { fake.failOn = { java.net.ConnectException("no route") } }
        // the fake returns no expanded charge, so no card fields (receipt says "Card (Stripe)")
        override val reportsCard = false
    }

    @Test
    fun `card fields come from the expanded charge`() {
        val pi = Json.parseToJsonElement("""{"id":"pi_1","status":"succeeded","amount":2500,
            "latest_charge":{"payment_method_details":{"card_present":{"brand":"visa","last4":"4242","read_method":"contactless_emv",
            "receipt":{"authorization_code":"123456","dedicated_file_name":"A0000000031010","terminal_verification_results":"0000000000",
            "transaction_status_information":"0000","application_preferred_name":"Visa Credit","cardholder_verification_method":"none"}}}}}""").jsonObject
        val card = assertNotNull(StripeTerminalAdapter.toResult(pi).card)
        assertEquals("Visa", card.brand)
        assertEquals("4242", card.last4)
        assertEquals(EntryMode.TAP, card.entryMode)
        assertEquals("A0000000031010", card.aid)
        assertEquals("123456", card.authCode)
    }
}

// --- J.P. Morgan online: the simulator reader + a mocked J.P. Morgan API ---------

class FakeJpmOnline(override val canDecline: Boolean = true) : JpmOnlineApi {
    data class Call(val op: String, val id: String?, val amount: Long?, val requestId: String)
    val calls = mutableListOf<Call>()
    var down = false
    private var seq = 0
    override val label = "J.P. Morgan sandbox (test)"
    override fun testCard(brand: String, scenario: String) = JpmOnlineHttp.sandboxCard(brand, scenario)
    private fun check() { if (down) throw TerminalException(503, TerminalException.UNAVAILABLE, "J.P. Morgan unreachable") }
    override fun authorize(requestId: String, amountCents: Long, currency: String, card: JpmTestCard, orderRef: String): JpmPayment {
        check(); calls += Call("authorize", null, amountCents, requestId)
        val id = "jpm-${++seq}"
        return when (amountCents) {
            52100L -> JpmPayment(id, false, "DECLINED", "INSUFFICIENT_FUNDS", "Insufficient funds")
            53000L -> JpmPayment(id, false, "DECLINED", "DO_NOT_HONOR", "Do not honor")
            else -> JpmPayment(id, true, "AUTHORIZED", "APPROVED", "Transaction approved", "tst${seq}01")
        }
    }
    override fun capture(transactionId: String, amountCents: Long, requestId: String): JpmPayment {
        check(); calls += Call("capture", transactionId, amountCents, requestId); return JpmPayment(transactionId, true, "CLOSED", "ACCEPTED")
    }
    override fun void(transactionId: String, requestId: String): JpmPayment {
        check(); calls += Call("void", transactionId, null, requestId); return JpmPayment(transactionId, true, "VOIDED")
    }
    override fun refund(transactionId: String, amountCents: Long, currency: String, requestId: String): JpmPayment {
        check(); calls += Call("refund", transactionId, amountCents, requestId); return JpmPayment("jpmre-${++seq}", true, "CLOSED", "ACCEPTED")
    }
}

class JpmOnlineContractTest : TerminalContractTest() {
    override fun harness() = object : Harness {
        var now = 0L
        val device = SimulatedTerminalDevice(clock = { now }, delays = SimulatedTerminalDevice.Delays.INSTANT)
        val api = FakeJpmOnline()
        override val terminal = JpmOnlineAdapter({ InProcessSimulatorLink(device) }, api, 90)
        override fun approve(ref: String) { device.present(EntryMode.INSERT, TestCard.AMEX, Scenario.APPROVE); device.enterPin("1234") }
        override fun decline(ref: String) { device.present(EntryMode.TAP, TestCard.VISA, Scenario.DO_NOT_HONOUR) }
        override fun customerCancel(ref: String) { device.customerCancel() }
        override fun timeout(ref: String) { now += 91_000 }
        override fun goOffline() { api.down = true }
        override val statusSeesOffline = false
    }

    @Test
    fun `the J P Morgan transaction id rides on the card, and refunds go to J P Morgan`() {
        val h = harness()
        val t = h.terminal
        val p = t.startPayment(PaymentRequest("ref-1", 2500, "USD"))
        h.approve(p.terminalRef)
        val r = t.result(p.terminalRef)
        assertEquals(Outcome.APPROVED, r.outcome)
        assertEquals("jpm-1", r.card?.processorRef)
        assertEquals("J.P. Morgan sandbox (test)", r.card?.processor)
        t.capture(p.terminalRef, "cap")
        val refund = t.refund(TerminalRefundRequest(p.terminalRef, 500, "rf", processorRef = "jpm-1"))
        assertEquals(Outcome.APPROVED, refund.outcome)
    }

    @Test
    fun `J P Morgan unreachable - the reader shows a decline, nothing is captured`() {
        val h = harness()
        val p = h.terminal.startPayment(PaymentRequest("ref-2", 2500, "USD"))
        h.goOffline()
        h.approve(p.terminalRef)
        val r = h.terminal.result(p.terminalRef)
        assertEquals(Outcome.DECLINED, r.outcome)
        assertEquals(JpmOnlineAdapter.PROCESSOR_UNAVAILABLE, r.declineCode)
    }

    @Test
    fun `not configured - a payment is refused up front`() {
        val t = JpmOnlineAdapter({ InProcessSimulatorLink(SimulatedTerminalDevice()) }, null, 90)
        val e = assertFailsWith<TerminalException> { t.startPayment(PaymentRequest("r", 100, "USD")) }
        assertEquals("jpm_not_configured", e.code)
        assertEquals(ReaderState.OFFLINE, t.status().state)
    }
}

// --- J.P. Morgan in-store: a fake Payment Terminal Application ------------------

/**
 * Speaks the documented Payment Terminal Application messages: `Transaction`
 * (SALE / REFUND / VOID) answered by `Status` notifications then a final
 * message, `Cancel` (result 0, then the sale ends with result 12),
 * `GetInformation`, `LastTransaction`.
 */
class FakeJpmTerminal : JpmConnection {
    private val json = Json { ignoreUnknownKeys = true }
    private val outbox = LinkedBlockingQueue<String>()
    @Volatile var sale: JsonObject? = null
    @Volatile var down = false
    @Volatile override var open = true
    val sent = mutableListOf<JsonObject>()

    override fun send(json: String) {
        if (down) throw IOException("link down")
        val o = this.json.parseToJsonElement(json).jsonObject
        synchronized(sent) { sent += o }
        fun s(k: String) = o[k]?.jsonPrimitive?.content
        when (s("operation")) {
            "GetInformation" -> emit(buildJsonObject { put("operation", "GetInformation"); put("result", "0")
                put("information", buildJsonObject { put("model", "AXIUM DX8000"); put("serialNumber", "TEST-1") }) })
            "Cancel" -> {
                emit(buildJsonObject { put("operation", "Cancel"); put("result", if (sale != null) "0" else "14") })
                if (sale != null) finish("12", null)
            }
            "LastTransaction" -> emit(buildJsonObject { put("operation", "LastTransaction"); put("result", "97") })
            "Transaction" -> when (s("type")) {
                "SALE" -> { sale = o; emit(buildJsonObject { put("operation", "Status"); put("context", "Card"); put("result", "0") }) }
                "REFUND", "VOID" -> emit(final(o, "0", "approved", s("requestedAmount") ?: "0"))
            }
        }
    }

    private fun final(req: JsonObject, result: String, approval: String?, amount: String) = buildJsonObject {
        put("operation", "Transaction"); put("type", req["type"]!!.jsonPrimitive.content); put("result", result)
        approval?.let { put("approval", it) }
        put("requestedAmount", amount); put("authorizedAmount", amount); put("totalAmount", amount); put("tipAmount", "0")
        put("account", "476173******0011"); put("cardBrand", "VISA"); put("entryMode", "Contactless")
        put("authCode", if (approval == "approved") "006830" else ""); put("AID", "A0000000031010"); put("TVR", "0000000000")
        put("TSI", "0000"); put("preferredName", "VISA CREDIT"); put("cvm", "NO CVM"); put("responseCode", if (approval == "approved") "00" else "05")
        put("hostMessage", if (approval == "approved") "APPROVED" else "DO NOT HONOR")
        put("transactionID", "0920000011000002")
        req["uniqueTransactionId"]?.let { put("uniqueTransactionId", it) }
    }

    fun finish(result: String, approval: String?) {
        val s = sale ?: return
        sale = null
        emit(final(s, result, approval, s["requestedAmount"]!!.jsonPrimitive.content))
    }

    private fun emit(o: JsonObject) { outbox.put(o.toString()) }

    override fun receive(timeoutMs: Int): String? {
        if (down) throw IOException("link down")
        return outbox.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
    }

    override fun close() { open = false }
}

class JpmInStoreContractTest : TerminalContractTest() {
    class InStore : Harness {
        val fake = FakeJpmTerminal()
        var offline = false
        override val terminal = JpmInStoreAdapter("192.168.1.2", 8442, JpmConnector { _, _ ->
            if (offline) throw IOException("no route to host") else fake
        }, 90)
        override fun approve(ref: String) = fake.finish("0", "approved")
        override fun decline(ref: String) = fake.finish("19", "declined")
        override fun customerCancel(ref: String) = fake.finish("11", null)
        override fun timeout(ref: String) = fake.finish("10", null)
        override fun goOffline() { offline = true; fake.open = false }
    }

    override fun harness() = InStore()

    @Test
    fun `requests follow the documented shapes`() {
        val h = harness()
        val p = h.terminal.startPayment(PaymentRequest("5b1c-ref-uuid-0001", 1234, "USD",
            tipMode = dev.dwhipstock.pos.payments.terminal.TipMode.ON_READER))
        val fake = h.fake
        val sale = synchronized(fake.sent) { fake.sent.last { it["operation"]?.jsonPrimitive?.content == "Transaction" } }
        assertEquals("SALE", sale["type"]!!.jsonPrimitive.content)
        assertEquals("1234", sale["requestedAmount"]!!.jsonPrimitive.content)
        assertEquals(p.terminalRef, sale["uniqueTransactionId"]!!.jsonPrimitive.content)
        assertTrue(p.terminalRef.all(Char::isLetterOrDigit) && p.terminalRef.length <= 30)
        assertTrue("\"TIP\"" in sale.toString() && "\"1\"" in sale.toString())
        h.approve(p.terminalRef)
        Thread.sleep(100)
        val r = h.terminal.result(p.terminalRef)
        assertEquals("Visa", r.card?.brand)
        assertEquals("0011", r.card?.last4)
        assertEquals("006830", r.card?.authCode)
        assertEquals("0920000011000002", r.card?.processorRef)
    }
}
