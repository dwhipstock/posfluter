package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.auth.ROLE_MANAGER
import dev.dwhipstock.poscloud.auth.hashPassword
import dev.dwhipstock.poscloud.auth.verifyPassword
import dev.dwhipstock.poscloud.db.PortalSessions
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
 * Idempotent boot seed (CONTRACT §6): the deployment's tenant ([CloudConfig.tenantId],
 * default 'copperlantern') and its stores
 * ([CloudConfig.stores]; default: 'vieux-port' alone), plus each store's API key
 * and the portal admin when the env vars are set. Existing users are left
 * untouched; only their absence triggers creation. Stores not listed are never
 * touched (a store is only ever added here, never removed).
 */
object Bootstrap {

    /** The default tenant id (TENANT_ID unset): the first client's, kept by every existing database. */
    const val TENANT = "copperlantern"

    private val log = LoggerFactory.getLogger(Bootstrap::class.java)

    /** venueId → plaintext key: STORE_API_KEY for the primary store, then STORE_API_KEYS. */
    fun storeKeys(config: CloudConfig): Map<String, String> = buildMap {
        config.storeApiKey?.let { put(config.stores.first().venueId, it) }
        putAll(config.storeApiKeys)
    }

    fun run(config: CloudConfig): Unit = transaction {
        val tenant = config.tenantId
        val now = dev.dwhipstock.poscloud.CloudTime.now()
        Tenants.insertIgnore {
            it[id] = tenant
            it[name] = config.venueName
            it[createdAt] = now
        }
        // The group name follows env (VENUE_NAME) on every boot, like the store
        // names below: a tenant row seeded once under an older name would
        // otherwise keep showing it in the portal forever. Name only.
        Tenants.update({ Tenants.id eq tenant }) {
            it[name] = config.venueName
            it[reportingCurrency] = config.reportingCurrency
        }
        for (store in config.stores) {
            // Do not use upsert here: PostgreSQL fills omitted columns from their
            // defaults on the UPDATE path, which erased the store's subdomain,
            // install identity, heartbeat, and LAN URL on every API restart.
            Venues.insertIgnore {
                it[tenantId] = tenant
                it[id] = store.venueId
                it[name] = store.name
                it[timezone] = config.storeZones[store.venueId] ?: config.venueTz
            }
            // The name follows env; the timezone is set on insert only. It decides
            // every business day and report hour, so a boot with a different or
            // defaulted VENUE_TZ must never silently re-zone a venue's history.
            // Currency, country and kind follow env too, but only for the stores
            // env names: an unlisted store keeps what it has (CAD, CA, restaurant).
            Venues.update({ (Venues.tenantId eq tenant) and (Venues.id eq store.venueId) }) {
                it[name] = store.name
                config.storeCurrencies[store.venueId]?.let { c -> it[currency] = c }
                config.storeCountries[store.venueId]?.let { c -> it[country] = c }
                if (store.venueId in config.retailStores) it[kind] = "retail"
            }
        }
        val known = config.stores.map { it.venueId }.toSet()
        storeKeys(config).forEach { (venueId, key) ->
            if (venueId !in known) {
                log.warn("STORE_API_KEYS names '$venueId', which is not in STORES — key ignored")
                return@forEach
            }
            StoreApiKeys.insertIgnore {
                it[tenantId] = tenant
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
                (PortalUsers.tenantId eq tenant) and (PortalUsers.email eq email)
            }.any()
            if (!exists) {
                PortalUsers.insert {
                    it[tenantId] = tenant
                    it[PortalUsers.email] = email
                    it[passwordHash] = hashPassword(password)
                    it[totpEnabled] = false // forces TOTP enrollment at first login
                    it[displayName] = "Owner"
                    it[createdAt] = now
                }
            }
        }
        seedDemoUser(config, tenant, now)
        config.resetTotpEmail?.let { resetEmail ->
            val users = PortalUsers.selectAll().where {
                (PortalUsers.tenantId eq tenant) and (PortalUsers.email eq resetEmail)
            }.map { it[PortalUsers.id] }
            users.forEach { userId ->
                PortalUsers.update({ PortalUsers.id eq userId }) {
                    it[totpEnabled] = false
                    it[totpSecret] = null
                    it[totpLastStep] = null // a new secret: its first code may share the old one's 30 s step
                }
                PortalBackupCodes.deleteWhere {
                    (PortalBackupCodes.tenantId eq tenant) and (PortalBackupCodes.userId eq userId)
                }
            }
            if (users.isEmpty()) log.warn("RESET_TOTP_EMAIL='$resetEmail' matched no portal user")
            else log.warn("RESET_TOTP_EMAIL: wiped TOTP for '$resetEmail' — next login re-enrolls. Clear the env var now.")
        }
    }

    /** The demo login's display name in the portal. */
    const val DEMO_DISPLAY_NAME = "Demo"

    /**
     * The demo login (DEMO_USER_NAME + DEMO_USER_PASSWORD), idempotently: created
     * when missing; on later boots its password hash follows env (a change also
     * ends its open sessions) and it is kept a demo manager with no TOTP. It is
     * stored by its lowercased username in the email column (no '@', so it never
     * collides with an owner). A demo row env no longer names — the username
     * changed, or the vars were removed — is retired: unusable password, sessions
     * ended. An existing NON-demo user is never touched. Passwords are never logged.
     * Call inside a transaction.
     */
    internal fun seedDemoUser(config: CloudConfig, tenant: String, now: OffsetDateTime) {
        val name = config.demoUserName
        val password = config.demoUserPassword
        val username = when {
            name == null && password == null -> null
            name == null || password == null -> {
                log.warn("demo login: set both DEMO_USER_NAME and DEMO_USER_PASSWORD — no demo login seeded")
                null
            }
            !DEMO_USERNAME_RE.matches(name) -> {
                log.warn("demo login: DEMO_USER_NAME must be 3-40 letters, digits, '-', '_' or '.' — no demo login seeded")
                null
            }
            else -> name.lowercase()
        }
        // retire demo rows env no longer names
        PortalUsers.selectAll().where { (PortalUsers.tenantId eq tenant) and (PortalUsers.isDemo eq true) }
            .filter { it[PortalUsers.email] != username && it[PortalUsers.passwordHash] != RETIRED_HASH }
            .forEach { row ->
                PortalUsers.update({ PortalUsers.id eq row[PortalUsers.id] }) { it[passwordHash] = RETIRED_HASH }
                PortalSessions.deleteWhere { PortalSessions.userId eq row[PortalUsers.id] }
                log.info("demo login '${row[PortalUsers.email]}' retired (no longer DEMO_USER_NAME)")
            }
        if (username == null || password == null) return
        val existing = PortalUsers.selectAll().where {
            (PortalUsers.tenantId eq tenant) and (PortalUsers.email eq username)
        }.firstOrNull()
        if (existing == null) {
            PortalUsers.insert {
                it[tenantId] = tenant
                it[email] = username
                it[passwordHash] = hashPassword(password)
                it[totpEnabled] = false
                it[displayName] = DEMO_DISPLAY_NAME
                it[role] = ROLE_MANAGER
                it[isDemo] = true
                it[createdAt] = now
            }
            log.info("demo login '$username' created (manager, every store)")
            return
        }
        if (!existing[PortalUsers.isDemo]) {
            log.warn("demo login: '$username' is already a real portal user — left untouched, no demo login")
            return
        }
        val id = existing[PortalUsers.id]
        val passwordChanged = !verifyPassword(password, existing[PortalUsers.passwordHash])
        PortalUsers.update({ PortalUsers.id eq id }) {
            if (passwordChanged) it[passwordHash] = hashPassword(password)
            it[role] = ROLE_MANAGER
            it[displayName] = DEMO_DISPLAY_NAME
            it[totpEnabled] = false
            it[totpSecret] = null
        }
        if (passwordChanged) {
            PortalSessions.deleteWhere { PortalSessions.userId eq id }
            log.info("demo login '$username': password changed, open sessions ended")
        }
    }

    /** Not a bcrypt hash: verifyPassword never accepts it. */
    private const val RETIRED_HASH = "!retired-demo-login"
}
