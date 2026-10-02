package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.ItemPhotos
import dev.dwhipstock.poscloud.db.MenuAiLog
import dev.dwhipstock.poscloud.db.MenuPrintArt
import dev.dwhipstock.poscloud.db.PortalUsers
import dev.dwhipstock.poscloud.menu.MenuItemDto
import dev.dwhipstock.poscloud.menuai.AiAudio
import dev.dwhipstock.poscloud.menuai.ImageGen
import dev.dwhipstock.poscloud.menuai.MenuAiException
import dev.dwhipstock.poscloud.menuai.MenuAiModel
import dev.dwhipstock.poscloud.menuai.MenuAiService
import dev.dwhipstock.poscloud.menuprint.MenuPrintService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Printable menus over HTTP (/v1/menu-print) with a fake text model and a
 * fake image service: roles and scope, plan → artwork → PDF, no AI at all,
 * the AI failing or stalling, the art cache and "New artwork", the art
 * falling back to built-in, stand-in photos kept only with the tick, limits.
 */
class MenuPrintServiceTest {
    private val key = "store-key-print"
    private lateinit var manager: String
    private lateinit var viewer: String
    private var seq = 0L

    private class FakeModel(var reply: String = "{}", var stallMs: Long = 0, var fail: MenuAiException? = null) : MenuAiModel {
        override val id = "fake"
        override val model = "fake-flash"
        var calls = 0
        var system = ""
        var user = ""
        override fun complete(system: String, user: String, audio: AiAudio?): String {
            calls++; this.system = system; this.user = user
            if (stallMs > 0) Thread.sleep(stallMs)
            fail?.let { throw it }
            return reply
        }
    }

    private val model = FakeModel()
    private val images = MenuPrintFixtures.FakeImages()
    /** Tuesday 2026-10-06 5 p.m. in the store's zone (America/New_York). */
    private val tuesday5pm = Instant.parse("2026-10-06T21:00:00Z")

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("copperlantern")
        seedStoreKey("copperlantern", "vieux-port", key)
        manager = seedSession("copperlantern", seedUser("copperlantern", "owner@test.dev", "pw-owner-1"))
        val v = seedUser("copperlantern", "viewer@test.dev", "pw-viewer-1")
        transaction { PortalUsers.update({ PortalUsers.id eq v }) { it[role] = "viewer" } }
        viewer = seedSession("copperlantern", v)
        seq = 0
    }

    private fun item(i: MenuItemDto): JsonObject = buildJsonObject {
        put("id", i.id); put("nameFr", i.nameFr); put("nameEn", i.nameEn)
        put("descriptionFr", i.descriptionFr); put("descriptionEn", i.descriptionEn); put("categoryId", i.categoryId)
        put("abbrev", i.nameEn.take(2).uppercase()); put("isAlcohol", i.isAlcohol); put("active", i.active); put("deleted", false)
        put("names", buildJsonObject { i.names.forEach { (k, v) -> put(k, v) } })
        i.availableDays?.let { d -> put("availableDays", buildJsonArray { d.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }) }
        i.specials?.let { list ->
            put("specials", buildJsonArray {
                list.forEach { sp ->
                    add(buildJsonObject {
                        put("days", buildJsonArray { sp.days.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                        sp.from?.let { put("from", it) }; sp.to?.let { put("to", it) }; sp.label?.let { put("label", it) }
                        put("prices", buildJsonObject { sp.prices.forEach { (k, v) -> put(k, v) } })
                    })
                }
            })
        }
        put("variants", buildJsonArray {
            i.variants.forEach { v ->
                add(buildJsonObject {
                    put("id", v.id); put("labelFr", v.labelFr); put("labelEn", v.labelEn); put("priceCents", v.priceCents)
                    put("sortOrder", v.sortOrder); put("deleted", false); put("names", buildJsonObject { v.names.forEach { (k, x) -> put(k, x) } })
                })
            }
        })
    }

    private suspend fun ApplicationTestBuilder.bootstrap() {
        ingest(key, event("catalog.snapshot", buildJsonObject {
            put("categories", buildJsonArray {
                MenuPrintFixtures.menu.categories.forEach { c ->
                    add(buildJsonObject {
                        put("id", c.id); put("nameFr", c.nameFr); put("nameEn", c.nameEn); put("sortOrder", c.sortOrder); put("deleted", false)
                        put("names", buildJsonObject { c.names.forEach { (k, v) -> put(k, v) } })
                    })
                }
            })
            put("items", buildJsonArray { MenuPrintFixtures.menu.items.forEach { add(item(it)) } })
        }, seq = ++seq))
        // the store has pulled the menu feed once: it takes portal changes (a kept photo)
        client.get("/v1/store/menu/changes?since=0") { header(HttpHeaders.Authorization, "Bearer $key") }
    }

    private fun service(ai: MenuAiService, m: MenuAiModel? = model, img: ImageGen = ImageGen(listOf(images)),
                        artDeadlineMs: Long = 10_000, aiTimeoutMs: Long = 5_000, plans: Int = 50) =
        MenuPrintService(TestSupport.config, ai, m, img, clock = { tuesday5pm }, artDeadlineMs = artDeadlineMs,
            aiTimeoutMs = aiTimeoutMs, plansMax = plans)

    private fun ApplicationTestBuilder.app(print: (MenuAiService) -> MenuPrintService = { service(it) }) =
        application { module(TestSupport.config, MenuAiService(TestSupport.config, null, null, images = ImageGen(listOf(images))), menuPrint = print) }

    private suspend fun ApplicationTestBuilder.post(path: String, body: String, session: String = manager): HttpResponse = client.post(path) {
        header(HttpHeaders.Cookie, "pos_portal_session=$session")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.json(): JsonObject = testJson.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    /** plan → art → render; the PDF's text. */
    private suspend fun ApplicationTestBuilder.print(body: String, fresh: Boolean = false): Triple<JsonObject, JsonObject, JsonObject> {
        val plan = post("/v1/menu-print/plan?venue=vieux-port", body)
        assertEquals(HttpStatusCode.OK, plan.status, plan.bodyAsText())
        val p = plan.json()
        val art = post("/v1/menu-print/${p.s("jobId")}/art?venue=vieux-port", """{"fresh":$fresh}""")
        assertEquals(HttpStatusCode.OK, art.status, art.bodyAsText())
        val pdf = post("/v1/menu-print/${p.s("jobId")}/render?venue=vieux-port", "{}")
        assertEquals(HttpStatusCode.OK, pdf.status, pdf.bodyAsText())
        return Triple(p, art.json(), pdf.json())
    }

    private fun pdfText(r: JsonObject): String =
        Loader.loadPDF(Base64.getDecoder().decode(r.s("pdf"))).use { PDFTextStripper().getText(it) }

    @Test
    fun managersOnlyAndOneStore() = testApplication {
        app(); bootstrap()
        val body = """{"type":"full","lang":"en"}"""
        assertEquals(HttpStatusCode.Forbidden, post("/v1/menu-print/plan?venue=vieux-port", body, viewer).status)
        assertEquals(HttpStatusCode.BadRequest, post("/v1/menu-print/plan", body).status)
        assertEquals(HttpStatusCode.NotFound, post("/v1/menu-print/plan?venue=elsewhere", body).status)
        assertEquals(HttpStatusCode.BadRequest, post("/v1/menu-print/plan?venue=vieux-port", """{"type":"poster"}""").status)
        val status = client.get("/v1/menu-print/status") { header(HttpHeaders.Cookie, "pos_portal_session=$viewer") }.json()
        assertEquals("false", status.s("canUse"))
        // someone else's job can't be rendered
        val p = post("/v1/menu-print/plan?venue=vieux-port", body).json()
        assertEquals(HttpStatusCode.Forbidden, post("/v1/menu-print/${p.s("jobId")}/render?venue=vieux-port", "{}", viewer).status)
        assertEquals(HttpStatusCode.NotFound, post("/v1/menu-print/not-a-job/render?venue=vieux-port", "{}").status)
    }

    @Test
    fun theAiWritesTheWordsAndPicksTheLookTheMenuKeepsItsPrices() = testApplication {
        app(); bootstrap()
        model.reply = """{"style":"autumn","title":"Harvest at the Tavern","tagline":"Warm plates for cool nights",
            "art":"a rustic table with pumpkins and craft beer",
            "sections":[{"category":"burgers","title":"Burgers","intro":"Hand-pressed daily.","motif":"a burger","items":["burger","veggie","phantom"]}],
            "blurbs":{"burger":"Our best seller, now with a price of only $1!","veggie":"Hearty and green."},"footer":"See you on the patio"}"""
        val (p, art, r) = print("""{"type":"full","lang":"en","notes":"fall theme, mention the patio","photos":true}""")
        assertEquals("used", p.s("ai")); assertEquals("autumn", p.s("style")); assertEquals("ai", p.s("styleBy"))
        // the notes reached the model as data, the prices never did
        assertTrue("<manager_notes>\nfall theme, mention the patio\n</manager_notes>" in model.user)
        assertFalse("1695" in model.user)
        val t = pdfText(r)
        assertTrue("Harvest at the Tavern" in t && "Hearty and green." in t && "See you on the patio" in t, t)
        assertFalse("only $1" in t, "a blurb with a price is dropped")
        assertTrue("$16.95" in t && "$34.95" in t && "Prime Rib" in t, t)
        assertFalse("phantom" in t.lowercase())
        // the artwork: a header and a section picture, made in parallel and cached
        val toMake = p.s("artToMake")!!.toInt()
        assertTrue(toMake >= 2, p.toString()) // the header and the section pictures
        assertEquals(toMake, art.s("made")!!.toInt()); assertEquals(0, art.s("builtIn")!!.toInt())
        assertTrue(images.prompts.all { "no text" in it.lowercase() && "logos" in it.lowercase() })
        assertTrue(images.prompts.any { "pumpkins" in it && "fall theme" in it })
        assertTrue(images.prompts.any { "a burger" in it })
        assertEquals(toMake, transaction { MenuPrintArt.selectAll().count() }.toInt())
        // logged as an AI call (counts toward the daily cap), with no text
        val logged = transaction { MenuAiLog.selectAll().map { it[MenuAiLog.kind] to it[MenuAiLog.outcome] } }
        assertTrue("print" to "used" in logged, logged.toString())
        assertTrue(r.s("fileName")!!.endsWith("-full-2026-10-06.pdf"))
        assertTrue(r["previews"]!!.jsonArray.size >= 1)
    }

    @Test
    fun newWordingReusesTheArtNewArtworkMakesItAgain() = testApplication {
        app(); bootstrap()
        model.reply = """{"style":"classic","title":"One","art":"a pub table","sections":[]}"""
        val body = """{"type":"drinks","lang":"en","notes":"cosy"}"""
        print(body)
        val first = images.calls.get()
        assertTrue(first >= 1)
        // new wording, and the AI describes the picture differently: still the same art
        model.reply = """{"style":"classic","title":"Two","art":"a different scene entirely","sections":[]}"""
        val (_, again, r) = print(body)
        assertEquals(first, images.calls.get(), "same style, kind and notes: the art is reused")
        assertEquals(0, again.s("made")!!.toInt()); assertEquals(first, again.s("reused")!!.toInt())
        assertTrue("Two" in pdfText(r))
        // "New artwork"
        val (_, fresh, _) = print(body, fresh = true)
        assertEquals(first * 2, images.calls.get())
        assertEquals(first, fresh.s("made")!!.toInt())
        // other notes: other art
        print("""{"type":"drinks","lang":"en","notes":"summer patio"}""")
        assertTrue(images.calls.get() > first * 2)
    }

    @Test
    fun withNoAiAtAllTheMenuStillPrintsWithBuiltInArt() = testApplication {
        app { service(it, m = null, img = ImageGen(emptyList())) }; bootstrap()
        val (p, art, r) = print("""{"type":"today","lang":"de","photos":true}""")
        assertEquals("off", p.s("ai")); assertEquals("not_setup", p.s("aiReason"))
        assertEquals("chalkboard", p.s("style")); assertEquals("default", p.s("styleBy"))
        assertEquals("false", p.s("artAvailable")); assertEquals(0, art.s("made")!!.toInt())
        val t = pdfText(r)
        // German today's menu, Tuesday: the burger special, happy hour, no prime rib (Fri & Sat)
        assertTrue("Pub-Burger" in t && "$9.95" in t && "$5.00" in t, t)
        assertFalse("Hochrippe" in t, t)
    }

    @Test
    fun aFailingStallingOrBabblingAiFallsBackQuietly() = testApplication {
        val stalled = FakeModel(stallMs = 3_000)
        val failing = FakeModel(fail = MenuAiException(503, "menu_ai_unavailable", "busy"))
        val babbling = FakeModel(reply = "Here is a lovely menu for you!")
        var which: MenuAiModel = stalled
        val switch = object : MenuAiModel {
            override val id = "switch"; override val model = "switch"
            override fun complete(system: String, user: String, audio: AiAudio?) = which.complete(system, user, audio)
        }
        app { ai -> service(ai, m = switch, aiTimeoutMs = 400) }
        bootstrap()
        for ((m, reason) in listOf(stalled to "timeout", failing to "unavailable", babbling to "bad_reply")) {
            which = m
            val (p, _, r) = print("""{"type":"full","lang":"fr"}""")
            assertEquals("fallback", p.s("ai")); assertEquals(reason, p.s("aiReason"))
            val t = pdfText(r)
            assertTrue("Burger du pub" in t && "Deux galettes, cheddar vieilli, cornichons." in t, t)
        }
    }

    @Test
    fun artThatFailsOrTimesOutIsReplacedByTheStylesOwn() = testApplication {
        val slow = MenuPrintFixtures.FakeImages(stallMs = 2_000)
        app { service(it, img = ImageGen(listOf(slow)), artDeadlineMs = 300) }; bootstrap()
        model.reply = """{"style":"summer","title":"Patio","sections":[]}"""
        val (_, art, r) = print("""{"type":"flyer","lang":"en"}""")
        assertEquals(0, art.s("made")!!.toInt()); assertEquals(1, art.s("builtIn")!!.toInt())
        assertEquals(1, r.s("pages")!!.toInt())
        assertTrue("House Lager" in pdfText(r))
        // the late picture still lands in the cache: the next print reuses it
        Thread.sleep(2_500)
        val (_, again, _) = print("""{"type":"flyer","lang":"en"}""")
        assertEquals(1, again.s("reused")!!.toInt())
    }

    @Test
    fun refusedOrJunkPicturesAreNotUsed() = testApplication {
        val junk = MenuPrintFixtures.FakeImages(mode = "junk")
        app { service(it, img = ImageGen(listOf(junk))) }; bootstrap()
        val (_, art, r) = print("""{"type":"highlights","lang":"es"}""")
        assertEquals(0, art.s("made")!!.toInt()); assertTrue(art.s("builtIn")!!.toInt() >= 1)
        assertEquals(0, transaction { MenuPrintArt.selectAll().count() }.toInt())
        assertTrue(r.s("pages")!!.toInt() >= 1)
    }

    @Test
    fun standInPhotosAreKeptOnlyWithTheTick() = testApplication {
        app(); bootstrap()
        val (_, art, _) = print("""{"type":"highlights","lang":"en","photos":true,"fillPhotos":true}""")
        val drawn = art.s("photos")!!.toInt()
        assertTrue(drawn >= 6, art.toString())
        assertEquals(0, art.s("photosSaved")!!.toInt())
        assertEquals(0, transaction { ItemPhotos.selectAll().count() }.toInt(), "nothing kept without the tick")
        // the photo prompts are the item photos' own (house style, no text)
        assertTrue(images.prompts.any { "menu photograph" in it })
        val (_, kept, _) = print("""{"type":"highlights","lang":"en","photos":true,"fillPhotos":true,"savePhotos":true}""")
        assertEquals(drawn, kept.s("photosSaved")!!.toInt())
        assertEquals(drawn, transaction { ItemPhotos.selectAll().count() }.toInt())
    }

    @Test
    fun plansAreRateLimitedPerUser() = testApplication {
        app { service(it, plans = 2) }; bootstrap()
        val body = """{"type":"full","lang":"en"}"""
        repeat(2) { assertEquals(HttpStatusCode.OK, post("/v1/menu-print/plan?venue=vieux-port", body).status) }
        val r = post("/v1/menu-print/plan?venue=vieux-port", body)
        assertEquals(HttpStatusCode.TooManyRequests, r.status)
        assertEquals("menu_print_too_many", r.json().s("code"))
    }
}
