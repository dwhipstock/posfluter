package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.Checks
import dev.dwhipstock.poscloud.db.Events
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class TenantIsolationTest {

    private val keyA = "store-key-tenant-a"
    private val keyB = "store-key-tenant-b"
    private lateinit var sessionA: String
    private lateinit var sessionB: String

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("ten-a")
        seedTenant("ten-b")
        seedStoreKey("ten-a", "main", keyA)
        seedStoreKey("ten-b", "main", keyB)
        sessionA = seedSession("ten-a", seedUser("ten-a", "a@test.dev", "password-a"))
        sessionB = seedSession("ten-b", seedUser("ten-b", "b@test.dev", "password-b"))
    }

    @Test
    fun sameEventIdLandsInBothTenantsAndNothingLeaks() = testApplication {
        application { module(TestSupport.config) }
        val sharedEventId = "11111111-2222-3333-4444-555555555555"
        val day = "2026-07-10"

        val resA = ingest(keyA, event(
            "check.closed",
            checkClosedPayload(1, 50000, storeTax(50000), "${day}T20:00:00", tableLabel = "A-1"),
            seq = 1, eventId = sharedEventId))
        val resB = ingest(keyB, event(
            "check.closed",
            checkClosedPayload(1, 90000, storeTax(90000), "${day}T21:00:00", tableLabel = "B-1"),
            seq = 1, eventId = sharedEventId))
        assertEquals(1, resA["accepted"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, resB["accepted"]!!.jsonPrimitive.content.toInt())

        // direct row check: per-tenant PK means one event row each, own payloads projected
        transaction {
            assertEquals(1, Events.selectAll().where { Events.tenantId eq "ten-a" }.count())
            assertEquals(1, Events.selectAll().where { Events.tenantId eq "ten-b" }.count())
            val a = Checks.selectAll().where { (Checks.tenantId eq "ten-a") }.single()
            val b = Checks.selectAll().where { (Checks.tenantId eq "ten-b") }.single()
            assertEquals(50000L, a[Checks.grandTotalCents])
            assertEquals(90000L, b[Checks.grandTotalCents])
            assertEquals(0, Checks.selectAll().where {
                (Checks.tenantId eq "ten-a") and (Checks.grandTotalCents eq 90000L)
            }.count())
        }

        // API layer: each session sees only its own tenant's numbers
        val summaryA = testJson.parseToJsonElement(
            getWithCookie("/v1/reports/summary?from=$day&to=$day", sessionA).bodyAsText()).jsonObject
        assertEquals(50000L, summaryA["grossCents"]!!.jsonPrimitive.content.toLong())
        assertEquals(1, summaryA["checkCount"]!!.jsonPrimitive.content.toInt())
        val summaryB = testJson.parseToJsonElement(
            getWithCookie("/v1/reports/summary?from=$day&to=$day", sessionB).bodyAsText()).jsonObject
        assertEquals(90000L, summaryB["grossCents"]!!.jsonPrimitive.content.toLong())

        val journalA = testJson.parseToJsonElement(
            getWithCookie("/v1/reports/journal?from=$day&to=$day", sessionA).bodyAsText()).jsonObject
        assertEquals(1, journalA["total"]!!.jsonPrimitive.content.toInt())
        assertEquals("A-1", journalA["rows"]!!.jsonArray[0].jsonObject["tableLabel"]!!.jsonPrimitive.content)
    }

    @Test
    fun menuAndCatalogStayPerTenant() = testApplication {
        application { module(TestSupport.config) }
        ingest(keyA, event("item.created", buildJsonObject {
            put("item", buildJsonObject {
                put("id", "lantern-lager")
                put("nameFr", "Lager de la Lanterne")
                put("nameEn", "Lantern House Lager")
                put("categoryId", "beer")
                put("variants", buildJsonArray { })
            })
        }, seq = 1))

        val menuA = testJson.parseToJsonElement(
            getWithCookie("/v1/menu", sessionA).bodyAsText()).jsonObject
        val menuB = testJson.parseToJsonElement(
            getWithCookie("/v1/menu", sessionB).bodyAsText()).jsonObject
        assertEquals(1, menuA["items"]!!.jsonArray.size)
        assertEquals(0, menuB["items"]!!.jsonArray.size)

        // B's store feed must not see A's catalog change
        val feedB = client.get("/v1/store/catalog/changes?since=0") {
            header(HttpHeaders.Authorization, "Bearer $keyB")
        }
        val feedBBody = testJson.parseToJsonElement(feedB.bodyAsText()).jsonObject
        assertEquals(0, feedBBody["changes"]!!.jsonArray.size)
        assertEquals(0, feedBBody["cursor"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun sessionsDoNotCrossTenants() = testApplication {
        application { module(TestSupport.config) }
        assertEquals(HttpStatusCode.OK, getWithCookie("/v1/auth/me", sessionA).status)
        assertEquals(HttpStatusCode.Unauthorized,
            getWithCookie("/v1/auth/me", "not-a-real-token").status)
        val meA = testJson.parseToJsonElement(getWithCookie("/v1/auth/me", sessionA).bodyAsText()).jsonObject
        assertEquals("a@test.dev", meA["email"]!!.jsonPrimitive.content)
    }
}
