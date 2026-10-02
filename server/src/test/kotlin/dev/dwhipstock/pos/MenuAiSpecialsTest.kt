package dev.dwhipstock.pos

import dev.dwhipstock.pos.aimenu.AiGuard
import dev.dwhipstock.pos.aimenu.MenuChangeSetParser
import dev.dwhipstock.pos.aimenu.MenuFacts
import dev.dwhipstock.pos.aimenu.MenuOp
import dev.dwhipstock.pos.aimenu.NewSpecial
import dev.dwhipstock.pos.base.ItemSchedules
import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.sdk.MenuAiConfig
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The AI menu assistant and menu specials: "prime rib only on Fridays and
 * Saturdays" (set_days), "burgers 9.95 on Tuesdays" / "happy hour 3-6 beers 5"
 * (set_specials) — parsed strictly, previewed as before / after, applied
 * through the menu's own code, undone like any AI change.
 */
class MenuAiSpecialsTest {

    private val facts = MenuFacts(
        categoryIds = listOf("burgers", "beer"),
        itemVariants = mapOf("burger" to listOf("burger:regular"), "lager" to listOf("lager:pint", "lager:pitcher"),
            "prime-rib" to listOf("prime-rib:regular")),
        variantPrices = mapOf("burger:regular" to 1245, "lager:pint" to 750, "lager:pitcher" to 2025, "prime-rib:regular" to 3495),
    )

    private fun parse(ops: String, f: MenuFacts = facts) = MenuChangeSetParser.parse("""{"summary":"","ops":[$ops]}""", f)

    @Test
    fun setDaysAndSpecialsParseStrictly() {
        val ok = parse("""
            {"op":"set_days","item":"prime-rib","days":["sat","fri","FRI"]},
            {"op":"set_specials","item":"burger","specials":[{"days":["tue"],"label":"","prices":[{"variant":"burger:regular","priceMinor":995}]}]},
            {"op":"set_specials","item":"lager","specials":[{"days":["mon","tue","wed","thu","fri","sat","sun"],"from":"15:00","to":"18:00","label":"Happy hour","priceMinor":500}]}
        """)
        assertEquals(emptyList(), ok.rejected)
        assertEquals(MenuOp.SetDays("prime-rib", listOf("fri", "sat")), ok.ops[0])
        assertEquals(MenuOp.SetSpecials("burger", listOf(NewSpecial(listOf("tue"), prices = mapOf("burger:regular" to 995L)))), ok.ops[1])
        // one price for every size: each size it is cheaper for
        val hh = (ok.ops[2] as MenuOp.SetSpecials).specials.single()
        assertEquals("15:00" to "18:00", hh.from to hh.to)
        assertEquals("Happy hour", hh.label)
        assertEquals(mapOf("lager:pint" to 500L, "lager:pitcher" to 500L), hh.prices)

        val bad = parse("""
            {"op":"set_days","item":"prime-rib","days":["friday","funday"]},
            {"op":"set_specials","item":"burger","specials":[{"days":["tue"],"from":"25:00","to":"18:00","priceMinor":995}]},
            {"op":"set_specials","item":"burger","specials":[{"days":["tue"],"from":"15:00","priceMinor":995}]},
            {"op":"set_specials","item":"lager","specials":[{"days":["tue"],"prices":[{"variant":"lager:tower","priceMinor":500}]}]},
            {"op":"set_specials","item":"burger","specials":[{"days":["tue"],"priceMinor":1500}]},
            {"op":"set_specials","item":"burger","specials":[{"days":[],"priceMinor":900}]},
            {"op":"set_days","item":"prime-rib","days":[]},
            {"op":"set_specials","item":"nope","specials":[]}
        """)
        assertEquals(emptyList(), bad.ops)
        assertEquals(8, bad.rejected.size, bad.rejected.toString())
        listOf("unknown day", "times must be HH:mm", "both from and to", "unknown size", "below the menu price",
            "at least one day", "nothing to change", "unknown item").forEach { why ->
            assertTrue(bad.rejected.any { it.contains(why) }, "$why in ${bad.rejected}")
        }
        // the item's current state: listing it again is nothing to change; [] clears
        val now = MenuFacts(facts.categoryIds, facts.itemVariants, variantPrices = facts.variantPrices,
            itemDays = mapOf("prime-rib" to listOf("fri", "sat")),
            itemSpecials = mapOf("burger" to listOf(NewSpecial(listOf("tue"), prices = mapOf("burger:regular" to 995L)))))
        val again = parse("""
            {"op":"set_days","item":"prime-rib","days":["fri","sat"]},
            {"op":"set_days","item":"prime-rib","days":["mon","tue","wed","thu","fri","sat","sun"]},
            {"op":"set_specials","item":"burger","specials":[{"days":["tue"],"priceMinor":995}]},
            {"op":"set_specials","item":"burger","specials":[]}
        """, now)
        assertEquals(listOf(MenuOp.SetDays("prime-rib", emptyList()), MenuOp.SetSpecials("burger", emptyList())), again.ops)
        assertEquals(2, again.rejected.size)
    }

    @Test
    fun beforeAndAfterWordsInEveryLanguage() {
        val d = MenuChangeSetParser
        assertEquals("Only Fri & Sat", d.describeAvailability(listOf("fri", "sat"), "en"))
        assertEquals("Seulement ven et sam", d.describeAvailability(listOf("fri", "sat"), "fr"))
        assertEquals("Solo vie y sáb", d.describeAvailability(listOf("fri", "sat"), "es"))
        assertEquals("Nur Fr & Sa", d.describeAvailability(listOf("fri", "sat"), "de"))
        assertEquals("Net Vr en Sa", d.describeAvailability(listOf("fri", "sat"), "af"))
        assertEquals("Every day", d.describeAvailability(emptyList(), "en"))
        assertEquals("Jeden Tag", d.describeAvailability(emptyList(), "de"))
        val hh = NewSpecial(listOf("mon", "tue", "wed", "thu", "fri"), "16:00", "18:00", "Happy hour", mapOf("x" to 500L))
        assertEquals("Happy hour · Mon–Fri 16:00–18:00", d.describeSpecial(hh, "en"))
        assertEquals("Happy hour · lun–ven 16:00–18:00", d.describeSpecial(hh, "fr"))
        assertEquals("So", d.describeDays(listOf("sun"), "de"))
        assertEquals("Mon, Wed & Fri", d.describeDays(listOf("mon", "wed", "fri"), "en"))
        assertEquals("Aucun spécial", d.describeSpecials(emptyList(), "fr"))
    }

    @Test
    fun specialsRequestsAreMenuRequestsInEveryLanguage() {
        val requests = listOf(
            "prime rib only on Fridays and Saturdays", "burgers $9.95 on Tuesdays", "happy hour 3-6 beers $5",
            "côte de bœuf seulement le vendredi et le samedi", "burgers à 9,95 $ le mardi", "happy hour de 15 h à 18 h, bières à 5 $",
            "costilla solo los viernes y sábados", "hamburguesas a 9,95 $ los martes", "hora feliz de 3 a 6, cervezas a 5 $",
            "Prime Rib nur freitags und samstags", "Burger dienstags für 9,95 $", "Happy Hour 15 bis 18 Uhr, Bier für 5 $",
            "ribbetjie net Vrydae en Saterdae", "burgers R9,95 op Dinsdae", "happy hour 3 tot 6, bier vir \$5",
        )
        assertEquals(emptyList(), requests.filter { AiGuard.offTopic(it) || AiGuard.hatefulRequest(it) })
    }

    // --- through the store: preview, apply, undo ---

    private fun tempDir(prefix: String) = Files.createTempDirectory(prefix).toString()
    private fun on() = MenuAiConfig.fromProperties(Properties().apply {
        setProperty("menu.ai", "on"); setProperty("menu.ai.provider", "anthropic")
        setProperty("menu.ai.anthropic.apiKey", "sk-ant-SECRET-never-leaves-0123456789")
    })
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun price(variantId: String) = transaction {
        ItemVariants.selectAll().where { ItemVariants.id eq variantId }.first()[ItemVariants.priceCents]
    }

    @Test
    fun chatProposesSpecialsAppliesThemAndUndoes() = testApplication {
        val fake = FakeMenuProvider("""
            {"summary":"Prime rib on weekends, burger Tuesday, happy hour.","ops":[
              {"op":"set_days","item":"steak-frites","days":["fri","sat"]},
              {"op":"set_specials","item":"lantern-burger","specials":[{"days":["tue"],"label":"","prices":[{"variant":"lantern-burger:regular","priceMinor":995}]}]},
              {"op":"set_specials","item":"lantern-lager","specials":[{"days":["mon","tue","wed","thu","fri"],"from":"15:00","to":"18:00","label":"Happy hour","prices":[{"variant":"lantern-lager:pint","priceMinor":500}]}]},
              {"op":"set_specials","item":"lantern-lager","specials":[{"days":["tue"],"prices":[{"variant":"lantern-lager:tower","priceMinor":500}]}]}
            ]}
        """.trimIndent())
        application {
            module(dbPath = tempDir("pos-menu-ai") + "/pos.db", photosDir = tempDir("photos"),
                menuAiConfig = on(), menuAiProvider = fake, imageReachable = { true })
        }
        val manager = loginClient()
        val burger = price("lantern-burger:regular")
        val res = manager.post("/menu-ai/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"managerPin":"1234","text":"steak frites only on Fridays and Saturdays, burgers 9.95 on Tuesdays, happy hour 3-6 weekdays pints 5"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val proposal = obj(res.bodyAsText())
        val changes = proposal["changes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, changes.size)
        assertEquals(1, proposal["rejected"]!!.jsonArray.size)
        // the model was told the specials ops
        assertTrue(fake.prompts.single().contains(MenuChangeSetParser.SPECIALS_PROMPT))
        val days = changes[0]["details"]!!.jsonArray.single().jsonObject
        assertEquals("available", days.s("field"))
        assertEquals("Every day", days.s("before"))
        assertEquals("Only Fri & Sat", days.s("after"))
        val tue = changes[1]["details"]!!.jsonArray.single().jsonObject
        assertEquals("price", tue.s("field"))
        assertEquals("Tue", tue.s("label"))
        assertEquals("$" + "%.2f".format(burger / 100.0), tue.s("before"))
        assertEquals("$9.95", tue.s("after"))
        val hh = changes[2]["details"]!!.jsonArray.single().jsonObject
        assertTrue(hh.s("label").endsWith("Happy hour · Mon–Fri 15:00–18:00"), hh.s("label"))
        assertEquals("$5.00", hh.s("after"))
        // nothing changed yet
        assertTrue(transaction { ItemSchedules.all() }.isEmpty())

        val apply = manager.post("/menu-ai/apply") {
            contentType(ContentType.Application.Json)
            setBody("""{"managerPin":"1234","proposalId":"${proposal.s("proposalId")}","changeIds":${changes.map { "\"" + it.s("id") + "\"" }}}""")
        }
        assertEquals(HttpStatusCode.OK, apply.status, apply.bodyAsText())
        val all = transaction { ItemSchedules.all() }
        assertEquals(listOf("fri", "sat"), all.getValue("steak-frites").availableDays)
        assertEquals(mapOf("lantern-burger:regular" to 995L), all.getValue("lantern-burger").specials.single().prices)
        val lager = all.getValue("lantern-lager").specials.single()
        assertEquals("15:00" to "18:00", lager.from to lager.to)
        assertEquals(burger, price("lantern-burger:regular")) // the menu price itself never moves

        // the next request sees them, so the model can keep or change them
        manager.post("/menu-ai/chat") {
            contentType(ContentType.Application.Json); setBody("""{"managerPin":"1234","text":"burgers 9.95 on Tuesdays"}""")
        }
        assertTrue(fake.prompts.last().contains("\"availableDays\":[\"fri\",\"sat\"]"), fake.prompts.last().takeLast(400))

        // undo: everything back to every day, no specials
        val setId = obj(apply.bodyAsText()).s("changeSetId")
        val revert = manager.post("/menu-ai/history/$setId/revert") {
            contentType(ContentType.Application.Json); setBody("""{"managerPin":"1234"}""")
        }
        assertEquals(HttpStatusCode.OK, revert.status, revert.bodyAsText())
        assertTrue(transaction { ItemSchedules.all() }.isEmpty())
    }
}
