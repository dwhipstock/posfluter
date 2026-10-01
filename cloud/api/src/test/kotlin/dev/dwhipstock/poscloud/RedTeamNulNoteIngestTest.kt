package dev.dwhipstock.poscloud

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * RED TEAM (redteam/inputs) — expected to FAIL until fixed.
 *
 * A guest's QR note (or a kiosk note) is free text with no control-character
 * filter on the store. A note containing U+0000 is stored on the store and
 * written to the outbox as `"note":"...\u0000..."`. Postgres JSONB cannot hold
 * \u0000 (SQLSTATE 22P05), and /v1/ingest runs the whole batch in ONE
 * transaction, so the batch fails; the store's push HWM only advances on a 200,
 * so it re-sends the same batch forever: every later sale never reaches the
 * portal.
 */
class RedTeamNulNoteIngestTest {
    private val key = "test-store-key-redteam-nul"

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("copperlantern")
        seedStoreKey("copperlantern", "vieux-port", key)
    }

    @Test
    fun aGuestNoteWithANulByteDoesNotWedgeTheIngestBatch() = testApplication {
        application { module(TestSupport.config) }
        val body = ingestBody(
            event("check.pending_line_submitted", buildJsonObject {
                put("checkId", 1); put("lineId", 3); put("itemId", "poutine"); put("variantId", "poutine:regular")
                put("qty", 1); put("note", "extra gravy\u0000")
            }, seq = 1, eventId = "rt-nul-1", aggregateType = "check"),
            event("check.closed", checkClosedPayload(2, 10000), seq = 2, eventId = "rt-nul-2"),
        )
        val res = client.post("/v1/ingest") {
            header(HttpHeaders.Authorization, "Bearer $key")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        // today: 500 (org.postgresql: unsupported Unicode escape sequence) and nothing in the batch lands
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
    }
}
