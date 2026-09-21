package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.auth.hashPassword
import dev.dwhipstock.poscloud.auth.sha256Hex
import dev.dwhipstock.poscloud.catalog.Scope
import dev.dwhipstock.poscloud.staff.seedStaffDefaults
import dev.dwhipstock.poscloud.db.PortalBackupCodes
import dev.dwhipstock.poscloud.db.PortalUsers
import dev.dwhipstock.poscloud.db.StoreApiKeys
import dev.dwhipstock.poscloud.db.Tenants
import dev.dwhipstock.poscloud.db.Venues
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Idempotent boot seed (CONTRACT §6): tenant 'copperlantern', venue 'main', plus the
 * store key / portal admin when the env vars are set. Existing users are left
 * untouched; only their absence triggers creation.
 */
object Bootstrap {

    const val TENANT = "copperlantern"
    const val VENUE = "main"

    private val log = LoggerFactory.getLogger(Bootstrap::class.java)

    fun run(config: CloudConfig): Unit = transaction {
        val now = LocalDateTime.now()
        Tenants.insertIgnore {
            it[id] = TENANT
            it[name] = config.venueName
            it[createdAt] = now
        }
        // Do not use upsert here: PostgreSQL fills omitted columns from their
        // defaults on the UPDATE path, which erased the store's subdomain,
        // install identity, heartbeat, and LAN URL on every API restart.
        Venues.insertIgnore {
            it[tenantId] = TENANT
            it[id] = VENUE
            it[name] = config.venueName
            it[timezone] = config.venueTz
        }
        Venues.update({ (Venues.tenantId eq TENANT) and (Venues.id eq VENUE) }) {
            it[name] = config.venueName
            it[timezone] = config.venueTz
        }
        // Default staff + grant matrix (CONTRACT §7), distributed to the store once.
        seedStaffDefaults(Scope(TENANT, VENUE))
        config.storeApiKey?.let { key ->
            StoreApiKeys.insertIgnore {
                it[tenantId] = TENANT
                it[venueId] = VENUE
                it[keySha256] = sha256Hex(key)
                it[label] = "bootstrap"
                it[createdAt] = now
            }
        }
        val email = config.adminEmail
        val password = config.adminPassword
        if (email != null && password != null) {
            val exists = PortalUsers.selectAll().where {
                (PortalUsers.tenantId eq TENANT) and (PortalUsers.email eq email)
            }.any()
            if (!exists) {
                PortalUsers.insert {
                    it[tenantId] = TENANT
                    it[PortalUsers.email] = email
                    it[passwordHash] = hashPassword(password)
                    it[totpEnabled] = false // forces TOTP enrollment at first login
                    it[displayName] = "Owner"
                    it[createdAt] = now
                }
            }
        }
        config.resetTotpEmail?.let { resetEmail ->
            val users = PortalUsers.selectAll().where {
                (PortalUsers.tenantId eq TENANT) and (PortalUsers.email eq resetEmail)
            }.map { it[PortalUsers.id] }
            users.forEach { userId ->
                PortalUsers.update({ PortalUsers.id eq userId }) {
                    it[totpEnabled] = false
                    it[totpSecret] = null
                }
                PortalBackupCodes.deleteWhere {
                    (PortalBackupCodes.tenantId eq TENANT) and (PortalBackupCodes.userId eq userId)
                }
            }
            if (users.isEmpty()) log.warn("RESET_TOTP_EMAIL='$resetEmail' matched no portal user")
            else log.warn("RESET_TOTP_EMAIL: wiped TOTP for '$resetEmail' — next login re-enrolls. Clear the env var now.")
        }
    }
}
