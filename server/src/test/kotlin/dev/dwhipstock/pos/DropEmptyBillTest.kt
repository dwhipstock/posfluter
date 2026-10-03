package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.Tenders
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.restaurant.BillGroups
import dev.dwhipstock.pos.restaurant.Checks
import dev.dwhipstock.pos.restaurant.StripePayments
import dev.dwhipstock.pos.restaurant.TerminalPayments
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A table tapped and left with nothing on it: its empty bill is dropped
 * (POST /checks/{id}/drop-if-empty) so the floor shows the table free. A bill
 * with anything on it is never dropped.
 */
class DropEmptyBillTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-drop-empty").resolve("pos.db").toString()

    private suspend fun HttpClient.postJson(path: String, body: String = "{}") =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }
    private suspend fun HttpResponse.obj(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpClient.open(table: String): Int = postJson("/tables/$table/checks").obj()["id"]!!.jsonPrimitive.int
    private suspend fun HttpClient.drop(id: Int): JsonObject =
        postJson("/checks/$id/drop-if-empty").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }.obj()

    /** The floor's open-check status for a table (null = shown free). */
    private suspend fun HttpClient.floor(tableId: String): String? =
        json.parseToJsonElement(get("/zones").bodyAsText()).jsonArray
            .flatMap { it.jsonObject["tables"]!!.jsonArray }
            .first { it.jsonObject["id"]!!.jsonPrimitive.content == tableId }
            .jsonObject["openCheckStatus"]?.jsonPrimitive?.contentOrNull

    private fun status(id: Int) = transaction { Checks.selectAll().where { Checks.id eq id }.single()[Checks.status] }
    private fun cancelEvents(id: Int) = transaction {
        SyncOutbox.selectAll().filter { it[SyncOutbox.eventType] == "check.cancelled" && "\"checkId\":$id" in it[SyncOutbox.payload] }
            .map { it[SyncOutbox.payload] }
    }

    @Test
    fun anEmptyBillIsDroppedAndTheTableIsFree() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val id = c.open("t3")
        assertEquals("OPEN", c.floor("t3"))

        val after = c.drop(id)
        assertEquals("CANCELLED", after["status"]!!.jsonPrimitive.content)
        assertNull(c.floor("t3"))
        val events = cancelEvents(id)
        assertEquals(1, events.size)
        assertEquals(true, "\"reason\":\"empty\"" in events.single())

        // idempotent: again = still CANCELLED, no second event
        assertEquals("CANCELLED", c.drop(id)["status"]!!.jsonPrimitive.content)
        assertEquals(1, cancelEvents(id).size)

        // the table opens a fresh bill afterwards
        val next = c.open("t3")
        assertEquals(true, next != id)
        assertEquals("OPEN", c.floor("t3"))
    }

    @Test
    fun needsASignIn() = testApplication {
        application { module(dbPath = tempDb()) }
        val id = loginClient().open("t3")
        assertEquals(HttpStatusCode.Unauthorized, client.post("/checks/$id/drop-if-empty").status)
        assertEquals("OPEN", status(id))
    }

    @Test
    fun aBillWithALineIsKept() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val id = c.open("t3")
        c.postJson("/checks/$id/lines", """{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":1}""")
        val after = c.drop(id)
        assertEquals("OPEN", after["status"]!!.jsonPrimitive.content)
        assertEquals(1, after["lines"]!!.jsonArray.size)
        assertEquals("OPEN", c.floor("t3"))
    }

    @Test
    fun aBillWithAGuestsPendingOrderIsKept() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val id = c.open("t5-5")
        client.postJson("${customerPath("t5-5")}/pending-lines",
            """{"lines":[{"itemId":"lantern-lager","variantId":"lantern-lager:pitcher","qty":1}]}""")
        val after = c.drop(id)
        assertEquals("OPEN", after["status"]!!.jsonPrimitive.content)
        assertEquals(1, after["pendingLines"]!!.jsonArray.size)
    }

    @Test
    fun aBillWithATenderIsKept() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val id = c.open("t3")
        transaction {
            Tenders.insert {
                it[transactionId] = id; it[type] = "CASH"; it[amountTenderedCents] = 0; it[amountAppliedCents] = 0
                it[createdAt] = Instant.now(); it[reversedAt] = Instant.now()
            }
        }
        c.drop(id)
        assertEquals("OPEN", status(id))
    }

    @Test
    fun aBillWithACardOnTheReaderIsKept() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val terminal = c.open("t3")
        val stripe = c.open("t4")
        transaction {
            TerminalPayments.insert {
                it[publicId] = "tp_test"; it[provider] = "simulator"; it[checkId] = terminal; it[amountCents] = 100
                it[currency] = "USD"; it[status] = "PENDING"; it[createdAt] = Instant.now(); it[updatedAt] = Instant.now()
            }
            StripePayments.insert {
                it[publicId] = "sp_test"; it[checkId] = stripe; it[amountCents] = 100
                it[currency] = "usd"; it[status] = "CREATED"; it[createdAt] = Instant.now(); it[updatedAt] = Instant.now()
            }
        }
        c.drop(terminal)
        c.drop(stripe)
        assertEquals("OPEN", status(terminal))
        assertEquals("OPEN", status(stripe))
    }

    @Test
    fun aSplitBillIsKept() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val id = c.open("t3")
        transaction {
            BillGroups.insert { it[checkId] = id; it[groupNumber] = 1; it[createdAt] = Instant.now() }
        }
        c.drop(id)
        assertEquals("OPEN", status(id))
    }

    @Test
    fun aLockedBillIsKept() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val id = c.open("t3")
        transaction { Checks.update({ Checks.id eq id }) { it[status] = "TOTAL_LOCKED" } }
        assertEquals("TOTAL_LOCKED", c.drop(id)["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun aCorkageOnlyBillIsKept() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val id = c.open("t3")
        c.postJson("/checks/$id/corkage", """{"bottles":1}""")
        c.drop(id)
        assertEquals("OPEN", status(id))
    }

    /** The app was closed on the check screen: after 2 minutes the floor shows the table free, the bill stays. */
    @Test
    fun theFloorShowsAnOldEmptyBillsTableAsFree() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val empty = c.open("t3")
        val withLine = c.open("t4")
        c.postJson("/checks/$withLine/lines", """{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":1}""")
        assertEquals("OPEN", c.floor("t3")) // just opened: someone may be on it

        val old = Instant.now().minus(Duration.ofMinutes(3))
        transaction { for (id in listOf(empty, withLine)) Checks.update({ Checks.id eq id }) { it[openedAt] = old } }
        assertNull(c.floor("t3"))
        assertEquals("OPEN", c.floor("t4")) // a bill with a line always shows
        assertEquals("OPEN", status(empty)) // only hidden, not dropped

        // tapping the table again opens the same bill
        assertEquals(empty, c.open("t3"))
    }
}
