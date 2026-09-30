package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.Translations
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.db.SyncState
import dev.dwhipstock.pos.sync.CloudSync
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spanish and German past the tablet: the guest page and staff phone read the
 * menu's names (items, sizes) off the API, the guest bill carries them, the
 * portal gets them in the catalog snapshots, and one printed copy can be in
 * any of the store's languages (?lang=) with the names from the translations
 * table, wrapped to the paper.
 */
class LanguagesEverywhereTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDir(prefix: String) = Files.createTempDirectory(prefix).toString()
    private fun ApplicationTestBuilder.store() = application {
        module(dbPath = tempDir("pos-langs") + "/pos.db", photosDir = tempDir("photos"), receiptsDir = tempDir("rc"))
    }
    private fun JsonObject.names() = this["names"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private suspend fun io.ktor.client.HttpClient.postJson(path: String, body: String) =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    @Test
    fun sizeLabelsCarryTheirNamesAndTheGuestBillToo() = testApplication {
        store()
        val c = loginClient()
        val items = Json.parseToJsonElement(c.get("/items").bodyAsText()).jsonArray.map { it.jsonObject }
        val amber = items.first { it.str("id") == "amber-ale" }
        val pint = amber["variants"]!!.jsonArray.map { it.jsonObject }.first { it.str("id") == "amber-ale:pint" }
        assertEquals("Pint (20 oz)", pint.names()["de"])
        assertEquals("Pinta de 20 oz", pint.names()["es"])

        // the guest's running bill names each line in every extra language
        val checkId = json.parseToJsonElement(c.post("/tables/t3/checks").bodyAsText()).jsonObject["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$checkId/lines", """{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":1}""")
        c.postJson("/checks/$checkId/lines", """{"itemId":"poutine","variantId":"poutine:regular","qty":1}""")
        val bill = json.parseToJsonElement(client.get("${customerPath("t3")}/bill").bodyAsText()).jsonObject
        val lines = bill["lines"]!!.jsonArray.map { it.jsonObject }
        val ale = lines.first { it.str("nameEn") == "Copper Amber Ale" }
        assertEquals("Copper Amber Ale", ale.names()["de"])
        assertEquals("Pint (20 oz)", ale["variantNames"]!!.jsonObject["de"]!!.jsonPrimitive.content)
        assertEquals("Poutine clásica", lines.first { it.str("nameEn") == "Classic Poutine" }.names()["es"])

        // the guest page ships every language's strings and the picker
        val page = client.get(customerPath("t3")).bodyAsText()
        assertTrue("id=\"lang-sel\"" in page && "Speisekarte" in page && "pida desde su teléfono" in page)
    }

    @Test
    fun namesRideUpInTheSnapshotsAndAnEditSendsItsThingAgain() = testApplication {
        store()
        loginClient()
        transaction { SyncOutbox.deleteAll() }
        transaction {
            Translations.set(Translations.ITEM, "poutine", "de", "Poutine nach Art des Hauses")
            Translations.set(Translations.VARIANT, "amber-ale:pint", "es", "Pinta grande")
            Translations.set(Translations.CATEGORY, "starters", "es", "Para empezar")
            Translations.set(Translations.ZONE, "lower", "de", "Untergeschoss")
            Translations.set(Translations.FLOOR_OBJECT, "lower-pool", "de", "Billardtisch") // stays in the store
        }
        val events = transaction {
            SyncOutbox.selectAll().map { it[SyncOutbox.eventType] to Json.parseToJsonElement(it[SyncOutbox.payload]).jsonObject }
        }
        assertEquals(listOf("item.updated", "item.updated", "category.updated", "zone.renamed"), events.map { it.first })
        assertEquals("Poutine nach Art des Hauses", events[0].second["item"]!!.jsonObject.names()["de"])
        val variants = events[1].second["item"]!!.jsonObject["variants"]!!.jsonArray.map { it.jsonObject }
        assertEquals("Pinta grande", variants.first { it.str("id") == "amber-ale:pint" }.names()["es"])
        assertEquals("Para empezar", events[2].second["category"]!!.jsonObject.names()["es"])
        assertEquals("Untergeschoss", events[3].second.names()["de"])
        assertEquals("lower", events[3].second.str("zoneId"))

        // the bootstrap snapshot carries them all, zones in the first chunk
        transaction { dev.dwhipstock.pos.api.writeChunkedCatalogSnapshot() }
        val snap = transaction {
            SyncOutbox.selectAll().filter { it[SyncOutbox.eventType] == "catalog.snapshot" }
                .map { Json.parseToJsonElement(it[SyncOutbox.payload]).jsonObject }.first()
        }
        val poutine = snap["items"]!!.jsonArray.map { it.jsonObject }.first { it.str("id") == "poutine" }
        assertEquals("Poutine clásica", poutine.names()["es"])
        val wings = snap["items"]!!.jsonArray.map { it.jsonObject }.first { it.str("id") == "wings" }
        assertEquals(setOf("es", "de", "af"), wings.names().keys)
        assertEquals("Vorspeisen", snap["categories"]!!.jsonArray.map { it.jsonObject }.first { it.str("id") == "starters" }.names()["de"])
        assertEquals("Untergeschoss", snap["zones"]!!.jsonArray.map { it.jsonObject }.first { it.str("id") == "lower" }.names()["de"])
    }

    @Test
    fun anAlreadySyncedStoreSendsItsNamesUpOnce() = testApplication {
        store()
        loginClient()
        fun snapshots() = transaction { SyncOutbox.selectAll().count { it[SyncOutbox.eventType] == "catalog.snapshot" } }
        transaction {
            SyncOutbox.deleteAll()
            SyncState.set(CloudSync.CATALOG_SNAPSHOT_SEQ, "1")
            SyncState.deleteWhere { key eq dev.dwhipstock.pos.customers.copperlantern.CopperLanternSeed.NAMES_SYNCED_KEY }
        }
        dev.dwhipstock.pos.customers.copperlantern.CopperLanternSeed.seedTranslations()
        val once = snapshots()
        assertTrue(once >= 1, "a synced store resends its catalog with the names")
        dev.dwhipstock.pos.customers.copperlantern.CopperLanternSeed.seedTranslations()
        assertEquals(once, snapshots(), "and only once")
    }

    @Test
    fun aBillPrintsInGermanOrSpanishFromAFrenchOwnersCheckWithinThePaper() = testApplication {
        store()
        val c = loginClient()
        c.patch("/me/preferences") { contentType(ContentType.Application.Json); setBody("""{"languageCode":"fr"}""") }
        transaction {
            Translations.set(Translations.ITEM, "mushroom-burger", "de",
                "Champignon-Burger mit geschmolzenem Bergkäse und hausgemachter Knoblauchmayonnaise")
        }
        val checkId = json.parseToJsonElement(c.post("/tables/t3/checks").bodyAsText()).jsonObject["id"]!!.jsonPrimitive.int
        c.postJson("/checks/$checkId/lines", """{"itemId":"mushroom-burger","variantId":"mushroom-burger:regular","qty":2}""")
        c.postJson("/checks/$checkId/lines", """{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":1}""")
        c.postJson("/checks/$checkId/lines", """{"itemId":"amber-ale","variantId":"amber-ale:pitcher","qty":1}""")

        suspend fun bill(q: String = ""): String {
            val r = c.post("/checks/$checkId/bill$q")
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            return json.parseToJsonElement(r.bodyAsText()).jsonObject.str("text")
        }
        val owner = bill()
        assertTrue("Burger aux champignons" in owner, owner) // the owner's French, untouched

        val de = bill("?lang=de")
        assertTrue("Summe" in de, de)
        assertTrue("Champignon-Burger mit" in de && "Knoblauchmayonnaise" in de, de)
        assertTrue("(Pint (20 oz))" in de && "(Krug (60 oz))" in de, de)
        val es = bill("?lang=es")
        assertTrue("Hamburguesa de champiñones y queso suizo" in es.replace("\n", " ").replace(Regex(" +"), " "), es)
        assertTrue("(Pinta de 20 oz)" in es, es)
        for (text in listOf(owner, de, es)) {
            val wide = text.lines().filter { it.length > 48 }
            assertTrue(wide.isEmpty(), "lines over 48 columns: $wide")
        }
        // the long name wrapped, and its amount stays on the row that ends it
        val rows = de.lines()
        val end = rows.indexOfFirst { "Knoblauchmayonnaise" in it }
        assertTrue(rows[end].trimEnd().last().isDigit(), rows[end])
        assertTrue(rows.none { it.startsWith("Champignon") && it.trimEnd().last().isDigit() && "Knoblauch" !in it }, de)

        // the owner's preference is untouched, and a language the store lacks is refused
        val me = json.parseToJsonElement(c.get("/me").bodyAsText()).jsonObject
        assertEquals("fr", me.str("languageCode"))
        assertEquals(HttpStatusCode.BadRequest, c.post("/checks/$checkId/bill?lang=it").status)
        assertTrue("Burger aux champignons" in bill())
    }
}
