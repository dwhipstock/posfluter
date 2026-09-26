package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.base.AuthService
import dev.dwhipstock.pos.base.Permissions
import dev.dwhipstock.pos.retail.AgeCheckRequest
import dev.dwhipstock.pos.retail.LookupResponse
import dev.dwhipstock.pos.retail.NewProductRequest
import dev.dwhipstock.pos.retail.RetailService
import dev.dwhipstock.pos.retail.ScanRequest
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * The retail counter (a store whose profile kind is retail). Session-gated
 * like every POS route; payment, receipts and refunds use the ordinary
 * /checks routes on the sale these return.
 */
fun Route.retailRoutes(retail: RetailService, auth: AuthService) {

    /** The sale in progress on the register, or a new one (idempotent). */
    post("/retail/sales") {
        call.respond(HttpStatusCode.Created, retail.openSale(call.sessionUser().userId))
    }

    /** The sale in progress, or 204 when the register is free. */
    get("/retail/sales/current") {
        val sale = retail.currentSale()
        if (sale == null) call.respond(HttpStatusCode.NoContent) else call.respond(sale)
    }

    /** A barcode → one more of that product. 404 `unknown_barcode` → offer to add it. */
    post("/retail/sales/{id}/scan") {
        val req = call.receive<ScanRequest>()
        call.respond(retail.scan(saleId(call), req.barcode))
    }

    /** ID check for age-restricted items. Only the outcome is kept. */
    post("/retail/sales/{id}/age-check") {
        val req = call.receive<AgeCheckRequest>()
        call.respond(retail.checkAge(
            saleId(call), req.method, req.scan, req.dateOfBirth, req.cashierSawId, call.sessionUser().userId))
    }

    /**
     * Optional online name suggestion for an unknown barcode. Always 200: no
     * network or no match is simply `name: null` (manual entry).
     */
    get("/retail/lookup/{barcode}") {
        val barcode = call.parameters["barcode"]!!.trim()
        val hit = retail.suggest(barcode)
        call.respond(LookupResponse(barcode, hit?.name, hit?.source))
    }

    /** Add the product behind an unknown barcode — a menu edit, so the edit-menu grant (or a manager's PIN). */
    post("/retail/products") {
        val req = call.receive<NewProductRequest>()
        requireGrant(auth, call, Permissions.EDIT_MENU, req.managerPin)
        call.respond(HttpStatusCode.Created, retail.addProduct(req))
    }
}

private fun saleId(call: io.ktor.server.application.ApplicationCall): Int =
    call.parameters["id"]?.toIntOrNull() ?: throw IllegalArgumentException("sale id must be a number")
