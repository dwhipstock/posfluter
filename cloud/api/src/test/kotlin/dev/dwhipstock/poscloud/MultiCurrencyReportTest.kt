package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.Checks
import dev.dwhipstock.poscloud.db.Refunds
import dev.dwhipstock.poscloud.db.Tenants
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One owner, stores in two countries: a Montréal pub in CAD and a Los Angeles
 * bottle shop in USD. Money is never added across currencies — "All stores"
 * shows exact totals per currency, and a converted total in the reporting
 * currency at the configured fixed rate, flagged approximate.
 */
class MultiCurrencyReportTest {

    private val keyPub = "fx-key-pub"
    private val keyShop = "fx-key-shop"
    private lateinit var session: String
    private val q = "from=2026-07-21&to=2026-07-21"
    private val fxConfig = TestSupport.config.copy(fxRates = Fx.Rates.of("FX_USD_CAD" to "1.37"))

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("fxtest", "vieux-port", "Copper Lantern — Vieux-Port")
        seedTenant("fxtest", "sage-poppy", "Sage & Poppy Bottle Shop")
        transaction {
            Venues.update({ (Venues.tenantId eq "fxtest") and (Venues.id eq "sage-poppy") }) {
                it[currency] = "USD"; it[country] = "US"; it[kind] = "retail"
                it[timezone] = "America/Los_Angeles"
            }
        }
        seedStoreKey("fxtest", "vieux-port", keyPub)
        seedStoreKey("fxtest", "sage-poppy", keyShop)
        session = seedSession("fxtest", seedUser("fxtest", "owner@fx.test", "password-fx"))
    }

    private suspend fun ApplicationTestBuilder.get(path: String): JsonObject {
        val res = client.get(path) { header(HttpHeaders.Cookie, "pos_portal_session=$session") }
        assertEquals(HttpStatusCode.OK, res.status, "$path → ${res.bodyAsText()}")
        return testJson.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    private fun JsonObject.long(key: String) = this[key]!!.jsonPrimitive.content.toLong()
    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.bool(key: String) = this[key]!!.jsonPrimitive.content.toBooleanStrict()
    private fun JsonObject.obj(key: String) = this[key]!!.jsonObject
    private fun JsonObject.arr(key: String): List<JsonObject> = this[key]!!.jsonArray.map { it.jsonObject }

    private fun line(itemId: String, name: String, qty: Int, unit: Long) = buildJsonObject {
        put("lineId", 1); put("itemId", itemId); put("categoryId", "mixers")
        put("nameFr", name); put("nameEn", name)
        put("qty", qty); put("unitPriceCents", unit); put("lineTotalCents", unit * qty)
    }

    private fun tender(id: Int, type: String, cents: Long) = buildJsonObject {
        put("tenderId", id); put("type", type); put("amountTenderedCents", cents); put("amountAppliedCents", cents)
    }

    /** A US sale: [subtotal] pre-tax plus one 9.5% sales tax, in USD. */
    private fun usSale(checkId: Int, subtotal: Long, closedAt: String, lines: kotlinx.serialization.json.JsonArray) = buildJsonObject {
        val tax = (subtotal * 95 * 2 + 1000) / 2000
        put("currency", "USD"); put("country", "US")
        put("checkId", checkId); put("grandTotalCents", subtotal + tax); put("taxIncludedCents", tax)
        put("subtotalCents", subtotal); put("closedAt", closedAt)
        put("taxes", buildJsonArray {
            add(buildJsonObject {
                put("code", "US_SALES"); put("labelFr", "Sales Tax"); put("labelEn", "Sales Tax")
                put("ratePercent", "9.5"); put("registrationNumber", "SR KH 123-456789"); put("amountCents", tax)
            })
        })
        put("lines", lines)
        put("tenders", buildJsonArray { add(tender(checkId, "CASH", subtotal + tax)) })
    }

    private suspend fun ApplicationTestBuilder.seedSales() {
        // the pub: one current sale (with currency) and one from an older store (none sent)
        ingest(keyPub,
            event("check.closed", buildJsonObject {
                qcCheckClosedPayload(1, 8697, "2026-07-21T19:00:00.000-04:00").forEach { (k, v) -> put(k, v) }
                put("currency", "CAD"); put("country", "CA")
                put("lines", buildJsonArray { add(line("club-soda", "Club Soda", 1, 8697)) })
                put("tenders", buildJsonArray { add(tender(1, "CARD", 10000)) })
            }, seq = 1, aggregateId = "1", createdAt = "2026-07-21T19:00:00.000-04:00"),
            event("check.closed", checkClosedPayload(
                2, 2000, storeTax(2000), closedAt = "2026-07-21T20:00:00.000-04:00",
                tenders = buildJsonArray { add(tender(2, "CASH", 2000)) },
            ), seq = 2, aggregateId = "2"),
            event("refund.created", buildJsonObject {
                put("refundId", 1); put("checkId", 2); put("grossCents", 500)
                put("taxIncludedCents", 58); put("netCents", 442); put("tenderType", "CASH")
                put("reason", "Spilled"); put("createdAt", "2026-07-21T20:30:00.000-04:00")
            }, seq = 3),
        )
        // the bottle shop, in its own (Pacific) business day: two sales in USD
        ingest(keyShop,
            event("check.closed", usSale(1, 4000, "2026-07-21T16:00:00.000-07:00",
                buildJsonArray { add(line("club-soda", "Club Soda", 4, 1000)) }), seq = 1, aggregateId = "1"),
            event("check.closed", usSale(2, 1000, "2026-07-21T17:00:00.000-07:00",
                buildJsonArray { add(line("ice-bag", "Ice 7 lb", 1, 1000)) }), seq = 2, aggregateId = "2"),
            event("refund.created", buildJsonObject {
                put("currency", "USD"); put("refundId", 1); put("checkId", 2); put("grossCents", 1095)
                put("taxIncludedCents", 95); put("netCents", 1000); put("tenderType", "CASH")
                put("reason", "Wrong item"); put("createdAt", "2026-07-21T17:30:00.000-07:00")
                put("taxes", buildJsonArray {
                    add(buildJsonObject { put("code", "US_SALES"); put("labelEn", "Sales Tax"); put("amountCents", 95) })
                })
            }, seq = 3),
        )
    }

    // pub: 10000 + 2000 − refund 500 = 11500 CAD; shop: 4380 + 1095 − 1095 = 4380 USD
    private val pubGross = 10000L + 2000 - 500
    private val shopGross = 4380L + 1095 - 1095

    @Test
    fun salesStampTheirCurrencyAndOlderPayloadsReadAsTheVenues() = testApplication {
        application { module(fxConfig) }
        seedSales()
        transaction {
            fun currencyOf(venue: String, id: Int) = Checks.selectAll()
                .where { (Checks.tenantId eq "fxtest") and (Checks.venueId eq venue) and (Checks.checkId eq id) }
                .first()[Checks.currency]
            assertEquals("CAD", currencyOf("vieux-port", 1))
            assertEquals("CAD", currencyOf("vieux-port", 2)) // no currency sent → the venue's
            assertEquals("USD", currencyOf("sage-poppy", 1))
            assertEquals("USD", Refunds.selectAll().where { Refunds.venueId eq "sage-poppy" }.first()[Refunds.currency])
        }
    }

    @Test
    fun allStoresShowsTotalsPerCurrencyAndAnApproximateConvertedTotal() = testApplication {
        application { module(fxConfig) }
        seedSales()
        val s = get("/v1/reports/summary?$q")
        val money = s.obj("money")
        assertEquals("CAD", money.str("currency"))
        assertTrue(money.bool("approximate"))
        assertTrue(money.bool("convertible"))
        assertEquals(listOf("CAD", "USD"), money["currencies"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("1.37", money.arr("rates").single().str("rate"))

        // exact per currency: never one pile of mixed dollars
        val byCurrency = s.arr("byCurrency").associateBy { it.str("currency") }
        assertEquals(pubGross, byCurrency["CAD"]!!.long("grossCents"))
        assertEquals(shopGross, byCurrency["USD"]!!.long("grossCents"))
        assertEquals(2, byCurrency["USD"]!!.long("checkCount").toInt())
        assertEquals(Math.round(shopGross * 1.37), byCurrency["USD"]!!.long("grossReportingCents"))

        // the combined figure: CAD as is + USD at 1.37, half-up to the cent
        assertEquals(pubGross + Math.round(shopGross * 1.37), s.long("grossCents"))

        // per-store rows stay exact, each in its own currency
        val byVenue = s.arr("byVenue").associateBy { it.str("venueId") }
        assertEquals(pubGross, byVenue["vieux-port"]!!.long("grossCents"))
        assertEquals("CAD", byVenue["vieux-port"]!!.str("currency"))
        assertEquals(shopGross, byVenue["sage-poppy"]!!.long("grossCents"))
        assertEquals("USD", byVenue["sage-poppy"]!!.str("currency"))
        val day = s.arr("byDay").single().arr("byVenue").associateBy { it.str("venueId") }
        assertEquals(shopGross, day["sage-poppy"]!!.long("grossCents"))
        assertEquals("USD", day["sage-poppy"]!!.str("currency"))
    }

    @Test
    fun aStoreOnItsOwnIsExactInItsOwnCurrency() = testApplication {
        application { module(fxConfig) }
        seedSales()
        val s = get("/v1/reports/summary?$q&venue=sage-poppy")
        val money = s.obj("money")
        assertEquals("USD", money.str("currency"))
        assertFalse(money.bool("approximate"))
        assertEquals(shopGross, s.long("grossCents"))
        assertEquals(2, s.long("checkCount").toInt())
        assertEquals(listOf("USD"), s.arr("byCurrency").map { it.str("currency") })

        // its tax report names its own tax, not GST / QST
        val tax = get("/v1/reports/tax?$q&venue=sage-poppy")
        assertEquals("USD", tax.obj("money").str("currency"))
        val salesTax = tax.arr("byTax").single()
        assertEquals("US_SALES", salesTax.str("code"))
        assertEquals("USD", salesTax.str("currency"))
        assertEquals(380L + 95 - 95, salesTax.long("amountCents"))
        assertEquals(0L, tax.obj("totals").long("gstCents"))

        val pub = get("/v1/reports/summary?$q&venue=vieux-port")
        assertEquals("CAD", pub.obj("money").str("currency"))
        assertEquals(pubGross, pub.long("grossCents"))
    }

    @Test
    fun itemsAndPaymentsNeverMergeCurrencies() = testApplication {
        application { module(fxConfig) }
        seedSales()
        // the same item id sold in both stores: one row per currency
        val soda = get("/v1/reports/items?$q").arr("rows").filter { it.str("itemId") == "club-soda" }
            .associateBy { it.str("currency") }
        assertEquals(8697L, soda["CAD"]!!.long("revenueCents"))
        assertEquals(4000L, soda["USD"]!!.long("revenueCents"))

        val pay = get("/v1/reports/payments?$q")
        val cash = pay.arr("byCurrency").associateBy { it.str("currency") }
        assertEquals(2000L, cash["CAD"]!!.arr("rows").single { it.str("type") == "CASH" }.long("amountCents"))
        assertEquals(4380L + 1095, cash["USD"]!!.arr("rows").single { it.str("type") == "CASH" }.long("amountCents"))
        assertEquals("USD", pay.arr("byVenue").single { it.str("venueId") == "sage-poppy" }.str("currency"))

        val journal = get("/v1/reports/journal?$q").arr("rows")
        assertEquals(setOf("CAD", "USD"), journal.map { it.str("currency") }.toSet())
    }

    @Test
    fun withoutARateTheCombinedFigureSaysSoInsteadOfGuessing() = testApplication {
        application { module(TestSupport.config) } // no FX_ rates configured
        seedSales()
        val s = get("/v1/reports/summary?$q")
        val money = s.obj("money")
        assertTrue(money.bool("approximate"))
        assertFalse(money.bool("convertible"))
        assertTrue(money.arr("rates").isEmpty())
        val usd = s.arr("byCurrency").single { it.str("currency") == "USD" }
        assertEquals(shopGross, usd.long("grossCents"))
        assertNull(usd["grossReportingCents"]!!.jsonPrimitive.content.toLongOrNull())
    }

    @Test
    fun venuesListTheirCurrencyAndTheTenantItsReportingCurrency() = testApplication {
        application { module(fxConfig) }
        val v = get("/v1/venues")
        val shop = v.arr("venues").single { it.str("id") == "sage-poppy" }
        assertEquals("USD", shop.str("currency"))
        assertEquals("US", shop.str("country"))
        assertEquals("retail", shop.str("kind"))
        assertEquals("CAD", v.str("reportingCurrency"))
        assertEquals("1.37", v.arr("rates").single().str("rate"))
    }

    @Test
    fun fxRatesParseFromEnvAndDeriveTheReverse() {
        val rates = Fx.Rates.fromEnv(mapOf("FX_USD_CAD" to "1.37", "FX_BAD" to "2", "FX_EUR_CAD" to "-1", "PATH" to "/bin"))
        assertEquals(13700L, rates.convert(10000, "USD", "CAD"))
        assertEquals(7299L, rates.convert(10000, "CAD", "USD")) // 1 / 1.37, half-up
        assertEquals(10000L, rates.convert(10000, "CAD", "CAD"))
        assertNull(rates.convert(10000, "EUR", "CAD"))
        assertEquals(1, rates.listed().size)
    }

    @Test
    fun bootstrapGivesEachListedStoreItsZoneCurrencyAndKind() {
        Bootstrap.run(TestSupport.config.copy(
            stores = listOf(StoreSeed("vieux-port", "Copper Lantern — Vieux-Port"), StoreSeed("sage-poppy", "Sage & Poppy Bottle Shop")),
            storeZones = mapOf("sage-poppy" to "America/Los_Angeles"),
            storeCurrencies = mapOf("sage-poppy" to "USD"),
            storeCountries = mapOf("sage-poppy" to "US"),
            retailStores = setOf("sage-poppy"),
            reportingCurrency = "CAD",
        ))
        transaction {
            fun venue(id: String) = Venues.selectAll()
                .where { (Venues.tenantId eq Bootstrap.TENANT) and (Venues.id eq id) }.first()
            val shop = venue("sage-poppy")
            assertEquals("America/Los_Angeles", shop[Venues.timezone])
            assertEquals("USD", shop[Venues.currency])
            assertEquals("US", shop[Venues.country])
            assertEquals("retail", shop[Venues.kind])
            val pub = venue("vieux-port")
            assertEquals("America/New_York", pub[Venues.timezone])
            assertEquals("CAD", pub[Venues.currency])
            assertEquals("restaurant", pub[Venues.kind])
            assertEquals("CAD", Tenants.selectAll().where { Tenants.id eq Bootstrap.TENANT }.first()[Tenants.reportingCurrency])
        }
    }
}
