package dev.dwhipstock.pos

import dev.dwhipstock.pos.customers.pronghorn.Pronghorn
import dev.dwhipstock.pos.customers.pronghorn.PronghornCatalog
import dev.dwhipstock.pos.customers.pronghorn.PronghornCatalog.Cat
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.forecourt.ForecourtService
import dev.dwhipstock.pos.sdk.AgeCheckMode
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
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pronghorn's shop: the counter's own drinks by cup size with a flavour, hot
 * food, the deals on the basket and receipt, tax on prepared food vs
 * groceries, what each thing costs, cigarettes and the ID check rules, and
 * what the shelf must never carry.
 */
class CStoreTest {
    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()
    private val json = Json { ignoreUnknownKeys = true }
    private fun obj(s: String): JsonObject = json.parseToJsonElement(s).jsonObject
    private lateinit var fc: ForecourtService

    private fun ApplicationTestBuilder.station(fake: FakeForecourt = FakeForecourt(), age: AgeCheckMode = AgeCheckMode.ALWAYS) =
        application {
            module(
                dbPath = tempDb(), receiptsDir = Files.createTempDirectory("rc").toString(),
                billsDir = Files.createTempDirectory("bl").toString(),
                venueId = Pronghorn.VENUE_ID, physicalPrinterEnabled = false,
                forecourtAdapter = fake, forecourtPoll = false, onForecourt = { fc = it }, ageCheckMode = age,
            )
        }

    private suspend fun HttpClient.postJson(path: String, body: String = "{}") =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.sale(): Int = obj(postJson("/retail/sales").bodyAsText())["id"]!!.jsonPrimitive.int

    private suspend fun HttpClient.add(sale: Int, p: PronghornCatalog.Product, size: String? = null, note: String? = null): JsonObject {
        val v = p.variants.firstOrNull { size == null || it.label == size } ?: error("no size $size on ${p.id}")
        val noteJson = note?.let { ""","note":"$it"""" } ?: ""
        return obj(postJson("/checks/$sale/lines", """{"itemId":"${p.id}","variantId":"${v.id}","qty":1$noteJson}""")
            .also { assertTrue(it.status.isSuccess(), it.bodyAsText()) }.bodyAsText())
    }

    private suspend fun HttpClient.scan(sale: Int, p: PronghornCatalog.Product): JsonObject =
        obj(postJson("/retail/sales/$sale/scan", """{"barcode":"${p.barcode}"}""")
            .also { assertTrue(it.status.isSuccess(), it.bodyAsText()) }.bodyAsText())

    private suspend fun HttpClient.payCash(sale: Int) {
        postJson("/shifts", """{"openingFloatCents":20000,"managerPin":"1234"}""")
        postJson("/checks/$sale/tenders", """{"type":"CASH","amountTenderedCents":20000}""")
            .also { assertTrue(it.status.isSuccess(), it.bodyAsText()) }
        post("/checks/$sale/finalize").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
    }

    private fun outbox(type: String) = transaction {
        SyncOutbox.selectAll().where { SyncOutbox.eventType eq type }.map { json.parseToJsonElement(it[SyncOutbox.payload]).jsonObject }
    }

    private fun product(id: String) = PronghornCatalog.products.single { it.id == id }
    private fun first(test: (PronghornCatalog.Product) -> Boolean) = PronghornCatalog.shelf.first(test)
    private fun tax(cents: Long) = BigDecimal(cents).multiply(BigDecimal("8.25")).divide(BigDecimal(100), 0, RoundingMode.HALF_UP).toLong()

    // ---- the shelf ----

    @Test
    fun theShelfCarriesNoQuestionableSupplements() {
        val deny = listOf(
            "pill", "pills", "enhancement", "male", "kratom", "cbd", "delta-8", "delta 8", "delta-9", "thc", "hemp", "kava",
            "herbal", "energy shot", "supplement", "libido", "stamina", "tianeptine", "poppers",
        )
        for (p in PronghornCatalog.products) {
            val text = "${p.name} ${p.subcategory} ${p.brand}".lowercase()
            deny.forEach { w -> assertTrue(!Regex("\\b${Regex.escape(w)}\\b").containsMatchIn(text), "'$w' in ${p.id}: ${p.name}") }
        }
        // plenty of energy drinks, though
        assertTrue(PronghornCatalog.shelf.count { it.subcategory == "Energy" } >= 40)
    }

    @Test
    fun everythingHasACostAndTheShopEarnsMoreThanThePumps() {
        for (p in PronghornCatalog.products.filter { it.id != "fuel-prepay" })
            p.variants.forEach { v -> assertTrue(v.costCents in 1..v.cents, "${v.id}: cost ${v.costCents} of ${v.cents}") }
        fun margin(ps: List<PronghornCatalog.Product>) =
            ps.sumOf { it.cents - it.costCents }.toDouble() / ps.sumOf { it.cents }
        val shop = PronghornCatalog.shelf.filter { it.cat != Cat.TOBACCO && it.subcategory != "Deposit" }
        assertTrue(margin(shop) in 0.30..0.55, "in-store margin ${margin(shop)}")
        assertTrue(margin(PronghornCatalog.shelf.filter { it.cat == Cat.TOBACCO }) < 0.2, "tobacco margin is thin")
        // fuel: a few cents a gallon
        Pronghorn.GRADES.forEach { assertTrue(it.priceMills - it.costMills in 100..300, it.code) }
    }

    @Test
    fun cigarettesComeByThePackAndTheCartonBehindTheCounter() {
        val cigs = PronghornCatalog.shelf.filter { it.subcategory == "Cigarettes" }
        assertTrue(cigs.isNotEmpty())
        assertTrue(cigs.all { it.cat == Cat.TOBACCO && it.ageRestricted && it.taxable && !it.barcodeless })
        for (pack in cigs.filter { it.size == "Pack" }) {
            val carton = cigs.single { it.name == pack.name.replace("King Pack", "King Carton") }
            assertTrue(carton.barcode != pack.barcode, "a carton has its own barcode")
            assertTrue(carton.cents in pack.cents * 8..pack.cents * 10, "10 packs, a little cheaper: ${carton.cents} vs ${pack.cents}")
        }
    }

    @Test
    fun theCounterSellsCupsBySizeHotFoodAndPropane() {
        val fountain = product("ph-fountain-drink")
        assertEquals(listOf("Small 22 oz", "Medium 32 oz", "Large 44 oz", "Jumbo 64 oz", "Refill"), fountain.variants.map { it.label })
        assertTrue(product("ph-frozen-slush").variants.any { it.label == "Refill" })
        assertTrue(product("ph-coffee").variants.size >= 3)
        listOf("ph-hot-dog", "ph-nachos-with-pump-cheese", "ph-add-jalapenos", "ph-add-chili", "ph-pizza-slice-pepperoni",
            "ph-breakfast-sandwich-sausage-egg-cheese", "ph-taquito-beef").forEach {
            val p = product(it)
            assertTrue(p.cat == Cat.HOT && p.taxable && p.barcodeless, it)
        }
        val exchange = product("ph-propane-exchange-20-lb-trade-in-a-tank")
        val deposit = product("ph-propane-tank-deposit-no-tank-to-trade")
        assertTrue(exchange.taxable && !deposit.taxable, "the deposit is money held, not a sale")
        assertEquals(deposit.cents, deposit.costCents, "no margin on a deposit")
    }

    // ---- at the counter ----

    @Test
    fun aCupSizeAndFlavourRingUpAtThatSizesPrice() = testApplication {
        station()
        val c = loginClient()
        val sale = c.sale()
        val view = c.add(sale, product("ph-frozen-slush"), size = "Large 44 oz", note = "Blue Raspberry")
        val line = view["lines"]!!.jsonArray.single().jsonObject
        assertEquals(299, line["unitPriceCents"]!!.jsonPrimitive.long)
        assertEquals("Large 44 oz", line["variantLabelEn"]!!.jsonPrimitive.content)
        assertEquals("Blue Raspberry", line["note"]!!.jsonPrimitive.content)
        val refill = c.add(sale, product("ph-frozen-slush"), size = "Refill", note = "Cherry")["lines"]!!.jsonArray
        assertEquals(129, refill.last().jsonObject["unitPriceCents"]!!.jsonPrimitive.long)
        c.payCash(sale)
        val receipt = c.get("/checks/$sale/receipt").bodyAsText()
        assertTrue(receipt.contains("Frozen Slush (Large 44 oz)"), receipt)
        assertTrue(receipt.contains("• Blue Raspberry"), receipt)
    }

    @Test
    fun hotFoodIsTaxedGroceriesAreNot() = testApplication {
        station()
        val c = loginClient()
        val sale = c.sale()
        val pizza = product("ph-pizza-slice-pepperoni")
        val eggs = first { it.name.endsWith("Large Eggs Dozen") }
        val bread = first { it.name.endsWith("White Bread Loaf") }
        c.add(sale, pizza)
        c.scan(sale, eggs)
        val view = c.scan(sale, bread)
        assertEquals(tax(pizza.cents), view["taxes"]!!.jsonArray.single().jsonObject["amountCents"]!!.jsonPrimitive.long,
            "only the prepared food is taxed")
    }

    @Test
    fun theDealsShowOnTheBasketAndReceiptAndComeOffBeforeTax() = testApplication {
        val fake = FakeForecourt()
        station(fake)
        val c = loginClient()
        fc.tick()
        c.post("/forecourt/pumps/1/authorise")
        val trx = fake.fillUp(1, "REG", 9_500)
        fc.tick()
        val sale = c.sale()
        c.postJson("/retail/sales/$sale/fuel", """{"trxId":"${trx.trxId}"}""")
        val cans = PronghornCatalog.shelf.filter { it.subcategory == "Energy" && it.size == "16 oz can" }.take(2)
        cans.forEach { c.scan(sale, it) }
        c.add(sale, product("ph-hot-dog"))
        c.add(sale, product("ph-fountain-drink"), size = "Large 44 oz", note = "Cola")
        val view = c.add(sale, product("ph-coffee"), size = "Medium 16 oz")
        val discounts = view["discounts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("energy-2for5", "hotdog-fountain", "coffee-fuel"), discounts.map { it["code"]!!.jsonPrimitive.content }.toSet())
        val off = discounts.sumOf { it["amountCents"]!!.jsonPrimitive.long }
        val cansGross = cans.sumOf { it.cents }
        assertEquals((cansGross - 500) + (179 + 189 - 300) + 100, off)
        // tax on what the customer pays for the shop goods (all taxable here); none on fuel
        val shopGross = cansGross + 179 + 189 + 179
        assertEquals(tax(shopGross - off), view["taxes"]!!.jsonArray.single().jsonObject["amountCents"]!!.jsonPrimitive.long)
        assertEquals(trx.amountCents + shopGross - off + tax(shopGross - off), view["grandTotalCents"]!!.jsonPrimitive.long)
        c.payCash(sale)
        val receipt = c.get("/checks/$sale/receipt").bodyAsText()
        assertTrue(receipt.contains("2 for \$5 energy drinks"), receipt)
        assertTrue(receipt.contains("\$1 off coffee with 8+ gal"), receipt)
        // what syncs: the deals, and the cost of every line (fuel at its cost per gallon)
        val closed = outbox("check.closed").single()
        assertEquals(off, closed["discounts"]!!.jsonArray.sumOf { it.jsonObject["amountCents"]!!.jsonPrimitive.long })
        val lines = closed["lines"]!!.jsonArray.map { it.jsonObject }
        assertTrue(lines.all { it["unitCostCents"] != null }, "every line carries its cost")
        val fuelLine = lines.single { it["categoryId"]?.jsonPrimitive?.content == "fuel" }
        assertEquals(dev.dwhipstock.pos.forecourt.fuelAmountCents(9_500, 2_689), fuelLine["unitCostCents"]!!.jsonPrimitive.long)
        val fuelSale = outbox("fuel.sale").single()
        assertEquals(2_689, fuelSale["costMills"]!!.jsonPrimitive.long)
        assertEquals(dev.dwhipstock.pos.forecourt.fuelAmountCents(9_500, 2_689), fuelSale["costCents"]!!.jsonPrimitive.long)
        // a later catalog change never re-prices the closed sale's deals
        assertEquals(off, obj(c.get("/checks/$sale").bodyAsText())["discounts"]!!.jsonArray
            .sumOf { it.jsonObject["amountCents"]!!.jsonPrimitive.long })
    }

    // ---- ID checks ----

    private val cigarettes = PronghornCatalog.shelf.first { it.subcategory == "Cigarettes" }
    private val beer = PronghornCatalog.shelf.first { it.cat == Cat.BEER && it.ageRestricted }

    @Test
    fun byDefaultEveryRestrictedSaleNeedsAnId() = testApplication {
        station()
        val c = loginClient()
        val sale = c.sale()
        c.scan(sale, beer)
        c.postJson("/retail/sales/$sale/age-check", """{"method":"VISUAL","cashierSawId":true}""").also {
            assertEquals(HttpStatusCode.Conflict, it.status)
            assertEquals("id_required", obj(it.bodyAsText())["code"]!!.jsonPrimitive.content)
        }
        assertEquals(null, obj(c.get("/health").bodyAsText())["looksOverAge"])
    }

    @Test
    fun looksUnderModePassesBeerOnSightButNeverTobacco() = testApplication {
        station(age = AgeCheckMode.parse("looks-under:40"))
        val c = loginClient()
        assertEquals(40, obj(c.get("/health").bodyAsText())["looksOverAge"]!!.jsonPrimitive.int)
        val sale = c.sale()
        c.scan(sale, beer)
        val passed = obj(c.postJson("/retail/sales/$sale/age-check", """{"method":"VISUAL","cashierSawId":true}""").bodyAsText())
        assertTrue(passed["check"]!!.jsonObject["ageCleared"]!!.jsonPrimitive.content == "true")
        // add cigarettes: the visual check no longer clears the sale
        val withCigs = c.scan(sale, cigarettes)
        assertEquals("false", withCigs["ageCleared"]!!.jsonPrimitive.content)
        c.postJson("/retail/sales/$sale/age-check", """{"method":"VISUAL","cashierSawId":true}""").also {
            assertEquals("id_required_tobacco", obj(it.bodyAsText())["code"]!!.jsonPrimitive.content)
        }
        val dob = LocalDate.now().minusYears(35).toString()
        val id = obj(c.postJson("/retail/sales/$sale/age-check",
            """{"method":"MANUAL","dateOfBirth":"$dob","cashierSawId":true}""").bodyAsText())
        assertEquals("true", id["check"]!!.jsonObject["ageCleared"]!!.jsonPrimitive.content)
    }

    @Test
    fun theAgeCheckSettingParsesSafely() {
        assertEquals(AgeCheckMode.ALWAYS, AgeCheckMode.parse(null))
        assertEquals(AgeCheckMode.ALWAYS, AgeCheckMode.parse("sometimes"))
        assertEquals(AgeCheckMode.ALWAYS, AgeCheckMode.parse("looks-under:12"), "under the legal age is nonsense")
        assertEquals(30, AgeCheckMode.parse(" LOOKS-UNDER:30 ").looksOver)
    }
}
