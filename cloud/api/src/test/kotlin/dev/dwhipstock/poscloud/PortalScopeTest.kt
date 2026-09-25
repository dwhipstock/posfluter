package dev.dwhipstock.poscloud

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
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The portal store picker: no venue = "All stores" (every report combines the
 * tenant's stores, each over its own business days); `?venue=<id>` = the same
 * view filtered to that store.
 */
class PortalScopeTest {

    private val keyEast = "store-key-east"
    private val keyWest = "store-key-west"
    private lateinit var session: String

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("lanterntest", "vieux-port", "Copper Lantern — Vieux-Port")
        seedTenant("lanterntest", "plateau", "Copper Lantern — Plateau")
        seedStoreKey("lanterntest", "vieux-port", keyEast)
        seedStoreKey("lanterntest", "plateau", keyWest)
        session = seedSession("lanterntest", seedUser("lanterntest", "owner@test.dev", "password-s"))
        // (a tenant of its own: Bootstrap seeds the demo tenant's stores at boot)
        // a foreign tenant's store must never show up
        seedTenant("othertenant", "vieux-port")
        seedStoreKey("othertenant", "vieux-port", "store-key-other")
    }

    private suspend fun ApplicationTestBuilder.get(path: String): JsonObject {
        val res = client.get(path) { header(HttpHeaders.Cookie, "pos_portal_session=$session") }
        assertEquals(HttpStatusCode.OK, res.status, "$path → ${res.bodyAsText()}")
        return testJson.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    private fun JsonObject.long(key: String) = this[key]!!.jsonPrimitive.content.toLong()

    private suspend fun ApplicationTestBuilder.sale(key: String, checkId: Int, gross: Long, closedAt: String) =
        ingest(key, event("check.closed", checkClosedPayload(
            checkId, gross, storeTax(gross), closedAt = closedAt, tableLabel = "U-1", zoneId = "upper",
            lines = buildJsonArray {
                add(buildJsonObject {
                    put("lineId", checkId); put("itemId", "lantern-lager"); put("categoryId", "beer-cider")
                    put("nameFr", "Lager de la Lanterne"); put("nameEn", "Lantern House Lager")
                    put("qty", 1); put("unitPriceCents", gross); put("lineTotalCents", gross)
                })
            },
            tenders = buildJsonArray {
                add(buildJsonObject {
                    put("tenderId", checkId); put("type", "CASH")
                    put("amountTenderedCents", gross); put("amountAppliedCents", gross)
                })
            },
        ), seq = checkId.toLong(), aggregateId = checkId.toString()))

    @Test
    fun allStoresCombinesAndAVenueFilters() = testApplication {
        application { module(TestSupport.config) }
        // the SAME check id at both stores — two different sales
        sale(keyEast, 1, 10000, "2026-07-21T12:00:00.000-04:00")
        sale(keyWest, 1, 25000, "2026-07-21T13:00:00.000-04:00")
        ingest("store-key-other", event("check.closed",
            checkClosedPayload(1, 99900, 0, closedAt = "2026-07-21T12:00:00.000-04:00"), seq = 1))
        val q = "from=2026-07-21&to=2026-07-21"

        val all = get("/v1/reports/summary?$q")
        assertEquals(35000, all.long("grossCents"))
        assertEquals(2, all.long("checkCount"))
        val perStore = all["byVenue"]!!.jsonArray.associate {
            it.jsonObject["venueId"]!!.jsonPrimitive.content to it.jsonObject.long("grossCents")
        }
        assertEquals(mapOf("plateau" to 25000L, "vieux-port" to 10000L), perStore)

        val one = get("/v1/reports/summary?$q&venue=plateau")
        assertEquals(25000, one.long("grossCents"))
        assertEquals(listOf("plateau"), one["byVenue"]!!.jsonArray.map { it.jsonObject["venueId"]!!.jsonPrimitive.content })

        // line and tender joins keep the two check #1s apart
        assertEquals(35000, get("/v1/reports/items?$q")["rows"]!!.jsonArray.single().jsonObject.long("revenueCents"))
        assertEquals(35000, get("/v1/reports/payments?$q").long("totalCents"))
        assertEquals(10000, get("/v1/reports/payments?$q&venue=vieux-port").long("totalCents"))

        // lists carry the store of each row
        val journal = get("/v1/reports/journal?$q")["rows"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("plateau", "vieux-port"), journal.map { it["venueId"]!!.jsonPrimitive.content })
        assertTrue(journal.all { it["lines"]!!.jsonArray.size == 1 })
        assertEquals(1, get("/v1/reports/journal?$q&venue=vieux-port")["rows"]!!.jsonArray.size)

        // another tenant's store is a 404, never a fall-through
        val foreign = client.get("/v1/reports/summary?$q&venue=nope") {
            header(HttpHeaders.Cookie, "pos_portal_session=$session")
        }
        assertEquals(HttpStatusCode.NotFound, foreign.status)
    }

    @Test
    fun storesInDifferentZonesRollUpOverTheirOwnBusinessDays() = testApplication {
        application { module(TestSupport.config) }
        startApplication()
        transaction {
            Venues.update({ (Venues.tenantId eq "lanterntest") and (Venues.id eq "plateau") }) {
                it[timezone] = "America/Vancouver"
            }
        }
        // 23:30 on the 21st in each store's own zone: 03:30Z and 06:30Z on the 22nd
        sale(keyEast, 1, 10000, "2026-07-22T03:30:00Z")
        sale(keyWest, 2, 25000, "2026-07-22T06:30:00Z")
        // and 00:30 on the 22nd, local to each
        sale(keyEast, 3, 1000, "2026-07-22T04:30:00Z")
        sale(keyWest, 4, 2000, "2026-07-22T07:30:00Z")

        val day21 = get("/v1/reports/summary?from=2026-07-21&to=2026-07-21")
        assertEquals(35000, day21.long("grossCents"))
        assertEquals(listOf("2026-07-21"), day21["byDay"]!!.jsonArray.map { it.jsonObject["date"]!!.jsonPrimitive.content })
        assertEquals(3000, get("/v1/reports/summary?from=2026-07-22&to=2026-07-22").long("grossCents"))

        // a two-day range splits each store's sales onto its own local days
        val days = get("/v1/reports/summary?from=2026-07-21&to=2026-07-22")["byDay"]!!.jsonArray
            .associate { it.jsonObject["date"]!!.jsonPrimitive.content to it.jsonObject.long("grossCents") }
        assertEquals(mapOf("2026-07-21" to 35000L, "2026-07-22" to 3000L), days)

        // the hourly view uses each store's local hour
        val hourly = get("/v1/reports/hourly?from=2026-07-21&to=2026-07-21")["rows"]!!.jsonArray
        assertEquals(35000, hourly[23].jsonObject.long("grossCents"))

        // and the journal shows each row with its own store's offset
        val rows = get("/v1/reports/journal?from=2026-07-21&to=2026-07-21")["rows"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("2026-07-21T23:30:00.000-04:00", "2026-07-21T23:30:00.000-07:00"),
            rows.map { it["closedAt"]!!.jsonPrimitive.content }.toSet())
    }

    @Test
    fun shiftDetailNeedsAStoreWhenAllAreInScope() = testApplication {
        application { module(TestSupport.config) }
        ingest(keyEast, event("shift.opened", buildJsonObject {
            put("shiftId", 7); put("openedBy", "manager"); put("openingFloatCents", 0)
            put("openedAt", "2026-07-21T10:00:00.000-04:00")
        }, seq = 1))
        val res = client.get("/v1/reports/shifts/7") { header(HttpHeaders.Cookie, "pos_portal_session=$session") }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("vieux-port", get("/v1/reports/shifts/7?venue=vieux-port")["venueId"]!!.jsonPrimitive.content)
        assertEquals(1, get("/v1/reports/shifts?from=2026-07-21&to=2026-07-21")["rows"]!!.jsonArray.size)
    }
}
