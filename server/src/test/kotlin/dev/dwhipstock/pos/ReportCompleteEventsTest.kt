package dev.dwhipstock.pos

import dev.dwhipstock.pos.db.SyncOutbox
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CONTRACT.md §2: the money-bearing outbox events carry everything cloud
 * reports need — asserted key for key against a real sale driven over the API.
 */
class ReportCompleteEventsTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    private suspend fun io.ktor.client.HttpClient.postJson(path: String, body: String) =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun io.ktor.client.HttpClient.patchJson(path: String, body: String) =
        patch(path) { contentType(ContentType.Application.Json); setBody(body) }

    private fun lastPayload(eventType: String): JsonObject = transaction {
        val raw = SyncOutbox.selectAll().where { SyncOutbox.eventType eq eventType }
            .orderBy(SyncOutbox.id, SortOrder.DESC).first()[SyncOutbox.payload]
        Json.parseToJsonElement(raw).jsonObject
    }

    /** Inclusive 13% sales tax, half-up at the cent — a Canadian policy fixture. */
    private fun inclusiveVat(base: Long): Long = (base * 13 * 2 + 113) / (113 * 2)

    @Test
    fun checkClosedVoidedAndShiftClosedAreReportComplete() = testApplication {
        application {
            module(
                dbPath = tempDb(),
                receiptsDir = Files.createTempDirectory("pos-receipts").toString(),
            )
        }
        val c = loginClient()

        // deterministic fee config regardless of migration defaults
        c.patchJson("/settings", """{"serviceChargePercent":0,"corkagePerBottleCents":10000}""")
            .let { assertEquals(HttpStatusCode.OK, it.status) }

        c.postJson("/shifts", """{"openingFloatCents":100000,"managerPin":"1234"}""")
            .let { assertEquals(HttpStatusCode.Created, it.status) }

        // t5 = U-1, the first stool in the combined dining room and bar
        val checkId = json.parseToJsonElement(c.postJson("/tables/t5/checks", "{}").bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.int
        // multi-variant item (variant label expected), single-variant item (no label),
        // an off-menu open line, and 2 corkage bottles
        c.postJson("/checks/$checkId/lines", """{"itemId":"lantern-lager","variantId":"lantern-lager:pitcher","qty":1}""")
            .let { assertEquals(HttpStatusCode.Created, it.status) }
        c.postJson("/checks/$checkId/lines",
            """{"itemId":"poutine","variantId":"poutine:regular","qty":1,"note":"Moins épicé"}""")
            .let { assertEquals(HttpStatusCode.Created, it.status) }
        c.postJson("/checks/$checkId/open-lines", """{"name":"Dépôt","unitPriceCents":12345,"qty":1}""")
            .let { assertEquals(HttpStatusCode.Created, it.status) }
        c.postJson("/checks/$checkId/corkage", """{"bottles":2}""")
            .let { assertEquals(HttpStatusCode.OK, it.status) }

        // Draft pitcher + poutine + open item + two corkage fees = $360.45.
        c.postJson("/checks/$checkId/tenders", """{"type":"CASH","amountTenderedCents":200000}""")
            .let { assertEquals(HttpStatusCode.Created, it.status) }
        c.post("/checks/$checkId/finalize").let { assertEquals(HttpStatusCode.OK, it.status) }

        val closed = lastPayload("check.closed")
        assertEquals(checkId, closed["checkId"]!!.jsonPrimitive.int)
        assertEquals("t5", closed["tableId"]!!.jsonPrimitive.content)
        assertEquals("U-1", closed["tableLabel"]!!.jsonPrimitive.content)
        assertEquals("upper", closed["zoneId"]!!.jsonPrimitive.content)
        assertEquals("Salle à manger et bar", closed["zoneNameFr"]!!.jsonPrimitive.content)
        assertEquals("Dining Room & Bar", closed["zoneNameEn"]!!.jsonPrimitive.content)
        assertEquals(1, closed["shiftId"]!!.jsonPrimitive.int)
        assertTrue(closed["openedAt"]!!.jsonPrimitive.content.isNotEmpty())
        assertTrue(closed["closedAt"]!!.jsonPrimitive.content.isNotEmpty())
        assertEquals("manager", closed["openedBy"]!!.jsonPrimitive.content)
        assertEquals(36045L, closed["grandTotalCents"]!!.jsonPrimitive.long)
        assertEquals(0L, closed["taxIncludedCents"]!!.jsonPrimitive.long)
        assertEquals(2, closed["corkageBottles"]!!.jsonPrimitive.int)

        val fees = closed["fees"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, fees.size)
        assertEquals("corkage", fees[0]["code"]!!.jsonPrimitive.content)
        assertEquals("Frais de bouchon de bouteille", fees[0]["labelFr"]!!.jsonPrimitive.content)
        assertEquals("Corkage", fees[0]["labelEn"]!!.jsonPrimitive.content)
        assertEquals(20000L, fees[0]["amountCents"]!!.jsonPrimitive.long)

        val lines = closed["lines"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, lines.size)
        val lanternLager = lines.first { it["itemId"]?.jsonPrimitive?.content == "lantern-lager" }
        assertTrue(lanternLager["lineId"]!!.jsonPrimitive.int > 0)
        assertEquals("lantern-lager:pitcher", lanternLager["variantId"]!!.jsonPrimitive.content)
        assertEquals("beer-cider", lanternLager["categoryId"]!!.jsonPrimitive.content)
        assertEquals("Lager de la Lanterne", lanternLager["nameFr"]!!.jsonPrimitive.content)
        assertEquals("Lantern House Lager", lanternLager["nameEn"]!!.jsonPrimitive.content)
        assertEquals("Pichet 60 oz", lanternLager["variantLabelFr"]!!.jsonPrimitive.content)
        assertEquals("60 oz pitcher", lanternLager["variantLabelEn"]!!.jsonPrimitive.content)
        assertEquals(1, lanternLager["qty"]!!.jsonPrimitive.int)
        assertEquals(2250L, lanternLager["unitPriceCents"]!!.jsonPrimitive.long)
        assertEquals(2250L, lanternLager["lineTotalCents"]!!.jsonPrimitive.long)
        val poutine = lines.first { it["itemId"]?.jsonPrimitive?.content == "poutine" }
        assertEquals("starters", poutine["categoryId"]!!.jsonPrimitive.content)
        // single live variant → no disambiguating label (mirrors receipts)
        assertFalse("variantLabelFr" in poutine)
        assertEquals("Moins épicé", poutine["note"]!!.jsonPrimitive.content)
        val openLine = lines.first { it["itemId"] is JsonNull }
        assertTrue(openLine["variantId"] is JsonNull)
        assertTrue(openLine["categoryId"] is JsonNull)
        assertEquals("Dépôt", openLine["displayName"]!!.jsonPrimitive.content)
        assertEquals(12345L, openLine["unitPriceCents"]!!.jsonPrimitive.long)

        val tenders = closed["tenders"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, tenders.size)
        assertEquals("CASH", tenders[0]["type"]!!.jsonPrimitive.content)
        assertEquals(200000L, tenders[0]["amountTenderedCents"]!!.jsonPrimitive.long)
        assertEquals(36045L, tenders[0]["amountAppliedCents"]!!.jsonPrimitive.long)
        assertEquals(0L, tenders[0]["roundingAdjustmentCents"]!!.jsonPrimitive.long)
        assertEquals(163955L, tenders[0]["changeCents"]!!.jsonPrimitive.long)
        assertTrue(tenders[0]["groupId"] is JsonNull)

        // --- void: same table/zone context + store-computed totals at void time ---
        val other = json.parseToJsonElement(c.postJson("/tables/t6/checks", "{}").bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$other/lines", """{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":2}""")
            .let { assertEquals(HttpStatusCode.Created, it.status) }
        c.postJson("/checks/$other/void", """{"reason":"J'ai commandé la mauvaise table","managerPin":"1234"}""")
            .let { assertEquals(HttpStatusCode.OK, it.status) }

        val voided = lastPayload("check.voided")
        assertEquals(other, voided["checkId"]!!.jsonPrimitive.int)
        assertEquals("J'ai commandé la mauvaise table", voided["reason"]!!.jsonPrimitive.content)
        assertEquals("manager", voided["authorizedBy"]!!.jsonPrimitive.content)
        assertEquals("t6", voided["tableId"]!!.jsonPrimitive.content)
        assertEquals("U-3", voided["tableLabel"]!!.jsonPrimitive.content)
        assertEquals("upper", voided["zoneId"]!!.jsonPrimitive.content)
        assertEquals("Salle à manger et bar", voided["zoneNameFr"]!!.jsonPrimitive.content)
        assertEquals("Dining Room & Bar", voided["zoneNameEn"]!!.jsonPrimitive.content)
        assertEquals(1, voided["shiftId"]!!.jsonPrimitive.int)
        assertTrue(voided["openedAt"]!!.jsonPrimitive.content.isNotEmpty())
        assertTrue(voided["voidedAt"]!!.jsonPrimitive.content.isNotEmpty())
        assertEquals(1750L, voided["amountCents"]!!.jsonPrimitive.long)
        assertEquals(0L, voided["taxIncludedCents"]!!.jsonPrimitive.long)

        // --- shift.closed: the Z-report echo ---
        // expected cash = opening float + settled cash sale.
        val z = json.parseToJsonElement(
            c.postJson("/shifts/current/close", """{"closingCountCents":136045,"managerPin":"1234"}""")
                .bodyAsText()).jsonObject
        assertEquals(0L, z["overShortCents"]!!.jsonPrimitive.long)

        val shiftClosed = lastPayload("shift.closed")
        assertEquals(1, shiftClosed["shiftId"]!!.jsonPrimitive.int)
        assertEquals("manager", shiftClosed["closedBy"]!!.jsonPrimitive.content)
        assertEquals(36045L, shiftClosed["revenueCents"]!!.jsonPrimitive.long)
        assertEquals(136045L, shiftClosed["expectedCashCents"]!!.jsonPrimitive.long)
        assertEquals(136045L, shiftClosed["closingCountCents"]!!.jsonPrimitive.long)
        assertEquals(0L, shiftClosed["overShortCents"]!!.jsonPrimitive.long)
        assertEquals(z["openedAt"]!!.jsonPrimitive.content, shiftClosed["openedAt"]!!.jsonPrimitive.content)
        assertTrue(shiftClosed["closedAt"]!!.jsonPrimitive.content.isNotEmpty())
        assertEquals("manager", shiftClosed["openedBy"]!!.jsonPrimitive.content)
        assertEquals(100000L, shiftClosed["openingFloatCents"]!!.jsonPrimitive.long)
        assertEquals(1, shiftClosed["transactionCount"]!!.jsonPrimitive.int)
        assertEquals(36045L, shiftClosed["avgCheckCents"]!!.jsonPrimitive.long)
        assertEquals(20000L, shiftClosed["corkageCents"]!!.jsonPrimitive.long)
        // tenderBreakdown matches the Z report exactly
        val zBreakdown = z["tenderBreakdown"]!!.jsonArray.map { it.jsonObject }
        val payloadBreakdown = shiftClosed["tenderBreakdown"]!!.jsonArray.map { it.jsonObject }
        assertEquals(zBreakdown.size, payloadBreakdown.size)
        assertEquals(1, payloadBreakdown.size)
        assertEquals("CASH", payloadBreakdown[0]["type"]!!.jsonPrimitive.content)
        assertEquals(zBreakdown[0]["amountCents"]!!.jsonPrimitive.long,
            payloadBreakdown[0]["amountCents"]!!.jsonPrimitive.long)
        assertEquals(36045L, payloadBreakdown[0]["amountCents"]!!.jsonPrimitive.long)
        assertEquals(1, payloadBreakdown[0]["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun shiftOpenedCarriesOpenedAt() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        c.postJson("/shifts", """{"openingFloatCents":50000,"managerPin":"1234"}""")
            .let { assertEquals(HttpStatusCode.Created, it.status) }
        val opened = lastPayload("shift.opened")
        assertEquals("manager", opened["openedBy"]!!.jsonPrimitive.content)
        assertEquals(50000L, opened["openingFloatCents"]!!.jsonPrimitive.long)
        assertTrue(opened["openedAt"]!!.jsonPrimitive.content.startsWith("20"))
    }

    /** Catalog mutations carry full §2 snapshots (shared helper, all call sites). */
    @Test
    fun catalogEventsCarryFullSnapshots() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()

        c.postJson("/items",
            """{"nameFr":"Soupe saisonnière","nameEn":"Seasonal Soup","categoryId":"starters","abbrev":"MM",
                "variants":[{"labelFr":"ordinaire","labelEn":"Regular","priceCents":6000},
                            {"labelFr":"spécial","labelEn":"Special","priceCents":8000}]}""")
            .let { assertEquals(HttpStatusCode.Created, it.status) }
        val created = lastPayload("item.created")["item"]!!.jsonObject
        assertEquals("seasonal-soup", created["id"]!!.jsonPrimitive.content)
        assertEquals("starters", created["categoryId"]!!.jsonPrimitive.content)
        assertEquals(false, created["deleted"]!!.jsonPrimitive.boolean)
        assertEquals(true, created["active"]!!.jsonPrimitive.boolean)
        assertEquals(2, created["variants"]!!.jsonArray.size)
        assertNull(created["photoVersion"]) // hint only where a PhotoStore is in scope

        // variant delete: snapshot keeps the row, flagged deleted
        c.delete("/items/seasonal-soup/variants/seasonal-soup:special")
            .let { assertEquals(HttpStatusCode.OK, it.status) }
        val afterVariantDelete = lastPayload("item.variant_deleted")["item"]!!.jsonObject
        val variants = afterVariantDelete["variants"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, variants.size)
        assertTrue(variants.first { it["id"]!!.jsonPrimitive.content == "seasonal-soup:special" }
            ["deleted"]!!.jsonPrimitive.boolean)

        // item delete: snapshot flagged deleted
        c.delete("/items/seasonal-soup").let { assertEquals(HttpStatusCode.OK, it.status) }
        assertTrue(lastPayload("item.deleted")["item"]!!
            .jsonObject["deleted"]!!.jsonPrimitive.boolean)

        // 86'ing carries the post-mutation snapshot too
        c.postJson("/items/lantern-lager/availability", """{"active":false,"managerPin":"1234"}""")
            .let { assertEquals(HttpStatusCode.OK, it.status) }
        val avail = lastPayload("item.availability_changed")["item"]!!.jsonObject
        assertEquals(false, avail["active"]!!.jsonPrimitive.boolean)
        assertEquals(2, avail["variants"]!!.jsonArray.size)

        // category events: snapshot + full-list on reorder
        c.postJson("/categories", """{"nameFr":"dessert","nameEn":"Dessert"}""")
            .let { assertEquals(HttpStatusCode.Created, it.status) }
        val cat = lastPayload("category.created")["category"]!!.jsonObject
        assertEquals("dessert", cat["id"]!!.jsonPrimitive.content)
        assertEquals(false, cat["deleted"]!!.jsonPrimitive.boolean)

        val ids = json.parseToJsonElement(c.get("/categories").bodyAsText()).jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
        c.patchJson("/categories/order",
            """{"orderedIds":[${ids.joinToString(",") { "\"$it\"" }}]}""")
            .let { assertEquals(HttpStatusCode.OK, it.status) }
        val reordered = lastPayload("categories.reordered")["categories"]!!.jsonArray
        assertEquals(ids.size, reordered.size)

        c.delete("/categories/dessert").let { assertEquals(HttpStatusCode.OK, it.status) }
        val deletedCat = lastPayload("category.deleted")["category"]!!.jsonObject
        assertEquals("dessert", deletedCat["id"]!!.jsonPrimitive.content)
        assertEquals(true, deletedCat["deleted"]!!.jsonPrimitive.boolean)
    }
}
