package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.CheckTenders
import dev.dwhipstock.poscloud.db.Refunds
import dev.dwhipstock.poscloud.db.Shifts
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
import kotlin.test.assertTrue

/**
 * Cash rounding to the nickel in the portal: the stores send each cash
 * payment's rounding (and each cash refund's); revenue and tax stay exact and
 * the net rounding is reported beside them — per store and per currency, never
 * CAD and USD added together. Payloads from older stores, without the figure,
 * still ingest and count 0.
 */
class CashRoundingReportTest {

    private val keyPub = "round-key-pub"
    private val keyShop = "round-key-shop"
    private lateinit var session: String
    private val q = "from=2026-07-21&to=2026-07-21"
    private val fxConfig = TestSupport.config.copy(fxRates = Fx.Rates.of("FX_USD_CAD" to "1.37"))

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("roundtest", "vieux-port", "Copper Lantern — Vieux-Port")
        seedTenant("roundtest", "sage-poppy", "Sage & Poppy Bottle Shop")
        transaction {
            Venues.update({ (Venues.tenantId eq "roundtest") and (Venues.id eq "sage-poppy") }) {
                it[currency] = "USD"; it[country] = "US"; it[kind] = "retail"
                it[timezone] = "America/Los_Angeles"
            }
        }
        seedStoreKey("roundtest", "vieux-port", keyPub)
        seedStoreKey("roundtest", "sage-poppy", keyShop)
        session = seedSession("roundtest", seedUser("roundtest", "owner@round.test", "password-round"))
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
    private fun JsonObject.isNull(key: String) = this[key] == null || this[key] is JsonNull

    /** A settled cash payment of [due] exact cents, paid [rounding] off the nickel (a $50 note). */
    private fun cash(id: Int, due: Long, rounding: Long?) = buildJsonObject {
        put("tenderId", id); put("type", "CASH"); put("amountTenderedCents", 5000); put("amountAppliedCents", due)
        rounding?.let { put("roundingAdjustmentCents", it) } // null = an older store's payload
        put("changeCents", 5000 - due - (rounding ?: 0))
    }

    private fun card(id: Int, cents: Long) = buildJsonObject {
        put("tenderId", id); put("type", "CARD"); put("amountTenderedCents", cents); put("amountAppliedCents", cents)
        put("roundingAdjustmentCents", 0); put("changeCents", 0)
    }

    private fun sale(checkId: Int, total: Long, closedAt: String, currency: String?, vararg tenders: JsonObject) = buildJsonObject {
        currency?.let { put("currency", it) }
        put("checkId", checkId); put("grandTotalCents", total); put("taxIncludedCents", total / 10)
        put("closedAt", closedAt); put("shiftId", 1)
        put("tenders", buildJsonArray { tenders.forEach { add(it) } })
    }

    private fun refund(id: Int, checkId: Int, gross: Long, type: String, rounding: Long?, createdAt: String, currency: String?) =
        buildJsonObject {
            currency?.let { put("currency", it) }
            put("refundId", id); put("checkId", checkId); put("grossCents", gross)
            put("taxIncludedCents", gross / 10); put("netCents", gross - gross / 10); put("tenderType", type)
            rounding?.let { put("roundingAdjustmentCents", it) }
            put("reason", "retour"); put("createdAt", createdAt)
        }

    private suspend fun ApplicationTestBuilder.seed() {
        val pubDay = "2026-07-21T19:00:00.000-04:00"
        ingest(keyPub,
            // 23.47 in cash → 23.45 (−2); 10.07 on a card (exact); 5.03 in cash → 5.05 (+2)
            event("check.closed", sale(1, 2347, pubDay, "CAD", cash(1, 2347, -2)), seq = 1, aggregateId = "1"),
            event("check.closed", sale(2, 1007, pubDay, "CAD", card(2, 1007)), seq = 2, aggregateId = "2"),
            event("check.closed", sale(3, 503, pubDay, "CAD", cash(3, 503, 2)), seq = 3, aggregateId = "3"),
            // mixed: $20 card + 3.47 cash → 3.45
            event("check.closed", sale(4, 2347, pubDay, "CAD", card(4, 2000), cash(5, 347, -2)), seq = 4, aggregateId = "4"),
            // an older store's sale: no currency, no rounding figure on its tender
            event("check.closed", sale(5, 1000, pubDay, null, cash(6, 1000, null)), seq = 5, aggregateId = "5"),
            // cash refund of 10.03 → 10.05 back (+2); a card refund (exact); an older refund (no figure)
            event("refund.created", refund(1, 1, 1003, "CASH", 2, "2026-07-21T20:00:00.000-04:00", "CAD"), seq = 6),
            event("refund.created", refund(2, 2, 500, "CARD", 0, "2026-07-21T20:05:00.000-04:00", "CAD"), seq = 7),
            event("refund.created", refund(3, 5, 200, "CASH", null, "2026-07-21T20:10:00.000-04:00", null), seq = 8),
            event("shift.closed", buildJsonObject {
                put("shiftId", 1); put("currency", "CAD"); put("openedAt", "2026-07-21T11:00:00.000-04:00")
                put("closedAt", "2026-07-21T23:00:00.000-04:00"); put("expectedCashCents", 123400)
                put("cashRoundingCents", -4)
            }, seq = 9, aggregateId = "1"),
        )
        val shopDay = "2026-07-21T16:00:00.000-07:00"
        ingest(keyShop,
            // 9.71 → 9.70 (−1), 12.34 → 12.35 (+1), 7.08 → 7.10 (+2)
            event("check.closed", sale(1, 971, shopDay, "USD", cash(1, 971, -1)), seq = 1, aggregateId = "1"),
            event("check.closed", sale(2, 1234, shopDay, "USD", cash(2, 1234, 1)), seq = 2, aggregateId = "2"),
            event("check.closed", sale(3, 708, shopDay, "USD", cash(3, 708, 2)), seq = 3, aggregateId = "3"),
            // 9.71 back in cash → 9.70 (−1)
            event("refund.created", refund(1, 1, 971, "CASH", -1, "2026-07-21T17:00:00.000-07:00", "USD"), seq = 4),
            // an older Z-report: no rounding figure
            event("shift.closed", buildJsonObject {
                put("shiftId", 1); put("currency", "USD"); put("openedAt", "2026-07-21T09:00:00.000-07:00")
                put("closedAt", "2026-07-21T21:00:00.000-07:00"); put("expectedCashCents", 20000)
            }, seq = 5, aggregateId = "1"),
        )
    }

    // pub: sales −2 + 0 + 2 − 2 + 0 = −2, less refunds (+2, 0, 0) → −4 CAD
    private val pubRounding = -4L
    // shop: sales −1 + 1 + 2 = 2, less the refund's −1 → 3 USD
    private val shopRounding = 3L
    private val pubGross = 2347L + 1007 + 503 + 2347 + 1000 - 1003 - 500 - 200
    private val shopGross = 971L + 1234 + 708 - 971

    @Test
    fun olderPayloadsWithoutTheFigureStillIngest() = testApplication {
        application { module(fxConfig) }
        seed()
        transaction {
            val tenders = CheckTenders.selectAll().where { CheckTenders.venueId eq "vieux-port" }.toList()
            assertEquals(6, tenders.size)
            assertNull(tenders.single { it[CheckTenders.tenderId] == 6L }[CheckTenders.roundingAdjustmentCents])
            val refunds = Refunds.selectAll().where { Refunds.venueId eq "vieux-port" }.associateBy { it[Refunds.refundId] }
            assertEquals(2L, refunds[1L]!![Refunds.roundingAdjustmentCents])
            assertNull(refunds[3L]!![Refunds.roundingAdjustmentCents])
            val shifts = Shifts.selectAll().toList().associateBy { it[Shifts.venueId] }
            assertEquals(-4L, shifts["vieux-port"]!![Shifts.cashRoundingCents])
            assertNull(shifts["sage-poppy"]!![Shifts.cashRoundingCents])
        }
    }

    @Test
    fun aStoreShowsItsRoundingBesideTheExactRevenueAndTax() = testApplication {
        application { module(fxConfig) }
        seed()
        val s = get("/v1/reports/summary?$q&venue=vieux-port")
        assertEquals(pubGross, s.long("grossCents")) // exact: the rounding is not revenue
        assertEquals(pubRounding, s.long("cashRoundingCents"))
        assertEquals(pubRounding, s.arr("byVenue").single().long("cashRoundingCents"))
        assertEquals(pubRounding, s.arr("byCurrency").single().long("cashRoundingCents"))

        val tax = get("/v1/reports/tax?$q&venue=vieux-port")
        assertEquals(pubGross, tax.obj("totals").long("grossCents"))
        assertEquals(pubRounding, tax.obj("totals").long("cashRoundingCents"))

        val pay = get("/v1/reports/payments?$q&venue=vieux-port")
        assertEquals(pubRounding, pay.long("cashRoundingCents"))
        // payment amounts stay the exact applied amounts
        val cash = pay.arr("rows").single { it.str("type") == "CASH" }
        assertEquals(2347L + 503 + 347 + 1000, cash.long("amountCents"))

        val shop = get("/v1/reports/summary?$q&venue=sage-poppy")
        assertEquals(shopGross, shop.long("grossCents"))
        assertEquals(shopRounding, shop.long("cashRoundingCents"))
        assertEquals("USD", shop.obj("money").str("currency"))
    }

    @Test
    fun allStoresShowsTheRoundingPerCurrencyNeverAddedTogether() = testApplication {
        application { module(fxConfig) }
        seed()
        for (path in listOf("summary", "payments")) {
            val r = get("/v1/reports/$path?$q")
            assertTrue(r.isNull("cashRoundingCents"), "$path: no combined CAD+USD rounding")
            val byCurrency = r.arr("byCurrency").associateBy { it.str("currency") }
            assertEquals(pubRounding, byCurrency["CAD"]!!.long("cashRoundingCents"), path)
            assertEquals(shopRounding, byCurrency["USD"]!!.long("cashRoundingCents"), path)
            val byVenue = r.arr("byVenue").associateBy { it.str("venueId") }
            assertEquals(pubRounding, byVenue["vieux-port"]!!.long("cashRoundingCents"), path)
            assertEquals("CAD", byVenue["vieux-port"]!!.str("currency"))
            assertEquals(shopRounding, byVenue["sage-poppy"]!!.long("cashRoundingCents"), path)
            assertEquals("USD", byVenue["sage-poppy"]!!.str("currency"))
        }
        val tax = get("/v1/reports/tax?$q")
        assertTrue(tax.obj("totals").isNull("cashRoundingCents"))
        assertEquals(listOf(pubRounding, shopRounding), tax.arr("byCurrency").map { it.long("cashRoundingCents") })
        assertEquals(mapOf("vieux-port" to pubRounding, "sage-poppy" to shopRounding),
            tax.arr("byVenue").associate { it.str("venueId") to it.long("cashRoundingCents") })
    }

    @Test
    fun refundsAndShiftsCarryTheirRounding() = testApplication {
        application { module(fxConfig) }
        seed()
        val refunds = get("/v1/reports/refunds?$q&venue=vieux-port")
        val rows = refunds.arr("rows").associateBy { it.long("refundId") }
        assertEquals(2L, rows[1L]!!.long("roundingAdjustmentCents"))
        assertEquals(0L, rows[2L]!!.long("roundingAdjustmentCents"))
        assertEquals(0L, rows[3L]!!.long("roundingAdjustmentCents")) // older store: none sent
        assertEquals(1003L, rows[1L]!!.long("grossCents")) // exact
        assertEquals(2L, refunds.arr("byVenue").single().long("roundingAdjustmentCents"))

        val shifts = get("/v1/reports/shifts?$q").arr("rows").associateBy { it.str("venueId") }
        assertEquals(-4L, shifts["vieux-port"]!!.long("cashRoundingCents"))
        assertTrue(shifts["sage-poppy"]!!.isNull("cashRoundingCents"))
    }
}
