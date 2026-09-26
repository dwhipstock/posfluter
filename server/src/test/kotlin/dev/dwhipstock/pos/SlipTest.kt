package dev.dwhipstock.pos

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
import kotlin.test.assertTrue

class SlipTest {

    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    @Test
    fun printableSlipsNeedATicketAndCoverAllTables() = testApplication {
        application { module(dbPath = tempDb()) }

        // the pages carry every table's link: no ticket (or a bogus one) → 401
        assertEquals(HttpStatusCode.Unauthorized, client.get("/tables/t5/slip").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/slips").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/slips?ticket=nope").status)
        // a staff session mints a ticket the tablet's browser can use
        assertEquals(HttpStatusCode.Unauthorized, client.post("/slips/ticket").status)
        val ticket = loginClient("9999").slipTicket()

        val slip = client.get("/tables/t5/slip?ticket=$ticket")
        assertEquals(HttpStatusCode.OK, slip.status)
        val slipHtml = slip.bodyAsText()
        assertTrue("data:image/png;base64," in slipHtml) // QR inline, no extra fetch
        assertTrue("Balayez pour commander" in slipHtml && "Scan to order" in slipHtml)
        // titled with the store this process runs, not a hardcoded name
        assertTrue("<title>Table slips — Copper Lantern — Vieux-Port</title>" in slipHtml)
        assertTrue("Copper Lantern Pub" !in slipHtml)

        assertEquals(HttpStatusCode.NotFound, client.get("/tables/nope/slip?ticket=$ticket").status)

        // print-all page: one slip per seeded table, VIP name override shown.
        // Count is derived from the DB (seed + migrations) so table-layout migrations
        // don't require touching this assertion.
        val tableCount = transaction { DiningTables.selectAll().count().toInt() }
        val all = client.get("/slips?ticket=$ticket").bodyAsText()
        assertEquals(tableCount, Regex("class=\"slip\"").findAll(all).count())
        assertTrue("Alex Morgan" in all)
    }

    @Test
    fun slipsCarryTheSelectedStoresName() = testApplication {
        application {
            module(dbPath = tempDb(),
                venue = dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue.PLATEAU)
        }
        val all = client.get("/slips?ticket=${loginClient().slipTicket()}").bodyAsText()
        assertTrue("<title>Table slips — Copper Lantern — Plateau</title>" in all)
        assertTrue("Vieux-Port" !in all)
    }
}
