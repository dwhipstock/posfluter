package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.SettingsRepository
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternConfig
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.restaurant.CheckService
import dev.dwhipstock.pos.restaurant.Checks
import dev.dwhipstock.pos.sdk.PrinterAdapter
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The backfill that fixes cloud VAT/payment-mix undercounting: re-emitting a
 * report-complete `check.closed` for a check that already synced. Drives a real
 * sale, then re-runs the backfill and asserts a fresh COMPLETE event (with
 * decomposed VAT + tenders) is written — the thing thin historical events lack.
 */
class ReportBackfillTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-backfill").resolve("pos.db").toString()

    private suspend fun io.ktor.client.HttpClient.postJson(path: String, body: String) =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    private fun closedEventsFor(checkId: Int) = transaction {
        SyncOutbox.selectAll()
            .where { (SyncOutbox.eventType eq "check.closed") and (SyncOutbox.aggregateId eq checkId.toString()) }
            .orderBy(SyncOutbox.id, SortOrder.DESC)
            .map { Json.parseToJsonElement(it[SyncOutbox.payload]).jsonObject }
    }

    @Test
    fun backfillReEmitsAReportCompleteClosedEventPerClosedCheck() = testApplication {
        val receipts = Files.createTempDirectory("pos-receipts").toString()
        val bills = Files.createTempDirectory("pos-bills").toString()
        application { module(dbPath = tempDb(), receiptsDir = receipts, billsDir = bills) }
        val c = loginClient()

        c.postJson("/shifts", """{"openingFloatCents":100000,"managerPin":"1234"}""")
            .let { assertEquals(HttpStatusCode.Created, it.status) }
        val checkId = json.parseToJsonElement(c.postJson("/tables/t5/checks", "{}").bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$checkId/lines", """{"itemId":"lantern-lager","variantId":"lantern-lager:pitcher","qty":1}""")
            .let { assertEquals(HttpStatusCode.Created, it.status) }
        c.postJson("/checks/$checkId/tenders", """{"type":"CASH","amountTenderedCents":100000}""")
            .let { assertEquals(HttpStatusCode.Created, it.status) }
        c.post("/checks/$checkId/finalize").let { assertEquals(HttpStatusCode.OK, it.status) }

        // one complete event from the live close
        assertEquals(1, closedEventsFor(checkId).size)

        // re-run the backfill against the same DB: one CLOSED check → one re-emit
        val config = CopperLanternConfig(
            settings = SettingsRepository(),
            printer = PrinterAdapter.VirtualPrinter(receipts, bills),
            publicBaseUrl = "http://test",
        )
        val emitted = CheckService(config).backfillReportCompleteClosedEvents()
        assertEquals(1, emitted)

        val events = closedEventsFor(checkId)
        assertEquals(2, events.size) // original + backfilled
        val original = events.last()
        val backfilled = events.first() // newest
        // the backfilled event is report-complete — the two things thin
        // historical events were missing — and agrees with the live close.
        assertEquals(
            original["grandTotalCents"]!!.jsonPrimitive.content.toLong(),
            backfilled["grandTotalCents"]!!.jsonPrimitive.content.toLong())
        assertEquals(0L, backfilled["taxIncludedCents"]!!.jsonPrimitive.content.toLong())
        assertTrue(backfilled["tenders"]!!.jsonArray.isNotEmpty())
        assertEquals("CASH", backfilled["tenders"]!!.jsonArray.first().jsonObject["type"]!!.jsonPrimitive.content)
    }

    /**
     * Regression: a degenerate legacy check — no lines, no tenders, and a
     * dangling table ref (its table/zone were hard-deleted) — must not make the
     * backfill throw. The original re-emit called tableZoneRow.first() over an
     * empty join and blew up with "Collection is empty."; because the backfill
     * runs before the drain, every sync tick then died before the marker could
     * be set, so it retried forever and no sync drained at all. A clean fixture
     * (live table, real lines/tenders) never hit it. Here the bad check must be
     * re-emitted well-formed AND its healthy sibling must still back-fill —
     * one pathological row can't abort the batch.
     */
    @Test
    fun backfillToleratesADegenerateCheckWithNoLinesTendersOrTable() = testApplication {
        val receipts = Files.createTempDirectory("pos-receipts").toString()
        val bills = Files.createTempDirectory("pos-bills").toString()
        application { module(dbPath = tempDb(), receiptsDir = receipts, billsDir = bills) }
        val c = loginClient()

        // a healthy CLOSED check from a real sale
        c.postJson("/shifts", """{"openingFloatCents":100000,"managerPin":"1234"}""")
            .let { assertEquals(HttpStatusCode.Created, it.status) }
        val healthyId = json.parseToJsonElement(c.postJson("/tables/t5/checks", "{}").bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$healthyId/lines", """{"itemId":"lantern-lager","variantId":"lantern-lager:pitcher","qty":1}""")
            .let { assertEquals(HttpStatusCode.Created, it.status) }
        c.postJson("/checks/$healthyId/tenders", """{"type":"CASH","amountTenderedCents":100000}""")
            .let { assertEquals(HttpStatusCode.Created, it.status) }
        c.post("/checks/$healthyId/finalize").let { assertEquals(HttpStatusCode.OK, it.status) }

        // a degenerate legacy check: locked totals stamped, but no lines, no
        // tenders, and a table_id that resolves to nothing.
        val degenerateId = transaction {
            Checks.insertAndGetId {
                it[tableId] = "ghost-table" // no matching DiningTables row
                it[status] = "CLOSED"
                it[openedBy] = "u1"
                it[openedAt] = java.time.Instant.parse("2026-01-01T23:00:00Z")
                it[closedAt] = java.time.Instant.parse("2026-01-02T00:00:00Z")
                it[lockedGrandTotalCents] = 5000
                it[lockedTaxIncludedCents] = 327
                it[lockedFeesJson] = "[]"
            }.value
        }

        // must NOT throw, and must re-emit BOTH checks
        val config = CopperLanternConfig(
            settings = SettingsRepository(),
            printer = PrinterAdapter.VirtualPrinter(receipts, bills),
            publicBaseUrl = "http://test",
        )
        val emitted = CheckService(config).backfillReportCompleteClosedEvents()
        assertEquals(2, emitted)

        // the degenerate check's event is well-formed: money carried through
        // intact, empty lines + tenders, and null zone/table labels (not a throw)
        val bad = closedEventsFor(degenerateId).first()
        assertEquals(5000, bad["grandTotalCents"]!!.jsonPrimitive.content.toLong())
        assertEquals(327, bad["taxIncludedCents"]!!.jsonPrimitive.content.toLong())
        assertTrue(bad["lines"]!!.jsonArray.isEmpty())
        assertTrue(bad["tenders"]!!.jsonArray.isEmpty())
        assertEquals(JsonNull, bad["zoneId"])
        assertEquals(JsonNull, bad["tableLabel"])

        // and the healthy sibling still back-filled a report-complete event
        assertTrue(closedEventsFor(healthyId).first()["tenders"]!!.jsonArray.isNotEmpty())
    }
}
