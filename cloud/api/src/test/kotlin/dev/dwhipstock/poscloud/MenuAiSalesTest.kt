package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.CheckLines
import dev.dwhipstock.poscloud.db.Checks
import dev.dwhipstock.poscloud.db.MenuAiLog
import dev.dwhipstock.poscloud.db.Venues
import dev.dwhipstock.poscloud.menuai.AiAudio
import dev.dwhipstock.poscloud.menuai.MenuAiModel
import dev.dwhipstock.poscloud.menuai.MenuAiService
import dev.dwhipstock.poscloud.menuai.MenuChangeSetParser
import dev.dwhipstock.poscloud.menuai.salesPrice
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
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The portal assistant's sales knowledge (menuai/MenuAiSales.kt) with a fake
 * model: the server recomputes every ranking from the store's sales (the same
 * rows and business days as the Items report), turns relative prices into
 * per-size specials, answers questions without changing anything, and never
 * sees another store's or tenant's sales.
 */
class MenuAiSalesTest {

    private val key = "store-key-ai-sales"
    private lateinit var owner: String
    private var seq = 0L
    private var checkId = 1000

    /** Saturday 2026-10-03, 15:00 in New York: "the last 30 days" are Sep 3 – Oct 2. */
    private val now = Instant.parse("2026-10-03T19:00:00Z").toEpochMilli()

    private class FakeModel(var reply: String = """{"ops":[]}""") : MenuAiModel {
        override val id = "fake"
        override val model = "fake-model"
        var system = ""
        var user = ""
        override fun complete(system: String, user: String, audio: AiAudio?): String {
            this.system = system; this.user = user
            return reply
        }
    }

    private val fake = FakeModel()
    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "server").isDirectory && File(it, "cloud").isDirectory }

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("copperlantern", "vieux-port", "Copper Lantern — Glenwood South")
        seedTenant("copperlantern", "plateau", "Copper Lantern — Plateau")
        seedTenant("other-tenant", "vieux-port", "Someone else")
        seedStoreKey("copperlantern", "vieux-port", key)
        owner = seedSession("copperlantern", seedUser("copperlantern", "owner@sales.test", "pw-owner-1"))
        seq = 0
    }

    private fun ApplicationTestBuilder.app() =
        application { module(TestSupport.config, MenuAiService(TestSupport.config, fake, fake, now = { now }, callsMax = 100)) }

    private fun item(id: String, en: String, fr: String, cat: String, sizes: List<Pair<String, Long>>) = buildJsonObject {
        put("id", id); put("nameFr", fr); put("nameEn", en)
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

    private suspend fun ApplicationTestBuilder.bootstrap() {
        ingest(key, event("catalog.snapshot", buildJsonObject {
            put("categories", buildJsonArray {
                add(buildJsonObject { put("id", "beer"); put("nameFr", "Bière"); put("nameEn", "Beer"); put("sortOrder", 0); put("deleted", false) })
                add(buildJsonObject { put("id", "food"); put("nameFr", "Plats"); put("nameEn", "Food"); put("sortOrder", 1); put("deleted", false) })
            })
            put("items", buildJsonArray {
                add(item("lager", "Lantern House Lager", "Lager maison", "beer", listOf("pint" to 825L, "pitcher" to 2400L)))
                add(item("ipa", "Hazy IPA", "IPA trouble", "beer", listOf("pint" to 900L)))
                add(item("stout", "Oatmeal Stout", "Stout à l'avoine", "beer", listOf("pint" to 875L)))
                add(item("porter", "Robust Porter", "Porter robuste", "beer", listOf("pint" to 850L)))
                add(item("burger", "Copper Burger", "Burger cuivré", "food", listOf("regular" to 1245L)))
                add(item("wings", "Wings", "Ailes", "food", listOf("regular" to 1600L)))
                add(item("poutine", "Poutine", "Poutine", "food", listOf("regular" to 1400L)))
                add(item("fries", "Side Fries", "Frites", "food", listOf("regular" to 199L)))
                add(item("salad", "Garden Salad", "Salade du jardin", "food", listOf("regular" to 1100L)))
            })
        }, seq = ++seq))
        // the store pulls once: it speaks two-way sync, so the portal's edits reach it
        client.get("/v1/store/menu/changes?since=0") { header(HttpHeaders.Authorization, "Bearer $key") }
    }

    /** One closed (or void) check of [venue] at [at] with [lines] (item, qty, unit price). */
    private fun sale(at: String, vararg lines: Triple<String, Int, Long>, tenant: String = "copperlantern", venue: String = "vieux-port", status: String = "CLOSED") =
        transaction {
            val id = ++checkId
            val closed = OffsetDateTime.parse(at)
            Checks.insert {
                it[tenantId] = tenant; it[venueId] = venue; it[checkId] = id; it[Checks.status] = status
                it[openedAt] = closed.minusMinutes(40); it[closedAt] = closed
                it[grandTotalCents] = lines.sumOf { l -> l.second * l.third }
            }
            for ((item, qty, unit) in lines) CheckLines.insert {
                it[tenantId] = tenant; it[venueId] = venue; it[CheckLines.checkId] = id; it[itemId] = item
                it[CheckLines.qty] = qty; it[unitPriceCents] = unit; it[lineTotalCents] = qty * unit
            }
        }

    /**
     * Last 30 days (Sep 3 – Oct 2): lager 50, burger 40 and IPA 40 (burger first: more revenue),
     * wings 30, poutine 20 and fries 20 (poutine first), salad 2 (its boundary sales), stout and
     * porter nothing. Plus: noise that must never count.
     */
    private fun seedSales() {
        sale("2026-09-28T19:00:00-04:00", Triple("lager", 30, 825L), Triple("burger", 20, 1245L), Triple("ipa", 25, 900L))
        sale("2026-10-01T20:00:00-04:00", Triple("lager", 20, 825L), Triple("burger", 20, 1245L), Triple("ipa", 15, 900L),
            Triple("wings", 30, 1600L), Triple("poutine", 20, 1400L), Triple("fries", 20, 199L))
        // business-day edges in New York: Oct 2 23:30 counts, Oct 3 00:30 is today (not in the window, but "last sold")
        sale("2026-10-02T23:30:00-04:00", Triple("salad", 1, 1100L))
        sale("2026-10-03T00:30:00-04:00", Triple("salad", 1, 1100L))
        // Sep 3 00:30 is the window's first day; Sep 2 23:30 is before it (but inside 90 days)
        sale("2026-09-03T00:30:00-04:00", Triple("salad", 1, 1100L))
        sale("2026-09-02T23:30:00-04:00", Triple("salad", 1, 1100L))
        // never counted: a void check, another store of this tenant, another tenant's store with the same id
        sale("2026-10-01T21:00:00-04:00", Triple("stout", 500, 875L), status = "VOID")
        sale("2026-10-01T21:00:00-04:00", Triple("stout", 900, 875L), venue = "plateau")
        sale("2026-10-01T21:00:00-04:00", Triple("stout", 800, 875L), Triple("secret-dish", 70, 999L), tenant = "other-tenant")
    }

    private suspend fun ApplicationTestBuilder.chat(text: String, lang: String = "en"): JsonObject {
        val r = client.post("/v1/menu-ai/chat?venue=vieux-port") {
            header(HttpHeaders.Cookie, "pos_portal_session=$owner")
            contentType(ContentType.Application.Json)
            setBody("""{"text":${kotlinx.serialization.json.JsonPrimitive(text)},"lang":"$lang"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return testJson.parseToJsonElement(r.bodyAsText()).jsonObject
    }

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content
    private fun JsonObject.list(k: String) = this[k]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.notes() = list("salesNotes").map { it.s("code")!! }
    private fun JsonObject.basis() = list("sales").single()
    private fun JsonObject.rows() = basis().list("rows").map { it.s("itemId") to it.s("units")!!.toLong() }

    private val top5TuesdayWrongPick = """{"summary":"Top 5 sellers 2 dollars off on Tuesdays","ops":[
        {"op":"sales_select","rank":"top","by":"units","days":30,"n":5,"items":["stout","salad","fries","lager","wings"],
         "then":{"do":"special","days":["tue"],"price":{"change_minor":-200},"sizes":"all"}},
        {"op":"set_specials","item":"stout","specials":[{"days":["tue"],"label":"","prices":[{"variant":"stout:pint","priceMinor":100}]}]}
    ]}"""

    @Test
    fun topFiveIsRecomputedAndOverridesAWrongPick() = testApplication {
        app(); bootstrap(); seedSales()
        transaction { Venues.update({ Venues.id eq "vieux-port" }) { it[name] = "Copper Lantern — Glenwood South" } }
        fake.reply = top5TuesdayWrongPick
        val p = chat("Take my top 5 selling items for the last 30 days and make them \$2.00 cheaper on Tuesdays")
        // the model saw this store's sales as data, never another store's or tenant's
        assertTrue("<sales_data>" in fake.user && "lager|Lantern House Lager|Beer|50|41250|50|41250|50|41250|2026-10-01" in fake.user, fake.user)
        assertTrue("stout|Oatmeal Stout|Beer|0|0|0|0|0|0|-" in fake.user, fake.user)
        assertFalse("secret-dish" in fake.user)
        assertTrue("sales_select" in fake.system)

        val basis = p.basis()
        assertEquals("top", basis.s("rank")); assertEquals("units", basis.s("by"))
        assertEquals("2026-09-03", basis.s("from")); assertEquals("2026-10-02", basis.s("to"))
        assertEquals("Copper Lantern — Glenwood South", basis.s("store"))
        // ties: burger and IPA both 40 (burger has more revenue), poutine and fries both 20 (poutine has more)
        assertEquals(listOf("lager" to 50L, "burger" to 40L, "ipa" to 40L, "wings" to 30L, "poutine" to 20L), p.rows())
        assertTrue("pick_corrected" in p.notes())
        // the model's own (wrong) summary and its stout special are gone
        assertEquals("", p.s("summary"))
        val changes = p.list("changes")
        assertEquals(listOf("Lantern House Lager", "Copper Burger", "Hazy IPA", "Wings", "Poutine"), changes.map { it.s("title") })
        // $2.00 off every size, on Tuesdays
        val lager = changes[0].list("details")
        assertEquals(listOf("pint · Tue" to "625", "pitcher · Tue" to "2200"), lager.map { it.s("label") to it.s("afterMinor") })
        assertEquals("1045", changes[1].list("details").single().s("afterMinor"))
        assertEquals("false", p.s("bulk"))

        // applies and reverts like any other proposal
        val a = client.post("/v1/menu-ai/apply?venue=vieux-port") {
            header(HttpHeaders.Cookie, "pos_portal_session=$owner"); contentType(ContentType.Application.Json)
            setBody("""{"proposalId":"${p.s("proposalId")}","changeIds":[${changes.joinToString(",") { "\"${it.s("id")}\"" }}]}""")
        }
        assertEquals(HttpStatusCode.OK, a.status, a.bodyAsText())
        val applyId = testJson.parseToJsonElement(a.bodyAsText()).jsonObject.s("applyId")
        val undo = client.post("/v1/menu-ai/revert/$applyId?venue=vieux-port") {
            header(HttpHeaders.Cookie, "pos_portal_session=$owner"); contentType(ContentType.Application.Json); setBody("{}")
        }
        assertEquals(HttpStatusCode.OK, undo.status, undo.bodyAsText())
        // the audit log has the kind and outcome only
        val log = transaction { MenuAiLog.selectAll().map { it[MenuAiLog.kind] to it[MenuAiLog.outcome] } }
        assertEquals(listOf("chat" to "proposed", "apply" to "applied", "revert" to "reverted"), log)
    }

    @Test
    fun relativePricesPerSizeRefuseWhatWouldNotBeCheaper() = testApplication {
        app(); bootstrap(); seedSales()
        // "$2 off" fries ($1.99) would be at or below zero: refused, the lager still gets it
        fake.reply = """{"summary":"x","ops":[{"op":"sales_select","rank":"top","days":30,"n":2,"among":["fries","lager"],"items":["lager","fries"],
            "then":{"do":"special","days":["mon","tue","wed","thu","fri"],"from":"16:00","to":"18:00","label":"Happy hour","price":{"change_minor":-200}}}]}"""
        val p = chat("lager and fries \$2 off at happy hour Mon-Fri 4-6")
        assertEquals(listOf("Lantern House Lager"), p.list("changes").map { it.s("title") })
        assertEquals(listOf("pint · Happy hour · Mon–Fri 16:00–18:00" to "625", "pitcher · Happy hour · Mon–Fri 16:00–18:00" to "2200"),
            p.list("changes")[0].list("details").map { it.s("label") to it.s("afterMinor") })
        val refused = p.list("salesNotes").single { it.s("code") == "size_refused" }
        assertEquals("Side Fries", refused.s("item"))
        assertFalse("pick_corrected" in p.notes())

        // a flat "$5" on the smallest size only; a size already at or under $5 is refused
        fake.reply = """{"summary":"x","ops":[{"op":"sales_select","rank":"bottom","days":30,"n":3,"among":["lager","ipa","stout","porter"],
            "then":{"do":"special","days":["mon","tue","wed","thu","fri"],"from":"16:00","to":"18:00","label":"","price":{"to_minor":500},"sizes":"smallest"}}]}"""
        val slow = chat("Put our 3 slowest drinks on happy hour Mon–Fri 4–6 at \$5")
        // the slowest: stout and porter (nothing; a tie, so by name: "Oatmeal Stout", "Robust Porter"), then IPA (40)
        assertEquals(listOf("stout" to 0L, "porter" to 0L, "ipa" to 40L), slow.rows())
        assertEquals(listOf("500", "500", "500"), slow.list("changes").map { it.list("details").single().s("afterMinor") })

        // the menu price itself, up: an update_item
        fake.reply = """{"summary":"x","ops":[{"op":"sales_select","rank":"top","days":7,"n":1,"then":{"do":"price","price":{"change_percent":10,"round":"95"}}}]}"""
        val up = chat("raise my best seller of the week by 10%, ending in .95")
        val d = up.list("changes").single().list("details")
        // lager 8.25 → 9.08 → 8.95; 24.00 → 26.40 → 25.95
        assertEquals(listOf("895", "2595"), d.map { it.s("afterMinor") })
    }

    @Test
    fun priceMath() {
        fun spec(s: String) = testJson.parseToJsonElement(s).jsonObject
        assertEquals(625L, salesPrice(825, spec("""{"change_minor":-200}""")))
        assertEquals(-1L, salesPrice(199, spec("""{"change_minor":-200}""")))
        assertEquals(500L, salesPrice(825, spec("""{"to_minor":500}""")))
        // 20% off: to the cent, half up; .05 / .95 only when asked
        assertEquals(996L, salesPrice(1245, spec("""{"change_percent":-20}""")))
        assertEquals(995L, salesPrice(1245, spec("""{"change_percent":-20,"round":"nickel"}""")))
        assertEquals(1120L, salesPrice(1400, spec("""{"change_percent":-20,"round":"nickel"}""")))
        assertEquals(1095L, salesPrice(1400, spec("""{"change_percent":-20,"round":"95"}""")))
        assertEquals(660L, salesPrice(825, spec("""{"change_percent":-20}""")))
        // half up: 1.5 → 2
        assertEquals(2L,salesPrice(3, spec("""{"change_percent":-50}""")))
        // a currency without cents never rounds to .05 / .95
        assertEquals(800L, salesPrice(1000, spec("""{"change_percent":-20,"round":"95"}"""), digits = 0))
        // not a price spec
        assertEquals(null, salesPrice(1000, spec("""{"change_percent":"-20"}""")))
        assertEquals(null, salesPrice(1000, spec("""{"change_percent":-100}""")))
    }

    @Test
    fun windowsFollowTheStoresBusinessDays() = testApplication {
        app(); bootstrap(); seedSales()
        fake.reply = """{"summary":"x","ops":[{"op":"sales_select","rank":"list","days":30,"among":["salad"],"then":{"do":"answer"}}]}"""
        val p30 = chat("how many salads did we sell in the last 30 days?")
        // Oct 2 23:30 and Sep 3 00:30 (New York) count; Oct 3 00:30 is today, Sep 2 23:30 the day before the window
        assertEquals(listOf("salad" to 2L), p30.rows())
        assertEquals("2026-10-03", p30.basis().list("rows").single().s("lastSold"))
        // the table: u7 1 (Oct 2), u30 2, u90 3, last sold today
        assertTrue("salad|Garden Salad|Food|1|1100|2|2200|3|3300|2026-10-03" in fake.user, fake.user)
        fake.reply = """{"summary":"x","ops":[{"op":"sales_select","rank":"list","month":"2026-09","among":["salad"],"then":{"do":"answer"}}]}"""
        val sep = chat("salads last month?")
        assertEquals("2026-09-01", sep.basis().s("from")); assertEquals("2026-09-30", sep.basis().s("to"))
        assertEquals(listOf("salad" to 2L), sep.rows())
    }

    @Test
    fun unsoldItemsAreTakenOffAndTodaysSaleCounts() = testApplication {
        app(); bootstrap(); seedSales()
        fake.reply = """{"summary":"86 unsold","ops":[{"op":"sales_select","rank":"unsold","days":14,"items":["stout"],"then":{"do":"86"}}]}"""
        val p = chat("86 anything that hasn't sold in 2 weeks")
        // porter and stout: nothing in Sep 19 – Oct 2 and nothing today (the salad sold today: kept)
        assertEquals(listOf("porter", "stout"), p.rows().map { it.first }.sortedBy { it })
        assertEquals(14, p.basis().s("days")!!.toInt())
        assertEquals(listOf("false", "false"), p.list("changes").map { it.list("details").single().s("after") })
        assertTrue("pick_corrected" in p.notes())
    }

    @Test
    fun anEmptyWindowProposesNothing() = testApplication {
        app(); bootstrap() // no sales at all
        fake.reply = top5TuesdayWrongPick
        val p = chat("Take my top 5 selling items for the last 30 days and make them \$2.00 cheaper on Tuesdays")
        assertEquals(listOf("no_sales"), p.notes())
        assertEquals(0, p.list("changes").size)
        assertEquals("", p.s("proposalId"))
        assertEquals(null, p["refusal"]?.jsonPrimitive?.contentOrNullOrNull())
        // never "86 the whole menu" because nothing synced
        fake.reply = """{"summary":"x","ops":[{"op":"sales_select","rank":"unsold","days":14,"then":{"do":"86"}}]}"""
        val u = chat("86 anything that hasn't sold in 2 weeks")
        assertEquals(listOf("no_sales"), u.notes()); assertEquals(0, u.list("changes").size)
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullOrNull() = if (this is kotlinx.serialization.json.JsonNull) null else content

    @Test
    fun fewerItemsThanAskedUsesWhatExists() = testApplication {
        app(); bootstrap(); seedSales()
        fake.reply = """{"summary":"x","ops":[{"op":"sales_select","rank":"top","days":30,"n":5,"among":["lager","ipa","stout","porter"],
            "then":{"do":"answer"}}]}"""
        val p = chat("top 5 beers?")
        assertEquals(listOf("lager" to 50L, "ipa" to 40L), p.rows())
        val fewer = p.list("salesNotes").single { it.s("code") == "fewer_items" }
        assertEquals("2", fewer.s("n")); assertEquals("5", fewer.s("want"))
    }

    @Test
    fun questionsGetAnAnswerAndChangeNothing() = testApplication {
        app(); bootstrap(); seedSales()
        fake.reply = """{"summary":"The Copper Burger sold 999 last month","ops":[{"op":"sales_select","rank":"top","by":"revenue","days":30,"n":3,
            "among":["burger"],"items":["burger"],"then":{"do":"answer"}}]}"""
        val p = chat("Which burgers sold best last month?")
        assertEquals("true", p.s("answer"))
        assertEquals("", p.s("proposalId"))
        assertEquals(0, p.list("changes").size)
        // the model's numbers never reach the screen: only the server's
        assertEquals("", p.s("summary"))
        val row = p.basis().list("rows").single()
        assertEquals("40", row.s("units")); assertEquals("49800", row.s("revenueMinor"))
        assertEquals("revenue", p.basis().s("by"))
        val log = transaction { MenuAiLog.selectAll().map { it[MenuAiLog.kind] to it[MenuAiLog.outcome] } }
        assertEquals(listOf("chat" to "answered"), log)
    }

    @Test
    fun germanAndFrenchRequests() = testApplication {
        app(); bootstrap(); seedSales()
        fake.reply = top5TuesdayWrongPick.replace("Top 5 sellers 2 dollars off on Tuesdays", "Top 5 dienstags 2 \$ günstiger")
        val de = chat("Nimm meine 5 meistverkauften Artikel der letzten 30 Tage und mach sie dienstags 2,00 \$ billiger", "de")
        assertEquals(listOf("lager", "burger", "ipa", "wings", "poutine"), de.rows().map { it.first })
        assertEquals("pint · Di", de.list("changes")[0].list("details")[0].s("label"))
        assertEquals("Lantern House Lager", de.basis().list("rows")[0].s("name"))
        fake.reply = top5TuesdayWrongPick
        val fr = chat("Prends mes 5 articles les plus vendus des 30 derniers jours et baisse-les de 2,00 \$ le mardi", "fr")
        assertEquals(listOf("lager", "burger", "ipa", "wings", "poutine"), fr.rows().map { it.first })
        // names in the manager's language
        assertEquals("Lager maison", fr.basis().list("rows")[0].s("name"))
        assertEquals("pint · mar", fr.list("changes")[0].list("details")[0].s("label"))
    }

    @Test
    fun badSelectionsAreIgnoredNotGuessed() = testApplication {
        app(); bootstrap(); seedSales()
        fake.reply = """{"summary":"x","ops":[{"op":"sales_select","rank":"top","days":400,"then":{"do":"86"}}]}"""
        val p = chat("86 the top sellers of the last 400 days")
        assertEquals(listOf("bad_request"), p.notes())
        assertEquals(0, p.list("changes").size)
        fake.reply = """{"summary":"x","ops":[{"op":"sales_select","rank":"top","days":30,"among":["nope"],"then":{"do":"86"}}]}"""
        assertEquals(listOf("bad_request"), chat("86 my top sellers").notes())
    }

    /**
     * Drift: the sales op is the portal's own. The shared parser (compared line
     * for line with the store's by AiGuardParityTest) knows nothing of it, the
     * store's assistant has no such prompt, and the cloud's system prompt still
     * ends with the shared specials prompt.
     */
    @Test
    fun salesAreACloudOnlyAddition() {
        val store = File(repo, "server/src/main/kotlin/dev/dwhipstock/pos/aimenu/MenuAiService.kt").readText()
        val cloud = File(repo, "cloud/api/src/main/kotlin/dev/dwhipstock/poscloud/menuai/MenuAiService.kt").readText()
        val parser = File(repo, "cloud/api/src/main/kotlin/dev/dwhipstock/poscloud/menuai/MenuChangeSet.kt").readText()
        assertFalse("sales_select" in store || "sales_data" in store)
        assertFalse("sales" in parser.substringAfter("object MenuChangeSetParser").lowercase())
        assertFalse("sales_select" in MenuChangeSetParser.KNOWN_OPS)
        assertTrue("SalesAsks.prompt(today)" in cloud && "<sales_data>" in cloud)
        assertTrue("\"\"\".trimIndent() + \"\\n\" + MenuChangeSetParser.SPECIALS_PROMPT" in cloud)
    }
}
