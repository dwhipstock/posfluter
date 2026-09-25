package dev.dwhipstock.pos

import dev.dwhipstock.pos.db.SyncOutbox
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settlement-time split checks: bill groups partition a check's lines (by
 * qty allocation), each group prints/pays on its own, per-group totals run
 * through the pricing pipeline, and the check finalizes only when every
 * group is fully covered. The split locks at the first group tender.
 */
class SplitCheckTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    private suspend fun HttpClient.postJson(path: String, body: String = "{}"): HttpResponse =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.check(checkId: Int): JsonObject =
        json.parseToJsonElement(get("/checks/$checkId").bodyAsText()).jsonObject

    private fun JsonObject.split(): JsonObject = this["split"]!!.jsonObject
    private fun JsonObject.groups() = split()["groups"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.groupId(number: Int): Int =
        groups().first { it["number"]!!.jsonPrimitive.int == number }["id"]!!.jsonPrimitive.int
    private fun JsonObject.groupTotal(number: Int): Long =
        groups().first { it["number"]!!.jsonPrimitive.int == number }["grandTotalCents"]!!.jsonPrimitive.long
    private fun JsonObject.groupOutstanding(number: Int): Long =
        groups().first { it["number"]!!.jsonPrimitive.int == number }["outstandingCents"]!!.jsonPrimitive.long

    private suspend fun errorCode(res: HttpResponse): String =
        json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content

    /** Open check on t3 with amberAle ×3 ($330) + lantern-lager ×1 ($100), shift open. Returns (checkId, leoLineId, changLineId). */
    private suspend fun setUpCheck(c: HttpClient): Triple<Int, Int, Int> {
        c.postJson("/shifts", """{"openingFloatCents":100000,"managerPin":"1234"}""")
        val checkId = json.parseToJsonElement(c.post("/tables/t3/checks").bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$checkId/lines", """{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":3}""")
        val lines = json.parseToJsonElement(
            c.postJson("/checks/$checkId/lines", """{"itemId":"lantern-lager","variantId":"lantern-lager:pint","qty":1}""")
                .bodyAsText()).jsonObject["lines"]!!.jsonArray.map { it.jsonObject }
        val amberAle = lines.first { it["itemId"]!!.jsonPrimitive.content == "amber-ale" }["id"]!!.jsonPrimitive.int
        val lanternLager = lines.first { it["itemId"]!!.jsonPrimitive.content == "lantern-lager" }["id"]!!.jsonPrimitive.int
        return Triple(checkId, amberAle, lanternLager)
    }

    @Test
    fun splitAssignTenderFinalize() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val (checkId, amberAle, lanternLager) = setUpCheck(c)
        c.postJson("/checks/$checkId/corkage", """{"bottles":1}""") // $200, defaults to group 1

        // create a 2-group split; everything starts unassigned
        val created = json.parseToJsonElement(
            c.postJson("/checks/$checkId/split", """{"groups":2}""").bodyAsText()).jsonObject
        assertEquals(2, created.groups().size)
        assertTrue(created.groups().first()["includesCorkage"]!!.jsonPrimitive.boolean)
        assertEquals(4, created.split()["unassigned"]!!.jsonArray.sumOf {
            it.jsonObject["qty"]!!.jsonPrimitive.int })
        val g1 = created.groupId(1)
        val g2 = created.groupId(2)

        // qty splits across groups: 2 of 3 amberAle → g1, the rest → g2
        c.postJson("/checks/$checkId/split/groups/$g1/lines", """{"lineId":$amberAle,"qty":2}""")
        c.postJson("/checks/$checkId/split/groups/$g2/lines", """{"lineId":$amberAle,"qty":1}""")
        val assigned = json.parseToJsonElement(
            c.postJson("/checks/$checkId/split/groups/$g2/lines", """{"lineId":$lanternLager,"qty":1}""")
                .bodyAsText()).jsonObject

        // per-group totals from the server pipeline: g1 = 2×110 + 200 corkage, g2 = 110 + 100
        assertEquals(4250L, assigned.groupTotal(1))
        assertEquals(1700L, assigned.groupTotal(2))
        assertTrue(assigned.split()["unassigned"]!!.jsonArray.isEmpty())

        // per-group provisional bill: only g1's items + its own total
        val bill = json.parseToJsonElement(
            c.post("/checks/$checkId/bill?groupId=$g1").bodyAsText())
            .jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("CUSTOMER BILL" in bill)
        assertTrue("Copper Amber Ale" in bill || "Ale ambrée" in bill)
        assertTrue("Lantern House Lager" !in bill && "Lager de la Lanterne" !in bill, "group 1 bill must not show group 2's items")
        assertTrue("42.50" in bill, "group bill total is the group's own")

        // pay g1 → check locks, g2 still owes; finalize refused until every group covered
        val t1 = c.postJson("/checks/$checkId/tenders",
            """{"type":"CASH","amountTenderedCents":4250,"groupId":$g1}""")
        assertEquals(HttpStatusCode.Created, t1.status)
        val afterT1 = c.check(checkId)
        assertEquals("TOTAL_LOCKED", afterT1["status"]!!.jsonPrimitive.content)
        assertEquals(0L, afterT1.groupOutstanding(1))
        assertEquals(1700L, afterT1.groupOutstanding(2))
        assertEquals(HttpStatusCode.Conflict, c.post("/checks/$checkId/finalize").status)

        // pay g2 → all groups covered → finalize closes the check
        c.postJson("/checks/$checkId/tenders",
            """{"type":"CASH","amountTenderedCents":1700,"groupId":$g2}""")
        assertEquals(HttpStatusCode.OK, c.post("/checks/$checkId/finalize").status)
        assertEquals("CLOSED", c.check(checkId)["status"]!!.jsonPrimitive.content)

        transaction {
            val events = SyncOutbox.selectAll().map { it[SyncOutbox.eventType] }
            assertEquals(1, events.count { it == "split.created" })
            assertEquals(3, events.count { it == "split.line_assigned" })
            assertEquals(2, events.count { it == "split.group_tendered" })
            assertEquals(1, events.count { it == "check.closed" })
        }
    }

    @Test
    fun groupTenderRequiresFullAllocationAndGroupScope() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val (checkId, amberAle, _) = setUpCheck(c)
        val created = json.parseToJsonElement(
            c.postJson("/checks/$checkId/split", """{"groups":2}""").bodyAsText()).jsonObject
        val g1 = created.groupId(1)

        // split check + tender without a group → refused
        val noGroup = c.postJson("/checks/$checkId/tenders",
            """{"type":"CASH","amountTenderedCents":50000}""")
        assertEquals(HttpStatusCode.Conflict, noGroup.status)
        assertEquals("group_required", errorCode(noGroup))

        // lantern-lager + 1 amberAle still unassigned → no group may tender yet
        c.postJson("/checks/$checkId/split/groups/$g1/lines", """{"lineId":$amberAle,"qty":2}""")
        val premature = c.postJson("/checks/$checkId/tenders",
            """{"type":"CASH","amountTenderedCents":22000,"groupId":$g1}""")
        assertEquals(HttpStatusCode.Conflict, premature.status)
        assertEquals("split_unassigned_lines", errorCode(premature))
        assertEquals("OPEN", c.check(checkId)["status"]!!.jsonPrimitive.content)

        // over-assigning beyond the line's unassigned qty is refused
        val over = c.postJson("/checks/$checkId/split/groups/$g1/lines", """{"lineId":$amberAle,"qty":2}""")
        assertEquals(HttpStatusCode.Conflict, over.status)
        assertEquals("qty_exceeds_unassigned", errorCode(over))

        // shrinking a line below its allocated qty is refused (unassign first)
        val shrink = c.postJson("/checks/$checkId/lines/$amberAle/qty", """{"qty":1}""")
        assertEquals(HttpStatusCode.Conflict, shrink.status)
        assertEquals("qty_below_allocated", errorCode(shrink))
    }

    @Test
    fun splitLocksAtFirstGroupTender() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val (checkId, amberAle, lanternLager) = setUpCheck(c)
        val created = json.parseToJsonElement(
            c.postJson("/checks/$checkId/split", """{"groups":2}""").bodyAsText()).jsonObject
        val g1 = created.groupId(1)
        val g2 = created.groupId(2)
        c.postJson("/checks/$checkId/split/groups/$g1/lines", """{"lineId":$amberAle,"qty":3}""")
        c.postJson("/checks/$checkId/split/groups/$g2/lines", """{"lineId":$lanternLager,"qty":1}""")
        c.postJson("/checks/$checkId/tenders",
            """{"type":"CASH","amountTenderedCents":33000,"groupId":$g1}""")

        // any split edit after money is applied → split_locked
        val moves = listOf(
            c.postJson("/checks/$checkId/split/groups/$g2/lines", """{"lineId":$amberAle,"qty":1}"""),
            c.postJson("/checks/$checkId/split/groups/$g1/lines/$amberAle/unassign", """{"qty":1}"""),
            c.postJson("/checks/$checkId/split/groups"),
            c.delete("/checks/$checkId/split/groups/$g2"),
            c.delete("/checks/$checkId/split"),
            c.postJson("/checks/$checkId/split/corkage", """{"groupId":$g2}"""),
        )
        for (res in moves) {
            assertEquals(HttpStatusCode.Conflict, res.status)
            assertEquals("split_locked", errorCode(res))
        }
        // and a paid group refuses further tenders
        val again = c.postJson("/checks/$checkId/tenders",
            """{"type":"CASH","amountTenderedCents":10000,"groupId":$g1}""")
        assertEquals("group_already_paid", errorCode(again))
    }

    @Test
    fun deleteGroupReturnsLinesAndPassesCorkageOn() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val (checkId, amberAle, _) = setUpCheck(c)
        c.postJson("/checks/$checkId/corkage", """{"bottles":2}""")
        val created = json.parseToJsonElement(
            c.postJson("/checks/$checkId/split", """{"groups":2}""").bodyAsText()).jsonObject
        val g1 = created.groupId(1)
        val g2 = created.groupId(2)
        c.postJson("/checks/$checkId/split/groups/$g1/lines", """{"lineId":$amberAle,"qty":2}""")

        // deleting the corkage-carrying group hands corkage to the survivor
        // and returns its lines to unassigned
        val after = json.parseToJsonElement(
            c.delete("/checks/$checkId/split/groups/$g1").bodyAsText()).jsonObject
        assertEquals(1, after.groups().size)
        assertTrue(after.groups().single()["includesCorkage"]!!.jsonPrimitive.boolean)
        assertEquals(4, after.split()["unassigned"]!!.jsonArray.sumOf {
            it.jsonObject["qty"]!!.jsonPrimitive.int })

        // the last group can't be deleted — clear the split instead
        val last = c.delete("/checks/$checkId/split/groups/$g2")
        assertEquals("last_group", errorCode(last))
        val cleared = json.parseToJsonElement(c.delete("/checks/$checkId/split").bodyAsText()).jsonObject
        assertNull(cleared["split"]?.takeIf { it !is kotlinx.serialization.json.JsonNull })
        // and a cleared check can be split again
        assertEquals(HttpStatusCode.Created,
            c.postJson("/checks/$checkId/split", """{"groups":3}""").status)
    }

    @Test
    fun evenSplitFloorsSharesRemainderToGroup1AndRoundsPerGroupCash() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val (checkId, _, _) = setUpCheck(c) // total $34.50

        val created = json.parseToJsonElement(
            c.postJson("/checks/$checkId/split", """{"groups":3,"even":true}""").bodyAsText()).jsonObject
        assertTrue(created.split()["even"]!!.jsonPrimitive.boolean)
        // money-only split: no line assignment, so no unassigned pool (the client
        // gates Pay on the pool being empty)
        assertTrue(created.split()["unassigned"]!!.jsonArray.isEmpty())
        assertEquals(1150L, created.groupTotal(1))
        assertEquals(1150L, created.groupTotal(2))
        assertEquals(1150L, created.groupTotal(3))

        // by-item edits don't apply to a money-only split
        val assign = c.postJson(
            "/checks/$checkId/split/groups/${created.groupId(1)}/lines", """{"lineId":1,"qty":1}""")
        assertEquals("even_split", errorCode(assign))

        // Each equal share is already on a five-cent boundary.
        for (n in 1..3) {
            val res = c.postJson("/checks/$checkId/tenders",
                """{"type":"CASH","amountTenderedCents":1150,"groupId":${created.groupId(n)}}""")
            assertEquals(HttpStatusCode.Created, res.status, "group $n cash settles its share")
            val tender = json.parseToJsonElement(res.bodyAsText()).jsonObject["tender"]!!.jsonObject
            assertEquals(0L, tender["roundingAdjustmentCents"]!!.jsonPrimitive.long)
        }
        assertEquals(HttpStatusCode.OK, c.post("/checks/$checkId/finalize").status)
        assertEquals("CLOSED", c.check(checkId)["status"]!!.jsonPrimitive.content)
    }
}
