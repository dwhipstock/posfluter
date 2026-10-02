package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.menuprint.MenuKind
import dev.dwhipstock.poscloud.menuprint.PrintAi
import dev.dwhipstock.poscloud.menuprint.PrintCatalog
import dev.dwhipstock.poscloud.menuprint.PrintSelect
import dev.dwhipstock.poscloud.menuprint.ReplyJson
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real-size menus (the 63-item pub, [PubMenuFixture]): a compact prompt, a
 * reply of realistic size planned in full, and a reply that was cut off or
 * wrapped in prose or fences recovered instead of thrown away.
 */
class MenuPrintBigMenuTest {
    private val c = PrintCatalog.of(PubMenuFixture.menu, "en")
    private val own = listOf("Copper Lantern", "Copper Lantern — Vieux-Port")

    /** What the model sends for a full menu: one section per category (no item lists), 25 blurbs. */
    private fun realisticReply(): String {
        val sections = c.categories.joinToString(",") { cat ->
            """{"category":"${cat.id}","title":"${cat.enName}","intro":"Made with care, poured with pride, every single night.","motif":"${cat.enName.lowercase()}"}"""
        }
        val blurbs = c.items.take(25).joinToString(",") { """"${it.id}":"A crowd favourite, generous and full of flavour, made fresh every day."""" }
        return """{"style":"classic","title":"The House Menu","tagline":"Hearty plates and good pours.","art":"a pub table with food and drink",
            "sections":[$sections],"blurbs":{$blurbs},"footer":"Please tell us about any allergies."}"""
    }

    @Test
    fun theFixtureIsRealSize() {
        assertEquals(63, c.items.size)
        assertEquals(34, PrintSelect.candidates(MenuKind.DRINKS, c, "mon").size)
        assertEquals(62, PrintSelect.candidates(MenuKind.TODAY, c, "mon").size) // steak frites is Fri & Sat only
    }

    @Test
    fun thePromptIsCompactForABigMenu() {
        val data = PrintAi.menuData(c, c.items, "en", complete = true)
        assertTrue(data.length < 10_000, "${data.length} chars") // ~2.5k tokens for 63 items
        assertFalse("\"sizes\"" in data || "\"photo\"" in data)
        val system = PrintAi.system(MenuKind.FULL, "en", "English", null, true, PrintAi.copyBudget(63))
        assertTrue("NO \"items\" lists" in system && "write one for 25 items" in system)
        assertEquals(7, PrintAi.copyBudget(7))
        assertEquals(PrintAi.MAX_BLURBS, PrintAi.copyBudget(80))
    }

    @Test
    fun aRealisticReplyPlansEveryItemByCategory() {
        for (kind in listOf(MenuKind.FULL, MenuKind.TODAY, MenuKind.DRINKS)) {
            val cands = PrintSelect.candidates(kind, c, "mon")
            val o = PrintAi.parseOutcome(realisticReply(), kind, c, cands, "en", own)
            val plan = assertNotNull(o.plan, kind.code).plan
            assertEquals("ok", o.reason)
            assertEquals(cands.map { it.id }.toSet(), plan.itemIds.toSet(), "$kind: every item printed")
            assertEquals(plan.itemIds.size, plan.itemIds.toSet().size)
            // the code's grouping: the menu's category order, the AI's section words
            assertEquals(plan.sections.map { it.categoryId }, c.categories.map { it.id }.filter { id -> cands.any { it.categoryId == id } })
            assertTrue(plan.sections.all { it.intro != null })
            assertTrue(plan.blurbs.size <= PrintAi.MAX_BLURBS)
        }
    }

    @Test
    fun aCutOffReplyKeepsWhatParsed() {
        val full = realisticReply()
        val cut = full.substring(0, full.indexOf("\"blurbs\"") + 600) // stops in the middle of a blurb
        val o = PrintAi.parseOutcome(cut, MenuKind.FULL, c, c.items, "en", own)
        assertEquals("repaired_truncated", o.reason)
        val plan = assertNotNull(o.plan).plan
        assertEquals("The House Menu", plan.title)
        assertEquals(63, plan.itemIds.size)
        assertTrue(plan.blurbs.size in 1..24, "${plan.blurbs.size} blurbs kept")
        assertTrue(plan.sections.all { it.intro != null })
        // cut in the sections: the title and the sections that came through
        val early = full.substring(0, full.indexOf("\"sections\"") + 300)
        val e = assertNotNull(PrintAi.parseOutcome(early, MenuKind.FULL, c, c.items, "en", own).plan).plan
        assertEquals("The House Menu", e.title)
        assertEquals(63, e.itemIds.size) // every item still prints (the code's own sections for the rest)
        assertNull(PrintAi.parseOutcome("{\"tit", MenuKind.FULL, c, c.items, "en").plan)
    }

    @Test
    fun wrappedOrSloppyJsonIsRead() {
        assertEquals("ok_wrapped", ReplyJson.read("Here is your menu:\n```json\n{\"title\":\"A\"}\n```").how)
        assertEquals("ok_trailing", ReplyJson.read("{\"title\":\"A\"}\nHope you like it!").how)
        // what broke the hosted drinks menu: a stray closing brace after the object
        assertEquals("ok_trailing", ReplyJson.read("{\n  \"title\": \"Drinks\"\n}\n}").how)
        assertEquals("repaired_commas", ReplyJson.read("{\"title\":\"A\",\"sections\":[{\"category\":\"x\",},],}").how)
        val t = ReplyJson.read("{\"title\":\"A, \\\"B\\\" {c}\",\"blurbs\":{\"x\":\"one\",\"y\":\"tw")
        assertEquals("repaired_truncated", t.how)
        assertEquals("A, \"B\" {c}", (t.root!!["title"] as JsonPrimitive).content)
        assertEquals("not_json", ReplyJson.read("I can't help with that.").how)
        assertEquals("empty", ReplyJson.read("  ").how)
        assertEquals("not_object", ReplyJson.read("[1,2]").how)
    }

    @Test
    fun theWriterAsksForLittleThinkingARoomyReplyAndFallsBackToLow() {
        val bodies = mutableListOf<String>()
        val http = dev.dwhipstock.poscloud.menuai.AiHttp { _, _, body, _ ->
            val b = body.decodeToString(); bodies += b
            if ("\"minimal\"" in b) 400 to "{}"
            else 200 to """{"status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"{\"title\":\"A\"}"}]}]}"""
        }
        val m = dev.dwhipstock.poscloud.menuai.GeminiMenuModel("test-key-not-real", "gemini-3.5-flash", http = http,
            thinkingLevel = PrintAi.THINKING, maxOutputTokens = PrintAi.MAX_OUTPUT_TOKENS, temperature = 0.9)
        assertEquals("{\"title\":\"A\"}", m.complete("s", "u", null))
        assertEquals(2, bodies.size)
        assertTrue("\"thinking_level\":\"minimal\"" in bodies[0] && "\"max_output_tokens\":8192" in bodies[0])
        assertTrue("\"thinking_level\":\"low\"" in bodies[1])
    }

    @Test
    fun aVariantOfTheVenuesNameIsNotAllowed() {
        assertTrue(PrintAi.namesABusiness("The Copper Tavern Menu", own))
        assertFalse(PrintAi.namesABusiness("Copper Lantern Classics", own))
        assertFalse(PrintAi.namesABusiness("Friday at the Pub", own))
    }
}
