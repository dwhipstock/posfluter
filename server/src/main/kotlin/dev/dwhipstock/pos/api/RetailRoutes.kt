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
fun Route.retailRoutes(
    retail: RetailService,
    auth: AuthService,
    quickKeys: dev.dwhipstock.pos.retail.QuickKeys = dev.dwhipstock.pos.retail.QuickKeys(),
) {

    /**
     * The counter's quick keys: pins, the products with no barcode, then this
     * store's fastest sellers of the last 28 days (computed here, offline).
     */
    get("/retail/quick-keys") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: dev.dwhipstock.pos.retail.QuickKeys.DEFAULT_KEYS
        call.respond(quickKeys.quickKeys(limit))
    }

    /** Pin a product to the quick keys (it stays through every refresh) — the edit-menu grant or a manager's PIN. */
    post("/retail/quick-keys/pins") {
        val req = call.receive<dev.dwhipstock.pos.retail.PinRequest>()
        val by = requireGrant(auth, call, Permissions.EDIT_MENU, req.managerPin)
        call.respond(quickKeys.pin(req.itemId.trim(), by))
    }

    /** Unpin: the tile goes back to being auto-filled (or drops off). */
    post("/retail/quick-keys/unpin") {
        val req = call.receive<dev.dwhipstock.pos.retail.PinRequest>()
        requireGrant(auth, call, Permissions.EDIT_MENU, req.managerPin)
        call.respond(quickKeys.unpin(req.itemId.trim()))
    }

    /** The ranked top 20% of the catalog by the last 28 days' units (then popularity). */
    get("/retail/top-sellers") {
        val p = call.request.queryParameters
        call.respond(quickKeys.topSellers(
            days = p["days"]?.toIntOrNull() ?: dev.dwhipstock.pos.retail.QuickKeys.WINDOW_DAYS,
            percent = p["percent"]?.toIntOrNull() ?: dev.dwhipstock.pos.retail.QuickKeys.TOP_PERCENT,
        ))
    }

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
