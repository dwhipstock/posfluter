package dev.dwhipstock.pos

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Refunds (return money on a finalized check, inclusive VAT reversed) and cash
 * movements (non-sale till in/out) — both post to the shift and feed the Z-close
 * expected-cash reconciliation.
 */
class RefundAndCashMovementTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-refund-test").resolve("pos.db").toString()

    private suspend fun HttpClient.postJson(path: String, body: String) =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    /** Ring one lantern-lager:pitcher ($22.50), pay cash, finalize. Returns the check id. */
    private suspend fun HttpClient.finalizeTowerSale(table: String): Int {
        val id = json.parseToJsonElement(postJson("/tables/$table/checks", """{"userId":"manager"}""")
            .bodyAsText()).jsonObject["id"]!!.jsonPrimitive.int
        postJson("/checks/$id/lines", """{"itemId":"lantern-lager","variantId":"lantern-lager:pitcher","qty":1}""")
        postJson("/checks/$id/tenders", """{"type":"CASH","amountTenderedCents":2250}""")
        post("/checks/$id/finalize").let { check(it.status == HttpStatusCode.OK) { "finalize failed: ${it.status}" } }
        return id
    }

    @Test
    fun refundReversesInclusiveVatAndFeedsShiftReconciliation() = testApplication {
        val receiptsDir = Files.createTempDirectory("pos-refund-receipts").toString()
        application { module(dbPath = tempDb(), receiptsDir = receiptsDir) }
        val c = loginClient()
        c.patch("/me/preferences") {
            contentType(ContentType.Application.Json)
            setBody("""{"languageCode":"fr"}""")
        }
        // a server lacks the refund / cash_movement grants → gated actions need approval (CONTRACT §7)
        val server = loginClient("9999")

        c.postJson("/shifts", """{"openingFloatCents":100000,"managerPin":"1234"}""")
            .let { assertEquals(HttpStatusCode.Created, it.status) }

        val checkId = c.finalizeTowerSale("t5-5")

        // the finished check shows in the refund picker, fully refundable
        val recent = json.parseToJsonElement(c.get("/checks/recent").bodyAsText()).jsonArray
            .map { it.jsonObject }
        val summary = recent.first { it["id"]!!.jsonPrimitive.int == checkId }
        assertEquals(2250L, summary["grandTotalCents"]!!.jsonPrimitive.long)
        assertEquals(2250L, summary["refundableCents"]!!.jsonPrimitive.long)

        // full refund, cash — included sales tax reversed exactly (matches the check's tax)
        val checkTax = json.parseToJsonElement(c.get("/checks/$checkId").bodyAsText())
            .jsonObject["taxIncludedCents"]!!.jsonPrimitive.long
        val refundRes = c.postJson("/checks/$checkId/refund",
            """{"amountCents":2250,"tenderType":"CASH","reason":"Le client retourne le produit","managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Created, refundRes.status)
        val refundBody = json.parseToJsonElement(refundRes.bodyAsText()).jsonObject
        val refund = refundBody["refund"]!!.jsonObject
        assertEquals(2250L, refund["grossCents"]!!.jsonPrimitive.long)
        assertEquals(checkTax, refund["taxCents"]!!.jsonPrimitive.long) // full refund → exact tax reversal
        assertEquals(2250L - checkTax, refund["netCents"]!!.jsonPrimitive.long)
        assertEquals("CASH", refund["tenderType"]!!.jsonPrimitive.content)
        // the manager (fr preference) opened the check → French slip, labels pinned exactly
        val slip = refundBody["slipText"]!!.jsonPrimitive.content
        assertTrue("*** Bon de remboursement / REFUND ***" in slip, "fr refund header expected:\n$slip")
        assertTrue("Facture de référence #$checkId" in slip)
        assertTrue("Remboursement #" in slip)
        assertTrue("Total retourné" in slip)
        assertTrue("Remboursé par" in slip && "Espèces" in slip)
        assertTrue("Motif : Le client retourne le produit" in slip) // colon + single space + reason

        // now fully refunded; a second refund is refused
        val info = json.parseToJsonElement(c.get("/checks/$checkId/refunds").bodyAsText()).jsonObject
        assertEquals(2250L, info["refundedCents"]!!.jsonPrimitive.long)
        assertEquals(0L, info["refundableCents"]!!.jsonPrimitive.long)
        assertEquals(1, info["refunds"]!!.jsonArray.size)
        assertEquals(HttpStatusCode.Conflict,
            c.postJson("/checks/$checkId/refund",
                """{"amountCents":100,"tenderType":"CASH","reason":"x","managerPin":"1234"}""").status)

        // a server (no refund grant) with no approving PIN → refused
        assertEquals(HttpStatusCode.Forbidden,
            server.postJson("/checks/$checkId/refund", """{"amountCents":100,"tenderType":"CASH","reason":"x"}""").status)

        // cash movements: $500 in, $200 out
        c.postJson("/cash-movements", """{"direction":"IN","amountCents":50000,"reason":"Ajouter un changement","managerPin":"1234"}""")
            .let { assertEquals(HttpStatusCode.Created, it.status) }
        val out = c.postJson("/cash-movements", """{"direction":"OUT","amountCents":20000,"reason":"acheter de la glace","managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Created, out.status)
        val outSlip = json.parseToJsonElement(out.bodyAsText()).jsonObject["slipText"]!!.jsonPrimitive.content
        assertTrue("*** Argent retiré / CASH OUT ***" in outSlip, "fr cash-out header expected:\n$outSlip")
        assertTrue("Heure" in outSlip)
        assertTrue("Motif : acheter de la glace" in outSlip)

        // cash movement by a server with no approving PIN is refused
        assertEquals(HttpStatusCode.Forbidden,
            server.postJson("/cash-movements", """{"direction":"IN","amountCents":10000,"reason":"x"}""").status)

        // X-report reflects the movements + the cash refund
        val x = json.parseToJsonElement(c.get("/shifts/current/report").bodyAsText()).jsonObject
        assertEquals(50000L, x["cashPaidInCents"]!!.jsonPrimitive.long)
        assertEquals(20000L, x["cashPaidOutCents"]!!.jsonPrimitive.long)
        assertEquals(2250L, x["cashRefundCents"]!!.jsonPrimitive.long)

        // Z-close: expected = 1000 float + 450 cash sale − 0 change + 500 in − 200 out − 450 cash refund = 1300
        val z = json.parseToJsonElement(
            c.postJson("/shifts/current/close", """{"closingCountCents":130000,"managerPin":"1234"}""").bodyAsText()).jsonObject
        assertEquals(130000L, z["expectedCashCents"]!!.jsonPrimitive.long)
        assertEquals(0L, z["overShortCents"]!!.jsonPrimitive.long)
    }

    /** Refund and till slips follow the acting/owning user's language, en pinned exactly. */
    @Test
    fun slipsFollowTheActingUsersLanguage() = testApplication {
        val receiptsDir = Files.createTempDirectory("pos-refund-receipts-en").toString()
        application { module(dbPath = tempDb(), receiptsDir = receiptsDir) }
        val c = loginClient()
        c.patch("/me/preferences") {
            contentType(ContentType.Application.Json)
            setBody("""{"languageCode":"en"}""")
        }.let { assertEquals(HttpStatusCode.OK, it.status) }
        c.postJson("/shifts", """{"openingFloatCents":0,"managerPin":"1234"}""")
            .let { assertEquals(HttpStatusCode.Created, it.status) }

        // till slip: rendered for the acting (en) manager
        val inRes = c.postJson("/cash-movements",
            """{"direction":"IN","amountCents":10000,"reason":"change float","managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Created, inRes.status)
        val inSlip = json.parseToJsonElement(inRes.bodyAsText()).jsonObject["slipText"]!!.jsonPrimitive.content
        assertTrue("*** CASH IN / Argent entrant ***" in inSlip, "en cash-in header expected:\n$inSlip")
        assertTrue("Cash in" in inSlip && "Time" in inSlip)
        assertTrue("Reason: change float" in inSlip)
        assertTrue("raison" !in inSlip, "fr label leaked into an en slip")

        // refund slip: the check owner (this en manager) sets the language
        val checkId = c.finalizeTowerSale("t6")
        val refundRes = c.postJson("/checks/$checkId/refund",
            """{"amountCents":2250,"tenderType":"CASH","reason":"changed mind","managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Created, refundRes.status)
        val slip = json.parseToJsonElement(refundRes.bodyAsText()).jsonObject["slipText"]!!.jsonPrimitive.content
        assertTrue("*** REFUND / Bon de remboursement ***" in slip, "en refund header (order swapped) expected:\n$slip")
        assertTrue("Ref bill #$checkId" in slip)
        assertTrue("Refund #" in slip)
        assertTrue("Refund total" in slip && "Refund via" in slip && "Cash" in slip)
        assertTrue("Reason: changed mind" in slip)
        assertTrue("raison" !in slip && "Total retourné" !in slip, "fr labels leaked into an en slip")
    }

    @Test
    fun refundByLineAndPartialGuards() = testApplication {
        val receiptsDir = Files.createTempDirectory("pos-refund-receipts2").toString()
        application { module(dbPath = tempDb(), receiptsDir = receiptsDir) }
        val c = loginClient()
        c.postJson("/shifts", """{"openingFloatCents":0,"managerPin":"1234"}""")

        // two towers = $45
        val id = json.parseToJsonElement(c.postJson("/tables/t6/checks", """{"userId":"manager"}""")
            .bodyAsText()).jsonObject["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$id/lines", """{"itemId":"lantern-lager","variantId":"lantern-lager:pitcher","qty":2}""")
        // card is electronic: initiate + confirm
        c.postJson("/checks/$id/tenders/initiate", """{"type":"CARD","amountCents":4500}""")
        c.postJson("/checks/$id/tenders/confirm", """{"type":"CARD","amountCents":4500}""")
        c.post("/checks/$id/finalize").let { assertEquals(HttpStatusCode.OK, it.status) }

        val lineId = json.parseToJsonElement(c.get("/checks/$id").bodyAsText())
            .jsonObject["lines"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.int

        // refund one tower by line → $22.50 back via Card (not cash, so no drawer hit)
        val res = c.postJson("/checks/$id/refund",
            """{"lines":[{"lineId":$lineId,"qty":1}],"tenderType":"CARD","reason":"Change d'avis","managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Created, res.status)
        assertEquals(2250L, json.parseToJsonElement(res.bodyAsText()).jsonObject["refund"]!!
            .jsonObject["grossCents"]!!.jsonPrimitive.long)

        // $22.50 remains refundable; refunding the whole $45 again would exceed it
        val info = json.parseToJsonElement(c.get("/checks/$id/refunds").bodyAsText()).jsonObject
        assertEquals(2250L, info["refundableCents"]!!.jsonPrimitive.long)
        assertEquals(HttpStatusCode.Conflict,
            c.postJson("/checks/$id/refund", """{"amountCents":4500,"tenderType":"CASH","reason":"x","managerPin":"1234"}""").status)

        // a Card refund did NOT leave the drawer — cashRefund stays 0
        val x = json.parseToJsonElement(c.get("/shifts/current/report").bodyAsText()).jsonObject
        assertEquals(0L, x["cashRefundCents"]!!.jsonPrimitive.long)
        assertEquals(2250L, x["refundTotalCents"]!!.jsonPrimitive.long)

        // an OPEN check can't be refunded
        val open = json.parseToJsonElement(c.postJson("/tables/t5/checks", """{"userId":"manager"}""")
            .bodyAsText()).jsonObject["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$open/lines", """{"itemId":"lantern-lager","variantId":"lantern-lager:pitcher","qty":1}""")
        assertEquals(HttpStatusCode.Conflict,
            c.postJson("/checks/$open/refund", """{"amountCents":100,"tenderType":"CASH","reason":"x","managerPin":"1234"}""").status)
    }
}
