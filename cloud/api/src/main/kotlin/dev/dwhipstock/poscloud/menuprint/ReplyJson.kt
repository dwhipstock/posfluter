package dev.dwhipstock.poscloud.menuprint

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The model's JSON, read as kindly as is safe: markdown fences or a line of
 * prose around it, trailing commas, and a reply cut off mid-way (the long
 * menus) are all recovered — a cut reply keeps every member that was
 * complete. [Read.how] names what happened, for the server log and
 * menu_ai_log (never the content): ok, ok_wrapped (prose or a fence before it), ok_trailing
 * (anything after its closing brace — Gemini sometimes adds a stray "}"), repaired_commas,
 * repaired_truncated, empty, not_json, not_object.
 */
object ReplyJson {
    class Read(val root: JsonObject?, val how: String)

    private val json = Json { isLenient = true }
    private val TRAILING_COMMA = Regex(",(\\s*[}\\]])")

    fun read(reply: String): Read {
        val t = reply.trim()
        if (t.isEmpty()) return Read(null, "empty")
        val start = t.indexOf('{')
        if (start < 0) return Read(null, if (t.trimStart().startsWith("[")) "not_object" else "not_json")
        val body = t.substring(start)
        val wrapped = start > 0 && t.substring(0, start).isNotBlank()
        // a complete object: up to its own closing brace (anything after it — a fence, a remark — is dropped)
        val end = closingBrace(body)
        if (end != null) {
            val whole = body.substring(0, end + 1)
            val trailingJunk = body.substring(end + 1).isNotBlank()
            parse(whole)?.let { return Read(it, if (wrapped) "ok_wrapped" else if (trailingJunk) "ok_trailing" else "ok") }
            parse(TRAILING_COMMA.replace(whole, "$1"))?.let { return Read(it, "repaired_commas") }
            return Read(null, "not_json")
        }
        // no closing brace: cut off — keep every complete member
        val repaired = repairTruncated(body) ?: return Read(null, "not_json")
        return parse(TRAILING_COMMA.replace(repaired, "$1"))?.let { Read(it, "repaired_truncated") } ?: Read(null, "not_json")
    }

    private fun parse(s: String): JsonObject? = runCatching { json.parseToJsonElement(s) }.getOrNull() as? JsonObject

    /** Index of the brace that closes the object starting at 0, or null when the text ends first. */
    private fun closingBrace(t: String): Int? {
        var depth = 0; var inStr = false; var esc = false
        for (i in t.indices) {
            val ch = t[i]
            if (inStr) { if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false; continue }
            when (ch) {
                '"' -> inStr = true
                '{', '[' -> depth++
                '}', ']' -> { depth--; if (depth == 0) return i }
            }
        }
        return null
    }

    /**
     * A cut-off object closed after its last complete member: the text up to
     * the last comma (or nested value end) outside a string, then the brackets
     * still open there. Null when nothing complete came through.
     */
    internal fun repairTruncated(t: String): String? {
        val stack = ArrayDeque<Char>()
        var inStr = false; var esc = false
        var cut = -1
        var open: List<Char> = emptyList()
        for (i in t.indices) {
            val ch = t[i]
            if (inStr) { if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false; continue }
            when (ch) {
                '"' -> inStr = true
                '{', '[' -> stack.addLast(ch)
                '}', ']' -> {
                    if (stack.isEmpty()) return null
                    stack.removeLast()
                    if (stack.isEmpty()) return t.substring(0, i + 1)
                    cut = i + 1; open = stack.toList()
                }
                ',' -> { cut = i; open = stack.toList() }
            }
        }
        if (cut < 0) return null
        val out = StringBuilder(t.substring(0, cut))
        for (b in open.reversed()) out.append(if (b == '{') '}' else ']')
        return out.toString()
    }
}
