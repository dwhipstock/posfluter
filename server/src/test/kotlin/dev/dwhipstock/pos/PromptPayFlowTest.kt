package dev.dwhipstock.pos

import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.sdk.CashRounding
import dev.dwhipstock.pos.sdk.CustomerConfig
import dev.dwhipstock.pos.sdk.PromptPayConfig
import dev.dwhipstock.pos.sdk.PromptPayMethod
import dev.dwhipstock.pos.sdk.StoreProfile
import dev.dwhipstock.pos.sdk.i18n.LocaleCode
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PromptPay QR end to end: a THB store with `promptpay.id` set offers it
 * (/health), the QR carries the exact amount, Paid records a PROMPTPAY tender
 * that the receipt, the X report and the sync events show as its own; a store
 * in another currency, or without an id, never offers it.
 */
class PromptPayFlowTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-promptpay").resolve("pos.db").toString()
    private fun obj(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private val fakeId = PromptPayConfig.resolve("0812345678", "TEST SHOP", "test")

    private val bahtStore: (CustomerConfig) -> CustomerConfig = { base ->
        object : CustomerConfig by base {
            override val profile = StoreProfile(
                country = "TH", currency = "THB", locales = listOf(LocaleCode.EN), timeZone = "Etc/GMT-7",
            )
            override val roundingPolicy = CashRounding.resolve(null, "test").forCurrency("THB").policy
        }
    }

    private fun ApplicationTestBuilder.store(
        promptPay: PromptPayConfig.Resolved = fakeId,
        configOverride: ((CustomerConfig) -> CustomerConfig)? = bahtStore,
    ) = application {
        module(
            dbPath = tempDb(), receiptsDir = Files.createTempDirectory("pp-rc").toString(),
            physicalPrinterEnabled = false, promptPay = promptPay, configOverride = configOverride,
        )
    }

    private suspend fun HttpClient.postJson(path: String, body: String = "{}") =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.openCheck(price: Long): Int {
        postJson("/shifts", """{"openingFloatCents":0,"managerPin":"1234"}""")
            .also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }
        val id = obj(postJson("/tables/t1/checks", """{"userId":"manager"}""").bodyAsText())["id"]!!.jsonPrimitive.int
        postJson("/checks/$id/open-lines", """{"name":"Dish","unitPriceCents":$price,"qty":1}""")
            .also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }
        return id
    }

    private fun lastPayload(type: String): JsonObject = transaction {
        SyncOutbox.selectAll().where { SyncOutbox.eventType eq type }
            .orderBy(SyncOutbox.id to SortOrder.DESC).first()[SyncOutbox.payload]
    }.let(::obj)

    @Test
    fun aBahtStoreTakesPromptPayAndReportsItAsItsOwnTender() = testApplication {
        store()
        val health = obj(client.get("/health").bodyAsText())
        assertEquals("true", health["promptPay"]?.jsonPrimitive?.content, health.toString())
        val c = loginClient()
        val id = c.openCheck(21_550)
        val total = obj(c.get("/checks/$id").bodyAsText())["grandTotalCents"]!!.jsonPrimitive.long

        // the QR: the exact amount (no cash rounding), the fake test phone, a valid CRC
        val shown = c.postJson("/checks/$id/tenders/initiate", """{"type":"PROMPTPAY"}""")
            .also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }.bodyAsText().let(::obj)
        assertEquals(total, shown["amountCents"]!!.jsonPrimitive.long)
        val qr = shown["qrPayload"]!!.jsonPrimitive.content
        val amount = "%d.%02d".format(total / 100, total % 100)
        assertTrue("54%02d".format(amount.length) + amount in qr, qr)
        assertTrue("01130066812345678" in qr && "5303764" in qr && "5802TH" in qr, qr)
        assertEquals(PromptPayMethod.crc16ccitt(qr.dropLast(4)), qr.takeLast(4))
        assertEquals("TEST SHOP", shown["displayFields"]!!.jsonArray.single().jsonObject["value"]!!.jsonPrimitive.content)

        // Paid: one PROMPTPAY tender, the bill settled, synced up as its own type
        val paid = c.postJson("/checks/$id/tenders/confirm", """{"type":"PROMPTPAY","amountCents":$total}""")
            .also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }.bodyAsText().let(::obj)
        assertEquals("PROMPTPAY", paid["tender"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(0L, paid["check"]!!.jsonObject["outstandingCents"]!!.jsonPrimitive.long)
        val event = lastPayload("check.tender_confirmed")
        assertEquals("PROMPTPAY", event["type"]!!.jsonPrimitive.content)
        assertEquals(total, event["amountAppliedCents"]!!.jsonPrimitive.long)
        c.post("/checks/$id/finalize").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }

        val receipt = c.get("/checks/$id/receipt").bodyAsText()
        assertTrue("PromptPay" in receipt, receipt)

        val x = obj(c.get("/shifts/current/report").bodyAsText())
        val row = x["tenderBreakdown"]!!.jsonArray.map { it.jsonObject }.single { it["type"]!!.jsonPrimitive.content == "PROMPTPAY" }
        assertEquals(total, row["amountCents"]!!.jsonPrimitive.long)
        assertEquals(1, row["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun notOfferedOutsideThbOrWithoutAnId() {
        // a USD store with an id set: ignored
        testApplication {
            store(configOverride = null)
            assertNull(obj(client.get("/health").bodyAsText())["promptPay"])
            val c = loginClient()
            val id = c.openCheck(1_000)
            val r = c.postJson("/checks/$id/tenders/initiate", """{"type":"PROMPTPAY"}""")
            assertEquals(HttpStatusCode.Conflict, r.status, r.bodyAsText())
            assertTrue("tender_type_not_accepted" in r.bodyAsText())
        }
        // a THB store with no id: not offered either
        testApplication {
            store(promptPay = PromptPayConfig.OFF)
            assertNull(obj(client.get("/health").bodyAsText())["promptPay"])
            val c = loginClient()
            val id = c.openCheck(1_000)
            assertEquals(HttpStatusCode.Conflict,
                c.postJson("/checks/$id/tenders/confirm", """{"type":"PROMPTPAY","amountCents":1000}""").status)
        }
    }
}
