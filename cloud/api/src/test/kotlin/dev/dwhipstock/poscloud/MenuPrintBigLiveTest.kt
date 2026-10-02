package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.menuai.AiHttp
import dev.dwhipstock.poscloud.menuai.GeminiMenuModel
import dev.dwhipstock.poscloud.menuai.JdkAiHttp
import dev.dwhipstock.poscloud.menuai.MenuAiService
import dev.dwhipstock.poscloud.menuprint.MenuKind
import dev.dwhipstock.poscloud.menuprint.PrintAi
import dev.dwhipstock.poscloud.menuprint.PrintCatalog
import dev.dwhipstock.poscloud.menuprint.PrintSelect
import dev.dwhipstock.poscloud.menuprint.PrintWords
import dev.dwhipstock.poscloud.menuprint.TodayRules
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Opt-in, online (MENU_PRINT_LIVE=1 + a Gemini key, as MenuPrintLiveTest):
 * the real writer on the 63-item pub menu — full, today and drinks, in
 * English and German — with timings and the AI status of each. The raw
 * provider replies go to MENU_PRINT_SAMPLES_DIR for diagnosis (never in CI).
 */
class MenuPrintBigLiveTest {
    @Test
    fun realSizeMenus() {
        Assume.assumeTrue(System.getenv("MENU_PRINT_LIVE") == "1")
        val file = System.getenv("MENU_PRINT_KEYS_FILE")?.let(::File)?.takeIf { it.isFile }?.readLines().orEmpty()
            .mapNotNull { l -> l.substringBefore('=', "").trim().takeIf { it.isNotEmpty() }?.let { it to l.substringAfter('=').trim() } }.toMap()
        val key = System.getenv("MENU_AI_GEMINI_API_KEY")?.takeIf { it.isNotBlank() } ?: file["GEMINI_API_KEY"]
        Assume.assumeTrue(!key.isNullOrBlank())
        val out = File(System.getenv("MENU_PRINT_SAMPLES_DIR") ?: "build/menu-print-live").apply { mkdirs() }
        var raw = ""
        val http = AiHttp { url, headers, body, timeout -> JdkAiHttp().post(url, headers, body, timeout).also { raw = it.second } }
        val model = GeminiMenuModel(key!!, System.getenv("MENU_PRINT_MODEL") ?: "gemini-3.5-flash", http = http,
            budgetMs = PrintAi.AI_BUDGET_MS, thinkingLevel = PrintAi.THINKING, temperature = 0.9, maxOutputTokens = PrintAi.MAX_OUTPUT_TOKENS)
        val zone = ZoneId.of("America/New_York")
        val now = TodayRules.moment(Instant.now(), zone)
        val kinds = (System.getenv("MENU_PRINT_LIVE_KINDS") ?: "full,today,drinks").split(',').mapNotNull { MenuKind.of(it) }
        for (lang in listOf("en", "de")) for (kind in kinds) {
            val c = PrintCatalog.of(PubMenuFixture.menu, lang)
            val cands = PrintSelect.candidates(kind, c, now.day)
            val t0 = System.currentTimeMillis()
            val reply = runCatching {
                model.complete(PrintAi.system(kind, lang, MenuAiService.LANGUAGE_NAMES.getValue(lang), PrintWords.dayName(now.day, "en"),
                    PrintSelect.flyerHasSpecials(c), PrintAi.copyBudget(cands.size)),
                    PrintAi.user(PrintAi.menuData(c, cands, lang, complete = kind.complete), null), null)
            }
            val ms = System.currentTimeMillis() - t0
            File(out, "big-${kind.code}-$lang-raw.json").writeText(raw)
            val outcome = reply.fold({ r ->
                val p = PrintAi.parseOutcome(r, kind, c, cands, lang, listOf("Copper Lantern"))
                "ai=${if (p.plan != null) "used" else "fallback"} reason=${p.reason ?: "-"} blurbs=${p.plan?.plan?.blurbs?.size} " +
                    "intros=${p.plan?.plan?.sections?.count { it.intro != null }} title='${p.plan?.plan?.title}' chars=${r.length}"
            }, { "ai=fallback error=${it.javaClass.simpleName}: ${it.message}" })
            println("LIVE-BIG ${kind.code}/$lang ${cands.size} items: ${ms}ms $outcome")
        }
    }
}
