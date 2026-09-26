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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stock counted and received in the store (stock.counted / stock.received),
 * and refunds putting stock back: a count sets on hand as of its count time,
 * so sales after it still subtract and sales before it don't; deliveries add;
 * by-line refunds restock (older stores' refunds too); replays never double
 * count; reorder suggestions; the store's on-hand feed; old payloads.
 */
class StoreStockTest {

    private val keyShop = "store-stock-shop"
    private val keyPub = "store-stock-pub"
    private lateinit var session: String

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("ss", "sage-poppy", "Sage & Poppy Bottle Shop")
        seedTenant("ss", "vieux-port", "Copper Lantern — Vieux-Port")
        transaction {
            Venues.update({ (Venues.tenantId eq "ss") and (Venues.id eq "sage-poppy") }) {
                it[kind] = "retail"; it[currency] = "USD"; it[country] = "US"; it[timezone] = "America/Los_Angeles"
            }
        }
        seedStoreKey("ss", "sage-poppy", keyShop)
        seedStoreKey("ss", "vieux-port", keyPub)
        session = seedSession("ss", seedUser("ss", "owner@ss.test", "password-ss"))
    }

    private var seq = 0L
    private fun next() = ++seq

    private suspend fun ApplicationTestBuilder.get(path: String): JsonObject {
        val res = getWithCookie(path, session)
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return testJson.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    private fun JsonObject.long(k: String) = this[k]!!.jsonPrimitive.content.toLong()
    private fun JsonObject.longOrNull(k: String) = this[k]?.jsonPrimitive?.content?.toLongOrNull()
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.rows(key: String = "rows") = this[key]!!.jsonArray.map { it.jsonObject }
    private suspend fun ApplicationTestBuilder.lager() =
        get("/v1/stock?venue=sage-poppy").rows().single { it.str("itemId") == "golden-lager-6" }

    private fun item(id: String) = buildJsonObject {
        put("id", id); put("nameFr", id); put("nameEn", id); put("categoryId", "beer")
        put("isAlcohol", true); put("active", true); put("deleted", false)
        put("variants", buildJsonArray {
            add(buildJsonObject { put("id", "$id:each"); put("labelFr", "Each"); put("labelEn", "Each"); put("priceCents", 999) })
        })
    }

    private fun catalog() = event("catalog.snapshot", buildJsonObject {
        put("items", buildJsonArray { add(item("golden-lager-6")); add(item("ice-7")) })
    }, seq = next(), aggregateType = "catalog", aggregateId = "snapshot")

    private fun sale(checkId: Int, closedAt: String, vararg lines: Pair<String, Int>) = event("check.closed", buildJsonObject {
        put("currency", "USD"); put("checkId", checkId); put("grandTotalCents", 1000); put("taxIncludedCents", 0)
        put("closedAt", closedAt)
        put("lines", buildJsonArray {
            lines.forEachIndexed { i, (id, qty) ->
                add(buildJsonObject {
                    put("lineId", checkId * 10 + i); put("itemId", id); put("variantId", "$id:each"); put("nameEn", id)
                    put("qty", qty); put("unitPriceCents", 999); put("lineTotalCents", 999L * qty)
                })
            }
        })
    }, seq = next(), aggregateId = "$checkId")

    private fun counted(countId: String, submittedAt: String, vararg lines: Triple<String, Int, String>, eventId: String? = null) =
        event("stock.counted", buildJsonObject {
            put("countId", countId); put("name", "Friday count"); put("submittedBy", "cashier")
            put("submittedByName", "Demo Cashier"); put("approvedBy", "manager"); put("submittedAt", submittedAt)
            put("startedAt", submittedAt)
            put("lines", buildJsonArray {
                lines.forEach { (id, qty, at) -> add(buildJsonObject { put("itemId", id); put("countedQty", qty); put("countedAt", at) }) }
            })
        }, seq = next(), eventId = eventId ?: java.util.UUID.randomUUID().toString(),
            aggregateType = "stock_count", aggregateId = countId)

    private fun received(receiptId: String, at: String, vararg lines: Pair<String, Int>) = event("stock.received", buildJsonObject {
        put("receiptId", receiptId); put("supplier", "Valley Beverage"); put("reference", "INV-1042")
        put("receivedBy", "cashier"); put("receivedByName", "Demo Cashier"); put("receivedAt", at)
        put("lines", buildJsonArray { lines.forEach { (id, qty) -> add(buildJsonObject { put("itemId", id); put("qty", qty) }) } })
    }, seq = next(), aggregateType = "stock_receipt", aggregateId = receiptId)

    @Test
    fun theReorderMathCoversTheDaysAsked() {
        assertEquals(2.0, StockMath.avgDaily(56, 28))
        assertEquals(28L, StockMath.target(2.0, 14))
        assertEquals(18L, StockMath.suggested(2.0, 14, onHand = 10))
        assertEquals(0L, StockMath.suggested(2.0, 14, onHand = 40)) // plenty: nothing to order
        assertEquals(31L, StockMath.suggested(2.0, 14, onHand = -3)) // sold ahead of a recorded delivery
        assertEquals(3L, StockMath.target(0.25, 10)) // ⌈2.5⌉
        assertEquals(0L, StockMath.suggested(0.0, 14, onHand = 0)) // no sales: no suggestion
        assertEquals(12L, StockMath.onHand(received = 6, sold = 2, adjusted = -1, returned = 1, counted = 8))
    }

    @Test
    fun aCountSetsOnHandAsOfItsTimeAndLaterSalesStillSubtract() = testApplication {
        application { module(TestSupport.config) }
        ingest(keyShop,
            catalog(),
            received("r-1", "2026-07-20T09:00:00.000-07:00", "golden-lager-6" to 24),
            sale(1, "2026-07-20T12:00:00.000-07:00", "golden-lager-6" to 3), // 21 expected
            // counted 19 at 14:00: two missing
            counted("c-1", "2026-07-20T14:05:00.000-07:00", Triple("golden-lager-6", 19, "2026-07-20T14:00:00.000-07:00")),
        )
        // a sale closed at 13:30 but synced after the count (a late replay): before the count → already counted
        ingest(keyShop,
            sale(2, "2026-07-20T13:30:00.000-07:00", "golden-lager-6" to 1),
            sale(3, "2026-07-20T15:00:00.000-07:00", "golden-lager-6" to 2),
            received("r-2", "2026-07-20T16:00:00.000-07:00", "golden-lager-6" to 6),
        )
        val lager = lager()
        assertEquals(19L, lager.long("countedQty"))
        assertTrue(lager.str("countedAt").startsWith("2026-07-20T14:00"))
        assertEquals(2L, lager.long("sold")) // since the count
        assertEquals(6L, lager.long("received"))
        assertEquals(19L - 2 + 6, lager.long("onHand")) // 23

        // the portal's count view: who, when, and the variance against the cloud's figure then
        val count = get("/v1/stock/counts?venue=sage-poppy").rows("counts").single()
        assertEquals("c-1", count.str("countId"))
        assertEquals("Demo Cashier", count.str("submittedBy"))
        assertEquals("manager", count.str("approvedBy"))
        assertEquals(1L, count.long("varianceLines"))
        val line = count.rows("lines").single()
        assertEquals(21L, line.long("expected")) // 24 − 3 before the count
        assertEquals(-2L, line.long("variance"))
        // portal movements show the count and the store deliveries alongside portal entries
        val kinds = get("/v1/stock/movements?venue=sage-poppy&itemId=golden-lager-6")["movements"]!!.jsonArray
            .map { it.jsonObject.str("kind") to it.jsonObject.str("source") }
        assertEquals(listOf("RECEIVED" to "store", "COUNT" to "store", "RECEIVED" to "store"), kinds)
        // a portal adjustment after the count still applies on top
        client.post("/v1/stock/movements?venue=sage-poppy") {
            header(HttpHeaders.Cookie, "pos_portal_session=$session")
            contentType(ContentType.Application.Json)
            setBody("""{"itemId":"golden-lager-6","kind":"ADJUSTMENT","qty":-1,"note":"broken"}""")
        }.let { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }
        assertEquals(22L, lager().long("onHand"))
    }

    @Test
    fun replayedStockEventsNeverDoubleCount() = testApplication {
        application { module(TestSupport.config) }
        val receipt = received("r-1", "2026-07-20T09:00:00.000-07:00", "golden-lager-6" to 24, "ice-7" to 10)
        val count = counted("c-1", "2026-07-20T10:05:00.000-07:00", Triple("ice-7", 8, "2026-07-20T10:00:00.000-07:00"))
        ingest(keyShop, catalog(), receipt, count)
        // the same events again (a retried batch) → duplicates
        val again = ingest(keyShop, receipt, count)
        assertEquals(2L, again.long("duplicates"))
        // and the same receipt / count re-emitted under a new event id → no-op too
        ingest(keyShop,
            received("r-1", "2026-07-20T09:00:00.000-07:00", "golden-lager-6" to 24, "ice-7" to 10),
            counted("c-1", "2026-07-20T10:05:00.000-07:00", Triple("ice-7", 8, "2026-07-20T10:00:00.000-07:00")),
        )
        assertEquals(24L, lager().long("onHand"))
        val ice = get("/v1/stock?venue=sage-poppy").rows().single { it.str("itemId") == "ice-7" }
        assertEquals(8L, ice.long("onHand"))
        assertEquals(1, get("/v1/stock/counts?venue=sage-poppy").rows("counts").size)
        val receipts = get("/v1/stock/receipts?venue=sage-poppy").rows("receipts")
        assertEquals(1, receipts.size)
        assertEquals(34L, receipts.single().long("units"))
        assertEquals("Valley Beverage", receipts.single().str("supplier"))
        assertEquals(2, receipts.single().rows("lines").size)
    }

    @Test
    fun byLineRefundsPutStockBackAndOlderStoresRefundsToo() = testApplication {
        application { module(TestSupport.config) }
        ingest(keyShop,
            catalog(),
            received("r-1", "2026-07-20T09:00:00.000-07:00", "golden-lager-6" to 10),
            sale(1, "2026-07-20T12:00:00.000-07:00", "golden-lager-6" to 3, "ice-7" to 1), // lines 10, 11
            // this store names the product on the refunded line
            event("refund.created", buildJsonObject {
                put("refundId", 1); put("checkId", 1); put("grossCents", 999); put("currency", "USD")
                put("createdAt", "2026-07-20T13:00:00.000-07:00")
                put("lines", buildJsonArray { add(buildJsonObject { put("lineId", 10); put("itemId", "golden-lager-6"); put("qty", 1) }) })
            }, seq = next(), aggregateId = "1"),
            // an older store sent only the line id: the sale's lines resolve it
            event("refund.created", buildJsonObject {
                put("refundId", 2); put("checkId", 1); put("grossCents", 999); put("currency", "USD")
                put("createdAt", "2026-07-20T13:10:00.000-07:00")
                put("lines", buildJsonArray { add(buildJsonObject { put("lineId", 11); put("qty", 1); put("amountCents", 499) }) })
            }, seq = next(), aggregateId = "2"),
            // an amount-only refund names no products: no stock change
            event("refund.created", buildJsonObject {
                put("refundId", 3); put("checkId", 1); put("grossCents", 500); put("currency", "USD")
            }, seq = next(), aggregateId = "3"),
        )
        val lager = lager()
        assertEquals(1L, lager.long("returned"))
        assertEquals(10L - 3 + 1, lager.long("onHand"))
        val ice = get("/v1/stock?venue=sage-poppy").rows().single { it.str("itemId") == "ice-7" }
        assertEquals(0L, ice.long("onHand")) // −1 sold, +1 returned
        // a count after the refund supersedes it; a refund after the count adds on top
        ingest(keyShop,
            counted("c-1", "2026-07-20T14:00:00.000-07:00", Triple("golden-lager-6", 7, "2026-07-20T14:00:00.000-07:00")),
            event("refund.created", buildJsonObject {
                put("refundId", 4); put("checkId", 1); put("grossCents", 999); put("currency", "USD")
                put("createdAt", "2026-07-20T15:00:00.000-07:00")
                put("lines", buildJsonArray { add(buildJsonObject { put("lineId", 10); put("itemId", "golden-lager-6"); put("qty", 1) }) })
            }, seq = next(), aggregateId = "4"),
        )
        assertEquals(8L, lager().long("onHand"))
    }

    @Test
    fun reorderSuggestionsComeFromRecentSalesAndExportCleanly() = testApplication {
        application { module(TestSupport.config) }
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        fun daysAgo(n: Long) = now.minusDays(n).toString()
        ingest(keyShop,
            catalog(),
            received("r-1", daysAgo(40), "golden-lager-6" to 100, "ice-7" to 5),
            sale(1, daysAgo(35), "golden-lager-6" to 50), // outside the 28-day window
            sale(2, daysAgo(20), "golden-lager-6" to 28),
            sale(3, daysAgo(3), "golden-lager-6" to 28, "ice-7" to 1),
        )
        // 56 sold in 28 days → 2/day; cover 14 days → 28; on hand 100 − 106 = −6 → order 34
        val r = get("/v1/stock/reorder-suggestions?venue=sage-poppy")
        assertEquals(28L, r.long("days"))
        assertEquals(14L, r.long("coverDays"))
        val lager = r.rows().first()
        assertEquals("golden-lager-6", lager.str("itemId"))
        assertEquals(56L, lager.long("soldInWindow"))
        assertEquals(-6L, lager.long("onHand"))
        assertEquals(28L, lager.long("target"))
        assertEquals(34L, lager.long("suggested"))
        // ice: 1 in 28 days, 4 on hand → nothing to order at 14 days' cover
        assertEquals(0L, r.rows().single { it.str("itemId") == "ice-7" }.long("suggested"))
        assertEquals(1L, r.long("toOrder"))
        // a shorter window and longer cover: 28 in 14 days → 2/day × 30 = 60 − (−6) = 66
        val r2 = get("/v1/stock/reorder-suggestions?venue=sage-poppy&days=14&cover=30")
        assertEquals(66L, r2.rows().first().long("suggested"))
        // the window is 14-28 days
        assertEquals(HttpStatusCode.BadRequest, getWithCookie("/v1/stock/reorder-suggestions?days=7", session).status)
        // all stores: only retail ones, the pub has nothing to reorder
        val all = get("/v1/stock/reorder-suggestions")
        assertTrue(all.rows().all { it.str("venueId") == "sage-poppy" })
        assertFalse(get("/v1/stock/reorder-suggestions?venue=vieux-port")["retail"]!!.jsonPrimitive.content.toBoolean())
        // the nav badge count
        assertEquals(0L, get("/v1/stock/low-count").long("lowCount"))
    }

    @Test
    fun theStoreReadsItsOwnOnHandAndARestaurantGetsNone() = testApplication {
        application { module(TestSupport.config) }
        ingest(keyShop,
            catalog(),
            received("r-1", "2026-07-20T09:00:00.000-07:00", "golden-lager-6" to 12),
            sale(1, "2026-07-20T12:00:00.000-07:00", "golden-lager-6" to 5),
        )
        val res = client.get("/v1/store/stock") { header(HttpHeaders.Authorization, "Bearer $keyShop") }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val body = testJson.parseToJsonElement(res.bodyAsText()).jsonObject
        assertTrue(body["retail"]!!.jsonPrimitive.content.toBoolean())
        val items = body["items"]!!.jsonArray.map { it.jsonObject }.associate { it.str("itemId") to it.long("onHand") }
        assertEquals(mapOf("golden-lager-6" to 7L), items) // ice never moved: no figure
        val pub = client.get("/v1/store/stock") { header(HttpHeaders.Authorization, "Bearer $keyPub") }
        val pubBody = testJson.parseToJsonElement(pub.bodyAsText()).jsonObject
        assertFalse(pubBody["retail"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(pubBody["items"]!!.jsonArray.isEmpty())
        assertEquals(HttpStatusCode.Unauthorized,
            client.get("/v1/store/stock") { header(HttpHeaders.Authorization, "Bearer nope") }.status)
        // the capability handshake advertises the feed
        val caps = client.get("/v1/store/capabilities") { header(HttpHeaders.Authorization, "Bearer $keyShop") }.bodyAsText()
        assertTrue("/v1/store/stock" in caps, caps)
    }

    @Test
    fun oldAndOddPayloadsStillIngest() = testApplication {
        application { module(TestSupport.config) }
        ingest(keyShop,
            catalog(),
            // a count with no id, a line with no qty, a receipt with no lines: stored, not projected
            event("stock.counted", buildJsonObject { put("name", "x") }, seq = next()),
            counted("c-odd", "2026-07-20T10:00:00.000-07:00", Triple("ice-7", 3, "not a time")),
            event("stock.received", buildJsonObject { put("receiptId", "r-empty") }, seq = next()),
            // an older store's refund: no lines, no rounding, legacy zone-less time
            event("refund.created", buildJsonObject { put("refundId", 9); put("checkId", 1); put("grossCents", 100) }, seq = next()),
            // a legacy zone-less sale time
            sale(1, "2026-07-20T12:00:00", "golden-lager-6" to 1),
            event("stock.something_new", buildJsonObject { put("x", 1) }, seq = next()),
        )
        val rows = get("/v1/stock?venue=sage-poppy").rows().associateBy { it.str("itemId") }
        assertEquals(-1L, rows.getValue("golden-lager-6").long("onHand"))
        assertEquals(3L, rows.getValue("ice-7").long("onHand")) // the odd time falls back to the submit time
        assertNull(rows.getValue("golden-lager-6").longOrNull("countedQty"))
        val receipts = get("/v1/stock/receipts?venue=sage-poppy").rows("receipts")
        assertEquals(0L, receipts.single().long("units"))
    }
}
