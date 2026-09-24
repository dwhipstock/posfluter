package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.auth.hashPassword
import dev.dwhipstock.poscloud.auth.newToken
import dev.dwhipstock.poscloud.auth.sha256Hex
import dev.dwhipstock.poscloud.db.Db
import dev.dwhipstock.poscloud.db.Migrations
import dev.dwhipstock.poscloud.db.PortalSessions
import dev.dwhipstock.poscloud.db.PortalUsers
import dev.dwhipstock.poscloud.db.StoreApiKeys
import dev.dwhipstock.poscloud.db.Tenants
import dev.dwhipstock.poscloud.db.Venues
import dev.dwhipstock.poscloud.store.IngestEvent
import dev.dwhipstock.poscloud.store.IngestRequest
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDateTime
import java.util.UUID

/** Suites hit a real Postgres: pos_cloud_test, created on demand, truncated per test.
 *
 * Connection details are env-overridable so the same suite runs on a dev box (the
 * defaults below — local Postgres, current OS user, no password) AND in CI against
 * a `postgres:17` service container (TEST_DB_URL/USER/PASSWORD point at it). Nothing
 * about the URL, user, or password is hardcoded anymore — that hardcoding was the
 * one thing keeping this suite from running anywhere but this laptop. */
object TestSupport {

    private fun tenv(name: String, default: String) =
        System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

    // Maintenance DB used only to CREATE the test DB; in CI point it at the
    // service container's default `postgres` database with a superuser.
    private val adminUrl = tenv("TEST_DB_ADMIN_URL", "jdbc:postgresql://localhost:5432/postgres")

    val config = CloudConfig(
        databaseUrl = tenv("TEST_DB_URL", "jdbc:postgresql://localhost:5432/pos_cloud_test"),
        dbUser = tenv("TEST_DB_USER", System.getProperty("user.name")),
        dbPassword = tenv("TEST_DB_PASSWORD", ""),
        migrationsDir = tenv("TEST_MIGRATIONS_DIR", "../migrations"),
        adminEmail = null,
        adminPassword = null,
        storeApiKey = null,
        venueName = "Test Pub",
        venueTz = "America/New_York",
    )

    private var initialized = false

    @Synchronized
    fun reset() {
        if (!initialized) {
            ensureDatabase()
            Migrations.run(Db.connect(config), File(config.migrationsDir))
            initialized = true
        }
        transaction {
            exec(
                "TRUNCATE tenants, venues, store_api_keys, portal_users, portal_sessions, " +
                    "portal_backup_codes, login_pending, events, checks, check_lines, check_tenders, shifts, " +
                    "refunds, cash_movements, " +
                    "catalog_categories, catalog_items, catalog_variants, catalog_changes, " +
                    "staff, staff_venues, role_grants, staff_grants, " +
                    "pairing_codes, devices, " +
                    "item_photos RESTART IDENTITY CASCADE"
            )
        }
    }

    private fun ensureDatabase() {
        // Derive the DB name from the (possibly overridden) URL so CREATE targets
        // exactly what config.databaseUrl connects to.
        val dbName = config.databaseUrl.substringAfterLast('/').substringBefore('?')
        DriverManager.getConnection(
            adminUrl, config.dbUser, config.dbPassword,
        ).use { conn ->
            conn.createStatement().use { st ->
                try {
                    st.execute("CREATE DATABASE \"$dbName\"")
                } catch (e: SQLException) {
                    if (e.sqlState != "42P04") throw e // 42P04 = duplicate_database
                }
            }
        }
    }
}

// --- seed helpers ---

fun seedTenant(tenantId: String, venueId: String = "main", venueName: String = "Test Pub") = transaction {
    Tenants.insertIgnore {
        it[id] = tenantId
        it[name] = venueName
        it[createdAt] = LocalDateTime.now()
    }
    Venues.insertIgnore {
        it[Venues.tenantId] = tenantId
        it[id] = venueId
        it[name] = venueName
        it[timezone] = "America/New_York"
    }
}

fun seedStoreKey(tenantId: String, venueId: String, key: String) = transaction {
    StoreApiKeys.insertIgnore {
        it[StoreApiKeys.tenantId] = tenantId
        it[StoreApiKeys.venueId] = venueId
        it[keySha256] = sha256Hex(key)
        it[label] = "test"
        it[createdAt] = LocalDateTime.now()
    }
}

fun seedUser(tenantId: String, email: String, password: String, totpSecret: String? = null): Long = transaction {
    PortalUsers.insert {
        it[PortalUsers.tenantId] = tenantId
        it[PortalUsers.email] = email
        it[passwordHash] = hashPassword(password)
        it[PortalUsers.totpSecret] = totpSecret
        it[totpEnabled] = totpSecret != null
        it[displayName] = "Test User"
        it[createdAt] = LocalDateTime.now()
    } get PortalUsers.id
}

/** Direct session row — auth flow is covered by AuthTotpTest; other suites just need a cookie. */
fun seedSession(tenantId: String, userId: Long): String = transaction {
    val token = newToken()
    val now = LocalDateTime.now()
    PortalSessions.insert {
        it[tokenSha256] = sha256Hex(token)
        it[PortalSessions.tenantId] = tenantId
        it[PortalSessions.userId] = userId
        it[createdAt] = now
        it[expiresAt] = now.plusDays(30)
        it[lastUsedAt] = now
    }
    token
}

// --- request helpers ---

val testJson = Json { ignoreUnknownKeys = true }

fun event(
    eventType: String, payload: JsonObject,
    seq: Long, eventId: String = UUID.randomUUID().toString(),
    createdAt: String = "2026-07-10T20:00:00",
    aggregateType: String = eventType.substringBefore('.'),
    aggregateId: String = "1",
) = IngestEvent(eventId, seq, eventType, aggregateType, aggregateId, createdAt, payload)

fun ingestBody(vararg events: IngestEvent): String =
    testJson.encodeToString(IngestRequest.serializer(), IngestRequest(events.toList()))

suspend fun ApplicationTestBuilder.ingest(key: String, vararg events: IngestEvent): JsonObject {
    val res = client.post("/v1/ingest") {
        header(HttpHeaders.Authorization, "Bearer $key")
        contentType(ContentType.Application.Json)
        setBody(ingestBody(*events))
    }
    check(res.status == HttpStatusCode.OK) { "ingest failed: ${res.status} ${res.bodyAsText()}" }
    return testJson.parseToJsonElement(res.bodyAsText()) as JsonObject
}

suspend fun ApplicationTestBuilder.getWithCookie(path: String, sessionToken: String): HttpResponse =
    client.get(path) { header(HttpHeaders.Cookie, "pos_portal_session=$sessionToken") }

/** Canadian test fixture: inclusive 13% sales-tax decomposition, half-up cents. */
fun storeTax(gross: Long): Long = (gross * 13 * 2 + 113) / 226

fun checkClosedPayload(
    checkId: Int, gross: Long, tax: Long? = null,
    closedAt: String? = null, shiftId: Long? = null,
    lines: kotlinx.serialization.json.JsonArray? = null,
    tenders: kotlinx.serialization.json.JsonArray? = null,
    tableLabel: String? = null, zoneId: String? = null, zoneNameEn: String? = null,
): JsonObject = buildJsonObject {
    put("checkId", testJson.encodeToJsonElement(checkId))
    put("grandTotalCents", testJson.encodeToJsonElement(gross))
    tax?.let { put("taxIncludedCents", testJson.encodeToJsonElement(it)) }
    closedAt?.let { put("closedAt", testJson.encodeToJsonElement(it)) }
    shiftId?.let { put("shiftId", testJson.encodeToJsonElement(it)) }
    lines?.let { put("lines", it) }
    tenders?.let { put("tenders", it) }
    tableLabel?.let { put("tableLabel", testJson.encodeToJsonElement(it)) }
    zoneId?.let { put("zoneId", testJson.encodeToJsonElement(it)) }
    zoneNameEn?.let { put("zoneNameEn", testJson.encodeToJsonElement(it)) }
}
