package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.CheckLines
import dev.dwhipstock.poscloud.db.Checks
import dev.dwhipstock.poscloud.menuai.AiAudio
import dev.dwhipstock.poscloud.menuai.MenuAiModel
import dev.dwhipstock.poscloud.menuai.MenuAiService
import dev.dwhipstock.poscloud.menuai.SalesInsights
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How the specials are doing (menuai/MenuAiSalesInsights.kt), with a fake
 * model: the lift of each special on its days and hours against comparable
 * other days, ending the ones that don't work (a proposal), and what sells in
 * happy hour — every number computed by the server.
 */
class MenuAiSalesInsightsTest {

    private val key = "store-key-ai-insights"
    private lateinit var owner: String
    private var checkId = 5000
    private val zone = ZoneId.of("America/New_York")
    /** Saturday 2026-10-03, 15:00 in New York. */
    private val now = Instant.parse("2026-10-03T19:00:00Z").toEpochMilli()
    private val today = LocalDate.of(2026, 10, 3)

    private class FakeModel(var reply: String = """{"ops":[]}""") : MenuAiModel {
        override val id = "fake"
        override val model = "fake-model"
        override fun complete(system: String, user: String, audio: AiAudio?): String = reply
    }

    private val fake = FakeModel()

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("copperlantern", "vieux-port", "Copper Lantern — Glenwood South")
        seedStoreKey("copperlantern", "vieux-port", key)
        owner = seedSession("copperlantern", seedUser("copperlantern", "owner@insights.test", "pw-owner-1"))
    }

    private fun item(id: String, en: String, cat: String, sizes: List<Pair<String, Long>>) = buildJsonObject {
        put("id", id); put("nameFr", en); put("nameEn", en)
        put("descriptionFr", ""); put("descriptionEn", ""); put("categoryId", cat)
        put("abbrev", en.take(2).uppercase()); put("isAlcohol", cat == "beer"); put("active", true); put("deleted", false)
        put("names", buildJsonObject {})
        put("variants", buildJsonArray {
            sizes.forEachIndexed { i, (label, price) ->
                add(buildJsonObject {
                    put("id", "$id:$label"); put("labelFr", label); put("labelEn", label)
                    put("priceCents", price); put("sortOrder", i); put("deleted", false); put("names", buildJsonObject {})
                })
            }
        })
    }

    private suspend fun ApplicationTestBuilder.setUpStore() {
        application { module(TestSupport.config, MenuAiService(TestSupport.config, fake, fake, now = { now }, callsMax = 100)) }
        ingest(key, event("catalog.snapshot", buildJsonObject {
            put("categories", buildJsonArray {
                add(buildJsonObject { put("id", "beer"); put("nameFr", "Bière"); put("nameEn", "Beer"); put("sortOrder", 0); put("deleted", false) })
                add(buildJsonObject { put("id", "food"); put("nameFr", "Plats"); put("nameEn", "Food"); put("sortOrder", 1); put("deleted", false) })
            })
            put("items", buildJsonArray {
                add(item("lager", "Lantern House Lager", "beer", listOf("pint" to 825L)))
                add(item("burger", "Copper Burger", "food", listOf("regular" to 1245L)))
                add(item("wings", "Wings", "food", listOf("regular" to 1600L)))
                add(item("poutine", "Poutine", "food", listOf("regular" to 1400L)))
            })
        }, seq = 1))
        client.get("/v1/store/menu/changes?since=0") { header(HttpHeaders.Authorization, "Bearer $key") }
        // the specials: burger Tuesdays all day, lager happy hour Mon–Fri 4–6, poutine Sundays
        fake.reply = """{"summary":"x","ops":[
            {"op":"set_specials","item":"burger","specials":[{"days":["tue"],"label":"","prices":[{"variant":"burger:regular","priceMinor":995}]}]},
            {"op":"set_specials","item":"lager","specials":[{"days":["mon","tue","wed","thu","fri"],"from":"16:00","to":"18:00","label":"Happy hour","prices":[{"variant":"lager:pint","priceMinor":500}]}]},
            {"op":"set_specials","item":"poutine","specials":[{"days":["sun"],"label":"","prices":[{"variant":"poutine:regular","priceMinor":1100}]}]}]}"""
        val p = chat("specials")
        val a = post("/v1/menu-ai/apply?venue=vieux-port", """{"proposalId":"${p.s("proposalId")}","changeIds":["c1","c2","c3"]}""")
        assertEquals(HttpStatusCode.OK, a.status, a.bodyAsText())
        seedSales()
    }

    private fun sale(date: LocalDate, hour: Int, item: String, qty: Int, unit: Long) = transaction {
        val id = ++checkId
        val closed = OffsetDateTime.of(date.atTime(hour, 15), zone.rules.getOffset(date.atTime(hour, 15)))
        Checks.insert {
            it[tenantId] = "copperlantern"; it[venueId] = "vieux-port"; it[checkId] = id; it[status] = "CLOSED"
            it[closedAt] = closed; it[grandTotalCents] = qty * unit
        }
        CheckLines.insert {
            it[tenantId] = "copperlantern"; it[venueId] = "vieux-port"; it[CheckLines.checkId] = id; it[itemId] = item
            it[CheckLines.qty] = qty; it[unitPriceCents] = unit; it[lineTotalCents] = qty * unit
        }
    }

    private val days = generateSequence(today.minusDays(90)) { it.plusDays(1) }.takeWhile { it < today }.toList()
    private fun LocalDate.weekday() = dayOfWeek.value <= 5

    /**
     * Every day of the last 90: burgers 5 on Tuesdays, 2 on other weekdays, 10 at weekends;
     * lagers 3 at 17:00 every day (happy hour on weekdays: no lift) and 10 at 20:00;
     * wings 1 at 17:00 on weekdays and 2 at noon every day.
     */
    private fun seedSales() {
        for (d in days) {
            sale(d, 13, "burger", when { d.dayOfWeek.value == 2 -> 5; d.weekday() -> 2; else -> 10 }, 1245)
            sale(d, 17, "lager", 3, 825)
            sale(d, 20, "lager", 10, 825)
            if (d.weekday()) sale(d, 17, "wings", 1, 1600)
            sale(d, 12, "wings", 2, 1600)
        }
    }

    private suspend fun ApplicationTestBuilder.post(path: String, body: String) = client.post(path) {
        header(HttpHeaders.Cookie, "pos_portal_session=$owner"); contentType(ContentType.Application.Json); setBody(body)
    }

    private suspend fun ApplicationTestBuilder.chat(text: String): JsonObject {
        val r = post("/v1/menu-ai/chat?venue=vieux-port", buildJsonObject { put("text", text); put("lang", "en") }.toString())
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return testJson.parseToJsonElement(r.bodyAsText()).jsonObject
    }

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content
    private fun JsonObject.list(k: String) = this[k]!!.jsonArray.map { it.jsonObject }

    @Test
    fun howTheSpecialsAreDoing() = testApplication {
        setUpStore()
        fake.reply = """{"summary":"x","ops":[{"op":"sales_select","rank":"specials","then":{"do":"answer"}}]}"""
        val p = chat("How is my Tuesday burger special doing?")
        assertEquals("true", p.s("answer"))
        assertEquals(0, p.list("changes").size)
        val b = p.list("sales").single()
        // 8 weeks ending yesterday
        assertEquals("2026-08-08", b.s("from")); assertEquals("2026-10-02", b.s("to"))
        val rows = b.list("rows").associateBy { it.s("itemId") }
        val burger = rows.getValue("burger")
        assertEquals("Tue", burger.s("special"))
        assertEquals("5.0", burger.s("onAvg")); assertEquals("2.0", burger.s("offAvg"))
        assertEquals("weekdays", burger.s("baseline")); assertEquals("150", burger.s("liftPct"))
        // happy hour Mon–Fri 4–6: the same hours at the weekend sell the same → no lift
        val lager = rows.getValue("lager")
        assertEquals("Happy hour · Mon–Fri 16:00–18:00", lager.s("special"))
        assertEquals("other_days", lager.s("baseline")); assertEquals("0", lager.s("liftPct"))
        // poutine on Sundays never sold: too little to say
        assertEquals("false", rows.getValue("poutine").s("enough"))
        assertEquals(null, rows.getValue("poutine")["liftPct"]?.jsonPrimitive?.content?.takeIf { it != "null" })
        assertTrue(p.list("salesNotes").any { it.s("code") == "assumes_whole_period" })
        // the best first, too-little-data last
        assertEquals(listOf("burger", "lager", "poutine"), b.list("rows").map { it.s("itemId") })
    }

    @Test
    fun specialsThatDontWorkAreProposedForRemoval() = testApplication {
        setUpStore()
        fake.reply = """{"summary":"End weak specials","ops":[{"op":"sales_select","rank":"specials","then":{"do":"end_weak"}}]}"""
        val p = chat("End specials that aren't working")
        assertEquals("false", p.s("answer"))
        val changes = p.list("changes")
        // only the happy hour (0% over the last 4 weeks): the burger's works, the poutine's has too little data
        assertEquals(listOf("Lantern House Lager"), changes.map { it.s("title") })
        val d = changes.single().list("details").single()
        assertEquals("500", d.s("beforeMinor")); assertEquals("825", d.s("afterMinor"))
        val b = p.list("sales").single()
        assertEquals("2026-09-05", b.s("from"))
        assertEquals("lager", b.list("rows").first().s("itemId"))
    }

    @Test
    fun whatSellsDuringHappyHour() = testApplication {
        setUpStore()
        fake.reply = """{"summary":"x","ops":[{"op":"sales_select","rank":"happy_hour","n":5,"then":{"do":"answer"}}]}"""
        val p = chat("What sells during happy hour?")
        assertEquals("true", p.s("answer"))
        val b = p.list("sales").single()
        assertEquals("Mon–Fri 16:00–18:00", b.s("special"))
        val win = days.filter { it >= today.minusDays(30) }
        val wd = win.count { it.weekday() }
        val we = win.size - wd
        val rows = b.list("rows")
        assertEquals(listOf("lager", "wings"), rows.map { it.s("itemId") })
        assertEquals((wd * 3).toString(), rows[0].s("units"))
        assertEquals(Math.round(wd * 3 * 100.0 / (win.size * 13)).toString(), rows[0].s("sharePct"))
        assertEquals(wd.toString(), rows[1].s("units"))
        assertEquals(Math.round(wd * 100.0 / (wd * 3 + we * 2)).toString(), rows[1].s("sharePct"))
    }

    @Test
    fun hoursAndBaselines() {
        assertEquals(setOf(16, 17), SalesInsights.hours("16:00", "18:00"))
        assertEquals(setOf(16, 17), SalesInsights.hours("16:30", "17:45"))
        assertEquals(setOf(22, 23, 0, 1), SalesInsights.hours("22:00", "02:00"))
        assertEquals(24, SalesInsights.hours(null, null).size)
        assertEquals("weekdays" to listOf("mon", "wed", "thu", "fri"), SalesInsights.baseline(listOf("tue")))
        assertEquals("weekend" to listOf("sun"), SalesInsights.baseline(listOf("sat")))
        assertEquals("other_days" to listOf("sat", "sun"), SalesInsights.baseline(listOf("mon", "tue", "wed", "thu", "fri")))
        assertEquals(null, SalesInsights.baseline(listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")))
        assertFalse(SalesInsights.WEAK_LIFT_PCT <= 0)
    }
}
