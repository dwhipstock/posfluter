package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.menu.MenuSpecials
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Menu specials over two-way menu sync (CONTRACT §10 "Specials"): the two item
 * registers `availableDays` and `specials` are edited in the portal, merged
 * with the store's by stamp, and always travel in the store's canonical text.
 */
class MenuSpecialsSyncTest {

    private val key = "store-key-specials"
    private lateinit var session: String
    private var seq = 0L

    /** The CONTRACT example, exactly as the store writes it. */
    private val happyHourText =
        """[{"days":["mon","tue","wed","thu","fri"],"from":"16:00","to":"18:00","label":"Happy hour","prices":{"lantern-lager:pint":500}}]"""

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("copperlantern")
        seedStoreKey("copperlantern", "vieux-port", key)
        session = seedSession("copperlantern", seedUser("copperlantern", "owner@test.dev", "password-o"))
        seq = 0
    }

    private fun stamp(deltaMs: Long, node: String = "sstore1") =
        "%013d-%04d-%s".format(System.currentTimeMillis() + deltaMs, 0, node)

    private fun item(
        availableDays: JsonElement? = null, specials: JsonElement? = null, clock: Map<String, String>? = null,
    ): JsonObject = buildJsonObject {
        put("id", "lantern-lager")
        put("nameFr", "Lager de la Lanterne"); put("nameEn", "Lantern House Lager")
        put("descriptionFr", ""); put("descriptionEn", "")
        put("categoryId", "beer"); put("abbrev", "LL")
        put("isAlcohol", true); put("active", true); put("deleted", false)
        put("names", buildJsonObject {})
        availableDays?.let { put("availableDays", it) }
        specials?.let { put("specials", it) }
        clock?.let { c -> put("clock", buildJsonObject { c.forEach { (k, v) -> put(k, v) } }) }
        put("variants", buildJsonArray {
            add(buildJsonObject {
                put("id", "lantern-lager:pint"); put("labelFr", "Pinte"); put("labelEn", "Pint")
                put("priceCents", 750); put("sortOrder", 0); put("deleted", false); put("names", buildJsonObject {})
            })
            add(buildJsonObject {
                put("id", "lantern-lager:pitcher"); put("labelFr", "Pichet"); put("labelEn", "Pitcher")
                put("priceCents", 2025); put("sortOrder", 1); put("deleted", false); put("names", buildJsonObject {})
            })
        })
    }

    private suspend fun ApplicationTestBuilder.bootstrap() {
        ingest(key, event("catalog.snapshot", buildJsonObject {
            put("categories", buildJsonArray {
                add(buildJsonObject { put("id", "beer"); put("nameFr", "Bière"); put("nameEn", "Beer"); put("sortOrder", 0); put("deleted", false) })
            })
            put("items", buildJsonArray { add(item()) })
        }, seq = ++seq))
    }

    private suspend fun ApplicationTestBuilder.pull(since: Long = 0): JsonObject =
        testJson.parseToJsonElement(client.get("/v1/store/menu/changes?since=$since") {
            header(HttpHeaders.Authorization, "Bearer $key")
        }.bodyAsText()).jsonObject

    private suspend fun ApplicationTestBuilder.patch(body: String): HttpResponse =
        client.request("/v1/menu/items/lantern-lager?venue=vieux-port") {
            method = HttpMethod.Patch
            header(HttpHeaders.Cookie, "pos_portal_session=$session")
            contentType(ContentType.Application.Json); setBody(body)
        }

    private suspend fun ApplicationTestBuilder.menuItem(): JsonObject =
        testJson.parseToJsonElement(getWithCookie("/v1/menu?venue=vieux-port", session).bodyAsText())
            .jsonObject["items"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "lantern-lager" }

    private fun JsonObject.raw(k: String) = (this[k] ?: JsonNull).toString()

    @Test
    fun portalSetsDaysAndSpecialsTheFeedCarriesThemInTheStoresCanonicalText() = testApplication {
        application { module(TestSupport.config) }
        bootstrap()
        pull()
        // messy input: days out of order, duplicates, a short time, padded label, unsorted prices
        val res = patch("""{"availableDays":["Sat","fri","fri"],
            "specials":[{"days":["fri","mon","tue","wed","thu"],"from":"16:00","to":"18:00","label":"  Happy   hour ",
              "prices":{"lantern-lager:pint":500}}]}""")
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val change = pull()["changes"]!!.jsonArray.single().jsonObject["data"]!!.jsonObject
        assertEquals("""["fri","sat"]""", change.raw("availableDays"))
        assertEquals(happyHourText, change.raw("specials"))
        val clock = change["clock"]!!.jsonObject
        assertTrue(clock["specials"]!!.jsonPrimitive.content.endsWith("-cloud"))
        assertTrue(clock["availableDays"]!!.jsonPrimitive.content.endsWith("-cloud"))
        // the portal's menu shows them
        val shown = menuItem()
        assertEquals("""["fri","sat"]""", shown.raw("availableDays"))
        val sp = shown["specials"]!!.jsonArray.single().jsonObject
        assertEquals("16:00", sp["from"]!!.jsonPrimitive.content)
        assertEquals("Happy hour", sp["label"]!!.jsonPrimitive.content)
        assertEquals(500, sp["prices"]!!.jsonObject["lantern-lager:pint"]!!.jsonPrimitive.content.toInt())

        // clearing: every day again, no specials
        assertEquals(HttpStatusCode.OK, patch("""{"availableDays":["mon","tue","wed","thu","fri","sat","sun"],"specials":[]}""").status)
        val cleared = pull()["changes"]!!.jsonArray.last().jsonObject["data"]!!.jsonObject
        assertEquals("null", cleared.raw("availableDays"))
        assertEquals("null", cleared.raw("specials"))
        assertEquals("null", menuItem().raw("specials"))
    }

    @Test
    fun badSpecialsAreRefused() = testApplication {
        application { module(TestSupport.config) }
        bootstrap()
        pull()
        fun body(sp: String) = """{"specials":[$sp]}"""
        val cases = mapOf(
            body("""{"days":["tue"],"prices":{"lantern-lager:bottle":500}}""") to "size_not_found",
            body("""{"days":["tue"],"prices":{}}""") to "special_no_price",
            body("""{"days":[],"prices":{"lantern-lager:pint":500}}""") to "special_no_days",
            body("""{"days":["tus"],"prices":{"lantern-lager:pint":500}}""") to "bad_day",
            body("""{"days":["tue"],"from":"16:00","prices":{"lantern-lager:pint":500}}""") to "bad_time",
            body("""{"days":["tue"],"from":"16:00","to":"16:00","prices":{"lantern-lager:pint":500}}""") to "bad_time",
            body("""{"days":["tue"],"prices":{"lantern-lager:pint":-1}}""") to "bad_price",
        )
        for ((b, code) in cases) {
            val res = patch(b)
            assertEquals(HttpStatusCode.BadRequest, res.status, "$b → ${res.bodyAsText()}")
            assertTrue(res.bodyAsText().contains(code), "$b → ${res.bodyAsText()}")
        }
        assertEquals("null", menuItem().raw("specials"))
    }

    @Test
    fun storeEditsMergeLastWriteWinsBothWays() = testApplication {
        application { module(TestSupport.config) }
        bootstrap()
        pull()
        val storeSpecials = testJson.parseToJsonElement(happyHourText)
        // the tablet adds happy hour (a newer stamp than anything): it lands, nothing goes back down
        ingest(key, event("item.updated", buildJsonObject {
            put("item", item(availableDays = buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("sun")) },
                specials = storeSpecials,
                clock = mapOf("availableDays" to stamp(-5_000), "specials" to stamp(-5_000))))
        }, seq = ++seq))
        assertEquals(happyHourText, menuItem().raw("specials"))
        assertEquals("""["sun"]""", menuItem().raw("availableDays"))
        assertEquals(0, pull()["changes"]!!.jsonArray.size)

        // the portal clears the specials now; an OLDER store write of them loses and is corrected
        assertEquals(HttpStatusCode.OK, patch("""{"specials":[]}""").status)
        val cursor = pull()["cursor"]!!.jsonPrimitive.content.toLong()
        ingest(key, event("item.updated", buildJsonObject {
            put("item", item(availableDays = buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("sun")) },
                specials = storeSpecials,
                clock = mapOf("availableDays" to stamp(-5_000), "specials" to stamp(-60_000))))
        }, seq = ++seq))
        assertEquals("null", menuItem().raw("specials"))
        val correction = pull(since = cursor)["changes"]!!.jsonArray.single().jsonObject["data"]!!.jsonObject
        assertEquals("null", correction.raw("specials"))
        assertEquals("""["sun"]""", correction.raw("availableDays"))

        // a NEWER store write beats the portal's; leaving the key out means "none" (the store omits nulls)
        ingest(key, event("item.updated", buildJsonObject {
            put("item", item(specials = storeSpecials, clock = mapOf("availableDays" to stamp(30_000), "specials" to stamp(30_000))))
        }, seq = ++seq))
        assertEquals(happyHourText, menuItem().raw("specials"))
        assertEquals("null", menuItem().raw("availableDays"))
    }

    @Test
    fun anOlderStoreThatSaysNothingOfSpecialsKeepsThePortals() = testApplication {
        application { module(TestSupport.config) }
        bootstrap()
        pull()
        assertEquals(HttpStatusCode.OK, patch("""{"availableDays":["fri","sat"]}""").status)
        // a v3 store from before specials: its clock knows nothing of the two fields
        ingest(key, event("item.updated", buildJsonObject {
            put("item", item(clock = mapOf("nameEn" to stamp(10_000))))
        }, seq = ++seq))
        assertEquals("""["fri","sat"]""", menuItem().raw("availableDays"))
    }

    @Test
    fun theCanonicalFormMatchesTheStore() {
        val messy = testJson.parseToJsonElement(
            """[{"prices":{"b":2,"a":1},"label":" x ","to":"2:00","from":"22:00","days":["sun","mon","mon"]},
                {"prices":{"b":2,"a":1},"label":"x","to":"02:00","from":"22:00","days":["mon","sun"]},
                {"days":["xyz"],"prices":{"a":1}}]""")
        assertEquals("""[{"days":["mon","sun"],"from":"22:00","to":"02:00","label":"x","prices":{"a":1,"b":2}}]""",
            MenuSpecials.canonical("specials", messy).toString())
        assertEquals("null", MenuSpecials.canonical("availableDays",
            testJson.parseToJsonElement("""["sun","sat","fri","thu","wed","tue","mon"]""")).toString())
        assertEquals(happyHourText, MenuSpecials.canonical("specials", testJson.parseToJsonElement(happyHourText)).toString())
    }
}
