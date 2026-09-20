package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.auth.Principal
import dev.dwhipstock.poscloud.auth.requirePortal
import dev.dwhipstock.poscloud.catalog.Scope
import dev.dwhipstock.poscloud.db.Venues
import io.ktor.server.application.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Portal session → (tenant, venue) scope. A multi-venue tenant picks the venue
 * with `?venue=<id>` (or a `{venueId}` path parameter on venue-nested routes).
 *
 * Without an explicit venue the DEFAULT is the tenant's primary venue: the one
 * with id "main" (the Bootstrap default) if present, else the first by id. This
 * avoids silently changing the selected venue when another venue sorts before
 * `main`. Preferring `main` keeps the portal pinned to its designated primary
 * venue as new venue ids are added. (Full multi-venue
 * portal navigation — a picker on every page — is a later session; today only the
 * Devices page selects a venue explicitly.)
 *
 * A venue id that doesn't belong to the session's tenant is a 404 — never a
 * fall-through to someone else's venue.
 *
 * Call OUTSIDE a transaction (it opens its own, like requirePortal).
 */
fun portalVenueScope(call: ApplicationCall): Scope = portalScopeAndPrincipal(call).second

/**
 * Same resolution as [portalVenueScope] but also returns the [Principal], for the
 * few routes that need the signed-in user (e.g. createdBy) — so they don't
 * re-run requirePortal() (a second session SELECT + last_used_at write) just to
 * read one field.
 */
fun portalScopeAndPrincipal(call: ApplicationCall): Pair<Principal, Scope> {
    val principal = requirePortal(call)
    val requested = call.parameters["venueId"] ?: call.request.queryParameters["venue"]
    val scope = transaction {
        val venue = if (requested != null) {
            Venues.selectAll().where {
                (Venues.tenantId eq principal.tenantId) and (Venues.id eq requested)
            }.firstOrNull() ?: throw NotFoundException("no venue '$requested' for tenant", "bad_venue")
        } else {
            val venues = Venues.selectAll().where { Venues.tenantId eq principal.tenantId }
                .orderBy(Venues.id).toList()
            venues.firstOrNull { it[Venues.id] == "main" }
                ?: venues.firstOrNull()
                ?: throw NotFoundException("no venue for tenant")
        }
        Scope(principal.tenantId, venue[Venues.id])
    }
    return principal to scope
}
