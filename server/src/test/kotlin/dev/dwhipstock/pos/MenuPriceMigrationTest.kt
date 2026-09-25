package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternSeed
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue
import dev.dwhipstock.pos.db.MenuPriceMigration
import dev.dwhipstock.pos.db.Migrations
import dev.dwhipstock.pos.db.SyncOutbox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Migration 037: the demo menu drops ~10% now that tax goes on top. Existing
 * stores are repriced in place, but only where the price is still the old
 * seeded one — a manager's edit is never overwritten.
 */
class MenuPriceMigrationTest {

    private fun tempDb() = Files.createTempDirectory("pos-mig037").resolve("pos.db").toString()
    private fun connect(path: String): Database =
        Database.connect(SQLiteDataSource().apply { url = "jdbc:sqlite:$path" })

    private fun prices(db: Database) = transaction(db) {
        ItemVariants.selectAll().associate { it[ItemVariants.id] to it[ItemVariants.priceCents] }
    }

    private fun priceEvents(db: Database) = transaction(db) {
        SyncOutbox.selectAll().filter { it[SyncOutbox.eventType] == "item.variant_updated" }
            .map { Json.parseToJsonElement(it[SyncOutbox.payload]).jsonObject }
    }

    @Test
    fun seedAndMigrationAgreeOnTheNewPrices() {
        for (venue in CopperLanternVenue.entries) {
            val db = connect(tempDb())
            Migrations.run(db)
            CopperLanternSeed.seedIfEmpty(venue)
            val seeded = prices(db)
            assertTrue(seeded.isNotEmpty())
            for ((variant, cents) in seeded) {
                assertEquals(MenuPriceMigration.PRICES[variant]?.second, cents, "$variant at ${venue.name}")
            }
            // new menu prices look like menu prices: .00 / .25 / .50 / .75 / .95
            assertTrue(seeded.values.all { it % 100 in setOf(0L, 25L, 50L, 75L, 95L) })
        }
        for ((variant, p) in MenuPriceMigration.PRICES) {
            val (old, new) = p
            assertTrue(new < old && new >= old * 85 / 100, "$variant: $old → $new is about 10% off")
        }
    }

    @Test
    fun oldSeededPricesDropButAManagersEditIsKept() {
        val path = tempDb()
        val db = connect(path)
        Migrations.run(db, through = 36)
        CopperLanternSeed.seedIfEmpty(CopperLanternVenue.PLATEAU)
        // put the store back on the old seeded prices, as a pre-037 install has
        transaction(db) {
            for ((variant, p) in MenuPriceMigration.PRICES) {
                ItemVariants.update({ ItemVariants.id eq variant }) { it[priceCents] = p.first }
            }
            // …except one the manager re-priced on the tablet
            ItemVariants.update({ ItemVariants.id eq "malbec:bottle" }) { it[priceCents] = 5250 }
        }
        val before = prices(db)

        Migrations.run(db)

        val after = prices(db)
        assertEquals(5250L, after["malbec:bottle"], "a manager's price is never overwritten")
        val repriced = before.keys - "malbec:bottle"
        for (variant in repriced) assertEquals(MenuPriceMigration.PRICES.getValue(variant).second, after[variant], variant)

        // one catalog event per repriced variant, carrying the full item snapshot for the portal
        val events = priceEvents(db)
        assertEquals(repriced, events.map { it["variantId"]!!.jsonPrimitive.content }.toSet())
        val lager = events.first { it["variantId"]!!.jsonPrimitive.content == "lantern-lager:pitcher" }
        assertEquals(2025L, lager["priceCents"]!!.jsonPrimitive.long)
        val snapshotPrice = lager["item"]!!.jsonObject["variants"]!!.jsonArray.map { it.jsonObject }
            .first { it["id"]!!.jsonPrimitive.content == "lantern-lager:pitcher" }["priceCents"]!!.jsonPrimitive.long
        assertEquals(2025L, snapshotPrice)

        // re-running reprices nothing (the step is recorded, and nothing is at an old price)
        assertEquals(emptyList(), transaction(db) { MenuPriceMigration.reprice(this) })
    }
}
