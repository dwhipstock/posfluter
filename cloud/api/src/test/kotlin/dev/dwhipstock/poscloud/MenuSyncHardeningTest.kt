package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.catalog.Scope
import dev.dwhipstock.poscloud.db.CatalogCategories
import dev.dwhipstock.poscloud.menu.CloudHlc
import dev.dwhipstock.poscloud.menu.Hlc
import dev.dwhipstock.poscloud.menu.MenuFields
import dev.dwhipstock.poscloud.menu.MenuState
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two-way menu sync after the red-team pass (scripts/e2e/redteam_sync.py),
 * the cloud's side: a losing store write is corrected through the feed, the
 * feed restarts after a restore/reset, a store push never overwrites a portal
 * edit committing at the same time, the category order is one value, and
 * the clock never ties.
 */
class MenuSyncHardeningTest {

    private val key = "store-key-hardening"
    private lateinit var session: String
    private var seq = 0L
    private val scope = Scope("copperlantern", "vieux-port")

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

    private fun item(descriptionEn: String = "", clock: Map<String, String>? = null, names: Map<String, String> = emptyMap()) =
        buildJsonObject {
            put("id", "lantern-lager")
            put("nameFr", "Lager"); put("nameEn", "Lager")
            put("descriptionFr", ""); put("descriptionEn", descriptionEn)
            put("categoryId", "beer"); put("abbrev", "LL"); put("isAlcohol", true)
            put("active", true); put("deleted", false)
            put("names", buildJsonObject { names.forEach { (k, v) -> put(k, v) } })
            clock?.let { c -> put("clock", buildJsonObject { c.forEach { (k, v) -> put(k, v) } }) }
            put("variants", buildJsonArray {
                add(buildJsonObject {
                    put("id", "lantern-lager:pint"); put("labelFr", "Pinte"); put("labelEn", "Pint")
                    put("priceCents", 825); put("sortOrder", 0); put("deleted", false); put("names", buildJsonObject {})
                })
            })
        }

    private fun category(id: String, sort: Int, clock: Map<String, String>? = null) = buildJsonObject {
        put("id", id); put("nameFr", id); put("nameEn", id); put("sortOrder", sort); put("deleted", false)
        clock?.let { c -> put("clock", buildJsonObject { c.forEach { (k, v) -> put(k, v) } }) }
    }

    private suspend fun ApplicationTestBuilder.bootstrap() {
        ingest(key, event("catalog.snapshot", buildJsonObject {
            put("categories", buildJsonArray { add(category("beer", 0)); add(category("wine", 1)); add(category("food", 2)) })
            put("items", buildJsonArray { add(item()) })
        }, seq = ++seq))
    }

    private suspend fun ApplicationTestBuilder.pull(query: String = "since=0"): JsonObject =
        testJson.parseToJsonElement(client.get("/v1/store/menu/changes?$query") {
            header(HttpHeaders.Authorization, "Bearer $key")
        }.bodyAsText()).jsonObject

    private suspend fun ApplicationTestBuilder.portal(method: HttpMethod, path: String, body: String? = null): HttpResponse =
        client.request(path) {
            this.method = method
            header(HttpHeaders.Cookie, "pos_portal_session=$session")
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }

    private suspend fun ApplicationTestBuilder.syncStatus(): JsonObject =
        testJson.parseToJsonElement(getWithCookie("/v1/menu/sync-status?venue=vieux-port", session).bodyAsText())
            .jsonObject["stores"]!!.jsonArray.single().jsonObject

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.changes() = this["changes"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun aStoreWriteThatLosesIsCorrectedThroughTheFeed() = testApplication {
        application { module(TestSupport.config) }
        bootstrap()
        val cursor = pull().str("cursor")
        assertEquals(HttpStatusCode.OK, portal(HttpMethod.Patch, "/v1/menu/items/lantern-lager?venue=vieux-port",
            """{"descriptionEn":"Portal text"}""").status)
        val afterPortal = pull("since=$cursor").str("cursor")
        // a store whose clock went back an hour pushes an OLDER write of the same field
        ingest(key, event("item.updated", buildJsonObject {
            put("item", item(descriptionEn = "Tablet text", clock = mapOf("descriptionEn" to stamp(-3_600_000))))
        }, seq = ++seq))
        // the portal's text stays, and the store is told the winner (or it would keep its own forever)
        val fix = pull("since=$afterPortal").changes().single()
        assertEquals("lantern-lager", fix.str("id"))
        assertEquals("Portal text", fix["data"]!!.jsonObject.str("descriptionEn"))
        // a store write that wins (or ties the value) is not corrected
        ingest(key, event("item.updated", buildJsonObject {
            put("item", item(descriptionEn = "Tablet later", clock = mapOf("descriptionEn" to stamp(1_000))))
        }, seq = ++seq))
        assertEquals(1, pull("since=$afterPortal").changes().size)
    }

    @Test
    fun aFeedFromAnotherEpochOrPastTheNewestEntryStartsOver() = testApplication {
        application { module(TestSupport.config) }
        bootstrap()
        pull()
        portal(HttpMethod.Patch, "/v1/menu/items/lantern-lager?venue=vieux-port", """{"descriptionEn":"one"}""")
        val first = pull("since=0")
        val epoch = first.str("epoch")
        val newest = first.str("cursor").toLong()
        assertTrue(epoch.isNotBlank())
        // the same epoch, caught up: nothing new
        assertEquals(0, pull("since=$newest&epoch=$epoch").changes().size)
        // a cursor past the newest entry (the feed went back: a restore): served from the start
        val ahead = pull("since=${newest + 50}&epoch=$epoch")
        assertEquals(1, ahead.changes().size)
        // another epoch (a restored / reset database): from the start as well
        val other = pull("since=$newest&epoch=0-somethingelse")
        assertEquals(1, other.changes().size)
        assertEquals(epoch, other.str("epoch"))
        // and the portal's waiting count is not fooled: the store is at 0 now
        pull("since=$newest&epoch=0-somethingelse&failed=2")
        val status = syncStatus()
        assertEquals(1, status.str("pending").toLong())
        assertEquals(2, status.str("failed").toInt())
        // a reset that empties the epoch table gets a new epoch
        transaction { exec("DELETE FROM menu_feed_epoch") }
        assertTrue(pull("since=0").str("epoch") != epoch)
    }

    @Test
    fun aStorePushWaitsForAPortalEditCommittingMeanwhile() = testApplication {
        application { module(TestSupport.config) }
        bootstrap()
        val locked = CountDownLatch(1)
        // a portal-style edit holds the menu lock, writes a new name, and commits a bit later
        val portal = Thread {
            transaction {
                CloudHlc.lock(scope.tenantId)
                val st = MenuState.loadItems(scope, listOf("lantern-lager")).getValue("lantern-lager")
                st.item.set(MenuFields.NAMES + "rtaa", JsonPrimitive("portal name"), CloudHlc.now(scope.tenantId))
                MenuState.saveItems(scope, listOf(st))
                locked.countDown()
                Thread.sleep(700)
            }
        }
        portal.start()
        locked.await()
        // the store pushes the same item (it doesn't know the new name yet) while the edit is in flight
        withContext(Dispatchers.IO) {
            ingest(key, event("item.updated", buildJsonObject {
                put("item", item(descriptionEn = "store text", clock = mapOf("descriptionEn" to stamp(0))))
            }, seq = ++seq))
        }
        portal.join()
        val names = transaction {
            dev.dwhipstock.poscloud.db.CatalogNames.selectAll().where {
                dev.dwhipstock.poscloud.db.CatalogNames.entityId eq "lantern-lager"
            }.map { it[dev.dwhipstock.poscloud.db.CatalogNames.lang] }
        }
        assertTrue("rtaa" in names, "the portal's name was overwritten by the store push: $names")
    }

    @Test
    fun aPortalReorderStampsTheWholeOrder() = testApplication {
        application { module(TestSupport.config) }
        bootstrap()
        pull()
        // only the first two swap; the third keeps its place but is part of the order too
        assertEquals(HttpStatusCode.OK, portal(HttpMethod.Put, "/v1/menu/categories/order?venue=vieux-port",
            """{"orderedIds":["wine","beer","food"]}""").status)
        val stamps = transaction {
            CatalogCategories.selectAll().map { row ->
                testJson.parseToJsonElement(row[CatalogCategories.clock]).jsonObject["sortOrder"]?.jsonPrimitive?.content
            }
        }
        assertEquals(3, stamps.size)
        assertEquals(1, stamps.toSet().size, stamps.toString())
        // an older tablet reorder then loses whole (no mix of the two orders)
        ingest(key, event("categories.reordered", buildJsonObject {
            put("categories", buildJsonArray {
                add(category("food", 0, mapOf("sortOrder" to stamp(-60_000))))
                add(category("beer", 1, mapOf("sortOrder" to stamp(-60_000))))
                add(category("wine", 2, mapOf("sortOrder" to stamp(-60_000))))
            })
        }, seq = ++seq))
        val order = transaction {
            CatalogCategories.selectAll().orderBy(CatalogCategories.sortOrder).map { it[CatalogCategories.id] }
        }
        assertEquals(listOf("wine", "beer", "food"), order)
    }

    @Test
    fun theClockCounterNeverTies() {
        val a = Hlc.of(1_000, 9_999, "cloud")
        val b = Hlc.next(1_000, 9_999, "cloud")
        assertTrue(b > a, "$a then $b")
        assertEquals(Hlc.of(1_001, 0, "cloud"), b)
        assertTrue(Hlc.next(1_000, 5, "cloud") > Hlc.of(1_000, 5, "cloud"))
    }
}
