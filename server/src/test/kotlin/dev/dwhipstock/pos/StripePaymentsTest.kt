package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.Tenders
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.payments.StripeHttp
import dev.dwhipstock.pos.payments.StripeHttpResponse
import dev.dwhipstock.pos.restaurant.Refunds
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
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * In-memory Stripe: enough of the REST API for the store's Terminal flow. The
 * test plays the simulated reader with [authorize] / [decline]; [failOn] makes
 * a matching call throw like a dead network or a timeout.
 */
class FakeStripe(
    var country: String = "CA",
    var currency: String = "cad",
) : StripeHttp {
    data class Call(val method: String, val path: String, val params: Map<String, String>, val idempotencyKey: String?)

    val calls = mutableListOf<Call>()
    var failOn: (Call) -> IOException? = { null }
    private val intents = mutableMapOf<String, MutableMap<String, Any?>>()
    private var seq = 0

    fun authorize(pi: String) { intents[pi]!!["status"] = "requires_capture" }
    fun decline(pi: String, code: String = "card_declined") {
        intents[pi]!!["status"] = "requires_payment_method"
        intents[pi]!!["error"] = code
    }
    fun status(pi: String) = intents[pi]!!["status"]
    fun callsTo(path: String) = calls.filter { it.path.startsWith(path) }

    private fun pi(id: String): String {
        val p = intents[id]!!
        val err = p["error"]?.let { ""","last_payment_error":{"type":"card_error","code":"card_declined","decline_code":"$it","message":"Your card was declined."}""" } ?: ""
        return """{"id":"$id","object":"payment_intent","amount":${p["amount"]},"currency":"${p["currency"]}",
            |"status":"${p["status"]}","client_secret":"${id}_secret_x","capture_method":"manual",
            |"metadata":{"pos_payment_id":"${p["pos_payment_id"]}"}$err}""".trimMargin()
    }

    override fun send(method: String, path: String, params: List<Pair<String, String>>, idempotencyKey: String?): StripeHttpResponse {
        val call = Call(method, path, params.toMap(), idempotencyKey)
        calls += call
        failOn(call)?.let { throw it }
        fun ok(body: String) = StripeHttpResponse(200, body)
        return when {
            path == "/v1/account" -> ok("""{"id":"acct_fake","country":"$country","default_currency":"$currency"}""")
            path == "/v1/terminal/locations" && method == "GET" -> ok("""{"data":[]}""")
            path == "/v1/terminal/locations" -> ok("""{"id":"tml_fake"}""")
            path == "/v1/terminal/connection_tokens" -> ok("""{"secret":"pst_test_fake"}""")
            path == "/v1/payment_intents" -> {
                // same idempotency key → same PaymentIntent, like Stripe
                val existing = intents.entries.firstOrNull { it.value["idem"] == idempotencyKey }?.key
                val id = existing ?: "pi_${++seq}".also {
                    intents[it] = mutableMapOf("amount" to call.params["amount"], "currency" to call.params["currency"],
                        "status" to "requires_payment_method", "pos_payment_id" to call.params["metadata[pos_payment_id]"],
                        "idem" to idempotencyKey)
                }
                ok(pi(id))
            }
            path.startsWith("/v1/payment_intents/") -> {
                val parts = path.removePrefix("/v1/payment_intents/").split("/")
                val id = parts[0]
                when (parts.getOrNull(1)) {
                    "capture" -> { intents[id]!!["status"] = "succeeded"; ok(pi(id)) }
                    "cancel" -> { intents[id]!!["status"] = "canceled"; ok(pi(id)) }
                    else -> ok(pi(id))
                }
            }
            path == "/v1/refunds" -> ok("""{"id":"re_${++seq}","status":"succeeded","amount":${call.params["amount"] ?: 0}}""")
            else -> StripeHttpResponse(404, """{"error":{"type":"invalid_request_error","message":"no route $path"}}""")
        }
    }
}

class StripePaymentsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val testKey = StripeConfig.of("sk_test_" + "unit0000", null, "test")
    private fun tempDb() = Files.createTempDirectory("pos-stripe-test").resolve("pos.db").toString()

    private suspend fun HttpClient.postJson(path: String, body: String = "{}") =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpResponse.obj(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject

    /** Manager session with an open shift and a $22.50 check on [table]. */
    private suspend fun ApplicationTestBuilder.checkOf(table: String = "t5-5"): Pair<HttpClient, Int> {
        val c = loginClient()
        c.postJson("/shifts", """{"openingFloatCents":10000,"managerPin":"1234"}""")
        val id = c.postJson("/tables/$table/checks", """{"userId":"manager"}""").obj()["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$id/lines", """{"itemId":"lantern-lager","variantId":"lantern-lager:pitcher","qty":1}""")
        return c to id
    }

    private suspend fun HttpClient.intent(checkId: Int): JsonObject =
        postJson("/checks/$checkId/stripe/intents").also {
            assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText())
        }.obj()

    private suspend fun HttpClient.payCash(checkId: Int, cents: Long) {
        assertEquals(HttpStatusCode.Created,
            postJson("/checks/$checkId/tenders", """{"type":"CASH","amountTenderedCents":$cents}""").status)
        assertEquals(HttpStatusCode.OK, post("/checks/$checkId/finalize").status)
    }

    private fun outboxPayloads(): List<String> = transaction { SyncOutbox.selectAll().map { it[SyncOutbox.payload] } }

    // --- config -------------------------------------------------------------

    @Test
    fun `only sk_test_ keys are accepted and the key is never printed`() {
        val live = StripeConfig.of("sk_live_" + "abc123", null, "STRIPE_KEY")
        assertFalse(live.enabled)
        assertEquals(StripeConfig.Disabled.NOT_TEST_KEY, live.disabled)
        assertFalse("abc123" in live.describe())
        assertFalse(StripeConfig.of("rk_test_" + "abc", null, "x").enabled, "restricted keys refused")
        assertFalse(StripeConfig.of("pk_test_" + "abc", null, "x").enabled, "publishable keys refused")
        assertFalse(StripeConfig.of("sk_test_", null, "x").enabled, "empty test key refused")
        assertEquals(StripeConfig.Disabled.NOT_CONFIGURED, StripeConfig.of("  ", null, "x").disabled)

        val ok = StripeConfig.fromEnv { mapOf("STRIPE_KEY" to "sk_test_" + "secret42", "STRIPE_LOCATION_ID" to "tml_9")[it] }
        assertTrue(ok.enabled)
        assertEquals("tml_9", ok.locationId)
        assertFalse("secret42" in ok.describe())
        assertFalse("secret42" in ok.toString())

        val file = Files.createTempFile("store", ".properties").toFile()
        file.writeText("print.receipts=digital\nstripe.secretKey=sk_live_" + "zzz\n")
        assertEquals(StripeConfig.Disabled.NOT_TEST_KEY, StripeConfig.fromFile(file).disabled)
        file.writeText("stripe.secretKey=sk_test_" + "yyy\nstripe.locationId=tml_2\n")
        assertTrue(StripeConfig.fromFile(file).enabled)
        assertEquals(StripeConfig.Disabled.NOT_CONFIGURED, StripeConfig.fromFile(null).disabled)
    }

    @Test
    fun `no key - Stripe disabled, cash sale unaffected`() = testApplication {
        val fake = FakeStripe()
        application { module(dbPath = tempDb(), stripeConfig = StripeConfig.OFF, stripeHttp = fake) }
        val (c, id) = checkOf()
        val status = c.get("/stripe/status").obj()
        assertEquals(false, status["configured"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, status["available"]!!.jsonPrimitive.content.toBoolean())
        val res = c.postJson("/checks/$id/stripe/intents")
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertEquals("stripe_not_configured", res.obj()["code"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Conflict, c.postJson("/stripe/connection-token").status)
        c.payCash(id, 2250)
        assertTrue(fake.calls.isEmpty(), "no Stripe traffic without a key")
    }

    @Test
    fun `live key refused - disabled with a reason, never sent to Stripe`() = testApplication {
        val fake = FakeStripe()
        application {
            module(dbPath = tempDb(), stripeConfig = StripeConfig.of("sk_live_" + "nope", null, "test"), stripeHttp = fake)
        }
        val (c, id) = checkOf()
        val status = c.get("/stripe/status").obj()
        assertEquals(true, status["configured"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, status["available"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("stripe_live_key_refused", status["reason"]!!.jsonPrimitive.content)
        assertEquals("stripe_live_key_refused", c.postJson("/checks/$id/stripe/intents").obj()["code"]!!.jsonPrimitive.content)
        c.payCash(id, 2250)
        assertTrue(fake.calls.isEmpty())
    }

    // --- terminal setup -----------------------------------------------------

    @Test
    fun `connection token and a Terminal location created once for the store`() = testApplication {
        val fake = FakeStripe()
        application { module(dbPath = tempDb(), stripeConfig = testKey, stripeHttp = fake) }
        val c = loginClient()
        val status = c.get("/stripe/status").obj()
        assertEquals(true, status["available"]!!.jsonPrimitive.content.toBoolean(), status.toString())
        assertEquals("CAD", status["currency"]!!.jsonPrimitive.content)
        assertEquals("tml_fake", status["locationId"]!!.jsonPrimitive.content)
        val token = c.postJson("/stripe/connection-token").obj()
        assertEquals("pst_test_fake", token["secret"]!!.jsonPrimitive.content)

        val tokenCall = fake.callsTo("/v1/terminal/connection_tokens").single()
        assertEquals("tml_fake", tokenCall.params["location"])
        val create = fake.calls.single { it.path == "/v1/terminal/locations" && it.method == "POST" }
        assertEquals("CA", create.params["address[country]"])
        assertEquals("QC", create.params["address[state]"])
        assertNotNull(create.idempotencyKey)
        // second status/token: location remembered, not re-created
        c.get("/stripe/status"); c.postJson("/stripe/connection-token")
        assertEquals(1, fake.calls.count { it.path == "/v1/terminal/locations" && it.method == "POST" })
    }

    @Test
    fun `configured STRIPE_LOCATION_ID is used as-is`() = testApplication {
        val fake = FakeStripe()
        application {
            module(dbPath = tempDb(), stripeConfig = StripeConfig.of("sk_test_" + "unit", "tml_given", "test"), stripeHttp = fake)
        }
        val c = loginClient()
        assertEquals("tml_given", c.get("/stripe/status").obj()["locationId"]!!.jsonPrimitive.content)
        assertTrue(fake.callsTo("/v1/terminal/locations").isEmpty())
    }

    // --- payments -----------------------------------------------------------

    @Test
    fun `PaymentIntent is card_present for the amount due, with check metadata`() = testApplication {
        val fake = FakeStripe()
        application { module(dbPath = tempDb(), stripeConfig = testKey, stripeHttp = fake) }
        val (c, id) = checkOf()
        val intent = c.intent(id)
        assertEquals(2250L, intent["amountCents"]!!.jsonPrimitive.long)
        assertEquals("CAD", intent["currency"]!!.jsonPrimitive.content)
        assertEquals("pi_1_secret_x", intent["clientSecret"]!!.jsonPrimitive.content)
        val create = fake.callsTo("/v1/payment_intents").single()
        assertEquals("2250", create.params["amount"])
        assertEquals("cad", create.params["currency"])
        assertEquals("card_present", create.params["payment_method_types[]"])
        assertEquals("manual", create.params["capture_method"])
        assertEquals(id.toString(), create.params["metadata[check_id]"])
        assertEquals("STRIPE", create.params["metadata[tender]"])
        assertEquals(intent["paymentId"]!!.jsonPrimitive.content, create.params["metadata[pos_payment_id]"])
        assertTrue(create.idempotencyKey!!.startsWith("pos-pi-"))
        // a partial amount is allowed; more than due is not
        assertEquals(HttpStatusCode.Created, c.postJson("/checks/$id/stripe/intents", """{"amountCents":1000}""").status)
        assertEquals(HttpStatusCode.BadRequest, c.postJson("/checks/$id/stripe/intents", """{"amountCents":999999}""").status)
    }

    @Test
    fun `US account - charged in the account currency with a US location`() = testApplication {
        val fake = FakeStripe(country = "US", currency = "usd")
        application { module(dbPath = tempDb(), stripeConfig = testKey, stripeHttp = fake) }
        val (c, id) = checkOf()
        assertEquals("USD", c.get("/stripe/status").obj()["currency"]!!.jsonPrimitive.content)
        assertEquals("USD", c.intent(id)["currency"]!!.jsonPrimitive.content)
        assertEquals("usd", fake.callsTo("/v1/payment_intents").single().params["currency"])
        assertEquals("US", fake.calls.single { it.path == "/v1/terminal/locations" && it.method == "POST" }.params["address[country]"])
    }

    @Test
    fun `approved card - captured, tender recorded with the PaymentIntent, synced without the key`() = testApplication {
        val fake = FakeStripe()
        application { module(dbPath = tempDb(), stripeConfig = testKey, stripeHttp = fake) }
        val (c, id) = checkOf()
        val intent = c.intent(id)
        val pid = intent["paymentId"]!!.jsonPrimitive.content
        val pi = intent["paymentIntentId"]!!.jsonPrimitive.content
        fake.authorize(pi) // the simulated reader approved the card

        val res = c.postJson("/stripe/payments/$pid/confirm")
        assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
        val body = res.obj()
        val tender = body["tender"]!!.jsonObject
        assertEquals("STRIPE", tender["type"]!!.jsonPrimitive.content)
        assertEquals(2250L, tender["amountAppliedCents"]!!.jsonPrimitive.long)
        assertEquals(0L, body["check"]!!.jsonObject["outstandingCents"]!!.jsonPrimitive.long)
        assertEquals("succeeded", fake.status(pi))
        val capture = fake.callsTo("/v1/payment_intents/$pi/capture").single()
        assertEquals("pos-capture-$pid", capture.idempotencyKey)
        val tenderId = tender["id"]!!.jsonPrimitive.int
        assertEquals(pi, transaction {
            Tenders.selectAll().where { Tenders.id eq tenderId }.single()[Tenders.stripePaymentIntentId]
        })

        // confirm again (double tap / retry) → the same tender, no second capture
        val again = c.postJson("/stripe/payments/$pid/confirm").obj()
        assertEquals(tenderId, again["tender"]!!.jsonObject["id"]!!.jsonPrimitive.int)
        assertEquals(1, fake.callsTo("/v1/payment_intents/$pi/capture").size)

        assertEquals(HttpStatusCode.OK, c.post("/checks/$id/finalize").status)
        assertTrue("Stripe" in c.get("/checks/$id/receipt").obj()["text"]!!.jsonPrimitive.content)

        val payloads = outboxPayloads()
        val confirmed = payloads.map { json.parseToJsonElement(it).jsonObject }
            .single { it["tenderId"]?.jsonPrimitive?.int == tenderId && it["type"] != null && it["processor"] != null }
        assertEquals("STRIPE", confirmed["type"]!!.jsonPrimitive.content)
        assertEquals(pi, confirmed["stripePaymentIntentId"]!!.jsonPrimitive.content)
        assertTrue(payloads.any { "\"STRIPE\"" in it && "\"tenders\"" in it }, "check.closed carries the STRIPE tender")
        assertTrue(payloads.none { "sk_test" in it || "secret_x" in it }, "no key or client secret in the outbox")
    }

    @Test
    fun `declined card - friendly code, nothing recorded, cash still pays`() = testApplication {
        val fake = FakeStripe()
        application { module(dbPath = tempDb(), stripeConfig = testKey, stripeHttp = fake) }
        val (c, id) = checkOf()
        val intent = c.intent(id)
        val pid = intent["paymentId"]!!.jsonPrimitive.content
        fake.decline(intent["paymentIntentId"]!!.jsonPrimitive.content, "insufficient_funds")

        val res = c.postJson("/stripe/payments/$pid/confirm")
        assertEquals(HttpStatusCode.PaymentRequired, res.status)
        val err = res.obj()
        assertEquals("stripe_declined", err["code"]!!.jsonPrimitive.content)
        assertEquals("insufficient_funds", err["declineCode"]!!.jsonPrimitive.content)
        assertEquals(2250L, c.get("/checks/$id").obj()["outstandingCents"]!!.jsonPrimitive.long)
        assertEquals(HttpStatusCode.OK, c.postJson("/stripe/payments/$pid/cancel").status)
        c.payCash(id, 2250)
    }

    @Test
    fun `Stripe timeout - nothing recorded, the check stays payable by cash`() = testApplication {
        val fake = FakeStripe()
        application { module(dbPath = tempDb(), stripeConfig = testKey, stripeHttp = fake) }
        val (c, id) = checkOf()
        val intent = c.intent(id)
        val pid = intent["paymentId"]!!.jsonPrimitive.content
        fake.authorize(intent["paymentIntentId"]!!.jsonPrimitive.content)
        fake.failOn = { if (it.path.endsWith("/capture")) SocketTimeoutException("read timed out") else null }

        val res = c.postJson("/stripe/payments/$pid/confirm")
        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
        assertEquals("stripe_unavailable", res.obj()["code"]!!.jsonPrimitive.content)
        assertEquals(0L, c.get("/checks/$id").obj()["paidCents"]!!.jsonPrimitive.long)

        // a timeout creating the PaymentIntent fails just as cleanly
        fake.failOn = { if (it.path == "/v1/payment_intents") SocketTimeoutException("read timed out") else null }
        assertEquals(HttpStatusCode.ServiceUnavailable, c.postJson("/checks/$id/stripe/intents").status)

        // …and the sale finishes in cash
        c.payCash(id, 2250)
        assertEquals("CLOSED", c.get("/checks/$id").obj()["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `offline - status says unavailable instead of failing`() = testApplication {
        val fake = FakeStripe().apply { failOn = { ConnectException("no route to host") } }
        application { module(dbPath = tempDb(), stripeConfig = testKey, stripeHttp = fake) }
        val (c, id) = checkOf()
        val status = c.get("/stripe/status")
        assertEquals(HttpStatusCode.OK, status.status)
        assertEquals(false, status.obj()["available"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("stripe_unavailable", status.obj()["reason"]!!.jsonPrimitive.content)
        c.payCash(id, 2250)
    }

    @Test
    fun `cancel releases the PaymentIntent and records nothing`() = testApplication {
        val fake = FakeStripe()
        application { module(dbPath = tempDb(), stripeConfig = testKey, stripeHttp = fake) }
        val (c, id) = checkOf()
        val intent = c.intent(id)
        val pid = intent["paymentId"]!!.jsonPrimitive.content
        val pi = intent["paymentIntentId"]!!.jsonPrimitive.content
        fake.authorize(pi)
        val res = c.postJson("/stripe/payments/$pid/cancel")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("CANCELED", res.obj()["status"]!!.jsonPrimitive.content)
        assertEquals("canceled", fake.status(pi))
        assertEquals(HttpStatusCode.Conflict, c.postJson("/stripe/payments/$pid/confirm").status)
        c.payCash(id, 2250)
    }

    @Test
    fun `authorized after the check was paid another way - released, not captured`() = testApplication {
        val fake = FakeStripe()
        application { module(dbPath = tempDb(), stripeConfig = testKey, stripeHttp = fake) }
        val (c, id) = checkOf()
        val intent = c.intent(id)
        val pi = intent["paymentIntentId"]!!.jsonPrimitive.content
        fake.authorize(pi)
        c.postJson("/checks/$id/tenders", """{"type":"CASH","amountTenderedCents":2250}""")
        val res = c.postJson("/stripe/payments/${intent["paymentId"]!!.jsonPrimitive.content}/confirm")
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertEquals("stripe_amount_exceeds_due", res.obj()["code"]!!.jsonPrimitive.content)
        assertEquals("canceled", fake.status(pi))
        assertTrue(fake.callsTo("/v1/payment_intents/$pi/capture").isEmpty())
    }

    // --- refunds ------------------------------------------------------------

    private suspend fun HttpClient.paidByStripe(fake: FakeStripe, id: Int): String {
        val intent = intent(id)
        val pi = intent["paymentIntentId"]!!.jsonPrimitive.content
        fake.authorize(pi)
        assertEquals(HttpStatusCode.Created, postJson("/stripe/payments/${intent["paymentId"]!!.jsonPrimitive.content}/confirm").status)
        assertEquals(HttpStatusCode.OK, post("/checks/$id/finalize").status)
        return pi
    }

    @Test
    fun `refund to the card - made at Stripe, then recorded, partial and capped`() = testApplication {
        val fake = FakeStripe()
        application { module(dbPath = tempDb(), stripeConfig = testKey, stripeHttp = fake) }
        val (c, id) = checkOf()
        val pi = c.paidByStripe(fake, id)
        val info = c.get("/checks/$id/refunds").obj()
        assertEquals(2250L, info["stripeRefundableCents"]!!.jsonPrimitive.long)

        val res = c.postJson("/checks/$id/refund",
            """{"amountCents":1000,"tenderType":"STRIPE","reason":"wrong item","managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
        assertEquals("STRIPE", res.obj()["refund"]!!.jsonObject["tenderType"]!!.jsonPrimitive.content)
        val call = fake.callsTo("/v1/refunds").single()
        assertEquals(pi, call.params["payment_intent"])
        assertEquals("1000", call.params["amount"])
        assertNotNull(call.idempotencyKey)
        val row = transaction { Refunds.selectAll().where { Refunds.checkId eq id }.single() }
        assertEquals(pi, row[Refunds.stripePaymentIntentId])
        assertTrue(row[Refunds.stripeRefundId]!!.startsWith("re_"))
        assertEquals(1250L, c.get("/checks/$id/refunds").obj()["stripeRefundableCents"]!!.jsonPrimitive.long)
        assertTrue(outboxPayloads().any { "\"refundId\"" in it && "\"STRIPE\"" in it && "stripeRefundId" in it })

        // more than is left on the card → refused before Stripe is called
        val over = c.postJson("/checks/$id/refund",
            """{"amountCents":2000,"tenderType":"STRIPE","reason":"x","managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Conflict, over.status)
        assertEquals(1, fake.callsTo("/v1/refunds").size)

        // the rest, in full
        assertEquals(HttpStatusCode.Created, c.postJson("/checks/$id/refund",
            """{"amountCents":1250,"tenderType":"STRIPE","reason":"rest","managerPin":"1234"}""").status)
        assertEquals(0L, c.get("/checks/$id/refunds").obj()["refundableCents"]!!.jsonPrimitive.long)
    }

    @Test
    fun `refund while Stripe is down - refused, nothing recorded locally`() = testApplication {
        val fake = FakeStripe()
        application { module(dbPath = tempDb(), stripeConfig = testKey, stripeHttp = fake) }
        val (c, id) = checkOf()
        c.paidByStripe(fake, id)
        fake.failOn = { if (it.path == "/v1/refunds") ConnectException("network is unreachable") else null }
        val res = c.postJson("/checks/$id/refund",
            """{"amountCents":2250,"tenderType":"STRIPE","reason":"returned","managerPin":"1234"}""")
        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
        assertEquals("stripe_unavailable", res.obj()["code"]!!.jsonPrimitive.content)
        assertEquals(0L, c.get("/checks/$id/refunds").obj()["refundedCents"]!!.jsonPrimitive.long)
        assertNull(transaction { Refunds.selectAll().where { Refunds.checkId eq id }.firstOrNull() })
        assertTrue(outboxPayloads().none { "\"refundId\"" in it })
        // a cash refund of the same check still works (existing rules)
        assertEquals(HttpStatusCode.Created, c.postJson("/checks/$id/refund",
            """{"amountCents":2250,"tenderType":"CASH","reason":"returned","managerPin":"1234"}""").status)
    }

    @Test
    fun `STRIPE refund on a check with no Stripe tender is refused`() = testApplication {
        val fake = FakeStripe()
        application { module(dbPath = tempDb(), stripeConfig = testKey, stripeHttp = fake) }
        val (c, id) = checkOf()
        c.payCash(id, 2250)
        val res = c.postJson("/checks/$id/refund",
            """{"amountCents":500,"tenderType":"STRIPE","reason":"x","managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertEquals("stripe_no_card_tender", res.obj()["code"]!!.jsonPrimitive.content)
        assertEquals(0L, c.get("/checks/$id/refunds").obj()["stripeRefundableCents"]!!.jsonPrimitive.long)
    }

    @Test
    fun `STRIPE cannot be confirmed by hand through the generic electronic tender path`() = testApplication {
        application { module(dbPath = tempDb(), stripeConfig = testKey, stripeHttp = FakeStripe()) }
        val (c, id) = checkOf()
        val res = c.postJson("/checks/$id/tenders/confirm", """{"type":"STRIPE","amountCents":2250}""")
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertEquals(0L, c.get("/checks/$id").obj()["paidCents"]!!.jsonPrimitive.long)
    }

    @Test
    fun `split check - a bill group pays by Stripe`() = testApplication {
        val fake = FakeStripe()
        application { module(dbPath = tempDb(), stripeConfig = testKey, stripeHttp = fake) }
        val (c, id) = checkOf()
        val split = c.postJson("/checks/$id/split", """{"groups":2,"even":true}""")
        assertEquals(HttpStatusCode.Created, split.status, split.bodyAsText())
        val groups = split.obj()["split"]!!.jsonObject["groups"]!!.jsonArray.map { it.jsonObject }
        val g1 = groups[0]["id"]!!.jsonPrimitive.int
        val intent = c.postJson("/checks/$id/stripe/intents", """{"groupId":$g1}""").obj()
        val pi = intent["paymentIntentId"]!!.jsonPrimitive.content
        assertEquals(g1.toString(), fake.callsTo("/v1/payment_intents").single().params["metadata[group_id]"])
        fake.authorize(pi)
        val res = c.postJson("/stripe/payments/${intent["paymentId"]!!.jsonPrimitive.content}/confirm")
        assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
        assertEquals(g1, res.obj()["tender"]!!.jsonObject["groupId"]!!.jsonPrimitive.int)
    }
}
