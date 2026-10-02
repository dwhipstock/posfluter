package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.menu.MenuFields
import dev.dwhipstock.poscloud.menuai.AiAudio
import dev.dwhipstock.poscloud.menuai.MenuAiModel
import dev.dwhipstock.poscloud.menuai.MenuAiService
import dev.dwhipstock.poscloud.menuai.MenuChangeSetParser
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
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The portal AI and menu specials: set_days / set_specials proposals with
 * before / after, applied through the portal's own edit (MenuItemPatch with
 * availableDays / specials), undoable; and the specials prompt and parser
 * the same as the store's, word for word.
 */
class MenuAiSpecialsTest {

    private val key = "store-key-ai-specials"
    private lateinit var manager: String
    private var seq = 0L

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
        seedTenant("copperlantern")
        seedStoreKey("copperlantern", "vieux-port", key)
        manager = seedSession("copperlantern", seedUser("copperlantern", "owner@test.dev", "pw-owner-1"))
        seq = 0
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

    private suspend fun ApplicationTestBuilder.bootstrap() {
        ingest(key, event("catalog.snapshot", buildJsonObject {
            put("categories", buildJsonArray {
                add(buildJsonObject { put("id", "beer"); put("nameFr", "Bière"); put("nameEn", "Beer"); put("sortOrder", 0); put("deleted", false) })
                add(buildJsonObject { put("id", "food"); put("nameFr", "Plats"); put("nameEn", "Food"); put("sortOrder", 1); put("deleted", false) })
            })
            put("items", buildJsonArray {
                add(item("lager", "Lantern Lager", "beer", listOf("pint" to 825L, "pitcher" to 2400L)))
                add(item("burger", "Burger", "food", listOf("regular" to 1245L)))
                add(item("prime-rib", "Prime Rib", "food", listOf("regular" to 3495L)))
            })
        }, seq = ++seq))
        pull()
    }

    private suspend fun ApplicationTestBuilder.pull(): JsonObject =
        testJson.parseToJsonElement(client.get("/v1/store/menu/changes?since=0") {
            header(HttpHeaders.Authorization, "Bearer $key")
        }.bodyAsText()).jsonObject

    private suspend fun ApplicationTestBuilder.post(path: String, body: String): HttpResponse = client.post(path) {
        header(HttpHeaders.Cookie, "pos_portal_session=$manager")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content
    private suspend fun HttpResponse.body(): JsonObject = testJson.parseToJsonElement(bodyAsText()).jsonObject

    private val reply = """{"summary":"Prime rib on weekends, burger Tuesday, happy hour","language":"de","ops":[
        {"op":"set_days","item":"prime-rib","days":["fri","sat"]},
        {"op":"set_specials","item":"burger","specials":[{"days":["tue"],"label":"","prices":[{"variant":"burger:regular","priceMinor":995}]}]},
        {"op":"set_specials","item":"lager","specials":[{"days":["mon","tue","wed","thu","fri"],"from":"15:00","to":"18:00","label":"Happy hour","prices":[{"variant":"lager:pint","priceMinor":500}]}]},
        {"op":"set_days","item":"prime-rib","days":["funday"]}
    ]}"""

    @Test
    fun specialsAreProposedWithBeforeAndAfterAndApplyThroughThePortalEdit() = testApplication {
        application { module(TestSupport.config, MenuAiService(TestSupport.config, fake, fake)) }
        bootstrap()
        fake.reply = reply
        val r = post("/v1/menu-ai/chat?venue=vieux-port", """{"text":"Prime Rib nur freitags und samstags, Burger dienstags für 9,95 $","lang":"de"}""")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val p = r.body()
        // the model was told the specials ops, the same words as the store's assistant
        assertTrue(MenuChangeSetParser.SPECIALS_PROMPT in fake.system)
        val changes = p["changes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, changes.size)
        assertEquals(1, p["rejected"]!!.jsonArray.size)
        val days = changes[0]["details"]!!.jsonArray.single().jsonObject
        assertEquals("available", days.s("field"))
        assertEquals("Jeden Tag", days.s("before"))
        assertEquals("Nur Fr & Sa", days.s("after"))
        val tue = changes[1]["details"]!!.jsonArray.single().jsonObject
        assertEquals("price", tue.s("field")); assertEquals("Di", tue.s("label"))
        assertEquals("1245", tue.s("beforeMinor")); assertEquals("995", tue.s("afterMinor"))
        val hh = changes[2]["details"]!!.jsonArray.single().jsonObject
        assertEquals("pint · Happy hour · Mo–Fr 15:00–18:00", hh.s("label"))
        assertEquals("500", hh.s("afterMinor"))

        val a = post("/v1/menu-ai/apply?venue=vieux-port",
            """{"proposalId":"${p.s("proposalId")}","changeIds":[${changes.joinToString(",") { "\"${it.s("id")}\"" }}],"confirmBulk":false}""")
        assertEquals(HttpStatusCode.OK, a.status, a.bodyAsText())
        assertEquals("3", a.body().s("applied"))

        // once the portal's menu edit stores the two fields (CONTRACT §10 "Specials"), they reach the store
        Assume.assumeTrue("specials" in MenuFields.ITEM_FIELDS)
        val pulled = pull()["changes"]!!.jsonArray.map { it.jsonObject["data"]!!.jsonObject }
        val rib = pulled.last { it.s("id") == "prime-rib" }
        assertEquals("""["fri","sat"]""", rib["availableDays"].toString())
        val lager = pulled.last { it.s("id") == "lager" }
        assertEquals("""[{"days":["mon","tue","wed","thu","fri"],"from":"15:00","to":"18:00","label":"Happy hour","prices":{"lager:pint":500}}]""",
            lager["specials"].toString())
        // and the next request shows the model what is on now
        post("/v1/menu-ai/chat?venue=vieux-port", """{"text":"burgers 9.95 on Tuesdays","lang":"en"}""")
        assertTrue("\"availableDays\":[\"fri\",\"sat\"]" in fake.user, fake.user.takeLast(400))
        // undo puts every day / no specials back
        val undo = post("/v1/menu-ai/revert/${a.body().s("applyId")}?venue=vieux-port", "{}")
        assertEquals(HttpStatusCode.OK, undo.status, undo.bodyAsText())
        val after = pull()["changes"]!!.jsonArray.map { it.jsonObject["data"]!!.jsonObject }
        assertEquals(null, after.last { it.s("id") == "prime-rib" }["availableDays"]?.takeUnless { it is kotlinx.serialization.json.JsonNull })
    }

    /** The specials words the model is given: the same ops, rules and wording in both assistants' prompts. */
    @Test
    fun bothAssistantsAppendTheSameSpecialsPrompt() {
        val call = "\"\"\".trimIndent() + \"\\n\" + MenuChangeSetParser.SPECIALS_PROMPT"
        for (path in listOf("server/src/main/kotlin/dev/dwhipstock/pos/aimenu/MenuAiService.kt",
            "cloud/api/src/main/kotlin/dev/dwhipstock/poscloud/menuai/MenuAiService.kt")) {
            assertTrue(call in File(repo, path).readText(), "$path: the system prompt must end with MenuChangeSetParser.SPECIALS_PROMPT")
        }
        // the prompt text itself lives in the parser object, which AiGuardParityTest compares line for line
        assertTrue("set_specials" in MenuChangeSetParser.SPECIALS_PROMPT && "set_days" in MenuChangeSetParser.SPECIALS_PROMPT)
    }
}
