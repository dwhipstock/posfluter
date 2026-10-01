package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.auth.LoginRateLimiter
import dev.dwhipstock.poscloud.auth.verifyPassword
import dev.dwhipstock.poscloud.db.PortalSessions
import dev.dwhipstock.poscloud.db.PortalUsers
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PORTAL_DEMO_MODE + the demo login (DEMO_USER_NAME / DEMO_USER_PASSWORD), migration 028. */
class DemoLoginTest {

    private val tenant = Bootstrap.TENANT
    private val demoName = "lantern-demo"
    private val demoPw = "copper-lantern-2026"
    private val ownerEmail = "owner@demo-test.dev"
    private val ownerPw = "correct-horse-battery"

    @Before
    fun setUp() {
        TestSupport.reset()
        LoginRateLimiter.reset()
    }

    private fun demoConfig(on: Boolean = true, name: String? = demoName, pw: String? = demoPw) =
        TestSupport.config.copy(
            adminEmail = ownerEmail, adminPassword = ownerPw,
            demoMode = on, demoUserName = name, demoUserPassword = pw,
        )

    /** A portal_users row read inside a transaction (an auto-increment id can't be read outside one). */
    private data class User(
        val id: Long, val email: String, val hash: String, val role: String,
        val isDemo: Boolean, val displayName: String, val totpEnabled: Boolean,
    )

    private fun users(): List<User> = transaction {
        PortalUsers.selectAll().orderBy(PortalUsers.id).map {
            User(
                it[PortalUsers.id], it[PortalUsers.email], it[PortalUsers.passwordHash], it[PortalUsers.role],
                it[PortalUsers.isDemo], it[PortalUsers.displayName], it[PortalUsers.totpEnabled],
            )
        }
    }

    private fun demoRow(username: String = demoName): User? = users().firstOrNull { it.email == username }

    private fun ownerId(): Long = users().single { it.email == ownerEmail }.id

    private suspend fun ApplicationTestBuilder.login(user: String, pw: String): HttpResponse =
        client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$user","password":"$pw"}""")
        }

    private fun HttpResponse.json() = testJson.parseToJsonElement(
        kotlinx.coroutines.runBlocking { bodyAsText() }).jsonObject

    private fun HttpResponse.session() = setCookie().first { it.name == "pos_portal_session" }.value

    // ---- bootstrap ----

    @Test
    fun bootstrapCreatesTheDemoLoginOnceAndFollowsThePassword() {
        TestSupport.reset()
        val cfg = demoConfig()
        Bootstrap.run(cfg)
        Bootstrap.run(cfg) // idempotent: still one demo row
        val rows = users().filter { it.isDemo }
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(demoName, row.email)
        assertEquals("manager", row.role)
        assertEquals("Demo", row.displayName)
        assertFalse(row.totpEnabled)
        assertTrue(verifyPassword(demoPw, row.hash))
        val firstHash = row.hash
        Bootstrap.run(cfg)
        assertEquals(firstHash, demoRow()!!.hash, "same password: no re-hash")

        // the owner is a separate, real user
        val owner = users().single { it.email == ownerEmail }
        assertEquals("owner", owner.role)
        assertFalse(owner.isDemo)

        // a changed password re-hashes and ends open sessions
        val token = seedSession(tenant, row.id)
        Bootstrap.run(cfg.copy(demoUserPassword = "a-new-demo-password"))
        val updated = demoRow()!!
        assertEquals(row.id, updated.id)
        assertTrue(verifyPassword("a-new-demo-password", updated.hash))
        assertFalse(verifyPassword(demoPw, updated.hash))
        assertEquals(0L, transaction {
            PortalSessions.selectAll().where { PortalSessions.userId eq row.id }.count()
        })
        assertNotEquals("", token)
    }

    @Test
    fun usernameIsStoredLowercasedAndInvalidOnesAreSkipped() {
        Bootstrap.run(demoConfig(name = "Lantern-Demo"))
        assertTrue(demoRow("lantern-demo")!!.isDemo)

        TestSupport.reset()
        for (bad in listOf("demo@example.com", "ab", "has space", "x".repeat(41))) {
            Bootstrap.run(demoConfig(name = bad))
        }
        Bootstrap.run(demoConfig(pw = null)) // name without password: nothing
        assertTrue(users().none { it.isDemo })
    }

    @Test
    fun bootstrapNeverTurnsARealUserIntoTheDemoLogin() {
        seedTenant(tenant)
        seedUser(tenant, demoName, "the-real-users-password")
        Bootstrap.run(demoConfig())
        val row = demoRow()!!
        assertFalse(row.isDemo)
        assertEquals("owner", row.role)
        assertTrue(verifyPassword("the-real-users-password", row.hash))
    }

    @Test
    fun aDemoLoginEnvNoLongerNamesIsRetired() = testApplication {
        Bootstrap.run(demoConfig())
        val renamed = demoConfig(name = "other-demo")
        application { module(renamed) }
        assertEquals(HttpStatusCode.Unauthorized, login(demoName, demoPw).status)
        assertEquals(HttpStatusCode.OK, login("other-demo", demoPw).status)
        assertFalse(verifyPassword(demoPw, demoRow()!!.hash))
    }

    // ---- sign-in ----

    @Test
    fun demoLoginSignsInWithoutTotpInDemoMode() = testApplication {
        application { module(demoConfig(on = true)) }
        val res = login("Lantern-Demo", demoPw) // username, any case
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("authenticated", res.json()["stage"]!!.jsonPrimitive.content)
        val me = getWithCookie("/v1/auth/me", res.session())
        assertEquals(HttpStatusCode.OK, me.status)
        val body = me.json()
        assertEquals("manager", body["role"]!!.jsonPrimitive.content)
        assertEquals("true", body["canEditMenu"]!!.jsonPrimitive.content, "a manager edits the menu (027)")
        assertEquals("true", body["demo"]!!.jsonPrimitive.content)
        assertEquals("true", body["demoMode"]!!.jsonPrimitive.content)
        assertEquals("Demo", body["displayName"]!!.jsonPrimitive.content)
    }

    @Test
    fun wrongDemoPasswordIsStillRefused() = testApplication {
        application { module(demoConfig(on = true)) }
        val res = login(demoName, "not-it")
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("bad_credentials", res.json()["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun ownerStillNeedsTotpInDemoMode() = testApplication {
        application { module(demoConfig(on = true)) }
        val res = login(ownerEmail, ownerPw)
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("totp_setup", res.json()["stage"]!!.jsonPrimitive.content)
        assertTrue(res.setCookie().none { it.name == "pos_portal_session" && it.value.isNotEmpty() })
        // and the badge shows for the owner too once signed in (me.demoMode)
                val me = getWithCookie("/v1/auth/me", seedSession(tenant, ownerId())).json()
        assertEquals("true", me["demoMode"]!!.jsonPrimitive.content)
        assertEquals("false", me["demo"]!!.jsonPrimitive.content)
        assertEquals("owner", me["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun demoLoginCannotSignInWhenDemoModeIsOff() = testApplication {
        application { module(demoConfig(on = false)) }
        val res = login(demoName, demoPw)
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("demo_mode_off", res.json()["code"]!!.jsonPrimitive.content)
        // a session left over from a demo stops working too
        val stale = seedSession(tenant, demoRow()!!.id)
        val me = getWithCookie("/v1/auth/me", stale)
        assertEquals(HttpStatusCode.Unauthorized, me.status)
        assertEquals("demo_mode_off", me.json()["code"]!!.jsonPrimitive.content)
        // and the owner is unaffected (still TOTP)
        assertEquals("totp_setup", login(ownerEmail, ownerPw).json()["stage"]!!.jsonPrimitive.content)
        assertEquals("false", getWithCookie("/v1/auth/me",
            seedSession(tenant, ownerId()))
            .json()["demoMode"]!!.jsonPrimitive.content)
    }

    @Test
    fun demoSignInIsRateLimited() = testApplication {
        application { module(demoConfig(on = true)) }
        repeat(10) { assertEquals(HttpStatusCode.Unauthorized, login(demoName, "wrong-$it").status) }
        assertEquals(HttpStatusCode.TooManyRequests, login(demoName, demoPw).status)
    }

    // ---- what the demo login may do ----

    @Test
    fun demoLoginSeesReportsAndMenuButCannotManageDevices() = testApplication {
        application { module(demoConfig(on = true)) }
        val demo = login(demoName, demoPw).session()
        for (path in listOf(
            "/v1/venues", "/v1/reports/summary?from=2026-09-01&to=2026-09-30",
            "/v1/menu", "/v1/staff", "/v1/devices", "/v1/stock",
        )) {
            assertEquals(HttpStatusCode.OK, getWithCookie(path, demo).status, path)
        }
        suspend fun asDemo(method: HttpMethod, path: String) = client.request(path) {
            this.method = method
            header(HttpHeaders.Cookie, "pos_portal_session=$demo")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        for ((method, path) in listOf(
            HttpMethod.Post to "/v1/venues/vieux-port/pairing-codes",
            HttpMethod.Post to "/v1/venues/vieux-port/devices/tab-1/revoke",
            HttpMethod.Delete to "/v1/venues/vieux-port/devices/tab-1",
        )) {
            val res = asDemo(method, path)
            assertEquals(HttpStatusCode.Forbidden, res.status, "$method $path")
            assertEquals("owner_only", res.json()["code"]!!.jsonPrimitive.content)
        }

        // the owner still can
                val pairing = client.post("/v1/venues/vieux-port/pairing-codes") {
            header(HttpHeaders.Cookie, "pos_portal_session=${seedSession(tenant, ownerId())}")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Created, pairing.status)
    }

    @Test
    fun parseOnOffAcceptsTheUsualSpellings() {
        for (v in listOf("on", "ON", "true", "1", "yes", " on ")) assertTrue(parseOnOff(v), v)
        for (v in listOf(null, "", "off", "false", "0", "no", "maybe")) assertFalse(parseOnOff(v), "$v")
        assertNull(TestSupport.config.demoUserName)
    }
}
