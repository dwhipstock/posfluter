package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.Categories
import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternSeed
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue
import dev.dwhipstock.pos.db.MenuCategoryMigration
import dev.dwhipstock.pos.db.Migrations
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.restaurant.CheckLines
import dev.dwhipstock.pos.restaurant.Checks
import dev.dwhipstock.pos.sdk.VenueClock
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
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

/** Migration 034: the demo's 15 categories fold into 7, in place, on stores that own their menu. */
class MenuCategoryMigrationTest {

    /** The pre-034 demo catalog: old category → its items, in seed order. */
    private val oldLayout = linkedMapOf(
        "draft-beer" to listOf("lantern-lager", "amber-ale", "north-ipa", "maple-stout", "wheat-beer"),
        "bottles-cans" to listOf("canadian-lager", "pilsner-can"),
        "craft-beer" to listOf("porter-can", "hazy-ipa", "saison"),
        "imported-beer" to listOf("belgian-blonde", "irish-stout", "mexican-lager"),
        "cider-na" to listOf("dry-cider", "berry-cider", "na-lager", "hop-water"),
        "red-wine" to listOf("pinot-noir", "cab-merlot", "malbec"),
        "white-wine" to listOf("riesling", "chardonnay", "sauvignon-blanc"),
        "rose-sparkling" to listOf("rose", "sparkling", "icewine"),
        "cocktails" to listOf("copper-old-fashioned", "lantern-mule", "smoked-caesar", "maple-sour", "elderflower-gin", "espresso-martini", "dark-stormy", "zero-gimlet"),
        "appetizers" to listOf("pretzel", "wings", "nachos", "calamari", "spinach-dip", "poutine"),
        "burgers-sandwiches" to listOf("lantern-burger", "mushroom-burger", "veggie-burger", "club", "reuben", "fish-sandwich"),
        "mains" to listOf("fish-chips", "steak-frites", "shepherd-pie", "mac-cheese", "salmon", "chicken-pot-pie"),
        "salads-vegetarian" to listOf("caesar-salad", "harvest-salad", "falafel-bowl", "cauliflower"),
        "desserts" to listOf("sticky-pudding", "cheesecake", "brownie"),
        "late-night" to listOf("late-fries", "mini-burgers", "grilled-cheese", "onion-rings"),
    )
    private val newOrder = listOf("beer-cider", "wine", "cocktails", "starters", "burgers-sandwiches", "mains-salads", "desserts")
    private val plateauExtras = listOf("sushi", "plateau-specials")

    private fun tempDb() = Files.createTempDirectory("pos-mig034").resolve("pos.db").toString()

    private fun connect(path: String): Database =
        Database.connect(SQLiteDataSource().apply { url = "jdbc:sqlite:$path" })

    /**
     * A store as it looked before 034: migrations through 033, the Plateau seed,
     * then the catalog put back into the 15 old categories. Plus the owner's own
     * edits the migration must carry over: a photo, a price, a soft-deleted
     * item, an 86'ed item, and an open check with a line.
     */
    private fun oldPlateauStore(path: String): Int {
        val db = connect(path)
        Migrations.run(db, through = 33)
        CopperLanternSeed.seedIfEmpty(CopperLanternVenue.PLATEAU)
        return transaction(db) {
            for ((old, ids) in oldLayout) for (id in ids) {
                Items.update({ Items.id eq id }) { it[categoryId] = old }
            }
            for (id in newOrder - oldLayout.keys) Categories.deleteWhere { Categories.id eq id }
            Items.update({ Items.id eq "wings" }) { it[photoPath] = "wings.jpg" }
            ItemVariants.update({ ItemVariants.id eq "malbec:bottle" }) { it[priceCents] = 5250 }
            Items.update({ Items.id eq "onion-rings" }) { it[active] = false; it[deletedAt] = VenueClock.now() }
            Items.update({ Items.id eq "saison" }) { it[active] = false }
            // raw SQL: the Checks table object has columns later migrations add (036)
            exec("INSERT INTO checks (table_id, status, opened_by, opened_at, corkage_bottles) " +
                "VALUES ('t1', 'OPEN', 'manager', '${VenueClock.now()}', 0)")
            var checkId = 0
            exec("SELECT MAX(id) FROM checks") { rs -> if (rs.next()) checkId = rs.getInt(1) }
            CheckLines.insert {
                it[CheckLines.checkId] = checkId; it[itemId] = "late-fries"; it[variantId] = "late-fries:regular"
                it[qty] = 2; it[unitPriceCents] = 850; it[createdAt] = VenueClock.now()
            }
            checkId
        }
    }

    private fun categoryIds(db: Database) = transaction(db) {
        Categories.selectAll().orderBy(Categories.sortOrder).map { it[Categories.id] }
    }

    private fun itemCategories(db: Database) = transaction(db) {
        Items.selectAll().associate { it[Items.id] to it[Items.categoryId] }
    }

    private fun outbox(db: Database) = transaction(db) {
        SyncOutbox.selectAll().orderBy(SyncOutbox.id, SortOrder.ASC)
            .map { it[SyncOutbox.eventType] to Json.parseToJsonElement(it[SyncOutbox.payload]).jsonObject }
    }

    @Test
    fun oldDemoCatalogBecomesSevenCategoriesPlusPlateauExtras() {
        val path = tempDb()
        val checkId = oldPlateauStore(path)
        val db = connect(path)
        assertEquals(oldLayout.keys.toList() + plateauExtras, categoryIds(db), "fixture is the old 15 + Plateau")
        val variantsBefore = transaction(db) {
            ItemVariants.selectAll().associate { it[ItemVariants.id] to (it[ItemVariants.priceCents] to it[ItemVariants.deletedAt]) }
        }

        Migrations.run(db)

        assertEquals(newOrder + plateauExtras, categoryIds(db))
        val names = transaction(db) { Categories.selectAll().associate { it[Categories.id] to (it[Categories.nameEn] to it[Categories.nameFr]) } }
        assertEquals("Beer & Cider" to "Bières et cidres", names["beer-cider"])
        assertEquals("Wine" to "Vins", names["wine"])
        assertEquals("Starters" to "Entrées", names["starters"])
        assertEquals("Mains & Salads" to "Plats et salades", names["mains-salads"])
        assertEquals(CopperLanternSeed.sharedCategories.map { it[0] }, newOrder, "seed and migration agree")

        val cats = itemCategories(db)
        assertEquals(63, oldLayout.values.sumOf { it.size })
        assertEquals(63, cats.values.count { it in newOrder }, "all 63 shared-menu items now sit in the 7")
        assertEquals(63 + 12, cats.size, "every item kept, Plateau's 12 included")
        assertEquals(CopperLanternSeed.menuItemIds(CopperLanternVenue.PLATEAU).toSet(), cats.keys)
        val target = MenuCategoryMigration.TARGETS.flatMap { t -> t.from.map { it to t.id } }.toMap()
        for ((old, ids) in oldLayout) for (id in ids) assertEquals(target[old], cats[id], "$id from $old")
        assertEquals("sushi", cats["salmon-maki"])
        assertEquals("plateau-specials", cats["yuzu-sour"])

        // merged categories list their old sub-groups together, in the listed order
        val starters = transaction(db) {
            Items.selectAll().where { Items.categoryId eq "starters" }.map { it[Items.id] }
        }
        assertEquals(oldLayout["appetizers"]!! + oldLayout["late-night"]!!, starters)
        val beer = transaction(db) { Items.selectAll().where { Items.categoryId eq "beer-cider" }.map { it[Items.id] } }
        assertEquals(listOf("draft-beer", "bottles-cans", "craft-beer", "imported-beer", "cider-na").flatMap { oldLayout[it]!! }, beer)

        // owner state is carried over untouched
        transaction(db) {
            val rows = Items.selectAll().associateBy { it[Items.id] }
            assertEquals("wings.jpg", rows["wings"]!![Items.photoPath])
            assertTrue(rows["onion-rings"]!![Items.deletedAt] != null)
            assertFalse(rows["saison"]!![Items.active])
            val variantsAfter = ItemVariants.selectAll().associate { it[ItemVariants.id] to (it[ItemVariants.priceCents] to it[ItemVariants.deletedAt]) }
            assertEquals(variantsBefore, variantsAfter)
            assertEquals(5250L, variantsAfter["malbec:bottle"]!!.first)
            assertEquals(1, CheckLines.selectAll().where { CheckLines.checkId eq checkId }.count().toInt())
        }
    }

    @Test
    fun conversionEmitsCatalogEventsForTheCloud() {
        val path = tempDb()
        oldPlateauStore(path)
        val db = connect(path)
        val seqBefore = outbox(db).size
        Migrations.run(db)
        val events = outbox(db).drop(seqBefore)

        val created = events.filter { it.first == "category.created" }.map { it.second["categoryId"]!!.jsonPrimitive.content }
        assertEquals(listOf("beer-cider", "wine", "starters", "mains-salads"), created)
        created.forEach { id ->
            val e = events.first { it.first == "category.created" && it.second["categoryId"]!!.jsonPrimitive.content == id }
            assertEquals(id, e.second["category"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        }

        val moved = events.filter { it.first == "item.updated" }.map { it.second }
        val movedIds = oldLayout.filterKeys { it in MenuCategoryMigration.RETIRED }.values.flatten()
        assertEquals(movedIds.toSet(), moved.map { it["itemId"]!!.jsonPrimitive.content }.toSet())
        val wings = moved.single { it["itemId"]!!.jsonPrimitive.content == "wings" }
        val snapshot = wings["item"] as JsonObject
        assertEquals("starters", snapshot["categoryId"]!!.jsonPrimitive.content)
        assertEquals("Chicken Wings", snapshot["nameEn"]!!.jsonPrimitive.content)
        assertEquals(1, snapshot["variants"]!!.jsonArray.size)
        val rings = moved.single { it["itemId"]!!.jsonPrimitive.content == "onion-rings" }["item"] as JsonObject
        assertEquals("true", rings["deleted"]!!.jsonPrimitive.content, "soft-deleted item stays deleted in the cloud")

        val deleted = events.filter { it.first == "category.deleted" }.map { it.second }
        assertEquals(MenuCategoryMigration.RETIRED.toSet(), deleted.map { it["categoryId"]!!.jsonPrimitive.content }.toSet())
        assertTrue(deleted.all { (it["category"] as JsonObject)["deleted"]!!.jsonPrimitive.content == "true" })

        val reordered = events.last()
        assertEquals("categories.reordered", reordered.first)
        assertEquals((newOrder + plateauExtras).joinToString(","), reordered.second["orderedIds"]!!.jsonPrimitive.content)
        val sorts = reordered.second["categories"]!!.jsonArray.map {
            it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["sortOrder"]!!.jsonPrimitive.content.toInt()
        }
        assertEquals((newOrder + plateauExtras).mapIndexed { i, id -> id to i }, sorts)
    }

    @Test
    fun rerunIsANoOp() {
        val path = tempDb()
        oldPlateauStore(path)
        val db = connect(path)
        Migrations.run(db)
        val cats = categoryIds(db)
        val items = itemCategories(db)
        val events = outbox(db).size

        // the step itself, forced again (not just the runner's bookkeeping)
        val acted = transaction(db) { MenuCategoryMigration.remap(this) }
        assertFalse(acted)
        Migrations.run(db)
        assertEquals(cats, categoryIds(db))
        assertEquals(items, itemCategories(db))
        assertEquals(events, outbox(db).size, "no duplicate events")
    }

    @Test
    fun nonDemoCatalogIsUntouched() {
        val path = tempDb()
        val db = connect(path)
        Migrations.run(db, through = 33)
        // an owner-built menu: 005's residue gone, a few of their own (one slugs to a demo id)
        transaction(db) {
            exec("DELETE FROM categories")
            listOf("draft-beer" to "Draft Beer", "snacks" to "Snacks", "mains" to "Mains").forEachIndexed { i, (id, en) ->
                Categories.insert { it[Categories.id] = id; it[sortOrder] = i; it[nameFr] = en; it[nameEn] = en }
            }
            Items.insert {
                it[id] = "house-pint"; it[nameFr] = "Pinte"; it[nameEn] = "House Pint"; it[categoryId] = "draft-beer"; it[abbrev] = "HP"
            }
        }
        val eventsBefore = outbox(db).size
        Migrations.run(db)
        assertEquals(listOf("draft-beer", "snacks", "mains"), categoryIds(db))
        assertEquals(mapOf("house-pint" to "draft-beer"), itemCategories(db))
        assertEquals(eventsBefore, outbox(db).size)
    }

    @Test
    fun freshInstallsGetSevenCategoriesWithoutMigrationEvents() {
        val db = connect(tempDb())
        Migrations.run(db)
        assertEquals(newOrder, categoryIds(db), "005's residue folded on an empty store")
        assertTrue(outbox(db).isEmpty(), "nothing to mirror yet; the first sync's snapshot carries it")
    }

    @Test
    fun migratedStoreServesTheNewMenuAndOpenChecksStillWork() {
        val path = tempDb()
        val checkId = oldPlateauStore(path)
        testApplication {
            application { module(dbPath = path, venue = CopperLanternVenue.PLATEAU) }
            val cats = Json.parseToJsonElement(client.get("/categories").bodyAsText()).jsonArray
                .map { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertEquals(newOrder + plateauExtras, cats)
            val c = loginClient()
            val check = c.get("/checks/$checkId")
            assertEquals(HttpStatusCode.OK, check.status, check.bodyAsText())
            assertTrue("late-fries" in check.bodyAsText())
            val add = c.post("/checks/$checkId/lines") {
                contentType(ContentType.Application.Json)
                setBody("""{"itemId":"late-fries","variantId":"late-fries:regular","qty":1}""")
            }
            assertTrue(add.status.isSuccess(), "${add.status} ${add.bodyAsText()}")
        }
    }
}
