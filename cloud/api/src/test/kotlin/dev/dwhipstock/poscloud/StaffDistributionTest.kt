package dev.dwhipstock.poscloud

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Staff + grants: portal CRUD lands a full snapshot on the store change feed
 * (CONTRACT §7), PINs are BCrypt-hashed (never plaintext), and the last-manager
 * guard refuses lockout.
 */
class StaffDistributionTest {

    private val key = "store-key-staff"
    private lateinit var session: String

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("copperlantern")
        seedStoreKey("copperlantern", "main", key)
        session = seedSession("copperlantern", seedUser("copperlantern", "staff@test.dev", "password-s"))
    }

    private suspend fun ApplicationTestBuilder.feed(since: Long): JsonObject {
        val res = client.get("/v1/store/catalog/changes?since=$since") {
            header(HttpHeaders.Authorization, "Bearer $key")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        return testJson.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    private fun cookie() = "pos_portal_session=$session"

    @Test
    fun createStaffFeedsHashedPinSnapshotToStore() = testApplication {
        application { module(TestSupport.config) }

        val created = client.post("/v1/staff") {
            header(HttpHeaders.Cookie, cookie())
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Somchai","role":"SERVER","pin":"4321"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val body = testJson.parseToJsonElement(created.bodyAsText()).jsonObject
        val staffId = body["id"]!!.jsonPrimitive.content
        assertFalse(body.containsKey("pinHash"), "staff DTO must never expose the PIN hash")

        // the change feed carries a staff snapshot with a BCrypt hash, never the plaintext
        // (Bootstrap already seeded manager/server1, so match by the created id)
        val page = feed(0)
        val staffChange = page["changes"]!!.jsonArray.map { it.jsonObject }
            .first { it["kind"]!!.jsonPrimitive.content == "staff" &&
                it["data"]!!.jsonObject["id"]!!.jsonPrimitive.content == staffId }
        val data = staffChange["data"]!!.jsonObject
        assertEquals("upsert", staffChange["op"]!!.jsonPrimitive.content)
        assertEquals("SERVER", data["role"]!!.jsonPrimitive.content)
        val pinHash = data["pinHash"]!!.jsonPrimitive.content
        assertTrue(pinHash.startsWith("\$2"), "PIN must be distributed as a BCrypt hash")
        assertFalse(pinHash.contains("4321"), "plaintext PIN must never appear")
    }

    @Test
    fun perStaffOverrideAndRoleMatrixFlowToFeed() = testApplication {
        application { module(TestSupport.config) }

        val created = client.post("/v1/staff") {
            header(HttpHeaders.Cookie, cookie())
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Nok","role":"SERVER","pin":"1111"}""")
        }
        val staffId = testJson.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        // grant refund to this one server via an override
        val grants = client.patch("/v1/staff/$staffId/grants") {
            header(HttpHeaders.Cookie, cookie())
            contentType(ContentType.Application.Json)
            setBody("""{"overrides":{"refund":true}}""")
        }
        assertEquals(HttpStatusCode.OK, grants.status)
        val gBody = testJson.parseToJsonElement(grants.bodyAsText()).jsonObject
        assertEquals(true, gBody["overrides"]!!.jsonObject["refund"]!!.jsonPrimitive.content.toBoolean())

        // role matrix edit distributes a role_grants change
        val roles = client.put("/v1/roles/grants") {
            header(HttpHeaders.Cookie, cookie())
            contentType(ContentType.Application.Json)
            setBody("""{"roles":{"SERVER":{"cash_movement":true}}}""")
        }
        assertEquals(HttpStatusCode.OK, roles.status)

        val changes = feed(0)["changes"]!!.jsonArray.map { it.jsonObject }
        // this server's staff snapshot carries the refund override
        val staffData = changes.last {
            it["kind"]!!.jsonPrimitive.content == "staff" &&
                it["data"]!!.jsonObject["id"]!!.jsonPrimitive.content == staffId
        }["data"]!!.jsonObject
        assertEquals(true,
            staffData["overrides"]!!.jsonObject["refund"]!!.jsonPrimitive.content.toBoolean())
        // the latest role_grants snapshot reflects the SERVER cash_movement toggle
        val roleData = changes.last { it["kind"]!!.jsonPrimitive.content == "role_grants" }["data"]!!.jsonObject
        assertEquals(true, roleData["roles"]!!.jsonObject["SERVER"]!!
            .jsonObject["cash_movement"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun lastManagerGuardBlocksLockout() = testApplication {
        application { module(TestSupport.config) }
        // Bootstrap seeded exactly one manager ("manager"); deactivating it would
        // leave no active staff with manage_staff → 409 last_manager.
        val res = client.patch("/v1/staff/manager") {
            header(HttpHeaders.Cookie, cookie())
            contentType(ContentType.Application.Json)
            setBody("""{"active":false}""")
        }
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertEquals("last_manager",
            testJson.parseToJsonElement(res.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
    }
}
