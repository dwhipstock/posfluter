package dev.dwhipstock.pos

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Migration 012 relabel: a freshly-migrated + seeded DB exposes zone-prefixed
 * sequential labels (U-/O-/B-/L-), Upper shrunk to 10, IDs unchanged, and the
 * VIP name_override preserved. Asserts through /zones — the real read path the
 * client uses — so the whole migration+seed+API chain is exercised.
 */
class RelabelTablesTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    private suspend fun io.ktor.client.HttpClient.zoneTables(): Map<String, List<Pair<String, String>>> {
        // zoneId -> list of (label, id) in sort order
        val zones = json.parseToJsonElement(get("/zones").bodyAsText()).jsonArray
        return zones.associate { z ->
            z.jsonObject["id"]!!.jsonPrimitive.content to
                z.jsonObject["tables"]!!.jsonArray.map {
                    it.jsonObject["label"]!!.jsonPrimitive.content to
                        it.jsonObject["id"]!!.jsonPrimitive.content
                }
        }
    }

    @Test
    fun freshDbSeedsZonePrefixedSequentialLabels() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val byZone = c.zoneTables()

        // exact labels, in order, per zone
        assertEquals((1..10).map { "U-$it" }, byZone["upper"]!!.map { it.first })
        assertEquals(listOf("O-1", "O-2"), byZone["outside"]!!.map { it.first })
        assertEquals((1..7).map { "B-$it" }, byZone["bar-front"]!!.map { it.first })
        assertEquals((1..23).map { "L-$it" }, byZone["lower"]!!.map { it.first })
        assertEquals(42, byZone.values.sumOf { it.size })

        // IDs never changed — labels are pure display over stable internal ids
        assertEquals("t1", byZone["upper"]!!.first { it.first == "U-1" }.second)
        assertEquals("t5-5", byZone["bar-front"]!!.first { it.first == "B-2" }.second)

        // the 3 dropped upper tables are gone
        assertNull(byZone["upper"]!!.firstOrNull { it.second in setOf("u6", "u6-6", "u7") })
    }

    @Test
    fun vipNameOverrideSurvivesRelabel() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        // t8 became L-2 but still carries its VIP display name
        val lower = json.parseToJsonElement(c.get("/zones").bodyAsText()).jsonArray
            .first { it.jsonObject["id"]!!.jsonPrimitive.content == "lower" }
            .jsonObject["tables"]!!.jsonArray
        val l2 = lower.first { it.jsonObject["id"]!!.jsonPrimitive.content == "t8" }.jsonObject
        assertEquals("L-2", l2["label"]!!.jsonPrimitive.content)
        assertEquals("Alex Morgan", l2["nameOverride"]!!.jsonPrimitive.contentOrNull)
    }
}
