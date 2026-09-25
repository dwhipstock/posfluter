package dev.dwhipstock.poscloud

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
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every report endpoint in the three modes the store picker produces:
 *  - one store (`?venue=`): only that store's rows, and a single-row `byVenue`;
 *  - all stores: combined totals plus a `byVenue` row per store that adds up to them;
 *  - tenant isolation: a second tenant owning a store with the SAME venue id never
 *    leaks into these figures, and its venue id is a 404 on this tenant's session.
 */
class ReportScopingTest {

    private val keyHarbour = "scope-key-harbour"
    private val keyHilltop = "scope-key-hilltop"
    private val keyRival = "scope-key-rival"
    private lateinit var session: String
    private lateinit var rivalSession: String
    private val q = "from=2026-07-21&to=2026-07-21"

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("scopetest", "harbour", "Copper Lantern — Harbour")
        seedTenant("scopetest", "hilltop", "Copper Lantern — Hilltop")
        seedStoreKey("scopetest", "harbour", keyHarbour)
        seedStoreKey("scopetest", "hilltop", keyHilltop)
        session = seedSession("scopetest", seedUser("scopetest", "owner@scope.test", "password-s"))
        // another tenant with a store whose id collides with ours, plus one only it has
        seedTenant("rivaltest", "harbour", "Rival — Harbour")
        seedTenant("rivaltest", "rivalonly", "Rival — Only")
        seedStoreKey("rivaltest", "harbour", keyRival)
        rivalSession = seedSession("rivaltest", seedUser("rivaltest", "owner@rival.test", "password-r"))
    }

    private suspend fun ApplicationTestBuilder.get(path: String, token: String = session): JsonObject {
        val res = client.get(path) { header(HttpHeaders.Cookie, "pos_portal_session=$token") }
        assertEquals(HttpStatusCode.OK, res.status, "$path → ${res.bodyAsText()}")
        return testJson.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    private fun JsonObject.long(key: String) = this[key]!!.jsonPrimitive.content.toLong()
    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.arr(key: String): List<JsonObject> = this[key]!!.jsonArray.map { it.jsonObject }

    /** byVenue → venueId to the given field. */
    private fun JsonObject.split(field: String, key: String = "byVenue"): Map<String, Long> =
        arr(key).associate { it.str("venueId") to it.long(field) }

    /** One store's day: a sale, a void, a refund, a cash movement and an open shift. */
    private suspend fun ApplicationTestBuilder.storeDay(
        key: String, gross: Long, voided: Long, refund: Long, cashDir: String, cash: Long, tender: String,
    ) {
        val at = "2026-07-21T19:00:00.000-04:00"
        ingest(key,
            event("shift.opened", buildJsonObject {
                put("shiftId", 7); put("openedBy", "Manager"); put("openingFloatCents", 10000)
                put("openedAt", "2026-07-21T10:00:00.000-04:00")
            }, seq = 1),
            event("check.closed", checkClosedPayload(
                1, gross, storeTax(gross), closedAt = at, shiftId = 7,
                tableLabel = "T-1", zoneId = "main", zoneNameEn = "Dining room",
                lines = buildJsonArray {
                    add(buildJsonObject {
                        put("lineId", 1); put("itemId", "house-ale"); put("categoryId", "beer")
                        put("nameFr", "Bière maison"); put("nameEn", "House Ale")
                        put("qty", 2); put("unitPriceCents", gross / 2); put("lineTotalCents", gross)
                    })
                },
                tenders = buildJsonArray {
                    add(buildJsonObject {
                        put("tenderId", 1); put("type", tender)
                        put("amountTenderedCents", gross); put("amountAppliedCents", gross)
                    })
                },
            ), seq = 2, aggregateId = "1", createdAt = at),
            event("check.voided", buildJsonObject {
                put("checkId", 2); put("amountCents", voided); put("reason", "Entered twice")
                put("authorizedBy", "Manager"); put("voidedAt", "2026-07-21T19:30:00.000-04:00")
            }, seq = 3, aggregateId = "2"),
            event("refund.created", buildJsonObject {
                put("refundId", 1); put("checkId", 1); put("grossCents", refund)
                put("taxIncludedCents", storeTax(refund)); put("netCents", refund - storeTax(refund))
                put("tenderType", tender); put("reason", "Returned"); put("createdAt", "2026-07-21T20:00:00.000-04:00")
            }, seq = 4),
            event("cash.movement", buildJsonObject {
                put("movementId", 1); put("direction", cashDir); put("amountCents", cash)
                put("reason", "Float"); put("user", "Manager"); put("createdAt", "2026-07-21T15:00:00.000-04:00")
            }, seq = 5),
        )
    }

    private suspend fun ApplicationTestBuilder.seedAll() {
        storeDay(keyHarbour, gross = 10000, voided = 500, refund = 300, cashDir = "IN", cash = 2000, tender = "CASH")
        storeDay(keyHilltop, gross = 25000, voided = 700, refund = 400, cashDir = "OUT", cash = 1500, tender = "CARD")
        // the rival's "harbour" — same venue id, same check ids, different tenant
        storeDay(keyRival, gross = 99900, voided = 9900, refund = 9900, cashDir = "IN", cash = 9900, tender = "CASH")
    }

    private val both = setOf("harbour", "hilltop")

    @Test
    fun salesReportsSplitByStore() = testApplication {
        application { module(TestSupport.config) }
        seedAll()

        // summary: combined totals + one row per store, net of refunds
        val all = get("/v1/reports/summary?$q")
        assertEquals(10000L - 300 + 25000 - 400, all.long("grossCents"))
        assertEquals(mapOf("harbour" to 9700L, "hilltop" to 24600L), all.split("grossCents"))
        // each day carries the same split
        val day = all.arr("byDay").single()
        assertEquals(mapOf("harbour" to 9700L, "hilltop" to 24600L), day.split("grossCents"))
        val one = get("/v1/reports/summary?$q&venue=hilltop")
        assertEquals(24600, one.long("grossCents"))
        assertEquals(setOf("hilltop"), one.split("grossCents").keys)
        assertEquals(setOf("hilltop"), one.arr("byDay").single().split("grossCents").keys)

        // by-venue comparison
        val cmp = get("/v1/reports/by-venue?$q")
        assertEquals(both, cmp.split("grossCents", "venues").keys)
        assertEquals(34300, cmp.long("grossCents"))
        assertEquals(1, get("/v1/reports/by-venue?$q&venue=harbour").arr("venues").size)

        // tax
        val tax = get("/v1/reports/tax?$q")
        val expectedTax = storeTax(10000) - storeTax(300) + storeTax(25000) - storeTax(400)
        assertEquals(expectedTax, tax["totals"]!!.jsonObject.long("taxCents"))
        assertEquals(mapOf("harbour" to storeTax(10000) - storeTax(300), "hilltop" to storeTax(25000) - storeTax(400)),
            tax.split("taxCents"))
        assertEquals(storeTax(10000) - storeTax(300),
            get("/v1/reports/tax?$q&venue=harbour")["totals"]!!.jsonObject.long("taxCents"))

        // payments: tender mix per store
        val pay = get("/v1/reports/payments?$q")
        assertEquals(35000, pay.long("totalCents"))
        assertEquals(mapOf("harbour" to 10000L, "hilltop" to 25000L), pay.split("totalCents"))
        assertEquals(listOf("Copper Lantern — Harbour", "Copper Lantern — Hilltop"), pay.arr("byVenue").map { it.str("venueName") })
        val payOne = get("/v1/reports/payments?$q&venue=harbour")
        assertEquals(listOf("CASH"), payOne.arr("rows").map { it.str("type") })
        assertEquals(setOf("harbour"), payOne.split("totalCents").keys)
    }

    @Test
    fun itemHourlyAndTableReportsSplitByStore() = testApplication {
        application { module(TestSupport.config) }
        seedAll()

        // items: the same item id across stores is one row with a per-store split
        val items = get("/v1/reports/items?$q")
        val ale = items.arr("rows").single()
        assertEquals(35000, ale.long("revenueCents"))
        assertEquals(mapOf("harbour" to 10000L, "hilltop" to 25000L), ale.split("revenueCents"))
        assertEquals(mapOf("harbour" to 2L, "hilltop" to 2L), ale.split("qty"))
        assertEquals(mapOf("harbour" to 10000L, "hilltop" to 25000L), items.split("grossCents"))
        val itemsOne = get("/v1/reports/items?$q&venue=hilltop")
        assertEquals(25000, itemsOne.arr("rows").single().long("revenueCents"))
        assertEquals(setOf("hilltop"), itemsOne.split("grossCents").keys)

        val cats = get("/v1/reports/categories?$q")
        assertEquals(mapOf("harbour" to 10000L, "hilltop" to 25000L), cats.arr("rows").single().split("revenueCents"))
        assertEquals(setOf("harbour"), get("/v1/reports/categories?$q&venue=harbour").split("qty").keys)

        // hourly: every hour has one entry per store so charts can stack
        val hourly = get("/v1/reports/hourly?$q")
        val h19 = hourly.arr("rows")[19]
        assertEquals(35000, h19.long("grossCents"))
        assertEquals(mapOf("harbour" to 10000L, "hilltop" to 25000L), h19.split("grossCents"))
        assertEquals(both, hourly.arr("rows")[3].split("grossCents").keys)
        assertEquals(mapOf("harbour" to 1L, "hilltop" to 1L), hourly.split("checkCount"))
        val hourlyOne = get("/v1/reports/hourly?$q&venue=harbour")
        assertEquals(10000, hourlyOne.arr("rows")[19].long("grossCents"))
        assertEquals(setOf("harbour"), hourlyOne.arr("rows")[19].split("grossCents").keys)

        // tables: zone/table rows name their store; totals per store
        val tables = get("/v1/reports/tables?$q")
        assertEquals(both, tables.arr("byTable").map { it.str("venueId") }.toSet())
        assertEquals(mapOf("harbour" to 10000L, "hilltop" to 25000L), tables.split("grossCents"))
        val tablesOne = get("/v1/reports/tables?$q&venue=hilltop")
        assertEquals(listOf("hilltop"), tablesOne.arr("byZone").map { it.str("venueId") })
    }

    @Test
    fun operationsReportsSplitByStore() = testApplication {
        application { module(TestSupport.config) }
        seedAll()

        val exc = get("/v1/reports/exceptions?$q")
        assertEquals(1200, exc.long("voidAmountCents"))
        assertEquals(mapOf("harbour" to 500L, "hilltop" to 700L), exc.split("voidAmountCents"))
        assertEquals(both, exc.arr("voids").map { it.str("venueId") }.toSet())
        val excOne = get("/v1/reports/exceptions?$q&venue=harbour")
        assertEquals(500, excOne.long("voidAmountCents"))
        assertEquals(setOf("harbour"), excOne.split("voidCount").keys)

        val ref = get("/v1/reports/refunds?$q")
        assertEquals(700, ref.long("grossCents"))
        assertEquals(mapOf("harbour" to 300L, "hilltop" to 400L), ref.split("grossCents"))
        assertEquals(mapOf("harbour" to 1L, "hilltop" to 1L), ref.split("count"))
        val refOne = get("/v1/reports/refunds?$q&venue=hilltop")
        assertEquals(400, refOne.long("grossCents"))
        assertEquals(listOf("hilltop"), refOne.arr("rows").map { it.str("venueId") })

        val cash = get("/v1/reports/cash-movements?$q")
        assertEquals(2000 - 1500, cash.long("netCents"))
        assertEquals(mapOf("harbour" to 2000L, "hilltop" to -1500L), cash.split("netCents"))
        assertEquals(mapOf("harbour" to 0L, "hilltop" to 1500L), cash.split("paidOutCents"))
        val cashOne = get("/v1/reports/cash-movements?$q&venue=harbour")
        assertEquals(2000, cashOne.long("paidInCents"))
        assertEquals(setOf("harbour"), cashOne.split("netCents").keys)

        val shifts = get("/v1/reports/shifts?$q")
        assertEquals(2, shifts.arr("rows").size)
        assertEquals(mapOf("harbour" to 1L, "hilltop" to 1L), shifts.split("openCount"))
        val shiftsOne = get("/v1/reports/shifts?$q&venue=hilltop")
        assertEquals(listOf("hilltop"), shiftsOne.arr("rows").map { it.str("venueId") })
        assertEquals(setOf("hilltop"), shiftsOne.split("shiftCount").keys)

        // journal: per-store counts cover the whole filtered range, not only the page
        val journal = get("/v1/reports/journal?$q&limit=1")
        assertEquals(4, journal.long("total"))
        assertEquals(1, journal.arr("rows").size)
        assertEquals(mapOf("harbour" to 1L, "hilltop" to 1L), journal.split("closedCount"))
        assertEquals(mapOf("harbour" to 1L, "hilltop" to 1L), journal.split("voidCount"))
        assertEquals(mapOf("harbour" to 10000L, "hilltop" to 25000L), journal.split("closedCents"))
        val journalOne = get("/v1/reports/journal?$q&venue=harbour")
        assertEquals(2, journalOne.long("total"))
        assertEquals(setOf("harbour"), journalOne.split("closedCount").keys)
    }

    @Test
    fun anotherTenantsStoresNeverLeakIn() = testApplication {
        application { module(TestSupport.config) }
        seedAll()
        val endpoints = listOf(
            "summary", "by-venue", "tax", "payments", "items", "categories", "hourly", "tables",
            "exceptions", "refunds", "cash-movements", "shifts", "journal",
        )

        // the rival's data is never in our combined or single-store figures
        for (e in endpoints) {
            for (path in listOf("/v1/reports/$e?$q", "/v1/reports/$e?$q&venue=harbour")) {
                val body = get(path).toString()
                assertTrue("99900" !in body && "9900" !in body, "$path leaked another tenant's figures: $body")
            }
        }
        // a store that only the rival has is a 404 for us, on every report
        for (e in endpoints) {
            val res = client.get("/v1/reports/$e?$q&venue=rivalonly") {
                header(HttpHeaders.Cookie, "pos_portal_session=$session")
            }
            assertEquals(HttpStatusCode.NotFound, res.status, e)
        }
        // and from the other side: the rival sees only its own harbour, with its own byVenue
        val rival = get("/v1/reports/summary?$q", rivalSession)
        assertEquals(99900L - 9900, rival.long("grossCents"))
        assertEquals(mapOf("harbour" to 90000L, "rivalonly" to 0L), rival.split("grossCents"))
        val rivalPay = get("/v1/reports/payments?$q&venue=harbour", rivalSession)
        assertEquals(99900, rivalPay.long("totalCents"))
        val rivalItems = get("/v1/reports/items?$q", rivalSession).arr("rows").single()
        assertEquals(mapOf("harbour" to 99900L), rivalItems.split("revenueCents"))
    }
}
