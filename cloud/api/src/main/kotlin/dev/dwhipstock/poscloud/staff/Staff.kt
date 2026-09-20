package dev.dwhipstock.poscloud.staff

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.dwhipstock.poscloud.BadRequestException
import dev.dwhipstock.poscloud.ConflictException
import dev.dwhipstock.poscloud.NotFoundException
import dev.dwhipstock.poscloud.catalog.Catalog
import dev.dwhipstock.poscloud.catalog.Scope
import dev.dwhipstock.poscloud.catalog.uniqueSlug
import dev.dwhipstock.poscloud.db.RoleGrants
import dev.dwhipstock.poscloud.db.Staff
import dev.dwhipstock.poscloud.db.StaffGrants
import dev.dwhipstock.poscloud.db.StaffVenues
import dev.dwhipstock.poscloud.db.Venues
import dev.dwhipstock.poscloud.portalVenueScope
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime

/**
 * Cloud-authoritative staff + a basic predefined grant system (CONTRACT §7).
 *
 * Staff identity is TENANT-scoped (one person, one PIN, across the whole group);
 * which venues they work at — and their role at each — is the per-venue
 * assignment in staff_venues. Grants stay venue-scoped. Every mutation appends
 * full snapshots to the catalog_changes feed FOR EACH AFFECTED VENUE, so each
 * store container converges on exactly its own assigned staff. PINs are
 * BCrypt-hashed (never returned); the "don't lock out the last manager" guard
 * refuses any change that leaves an affected venue with no active manage_staff.
 *
 * The routes stay venue-scoped (portalVenueScope): the portal edits staff in the
 * context of one venue. Identity-level edits (name / PIN / active / delete)
 * naturally ripple to every venue the member is assigned to.
 */

private const val PIN_BCRYPT_COST = 10 // matches the store; 4-digit PINs — the rate limiter is the real defense

/** BCrypt-hash a 4-digit PIN. The plaintext is never persisted or returned. */
fun hashStaffPin(pin: String): String =
    BCrypt.withDefaults().hashToString(PIN_BCRYPT_COST, pin.toCharArray())

/** The fixed grant vocabulary. Basic by design — predefined toggles, not custom RBAC. */
object Permissions {
    const val VOID = "void"
    const val REFUND = "refund"
    const val DISCOUNT_COMP = "discount_comp"
    const val CASH_MOVEMENT = "cash_movement"
    const val OPEN_SHIFT = "open_shift"
    const val CLOSE_SHIFT = "close_shift"
    const val PRICE_OVERRIDE = "price_override"
    const val ZONE_OPEN_CLOSE = "zone_open_close"
    const val EDIT_MENU = "edit_menu"
    const val MANAGE_STAFF = "manage_staff"

    val ALL = listOf(
        VOID, REFUND, DISCOUNT_COMP, CASH_MOVEMENT, OPEN_SHIFT, CLOSE_SHIFT,
        PRICE_OVERRIDE, ZONE_OPEN_CLOSE, EDIT_MENU, MANAGE_STAFF,
    )
    val ROLES = listOf("MANAGER", "SERVER")

    /** Server keeps only price_override by default; a manager gets everything. */
    private val SERVER_DEFAULTS = setOf(PRICE_OVERRIDE)

    fun defaultGranted(role: String, permission: String): Boolean = when (role) {
        "MANAGER" -> true
        "SERVER" -> permission in SERVER_DEFAULTS
        else -> false // an unrecognized role gets nothing — fail closed
    }
}

@Serializable
data class StaffDto(
    val id: String, val name: String, val role: String, val active: Boolean,
    /** Per-staff overrides the owner has explicitly set at THIS venue; absent = inherit the role default. */
    val overrides: Map<String, Boolean>,
    /** Every venue the member is assigned to (venue id → role there). */
    val venues: Map<String, String>,
)

@Serializable
data class StaffListResponse(
    val staff: List<StaffDto>,
    val roleGrants: Map<String, Map<String, Boolean>>,
    val permissions: List<String>,
)

@Serializable
data class StaffCreateRequest(val name: String, val role: String, val pin: String)

@Serializable
data class StaffPatchRequest(val name: String? = null, val role: String? = null, val active: Boolean? = null)

@Serializable
data class PinRequest(val pin: String)

@Serializable
data class StaffGrantsRequest(val overrides: Map<String, Boolean>)

@Serializable
data class RoleGrantsRequest(val roles: Map<String, Map<String, Boolean>>)

@Serializable
data class StaffVenueAssignment(val venueId: String, val role: String)

@Serializable
data class StaffVenuesRequest(val venues: List<StaffVenueAssignment>)

fun Route.staffRoutes() {

    get("/staff") {
        val scope = portalVenueScope(call)
        call.respond(transaction { staffList(scope) })
    }

    post("/staff") {
        val scope = portalVenueScope(call)
        val req = call.receive<StaffCreateRequest>()
        require(req.name.isNotBlank()) { "name must not be blank" }
        requireRole(req.role)
        requirePin(req.pin)
        val dto = transaction {
            val staffId = uniqueSlug(req.name, fallback = "staff") { candidate ->
                Staff.selectAll().where { tenantStaff(scope) and (Staff.id eq candidate) }.any()
            }
            val now = LocalDateTime.now()
            Staff.insert {
                it[tenantId] = scope.tenantId
                it[id] = staffId
                it[name] = req.name.trim()
                it[pinHash] = hashStaffPin(req.pin)
                it[active] = true
                it[languageCode] = "en"
                it[calendar] = "CE"
                it[deleted] = false
                it[createdAt] = now
                it[updatedAt] = now
            }
            StaffVenues.insert {
                it[tenantId] = scope.tenantId
                it[StaffVenues.staffId] = staffId
                it[venueId] = scope.venueId
                it[role] = req.role
                it[createdAt] = now
            }
            appendStaffChange(scope, staffId)
            staffDtoById(scope, staffId)
        }
        call.respond(HttpStatusCode.Created, dto)
    }

    patch("/staff/{id}") {
        val scope = portalVenueScope(call)
        val staffId = call.parameters["id"]!!
        val req = call.receive<StaffPatchRequest>()
        req.name?.let { require(it.isNotBlank()) { "name must not be blank" } }
        req.role?.let { requireRole(it) }
        val dto = transaction {
            requireLiveStaff(scope, staffId)
            val venues = assignedVenues(scope.tenantId, staffId)
            val before = managersBefore(scope.tenantId, venues)
            Staff.update({ tenantStaff(scope) and (Staff.id eq staffId) }) { row ->
                req.name?.let { row[name] = it.trim() }
                req.active?.let { row[active] = it }
                row[updatedAt] = LocalDateTime.now()
            }
            // role is a per-venue property — a PATCH changes it at the scoped venue only
            req.role?.let { newRole ->
                requireAssigned(scope, staffId)
                StaffVenues.update({
                    (StaffVenues.tenantId eq scope.tenantId) and (StaffVenues.staffId eq staffId) and
                        (StaffVenues.venueId eq scope.venueId)
                }) { it[role] = newRole }
            }
            guardManagers(scope.tenantId, before)
            // name/active ripple to every assigned venue; role only to this one —
            // snapshotting all assigned venues covers both without special-casing
            venues.forEach { appendStaffChange(Scope(scope.tenantId, it), staffId) }
            staffDtoById(scope, staffId)
        }
        call.respond(dto)
    }

    /** Set / reset a staff member's PIN (owner-driven, no current-PIN challenge). */
    post("/staff/{id}/pin") {
        val scope = portalVenueScope(call)
        val staffId = call.parameters["id"]!!
        val req = call.receive<PinRequest>()
        requirePin(req.pin)
        transaction {
            requireLiveStaff(scope, staffId)
            Staff.update({ tenantStaff(scope) and (Staff.id eq staffId) }) {
                it[pinHash] = hashStaffPin(req.pin)
                it[updatedAt] = LocalDateTime.now()
            }
            assignedVenues(scope.tenantId, staffId).forEach {
                appendStaffChange(Scope(scope.tenantId, it), staffId)
            }
        }
        call.respond(mapOf("ok" to true))
    }

    delete("/staff/{id}") {
        val scope = portalVenueScope(call)
        val staffId = call.parameters["id"]!!
        transaction {
            requireLiveStaff(scope, staffId)
            val venues = assignedVenues(scope.tenantId, staffId)
            val before = managersBefore(scope.tenantId, venues)
            // soft delete: session/check FKs keep the row; the login screen and
            // grant lookups filter deleted rows out
            Staff.update({ tenantStaff(scope) and (Staff.id eq staffId) }) {
                it[deleted] = true
                it[active] = false
                it[updatedAt] = LocalDateTime.now()
            }
            StaffGrants.deleteWhere {
                (StaffGrants.tenantId eq scope.tenantId) and (StaffGrants.staffId eq staffId)
            }
            StaffVenues.deleteWhere {
                (StaffVenues.tenantId eq scope.tenantId) and (StaffVenues.staffId eq staffId)
            }
            guardManagers(scope.tenantId, before)
            venues.forEach { appendStaffDelete(Scope(scope.tenantId, it), staffId) }
        }
        call.respond(mapOf("ok" to true))
    }

    /** Replace a staff member's per-permission overrides AT THIS VENUE with exactly this set. */
    patch("/staff/{id}/grants") {
        val scope = portalVenueScope(call)
        val staffId = call.parameters["id"]!!
        val req = call.receive<StaffGrantsRequest>()
        req.overrides.keys.forEach { requirePermission(it) }
        val dto = transaction {
            requireLiveStaff(scope, staffId)
            requireAssigned(scope, staffId)
            val before = managersBefore(scope.tenantId, listOf(scope.venueId))
            StaffGrants.deleteWhere {
                (StaffGrants.tenantId eq scope.tenantId) and (StaffGrants.venueId eq scope.venueId) and
                    (StaffGrants.staffId eq staffId)
            }
            req.overrides.forEach { (perm, granted) ->
                StaffGrants.insert {
                    it[tenantId] = scope.tenantId
                    it[venueId] = scope.venueId
                    it[StaffGrants.staffId] = staffId
                    it[permission] = perm
                    it[StaffGrants.granted] = granted
                }
            }
            guardManagers(scope.tenantId, before)
            appendStaffChange(scope, staffId)
            staffDtoById(scope, staffId)
        }
        call.respond(dto)
    }

    /**
     * Replace the member's venue assignments with exactly this set (M8 minimal
     * group-staff API — the full cross-venue management UI is a later session).
     * Removed venues get a staff delete on their feed (the store deactivates the
     * member and revokes their sessions); added/kept venues get a fresh snapshot.
     */
    put("/staff/{id}/venues") {
        val scope = portalVenueScope(call)
        val staffId = call.parameters["id"]!!
        val req = call.receive<StaffVenuesRequest>()
        // an empty set would unassign the member from every venue while leaving the
        // identity live — invisible in every venue's staff list and unreachable from
        // any portal page. To remove a member entirely, DELETE them instead.
        if (req.venues.isEmpty())
            throw BadRequestException(
                "a staff member must be assigned to at least one venue; delete them to remove", "no_venues")
        req.venues.forEach { requireRole(it.role) }
        if (req.venues.map { it.venueId }.distinct().size != req.venues.size)
            throw BadRequestException("duplicate venueId in assignment list", "bad_venues")
        val dto = transaction {
            requireLiveStaff(scope, staffId)
            val tenantVenues = Venues.selectAll().where { Venues.tenantId eq scope.tenantId }
                .map { it[Venues.id] }.toSet()
            req.venues.forEach {
                if (it.venueId !in tenantVenues)
                    throw NotFoundException("no venue '${it.venueId}' for tenant", "bad_venue")
            }
            val before = assignedVenues(scope.tenantId, staffId).toSet()
            val after = req.venues.associate { it.venueId to it.role }
            val hadManager = managersBefore(scope.tenantId, before + after.keys)
            val now = LocalDateTime.now()
            StaffVenues.deleteWhere {
                (StaffVenues.tenantId eq scope.tenantId) and (StaffVenues.staffId eq staffId)
            }
            req.venues.forEach { assignment ->
                StaffVenues.insert {
                    it[tenantId] = scope.tenantId
                    it[StaffVenues.staffId] = staffId
                    it[venueId] = assignment.venueId
                    it[role] = assignment.role
                    it[createdAt] = now
                }
            }
            val removed = before - after.keys
            // overrides are venue-local: unassigning clears them, same as a delete
            removed.forEach { venue ->
                StaffGrants.deleteWhere {
                    (StaffGrants.tenantId eq scope.tenantId) and (StaffGrants.venueId eq venue) and
                        (StaffGrants.staffId eq staffId)
                }
            }
            guardManagers(scope.tenantId, hadManager)
            removed.forEach { appendStaffDelete(Scope(scope.tenantId, it), staffId) }
            after.keys.forEach { appendStaffChange(Scope(scope.tenantId, it), staffId) }
            staffDtoById(scope, staffId)
        }
        call.respond(dto)
    }

    /** Replace the whole role-default matrix AT THIS VENUE (owner toggles per role × permission). */
    put("/roles/grants") {
        val scope = portalVenueScope(call)
        val req = call.receive<RoleGrantsRequest>()
        req.roles.forEach { (role, perms) ->
            requireRole(role)
            perms.keys.forEach { requirePermission(it) }
        }
        val response = transaction {
            val before = managersBefore(scope.tenantId, listOf(scope.venueId))
            RoleGrants.deleteWhere {
                (RoleGrants.tenantId eq scope.tenantId) and (RoleGrants.venueId eq scope.venueId)
            }
            Permissions.ROLES.forEach { role ->
                Permissions.ALL.forEach { perm ->
                    val granted = req.roles[role]?.get(perm) ?: Permissions.defaultGranted(role, perm)
                    RoleGrants.insert {
                        it[tenantId] = scope.tenantId
                        it[venueId] = scope.venueId
                        it[RoleGrants.role] = role
                        it[permission] = perm
                        it[RoleGrants.granted] = granted
                    }
                }
            }
            guardManagers(scope.tenantId, before)
            appendRoleGrantsChange(scope)
            staffList(scope)
        }
        call.respond(response)
    }
}

// --- seed (called from Bootstrap, inside its transaction) ---

/**
 * Seed the default staff (manager PIN 1234, server1 PIN 9999 — matching the
 * store seed ids so they converge, not duplicate) and the default role matrix.
 * Idempotent: existing rows are left untouched. No distribution change is emitted
 * — every store already seeds the identical defaults locally (CopperLanternSeed), so
 * the store is correct offline from first boot and converges to the cloud only
 * when the owner actually edits staff/grants in the portal.
 */
fun seedStaffDefaults(scope: Scope) {
    val now = LocalDateTime.now()
    if (Staff.selectAll().where { Staff.tenantId eq scope.tenantId }.none()) {
        insertSeedStaff(scope, "manager", "directeur (Manager)", "MANAGER", "1234", now)
        insertSeedStaff(scope, "server1", "employé (Server)", "SERVER", "9999", now)
    }
    if (RoleGrants.selectAll().where {
            (RoleGrants.tenantId eq scope.tenantId) and (RoleGrants.venueId eq scope.venueId)
        }.none()) {
        Permissions.ROLES.forEach { role ->
            Permissions.ALL.forEach { perm ->
                RoleGrants.insert {
                    it[tenantId] = scope.tenantId
                    it[venueId] = scope.venueId
                    it[RoleGrants.role] = role
                    it[permission] = perm
                    it[granted] = Permissions.defaultGranted(role, perm)
                }
            }
        }
    }
}

private fun insertSeedStaff(scope: Scope, id: String, name: String, role: String, pin: String, now: LocalDateTime) {
    Staff.insert {
        it[tenantId] = scope.tenantId
        it[Staff.id] = id
        it[Staff.name] = name
        it[pinHash] = hashStaffPin(pin)
        it[active] = true
        it[languageCode] = "en"
        it[calendar] = "CE"
        it[deleted] = false
        it[createdAt] = now
        it[updatedAt] = now
    }
    StaffVenues.insert {
        it[tenantId] = scope.tenantId
        it[StaffVenues.staffId] = id
        it[venueId] = scope.venueId
        it[StaffVenues.role] = role
        it[createdAt] = now
    }
}

// --- helpers (call inside a transaction unless noted) ---

private fun org.jetbrains.exposed.sql.SqlExpressionBuilder.tenantStaff(scope: Scope) =
    Staff.tenantId eq scope.tenantId

private fun requireRole(role: String) {
    if (role !in Permissions.ROLES) throw BadRequestException("unknown role $role", "bad_role")
}

private fun requirePin(pin: String) {
    if (pin.length != 4 || !pin.all { it.isDigit() }) throw BadRequestException("PIN must be 4 digits", "bad_pin")
}

private fun requirePermission(permission: String) {
    if (permission !in Permissions.ALL) throw BadRequestException("unknown permission $permission", "bad_permission")
}

private fun requireLiveStaff(scope: Scope, staffId: String) {
    Staff.selectAll().where {
        tenantStaff(scope) and (Staff.id eq staffId) and (Staff.deleted eq false)
    }.firstOrNull() ?: throw NotFoundException("staff $staffId not found")
}

private fun requireAssigned(scope: Scope, staffId: String) {
    roleAt(scope, staffId)
        ?: throw NotFoundException("staff $staffId is not assigned to this venue", "not_assigned")
}

/** Every venue the member is assigned to → role there, ordered by venue id so any
 *  "primary/first venue" fallback is deterministic, not physical-row order. The
 *  single source for the three venue-assignment questions below. */
private fun venueRolesFor(tenantId: String, staffId: String): Map<String, String> =
    StaffVenues.selectAll().where {
        (StaffVenues.tenantId eq tenantId) and (StaffVenues.staffId eq staffId)
    }.orderBy(StaffVenues.venueId).associate { it[StaffVenues.venueId] to it[StaffVenues.role] }

/** Venue ids the member is assigned to. */
private fun assignedVenues(tenantId: String, staffId: String): List<String> =
    venueRolesFor(tenantId, staffId).keys.toList()

/** The member's role at the scoped venue, or null if unassigned there. */
private fun roleAt(scope: Scope, staffId: String): String? =
    venueRolesFor(scope.tenantId, staffId)[scope.venueId]

/** Role default for (role, permission), falling back to the built-in default. */
private fun roleGrantOr(scope: Scope, role: String, permission: String): Boolean =
    RoleGrants.selectAll().where {
        (RoleGrants.tenantId eq scope.tenantId) and (RoleGrants.venueId eq scope.venueId) and
            (RoleGrants.role eq role) and (RoleGrants.permission eq permission)
    }.firstOrNull()?.get(RoleGrants.granted) ?: Permissions.defaultGranted(role, permission)

/** Effective grant for a staff member at the scoped venue: override wins, else the role default. */
private fun effectiveGrant(scope: Scope, staffId: String, role: String, permission: String): Boolean {
    val override = StaffGrants.selectAll().where {
        (StaffGrants.tenantId eq scope.tenantId) and (StaffGrants.venueId eq scope.venueId) and
            (StaffGrants.staffId eq staffId) and (StaffGrants.permission eq permission)
    }.firstOrNull()?.get(StaffGrants.granted)
    return override ?: roleGrantOr(scope, role, permission)
}

/** True when some active, non-deleted staff ASSIGNED HERE has effective manage_staff.
 *  Grants for the venue are loaded once (2 role defaults + the venue's manage_staff
 *  overrides) and evaluated in memory, not 1-2 queries per staff member — this runs
 *  once per affected venue on both sides of every staff mutation. */
private fun managerRemains(scope: Scope): Boolean {
    val assigned = StaffVenues.selectAll().where {
        (StaffVenues.tenantId eq scope.tenantId) and (StaffVenues.venueId eq scope.venueId)
    }.associate { it[StaffVenues.staffId] to it[StaffVenues.role] }
    if (assigned.isEmpty()) return false
    val roleDefault = Permissions.ROLES.associateWith { roleGrantOr(scope, it, Permissions.MANAGE_STAFF) }
    val override = StaffGrants.selectAll().where {
        (StaffGrants.tenantId eq scope.tenantId) and (StaffGrants.venueId eq scope.venueId) and
            (StaffGrants.permission eq Permissions.MANAGE_STAFF)
    }.associate { it[StaffGrants.staffId] to it[StaffGrants.granted] }
    return Staff.selectAll().where {
        (Staff.tenantId eq scope.tenantId) and (Staff.deleted eq false) and (Staff.active eq true)
    }.filter { it[Staff.id] in assigned }.any { row ->
        val id = row[Staff.id]
        override[id] ?: (roleDefault[assigned.getValue(id)] ?: false)
    }
}

/**
 * Lockout guard: refuse a change that takes a venue FROM having a manager TO
 * having none. Venues that had no manager before the change (e.g. a freshly
 * provisioned one being staffed up) are not blocked — the guard prevents
 * regression, it doesn't demand instant perfection.
 * Capture [before] via [managersBefore] BEFORE mutating, then call this after.
 */
private fun guardManagers(tenantId: String, before: Map<String, Boolean>) {
    before.forEach { (venue, had) ->
        if (had && !managerRemains(Scope(tenantId, venue))) throw ConflictException(
            "this change would leave no active staff who can manage staff", "last_manager")
    }
}

private fun managersBefore(tenantId: String, venues: Collection<String>): Map<String, Boolean> =
    venues.distinct().associateWith { managerRemains(Scope(tenantId, it)) }

private fun overridesFor(scope: Scope, staffId: String): Map<String, Boolean> =
    StaffGrants.selectAll().where {
        (StaffGrants.tenantId eq scope.tenantId) and (StaffGrants.venueId eq scope.venueId) and
            (StaffGrants.staffId eq staffId)
    }.associate { it[StaffGrants.permission] to it[StaffGrants.granted] }

private fun staffDtoById(scope: Scope, staffId: String): StaffDto {
    val row = Staff.selectAll().where { tenantStaff(scope) and (Staff.id eq staffId) }.first()
    val venues = venueRolesFor(scope.tenantId, staffId)
    // top-level role = role at the SCOPED venue. If the member isn't assigned here
    // (a tenant-level identity edit — name/active/pin — made from another venue's
    // context) fall back to their role at their lowest-id venue: deterministic, and
    // the authoritative per-venue truth is always the `venues` map regardless.
    return StaffDto(
        row[Staff.id], row[Staff.name],
        venues[scope.venueId] ?: venues.values.firstOrNull() ?: "SERVER",
        row[Staff.active], overridesFor(scope, staffId), venues,
    )
}

private fun staffList(scope: Scope): StaffListResponse {
    val roleMatrix = Permissions.ROLES.associateWith { role ->
        Permissions.ALL.associateWith { perm -> roleGrantOr(scope, role, perm) }
    }
    // batch the per-member data into three queries instead of two per row:
    // the venue's assignments (who shows here + their role), the tenant's full
    // assignments (each member's venues map), and the venue's overrides.
    val assigned = StaffVenues.selectAll().where {
        (StaffVenues.tenantId eq scope.tenantId) and (StaffVenues.venueId eq scope.venueId)
    }.associate { it[StaffVenues.staffId] to it[StaffVenues.role] }
    val venuesByStaff = StaffVenues.selectAll().where { StaffVenues.tenantId eq scope.tenantId }
        .orderBy(StaffVenues.venueId)
        .groupBy({ it[StaffVenues.staffId] }, { it[StaffVenues.venueId] to it[StaffVenues.role] })
    val overridesByStaff = StaffGrants.selectAll().where {
        (StaffGrants.tenantId eq scope.tenantId) and (StaffGrants.venueId eq scope.venueId)
    }.groupBy({ it[StaffGrants.staffId] }, { it[StaffGrants.permission] to it[StaffGrants.granted] })
    val staff = Staff.selectAll().where { tenantStaff(scope) and (Staff.deleted eq false) }
        .filter { it[Staff.id] in assigned }
        .sortedBy { assigned.getValue(it[Staff.id]) } // MANAGER before SERVER, like the old role ordering
        .map {
            val id = it[Staff.id]
            StaffDto(
                id, it[Staff.name], assigned.getValue(id), it[Staff.active],
                (overridesByStaff[id] ?: emptyList()).toMap(),
                (venuesByStaff[id] ?: emptyList()).toMap(),
            )
        }
    return StaffListResponse(staff, roleMatrix, Permissions.ALL)
}

// --- distribution (CONTRACT §7): full snapshots onto the catalog_changes feed ---

private fun staffSnapshot(scope: Scope, staffId: String): JsonObject {
    val row = Staff.selectAll().where { tenantStaff(scope) and (Staff.id eq staffId) }.first()
    val overrides = overridesFor(scope, staffId)
    return buildJsonObject {
        put("id", row[Staff.id])
        put("name", row[Staff.name])
        put("role", roleAt(scope, staffId) ?: "SERVER")
        put("pinHash", row[Staff.pinHash]) // BCrypt hash — the store verifies logins offline against it
        put("active", row[Staff.active])
        put("languageCode", row[Staff.languageCode])
        put("calendar", row[Staff.calendar])
        put("deleted", row[Staff.deleted])
        put("overrides", buildJsonObject { overrides.forEach { (k, v) -> put(k, v) } })
    }
}

private fun roleGrantsSnapshot(scope: Scope): JsonObject = buildJsonObject {
    put("roles", buildJsonObject {
        Permissions.ROLES.forEach { role ->
            put(role, buildJsonObject {
                Permissions.ALL.forEach { perm -> put(perm, roleGrantOr(scope, role, perm)) }
            })
        }
    })
}

private fun appendStaffChange(scope: Scope, staffId: String) {
    Catalog.appendChange(scope, "staff", staffId, "upsert", staffSnapshot(scope, staffId))
}

/** The store's delete path only needs the id (CloudSync.applyStaff). */
private fun appendStaffDelete(scope: Scope, staffId: String) {
    Catalog.appendChange(scope, "staff", staffId, "delete", buildJsonObject { put("id", staffId) })
}

private fun appendRoleGrantsChange(scope: Scope) {
    Catalog.appendChange(scope, "role_grants", "all", "upsert", roleGrantsSnapshot(scope))
}
