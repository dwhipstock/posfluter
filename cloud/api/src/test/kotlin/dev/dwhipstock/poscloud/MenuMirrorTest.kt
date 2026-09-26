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

/** One-way sync: the portal menu is a read-only mirror of what the store pushes. */
class MenuMirrorTest {

    private val key = "store-key-menu"
    private lateinit var session: String

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("copperlantern")
        seedStoreKey("copperlantern", "vieux-port", key)
        session = seedSession("copperlantern", seedUser("copperlantern", "menu@test.dev", "password-m"))
    }

    private fun changeCount(): Long = transaction { CatalogChanges.selectAll().count() }

    private fun itemSnapshot(nameEn: String, origin: String? = null): JsonObject = buildJsonObject {
        origin?.let { put("origin", it) }
        put("item", buildJsonObject {
            put("id", "lantern-lager")
            put("nameFr", "Lager de la Lanterne")
            put("nameEn", nameEn)
            put("categoryId", "beer")
            put("isAlcohol", true)
            put("active", true)
            put("deleted", false)
            put("variants", buildJsonArray {
                add(buildJsonObject {
                    put("id", "lantern-lager:pint")
                    put("labelFr", "Pinte 20 oz")
                    put("labelEn", "20 oz pint")
                    put("priceCents", 825)
                    put("sortOrder", 0)
                    put("deleted", false)
                })
            })
        })
    }

    private suspend fun ApplicationTestBuilder.menu(): JsonObject =
        testJson.parseToJsonElement(getWithCookie("/v1/menu", session).bodyAsText()).jsonObject

    @Test
    fun storePushedMenuIsMirroredAndNothingIsRedistributed() = testApplication {
        application { module(TestSupport.config) }
        ingest(key, event("catalog.snapshot", buildJsonObject {
            put("categories", buildJsonArray {
                add(buildJsonObject {
                    put("id", "beer"); put("nameFr", "Bière"); put("nameEn", "Beer")
                    put("sortOrder", 0); put("deleted", false)
                })
            })
            put("items", buildJsonArray { add(itemSnapshot("Lantern House Lager")["item"]!!) })
        }, seq = 1))
        assertEquals(1, menu()["items"]!!.jsonArray.size)

        // a tablet edit updates the mirror…
        ingest(key, event("item.updated", itemSnapshot("Lantern House Lager Classic"), seq = 2))
        assertEquals("Lantern House Lager Classic",
            menu()["items"]!!.jsonArray[0].jsonObject["nameEn"]!!.jsonPrimitive.content)
        // …a legacy cloud echo is audit-only…
        ingest(key, event("item.updated", itemSnapshot("Should Not Apply", origin = "cloud"), seq = 3))
        assertEquals("Lantern House Lager Classic",
            menu()["items"]!!.jsonArray[0].jsonObject["nameEn"]!!.jsonPrimitive.content)
        // …and nothing is ever queued back down to a store
        assertEquals(0, changeCount())
    }

    @Test
    fun photoProvenanceIsMirroredAndSurvivesEditsWithoutIt() = testApplication {
        application { module(TestSupport.config) }
        fun withPhoto(source: String?) = buildJsonObject {
            put("itemId", "lantern-lager")
            source?.let { put("source", it) }
            put("item", buildJsonObject {
                itemSnapshot("Lantern House Lager")["item"]!!.jsonObject.forEach { (k, v) -> put(k, v) }
                put("photoVersion", 1736590000000)
                source?.let { put("photoSource", it) }
            })
        }
        suspend fun source() = menu()["items"]!!.jsonArray[0].jsonObject["photoSource"]
        ingest(key, event("item.photo_uploaded", withPhoto("ai_generated"), seq = 1))
        assertEquals("ai_generated", source()!!.jsonPrimitive.content)
        // an ordinary edit (no photo fields) keeps the badge
        ingest(key, event("item.updated", itemSnapshot("Lantern House Lager"), seq = 2))
        assertEquals("ai_generated", source()!!.jsonPrimitive.content)
        // a manager's own photo replaces it
        ingest(key, event("item.photo_uploaded", withPhoto("original"), seq = 3))
        assertEquals("original", source()!!.jsonPrimitive.content)
        // an unknown value is ignored rather than shown
        ingest(key, event("item.photo_uploaded", withPhoto("something-else"), seq = 4))
        assertEquals("original", source()!!.jsonPrimitive.content)
    }

    @Test
    fun portalCannotEditTheMenu() = testApplication {
        application { module(TestSupport.config) }
        val attempts = listOf(
            client.post("/v1/menu/items") {
                header(HttpHeaders.Cookie, "pos_portal_session=$session")
                contentType(ContentType.Application.Json)
                setBody("""{"nameFr":"x","nameEn":"x","categoryId":"beer","abbrev":"X","variants":[]}""")
            },
            client.patch("/v1/menu/items/lantern-lager") {
                header(HttpHeaders.Cookie, "pos_portal_session=$session")
                contentType(ContentType.Application.Json)
                setBody("""{"active":false}""")
            },
            client.post("/v1/menu/categories") {
                header(HttpHeaders.Cookie, "pos_portal_session=$session")
                contentType(ContentType.Application.Json)
                setBody("""{"nameFr":"x","nameEn":"x"}""")
            },
        )
        assertTrue(attempts.all { it.status == HttpStatusCode.NotFound || it.status == HttpStatusCode.MethodNotAllowed })
        assertEquals(0, changeCount())
    }
}
