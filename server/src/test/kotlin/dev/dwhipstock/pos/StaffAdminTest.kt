package dev.dwhipstock.pos

import dev.dwhipstock.pos.db.SyncOutbox
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One-way sync: staff are owned by the tablet. A manager adds, edits,
 * deactivates and deletes staff offline; every change lands in the outbox as a
 * full snapshot (without the PIN hash) for the portal to display.
 */
class StaffAdminTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-staff-admin").resolve("pos.db").toString()

    private suspend fun HttpClient.send(method: HttpMethod, path: String, body: String? = null) =
        request(path) {
            this.method = method
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }

    private fun outboxOf(type: String) = transaction {
        SyncOutbox.selectAll().where { SyncOutbox.eventType eq type }.map { it[SyncOutbox.payload] }
    }

    @Test
    fun managerManagesStaffOfflineAndEveryChangeIsPushedUp() = testApplication {
        application { module(dbPath = tempDb()) }
        val mgr = loginClient("1234")

        val created = mgr.send(HttpMethod.Post, "/staff/manage", """{"name":"Camille Tremblay","role":"SERVER","pin":"4321"}""")
        assertEquals(HttpStatusCode.Created, created.status)
        val id = json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        assertEquals("camille-tremblay", id)

        // the new PIN logs in at once, with no cloud anywhere
        loginClient("4321")

        // PINs are unique: login resolves the user by PIN
        assertEquals(HttpStatusCode.Conflict,
            mgr.send(HttpMethod.Post, "/staff/manage", """{"name":"Dup","role":"SERVER","pin":"9999"}""").status)

        assertEquals(HttpStatusCode.OK,
            mgr.send(HttpMethod.Patch, "/staff/manage/$id", """{"role":"MANAGER"}""").status)
        assertEquals(HttpStatusCode.OK,
            mgr.send(HttpMethod.Patch, "/staff/manage/$id", """{"active":false}""").status)
        // a deactivated member drops off the login tiles
        val tiles = json.parseToJsonElement(client.get("/staff").bodyAsText()).jsonArray
        assertFalse(tiles.any { it.jsonObject["id"]!!.jsonPrimitive.content == id })

        assertEquals(HttpStatusCode.OK, mgr.send(HttpMethod.Delete, "/staff/manage/$id").status)
        val listed = json.parseToJsonElement(mgr.get("/staff/manage").bodyAsText()).jsonObject
        assertFalse(listed["staff"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == id })

        val pushed = outboxOf("staff.created") + outboxOf("staff.updated") + outboxOf("staff.deleted")
        assertEquals(4, pushed.size)
        assertTrue(pushed.all { "\$2a\$" !in it }, "PIN hashes must never enter the outbox")
        assertTrue(outboxOf("staff.deleted").single().contains("\"deleted\":true"))
    }

    @Test
    fun serversCannotManageStaffAndTheLastManagerIsProtected() = testApplication {
        application { module(dbPath = tempDb()) }
        val server = loginClient("9999")
        assertEquals(HttpStatusCode.Forbidden, server.get("/staff/manage").status)

        val mgr = loginClient("1234")
        val res = mgr.send(HttpMethod.Patch, "/staff/manage/manager", """{"role":"SERVER"}""")
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertEquals("last_manager",
            json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun emptyStoreGetsABootstrapManager() = testApplication {
        application { module(dbPath = tempDb(), seedMode = "none") }
        loginClient("1234") // otherwise nobody could ever sign in
    }
}
