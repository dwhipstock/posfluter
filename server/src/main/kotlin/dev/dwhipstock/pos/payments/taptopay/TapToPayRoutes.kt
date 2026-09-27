package dev.dwhipstock.pos.payments.taptopay

import dev.dwhipstock.pos.api.onIo
import dev.dwhipstock.pos.payments.StripeService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable data class PhonePairRequest(val code: String, val deviceName: String? = null)
@Serializable data class PhonePairResponse(val token: String, val storeName: String, val currency: String)
@Serializable data class PhoneConfigView(
    val storeName: String,
    val currency: String,
    /** The Stripe Terminal Location the phone's Tap to Pay reader connects to. */
    val locationId: String?,
    val stripeAvailable: Boolean,
    val reason: String? = null,
    /** Stripe's simulated Tap to Pay reader (test card picked on the phone) instead of a real card tap. */
    val simulated: Boolean = true,
)
@Serializable data class PhoneSecretView(val secret: String)

/**
 * The phone card reader's API (`payment.terminal=tap_to_pay`). Outside the
 * staff session gate: the credential is the phone's own bearer token from
 * [PhoneReaderHub.pair]. Every authenticated call is a heartbeat.
 *
 *   POST /reader/pair                 {code, deviceName}  → {token}
 *   GET  /reader/config               store name, currency, Terminal Location
 *   POST /reader/heartbeat            {state, readerName?, message?}
 *   POST /reader/connection-token     a Stripe Terminal connection token (same as /stripe/connection-token)
 *   GET  /reader/payment              the payment to take now → 200 job, or 204
 *   GET  /reader/payments/{pi}        one job's state (the phone stops if the POS cancelled)
 *   POST /reader/payments/{pi}/result {status, code?, message?, declined?}
 *   GET  /reader/log?since=           the transaction monitor
 */
fun Route.tapToPayRoutes(hub: PhoneReaderHub, stripe: StripeService, storeName: String, currency: String, simulated: Boolean) {
    fun ApplicationCall.phone() = hub.requirePhone(request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer")?.trim())

    post("/reader/pair") {
        val req = call.receive<PhonePairRequest>()
        call.respond(PhonePairResponse(hub.pair(req.code, req.deviceName), storeName, currency))
    }
    get("/reader/config") {
        call.phone()
        val st = onIo { stripe.status() }
        call.respond(PhoneConfigView(storeName, currency, st.locationId, st.available, st.reason, simulated))
    }
    post("/reader/heartbeat") {
        call.phone()
        hub.beat(runCatching { call.receive<PhoneHeartbeat>() }.getOrDefault(PhoneHeartbeat()))
        call.respond(mapOf("ok" to true))
    }
    post("/reader/connection-token") {
        call.phone()
        hub.log("TOKEN connection token for the phone")
        call.respond(PhoneSecretView(onIo { stripe.connectionToken() }))
    }
    get("/reader/payment") {
        call.phone()
        val job = hub.next()
        if (job == null) call.respond(HttpStatusCode.NoContent, "") else call.respond(job)
    }
    get("/reader/payments/{pi}") {
        call.phone()
        val j = hub.job(call.parameters["pi"]!!)
            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "no such payment", "code" to "terminal_payment_not_found"))
        call.respond(j.view())
    }
    post("/reader/payments/{pi}/result") {
        call.phone()
        val req = call.receive<PhoneReport>()
        call.respond(hub.report(call.parameters["pi"]!!, req).view())
    }
    get("/reader/log") {
        call.phone()
        call.respond(hub.logSince(call.request.queryParameters["since"]?.toLongOrNull() ?: 0L))
    }
}
