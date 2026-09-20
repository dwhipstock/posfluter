package dev.dwhipstock.pos

import dev.dwhipstock.pos.db.SyncOutbox
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Floor objects: inert structural props (pool / bar front / pillar). */
class FloorObjectsTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    private suspend fun io.ktor.client.HttpClient.postJson(path: String, body: String) =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }
    private suspend fun io.ktor.client.HttpClient.putJson(path: String, body: String) =
        put(path) { contentType(ContentType.Application.Json); setBody(body) }

    private fun zoneObjects(zonesBody: String, zoneId: String): List<JsonObject> =
        json.parseToJsonElement(zonesBody).jsonArray
            .first { it.jsonObject["id"]!!.jsonPrimitive.content == zoneId }
            .jsonObject["objects"]!!.jsonArray.map { it.jsonObject }

    private fun outboxEvents(type: String): List<String> = transaction {
        SyncOutbox.selectAll().where { SyncOutbox.eventType eq type }
            .map { it[SyncOutbox.payload] }
    }

    private fun errCode(body: String): String =
        json.parseToJsonElement(body).jsonObject["code"]!!.jsonPrimitive.content

    @Test
    fun `add is manager-gated, lands on the zone, and validates type`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()

        // no PIN → refused
        assertEquals(HttpStatusCode.Forbidden, c.postJson("/zones/outside/objects",
            """{"type":"POOL","x":100,"y":100,"width":200,"height":120}""").status)

        // bad type → rejected before touching the db
        assertEquals(HttpStatusCode.BadRequest, c.postJson("/zones/outside/objects",
            """{"type":"PLANT","x":100,"y":100,"width":100,"height":100,"managerPin":"1234"}""").status)

        val created = c.postJson("/zones/outside/objects",
            """{"type":"POOL","x":100,"y":100,"width":200,"height":120,"managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Created, created.status)
        val id = json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        assertEquals("outside-pool", id)

        val onPlan = zoneObjects(c.get("/zones").bodyAsText(), "outside").single { it["id"]!!.jsonPrimitive.content == id }
        assertEquals("POOL", onPlan["type"]!!.jsonPrimitive.content)
        assertEquals(200, onPlan["width"]!!.jsonPrimitive.int)
        assertEquals(1, outboxEvents("floor_object.added").size)
    }

    @Test
    fun `objects-layout batch-writes geometry, skips unchanged, refuses foreign zone`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val a = json.parseToJsonElement(c.postJson("/zones/outside/objects",
            """{"type":"PILLAR","x":80,"y":80,"width":80,"height":80,"managerPin":"1234"}""")
            .bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val b = json.parseToJsonElement(c.postJson("/zones/upper/objects",
            """{"type":"BAR_FRONT","x":40,"y":40,"width":320,"height":60,"managerPin":"1234"}""")
            .bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        // move a; resubmit a's own object unchanged would be pointless — move only a
        val res = c.putJson("/zones/outside/objects-layout", """{
            "objects":[{"id":"$a","x":200,"y":220,"width":80,"height":80,"rotation":90}],
            "managerPin":"1234"}""")
        assertEquals(HttpStatusCode.OK, res.status)
        val moved = zoneObjects(c.get("/zones").bodyAsText(), "outside").single()
        assertEquals(200, moved["x"]!!.jsonPrimitive.int)
        assertEquals(90, moved["rotation"]!!.jsonPrimitive.int)
        assertEquals(1, outboxEvents("floor_object.moved").size)

        // re-saving the same geometry emits nothing new
        c.putJson("/zones/outside/objects-layout", """{
            "objects":[{"id":"$a","x":200,"y":220,"width":80,"height":80,"rotation":90}],
            "managerPin":"1234"}""")
        assertEquals(1, outboxEvents("floor_object.moved").size)

        // an object from another zone in the batch → whole save refused
        val wrong = c.putJson("/zones/outside/objects-layout", """{
            "objects":[{"id":"$b","x":10,"y":10,"width":320,"height":60,"rotation":0}],
            "managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Conflict, wrong.status)
        assertEquals("object_not_in_zone", errCode(wrong.bodyAsText()))
    }

    @Test
    fun `delete removes the object outright and emits an event`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val id = json.parseToJsonElement(c.postJson("/zones/outside/objects",
            """{"type":"POOL","x":100,"y":100,"width":200,"height":120,"managerPin":"1234"}""")
            .bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        assertEquals(HttpStatusCode.OK, c.postJson("/objects/$id/delete", """{"managerPin":"1234"}""").status)
        assertTrue(zoneObjects(c.get("/zones").bodyAsText(), "outside").isEmpty())
        assertEquals(1, outboxEvents("floor_object.removed").size)
    }
}
