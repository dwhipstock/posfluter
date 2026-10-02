package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.MenuFeed
import dev.dwhipstock.poscloud.db.PortalUsers
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
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
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Edit item -> Stores in the portal: an item one store carries is put on
 * another under the same id (POST /v1/menu/items/{id}/copy?venue=), with every
 * field, its specials and its photo, through the ordinary portal edit path
 * (cloud stamps, menu feed of that store only); taken off one store with the
 * ordinary scoped delete; and GET /v1/menu/stores lists every store.
 */
class MenuItemStoresTest {

    private val keyA = "store-key-stores-a" // vieux-port: carries the lager
    private val keyB = "store-key-stores-b" // plateau: no "beer" category
    private val keyC = "store-key-stores-c" // mile-end: has "beer", not the lager
    private lateinit var session: String
    private var seq = 0L

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("copperlantern")
        seedTenant("copperlantern", "plateau", "Copper Lantern — Plateau")
        seedTenant("copperlantern", "mile-end", "Copper Lantern — Mile End")
        seedStoreKey("copperlantern", "vieux-port", keyA)
        seedStoreKey("copperlantern", "plateau", keyB)
        seedStoreKey("copperlantern", "mile-end", keyC)
        session = seedSession("copperlantern", seedUser("copperlantern", "owner@test.dev", "password-o"))
        seq = 0
    }

    private fun category(id: String, en: String, fr: String, sort: Int = 0) = buildJsonObject {
        put("id", id); put("nameFr", fr); put("nameEn", en); put("sortOrder", sort); put("deleted", false)
    }

    private fun lager() = buildJsonObject {
        put("id", "lantern-lager")
        put("nameFr", "Lager de la Lanterne"); put("nameEn", "Lantern House Lager")
        put("descriptionFr", "Blonde maison"); put("descriptionEn", "House blonde")
        put("categoryId", "beer"); put("abbrev", "LL"); put("isAlcohol", true); put("active", true); put("deleted", false)
        put("names", buildJsonObject { put("es", "Lager de la Linterna"); put("de", "Laternen-Lager") })
        put("variants", buildJsonArray {
            add(buildJsonObject {
                put("id", "lantern-lager:pint"); put("labelFr", "Pinte"); put("labelEn", "Pint")
                put("priceCents", 825); put("sortOrder", 0); put("deleted", false)
                put("names", buildJsonObject { put("es", "Pinta") })
            })
            add(buildJsonObject {
                put("id", "lantern-lager:pitcher"); put("labelFr", "Pichet"); put("labelEn", "Pitcher")
                put("priceCents", 2400); put("sortOrder", 1); put("deleted", false); put("names", buildJsonObject {})
            })
        })
    }

    private suspend fun ApplicationTestBuilder.snapshot(key: String, categories: List<JsonObject>, items: List<JsonObject>) {
        ingest(key, event("catalog.snapshot", buildJsonObject {
            put("categories", buildJsonArray { categories.forEach { add(it) } })
            put("items", buildJsonArray { items.forEach { add(it) } })
        }, seq = ++seq))
    }

    /** Three stores, all speaking two-way sync. */
    private suspend fun ApplicationTestBuilder.stores(pullB: Boolean = true) {
        snapshot(keyA, listOf(category("beer", "Beer", "Bière"), category("food", "Food", "Plats", 1)), listOf(lager()))
        snapshot(keyB, listOf(category("plats", "Mains", "Plats")), emptyList())
        snapshot(keyC, listOf(category("beer", "Beer", "Bière")), emptyList())
        pull(keyA); if (pullB) pull(keyB); pull(keyC)
    }

    private suspend fun ApplicationTestBuilder.pull(key: String, since: Long = 0): JsonObject =
        testJson.parseToJsonElement(client.get("/v1/store/menu/changes?since=$since") {
            header(HttpHeaders.Authorization, "Bearer $key")
        }.bodyAsText()).jsonObject

    private suspend fun ApplicationTestBuilder.portal(
        method: HttpMethod, path: String, body: String? = null, idem: String? = null, who: String = session,
    ): HttpResponse = client.request(path) {
        this.method = method
        header(HttpHeaders.Cookie, "pos_portal_session=$who")
        idem?.let { header("Idempotency-Key", it) }
        if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
    }

    private suspend fun ApplicationTestBuilder.copy(to: String, body: String = """{"from":"vieux-port"}""", idem: String? = null) =
        portal(HttpMethod.Post, "/v1/menu/items/lantern-lager/copy?venue=$to", body, idem)

    private suspend fun ApplicationTestBuilder.menuItem(venue: String): JsonObject? =
        testJson.parseToJsonElement(getWithCookie("/v1/menu?venue=$venue", session).bodyAsText())
            .jsonObject["items"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it.str("id") == "lantern-lager" }

    private suspend fun ApplicationTestBuilder.uploadPhoto(key: String, bytes: ByteArray) {
        val res = client.post("/v1/ingest/photos/lantern-lager") {
            header(HttpHeaders.Authorization, "Bearer $key")
            setBody(MultiPartFormDataContent(formData {
                append("photo", bytes, Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"lantern-lager.jpg\"")
                })
            }))
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
    }

    private suspend fun HttpResponse.json(): JsonObject = testJson.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun feedCount(venue: String) = transaction { MenuFeed.selectAll().where { MenuFeed.venueId eq venue }.count() }

    @Test
    fun copyingPutsTheItemOnTheStoreWithEveryFieldSpecialsAndPhotoUnderTheSameId() = testApplication {
        application { module(TestSupport.config) }
        stores()
        // selling days and a special, set in the portal at the source store
        assertEquals(HttpStatusCode.OK, portal(HttpMethod.Patch, "/v1/menu/items/lantern-lager?venue=vieux-port",
            """{"availableDays":["fri","sat"],"specials":[{"days":["fri"],"from":"16:00","to":"18:00","label":"Happy hour",
               "prices":{"lantern-lager:pint":600}}]}""").status)
        val photo = MenuAiPhotosTest.jpeg(90, 90, 0x884422)
        uploadPhoto(keyA, photo)
        val feedA = feedCount("vieux-port")
        val feedB = feedCount("plateau")
        val cursorC = pull(keyC)["cursor"]!!.jsonPrimitive.content.toLong()

        val res = copy("mile-end")
        assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
        val body = res.json()
        assertEquals(listOf("mile-end"), body["applied"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("lantern-lager", body.str("id"))

        // the portal shows it at mile-end with everything the source has
        val got = menuItem("mile-end")!!
        val src = menuItem("vieux-port")!!
        for (k in listOf("nameEn", "nameFr", "descriptionEn", "descriptionFr", "categoryId", "abbrev", "isAlcohol", "active"))
            assertEquals(src[k], got[k], k)
        assertEquals(src["names"], got["names"])
        assertEquals(src["variants"], got["variants"])
        assertEquals(src["availableDays"], got["availableDays"])
        assertEquals(src["specials"], got["specials"])
        assertNotNull(got["photoVersion"]?.jsonPrimitive?.content?.toLongOrNull())

        // the feed: only mile-end's, the full item with cloud stamps, then its photo
        assertEquals(feedA, feedCount("vieux-port"))
        assertEquals(feedB, feedCount("plateau"))
        val changes = pull(keyC, cursorC)["changes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("item", "photo"), changes.map { it.str("entity") })
        val data = changes[0]["data"]!!.jsonObject
        assertEquals("false", data.str("deleted"))
        assertTrue(data["clock"]!!.jsonObject.str("nameEn").endsWith("-cloud"))
        assertEquals(listOf("lantern-lager:pint", "lantern-lager:pitcher"), data["variants"]!!.jsonArray.map { it.jsonObject.str("id") })
        assertTrue("Happy hour" in data["specials"].toString())
        val dl = client.get("/v1/store/menu/photos/lantern-lager") { header(HttpHeaders.Authorization, "Bearer $keyC") }
        assertEquals(HttpStatusCode.OK, dl.status)
        assertTrue(dl.readRawBytes().contentEquals(photo))

        // one id everywhere: an "All stores" edit now reaches both copies
        val all = portal(HttpMethod.Patch, "/v1/menu/items/lantern-lager", """{"descriptionEn":"Our house blonde"}""").json()
        assertEquals(setOf("vieux-port", "mile-end"), all["applied"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals("Our house blonde", menuItem("mile-end")!!.str("descriptionEn"))
    }

    @Test
    fun aStoreWithoutTheCategoryIsRefusedUntilOneIsPicked() = testApplication {
        application { module(TestSupport.config) }
        stores()
        val res = copy("plateau")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("category_not_found", res.json().str("code"))
        assertEquals(null, menuItem("plateau"))
        // a category of plateau's own
        val ok = copy("plateau", """{"from":"vieux-port","categoryId":"plats"}""")
        assertEquals(HttpStatusCode.Created, ok.status, ok.bodyAsText())
        assertEquals("plats", menuItem("plateau")!!.str("categoryId"))
        assertEquals("beer", menuItem("vieux-port")!!.str("categoryId"))
    }

    @Test
    fun theGuards() = testApplication {
        application { module(TestSupport.config) }
        stores(pullB = false)
        // a store too old to take edits
        val old = copy("plateau", """{"from":"vieux-port","categoryId":"plats"}""")
        assertEquals(HttpStatusCode.Conflict, old.status)
        assertEquals("store_not_upgraded", old.json().str("code"))
        // one store to copy to; never onto itself; from a real store that has it
        assertEquals("venue_required", portal(HttpMethod.Post, "/v1/menu/items/lantern-lager/copy", """{"from":"vieux-port"}""").json().str("code"))
        assertEquals("same_store", copy("vieux-port").json().str("code"))
        assertEquals(HttpStatusCode.NotFound, copy("mile-end", """{"from":"nowhere"}""").status)
        assertEquals(HttpStatusCode.NotFound, copy("vieux-port", """{"from":"mile-end"}""").status)
        // already there
        assertEquals(HttpStatusCode.Created, copy("mile-end").status)
        val again = copy("mile-end")
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("already_on_menu", again.json().str("code"))
        // a retry with the same key changes nothing
        val n = feedCount("mile-end")
        portal(HttpMethod.Delete, "/v1/menu/items/lantern-lager?venue=mile-end")
        val first = copy("mile-end", idem = "k-1")
        val dup = copy("mile-end", idem = "k-1")
        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals("true", dup.json().str("duplicate"))
        assertEquals(n + 2, feedCount("mile-end"))
        // viewers can't
        val viewer = seedUser("copperlantern", "viewer@test.dev", "password-v")
        transaction { PortalUsers.update({ PortalUsers.id eq viewer }) { it[role] = "viewer" } }
        val v = seedSession("copperlantern", viewer)
        assertEquals(HttpStatusCode.Forbidden, portal(HttpMethod.Post, "/v1/menu/items/lantern-lager/copy?venue=plateau",
            """{"from":"vieux-port","categoryId":"plats"}""", who = v).status)
        // …but see the list, read-only
        val list = getWithCookie("/v1/menu/stores?item=lantern-lager", v).json()
        assertEquals("false", list.str("canEdit"))
    }

    @Test
    fun removingFromOneStoreLeavesTheOthersAndACopyBackHasOnlyTheLiveSizes() = testApplication {
        application { module(TestSupport.config) }
        stores()
        assertEquals(HttpStatusCode.Created, copy("mile-end").status)
        val feedA = feedCount("vieux-port")
        val cursorC = pull(keyC)["cursor"]!!.jsonPrimitive.content.toLong()
        // off mile-end only: the portal's ordinary delete, scoped to that store
        val del = portal(HttpMethod.Delete, "/v1/menu/items/lantern-lager?venue=mile-end")
        assertEquals(HttpStatusCode.OK, del.status, del.bodyAsText())
        assertEquals(null, menuItem("mile-end"))
        assertNotNull(menuItem("vieux-port"))
        assertEquals(feedA, feedCount("vieux-port"))
        val gone = pull(keyC, cursorC)["changes"]!!.jsonArray.single().jsonObject
        assertEquals("true", gone["data"]!!.jsonObject.str("deleted"))

        // the pitcher goes at the source; a copy back brings the item with the pint only
        val src = menuItem("vieux-port")!!
        assertEquals(2, src["variants"]!!.jsonArray.size)
        assertEquals(HttpStatusCode.OK, portal(HttpMethod.Delete,
            "/v1/menu/items/lantern-lager/variants/lantern-lager:pitcher?venue=vieux-port").status)
        assertEquals(HttpStatusCode.Created, copy("mile-end").status)
        val back = menuItem("mile-end")!!
        assertEquals(listOf("lantern-lager:pint"), back["variants"]!!.jsonArray.map { it.jsonObject.str("id") })
        val entry = pull(keyC, cursorC)["changes"]!!.jsonArray.last { it.jsonObject.str("entity") == "item" }.jsonObject["data"]!!.jsonObject
        assertEquals("false", entry.str("deleted"))
        val pitcher = entry["variants"]!!.jsonArray.map { it.jsonObject }.single { it.str("id") == "lantern-lager:pitcher" }
        assertEquals("true", pitcher.str("deleted"))
    }

    @Test
    fun theStoresListShowsEveryStoreWhateverIsPicked() = testApplication {
        application { module(TestSupport.config) }
        stores(pullB = false)
        for (path in listOf("/v1/menu/stores?item=lantern-lager", "/v1/menu/stores?item=lantern-lager&venue=plateau")) {
            val list = getWithCookie(path, session).json()
            assertEquals("true", list.str("canEdit"))
            val byId = list["stores"]!!.jsonArray.map { it.jsonObject }.associateBy { it.str("venueId") }
            assertEquals(setOf("vieux-port", "plateau", "mile-end"), byId.keys)
            assertEquals("true", byId["vieux-port"]!!.str("carries"))
            assertEquals("beer", byId["vieux-port"]!!.str("categoryId"))
            assertEquals("false", byId["mile-end"]!!.str("carries"))
            assertEquals("false", byId["plateau"]!!.str("editable"))
            assertEquals(listOf("plats"), byId["plateau"]!!["categories"]!!.jsonArray.map { it.jsonObject.str("id") })
            assertEquals(listOf("beer", "food"), byId["vieux-port"]!!["categories"]!!.jsonArray.map { it.jsonObject.str("id") })
        }
        // with no item (Add item): every store and its categories, nothing carried
        val none = getWithCookie("/v1/menu/stores", session).json()["stores"]!!.jsonArray.map { it.jsonObject }
        assertFalse(none.any { it.str("carries") == "true" })
    }
}
