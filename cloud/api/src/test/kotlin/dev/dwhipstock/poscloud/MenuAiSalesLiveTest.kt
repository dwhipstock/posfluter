package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.Tenants
import dev.dwhipstock.poscloud.db.Venues
import dev.dwhipstock.poscloud.menuai.AiAudio
import dev.dwhipstock.poscloud.menuai.GeminiMenuModel
import dev.dwhipstock.poscloud.menuai.MenuAiModel
import dev.dwhipstock.poscloud.menuai.MenuAiService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sales assistant against the real model, on a store with realistic
 * sales: the Copper Lantern demo menu (scripts/tests/fixtures) and 90 days of
 * history from scripts/demo-reseed.py, written into the test database.
 *
 * Off unless MENU_AI_SALES_LIVE=1 and a Gemini key is set (MENU_AI_GEMINI_API_KEY
 * or GEMINI_API_KEY, in the environment or in a dotenv file named by
 * MENU_AI_KEYS_FILE). Needs python3. The answers go to build/menu-ai-sales-live.
 * Never runs in CI.
 */
class MenuAiSalesLiveTest {

    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "server").isDirectory && File(it, "cloud").isDirectory }
    private val key = "sales-live-key"

    @Test
    fun liveSamples() {
        Assume.assumeTrue(System.getenv("MENU_AI_SALES_LIVE") == "1")
        val file = System.getenv("MENU_AI_KEYS_FILE")?.let(::File)?.takeIf { it.isFile }?.readLines().orEmpty()
            .mapNotNull { l -> l.substringBefore('=', "").trim().takeIf { it.isNotEmpty() }?.let { it to l.substringAfter('=').trim().trim('"') } }.toMap()
        fun secret(vararg names: String) = names.firstNotNullOfOrNull { n -> System.getenv(n)?.takeIf { it.isNotBlank() } ?: file[n]?.takeIf { it.isNotBlank() } }
        val apiKey = secret("MENU_AI_GEMINI_API_KEY", "GEMINI_API_KEY")
        Assume.assumeTrue(apiKey != null)
        val py = listOf("python3", "python").firstOrNull { cmd ->
            runCatching { ProcessBuilder(cmd, "--version").start().waitFor(20, TimeUnit.SECONDS) }.getOrDefault(false)
        }
        Assume.assumeTrue(py != null)

        TestSupport.reset()
        seedTenant("copperlantern", "vieux-port", "Copper Lantern — Glenwood South")
        seedTenant("copperlantern", "express", "Copper Lantern — Express")
        transaction {
            Tenants.update({ Tenants.id eq "copperlantern" }) { it[reportingCurrency] = "USD" }
            Venues.update({ Venues.tenantId eq "copperlantern" }) { it[currency] = "USD"; it[country] = "US" }
        }
        seedStoreKey("copperlantern", "vieux-port", key)
        val owner = seedSession("copperlantern", seedUser("copperlantern", "owner@live.test", "pw-owner-live"))
        val zone = ZoneId.of("America/New_York")
        val today = LocalDate.now(zone)

        // 90 days of realistic sales, ending yesterday
        val sql = File.createTempFile("sales-live", ".sql").apply { deleteOnExit() }
        val proc = ProcessBuilder(py, File(repo, "scripts/demo-reseed.py").path,
            "--menu-json", File(repo, "scripts/tests/fixtures/copperlantern-catalog.json").path,
            "--today", today.toString(), "--days", "90", "--seed", "7", "--dry-run-sql", sql.path,
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue(proc.waitFor(300, TimeUnit.SECONDS) && proc.exitValue() == 0, out.takeLast(2000))
        DriverManager.getConnection(TestSupport.config.databaseUrl, TestSupport.config.dbUser, TestSupport.config.dbPassword).use { c ->
            c.autoCommit = true
            c.createStatement().use { it.execute(sql.readText()) }
        }

        val gemini = GeminiMenuModel(apiKey!!, System.getenv("MENU_AI_MODEL") ?: GeminiMenuModel.DEFAULT_MODEL)
        // the raw reply too, for the samples file (the request and the reply only; never the key)
        var lastReply = ""
        val model = object : MenuAiModel by gemini {
            override fun complete(system: String, user: String, audio: AiAudio?): String =
                gemini.complete(system, user, audio).also { lastReply = it }
        }
        val samples = File(System.getenv("MENU_AI_SALES_SAMPLES_DIR") ?: "build/menu-ai-sales-live").apply { mkdirs() }
        val report = StringBuilder()
        val problems = mutableListOf<String>()

        testApplication {
            application { module(TestSupport.config, MenuAiService(TestSupport.config, model, model, callsMax = 100)) }
            // the demo menu, as the store sends it, and one pull (two-way sync)
            val fixture = testJson.parseToJsonElement(File(repo, "scripts/tests/fixtures/copperlantern-catalog.json").readText()).jsonObject
            val menu = fixture["venues"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "vieux-port" }["menu"]!!.jsonObject
            ingest(key, event("catalog.snapshot", buildJsonObject {
                put("categories", JsonArray(menu["categories"]!!.jsonArray.map { c -> JsonObject(c.jsonObject + ("deleted" to JsonPrimitive(false))) }))
                put("items", JsonArray(menu["items"]!!.jsonArray.map { i ->
                    val o = i.jsonObject
                    JsonObject(o + mapOf(
                        "descriptionFr" to JsonPrimitive(""), "descriptionEn" to JsonPrimitive(""),
                        "abbrev" to JsonPrimitive(o["nameEn"]!!.jsonPrimitive.content.take(2).uppercase()),
                        "active" to JsonPrimitive(true), "deleted" to JsonPrimitive(false), "names" to buildJsonObject {},
                        "variants" to JsonArray(o["variants"]!!.jsonArray.map { v ->
                            JsonObject(v.jsonObject + mapOf("deleted" to JsonPrimitive(false), "names" to buildJsonObject {}))
                        }),
                    ).filterValues { it !is JsonNull })
                }))
            }, seq = 1))
            client.get("/v1/store/menu/changes?since=0") { header(HttpHeaders.Authorization, "Bearer $key") }
            // the app's start-up names the test venue; the demo's name back
            transaction { Venues.update({ Venues.id eq "vieux-port" }) { it[name] = "Copper Lantern — Glenwood South" } }

            val cases = listOf(
                "en" to "Take my top 5 selling items for the last 30 days and make them \$2.00 cheaper on Tuesdays",
                "de" to "Nimm meine 5 meistverkauften Artikel der letzten 30 Tage und mach sie dienstags 2,00 \$ billiger",
                "fr" to "Prends mes 5 articles les plus vendus des 30 derniers jours et baisse-les de 2,00 \$ le mardi",
                "en" to "86 anything that hasn't sold in 2 weeks",
                "en" to "Put our 3 slowest drinks on happy hour Mon–Fri 4–6 at \$5",
                "en" to "Which burgers sold best last month?",
                "de" to "Welche Burger haben sich letzten Monat am besten verkauft?",
            ).let { all -> System.getenv("MENU_AI_SALES_CASES")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.map { all[it] } ?: all }

            for ((lang, text) in cases) {
                val r = client.post("/v1/menu-ai/chat?venue=vieux-port") {
                    header(HttpHeaders.Cookie, "pos_portal_session=$owner")
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("text", text); put("lang", lang) }.toString())
                }
                assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
                val p = testJson.parseToJsonElement(r.bodyAsText()).jsonObject
                report.append(describe(lang, text, p))
                if (System.getenv("MENU_AI_SALES_RAW") == "1") report.append("  raw: ${lastReply.replace(Regex("\\s+"), " ").take(1500)}\n")
                report.append("\n")
                // the server's numbers: every new Tuesday price is exactly the menu price minus 2.00
                if ("2,00" in text || "2.00" in text) {
                    val changes = p["changes"]!!.jsonArray.map { it.jsonObject }
                    if (changes.size !in 1..5) problems += "$lang: ${changes.size} changes"
                    changes.flatMap { it["details"]!!.jsonArray.map { d -> d.jsonObject } }
                        // the new specials (a replaced one shows as going back up to the menu price)
                        .filter { d -> d["afterMinor"]!!.jsonPrimitive.content.toLong() < d["beforeMinor"]!!.jsonPrimitive.content.toLong() }
                        .forEach { d ->
                            if (d["beforeMinor"]!!.jsonPrimitive.content.toLong() - 200 != d["afterMinor"]!!.jsonPrimitive.content.toLong()) problems += "$lang: $d"
                        }
                }
            }
        }
        File(samples, "samples.txt").writeText(report.toString())
        println(report)
        assertEquals(emptyList(), problems)
    }

    private fun describe(lang: String, text: String, p: JsonObject): String = buildString {
        fun s(o: JsonObject, k: String) = o[k]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
        append("[$lang] $text\n")
        append("  ${p["elapsedMs"]} ms, answer=${s(p, "answer")}, refusal=${s(p, "refusal")}, summary=\"${s(p, "summary")}\"\n")
        p["sales"]?.jsonArray?.forEach { b ->
            val o = b.jsonObject
            append("  basis: ${s(o, "rank")} ${s(o, "n")} by ${s(o, "by")}, ${s(o, "from")} – ${s(o, "to")}, ${s(o, "store")}" +
                (s(o, "days")?.let { " (not sold in $it days)" } ?: "") + ":\n")
            o["rows"]!!.jsonArray.forEach { r ->
                val row = r.jsonObject
                append("    ${s(row, "name")}: ${s(row, "units")} sold, ${s(row, "revenueMinor")} ¢, last ${s(row, "lastSold")}\n")
            }
        }
        p["salesNotes"]?.jsonArray?.forEach { append("  note: ${it.jsonObject}\n") }
        p["changes"]?.jsonArray?.forEach { c ->
            val o = c.jsonObject
            append("  change: ${s(o, "title")}: " + o["details"]!!.jsonArray.joinToString("; ") { d ->
                val x = d.jsonObject
                "${s(x, "field")} ${s(x, "label") ?: ""} ${s(x, "before") ?: ""} -> ${s(x, "after") ?: ""}".replace(Regex("\\s+"), " ").trim()
            } + "\n")
        }
        if ((p["rejected"]?.jsonArray?.size ?: 0) > 0) append("  rejected: ${p["rejected"]}\n")
    }
}
