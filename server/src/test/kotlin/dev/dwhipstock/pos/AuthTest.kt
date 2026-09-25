package dev.dwhipstock.pos

import io.ktor.client.plugins.DefaultRequest
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

class AuthTest {

    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    @Test
    fun staffApiRequiresLoginButCustomerRoutesStayOpen() = testApplication {
        application { module(dbPath = tempDb()) }

        // gated without a token
        assertEquals(HttpStatusCode.Unauthorized, client.get("/zones").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/tables/t5/checks").status)

        // customer-facing stays open
        assertEquals(HttpStatusCode.OK, client.get("/items").status)
        assertEquals(HttpStatusCode.OK, client.get(customerPath("t5")).status)
        // the table QR carries the table's link, so it is staff-only now
        assertEquals(HttpStatusCode.Unauthorized, client.get("/tables/t5/qr").status)

        // wrong PIN
        assertEquals(HttpStatusCode.Unauthorized, client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"0000"}""")
        }.status)

        // server-role login works and can ring items, but cannot approve a void
        val server = loginClient(pin = "9999")
        assertEquals(HttpStatusCode.OK, server.get("/zones").status)
        val me = Json.parseToJsonElement(server.get("/me").bodyAsText()).jsonObject
        assertEquals("SERVER", me["role"]!!.jsonPrimitive.content)

        // logout revokes the session
        assertEquals(HttpStatusCode.OK, server.post("/logout").status)
        assertEquals(HttpStatusCode.Unauthorized, server.get("/zones").status)
    }

    /** Modal pre-flight: manager PIN verifies inline; wrong/role PIN → 403; needs a session. */
    @Test
    fun verifyManagerPinPreflight() = testApplication {
        application { module(dbPath = tempDb()) }

        assertEquals(HttpStatusCode.Unauthorized, client.post("/auth/verify-manager-pin") {
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"1234"}""")
        }.status)

        val c = loginClient(pin = "9999") // server-role session asks a manager to approve
        val ok = c.post("/auth/verify-manager-pin") {
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"1234"}""")
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("manager",
            Json.parseToJsonElement(ok.bodyAsText()).jsonObject["approverId"]!!.jsonPrimitive.content)

        // the server's own (non-manager) PIN doesn't approve
        assertEquals(HttpStatusCode.Forbidden, c.post("/auth/verify-manager-pin") {
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"9999"}""")
        }.status)
    }

    /** Concurrent sessions: one staff member can be signed in on two surfaces at
     *  once — both tokens resolve, and logging out of one leaves the other live. */
    @Test
    fun concurrentSessionsPerUserCoexist() = testApplication {
        application { module(dbPath = tempDb()) }

        // the same user signs in twice (e.g. POS terminal + staff app)
        val a = loginClient(pin = "9999")
        val b = loginClient(pin = "9999")

        // both sessions resolve — logging into one no longer evicts the other
        assertEquals(HttpStatusCode.OK, a.get("/me").status)
        assertEquals(HttpStatusCode.OK, b.get("/me").status)

        // logout is per-token: dropping one leaves the other live
        assertEquals(HttpStatusCode.OK, a.post("/logout").status)
        assertEquals(HttpStatusCode.Unauthorized, a.get("/me").status)
        assertEquals(HttpStatusCode.OK, b.get("/me").status)
    }

    /** The live-session count is bounded: past the per-user cap (10) the oldest
     *  session is evicted, while every session inside the cap keeps working. */
    @Test
    fun oldestSessionEvictedPastCap() = testApplication {
        application { module(dbPath = tempDb()) }

        suspend fun freshToken(): String {
            val res = client.post("/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"pin":"9999"}""")
            }
            check(res.status == HttpStatusCode.OK) { "login failed: ${res.status}" }
            return Json.parseToJsonElement(res.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
        }
        fun bearer(token: String) = createClient {
            install(DefaultRequest) { header(HttpHeaders.Authorization, "Bearer $token") }
        }

        // 11 logins with a cap of 10 → exactly the oldest session is evicted
        val tokens = (1..11).map { freshToken() }
        assertEquals(HttpStatusCode.Unauthorized, bearer(tokens.first()).get("/me").status)
        assertEquals(HttpStatusCode.OK, bearer(tokens[1]).get("/me").status)
        assertEquals(HttpStatusCode.OK, bearer(tokens.last()).get("/me").status)
    }
}
