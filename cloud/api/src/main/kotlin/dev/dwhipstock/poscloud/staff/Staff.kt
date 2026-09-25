package dev.dwhipstock.poscloud.staff

import dev.dwhipstock.poscloud.catalog.Scope
import dev.dwhipstock.poscloud.catalog.bool
import dev.dwhipstock.poscloud.catalog.obj
import dev.dwhipstock.poscloud.catalog.str
import dev.dwhipstock.poscloud.db.RoleGrants
import dev.dwhipstock.poscloud.db.StaffGrants
import dev.dwhipstock.poscloud.db.StoreStaff
import dev.dwhipstock.poscloud.portalVenueScope
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import java.time.OffsetDateTime

/**
 * Staff as the portal sees them: a READ-ONLY projection of what each store
 * pushes up (one-way sync — the tablet owns staff and grants; CONTRACT §7).
 * Venue-scoped: every store has its own staff list. No PIN hash ever reaches
 * the cloud.
 */

/** The fixed grant vocabulary — mirrors the store's; used for display order and defaults. */
object Permissions {
    const val MANAGE_STAFF = "manage_staff"

    val ALL = listOf(
        "void", "refund", "discount_comp", "cash_movement", "open_shift", "close_shift",
        "price_override", "zone_open_close", "edit_menu", MANAGE_STAFF,
    )
    val ROLES = listOf("MANAGER", "SERVER")

    private val SERVER_DEFAULTS = setOf("price_override")

    fun defaultGranted(role: String, permission: String): Boolean = when (role) {
        "MANAGER" -> true
        "SERVER" -> permission in SERVER_DEFAULTS
        else -> false
    }
}

@Serializable
data class StaffDto(
    val id: String, val name: String, val role: String, val active: Boolean,
    /** Per-staff overrides set on the store; absent = inherit the role default. */
    val overrides: Map<String, Boolean>,
    val venueId: String,
)

@Serializable
data class VenueGrantsDto(val venueId: String, val roleGrants: Map<String, Map<String, Boolean>>)

@Serializable
data class StaffListResponse(
    val staff: List<StaffDto>,
    /** Role matrix of the selected store (the first store's in an all-stores view). */
    val roleGrants: Map<String, Map<String, Boolean>>,
    /** Every in-scope store's matrix, for the combined view. */
    val venueGrants: List<VenueGrantsDto>,
    val permissions: List<String>,
)

fun Route.staffRoutes() {
    get("/staff") {
        val scope = portalVenueScope(call)
        call.respond(transaction { staffList(listOf(scope)) })
    }
}

/** Inside a transaction. */
internal fun staffList(scopes: List<Scope>): StaffListResponse {
    val staff = scopes.flatMap { scope ->
        val overrides = StaffGrants.selectAll().where {
            (StaffGrants.tenantId eq scope.tenantId) and (StaffGrants.venueId eq scope.venueId)
        }.groupBy({ it[StaffGrants.staffId] }, { it[StaffGrants.permission] to it[StaffGrants.granted] })
        StoreStaff.selectAll().where {
            (StoreStaff.tenantId eq scope.tenantId) and (StoreStaff.venueId eq scope.venueId) and
                (StoreStaff.deleted eq false)
        }.map {
            val id = it[StoreStaff.id]
            StaffDto(id, it[StoreStaff.name], it[StoreStaff.role], it[StoreStaff.active],
                (overrides[id] ?: emptyList()).toMap(), scope.venueId)
        }.sortedWith(compareBy({ it.role }, { it.name }))
    }
    val grants = scopes.map { VenueGrantsDto(it.venueId, roleMatrix(it)) }
    return StaffListResponse(staff, grants.firstOrNull()?.roleGrants ?: defaultMatrix(), grants, Permissions.ALL)
}

private fun defaultMatrix() = Permissions.ROLES.associateWith { role ->
    Permissions.ALL.associateWith { Permissions.defaultGranted(role, it) }
}

private fun roleMatrix(scope: Scope): Map<String, Map<String, Boolean>> {
    val stored = RoleGrants.selectAll().where {
        (RoleGrants.tenantId eq scope.tenantId) and (RoleGrants.venueId eq scope.venueId)
    }.associate { (it[RoleGrants.role] to it[RoleGrants.permission]) to it[RoleGrants.granted] }
    return Permissions.ROLES.associateWith { role ->
        Permissions.ALL.associateWith { perm -> stored[role to perm] ?: Permissions.defaultGranted(role, perm) }
    }
}

/**
 * Ingest-side projection of store staff events (called from Projections, inside
 * the batch transaction). Snapshots are full state, so applying is idempotent.
 */
object StaffProjection {

    /** One `staff` snapshot: {id, name, role, active, deleted, overrides}. */
    fun applyStaff(scope: Scope, staff: JsonObject) {
        val id = staff.str("id") ?: return
        val deleted = staff.bool("deleted") ?: false
        StoreStaff.upsert {
            it[tenantId] = scope.tenantId
            it[venueId] = scope.venueId
            it[StoreStaff.id] = id
            it[name] = staff.str("name") ?: id
            it[role] = staff.str("role") ?: "SERVER"
            it[active] = !deleted && (staff.bool("active") ?: true)
            it[StoreStaff.deleted] = deleted
            it[updatedAt] = dev.dwhipstock.poscloud.CloudTime.now()
        }
        StaffGrants.deleteWhere {
            (StaffGrants.tenantId eq scope.tenantId) and (StaffGrants.venueId eq scope.venueId) and
                (StaffGrants.staffId eq id)
        }
        if (deleted) return
        staff.obj("overrides")?.forEach { (perm, value) ->
            val granted = (value as? JsonPrimitive)?.booleanOrNull ?: return@forEach
            if (perm !in Permissions.ALL) return@forEach
            StaffGrants.insert {
                it[tenantId] = scope.tenantId
                it[venueId] = scope.venueId
                it[staffId] = id
                it[permission] = perm
                it[StaffGrants.granted] = granted
            }
        }
    }

    /** The full role → permission matrix. */
    fun applyRoleGrants(scope: Scope, roles: JsonObject) {
        RoleGrants.deleteWhere { (RoleGrants.tenantId eq scope.tenantId) and (RoleGrants.venueId eq scope.venueId) }
        Permissions.ROLES.forEach { role ->
            val perms = roles.obj(role)
            Permissions.ALL.forEach { perm ->
                RoleGrants.insert {
                    it[tenantId] = scope.tenantId
                    it[venueId] = scope.venueId
                    it[RoleGrants.role] = role
                    it[permission] = perm
                    it[granted] = perms?.bool(perm) ?: Permissions.defaultGranted(role, perm)
                }
            }
        }
    }
}
