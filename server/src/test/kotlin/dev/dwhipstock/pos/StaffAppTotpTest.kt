package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.Totp
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Staff-app 2FA (M7): PIN + TOTP with a 90-day trusted device. Drives the real
 * HTTP endpoints; codes are computed with the store's own [Totp] (the same
 * algorithm it verifies with). The Flutter terminal's PIN-only /login is
 * unaffected — covered by AuthTest.
 */
class StaffAppTotpTest {

    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    @Test
    fun enrollThenTrustedDeviceThenReenrollWhenUntrusted() = testApplication {
        application { module(dbPath = tempDb()) }

        // step 1, no device → enrollment (QR + secret)
        val e = post("/staff-app/login", """{"pin":"9999"}""").obj()
        assertEquals("enroll", e["status"]!!.jsonPrimitive.content)
        val secret = e["secret"]!!.jsonPrimitive.content
        assertTrue(e["qrDataUri"]!!.jsonPrimitive.content.startsWith("data:image/png;base64,"))
        assertTrue(e["otpauthUri"]!!.jsonPrimitive.content.startsWith("otpauth://totp/"))

        // step 2, correct code → a session + a device-trust token
        val v = post("/staff-app/totp", """{"pin":"9999","code":"${Totp.code(secret)}"}""")
        assertEquals(HttpStatusCode.OK, v.status)
        val vo = v.obj()
        val deviceToken = vo["deviceToken"]!!.jsonPrimitive.content
        assertTrue(deviceToken.isNotEmpty())
        assertTrue(vo["user"]!!.jsonObject["token"]!!.jsonPrimitive.content.isNotEmpty())

        // step 1 WITH the device token → straight in (PIN only)
        val trusted = post("/staff-app/login", """{"pin":"9999","deviceToken":"$deviceToken"}""").obj()
        assertEquals("ok", trusted["status"]!!.jsonPrimitive.content)
        assertTrue(trusted["user"]!!.jsonObject.containsKey("token"))

        // step 1, no device, already enrolled → the code is required again
        assertEquals("totp",
            post("/staff-app/login", """{"pin":"9999"}""").obj()["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun wrongCodeAndBadPinAreDistinct() = testApplication {
        application { module(dbPath = tempDb()) }
        post("/staff-app/login", """{"pin":"9999"}""") // create the pending secret

        val bad = post("/staff-app/totp", """{"pin":"9999","code":"000000"}""")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("invalid_totp", bad.obj()["code"]!!.jsonPrimitive.content)

        val badPin = post("/staff-app/login", """{"pin":"0000"}""")
        assertEquals(HttpStatusCode.Unauthorized, badPin.status)
        assertEquals("invalid_pin", badPin.obj()["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun deviceTrustIsBoundToTheStaffMember() = testApplication {
        application { module(dbPath = tempDb()) }
        // server1 enrolls and earns a device token
        val secret = post("/staff-app/login", """{"pin":"9999"}""").obj()["secret"]!!.jsonPrimitive.content
        val dev = post("/staff-app/totp", """{"pin":"9999","code":"${Totp.code(secret)}"}""")
            .obj()["deviceToken"]!!.jsonPrimitive.content
        // the manager presenting server1's token is NOT auto-trusted (token is bound to server1)
        assertNotEquals("ok",
            post("/staff-app/login", """{"pin":"1234","deviceToken":"$dev"}""").obj()["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun managerResetForcesReenrollment() = testApplication {
        application { module(dbPath = tempDb()) }
        val secret = post("/staff-app/login", """{"pin":"9999"}""").obj()["secret"]!!.jsonPrimitive.content
        val dev = post("/staff-app/totp", """{"pin":"9999","code":"${Totp.code(secret)}"}""")
            .obj()["deviceToken"]!!.jsonPrimitive.content
        assertEquals("ok",
            post("/staff-app/login", """{"pin":"9999","deviceToken":"$dev"}""").obj()["status"]!!.jsonPrimitive.content)

        // reset needs a session (401 without one)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/staff/server1/totp/reset") {
            contentType(ContentType.Application.Json); setBody("{}")
        }.status)
        // a server (no manage_staff, no manager PIN) is refused
        val srv = loginClient(pin = "9999")
        assertEquals(HttpStatusCode.Forbidden, srv.post("/staff/server1/totp/reset") {
            contentType(ContentType.Application.Json); setBody("{}")
        }.status)
        // a manager can reset
        val mgr = loginClient(pin = "1234")
        assertEquals(HttpStatusCode.OK, mgr.post("/staff/server1/totp/reset") {
            contentType(ContentType.Application.Json); setBody("{}")
        }.status)

        // the old device token is dead and the secret is gone → enroll again
        assertEquals("enroll",
            post("/staff-app/login", """{"pin":"9999","deviceToken":"$dev"}""").obj()["status"]!!.jsonPrimitive.content)
    }

    /** Regression: a valid-PIN /staff-app/login (step 1) must NOT clear the shared
     *  brute-force counter, or someone who knows the PIN could reset the TOTP lockout
     *  between guesses and brute-force the second factor. */
    @Test
    fun interleavedLoginDoesNotResetTheTotpLockout() = testApplication {
        application { module(dbPath = tempDb()) }
        // enroll + activate server1 so begin() returns "totp" (untrusted device)
        val secret = post("/staff-app/login", """{"pin":"9999"}""").obj()["secret"]!!.jsonPrimitive.content
        post("/staff-app/totp", """{"pin":"9999","code":"${Totp.code(secret)}"}""")

        // 5 wrong codes, each preceded by a correct-PIN begin() that must not clear the counter
        repeat(5) {
            assertEquals("totp",
                post("/staff-app/login", """{"pin":"9999"}""").obj()["status"]!!.jsonPrimitive.content)
            post("/staff-app/totp", """{"pin":"9999","code":"000000"}""")
        }
        // the terminal is locked despite all those successful-PIN begins in between
        assertEquals(HttpStatusCode.TooManyRequests, post("/staff-app/login", """{"pin":"9999"}""").status)
    }

    /** A TOTP code is single-use within its 30s window (no replay). */
    @Test
    fun aCodeCannotBeReplayedWithinItsWindow() = testApplication {
        application { module(dbPath = tempDb()) }
        val secret = post("/staff-app/login", """{"pin":"9999"}""").obj()["secret"]!!.jsonPrimitive.content
        val code = Totp.code(secret)
        assertEquals(HttpStatusCode.OK, post("/staff-app/totp", """{"pin":"9999","code":"$code"}""").status)
        val replay = post("/staff-app/totp", """{"pin":"9999","code":"$code"}""")
        assertEquals(HttpStatusCode.BadRequest, replay.status)
        assertEquals("invalid_totp", replay.obj()["code"]!!.jsonPrimitive.content)
    }

    private suspend fun ApplicationTestBuilder.post(path: String, body: String) =
        client.post(path) { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpResponse.obj() = Json.parseToJsonElement(bodyAsText()).jsonObject
}
