package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.MenuFeed
import dev.dwhipstock.poscloud.db.PortalUsers
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
import org.jetbrains.exposed.sql.update
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two-way menu sync, the cloud's side (CONTRACT §10): portal edits are stamped
 * by the cloud's clock and queued per store; store edits are merged field by
 * field; the later write wins either way, deletes included; replays and
 * echoes change nothing.
 */
class MenuTwoWaySyncTest {

    private val key = "store-key-2way"
    private val key2 = "store-key-2way-b"
    private lateinit var session: String
    private var seq = 0L

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("copperlantern")
        seedTenant("copperlantern", "plateau", "Plateau")
        seedStoreKey("copperlantern", "vieux-port", key)
        seedStoreKey("copperlantern", "plateau", key2)
        session = seedSession("copperlantern", seedUser("copperlantern", "owner@test.dev", "password-o"))
        seq = 0
    }

    /** A stamp [deltaMs] from now, written by store node [node]. */
    private fun stamp(deltaMs: Long, node: String = "sstore1") =
        "%013d-%04d-%s".format(System.currentTimeMillis() + deltaMs, 0, node)

    private fun item(
        nameEn: String = "Lantern House Lager", price: Long = 825, active: Boolean = true, deleted: Boolean = false,
        clock: Map<String, String>? = null, variantClock: Map<String, String>? = null,
    ): JsonObject = buildJsonObject {
        put("id", "lantern-lager")
        put("nameFr", "Lager de la Lanterne")
        put("nameEn", nameEn)
        put("descriptionFr", ""); put("descriptionEn", "")
        put("categoryId", "beer")
        put("abbrev", "LL")
        put("isAlcohol", true)
        put("active", active)
        put("deleted", deleted)
        put("names", buildJsonObject { put("es", "Lager de la Linterna") })
        clock?.let { c -> put("clock", buildJsonObject { c.forEach { (k, v) -> put(k, v) } }) }
        put("variants", buildJsonArray {
            add(buildJsonObject {
                put("id", "lantern-lager:pint")
                put("labelFr", "Pinte"); put("labelEn", "Pint")
                put("priceCents", price); put("sortOrder", 0); put("deleted", false)
                put("names", buildJsonObject {})
                variantClock?.let { c -> put("clock", buildJsonObject { c.forEach { (k, v) -> put(k, v) } }) }
            })
            add(buildJsonObject {
                put("id", "lantern-lager:pitcher")
                put("labelFr", "Pichet"); put("labelEn", "Pitcher")
                put("priceCents", 2400); put("sortOrder", 1); put("deleted", false)
                put("names", buildJsonObject {})
            })
        })
    }

    private suspend fun ApplicationTestBuilder.bootstrap(storeKey: String = key) {
        ingest(storeKey, event("catalog.snapshot", buildJsonObject {
            put("categories", buildJsonArray {
                add(buildJsonObject {
                    put("id", "beer"); put("nameFr", "Bière"); put("nameEn", "Beer"); put("sortOrder", 0); put("deleted", false)
                })
            })
            put("items", buildJsonArray { add(item()) })
        }, seq = ++seq))
    }

    private suspend fun ApplicationTestBuilder.pull(storeKey: String = key, since: Long = 0): JsonObject =
        testJson.parseToJsonElement(client.get("/v1/store/menu/changes?since=$since") {
            header(HttpHeaders.Authorization, "Bearer $storeKey")
        }.bodyAsText()).jsonObject

    private suspend fun ApplicationTestBuilder.portal(
        method: HttpMethod, path: String, body: String? = null, idem: String? = null,
    ): HttpResponse = client.request(path) {
        this.method = method
        header(HttpHeaders.Cookie, "pos_portal_session=$session")
        idem?.let { header("Idempotency-Key", it) }
        if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
    }

    private suspend fun ApplicationTestBuilder.menuItem(venue: String = "vieux-port"): JsonObject? =
        testJson.parseToJsonElement(getWithCookie("/v1/menu?venue=$venue", session).bodyAsText())
            .jsonObject["items"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it["id"]!!.jsonPrimitive.content == "lantern-lager" }

    private fun JsonObject.price() = this["variants"]!!.jsonArray[0].jsonObject["priceCents"]!!.jsonPrimitive.content.toLong()
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun feedCount(venue: String = "vieux-port") = transaction {
        MenuFeed.selectAll().where { MenuFeed.venueId eq venue }.count()
    }

    @Test
    fun portalEditIsQueuedForTheStoreWithTheCloudsStamp() = testApplication {
        application { module(TestSupport.config) }
        bootstrap()
        pull() // the store speaks two-way sync from now on
        val before = System.currentTimeMillis()
        val res = portal(HttpMethod.Patch, "/v1/menu/items/lantern-lager/variants/lantern-lager:pint?venue=vieux-port",
            """{"priceCents":875}""")
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        // the portal shows it at once…
        assertEquals(875, menuItem()!!.price())
        // …and the store's feed carries the full item with the cloud's stamp on the price
        val page = pull()
        val change = page["changes"]!!.jsonArray.single().jsonObject
        assertEquals("item", change.str("entity"))
        val pint = change["data"]!!.jsonObject["variants"]!!.jsonArray.first { it.jsonObject.str("id") == "lantern-lager:pint" }.jsonObject
        assertEquals(875, pint.str("priceCents").toLong())
        val priceStamp = pint["clock"]!!.jsonObject.str("priceCents")
        assertTrue(priceStamp.endsWith("-cloud"), priceStamp)
        assertTrue(priceStamp.substringBefore('-').toLong() >= before)
        assertTrue(page["serverTimeMs"]!!.jsonPrimitive.content.toLong() >= before)
        // the cursor moves; asking again from it is empty
        assertEquals(0, pull(since = page["cursor"]!!.jsonPrimitive.content.toLong())["changes"]!!.jsonArray.size)
    }

    @Test
    fun tabletEditReachesThePortal() = testApplication {
        application { module(TestSupport.config) }
        bootstrap()
        ingest(key, event("item.updated", buildJsonObject {
            put("item", item(nameEn = "Lantern Lager Classic", clock = mapOf("nameEn" to stamp(-1000))))
        }, seq = ++seq))
        assertEquals("Lantern Lager Classic", menuItem()!!.str("nameEn"))
        assertEquals(0, feedCount()) // the store already has its own edit: nothing queued back
    }

    @Test
    fun bothEditTheSameFieldTheLaterWriteWinsAndOtherFieldsMerge() = testApplication {
        application { module(TestSupport.config) }
        bootstrap()
        pull()
        // portal sets the price now; the store's OLDER offline price edit arrives later: the portal's stays
        portal(HttpMethod.Patch, "/v1/menu/items/lantern-lager/variants/lantern-lager:pint?venue=vieux-port", """{"priceCents":900}""")
        ingest(key, event("item.variant_updated", buildJsonObject {
            put("item", item(price = 700, nameEn = "Lantern Lager (store)",
                clock = mapOf("nameEn" to stamp(-30_000)), variantClock = mapOf("priceCents" to stamp(-30_000))))
        }, seq = ++seq))
        val merged = menuItem()!!
        assertEquals(900, merged.price()) // newer portal price wins
        assertEquals("Lantern Lager (store)", merged.str("nameEn")) // the store's name (no conflict) lands

        // a NEWER store price beats the portal's
        ingest(key, event("item.variant_updated", buildJsonObject {
            put("item", item(price = 950, nameEn = "Lantern Lager (store)",
                clock = mapOf("nameEn" to stamp(-30_000)), variantClock = mapOf("priceCents" to stamp(30_000))))
        }, seq = ++seq))
        assertEquals(950, menuItem()!!.price())
    }

    @Test
    fun deleteVersusEditTheLaterOneWins() = testApplication {
        application { module(TestSupport.config) }
        bootstrap()
        pull()
        assertEquals(HttpStatusCode.OK, portal(HttpMethod.Delete, "/v1/menu/items/lantern-lager?venue=vieux-port").status)
        assertEquals(null, menuItem()) // gone from the portal menu
        // an OLDER store edit (made offline before the delete) does not bring it back
        ingest(key, event("item.updated", buildJsonObject {
            put("item", item(nameEn = "Old rename", clock = mapOf("nameEn" to stamp(-60_000))))
        }, seq = ++seq))
        assertEquals(null, menuItem())
        // a NEWER store edit does: the item is live again, with the store's change
        ingest(key, event("item.updated", buildJsonObject {
            put("item", item(nameEn = "Kept by the bar", clock = mapOf("nameEn" to stamp(20_000))))
        }, seq = ++seq))
        assertEquals("Kept by the bar", menuItem()!!.str("nameEn"))

        // and the other way round: the store deletes after a portal edit → deleted
        portal(HttpMethod.Patch, "/v1/menu/items/lantern-lager?venue=vieux-port", """{"nameFr":"Lager maison"}""")
        ingest(key, event("item.deleted", buildJsonObject {
            put("item", item(deleted = true, clock = mapOf("nameEn" to stamp(20_000), "deleted" to stamp(40_000))))
        }, seq = ++seq))
        assertEquals(null, menuItem())
    }

    @Test
    fun replaysAndDuplicatesChangeNothing() = testApplication {
        application { module(TestSupport.config) }
        bootstrap()
        pull()
        // a portal retry with the same key: one feed entry, the first answer
        val first = portal(HttpMethod.Patch, "/v1/menu/items/lantern-lager?venue=vieux-port", """{"active":false}""", idem = "edit-1")
        val again = portal(HttpMethod.Patch, "/v1/menu/items/lantern-lager?venue=vieux-port", """{"active":false}""", idem = "edit-1")
        assertEquals(HttpStatusCode.OK, again.status)
        assertTrue("\"duplicate\":true" in again.bodyAsText())
        assertTrue("\"duplicate\":false" in first.bodyAsText())
        assertEquals(1, feedCount())
        // the same store event twice (dedup by event id) and the same state under a new id (merge is idempotent)
        val e = event("item.updated", buildJsonObject {
            put("item", item(nameEn = "Replayed", clock = mapOf("nameEn" to stamp(-500))))
        }, seq = ++seq)
        ingest(key, e)
        val dup = ingest(key, e)
        assertEquals(1, dup["duplicates"]!!.jsonPrimitive.content.toInt())
        ingest(key, e.copy(eventId = java.util.UUID.randomUUID().toString(), seq = ++seq))
        val now = menuItem(); assertTrue(now == null || now.str("nameEn") == "Replayed")
        assertEquals(1, feedCount())
    }

    @Test
    fun theStoresRecordOfApplyingAPortalEditIsNotAnEcho() = testApplication {
        application { module(TestSupport.config) }
        bootstrap()
        pull()
        portal(HttpMethod.Patch, "/v1/menu/items/lantern-lager/variants/lantern-lager:pint?venue=vieux-port", """{"priceCents":990}""")
        // the store applied it; its outbox event is tagged origin cloud (maybe an intermediate state): ignored
        ingest(key, event("item.variant_updated", buildJsonObject {
            put("origin", "cloud")
            put("item", item(price = 825, variantClock = mapOf("priceCents" to stamp(0, "cloud"))))
        }, seq = ++seq))
        assertEquals(990, menuItem()!!.price())
        assertEquals(1, feedCount()) // nothing new goes back down
    }

    @Test
    fun aStoreClockFarAheadIsRestampedAndTheStoreIsTold() = testApplication {
        application { module(TestSupport.config) }
        bootstrap()
        pull()
        ingest(key, event("item.updated", buildJsonObject {
            put("item", item(nameEn = "From the future", clock = mapOf("nameEn" to stamp(3_600_000))))
        }, seq = ++seq))
        assertEquals("From the future", menuItem()!!.str("nameEn"))
        val change = pull()["changes"]!!.jsonArray.single().jsonObject["data"]!!.jsonObject
        assertEquals(true, change["restamp"]!!.jsonPrimitive.content.toBoolean())
        val s = change["clock"]!!.jsonObject.str("nameEn")
        assertTrue(s.endsWith("-cloud") && s.substringBefore('-').toLong() < System.currentTimeMillis() + 60_000, s)
        // a later portal edit still wins over the broken clock's write
        portal(HttpMethod.Patch, "/v1/menu/items/lantern-lager?venue=vieux-port", """{"nameEn":"Fixed"}""")
        assertEquals("Fixed", menuItem()!!.str("nameEn"))
    }

    @Test
    fun anOlderStoreKeepsWorkingOneWay() = testApplication {
        application { module(TestSupport.config) }
        bootstrap()
        // never pulled the feed: portal edits are refused…
        val res = portal(HttpMethod.Patch, "/v1/menu/items/lantern-lager?venue=vieux-port", """{"nameEn":"x"}""")
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertTrue("store_not_upgraded" in res.bodyAsText())
        // …and its clockless snapshots still mirror as before
        ingest(key, event("item.updated", buildJsonObject { put("item", item(nameEn = "Legacy edit", price = 850)) }, seq = ++seq))
        assertEquals("Legacy edit", menuItem()!!.str("nameEn"))
        assertEquals(850, menuItem()!!.price())
        assertEquals(0, feedCount())
    }

    @Test
    fun allStoresEditGoesToEveryUpgradedStoreThatHasTheItem() = testApplication {
        application { module(TestSupport.config) }
        bootstrap(key)
        bootstrap(key2)
        pull(key) // only vieux-port is upgraded
        val res = portal(HttpMethod.Patch, "/v1/menu/items/lantern-lager", """{"descriptionEn":"House lager"}""")
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val body = testJson.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals(listOf("vieux-port"), body["applied"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("store_not_upgraded", body["skipped"]!!.jsonArray.single().jsonObject.str("reason"))
        assertEquals(1, feedCount("vieux-port"))
        assertEquals(0, feedCount("plateau"))
        pull(key2)
        portal(HttpMethod.Patch, "/v1/menu/items/lantern-lager", """{"descriptionEn":"House lager!"}""")
        assertEquals(2, feedCount("vieux-port"))
        assertEquals(1, feedCount("plateau"))
    }

    @Test
    fun createSizesCategoriesAndTheirGuards() = testApplication {
        application { module(TestSupport.config) }
        bootstrap()
        pull()
        val created = portal(HttpMethod.Post, "/v1/menu/items?venue=vieux-port", """{"nameEn":"Nachos","nameFr":"Nachos",
            "names":{"es":"Nachos"},"categoryId":"beer","variants":[{"labelEn":"Regular","priceCents":1200}]}""")
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val id = testJson.parseToJsonElement(created.bodyAsText()).jsonObject.str("id")
        assertTrue(id.startsWith("nachos-"), id) // a fresh id: never collides with a store's own "nachos"
        // its only size can't go
        val last = portal(HttpMethod.Delete, "/v1/menu/items/$id/variants/$id:regular?venue=vieux-port")
        assertEquals(HttpStatusCode.Conflict, last.status)
        assertTrue("last_variant" in last.bodyAsText())
        // a category with items can't be deleted
        val cat = portal(HttpMethod.Delete, "/v1/menu/categories/beer?venue=vieux-port")
        assertEquals(HttpStatusCode.Conflict, cat.status)
        assertTrue("category_not_empty" in cat.bodyAsText())
        // an unknown category is refused
        val bad = portal(HttpMethod.Post, "/v1/menu/items?venue=vieux-port",
            """{"nameEn":"X","categoryId":"nope","variants":[{"labelEn":"R","priceCents":1}]}""")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        // a new empty category can be made, renamed, ordered and deleted
        val c = testJson.parseToJsonElement(portal(HttpMethod.Post, "/v1/menu/categories?venue=vieux-port",
            """{"nameEn":"Snacks","nameFr":"Grignotines"}""").bodyAsText()).jsonObject.str("id")
        assertEquals(HttpStatusCode.OK, portal(HttpMethod.Put, "/v1/menu/categories/order?venue=vieux-port",
            """{"orderedIds":["$c","beer"]}""").status)
        assertEquals(HttpStatusCode.OK, portal(HttpMethod.Patch, "/v1/menu/categories/$c?venue=vieux-port",
            """{"nameEn":"Bar snacks"}""").status)
        assertEquals(HttpStatusCode.OK, portal(HttpMethod.Delete, "/v1/menu/categories/$c?venue=vieux-port").status)
        // every edit went down the feed, in order; each thing once, at its newest full state
        val changes = pull()["changes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("item", "category", "category"), changes.map { it.str("entity") })
        assertEquals(listOf(id, "beer", c), changes.map { it.str("id") })
        assertEquals("true", changes.last()["data"]!!.jsonObject.str("deleted"))
        assertEquals(6L, feedCount()) // the feed itself keeps every edit
    }

    /** An offline store sold an item the portal had deleted meanwhile: the sale counts, under its name as sold. */
    @Test
    fun aSaleOfAnItemDeletedInThePortalStillReports() = testApplication {
        application { module(TestSupport.config) }
        bootstrap()
        pull()
        portal(HttpMethod.Delete, "/v1/menu/items/lantern-lager?venue=vieux-port")
        val at = "2026-07-21T20:00:00.000-04:00"
        ingest(key, event("check.closed", checkClosedPayload(
            1, 1650, 0, closedAt = at,
            lines = buildJsonArray {
                add(buildJsonObject {
                    put("lineId", 1); put("itemId", "lantern-lager"); put("variantId", "lantern-lager:pint")
                    put("categoryId", "beer"); put("nameFr", "Lager de la Lanterne"); put("nameEn", "Lantern House Lager")
                    put("qty", 2); put("unitPriceCents", 825); put("lineTotalCents", 1650)
                })
            },
        ), seq = ++seq, aggregateId = "1", createdAt = at))
        val row = testJson.parseToJsonElement(getWithCookie("/v1/reports/items?from=2026-07-21&to=2026-07-21", session)
            .bodyAsText()).jsonObject["rows"]!!.jsonArray.single().jsonObject
        assertEquals("Lantern House Lager", row.str("nameEn"))
        assertEquals(1650, row.str("revenueCents").toLong())
        assertEquals(null, menuItem()) // and it stays off the menu
    }

    @Test
    fun onlyOwnersAndManagersEdit() = testApplication {
        application { module(TestSupport.config) }
        bootstrap()
        pull()
        val viewer = seedUser("copperlantern", "viewer@test.dev", "password-v")
        transaction { PortalUsers.update({ PortalUsers.id eq viewer }) { it[role] = "viewer" } }
        val viewerSession = seedSession("copperlantern", viewer)
        val res = client.patch("/v1/menu/items/lantern-lager?venue=vieux-port") {
            header(HttpHeaders.Cookie, "pos_portal_session=$viewerSession")
            contentType(ContentType.Application.Json)
            setBody("""{"active":false}""")
        }
        assertEquals(HttpStatusCode.Forbidden, res.status)
        assertEquals(0, feedCount())
        val me = getWithCookie("/v1/auth/me", viewerSession).bodyAsText()
        assertTrue("\"canEditMenu\":false" in me, me)
        val status = getWithCookie("/v1/menu/sync-status?venue=vieux-port", viewerSession).bodyAsText()
        assertTrue("\"canEdit\":false" in status, status)
        assertFalse("\"editable\":false" in status)
    }
}
