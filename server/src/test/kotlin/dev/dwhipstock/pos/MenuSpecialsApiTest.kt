package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.ItemSchedules
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternSpecials
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue
import dev.dwhipstock.pos.sdk.VenueClock
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** What the menu API shows of specials: the price now, the menu price, the days; and the editor's PATCH. */
class MenuSpecialsApiTest {
    @AfterTest
    fun resetClock() { ItemSchedules.clock = Clock.systemUTC() }

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) {
        ItemSchedules.clock = Clock.fixed(VenueClock.fromLocal(LocalDateTime.of(y, mo, d, h, mi)), ZoneOffset.UTC)
    }

    private suspend fun ApplicationTestBuilder.items(): Map<String, JsonObject> =
        Json.parseToJsonElement(client.get("/items").bodyAsText()).jsonArray.associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject }

    private suspend fun ApplicationTestBuilder.version(): Long =
        Json.parseToJsonElement(client.get("/menu/version").bodyAsText()).jsonObject["version"]!!.jsonPrimitive.long

    @Test
    fun `items show the price now, the menu price and the days`() = testApplication {
        application { module(dbPath = Files.createTempDirectory("pos-specials-api").resolve("pos.db").toString()) }
        client.get("/health")
        CopperLanternSpecials.seed(CopperLanternVenue.VIEUX_PORT, enabled = true)

        at(2026, 10, 6, 17) // Tuesday, happy hour
        val v1 = version()
        var all = items()
        val pint = all["lantern-lager"]!!["variants"]!!.jsonArray.first { it.jsonObject["id"]!!.jsonPrimitive.content == "lantern-lager:pint" }.jsonObject
        assertEquals(500, pint["priceCents"]!!.jsonPrimitive.long)
        assertEquals(750, pint["regularPriceCents"]!!.jsonPrimitive.long)
        assertEquals("16:00", pint["special"]!!.jsonObject["from"]!!.jsonPrimitive.content)
        val pitcher = all["lantern-lager"]!!["variants"]!!.jsonArray.first { it.jsonObject["id"]!!.jsonPrimitive.content == "lantern-lager:pitcher" }.jsonObject
        assertNull(pitcher["regularPriceCents"])
        assertEquals(1495, all["lantern-burger"]!!["variants"]!!.jsonArray[0].jsonObject["priceCents"]!!.jsonPrimitive.long)
        // prime rib: listed (staff screens grey it), not sold on a Tuesday
        assertFalse(all["prime-rib"]!!["availableNow"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("fri", "sat"), all["prime-rib"]!!["availableDays"]!!.jsonArray.map { it.jsonPrimitive.content })
        // a plain item carries none of it
        assertNull(all["amber-ale"]!!["specials"]); assertNull(all["amber-ale"]!!["availableNow"])

        // 6:01 pm: happy hour is over, and the menu version moved so the screens reload
        at(2026, 10, 6, 18, 1)
        assertNotEquals(v1, version())
        all = items()
        assertEquals(750, all["lantern-lager"]!!["variants"]!!.jsonArray[0].jsonObject["priceCents"]!!.jsonPrimitive.long)
        assertNull(all["lantern-lager"]!!["variants"]!!.jsonArray[0].jsonObject["regularPriceCents"])
        // the editor still sees every special
        assertEquals(1, (all["lantern-lager"]!!["specials"] as JsonArray).size)
    }

    @Test
    fun `the menu editor sets and clears specials with a PATCH`() = testApplication {
        application { module(dbPath = Files.createTempDirectory("pos-specials-api").resolve("pos.db").toString()) }
        val c = loginClient()
        val r = c.patch("/items/mushroom-burger") {
            contentType(ContentType.Application.Json)
            setBody("""{"availableDays":["sun"],"specials":[{"days":["sun"],"from":"11:00","to":"14:00","label":"Brunch","prices":{"mushroom-burger:regular":1500}}]}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals("Brunch", body["specials"]!!.jsonArray[0].jsonObject["label"]!!.jsonPrimitive.content)
        // a bad size is a 400 with the reason
        val bad = c.patch("/items/mushroom-burger") {
            contentType(ContentType.Application.Json)
            setBody("""{"specials":[{"days":["sun"],"prices":{"lantern-lager:pint":1}}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        // clearing
        c.patch("/items/mushroom-burger") { contentType(ContentType.Application.Json); setBody("""{"availableDays":[],"specials":[]}""") }
        assertNull(items()["mushroom-burger"]!!["specials"])
    }
}
