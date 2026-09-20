package dev.dwhipstock.pos.base

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * The fixed grant vocabulary — a basic, predefined permission set that gates POS
 * actions. Shared shape with the cloud (CONTRACT §7); not custom RBAC.
 */
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

    private val SERVER_DEFAULTS = setOf(PRICE_OVERRIDE)

    fun defaultGranted(role: String, permission: String): Boolean = when (role) {
        "MANAGER" -> true
        "SERVER" -> permission in SERVER_DEFAULTS
        else -> false // an unrecognized role gets nothing — fail closed
    }
}

/**
 * Local grant store, cloud-mirrored (CONTRACT §7): effective grant for a staff
 * member = per-staff override ?? role default ?? built-in default. Enforced offline
 * on the store. The read/apply helpers must run inside a transaction (like
 * [dev.dwhipstock.pos.db.SyncState] / [dev.dwhipstock.pos.sdk.Outbox]).
 */
object GrantsRepo {

    /**
     * Seed the default role matrix if empty. Migration 024 only creates the table,
     * so this covers existing store databases too. Idempotent; runs at startup.
     */
    fun seedDefaultRoleGrantsIfEmpty() = transaction {
        if (RoleGrants.selectAll().any()) return@transaction
        Permissions.ROLES.forEach { role ->
            Permissions.ALL.forEach { perm ->
                RoleGrants.insert {
                    it[RoleGrants.role] = role
                    it[permission] = perm
                    it[granted] = Permissions.defaultGranted(role, perm)
                }
            }
        }
    }

    private fun roleDefault(role: String, permission: String): Boolean =
        RoleGrants.selectAll()
            .where { (RoleGrants.role eq role) and (RoleGrants.permission eq permission) }
            .firstOrNull()?.get(RoleGrants.granted)
            ?: Permissions.defaultGranted(role, permission)

    /** Effective grant for one permission. Call inside a transaction. */
    fun has(userId: String, permission: String): Boolean {
        val role = Users.selectAll().where { Users.id eq userId }.firstOrNull()?.get(Users.role)
            ?: return false
        val override = StaffGrants.selectAll()
            .where { (StaffGrants.staffId eq userId) and (StaffGrants.permission eq permission) }
            .firstOrNull()?.get(StaffGrants.granted)
        return override ?: roleDefault(role, permission)
    }

    /** Every permission this user effectively has. Call inside a transaction. */
    fun effectiveGrants(userId: String): List<String> {
        val role = Users.selectAll().where { Users.id eq userId }.firstOrNull()?.get(Users.role)
            ?: return emptyList()
        val overrides = StaffGrants.selectAll().where { StaffGrants.staffId eq userId }
            .associate { it[StaffGrants.permission] to it[StaffGrants.granted] }
        return Permissions.ALL.filter { perm -> overrides[perm] ?: roleDefault(role, perm) }
    }

    /** Replace the whole role matrix from a distributed snapshot. Call inside a transaction. */
    fun applyRoleGrants(roles: JsonObject) {
        RoleGrants.deleteAll()
        Permissions.ROLES.forEach { role ->
            val perms = roles[role] as? JsonObject
            Permissions.ALL.forEach { perm ->
                val granted = perms?.get(perm)?.jsonPrimitive?.booleanOrNull
                    ?: Permissions.defaultGranted(role, perm)
                RoleGrants.insert {
                    it[RoleGrants.role] = role
                    it[permission] = perm
                    it[RoleGrants.granted] = granted
                }
            }
        }
    }

    /** Replace a staff member's overrides with exactly this set. Call inside a transaction. */
    fun applyStaffOverrides(staffId: String, overrides: JsonObject) {
        StaffGrants.deleteWhere { StaffGrants.staffId eq staffId }
        overrides.forEach { (perm, value) ->
            val granted = (value as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: return@forEach
            StaffGrants.insert {
                it[StaffGrants.staffId] = staffId
                it[permission] = perm
                it[StaffGrants.granted] = granted
            }
        }
    }
}
