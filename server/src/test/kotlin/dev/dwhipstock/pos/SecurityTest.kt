package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.LoginRateLimiter
import dev.dwhipstock.pos.base.RateLimitException
import dev.dwhipstock.pos.base.Sessions
import dev.dwhipstock.pos.sdk.VenueClock
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SecurityTest {

    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    @Test
    fun pinsOnDiskAreBcryptHashes() = testApplication {
        val db = tempDb()
        application { module(dbPath = db) }
        loginClient() // force app start
        val pins = mutableListOf<String>()
        java.sql.DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().executeQuery("SELECT pin FROM users").use { rs ->
                while (rs.next()) pins += rs.getString(1)
            }
        }
        assertTrue(pins.isNotEmpty())
        pins.forEach { assertTrue(it.startsWith("\$2"), "plaintext PIN on disk: $it") }
    }

    @Test
    fun loginLocksAfterFiveFailures() = testApplication {
        application { module(dbPath = tempDb()) }
        suspend fun tryPin(pin: String) = client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"$pin"}""")
        }
        repeat(5) { assertEquals(HttpStatusCode.Unauthorized, tryPin("0000").status) }
        // locked now — even the correct PIN gets 429 + Retry-After
        val locked = tryPin("1234")
        assertEquals(HttpStatusCode.TooManyRequests, locked.status)
        assertTrue((locked.headers[HttpHeaders.RetryAfter]?.toLong() ?: 0) > 0)
        assertTrue("rate_limited" in locked.bodyAsText())
    }

    @Test
    fun rateLimiterWindowAndLockoutTiming() {
        var now = Instant.parse("2026-07-08T00:00:00Z")
        val limiter = LoginRateLimiter(clock = { now })
        repeat(4) { limiter.recordFailure() }
        limiter.checkNotLocked() // 4 failures: still fine
        limiter.recordFailure() // 5th → locked
        assertFailsWith<RateLimitException> { limiter.checkNotLocked() }
        now = now.plus(Duration.ofMinutes(14))
        assertFailsWith<RateLimitException> { limiter.checkNotLocked() }
        now = now.plus(Duration.ofMinutes(2)) // past the 15min lockout
        limiter.checkNotLocked()
        // old failures don't linger after the lock clears
        limiter.recordFailure()
        limiter.checkNotLocked()
    }

    @Test
    fun sessionExpiryAbsoluteAndSliding() = testApplication {
        application { module(dbPath = tempDb()) }
        val manager = loginClient()
        assertEquals(HttpStatusCode.OK, manager.get("/me").status)

        // simulate 31 idle minutes → sliding expiry → 401
        transaction { Sessions.update { it[lastUsedAt] = VenueClock.now().minusMinutes(31) } }
        assertEquals(HttpStatusCode.Unauthorized, manager.get("/me").status)

        // fresh login, then simulate an ancient session → absolute expiry
        val manager2 = loginClient()
        transaction {
            Sessions.update({ Sessions.revokedAt.isNull() }) {
                it[expiresAt] = VenueClock.now().minusMinutes(1)
            }
        }
        assertEquals(HttpStatusCode.Unauthorized, manager2.get("/me").status)
    }

    @Test
    fun concurrentLoginsCoexistAndPinChangeWorks() = testApplication {
        application { module(dbPath = tempDb()) }
        val first = loginClient(pin = "9999")
        val second = loginClient(pin = "9999")
        // a second login no longer kicks the first — both surfaces stay live
        assertEquals(HttpStatusCode.OK, first.get("/me").status, "first session must stay live")
        assertEquals(HttpStatusCode.OK, second.get("/me").status)

        // change own PIN: wrong current rejected, then a real change + re-login
        assertEquals(HttpStatusCode.BadRequest, second.patch("/me/pin") {
            contentType(ContentType.Application.Json)
            setBody("""{"currentPin":"0000","newPin":"5678"}""")
        }.status)
        assertEquals(HttpStatusCode.OK, second.patch("/me/pin") {
            contentType(ContentType.Application.Json)
            setBody("""{"currentPin":"9999","newPin":"5678"}""")
        }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"9999"}""")
        }.status)
        loginClient(pin = "5678") // succeeds
        val sessions = transaction { Sessions.selectAll().count() }
        assertTrue(sessions >= 3)
    }
}
