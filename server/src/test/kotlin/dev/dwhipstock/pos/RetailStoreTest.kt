package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.AgeChecks
import dev.dwhipstock.pos.base.Upc
import dev.dwhipstock.pos.customers.sagepoppy.SagePoppy
import dev.dwhipstock.pos.customers.sagepoppy.SagePoppySeed
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.retail.ProductLookup
import dev.dwhipstock.pos.sdk.BasketLine
import dev.dwhipstock.pos.sdk.Crv
import dev.dwhipstock.pos.sdk.Money
import dev.dwhipstock.pos.sdk.TransactionPipeline
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
import kotlinx.serialization.json.long
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Sage & Poppy Bottle Shop (POS_VENUE=sage-poppy): the US retail counter —
 * its seed and barcodes, CRV and California sales tax, the ID check before
 * payment, adding an unknown product, and Spanish receipts.
 */
class RetailStoreTest {

    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()
    private val json = Json { ignoreUnknownKeys = true }

    private fun product(id: String) = SagePoppySeed.products.single { it.id == id }

    private fun ApplicationTestBuilder.shop(lookup: ProductLookup = ProductLookup.NONE) = application {
        module(
            dbPath = tempDb(), receiptsDir = Files.createTempDirectory("rc").toString(),
            venueId = SagePoppy.VENUE_ID, sagePoppy = true, productLookup = lookup,
            physicalPrinterEnabled = false,
        )
    }

    private suspend fun HttpClient.postJson(path: String, body: String = "{}") =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    private fun obj(res: String): JsonObject = json.parseToJsonElement(res).jsonObject

    private suspend fun HttpClient.openShift() =
        postJson("/shifts", """{"openingFloatCents":20000,"managerPin":"1234"}""")
            .also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }

    private suspend fun HttpClient.sale(): Int =
        obj(postJson("/retail/sales").bodyAsText())["id"]!!.jsonPrimitive.int

    private suspend fun HttpClient.scan(sale: Int, code: String): HttpResponse =
        postJson("/retail/sales/$sale/scan", """{"barcode":"$code"}""")

    // ---- seed ----

    @Test
    fun theSeedIsAboutFiftyProductsWithValidUniqueUpcCodes() {
        val products = SagePoppySeed.products
        assertTrue(products.size in 45..60, "about 50 products, got ${products.size}")
        val codes = products.map { it.barcode }
        assertEquals(codes.size, codes.toSet().size, "barcodes are unique")
        for (p in products) {
            assertEquals(12, p.barcode.length, p.id)
            assertTrue(Upc.isValid(p.barcode), "${p.id}: ${p.barcode} has a bad check digit")
            assertTrue(p.barcode.startsWith(SagePoppySeed.UPC_PREFIX), "${p.id}: made-up in-store prefix")
            assertTrue(p.cents > 0, p.id)
        }
        // number system 4 = in-store codes, never a manufacturer's
        assertTrue(codes.all { it.startsWith("4") })
        // every category is stocked; alcohol is age-restricted, food is not taxed
        assertEquals(SagePoppySeed.Cat.entries.toSet() - SagePoppySeed.Cat.SUNDRIES, products.map { it.cat }.toSet())
        val alcohol = setOf(SagePoppySeed.Cat.WINE, SagePoppySeed.Cat.SPIRITS, SagePoppySeed.Cat.SELTZERS)
        assertTrue(products.filter { it.cat in alcohol }.all { it.ageRestricted })
        assertTrue(products.filter { it.cat == SagePoppySeed.Cat.SNACKS || it.cat == SagePoppySeed.Cat.ICE }
            .none { it.ageRestricted })
        assertTrue(products.filter { it.cat == SagePoppySeed.Cat.ICE }.none { it.taxable })
        // wine and spirits carry no CRV; beer cans and bottles do, per container
        assertTrue(products.filter { it.cat == SagePoppySeed.Cat.WINE || it.cat == SagePoppySeed.Cat.SPIRITS }
            .all { it.crv == Crv.Size.NONE })
        assertEquals(Crv.Size.SMALL to 6, product("golden-lager-6").let { it.crv to it.pack })
        assertEquals(Crv.Size.LARGE, product("big-pour-lager").crv)
        // beer as singles, 6-packs and 12s
        assertTrue(products.any { it.cat == SagePoppySeed.Cat.BEER && it.pack == 1 })
        assertTrue(products.any { it.cat == SagePoppySeed.Cat.BEER && it.pack == 6 })
        assertTrue(products.any { it.cat == SagePoppySeed.Cat.BEER && it.pack == 12 })
    }

    @Test
    fun upcCheckDigitsFollowTheStandard() {
        assertEquals(2, Upc.checkDigit("03600029145")) // the textbook UPC-A example
        assertTrue(Upc.isValid("036000291452"))
        assertFalse(Upc.isValid("036000291453"))
        assertEquals(Upc.upcA("48723000101"), SagePoppySeed.products.first().barcode)
        // an EAN-13 with a leading zero is the same product as its UPC-A
        assertEquals("036000291452", Upc.normalize("0036000291452"))
        assertEquals("ABC-1", Upc.normalize(" ABC-1 "))
    }

    @Test
    fun theShopBootsAsAUsRetailStoreInPacificTime() = testApplication {
        shop()
        val health = obj(client.get("/health").bodyAsText())
        assertEquals("sage-poppy", health["venueId"]!!.jsonPrimitive.content)
        assertEquals("Sage & Poppy Bottle Shop", health["venue"]!!.jsonPrimitive.content)
        assertEquals("retail", health["kind"]!!.jsonPrimitive.content)
        assertEquals("USD", health["currency"]!!.jsonPrimitive.content)
        assertEquals("US", health["country"]!!.jsonPrimitive.content)
        assertEquals(listOf("en", "es"), health["locales"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(21, health["legalAge"]!!.jsonPrimitive.int)
        assertEquals(ZoneId.of("America/Los_Angeles"), dev.dwhipstock.pos.sdk.VenueClock.zone)
        val items = json.parseToJsonElement(loginClient().get("/items").bodyAsText()).jsonArray
        // the whole ~5,000-product shelf, the hand-written first-boot products among it
        assertEquals(dev.dwhipstock.pos.customers.sagepoppy.SagePoppyCatalog.TOTAL, items.size)
        assertTrue(items.none { it.jsonObject["id"]!!.jsonPrimitive.content == "lantern-lager" }, "no pub residue")
        val sixPack = items.single { it.jsonObject["id"]!!.jsonPrimitive.content == "golden-lager-6" }.jsonObject
        assertEquals(30L, sixPack["depositCents"]!!.jsonPrimitive.long)
        assertEquals(product("golden-lager-6").barcode, sixPack["barcode"]!!.jsonPrimitive.content)
    }

    // ---- CRV + California sales tax ----

    @Test
    fun crvIsPerContainerTimesPackAndNeverTaxed() {
        assertEquals(Money(30), Crv.perUnit(Crv.Size.SMALL, 6)) // 6 × 5¢
        assertEquals(Money(60), Crv.perUnit(Crv.Size.SMALL, 12))
        assertEquals(Money(10), Crv.perUnit(Crv.Size.LARGE, 1))
        assertEquals(Money.ZERO, Crv.perUnit(Crv.Size.NONE, 1))
        assertEquals(Crv.Size.SMALL, Crv.sizeFor(12.0))
        assertEquals(Crv.Size.SMALL, Crv.sizeFor(23.9))
        assertEquals(Crv.Size.LARGE, Crv.sizeFor(24.0))

        val config = dev.dwhipstock.pos.customers.sagepoppy.SagePoppyConfig(
            settings = dev.dwhipstock.pos.base.SettingsRepository(),
            printer = dev.dwhipstock.pos.sdk.PrinterAdapter.VirtualPrinter(
                Files.createTempDirectory("r").toString(), Files.createTempDirectory("b").toString()),
            publicBaseUrl = "http://localhost", legalAge = 21, salesTaxPercent = BigDecimal("9.5"),
        )
        // 2 × 6-pack at $9.99, a 25 oz can at $2.99, chips at $4.49 (not taxed)
        val t = TransactionPipeline.computeTotals(listOf(
            BasketLine(Money(999), 2, taxable = true, depositPerUnit = Money(30)),
            BasketLine(Money(299), 1, taxable = true, depositPerUnit = Money(10)),
            BasketLine(Money(449), 1, taxable = false),
        ), corkageBottles = 0, config = config)
        assertEquals(Money(999 * 2 + 299 + 449), t.itemsSubtotal)
        assertEquals(listOf("crv" to Money(70)), t.feeLines.map { it.code to it.amount }) // 2×30 + 10
        // 9.5% on the taxable goods only (22.97): 2.18215 → 2.18; CRV and chips untaxed
        assertEquals(Money(2297), t.taxableBase)
        assertEquals(listOf(Money(218)), t.taxLines.map { it.amount })
        assertEquals(Money(2297 + 449 + 70 + 218), t.grandTotal)
    }

    // ---- the counter, end to end ----

    @Test
    fun aSaleScansProductsAndPrintsCrvAndSalesTaxOnItsOwnLines() = testApplication {
        shop()
        val c = loginClient("9999") // Demo Cashier (English)
        loginClient().openShift()
        val sale = c.sale()
        // scanning the same product twice bumps its line
        c.scan(sale, product("club-soda").barcode).let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        c.scan(sale, product("club-soda").barcode)
        // an EAN-13 form of the same UPC finds it too
        val check = obj(c.scan(sale, "0" + product("chips-sea-salt").barcode).bodyAsText())
        val lines = check["lines"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, lines.size)
        assertEquals(2, lines.first { it["itemId"]!!.jsonPrimitive.content == "club-soda" }["qty"]!!.jsonPrimitive.int)
        assertFalse(check["ageCheckRequired"]!!.jsonPrimitive.content.toBoolean())
        // club soda 2 × 2.29 = 4.58 taxable, chips 4.49 untaxed; CRV 2 × 10¢
        // tax 9.5% × 4.58 = 0.4351 → 0.44; total 4.58 + 4.49 + 0.20 + 0.44 = 9.71
        assertEquals(971L, check["grandTotalCents"]!!.jsonPrimitive.long)
        c.postJson("/checks/$sale/tenders", """{"type":"CASH","amountTenderedCents":1000}""")
            .let { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }
        c.post("/checks/$sale/finalize").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        val receipt = obj(c.get("/checks/$sale/receipt").bodyAsText())["text"]!!.jsonPrimitive.content
        assertTrue("Register 1" in receipt && "Sale #$sale" in receipt, receipt)
        assertTrue("CRV" in receipt && "0.20" in receipt, receipt)
        assertTrue("Sales Tax 9.5%" in receipt && "0.44" in receipt, receipt)
        assertTrue("Table" !in receipt && "GST" !in receipt, receipt)
        // US cash rounds to the nickel too: 9.71 → 9.70 in cash, 10.00 − 9.70 = 0.30 change
        val kv = receipt.lines().map { it.trim().replace(Regex(" {2,}"), " | ") }
        assertTrue("Total | 9.71" in kv, receipt) // the sale itself stays exact
        assertTrue("Rounding | -0.01" in kv && "Cash total | 9.70" in kv, receipt)
        assertTrue("Change | 0.30" in kv, receipt)
        // US receipt style: cents always, month-first dates on a 12-hour clock
        assertTrue("10.00" in receipt, receipt)
        assertTrue(Regex("""\d{2}/\d{2}/\d{4} \d{1,2}:\d{2} (AM|PM)""").containsMatchIn(receipt), receipt)
        // the sale synced in dollars, with its deposit and currency
        val closed = transaction {
            SyncOutbox.selectAll().where { SyncOutbox.eventType eq "check.closed" }.first()[SyncOutbox.payload]
        }.let(::obj)
        assertEquals("USD", closed["currency"]!!.jsonPrimitive.content)
        assertEquals("US", closed["country"]!!.jsonPrimitive.content)
        assertEquals("crv", closed["fees"]!!.jsonArray.single().jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun aSpanishSpeakingCashierPrintsASpanishReceipt() = testApplication {
        shop()
        loginClient().openShift()
        val c = loginClient("5555") // Cajera Demo, language es
        val sale = c.sale()
        c.scan(sale, product("tonic").barcode)
        c.scan(sale, product("ice-7").barcode)
        c.postJson("/checks/$sale/tenders", """{"type":"CASH","amountTenderedCents":1000}""")
        c.post("/checks/$sale/finalize").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        val receipt = obj(c.get("/checks/$sale/receipt").bodyAsText())["text"]!!.jsonPrimitive.content
        assertTrue("Caja 1" in receipt && "Venta #$sale" in receipt, receipt)
        assertTrue("Impuesto sobre la venta 9.5%" in receipt, receipt)
        assertTrue("CRV (depósito de envases)" in receipt, receipt)
        assertTrue("Efectivo" in receipt && "Cambio" in receipt, receipt)
        assertTrue("Subtotal" in receipt && "Total" in receipt, receipt)
    }

    // ---- the ID check ----

    private fun today() = LocalDate.now(ZoneId.of(SagePoppy.TIME_ZONE))

    @Test
    fun ageRestrictedItemsWaitForAPassingIdCheck() = testApplication {
        shop()
        val c = loginClient()
        c.openShift()
        val sale = c.sale()
        val beer = obj(c.scan(sale, product("golden-lager-6").barcode).bodyAsText())
        assertTrue(beer["ageCheckRequired"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(beer["ageCleared"]!!.jsonPrimitive.content.toBoolean())
        c.scan(sale, product("ice-7").barcode)

        // no check yet → no payment
        c.postJson("/checks/$sale/tenders", """{"type":"CASH","amountTenderedCents":5000}""").let {
            assertEquals(HttpStatusCode.Conflict, it.status)
            assertEquals("age_check_required", obj(it.bodyAsText())["code"]!!.jsonPrimitive.content)
        }
        // 20 years old today minus a day: under 21 → failed, still blocked
        val dob20 = today().minusYears(21).plusDays(1)
        val under = obj(c.postJson("/retail/sales/$sale/age-check",
            """{"method":"MANUAL","dateOfBirth":"$dob20","cashierSawId":true}""").bodyAsText())
        assertFalse(under["passed"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("under_age", under["reason"]!!.jsonPrimitive.content)
        assertEquals(20, under["ageYears"]!!.jsonPrimitive.int)
        assertTrue(under["check"]!!.jsonObject["ageCheckFailed"]!!.jsonPrimitive.content.toBoolean())
        c.postJson("/checks/$sale/tenders", """{"type":"CASH","amountTenderedCents":5000}""").let {
            assertEquals(HttpStatusCode.Conflict, it.status)
            assertEquals("age_check_failed", obj(it.bodyAsText())["code"]!!.jsonPrimitive.content)
        }
        // remove the beer and sell the rest
        val beerLine = obj(c.get("/checks/$sale").bodyAsText())["lines"]!!.jsonArray.map { it.jsonObject }
            .single { it["itemId"]!!.jsonPrimitive.content == "golden-lager-6" }["id"]!!.jsonPrimitive.int
        c.delete("/checks/$sale/lines/$beerLine")
        c.postJson("/checks/$sale/tenders", """{"type":"CASH","amountTenderedCents":5000}""")
            .let { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }

        // only the outcome is kept: no date of birth anywhere
        transaction {
            val row = AgeChecks.selectAll().single()
            assertEquals("MANUAL", row[AgeChecks.method])
            assertEquals(20, row[AgeChecks.ageYears])
            assertEquals(21, row[AgeChecks.legalAge])
            assertEquals("manager", row[AgeChecks.checkedBy])
            val event = SyncOutbox.selectAll().where { SyncOutbox.eventType eq "age.checked" }.single()[SyncOutbox.payload]
            assertFalse(dob20.toString() in event, "the date of birth never leaves the check")
            assertEquals(setOf("checkId", "method", "passed", "ageYears", "legalAge", "reason", "checkedBy", "checkedAt"),
                obj(event).keys)
        }
    }

    @Test
    fun aScannedLicenceClearsTheSaleAndTheReceiptSaysSo() = testApplication {
        shop()
        val c = loginClient()
        c.openShift()
        val sale = c.sale()
        c.scan(sale, product("coast-vodka").barcode)
        val dob = today().minusYears(35)
        val exp = today().plusYears(3)
        fun mmddyyyy(d: LocalDate) = "%02d%02d%04d".format(d.monthValue, d.dayOfMonth, d.year)
        val scan = "@\n\u001E\rANSI 636014090002DL00410278ZC03190024DLDAQD7654321\nDCSSAMPLE\nDACJORDAN\n" +
            "DBB${mmddyyyy(dob)}\nDBA${mmddyyyy(exp)}\nDCGUSA\r"
        val ok = obj(c.postJson("/retail/sales/$sale/age-check",
            Json.encodeToString(dev.dwhipstock.pos.retail.AgeCheckRequest.serializer(),
                dev.dwhipstock.pos.retail.AgeCheckRequest("SCAN", scan = scan))).bodyAsText())
        assertTrue(ok["passed"]!!.jsonPrimitive.content.toBoolean(), ok.toString())
        assertEquals(35, ok["ageYears"]!!.jsonPrimitive.int)
        c.postJson("/checks/$sale/tenders", """{"type":"CASH","amountTenderedCents":5000}""")
            .let { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }
        c.post("/checks/$sale/finalize")
        val receipt = obj(c.get("/checks/$sale/receipt").bodyAsText())["text"]!!.jsonPrimitive.content
        assertTrue("ID checked: 21+" in receipt, receipt)
        assertFalse("JORDAN" in receipt || "SAMPLE" in receipt)

        // an expired licence fails even at 35
        val sale2 = c.sale()
        c.scan(sale2, product("coast-vodka").barcode)
        val expired = scan.replace("DBA${mmddyyyy(exp)}", "DBA${mmddyyyy(today().minusDays(1))}")
        val bad = obj(c.postJson("/retail/sales/$sale2/age-check",
            Json.encodeToString(dev.dwhipstock.pos.retail.AgeCheckRequest.serializer(),
                dev.dwhipstock.pos.retail.AgeCheckRequest("SCAN", scan = expired))).bodyAsText())
        assertEquals("expired", bad["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun thePubsNeverMeetTheGate() = testApplication {
        application { module(dbPath = tempDb(), receiptsDir = Files.createTempDirectory("rc").toString()) }
        val c = loginClient()
        c.openShift()
        val id = obj(c.postJson("/tables/t5/checks").bodyAsText())["id"]!!.jsonPrimitive.int
        val check = obj(c.postJson("/checks/$id/lines",
            """{"itemId":"lantern-lager","variantId":"lantern-lager:pint","qty":1}""").bodyAsText())
        assertFalse(check["ageCheckRequired"]!!.jsonPrimitive.content.toBoolean())
        c.postJson("/checks/$id/tenders", """{"type":"CASH","amountTenderedCents":2000}""")
            .let { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }
    }

    // ---- unknown barcodes ----

    @Test
    fun aManagerAddsAnUnknownProductAtTheCounter() = testApplication {
        shop(lookup = { code -> if (code == "012345678905") ProductLookup.Suggestion("Sparkling Lemonade, 12 fl oz", "test") else null })
        val cashier = loginClient("9999")
        loginClient().openShift()
        val sale = cashier.sale()
        cashier.scan(sale, "012345678905").let {
            assertEquals(HttpStatusCode.NotFound, it.status)
            assertEquals("unknown_barcode", obj(it.bodyAsText())["code"]!!.jsonPrimitive.content)
        }
        // an online suggestion prefills the name; none is fine too
        val hit = obj(cashier.get("/retail/lookup/012345678905").bodyAsText())
        assertEquals("Sparkling Lemonade, 12 fl oz", hit["name"]!!.jsonPrimitive.content)
        val miss = obj(cashier.get("/retail/lookup/487239999990").bodyAsText())
        assertEquals("null", miss["name"].toString())

        val body = """{"barcode":"012345678905","name":"Sparkling Lemonade 12 oz","priceCents":189,
            "categoryId":"mixers","ageRestricted":false,"crvSize":"SMALL","packUnits":1,"taxable":true""".trimIndent()
        // a cashier can't add to the catalog on their own
        cashier.postJson("/retail/products", "$body}").let { assertEquals(HttpStatusCode.Forbidden, it.status) }
        // with a manager's PIN they can
        cashier.postJson("/retail/products", """$body,"managerPin":"1234"}""")
            .let { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }
        cashier.postJson("/retail/products", """$body,"managerPin":"1234"}""").let {
            assertEquals(HttpStatusCode.Conflict, it.status)
            assertEquals("barcode_taken", obj(it.bodyAsText())["code"]!!.jsonPrimitive.content)
        }
        // and the next scan sells it, CRV and all
        val check = obj(cashier.scan(sale, "012345678905").bodyAsText())
        val line = check["lines"]!!.jsonArray.single().jsonObject
        assertEquals("Sparkling Lemonade 12 oz", line["nameEn"]!!.jsonPrimitive.content)
        assertEquals(5L, line["depositCents"]!!.jsonPrimitive.long)
        // it synced up like any menu edit, barcode included
        transaction {
            val created = SyncOutbox.selectAll().where { SyncOutbox.eventType eq "item.created" }
                .orderBy(SyncOutbox.id, SortOrder.DESC).first()[SyncOutbox.payload].let(::obj)
            assertEquals("012345678905", created["item"]!!.jsonObject["barcode"]!!.jsonPrimitive.content)
        }
        assertNotNull(check["id"])
    }
}
