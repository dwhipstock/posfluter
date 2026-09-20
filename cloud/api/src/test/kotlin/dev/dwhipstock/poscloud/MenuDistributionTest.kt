package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.CatalogChanges
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
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MenuDistributionTest {

    private val key = "store-key-menu"
    private lateinit var session: String

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("copperlantern")
        seedStoreKey("copperlantern", "main", key)
        session = seedSession("copperlantern", seedUser("copperlantern", "menu@test.dev", "password-m"))
    }

    private fun changeCount(): Long = transaction {
        CatalogChanges.selectAll().where { CatalogChanges.tenantId eq "copperlantern" }.count()
    }

    private fun itemSnapshot(nameEn: String, origin: String? = null): JsonObject = buildJsonObject {
        origin?.let { put("origin", it) }
        put("item", buildJsonObject {
            put("id", "lantern-lager")
            put("nameFr", "éléphant")
            put("nameEn", nameEn)
            put("categoryId", "beer")
            put("isAlcohol", true)
            put("active", true)
            put("deleted", false)
            put("variants", buildJsonArray {
                add(buildJsonObject {
                    put("id", "lantern-lager:bottle")
                    put("labelFr", "bouteille")
                    put("labelEn", "Bottle")
                    put("priceCents", 9000)
                    put("sortOrder", 0)
                    put("deleted", false)
                })
            })
        })
    }

    private suspend fun ApplicationTestBuilder.feed(since: Long): JsonObject {
        val res = client.get("/v1/store/catalog/changes?since=$since") {
            header(HttpHeaders.Authorization, "Bearer $key")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        return testJson.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    @Test
    fun snapshotBootstrapsWithoutChangeRows() = testApplication {
        application { module(TestSupport.config) }
        ingest(key, event("catalog.snapshot", buildJsonObject {
            put("categories", buildJsonArray {
                add(buildJsonObject {
                    put("id", "beer")
                    put("nameFr", "bière")
                    put("nameEn", "Beer")
                    put("sortOrder", 0)
                    put("deleted", false)
                })
            })
            put("items", buildJsonArray { add(itemSnapshot("Lantern House Lager")["item"]!!) })
        }, seq = 1))

        assertEquals(0, changeCount())
        val menu = testJson.parseToJsonElement(getWithCookie("/v1/menu", session).bodyAsText()).jsonObject
        assertEquals(1, menu["categories"]!!.jsonArray.size)
        assertEquals(1, menu["items"]!!.jsonArray.size)
        assertEquals("Lantern House Lager",
            menu["items"]!!.jsonArray[0].jsonObject["nameEn"]!!.jsonPrimitive.content)
    }

    @Test
    fun portalEditFlowsToStoreFeedAndEchoBreaksTheLoop() = testApplication {
        application { module(TestSupport.config) }
        ingest(key, event("catalog.snapshot", buildJsonObject {
            put("categories", buildJsonArray {
                add(buildJsonObject {
                    put("id", "beer")
                    put("nameFr", "bière")
                    put("nameEn", "Beer")
                    put("sortOrder", 0)
                })
            })
            put("items", buildJsonArray { add(itemSnapshot("Lantern House Lager")["item"]!!) })
        }, seq = 1))
        assertEquals(0, changeCount())

        // portal edit → change row + feed entry
        val patch = client.patch("/v1/menu/items/lantern-lager") {
            header(HttpHeaders.Cookie, "pos_portal_session=$session")
            contentType(ContentType.Application.Json)
            setBody("""{"nameEn":"Lantern House Lager Beer"}""")
        }
        assertEquals(HttpStatusCode.OK, patch.status)
        assertEquals(1, changeCount())

        val page1 = feed(0)
        val changes1 = page1["changes"]!!.jsonArray
        assertEquals(1, changes1.size)
        val change = changes1[0].jsonObject
        assertEquals("item", change["kind"]!!.jsonPrimitive.content)
        assertEquals("upsert", change["op"]!!.jsonPrimitive.content)
        assertEquals("Lantern House Lager Beer",
            change["data"]!!.jsonObject["nameEn"]!!.jsonPrimitive.content)
        val cursor1 = page1["cursor"]!!.jsonPrimitive.content.toLong()
        assertEquals(change["version"]!!.jsonPrimitive.content.toLong(), cursor1)

        // POS-originated edit (no origin) → applied AND redistributed
        ingest(key, event("item.updated", itemSnapshot("Lantern House Lager Classic"), seq = 2))
        val page2 = feed(cursor1)
        assertEquals(1, page2["changes"]!!.jsonArray.size)
        val cursor2 = page2["cursor"]!!.jsonPrimitive.content.toLong()
        assertTrue(cursor2 > cursor1)
        var menu = testJson.parseToJsonElement(getWithCookie("/v1/menu", session).bodyAsText()).jsonObject
        assertEquals("Lantern House Lager Classic",
            menu["items"]!!.jsonArray[0].jsonObject["nameEn"]!!.jsonPrimitive.content)

        // echo (origin: cloud) → stored, NOT applied, NOT redistributed
        ingest(key, event("item.updated", itemSnapshot("Should Not Apply", origin = "cloud"), seq = 3))
        val page3 = feed(cursor2)
        assertEquals(0, page3["changes"]!!.jsonArray.size)
        assertEquals(cursor2, page3["cursor"]!!.jsonPrimitive.content.toLong())
        menu = testJson.parseToJsonElement(getWithCookie("/v1/menu", session).bodyAsText()).jsonObject
        assertEquals("Lantern House Lager Classic",
            menu["items"]!!.jsonArray[0].jsonObject["nameEn"]!!.jsonPrimitive.content)
    }

    @Test
    fun portalItemCreateGetsSlugIdAndFeedsTheStore() = testApplication {
        application { module(TestSupport.config) }
        val category = client.post("/v1/menu/categories") {
            header(HttpHeaders.Cookie, "pos_portal_session=$session")
            contentType(ContentType.Application.Json)
            setBody("""{"nameFr":"bière","nameEn":"Beer"}""")
        }
        assertEquals(HttpStatusCode.Created, category.status)
        val categoryId = testJson.parseToJsonElement(category.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
        assertEquals("beer", categoryId)

        val item = client.post("/v1/menu/items") {
            header(HttpHeaders.Cookie, "pos_portal_session=$session")
            contentType(ContentType.Application.Json)
            setBody("""{"nameFr":"Tour de l'éléphant","nameEn":"Lantern House Lager Tower","categoryId":"beer","abbrev":"CT",
                        "isAlcohol":true,"variants":[{"labelFr":"Tour","labelEn":"Tower","priceCents":29900}]}""")
        }
        assertEquals(HttpStatusCode.Created, item.status)
        val itemBody = testJson.parseToJsonElement(item.bodyAsText()).jsonObject
        assertEquals("lantern-house-lager-tower", itemBody["id"]!!.jsonPrimitive.content)
        assertEquals("lantern-house-lager-tower:tower",
            itemBody["variants"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)

        // category change + item change on the feed, in version order
        val page = feed(0)
        val kinds = page["changes"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content }
        assertEquals(listOf("category", "item"), kinds)
    }
}
