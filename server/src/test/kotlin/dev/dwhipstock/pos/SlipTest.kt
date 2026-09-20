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
    fun printableSlipsAreOpenAndCoverAllTables() = testApplication {
        application { module(dbPath = tempDb()) }

        // single slip, unauthenticated (copy-shop friendly)
        val slip = client.get("/tables/t5/slip")
        assertEquals(HttpStatusCode.OK, slip.status)
        val slipHtml = slip.bodyAsText()
        assertTrue("/tables/t5/qr" in slipHtml)
        assertTrue("Scannez pour commander de la nourriture" in slipHtml && "Scan to order" in slipHtml)

        assertEquals(HttpStatusCode.NotFound, client.get("/tables/nope/slip").status)

        // print-all page: one slip per seeded table, VIP name override shown.
        // Count is derived from the DB (seed + migrations) so table-layout migrations
        // don't require touching this assertion.
        val tableCount = transaction { DiningTables.selectAll().count().toInt() }
        val all = client.get("/slips").bodyAsText()
        assertEquals(tableCount, Regex("class=\"slip\"").findAll(all).count())
        assertTrue("Alex Morgan" in all)
    }
}
