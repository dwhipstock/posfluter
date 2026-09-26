package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.VenueSettings
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternSeed
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue
import dev.dwhipstock.pos.db.MenuTextMigration
import dev.dwhipstock.pos.db.Migrations
import dev.dwhipstock.pos.db.SyncOutbox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Migration 042: the pub menu's Ontario/Niagara references become Québec ones
 * and the French is reviewed. Running stores change only where the text is
 * still the old seeded text; a fresh store is seeded with the new text.
 */
class MenuTextMigrationTest {

    private fun tempDb() = Files.createTempDirectory("pos-mig042").resolve("pos.db").toString()
    private fun connect(path: String): Database =
        Database.connect(SQLiteDataSource().apply { url = "jdbc:sqlite:$path" })

    private data class Text(val nameFr: String, val nameEn: String, val descFr: String?, val descEn: String?)

    private fun texts(db: Database) = transaction(db) {
        Items.selectAll().associate {
            it[Items.id] to Text(it[Items.nameFr], it[Items.nameEn], it[Items.descriptionFr], it[Items.descriptionEn])
        }
    }

    private fun column(t: Text, column: String) = when (column) {
        "name_fr" -> t.nameFr
        "name_en" -> t.nameEn
        "description_fr" -> t.descFr
        "description_en" -> t.descEn
        else -> error(column)
    }

    private val placeNames = Regex("Ontario|ontarien|Toronto|Niagara|péninsule|Peninsula|clamato")

    @Test
    fun aFreshStoreIsSeededWithTheNewTextAndNoOldPlaceNames() {
        for (venue in CopperLanternVenue.entries) {
            val db = connect(tempDb())
            Migrations.run(db)
            CopperLanternSeed.seedIfEmpty(venue)
            val seeded = texts(db)
            for (c in MenuTextMigration.CHANGES) {
                val item = seeded[c.itemId] ?: continue // Plateau-only ids at Vieux-Port
                assertEquals(c.new, column(item, c.column), "${c.itemId}.${c.column} at ${venue.name}")
            }
            for ((id, t) in seeded) {
                for (s in listOfNotNull(t.nameFr, t.nameEn, t.descFr, t.descEn)) {
                    assertFalse(placeNames.containsMatchIn(s), "$id: $s")
                }
            }
            transaction(db) {
                assertTrue(ItemVariants.selectAll().none { it[ItemVariants.labelFr] == MenuTextMigration.OLD_REGULAR_FR })
                val s = VenueSettings.selectAll().single()
                assertEquals(venue.phone, s[VenueSettings.venuePhone])
                assertEquals(venue.address, s[VenueSettings.venueAddress])
                assertEquals("Merci de votre visite ! · Thank you for visiting!", s[VenueSettings.receiptFooter])
            }
        }
    }

    @Test
    fun oldSeededTextIsUpdatedButAManagersEditIsKept() {
        val db = connect(tempDb())
        Migrations.run(db, through = 41)
        seedOldStore(db, CopperLanternVenue.PLATEAU)
        // put the store back on the old seeded text, as a pre-042 install has
        transaction(db) {
            for (c in MenuTextMigration.CHANGES) {
                exec("UPDATE items SET ${c.column} = '${c.old.replace("'", "''")}' WHERE id = '${c.itemId}'")
            }
            for (v in MenuTextMigration.REGULAR_VARIANTS) {
                ItemVariants.update({ ItemVariants.id eq v }) { it[labelFr] = MenuTextMigration.OLD_REGULAR_FR }
            }
            // …except a name the manager changed on the tablet
            Items.update({ Items.id eq "dry-cider" }) { it[nameEn] = "House Cider" }
            // and the 006 placeholders an early store still carries
            VenueSettings.update({ VenueSettings.id eq 1 }) {
                it[venuePhone] = "+1 416 555 0142"
                it[venueAddress] = "47 Lantern Lane, Toronto, ON"
                it[receiptFooter] = "Thank you for visiting!"
            }
            SyncOutbox.deleteAll()
        }

        Migrations.run(db)

        val after = texts(db)
        assertEquals("House Cider", after.getValue("dry-cider").nameEn, "a manager's name is never overwritten")
        assertEquals("Cidre sec du Québec", after.getValue("dry-cider").nameFr)
        assertEquals("Québec Ice Cider", after.getValue("icewine").nameEn)
        for (c in MenuTextMigration.CHANGES) {
            if (c.itemId == "dry-cider" && c.column == "name_en") continue
            assertEquals(c.new, column(after.getValue(c.itemId), c.column), "${c.itemId}.${c.column}")
        }
        transaction(db) {
            val labels = ItemVariants.selectAll().associate { it[ItemVariants.id] to it[ItemVariants.labelFr] }
            for (v in MenuTextMigration.REGULAR_VARIANTS) assertEquals("Standard", labels[v], v)
            val s = VenueSettings.selectAll().single()
            assertEquals("+1 514 555 0142", s[VenueSettings.venuePhone])
            // 042 moved it to Montréal; 043 then gave it the French-style address
            assertEquals(CopperLanternVenue.VIEUX_PORT.address, s[VenueSettings.venueAddress])
        }

        // one item.updated per changed item, with the full snapshot for the portal
        val events = transaction(db) {
            SyncOutbox.selectAll().filter { it[SyncOutbox.eventType] == "item.updated" }
                .map { Json.parseToJsonElement(it[SyncOutbox.payload]).jsonObject }
        }
        val ids = events.map { it["itemId"]!!.jsonPrimitive.content }
        assertEquals(ids.size, ids.toSet().size, "one event per item")
        assertTrue("icewine" in ids && "salmon-maki" in ids && "dry-cider" in ids)
        val ice = events.first { it["itemId"]!!.jsonPrimitive.content == "icewine" }["item"]!!.jsonObject
        assertEquals("Cidre de glace du Québec", ice["nameFr"]!!.jsonPrimitive.content)
        val maki = events.first { it["itemId"]!!.jsonPrimitive.content == "salmon-maki" }["item"]!!.jsonObject
        assertEquals("Standard", maki["variants"]!!.jsonArray.single().jsonObject["labelFr"]!!.jsonPrimitive.content)

        // re-running changes nothing
        assertEquals(emptyList(), transaction(db) { MenuTextMigration.update(this) })
    }

    @Test
    fun anOwnerBuiltMenuIsUntouched() {
        val db = connect(tempDb())
        Migrations.run(db)
        transaction(db) {
            exec("INSERT INTO categories (id, sort_order, name_fr, name_en) VALUES ('mine', 0, 'Maison', 'House')")
            exec("INSERT INTO items (id, name_fr, name_en, category_id, abbrev) VALUES ('my-cider', 'Cidre sec', 'Ontario Dry Cider', 'mine', 'MC')")
            SyncOutbox.deleteAll()
        }
        assertEquals(emptyList(), transaction(db) { MenuTextMigration.update(this) })
        assertEquals("Ontario Dry Cider", texts(db).getValue("my-cider").nameEn)
    }
}
