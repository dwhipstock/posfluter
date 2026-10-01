package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternExpressSeed
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternSeed
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue
import dev.dwhipstock.pos.db.Migrations
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Migration 060: a counter store's alcohol is age-restricted (an ID check
 * before it is paid, kiosk orders too). A pub is never touched.
 */
class CounterAlcoholMigrationTest {
    private fun connect(): Database = Database.connect(SQLiteDataSource().apply {
        url = "jdbc:sqlite:" + Files.createTempDirectory("pos-mig060").resolve("pos.db")
    })

    private fun restricted(db: Database) = transaction(db) {
        Items.selectAll().where { Items.isAlcohol eq true }.associate { it[Items.id] to it[Items.ageRestricted] }
    }

    @Test
    fun anExistingExpressStoreHasItsBeerAndWineRestrictedAndAPubDoesNot() {
        // an Express store from before 060: seeded, counter zone there, beer not restricted
        val express = connect()
        Migrations.run(express, through = 59)
        transaction(express) {
            CopperLanternExpressSeed.seedIfEmpty()
            exec("INSERT INTO zones (id, name_fr, name_en, sort_order, label_prefix) VALUES ('counter', 'Comptoir', 'Counter', 0, 'C')")
            exec("UPDATE items SET age_restricted = 0")
        }
        assertTrue(restricted(express).values.none { it })
        Migrations.run(express)
        val after = restricted(express)
        assertTrue(after.isNotEmpty() && after.values.all { it }, "$after")
        assertTrue("north-ipa" in after)
        // food is untouched
        transaction(express) {
            assertTrue(Items.selectAll().where { Items.isAlcohol eq false }.none { it[Items.ageRestricted] })
        }
        // an item becoming alcohol later (menu editor, portal sync) follows; and back
        transaction(express) { Items.update({ Items.id eq "brownie" }) { it[isAlcohol] = true } }
        assertEquals(true, restricted(express)["brownie"])
        transaction(express) { Items.update({ Items.id eq "brownie" }) { it[isAlcohol] = false } }
        transaction(express) {
            assertEquals(false, Items.selectAll().where { Items.id eq "brownie" }.single()[Items.ageRestricted])
        }
        // running it again changes nothing (idempotent)
        transaction(express) { exec("DELETE FROM schema_migrations WHERE version = 60") }
        Migrations.run(express)
        assertEquals(after, restricted(express))

        // a pub: its beer, wine and cocktails stay unrestricted (table checks are never gated)
        val pub = connect()
        Migrations.run(pub, through = 59)
        transaction(pub) { CopperLanternSeed.seedIfEmpty(CopperLanternVenue.VIEUX_PORT) }
        Migrations.run(pub)
        val pubAlcohol = restricted(pub)
        assertTrue(pubAlcohol.isNotEmpty() && pubAlcohol.values.none { it }, "$pubAlcohol")
    }

    @Test
    fun aNewExpressStoreIsSeededWithItsAlcoholRestricted() {
        val db = connect()
        Migrations.run(db)
        CopperLanternExpressSeed.seedIfEmpty()
        val r = restricted(db)
        assertTrue(r.isNotEmpty() && r.values.all { it }, "$r")
    }
}
