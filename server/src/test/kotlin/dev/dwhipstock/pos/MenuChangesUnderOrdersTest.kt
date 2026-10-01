package dev.dwhipstock.pos

import dev.dwhipstock.pos.CloudMenu.b
import dev.dwhipstock.pos.CloudMenu.n
import dev.dwhipstock.pos.CloudMenu.s
import dev.dwhipstock.pos.aimenu.MenuChangeLog
import dev.dwhipstock.pos.aimenu.MenuRevertConflictException
import dev.dwhipstock.pos.api.CatalogOps
import dev.dwhipstock.pos.api.ItemPatchRequest
import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternConfig
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternExpressSeed
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue
import dev.dwhipstock.pos.base.SettingsRepository
import dev.dwhipstock.pos.db.initDatabase
import dev.dwhipstock.pos.restaurant.CheckService
import dev.dwhipstock.pos.restaurant.KioskOrderLine
import dev.dwhipstock.pos.restaurant.KioskOrderRequest
import dev.dwhipstock.pos.restaurant.LineRejectedException
import dev.dwhipstock.pos.restaurant.QuickServeService
import dev.dwhipstock.pos.sdk.PrinterAdapter
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
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What happens when the menu changes under a live order (two-way menu sync:
 * a manager can rename, reprice, 86 or delete an item from the portal while
 * it is on an open check, in a guest's QR cart, at the kiosk or on a staff
 * phone). The store is the source of truth for orders: a line keeps the menu
 * as it was rung; a cart line the menu no longer allows is refused on its own,
 * with a code the client explains; nothing is silently dropped or re-priced.
 * One test per case of docs/architecture.md "When the menu changes under an
 * open order".
 */
class MenuChangesUnderOrdersTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-menu-orders").resolve("pos.db").toString()
    private fun obj(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private suspend fun HttpClient.postJson(path: String, body: String = "{}") =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.openCheck(table: String): Int =
        obj(postJson("/tables/$table/checks", """{"userId":"manager"}""").bodyAsText())["id"]!!.jsonPrimitive.int

    private suspend fun HttpClient.add(check: Int, item: String, variant: String, qty: Int = 1, expected: Long? = null) =
        postJson("/checks/$check/lines", """{"itemId":"$item","variantId":"$variant","qty":$qty${
            expected?.let { ",\"expectedPriceCents\":$it" } ?: ""}}""")

    private suspend fun HttpClient.check(id: Int): JsonObject = obj(get("/checks/$id").bodyAsText())

    private fun JsonObject.line(item: String) = this["lines"]!!.jsonArray.map { it.jsonObject }.first { it["itemId"]!!.jsonPrimitive.content == item }

    private fun price(variantId: String) = transaction { ItemVariants.selectAll().where { ItemVariants.id eq variantId }.first()[ItemVariants.priceCents] }

    private suspend fun HttpClient.payAndClose(check: Int) {
        val due = check(check)["cashDueCents"]!!.jsonPrimitive.long
        postJson("/checks/$check/tenders", """{"type":"CASH","amountTenderedCents":${due + 10_000}}""")
            .let { assertTrue(it.status.isSuccess(), it.bodyAsText()) }
        post("/checks/$check/finalize").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
    }

    /** 1 + 5 + 7 + 8 + 12: deleted in the portal while on an open check. */
    @Test
    fun anItemDeletedInThePortalStaysOnTheOpenCheckAndCanBeSettledButNotRungAgain() = testApplication {
        application { module(dbPath = tempDb(), receiptsDir = Files.createTempDirectory("rc").toString()) }
        val c = loginClient()
        c.postJson("/shifts", """{"openingFloatCents":0,"managerPin":"1234"}""")
        val id = c.openCheck("t1")
        assertEquals(HttpStatusCode.Created, c.add(id, "lantern-lager", "lantern-lager:pitcher", qty = 2).status)
        val before = c.check(id).line("lantern-lager")
        val total = c.check(id)["grandTotalCents"]!!.jsonPrimitive.long
        val version = obj(c.get("/menu/version").bodyAsText())["version"]!!.jsonPrimitive.long

        // the portal deletes it (synced down): the store takes the delete although it is on the check
        CloudMenu.apply(CloudMenu.item("lantern-lager", fields = mapOf("deleted" to b(true))))
        assertNotNull(transaction { Items.selectAll().where { Items.id eq "lantern-lager" }.first()[Items.deletedAt] })
        assertTrue(obj(c.get("/menu/version").bodyAsText())["version"]!!.jsonPrimitive.long > version, "menus are told to refresh")
        // gone from every menu…
        assertFalse(c.get("/items").bodyAsText().contains("\"lantern-lager\""))
        // …but the line is exactly as rung: name, size, price, total
        val after = c.check(id).line("lantern-lager")
        for (k in listOf("nameEn", "nameFr", "variantLabelEn", "unitPriceCents", "qty", "lineTotalCents"))
            assertEquals(before[k], after[k], k)
        assertEquals(total, c.check(id)["grandTotalCents"]!!.jsonPrimitive.long)
        // it can't be rung again (a clear code, not a 500)
        c.add(id, "lantern-lager", "lantern-lager:pint").let {
            assertEquals(HttpStatusCode.Conflict, it.status)
            assertEquals("item_unavailable", obj(it.bodyAsText())["code"]!!.jsonPrimitive.content)
        }
        // it is paid, closed, refunded and reprinted under its name as rung
        c.payAndClose(id)
        val receipt = c.get("/checks/$id/receipt").bodyAsText()
        assertTrue(before["nameEn"]!!.jsonPrimitive.content in receipt || before["nameFr"]!!.jsonPrimitive.content in receipt, receipt)
        val lineId = after["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$id/refund", """{"lines":[{"lineId":$lineId,"qty":1}],"tenderType":"CASH","reason":"x","managerPin":"1234"}""")
            .let { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }
        // the sale goes up (and reports) under the name it was sold as
        val closed = outboxSince(0).last { it.first == "check.closed" }.second
        val line = closed["lines"]!!.jsonArray.single().jsonObject
        assertEquals(before["nameEn"]!!.jsonPrimitive.content, line["nameEn"]!!.jsonPrimitive.content)
        val mix = obj(c.get("/shifts/current/report").bodyAsText()).toString()
        assertTrue(before["nameEn"]!!.jsonPrimitive.content in mix, mix)
    }

    /** 1: an 86 from the portal works the same way. */
    @Test
    fun anItem86dInThePortalCantBeAddedButTheLineOnTheCheckIsUntouched() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val id = c.openCheck("t2")
        c.add(id, "amber-ale", "amber-ale:pint")
        CloudMenu.apply(CloudMenu.item("amber-ale", fields = mapOf("active" to b(false))))
        assertEquals(1, c.check(id)["lines"]!!.jsonArray.size)
        c.add(id, "amber-ale", "amber-ale:pint").let {
            assertEquals(HttpStatusCode.Conflict, it.status)
            assertEquals("item_unavailable", obj(it.bodyAsText())["code"]!!.jsonPrimitive.content)
        }
        // a deleted item can't be "un-86'd" back into a ghost either
        CloudMenu.apply(CloudMenu.item("amber-ale", fields = mapOf("deleted" to b(true))))
        c.postJson("/items/amber-ale/availability", """{"active":true,"managerPin":"1234"}""")
            .let { assertEquals(HttpStatusCode.NotFound, it.status) }
    }

    /** 2: renamed, repriced and re-translated while on an open check (and mid-split). */
    @Test
    fun aRenameOrRepriceKeepsTheRungLinesAndOnlyNewAddsUseTheNewValues() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        val id = c.openCheck("t3")
        c.add(id, "lantern-lager", "lantern-lager:pint", qty = 2)
        c.add(id, "amber-ale", "amber-ale:pint")
        val oldLine = c.check(id).line("lantern-lager")
        // a 2-way split, assigned before the change
        val split = obj(c.postJson("/checks/$id/split", """{"groups":2}""").bodyAsText())
        val g = split["split"]!!.jsonObject["groups"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.int }
        val lines = c.check(id)["lines"]!!.jsonArray.map { it.jsonObject }
        c.postJson("/checks/$id/split/groups/${g[0]}/lines", """{"lineId":${lines[0]["id"]!!.jsonPrimitive.int},"qty":2}""")
        c.postJson("/checks/$id/split/groups/${g[1]}/lines", """{"lineId":${lines[1]["id"]!!.jsonPrimitive.int},"qty":1}""")
        val groupTotals = c.check(id)["split"]!!.jsonObject["groups"]!!.jsonArray.map { it.jsonObject["grandTotalCents"]!!.jsonPrimitive.long }

        CloudMenu.apply(CloudMenu.item("lantern-lager", fields = mapOf("nameEn" to s("Renamed Lager"), "names.es" to s("Cerveza nueva")),
            variants = mapOf("lantern-lager:pint" to mapOf("priceCents" to n(1999), "labelEn" to s("Big pint")))))
        val now = c.check(id)
        val kept = now.line("lantern-lager")
        assertEquals(oldLine["nameEn"], kept["nameEn"])
        assertEquals(oldLine["unitPriceCents"], kept["unitPriceCents"])
        assertEquals(groupTotals, now["split"]!!.jsonObject["groups"]!!.jsonArray.map { it.jsonObject["grandTotalCents"]!!.jsonPrimitive.long })
        // the guest's bill keeps the extra-language name as ordered, too
        assertFalse("Cerveza nueva" in c.get("/checks/$id/receipt").bodyAsText())

        // a new add uses the new name and price — and a client that showed the old price is asked to confirm
        val other = c.openCheck("t4")
        c.add(other, "lantern-lager", "lantern-lager:pint", expected = oldLine["unitPriceCents"]!!.jsonPrimitive.long).let {
            assertEquals(HttpStatusCode.Conflict, it.status)
            val body = obj(it.bodyAsText())
            assertEquals("price_changed", body["code"]!!.jsonPrimitive.content)
            assertEquals(1999L, body["priceCents"]!!.jsonPrimitive.long)
        }
        assertEquals(HttpStatusCode.Created, c.add(other, "lantern-lager", "lantern-lager:pint", expected = 1999).status)
        val fresh = c.check(other).line("lantern-lager")
        assertEquals("Renamed Lager", fresh["nameEn"]!!.jsonPrimitive.content)
        assertEquals(1999L, fresh["unitPriceCents"]!!.jsonPrimitive.long)
    }

    /** 3: the guest's QR cart — only the affected lines are refused, the rest goes through. */
    @Test
    fun aGuestCartRefusesOnlyTheLinesTheMenuNoLongerAllows() = testApplication {
        application { module(dbPath = tempDb()) }
        startApplication() // the store's database is up before the portal's edits are applied to it
        val pitcher = price("lantern-lager:pitcher")
        val pint = price("amber-ale:pint")
        CloudMenu.apply(
            CloudMenu.item("late-fries", fields = mapOf("deleted" to b(true))),
            CloudMenu.item("amber-ale", variants = mapOf("amber-ale:pint" to mapOf("priceCents" to n(pint + 100)))),
        )
        val res = client.postJson("${customerPath("t5-5")}/pending-lines", """{"lines":[
            {"itemId":"lantern-lager","variantId":"lantern-lager:pitcher","qty":1,"expectedPriceCents":$pitcher},
            {"itemId":"late-fries","variantId":"late-fries:regular","qty":1},
            {"itemId":"amber-ale","variantId":"amber-ale:pint","qty":1,"expectedPriceCents":$pint}]}""")
        assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
        val body = obj(res.bodyAsText())
        assertEquals(1, body["pendingLines"]!!.jsonArray.size)
        assertEquals(listOf("lantern-lager"), loginClient().staffCheckAt("t5-5")["pendingLines"]!!.jsonArray
            .map { it.jsonObject["itemId"]!!.jsonPrimitive.content })
        val rejected = body["rejected"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(1 to "item_unavailable", 2 to "price_changed"),
            rejected.map { it["index"]!!.jsonPrimitive.int to it["code"]!!.jsonPrimitive.content })
        assertEquals(pint + 100, rejected[1]["priceCents"]!!.jsonPrimitive.long)
        assertTrue(rejected[0]["nameEn"]!!.jsonPrimitive.content.isNotBlank()) // so the guest's phone can say which
        // every line refused: 409, nothing added
        val linesBefore = transaction { dev.dwhipstock.pos.restaurant.CheckLines.selectAll().count() }
        val none = client.postJson("${customerPath("t5-5")}/pending-lines",
            """{"lines":[{"itemId":"late-fries","variantId":"late-fries:regular","qty":1}]}""")
        assertEquals(HttpStatusCode.Conflict, none.status)
        assertEquals("lines_rejected", obj(none.bodyAsText())["code"]!!.jsonPrimitive.content)
        assertEquals(linesBefore, transaction { dev.dwhipstock.pos.restaurant.CheckLines.selectAll().count() })
    }

    /** 3: the kiosk — partial order with the refused lines listed; the upsell never offers a deleted item. */
    @Test
    fun aKioskOrderTakesTheGoodLinesAndListsTheRefusedOnes() {
        val dir = Files.createTempDirectory("pos-menu-kiosk").toFile()
        initDatabase(File(dir, "pos.db").path)
        CopperLanternExpressSeed.seedIfEmpty()
        val config = CopperLanternConfig(
            venue = CopperLanternVenue.EXPRESS, settings = SettingsRepository(),
            printer = PrinterAdapter.VirtualPrinter(File(dir, "r").path, File(dir, "b").path),
            publicBaseUrl = "http://127.0.0.1:8080",
        )
        val checks = CheckService(config)
        val qs = QuickServeService(config, checks).also { it.ensureCounter() }
        val burger = KioskOrderLine("lantern-burger", "lantern-burger:regular", expectedPriceCents = price("lantern-burger:regular"))
        val brownie = KioskOrderLine("brownie", "brownie:regular")
        CloudMenu.apply(CloudMenu.item("brownie", fields = mapOf("active" to b(false))))
        val r = qs.placeKioskOrder(KioskOrderRequest("TAKE_OUT", listOf(burger, brownie)), "Kiosk")
        assertEquals(listOf(1), r.rejected.map { it.index })
        assertEquals("item_unavailable", r.rejected.single().code)
        assertEquals(listOf("lantern-burger"), checks.getCheck(r.checkId).lines.map { it.itemId })
        assertEquals(checks.getCheck(r.checkId).grandTotalCents, r.totalCents) // the total is what was taken
        val all = assertFailsWith<LineRejectedException> { qs.placeKioskOrder(KioskOrderRequest("TAKE_OUT", listOf(brownie)), "Kiosk") }
        assertEquals("lines_rejected", all.code)
        // the counter's single add gets the same refusal
        assertEquals("item_unavailable", assertFailsWith<LineRejectedException> { qs.createAtPos("TAKE_OUT", brownie, "manager") }.code)
    }

    /** 2 + 13: a rename / size change / re-categorisation after the kitchen ticket printed re-prints nothing. */
    @Test
    fun aMenuChangeAfterTheTicketPrintedDoesNotVoidAndReAddIt() {
        val f = KitchenFixture()
        val check = f.open()
        f.add(check, "lantern-burger")
        assertEquals(1, f.kitchen.send(check, "Demo Server").tickets)
        val station = f.kitchen.stationForItem("lantern-burger")
        // the portal renames it, adds a second size and moves it to another category
        val otherCategory = transaction { Items.selectAll().where { Items.id eq "amber-ale" }.first()[Items.categoryId] }
        CloudMenu.apply(CloudMenu.item("lantern-burger", fields = mapOf("nameEn" to s("Big Burger"), "categoryId" to s(otherCategory)),
            variants = mapOf("lantern-burger:double" to mapOf("labelEn" to s("Double"), "labelFr" to s("Double"),
                "priceCents" to n(2400), "sortOrder" to n(1), "deleted" to b(false)))))
        assertEquals(0, f.kitchen.send(check, "Demo Server").tickets, "nothing new for the kitchen")
        // a line rung after the change goes where the item goes now; the old one stayed where it was sent
        f.add(check, "lantern-burger", "lantern-burger:double")
        val r = f.kitchen.send(check, "Demo Server")
        assertEquals(1, r.tickets)
        assertTrue(station != null)
    }

    /** 9: an AI menu revert after the portal changed the same item. */
    @Test
    fun anAiRevertAfterAPortalEditAsksFirstAndThenWinsAsANewEdit() {
        initDatabase(tempDb())
        dev.dwhipstock.pos.customers.copperlantern.CopperLanternSeed.seedIfEmpty(CopperLanternVenue.VIEUX_PORT)
        transaction { dev.dwhipstock.pos.api.writeChunkedCatalogSnapshot() }
        val original = transaction { Items.selectAll().where { Items.id eq "lantern-lager" }.first()[Items.nameEn] }
        transaction {
            val before = MenuChangeLog.itemState("lantern-lager")
            CatalogOps.patchItem("lantern-lager", ItemPatchRequest(nameEn = "AI Lager"))
            MenuChangeLog.record("set-1", "manager", "manager", "chat", "rename",
                listOf(MenuChangeLog.Row("item", "lantern-lager", "update", "Lantern Lager", before)))
        }
        CloudMenu.apply(CloudMenu.item("lantern-lager", fields = mapOf("nameEn" to s("Portal Lager")), stamp = CloudMenu.stamp(1_000)))
        // not silently: the manager is told the portal changed it since
        assertFailsWith<MenuRevertConflictException> { transaction { MenuChangeLog.revert("set-1", "manager", force = false) } }
        assertEquals("Portal Lager", transaction { Items.selectAll().where { Items.id eq "lantern-lager" }.first()[Items.nameEn] })
        // confirmed: the revert is a new edit, stamped now, so it wins everywhere
        val mark = lastOutboxId()
        transaction { MenuChangeLog.revert("set-1", "manager", force = true) }
        assertEquals(original, transaction { Items.selectAll().where { Items.id eq "lantern-lager" }.first()[Items.nameEn] })
        val up = outboxSince(mark).last { it.first.startsWith("item.") }.second
        assertTrue(up["origin"] == null)
        val stamp = up["item"]!!.jsonObject["clock"]!!.jsonObject["nameEn"]!!.jsonPrimitive.content
        assertFalse(stamp.endsWith("-cloud"), stamp)
    }

    /** 10: the same name again after a delete is a new item; the old one's history stays. */
    @Test
    fun reAddingADeletedItemByNameMakesANewIdAndKeepsTheOldHistory() = testApplication {
        application { module(dbPath = tempDb()) }
        val c = loginClient()
        c.postJson("/shifts", """{"openingFloatCents":0,"managerPin":"1234"}""")
        val body = """{"nameFr":"Soupe","nameEn":"Seasonal Soup","categoryId":"starters","abbrev":"SS",
            "variants":[{"labelFr":"bol","labelEn":"Bowl","priceCents":800}]}"""
        val first = obj(c.postJson("/items", body).bodyAsText())["id"]!!.jsonPrimitive.content
        val id = c.openCheck("t1")
        c.add(id, first, "$first:bowl")
        c.payAndClose(id)
        CloudMenu.apply(CloudMenu.item(first, fields = mapOf("deleted" to b(true))))
        val second = obj(c.postJson("/items", body).bodyAsText())["id"]!!.jsonPrimitive.content
        assertTrue(second != first, "$second vs $first")
        assertEquals("Seasonal Soup", c.check(id).line(first)["nameEn"]!!.jsonPrimitive.content)
    }
}
