package dev.dwhipstock.pos

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RED-TEAM (security & auth): the PIN rate limiter ([LoginRateLimiter]) is a
 * single, terminal-WIDE, unauthenticated lock. Any client that can reach the
 * open POST /login — on the LAN that is every guest phone on the venue Wi-Fi —
 * can freeze every staff sign-in, including the manager's, for the full 15-minute
 * lockout with five junk PINs. Nothing identifies the attacker, so the owner's
 * own tablet is locked out by someone else's guesses. At a live demo this is a
 * one-liner that takes the whole POS offline mid-service.
 *
 * These tests assert the SECURE behaviour (a legitimate login still succeeds
 * after an unrelated client's bad guesses), so they FAIL on today's code.
 */
class RedteamLoginLockoutTest {

    private fun tempDb() = Files.createTempDirectory("pos-redteam").resolve("pos.db").toString()

    private suspend fun ApplicationTestBuilder.login(pin: String): HttpStatusCode =
        client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"$pin"}""")
        }.status

    /** Five wrong PINs from an attacker must not lock out the real manager. */
    @Test
    fun badPinsDoNotLockOutEveryoneElse() = testApplication {
        application { module(dbPath = tempDb()) }

        // attacker flood: five wrong PINs on the open /login route
        repeat(5) { assertEquals(HttpStatusCode.Unauthorized, login("0000")) }

        // the manager (correct PIN 1234) must still be able to sign in.
        // Today: 429 rate_limited — the whole POS is frozen for 15 minutes.
        assertEquals(
            HttpStatusCode.OK, login("1234"),
            "a correct manager PIN must still log in despite another client's bad guesses",
        )
    }

    /** The same terminal-wide lock also freezes inline manager approvals
     *  (void/refund/cash-movement), so a flood halts mid-service overrides too. */
    @Test
    fun badPinsDoNotFreezeManagerApprovals() = testApplication {
        application { module(dbPath = tempDb()) }

        val server = loginClient(pin = "9999") // a normal server session, before the flood

        repeat(5) { assertEquals(HttpStatusCode.Unauthorized, login("0000")) }

        // the manager approves a void inline with the correct PIN; must not be frozen
        val res = server.post("/auth/verify-manager-pin") {
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"1234"}""")
        }
        assertEquals(
            HttpStatusCode.OK, res.status,
            "a correct manager approval PIN must still work despite another client's bad guesses",
        )
    }
}
