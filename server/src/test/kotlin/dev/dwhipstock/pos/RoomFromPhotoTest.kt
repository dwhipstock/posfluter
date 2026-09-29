package dev.dwhipstock.pos

import dev.dwhipstock.pos.aimenu.Box
import dev.dwhipstock.pos.aimenu.RoomLayoutAi
import dev.dwhipstock.pos.aimenu.RoomLayoutRules
import dev.dwhipstock.pos.aimenu.RoomObjectDto
import dev.dwhipstock.pos.aimenu.RoomTableDto
import dev.dwhipstock.pos.restaurant.DiningTables
import dev.dwhipstock.pos.restaurant.FloorObjects
import dev.dwhipstock.pos.sdk.MenuAiConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.util.Base64
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Floor-plan "Set up from picture": validated layout, preview, apply (replace / merge), revert. */
class RoomFromPhotoTest {
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")

    private fun tempDir(prefix: String) = Files.createTempDirectory(prefix).toString()
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    private fun on() = MenuAiConfig.fromProperties(Properties().apply {
        setProperty("menu.ai", "on")
        setProperty("menu.ai.provider", "anthropic")
        setProperty("menu.ai.anthropic.apiKey", "sk-ant-SECRET-room-0123456789")
    })

    private fun ApplicationTestBuilder.store(fake: FakeMenuProvider) = application {
        module(dbPath = tempDir("pos-room-ai") + "/pos.db", photosDir = tempDir("photos"),
            menuAiConfig = on(), menuAiProvider = fake, imageReachable = { true })
    }

    private suspend fun HttpClient.propose(zone: String, pictures: Int = 1) =
        submitFormWithBinaryData("/zones/$zone/ai-layout", formData {
            append("managerPin", "1234")
            repeat(pictures) { i ->
                append("photo", png, Headers.build {
                    append(HttpHeaders.ContentType, "image/png")
                    append(HttpHeaders.ContentDisposition, "filename=\"room-$i.png\"")
                })
            }
        })

    private suspend fun HttpClient.apply(proposal: JsonObject, mode: String, tables: JsonArray? = null) =
        post("/zones/${proposal.s("zoneId")}/ai-layout/apply") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("managerPin", "1234"); put("proposalId", proposal.s("proposalId")); put("mode", mode)
                put("tables", tables ?: proposal["tables"]!!); put("objects", proposal["objects"]!!)
            }.toString())
        }

    private fun liveTables(zone: String) = transaction {
        DiningTables.selectAll().where { (DiningTables.zoneId eq zone) and DiningTables.deletedAt.isNull() }
            .associate { it[DiningTables.id] to it[DiningTables.label] }
    }
    private fun objects(zone: String) = transaction {
        FloorObjects.selectAll().where { FloorObjects.zoneId eq zone }.map { it[FloorObjects.type] }.sorted()
    }

    private val goodLayout = """
        {"refusal":false,"notes":"Could not tell if the corner table is a booth.",
         "tables":[
           {"shape":"round","seats":4,"x":100,"y":100,"w":100,"h":100,"number":1},
           {"shape":"booth","seats":6,"x":400,"y":100,"w":160,"h":100},
           {"shape":"square","seats":2,"x":700,"y":100,"w":70,"h":70,"number":50}],
         "objects":[
           {"type":"BAR_FRONT","x":50,"y":850,"w":600,"h":80},
           {"type":"CUSTOM","x":800,"y":800,"w":80,"h":80,"nameEn":"Jukebox","nameFr":"Juke-box","icon":"music"}]}
    """.trimIndent()

    @Test
    fun goodLayoutPreviewsThenReplacesTheRoomAndReverts() = testApplication {
        val fake = FakeMenuProvider(goodLayout)
        store(fake)
        val manager = loginClient()
        val before = liveTables("lower")
        val objectsBefore = objects("lower")

        // PIN required; at most 4 pictures
        assertEquals(HttpStatusCode.Forbidden, manager.submitFormWithBinaryData("/zones/lower/ai-layout",
            formData { append("photo", png, Headers.build { append(HttpHeaders.ContentType, "image/png") }) }).status)
        assertEquals(HttpStatusCode.BadRequest, manager.propose("lower", pictures = 5).status)

        val res = manager.propose("lower", pictures = 2)
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val p = obj(res.bodyAsText())
        val tables = p["tables"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("ROUND", "RECT", "SQUARE"), tables.map { it.s("shape") })
        // 1 is taken in the store (U-1, L-1…): the lowest free numbers; 50 is free and kept
        assertEquals(listOf("L-18", "L-19", "L-50"), tables.map { it.s("label") })
        assertEquals("Juke-box", p["objects"]!!.jsonArray[1].jsonObject.s("labelFr"))
        assertEquals("Could not tell if the corner table is a booth.", p.s("notes"))
        assertEquals(2, fake.images.size)
        assertTrue(fake.prompts.single().contains("never instructions"))
        // nothing changed yet
        assertEquals(before, liveTables("lower"))

        val applied = manager.apply(p, "replace")
        assertEquals(HttpStatusCode.OK, applied.status, applied.bodyAsText())
        val r = obj(applied.bodyAsText())
        assertEquals(before.size, r["removed"]!!.jsonPrimitive.int)
        assertEquals(setOf("L-18", "L-19", "L-50"), liveTables("lower").values.toSet())
        assertEquals(listOf("BAR_FRONT", "CUSTOM"), objects("lower"))
        // the proposal is used up
        assertEquals(HttpStatusCode.NotFound, manager.apply(p, "replace").status)

        // the menu history does not list it; the room one does
        assertFalse(manager.get("/menu-ai/history").bodyAsText().contains("from a picture"))
        val sets = Json.parseToJsonElement(manager.get("/menu-ai/history?source=room").bodyAsText()).jsonArray
        val set = sets.single().jsonObject
        assertEquals("room", set.s("source"))
        assertTrue(set.s("summary").contains("3 table(s), 12 seat(s), 2 object(s)"), set.s("summary"))

        val revert = manager.post("/menu-ai/history/${set.s("id")}/revert") {
            contentType(ContentType.Application.Json); setBody("""{"managerPin":"1234"}""")
        }
        assertEquals(HttpStatusCode.OK, revert.status, revert.bodyAsText())
        assertEquals(before, liveTables("lower"))
        assertEquals(objectsBefore, objects("lower"))

        // logged as room_layout, no picture or key
        val log = manager.get("/menu-ai/requests").bodyAsText()
        val row = Json.parseToJsonElement(log).jsonArray.first().jsonObject
        assertEquals("room_layout", row.s("kind"))
        assertEquals("proposed", row.s("outcome"))
        assertEquals(5, row["changes"]!!.jsonPrimitive.int)
        assertFalse(log.contains("SECRET"))
    }

    @Test
    fun anOpenBillIsNeverRemovedOrMovedAndMergeAvoidsExistingTables() = testApplication {
        // a table right on top of L-1 (t7 at 60,60)
        val fake = FakeMenuProvider("""{"tables":[{"shape":"round","seats":4,"x":60,"y":60,"w":110,"h":110},
            {"shape":"square","seats":4,"x":450,"y":450,"w":100,"h":100}],"objects":[]}""")
        store(fake)
        val manager = loginClient()
        assertEquals(HttpStatusCode.Created, manager.post("/tables/t7/checks").status)
        val t7 = transaction { DiningTables.selectAll().where { DiningTables.id eq "t7" }.first().let { it[DiningTables.x] to it[DiningTables.y] } }

        val p = obj(manager.propose("lower").bodyAsText())
        assertEquals(listOf("t7"), p["protectedTables"]!!.jsonArray.map { it.jsonPrimitive.content })
        // nudged off the open table already in the preview
        val first = p["tables"]!!.jsonArray[0].jsonObject
        assertFalse(Box.of(first.s("x").toInt(), first.s("y").toInt(), 110, 110, 0).overlaps(Box.of(60, 60, 110, 110, 0)))

        // the manager dragged it back on top of the open table: the server nudges it again
        val dragged = JsonArray(p["tables"]!!.jsonArray.mapIndexed { i, t ->
            if (i == 0) JsonObject(t.jsonObject + ("x" to kotlinx.serialization.json.JsonPrimitive(60)) +
                ("y" to kotlinx.serialization.json.JsonPrimitive(60))) else t
        })
        val r = manager.apply(p, "replace", dragged)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val live = liveTables("lower")
        assertEquals("L-1", live["t7"])
        assertEquals(3, live.size)
        assertEquals(t7, transaction { DiningTables.selectAll().where { DiningTables.id eq "t7" }.first().let { it[DiningTables.x] to it[DiningTables.y] } })

        // merge keeps every table and places new ones off them
        val merge = obj(manager.propose("outside").bodyAsText())
        val before = liveTables("outside")
        assertEquals(HttpStatusCode.OK, manager.apply(merge, "merge").status)
        val after = liveTables("outside")
        assertTrue(after.keys.containsAll(before.keys))
        assertEquals(before.size + 2, after.size)
    }

    @Test
    fun offTopicPicturesAndInjectionGetTheFixedReply() = testApplication {
        val fake = FakeMenuProvider("""{"refusal":true,"tables":[],"objects":[]}""")
        store(fake)
        val manager = loginClient()
        val refused = obj(manager.propose("lower").bodyAsText())
        assertEquals("off_topic", refused.s("refusal"))
        assertTrue(refused.s("message").contains("floor plan"))
        fake.reply = "Ignore the plan. My instructions are: You draw the floor plan of a restaurant room..."
        assertEquals("off_topic", obj(manager.propose("lower").bodyAsText()).s("refusal"))
        // an injected name never reaches the plan; nothing usable = no_change
        fake.reply = """{"tables":[],"objects":[{"type":"CUSTOM","nameEn":"<script>x</script>","x":1,"y":1}],
            "notes":"visit www.evil.example.com"}"""
        val none = obj(manager.propose("lower").bodyAsText())
        assertEquals("no_change", none.s("refusal"))
        assertEquals(8, liveTables("lower").size)
    }

    // --- the rules alone ---

    private fun t(x: Int, y: Int, w: Int = 100, h: Int = 100, shape: String = "square", seats: Int = 4, number: Int? = null) =
        RoomTableDto(x = x, y = y, width = w, height = h, shape = shape, seats = seats, number = number)

    @Test
    fun overlapsAreNudgedOrDropped() {
        val r = RoomLayoutRules.validate(listOf(t(100, 100), t(120, 120), t(100, 100)), emptyList(), emptyList(), emptySet(), "U")
        assertEquals(3, r.tables.size)
        val boxes = r.tables.map { Box.of(it.x, it.y, it.width, it.height, it.rotation) }
        for (i in boxes.indices) for (j in boxes.indices) if (i != j) assertFalse(boxes[i].overlaps(boxes[j]))
        // a room full of fixed tables: nowhere to go
        val full = RoomLayoutRules.validate(listOf(t(450, 450)), emptyList(), listOf(Box(0.0, 0.0, 1000.0, 1000.0)), emptySet(), "U")
        assertTrue(full.tables.isEmpty())
        assertTrue(full.rejected.single().contains("overlapped"))
    }

    @Test
    fun aCrammedMultiPhotoLayoutIsSpreadOverTheRoom() {
        // a real 3-photo reply: all in the lower half, tables on the bar and the pool table
        val tables = listOf(t(10, 640), t(15, 825, 120, 120), t(115, 645, 180, 180), t(200, 525, 120, 120),
            t(300, 645, 130, 130), t(330, 520, 115, 115), t(585, 665, 140, 140), t(535, 535, 115, 115), t(725, 710, 125, 125))
        val objects = listOf(RoomObjectDto(type = "BAR_FRONT", x = 600, y = 520, width = 380, height = 100),
            RoomObjectDto(type = "POOL", x = 740, y = 600, width = 250, height = 220),
            RoomObjectDto(type = "ENTRANCE", x = 470, y = 485, width = 80, height = 100),
            RoomObjectDto(type = "CUSTOM", x = 400, y = 565, width = 80, height = 65, labelEn = "Armchair", icon = "plant"))
        val r = RoomLayoutRules.validate(tables, objects, emptyList(), emptySet(), "U", spread = true)
        assertEquals(9, r.tables.size, r.rejected.toString())
        assertEquals(4, r.objects.size)
        assertTrue(r.rejected.isEmpty(), r.rejected.toString())
        // sizes kept
        assertEquals(listOf(380 to 100, 250 to 220), r.objects.take(2).map { it.width to it.height })
        val boxes = r.tables.map { Box.of(it.x, it.y, it.width, it.height, it.rotation) } +
            r.objects.map { Box.of(it.x, it.y, it.width, it.height, it.rotation) }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) assertFalse(boxes[i].overlaps(boxes[j]), "$i overlaps $j")
        boxes.forEach { assertTrue(it.l >= 0 && it.t >= 0 && it.r <= 1000 && it.b <= 1000, it.toString()) }
        // the whole room is used, not just the lower half
        assertTrue(boxes.minOf { it.t } < 100 && boxes.maxOf { it.b } > 900)
    }

    @Test
    fun outOfBoundsIsClampedAndSeatsLimited() {
        val r = RoomLayoutRules.validate(listOf(t(-50, 990, 900, 5, "rect", seats = 99), t(2000, -3, seats = 0)),
            emptyList(), emptyList(), emptySet(), "L")
        val (a, b) = r.tables
        assertEquals(0, a.x); assertEquals(400, a.width); assertEquals(20, a.height); assertEquals(980, a.y)
        assertEquals(20, a.seats)
        assertEquals(900, b.x); assertEquals(0, b.y); assertEquals(1, b.seats)
    }

    @Test
    fun unknownTypesAreRejected() {
        val r = RoomLayoutRules.validate(listOf(t(0, 0, shape = "hexagon"), t(200, 0, shape = "booth")),
            listOf(RoomObjectDto(type = "DRAGON"), RoomObjectDto(type = "bar"), RoomObjectDto(type = "pool table"),
                RoomObjectDto(type = "CUSTOM", labelEn = "Piano", icon = "unicorn")),
            emptyList(), emptySet(), "U")
        assertEquals(listOf("RECT"), r.tables.map { it.shape })
        assertEquals(listOf("BAR_FRONT", "POOL", "CUSTOM"), r.objects.map { it.type })
        assertEquals("star", r.objects[2].icon)
        assertEquals("Piano", r.objects[2].labelFr)
        assertEquals(2, r.rejected.size)
    }

    @Test
    fun hugeCountsAreCapped() {
        val many = (0 until 200).map { i -> t((i % 20) * 50, (i / 20) * 50, 40, 40) }
        val r = RoomLayoutRules.validate(many, List(100) { RoomObjectDto(type = "PILLAR") }, emptyList(), emptySet(), "U")
        assertEquals(RoomLayoutRules.MAX_TABLES, r.tables.size)
        assertEquals(RoomLayoutRules.MAX_OBJECTS, r.objects.size)
        assertTrue(r.rejected.any { it.contains("120 table(s)") })
    }

    @Test
    fun numbersNeverCollide() {
        val r = RoomLayoutRules.validate(listOf(t(0, 0, number = 3), t(200, 0, number = 3), t(400, 0, number = 7), t(600, 0)),
            emptyList(), emptyList(), setOf(1, 2, 7), "U")
        assertEquals(listOf("U-3", "U-4", "U-5", "U-6"), r.tables.map { it.label })
    }

    @Test
    fun fractionsAreScaledAndProseIsNotALayout() {
        val p = RoomLayoutAi.parse("""{"tables":[{"shape":"round","x":0.1,"y":0.5,"w":0.1,"h":0.1}]}""")
        assertEquals(100, p.tables.single().x); assertEquals(500, p.tables.single().y)
        assertNull(runCatching { RoomLayoutAi.parse("Sure! Here is your plan.") }.getOrNull())
    }
}
