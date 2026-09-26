package dev.dwhipstock.pos.sdk.i18n

import dev.dwhipstock.pos.customers.sagepoppy.SagePoppy
import dev.dwhipstock.pos.sdk.StoreProfile
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every string a store shows exists in every language that store speaks: the
 * pubs' French + English, the US shop's English + Spanish. Covers the server's
 * message catalogs (receipts, bills, slips), the staff web app and the
 * customer QR menu. Also: no English left behind in the French and Spanish
 * tables (a value equal to its English one, or a common English word), and
 * French typography (a no-break space before : ; ! ? and inside « »).
 *
 * Reads the main resources straight from disk, not the merged classpath (test
 * resources carry a partial messages_zh on purpose).
 */
class LocaleCoverageTest {
    private val resources = File("src/main/resources")

    /** The stores and the languages each one offers. */
    private val stores = mapOf(
        "Copper Lantern" to StoreProfile.QUEBEC_PUB.locales.map { it.tag },
        "Sage & Poppy" to SagePoppy.PROFILE.locales.map { it.tag },
    )
    private val allLocales = stores.values.flatten().toSortedSet()

    /** Identical in English and the other language, legitimately. */
    private val sameAsEnglish = setOf(
        "Total", "Table", "Tables", "Subtotal", "Menu", "Terminal", "{0} {1}%", "#{0}",
        "Tel. {0}", // the phone label: "Tel." in Spanish too
        "Table {0}", // the kitchen ticket's table line: "Table" in French too
    )

    /** Proper nouns and loanwords a French or Spanish string may carry. */
    private val properNouns = listOf("Sage & Poppy", "Copper Lantern", "Wi-Fi", "CRV", "PIN", "QR")

    /** Common English words that must not survive in a French or Spanish string. */
    private val englishWords = Regex(
        """\b(the|and|your|please|tap|add|added|bill|order|orders|scan|enter|loading|failed|retry|""" +
            """reset|cash|change|refund|receipt|sale|register|free|open|closed|pending|basket|back|welcome|shop)\b""",
        RegexOption.IGNORE_CASE,
    )

    // ---- server message catalogs --------------------------------------------

    private fun catalog(tag: String): Map<String, String> {
        val file = File(resources, "i18n/messages_$tag.properties")
        assertTrue(file.exists(), "no catalog for '$tag' at $file")
        val p = Properties()
        file.reader(Charsets.UTF_8).use(p::load)
        return p.entries.associate { (k, v) -> k.toString() to v.toString() }
    }

    @Test
    fun everyMessageKeyExistsInEveryLanguageAStoreUses() {
        val keys = MessageKey.entries.map { it.id }.toSet()
        for ((store, locales) in stores) for (tag in locales) {
            val missing = keys - catalog(tag).keys
            assertTrue(missing.isEmpty(), "$store speaks '$tag' but messages_$tag.properties lacks ${missing.sorted()}")
            val blank = catalog(tag).filterValues { it.isBlank() }.keys
            assertTrue(blank.isEmpty(), "messages_$tag.properties has blank values: $blank")
        }
    }

    @Test
    fun noEnglishLeftInTheFrenchAndSpanishCatalogs() {
        val en = catalog("en")
        for (tag in allLocales - "en") {
            val table = catalog(tag).filterKeys { !it.startsWith(Messages.DATA_PREFIX) }
            assertNoEnglish("messages_$tag.properties", tag, table, en)
        }
    }

    @Test
    fun frenchCatalogUsesFrenchTypography() {
        assertFrenchTypography("messages_fr.properties", catalog("fr"))
    }

    // ---- staff web app and customer QR menu ---------------------------------

    /** The page's `const STR = { fr: {...}, en: {...} }` tables: locale → key → text. */
    private fun strTables(page: String): Map<String, Map<String, String>> {
        val html = File(resources, page).readText()
        val start = html.indexOf("const STR = {")
        assertTrue(start >= 0, "$page has no STR table")
        val end = html.indexOf("\n};", start)
        val body = html.substring(start, end)
        val tables = mutableMapOf<String, Map<String, String>>()
        val block = Regex("""\n {2}(\w+): \{(.*?)\n {2}\},""", RegexOption.DOT_MATCHES_ALL)
        val entry = Regex("""(\w+):\s*"((?:[^"\\]|\\.)*)"""")
        for (m in block.findAll(body)) {
            tables[m.groupValues[1]] = entry.findAll(m.groupValues[2])
                .associate { it.groupValues[1] to it.groupValues[2].replace("\\u00a0", " ") }
        }
        return tables
    }

    @Test
    fun staffAppSpeaksEveryStoreLanguageWithTheSameKeys() {
        val tables = strTables("staff-app.html")
        assertEquals(allLocales, tables.keys.toSortedSet(), "staff app languages")
        val keys = tables.getValue("en").keys
        assertTrue(keys.size > 40, "parsed only ${keys.size} staff-app keys")
        for ((tag, table) in tables) {
            assertEquals(keys, table.keys, "staff app '$tag' keys differ from English: " +
                "missing ${(keys - table.keys).sorted()}, extra ${(table.keys - keys).sorted()}")
            assertTrue(table.values.none { it.isBlank() }, "staff app '$tag' has blank strings")
        }
        for (tag in allLocales - "en") assertNoEnglish("staff-app.html $tag", tag, tables.getValue(tag), tables.getValue("en"))
        assertFrenchTypography("staff-app.html fr", tables.getValue("fr"))
    }

    @Test
    fun customerMenuSpeaksThePubsLanguages() {
        val tables = strTables("customer-menu.html")
        val pub = stores.getValue("Copper Lantern").toSortedSet()
        assertEquals(pub, tables.keys.toSortedSet(), "customer menu languages")
        val keys = tables.getValue("en").keys
        for ((tag, table) in tables) assertEquals(keys, table.keys, "customer menu '$tag' keys differ from English")
        assertNoEnglish("customer-menu.html fr", "fr", tables.getValue("fr"), tables.getValue("en"))
        assertFrenchTypography("customer-menu.html fr", tables.getValue("fr"))
    }

    // ---- checks ---------------------------------------------------------------

    private fun assertNoEnglish(where: String, tag: String, table: Map<String, String>, en: Map<String, String>) {
        val problems = mutableListOf<String>()
        for ((key, value) in table) {
            val english = en[key]
            if (english != null && value.trim() == english.trim() && value.trim() !in sameAsEnglish) problems += "$key = \"$value\" (same as English)"
            // the other language inside a deliberately bilingual banner is fine
            val own = if (" / " in value) value.split(" / ").first() else value
            val words = properNouns.fold(own) { s, noun -> s.replace(noun, "") }
            englishWords.find(words)?.let { problems += "$key = \"$value\" (English word '${it.value}')" }
        }
        if (problems.isNotEmpty()) fail("English left in $where ($tag):\n" + problems.joinToString("\n"))
    }

    private fun assertFrenchTypography(where: String, table: Map<String, String>) {
        val problems = mutableListOf<String>()
        for ((key, value) in table) {
            val text = value.replace("://", "")
            // : ; ! ? need a no-break space before them (a time such as 20:15 excepted)
            Regex("""(?<! )[;:!?]""").findAll(text).forEach { m ->
                val i = m.range.first
                val time = m.value == ":" && i > 0 && text[i - 1].isDigit() && i + 1 < text.length && text[i + 1].isDigit()
                if (!time) problems += "$key = \"$value\" (no no-break space before '${m.value}')"
            }
            if (Regex("""«(?! )|(?<! )»""").containsMatchIn(text)) problems += "$key = \"$value\" (« » need no-break spaces)"
            if ('"' in text && !text.startsWith("*")) problems += "$key = \"$value\" (use « » quotes)"
            if ('\'' in text) problems += "$key = \"$value\" (use the ’ apostrophe)"
            if (Regex("""\d%""").containsMatchIn(text) || Regex("""\} ?%""").containsMatchIn(text)) problems += "$key = \"$value\" (no-break space before %)"
        }
        if (problems.isNotEmpty()) fail("French typography in $where:\n" + problems.joinToString("\n"))
    }
}
