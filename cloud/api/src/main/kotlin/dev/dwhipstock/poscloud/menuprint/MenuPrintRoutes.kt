package dev.dwhipstock.poscloud.menuprint

import dev.dwhipstock.poscloud.BadRequestException
import dev.dwhipstock.poscloud.auth.requirePortal
import dev.dwhipstock.poscloud.menu.requireMenuEditor
import dev.dwhipstock.poscloud.menuai.AiCaller
import dev.dwhipstock.poscloud.portalScopes
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Printable menus (/v1/menu-print): ONE store per request
 * (`?venue=` required), owners and managers only — the menu editor's own
 * scoping and role check. Works with no AI key (plain wording, built-in art).
 */
fun Route.menuPrintRoutes(service: MenuPrintService) {

    get("/menu-print/status") {
        val principal = requirePortal(call)
        call.respond(PrintStatus(principal.canEditMenu, service.aiEnabled && principal.canEditMenu,
            service.artEnabled && principal.canEditMenu, PrintStyles.KEYS))
    }

    post("/menu-print/plan") {
        val req = call.receive<PrintRequest>()
        val who = printCaller(call, req.lang)
        call.respond(withContext(Dispatchers.IO) { service.plan(who, req) })
    }

    post("/menu-print/{jobId}/art") {
        val req = runCatching { call.receive<PrintArtRequest>() }.getOrDefault(PrintArtRequest())
        val who = printCaller(call, null)
        val id = call.parameters["jobId"]!!.take(64)
        call.respond(withContext(Dispatchers.IO) { service.art(who, id, req) })
    }

    post("/menu-print/{jobId}/render") {
        val who = printCaller(call, null)
        val id = call.parameters["jobId"]!!.take(64)
        call.respond(withContext(Dispatchers.IO) { service.render(who, id) })
    }
}

/** Signed in (401), an owner or manager (403), exactly one of the tenant's stores (400 / 404). */
private fun printCaller(call: ApplicationCall, lang: String?): AiCaller {
    val requested = call.request.queryParameters["venue"]?.takeIf { it.isNotBlank() }
    val (principal, venues) = portalScopes(call)
    requireMenuEditor(principal)
    if (requested == null || venues.size != 1) throw BadRequestException("pick one store to print its menu", "venue_required")
    return AiCaller(principal, venues.single(), PrintWords.lang(lang))
}
