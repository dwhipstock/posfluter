package dev.dwhipstock.pos

import dev.dwhipstock.pos.customers.copperlantern.CopperLanternSeed
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** POS_VENUE picks the store: Vieux-Port (default) or Plateau with its Sushi Bar and specials. */
class VenueSeedTest {

    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    private suspend fun ApplicationTestBuilder.ids(path: String, token: String? = null): List<String> {
        val res = client.get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
        assertEquals(HttpStatusCode.OK, res.status, "$path → ${res.bodyAsText()}")
        return (Json.parseToJsonElement(res.bodyAsText()) as JsonArray).map { it.jsonObject["id"]!!.jsonPrimitive.content }
    }

    private suspend fun ApplicationTestBuilder.login(): String {
        val res = client.post("/login") { contentType(ContentType.Application.Json); setBody("""{"pin":"1234"}""") }
        return Json.parseToJsonElement(res.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    @Test
    fun plateauSeedsTheSharedMenuPlusSushiBarAndSpecials() = testApplication {
        application { module(dbPath = tempDb(), venue = CopperLanternVenue.PLATEAU) }
        val items = ids("/items")
        assertTrue(items.containsAll(listOf("lantern-lager", "poutine", "salmon-maki", "tuna-nigiri", "junmai-sake", "smoked-meat-poutine")))
        assertEquals(CopperLanternSeed.menuItemIds(CopperLanternVenue.PLATEAU).toSet(), items.toSet())
        val categories = ids("/categories")
        assertEquals(listOf("sushi", "plateau-specials"), categories.takeLast(2), "Plateau categories sort after the shared ones")
        val zones = ids("/zones", login())
        assertEquals(listOf("upper", "outside", "lower", "sushi"), zones)
        val settings = dev.dwhipstock.pos.base.SettingsRepository().get()
        assertEquals(CopperLanternVenue.PLATEAU.address, settings.venueAddress)
    }

    @Test
    fun vieuxPortIsTheDefaultAndHasNoSushiBar() = testApplication {
        application { module(dbPath = tempDb()) }
        val items = ids("/items")
        assertTrue("lantern-lager" in items)
        assertTrue(items.none { it in setOf("salmon-maki", "junmai-sake", "smoked-meat-poutine") })
        assertTrue("sushi" !in ids("/categories"))
        assertTrue("sushi" !in ids("/zones", login()))
        assertEquals(CopperLanternVenue.VIEUX_PORT.address, dev.dwhipstock.pos.base.SettingsRepository().get().venueAddress)
    }

    @Test
    fun plateauTablesLiveInTheSushiBar() = testApplication {
        application { module(dbPath = tempDb(), venue = CopperLanternVenue.PLATEAU) }
        val token = login()
        val res = client.get("/zones") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, res.status)
        val zone = Json.parseToJsonElement(res.bodyAsText()).jsonArray.map { it.jsonObject }
            .single { it["id"]!!.jsonPrimitive.content == "sushi" }
        assertEquals("Sushi Bar", zone["nameEn"]!!.jsonPrimitive.content)
        val sushi = zone["tables"]!!.jsonArray.map { it.jsonObject }
        assertEquals(11, sushi.size)
        assertTrue(sushi.all { it["label"]!!.jsonPrimitive.content.startsWith("S-") })
    }

    @Test
    fun venueIdIsParsedStrictly() {
        assertEquals(CopperLanternVenue.VIEUX_PORT, CopperLanternVenue.of(null))
        assertEquals(CopperLanternVenue.VIEUX_PORT, CopperLanternVenue.of(" "))
        assertEquals(CopperLanternVenue.PLATEAU, CopperLanternVenue.of("Plateau"))
        assertFailsWith<IllegalArgumentException> { CopperLanternVenue.of("main") }
    }
}
