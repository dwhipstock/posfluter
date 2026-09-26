package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.Venues
import dev.dwhipstock.poscloud.stock.StockMath
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
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Stock for retail stores, computed in the cloud: received − sold ±
 * adjustments, per store; "All stores" lists every retail store's products
 * side by side (never mixing two stores' counts for the same product id).
 * Restaurants don't track stock.
 */
class StockTest {

    private val keyShop = "stock-key-shop"
    private val keyShop2 = "stock-key-shop2"
    private val keyPub = "stock-key-pub"
    private lateinit var session: String

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("stocktest", "sage-poppy", "Sage & Poppy Bottle Shop")
        seedTenant("stocktest", "second-shop", "Second Shop")
        seedTenant("stocktest", "vieux-port", "Copper Lantern — Vieux-Port")
        transaction {
            Venues.update({ (Venues.tenantId eq "stocktest") and (Venues.id inList listOf("sage-poppy", "second-shop")) }) {
                it[kind] = "retail"; it[currency] = "USD"; it[country] = "US"
            }
        }
        seedStoreKey("stocktest", "sage-poppy", keyShop)
        seedStoreKey("stocktest", "second-shop", keyShop2)
        seedStoreKey("stocktest", "vieux-port", keyPub)
        session = seedSession("stocktest", seedUser("stocktest", "owner@stock.test", "password-st"))
    }

    private suspend fun ApplicationTestBuilder.req(method: HttpMethod, path: String, body: String? = null): HttpResponse =
        client.request(path) {
            this.method = method
            header(HttpHeaders.Cookie, "pos_portal_session=$session")
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }

    private suspend fun ApplicationTestBuilder.get(path: String): JsonObject {
        val res = req(HttpMethod.Get, path)
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return testJson.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    private fun JsonObject.long(k: String) = this[k]!!.jsonPrimitive.content.toLong()
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.rows() = this["rows"]!!.jsonArray.map { it.jsonObject }

    private fun item(id: String, name: String, barcode: String) = buildJsonObject {
        put("id", id); put("nameFr", name); put("nameEn", name); put("categoryId", "beer")
        put("isAlcohol", true); put("active", true); put("deleted", false); put("barcode", barcode)
        put("variants", buildJsonArray {
            add(buildJsonObject { put("id", "$id:each"); put("labelFr", "Each"); put("labelEn", "Each"); put("priceCents", 999) })
        })
    }

    private fun sale(checkId: Int, vararg lines: Pair<String, Int>) = buildJsonObject {
        put("currency", "USD"); put("checkId", checkId); put("grandTotalCents", 1000); put("taxIncludedCents", 0)
        put("closedAt", "2026-07-21T16:00:00.000-07:00")
        put("lines", buildJsonArray {
            lines.forEachIndexed { i, (id, qty) ->
                add(buildJsonObject {
                    put("lineId", i + 1); put("itemId", id); put("variantId", "$id:each"); put("nameEn", id)
                    put("qty", qty); put("unitPriceCents", 999); put("lineTotalCents", 999L * qty)
                })
            }
        })
    }

    private suspend fun ApplicationTestBuilder.seedShops() {
        ingest(keyShop,
            event("catalog.snapshot", buildJsonObject {
                put("items", buildJsonArray {
                    add(item("golden-lager-6", "Golden Hour Lager 6-pack", "487230001029"))
                    add(item("ice-7", "Party Ice 7 lb bag", "487230007014"))
                })
            }, seq = 1, aggregateType = "catalog", aggregateId = "snapshot"),
            event("check.closed", sale(1, "golden-lager-6" to 3, "ice-7" to 1), seq = 2, aggregateId = "1"),
            event("check.closed", sale(2, "golden-lager-6" to 2), seq = 3, aggregateId = "2"),
            // a voided sale never took stock
            event("check.voided", buildJsonObject { put("checkId", 3); put("amountCents", 999) }, seq = 4, aggregateId = "3"),
        )
        // the second shop carries the SAME product id: its counts are its own
        ingest(keyShop2,
            event("catalog.snapshot", buildJsonObject {
                put("items", buildJsonArray { add(item("golden-lager-6", "Golden Hour Lager 6-pack", "487230001029")) })
            }, seq = 1, aggregateType = "catalog", aggregateId = "snapshot"),
            event("check.closed", sale(1, "golden-lager-6" to 1), seq = 2, aggregateId = "1"),
        )
    }

    @Test
    fun onHandIsReceivedMinusSoldPlusAdjustments() {
        assertEquals(19L, StockMath.onHand(received = 24, sold = 5, adjusted = 0))
        assertEquals(17L, StockMath.onHand(received = 24, sold = 5, adjusted = -2))
        assertEquals(-3L, StockMath.onHand(received = 0, sold = 3, adjusted = 0)) // sold before a delivery was recorded
        assertTrue(StockMath.low(4, 4))
        assertFalse(StockMath.low(5, 4))
        assertFalse(StockMath.low(-10, null))
    }

    @Test
    fun aStoreSeesItsOwnStockAndCanRecordDeliveriesAndCounts() = testApplication {
        application { module(TestSupport.config) }
        seedShops()
        // sold before anything was received: negative, as the store kept selling
        var lager = get("/v1/stock?venue=sage-poppy").rows().single { it.str("itemId") == "golden-lager-6" }
        assertEquals(5L, lager.long("sold"))
        assertEquals(-5L, lager.long("onHand"))
        assertEquals("487230001029", lager.str("barcode"))

        req(HttpMethod.Post, "/v1/stock/movements?venue=sage-poppy",
            """{"itemId":"golden-lager-6","kind":"RECEIVED","qty":24,"note":"Delivery #1042"}""")
            .let { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }
        req(HttpMethod.Post, "/v1/stock/movements?venue=sage-poppy",
            """{"itemId":"golden-lager-6","kind":"ADJUSTMENT","qty":-2,"note":"Two broken"}""")
            .let { assertEquals(HttpStatusCode.Created, it.status) }
        req(HttpMethod.Put, "/v1/stock/reorder?venue=sage-poppy", """{"itemId":"golden-lager-6","reorderLevel":18}""")
            .let { assertEquals(HttpStatusCode.OK, it.status) }

        val stock = get("/v1/stock?venue=sage-poppy")
        lager = stock.rows().single { it.str("itemId") == "golden-lager-6" }
        assertEquals(24L, lager.long("received"))
        assertEquals(-2L, lager.long("adjusted"))
        assertEquals(24L - 5 - 2, lager.long("onHand")) // 17
        assertEquals(18L, lager.long("reorderLevel"))
        assertTrue(lager["low"]!!.jsonPrimitive.content.toBoolean()) // 17 ≤ 18
        assertEquals(1L, stock.long("lowCount"))
        val ice = stock.rows().single { it.str("itemId") == "ice-7" }
        assertEquals(-1L, ice.long("onHand"))
        assertFalse(ice["low"]!!.jsonPrimitive.content.toBoolean()) // no reorder level set

        val history = get("/v1/stock/movements?venue=sage-poppy&itemId=golden-lager-6")["movements"]!!.jsonArray
        assertEquals(listOf("ADJUSTMENT", "RECEIVED"), history.map { it.jsonObject.str("kind") })
        assertEquals("owner@stock.test", history.first().jsonObject.str("createdBy"))
    }

    @Test
    fun allStoresListsEachRetailStoreSeparatelyAndSkipsRestaurants() = testApplication {
        application { module(TestSupport.config) }
        seedShops()
        req(HttpMethod.Post, "/v1/stock/movements?venue=second-shop",
            """{"itemId":"golden-lager-6","kind":"RECEIVED","qty":12}""")
        val all = get("/v1/stock")
        assertTrue(all["retail"]!!.jsonPrimitive.content.toBoolean())
        val lagers = all.rows().filter { it.str("itemId") == "golden-lager-6" }.associateBy { it.str("venueId") }
        assertEquals(setOf("sage-poppy", "second-shop"), lagers.keys)
        assertEquals(-5L, lagers["sage-poppy"]!!.long("onHand"))
        assertEquals(11L, lagers["second-shop"]!!.long("onHand")) // 12 − 1, not mixed with the other shop
        val byVenue = all["byVenue"]!!.jsonArray.map { it.jsonObject }.associateBy { it.str("venueId") }
        assertEquals(setOf("sage-poppy", "second-shop"), byVenue.keys) // the pub doesn't track stock
        assertEquals(-5L - 1 + 11, all.long("totalOnHand"))

        // recording needs one store, and a retail one
        req(HttpMethod.Post, "/v1/stock/movements", """{"itemId":"golden-lager-6","kind":"RECEIVED","qty":1}""")
            .let { assertEquals(HttpStatusCode.BadRequest, it.status) }
        req(HttpMethod.Post, "/v1/stock/movements?venue=vieux-port", """{"itemId":"x","kind":"RECEIVED","qty":1}""")
            .let { assertEquals(HttpStatusCode.BadRequest, it.status) }
        req(HttpMethod.Post, "/v1/stock/movements?venue=sage-poppy", """{"itemId":"golden-lager-6","kind":"RECEIVED","qty":-3}""")
            .let { assertEquals(HttpStatusCode.BadRequest, it.status) }
        req(HttpMethod.Post, "/v1/stock/movements?venue=sage-poppy", """{"itemId":"nope","kind":"RECEIVED","qty":3}""")
            .let { assertEquals(HttpStatusCode.NotFound, it.status) }

        val pub = get("/v1/stock?venue=vieux-port")
        assertFalse(pub["retail"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(pub.rows().isEmpty())
    }
}
