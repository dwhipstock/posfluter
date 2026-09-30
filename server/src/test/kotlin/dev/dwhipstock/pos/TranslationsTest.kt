package dev.dwhipstock.pos

import dev.dwhipstock.pos.api.slipLocales
import dev.dwhipstock.pos.base.Translations
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternConfig
import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import dev.dwhipstock.pos.sdk.i18n.MessageKey
import dev.dwhipstock.pos.sdk.i18n.Messages
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Copper Lantern's extra languages: the tablet offers en, fr, es, de, af; names
 * beyond the en / fr catalog slots come from the translations table (seeded
 * for the demo in es, de and af) and fall back to English, then French.
 */
class TranslationsTest {
    private fun tempDir(prefix: String) = Files.createTempDirectory(prefix).toString()
    private fun ApplicationTestBuilder.store() = application {
        module(dbPath = tempDir("pos-translations") + "/pos.db", photosDir = tempDir("photos"))
    }
    private fun arr(text: String) = Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }
    private fun JsonObject.names() = this["names"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()

    @Test
    fun fallbackIsTheLanguageThenEnglishThenFrench() {
        val extra = mapOf("de" to "Klassische Poutine", "es" to " ")
        assertEquals("Poutine classique", Translations.pick("fr", "Poutine classique", "Classic Poutine", extra))
        assertEquals("Classic Poutine", Translations.pick("en", "Poutine classique", "Classic Poutine", extra))
        assertEquals("Klassische Poutine", Translations.pick("de", "Poutine classique", "Classic Poutine", extra))
        // blank or missing: English, and French when there is no English
        assertEquals("Classic Poutine", Translations.pick("es", "Poutine classique", "Classic Poutine", extra))
        assertEquals("Classic Poutine", Translations.pick("it", "Poutine classique", "Classic Poutine", null))
        assertEquals("Poutine classique", Translations.pick("de", "Poutine classique", "", null))
    }

    @Test
    fun copperLanternSpeaksFiveLanguagesButSlipsAreEnglishOnly() = testApplication {
        store()
        val health = Json.parseToJsonElement(client.get("/health").bodyAsText()).jsonObject
        assertEquals(listOf("en", "fr", "es", "de", "af"), health["locales"]!!.jsonArray.map { it.jsonPrimitive.content })
        val config = CopperLanternConfig(
            settings = dev.dwhipstock.pos.base.SettingsRepository(),
            printer = dev.dwhipstock.pos.sdk.PrinterAdapter.VirtualPrinter(tempDir("r"), tempDir("b")),
            publicBaseUrl = "http://test",
        )
        // Raleigh: guest slips are English only, like the receipts
        assertEquals(listOf(LocaleCode.EN), slipLocales(config))
        // a German server's receipt reads German
        assertEquals("Summe", Messages.get(MessageKey.RECEIPT_TOTAL, LocaleCode.DE))
    }

    @Test
    fun seededNamesReachTheTabletAndManagersKeepTheirEdits() = testApplication {
        store()
        val manager = loginClient()
        val poutine = arr(manager.get("/items").bodyAsText()).first { it["id"]!!.jsonPrimitive.content == "poutine" }
        assertEquals("Klassische Poutine", poutine.names()["de"])
        assertEquals(setOf("es", "de", "af"), poutine.names().keys)
        val starters = arr(manager.get("/categories").bodyAsText()).first { it["id"]!!.jsonPrimitive.content == "starters" }
        assertEquals("Vorspeisen", starters.names()["de"])
        val zones = arr(manager.get("/zones").bodyAsText())
        val lower = zones.first { it["id"]!!.jsonPrimitive.content == "lower" }
        assertEquals("Sala de juegos", lower.names()["es"])
        val pool = lower["objects"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "lower-pool" }
        assertEquals("Billard", pool.names()["de"])

        // a manager's own name survives the next boot's seeding
        transaction { Translations.set(Translations.ITEM, "poutine", "de", "Poutine nach Art des Hauses") }
        dev.dwhipstock.pos.customers.copperlantern.CopperLanternSeed.seedTranslations()
        assertEquals("Poutine nach Art des Hauses", transaction { Translations.get(Translations.ITEM, "poutine", "de") })
        // clearing one removes the row (the screens then read English)
        transaction { Translations.set(Translations.ITEM, "poutine", "es", "") }
        assertNull(transaction { Translations.get(Translations.ITEM, "poutine", "es") })
    }
}
