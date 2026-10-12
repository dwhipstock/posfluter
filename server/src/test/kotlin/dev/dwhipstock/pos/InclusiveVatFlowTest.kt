package dev.dwhipstock.pos

import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.sdk.CashRounding
import dev.dwhipstock.pos.sdk.CustomerConfig
import dev.dwhipstock.pos.sdk.Money
import dev.dwhipstock.pos.sdk.StoreProfile
import dev.dwhipstock.pos.sdk.TaxComponent
import dev.dwhipstock.pos.sdk.TaxPolicy
import dev.dwhipstock.pos.sdk.i18n.LocaleCode
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
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A store in baht whose menu prices include a 7% VAT, end to end over the
 * API: the check, the 10% service charge, cash rounded down to the whole
 * baht while card pays the exact amount, the receipt line, the sync events
 * the cloud reports from (the VAT as an included tax line), cumulative
 * partial refunds, a by-item split, a void and the X report.
 */
class InclusiveVatFlowTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-vat").resolve("pos.db").toString()
    private fun obj(text: String): JsonObject = json.parseToJsonElement(text).jsonObject
    private fun JsonObject.long(key: String) = this[key]!!.jsonPrimitive.long

    private val vat = TaxComponent("VAT", "TVA", "VAT", BigDecimal("7"), registrationNumber = "")
    private val vatPolicy = TaxPolicy.InclusiveTax(vat, showOnReceipt = false)
    private fun vatIn(gross: Long) = vatPolicy.includedIn(Money(gross)).cents

    /** The store's money rules on the engine: THB, VAT inside the prices, cash down to the whole baht. */
    private fun vatStore(showOnReceipt: Boolean): (CustomerConfig) -> CustomerConfig = { base ->
        object : CustomerConfig by base {
            override val profile = StoreProfile(
                country = "TH", currency = "THB", locales = listOf(LocaleCode.EN), timeZone = "Etc/GMT-7",
            )
            override val taxPolicy = TaxPolicy.InclusiveTax(vat, showOnReceipt)
            override val roundingPolicy = CashRounding.resolve(null, "test").forCurrency("THB").policy
        }
    }

    private fun ApplicationTestBuilder.store(showOnReceipt: Boolean = true) = application {
        module(
            dbPath = tempDb(), receiptsDir = Files.createTempDirectory("vat-rc").toString(),
            physicalPrinterEnabled = false, configOverride = vatStore(showOnReceipt),
        )
    }

    private suspend fun HttpClient.postJson(path: String, body: String = "{}") =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.setup(serviceChargePercent: Int = 10) {
        patch("/settings") {
            contentType(ContentType.Application.Json)
            setBody("""{"serviceChargePercent":$serviceChargePercent,"corkagePerBottleCents":0}""")
        }.also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        postJson("/shifts", """{"openingFloatCents":0,"managerPin":"1234"}""")
            .also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }
    }

    private suspend fun HttpClient.checkWith(table: String, vararg prices: Long): Pair<Int, JsonObject> {
        val id = obj(postJson("/tables/$table/checks", """{"userId":"manager"}""").bodyAsText())["id"]!!.jsonPrimitive.int
        var view = JsonObject(emptyMap())
        for (p in prices) view = postJson("/checks/$id/open-lines", """{"name":"Dish","unitPriceCents":$p,"qty":1}""")
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

    private fun JsonObject.vatLine(key: String = "taxes"): JsonObject =
        this[key]!!.jsonArray.map { it.jsonObject }.single { it["code"]!!.jsonPrimitive.content == "VAT" }

    @Test
    fun aVatInclusiveSaleWithServiceChargeSettlesInWholeBahtAndSyncsItsVat() = testApplication {
        store(showOnReceipt = true)
        val health = client.get("/health").bodyAsText()
        assertTrue("\"currency\":\"THB\"" in health, health)
        assertTrue("\"cashRounding\":\"unit-down\"" in health, health)
        val c = loginClient()
        c.setup(serviceChargePercent = 10)

        // ฿1,000.50 of food + 10% service ฿100.05 = ฿1,100.55, VAT inside both
        val (id, view) = c.checkWith("t1", 100050)
        val total = 110055L
        val tax = vatIn(total)
        assertEquals(7200L, tax) // 1,100.55 × 7 / 107 = 72.00 (half-up at the satang)
        assertEquals(total, view.long("grandTotalCents"))
        assertEquals(10005L, view["fees"]!!.jsonArray.single().jsonObject.long("amountCents"))
        assertEquals(tax, view.long("taxIncludedCents"))
        assertTrue(view["taxes"]!!.jsonArray.isEmpty(), "nothing is added on top")
        assertEquals(total, view.long("subtotalCents"))
        val inc = view.vatLine("includedTaxes")
        assertEquals(tax, inc.long("amountCents"))
        assertTrue(inc["included"]!!.jsonPrimitive.boolean)
        // cash drops the satang; the total stays exact
        assertEquals(110000L, view.long("cashDueCents"))
        assertEquals(-55L, view.long("cashRoundingCents"))

        val tender = c.cash(id, 110000)["tender"]!!.jsonObject
        assertEquals(total, tender.long("amountAppliedCents"))
        assertEquals(-55L, tender.long("roundingAdjustmentCents"))
        assertEquals(0L, tender.long("changeCents"))
        c.finalize(id)

        // the receipt: the total, then the VAT inside it; no subtotal or tax on top
        val receipt = kv(obj(c.get("/checks/$id/receipt").bodyAsText())["text"]!!.jsonPrimitive.content)
        assertTrue("Total | 1,100.55" in receipt, receipt.joinToString("\n"))
        assertTrue("VAT 7% included | 72.00" in receipt, receipt.joinToString("\n"))
        assertTrue(receipt.none { it.startsWith("Subtotal") }, receipt.joinToString("\n"))

        // the cloud gets the VAT as a tax line flagged included, inside taxIncludedCents
        val closed = lastPayload("check.closed")
        assertEquals("THB", closed["currency"]!!.jsonPrimitive.content)
        assertEquals(total, closed.long("grandTotalCents"))
        assertEquals(tax, closed.long("taxIncludedCents"))
        assertEquals(total, closed.long("subtotalCents"))
        val line = closed.vatLine()
        assertEquals(tax, line.long("amountCents"))
        assertEquals("7", line["ratePercent"]!!.jsonPrimitive.content)
        assertTrue(line["included"]!!.jsonPrimitive.boolean)
        assertEquals(1, closed["taxes"]!!.jsonArray.size)
        assertEquals(-55L, closed["tenders"]!!.jsonArray.single().jsonObject.long("roundingAdjustmentCents"))
        assertEquals(tax, lastPayload("check.total_locked").vatLine().long("amountCents"))

        // the X report: the VAT collected, and the satang given up in cash
        val x = obj(c.get("/shifts/current/report").bodyAsText())
        assertEquals(110000L, x.long("expectedCashCents"))
        assertEquals(-55L, x.long("cashRoundingCents"))
        val xVat = x.vatLine()
        assertEquals(tax, xVat.long("amountCents"))
        assertTrue(xVat["included"]!!.jsonPrimitive.boolean)

        // two partial cash refunds: the VAT reverses cumulatively and adds up to the check's
        val r1 = obj(c.postJson("/checks/$id/refund",
            """{"amountCents":50000,"tenderType":"CASH","reason":"x","managerPin":"1234"}""").bodyAsText())
        val ref1 = r1["refund"]!!.jsonObject
        val tax1 = BigDecimal(tax * 50000).divide(BigDecimal(total), 0, java.math.RoundingMode.HALF_UP).toLong()
        assertEquals(tax1, ref1.long("taxCents"))
        assertEquals(50000L - tax1, ref1.long("netCents"))
        assertEquals(tax1, ref1.vatLine().long("amountCents"))
        assertEquals(0L, ref1.long("roundingAdjustmentCents")) // ฿500 in cash: whole already
        assertTrue("VAT 7% included | ${Money(tax1).formatCents()}" in kv(r1["slipText"]!!.jsonPrimitive.content),
            r1["slipText"]!!.jsonPrimitive.content)
        val p1 = lastPayload("refund.created")
        assertEquals(tax1, p1.long("taxIncludedCents"))
        assertTrue(p1.vatLine()["included"]!!.jsonPrimitive.boolean)

        val ref2 = obj(c.postJson("/checks/$id/refund",
            """{"amountCents":${total - 50000},"tenderType":"CASH","reason":"x","managerPin":"1234"}""").bodyAsText())["refund"]!!.jsonObject
        assertEquals(tax - tax1, ref2.long("taxCents"))
        assertEquals(tax, tax1 + ref2.long("taxCents")) // the whole VAT handed back, to the satang
        // ฿600.55 back in cash: ฿600, rounded down like a sale
        assertEquals(-55L, ref2.long("roundingAdjustmentCents"))
        assertEquals(60000L, ref2.long("paidOutCents"))

        // the X report nets the refunds out of the VAT
        val x2 = obj(c.get("/shifts/current/report").bodyAsText())
        assertEquals(0L, x2.vatLine().long("amountCents"))
        assertEquals(0L, x2.long("expectedCashCents"))
    }

    @Test
    fun cardPaysTheExactAmountAndAHiddenVatStaysOffTheReceiptButNotTheReports() = testApplication {
        store(showOnReceipt = false)
        val c = loginClient()
        c.setup(serviceChargePercent = 0)
        val (id, view) = c.checkWith("t2", 12345)
        assertEquals(12345L, view.long("grandTotalCents"))
        c.card(id, 12345).let { assertEquals(0L, it["tender"]!!.jsonObject.long("roundingAdjustmentCents")) }
        c.finalize(id)
        val receipt = obj(c.get("/checks/$id/receipt").bodyAsText())["text"]!!.jsonPrimitive.content
        assertTrue("VAT" !in receipt, receipt)
        assertTrue("Rounding" !in receipt, receipt)
        assertEquals(vatIn(12345), lastPayload("check.closed").vatLine().long("amountCents"))
    }

    @Test
    fun aByItemSplitSharesTheChecksVatAndAVoidReportsIt() = testApplication {
        store()
        val c = loginClient()
        c.setup(serviceChargePercent = 10)
        val id = obj(c.postJson("/tables/t3/checks", """{"userId":"manager"}""").bodyAsText())["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$id/open-lines", """{"name":"A","unitPriceCents":33333,"qty":1}""")
        val lines = c.postJson("/checks/$id/open-lines", """{"name":"B","unitPriceCents":45678,"qty":1}""")
            .bodyAsText().let(::obj)["lines"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.int }
        val whole = obj(c.get("/checks/$id").bodyAsText())
        val checkVat = whole.long("taxIncludedCents")
        assertEquals(vatIn(whole.long("grandTotalCents")), checkVat)

        val split = c.postJson("/checks/$id/split", """{"groups":2}""").bodyAsText().let(::obj)["split"]!!.jsonObject
        val groupIds = split["groups"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.int }
        c.postJson("/checks/$id/split/groups/${groupIds[0]}/lines", """{"lineId":${lines[0]},"qty":1}""")
        val view = c.postJson("/checks/$id/split/groups/${groupIds[1]}/lines", """{"lineId":${lines[1]},"qty":1}""")
            .bodyAsText().let(::obj)
        val groups = view["split"]!!.jsonObject["groups"]!!.jsonArray.map { it.jsonObject }
        // each group carries its share of the check's VAT, assessed once on the
        // groups' bases (their service charges floor on their own: a split
        // check's total is the groups' sum), and the shares add up to it
        val splitTotal = groups.sumOf { it.long("grandTotalCents") }
        assertTrue(kotlin.math.abs(splitTotal - whole.long("grandTotalCents")) <= 1)
        val splitVat = vatIn(splitTotal)
        assertEquals(splitVat, groups.sumOf { it.vatLine("includedTaxes").long("amountCents") })
        assertTrue(groups.all { it["taxes"]!!.jsonArray.isEmpty() })
        // group 1 in cash (down to the baht on its own), group 2 on card (exact)
        val g1 = groups[0]
        assertEquals(g1.long("outstandingCents") / 100 * 100, g1.long("cashDueCents"))
        c.cash(id, g1.long("cashDueCents"), groupIds[0])
        c.card(id, groups[1].long("outstandingCents"), groupIds[1])
        c.finalize(id)
        val closed = lastPayload("check.closed")
        assertEquals(splitTotal, closed.long("grandTotalCents"))
        assertEquals(splitVat, closed.long("taxIncludedCents"))
        assertEquals(splitVat, closed.vatLine().long("amountCents"))

        // a voided check reports the VAT it would have carried
        val (vid, _) = c.checkWith("t4", 10700)
        c.postJson("/checks/$vid/void", """{"reason":"x"}""").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        val voided = lastPayload("check.voided")
        val base = 10700L + 1070L // ฿107 + 10% service
        assertEquals(vatIn(base), voided.long("taxIncludedCents"))
        assertEquals(vatIn(base), voided.vatLine().long("amountCents"))
        assertTrue(voided.vatLine()["included"]!!.jsonPrimitive.boolean)
    }
}
