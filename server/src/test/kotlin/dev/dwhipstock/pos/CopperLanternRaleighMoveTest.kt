package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.Categories
import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.Translations
import dev.dwhipstock.pos.base.Users
import dev.dwhipstock.pos.base.VenueSettings
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternExpressSeed
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternRaleighMove
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternSeed
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue
import dev.dwhipstock.pos.db.Migrations
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.db.SyncState
import dev.dwhipstock.pos.restaurant.DiningTables
import dev.dwhipstock.pos.restaurant.FloorObjects
import dev.dwhipstock.pos.restaurant.KitchenConfigTable
import dev.dwhipstock.pos.restaurant.Zones
import dev.dwhipstock.pos.sync.CloudSync
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
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
 * Copper Lantern moved from Montréal to Raleigh, NC. A store seeded the old
 * (Montréal) way — with AI photos, a manager's menu edits, an extra room and
 * table, extra staff and an old GST/QST sale — is moved in place on startup:
 * the receipt header, zone, kitchen-ticket language and Québec menu names
 * change; nothing is deleted; a second run changes nothing.
 */
class CopperLanternRaleighMoveTest {

    private fun tempDb() = Files.createTempDirectory("pos-raleigh").resolve("pos.db").toString()
    private fun connect(path: String): Database =
        Database.connect(SQLiteDataSource().apply { url = "jdbc:sqlite:$path" })

    private val montreal = mapOf(
        CopperLanternVenue.VIEUX_PORT to ("47, rue de la Lanterne, Montréal (Québec) H2Y 1Q7" to "+1 514 555 0142"),
        CopperLanternVenue.PLATEAU to ("212, avenue du Lampion, Montréal (Québec) H2J 3U4" to "+1 514 555 0187"),
        CopperLanternVenue.EXPRESS to ("9, rue du Fanal, Montréal (Québec) H2X 2Q8" to "+1 514 555 0163"),
    )

    /** What the store holds that the move must never lose. */
    private data class Kept(
        val items: Map<String, Pair<String?, Boolean>>, // id → (photo, active)
        val prices: Map<String, Long>,
        val categories: Int, val zones: Int, val tables: Int, val floorObjects: Int, val users: Int, val checks: Int,
    )

    private fun kept(): Kept = transaction {
        var checks = 0
        exec("SELECT COUNT(*) FROM checks") { rs -> if (rs.next()) checks = rs.getInt(1) }
        Kept(
            items = Items.selectAll().associate { it[Items.id] to (it[Items.photoPath] to it[Items.active]) },
            prices = ItemVariants.selectAll().associate { it[ItemVariants.id] to it[ItemVariants.priceCents] },
            categories = Categories.selectAll().count().toInt(),
            zones = Zones.selectAll().count().toInt(),
            tables = DiningTables.selectAll().count().toInt(),
            floorObjects = FloorObjects.selectAll().count().toInt(),
            users = Users.selectAll().count().toInt(),
            checks = checks,
        )
    }

    private fun item(id: String) = transaction { Items.selectAll().where { Items.id eq id }.single() }
    private fun name(id: String, lang: String) = transaction { Translations.get(Translations.ITEM, id, lang) }
    private fun settings() = transaction { VenueSettings.selectAll().single() }

    /**
     * A database as a Montréal-era store has it: today's seed for [venue], put
     * back on every old Montréal value the move knows (menu text, extra-language
     * names, receipt header and footer, zone, kitchen language), plus the
     * things a live demo store gathered since.
     */
    private fun oldMontrealStore(path: String, venue: CopperLanternVenue) {
        val db = connect(path)
        Migrations.run(db)
        if (venue.quickServe) CopperLanternExpressSeed.seedIfEmpty() else CopperLanternSeed.seedIfEmpty(venue)
        CopperLanternSeed.seedTranslations(express = venue.quickServe)
        transaction(db) {
            for (c in CopperLanternRaleighMove.ITEM_CHANGES) {
                exec("UPDATE items SET ${c.column} = '${c.old.replace("'", "''")}' WHERE id = '${c.itemId}'")
            }
            for (c in CopperLanternRaleighMove.NAME_CHANGES) {
                val present = Items.selectAll().where { Items.id eq c.itemId }.any()
                // Express's own names are the second old spelling where there are two
                if (present) Translations.set(Translations.ITEM, c.itemId, c.lang,
                    if (venue.quickServe) c.old.last() else c.old.first(), sync = false)
            }
            val (address, phone) = montreal.getValue(venue)
            VenueSettings.update({ VenueSettings.id eq 1 }) {
                it[venueAddress] = address
                it[venuePhone] = phone
                it[receiptFooter] = "Merci de votre visite ! · Thank you for visiting!"
                it[timezone] = "America/Toronto"
            }
            KitchenConfigTable.set("language", "both")
            // AI photos on renamed and untouched items; an 86'd item
            Items.update({ Items.id eq "dry-cider" }) { it[photoPath] = "ai/dry-cider.webp" }
            Items.update({ Items.id eq "lantern-lager" }) { it[photoPath] = "ai/lantern-lager.webp" }
            Items.update({ Items.id eq "brownie" }) { it[active] = false }
            // a manager's own edits: a renamed Reuben (pub) and a new price
            Items.update({ Items.id eq "reuben" }) { it[nameEn] = "The Big Reuben" }
            ItemVariants.update({ ItemVariants.id eq "lantern-lager:pint" }) { it[priceCents] = 799 }
            // a room, a table and a staff member of their own
            if (!venue.quickServe) {
                Zones.insert { it[id] = "loft"; it[nameFr] = "Mezzanine"; it[nameEn] = "Loft"; it[sortOrder] = 9; it[labelPrefix] = "M" }
                DiningTables.insert {
                    it[id] = "m1"; it[zoneId] = "loft"; it[label] = "M-1"; it[sortOrder] = 1
                    it[x] = 10; it[y] = 10; it[width] = 100; it[height] = 100; it[shape] = "ROUND"; it[seats] = 4
                    it[publicToken] = "tok-m1"
                }
            }
            Users.insert { it[id] = "sam"; it[name] = "Sam"; it[role] = "SERVER"; it[pin] = "x"; it[languageCode] = "fr" }
            // an old closed sale, taxed the Québec way (history)
            exec("INSERT INTO checks (table_id, status, opened_by, opened_at, corkage_bottles, locked_taxes_json) VALUES " +
                "('t1', 'CLOSED', 'manager', '2026-09-01T18:00:00Z', 0, " +
                "'[{\"code\":\"GST\",\"labelFr\":\"TPS\",\"labelEn\":\"GST\",\"ratePercent\":\"5\",\"registrationNumber\":\"123456789 RT0001\",\"amountCents\":101}]')")
            SyncOutbox.deleteAll()
        }
    }

    @Test
    fun anOldMontrealPubMovesToRaleighAndKeepsEverything() {
        val path = tempDb()
        oldMontrealStore(path, CopperLanternVenue.VIEUX_PORT)
        val before = kept()

        val first = CopperLanternRaleighMove.run(CopperLanternVenue.VIEUX_PORT)
        assertTrue(first.changed)

        // the receipt header, zone and footer
        val s = settings()
        assertEquals("412 Lantern Row, Raleigh, NC 27601", s[VenueSettings.venueAddress])
        assertEquals("(919) 555-0142", s[VenueSettings.venuePhone])
        assertEquals("Thank you for visiting!", s[VenueSettings.receiptFooter])
        assertEquals("America/New_York", s[VenueSettings.timezone])
        assertEquals("America/New_York", dev.dwhipstock.pos.sdk.VenueClock.zone.id)
        assertEquals(setOf("venueAddress", "venuePhone", "receiptFooter", "timezone"), first.settings.toSet())
        // kitchen tickets: back to the store's default language (English)
        assertEquals("", transaction { KitchenConfigTable.get("language") })

        // the Québec names are gone, in every language
        assertEquals("Orchard Dry Cider", item("dry-cider")[Items.nameEn])
        assertEquals("Cidre sec du verger", item("dry-cider")[Items.nameFr])
        assertEquals("Crisp dry cider made with orchard apples.", item("dry-cider")[Items.descriptionEn])
        assertEquals("American Lager", item("canadian-lager")[Items.nameEn])
        assertEquals("Oregon Pinot Noir", item("pinot-noir")[Items.nameEn])
        assertEquals("Yadkin Valley Rosé", item("rose")[Items.nameEn])
        assertEquals("Smoked Bloody Mary", item("smoked-caesar")[Items.nameEn])
        assertEquals("Harvest Salad", item("harvest-salad")[Items.nameEn])
        assertEquals("Crisp, malty lager brewed in Raleigh.", item("lantern-lager")[Items.descriptionEn])
        assertEquals("Sidra seca de huerto", name("dry-cider", "es"))
        assertEquals("Trockener Obstgarten-Cidre", name("dry-cider", "de"))
        assertEquals("Droë boordsider", name("dry-cider", "af"))
        assertEquals("Reuben clásico", name("reuben", "es"))
        val place = Regex("Montr[ée]al|Québec|Cantons-de-l|Eastern Townships|Montérégie|Canadian|canadienne")
        transaction {
            for (row in Items.selectAll()) {
                val id = row[Items.id]
                if (id == "reuben") continue // the manager's name, below
                for (t in listOf(row[Items.nameFr], row[Items.nameEn], row[Items.descriptionFr], row[Items.descriptionEn])) {
                    assertFalse(place.containsMatchIn(t), "$id: $t")
                }
            }
            for (row in Translations.selectAll()) assertFalse(place.containsMatchIn(row[Translations.text]), row.toString())
        }
        // poutine stays
        assertEquals("Classic Poutine", item("poutine")[Items.nameEn])

        // a manager's edit is theirs: the name stays, the untouched French side moves
        assertEquals("The Big Reuben", item("reuben")[Items.nameEn])
        assertEquals("Reuben classique", item("reuben")[Items.nameFr])

        // nothing lost: photos, 86'd items, prices, categories, rooms, tables, props, staff, sales
        assertEquals(before, kept())
        assertEquals("ai/dry-cider.webp", item("dry-cider")[Items.photoPath])
        assertEquals(799L, before.prices["lantern-lager:pint"])
        // the old sale keeps its GST as history
        var oldTaxes = ""
        transaction { exec("SELECT locked_taxes_json FROM checks WHERE status = 'CLOSED'") { rs -> if (rs.next()) oldTaxes = rs.getString(1) } }
        assertTrue("\"GST\"" in oldTaxes)

        // a never-synced store sends nothing now (its first sync snapshots it all)
        assertTrue(transaction { SyncOutbox.selectAll().none { it[SyncOutbox.eventType] == "catalog.snapshot" } })
        assertTrue(transaction { SyncState.get(CopperLanternRaleighMove.STATE_KEY) } != null)

        // idempotent: a second run finds nothing to change
        val second = CopperLanternRaleighMove.run(CopperLanternVenue.VIEUX_PORT)
        assertFalse(second.changed, second.toString())
        assertEquals(before, kept())
        assertEquals("412 Lantern Row, Raleigh, NC 27601", settings()[VenueSettings.venueAddress])
    }

    @Test
    fun theExpressCounterMovesWithItsOwnAddressAndNames() {
        oldMontrealStore(tempDb(), CopperLanternVenue.EXPRESS)
        val before = kept()
        assertTrue(CopperLanternRaleighMove.run(CopperLanternVenue.EXPRESS).changed)
        assertEquals("418 Lantern Row, Raleigh, NC 27601", settings()[VenueSettings.venueAddress])
        assertEquals("(919) 555-0163", settings()[VenueSettings.venuePhone])
        assertEquals("Oregon Pinot Noir", item("pinot-noir")[Items.nameEn])
        assertEquals("Pinot Noir de Oregón", name("pinot-noir", "es"))
        assertEquals("Finger Lakes-Riesling", name("riesling", "af"))
        assertEquals(before, kept())
        assertFalse(CopperLanternRaleighMove.run(CopperLanternVenue.EXPRESS).changed)
    }

    @Test
    fun aSyncedStoreSendsTheRenamedItemsUpOnce() {
        oldMontrealStore(tempDb(), CopperLanternVenue.PLATEAU)
        transaction { SyncState.set(CloudSync.CATALOG_SNAPSHOT_SEQ, "1") }
        CopperLanternRaleighMove.run(CopperLanternVenue.PLATEAU)
        assertEquals("430 Lantern Row, Raleigh, NC 27601", settings()[VenueSettings.venueAddress])
        val snapshots = transaction {
            SyncOutbox.selectAll().filter { it[SyncOutbox.eventType] == "catalog.snapshot" }
                .map { Json.parseToJsonElement(it[SyncOutbox.payload]).jsonObject }
        }
        assertEquals(1, snapshots.size)
        assertEquals("raleigh", snapshots.single()["reason"]!!.jsonPrimitive.content)
        val ids = snapshots.single()["items"]!!.jsonArray.map { it.jsonObject["itemId"]?.jsonPrimitive?.content ?: it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue("dry-cider" in ids && "reuben" in ids, ids.toString())
        // nothing more on a second boot
        CopperLanternRaleighMove.run(CopperLanternVenue.PLATEAU)
        assertEquals(1, transaction { SyncOutbox.selectAll().count { it[SyncOutbox.eventType] == "catalog.snapshot" } })
    }

    @Test
    fun anOwnersOwnSettingsAndAFreshStoreAreLeftAlone() {
        // a fresh Raleigh store: nothing Montréal to move (the seeded 042 footer aside)
        connect(tempDb()).also { Migrations.run(it) }
        CopperLanternSeed.seedIfEmpty(CopperLanternVenue.VIEUX_PORT)
        transaction { VenueSettings.update({ VenueSettings.id eq 1 }) { it[timezone] = "America/New_York" } }
        val fresh = CopperLanternRaleighMove.run(CopperLanternVenue.VIEUX_PORT)
        assertTrue(fresh.items.isEmpty() && fresh.names == 0, fresh.toString())
        assertEquals("412 Lantern Row, Raleigh, NC 27601", settings()[VenueSettings.venueAddress])

        // an owner's own address, phone and zone stay
        transaction {
            VenueSettings.update({ VenueSettings.id eq 1 }) {
                it[venueAddress] = "88 Oak St, Durham, NC 27701"
                it[venuePhone] = "(919) 555-0199"
                it[timezone] = "America/Chicago"
                it[receiptFooter] = "See you soon!"
            }
            KitchenConfigTable.set("language", "fr")
        }
        assertFalse(CopperLanternRaleighMove.run(CopperLanternVenue.VIEUX_PORT).changed)
        val s = settings()
        assertEquals("88 Oak St, Durham, NC 27701", s[VenueSettings.venueAddress])
        assertEquals("(919) 555-0199", s[VenueSettings.venuePhone])
        assertEquals("America/Chicago", s[VenueSettings.timezone])
        assertEquals("See you soon!", s[VenueSettings.receiptFooter])
        assertEquals("fr", transaction { KitchenConfigTable.get("language") })
    }

    @Test
    fun theStoreMovesOnStartup() = testApplication {
        val path = tempDb()
        oldMontrealStore(path, CopperLanternVenue.VIEUX_PORT)
        application { module(dbPath = path) }
        val c = loginClient()
        val s = Json.parseToJsonElement(c.get("/settings").bodyAsText()).jsonObject
        assertEquals("412 Lantern Row, Raleigh, NC 27601", s["venueAddress"]!!.jsonPrimitive.content)
        assertEquals("(919) 555-0142", s["venuePhone"]!!.jsonPrimitive.content)
        val health = Json.parseToJsonElement(client.get("/health").bodyAsText()).jsonObject
        assertEquals("Copper Lantern — Glenwood South", health["venue"]!!.jsonPrimitive.content)
        assertEquals("USD", health["currency"]!!.jsonPrimitive.content)
        assertEquals("US", health["country"]!!.jsonPrimitive.content)
        assertEquals(21, health["legalAge"]!!.jsonPrimitive.int)
        val items = Json.parseToJsonElement(c.get("/items").bodyAsText()).jsonArray.map { it.jsonObject }
        val cider = items.single { it["id"]!!.jsonPrimitive.content == "dry-cider" }
        assertEquals("Orchard Dry Cider", cider["nameEn"]!!.jsonPrimitive.content)
        // the room and table the owner added are still on the floor plan
        assertTrue(transaction { DiningTables.selectAll().where { (DiningTables.id eq "m1") and (DiningTables.zoneId eq "loft") }.any() })
    }
}
