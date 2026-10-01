package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.Checks
import dev.dwhipstock.poscloud.db.Events
import dev.dwhipstock.poscloud.db.IngestQuarantine
import io.ktor.server.testing.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One bad event in a batch never stops the rest from being stored (the NUL note poison pill). */
class IngestQuarantineTest {
    private val key = "test-store-key-quarantine"

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("copperlantern")
        seedStoreKey("copperlantern", "vieux-port", key)
    }

    private fun int(o: kotlinx.serialization.json.JsonObject, k: String) = o[k]!!.jsonPrimitive.content.toInt()

    @Test
    fun aNulInANoteIsDroppedAndTheEventIsKept() = testApplication {
        application { module(TestSupport.config) }
        val res = ingest(key,
            event("check.pending_line_submitted", buildJsonObject {
                put("checkId", 1); put("note", "extra\u0000 gravy\u001b"); put("na\u0000me", "x")
            }, seq = 1, eventId = "nul-1", aggregateType = "check"),
            event("check.closed", checkClosedPayload(2, 10000), seq = 2, eventId = "nul-2"),
        )
        assertEquals(2, int(res, "accepted"))
        assertEquals(0, int(res, "quarantined"))
        transaction {
            val stored = Events.selectAll().where { Events.eventId eq "nul-1" }.single()[Events.payload]
            val p = testJson.parseToJsonElement(stored).jsonObject
            assertEquals("extra gravy", p["note"]!!.jsonPrimitive.content)
            assertTrue("name" in p)
            assertEquals(1, Checks.selectAll().where { (Checks.tenantId eq "copperlantern") and (Checks.checkId eq 2) }.count())
        }
    }

    @Test
    fun anEventTheDatabaseRefusesIsSetAsideAndTheEventsAfterItStillLand() = testApplication {
        application { module(TestSupport.config) }
        // a stand-in for any event the cloud cannot store: the database refuses this type outright
        transaction {
            exec("""CREATE OR REPLACE FUNCTION test_refuse_poison() RETURNS trigger AS ${'$'}${'$'}
                BEGIN RAISE EXCEPTION 'refused by test'; END ${'$'}${'$'} LANGUAGE plpgsql""")
            exec("""CREATE TRIGGER test_refuse_poison BEFORE INSERT ON events FOR EACH ROW
                WHEN (NEW.event_type = 'test.poison') EXECUTE FUNCTION test_refuse_poison()""")
        }
        try {
            val batch = arrayOf(
                event("check.closed", checkClosedPayload(1, 5000), seq = 1, eventId = "q-1"),
                event("test.poison", buildJsonObject { put("note", "bad") }, seq = 2, eventId = "q-2"),
                event("check.closed", checkClosedPayload(3, 7000), seq = 3, eventId = "q-3"),
            )
            val res = ingest(key, *batch)
            assertEquals(2, int(res, "accepted"))
            assertEquals(1, int(res, "quarantined"))
            assertEquals(3, int(res, "highWaterMark"))
            transaction {
                assertEquals(2, Checks.selectAll().where { Checks.tenantId eq "copperlantern" }.count())
                assertEquals(0, Events.selectAll().where { Events.eventId eq "q-2" }.count())
                val q = IngestQuarantine.selectAll().where { IngestQuarantine.eventId eq "q-2" }.single()
                assertEquals("test.poison", q[IngestQuarantine.eventType])
                assertEquals(2, q[IngestQuarantine.storeSeq])
                assertTrue("refused by test" in q[IngestQuarantine.error], q[IngestQuarantine.error])
            }
            // the same batch again (a store retry): duplicates, and the poison set aside once
            val again = ingest(key, *batch)
            assertEquals(2, int(again, "duplicates"))
            assertEquals(1, int(again, "quarantined"))
            transaction { assertEquals(1, IngestQuarantine.selectAll().count()) }
        } finally {
            transaction {
                exec("DROP TRIGGER IF EXISTS test_refuse_poison ON events")
                exec("DROP FUNCTION IF EXISTS test_refuse_poison()")
            }
        }
    }
}
