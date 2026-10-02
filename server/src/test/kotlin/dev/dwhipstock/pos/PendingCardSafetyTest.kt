package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.Tenders
import dev.dwhipstock.pos.payments.PendingCardPayments
import dev.dwhipstock.pos.payments.simulator.InProcessSimulatorLink
import dev.dwhipstock.pos.payments.simulator.SimulatedTerminalDevice
import dev.dwhipstock.pos.payments.simulator.SimulatorLink
import dev.dwhipstock.pos.payments.terminal.EntryMode
import dev.dwhipstock.pos.payments.terminal.TerminalException
import dev.dwhipstock.pos.restaurant.StripePayments
import dev.dwhipstock.pos.restaurant.TerminalPayments
import dev.dwhipstock.pos.sdk.PaymentTerminalConfig
import dev.dwhipstock.pos.sdk.StripeConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A card payment still on the reader when the store (or the tablet) lost
 * power: settled after the restart (recorded once, or ended and the check
 * unlocked), and nothing else can take money on that check until it is.
 */
class PendingCardSafetyTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-pending-card").resolve("pos.db").toString()
    private val simulator = PaymentTerminalConfig.resolve({ if (it == PaymentTerminalConfig.KEY) "simulator" else null }, "test")
    private val stripeKey = StripeConfig.of("sk_test_" + "unit0000", null, "test")

    private val clock = AtomicLong(1_000_000L)
    private fun device() = SimulatedTerminalDevice(clock = clock::get, delays = SimulatedTerminalDevice.Delays.INSTANT,
        random = kotlin.random.Random(7))

    private suspend fun HttpClient.postJson(path: String, body: String = "{}") =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }
    private suspend fun HttpResponse.obj(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    /** Manager session, open shift, a $21.92 check. */
    private suspend fun ApplicationTestBuilder.checkOf(table: String = "t5-5", shift: Boolean = true): Pair<HttpClient, Int> {
        val c = loginClient()
        if (shift) c.postJson("/shifts", """{"openingFloatCents":10000,"managerPin":"1234"}""")
        val id = c.postJson("/tables/$table/checks", """{"userId":"manager"}""").obj()["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$id/lines", """{"itemId":"lantern-lager","variantId":"lantern-lager:pitcher","qty":1}""")
        return c to id
    }

    private suspend fun HttpClient.startCard(checkId: Int, body: String = "{}"): String =
        postJson("/checks/$checkId/terminal/payments", body).also {
            assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText())
        }.obj().s("paymentId")

    private suspend fun HttpClient.cash(checkId: Int, cents: Long, groupId: Int? = null) =
        postJson("/checks/$checkId/tenders", """{"type":"CASH","amountTenderedCents":$cents${groupId?.let { ",\"groupId\":$it" } ?: ""}}""")

    private fun tenders(checkId: Int) = transaction { Tenders.selectAll().where { Tenders.transactionId eq checkId }.map { it[Tenders.type] } }
    private fun terminalStatus(pid: String) = transaction {
        TerminalPayments.selectAll().where { TerminalPayments.publicId eq pid }.single()[TerminalPayments.status]
    }
    private suspend fun HttpClient.checkStatus(id: Int) = get("/checks/$id").obj().s("status")

    // --- restart -------------------------------------------------------------

    @Test
    fun `restart with the card approved on the reader - recorded once, however many sweeps`() {
        val db = tempDb()
        val reader = device() // the reader keeps its own state through the store's restart
        var checkId = 0
        var pid = ""
        testApplication {
            application { module(dbPath = db, paymentTerminal = simulator, terminalDevice = reader, cardSweepSeconds = 0) }
            val (c, id) = checkOf()
            checkId = id
            pid = c.startCard(id)
        }
        // power is out at the store; the guest taps anyway and the reader approves
        reader.present(EntryMode.TAP, SimulatedTerminalDevice.TestCard.VISA, SimulatedTerminalDevice.Scenario.APPROVE)
        assertEquals("PENDING", transaction { terminalStatus(pid) })

        var sweeper: PendingCardPayments? = null
        testApplication {
            application {
                module(dbPath = db, paymentTerminal = simulator, terminalDevice = reader, cardSweepSeconds = 0,
                    onPendingCards = { sweeper = it })
            }
            val c = loginClient()
            val s = assertNotNull(sweeper)
            // the startup sweep, a timer sweep and a cashier opening the check, all at once
            val threads = (1..4).map { thread { s.sweep() } }
            val view = c.get("/checks/$checkId/card-pending").obj()
            threads.forEach { it.join() }
            s.sweep()
            assertEquals(0, view["pending"]!!.jsonArray.size, view.toString())
            assertEquals("RECORDED", terminalStatus(pid))
            assertEquals(listOf("TERMINAL"), tenders(checkId))
            val check = c.get("/checks/$checkId").obj()
            assertEquals(0L, check["outstandingCents"]!!.jsonPrimitive.content.toLong())
            assertFalse(check["cardPaymentPending"]!!.jsonPrimitive.boolean)
            // and the cash the cashier would have taken is refused: already paid
            val cash = c.cash(checkId, 2200)
            assertEquals(HttpStatusCode.Conflict, cash.status)
            assertEquals("already_paid", cash.obj().s("code"))
            assertEquals(HttpStatusCode.OK, c.post("/checks/$checkId/finalize").status)
            assertEquals(listOf("TERMINAL"), tenders(checkId))
        }
    }

    @Test
    fun `restart with the card declined on the reader - ended, the check unlocked, cash pays`() {
        val db = tempDb()
        val reader = device()
        var checkId = 0
        var pid = ""
        testApplication {
            application { module(dbPath = db, paymentTerminal = simulator, terminalDevice = reader, cardSweepSeconds = 0) }
            val (c, id) = checkOf()
            checkId = id
            pid = c.startCard(id)
            assertEquals("TOTAL_LOCKED", c.checkStatus(id))
        }
        reader.present(EntryMode.TAP, SimulatedTerminalDevice.TestCard.VISA, SimulatedTerminalDevice.Scenario.INSUFFICIENT_FUNDS)
        var sweeper: PendingCardPayments? = null
        testApplication {
            application {
                module(dbPath = db, paymentTerminal = simulator, terminalDevice = reader, cardSweepSeconds = 0,
                    onPendingCards = { sweeper = it })
            }
            val c = loginClient()
            assertNotNull(sweeper).sweep()
            assertEquals("DECLINED", terminalStatus(pid))
            assertEquals("OPEN", c.checkStatus(checkId), "nothing was taken: the check opens again")
            assertTrue(tenders(checkId).isEmpty())
            // items can be added again, and cash settles the (re-locked) bill
            val more = c.postJson("/checks/$checkId/lines", """{"itemId":"lantern-lager","variantId":"lantern-lager:pitcher","qty":1}""")
            assertTrue(more.status.isSuccess(), more.bodyAsText())
            val due = c.get("/checks/$checkId").obj()["cashDueCents"]!!.jsonPrimitive.content.toLong()
            assertEquals(HttpStatusCode.Created, c.cash(checkId, due).status)
            assertEquals(HttpStatusCode.OK, c.post("/checks/$checkId/finalize").status)
        }
    }

    @Test
    fun `restart - a payment the built-in reader has no record of is closed, not left blocking`() {
        val db = tempDb()
        var checkId = 0
        testApplication {
            application { module(dbPath = db, paymentTerminal = simulator, terminalDevice = device(), cardSweepSeconds = 0) }
            val (c, id) = checkOf()
            checkId = id
            c.startCard(id)
        }
        // the built-in simulator restarted with the store: a new reader, no memory of the sale
        testApplication {
            application { module(dbPath = db, paymentTerminal = simulator, terminalDevice = device(), cardSweepSeconds = 0) }
            val c = loginClient()
            assertEquals(HttpStatusCode.Created, c.cash(checkId, 2190).status)
            assertEquals(HttpStatusCode.OK, c.post("/checks/$checkId/finalize").status)
            assertEquals(listOf("CASH"), tenders(checkId))
        }
    }

    // --- blocked while pending -------------------------------------------------

    @Test
    fun `cash, a hand-keyed card, void and close wait while the card is on the reader, then go once it ends`() = testApplication {
        application { module(dbPath = tempDb(), paymentTerminal = simulator, terminalDevice = device(), cardSweepSeconds = 0) }
        val (c, id) = checkOf()
        val pid = c.startCard(id)
        val check = c.get("/checks/$id").obj()
        assertTrue(check["cardPaymentPending"]!!.jsonPrimitive.boolean)

        val cash = c.cash(id, 2190)
        assertEquals(HttpStatusCode.Conflict, cash.status)
        assertEquals("card_payment_pending", cash.obj().s("code"))
        assertEquals(pid, cash.obj()["pending"]!!.jsonArray.single().jsonObject.s("paymentId"))
        assertEquals("card_payment_pending",
            c.postJson("/checks/$id/tenders/confirm", """{"type":"CARD","amountCents":2192}""").obj().s("code"))
        assertEquals("card_payment_pending",
            c.postJson("/checks/$id/tenders/initiate", """{"type":"CARD","amountCents":2192}""").obj().s("code"))
        assertEquals("card_payment_pending",
            c.postJson("/checks/$id/void", """{"reason":"walked out","managerPin":"1234"}""").obj().s("code"))
        assertEquals("card_payment_pending", c.post("/checks/$id/finalize").obj().s("code"))

        val pending = c.get("/checks/$id/card-pending").obj()
        assertEquals(pid, pending["pending"]!!.jsonArray.single().jsonObject.s("paymentId"))
        assertEquals("present_card", pending["pending"]!!.jsonArray.single().jsonObject.s("prompt"))

        // the guest cancels on the reader; nobody polls — the cash tender settles it first
        assertEquals(HttpStatusCode.OK, c.postJson("/terminal/ui/cancel").status)
        assertEquals(HttpStatusCode.Created, c.cash(id, 2190).status)
        assertEquals("CANCELED", terminalStatus(pid))
        assertEquals(HttpStatusCode.OK, c.post("/checks/$id/finalize").status)
        assertEquals(listOf("CASH"), tenders(id))
    }

    @Test
    fun `split check - the other group pays freely, the card's group and the close wait`() = testApplication {
        application { module(dbPath = tempDb(), paymentTerminal = simulator, terminalDevice = device(), cardSweepSeconds = 0) }
        val (c, id) = checkOf()
        val split = c.postJson("/checks/$id/split", """{"groups":2,"even":true}""").obj()["split"]!!.jsonObject
        val (g1, g2) = split["groups"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.int }
        val pid = c.startCard(id, """{"groupId":$g1}""")

        val same = c.cash(id, 1100, g1)
        assertEquals(HttpStatusCode.Conflict, same.status)
        assertEquals("card_payment_pending", same.obj().s("code"))
        assertEquals(HttpStatusCode.Created, c.cash(id, 1100, g2).status, "another guest's share isn't on the reader")
        assertEquals("card_payment_pending", c.post("/checks/$id/finalize").obj().s("code"))

        c.postJson("/terminal/ui/present", """{"entry":"tap","card":"visa","outcome":"approve"}""")
        // the close settles the card first: recorded, then closed
        assertEquals(HttpStatusCode.OK, c.post("/checks/$id/finalize").status)
        assertEquals("RECORDED", terminalStatus(pid))
        assertEquals(listOf("CASH", "TERMINAL"), tenders(id).sorted())
    }

    // --- manager cancel --------------------------------------------------------

    @Test
    fun `manager cancel - needs a manager, cancels on the reader, unlocks the check`() = testApplication {
        val reader = device()
        application { module(dbPath = tempDb(), paymentTerminal = simulator, terminalDevice = reader, cardSweepSeconds = 0) }
        val (c, id) = checkOf()
        val pid = c.startCard(id)
        val staff = loginClient("9999")
        val refused = staff.postJson("/checks/$id/card-pending/$pid/cancel")
        assertEquals(HttpStatusCode.Forbidden, refused.status, refused.bodyAsText())
        assertEquals("PENDING", terminalStatus(pid))

        val res = staff.postJson("/checks/$id/card-pending/$pid/cancel", """{"managerPin":"1234"}""")
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val body = res.obj()
        assertEquals(0, body["pending"]!!.jsonArray.size)
        assertEquals("CANCELED", body["resolved"]!!.jsonArray.single().jsonObject.s("status"))
        assertEquals("CANCELED", terminalStatus(pid))
        assertEquals("OPEN", body["check"]!!.jsonObject.s("status"))
        assertEquals("idle", c.get("/terminal/ui/state").obj().s("screen").let { if (it == "cancelled") "idle" else it })
        assertEquals(HttpStatusCode.Created, c.cash(id, 2190).status)
    }

    @Test
    fun `manager cancel on a card the reader already approved - recorded, not cancelled`() = testApplication {
        val reader = device()
        application { module(dbPath = tempDb(), paymentTerminal = simulator, terminalDevice = reader, cardSweepSeconds = 0) }
        val (c, id) = checkOf()
        val pid = c.startCard(id)
        reader.present(EntryMode.TAP, SimulatedTerminalDevice.TestCard.VISA, SimulatedTerminalDevice.Scenario.APPROVE)
        val res = c.postJson("/checks/$id/card-pending/$pid/cancel", """{"managerPin":"1234"}""").obj()
        assertEquals("RECORDED", res["resolved"]!!.jsonArray.single().jsonObject.s("status"))
        assertEquals(listOf("TERMINAL"), tenders(id))
        assertEquals(0L, res["check"]!!.jsonObject["outstandingCents"]!!.jsonPrimitive.content.toLong())
    }

    /** A LAN link that can be unplugged. */
    private class Unpluggable(val inner: SimulatorLink) : SimulatorLink by inner {
        @Volatile var down = false
        private fun check() { if (down) throw TerminalException(503, TerminalException.UNAVAILABLE, "card terminal can't be reached") }
        override val embedded = false
        override val address = "10.0.0.9:8090"
        override fun status() = check().let { inner.status() }
        override fun pair(code: String) = check().let { inner.pair(code) }
        override fun start(req: dev.dwhipstock.pos.payments.simulator.SimStartRequest) = check().let { inner.start(req) }
        override fun get(id: String) = check().let { inner.get(id) }
        override fun cancel(id: String) = check().let { inner.cancel(id) }
        override fun capture(id: String, amountCents: Long?) = check().let { inner.capture(id, amountCents) }
    }

    @Test
    fun `reader unreachable - stays pending (readerOffline), cancel can't be confirmed, cash waits`() = testApplication {
        val lanReader = SimulatedTerminalDevice(requirePairing = true, clock = clock::get, delays = SimulatedTerminalDevice.Delays.INSTANT)
        val lan = Unpluggable(InProcessSimulatorLink(lanReader))
        val cfg = PaymentTerminalConfig.resolve({
            when (it) { PaymentTerminalConfig.KEY -> "simulator"; PaymentTerminalConfig.KEY_HOST -> "10.0.0.9:8090"; else -> null }
        }, "test")
        var sweeper: PendingCardPayments? = null
        application {
            module(dbPath = tempDb(), paymentTerminal = cfg, terminalDevice = device(), cardSweepSeconds = 0,
                simulatorLinkFactory = { _, _, _ -> lan }, onPendingCards = { sweeper = it })
        }
        val (c, id) = checkOf()
        assertEquals(HttpStatusCode.OK, c.postJson("/payments/terminal/pair", """{"host":"10.0.0.9:8090","code":"${lanReader.pairingCode}"}""").status)
        val pid = c.startCard(id)
        lan.down = true

        assertEquals(0, assertNotNull(sweeper).sweep())
        val pending = c.get("/checks/$id/card-pending").obj()["pending"]!!.jsonArray.single().jsonObject
        assertEquals("true", pending.s("readerOffline"))
        // the cashier's cancel no longer closes it blind
        val cancel = c.postJson("/terminal/payments/$pid/cancel")
        assertEquals(HttpStatusCode.ServiceUnavailable, cancel.status, cancel.bodyAsText())
        val mgr = c.postJson("/checks/$id/card-pending/$pid/cancel", """{"managerPin":"1234"}""")
        assertEquals(HttpStatusCode.ServiceUnavailable, mgr.status, mgr.bodyAsText())
        assertEquals("card_cancel_unconfirmed", mgr.obj().s("code"))
        assertEquals("PENDING", terminalStatus(pid))
        assertEquals("card_payment_pending", c.cash(id, 2190).obj().s("code"))

        // back online, the guest had tapped meanwhile: the sweep records it
        lanReader.present(EntryMode.TAP, SimulatedTerminalDevice.TestCard.AMEX, SimulatedTerminalDevice.Scenario.APPROVE)
        lan.down = false
        sweeper!!.sweep()
        assertEquals(listOf("TERMINAL"), tenders(id))
    }

    // --- Stripe (the tablet's SDK) ---------------------------------------------

    @Test
    fun `stripe - the tablet died after the card was authorised - the sweep captures and records it once`() {
        val db = tempDb()
        val fake = FakeStripe()
        var checkId = 0
        var pi = ""
        testApplication {
            application { module(dbPath = db, stripeConfig = stripeKey, stripeHttp = fake, cardSweepSeconds = 0) }
            val (c, id) = checkOf()
            checkId = id
            pi = c.postJson("/checks/$id/stripe/intents").obj().s("paymentIntentId")
        }
        fake.authorize(pi)
        var sweeper: PendingCardPayments? = null
        testApplication {
            application {
                module(dbPath = db, stripeConfig = stripeKey, stripeHttp = fake, cardSweepSeconds = 0, onPendingCards = { sweeper = it })
            }
            val c = loginClient()
            val s = assertNotNull(sweeper)
            s.sweep()
            s.sweep()
            assertEquals("succeeded", fake.status(pi))
            assertEquals(listOf("STRIPE"), tenders(checkId))
            assertEquals(1, fake.callsTo("/v1/payment_intents/$pi/capture").size)
            assertEquals(HttpStatusCode.OK, c.post("/checks/$checkId/finalize").status)
        }
    }

    @Test
    fun `stripe - no card ever authorised - released after a while, or at once by a manager`() = testApplication {
        val fake = FakeStripe()
        var sweeper: PendingCardPayments? = null
        application { module(dbPath = tempDb(), stripeConfig = stripeKey, stripeHttp = fake, cardSweepSeconds = 0, onPendingCards = { sweeper = it }) }
        val (c, id) = checkOf()
        val a = c.postJson("/checks/$id/stripe/intents").obj()
        val s = assertNotNull(sweeper)
        assertEquals(0, s.sweep(), "a fresh PaymentIntent is the tablet's reader at work")
        // the tablet never came back: abandoned
        transaction {
            StripePayments.update({ StripePayments.publicId eq a.s("paymentId") }) {
                it[createdAt] = Instant.now().minusSeconds(600)
            }
        }
        s.sweep()
        assertEquals("canceled", fake.status(a.s("paymentIntentId")))
        assertEquals("OPEN", c.checkStatus(id))

        // the manager's cancel, right away
        val b = c.postJson("/checks/$id/stripe/intents").obj()
        assertEquals("card_payment_pending", c.cash(id, 2190).obj().s("code"))
        val res = c.postJson("/checks/$id/card-pending/${b.s("paymentId")}/cancel", """{"managerPin":"1234"}""")
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertEquals("CANCELED", res.obj()["resolved"]!!.jsonArray.single().jsonObject.s("status"))
        assertEquals("canceled", fake.status(b.s("paymentIntentId")))
        assertEquals(HttpStatusCode.Created, c.cash(id, 2190).status)
        assertTrue(fake.callsTo("/v1/payment_intents/${b.s("paymentIntentId")}/capture").isEmpty())
    }
}
