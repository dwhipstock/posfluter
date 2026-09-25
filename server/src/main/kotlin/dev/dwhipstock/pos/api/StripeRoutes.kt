package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.payments.StripeService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class StripeIntentRequest(val amountCents: Long? = null, val groupId: Int? = null)

@Serializable
data class ConnectionTokenResponse(val secret: String)

/**
 * "Card (Stripe)" — optional card tender through Stripe Terminal (test mode,
 * simulated reader). All routes sit behind the normal session gate. None of
 * them is on the path of startup, login, or any other tender: with no key or
 * no internet they answer with a coded error and change nothing.
 *
 *   GET  /stripe/status                        enabled? currency, location
 *   POST /stripe/connection-token              Terminal SDK connection token
 *   POST /checks/{id}/stripe/intents           card_present PaymentIntent for the amount due
 *   GET  /stripe/payments/{paymentId}          local + Stripe status
 *   POST /stripe/payments/{paymentId}/confirm  capture + record the STRIPE tender
 *   POST /stripe/payments/{paymentId}/cancel   release it, record nothing
 * Refunds use the ordinary POST /checks/{id}/refund with tenderType=STRIPE.
 */
fun Route.stripeRoutes(stripe: StripeService) {
    get("/stripe/status") { call.respond(onIo { stripe.status() }) }
    post("/stripe/connection-token") { call.respond(ConnectionTokenResponse(onIo { stripe.connectionToken() })) }
    post("/checks/{id}/stripe/intents") {
        val id = call.parameters["id"]?.toIntOrNull() ?: throw IllegalArgumentException("bad check id")
        val req = runCatching { call.receive<StripeIntentRequest>() }.getOrDefault(StripeIntentRequest())
        call.respond(HttpStatusCode.Created, onIo { stripe.createIntent(id, req.groupId, req.amountCents) })
    }
    get("/stripe/payments/{pid}") { call.respond(onIo { stripe.payment(call.parameters["pid"]!!) }) }
    post("/stripe/payments/{pid}/confirm") {
        call.respond(HttpStatusCode.Created, onIo { stripe.confirm(call.parameters["pid"]!!) })
    }
    post("/stripe/payments/{pid}/cancel") { call.respond(onIo { stripe.cancel(call.parameters["pid"]!!) }) }
}

/**
 * Stripe calls block on the network (seconds when offline): run them on the IO
 * pool so they never tie up the engine threads the rest of the POS is served on.
 */
internal suspend fun <T> onIo(block: () -> T): T = withContext(Dispatchers.IO) { block() }
