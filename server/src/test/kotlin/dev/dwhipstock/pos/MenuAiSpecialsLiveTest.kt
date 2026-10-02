package dev.dwhipstock.pos

import dev.dwhipstock.pos.sdk.MenuAiConfig
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test

/**
 * Opt-in LIVE check of the store's AI menu assistant and menu specials
 * against the real model: `LIVE_MENU_AI=1` with `GEMINI_API_KEY` in the
 * environment (never in a file). Skipped otherwise (CI). Prints each phrase's
 * proposal (titles and before → after) to build/live-menu-ai-specials.txt.
 */
class MenuAiSpecialsLiveTest {
    private val phrases = listOf(
        "de" to "Prime Rib nur freitags und samstags",
        "de" to "Steak Frites nur freitags und samstags",
        "de" to "Burger dienstags für 9,95 $",
        "en" to "happy hour 3-6 on weekdays, beers \$5",
        "fr" to "les burgers à 9,95 $ le mardi",
    )

    @Test
    fun liveSpecialsProposals() = testApplication {
        Assume.assumeTrue(System.getenv("LIVE_MENU_AI") == "1" && !System.getenv("GEMINI_API_KEY").isNullOrBlank())
        val dir = Files.createTempDirectory("pos-live-ai").toString()
        application {
            module(dbPath = "$dir/pos.db", photosDir = "$dir/photos",
                menuAiConfig = MenuAiConfig.fromProperties(Properties().apply {
                    setProperty("menu.ai", "on"); setProperty("menu.ai.provider", "gemini")
                    setProperty("menu.ai.gemini.apiKey", System.getenv("GEMINI_API_KEY"))
                }), imageReachable = { true })
        }
        val manager = loginClient()
        val out = StringBuilder()
        for ((lang, text) in phrases) {
            val res = manager.post("/menu-ai/chat") {
                contentType(ContentType.Application.Json)
                setBody("""{"managerPin":"1234","text":${kotlinx.serialization.json.JsonPrimitive(text)}}""")
            }
            out.append("=== [$lang] $text → ${res.status.value}\n")
            val body = runCatching { Json.parseToJsonElement(res.bodyAsText()).jsonObject }.getOrNull()
            if (res.status != HttpStatusCode.OK) out.append(res.bodyAsText().take(300)).append("\n")
            if (body == null) { out.append(res.bodyAsText().take(300)).append("\n"); continue }
            body["summary"]?.let { out.append("summary: ${it.jsonPrimitive.content}\n") }
            body["changes"]?.jsonArray?.forEach { c ->
                val o = c.jsonObject
                out.append("- ${o["kind"]?.jsonPrimitive?.content} ${o["title"]?.jsonPrimitive?.content}\n")
                o["details"]?.jsonArray?.forEach { d ->
                    val dd = d.jsonObject
                    fun s(k: String) = dd[k]?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content
                    out.append("    ${s("field")}${s("label")?.let { " ($it)" } ?: ""}: ${s("before") ?: ""} → ${s("after") ?: ""}\n")
                }
            }
            body["rejected"]?.jsonArray?.forEach { out.append("  rejected: ${it.jsonPrimitive.content}\n") }
            body["message"]?.let { out.append("message: $it\n") }
        }
        java.io.File("build/live-menu-ai-specials.txt").writeText(out.toString())
        println(out)
    }
}
