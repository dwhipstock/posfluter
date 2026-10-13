package dev.dwhipstock.pos

import dev.dwhipstock.pos.customers.barcode.Barcode
import dev.dwhipstock.pos.customers.sagepoppy.SagePoppy
import dev.dwhipstock.pos.restaurant.DiningTables
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The guest pages (scan-to-order menu, its 404 card, the pickup board) wear the store's brand skin. */
class GuestPageBrandTest {
    private fun tempDb() = Files.createTempDirectory("pos-guest-brand").resolve("pos.db").toString()
    private fun rc() = Files.createTempDirectory("rc").toString()

    /** Any table on this store's floor, for its guest link. */
    private suspend fun ApplicationTestBuilder.anyTableId(): String {
        startApplication()
        return transaction { DiningTables.selectAll().first()[DiningTables.id] }
    }

    @Test
    fun barcodeMenuGetsTheSkinTheNameAndTheThaiFont() = testApplication {
        application {
            module(dbPath = tempDb(), receiptsDir = rc(), venueId = Barcode.VENUE_ID, barcode = true,
                physicalPrinterEnabled = false)
        }
        val page = client.get(customerPath(anyTableId())).bodyAsText()
        assertTrue("<body class=\"brand-bc\">" in page)
        assertTrue("Barcode Pub guest skin" in page)
        assertTrue("<title>Barcode Pub — Order Online</title>" in page)
        assertTrue("<h1>Barcode Pub</h1>" in page)
        assertTrue("--bc-night: #1a1816" in page && "--bc-yellow: #f7cd46" in page && "--accent: #7a5a00" in page)
        assertFalse("Copper Lantern" in page.substringBefore("<script>"))
        // Thai from the store, nothing from the internet
        assertTrue("/m/fonts/NotoSansThai-Regular.ttf" in page)
        assertFalse("fonts.googleapis" in page || "https://" in page.substringBefore("<script>"))
        val font = client.get("/m/fonts/NotoSansThai-Bold.ttf")
        assertEquals(HttpStatusCode.OK, font.status)
        assertTrue(font.readRawBytes().size > 10_000)
        assertEquals(HttpStatusCode.NotFound, client.get("/m/fonts/OFL-NotoSansThai.txt").status)
        // a dead link's calm card wears it too
        val card = client.get("/m/t/AAAAAAAAAAAAAAAAAAAAAA").bodyAsText()
        assertTrue("<body class=\"brand-bc\">" in card && "Barcode Pub" in card)
    }

    @Test
    fun barcodePickupBoardShowsTheVenueNotCopperLantern() = testApplication {
        application {
            module(dbPath = tempDb(), receiptsDir = rc(), venueId = Barcode.VENUE_ID, barcode = true,
                physicalPrinterEnabled = false)
        }
        val board = client.get("/pickup").bodyAsText()
        assertTrue("<body class=\"brand-bc\">" in board)
        assertTrue("<h1 id=\"venue\">Barcode Pub</h1>" in board)
        assertFalse("Copper Lantern" in board)
    }

    @Test
    fun sagePoppyMenuGetsItsOwnSkin() = testApplication {
        application {
            module(dbPath = tempDb(), receiptsDir = rc(), venueId = SagePoppy.VENUE_ID, sagePoppy = true,
                physicalPrinterEnabled = false)
        }
        val card = client.get("/m/t/AAAAAAAAAAAAAAAAAAAAAA").bodyAsText()
        assertTrue("<body class=\"brand-sp\">" in card && "/staff-app/fonts/PlusJakartaSans-400.ttf" in card)
        assertTrue(GuestPageBrand.menu("<head></head><body>", "sage-poppy").contains("Sage & Poppy guest skin"))
    }

    @Test
    fun thePubsMenuIsNotSkinned() = testApplication {
        application { module(dbPath = tempDb(), receiptsDir = rc(), physicalPrinterEnabled = false) }
        val page = client.get(customerPath("t5")).bodyAsText()
        assertTrue("<body>" in page)
        assertFalse("brand-bc" in page || "brand-sp" in page)
        assertTrue("--accent: #1565c0" in page)
    }
}
