package dev.dwhipstock.pos

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsAndCategoriesTest {

    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    @Test
    fun categoriesAreServerDataInSeededOrder() = testApplication {
        application { module(dbPath = tempDb()) }
        // open route: the customer menu page fetches it pre-auth
        val res = client.get("/categories")
        assertEquals(HttpStatusCode.OK, res.status)
        val cats = Json.parseToJsonElement(res.bodyAsText()).jsonArray
        assertEquals(
            listOf("beer-cider", "wine", "cocktails", "starters", "burgers-sandwiches", "mains-salads", "desserts"),
            cats.map { it.jsonObject["id"]!!.jsonPrimitive.content },
        )
        assertEquals("Bières et cidres", cats[0].jsonObject["nameFr"]!!.jsonPrimitive.content)
        assertEquals("Beer & Cider", cats[0].jsonObject["nameEn"]!!.jsonPrimitive.content)

        // items still carry their category id after the column rename
        val items = Json.parseToJsonElement(client.get("/items").bodyAsText()).jsonArray
        assertTrue(items.all { it.jsonObject["category"]!!.jsonPrimitive.content in
            listOf("beer-cider", "wine", "cocktails", "starters", "burgers-sandwiches", "mains-salads", "desserts") })
    }

    @Test
    fun openCheckIsOwnedByTheSessionUser() = testApplication {
        application { module(dbPath = tempDb()) }
        val server = loginClient(pin = "9999") // server1 logs in
        val check = Json.parseToJsonElement(
            server.post("/tables/t5/checks").bodyAsText()).jsonObject
        val checkId = check["id"]!!.jsonPrimitive.content
        // outbox records the session user as opener, not a client-supplied id
        val openedBy = transaction2 {
            var v = ""
            exec("SELECT opened_by FROM checks WHERE id = $checkId") { rs -> rs.next(); v = rs.getString(1) }
            v
        }
        assertEquals("server1", openedBy)
    }

    @Test
    fun settingsAreManagerGatedAndTakeEffectNextTransaction() = testApplication {
        application {
            module(dbPath = tempDb(), receiptsDir = Files.createTempDirectory("rc").toString())
        }
        val server = loginClient(pin = "9999")
        val manager = loginClient()

        // gated: server role can't read or write
        assertEquals(HttpStatusCode.Forbidden, server.get("/settings").status)
        assertEquals(HttpStatusCode.Forbidden, server.patch("/settings") {
            contentType(ContentType.Application.Json); setBody("""{"serviceChargePercent":10}""")
        }.status)

        // defaults come from the migration seed
        val before = Json.parseToJsonElement(manager.get("/settings").bodyAsText()).jsonObject
        assertEquals("0", before["serviceChargePercent"]!!.jsonPrimitive.content)
        assertEquals("2500", before["corkagePerBottleCents"]!!.jsonPrimitive.content)

        // owner turns service charge on, changes corkage + footer + card
        val patched = manager.patch("/settings") {
            contentType(ContentType.Application.Json)
            setBody("""{"serviceChargePercent":10,"corkagePerBottleCents":15000,
                        "receiptFooter":"see you again","cardProcessor":"Front Bar Terminal"}""")
        }
        assertEquals(HttpStatusCode.OK, patched.status)

        // tendering needs an open shift
        manager.post("/shifts") {
            contentType(ContentType.Application.Json)
            setBody("""{"openingFloatCents":0,"managerPin":"1234"}""")
        }.let { assertEquals(HttpStatusCode.Created, it.status) }

        // next transaction: $43.25 bottle + 1 corkage bottle → 10% SC $4.32 + corkage $150
        val checkId = Json.parseToJsonElement(manager.post("/tables/t5/checks").bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
        manager.post("/checks/$checkId/lines") {
            contentType(ContentType.Application.Json)
            setBody("""{"itemId":"pinot-noir","variantId":"pinot-noir:bottle","qty":1}""")
        }
        manager.post("/checks/$checkId/corkage") {
            contentType(ContentType.Application.Json); setBody("""{"bottles":1}""")
        }
        val check = Json.parseToJsonElement(manager.get("/checks/$checkId").bodyAsText()).jsonObject
        val fees = check["fees"]!!.jsonArray.associate {
            it.jsonObject["code"]!!.jsonPrimitive.content to
                it.jsonObject["amountCents"]!!.jsonPrimitive.content
        }
        assertEquals("432", fees["service_charge"], "10% service charge on the $43.25 bottle (floored)")
        assertEquals("15000", fees["corkage"], "new $150 rate")

        // Card instructions identify the configured terminal.
        val instructions = manager.post("/checks/$checkId/tenders/initiate") {
            contentType(ContentType.Application.Json)
            setBody("""{"type":"CARD"}""")
        }.bodyAsText()
        assertTrue("Front Bar Terminal" in instructions)

        // receipt footer changed too: settle + finalize, then re-render
        manager.post("/checks/$checkId/tenders") {
            contentType(ContentType.Application.Json)
            setBody("""{"type":"CASH","amountTenderedCents":${check["grandTotalCents"]!!.jsonPrimitive.content}}""")
        }
        manager.post("/checks/$checkId/finalize")
        val receipt = Json.parseToJsonElement(manager.get("/checks/$checkId/receipt").bodyAsText())
            .jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("see you again" in receipt, "footer should be the edited one:\n$receipt")

        // outbox recorded the changed keys
        val changed = transaction2 {
            var v = ""
            exec("SELECT payload FROM sync_outbox WHERE event_type='settings.updated'") { rs ->
                rs.next(); v = rs.getString(1)
            }
            v
        }
        for (key in listOf("serviceChargePercent", "corkagePerBottleCents", "receiptFooter", "cardProcessor")) {
            assertTrue(key in changed, "expected $key in settings.updated payload: $changed")
        }
    }
}

/** Raw-SQL helper against the app's current default database. */
private fun <T> transaction2(block: org.jetbrains.exposed.sql.Transaction.() -> T): T =
    org.jetbrains.exposed.sql.transactions.transaction { block() }
