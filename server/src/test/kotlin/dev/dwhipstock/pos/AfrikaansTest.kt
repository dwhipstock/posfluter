package dev.dwhipstock.pos

import dev.dwhipstock.pos.aimenu.AiGuard
import dev.dwhipstock.pos.base.Translations
import dev.dwhipstock.pos.customers.pronghorn.Pronghorn
import dev.dwhipstock.pos.customers.sagepoppy.SagePoppy
import dev.dwhipstock.pos.sdk.Money
import dev.dwhipstock.pos.sdk.MoneyFormat
import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import dev.dwhipstock.pos.sdk.i18n.MessageKey
import dev.dwhipstock.pos.sdk.i18n.Messages
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Afrikaans (af), Copper Lantern's fifth language: a full receipt catalog,
 * Afrikaans menu names from the translations table with English where a
 * name is missing, North American money ($1,234.56, never R or 1 234,56),
 * and only Copper Lantern offers it.
 */
class AfrikaansTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDir(prefix: String) = Files.createTempDirectory(prefix).toString()
    private fun ApplicationTestBuilder.store() = application {
        module(dbPath = tempDir("pos-af") + "/pos.db", photosDir = tempDir("photos"), receiptsDir = tempDir("rc"))
    }

    @Test
    fun theAfrikaansCatalogHasEveryMessageKey() {
        val missing = MessageKey.entries.map { it.id }.toSet() - Messages.translatedKeys(LocaleCode.AF)
        assertTrue(missing.isEmpty(), "messages_af lacks ${missing.sorted()}")
        assertEquals("Totaal", Messages.get(MessageKey.RECEIPT_TOTAL, LocaleCode.AF))
        assertEquals("Fooitjie", Messages.get(MessageKey.RECEIPT_CARD_TIP, LocaleCode.AF))
        assertEquals("Kelner: Sam", Messages.get(MessageKey.KITCHEN_SERVER, LocaleCode.AF, "Sam"))
        assertEquals("*** NIE ’N KWITANSIE NIE / NOT A RECEIPT ***", Messages.get(MessageKey.RECEIPT_NOT_A_RECEIPT, LocaleCode.AF))
        assertEquals("$1,234.56", MoneyFormat.format(Money(123456), "CAD", "af"))
    }

    @Test
    fun onlyCopperLanternSpeaksAfrikaans() = testApplication {
        store()
        val health = Json.parseToJsonElement(client.get("/health").bodyAsText()).jsonObject
        assertTrue("af" in health["locales"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(LocaleCode.AF in SagePoppy.PROFILE.locales)
        assertFalse(LocaleCode.AF in Pronghorn.PROFILE.locales)
        assertFalse(LocaleCode.AF in dev.dwhipstock.pos.sdk.StoreProfile.QUEBEC_PUB.locales)
    }

    @Test
    fun aBillInAfrikaansReadsAfrikaansWithNorthAmericanMoneyAndEnglishWhereANameIsMissing() = testApplication {
        store()
        val c = loginClient()
        c.patch("/me/preferences") { contentType(ContentType.Application.Json); setBody("""{"languageCode":"fr"}""") }
        // no Afrikaans name for the poutine: the bill reads its English one
        transaction { Translations.set(Translations.ITEM, "poutine", "af", null) }
        val checkId = json.parseToJsonElement(c.post("/tables/t3/checks").bodyAsText()).jsonObject["id"]!!.jsonPrimitive.int
        suspend fun line(body: String) {
            val r = c.post("/checks/$checkId/lines") { contentType(ContentType.Application.Json); setBody(body) }
            assertTrue(r.status.isSuccess(), r.bodyAsText())
        }
        line("""{"itemId":"mushroom-burger","variantId":"mushroom-burger:regular","qty":55}""")
        line("""{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":1}""")
        line("""{"itemId":"poutine","variantId":"poutine:regular","qty":1}""")

        val r = c.post("/checks/$checkId/bill?lang=af")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val af = json.parseToJsonElement(r.bodyAsText()).jsonObject["text"]!!.jsonPrimitive.content
        val flat = af.replace("\n", " ").replace(Regex(" +"), " ")
        assertTrue("Totaal" in af && "Subtotaal" in af && "Tafel" in af, af)
        assertTrue("REKENING / CUSTOMER BILL" in af, af)
        assertTrue("Sampioen-en-Switserse-kaasburger" in flat, af)
        assertTrue("(Pint (20 oz))" in af, af)
        assertTrue("Classic Poutine" in af, af) // English fallback, not French
        assertFalse("Poutine classique" in af, af)
        // money: 1,234.56 style (the paper's amount column), grouped by commas;
        // no rand, no decimal commas, and the same amounts as the English bill
        assertTrue(Regex("""\b1,\d{3}\.\d{2}\b""").containsMatchIn(af), "a grouped total expected:\n$af")
        assertFalse(Regex("""\bR ?\d""").containsMatchIn(af), af)
        assertFalse(Regex("""\d,\d{2}(?!\d)""").containsMatchIn(af), af)
        val en = json.parseToJsonElement(c.post("/checks/$checkId/bill?lang=en").bodyAsText()).jsonObject["text"]!!.jsonPrimitive.content
        val amounts = Regex("""\d{1,3}(,\d{3})*\.\d{2}\b""")
        assertEquals(amounts.findAll(en).map { it.value }.toList(), amounts.findAll(af).map { it.value }.toList())
        assertTrue(af.lines().none { it.length > 48 }, af)
    }

    @Test
    fun theAiRepliesInAfrikaans() {
        for (r in AiGuard.Refusal.entries) {
            val reply = AiGuard.reply(r, "af")
            assertTrue(reply != AiGuard.reply(r, "en"), "$r has no Afrikaans reply")
        }
        assertTrue("spyskaart" in AiGuard.reply(AiGuard.Refusal.OFF_TOPIC, "af"))
    }
}
