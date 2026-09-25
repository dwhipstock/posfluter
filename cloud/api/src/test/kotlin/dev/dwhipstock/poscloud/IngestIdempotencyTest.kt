package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.CatalogItems
import dev.dwhipstock.poscloud.db.Checks
import io.ktor.server.testing.*
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class IngestIdempotencyTest {

    private val key = "test-store-key-idempotency"

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("copperlantern")
        seedStoreKey("copperlantern", "main", key)
    }

    @Test
    fun sameBatchTwiceCountsDuplicatesAndProjectsOnce() = testApplication {
        application { module(TestSupport.config) }
        val batch = arrayOf(
            event("check.closed", checkClosedPayload(1, 53500, storeTax(53500), "2026-07-10T20:00:00"),
                seq = 11, eventId = "e-1"),
            event("shift.opened", buildJsonObject {
                put("shiftId", 7)
                put("openedBy", "1234")
                put("openingFloatCents", 100000)
            }, seq = 12, eventId = "e-2"),
        )
        val first = ingest(key, *batch)
        assertEquals(2, first["accepted"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, first["duplicates"]!!.jsonPrimitive.content.toInt())
        assertEquals(12, first["highWaterMark"]!!.jsonPrimitive.content.toLong())

        val second = ingest(key, *batch)
        assertEquals(0, second["accepted"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, second["duplicates"]!!.jsonPrimitive.content.toInt())
        assertEquals(12, second["highWaterMark"]!!.jsonPrimitive.content.toLong())

        transaction {
            assertEquals(1, Checks.selectAll().where { Checks.tenantId eq "copperlantern" }.count())
        }
    }

    @Test
    fun duplicateEventIdWithinOneBatch() = testApplication {
        application { module(TestSupport.config) }
        val res = ingest(
            key,
            event("check.closed", checkClosedPayload(2, 10000), seq = 20, eventId = "dup"),
            event("check.closed", checkClosedPayload(2, 10000), seq = 21, eventId = "dup"),
        )
        assertEquals(1, res["accepted"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, res["duplicates"]!!.jsonPrimitive.content.toInt())
        transaction {
            assertEquals(1, Checks.selectAll().where { Checks.tenantId eq "copperlantern" }.count())
        }
    }

    @Test
    fun legacyThinShiftClosePreservesOpenSideColumns() = testApplication {
        application { module(TestSupport.config) }
        ingest(key, event("shift.opened", buildJsonObject {
            put("shiftId", 9)
            put("openedBy", "1234")
            put("openingFloatCents", 100000)
            put("openedAt", "2026-07-10T17:00:00")
        }, seq = 40, eventId = "sh-open"))
        // legacy thin Z echo: no openedAt/closedAt keys at all
        ingest(key, event("shift.closed", buildJsonObject {
            put("shiftId", 9)
            put("closedBy", "1234")
            put("revenueCents", 53500)
            put("expectedCashCents", 153500)
            put("closingCountCents", 153000)
            put("overShortCents", -500)
        }, seq = 41, eventId = "sh-close", createdAt = "2026-07-10T23:59:00"))
        transaction {
            val row = dev.dwhipstock.poscloud.db.Shifts.selectAll().single()
            assertEquals("CLOSED", row[dev.dwhipstock.poscloud.db.Shifts.status])
            // a legacy zone-less time is venue-local (EDT here) and stored as its instant
            assertEquals(java.time.Instant.parse("2026-07-10T21:00:00Z"), row[dev.dwhipstock.poscloud.db.Shifts.openedAt]?.toInstant())
            assertEquals("1234", row[dev.dwhipstock.poscloud.db.Shifts.openedBy])
            assertEquals(100000L, row[dev.dwhipstock.poscloud.db.Shifts.openingFloatCents])
            assertEquals(java.time.Instant.parse("2026-07-11T03:59:00Z"), row[dev.dwhipstock.poscloud.db.Shifts.closedAt]?.toInstant())
            assertEquals(53500L, row[dev.dwhipstock.poscloud.db.Shifts.revenueCents])
        }
    }

    @Test
    fun outOfOrderSeqProjectsInSeqOrder() = testApplication {
        application { module(TestSupport.config) }
        // array order is reversed vs seq: correct behavior applies seq 9 then 10,
        // leaving the seq-10 figures as the final projection
        ingest(
            key,
            event("check.closed", checkClosedPayload(3, 50000, storeTax(50000)), seq = 10, eventId = "later"),
            event("check.closed", checkClosedPayload(3, 30000, storeTax(30000)), seq = 9, eventId = "earlier"),
        )
        transaction {
            val row = Checks.selectAll().where { Checks.tenantId eq "copperlantern" }.single()
            assertEquals(50000L, row[Checks.grandTotalCents])
        }

        // same for catalog: the seq-later snapshot must win
        val snapshot = { name: String ->
            buildJsonObject {
                put("item", buildJsonObject {
                    put("id", "lantern-lager")
                    put("nameFr", "Lager de la Lanterne")
                    put("nameEn", name)
                    put("categoryId", "beer")
                    put("variants", buildJsonArray { })
                })
            }
        }
        ingest(
            key,
            event("item.updated", snapshot("Final Name"), seq = 31, eventId = "i-2"),
            event("item.updated", snapshot("Stale Name"), seq = 30, eventId = "i-1"),
        )
        transaction {
            val row = CatalogItems.selectAll().where { CatalogItems.id eq "lantern-lager" }.single()
            assertEquals("Final Name", row[CatalogItems.nameEn])
        }
    }
}
