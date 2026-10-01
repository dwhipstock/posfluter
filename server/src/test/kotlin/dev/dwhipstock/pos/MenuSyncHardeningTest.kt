package dev.dwhipstock.pos

import dev.dwhipstock.pos.CloudMenu.b
import dev.dwhipstock.pos.CloudMenu.n
import dev.dwhipstock.pos.CloudMenu.s
import dev.dwhipstock.pos.api.CatalogOps
import dev.dwhipstock.pos.api.CategoryCreateRequest
import dev.dwhipstock.pos.base.Categories
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.db.SyncState
import dev.dwhipstock.pos.db.initDatabase
import dev.dwhipstock.pos.sync.CloudSync
import dev.dwhipstock.pos.sync.MenuChange
import dev.dwhipstock.pos.sync.MenuClock
import dev.dwhipstock.pos.sync.MenuFields
import dev.dwhipstock.pos.sync.MenuPage
import dev.dwhipstock.pos.sync.MenuSync
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two-way menu sync after the red-team pass (scripts/e2e/redteam_sync.py):
 * a dish never lands in a category the store deleted, an unappliable change
 * is kept and retried (never dropped silently), a category reorder is one
 * value, and a reset cloud feed is read again from the start.
 */
class MenuSyncHardeningTest {

    private fun freshDb() {
        initDatabase(Files.createTempDirectory("pos-menusync-hard").resolve("pos.db").toString())
        dev.dwhipstock.pos.customers.copperlantern.CopperLanternSeed.seedIfEmpty(
            dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue.VIEUX_PORT)
        transaction { dev.dwhipstock.pos.api.writeChunkedCatalogSnapshot() }
    }

    private fun categoryExists(id: String) = transaction { Categories.selectAll().where { Categories.id eq id }.any() }
    private fun itemRow(id: String) = transaction { Items.selectAll().where { Items.id eq id }.firstOrNull() }

    /** A portal-made dish in [categoryId], as the cloud's feed carries it. */
    private fun portalDish(id: String, categoryId: String, stamp: String): MenuChange {
        val base = JsonObject(mapOf("id" to s(id), "variants" to JsonArray(emptyList())))
        return CloudMenu.itemFrom(id, base, fields = mapOf(
            "nameEn" to s("Orphan Dish"), "nameFr" to s("Plat"), "descriptionEn" to s(""), "descriptionFr" to s(""),
            "categoryId" to s(categoryId), "abbrev" to s("OD"), "isAlcohol" to b(false), "active" to b(true),
            "deleted" to b(false)),
            variants = mapOf("$id:regular" to mapOf("labelEn" to s("Regular"), "labelFr" to s("Régulier"),
                "priceCents" to n(1234), "sortOrder" to n(0), "deleted" to b(false))), stamp = stamp)
    }

    private fun emptyCategory(): String = CatalogOps.createCategory(CategoryCreateRequest(nameFr = "Vide", nameEn = "Empty")).id

    @Test
    fun aPortalDishInACategoryTheStoreDeletedBringsTheCategoryBack() {
        freshDb()
        val cid = emptyCategory()
        CatalogOps.deleteCategory(cid) // offline on the tablet
        val before = lastOutboxId()
        CloudMenu.apply(portalDish("orphan-ab12", cid, CloudMenu.stamp()))
        // the category is back (from its tombstone) and the dish is in it
        assertTrue(categoryExists(cid))
        assertEquals("Empty", transaction { Categories.selectAll().where { Categories.id eq cid }.first()[Categories.nameEn] })
        assertEquals(cid, itemRow("orphan-ab12")!![Items.categoryId])
        assertEquals(0, transaction { MenuSync.failedCount() })
        // the revival goes up as the store's own, freshly stamped edit, so the cloud brings it back too
        val up = outboxSince(before).filter { it.second["origin"] == null && it.first.startsWith("category.") }
        val revival = up.single()
        val cat = revival.second["category"]!!.jsonObject
        assertEquals(false, cat["deleted"]!!.jsonPrimitive.content.toBoolean())
        val stamp = cat["clock"]!!.jsonObject["deleted"]!!.jsonPrimitive.content
        assertFalse(stamp.endsWith("-cloud") || stamp.isEmpty(), stamp)
    }

    @Test
    fun aDishBroughtBackByALaterPortalEditBringsItsDeletedCategoryBackToo() {
        freshDb()
        val cid = emptyCategory()
        val dish = portalDish("seasonal-ab12", cid, CloudMenu.stamp(-60_000))
        CloudMenu.apply(dish)
        // offline: the tablet deletes the dish, then its (now empty) category
        CatalogOps.deleteItem("seasonal-ab12", allowInUse = true)
        CatalogOps.deleteCategory(cid)
        // later, the portal renames the dish (it did not know about the deletes)
        CloudMenu.apply(CloudMenu.itemFrom("seasonal-ab12", dish.data, fields = mapOf("nameEn" to s("Seasonal (fixed)"))))
        val row = itemRow("seasonal-ab12")!!
        assertNull(row[Items.deletedAt])
        assertEquals("Seasonal (fixed)", row[Items.nameEn])
        assertTrue(categoryExists(cid), "a live dish must have a live category")
    }

    @Test
    fun aChangeTheStoreCannotApplyIsKeptCountedAndRetried() {
        freshDb()
        // a dish in a category this store has never heard of (its feed entry is not applied yet)
        val dish = portalDish("lost-ab12", "brand-new-cat", CloudMenu.stamp())
        CloudMenu.apply(dish)
        assertNull(itemRow("lost-ab12"))
        assertEquals(1, transaction { MenuSync.failedCount() })
        // the next page brings the category: the dish is retried and lands
        CloudMenu.apply(CloudMenu.category("brand-new-cat", mapOf("nameEn" to s("New"), "nameFr" to s("Nouveau"),
            "sortOrder" to n(20), "deleted" to b(false))))
        assertEquals("brand-new-cat", itemRow("lost-ab12")!![Items.categoryId])
        assertEquals(0, transaction { MenuSync.failedCount() })
    }

    @Test
    fun theFailedCountGoesUpWithTheFeedPull() {
        freshDb()
        val t = MenuFeedTransport()
        val sync = CloudSync(t, InMemoryPhotoStore())
        t.pages += CloudMenu.page(portalDish("lost-cd34", "nowhere-cat", CloudMenu.stamp()))
        sync.pullMenuOnce()
        val seen = mutableListOf<Int>()
        val spy = object : dev.dwhipstock.pos.sync.CloudTransport by t {
            override fun fetchMenuChanges(since: Long, epoch: String?, failed: Int): MenuPage? {
                seen += failed
                return t.fetchMenuChanges(since, epoch, failed)
            }
        }
        CloudSync(spy, InMemoryPhotoStore()).pullMenuOnce()
        assertEquals(listOf(1), seen)
    }

    @Test
    fun aTabletReorderStampsTheWholeOrderAsOneValue() {
        freshDb()
        val ids = transaction { Categories.selectAll().orderBy(Categories.sortOrder).map { it[Categories.id] } }
        val before = lastOutboxId()
        CatalogOps.reorderCategories(listOf(ids.last()) + ids.dropLast(1))
        val (type, payload) = outboxSince(before).single()
        assertEquals("categories.reordered", type)
        val stamps = payload["categories"]!!.jsonArray.map {
            it.jsonObject["clock"]!!.jsonObject["sortOrder"]!!.jsonPrimitive.content
        }.toSet()
        // every position, moved or not, carries the same fresh stamp
        assertEquals(1, stamps.size, stamps.toString())
        assertTrue(stamps.single().isNotEmpty())
        assertTrue(transaction { ids.all { MenuClock.regs(MenuFields.CATEGORY, it)["sortOrder"]?.hlc == stamps.single() } })
    }

    @Test
    fun aNewFeedEpochRestartsTheFeedAndResendsTheMenu() {
        freshDb()
        val t = MenuFeedTransport()
        val sync = CloudSync(t, InMemoryPhotoStore())
        val stamp = CloudMenu.stamp()
        val first = CloudMenu.item("lantern-lager", fields = mapOf("descriptionFr" to s("avant")), stamp = stamp)
        t.pages += MenuPage(40, System.currentTimeMillis(), listOf(first.copy(seq = 40)), epoch = "1-aaa")
        sync.pullMenuOnce()
        assertEquals("1-aaa", transaction { SyncState.get(MenuSync.MENU_EPOCH) })
        assertEquals("40", transaction { SyncState.get(MenuSync.MENU_CURSOR) })
        transaction { SyncState.set(CloudSync.CATALOG_SNAPSHOT_SEQ, "1") }
        // the cloud was restored: a new epoch, and its feed starts again low
        val after = CloudMenu.item("lantern-lager", fields = mapOf("nameFr" to s("Apres restauration")))
        t.pages += MenuPage(3, System.currentTimeMillis(), listOf(after.copy(seq = 3)), epoch = "2-bbb")
        sync.pullMenuOnce()
        assertEquals("Apres restauration", itemRow("lantern-lager")!![Items.nameFr])
        assertEquals("3", transaction { SyncState.get(MenuSync.MENU_CURSOR) })
        assertEquals("2-bbb", transaction { SyncState.get(MenuSync.MENU_EPOCH) })
        // the whole menu goes up again on the next drain (the restored cloud may have lost recent pushes)
        assertNull(transaction { SyncState.get(CloudSync.CATALOG_SNAPSHOT_SEQ) })
    }
}
