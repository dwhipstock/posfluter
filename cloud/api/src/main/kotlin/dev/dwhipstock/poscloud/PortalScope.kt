package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.auth.Principal
import dev.dwhipstock.poscloud.auth.requirePortal
import dev.dwhipstock.poscloud.catalog.Scope
import dev.dwhipstock.poscloud.db.Venues
import io.ktor.server.application.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.ZoneId

/**
 * Portal session → which of the tenant's stores (venues) a request covers.
 *
 * Every portal page is the same page in two modes (the store picker):
 *  - `?venue=<id>` (or a `{venueId}` path parameter) → exactly that store;
 *  - no venue → **all stores** of the tenant, combined.
 *
 * A venue id that doesn't belong to the session's tenant is a 404 — never a
 * fall-through to someone else's venue.
 *
 * Call OUTSIDE a transaction (they open their own, like requirePortal).
 */

/** One in-scope store with its display name and IANA zone. */
data class VenueScope(val scope: Scope, val name: String, val zone: ZoneId) {
    val venueId: String get() = scope.venueId
}

/** The stores this request covers: the one asked for, else all of the tenant's (ordered by id). */
fun portalScopes(call: ApplicationCall): Pair<Principal, List<VenueScope>> {
    val principal = requirePortal(call)
    val requested = requestedVenue(call)
    val venues = transaction {
        Venues.selectAll().where {
            if (requested != null) (Venues.tenantId eq principal.tenantId) and (Venues.id eq requested)
            else Venues.tenantId eq principal.tenantId
        }.orderBy(Venues.id).map {
            VenueScope(Scope(principal.tenantId, it[Venues.id]), it[Venues.name], CloudTime.zone(it[Venues.timezone]))
        }
    }
    if (venues.isEmpty()) {
        throw if (requested != null) NotFoundException("no venue '$requested' for tenant", "bad_venue")
        else NotFoundException("no venue for tenant")
    }
    return principal to venues
}

/**
 * Single-store routes (a device registry, a pairing code, one photo): the
 * requested store, else the tenant's first store by id.
 */
fun portalVenueScope(call: ApplicationCall): Scope = portalScopeAndPrincipal(call).second

/** Same as [portalVenueScope] plus the [Principal] (one auth pass). */
fun portalScopeAndPrincipal(call: ApplicationCall): Pair<Principal, Scope> {
    val (principal, venues) = portalScopes(call)
    return principal to venues.first().scope
}

private fun requestedVenue(call: ApplicationCall): String? =
    (call.parameters["venueId"] ?: call.request.queryParameters["venue"])?.takeIf { it.isNotBlank() }
