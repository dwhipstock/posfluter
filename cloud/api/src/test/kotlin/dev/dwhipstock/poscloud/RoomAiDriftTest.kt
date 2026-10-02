package dev.dwhipstock.poscloud

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The portal's room assistant runs the store's floor-plan AI (prompts,
 * room-from-picture rules, parsers, references, skipped lines): each
 * "PORT BEGIN / PORT END" block in cloud rooms/RoomAiPort.kt must match the
 * store's source exactly, so a fix on one side can't silently miss the other
 * (#95/#96 — sub-table links are not locks, verbatim transcripts, replies in
 * the spoken language — live in these blocks).
 */
class RoomAiDriftTest {

    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "server").isDirectory && File(it, "cloud").isDirectory }

    private val store = "server/src/main/kotlin/dev/dwhipstock/pos/"
    private val aimenu = store + "aimenu/"

    /** Block name → (store file, from, to): the store's source from `from` up to (not including) `to`. */
    private val blocks = mapOf(
        "FloorObjects.kt: types, icons, shapes" to Triple(store + "api/FloorObjects.kt", "val FLOOR_OBJECT_TYPES = setOf(", "@Serializable\ndata class FloorObjectCreateRequest("),
        "RoomLayoutAi.kt: the proposal DTOs" to Triple(aimenu + "RoomLayoutAi.kt", "/** A proposed table, shaped like", "@Serializable\ndata class RoomLayoutProposalDto("),
        "RoomLayoutAi.kt: Box and RoomLayoutRules" to Triple(aimenu + "RoomLayoutAi.kt", "/** An axis-aligned box", "internal object RoomLayoutAi {"),
        "RoomLayoutAi.kt: the prompt and the parser" to Triple(aimenu + "RoomLayoutAi.kt", "    private val json = Json { isLenient = true }\n\n    fun systemPrompt(bilingual: Boolean)", "    // --- the room now (call inside a transaction) ---"),
        "RoomLayoutAi.kt: objectTitle" to Triple(aimenu + "RoomLayoutAi.kt", "    fun objectTitle(type: String, label: String?)", "    fun roomNow("),
        "FloorEditAi.kt: AiLangs and AiVoice" to Triple(aimenu + "FloorEditAi.kt", "internal class AiLangs(", "/** One line of the floor assistant's change list. */"),
        "FloorEditAi.kt: FloorOp" to Triple(aimenu + "FloorEditAi.kt", "/** What the floor assistant may do to the current room. */", "/**\n * The floor-plan \"Ask AI\" assistant"),
        "FloorEditAi.kt: the limits and the prompt" to Triple(aimenu + "FloorEditAi.kt", "internal object FloorEditAi {", "    // --- the room now (inside a transaction) ---"),
        "FloorEditAi.kt: references" to Triple(aimenu + "FloorEditAi.kt", "    private fun key(s: String)", "    private fun tableDto(r: ResultRow)"),
        "FloorEditAi.kt: the parser" to Triple(aimenu + "FloorEditAi.kt", "    class Parsed(val ops: List<FloorOp>", "    // --- the plan: ops checked against the room now"),
        "FloorEditAi.kt: relabel" to Triple(aimenu + "FloorEditAi.kt", "    /** \"P-41\" + 40 → \"P-40\"", "    fun plan(zoneId: String"),
        "AiText.kt" to Triple(aimenu + "AiText.kt", "internal object AiText {", "\u0000"),
    )

    private fun storeSegment(path: String, from: String, to: String): String {
        val src = File(repo, path).readText()
        val i = src.indexOf(from)
        check(i >= 0) { "'${from.take(40)}' not found in $path: the store's source moved — update the block's cut points" }
        val j = if (to == "\u0000") src.length else src.indexOf(to, i).also { check(it > i) { "end of '${from.take(40)}' not found in $path" } }
        return src.substring(i, j).trimEnd()
    }

    @Test
    fun everyPortedBlockMatchesTheStoresSource() {
        val port = File(repo, "cloud/api/src/main/kotlin/dev/dwhipstock/poscloud/rooms/RoomAiPort.kt").readText()
        val names = Regex("// ---- PORT BEGIN: (.+) ----").findAll(port).map { it.groupValues[1] }.toList()
        assertEquals(blocks.keys.sorted(), names.sorted(), "every block in RoomAiPort.kt has cut points here, and only those")
        for ((name, cut) in blocks) {
            val begin = "// ---- PORT BEGIN: $name ----\n"
            val body = port.substringAfter(begin).substringBefore("\n// ---- PORT END: $name ----")
            assertEquals(storeSegment(cut.first, cut.second, cut.third), body,
                "cloud rooms/RoomAiPort.kt '$name' drifted from the store's ${cut.first}: copy the change across")
        }
    }

    @Test
    fun theFixesThatMatterAreInThePort() {
        val port = File(repo, "cloud/api/src/main/kotlin/dev/dwhipstock/poscloud/rooms/RoomAiPort.kt").readText()
        // #95/#96: transcripts are never translated, and the summary is in the request's language
        assertTrue("NEVER translate the transcript" in port)
        assertTrue("Write \"summary\" in that SAME language" in port)
        // the floor assistant: an open bill is the only lock
        assertTrue("Tables with \"openBill\": true have guests" in port)
        // several photos of one room: merged by shared landmarks, each table once (the user line too)
        assertTrue("landmarks they share (the bar, the walls and corners, doors, windows, pillars)" in port)
        assertTrue("fun userPrompt(count: Int)" in port)
    }
}
