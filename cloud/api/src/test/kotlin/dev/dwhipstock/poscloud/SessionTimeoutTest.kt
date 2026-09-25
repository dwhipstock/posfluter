package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.auth.SessionPolicy
import dev.dwhipstock.poscloud.auth.sha256Hex
import dev.dwhipstock.poscloud.auth.sweepExpiredSessions
import dev.dwhipstock.poscloud.db.PortalSessions
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Portal session lifetime: sliding idle timeout, absolute cap, background polls, cleanup. */
class SessionTimeoutTest {

    private val tenant = "t-session"

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant(tenant)
    }

    private fun session(): String = seedSession(tenant, seedUser(tenant, "owner@session.test", "pw-123456"))

    /** Rewrite a session's clock columns — how the tests "wait" an hour. */
    private fun age(token: String, lastUsedAgo: Duration? = null, createdAgo: Duration? = null) = transaction {
        val now = CloudTime.now()
        PortalSessions.update({ PortalSessions.tokenSha256 eq sha256Hex(token) }) {
            lastUsedAgo?.let { d -> it[lastUsedAt] = now.minus(d) }
            createdAgo?.let { d -> it[createdAt] = now.minus(d) }
        }
    }

    private fun lastUsed(token: String): OffsetDateTime? = transaction {
        PortalSessions.selectAll().where { PortalSessions.tokenSha256 eq sha256Hex(token) }
            .firstOrNull()?.get(PortalSessions.lastUsedAt)
    }

    private suspend fun ApplicationTestBuilder.poll(path: String, token: String): HttpResponse =
        client.get(path) {
            header(HttpHeaders.Cookie, "pos_portal_session=$token")
            header("X-Background", "1")
        }

    private fun code(res: HttpResponse, body: String) =
        testJson.parseToJsonElement(body).jsonObject["code"]!!.jsonPrimitive.content

    @Test
    fun idleSessionIsRejectedWithReasonAndClearedCookie() = testApplication {
        application { module(TestSupport.config) }
        val token = session()
        age(token, lastUsedAgo = Duration.ofMinutes(61))

        val res = getWithCookie("/v1/auth/me", token)
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("session_idle", code(res, res.bodyAsText()))
        assertEquals("60", res.headers["X-Session-Idle-Minutes"])
        val cleared = res.setCookie().first { it.name == "pos_portal_session" }
        assertEquals(0, cleared.maxAge)

        // parallel requests from the same page keep getting the same reason (row
        // stays until the sweep), and it never comes back to life
        val again = getWithCookie("/v1/reports/summary", token)
        assertEquals(HttpStatusCode.Unauthorized, again.status)
        assertEquals("session_idle", code(again, again.bodyAsText()))
    }

    @Test
    fun idleWindowFollowsConfig() = testApplication {
        application { module(TestSupport.config.copy(sessionIdleMinutes = 5)) }
        val token = session()
        age(token, lastUsedAgo = Duration.ofMinutes(4))
        assertEquals(HttpStatusCode.OK, getWithCookie("/v1/auth/me", token).status)
        age(token, lastUsedAgo = Duration.ofMinutes(6))
        val res = getWithCookie("/v1/auth/me", token)
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("5", res.headers["X-Session-Idle-Minutes"])
    }

    @Test
    fun activityRefreshesTheIdleWindow() = testApplication {
        application { module(TestSupport.config) }
        val token = session()
        age(token, lastUsedAgo = Duration.ofMinutes(50))
        val before = lastUsed(token)!!

        assertEquals(HttpStatusCode.OK, getWithCookie("/v1/auth/me", token).status)
        val after = lastUsed(token)!!
        assertTrue(Duration.between(before, after) >= Duration.ofMinutes(49), "last_used_at slid forward")

        // 50 more minutes later: 100 min since sign-in, but only 50 idle → still valid
        age(token, lastUsedAgo = Duration.ofMinutes(50))
        assertEquals(HttpStatusCode.OK, getWithCookie("/v1/auth/me", token).status)
    }

    @Test
    fun backgroundPollDoesNotRefreshTheIdleWindow() = testApplication {
        application { module(TestSupport.config) }
        val token = session()
        age(token, lastUsedAgo = Duration.ofMinutes(50))
        val before = lastUsed(token)!!

        // a poll on a live session is served...
        assertEquals(HttpStatusCode.OK, poll("/v1/devices", token).status)
        assertEquals(HttpStatusCode.OK, poll("/v1/auth/me", token).status)
        // ...but doesn't count as activity
        assertEquals(before, lastUsed(token))

        // so a tab left polling still idles out
        age(token, lastUsedAgo = Duration.ofMinutes(61))
        val res = poll("/v1/devices", token)
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("session_idle", code(res, res.bodyAsText()))
    }

    @Test
    fun absoluteCapEndsAnActiveSession() = testApplication {
        application { module(TestSupport.config) }
        val token = session()
        // active a moment ago, but signed in 12h+ ago
        age(token, lastUsedAgo = Duration.ofMinutes(1), createdAgo = Duration.ofHours(12).plusMinutes(1))

        val res = getWithCookie("/v1/auth/me", token)
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("session_expired", code(res, res.bodyAsText()))
        assertNull(res.headers["X-Session-Idle-Minutes"])
    }

    @Test
    fun absoluteCapFollowsConfigEvenForOlderLongLivedRows() = testApplication {
        application { module(TestSupport.config.copy(sessionMaxHours = 2)) }
        val token = session() // seeded with a 30-day expires_at, like pre-change rows
        age(token, lastUsedAgo = Duration.ofMinutes(1), createdAgo = Duration.ofHours(2).plusMinutes(1))
        val res = getWithCookie("/v1/auth/me", token)
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("session_expired", code(res, res.bodyAsText()))
    }

    @Test
    fun loginCookieAndRowMatchTheAbsoluteCap() = testApplication {
        val config = TestSupport.config.copy(
            adminEmail = "cap@session.test", adminPassword = "pw-123456", totpRequired = false,
        )
        application { module(config) }
        val res = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"cap@session.test","password":"pw-123456"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val cookie = res.setCookie().first { it.name == "pos_portal_session" }
        assertEquals(12 * 3600, cookie.maxAge)
        val row = transaction {
            PortalSessions.selectAll().where { PortalSessions.tokenSha256 eq sha256Hex(cookie.value) }.first()
        }
        assertEquals(
            Duration.ofHours(12),
            Duration.between(row[PortalSessions.createdAt], row[PortalSessions.expiresAt]),
        )
    }

    @Test
    fun sweepDeletesIdleAndExpiredRowsOnly() {
        val live = session()
        val idle = seedSession(tenant, seedUser(tenant, "b@session.test", "pw-123456"))
        val old = seedSession(tenant, seedUser(tenant, "c@session.test", "pw-123456"))
        age(idle, lastUsedAgo = Duration.ofMinutes(61))
        age(old, lastUsedAgo = Duration.ofMinutes(1), createdAgo = Duration.ofHours(13))

        val deleted = transaction { sweepExpiredSessions(SessionPolicy()) }
        assertEquals(2, deleted)
        assertNotNull(lastUsed(live))
        assertNull(lastUsed(idle))
        assertNull(lastUsed(old))
    }

    @Test
    fun signInSweepsDeadSessions() = testApplication {
        val config = TestSupport.config.copy(
            adminEmail = "sweep@session.test", adminPassword = "pw-123456", totpRequired = false,
        )
        application { module(config) }
        client.get("/health") // boot (seeds the admin) before planting a stale row
        val stale = session()
        age(stale, lastUsedAgo = Duration.ofHours(2))
        val res = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"sweep@session.test","password":"pw-123456"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertNull(lastUsed(stale))
    }

    @Test
    fun configDefaultsAndOverrides() {
        val c = CloudConfig()
        // defaults unless the environment sets them
        if (System.getenv("PORTAL_SESSION_IDLE_MINUTES").isNullOrBlank()) assertEquals(60, c.sessionIdleMinutes)
        if (System.getenv("PORTAL_SESSION_MAX_HOURS").isNullOrBlank()) assertEquals(12, c.sessionMaxHours)
        assertEquals(SessionPolicy(60, 12), SessionPolicy.from(TestSupport.config))
    }
}
