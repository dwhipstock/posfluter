package dev.dwhipstock.pos

import dev.dwhipstock.pos.api.SNAPSHOT_CHUNK_ITEMS
import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.customers.sagepoppy.SagePoppy
import dev.dwhipstock.pos.customers.sagepoppy.SagePoppyCatalog
import dev.dwhipstock.pos.customers.sagepoppy.SagePoppyCatalogUpgrade
import dev.dwhipstock.pos.customers.sagepoppy.SagePoppySeed
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.db.SyncState
import dev.dwhipstock.pos.db.initDatabase
import dev.dwhipstock.pos.sync.CloudSync
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Sage & Poppy at ~5,000 products, on the store: the seed and the upgrade of
 * an existing store, paged / searched / filtered `GET /items`, the quick keys
 * and top sellers, and the chunked first-sync snapshot.
 */
class BigCatalogStoreTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-big").resolve("pos.db").toString()

    private fun ApplicationTestBuilder.shop() = application {
        module(
            dbPath = tempDb(), receiptsDir = Files.createTempDirectory("rc").toString(),
            venueId = SagePoppy.VENUE_ID, sagePoppy = true, physicalPrinterEnabled = false,
        )
    }

    private suspend fun HttpClient.postJson(path: String, body: String = "{}") =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.items(query: String): Pair<List<JsonObject>, Int> {
        val res = get("/items?$query")
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val list = json.parseToJsonElement(res.bodyAsText()).jsonArray.map { it.jsonObject }
        return list to res.headers["X-Total-Count"]!!.toInt()
    }

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    // ---- the seed ----

    @Test
    fun aNewStoreStartsWithTheWholeShelfQuickly() = testApplication {
        val t0 = System.nanoTime()
        shop()
        val c = loginClient()
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("first boot with ${SagePoppyCatalog.TOTAL} products: $ms ms (seed + migrations + login)")
        val t1 = System.nanoTime()
        val res = c.get("/items")
        val all = json.parseToJsonElement(res.bodyAsText()).jsonArray
        println("GET /items (all ${all.size}): ${(System.nanoTime() - t1) / 1_000_000} ms, ${res.bodyAsText().length / 1024} KB")
        assertEquals(SagePoppyCatalog.TOTAL, all.size)
        val ipa = all.map { it.jsonObject }.first { it.s("id") == "hazy-ipa-4" }
        assertEquals("Hazy IPA", ipa.s("subcategory"))
        assertEquals("4-pack", ipa.s("size"))
        assertEquals("Hazy Hills", ipa.s("brand"))
        // a second boot of the same database adds nothing
        assertEquals(true, SagePoppyCatalogUpgrade.upgradeIfNeeded().alreadyCurrent)
    }

    // ---- paging, search and filters ----

    @Test
    fun itemsPageWithoutGapsOrRepeats() = testApplication {
        shop()
        val c = loginClient()
        val seen = mutableListOf<String>()
        var offset = 0
        var total: Int
        do {
            val (page, t) = c.items("limit=1000&offset=$offset")
            total = t
            seen += page.map { it.s("id")!! }
            offset += 1000
        } while (offset < total)
        assertEquals(SagePoppyCatalog.TOTAL, total)
        assertEquals(total, seen.size)
        assertEquals(total, seen.toSet().size)
        // the pages come in name order
        val (first, _) = c.items("limit=5")
        assertEquals(first.map { it.s("nameEn")!!.lowercase() }.sorted(), first.map { it.s("nameEn")!!.lowercase() })
        assertEquals(HttpStatusCode.BadRequest, c.get("/items?limit=0").status)
        assertEquals(HttpStatusCode.BadRequest, c.get("/items?limit=5000").status)
        assertEquals(HttpStatusCode.BadRequest, c.get("/items?offset=-1").status)
        // past the end: an empty page, same total
        val (empty, t) = c.items("limit=10&offset=99999")
        assertTrue(empty.isEmpty())
        assertEquals(SagePoppyCatalog.TOTAL, t)
    }

    @Test
    fun filtersCombineCategorySubcategoryAndSize() = testApplication {
        shop()
        val c = loginClient()
        val expected = SagePoppyCatalog.products.count { it.cat == SagePoppySeed.Cat.BEER && it.subcategory == "IPA" && it.size == "6-pack" }
        val (hits, total) = c.items("category=beer&subcategory=IPA&size=6-pack")
        assertEquals(expected, total)
        assertEquals(expected, hits.size)
        assertTrue(hits.all { it.s("category") == "beer" && it.s("subcategory") == "IPA" && it.s("size") == "6-pack" })
        // one filter at a time narrows less
        assertTrue(c.items("category=beer").second > c.items("category=beer&subcategory=IPA").second)
        assertTrue(c.items("category=beer&subcategory=IPA").second > total)
        val sixPacks = c.items("size=6-pack").second
        assertTrue(sixPacks > total)
        // a filter that matches nothing is an empty list, not an error
        assertEquals(0, c.items("category=wine&subcategory=IPA").second)
        // filters and paging together
        val (page, pagedTotal) = c.items("category=beer&subcategory=IPA&size=6-pack&limit=3")
        assertEquals(3, page.size)
        assertEquals(expected, pagedTotal)
    }

    @Test
    fun searchFindsWordsSizesAndBarcodesBestFirst() = testApplication {
        shop()
        val c = loginClient()
        // brand words
        val (hazy, _) = c.items("q=hazy%20hills")
        assertEquals(setOf("hazy-ipa-can", "hazy-ipa-4"), hazy.take(2).map { it.s("id") }.toSet())
        // "ipa 6": IPA six-packs (words plus a pack size)
        val (ipa6, n) = c.items("q=ipa%206")
        assertTrue(n >= 5, "ipa 6 → $n")
        assertTrue(ipa6.all { "ipa" in it.s("nameEn")!!.lowercase() && it["packUnits"]!!.jsonPrimitive.int == 6 },
            ipa6.take(3).map { it.s("nameEn") }.toString())
        // "6pk" is the same as "6-pack"
        assertEquals(n, c.items("q=ipa%206pk").second)
        // a size in ml
        val (vodka, _) = c.items("q=vodka%201.75")
        assertTrue(vodka.isNotEmpty() && vodka.all { it.s("size") == "1.75 L" && "vodka" in it.s("nameEn")!!.lowercase() })
        // prefixes as you type
        assertTrue(c.items("q=cab%20sauv").first.first().s("subcategory") == "Cabernet Sauvignon")
        // barcode digits (4+) find the product
        val code = SagePoppySeed.products.first { it.id == "coastal-cab" }.barcode
        assertEquals("coastal-cab", c.items("q=${code.takeLast(6)}").first.first().s("id"))
        // accents fold: "rose" finds Rosé
        assertTrue(c.items("q=rose").first.any { it.s("subcategory") == "Rosé" })
        // nothing silly: a word that is in no product
        assertEquals(0, c.items("q=zzzq").second)
        // search inside a category
        assertTrue(c.items("q=lager&category=beer").first.all { it.s("category") == "beer" })
    }

    // ---- quick keys and top sellers ----

    private suspend fun HttpClient.sell(barcode: String, times: Int) {
        repeat(times) {
            val sale = json.parseToJsonElement(postJson("/retail/sales").bodyAsText()).jsonObject["id"]!!.jsonPrimitive.int
            val check = json.parseToJsonElement(postJson("/retail/sales/$sale/scan", """{"barcode":"$barcode"}""").bodyAsText()).jsonObject
            if (check["ageCheckRequired"]!!.jsonPrimitive.content.toBoolean()) {
                postJson("/retail/sales/$sale/age-check", """{"method":"MANUAL","dateOfBirth":"1980-01-01","cashierSawId":true}""")
            }
            val total = check["grandTotalCents"]!!.jsonPrimitive.int
            postJson("/checks/$sale/tenders/initiate", """{"type":"CARD","amountCents":$total}""")
            postJson("/checks/$sale/tenders/confirm", """{"type":"CARD","amountCents":$total}""")
            val fin = post("/checks/$sale/finalize")
            assertEquals(HttpStatusCode.OK, fin.status, fin.bodyAsText())
        }
    }

    private suspend fun HttpClient.keys(): List<JsonObject> =
        json.parseToJsonElement(get("/retail/quick-keys").bodyAsText()).jsonObject["keys"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun quickKeysRankRecentSalesKeepPinsAndIncludeTheUnscannables() = testApplication {
        shop()
        val m = loginClient()
        m.postJson("/shifts", """{"openingFloatCents":20000,"managerPin":"1234"}""")
        // a brand-new store: no sales yet, so the catalog's popularity fills the grid
        val cold = m.keys()
        assertEquals(36, cold.size)
        assertTrue(cold.any { it.s("itemId") == "sp-paper-bag" && it.s("source") == "unscannable" }, cold.toString())
        assertTrue(cold.filter { it.s("source") != "unscannable" }.all { it.s("source") == "popular" })
        // a slow product that suddenly sells jumps to the top of the auto-filled keys
        val slow = SagePoppyCatalog.products.filter { !it.barcodeless }.minBy { it.salesWeight }
        assertTrue(cold.none { it.s("itemId") == slow.id })
        m.sell(slow.barcode, 3)
        val warm = m.keys()
        val auto = warm.filter { it.s("source") == "velocity" }
        assertEquals(slow.id, auto.first().s("itemId"))
        assertEquals(3, auto.first()["units"]!!.jsonPrimitive.int)
        // a pin goes first and stays through refreshes
        val pinned = SagePoppyCatalog.products.filter { !it.barcodeless }.sortedBy { it.salesWeight }[1]
        val pinRes = m.postJson("/retail/quick-keys/pins", """{"itemId":"${pinned.id}"}""")
        assertEquals(HttpStatusCode.OK, pinRes.status, pinRes.bodyAsText())
        repeat(2) {
            val k = m.keys()
            assertEquals(pinned.id, k.first().s("itemId"))
            assertEquals(true, k.first()["pinned"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(36, k.size)
        }
        // pinning is a menu edit: a cashier needs a manager's PIN
        val cashier = loginClient("9999")
        assertEquals(HttpStatusCode.Forbidden, cashier.postJson("/retail/quick-keys/pins", """{"itemId":"ice-7"}""").status)
        assertEquals(HttpStatusCode.OK, cashier.postJson("/retail/quick-keys/pins", """{"itemId":"ice-7","managerPin":"1234"}""").status)
        assertEquals(HttpStatusCode.NotFound, m.postJson("/retail/quick-keys/pins", """{"itemId":"no-such"}""").status)
        // unpin: back to auto-fill (a product nobody buys drops off)
        m.postJson("/retail/quick-keys/unpin", """{"itemId":"${pinned.id}"}""")
        assertTrue(m.keys().none { it.s("itemId") == pinned.id })
        assertTrue(m.keys().first().s("itemId") == "ice-7")
    }

    @Test
    fun topSellersAreTheRankedTopFifthOfTheCatalog() = testApplication {
        shop()
        val m = loginClient()
        m.postJson("/shifts", """{"openingFloatCents":20000,"managerPin":"1234"}""")
        val slow = SagePoppyCatalog.products.filter { !it.barcodeless && !it.ageRestricted }.minBy { it.salesWeight }
        m.sell(slow.barcode, 2)
        val view = json.parseToJsonElement(m.get("/retail/top-sellers").bodyAsText()).jsonObject
        val items = view["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(SagePoppyCatalog.TOTAL, view["catalogSize"]!!.jsonPrimitive.int)
        assertEquals(SagePoppyCatalog.TOTAL / 5, items.size)
        assertEquals(slow.id, items.first().s("itemId"))
        assertEquals((1..items.size).toList(), items.map { it["rank"]!!.jsonPrimitive.int })
        // after the sellers, the popularity order
        val weights = SagePoppyCatalog.products.associate { it.id to it.salesWeight }
        val rest = items.drop(1).map { weights.getValue(it.s("itemId")!!) }
        assertEquals(rest.sortedDescending(), rest)
    }

    // ---- an existing store gets the new shelf ----

    /** A Sage & Poppy database as it was before the big catalog: the ~50 products only. */
    private fun oldStore(synced: Boolean) {
        initDatabase(tempDb())
        SagePoppySeed.seedIfEmpty()
        transaction {
            val generated = SagePoppyCatalog.generated.map { it.id }.toSet()
            ItemVariants.deleteWhere { ItemVariants.itemId inList generated }
            Items.deleteWhere { Items.id inList generated }
            Items.update { it[brand] = null; it[subcategory] = null; it[sizeLabel] = null; it[salesWeight] = 0 }
            exec("DELETE FROM categories WHERE id = 'sundries'")
            SyncState.set(SagePoppyCatalogUpgrade.STATE_KEY, "0")
            if (synced) SyncState.set(CloudSync.CATALOG_SNAPSHOT_SEQ, "1")
            exec("DELETE FROM sync_outbox")
        }
    }

    @Test
    fun anExistingStoreGetsTheNewProductsWithoutTouchingManagerEdits() {
        oldStore(synced = true)
        val taken = SagePoppyCatalog.generated.first { !it.barcodeless }
        transaction {
            // a manager renamed and repriced a first-boot product …
            Items.update({ Items.id eq "golden-lager-6" }) { it[nameEn] = "Golden Hour Lager 6pk (house favourite)" }
            ItemVariants.update({ ItemVariants.id eq "golden-lager-6:each" }) { it[priceCents] = 1049 }
            // … took a product off sale …
            Items.update({ Items.id eq "night-owl-stout" }) { it[active] = false }
            // … and added one at the counter, under a code the new catalog also uses
            Items.insert {
                it[id] = "p-local-kombucha"; it[nameFr] = "Local Kombucha"; it[nameEn] = "Local Kombucha"
                it[categoryId] = "mixers"; it[abbrev] = "LK"; it[barcode] = taken.barcode
            }
            ItemVariants.insert {
                it[id] = "p-local-kombucha:each"; it[itemId] = "p-local-kombucha"; it[labelFr] = "Each"
                it[labelEn] = "Each"; it[priceCents] = 499
            }
        }
        val t0 = System.nanoTime()
        val r = SagePoppyCatalogUpgrade.upgradeIfNeeded()
        println("upgrade of an existing store: ${r.added} products in ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertEquals(SagePoppyCatalog.generated.size - 1, r.added)
        assertEquals(1, r.skippedBarcodeTaken)
        transaction {
            val golden = Items.selectAll().where { Items.id eq "golden-lager-6" }.single()
            assertEquals("Golden Hour Lager 6pk (house favourite)", golden[Items.nameEn])
            assertEquals("Lager", golden[Items.subcategory]) // gained its facets
            assertEquals("6-pack", golden[Items.sizeLabel])
            assertTrue(golden[Items.salesWeight] > 0)
            assertEquals(1049L, ItemVariants.selectAll().where { ItemVariants.id eq "golden-lager-6:each" }.single()[ItemVariants.priceCents])
            assertFalse(Items.selectAll().where { Items.id eq "night-owl-stout" }.single()[Items.active])
            val mine = Items.selectAll().where { Items.id eq "p-local-kombucha" }.single()
            assertEquals("Local Kombucha", mine[Items.nameEn])
            assertEquals(null, mine[Items.subcategory])
            assertEquals(taken.barcode, mine[Items.barcode])
            assertTrue(Items.selectAll().where { Items.id eq taken.id }.empty())
            assertEquals(SagePoppyCatalog.TOTAL, Items.selectAll().count().toInt()) // -1 skipped +1 manager's
            assertEquals(1, exec("SELECT COUNT(*) FROM categories WHERE id='sundries'") { it.next(); it.getInt(1) })
            // the synced store sends the additions up, in small chunks
            val chunks = SyncOutbox.selectAll().where { SyncOutbox.eventType eq "catalog.snapshot" }.map {
                json.parseToJsonElement(it[SyncOutbox.payload]).jsonObject
            }
            assertTrue(chunks.size >= SagePoppyCatalog.TOTAL / SNAPSHOT_CHUNK_ITEMS, "${chunks.size} chunks")
            assertTrue(chunks.all { it["items"]!!.jsonArray.size <= SNAPSHOT_CHUNK_ITEMS })
            assertEquals(r.added + r.facetsFilled, chunks.sumOf { it["items"]!!.jsonArray.size })
            assertTrue("categories" in chunks.first())
            assertTrue(chunks.flatMap { it["items"]!!.jsonArray }.none { it.jsonObject.s("id") == "p-local-kombucha" })
        }
        // once only
        assertTrue(SagePoppyCatalogUpgrade.upgradeIfNeeded().alreadyCurrent)
    }

    @Test
    fun aNeverSyncedStoreUpgradesWithoutQueueingSnapshots() {
        oldStore(synced = false)
        val r = SagePoppyCatalogUpgrade.upgradeIfNeeded()
        assertEquals(SagePoppyCatalog.generated.size, r.added)
        transaction { assertEquals(0L, SyncOutbox.selectAll().where { SyncOutbox.eventType eq "catalog.snapshot" }.count()) }
    }

    @Test
    fun theFirstSyncSnapshotIsChunkedAndPushesInSmallBatches() {
        initDatabase(tempDb())
        SagePoppySeed.seedIfEmpty()
        val t = FakeTransport()
        val t0 = System.nanoTime()
        CloudSync(t, InMemoryPhotoStore()).drainOnce()
        val events = t.batches.flatten()
        val snaps = events.filter { it.eventType == "catalog.snapshot" }
        println("first sync: ${snaps.size} snapshot chunks in ${t.batches.size} pushes, ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertEquals((SagePoppyCatalog.TOTAL + SNAPSHOT_CHUNK_ITEMS - 1) / SNAPSHOT_CHUNK_ITEMS, snaps.size)
        assertEquals(SagePoppyCatalog.TOTAL, snaps.sumOf { it.payload["items"]!!.jsonArray.size })
        assertEquals(snaps.size, snaps.map { it.payload["chunk"]!!.jsonPrimitive.int }.toSet().size)
        // every push stays around a megabyte, however big the shelf
        for (batch in t.batches) {
            val bytes = batch.sumOf { it.payload.toString().length }
            assertTrue(bytes < 1_100_000, "a push of $bytes bytes")
        }
        assertTrue(t.batches.size > 1)
        assertEquals(events.map { it.seq }.sorted(), events.map { it.seq })
    }
}
