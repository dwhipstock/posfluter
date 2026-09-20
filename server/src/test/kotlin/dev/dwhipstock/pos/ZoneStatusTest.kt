package dev.dwhipstock.pos

import dev.dwhipstock.pos.db.SyncOutbox
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZoneStatusTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()
    private suspend fun io.ktor.client.HttpClient.postJson(path: String, body: String) =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }
    private suspend fun io.ktor.client.HttpClient.patchJson(path: String, body: String) =
        patch(path) { contentType(ContentType.Application.Json); setBody(body) }

    private fun zoneStatus(zonesBody: String, zoneId: String): String =
        json.parseToJsonElement(zonesBody).jsonArray
            .first { it.jsonObject["id"]!!.jsonPrimitive.content == zoneId }
            .jsonObject["status"]!!.jsonPrimitive.content

    @Test
    fun closingZoneBlocksNewChecksButNotExistingOnes() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        // a server lacks the zone_open_close grant, so its toggle needs manager approval (CONTRACT §7)
        val server = loginClient("9999")
        c.postJson("/shifts", """{"openingFloatCents":100000,"managerPin":"1234"}""") // for the tender below

        // GET /zones carries status; everything starts OPEN
        assertEquals("OPEN", zoneStatus(c.get("/zones").bodyAsText(), "outside"))

        // an existing open check on t3 (outside), opened while the zone is still OPEN
        val existing = json.parseToJsonElement(c.post("/tables/t3/checks").bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.int

        // grant gate: a server toggling with no approving PIN is refused
        assertEquals(HttpStatusCode.Forbidden,
            server.patchJson("/zones/outside/status", """{"status":"CLOSED"}""").status)

        // close the outside zone with a manager PIN
        assertEquals(HttpStatusCode.OK,
            c.patchJson("/zones/outside/status", """{"status":"CLOSED","managerPin":"1234"}""").status)
        assertEquals("CLOSED", zoneStatus(c.get("/zones").bodyAsText(), "outside"))

        // a NEW check on t4 (also outside) is refused with zone_closed
        val blocked = c.post("/tables/t4/checks")
        assertEquals(HttpStatusCode.Conflict, blocked.status)
        assertEquals("zone_closed",
            json.parseToJsonElement(blocked.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)

        // the EXISTING t3 check is unaffected: still editable and tenderable
        assertEquals(HttpStatusCode.Created,
            c.postJson("/checks/$existing/lines", """{"itemId":"lantern-lager","variantId":"lantern-lager:pint","qty":1}""").status)
        assertEquals(HttpStatusCode.Created,
            c.postJson("/checks/$existing/tenders", """{"type":"CASH","amountTenderedCents":100000}""").status)

        // reopening restores new-check ordering
        assertEquals(HttpStatusCode.OK,
            c.patchJson("/zones/outside/status", """{"status":"OPEN","managerPin":"1234"}""").status)
        assertEquals(HttpStatusCode.Created, c.post("/tables/t4/checks").status)

        // outbox trail carries previous+new for both the close and the reopen
        val events = transaction {
            SyncOutbox.selectAll()
                .filter { it[SyncOutbox.eventType] == "zone.status_changed" }
                .map { it[SyncOutbox.payload] }
        }
        assertTrue(events.any { """"previous":"OPEN"""" in it && """"status":"CLOSED"""" in it },
            "expected a close event (OPEN→CLOSED): $events")
        assertTrue(events.any { """"previous":"CLOSED"""" in it && """"status":"OPEN"""" in it },
            "expected a reopen event (CLOSED→OPEN): $events")
    }

    @Test
    fun closedZoneCustomerMenuShowsBannerAndRejectsPendingLines() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()

        // menu serves normally while open
        assertEquals(HttpStatusCode.OK, client.get("/m/t3").status)

        c.patchJson("/zones/outside/status", """{"status":"CLOSED","managerPin":"1234"}""")

        // customer scan on a closed-zone table → 200 with a calm banner (FR + EN), not a 4xx
        val menu = client.get("/m/t3")
        assertEquals(HttpStatusCode.OK, menu.status)
        val body = menu.bodyAsText()
        assertTrue("Cette zone est temporairement fermée." in body, "missing French closed banner")
        assertTrue("temporarily closed" in body, "missing English closed banner")

        // the raw QR-order endpoint refuses (machine surface) with zone_closed
        val pend = client.postJson("/tables/t3/pending-lines",
            """{"lines":[{"itemId":"lantern-lager","variantId":"lantern-lager:pint","qty":1}]}""")
        assertEquals(HttpStatusCode.Conflict, pend.status)
        assertEquals("zone_closed",
            json.parseToJsonElement(pend.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)

        // slips are physical objects — still printable for a closed zone
        assertEquals(HttpStatusCode.OK, client.get("/tables/t3/slip").status)
    }
}
