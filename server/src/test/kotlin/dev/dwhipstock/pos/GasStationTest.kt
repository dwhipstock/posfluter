package dev.dwhipstock.pos

import dev.dwhipstock.pos.restaurant.Refunds
import dev.dwhipstock.pos.base.Upc
import dev.dwhipstock.pos.customers.pronghorn.Pronghorn
import dev.dwhipstock.pos.customers.pronghorn.PronghornCatalog
import dev.dwhipstock.pos.customers.pronghorn.PronghornCatalog.Cat
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.forecourt.ForecourtService
import dev.dwhipstock.pos.forecourt.FuelSales
import dev.dwhipstock.pos.forecourt.FuelStatus
import dev.dwhipstock.pos.forecourt.TrxState
import dev.dwhipstock.pos.forecourt.fuelAmountCents
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pronghorn Fuel & Market (POS_VENUE=pronghorn): a gas station's counter.
 * Postpay fuel onto a sale, prepay with the unused amount refunded when the
 * pump finishes, fuel without sales tax next to taxed in-store goods, the
 * forecourt going offline, and what syncs up — against [FakeForecourt].
 */
class GasStationTest {

    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()
    private val json = Json { ignoreUnknownKeys = true }
    private fun obj(s: String): JsonObject = json.parseToJsonElement(s).jsonObject

    private lateinit var fc: ForecourtService

    private fun ApplicationTestBuilder.station(fake: FakeForecourt) = application {
        module(
            dbPath = tempDb(), receiptsDir = Files.createTempDirectory("rc").toString(),
            billsDir = Files.createTempDirectory("bl").toString(),
            venueId = Pronghorn.VENUE_ID, physicalPrinterEnabled = false,
            forecourtAdapter = fake, forecourtPoll = false, onForecourt = { fc = it },
        )
    }

    private suspend fun HttpClient.postJson(path: String, body: String = "{}") =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.openShift() =
        postJson("/shifts", """{"openingFloatCents":20000,"managerPin":"1234"}""")
            .also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }

    private suspend fun HttpClient.sale(): Int = obj(postJson("/retail/sales").bodyAsText())["id"]!!.jsonPrimitive.int

    private suspend fun HttpClient.forecourt(): JsonObject = obj(get("/forecourt").bodyAsText())

    private fun JsonObject.pump(n: Int) = this["pumps"]!!.jsonArray.map { it.jsonObject }.single { it["pump"]!!.jsonPrimitive.int == n }

    private suspend fun HttpClient.payCash(sale: Int, cents: Long = 20_000) {
        postJson("/checks/$sale/tenders", """{"type":"CASH","amountTenderedCents":$cents}""")
            .also { assertTrue(it.status.isSuccess(), it.bodyAsText()) }
        post("/checks/$sale/finalize").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
    }

    private fun outbox(type: String): List<JsonObject> = transaction {
        SyncOutbox.selectAll().where { SyncOutbox.eventType eq type }.orderBy(SyncOutbox.id)
            .map { json.parseToJsonElement(it[SyncOutbox.payload]).jsonObject }
    }

    /** A taxable in-store product with a barcode (not age-restricted), and an untaxed one. */
    private val soda = PronghornCatalog.shelf.first { it.cat == Cat.DRINKS && it.taxable && !it.barcodeless }
    private val water = PronghornCatalog.shelf.first { it.subcategory == "Water" && !it.barcodeless }

    private fun salesTax(cents: Long): Long =
        BigDecimal(cents).multiply(BigDecimal("8.25")).divide(BigDecimal(100), 0, RoundingMode.HALF_UP).toLong()

    // ---- the store ----

    @Test
    fun theStationBootsAsATexasRetailStoreWithAForecourt() = testApplication {
        station(FakeForecourt())
        val health = obj(client.get("/health").bodyAsText())
        assertEquals("pronghorn", health["brand"]!!.jsonPrimitive.content)
        assertEquals("retail", health["kind"]!!.jsonPrimitive.content)
        assertEquals("USD", health["currency"]!!.jsonPrimitive.content)
        assertEquals(listOf("en", "es"), health["locales"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(21, health["legalAge"]!!.jsonPrimitive.int)
        assertTrue(health["forecourt"]!!.jsonPrimitive.boolean)
        assertEquals("America/Chicago", dev.dwhipstock.pos.sdk.VenueClock.zone.id)
    }

    @Test
    fun theShelfIsACStoreOfAboutAThousandProducts() {
        val shelf = PronghornCatalog.shelf
        assertTrue(shelf.size in 800..1500, "c-store size, got ${shelf.size}")
        val codes = shelf.mapNotNull { it.shelfCode }
        assertEquals(codes.size, codes.toSet().size, "barcodes are unique")
        assertTrue(codes.all { Upc.isValid(it) && it.startsWith(PronghornCatalog.UPC_PREFIX) })
        assertEquals(shelf.size, shelf.map { it.id }.toSet().size, "ids are unique")
        assertEquals(shelf.size, shelf.map { it.name.lowercase() }.toSet().size, "names are unique")
        // every department is stocked
        assertEquals(Cat.entries.toSet() - Cat.FUEL, shelf.map { it.cat }.toSet())
        // beer (but not the non-alcoholic) and tobacco & vape are 21+; nothing else is
        assertTrue(shelf.filter { it.cat == Cat.TOBACCO }.all { it.ageRestricted })
        assertTrue(shelf.filter { it.cat == Cat.BEER && it.subcategory != "Non-Alcoholic" }.all { it.ageRestricted })
        assertTrue(shelf.filter { it.cat !in setOf(Cat.BEER, Cat.TOBACCO) }.none { it.ageRestricted })
        // Texas: snacks, groceries, water are exempt; candy and soda are taxed
        assertTrue(shelf.filter { it.cat == Cat.SNACKS || it.cat == Cat.GROCERY }.none { it.taxable })
        assertTrue(shelf.filter { it.cat == Cat.CANDY }.all { it.taxable })
        assertTrue(shelf.filter { it.subcategory == "Soda" }.all { it.taxable })
        assertTrue(shelf.filter { it.subcategory == "Water" }.none { it.taxable })
        // hot food and coffee are quick keys, no barcode; a long tail of sales weights
        assertTrue(shelf.filter { it.cat == Cat.HOT }.all { it.barcodeless })
        assertTrue(shelf.all { it.salesWeight > 0 })
        // fuel: one item per grade + the prepay, never taxed, not on the shelf
        assertEquals(setOf("fuel-reg", "fuel-mid", "fuel-pre", "fuel-dsl", "fuel-prepay"), PronghornCatalog.fuel.map { it.id }.toSet())
        assertTrue(PronghornCatalog.fuel.none { it.taxable || it.active })
        // deterministic
        assertEquals(PronghornCatalog.shelf.map { it.barcode }, PronghornCatalog.shelf.map { it.barcode })
    }

    // ---- postpay ----

    @Test
    fun postpayFuelGoesOnTheSaleWithoutSalesTaxBesideTaxedGoods() = testApplication {
        val fake = FakeForecourt()
        station(fake)
        val c = loginClient()
        fc.tick()
        c.openShift()
        // authorise pump 3 from the counter; the customer fills up and comes in
        assertEquals(HttpStatusCode.OK, c.post("/forecourt/pumps/3/authorise").status)
        assertTrue(fake.calls.any { it.startsWith("authorise 3 POSTPAY") })
        val t = fake.fillUp(3, "MID", 10_052)
        assertEquals(fuelAmountCents(10_052, 3_299), t.amountCents)
        assertEquals(3_316, t.amountCents)
        fc.tick()
        val payable = c.forecourt().pump(3)["payable"]!!.jsonArray.single().jsonObject
        assertEquals(t.trxId, payable["trxId"]!!.jsonPrimitive.content)

        val sale = c.sale()
        val withFuel = obj(c.postJson("/retail/sales/$sale/fuel", """{"trxId":"${t.trxId}"}""").also {
            assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText())
        }.bodyAsText())
        val fuelLine = withFuel["lines"]!!.jsonArray.single().jsonObject
        assertEquals(3_316, fuelLine["lineTotalCents"]!!.jsonPrimitive.long)
        assertFalse(fuelLine["taxable"]!!.jsonPrimitive.boolean)
        assertEquals("POSTPAY", fuelLine["fuel"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertEquals(10_052, fuelLine["fuel"]!!.jsonObject["volumeMilli"]!!.jsonPrimitive.long)
        // locked at the controller for this sale: no other till can take it
        assertEquals(TrxState.LOCKED, fake.trx.getValue(t.trxId).state)
        assertEquals("sale-$sale", fake.trx.getValue(t.trxId).lockedBy)
        // one fuelling is one line
        val lineId = fuelLine["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$sale/lines/$lineId/qty", """{"qty":2}""").also {
            assertEquals(HttpStatusCode.Conflict, it.status, it.bodyAsText())
            assertEquals("fuel_line_fixed", obj(it.bodyAsText())["code"]!!.jsonPrimitive.content)
        }

        // mixed: a taxable soda and an untaxed water
        c.postJson("/retail/sales/$sale/scan", """{"barcode":"${soda.barcode}"}""")
        val mixed = obj(c.postJson("/retail/sales/$sale/scan", """{"barcode":"${water.barcode}"}""").bodyAsText())
        val tax = mixed["taxes"]!!.jsonArray.single().jsonObject
        assertEquals(salesTax(soda.cents), tax["amountCents"]!!.jsonPrimitive.long, "sales tax only on the soda")
        assertEquals(3_316 + soda.cents + water.cents + salesTax(soda.cents), mixed["grandTotalCents"]!!.jsonPrimitive.long)

        c.payCash(sale)
        // settled: cleared at the controller, recorded, synced up
        assertEquals(TrxState.CLEARED, fake.trx.getValue(t.trxId).state)
        val row = transaction { FuelSales.selectAll().single() }
        assertEquals(FuelStatus.SETTLED, row[FuelSales.status])
        assertTrue(row[FuelSales.fdcCleared])
        val fuelSale = outbox("fuel.sale").single()
        assertEquals("POSTPAY", fuelSale["mode"]!!.jsonPrimitive.content)
        assertEquals("MID", fuelSale["grade"]!!.jsonPrimitive.content)
        assertEquals("Mid-Grade", fuelSale["gradeName"]!!.jsonPrimitive.content)
        assertEquals(10_052, fuelSale["volumeMilli"]!!.jsonPrimitive.long)
        assertEquals(3_299, fuelSale["priceMills"]!!.jsonPrimitive.long)
        assertEquals(3_316, fuelSale["amountCents"]!!.jsonPrimitive.long)
        assertEquals(3, fuelSale["pump"]!!.jsonPrimitive.int)
        assertEquals(sale, fuelSale["checkId"]!!.jsonPrimitive.int)
        assertEquals("USD", fuelSale["currency"]!!.jsonPrimitive.content)
        assertEquals(t.trxId, fuelSale["fdcTransactionId"]!!.jsonPrimitive.content)
        // the sale's report-complete close carries the fuel line's facts
        val closed = outbox("check.closed").single()
        val lines = closed["lines"]!!.jsonArray.map { it.jsonObject }
        val fuel = lines.single { it["categoryId"]?.jsonPrimitive?.content == "fuel" }
        assertEquals("fuel-mid", fuel["itemId"]!!.jsonPrimitive.content)
        assertFalse(fuel["taxable"]!!.jsonPrimitive.boolean)
        assertEquals(10_052, fuel["fuel"]!!.jsonObject["volumeMilli"]!!.jsonPrimitive.long)
        assertEquals("MID", fuel["fuel"]!!.jsonObject["grade"]!!.jsonPrimitive.content)
        assertEquals(salesTax(soda.cents), closed["taxes"]!!.jsonArray.single().jsonObject["amountCents"]!!.jsonPrimitive.long)
        // the receipt says what the pump did, and that fuel carries its own taxes
        val receipt = c.get("/checks/$sale/receipt").bodyAsText()
        assertTrue(receipt.contains("Pump 3 · 10.052 gal @ 3.299/gal"), receipt)
        assertTrue(receipt.contains("Fuel prices include fuel taxes"), receipt)
        // the payable list is empty again
        fc.tick()
        assertTrue(c.forecourt().pump(3)["payable"]!!.jsonArray.isEmpty())
    }

    @Test
    fun takingFuelOffTheSaleUnlocksItForAnother() = testApplication {
        val fake = FakeForecourt()
        station(fake)
        val c = loginClient()
        fc.tick()
        c.post("/forecourt/pumps/2/authorise")
        val t = fake.fillUp(2, "REG", 5_000)
        fc.tick()
        val sale = c.sale()
        val line = obj(c.postJson("/retail/sales/$sale/fuel", """{"trxId":"${t.trxId}"}""").bodyAsText())["lines"]!!
            .jsonArray.single().jsonObject["id"]!!.jsonPrimitive.int
        c.delete("/checks/$sale/lines/$line").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        assertEquals(TrxState.PAYABLE, fake.trx.getValue(t.trxId).state)
        assertEquals(FuelStatus.RELEASED, transaction { FuelSales.selectAll().single()[FuelSales.status] })
        // the next sale can take it
        val next = c.sale()
        assertEquals(HttpStatusCode.OK, c.postJson("/retail/sales/$next/fuel", """{"trxId":"${t.trxId}"}""").status)
        assertEquals("sale-$next", fake.trx.getValue(t.trxId).lockedBy)
    }

    // ---- prepay ----

    @Test
    fun anUnusedPrepayIsRefundedWhenThePumpFinishes() = testApplication {
        val fake = FakeForecourt()
        station(fake)
        val c = loginClient()
        fc.tick()
        c.openShift()
        val sale = c.sale()
        val view = obj(c.postJson("/retail/sales/$sale/prepay", """{"pump":5,"amountCents":4000}""").also {
            assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText())
        }.bodyAsText())
        val prepayLine = view["lines"]!!.jsonArray.single().jsonObject
        assertEquals(4_000, prepayLine["lineTotalCents"]!!.jsonPrimitive.long)
        assertEquals("PREPAY", prepayLine["fuel"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertFalse(prepayLine["taxable"]!!.jsonPrimitive.boolean)
        // a second prepay on the same pump is refused
        assertEquals(HttpStatusCode.Conflict, c.postJson("/retail/sales/$sale/prepay", """{"pump":5,"amountCents":1000}""").status)
        // and some candy on the same sale
        c.postJson("/retail/sales/$sale/scan", """{"barcode":"${soda.barcode}"}""")
        // nothing reaches the pump until the sale is paid
        assertTrue(fake.calls.none { it.startsWith("authorise 5") })
        c.payCash(sale)
        val id = prepayLine["fuel"]!!.jsonObject["fuelSaleId"]!!.jsonPrimitive.int
        assertTrue(fake.calls.contains("authorise 5 PREPAY 4000 prepay-$id"), fake.calls.toString())
        fc.tick()
        assertEquals("AUTHORISED", c.forecourt().pump(5)["prepay"]!!.jsonObject["status"]!!.jsonPrimitive.content)

        // the tank was full at $31.89: $8.11 back
        val t = fake.fillUp(5, "REG", 11_000, postpayAuthorised = false)
        assertEquals(3_189, t.amountCents)
        fc.tick()
        val refund = transaction { Refunds.selectAll().single() }
        assertEquals(811, refund[Refunds.grossCents])
        assertEquals(0, refund[Refunds.taxCents], "no sales tax to reverse on fuel")
        assertEquals("CASH", refund[Refunds.tenderType])
        assertEquals("Prepay change", refund[Refunds.reason])
        assertEquals(-1, refund[Refunds.roundingAdjustmentCents], "cash back to the nickel: $8.10")
        val refundEvent = outbox("refund.created").single()
        assertEquals(id, refundEvent["fuelSaleId"]!!.jsonPrimitive.int)
        val fuelSale = outbox("fuel.sale").single()
        assertEquals("PREPAY", fuelSale["mode"]!!.jsonPrimitive.content)
        assertEquals(4_000, fuelSale["prepaidCents"]!!.jsonPrimitive.long)
        assertEquals(3_189, fuelSale["amountCents"]!!.jsonPrimitive.long)
        assertEquals(811, fuelSale["refundCents"]!!.jsonPrimitive.long)
        assertEquals(11_000, fuelSale["volumeMilli"]!!.jsonPrimitive.long)
        assertEquals(refund[Refunds.id].value, fuelSale["refundId"]!!.jsonPrimitive.int)
        assertEquals(TrxState.CLEARED, fake.trx.getValue(t.trxId).state)
        // the tile asks the cashier to hand back the change, until they say so
        val change = c.forecourt().pump(5)["change"]!!.jsonObject
        assertEquals(811, change["refundCents"]!!.jsonPrimitive.long)
        assertNull(c.forecourt().pump(5)["prepay"]?.takeIf { it !is kotlinx.serialization.json.JsonNull })
        c.post("/forecourt/prepays/$id/change-given")
        assertNull(c.forecourt().pump(5)["change"]?.takeIf { it !is kotlinx.serialization.json.JsonNull })
        // idempotent: another tick doesn't refund twice
        fc.tick()
        assertEquals(1, transaction { Refunds.selectAll().count() })
        assertEquals(1, outbox("fuel.sale").size)
    }

    @Test
    fun aPrepayThatFillsToTheLimitHasNoChange() = testApplication {
        val fake = FakeForecourt()
        station(fake)
        val c = loginClient()
        fc.tick()
        c.openShift()
        val sale = c.sale()
        c.postJson("/retail/sales/$sale/prepay", """{"pump":1,"amountCents":4000}""")
        c.payCash(sale)
        val t = fake.fillUp(1, "PRE", 20_000, postpayAuthorised = false)
        assertEquals(4_000, t.amountCents, "the pump stops at the prepaid amount")
        assertEquals(4_000L * 10_000 / 3_699, t.volumeMilli)
        fc.tick()
        assertEquals(0, transaction { Refunds.selectAll().count() })
        val fuelSale = outbox("fuel.sale").single()
        assertEquals(0, fuelSale["refundCents"]!!.jsonPrimitive.long)
        assertEquals(4_000, fuelSale["amountCents"]!!.jsonPrimitive.long)
        assertNull(c.forecourt().pump(1)["change"]?.takeIf { it !is kotlinx.serialization.json.JsonNull })
    }

    @Test
    fun aPaidPrepayCanBeCancelledBeforeAnyFuelFlows() = testApplication {
        val fake = FakeForecourt()
        station(fake)
        val c = loginClient()
        fc.tick()
        c.openShift()
        val sale = c.sale()
        val id = obj(c.postJson("/retail/sales/$sale/prepay", """{"pump":4,"amountCents":2500}""").bodyAsText())["lines"]!!
            .jsonArray.single().jsonObject["fuel"]!!.jsonObject["fuelSaleId"]!!.jsonPrimitive.int
        c.payCash(sale)
        fc.tick()
        c.post("/forecourt/prepays/$id/cancel").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        assertTrue(fake.calls.contains("free 4"))
        assertEquals(2_500, transaction { Refunds.selectAll().single()[Refunds.grossCents] })
        assertEquals(FuelStatus.CANCELLED, transaction { FuelSales.selectAll().single()[FuelSales.status] })
        assertTrue(outbox("fuel.sale").isEmpty(), "no fuel, no fuel sale")
    }

    // ---- the controller goes away ----

    @Test
    fun anUnreachableForecourtShowsOfflinePumpsAndTheShopKeepsSelling() = testApplication {
        val fake = FakeForecourt()
        station(fake)
        val c = loginClient()
        c.openShift()
        fake.offline = true
        fc.tick()
        val view = c.forecourt()
        assertFalse(view["online"]!!.jsonPrimitive.boolean)
        assertNotNull(view["message"])
        val pumps = view["pumps"]!!.jsonArray.map { it.jsonObject }
        assertEquals(8, pumps.size)
        assertTrue(pumps.all { it["state"]!!.jsonPrimitive.content == "OFFLINE" })
        // no pump commands, no prepay…
        c.post("/forecourt/pumps/1/authorise").also {
            assertEquals(HttpStatusCode.Conflict, it.status)
            assertEquals("forecourt_offline", obj(it.bodyAsText())["code"]!!.jsonPrimitive.content)
        }
        val sale = c.sale()
        assertEquals(HttpStatusCode.Conflict, c.postJson("/retail/sales/$sale/prepay", """{"pump":1,"amountCents":2000}""").status)
        // …but the in-store sale goes through
        c.postJson("/retail/sales/$sale/scan", """{"barcode":"${soda.barcode}"}""").also { assertEquals(HttpStatusCode.OK, it.status) }
        c.payCash(sale)
        assertEquals(1, outbox("check.closed").size)
    }

    @Test
    fun aPrepayPaidWhileTheControllerIsDownStartsWhenItComesBack() = testApplication {
        val fake = FakeForecourt()
        station(fake)
        val c = loginClient()
        fc.tick()
        c.openShift()
        val sale = c.sale()
        c.postJson("/retail/sales/$sale/prepay", """{"pump":6,"amountCents":3000}""")
        fake.offline = true
        c.payCash(sale) // the sale closes regardless
        fc.tick()
        val row = transaction { FuelSales.selectAll().single() }
        assertEquals(FuelStatus.AUTH_FAILED, row[FuelSales.status])
        fake.offline = false
        fc.tick()
        assertEquals(FuelStatus.AUTHORISED, transaction { FuelSales.selectAll().single()[FuelSales.status] })
        assertTrue(fake.calls.any { it.startsWith("authorise 6 PREPAY 3000") })
    }

    @Test
    fun aSettledFuelSaleIsClearedAtTheControllerOnceItAnswersAgain() = testApplication {
        val fake = FakeForecourt()
        station(fake)
        val c = loginClient()
        fc.tick()
        c.openShift()
        c.post("/forecourt/pumps/7/authorise")
        val t = fake.fillUp(7, "DSL", 20_000)
        fc.tick()
        val sale = c.sale()
        c.postJson("/retail/sales/$sale/fuel", """{"trxId":"${t.trxId}"}""")
        fake.offline = true
        c.payCash(sale)
        // the books are right straight away; the controller hears later
        assertEquals(1, outbox("fuel.sale").size)
        assertFalse(transaction { FuelSales.selectAll().single()[FuelSales.fdcCleared] })
        fake.offline = false
        fc.tick()
        assertTrue(transaction { FuelSales.selectAll().single()[FuelSales.fdcCleared] })
        assertEquals(TrxState.CLEARED, fake.trx.getValue(t.trxId).state)
    }

    @Test
    fun emergencyStopReachesEveryPump() = testApplication {
        val fake = FakeForecourt()
        station(fake)
        val c = loginClient()
        fc.tick()
        c.post("/forecourt/emergency-stop").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        assertTrue(fake.calls.contains("emergency-stop all"))
        assertTrue(c.forecourt()["pumps"]!!.jsonArray.all { it.jsonObject["state"]!!.jsonPrimitive.content == "EMERGENCY_STOP" })
        c.post("/forecourt/pumps/2/reset")
        assertEquals("IDLE", c.forecourt().pump(2)["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun otherStoresHaveNoForecourt() = testApplication {
        application {
            module(dbPath = tempDb(), receiptsDir = Files.createTempDirectory("rc").toString(), physicalPrinterEnabled = false)
        }
        val health = obj(client.get("/health").bodyAsText())
        assertNull(health["forecourt"], "left out of /health for a store without pumps")
        assertEquals(HttpStatusCode.Conflict, loginClient().get("/forecourt").status)
    }
}
