package dev.dwhipstock.poscloud

import io.ktor.client.statement.*
import io.ktor.server.testing.*
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

/** Names beyond fr/en (es, de, ...) ride the catalog snapshots up to the portal (026). */
class CatalogNamesTest {

    private val key = "store-key-names"
    private lateinit var session: String

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("copperlantern")
        seedStoreKey("copperlantern", "vieux-port", key)
        session = seedSession("copperlantern", seedUser("copperlantern", "names@test.dev", "password-n"))
    }

    private fun names(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    /** [itemNames]/[variantNames] null = an older store (no `names` key at all). */
    private fun item(itemNames: JsonObject?, variantNames: JsonObject? = itemNames?.let { names() }) = buildJsonObject {
        put("id", "lantern-lager"); put("nameFr", "Lager de la Lanterne"); put("nameEn", "Lantern Lager")
        put("categoryId", "beer"); put("active", true); put("deleted", false)
        itemNames?.let { put("names", it) }
        put("variants", buildJsonArray {
            add(buildJsonObject {
                put("id", "lantern-lager:pint"); put("labelFr", "Pinte"); put("labelEn", "Pint")
                put("priceCents", 825); put("sortOrder", 0); put("deleted", false)
                variantNames?.let { put("names", it) }
            })
        })
    }

    private suspend fun ApplicationTestBuilder.getJson(path: String): JsonObject =
        testJson.parseToJsonElement(getWithCookie(path, session).bodyAsText()).jsonObject

    private fun JsonObject.namesMap(): Map<String, String> =
        this["names"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }

    @Test
    fun namesAreMirroredReplacedAndKeptForOlderStores() = testApplication {
        application { module(TestSupport.config) }
        ingest(key, event("catalog.snapshot", buildJsonObject {
            put("categories", buildJsonArray {
                add(buildJsonObject {
                    put("id", "beer"); put("nameFr", "Bière"); put("nameEn", "Beer")
                    put("sortOrder", 0); put("deleted", false)
                    put("names", names("es" to "Cerveza", "de" to "Bier"))
                })
            })
            put("items", buildJsonArray {
                add(item(names("es" to "Lager de la Linterna", "de" to "Laternen-Lager"), names("de" to "Halbe")))
            })
            put("zones", buildJsonArray {
                add(buildJsonObject { put("id", "upper"); put("names", names("de" to "Obergeschoss")) })
            })
        }, seq = 1))

        var menu = getJson("/v1/menu")
        val category = menu["categories"]!!.jsonArray.single().jsonObject
        assertEquals(mapOf("es" to "Cerveza", "de" to "Bier"), category.namesMap())
        var dto = menu["items"]!!.jsonArray.single().jsonObject
        assertEquals(mapOf("de" to "Laternen-Lager", "es" to "Lager de la Linterna"), dto.namesMap())
        assertEquals(mapOf("de" to "Halbe"), dto["variants"]!!.jsonArray.single().jsonObject.namesMap())

        // an older store's snapshot (no `names` key) keeps what is stored
        ingest(key, event("item.updated", buildJsonObject { put("item", item(null)) }, seq = 2))
        dto = getJson("/v1/menu")["items"]!!.jsonArray.single().jsonObject
        assertEquals("Laternen-Lager", dto.namesMap()["de"])
        assertEquals("Halbe", dto["variants"]!!.jsonArray.single().jsonObject.namesMap()["de"])

        // an empty `names` object clears them (item and variant)
        ingest(key, event("item.updated", buildJsonObject { put("item", item(names())) }, seq = 3))
        menu = getJson("/v1/menu")
        dto = menu["items"]!!.jsonArray.single().jsonObject
        assertEquals(emptyMap(), dto.namesMap())
        assertEquals(emptyMap(), dto["variants"]!!.jsonArray.single().jsonObject.namesMap())
        // the category was not touched
        assertEquals("Bier", menu["categories"]!!.jsonArray.single().jsonObject.namesMap()["de"])
    }

    @Test
    fun zoneAndCatalogNamesReachTheReports() = testApplication {
        application { module(TestSupport.config) }
        val at = "2026-07-21T19:00:00.000-04:00"
        ingest(key,
            event("catalog.snapshot", buildJsonObject {
                put("categories", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "beer"); put("nameFr", "Bière"); put("nameEn", "Beer")
                        put("sortOrder", 0); put("deleted", false); put("names", names("de" to "Bier"))
                    })
                })
                put("items", buildJsonArray { add(item(names("de" to "Laternen-Lager"))) })
                put("zones", buildJsonArray {
                    add(buildJsonObject { put("id", "upper"); put("names", names("de" to "Oben")) })
                })
            }, seq = 1),
            // a later rename replaces the zone's names
            event("zone.renamed", buildJsonObject {
                put("zoneId", "upper"); put("nameEn", "Upstairs")
                put("names", names("de" to "Obergeschoss", "es" to "Planta alta"))
            }, seq = 2),
            event("check.closed", checkClosedPayload(
                1, 1650, storeTax(1650), closedAt = at,
                tableLabel = "U-1", zoneId = "upper", zoneNameEn = "Upstairs",
                lines = buildJsonArray {
                    add(buildJsonObject {
                        put("lineId", 1); put("itemId", "lantern-lager"); put("categoryId", "beer")
                        put("nameFr", "Lager de la Lanterne"); put("nameEn", "Lantern Lager")
                        put("qty", 2); put("unitPriceCents", 825); put("lineTotalCents", 1650)
                    })
                },
            ), seq = 3, aggregateId = "1", createdAt = at),
        )
        val q = "from=2026-07-21&to=2026-07-21"
        val tables = getJson("/v1/reports/tables?$q")
        val zoneNames = mapOf("de" to "Obergeschoss", "es" to "Planta alta")
        assertEquals(zoneNames, tables["byZone"]!!.jsonArray.single().jsonObject.namesMap())
        assertEquals(zoneNames, tables["byTable"]!!.jsonArray.single().jsonObject["zoneNames"]!!.jsonObject
            .mapValues { it.value.jsonPrimitive.content })

        val items = getJson("/v1/reports/items?$q")["rows"]!!.jsonArray.single().jsonObject
        assertEquals(mapOf("de" to "Laternen-Lager"), items.namesMap())
        assertEquals("Bier", items["categoryNames"]!!.jsonObject["de"]!!.jsonPrimitive.content)

        val categories = getJson("/v1/reports/categories?$q")["rows"]!!.jsonArray.single().jsonObject
        assertEquals(mapOf("de" to "Bier"), categories.namesMap())
    }
}
