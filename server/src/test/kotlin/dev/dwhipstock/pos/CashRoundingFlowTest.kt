package dev.dwhipstock.pos

import dev.dwhipstock.pos.customers.copperlantern.CopperLanternConfig
import dev.dwhipstock.pos.customers.sagepoppy.SagePoppy
import dev.dwhipstock.pos.customers.sagepoppy.SagePoppySeed
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.sdk.CashRounding
import dev.dwhipstock.pos.sdk.Money
import dev.dwhipstock.pos.sdk.RoundingPolicy
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cash rounding to the nickel end to end, on the pubs (CAD) and the bottle
 * shop (USD): what a check says cash comes to, cash vs card, mixed and split
 * payments, cash refunds, the bill and receipt lines, the drawer, the sync
 * events, and the switch turned off.
 */
class CashRoundingFlowTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-rounding").resolve("pos.db").toString()
    private fun obj(text: String): JsonObject = json.parseToJsonElement(text).jsonObject
    private fun JsonObject.long(key: String) = this[key]!!.jsonPrimitive.long

    private suspend fun HttpClient.postJson(path: String, body: String = "{}") =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.check(id: Int) = obj(get("/checks/$id").bodyAsText())

    private suspend fun HttpClient.openShift(float: Long = 10000) =
        postJson("/shifts", """{"openingFloatCents":$float,"managerPin":"1234"}""")
            .also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }

    /** A Québec total (price + GST 5% + QST 9.975%, each half-up) for a pre-tax price. */
    private fun quebecTotal(price: Long): Long =
        price + CopperLanternConfig.QUEBEC_TAXES.sumOf { it.on(Money(price)).cents }

    /** The smallest pre-tax price ≥ [from] whose Québec total ends in [digit]. */
    private fun priceEndingIn(digit: Int, from: Long = 1000): Long =
        generateSequence(from) { it + 1 }.first { quebecTotal(it) % 10 == digit.toLong() }

    /** Open a check on [table] with one off-menu line at [price]; returns (id, check view). */
    private suspend fun HttpClient.checkAt(table: String, price: Long): Pair<Int, JsonObject> {
        val id = obj(postJson("/tables/$table/checks", """{"userId":"manager"}""").bodyAsText())["id"]!!.jsonPrimitive.int
        val view = postJson("/checks/$id/open-lines", """{"name":"Plat","unitPriceCents":$price,"qty":1}""")
            .also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }.bodyAsText().let(::obj)
        return id to view
    }

    private suspend fun HttpClient.cash(id: Int, cents: Long, group: Int? = null): JsonObject =
        postJson("/checks/$id/tenders", """{"type":"CASH","amountTenderedCents":$cents${group?.let { ",\"groupId\":$it" } ?: ""}}""")
            .also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }.bodyAsText().let(::obj)

    private suspend fun HttpClient.card(id: Int, cents: Long, group: Int? = null): JsonObject {
        val g = group?.let { ",\"groupId\":$it" } ?: ""
        postJson("/checks/$id/tenders/initiate", """{"type":"CARD","amountCents":$cents$g}""")
            .also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        return postJson("/checks/$id/tenders/confirm", """{"type":"CARD","amountCents":$cents$g}""")
            .also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }.bodyAsText().let(::obj)
    }

    private suspend fun HttpClient.finalize(id: Int) =
        post("/checks/$id/finalize").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }

    private fun kv(text: String) = text.lines().map { it.trim().replace(Regex(" {2,}"), " | ") }

    private fun lastPayload(type: String): JsonObject = transaction {
        SyncOutbox.selectAll().where { SyncOutbox.eventType eq type }
            .orderBy(SyncOutbox.id to SortOrder.DESC).first()[SyncOutbox.payload]
    }.let(::obj)

    @Test
    fun everyLastDigitSettlesInCashAtTheNickelAndTheDrawerCountsTheRoundedCash() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        c.openShift(float = 10000)
        var drawer = 10000L
        var rounding = 0L
        for (digit in 0..9) {
            val (id, view) = c.checkAt("t1", priceEndingIn(digit))
            val total = view.long("grandTotalCents")
            assertEquals(digit.toLong(), total % 10, "total $total")
            val due = RoundingPolicy.NICKEL.roundCashDue(Money(total)).cents
            // the check itself says what cash comes to; the total stays exact
            assertEquals(due, view.long("cashDueCents"), "digit $digit")
            assertEquals(due - total, view.long("cashRoundingCents"), "digit $digit")
            assertEquals(total, view.long("outstandingCents"))
            // a $100 note: change is from the rounded amount
            val res = c.cash(id, 10000)
            val tender = res["tender"]!!.jsonObject
            assertEquals(total, tender.long("amountAppliedCents"))
            assertEquals(due - total, tender.long("roundingAdjustmentCents"))
            assertEquals(10000 - due, tender.long("changeCents"))
            assertEquals(0L, res["check"]!!.jsonObject.long("outstandingCents"))
            c.finalize(id)
            drawer += due
            rounding += due - total
        }
        // X and Z: the drawer holds the rounded cash, never the exact totals
        val x = obj(c.get("/shifts/current/report").bodyAsText())
        assertEquals(drawer, x.long("expectedCashCents"))
        assertEquals(rounding, x.long("cashRoundingCents"))
        val z = obj(c.postJson("/shifts/current/close", """{"closingCountCents":$drawer,"managerPin":"1234"}""").bodyAsText())
        assertEquals(drawer, z.long("expectedCashCents"))
        assertEquals(0L, z.long("overShortCents"))
        // revenue stays exact
        assertEquals(drawer - 10000 - rounding, z.long("revenueCents"))
        assertEquals(rounding, lastPayload("shift.closed").long("cashRoundingCents"))
    }

    @Test
    fun oneCentThreeCentsAndTenOhSeven() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        c.openShift()
        // 0.01 (no tax on a cent): nothing to hand over in cash
        val (penny, pv) = c.checkAt("t1", 1)
        assertEquals(1L, pv.long("grandTotalCents"))
        assertEquals(0L, pv.long("cashDueCents"))
        assertEquals(-1L, pv.long("cashRoundingCents"))
        val t = c.cash(penny, 0)["tender"]!!.jsonObject
        assertEquals(-1L, t.long("roundingAdjustmentCents"))
        assertEquals(0L, t.long("changeCents"))
        c.finalize(penny)
        // 0.03 → a nickel
        val (three, tv) = c.checkAt("t1", 3)
        assertEquals(3L, tv.long("grandTotalCents"))
        assertEquals(5L, tv.long("cashDueCents"))
        assertEquals(HttpStatusCode.BadRequest, c.postJson("/checks/$three/tenders", """{"type":"CASH","amountTenderedCents":0}""").status)
        assertEquals(2L, c.cash(three, 5)["tender"]!!.jsonObject.long("roundingAdjustmentCents"))
        c.finalize(three)
        // 10.07 → 10.05
        val price = generateSequence(800L) { it + 1 }.first { quebecTotal(it) == 1007L }
        val (ten, v) = c.checkAt("t1", price)
        assertEquals(1007L, v.long("grandTotalCents"))
        assertEquals(1005L, v.long("cashDueCents"))
        assertEquals(995L, c.cash(ten, 2000)["tender"]!!.jsonObject.long("changeCents"))
        c.finalize(ten)
        val receipt = kv(obj(c.get("/checks/$ten/receipt").bodyAsText())["text"]!!.jsonPrimitive.content)
        assertTrue(receipt.any { it.endsWith("| 10.07") }, receipt.joinToString("\n")) // the exact total
        assertTrue(receipt.any { it.matches(Regex("(Rounding|Arrondi) \\| -0.02")) }, receipt.joinToString("\n"))
        assertTrue(receipt.any { it.matches(Regex("(Cash total|Total comptant) \\| 10.05")) }, receipt.joinToString("\n"))
    }

    @Test
    fun cardIsChargedTheExactAmount() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        c.openShift()
        val (id, view) = c.checkAt("t2", priceEndingIn(7))
        val total = view.long("grandTotalCents")
        // the card terminal is asked for the exact total, not the cash figure
        val res = c.card(id, total)
        val tender = res["tender"]!!.jsonObject
        assertEquals(total, tender.long("amountAppliedCents"))
        assertEquals(0L, tender.long("roundingAdjustmentCents"))
        assertEquals(0L, tender.long("changeCents"))
        c.finalize(id)
        val receipt = obj(c.get("/checks/$id/receipt").bodyAsText())["text"]!!.jsonPrimitive.content
        assertTrue("Rounding" !in receipt && "Arrondi" !in receipt, receipt)
        // a card for the rounded cash amount would leave 2¢ unpaid: never rounded
        val (other, ov) = c.checkAt("t2", priceEndingIn(7))
        c.card(other, ov.long("cashDueCents"))
        assertEquals(2L, c.check(other).long("outstandingCents"))
        assertEquals(HttpStatusCode.Conflict, c.post("/checks/$other/finalize").status)
    }

    @Test
    fun mixedCardThenCashRoundsOnlyTheCashThatSettles() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        c.openShift(float = 0)
        // $20 on card, the rest in cash: 3.47 → 3.45 in cash
        val price = generateSequence(1900L) { it + 1 }.first { quebecTotal(it) == 2347L }
        val (id, _) = c.checkAt("t3", price)
        val afterCard = c.card(id, 2000)["check"]!!.jsonObject
        assertEquals(347L, afterCard.long("outstandingCents"))
        assertEquals(345L, afterCard.long("cashDueCents"))
        assertEquals(-2L, afterCard.long("cashRoundingCents"))
        val tender = c.cash(id, 500)["tender"]!!.jsonObject
        assertEquals(347L, tender.long("amountAppliedCents"))
        assertEquals(-2L, tender.long("roundingAdjustmentCents"))
        assertEquals(155L, tender.long("changeCents"))
        c.finalize(id)
        // the sync event carries each payment's rounding: card 0, cash −2
        val tenders = lastPayload("check.closed")["tenders"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("CARD" to 0L, "CASH" to -2L),
            tenders.map { it["type"]!!.jsonPrimitive.content to it.long("roundingAdjustmentCents") })
        assertEquals(2347L, lastPayload("check.closed").long("grandTotalCents"))
        // the drawer took 3.45
        assertEquals(345L, obj(c.get("/shifts/current/report").bodyAsText()).long("expectedCashCents"))

        // partial cash first is face value; the card then pays the exact rest
        val (second, _) = c.checkAt("t3", price)
        val partial = c.cash(second, 1000)["tender"]!!.jsonObject
        assertEquals(0L, partial.long("roundingAdjustmentCents"))
        assertEquals(1347L, c.card(second, 1347)["check"]!!.jsonObject.long("paidCents") - 1000)
        c.finalize(second)
    }

    @Test
    fun splitGroupsRoundEachGroupsCashOnItsOwn() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        c.openShift(float = 0)
        val id = obj(c.postJson("/tables/t4/checks", """{"userId":"manager"}""").bodyAsText())["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$id/open-lines", """{"name":"A","unitPriceCents":${priceEndingIn(3)},"qty":1}""")
        val lines = c.postJson("/checks/$id/open-lines", """{"name":"B","unitPriceCents":${priceEndingIn(9, 2000)},"qty":1}""")
            .bodyAsText().let(::obj)["lines"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.int }
        val split = c.postJson("/checks/$id/split", """{"groups":2}""").bodyAsText().let(::obj)["split"]!!.jsonObject
        val groupIds = split["groups"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.int }
        c.postJson("/checks/$id/split/groups/${groupIds[0]}/lines", """{"lineId":${lines[0]},"qty":1}""")
        val view = c.postJson("/checks/$id/split/groups/${groupIds[1]}/lines", """{"lineId":${lines[1]},"qty":1}""")
            .bodyAsText().let(::obj)
        val groups = view["split"]!!.jsonObject["groups"]!!.jsonArray.map { it.jsonObject }
        for (g in groups) {
            val out = g.long("outstandingCents")
            assertEquals(RoundingPolicy.NICKEL.roundCashDue(Money(out)).cents, g.long("cashDueCents"))
            assertEquals(g.long("cashDueCents") - out, g.long("cashRoundingCents"))
        }
        // group 1 in cash (rounded on its own), group 2 on card (exact)
        val g1 = groups[0]
        val t1 = c.cash(id, g1.long("cashDueCents"), groupIds[0])["tender"]!!.jsonObject
        assertEquals(g1.long("cashRoundingCents"), t1.long("roundingAdjustmentCents"))
        assertEquals(0L, t1.long("changeCents"))
        val t2 = c.card(id, groups[1].long("outstandingCents"), groupIds[1])["tender"]!!.jsonObject
        assertEquals(0L, t2.long("roundingAdjustmentCents"))
        c.finalize(id)
        assertEquals(g1.long("cashDueCents"), obj(c.get("/shifts/current/report").bodyAsText()).long("expectedCashCents"))
    }

    @Test
    fun cashRefundsRoundAndCardRefundsStayExact() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        c.openShift(float = 10000)
        val (id, view) = c.checkAt("t5", priceEndingIn(0, 3000))
        val total = view.long("grandTotalCents")
        c.cash(id, total)
        c.finalize(id)
        // 10.03 back in cash → 10.05 handed over; gross/net/tax exact
        val cashRefund = obj(c.postJson("/checks/$id/refund",
            """{"amountCents":1003,"tenderType":"CASH","reason":"retour","managerPin":"1234"}""").bodyAsText())
        val r = cashRefund["refund"]!!.jsonObject
        assertEquals(1003L, r.long("grossCents"))
        assertEquals(2L, r.long("roundingAdjustmentCents"))
        assertEquals(1005L, r.long("paidOutCents"))
        assertEquals(r.long("grossCents"), r.long("netCents") + r.long("taxCents"))
        val slip = kv(cashRefund["slipText"]!!.jsonPrimitive.content)
        assertTrue(slip.any { it.matches(Regex("(Rounding|Arrondi) \\| \\+0.02")) }, slip.joinToString("\n"))
        assertEquals(2L, lastPayload("refund.created").long("roundingAdjustmentCents"))
        // 10.01 back on the card: exact, no rounding
        val card = obj(c.postJson("/checks/$id/refund",
            """{"amountCents":1001,"tenderType":"CARD","reason":"retour","managerPin":"1234"}""").bodyAsText())["refund"]!!.jsonObject
        assertEquals(0L, card.long("roundingAdjustmentCents"))
        assertEquals(1001L, card.long("paidOutCents"))
        assertEquals(0L, lastPayload("refund.created").long("roundingAdjustmentCents"))
        // the refund cap is on the exact gross
        assertEquals(2004L, obj(c.get("/checks/$id/refunds").bodyAsText()).long("refundedCents"))
        // drawer: float + the sale − the 10.05 cash handed back (the card refund never touches it)
        val x = obj(c.get("/shifts/current/report").bodyAsText())
        assertEquals(1005L, x.long("cashRefundCents"))
        assertEquals(10000 + total - 1005, x.long("expectedCashCents"))
        assertEquals(-2L, x.long("cashRoundingCents")) // the store gave 2¢ in rounding
    }

    @Test
    fun theBillAndTheGuestsPhoneShowWhatCashComesTo() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        c.openShift()
        val (id, view) = c.checkAt("t6", priceEndingIn(8))
        val total = view.long("grandTotalCents")
        val bill = kv(obj(c.post("/checks/$id/bill").bodyAsText())["text"]!!.jsonPrimitive.content)
        assertTrue(bill.any { it.matches(Regex("(Rounding|Arrondi) \\| \\+0.02")) }, bill.joinToString("\n"))
        assertTrue(bill.any { it.endsWith("| " + Money(total + 2).format()) }, bill.joinToString("\n"))
        val guest = obj(client.get(customerPath("t6") + "/bill").bodyAsText())
        assertEquals(total, guest.long("grandTotalCents"))
        assertEquals(total + 2, guest.long("cashDueCents"))
        assertEquals(2L, guest.long("cashRoundingCents"))
    }

    @Test
    fun turnedOffCashIsChargedToTheCent() = testApplication {
        application { module(dbPath = tempDb(), cashRounding = CashRounding.Resolved(CashRounding.OFF, "test")) }
        assertTrue("\"cashRounding\":\"off\"" in client.get("/health").bodyAsText())
        val c = loginClient()
        c.openShift(float = 0)
        val (id, view) = c.checkAt("t1", priceEndingIn(7))
        val total = view.long("grandTotalCents")
        assertEquals(total, view.long("cashDueCents"))
        assertEquals(0L, view.long("cashRoundingCents"))
        val tender = c.cash(id, 10000)["tender"]!!.jsonObject
        assertEquals(0L, tender.long("roundingAdjustmentCents"))
        assertEquals(10000 - total, tender.long("changeCents"))
        c.finalize(id)
        val bill = obj(c.get("/checks/$id/receipt").bodyAsText())["text"]!!.jsonPrimitive.content
        assertTrue("Rounding" !in bill && "Arrondi" !in bill, bill)
        val refund = obj(c.postJson("/checks/$id/refund",
            """{"amountCents":1003,"tenderType":"CASH","reason":"x","managerPin":"1234"}""").bodyAsText())["refund"]!!.jsonObject
        assertEquals(0L, refund.long("roundingAdjustmentCents"))
        assertEquals(total - 1003, obj(c.get("/shifts/current/report").bodyAsText()).long("expectedCashCents"))
    }

    // ---- the US store ----

    private fun ApplicationTestBuilder.shop(rounding: CashRounding = CashRounding.NICKEL) = application {
        module(
            dbPath = tempDb(), receiptsDir = Files.createTempDirectory("rc").toString(),
            venueId = SagePoppy.VENUE_ID, sagePoppy = true, physicalPrinterEnabled = false,
            cashRounding = CashRounding.Resolved(rounding, "test"),
        )
    }

    private suspend fun HttpClient.soda(): Pair<Int, JsonObject> {
        val sale = obj(postJson("/retail/sales").bodyAsText())["id"]!!.jsonPrimitive.int
        val soda = SagePoppySeed.products.single { it.id == "club-soda" }.barcode
        val chips = SagePoppySeed.products.single { it.id == "chips-sea-salt" }.barcode
        postJson("/retail/sales/$sale/scan", """{"barcode":"$soda"}""")
        postJson("/retail/sales/$sale/scan", """{"barcode":"$soda"}""")
        return sale to obj(postJson("/retail/sales/$sale/scan", """{"barcode":"$chips"}""").bodyAsText())
    }

    @Test
    fun theUsStoreRoundsCashDollarsToTheNickelToo() = testApplication {
        shop()
        assertTrue("\"cashRounding\":\"nickel\"" in client.get("/health").bodyAsText())
        val c = loginClient()
        c.openShift(float = 20000)
        // 2 × club soda + chips, CRV and 9.5% sales tax: 9.71 exact, 9.70 in cash
        val (sale, view) = c.soda()
        assertEquals(971L, view.long("grandTotalCents"))
        assertEquals(970L, view.long("cashDueCents"))
        assertEquals(-1L, view.long("cashRoundingCents"))
        val t = c.cash(sale, 1000)["tender"]!!.jsonObject
        assertEquals(30L, t.long("changeCents"))
        c.finalize(sale)
        val closed = lastPayload("check.closed")
        assertEquals("USD", closed["currency"]!!.jsonPrimitive.content)
        assertEquals(-1L, closed["tenders"]!!.jsonArray.single().jsonObject.long("roundingAdjustmentCents"))
        // the next sale on a card: 9.71 exact
        val (card, _) = c.soda()
        assertEquals(0L, c.card(card, 971)["tender"]!!.jsonObject.long("roundingAdjustmentCents"))
        c.finalize(card)
        // a cash refund of the soda sale rounds too (9.71 → 9.70 handed back)
        val refund = obj(c.postJson("/checks/$sale/refund",
            """{"amountCents":971,"tenderType":"CASH","reason":"return","managerPin":"1234"}""").bodyAsText())["refund"]!!.jsonObject
        assertEquals(-1L, refund.long("roundingAdjustmentCents"))
        assertEquals(970L, refund.long("paidOutCents"))
        val z = obj(c.postJson("/shifts/current/close", """{"closingCountCents":20000,"managerPin":"1234"}""").bodyAsText())
        assertEquals(20000L, z.long("expectedCashCents")) // +9.70 in, −9.70 out
        assertEquals(0L, z.long("cashRoundingCents"))
        assertEquals("USD", lastPayload("shift.closed")["currency"]!!.jsonPrimitive.content)
    }

    @Test
    fun theUsStoreCanTurnItOff() = testApplication {
        shop(CashRounding.OFF)
        val c = loginClient()
        c.openShift(float = 0)
        val (sale, view) = c.soda()
        assertEquals(971L, view.long("cashDueCents"))
        assertEquals(29L, c.cash(sale, 1000)["tender"]!!.jsonObject.long("changeCents"))
    }
}
