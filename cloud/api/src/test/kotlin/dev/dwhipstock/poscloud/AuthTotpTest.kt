package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.auth.Totp
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthTotpTest {

    private val email = "owner@test.dev"
    private val password = "correct-horse-battery"

    @Before
    fun setUp() {
        TestSupport.reset()
    }

    private fun bootstrapConfig(e: String = email) =
        TestSupport.config.copy(adminEmail = e, adminPassword = password)

    private suspend fun ApplicationTestBuilder.login(pw: String = password): HttpResponse =
        loginAs(email, pw)

    // Distinct emails per test keep the in-memory login rate-limiter (10/min per
    // email+IP, not reset between tests) from bleeding across the suite.
    private suspend fun ApplicationTestBuilder.loginAs(e: String, pw: String = password): HttpResponse =
        client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$e","password":"$pw"}""")
        }

    private data class Enrolled(val secret: String, val backupCodes: List<String>)

    /** Password → forced enrollment → confirm. Returns the secret and the one-time backup codes. */
    private suspend fun ApplicationTestBuilder.enroll(e: String): Enrolled {
        val setup = testJson.parseToJsonElement(loginAs(e).bodyAsText()).jsonObject
        assertEquals("totp_setup", setup["stage"]!!.jsonPrimitive.content)
        val secret = setup["secret"]!!.jsonPrimitive.content
        val pending = setup["pendingToken"]!!.jsonPrimitive.content
        val confirm = client.post("/v1/auth/totp/confirm") {
            contentType(ContentType.Application.Json)
            setBody("""{"pendingToken":"$pending","code":"${Totp.code(secret)}"}""")
        }
        assertEquals(HttpStatusCode.OK, confirm.status)
        val codes = testJson.parseToJsonElement(confirm.bodyAsText())
            .jsonObject["backupCodes"]!!.jsonArray.map { it.jsonPrimitive.content }
        return Enrolled(secret, codes)
    }

    /** Password step for an already-enrolled user → returns the fresh `totp` pending token. */
    private suspend fun ApplicationTestBuilder.pendingFor(e: String): String =
        testJson.parseToJsonElement(loginAs(e).bodyAsText()).jsonObject["pendingToken"]!!.jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.submitTotp(pending: String, code: String): HttpResponse =
        client.post("/v1/auth/totp") {
            contentType(ContentType.Application.Json)
            setBody("""{"pendingToken":"$pending","code":"$code"}""")
        }

    @Test
    fun demoModeCanAuthenticateWithPasswordOnly() = testApplication {
        application { module(bootstrapConfig().copy(totpRequired = false)) }

        val response = login()
        assertEquals(HttpStatusCode.OK, response.status)
        val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("authenticated", body["stage"]!!.jsonPrimitive.content)
        val session = response.setCookie().first { it.name == "pos_portal_session" }.value
        assertEquals(HttpStatusCode.OK, getWithCookie("/v1/auth/me", session).status)
    }

    @Test
    fun firstLoginForcesTotpEnrollmentThenSessionsWork() = testApplication {
        application { module(bootstrapConfig()) }

        assertEquals(HttpStatusCode.Unauthorized, login("wrong-password").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/reports/summary").status)

        // stage 1: bootstrap user has no TOTP → forced enrollment
        val setupRes = login()
        assertEquals(HttpStatusCode.OK, setupRes.status)
        val setup = testJson.parseToJsonElement(setupRes.bodyAsText()).jsonObject
        assertEquals("totp_setup", setup["stage"]!!.jsonPrimitive.content)
        val secret = setup["secret"]!!.jsonPrimitive.content
        assertTrue(setup["otpauthUri"]!!.jsonPrimitive.content.startsWith("otpauth://totp/"))

        // wrong code refused, pending token survives for a retry
        val pendingToken = setup["pendingToken"]!!.jsonPrimitive.content
        val realCode = Totp.code(secret)
        val wrongCode = if (realCode == "000000") "111111" else "000000"
        val bad = client.post("/v1/auth/totp/confirm") {
            contentType(ContentType.Application.Json)
            setBody("""{"pendingToken":"$pendingToken","code":"$wrongCode"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, bad.status)

        val confirm = client.post("/v1/auth/totp/confirm") {
            contentType(ContentType.Application.Json)
            setBody("""{"pendingToken":"$pendingToken","code":"${Totp.code(secret)}"}""")
        }
        assertEquals(HttpStatusCode.OK, confirm.status)
        val session = confirm.setCookie().first { it.name == "pos_portal_session" }.value

        val me = getWithCookie("/v1/auth/me", session)
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals(email,
            testJson.parseToJsonElement(me.bodyAsText()).jsonObject["email"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, getWithCookie("/v1/reports/summary", session).status)

        // stage 2: TOTP now enrolled → plain totp stage, single-use pending token
        val secondRes = login()
        val second = testJson.parseToJsonElement(secondRes.bodyAsText()).jsonObject
        assertEquals("totp", second["stage"]!!.jsonPrimitive.content)
        val secondPending = second["pendingToken"]!!.jsonPrimitive.content
        assertNotEquals(pendingToken, secondPending)

        val badSecond = client.post("/v1/auth/totp") {
            contentType(ContentType.Application.Json)
            setBody("""{"pendingToken":"$secondPending","code":"$wrongCode"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, badSecond.status)

        val goodSecond = client.post("/v1/auth/totp") {
            contentType(ContentType.Application.Json)
            setBody("""{"pendingToken":"$secondPending","code":"${Totp.code(secret)}"}""")
        }
        assertEquals(HttpStatusCode.OK, goodSecond.status)
        val session2 = goodSecond.setCookie().first { it.name == "pos_portal_session" }.value
        assertEquals(HttpStatusCode.OK, getWithCookie("/v1/auth/me", session2).status)

        // pending token was consumed: replay refused
        val replay = client.post("/v1/auth/totp") {
            contentType(ContentType.Application.Json)
            setBody("""{"pendingToken":"$secondPending","code":"${Totp.code(secret)}"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, replay.status)

        // logout revokes
        val logout = client.post("/v1/auth/logout") {
            header(HttpHeaders.Cookie, "pos_portal_session=$session2")
        }
        assertEquals(HttpStatusCode.OK, logout.status)
        assertEquals(HttpStatusCode.Unauthorized, getWithCookie("/v1/auth/me", session2).status)
    }

    @Test
    fun backupCodeSignsInOnceThenIsSpent() = testApplication {
        val user = "backup@test.dev"
        application { module(bootstrapConfig(user)) }

        val enrolled = enroll(user)
        assertEquals(10, enrolled.backupCodes.size)

        // phone gone → sign in with a backup code
        val ok = submitTotp(pendingFor(user), enrolled.backupCodes[0])
        assertEquals(HttpStatusCode.OK, ok.status)
        val session = ok.setCookie().first { it.name == "pos_portal_session" }.value
        assertEquals(HttpStatusCode.OK, getWithCookie("/v1/auth/me", session).status)

        // that code is single-use — a reuse is refused
        val reuse = submitTotp(pendingFor(user), enrolled.backupCodes[0])
        assertEquals(HttpStatusCode.Unauthorized, reuse.status)

        // a different code still works, and matching is case/format insensitive
        val ok2 = submitTotp(pendingFor(user), enrolled.backupCodes[1].uppercase())
        assertEquals(HttpStatusCode.OK, ok2.status)
    }

    @Test
    fun adminResetReEnrollsAndKillsOldBackupCodes() = testApplication {
        val user = "reset@test.dev"
        application { module(bootstrapConfig(user)) }

        val first = enroll(user)
        // TOTP is now required at login
        assertEquals("totp",
            testJson.parseToJsonElement(loginAs(user).bodyAsText()).jsonObject["stage"]!!.jsonPrimitive.content)

        // break-glass: env-driven reset wipes this user's TOTP
        Bootstrap.run(bootstrapConfig(user).copy(resetTotpEmail = user))

        // next login forces a fresh enrollment with a new secret
        val second = enroll(user)
        assertNotEquals(first.secret, second.secret)

        // old backup codes died with the reset; new ones work
        assertEquals(HttpStatusCode.Unauthorized, submitTotp(pendingFor(user), first.backupCodes[0]).status)
        assertEquals(HttpStatusCode.OK, submitTotp(pendingFor(user), second.backupCodes[0]).status)
    }
}
