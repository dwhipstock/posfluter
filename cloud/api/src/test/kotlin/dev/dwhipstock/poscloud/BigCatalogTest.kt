package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.CatalogItems
import dev.dwhipstock.poscloud.db.CheckLines
import dev.dwhipstock.poscloud.db.Checks
import dev.dwhipstock.poscloud.db.StockLevels
import dev.dwhipstock.poscloud.db.Venues
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
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Before
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A retail store with ~5,000 products: the first-sync catalog arrives in
 * chunks (additive), the new shelf facts (brand, subcategory, size) are
 * mirrored, and the portal's Products / Stock / reorder lists page, search
 * and filter (category → subcategory, plus size) without listing 5k rows.
 */
class BigCatalogTest {

    private val key = "bigcat-key"
    private lateinit var session: String

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("bigcat", "sage-poppy", "Sage & Poppy Bottle Shop")
        transaction {
            Venues.update({ (Venues.tenantId eq "bigcat") and (Venues.id eq "sage-poppy") }) {
                it[kind] = "retail"; it[currency] = "USD"; it[country] = "US"
            }
        }
        seedStoreKey("bigcat", "sage-poppy", key)
        session = seedSession("bigcat", seedUser("bigcat", "owner@bigcat.test", "password-bc"))
    }

    // --- a deterministic 5,000-product fixture ---

    private val beerSubs = listOf("IPA", "Hazy IPA", "Lager", "Pilsner", "Stout")
    private val beerSizes = listOf("12 oz can", "16 oz tallboy", "6-pack", "12-pack", "24-pack")
    private val wineSubs = listOf("Cabernet Sauvignon", "Pinot Noir", "Chardonnay")
    private val wineSizes = listOf("750 ml", "1.5 L", "3 L box")

    private data class P(val id: String, val name: String, val cat: String, val sub: String, val size: String, val brand: String, val barcode: String)

    private val products: List<P> = (0 until 5000).map { n ->
        val beer = n % 2 == 0
        val sub = if (beer) beerSubs[n / 2 % beerSubs.size] else wineSubs[n / 2 % wineSubs.size]
        val size = if (beer) beerSizes[n / 10 % beerSizes.size] else wineSizes[n / 6 % wineSizes.size]
        val brand = "Maker ${n % 40}"
        P("sp${10000 + n}", "$brand $sub $size #$n", if (beer) "beer" else "wine", sub, size, brand,
            "4872301" + (10000 + n).toString().padStart(5, '0'))
    }

    private fun itemJson(p: P, name: String = p.name, photoVersion: Long? = null) = buildJsonObject {
        put("id", p.id); put("nameFr", name); put("nameEn", name); put("categoryId", p.cat)
        put("abbrev", "SP"); put("isAlcohol", true); put("active", true); put("deleted", false)
        put("barcode", p.barcode); put("brand", p.brand); put("subcategory", p.sub); put("size", p.size)
        photoVersion?.let { put("photoVersion", it) }
        put("variants", buildJsonArray {
            add(buildJsonObject {
                put("id", "${p.id}:each"); put("labelFr", "Each"); put("labelEn", "Each")
                put("priceCents", 999 + (p.id.hashCode() and 0xff)); put("sortOrder", 0); put("deleted", false)
            })
        })
    }

    private val categories = buildJsonArray {
        add(buildJsonObject { put("id", "beer"); put("nameFr", "Beer & Cider"); put("nameEn", "Beer & Cider"); put("sortOrder", 0); put("deleted", false) })
        add(buildJsonObject { put("id", "wine"); put("nameFr", "Wine"); put("nameEn", "Wine"); put("sortOrder", 1); put("deleted", false) })
    }

    /** The store's chunked first sync: 20 `catalog.snapshot` events of 250 items, 5 per push. */
    private suspend fun ApplicationTestBuilder.ingestCatalog(): Long {
        val chunks = products.chunked(250)
        val started = System.nanoTime()
        chunks.withIndex().chunked(5).forEach { batch ->
            ingest(key, *batch.map { (i, chunk) ->
                event("catalog.snapshot", buildJsonObject {
                    if (i == 0) put("categories", categories)
                    put("chunk", i + 1); put("chunks", chunks.size)
                    put("items", buildJsonArray { chunk.forEach { add(itemJson(it)) } })
                }, seq = i + 1L, aggregateType = "catalog", aggregateId = "snapshot")
            }.toTypedArray())
        }
        return (System.nanoTime() - started) / 1_000_000
    }

    private suspend fun ApplicationTestBuilder.get(path: String): JsonObject {
        val res = getWithCookie(path, session)
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return testJson.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    private suspend fun ApplicationTestBuilder.timed(path: String): Pair<JsonObject, Long> {
        val started = System.nanoTime()
        val body = get(path)
        return body to (System.nanoTime() - started) / 1_000_000
    }

    private fun JsonObject.int(k: String) = this[k]!!.jsonPrimitive.content.toInt()
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.list(k: String) = this[k]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.facet(k: String) =
        this["facets"]!!.jsonObject[k]!!.jsonArray.associate { it.jsonObject.str("value") to it.jsonObject.int("count") }

    @Test
    fun fiveThousandProductsArriveInChunksAndPageWithFilters() = testApplication {
        application { module(TestSupport.config) }
        val ingestMs = ingestCatalog()
        println("[big-catalog] ingest 5,000 items in 20 chunks: $ingestMs ms")
        assertEquals(5000L, transaction {
            CatalogItems.selectAll().where { CatalogItems.tenantId eq "bigcat" }.count()
        })
        // the new shelf facts are mirrored
        transaction {
            val row = CatalogItems.selectAll().where { (CatalogItems.tenantId eq "bigcat") and (CatalogItems.id eq "sp10000") }.single()
            assertEquals("Maker 0", row[CatalogItems.brand])
            assertEquals("IPA", row[CatalogItems.subcategory])
            assertEquals("12 oz can", row[CatalogItems.sizeLabel])
        }

        // legacy callers: no parameter = the whole menu
        val (full, fullMs) = timed("/v1/menu")
        assertEquals(5000, full.list("items").size)
        assertEquals(5000, full.int("total"))
        assertTrue(full["facets"] == null || full["facets"].toString() == "null")

        // a page
        val (page, pageMs) = timed("/v1/menu?limit=50")
        println("[big-catalog] /v1/menu full: $fullMs ms, page of 50: $pageMs ms")
        assertEquals(50, page.list("items").size)
        assertEquals(5000, page.int("total"))
        // sorted by category order, then name: all beer first
        assertTrue(page.list("items").all { it.str("categoryId") == "beer" })
        assertEquals(mapOf("beer" to 2500, "wine" to 2500), page.facet("categories"))
        val second = get("/v1/menu?limit=50&offset=50").list("items")
        assertTrue(second.map { it.str("id") }.intersect(page.list("items").map { it.str("id") }.toSet()).isEmpty())
        val last = get("/v1/menu?limit=50&offset=4990")
        assertEquals(10, last.list("items").size)
        assertEquals(0, get("/v1/menu?limit=50&offset=6000").list("items").size)

        // 2-way filter: category → subcategory, + size; they combine
        val beer = get("/v1/menu?limit=10&category=beer")
        assertEquals(2500, beer.int("total"))
        assertEquals(beerSubs.toSet(), beer.facet("subcategories").keys)
        assertEquals(beerSizes.toSet(), beer.facet("sizes").keys)
        val ipa = get("/v1/menu?limit=10&category=beer&subcategory=IPA")
        assertEquals(500, ipa.int("total"))
        assertEquals(beerSizes.toSet(), ipa.facet("sizes").keys) // sizes present among IPAs
        val ipa6 = get("/v1/menu?limit=500&category=beer&subcategory=IPA&size=6-pack")
        val expected = products.count { it.cat == "beer" && it.sub == "IPA" && it.size == "6-pack" }
        assertEquals(expected, ipa6.int("total"))
        assertTrue(ipa6.list("items").all { it.str("subcategory") == "IPA" && it.str("size") == "6-pack" })
        // a wine subcategory under beer: nothing
        assertEquals(0, get("/v1/menu?limit=10&category=beer&subcategory=Pinot%20Noir").int("total"))

        // search: every word (name, brand, subcategory, size), barcode digits
        // a word matches the start of a word: "2" finds brand "Maker 2" (also "Maker 2x" and "#2…")
        val maker2 = get("/v1/menu?limit=5000&q=maker%202%20ipa").list("items")
        val ids = maker2.map { it.str("id") }.toSet()
        val wanted = products.filter { it.brand == "Maker 2" && "IPA" in it.sub }.map { it.id }
        assertTrue(wanted.isNotEmpty() && ids.containsAll(wanted))
        assertTrue(maker2.all { "IPA" in it.str("subcategory") })
        // "ipa 6-pack" style: words plus a size
        val ipaSix = get("/v1/menu?limit=5000&q=hazy%20ipa%206-pack").list("items")
        assertTrue(ipaSix.map { it.str("id") }.containsAll(
            products.filter { it.sub == "Hazy IPA" && it.size == "6-pack" }.map { it.id }))
        assertTrue(ipaSix.all { it.str("subcategory") == "Hazy IPA" && "pack" in it.str("size") })
        val byCode = get("/v1/menu?limit=5&q=${products[1234].barcode.takeLast(6)}")
        assertEquals(listOf(products[1234].id), byCode.list("items").map { it.str("id") })
        assertEquals(0, get("/v1/menu?limit=5&q=nothing-like-this").int("total"))

        // export: every matching row in one call
        val (export, exportMs) = timed("/v1/menu?limit=10000")
        println("[big-catalog] /v1/menu export (5,000 rows): $exportMs ms")
        assertEquals(5000, export.list("items").size)

        // bad paging is a 400, not a 500
        assertEquals(HttpStatusCode.BadRequest, getWithCookie("/v1/menu?limit=0", session).status)
        assertEquals(HttpStatusCode.BadRequest, getWithCookie("/v1/menu?limit=abc", session).status)
    }

    @Test
    fun chunksAndLaterEditsAreAdditive() = testApplication {
        application { module(TestSupport.config) }
        val a = products[0]
        val b = products[1]
        ingest(key, event("catalog.snapshot", buildJsonObject {
            put("categories", categories)
            put("items", buildJsonArray { add(itemJson(a, photoVersion = 42)) })
        }, seq = 1))
        // a later chunk without [a] leaves it alone
        ingest(key, event("catalog.snapshot", buildJsonObject {
            put("items", buildJsonArray { add(itemJson(b)) })
        }, seq = 2))
        // an item edit without a photo hint keeps the stored photo version
        ingest(key, event("item.updated", buildJsonObject { put("item", itemJson(a, name = "Renamed")) }, seq = 3))
        // a snapshot listing a product twice keeps the last copy (no upsert conflict)
        ingest(key, event("catalog.snapshot", buildJsonObject {
            put("items", buildJsonArray { add(itemJson(b, name = "First")); add(itemJson(b, name = "Second")) })
        }, seq = 4))
        val items = get("/v1/menu").list("items").associateBy { it.str("id") }
        assertEquals(setOf(a.id, b.id), items.keys)
        assertEquals("Renamed", items.getValue(a.id).str("nameEn"))
        assertEquals("42", items.getValue(a.id).str("photoVersion"))
        assertEquals("Second", items.getValue(b.id).str("nameEn"))
        // a pub item without the new facts: nulls, nothing breaks
        ingest(key, event("item.updated", buildJsonObject {
            put("item", buildJsonObject {
                put("id", "plain"); put("nameFr", "Plain"); put("nameEn", "Plain"); put("categoryId", "beer")
                put("variants", buildJsonArray {})
            })
        }, seq = 5))
        val plain = get("/v1/menu?limit=10&q=plain").list("items").single()
        assertTrue(plain["subcategory"] == null || plain["subcategory"].toString() == "null")
        transaction {
            assertNull(CatalogItems.selectAll().where { CatalogItems.id eq "plain" }.single()[CatalogItems.brand])
        }
    }

    @Test
    fun stockAndReorderPageWithFiltersAtFiveThousandProducts() = testApplication {
        application { module(TestSupport.config) }
        ingestCatalog()
        // ~2 months of sales: 61 days × 40 sales × 3 lines, the long tail rarely sold
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val rng = java.util.Random(7)
        val sales = (0 until 61 * 40).map { n -> n + 1 to now.minusDays((n / 40).toLong()).minusMinutes((n % 40) * 13L) }
        transaction {
            Checks.batchInsert(sales, shouldReturnGeneratedValues = false) { (id, at) ->
                this[Checks.tenantId] = "bigcat"; this[Checks.venueId] = "sage-poppy"; this[Checks.checkId] = id
                this[Checks.status] = "CLOSED"; this[Checks.closedAt] = at; this[Checks.grandTotalCents] = 3000
                this[Checks.currency] = "USD"
            }
            val lines = sales.flatMap { (id, _) ->
                (1..3).map { l ->
                    // popular products (low index) far more often
                    val idx = (Math.pow(rng.nextDouble(), 3.0) * products.size).toInt().coerceIn(0, products.size - 1)
                    Triple(id, l, products[idx])
                }
            }
            CheckLines.batchInsert(lines, shouldReturnGeneratedValues = false) { (id, l, p) ->
                this[CheckLines.tenantId] = "bigcat"; this[CheckLines.venueId] = "sage-poppy"
                this[CheckLines.checkId] = id; this[CheckLines.lineId] = l.toLong(); this[CheckLines.itemId] = p.id
                this[CheckLines.variantId] = "${p.id}:each"; this[CheckLines.qty] = 1 + (l % 2)
                this[CheckLines.unitPriceCents] = 999; this[CheckLines.lineTotalCents] = 999
            }
            StockLevels.batchInsert(products.take(300), shouldReturnGeneratedValues = false) { p ->
                this[StockLevels.tenantId] = "bigcat"; this[StockLevels.venueId] = "sage-poppy"
                this[StockLevels.itemId] = p.id; this[StockLevels.reorderLevel] = 2; this[StockLevels.updatedAt] = now
            }
        }
        // a live database has planner statistics (autovacuum); a bulk insert a
        // moment ago doesn't, and the planner would guess 1 row per table
        transaction { exec("ANALYZE checks, check_lines, catalog_items, stock_movements, stock_levels") }

        val (full, fullMs) = timed("/v1/stock?venue=sage-poppy")
        assertEquals(5000, full.list("rows").size)
        assertEquals(5000, full.int("total"))
        val (page, pageMs) = timed("/v1/stock?venue=sage-poppy&limit=50")
        println("[big-catalog] /v1/stock full: $fullMs ms, page of 50: $pageMs ms")
        assertEquals(50, page.list("rows").size)
        assertEquals(5000, page.int("total"))
        // the KPIs stay the store's whole figures
        assertEquals(full.int("lowCount"), page.int("lowCount"))
        assertEquals(full.str("totalOnHand"), page.str("totalOnHand"))
        // low first, then the filters
        val low = get("/v1/stock?venue=sage-poppy&limit=1000&low=true")
        assertEquals(full.int("lowCount"), low.int("total"))
        assertTrue(low.list("rows").all { it.str("low") == "true" })
        val wine = get("/v1/stock?venue=sage-poppy&limit=20&category=wine&subcategory=Pinot%20Noir&size=750%20ml")
        assertEquals(products.count { it.cat == "wine" && it.sub == "Pinot Noir" && it.size == "750 ml" }, wine.int("total"))
        assertEquals(wineSubs.toSet(), get("/v1/stock?venue=sage-poppy&limit=1&category=wine").facet("subcategories").keys)
        assertEquals(1, get("/v1/stock?venue=sage-poppy&limit=5&q=${products[42].barcode}").int("total"))

        val (reorder, reorderMs) = timed("/v1/stock/reorder-suggestions?venue=sage-poppy&days=28&cover=14")
        println("[big-catalog] reorder suggestions (5,000 products, ${sales.size} sales): $reorderMs ms")
        assertEquals(5000, reorder.list("rows").size)
        val toOrder = reorder.int("toOrder")
        assertTrue(toOrder > 0)
        val (paged, pagedMs) = timed("/v1/stock/reorder-suggestions?venue=sage-poppy&only=to-order&limit=50")
        println("[big-catalog] reorder page of 50 (to order only): $pagedMs ms")
        assertEquals(toOrder, paged.int("total"))
        assertEquals(50, paged.list("rows").size)
        assertTrue(paged.list("rows").all { it.str("suggested").toLong() > 0 })
        // the same filters on the reorder list
        val beerIpa = get("/v1/stock/reorder-suggestions?venue=sage-poppy&limit=5000&category=beer&subcategory=IPA")
        assertTrue(beerIpa.list("rows").all { it.str("subcategory") == "IPA" })

        // recording one product's delivery reads only that product
        val res = client.post("/v1/stock/movements?venue=sage-poppy") {
            header(HttpHeaders.Cookie, "pos_portal_session=$session")
            contentType(ContentType.Application.Json)
            setBody("""{"itemId":"${products[4999].id}","kind":"RECEIVED","qty":12}""")
        }
        assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
        val row = testJson.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals(products[4999].id, row.str("itemId"))
        assertEquals(products[4999].sub, row.str("subcategory"))
        assertEquals(12L, row.str("received").toLong())
    }
}
