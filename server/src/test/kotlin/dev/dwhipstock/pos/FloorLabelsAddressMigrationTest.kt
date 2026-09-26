package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.VenueSettings
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternSeed
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue
import dev.dwhipstock.pos.db.Migrations
import dev.dwhipstock.pos.restaurant.FloorObjects
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Migration 043: floor-plan captions become bilingual, and the pubs' receipt
 * addresses become French-style Montréal ones. Running stores change only
 * where the value is still the old seeded one; a fresh store is seeded new.
 */
class FloorLabelsAddressMigrationTest {

    private fun tempDb() = Files.createTempDirectory("pos-mig043").resolve("pos.db").toString()
    private fun connect(path: String): Database =
        Database.connect(SQLiteDataSource().apply { url = "jdbc:sqlite:$path" })

    private fun labels(db: Database) = transaction(db) {
        FloorObjects.selectAll().associate { it[FloorObjects.id] to (it[FloorObjects.labelFr] to it[FloorObjects.labelEn]) }
    }

    private fun address(db: Database) = transaction(db) { VenueSettings.selectAll().single()[VenueSettings.venueAddress] }

    private val seeded = mapOf(
        "upper-bar" to ("Bar en cuivre" to "Copper Bar"),
        "lower-pool" to ("Billard" to "Pool"),
        "sushi-counter" to ("Comptoir à sushis" to "Sushi Counter"),
    )

    /** "…, Montréal (Québec) H2Y 1Q7": French style, an H postal code, never "Lantern Lane". */
    private val montrealAddress = Regex("""^\d+, (rue|avenue) [^,]+, Montréal \(Québec\) H\d[A-Z] \d[A-Z]\d$""")

    @Test
    fun aFreshStoreIsSeededWithBothCaptionsAndItsOwnAddress() {
        for (venue in CopperLanternVenue.entries) {
            val db = connect(tempDb())
            Migrations.run(db)
            CopperLanternSeed.seedIfEmpty(venue)
            val l = labels(db)
            assertEquals(seeded["upper-bar"], l["upper-bar"])
            assertEquals(seeded["lower-pool"], l["lower-pool"])
            assertEquals(if (venue == CopperLanternVenue.PLATEAU) seeded["sushi-counter"] else null, l["sushi-counter"])
            assertEquals(venue.address, address(db))
            assertTrue(montrealAddress.matches(venue.address), venue.address)
            assertFalse(Regex("Lantern (Lane|Row)|, QC").containsMatchIn(venue.address), venue.address)
        }
        assertTrue(CopperLanternVenue.VIEUX_PORT.address != CopperLanternVenue.PLATEAU.address, "each pub has its own address")
    }

    @Test
    fun oldSeededValuesAreUpdatedButAnOwnersEditIsKept() {
        val db = connect(tempDb())
        Migrations.run(db, through = 42)
        seedOldStore(db, CopperLanternVenue.PLATEAU)
        transaction(db) {
            // the address a pre-043 Plateau store was seeded with
            exec("UPDATE venue_settings SET venue_address = '212 Lantern Row, Montréal, QC' WHERE id = 1")
            // a caption the manager changed, and a prop of their own
            exec("UPDATE floor_objects SET label = 'Main Bar' WHERE id = 'upper-bar'")
            exec("INSERT INTO floor_objects (id, zone_id, type, label) VALUES ('outside-pool', 'outside', 'POOL', 'Snooker')")
        }

        Migrations.run(db)

        val l = labels(db)
        assertEquals(seeded["lower-pool"], l["lower-pool"])
        assertEquals(seeded["sushi-counter"], l["sushi-counter"])
        assertEquals("Main Bar" to "Main Bar", l["upper-bar"], "a manager's caption reads the same in both languages")
        assertEquals("Snooker" to "Snooker", l["outside-pool"])
        assertEquals(null to null, l["upper-pillar-1"], "pillars stay uncaptioned")
        assertEquals(CopperLanternVenue.PLATEAU.address, address(db))
    }

    @Test
    fun vieuxPortsOldAddressMovesAndAnEditedOneStays() {
        val old = connect(tempDb())
        Migrations.run(old, through = 42)
        transaction(old) { exec("UPDATE venue_settings SET venue_address = '47 Lantern Lane, Montréal, QC' WHERE id = 1") }
        Migrations.run(old)
        assertEquals(CopperLanternVenue.VIEUX_PORT.address, address(old))

        val edited = connect(tempDb())
        Migrations.run(edited, through = 42)
        transaction(edited) { exec("UPDATE venue_settings SET venue_address = '1200, rue Ailleurs, Laval (Québec)' WHERE id = 1") }
        Migrations.run(edited)
        assertEquals("1200, rue Ailleurs, Laval (Québec)", address(edited))
    }
}
