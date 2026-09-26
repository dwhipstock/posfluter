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
 * Refunds (return money on a finalized check, included tax reversed) and cash
 * movements (non-sale till in/out) — both post to the shift and feed the Z-close
 * expected-cash reconciliation.
 */
class RefundAndCashMovementTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-refund-test").resolve("pos.db").toString()

    private suspend fun HttpClient.postJson(path: String, body: String) =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    /** Ring one lantern-lager:pitcher ($20.25 + GST 1.01 + QST 2.02 = $23.28), pay $23.30 cash (due rounds to it), finalize. Returns the check id. */
    private suspend fun HttpClient.finalizeTowerSale(table: String): Int {
        val id = json.parseToJsonElement(postJson("/tables/$table/checks", """{"userId":"manager"}""")
            .bodyAsText()).jsonObject["id"]!!.jsonPrimitive.int
        postJson("/checks/$id/lines", """{"itemId":"lantern-lager","variantId":"lantern-lager:pitcher","qty":1}""")
        postJson("/checks/$id/tenders", """{"type":"CASH","amountTenderedCents":2330}""")
        post("/checks/$id/finalize").let { check(it.status == HttpStatusCode.OK) { "finalize failed: ${it.status}" } }
        return id
    }

    @Test
    fun refundReversesInclusiveTaxAndFeedsShiftReconciliation() = testApplication {
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
        assertEquals(2328L, summary["grandTotalCents"]!!.jsonPrimitive.long)
        assertEquals(2328L, summary["refundableCents"]!!.jsonPrimitive.long)

        // full refund, cash — every tax reversed exactly (matches the check's GST + QST)
        val checkTaxes = json.parseToJsonElement(c.get("/checks/$checkId").bodyAsText())
            .jsonObject["taxes"]!!.jsonArray.map { it.jsonObject["amountCents"]!!.jsonPrimitive.long }
        assertEquals(listOf(101L, 202L), checkTaxes)
        val checkTax = checkTaxes.sum()
        val refundRes = c.postJson("/checks/$checkId/refund",
            """{"amountCents":2328,"tenderType":"CASH","reason":"Le client retourne le produit","managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Created, refundRes.status)
        val refundBody = json.parseToJsonElement(refundRes.bodyAsText()).jsonObject
        val refund = refundBody["refund"]!!.jsonObject
        assertEquals(2328L, refund["grossCents"]!!.jsonPrimitive.long)
        assertEquals(checkTax, refund["taxCents"]!!.jsonPrimitive.long) // full refund → exact tax reversal
        assertEquals(2328L - checkTax, refund["netCents"]!!.jsonPrimitive.long)
        assertEquals(checkTaxes, refund["taxes"]!!.jsonArray.map { it.jsonObject["amountCents"]!!.jsonPrimitive.long })
        assertEquals("CASH", refund["tenderType"]!!.jsonPrimitive.content)
        // the manager (fr preference) opened the check → French slip, labels pinned exactly
        val slip = refundBody["slipText"]!!.jsonPrimitive.content
        val slipKv = slip.lines().map { it.trim().replace(Regex(" {2,}"), " | ") }
        assertTrue("Sous-total | 20.25" in slipKv, slip)
        assertTrue("TPS/GST 5 % | 1.01" in slipKv, slip)
        assertTrue("TVQ/QST 9,975 % | 2.02" in slipKv, slip)
        assertTrue("*** Bon de remboursement / REFUND ***" in slip, "fr refund header expected:\n$slip")
        assertTrue("Facture de référence #$checkId" in slip)
        assertTrue("Remboursement #" in slip)
        assertTrue("Total retourné" in slip)
        assertTrue("Remboursé par" in slip && "Espèces" in slip)
        assertTrue("Motif : Le client retourne le produit" in slip) // colon + single space + reason

        // now fully refunded; a second refund is refused
        val info = json.parseToJsonElement(c.get("/checks/$checkId/refunds").bodyAsText()).jsonObject
        assertEquals(2328L, info["refundedCents"]!!.jsonPrimitive.long)
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
        assertEquals(2328L, x["cashRefundCents"]!!.jsonPrimitive.long)

        // Z-close: expected = 1000 float + 23.30 cash in − 0 change + 500 in − 200 out − 23.28 cash refund = 1300.02
        val z = json.parseToJsonElement(
            c.postJson("/shifts/current/close", """{"closingCountCents":130002,"managerPin":"1234"}""").bodyAsText()).jsonObject
        assertEquals(130002L, z["expectedCashCents"]!!.jsonPrimitive.long)
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
            """{"amountCents":2328,"tenderType":"CASH","reason":"changed mind","managerPin":"1234"}""")
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

        // two pitchers = $40.50 + GST 2.03 (2.025 half-up) + QST 4.04 = $46.57
        val id = json.parseToJsonElement(c.postJson("/tables/t6/checks", """{"userId":"manager"}""")
            .bodyAsText()).jsonObject["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$id/lines", """{"itemId":"lantern-lager","variantId":"lantern-lager:pitcher","qty":2}""")
        // card is electronic: initiate + confirm
        c.postJson("/checks/$id/tenders/initiate", """{"type":"CARD","amountCents":4657}""")
        c.postJson("/checks/$id/tenders/confirm", """{"type":"CARD","amountCents":4657}""")
        c.post("/checks/$id/finalize").let { assertEquals(HttpStatusCode.OK, it.status) }

        val lineId = json.parseToJsonElement(c.get("/checks/$id").bodyAsText())
            .jsonObject["lines"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.int

        // refund one pitcher by line → its price plus its tax: 20.25 × 46.57 / 40.50
        // = 23.285 → $23.29 back via Card (not cash, so no drawer hit)
        val res = c.postJson("/checks/$id/refund",
            """{"lines":[{"lineId":$lineId,"qty":1}],"tenderType":"CARD","reason":"Change d'avis","managerPin":"1234"}""")
        assertEquals(HttpStatusCode.Created, res.status)
        val first = json.parseToJsonElement(res.bodyAsText()).jsonObject["refund"]!!.jsonObject
        assertEquals(2329L, first["grossCents"]!!.jsonPrimitive.long)
        // tax reversed half of each tax, half-up: GST 1.015 → 1.02, QST 2.02
        assertEquals(listOf(102L, 202L), first["taxes"]!!.jsonArray.map { it.jsonObject["amountCents"]!!.jsonPrimitive.long })

        // $23.28 remains refundable; refunding the whole $46.57 again would exceed it
        val info = json.parseToJsonElement(c.get("/checks/$id/refunds").bodyAsText()).jsonObject
        assertEquals(2328L, info["refundableCents"]!!.jsonPrimitive.long)
        assertEquals(HttpStatusCode.Conflict,
            c.postJson("/checks/$id/refund", """{"amountCents":4657,"tenderType":"CASH","reason":"x","managerPin":"1234"}""").status)

        // the other pitcher by line: rounding already went to the first, so the
        // cap returns exactly what is left, and every tax is reversed in full
        val second = json.parseToJsonElement(c.postJson("/checks/$id/refund",
            """{"lines":[{"lineId":$lineId,"qty":1}],"tenderType":"CARD","reason":"Change d'avis","managerPin":"1234"}""")
            .bodyAsText()).jsonObject["refund"]!!.jsonObject
        assertEquals(2328L, second["grossCents"]!!.jsonPrimitive.long)
        assertEquals(listOf(101L, 202L), second["taxes"]!!.jsonArray.map { it.jsonObject["amountCents"]!!.jsonPrimitive.long })

        // a Card refund did NOT leave the drawer — cashRefund stays 0
        val x = json.parseToJsonElement(c.get("/shifts/current/report").bodyAsText()).jsonObject
        assertEquals(0L, x["cashRefundCents"]!!.jsonPrimitive.long)
        assertEquals(4657L, x["refundTotalCents"]!!.jsonPrimitive.long)

        // an OPEN check can't be refunded
        val open = json.parseToJsonElement(c.postJson("/tables/t5/checks", """{"userId":"manager"}""")
            .bodyAsText()).jsonObject["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$open/lines", """{"itemId":"lantern-lager","variantId":"lantern-lager:pitcher","qty":1}""")
        assertEquals(HttpStatusCode.Conflict,
            c.postJson("/checks/$open/refund", """{"amountCents":100,"tenderType":"CASH","reason":"x","managerPin":"1234"}""").status)
    }
}
