package dev.dwhipstock.pos.base

import dev.dwhipstock.pos.restaurant.BadRequestException
import dev.dwhipstock.pos.restaurant.ConflictException
import dev.dwhipstock.pos.restaurant.NotFoundException
import dev.dwhipstock.pos.sdk.Outbox
import dev.dwhipstock.pos.sdk.VenueClock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Staff snapshots for outbox payloads (CONTRACT §2/§7). Staff are owned by the
 * tablet and pushed UP only, for display in the owner portal. The PIN hash
 * never leaves the tablet. Call inside a transaction, like Outbox.write.
 */
object StaffSnapshots {

    fun staff(id: String): JsonObject {
        val row = Users.selectAll().where { Users.id eq id }.first()
        val overrides = StaffGrants.selectAll().where { StaffGrants.staffId eq id }
            .associate { it[StaffGrants.permission] to it[StaffGrants.granted] }
        return buildJsonObject {
            put("id", row[Users.id])
            put("name", row[Users.name])
            put("role", row[Users.role])
            put("active", row[Users.active])
            put("deleted", row[Users.deletedAt] != null)
            put("overrides", JsonObject(overrides.mapValues { JsonPrimitive(it.value) }))
        }
    }

    /** Role → permission → granted, the full matrix (defaults filled in). */
    fun roleGrants(): JsonObject {
        val stored = RoleGrants.selectAll().associate {
            (it[RoleGrants.role] to it[RoleGrants.permission]) to it[RoleGrants.granted]
        }
        return buildJsonObject {
            Permissions.ROLES.forEach { role ->
                put(role, buildJsonObject {
                    Permissions.ALL.forEach { perm ->
                        put(perm, stored[role to perm] ?: Permissions.defaultGranted(role, perm))
                    }
                })
            }
        }
    }

    /** Every staff row (deleted ones flagged) + the role matrix — the one-time bootstrap. */
    fun fullSnapshot(): JsonObject = buildJsonObject {
        put("staff", buildJsonArray {
            Users.selectAll().orderBy(Users.id).forEach { add(staff(it[Users.id])) }
        })
        put("roles", roleGrants())
    }
}

@Serializable
data class ManagedStaffDto(
    val id: String, val name: String, val role: String, val active: Boolean,
    val overrides: Map<String, Boolean>,
)

@Serializable
data class ManagedStaffList(
    val staff: List<ManagedStaffDto>,
    val roleGrants: Map<String, Map<String, Boolean>>,
    val permissions: List<String>,
)

/**
 * Tablet-side staff administration (one-way sync: the tablet owns staff). Every
 * mutation writes a full staff snapshot to the outbox in the same transaction so
 * the portal can display it. Guards enforced here, offline:
 *  - 4-digit PINs, unique among live staff (login resolves a user BY PIN);
 *  - never leave the store without an active staff member who can manage staff;
 *  - deactivating/deleting ends that member's sessions and trusted devices.
 */
object StaffAdmin {

    fun list(): ManagedStaffList = transaction {
        val overrides = StaffGrants.selectAll()
            .groupBy({ it[StaffGrants.staffId] }, { it[StaffGrants.permission] to it[StaffGrants.granted] })
        val staff = Users.selectAll().where { Users.deletedAt.isNull() }
            .orderBy(Users.role).orderBy(Users.name)
            .map {
                ManagedStaffDto(
                    it[Users.id], it[Users.name], it[Users.role], it[Users.active],
                    (overrides[it[Users.id]] ?: emptyList()).toMap(),
                )
            }
        val matrix = Permissions.ROLES.associateWith { role ->
            Permissions.ALL.associateWith { perm ->
                RoleGrants.selectAll()
                    .where { (RoleGrants.role eq role) and (RoleGrants.permission eq perm) }
                    .firstOrNull()?.get(RoleGrants.granted) ?: Permissions.defaultGranted(role, perm)
            }
        }
        ManagedStaffList(staff, matrix, Permissions.ALL)
    }

    fun create(name: String, role: String, pin: String): ManagedStaffDto = transaction {
        require(name.isNotBlank()) { "name must not be blank" }
        requireRole(role)
        requirePin(pin)
        requirePinUnused(pin, exceptId = null)
        val id = uniqueStaffId(name)
        Users.insert {
            it[Users.id] = id
            it[Users.name] = name.trim().take(100)
            it[Users.role] = role
            it[Users.pin] = AuthService.hashPin(pin)
            it[Users.active] = true
            it[languageCode] = "en"
        }
        emit("staff.created", id)
        dto(id)
    }

    fun update(id: String, name: String?, role: String?, active: Boolean?): ManagedStaffDto = transaction {
        requireLive(id)
        name?.let { require(it.isNotBlank()) { "name must not be blank" } }
        role?.let { requireRole(it) }
        val hadManager = managerRemains()
        Users.update({ Users.id eq id }) { row ->
            name?.let { row[Users.name] = it.trim().take(100) }
            role?.let { row[Users.role] = it }
            active?.let { row[Users.active] = it }
        }
        guardManager(hadManager)
        if (active == false) revokeAccess(id)
        emit("staff.updated", id)
        dto(id)
    }

    fun resetPin(id: String, pin: String): Unit = transaction {
        requireLive(id)
        requirePin(pin)
        requirePinUnused(pin, exceptId = id)
        Users.update({ Users.id eq id }) { it[Users.pin] = AuthService.hashPin(pin) }
        // a PIN reset is an identity change: sessions minted with the old PIN end
        revokeAccess(id)
        Outbox.write("user.pin_reset", "user", id, buildJsonObject { put("userId", id) })
    }

    /** Soft delete: closed checks and sessions keep their FKs to the row. */
    fun delete(id: String): Unit = transaction {
        requireLive(id)
        val hadManager = managerRemains()
        Users.update({ Users.id eq id }) {
            it[active] = false
            it[deletedAt] = VenueClock.now()
        }
        StaffGrants.deleteWhere { StaffGrants.staffId eq id }
        guardManager(hadManager)
        revokeAccess(id)
        StaffTotp.deleteWhere { StaffTotp.userId eq id }
        emit("staff.deleted", id)
    }

    fun setOverrides(id: String, overrides: Map<String, Boolean>): ManagedStaffDto = transaction {
        requireLive(id)
        overrides.keys.forEach { requirePermission(it) }
        val hadManager = managerRemains()
        GrantsRepo.applyStaffOverrides(id, JsonObject(overrides.mapValues { JsonPrimitive(it.value) }))
        guardManager(hadManager)
        emit("staff.updated", id)
        dto(id)
    }

    fun setRoleGrants(roles: Map<String, Map<String, Boolean>>): ManagedStaffList {
        transaction {
            roles.forEach { (role, perms) ->
                requireRole(role)
                perms.keys.forEach { requirePermission(it) }
            }
            val hadManager = managerRemains()
            GrantsRepo.applyRoleGrants(JsonObject(roles.mapValues { (_, perms) ->
                JsonObject(perms.mapValues { JsonPrimitive(it.value) })
            }))
            guardManager(hadManager)
            Outbox.write("role_grants.updated", "role_grants", "all", buildJsonObject {
                put("roles", StaffSnapshots.roleGrants())
            })
        }
        return list()
    }

    // --- helpers (inside a transaction) ---

    private fun emit(eventType: String, id: String) {
        Outbox.write(eventType, "staff", id, buildJsonObject {
            put("staffId", id)
            put("staff", StaffSnapshots.staff(id))
        })
    }

    private fun dto(id: String): ManagedStaffDto {
        val row = Users.selectAll().where { Users.id eq id }.first()
        val overrides = StaffGrants.selectAll().where { StaffGrants.staffId eq id }
            .associate { it[StaffGrants.permission] to it[StaffGrants.granted] }
        return ManagedStaffDto(row[Users.id], row[Users.name], row[Users.role], row[Users.active], overrides)
    }

    private fun requireLive(id: String) {
        Users.selectAll().where { (Users.id eq id) and Users.deletedAt.isNull() }.firstOrNull()
            ?: throw NotFoundException("staff $id not found", "staff_not_found")
    }

    private fun requireRole(role: String) {
        if (role !in Permissions.ROLES) throw BadRequestException("unknown role $role", "bad_role")
    }

    private fun requirePermission(permission: String) {
        if (permission !in Permissions.ALL) throw BadRequestException("unknown permission $permission", "bad_permission")
    }

    private fun requirePin(pin: String) {
        if (pin.length != 4 || !pin.all { it.isDigit() }) throw BadRequestException("PIN must be 4 digits", "bad_pin")
    }

    /** Login resolves the user BY PIN, so two live staff can never share one. */
    private fun requirePinUnused(pin: String, exceptId: String?) {
        val clash = Users.selectAll().where { Users.deletedAt.isNull() }
            .filter { it[Users.id] != exceptId }
            .any { AuthService.verifyPin(pin, it[Users.pin]) }
        if (clash) throw ConflictException("that PIN is already used by another staff member", "pin_in_use")
    }

    private fun uniqueStaffId(name: String): String {
        val base = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "staff" }.take(56)
        fun taken(candidate: String) = Users.selectAll().where { Users.id eq candidate }.any()
        if (!taken(base)) return base
        var n = 2
        while (taken("$base-$n")) n++
        return "$base-$n"
    }

    private fun managerRemains(): Boolean =
        Users.selectAll().where { (Users.active eq true) and Users.deletedAt.isNull() }
            .any { GrantsRepo.has(it[Users.id], Permissions.MANAGE_STAFF) }

    /** Refuse a change that takes the store FROM having a staff manager TO none. */
    private fun guardManager(hadManager: Boolean) {
        if (hadManager && !managerRemains()) throw ConflictException(
            "this change would leave no active staff who can manage staff", "last_manager")
    }

    /** A deactivated/deleted/re-PINned member's live sessions and trusted devices end at once. */
    private fun revokeAccess(userId: String) {
        Sessions.update({ (Sessions.userId eq userId) and Sessions.revokedAt.isNull() }) {
            it[revokedAt] = VenueClock.now()
        }
        TrustedDevices.deleteWhere { TrustedDevices.userId eq userId }
    }
}
