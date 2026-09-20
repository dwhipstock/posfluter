package dev.dwhipstock.pos

import dev.dwhipstock.pos.db.SyncOutbox
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
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

class FloorPlanTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    private suspend fun io.ktor.client.HttpClient.postJson(path: String, body: String) =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }
    private suspend fun io.ktor.client.HttpClient.patchJson(path: String, body: String) =
        patch(path) { contentType(ContentType.Application.Json); setBody(body) }
    private suspend fun io.ktor.client.HttpClient.putJson(path: String, body: String) =
        put(path) { contentType(ContentType.Application.Json); setBody(body) }

    private fun zoneTables(zonesBody: String, zoneId: String): List<JsonObject> =
        json.parseToJsonElement(zonesBody).jsonArray
            .first { it.jsonObject["id"]!!.jsonPrimitive.content == zoneId }
            .jsonObject["tables"]!!.jsonArray.map { it.jsonObject }

    private fun table(zonesBody: String, zoneId: String, tableId: String): JsonObject? =
        zoneTables(zonesBody, zoneId)
            .firstOrNull { it["id"]!!.jsonPrimitive.content == tableId }

    private fun outboxEvents(type: String): List<String> = transaction {
        SyncOutbox.selectAll().where { SyncOutbox.eventType eq type }
            .map { it[SyncOutbox.payload] }
    }

    private fun errCode(body: String): String =
        json.parseToJsonElement(body).jsonObject["code"]!!.jsonPrimitive.content

    @Test
    fun `existing tables are auto-laid into a non-overlapping grid with geometry`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val zones = json.parseToJsonElement(c.get("/zones").bodyAsText()).jsonArray
        assertTrue(zones.isNotEmpty())
        for (zone in zones) {
            val tables = zone.jsonObject["tables"]!!.jsonArray.map { it.jsonObject }
            val positions = mutableSetOf<Pair<Int, Int>>()
            for (t in tables) {
                val x = t["x"]!!.jsonPrimitive.int
                val y = t["y"]!!.jsonPrimitive.int
                assertTrue(x in 0..1000 && y in 0..1000, "coords in canvas: $t")
                assertTrue(t["width"]!!.jsonPrimitive.int >= 20)
                assertEquals("SQUARE", t["shape"]!!.jsonPrimitive.content)
                assertEquals(4, t["seats"]!!.jsonPrimitive.int)
                assertTrue(positions.add(x to y),
                    "tables overlap at ($x,$y) in zone ${zone.jsonObject["id"]}")
            }
        }
        // the seeded upper zone (10 tables, 5-per-row grid) spans two rows
        val upperYs = zoneTables(c.get("/zones").bodyAsText(), "upper")
            .map { it["y"]!!.jsonPrimitive.int }.toSet()
        assertEquals(setOf(60, 220), upperYs)
    }

    @Test
    fun `geometry patch is manager-gated and emits per-concern outbox events`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()

        // no PIN → refused
        assertEquals(HttpStatusCode.Forbidden,
            c.patchJson("/tables/t3/geometry", """{"x":500,"y":300}""").status)

        // move
        assertEquals(HttpStatusCode.OK,
            c.patchJson("/tables/t3/geometry", """{"x":500,"y":300,"managerPin":"1234"}""").status)
        val moved = table(c.get("/zones").bodyAsText(), "outside", "t3")!!
        assertEquals(500, moved["x"]!!.jsonPrimitive.int)
        assertEquals(300, moved["y"]!!.jsonPrimitive.int)
        assertEquals(1, outboxEvents("table.moved").size)
        assertTrue(outboxEvents("table.resized").isEmpty(), "move alone must not emit resize")

        // resize + reshape in one patch → both events, no second move
        assertEquals(HttpStatusCode.OK,
            c.patchJson("/tables/t3/geometry",
                """{"width":200,"height":80,"shape":"BAR","rotation":90,"seats":8,"managerPin":"1234"}""").status)
        assertEquals(1, outboxEvents("table.moved").size)
        assertEquals(1, outboxEvents("table.resized").size)
        assertEquals(1, outboxEvents("table.reshaped").size)

        // validation: off-canvas / unknown shape refused
        assertEquals(HttpStatusCode.BadRequest,
            c.patchJson("/tables/t3/geometry", """{"x":2000,"managerPin":"1234"}""").status)
        assertEquals(HttpStatusCode.BadRequest,
            c.patchJson("/tables/t3/geometry", """{"shape":"BLOB","managerPin":"1234"}""").status)
    }

    @Test
    fun `save layout batch-writes a zone and skips unchanged tables`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val before = table(c.get("/zones").bodyAsText(), "outside", "t4")!!

        // t3 moves; t4 is resubmitted unchanged → only t3 events
        val res = c.putJson("/zones/outside/layout", """{
            "tables":[
              {"id":"t3","x":111,"y":222,"width":100,"height":100,"rotation":0,"shape":"SQUARE","seats":4},
              {"id":"t4","x":${before["x"]!!.jsonPrimitive.int},"y":${before["y"]!!.jsonPrimitive.int},
               "width":100,"height":100,"rotation":0,"shape":"SQUARE","seats":4}
            ],"managerPin":"1234"}""")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(111, table(c.get("/zones").bodyAsText(), "outside", "t3")!!["x"]!!.jsonPrimitive.int)
        assertEquals(1, outboxEvents("table.moved").size)

        // a table from another zone in the batch → whole save refused
        val wrongZone = c.putJson("/zones/outside/layout", """{
            "tables":[{"id":"t1","x":10,"y":10,"width":100,"height":100,"rotation":0,"shape":"SQUARE","seats":4}],
            "managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Conflict, wrongZone.status)
        assertEquals("table_not_in_zone", errCode(wrongZone.bodyAsText()))
    }

    @Test
    fun `add table creates a zone-prefixed id and auto-bumps a taken number`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val created = c.postJson("/zones/outside/tables",
            """{"label":"O-3","x":400,"y":400,"shape":"ROUND","seats":6,"managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Created, created.status)
        val id = json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        assertEquals("outside-o-3", id)

        val onPlan = table(c.get("/zones").bodyAsText(), "outside", id)!!
        assertEquals("ROUND", onPlan["shape"]!!.jsonPrimitive.content)
        assertEquals(6, onPlan["seats"]!!.jsonPrimitive.int)
        assertEquals(1, outboxEvents("table.added").size)

        // a check opens on it like any other table
        assertEquals(HttpStatusCode.Created, c.post("/tables/$id/checks").status)

        // re-requesting a taken number auto-bumps to the next free one (no clash error)
        val second = c.postJson("/zones/outside/tables",
            """{"label":"O-3","x":100,"y":100,"managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Created, second.status)
        assertEquals("O-4",
            json.parseToJsonElement(second.bodyAsText()).jsonObject["label"]!!.jsonPrimitive.content)
    }

    @Test
    fun `delete honors open-check and sub-table guards then soft-deletes everywhere`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()

        // open check on t3 → delete refused, table_in_use
        c.post("/tables/t3/checks")
        c.postJson("/checks/1/lines", """{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":1}""")
        val inUse = c.postJson("/tables/t3/delete", """{"managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Conflict, inUse.status)
        assertEquals("table_in_use", errCode(inUse.bodyAsText()))

        // t4 gets a sub-table (label auto-assigned) → delete refused until the sub-table goes first
        val sub = c.postJson("/zones/outside/tables",
            """{"x":300,"y":300,"parentTableId":"t4","managerPin":"1234"}""")
        val subId = json.parseToJsonElement(sub.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val hasSub = c.postJson("/tables/t4/delete", """{"managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Conflict, hasSub.status)
        assertEquals("has_sub_tables", errCode(hasSub.bodyAsText()))
        assertEquals(HttpStatusCode.OK,
            c.postJson("/tables/$subId/delete", """{"managerPin":"1234"}""").status)
        assertEquals(HttpStatusCode.OK,
            c.postJson("/tables/t4/delete", """{"managerPin":"1234"}""").status)

        // gone from the floor plan, the customer page, and new-check opening
        assertNull(table(c.get("/zones").bodyAsText(), "outside", "t4"))
        assertEquals(HttpStatusCode.NotFound, c.get("/m/t4").status)
        assertEquals(HttpStatusCode.NotFound, c.get("/tables/t4/qr").status)
        assertEquals(HttpStatusCode.NotFound, c.post("/tables/t4/checks").status)
        assertTrue(outboxEvents("table.removed").any { it.contains("\"t4\"") })
    }

    @Test
    fun `rename updates label and VIP override with empty-string clear`() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()

        assertEquals(HttpStatusCode.OK, c.patchJson("/tables/t3",
            """{"label":"O-9","nameOverride":"Frère Nueng","managerPin":"1234"}""").status)
        var t3 = table(c.get("/zones").bodyAsText(), "outside", "t3")!!
        assertEquals("O-9", t3["label"]!!.jsonPrimitive.content)
        assertEquals("Frère Nueng", t3["nameOverride"]!!.jsonPrimitive.content)
        assertEquals(1, outboxEvents("table.renamed").size)

        // empty string clears the VIP name; label untouched
        assertEquals(HttpStatusCode.OK, c.patchJson("/tables/t3",
            """{"nameOverride":"","managerPin":"1234"}""").status)
        t3 = table(c.get("/zones").bodyAsText(), "outside", "t3")!!
        assertEquals("O-9", t3["label"]!!.jsonPrimitive.content)
        assertTrue(t3["nameOverride"] == null || t3["nameOverride"]!!.jsonPrimitive.contentOrNull == null)

        // renaming onto a live sibling's number (O-2 = t4) auto-bumps to a free one (O-1), not a clash
        val bumped = c.patchJson("/tables/t3", """{"label":"O-2","managerPin":"1234"}""")
        assertEquals(HttpStatusCode.OK, bumped.status)
        assertEquals("O-1",
            json.parseToJsonElement(bumped.bodyAsText()).jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals(3, outboxEvents("table.renamed").size) // set + clear + bump

        // no-op rename (same label) emits nothing new
        c.patchJson("/tables/t3", """{"label":"O-1","managerPin":"1234"}""")
        assertEquals(3, outboxEvents("table.renamed").size)
    }
}
