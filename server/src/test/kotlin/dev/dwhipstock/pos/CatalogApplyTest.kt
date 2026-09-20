package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.Categories
import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.db.SyncState
import dev.dwhipstock.pos.db.initDatabase
import dev.dwhipstock.pos.sync.CatalogChange
import dev.dwhipstock.pos.sync.ChangesPage
import dev.dwhipstock.pos.sync.CloudSync
import dev.dwhipstock.pos.sync.FetchedPhoto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CONTRACT.md §4: pull applies full snapshots idempotently, echoes with origin:"cloud". */
class CatalogApplyTest {

    private fun freshDb() =
        initDatabase(Files.createTempDirectory("pos-apply-test").resolve("pos.db").toString())

    private fun variantJson(id: String, labelFr: String, labelEn: String, price: Long, sort: Int, deleted: Boolean) =
        buildJsonObject {
            put("id", id); put("labelFr", labelFr); put("labelEn", labelEn)
            put("priceCents", price); put("sortOrder", sort); put("deleted", deleted)
        }

    private fun proseccoSnapshot(deletedBottle: Boolean = true, price: Long = 25000) = buildJsonObject {
        put("id", "prosecco"); put("nameFr", "prosecco"); put("nameEn", "Prosecco")
        put("categoryId", "wine"); put("abbrev", "PR"); put("isAlcohol", true)
        put("active", true); put("deleted", false)
        putJsonArray("variants") {
            add(variantJson("prosecco:glass", "verre", "Glass", price, 0, deleted = false))
            add(variantJson("prosecco:bottle", "bouteille", "Bottle", 120000, 1, deleted = deletedBottle))
        }
    }

    private fun page(cursor: Long, vararg changes: CatalogChange) = ChangesPage(cursor, changes.toList())

    private fun cloudEvents(type: String): List<kotlinx.serialization.json.JsonObject> = transaction {
        SyncOutbox.selectAll().where { SyncOutbox.eventType eq type }
            .map { Json.parseToJsonElement(it[SyncOutbox.payload]).jsonObject }
            .filter { it["origin"]?.jsonPrimitive?.content == "cloud" }
    }

    @Test
    fun appliesUpsertsPhotoAndDeleteWithCloudEchoes() {
        freshDb()
        val t = FakeTransport()
        val photoStore = InMemoryPhotoStore()
        val sync = CloudSync(t, photoStore)
        t.photos["prosecco"] = FetchedPhoto(byteArrayOf(9, 9, 9), "image/jpeg")

        val page1 = page(4,
            CatalogChange(1, "category", "upsert", buildJsonObject {
                put("id", "wine"); put("nameFr", "vin"); put("nameEn", "Wine")
                put("sortOrder", 9); put("deleted", false)
            }),
            CatalogChange(2, "item", "upsert", proseccoSnapshot()),
            CatalogChange(3, "item.photo", "upsert", buildJsonObject {
                put("itemId", "prosecco"); put("photoVersion", 111)
            }),
            // unknown category → skipped, but the batch still advances past it
            CatalogChange(4, "item", "upsert", buildJsonObject {
                put("id", "ghost"); put("nameFr", "x"); put("nameEn", "x")
                put("categoryId", "nonexistent"); put("abbrev", "GH")
                put("isAlcohol", false); put("active", true); put("deleted", false)
                putJsonArray("variants") { add(variantJson("ghost:std", "normale", "Regular", 1000, 0, false)) }
            }),
        )
        t.pages[0L] = page1
        sync.pullOnce()

        transaction {
            val cat = Categories.selectAll().where { Categories.id eq "wine" }.first()
            assertEquals("Wine", cat[Categories.nameEn])
            assertEquals(9, cat[Categories.sortOrder])

            val item = Items.selectAll().where { Items.id eq "prosecco" }.first()
            assertEquals("wine", item[Items.categoryId])
            assertEquals(true, item[Items.active])
            assertNull(item[Items.deletedAt])
            assertEquals("mem/prosecco", item[Items.photoPath])

            val glass = ItemVariants.selectAll().where { ItemVariants.id eq "prosecco:glass" }.first()
            assertEquals(25000L, glass[ItemVariants.priceCents])
            assertNull(glass[ItemVariants.deletedAt])
            // snapshot's deleted:true variant lands soft-deleted
            val bottle = ItemVariants.selectAll().where { ItemVariants.id eq "prosecco:bottle" }.first()
            assertNotNull(bottle[ItemVariants.deletedAt])

            // missing-category change skipped — no placeholder rows
            assertEquals(0L, Items.selectAll().where { Items.id eq "ghost" }.count())
        }
        assertEquals("4", transaction { SyncState.get(CloudSync.CATALOG_CURSOR) })
        assertTrue(photoStore.stored.containsKey("prosecco"))

        // echoes: created events + photo event, all tagged origin:"cloud" with snapshots
        assertEquals(1, cloudEvents("category.created").size)
        val itemEcho = cloudEvents("item.created").single()
        assertEquals("prosecco", itemEcho["item"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        val photoEcho = cloudEvents("item.photo_uploaded").single()
        assertEquals("prosecco",
            photoEcho["item"]!!.jsonObject["id"]!!.jsonPrimitive.content)

        // --- re-apply the same page: state converges to the same rows (no-op) ---
        sync.applyChanges(page1)
        transaction {
            assertEquals(1L, Items.selectAll().where { Items.id eq "prosecco" }.count())
            assertEquals(1L, Categories.selectAll().where { Categories.id eq "wine" }.count())
            val glass = ItemVariants.selectAll().where { ItemVariants.id eq "prosecco:glass" }.first()
            assertEquals(25000L, glass[ItemVariants.priceCents])
            assertNull(glass[ItemVariants.deletedAt])
        }
        assertEquals("4", transaction { SyncState.get(CloudSync.CATALOG_CURSOR) })

        // --- next page: price edit, then delete item + its (now empty) category ---
        t.pages[4L] = page(7,
            CatalogChange(5, "item", "upsert", proseccoSnapshot(price = 27000)),
            CatalogChange(6, "item", "delete", buildJsonObject { put("id", "prosecco") }),
            CatalogChange(7, "category", "delete", buildJsonObject { put("id", "wine") }),
        )
        sync.pullOnce()

        transaction {
            val item = Items.selectAll().where { Items.id eq "prosecco" }.first()
            assertEquals(false, item[Items.active])
            assertNotNull(item[Items.deletedAt]) // soft delete — history keeps the row
            assertEquals(27000L, ItemVariants.selectAll()
                .where { ItemVariants.id eq "prosecco:glass" }.first()[ItemVariants.priceCents])
            assertEquals(0L, Categories.selectAll().where { Categories.id eq "wine" }.count())
        }
        assertEquals("7", transaction { SyncState.get(CloudSync.CATALOG_CURSOR) })

        val deleteEcho = cloudEvents("item.deleted").single()
        assertTrue(deleteEcho["item"]!!.jsonObject["deleted"]!!.jsonPrimitive.boolean)
        val catDeleteEcho = cloudEvents("category.deleted").single()
        assertTrue(catDeleteEcho["category"]!!.jsonObject["deleted"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun upsertUpdatesExistingRowsAndRevivesSoftDeleted() {
        freshDb()
        val t = FakeTransport()
        val sync = CloudSync(t, InMemoryPhotoStore())

        t.pages[0L] = page(2,
            CatalogChange(1, "category", "upsert", buildJsonObject {
                put("id", "wine"); put("nameFr", "vin"); put("nameEn", "Wine")
                put("sortOrder", 9); put("deleted", false)
            }),
            CatalogChange(2, "item", "upsert", proseccoSnapshot()),
        )
        sync.pullOnce()

        // cloud renames the item, revives the bottle variant, retires the glass
        t.pages[2L] = page(3,
            CatalogChange(3, "item", "upsert", buildJsonObject {
                put("id", "prosecco"); put("nameFr", "Prosecco italien"); put("nameEn", "Italian Prosecco")
                put("categoryId", "wine"); put("abbrev", "IP"); put("isAlcohol", true)
                put("active", true); put("deleted", false)
                putJsonArray("variants") {
                    add(variantJson("prosecco:glass", "verre", "Glass", 25000, 0, deleted = true))
                    add(variantJson("prosecco:bottle", "bouteille", "Bottle", 110000, 1, deleted = false))
                }
            }),
        )
        sync.pullOnce()

        transaction {
            val item = Items.selectAll().where { Items.id eq "prosecco" }.first()
            assertEquals("Italian Prosecco", item[Items.nameEn])
            assertEquals("IP", item[Items.abbrev])
            assertNotNull(ItemVariants.selectAll()
                .where { ItemVariants.id eq "prosecco:glass" }.first()[ItemVariants.deletedAt])
            val bottle = ItemVariants.selectAll()
                .where { ItemVariants.id eq "prosecco:bottle" }.first()
            assertNull(bottle[ItemVariants.deletedAt]) // deleted:false revives it
            assertEquals(110000L, bottle[ItemVariants.priceCents])
        }
        assertEquals(1, cloudEvents("item.updated").size)
        assertEquals("3", transaction { SyncState.get(CloudSync.CATALOG_CURSOR) })
    }

    @Test
    fun localVariantMissingFromSnapshotIsSoftDeleted() {
        freshDb()
        val t = FakeTransport()
        val sync = CloudSync(t, InMemoryPhotoStore())
        t.pages[0L] = page(2,
            CatalogChange(1, "category", "upsert", buildJsonObject {
                put("id", "wine"); put("nameFr", "vin"); put("nameEn", "Wine")
                put("sortOrder", 9); put("deleted", false)
            }),
            CatalogChange(2, "item", "upsert", proseccoSnapshot(deletedBottle = false)),
        )
        sync.pullOnce()

        // cloud's authoritative snapshot no longer lists the bottle at all
        t.pages[2L] = page(3,
            CatalogChange(3, "item", "upsert", buildJsonObject {
                put("id", "prosecco"); put("nameFr", "prosecco"); put("nameEn", "Prosecco")
                put("categoryId", "wine"); put("abbrev", "PR"); put("isAlcohol", true)
                put("active", true); put("deleted", false)
                putJsonArray("variants") {
                    add(variantJson("prosecco:glass", "verre", "Glass", 25000, 0, deleted = false))
                }
            }),
        )
        sync.pullOnce()
        transaction {
            assertNotNull(ItemVariants.selectAll()
                .where { ItemVariants.id eq "prosecco:bottle" }.first()[ItemVariants.deletedAt])
        }
    }
}
