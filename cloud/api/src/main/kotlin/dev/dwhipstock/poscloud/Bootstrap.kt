package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.auth.hashPassword
import dev.dwhipstock.poscloud.auth.sha256Hex
import dev.dwhipstock.poscloud.catalog.Scope
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
import java.time.OffsetDateTime

/**
 * Idempotent boot seed (CONTRACT §6): tenant 'copperlantern' and its stores
 * ([CloudConfig.stores]; default: 'vieux-port' alone), plus each store's API key
 * and the portal admin when the env vars are set. Existing users are left
 * untouched; only their absence triggers creation. Stores not listed are never
 * touched (a store is only ever added here, never removed).
 */
object Bootstrap {

    const val TENANT = "copperlantern"

    private val log = LoggerFactory.getLogger(Bootstrap::class.java)

    /** venueId → plaintext key: STORE_API_KEY for the primary store, then STORE_API_KEYS. */
    fun storeKeys(config: CloudConfig): Map<String, String> = buildMap {
        config.storeApiKey?.let { put(config.stores.first().venueId, it) }
        putAll(config.storeApiKeys)
    }

    fun run(config: CloudConfig): Unit = transaction {
        val now = dev.dwhipstock.poscloud.CloudTime.now()
        Tenants.insertIgnore {
            it[id] = TENANT
            it[name] = config.venueName
            it[createdAt] = now
        }
        for (store in config.stores) {
            // Do not use upsert here: PostgreSQL fills omitted columns from their
            // defaults on the UPDATE path, which erased the store's subdomain,
            // install identity, heartbeat, and LAN URL on every API restart.
            Venues.insertIgnore {
                it[tenantId] = TENANT
                it[id] = store.venueId
                it[name] = store.name
                it[timezone] = config.venueTz
            }
            Venues.update({ (Venues.tenantId eq TENANT) and (Venues.id eq store.venueId) }) {
                it[name] = store.name
                it[timezone] = config.venueTz
            }
        }
        val known = config.stores.map { it.venueId }.toSet()
        storeKeys(config).forEach { (venueId, key) ->
            if (venueId !in known) {
                log.warn("STORE_API_KEYS names '$venueId', which is not in STORES — key ignored")
                return@forEach
            }
            StoreApiKeys.insertIgnore {
                it[tenantId] = TENANT
                it[StoreApiKeys.venueId] = venueId
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
