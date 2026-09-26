package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.base.AuthService
import dev.dwhipstock.pos.retail.CountLinesRequest
import dev.dwhipstock.pos.retail.ReceiveRequest
import dev.dwhipstock.pos.retail.StartCountRequest
import dev.dwhipstock.pos.retail.StockService
import dev.dwhipstock.pos.retail.SubmitCountRequest
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Counting and receiving stock (retail stores; a restaurant answers 409
 * `not_retail`). Session-gated like every POS route: any staff member counts
 * and receives; submitting a count whose figures differ from the expected qty
 * needs a manager (their own session, or a manager's PIN inline). Every write
 * is idempotent on the client's UUID so the stock app can resend after a
 * dropped connection.
 */
fun Route.stockRoutes(stock: StockService, auth: AuthService) {

    /** The best-effort expected on hand per product (cloud figure + this store's moves since). */
    get("/stock/expected") { call.respond(stock.expected()) }

    get("/stock/counts") { call.respond(stock.listCounts()) }

    /** Start a count (or return the one with this id). */
    post("/stock/counts") {
        val req = call.receive<StartCountRequest>()
        call.respond(HttpStatusCode.Created, stock.startCount(req, call.sessionUser().userId))
    }

    get("/stock/counts/{id}") {
        call.respond(stock.getCount(call.parameters["id"]!!, call.request.queryParameters["counter"]))
    }

    /** Set this counter's quantities (a SET per product: resending is harmless). */
    put("/stock/counts/{id}/lines") {
        val req = call.receive<CountLinesRequest>()
        call.respond(stock.setLines(call.parameters["id"]!!, req, call.sessionUser().userId))
    }

    /** Submit: a variance needs a manager. Already submitted → answered as is. */
    post("/stock/counts/{id}/submit") {
        val id = call.parameters["id"]!!
        val req = call.receive<SubmitCountRequest>()
        val user = call.sessionUser()
        val approver = if (stock.needsApproval(id) && user.role != "MANAGER")
            requireManagerApproval(auth, req.managerPin) else null
        call.respond(stock.submit(id, user.userId, approver))
    }

    /** Discard an open count; nothing is sent. */
    post("/stock/counts/{id}/cancel") { call.respond(stock.cancel(call.parameters["id"]!!)) }

    get("/stock/receipts") { call.respond(stock.recentReceipts()) }

    /** A delivery received (idempotent by id). */
    post("/stock/receipts") {
        val req = call.receive<ReceiveRequest>()
        call.respond(HttpStatusCode.Created, stock.receive(req, call.sessionUser().userId))
    }
}
