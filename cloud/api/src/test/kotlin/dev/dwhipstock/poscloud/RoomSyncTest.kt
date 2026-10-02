package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.FloorThings
import dev.dwhipstock.poscloud.db.MenuFeed
import dev.dwhipstock.poscloud.db.PortalUsers
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two-way room sync, the cloud's side (CONTRACT §11): the store's
 * `floor.snapshot` lands in the cloud's copy (merged per field, last write
 * wins, two "before sync" stamps take the store's value), the locked list
 * marks tables with an open bill, a losing store write is corrected through
 * the feed, a store pulling with `rooms=1` makes its floor editable, and the
 * Rooms list is tenant- and store-scoped.
 */
class RoomSyncTest {
    private val key = "store-key-rooms"
    private val key2 = "store-key-rooms-b"
    private lateinit var owner: String
    private lateinit var viewer: String
    private var seq = 0L

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("copperlantern")
        seedTenant("copperlantern", "plateau", "Plateau")
        seedTenant("other-tenant", "elsewhere", "Elsewhere")
        seedStoreKey("copperlantern", "vieux-port", key)
        seedStoreKey("copperlantern", "plateau", key2)
        owner = seedSession("copperlantern", seedUser("copperlantern", "owner@test.dev", "pw-owner-1"))
        val v = seedUser("copperlantern", "viewer@test.dev", "pw-viewer-1")
        transaction { PortalUsers.update({ PortalUsers.id eq v }) { it[role] = "viewer" } }
        viewer = seedSession("copperlantern", v)
        seq = 0
    }

    private fun stamp(deltaMs: Long = 0, node: String = "sabc") = "%013d-0000-%s".format(System.currentTimeMillis() + deltaMs, node)

    private fun room(id: String, name: String, clock: String = "", sort: Int = 0) = buildJsonObject {
        put("id", id); put("nameFr", name); put("nameEn", name); put("sortOrder", sort); put("labelPrefix", name.take(1)); put("deleted", false)
        put("names", buildJsonObject { put("es", "$name ES") })
        put("clock", buildJsonObject { listOf("nameFr", "nameEn", "sortOrder", "labelPrefix", "deleted", "names.es").forEach { put(it, clock) } })
    }

    private fun table(id: String, zone: String, label: String, x: Int, clock: String = "", deleted: Boolean = false, parent: String? = null) = buildJsonObject {
        put("id", id); put("zoneId", zone); put("label", label); put("parentTableId", parent?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        put("x", x); put("y", 100); put("width", 100); put("height", 100); put("rotation", 0); put("shape", "ROUND"); put("seats", 4); put("deleted", deleted)
        put("clock", buildJsonObject { listOf("zoneId", "label", "parentTableId", "x", "y", "width", "height", "rotation", "shape", "seats", "deleted").forEach { put(it, clock) } })
    }

    private fun obj(id: String, zone: String, type: String, clock: String = "") = buildJsonObject {
        put("id", id); put("zoneId", zone); put("type", type); put("x", 800); put("y", 20); put("width", 150); put("height", 60); put("rotation", 0)
        put("labelFr", kotlinx.serialization.json.JsonNull); put("labelEn", kotlinx.serialization.json.JsonNull)
        put("icon", kotlinx.serialization.json.JsonNull); put("shape", kotlinx.serialization.json.JsonNull); put("deleted", false); put("names", buildJsonObject {})
        put("clock", buildJsonObject { listOf("zoneId", "type", "x", "y", "width", "height", "rotation", "labelFr", "labelEn", "icon", "shape", "deleted").forEach { put(it, clock) } })
    }

    private suspend fun ApplicationTestBuilder.floor(
        storeKey: String = key, rooms: List<JsonObject> = emptyList(), tables: List<JsonObject> = emptyList(),
        objects: List<JsonObject> = emptyList(), locked: List<String> = emptyList(),
    ) = ingest(storeKey, event("floor.snapshot", buildJsonObject {
        put("rooms", JsonArray(rooms)); put("tables", JsonArray(tables)); put("objects", JsonArray(objects))
        put("locked", buildJsonArray { locked.forEach { add(JsonPrimitive(it)) } })
    }, seq = ++seq, aggregateType = "floor", aggregateId = "rooms"))

    private suspend fun ApplicationTestBuilder.pull(storeKey: String = key, rooms: Boolean = true): JsonObject =
        testJson.parseToJsonElement(client.get("/v1/store/menu/changes?since=0" + if (rooms) "&rooms=1" else "") {
            header(HttpHeaders.Authorization, "Bearer $storeKey")
        }.bodyAsText()).jsonObject

    private suspend fun ApplicationTestBuilder.rooms(venue: String? = "vieux-port", session: String = owner): HttpResponse =
        getWithCookie("/v1/rooms" + (venue?.let { "?venue=$it" } ?: ""), session)

    private suspend fun HttpResponse.body(): JsonObject = testJson.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    private fun thing(entity: String, id: String) = transaction {
        FloorThings.selectAll().where { (FloorThings.venueId eq "vieux-port") and (FloorThings.entity eq entity) and (FloorThings.id eq id) }.single()
    }

    @Test
    fun theStoresFloorLandsAndTheRoomsListShowsIt() = testApplication {
        application { module(TestSupport.config) }
        floor(rooms = listOf(room("upper", "Dining Room"), room("patio", "Patio", sort = 1)),
            tables = listOf(table("t1", "upper", "D-1", 100), table("t2", "upper", "D-2", 300, parent = "t1"),
                table("t3", "upper", "D-3", 500, deleted = true)),
            objects = listOf(obj("upper-bar", "upper", "BAR_FRONT")), locked = listOf("t1"))
        val r = rooms()
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val body = r.body()
        assertEquals("false", body.s("editable"), "a store that never pulled with rooms=1 can't take portal room edits")
        val list = body["rooms"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("upper", "patio"), list.map { it.s("id") })
        val upper = list.first()
        assertEquals("Dining Room ES", upper["names"]!!.jsonObject.s("es"))
        val tables = upper["tables"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("D-1", "D-2"), tables.map { it.s("label") }, "a deleted table is not drawn")
        assertEquals("true", tables[0].s("locked"))
        assertEquals("false", tables[1].s("locked"))
        assertEquals("t1", tables[1].s("parentTableId"))
        assertEquals("1", tables[0].s("number"))
        assertEquals("BAR_FRONT", upper["objects"]!!.jsonArray.single().jsonObject.s("type"))

        // the store pulls saying it applies room changes: editable now
        pull()
        assertEquals("true", rooms().body().s("editable"))
        // the bill is closed: the next snapshot's locked list frees the table
        floor(locked = emptyList())
        assertEquals("false", rooms().body()["rooms"]!!.jsonArray[0].jsonObject["tables"]!!.jsonArray[0].jsonObject.s("locked"))
    }

    @Test
    fun scopedToTheTenantAndOneStore() = testApplication {
        application { module(TestSupport.config) }
        floor(rooms = listOf(room("upper", "Dining Room")))
        floor(storeKey = key2, rooms = listOf(room("terrace", "Terrace")))
        assertEquals(listOf("terrace"), rooms("plateau").body()["rooms"]!!.jsonArray.map { it.jsonObject.s("id") })
        assertEquals(HttpStatusCode.NotFound, rooms("elsewhere").status)
        assertEquals("venue_required", rooms(null).body().s("code"))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/rooms?venue=vieux-port").status)
        // a viewer may look, not change
        val v = rooms(session = viewer).body()
        assertEquals("false", v.s("canEdit"))
        assertEquals(1, v["rooms"]!!.jsonArray.size)
    }

    @Test
    fun theLaterWriteWinsPerFieldAndALosingStoreWriteIsCorrected() = testApplication {
        application { module(TestSupport.config) }
        floor(rooms = listOf(room("upper", "Dining Room")), tables = listOf(table("t1", "upper", "D-1", 100)))
        pull()
        // a newer store edit lands
        val s1 = stamp()
        floor(tables = listOf(table("t1", "upper", "D-1", 250, clock = s1)))
        assertEquals("250", thing("table", "t1")[FloorThings.values].let { testJson.parseToJsonElement(it).jsonObject.s("x") })
        // an older one (a store clock set back) loses, and the store is told the winner
        val feedBefore = transaction { MenuFeed.selectAll().count() }
        floor(tables = listOf(table("t1", "upper", "D-1", 999, clock = stamp(-60_000))))
        assertEquals("250", testJson.parseToJsonElement(thing("table", "t1")[FloorThings.values]).jsonObject.s("x"))
        val correction = transaction { MenuFeed.selectAll().where { MenuFeed.origin eq "correction" }.toList() }
        assertEquals(feedBefore + 1, transaction { MenuFeed.selectAll().count() })
        assertEquals("table", correction.single()[MenuFeed.entity])
        val pulled = pull()["changes"]!!.jsonArray.map { it.jsonObject }.last()
        assertEquals("table", pulled.s("entity"))
        assertEquals("250", pulled["data"]!!.jsonObject.s("x"))
        // a far-future stamp (a broken clock) is re-stamped by the cloud and the store told so
        floor(tables = listOf(table("t1", "upper", "D-1", 260, clock = stamp(3_600_000))))
        val restamp = transaction { MenuFeed.selectAll().where { MenuFeed.origin eq "restamp" }.single() }
        assertTrue(testJson.parseToJsonElement(restamp[MenuFeed.data]).jsonObject.s("restamp") == "true")
    }

    @Test
    fun aDeleteIsATombstoneAndALaterEditBringsItBack() = testApplication {
        application { module(TestSupport.config) }
        val s1 = stamp(-10_000)
        floor(rooms = listOf(room("upper", "Dining Room")), tables = listOf(table("t1", "upper", "D-1", 100, clock = s1)))
        floor(tables = listOf(table("t1", "upper", "D-1", 100, clock = s1, deleted = true).let { t ->
            JsonObject(t + ("clock" to JsonObject((t["clock"] as JsonObject) + ("deleted" to JsonPrimitive(stamp(-5_000))))))
        }))
        assertTrue(thing("table", "t1")[FloorThings.deleted])
        assertEquals(0, rooms().body()["rooms"]!!.jsonArray[0].jsonObject["tables"]!!.jsonArray.size)
        // a later edit of the deleted table (newer than its delete) revives it
        floor(tables = listOf(table("t1", "upper", "D-1", 400, clock = stamp()).let { t ->
            JsonObject(t + ("deleted" to JsonPrimitive(true)) + ("clock" to JsonObject((t["clock"] as JsonObject) + ("deleted" to JsonPrimitive(stamp(-5_000))))))
        }))
        assertFalse(thing("table", "t1")[FloorThings.deleted])
    }
}
