package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.CatalogItems
import dev.dwhipstock.poscloud.db.Checks
import dev.dwhipstock.poscloud.db.Venues
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Margins at a gas station (cloud migration 024): in-store net of promotions,
 * fuel in cents per gallon, and a missing cost is unknown — counted, never
 * taken as zero.
 */
class FuelMarginTest {

    private val key = "margin-key"
    private lateinit var session: String
    private val q = "from=2026-09-26&to=2026-09-26&venue=pronghorn"

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("pronghorn", "pronghorn", "Pronghorn Fuel & Market")
        transaction {
            Venues.update({ (Venues.tenantId eq "pronghorn") and (Venues.id eq "pronghorn") }) {
                it[currency] = "USD"; it[country] = "US"; it[kind] = "retail"; it[timezone] = "America/Chicago"
            }
        }
        seedStoreKey("pronghorn", "pronghorn", key)
        session = seedSession("pronghorn", seedUser("pronghorn", "owner@margin.test", "password-margin"))
    }

    private suspend fun ApplicationTestBuilder.get(path: String): JsonObject {
        val res = client.get(path) { header(HttpHeaders.Cookie, "pos_portal_session=$session") }
        assertEquals(HttpStatusCode.OK, res.status, "$path → ${res.bodyAsText()}")
        return testJson.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    private fun JsonObject.long(key: String) = this[key]!!.jsonPrimitive.content.toLong()
    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.obj(key: String) = this[key]!!.jsonObject
    private fun JsonObject.arr(key: String): List<JsonObject> = this[key]!!.jsonArray.map { it.jsonObject }

    private fun line(lineId: Int, itemId: String, category: String, qty: Int, unit: Long, unitCost: Long?) = buildJsonObject {
        put("lineId", lineId); put("itemId", itemId); put("categoryId", category)
        put("nameEn", itemId); put("nameFr", itemId)
        put("qty", qty); put("unitPriceCents", unit); put("lineTotalCents", unit * qty)
        unitCost?.let { put("unitCostCents", it) }
    }

    private fun sale(checkId: Int, discount: Long?, vararg lines: JsonObject) = buildJsonObject {
        val total = lines.sumOf { it["lineTotalCents"]!!.jsonPrimitive.content.toLong() } - (discount ?: 0)
        put("currency", "USD"); put("checkId", checkId)
        put("grandTotalCents", total); put("taxIncludedCents", 0)
        put("closedAt", "2026-09-26T12:0$checkId:00.000-05:00")
        put("lines", buildJsonArray { lines.forEach { add(it) } })
        discount?.let {
            put("discounts", buildJsonArray {
                add(buildJsonObject { put("code", "energy-2for5"); put("label", "2 for \$5 energy drinks"); put("amountCents", it) })
            })
        }
    }

    private fun fuelSale(id: Int, grade: String, volumeMilli: Long, amount: Long, costMills: Long?, cost: Long?) = buildJsonObject {
        put("currency", "USD"); put("fuelSaleId", id); put("checkId", id); put("pump", 1)
        put("grade", grade); put("gradeName", grade); put("volumeMilli", volumeMilli)
        put("priceMills", 2999); put("amountCents", amount); put("mode", "POSTPAY")
        costMills?.let { put("costMills", it) }
        cost?.let { put("costCents", it) }
        put("completedAt", "2026-09-26T09:00:00.000-05:00")
    }

    private suspend fun ApplicationTestBuilder.seed() {
        ingest(key,
            // two energy drinks with the 2-for-$5 promotion (98¢ off) and a bag of chips, all costed
            event("check.closed", sale(1, 98,
                line(1, "energy", "drinks", 2, 299, 150),
                line(2, "chips", "snacks", 1, 249, 120),
            ), seq = 1, aggregateId = "1"),
            // a coffee from a till that sends no cost, and a fuel line (not in-store)
            event("check.closed", sale(2, null,
                line(1, "coffee", "hot", 1, 199, null),
                buildJsonObject {
                    put("lineId", 2); put("itemId", "fuel-reg"); put("categoryId", "fuel"); put("qty", 1)
                    put("unitPriceCents", 5998); put("lineTotalCents", 5998); put("unitCostCents", 5398)
                },
            ), seq = 2, aggregateId = "2"),
            // 20 gal of regular at $2.999, cost $2.699 → 30.0¢/gal; 10 gal of mid-grade with no cost sent
            event("fuel.sale", fuelSale(1, "REG", 20000, 5998, 2699, 5398), seq = 3, aggregateType = "fuel_sale"),
            event("fuel.sale", fuelSale(2, "MID", 10000, 3299, null, null), seq = 4, aggregateType = "fuel_sale"),
        )
    }

    @Test
    fun inStoreMarginIsNetOfPromotionsAndOnlyOverCostedLines() = testApplication {
        application { module(TestSupport.config) }
        seed()
        val inStore = get("/v1/reports/fuel?$q").obj("inStore")
        assertEquals(598L + 249 + 199, inStore.long("grossSalesCents"))
        assertEquals(98, inStore.long("discountCents"))
        assertEquals(598L + 249 + 199 - 98, inStore.long("salesCents"), "net in-store sales")
        assertEquals(3, inStore.long("lineCount"))
        assertEquals(2, inStore.long("costedLineCount"))
        assertEquals(1, inStore.long("uncostedLineCount"), "the coffee's cost is unknown, not zero")
        // the 98¢ is shared 70/28 (598 : 249, the rounding cent to the larger line)
        assertEquals(598L + 249 - 98, inStore.long("costedSalesCents"))
        assertEquals(2L * 150 + 120, inStore.long("costCents"))
        val margin = 598L + 249 - 98 - 420
        assertEquals(margin, inStore.long("marginCents"))
        assertEquals(Math.round(margin * 10_000.0 / (598 + 249 - 98)), inStore.long("marginBasisPoints"))

        // categories, best margin first; the uncosted one last, with no margin ratio
        val cats = get("/v1/reports/fuel?$q").arr("inStoreByCategory")
        assertEquals(listOf("drinks", "snacks", "hot"), cats.map { it.str("categoryId") })
        cats[0].let {
            assertEquals(598L - 70, it.long("salesCents")); assertEquals(300, it.long("costCents"))
            assertEquals(598L - 70 - 300, it.long("marginCents"))
        }
        cats[1].let { assertEquals(249L - 28, it.long("salesCents")); assertEquals(249L - 28 - 120, it.long("marginCents")) }
        cats[2].let {
            assertEquals(199, it.long("salesCents")); assertEquals(1, it.long("uncostedLineCount"))
            assertEquals(0, it.long("marginCents")); assertEquals(JsonNull, it["marginBasisPoints"])
        }

        transaction {
            val row = Checks.selectAll().where { (Checks.tenantId eq "pronghorn") and (Checks.checkId eq 1) }.single()
            assertEquals(98L, row[Checks.discountCents])
            val legacy = Checks.selectAll().where { (Checks.tenantId eq "pronghorn") and (Checks.checkId eq 2) }.single()
            assertNull(legacy[Checks.discountCents], "no discounts sent → none recorded")
        }
    }

    @Test
    fun fuelMarginIsCentsPerGallonOverCostedFuellings() = testApplication {
        application { module(TestSupport.config) }
        seed()
        val report = get("/v1/reports/fuel?$q")
        report.obj("fuel").let {
            assertEquals(30000, it.long("volumeMilli"))
            assertEquals(5998L + 3299, it.long("amountCents"))
            assertEquals(1, it.long("costedCount")); assertEquals(1, it.long("uncostedCount"))
            assertEquals(20000, it.long("costedVolumeMilli")); assertEquals(5998, it.long("costedAmountCents"))
            assertEquals(5398, it.long("costCents")); assertEquals(600, it.long("marginCents"))
            assertEquals(300, it.long("marginMillsPerGallon"), "30.0¢ a gallon")
        }
        val grades = report.arr("byGrade").associateBy { it.str("grade") }
        assertEquals(300, grades["REG"]!!.long("marginMillsPerGallon"))
        grades["MID"]!!.let {
            assertEquals(0, it.long("costedCount")); assertEquals(0, it.long("marginCents"))
            assertEquals(JsonNull, it["marginMillsPerGallon"], "no cost → no margin, not 100%")
        }
        report.arr("byVenue").single().let {
            assertEquals(600, it.long("fuelMarginCents")); assertEquals(300, it.long("fuelMarginMillsPerGallon"))
            assertEquals(598L + 249 - 98 - 420, it.long("inStoreMarginCents"))
            assertEquals(598L + 249 + 199 - 98, it.long("inStoreSalesCents"))
        }
    }

    @Test
    fun anOlderStoreWithNoCostsShowsNoMargin() = testApplication {
        application { module(TestSupport.config) }
        ingest(key,
            event("check.closed", sale(1, null, line(1, "chips", "snacks", 1, 249, null)), seq = 1),
            event("fuel.sale", fuelSale(1, "REG", 10000, 2999, null, null), seq = 2, aggregateType = "fuel_sale"),
        )
        val report = get("/v1/reports/fuel?$q")
        assertEquals(JsonNull, report.obj("fuel")["marginMillsPerGallon"])
        assertEquals(1, report.obj("fuel").long("uncostedCount"))
        assertEquals(JsonNull, report.obj("inStore")["marginBasisPoints"])
        assertEquals(249, report.obj("inStore").long("salesCents"))
        assertEquals(0, report.obj("inStore").long("discountCents"))
    }

    @Test
    fun itemSnapshotsKeepTheirCost() = testApplication {
        application { module(TestSupport.config) }
        ingest(key, event("catalog.snapshot", buildJsonObject {
            put("items", buildJsonArray {
                add(buildJsonObject { put("id", "energy"); put("nameEn", "Energy"); put("nameFr", "Energy"); put("categoryId", "drinks"); put("costCents", 150) })
                add(buildJsonObject { put("id", "coffee"); put("nameEn", "Coffee"); put("nameFr", "Coffee"); put("categoryId", "hot") })
            })
        }, seq = 1))
        transaction {
            val cost = CatalogItems.selectAll().where { CatalogItems.tenantId eq "pronghorn" }
                .associate { it[CatalogItems.id] to it[CatalogItems.costCents] }
            assertEquals(150L, cost["energy"])
            assertNull(cost["coffee"])
        }
    }
}
