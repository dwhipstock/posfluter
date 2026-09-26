package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.CheckLines
import dev.dwhipstock.poscloud.db.FuelSales
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
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A gas station with a shop (CONTRACT §2, Fuel): `fuel.sale` is projected
 * idempotently, `check.closed` fuel lines keep their pump details, and the
 * fuel report splits fuel (what the pumps dispensed, by grade) from in-store
 * sales (the non-fuel lines of closed checks), over each store's own days.
 */
class FuelReportTest {

    private val key1 = "fuel-key-1"
    private val key2 = "fuel-key-2"
    private lateinit var session: String
    private val q = "from=2026-09-26&to=2026-09-26"

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("pronghorn", "pronghorn", "Pronghorn Fuel & Market")
        seedTenant("pronghorn", "pronghorn-2", "Pronghorn Fuel & Market — Two")
        transaction {
            for (id in listOf("pronghorn", "pronghorn-2")) {
                Venues.update({ (Venues.tenantId eq "pronghorn") and (Venues.id eq id) }) {
                    it[currency] = "USD"; it[country] = "US"; it[kind] = "retail"
                    it[timezone] = "America/Chicago"
                }
            }
        }
        seedStoreKey("pronghorn", "pronghorn", key1)
        seedStoreKey("pronghorn", "pronghorn-2", key2)
        session = seedSession("pronghorn", seedUser("pronghorn", "owner@fuel.test", "password-fuel"))
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

    private fun fuelSale(
        id: Int, checkId: Int, pump: Int, grade: String, gradeName: String,
        volumeMilli: Long, priceMills: Long, amount: Long, completedAt: String,
        prepaid: Long? = null, refund: Long? = null,
    ) = buildJsonObject {
        put("currency", "USD"); put("country", "US")
        put("fuelSaleId", id); put("checkId", checkId); put("pump", pump); put("nozzle", 2)
        put("grade", grade); put("gradeName", gradeName)
        put("volumeMilli", volumeMilli); put("priceMills", priceMills); put("amountCents", amount)
        put("mode", if (prepaid != null) "PREPAY" else "POSTPAY")
        prepaid?.let { put("prepaidCents", it) }
        refund?.let { put("refundCents", it); if (it > 0) put("refundId", 9) }
        put("fdcTransactionId", "T-%06d".format(id))
        put("completedAt", completedAt)
    }

    private fun shopLine(lineId: Int, itemId: String, qty: Int, unit: Long) = buildJsonObject {
        put("lineId", lineId); put("itemId", itemId); put("categoryId", "snacks")
        put("nameEn", itemId); put("nameFr", itemId)
        put("qty", qty); put("unitPriceCents", unit); put("lineTotalCents", unit * qty)
    }

    private fun fuelLine(lineId: Int, grade: String?, amount: Long, fuel: JsonObject) = buildJsonObject {
        put("lineId", lineId); put("categoryId", "fuel"); put("taxable", false)
        put("itemId", if (grade == null) "fuel-prepay" else "fuel-${grade.lowercase()}")
        put("nameEn", grade ?: "Prepay"); put("nameFr", grade ?: "Prepay")
        put("qty", 1); put("unitPriceCents", amount); put("lineTotalCents", amount)
        put("fuel", fuel)
    }

    /** A Texas sale: fuel lines untaxed, shop lines with an 8.25% sales tax on top. */
    private fun sale(checkId: Int, closedAt: String, vararg lines: JsonObject) = buildJsonObject {
        val shop = lines.filter { it["categoryId"]!!.jsonPrimitive.content != "fuel" }
            .sumOf { it["lineTotalCents"]!!.jsonPrimitive.content.toLong() }
        val all = lines.sumOf { it["lineTotalCents"]!!.jsonPrimitive.content.toLong() }
        val tax = (shop * 825 * 2 + 10_000) / 20_000
        put("currency", "USD"); put("country", "US")
        put("checkId", checkId); put("grandTotalCents", all + tax); put("taxIncludedCents", tax)
        put("subtotalCents", all); put("closedAt", closedAt)
        put("tableId", "register-1"); put("zoneId", "counter")
        put("lines", buildJsonArray { lines.forEach { add(it) } })
        put("tenders", buildJsonArray {
            add(buildJsonObject {
                put("tenderId", checkId); put("type", "CARD")
                put("amountTenderedCents", all + tax); put("amountAppliedCents", all + tax)
            })
        })
    }

    private suspend fun ApplicationTestBuilder.seedStation() {
        // check 42: a $40 prepay on pump 3 and a snack; the pump stopped at $33.16, $6.84 handed back
        ingest(key1,
            event("check.closed", sale(42, "2026-09-26T14:58:00.000-05:00",
                fuelLine(1, null, 4000, buildJsonObject { put("pump", 3); put("mode", "PREPAY"); put("prepaidCents", 4000) }),
                shopLine(2, "jerky", 1, 250),
            ), seq = 1, aggregateId = "42", createdAt = "2026-09-26T14:58:00.000-05:00"),
            event("fuel.sale", fuelSale(17, 42, 3, "MID", "Mid-Grade", 10052, 3299, 3316,
                "2026-09-26T15:04:05.000-05:00", prepaid = 4000, refund = 684),
                seq = 2, aggregateType = "fuel_sale", aggregateId = "17", createdAt = "2026-09-26T15:04:05.000-05:00"),
            event("refund.created", buildJsonObject {
                put("currency", "USD"); put("refundId", 9); put("checkId", 42); put("fuelSaleId", 17)
                put("grossCents", 684); put("taxIncludedCents", 0); put("netCents", 684)
                put("tenderType", "CARD"); put("reason", "Prepay change")
                put("createdAt", "2026-09-26T15:04:06.000-05:00")
            }, seq = 3, aggregateId = "9"),
            // check 43: pay for pump 1 after filling (postpay), plus two drinks; late evening, local
            event("check.closed", sale(43, "2026-09-26T23:30:00.000-05:00",
                fuelLine(1, "REG", 3599, buildJsonObject {
                    put("pump", 1); put("nozzle", 1); put("grade", "REG"); put("volumeMilli", 12000)
                    put("priceMills", 2999); put("mode", "POSTPAY"); put("fdcTransactionId", "T-000018")
                }),
                shopLine(2, "cola", 2, 199),
            ), seq = 4, aggregateId = "43", createdAt = "2026-09-26T23:30:00.000-05:00"),
            event("fuel.sale", fuelSale(18, 43, 1, "REG", "Regular", 12000, 2999, 3599,
                "2026-09-26T23:30:00.000-05:00"),
                seq = 5, aggregateType = "fuel_sale", aggregateId = "18", createdAt = "2026-09-26T23:30:00.000-05:00"),
            // the day before (local): out of range
            event("fuel.sale", fuelSale(16, 41, 2, "PRE", "Premium", 5000, 3899, 1950,
                "2026-09-25T23:59:00.000-05:00"),
                seq = 6, aggregateType = "fuel_sale", aggregateId = "16", createdAt = "2026-09-25T23:59:00.000-05:00"),
        )
        // the second station: one diesel fill and a shop sale
        ingest(key2,
            event("check.closed", sale(1, "2026-09-26T10:00:00.000-05:00",
                fuelLine(1, "DSL", 7000, buildJsonObject { put("pump", 5); put("grade", "DSL"); put("volumeMilli", 20000) }),
                shopLine(2, "coffee", 1, 500),
            ), seq = 1, aggregateId = "1"),
            event("fuel.sale", fuelSale(1, 1, 5, "DSL", "Diesel", 20000, 3500, 7000,
                "2026-09-26T10:00:00.000-05:00"), seq = 2, aggregateType = "fuel_sale", aggregateId = "1"),
        )
    }

    @Test
    fun fuelSaleIsProjectedOnceAndRedeliveryIsANoOp() = testApplication {
        application { module(TestSupport.config) }
        val sale = fuelSale(17, 42, 3, "MID", "Mid-Grade", 10052, 3299, 3316,
            "2026-09-26T15:04:05.000-05:00", prepaid = 4000, refund = 684)
        val first = event("fuel.sale", sale, seq = 1, aggregateType = "fuel_sale", aggregateId = "17")
        ingest(key1, first)
        // the same event again (a lost ack), then a re-sent copy under a new event id
        val again = ingest(key1, first)
        assertEquals(1, again["duplicates"]!!.jsonPrimitive.content.toInt())
        ingest(key1, event("fuel.sale", sale, seq = 2, aggregateType = "fuel_sale", aggregateId = "17"))

        transaction {
            val rows = FuelSales.selectAll().where { FuelSales.tenantId eq "pronghorn" }.toList()
            assertEquals(1, rows.size)
            val r = rows.single()
            assertEquals("pronghorn", r[FuelSales.venueId])
            assertEquals(42, r[FuelSales.checkId])
            assertEquals(3, r[FuelSales.pump])
            assertEquals(2, r[FuelSales.nozzle])
            assertEquals("MID", r[FuelSales.grade])
            assertEquals("Mid-Grade", r[FuelSales.gradeName])
            assertEquals(10052L, r[FuelSales.volumeMilli])
            assertEquals(3299L, r[FuelSales.priceMills])
            assertEquals(3316L, r[FuelSales.amountCents])
            assertEquals("PREPAY", r[FuelSales.mode])
            assertEquals(4000L, r[FuelSales.prepaidCents])
            assertEquals(684L, r[FuelSales.refundCents])
            assertEquals("T-000017", r[FuelSales.fdcTransactionId])
            assertEquals("USD", r[FuelSales.currency])
            // the store's local time, kept as the instant it was
            assertEquals(java.time.OffsetDateTime.parse("2026-09-26T20:04:05Z").toInstant(), r[FuelSales.completedAt]!!.toInstant())
        }
    }

    @Test
    fun fuelLinesKeepTheirPumpDetailsAndShopLinesDoNot() = testApplication {
        application { module(TestSupport.config) }
        seedStation()
        transaction {
            val lines = CheckLines.selectAll().where {
                (CheckLines.tenantId eq "pronghorn") and (CheckLines.venueId eq "pronghorn") and (CheckLines.checkId eq 43)
            }.associateBy { it[CheckLines.itemId] }
            val fuel = assertNotNull(lines["fuel-reg"])
            assertEquals("fuel", fuel[CheckLines.categoryId])
            val details = testJson.parseToJsonElement(assertNotNull(fuel[CheckLines.fuel])).jsonObject
            assertEquals("12000", details["volumeMilli"]!!.jsonPrimitive.content)
            assertEquals("POSTPAY", details["mode"]!!.jsonPrimitive.content)
            assertNull(lines["cola"]!![CheckLines.fuel])
        }
    }

    @Test
    fun fuelReportSplitsFuelByGradeFromInStoreSales() = testApplication {
        application { module(TestSupport.config) }
        seedStation()

        val one = get("/v1/reports/fuel?$q&venue=pronghorn")
        assertEquals("USD", one.str("currency"))
        val grades = one.arr("byGrade")
        assertEquals(listOf("REG", "MID"), grades.map { it.str("grade") }, "store order, the day before left out")
        grades[0].let {
            assertEquals("Regular", it.str("gradeName")); assertEquals(12000, it.long("volumeMilli"))
            assertEquals(3599, it.long("amountCents")); assertEquals(1, it.long("count")); assertEquals("USD", it.str("currency"))
        }
        grades[1].let {
            assertEquals("Mid-Grade", it.str("gradeName")); assertEquals(10052, it.long("volumeMilli"))
            // what the pump dispensed — the $6.84 change is a refund, not taken off again
            assertEquals(3316, it.long("amountCents"))
        }
        one.obj("fuel").let {
            assertEquals(22052, it.long("volumeMilli")); assertEquals(3599L + 3316, it.long("amountCents"))
            assertEquals(2, it.long("count")); assertEquals(1, it.long("prepayCount"))
            assertEquals(4000, it.long("prepaidCents")); assertEquals(684, it.long("prepayRefundCents"))
        }
        // in-store: the jerky and the two colas, pre-tax; the prepay and fuel lines are not shop sales
        one.obj("inStore").let {
            assertEquals(250L + 398, it.long("salesCents")); assertEquals(2, it.long("lineCount"))
            assertEquals(3, it.long("qty")); assertEquals(2, it.long("checkCount"))
        }
        assertEquals(listOf("pronghorn"), one.arr("byVenue").map { it.str("venueId") })
        assertEquals(false, one.obj("money")["approximate"]!!.jsonPrimitive.content.toBooleanStrict())

        // the prepay change still shows as a refund, as every refund does
        val refunds = get("/v1/reports/refunds?$q&venue=pronghorn")
        assertEquals(684, refunds.long("grossCents"))
        assertEquals("Prepay change", refunds.arr("byReason").single().str("reason"))

        // all stores: both stations, one currency, exact
        val all = get("/v1/reports/fuel?$q")
        assertEquals(listOf("REG", "MID", "DSL"), all.arr("byGrade").map { it.str("grade") })
        all.obj("fuel").let {
            assertEquals(42052, it.long("volumeMilli")); assertEquals(3599L + 3316 + 7000, it.long("amountCents"))
            assertEquals(3, it.long("count"))
        }
        assertEquals(250L + 398 + 500, all.obj("inStore").long("salesCents"))
        assertEquals(3, all.obj("inStore").long("checkCount"))
        val byVenue = all.arr("byVenue").associateBy { it.str("venueId") }
        assertEquals(6915, byVenue["pronghorn"]!!.long("fuelAmountCents"))
        assertEquals(7000, byVenue["pronghorn-2"]!!.long("fuelAmountCents"))
        assertEquals(20000, byVenue["pronghorn-2"]!!.long("fuelVolumeMilli"))
        assertEquals(500, byVenue["pronghorn-2"]!!.long("inStoreSalesCents"))
        assertEquals(1, byVenue["pronghorn-2"]!!.long("inStoreCheckCount"))

        // the other station alone: none of the first one's fuel
        val two = get("/v1/reports/fuel?$q&venue=pronghorn-2")
        assertEquals(listOf("DSL"), two.arr("byGrade").map { it.str("grade") })
        assertEquals(7000, two.obj("fuel").long("amountCents"))
        assertEquals(0, two.obj("fuel").long("prepayCount"))

        // the day before: the premium fill only
        val before = get("/v1/reports/fuel?from=2026-09-25&to=2026-09-25&venue=pronghorn")
        assertEquals(listOf("PRE"), before.arr("byGrade").map { it.str("grade") })
        assertEquals(0, before.obj("inStore").long("salesCents"))
    }

    @Test
    fun fuelReportIsTenantScoped() = testApplication {
        application { module(TestSupport.config) }
        seedStation()
        seedTenant("other", "elsewhere", "Elsewhere")
        val otherSession = seedSession("other", seedUser("other", "owner@other.test", "password-other"))
        val res = client.get("/v1/reports/fuel?$q&venue=pronghorn") {
            header(HttpHeaders.Cookie, "pos_portal_session=$otherSession")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
        val mine = client.get("/v1/reports/fuel?$q") { header(HttpHeaders.Cookie, "pos_portal_session=$otherSession") }
        val body = testJson.parseToJsonElement(mine.bodyAsText()).jsonObject
        assertEquals(0, body.obj("fuel").long("count"))
        assertEquals(0, body.obj("inStore").long("salesCents"))
    }
}
