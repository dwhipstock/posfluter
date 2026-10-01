package dev.dwhipstock.pos

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Red team: hostile names typed by execs at the live demo (item / category /
 * variant / table names) reaching the server-served HTML pages.
 *
 * The customer menu page is driven for real: the page is fetched from the
 * running app, its <script> is run under node with a tiny DOM stub whose
 * fetch() answers with the app's own /items, /categories and /health, and the
 * innerHTML the page writes is checked. Skipped when node is not installed.
 */
class RedTeamHtmlTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    private suspend fun HttpClient.patchJson(path: String, body: JsonObject) =
        patch(path) { contentType(ContentType.Application.Json); setBody(body.toString()) }

    private fun nodeOk(): Boolean = runCatching {
        ProcessBuilder("node", "--version").start().waitFor(10, TimeUnit.SECONDS)
    }.getOrDefault(false)

    /**
     * Run the customer-menu page's script under node. [responses] maps a fetch
     * path to its JSON body. Returns {grid, chips, hdr, pwned, error}.
     */
    private fun runMenuScript(pageHtml: String, responses: Map<String, String>): JsonObject {
        val script = Regex("<script>([\\s\\S]*)</script>").findAll(pageHtml).last().groupValues[1]
        val prelude = """
            const __els = {};
            const __el = id => __els[id] || (__els[id] = {
              id, innerHTML: "", textContent: "", value: "", style: {}, offsetWidth: 0,
              classList: { add() {}, remove() {}, toggle() {} }, setAttribute() {},
            });
            globalThis.document = { getElementById: __el, documentElement: {}, addEventListener() {}, hidden: false,
                                    querySelectorAll: () => [] };
            globalThis.localStorage = { getItem: () => null, setItem() {} };
            Object.defineProperty(globalThis, "navigator", { value: { languages: ["en"], language: "en" } });
            globalThis.location = { hash: "" };
            globalThis.alert = () => { globalThis.PWNED = (globalThis.PWNED || 0) + 1; };
            globalThis.setInterval = () => 0;
            const __resp = ${json.encodeToString(JsonObject.serializer(),
                JsonObject(responses.mapValues { json.parseToJsonElement(it.value) }))};
            globalThis.fetch = async url => {
              const body = __resp[String(url).split("?")[0]];
              return { ok: body !== undefined, json: async () => body };
            };
            process.on("uncaughtException", e => { globalThis.__err = String(e); });
        """.trimIndent()
        val epilogue = """
            setTimeout(() => console.log(JSON.stringify({
              grid: __el("grid").innerHTML, chips: __el("chips").innerHTML,
              hdr: __el("hdr-table").textContent, pwned: globalThis.PWNED || 0, error: globalThis.__err || null,
              money: [cad(9999999), cad(123456), cad(-500)],
            })), 300);
        """.trimIndent()
        val file = Files.createTempFile("redteam-menu", ".js")
        // epilogue first: its timer reads the page's top-level consts (cad, …) once the page has run
        Files.writeString(file, "$prelude\n$epilogue\n$script\n")
        val proc = ProcessBuilder("node", file.toString()).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor(30, TimeUnit.SECONDS)
        val line = out.lines().lastOrNull { it.startsWith("{") } ?: error("node gave no result:\n$out")
        return json.parseToJsonElement(line).jsonObject
    }

    private suspend fun ApplicationTestBuilder.menuResponses(): Map<String, String> = mapOf(
        "/items" to client.get("/items").bodyAsText(),
        "/categories" to client.get("/categories").bodyAsText(),
        "/health" to client.get("/health").bodyAsText(),
        "/menu/version" to """{"version":1}""",
    )

    // --- customer scan-to-order menu: item / category / variant names --------------

    @Test
    fun `customer menu - hostile item, category and size names stay inert`() = testApplication {
        assumeTrue("node not installed", nodeOk())
        application { module(dbPath = tempDb()) }
        val mgr = loginClient()
        val items = json.parseToJsonElement(client.get("/items").bodyAsText()).jsonArray.map { it.jsonObject }
        val cats = json.parseToJsonElement(client.get("/categories").bodyAsText()).jsonArray.map { it.jsonObject }
        // the page opens on the first category (in server order) that has an item
        val firstCat = cats.first { c -> items.any { it["category"]!!.jsonPrimitive.content == c["id"]!!.jsonPrimitive.content } }
        val catId = firstCat["id"]!!.jsonPrimitive.content
        val inCat = items.filter { it["category"]!!.jsonPrimitive.content == catId }
        val item = inCat.firstOrNull { it["variants"]!!.jsonArray.size > 1 } ?: inCat.first()
        val itemId = item["id"]!!.jsonPrimitive.content

        assertEquals(HttpStatusCode.OK, mgr.patchJson("/items/$itemId", buildJsonObject {
            put("nameEn", "<img src=x onerror=alert('nameEn')>")
            put("nameFr", "<img src=x onerror=alert('nameFr')>")
        }).status)
        assertEquals(HttpStatusCode.OK, mgr.patchJson("/categories/$catId", buildJsonObject {
            put("nameEn", "<img src=x onerror=alert('cat')>")
        }).status)
        val multi = item["variants"]!!.jsonArray.size > 1
        if (multi) {
            val vId = item["variants"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content
            assertEquals(HttpStatusCode.OK, mgr.patchJson("/items/$itemId/variants/$vId", buildJsonObject {
                put("labelEn", "</select><img src=x onerror=alert('variant')>")
            }).status)
        }

        val page = client.get(customerPath("t5")).bodyAsText()
        val r = runMenuScript(page, menuResponses())
        assertEquals("null", r["error"].toString(), "page script threw")
        val grid = r["grid"]!!.jsonPrimitive.content
        val chips = r["chips"]!!.jsonPrimitive.content
        val raw = listOfNotNull(
            "<img src=x onerror=alert('nameEn')>".takeIf { it in grid }?.let { "item nameEn in #grid" },
            "<img src=x onerror=alert('nameFr')>".takeIf { it in grid }?.let { "item nameFr in #grid" },
            "<img src=x onerror=alert('cat')>".takeIf { it in chips }?.let { "category name in #chips" },
            if (multi && "</select><img src=x onerror=alert('variant')>" in grid) "size label in #grid <option>" else null,
        )
        assertTrue(raw.isEmpty(), "hostile markup written raw into innerHTML: $raw")
    }

    // --- customer menu: table name inside the inline JS string -----------------------

    @Test
    fun `customer menu - a quote in the table name cannot break out of the inline script`() = testApplication {
        assumeTrue("node not installed", nodeOk())
        application { module(dbPath = tempDb()) }
        val mgr = loginClient()
        val evil = "\"+alert(1)+\""
        assertEquals(HttpStatusCode.OK, mgr.patchJson("/tables/t5", buildJsonObject {
            put("nameOverride", evil); put("managerPin", "1234")
        }).status)
        val page = client.get(customerPath("t5")).bodyAsText()
        val r = runMenuScript(page, menuResponses())
        assertEquals(0, r["pwned"]!!.jsonPrimitive.content.toInt(),
            "table name ran as script on the guest's phone; page has: " +
                page.lines().first { "const TABLE_LABEL" in it })
        assertTrue(evil in r["hdr"]!!.jsonPrimitive.content, "header should show the name as text")
    }

    // --- printable table slips: name inside an attribute ---------------------------

    @Test
    fun `table slip - a quote in the table name cannot add an attribute to the QR image`() = testApplication {
        application { module(dbPath = tempDb()) }
        val mgr = loginClient()
        assertEquals(HttpStatusCode.OK, mgr.patchJson("/tables/t5", buildJsonObject {
            put("nameOverride", "x\" onload=\"alert(1)"); put("managerPin", "1234")
        }).status)
        val html = client.get("/tables/t5/slip?ticket=${mgr.slipTicket()}").bodyAsText()
        val img = Regex("<img class=\"qr\"[^>]*>").find(html)!!.value.replace(Regex("base64,[^\"]+"), "base64,…")
        assertFalse("\" onload=\"alert(1)" in html, "attribute breakout in the slip's <img>: $img")
    }

    // --- long names with no spaces -------------------------------------------------

    private fun css(page: String) = StoreAssets.readText(page)
        .substringAfter("<style>").substringBefore("</style>")

    @Test
    fun `customer menu - a long unbroken name wraps instead of being cut off`() {
        assertTrue(Regex("overflow-wrap:\\s*(anywhere|break-word)|word-break:\\s*break-(word|all)").containsMatchIn(css("customer-menu.html")),
            "customer-menu.html has no overflow-wrap/word-break: a 60-char word clips inside .card (overflow:hidden)")
    }

    @Test
    fun `kitchen display - a long unbroken name wraps instead of being cut off`() {
        assertTrue(Regex("overflow-wrap:\\s*(anywhere|break-word)|word-break:\\s*break-(word|all)").containsMatchIn(css("kitchen.html")),
            "kitchen.html has no overflow-wrap/word-break: a long item/note clips inside .card (overflow:hidden)")
    }

    @Test
    fun `staff app - a long unbroken name wraps instead of being cut off`() {
        val c = css("staff-app.html").replace(Regex("\\.enroll-manual code[^}]*}"), "") // only the 2FA secret has it
        assertTrue(Regex("overflow-wrap:\\s*(anywhere|break-word)|word-break:\\s*break-(word|all)").containsMatchIn(c),
            "staff-app.html has no overflow-wrap/word-break for names / notes / table labels")
    }

    // --- what held: money on the guest page is always North American ---------------

    @Test
    fun `customer menu - money is North American whatever the language`() = testApplication {
        assumeTrue("node not installed", nodeOk())
        application { module(dbPath = tempDb()) }
        val page = client.get(customerPath("t5")).bodyAsText()
        val r = runMenuScript(page, menuResponses())
        assertEquals(listOf("\$99,999.99", "\$1,234.56", "-\$5.00"),
            r["money"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
}
