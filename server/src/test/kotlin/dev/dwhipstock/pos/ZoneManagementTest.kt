package dev.dwhipstock.pos

import dev.dwhipstock.pos.db.SyncOutbox
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Zone (room) CRUD: add / rename / delete (guarded) / reorder, manager-gated. */
class ZoneManagementTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    private suspend fun io.ktor.client.HttpClient.postJson(path: String, body: String) =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }
    private suspend fun io.ktor.client.HttpClient.patchJson(path: String, body: String) =
        patch(path) { contentType(ContentType.Application.Json); setBody(body) }

    private fun zones(body: String): List<JsonObject> =
        json.parseToJsonElement(body).jsonArray.map { it.jsonObject }

    private fun zone(body: String, id: String): JsonObject? =
        zones(body).firstOrNull { it["id"]!!.jsonPrimitive.content == id }

    private fun outboxEvents(type: String): List<String> = transaction {
        SyncOutbox.selectAll().where { SyncOutbox.eventType eq type }
            .map { it[SyncOutbox.payload] }
    }

    private fun errCode(body: String): String =
        json.parseToJsonElement(body).jsonObject["code"]!!.jsonPrimitive.content

    @Test
    fun `create is manager-gated, validates names, lands empty at the end of the order`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()

        // no PIN → refused
        assertEquals(HttpStatusCode.Forbidden,
            c.postJson("/zones", """{"nameFr":"balcon","nameEn":"Patio"}""").status)

        // blank name → rejected before touching the db
        assertEquals(HttpStatusCode.BadRequest,
            c.postJson("/zones", """{"nameFr":"","nameEn":"Patio","managerPin":"1234"}""").status)

        val created = c.postJson("/zones", """{"nameFr":"balcon","nameEn":"Patio","managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Created, created.status)
        val body = json.parseToJsonElement(created.bodyAsText()).jsonObject
        assertEquals("patio", body["id"]!!.jsonPrimitive.content)
        assertEquals("OPEN", body["status"]!!.jsonPrimitive.content)
        assertTrue(body["tables"]!!.jsonArray.isEmpty())

        // it shows up on /zones, empty, after the two seeded rooms
        val all = zones(c.get("/zones").bodyAsText())
        assertEquals("patio", all.last()["id"]!!.jsonPrimitive.content)
        assertTrue(zone(c.get("/zones").bodyAsText(), "patio")!!["tables"]!!.jsonArray.isEmpty())
        assertEquals(1, outboxEvents("zone.created").size)
    }

    @Test
    fun `duplicate name gets a distinct id`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val a = c.postJson("/zones", """{"nameFr":"balcon","nameEn":"Patio","managerPin":"1234"}""")
        val b = c.postJson("/zones", """{"nameFr":"balcon 2","nameEn":"Patio","managerPin":"1234"}""")
        assertEquals("patio", json.parseToJsonElement(a.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("patio-2", json.parseToJsonElement(b.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `rename updates the names and emits an event`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val res = c.patchJson("/zones/outside", """{"nameFr":"cour","nameEn":"Courtyard","managerPin":"1234"}""")
        assertEquals(HttpStatusCode.OK, res.status)
        val updated = zone(c.get("/zones").bodyAsText(), "outside")!!
        assertEquals("Courtyard", updated["nameEn"]!!.jsonPrimitive.content)
        assertEquals("cour", updated["nameFr"]!!.jsonPrimitive.content)
        assertEquals(1, outboxEvents("zone.renamed").size)

        // no PIN → refused, names unchanged
        assertEquals(HttpStatusCode.Forbidden,
            c.patchJson("/zones/outside", """{"nameEn":"Nope"}""").status)
    }

    @Test
    fun `delete is refused while the room holds tables or objects, allowed once empty`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()

        // seeded rooms hold tables → refused
        val busy = c.postJson("/zones/upper/delete", """{"managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Conflict, busy.status)
        assertEquals("zone_not_empty", errCode(busy.bodyAsText()))

        // a fresh empty room deletes cleanly
        c.postJson("/zones", """{"nameFr":"balcon","nameEn":"Patio","managerPin":"1234"}""")
        val gone = c.postJson("/zones/patio/delete", """{"managerPin":"1234"}""")
        assertEquals(HttpStatusCode.OK, gone.status)
        assertNull(zone(c.get("/zones").bodyAsText(), "patio"))
        assertEquals(1, outboxEvents("zone.deleted").size)

        // a floor object alone also blocks the delete
        c.postJson("/zones", """{"nameFr":"bar","nameEn":"Bar","managerPin":"1234"}""")
        c.postJson("/zones/bar/objects",
            """{"type":"BAR_FRONT","x":40,"y":40,"width":320,"height":60,"managerPin":"1234"}""")
        val hasObject = c.postJson("/zones/bar/delete", """{"managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Conflict, hasObject.status)
        assertEquals("zone_not_empty", errCode(hasObject.bodyAsText()))
    }

    @Test
    fun `reorder rewrites sort_order so the room switcher follows`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        // seed order is upper, outside; flip it
        val res = c.patchJson("/zones/order", """{"orderedIds":["outside","upper"],"managerPin":"1234"}""")
        assertEquals(HttpStatusCode.OK, res.status)
        val all = zones(c.get("/zones").bodyAsText())
        assertEquals("outside", all.first()["id"]!!.jsonPrimitive.content)
        assertEquals("upper", all[1]["id"]!!.jsonPrimitive.content)
        assertEquals(1, outboxEvents("zones.reordered").size)

        // unknown id → 404, no partial reorder committed
        val bad = c.patchJson("/zones/order", """{"orderedIds":["outside","ghost"],"managerPin":"1234"}""")
        assertEquals(HttpStatusCode.NotFound, bad.status)
    }

    @Test
    fun `reorder without a manager PIN is refused`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        assertEquals(HttpStatusCode.Forbidden,
            c.patchJson("/zones/order", """{"orderedIds":["outside","upper"]}""").status)
        // seed order untouched
        assertEquals("upper", zones(c.get("/zones").bodyAsText()).first()["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `zones order route wins over the rename param route`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        // PATCH /zones/order must hit reorder, not rename a zone literally named "order"
        val res = c.patchJson("/zones/order", """{"orderedIds":["upper","outside"],"managerPin":"1234"}""")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("true", json.parseToJsonElement(res.bodyAsText()).jsonObject["ok"]!!.jsonPrimitive.content)
        // no zone named "order" was created
        assertNull(zone(c.get("/zones").bodyAsText(), "order"))
    }
}
