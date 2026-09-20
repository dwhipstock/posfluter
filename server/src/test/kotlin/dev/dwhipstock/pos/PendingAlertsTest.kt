package dev.dwhipstock.pos

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pending-order alerts: the /zones poll exposes the oldest un-actioned PENDING
 * line's timestamp (server-derived age → survives app restart), and the alert
 * knobs are owner-tunable venue settings following the session_idle_minutes
 * pattern.
 */
class PendingAlertsTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    private suspend fun io.ktor.client.HttpClient.postJson(path: String, body: String) =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun io.ktor.client.HttpClient.patchJson(path: String, body: String) =
        patch(path) { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun ApplicationTestBuilder.tableOnZones(c: io.ktor.client.HttpClient, tableId: String) =
        json.parseToJsonElement(c.get("/zones").bodyAsText()).jsonArray
            .flatMap { it.jsonObject["tables"]!!.jsonArray }
            .first { it.jsonObject["id"]!!.jsonPrimitive.content == tableId }
            .jsonObject

    @Test
    fun zonesExposesOldestPendingTimestampAndClearsWhenActioned() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()

        // no pending → null
        assertTrue(tableOnZones(c, "t6")["oldestPendingAt"] is JsonNull)

        // customer submits two QR orders
        client.postJson("/tables/t6/pending-lines",
            """{"lines":[{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":1}]}""")
        client.postJson("/tables/t6/pending-lines",
            """{"lines":[{"itemId":"lantern-lager","variantId":"lantern-lager:pint","qty":1}]}""")

        val withPending = tableOnZones(c, "t6")
        assertEquals(2, withPending["pendingCount"]!!.jsonPrimitive.int)
        val oldest = withPending["oldestPendingAt"]!!.jsonPrimitive.content
        assertTrue(oldest.isNotBlank() && oldest.startsWith("20"), "want ISO timestamp, got $oldest")

        // accept one → oldest advances but is still present; reject the other → clears
        val checkId = withPending["openCheckId"]!!.jsonPrimitive.int
        val pending = json.parseToJsonElement(c.get("/checks/$checkId").bodyAsText())
            .jsonObject["pendingLines"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.int }
        c.post("/checks/$checkId/pending-lines/${pending[0]}/accept")
        assertTrue(tableOnZones(c, "t6")["oldestPendingAt"] !is JsonNull, "one still pending")
        c.post("/checks/$checkId/pending-lines/${pending[1]}/reject")
        assertTrue(tableOnZones(c, "t6")["oldestPendingAt"] is JsonNull, "all actioned → cleared")
    }

    @Test
    fun alertSettingsDefaultAndRoundTripWithValidation() = testApplication {
        application { module(dbPath = tempDb()) }
        val manager = loginClient()

        // migration seed defaults
        val before = json.parseToJsonElement(manager.get("/settings").bodyAsText()).jsonObject
        assertEquals(true, before["pendingAlertsEnabled"]!!.jsonPrimitive.boolean)
        assertEquals(90, before["pendingAlertEscalateSeconds"]!!.jsonPrimitive.int)
        assertEquals(80, before["pendingAlertVolume"]!!.jsonPrimitive.int)

        // owner tunes them
        val patched = manager.patchJson("/settings",
            """{"pendingAlertsEnabled":false,"pendingAlertEscalateSeconds":45,"pendingAlertVolume":50}""")
        assertEquals(HttpStatusCode.OK, patched.status)
        val after = json.parseToJsonElement(patched.bodyAsText()).jsonObject
        assertEquals(false, after["pendingAlertsEnabled"]!!.jsonPrimitive.boolean)
        assertEquals(45, after["pendingAlertEscalateSeconds"]!!.jsonPrimitive.int)
        assertEquals(50, after["pendingAlertVolume"]!!.jsonPrimitive.int)

        // out-of-range is rejected
        assertEquals(HttpStatusCode.BadRequest,
            manager.patchJson("/settings", """{"pendingAlertEscalateSeconds":5}""").status)
        assertEquals(HttpStatusCode.BadRequest,
            manager.patchJson("/settings", """{"pendingAlertVolume":150}""").status)
    }
}
