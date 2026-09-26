package dev.dwhipstock.pos

import dev.dwhipstock.pos.customers.sagepoppy.SagePoppy
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The staff web app wears the store's brand skin; the pubs' page is untouched. */
class StaffAppBrandTest {
    private fun tempDb() = Files.createTempDirectory("pos-brand").resolve("pos.db").toString()

    @Test
    fun sagePoppyGetsItsOwnSkinAndFontFromTheStore() = testApplication {
        application {
            module(dbPath = tempDb(), receiptsDir = Files.createTempDirectory("rc").toString(),
                venueId = SagePoppy.VENUE_ID, sagePoppy = true, physicalPrinterEnabled = false)
        }
        val page = client.get("/staff-app").bodyAsText()
        assertTrue("<body class=\"brand-sp\">" in page)
        assertTrue("<title>Sage &amp; Poppy — Staff</title>" in page)
        assertTrue("Welcome back" in page && "/staff-app/fonts/PlusJakartaSans-400.ttf" in page)
        assertFalse("Copper Lantern POS</div>" in page)
        val font = client.get("/staff-app/fonts/PlusJakartaSans-700.ttf")
        assertEquals(HttpStatusCode.OK, font.status)
        assertTrue(font.readRawBytes().size > 10_000)
        assertEquals(HttpStatusCode.NotFound, client.get("/staff-app/fonts/nope.ttf").status)
    }

    @Test
    fun thePubsPageIsUnchanged() = testApplication {
        application {
            module(dbPath = tempDb(), receiptsDir = Files.createTempDirectory("rc").toString(), physicalPrinterEnabled = false)
        }
        val page = client.get("/staff-app").bodyAsText()
        assertEquals(StoreAssets.readText("staff-app.html"), page)
    }
}
