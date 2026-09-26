package dev.dwhipstock.pos

import dev.dwhipstock.pos.customers.sagepoppy.SagePoppy
import dev.dwhipstock.pos.customers.sagepoppy.SagePoppySeed
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.db.SyncState
import dev.dwhipstock.pos.retail.CloudOnHand
import dev.dwhipstock.pos.retail.StockExpected
import dev.dwhipstock.pos.retail.StockService
import dev.dwhipstock.pos.sync.CloudSync
import dev.dwhipstock.pos.sync.CloudTransport
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
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Counting and receiving stock in the store (Sage & Poppy), offline: count
 * sessions summed over devices, idempotent resends and submits, deliveries,
 * the expected-qty hint (the cloud's figure + this store's moves since), the
 * manager's approval of a variance, and the outbox events the cloud ingests.
 * Restaurants don't count stock.
 */
class StockCountTest {

    private fun tempDb() = Files.createTempDirectory("pos-stock").resolve("pos.db").toString()
    private val json = Json { ignoreUnknownKeys = true }
    private fun obj(res: String): JsonObject = json.parseToJsonElement(res).jsonObject
    private fun product(id: String) = SagePoppySeed.products.single { it.id == id }

    private val count1 = "0d6b3c1e-0000-4000-8000-000000000001"
    private val phoneA = "phone-aaaa-0001"
    private val phoneB = "phone-bbbb-0002"

    private fun ApplicationTestBuilder.shop() = application {
        module(
            dbPath = tempDb(), receiptsDir = Files.createTempDirectory("rc").toString(),
            venueId = SagePoppy.VENUE_ID, sagePoppy = true, physicalPrinterEnabled = false,
        )
    }

    private suspend fun HttpClient.postJson(path: String, body: String = "{}") =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.putJson(path: String, body: String) =
        put(path) { contentType(ContentType.Application.Json); setBody(body) }

    private fun events(type: String): List<JsonObject> = transaction {
        SyncOutbox.selectAll().where { SyncOutbox.eventType eq type }.map { obj(it[SyncOutbox.payload]) }
    }

    private fun JsonObject.lines() = this["lines"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.line(itemId: String) = lines().single { it["itemId"]!!.jsonPrimitive.content == itemId }
    private fun JsonObject.intOrNull(k: String) = this[k]?.jsonPrimitive?.content?.toIntOrNull()

    /** Ring up [qty] of [itemId] and pay cash; returns the check id. */
    private suspend fun HttpClient.sell(itemId: String, qty: Int): Int {
        val sale = obj(postJson("/retail/sales").bodyAsText())["id"]!!.jsonPrimitive.int
        repeat(qty) { postJson("/retail/sales/$sale/scan", """{"barcode":"${product(itemId).barcode}"}""") }
        if (product(itemId).ageRestricted)
            postJson("/retail/sales/$sale/age-check", """{"method":"MANUAL","dateOfBirth":"1980-01-01","cashierSawId":true}""")
        postJson("/checks/$sale/tenders", """{"type":"CASH","amountTenderedCents":100000}""")
            .let { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }
        post("/checks/$sale/finalize").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        return sale
    }

    @Test
    fun aRestaurantDoesNotCountStock() = testApplication {
        application { module(dbPath = tempDb(), physicalPrinterEnabled = false) }
        val c = loginClient()
        c.get("/stock/counts").let {
            assertEquals(HttpStatusCode.Conflict, it.status)
            assertEquals("not_retail", obj(it.bodyAsText())["code"]!!.jsonPrimitive.content)
        }
        assertEquals(HttpStatusCode.Conflict, c.postJson("/stock/receipts", """{"lines":[]}""").status)
    }

    @Test
    fun twoPhonesCountOneSessionAndResendingNeverDoublesAnything() = testApplication {
        shop()
        val cashier = loginClient("9999")
        // start twice with the same client id (the phone retried): one session
        repeat(2) {
            cashier.postJson("/stock/counts", """{"id":"$count1","name":"Back room"}""")
                .let { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }
        }
        assertEquals(1, obj("""{"a":${cashier.get("/stock/counts").bodyAsText()}}""")["a"]!!.jsonArray.size)

        val lager = product("golden-lager-6")
        // phone A: 5 on the shelf (sent twice after a dropped reply); by barcode
        repeat(2) {
            cashier.putJson("/stock/counts/$count1/lines",
                """{"counterId":"$phoneA","lines":[{"barcode":"0${lager.barcode}","qty":5}]}""")
                .let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        }
        // phone B: 7 in the back room, then corrects itself to 8 (a SET, not +1)
        cashier.putJson("/stock/counts/$count1/lines", """{"counterId":"$phoneB","lines":[{"itemId":"golden-lager-6","qty":7}]}""")
        val view = obj(cashier.putJson("/stock/counts/$count1/lines",
            """{"counterId":"$phoneB","lines":[{"itemId":"golden-lager-6","qty":8},{"itemId":"ice-7","qty":0}]}""").bodyAsText())
        assertEquals(13, view.line("golden-lager-6")["counted"]!!.jsonPrimitive.int) // 5 + 8
        assertEquals(8, view.line("golden-lager-6")["mine"]!!.jsonPrimitive.int)
        assertEquals(0, view.line("ice-7")["counted"]!!.jsonPrimitive.int) // counted zero is a count
        // no cloud figure yet: no expected qty, no variance, no approval needed
        assertNull(view.line("golden-lager-6").intOrNull("expected"))
        assertEquals("false", view["needsApproval"]!!.jsonPrimitive.content)

        // an unknown code is refused, the count keeps what it had
        cashier.putJson("/stock/counts/$count1/lines", """{"counterId":"$phoneA","lines":[{"barcode":"999999999999","qty":1}]}""")
            .let { assertEquals(HttpStatusCode.NotFound, it.status) }

        // submit twice (the reply was lost): one event, same answer
        repeat(2) {
            cashier.postJson("/stock/counts/$count1/submit").let {
                assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText())
                assertEquals("SUBMITTED", obj(it.bodyAsText())["status"]!!.jsonPrimitive.content)
            }
        }
        val counted = events("stock.counted").single()
        assertEquals(count1, counted["countId"]!!.jsonPrimitive.content)
        assertEquals("Back room", counted["name"]!!.jsonPrimitive.content)
        assertEquals(13, counted.line("golden-lager-6")["countedQty"]!!.jsonPrimitive.int)
        assertEquals(0, counted.line("ice-7")["countedQty"]!!.jsonPrimitive.int)
        assertTrue(counted.line("ice-7")["countedAt"]!!.jsonPrimitive.content.contains("T"))
        // a submitted count is closed to more lines
        cashier.putJson("/stock/counts/$count1/lines", """{"counterId":"$phoneA","lines":[{"itemId":"ice-7","qty":3}]}""")
            .let { assertEquals(HttpStatusCode.Conflict, it.status) }
    }

    @Test
    fun aDeliveryIsRecordedOnceHoweverOftenThePhoneSendsIt() = testApplication {
        shop()
        val c = loginClient("9999")
        val body = """{"id":"rcpt-0000-0000-0001","supplier":"Valley Beverage","reference":"INV-1042",
            "lines":[{"itemId":"golden-lager-6","qty":12},{"barcode":"${product("golden-lager-6").barcode}","qty":12},{"itemId":"ice-7","qty":20}]}"""
        repeat(3) { c.postJson("/stock/receipts", body).let { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) } }
        val received = events("stock.received").single()
        assertEquals("Valley Beverage", received["supplier"]!!.jsonPrimitive.content)
        assertEquals("INV-1042", received["reference"]!!.jsonPrimitive.content)
        assertEquals(24, received.line("golden-lager-6")["qty"]!!.jsonPrimitive.int) // the two scans merged
        assertEquals(20, received.line("ice-7")["qty"]!!.jsonPrimitive.int)
        assertEquals(1, json.parseToJsonElement(c.get("/stock/receipts").bodyAsText()).jsonArray.size)
        // a delivery takes positive quantities of known products
        assertEquals(HttpStatusCode.BadRequest, c.postJson("/stock/receipts", """{"lines":[{"itemId":"ice-7","qty":0}]}""").status)
        assertEquals(HttpStatusCode.BadRequest, c.postJson("/stock/receipts", """{"lines":[]}""").status)
        assertEquals(HttpStatusCode.NotFound, c.postJson("/stock/receipts", """{"lines":[{"itemId":"nope","qty":1}]}""").status)
    }

    @Test
    fun theExpectedQtyIsTheCloudFigurePlusThisStoresMovesAndAVarianceNeedsAManager() = testApplication {
        shop()
        val manager = loginClient()
        manager.postJson("/shifts", """{"openingFloatCents":20000,"managerPin":"1234"}""")
        // the cloud said 20 golden lager, as of a moment ago
        StockService(dev.dwhipstock.pos.customers.sagepoppy.SagePoppyConfig(
            settings = dev.dwhipstock.pos.base.SettingsRepository(),
            printer = dev.dwhipstock.pos.sdk.PrinterAdapter.VirtualPrinter(
                Files.createTempDirectory("r").toString(), Files.createTempDirectory("b").toString()),
            publicBaseUrl = "http://localhost:8080",
        )).applyCloudOnHand(Instant.now().minusSeconds(5), listOf(CloudOnHand("golden-lager-6", 20)))

        val cashier = loginClient("9999")
        cashier.sell("golden-lager-6", 3)
        cashier.postJson("/stock/receipts", """{"lines":[{"itemId":"golden-lager-6","qty":6}]}""")
        var expected = obj(cashier.get("/stock/expected").bodyAsText())
        assertEquals("true", expected["available"]!!.jsonPrimitive.content)
        assertEquals(23, expected["items"]!!.jsonObject["golden-lager-6"]!!.jsonPrimitive.int) // 20 − 3 + 6
        assertNull(expected["items"]!!.jsonObject["ice-7"]) // no data → "no expected qty"

        // counted 22: one short
        cashier.postJson("/stock/counts", """{"id":"$count1"}""")
        val view = obj(cashier.putJson("/stock/counts/$count1/lines",
            """{"counterId":"$phoneA","lines":[{"itemId":"golden-lager-6","qty":22}]}""").bodyAsText())
        assertEquals(23, view.line("golden-lager-6").intOrNull("expected"))
        assertEquals(-1, view.line("golden-lager-6").intOrNull("variance"))
        assertEquals("true", view["needsApproval"]!!.jsonPrimitive.content)
        // a cashier can't submit a variance alone …
        cashier.postJson("/stock/counts/$count1/submit").let {
            assertEquals(HttpStatusCode.Forbidden, it.status)
            assertEquals("manager_approval_required", obj(it.bodyAsText())["code"]!!.jsonPrimitive.content)
        }
        cashier.postJson("/stock/counts/$count1/submit", """{"managerPin":"5555"}""")
            .let { assertEquals(HttpStatusCode.Forbidden, it.status) } // another cashier's PIN
        // … a manager's PIN approves it
        val submitted = obj(cashier.postJson("/stock/counts/$count1/submit", """{"managerPin":"1234"}""").bodyAsText())
        assertEquals("manager", submitted["approvedBy"]!!.jsonPrimitive.content)
        assertEquals("cashier", submitted["submittedBy"]!!.jsonPrimitive.content)
        val event = events("stock.counted").single()
        assertEquals(23, event.line("golden-lager-6").intOrNull("expectedQty"))
        assertEquals("manager", event["approvedBy"]!!.jsonPrimitive.content)

        // the count is the new starting point: 22, then a sale of 2 → 20
        cashier.sell("golden-lager-6", 2)
        expected = obj(cashier.get("/stock/expected").bodyAsText())
        assertEquals(20, expected["items"]!!.jsonObject["golden-lager-6"]!!.jsonPrimitive.int)
        // and the submitted count still shows what it was compared with
        val later = obj(cashier.get("/stock/counts/$count1").bodyAsText())
        assertEquals(23, later.line("golden-lager-6").intOrNull("expected"))
    }

    @Test
    fun aByLineRefundNamesTheProductsSoTheCloudCanRestockThem() = testApplication {
        shop()
        val c = loginClient()
        c.postJson("/shifts", """{"openingFloatCents":20000,"managerPin":"1234"}""")
        val sale = c.sell("golden-lager-6", 2)
        val lineId = obj(c.get("/checks/$sale").bodyAsText())["lines"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$sale/refund",
            """{"lines":[{"lineId":$lineId,"qty":1}],"tenderType":"CASH","reason":"Returned unopened","managerPin":"1234"}""")
            .let { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }
        val refund = events("refund.created").single()
        val line = refund["lines"]!!.jsonArray.single().jsonObject
        assertEquals("golden-lager-6", line["itemId"]!!.jsonPrimitive.content)
        assertEquals(1, line["qty"]!!.jsonPrimitive.int)
    }

    @Test
    fun theSyncLoopCachesTheCloudOnHandStampedWithWhatTheCloudHad() = testApplication {
        shop()
        startApplication()
        val service = StockService(dev.dwhipstock.pos.customers.sagepoppy.SagePoppyConfig(
            settings = dev.dwhipstock.pos.base.SettingsRepository(),
            printer = dev.dwhipstock.pos.sdk.PrinterAdapter.VirtualPrinter(
                Files.createTempDirectory("r").toString(), Files.createTempDirectory("b").toString()),
            publicBaseUrl = "http://localhost:8080",
        ))
        var onHand: List<CloudOnHand>? = listOf(CloudOnHand("golden-lager-6", 11), CloudOnHand("ice-7", 4))
        val transport = object : CloudTransport by FakeTransport() {
            override fun fetchOnHand(): List<CloudOnHand>? = onHand
        }
        val sync = CloudSync(transport, InMemoryPhotoStore(), stock = service)
        sync.pullStockOnce()
        val rows = transaction { StockExpected.selectAll().associate { it[StockExpected.itemId] to it[StockExpected.onHand] } }
        assertEquals(mapOf("golden-lager-6" to 11, "ice-7" to 4), rows)
        // nothing pushed yet: the figure is stamped before the first unsent event
        val firstUnsent = transaction { SyncOutbox.selectAll().orderBy(SyncOutbox.id).first()[SyncOutbox.createdAt] }
        val asOf = transaction { StockExpected.selectAll().first()[StockExpected.asOf] }
        assertTrue(asOf.isBefore(firstUnsent))
        // an older cloud (no feed) leaves the cache alone
        onHand = null
        sync.pullStockOnce()
        assertEquals(2, transaction { StockExpected.selectAll().count() }.toInt())
        // a transport failure too
        val broken = CloudSync(object : CloudTransport by FakeTransport() {
            override fun fetchOnHand(): List<CloudOnHand>? = error("no internet")
        }, InMemoryPhotoStore(), stock = service)
        broken.pullStockOnce()
        assertEquals(2, transaction { StockExpected.selectAll().count() }.toInt())
        assertNull(transaction { SyncState.get("stock_cursor") })
    }
}
