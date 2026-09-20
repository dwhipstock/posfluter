package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.Permissions
import dev.dwhipstock.pos.base.StaffGrants
import dev.dwhipstock.pos.base.Users
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Offline grant enforcement (CONTRACT §7): the acting user's grant decides
 * whether a gated action goes straight through or falls back to the existing
 * manager-PIN override. Defaults preserve the old posture (MANAGER = all,
 * SERVER = price_override only); a synced per-staff override changes it.
 */
class GrantEnforcementTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-grant-test").resolve("pos.db").toString()

    private suspend fun HttpClient.postJson(path: String, body: String) =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.openCheckId(table: String): Int =
        json.parseToJsonElement(post("/tables/$table/checks").bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.int

    private fun grantsOfLogin(body: String): Set<String> =
        json.parseToJsonElement(body).jsonObject["grants"]!!.jsonArray
            .map { it.jsonPrimitive.content }.toSet()

    @Test
    fun loginReturnsEffectiveGrants() = testApplication {
        application { module(dbPath = tempDb()) }
        val mgr = grantsOfLogin(client.post("/login") {
            contentType(ContentType.Application.Json); setBody("""{"pin":"1234"}""")
        }.bodyAsText())
        assertTrue(Permissions.VOID in mgr && Permissions.MANAGE_STAFF in mgr) // manager gets everything

        val srv = grantsOfLogin(client.post("/login") {
            contentType(ContentType.Application.Json); setBody("""{"pin":"9999"}""")
        }.bodyAsText())
        assertFalse(Permissions.VOID in srv)
        assertTrue(Permissions.PRICE_OVERRIDE in srv) // the one server default
    }

    @Test
    fun managerVoidsWithoutAnyPin() = testApplication {
        application { module(dbPath = tempDb()) }
        val mgr = loginClient("1234")
        val checkId = mgr.openCheckId("t1")
        // the manager holds the void grant → no manager-PIN prompt needed
        assertEquals(HttpStatusCode.OK, mgr.postJson("/checks/$checkId/void", """{"reason":"x"}""").status)
    }

    @Test
    fun serverWithSyncedOverrideVoidsWithoutApproval() = testApplication {
        application { module(dbPath = tempDb()) }
        val server = loginClient("9999") // first request starts the app + connects this test's DB
        // simulate the cloud granting this server the void permission (a sync-applied override);
        // enforcement re-reads grants live per request, so applying it after login is fine
        transaction {
            StaffGrants.insert {
                it[staffId] = "server1"; it[permission] = Permissions.VOID; it[granted] = true
            }
        }
        val checkId = server.openCheckId("t2")
        assertEquals(HttpStatusCode.OK, server.postJson("/checks/$checkId/void", """{"reason":"x"}""").status)
    }

    @Test
    fun serverWithoutGrantFallsBackToManagerOverride() = testApplication {
        application { module(dbPath = tempDb()) }
        val server = loginClient("9999")
        val checkId = server.openCheckId("t3")
        // no approving PIN → refused
        assertEquals(HttpStatusCode.Forbidden,
            server.postJson("/checks/$checkId/void", """{"reason":"x"}""").status)
        // the server's own PIN can't approve (no void grant) → refused
        assertEquals(HttpStatusCode.Forbidden,
            server.postJson("/checks/$checkId/void", """{"reason":"x","managerPin":"9999"}""").status)
        // a manager PIN authorizes it
        assertEquals(HttpStatusCode.OK,
            server.postJson("/checks/$checkId/void", """{"reason":"x","managerPin":"1234"}""").status)
    }

    @Test
    fun deactivatedStaffTokenStopsResolvingAtOnce() = testApplication {
        application { module(dbPath = tempDb()) }
        val server = loginClient("9999")
        assertEquals(HttpStatusCode.OK, server.get("/me").status)
        // the cloud deactivates this staff member (as CloudSync.applyStaff would)
        transaction { Users.update({ Users.id eq "server1" }) { it[active] = false } }
        // the still-held token no longer resolves — a fired employee is locked out immediately,
        // not only when the 12h/idle window lapses
        assertEquals(HttpStatusCode.Unauthorized, server.get("/me").status)
    }
}
