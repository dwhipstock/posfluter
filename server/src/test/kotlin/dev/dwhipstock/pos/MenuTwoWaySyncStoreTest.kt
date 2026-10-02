package dev.dwhipstock.pos

import dev.dwhipstock.pos.CloudMenu.b
import dev.dwhipstock.pos.CloudMenu.n
import dev.dwhipstock.pos.CloudMenu.s
import dev.dwhipstock.pos.api.CatalogOps
import dev.dwhipstock.pos.api.ItemPatchRequest
import dev.dwhipstock.pos.api.VariantPatchRequest
import dev.dwhipstock.pos.api.itemSnapshotJson
import dev.dwhipstock.pos.base.Categories
import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.Translations
import dev.dwhipstock.pos.db.Migrations
import dev.dwhipstock.pos.db.SyncState
import dev.dwhipstock.pos.db.initDatabase
import dev.dwhipstock.pos.sync.CloudSync
import dev.dwhipstock.pos.sync.CloudTransport
import dev.dwhipstock.pos.sync.Hlc
import dev.dwhipstock.pos.sync.MenuClock
import dev.dwhipstock.pos.sync.MenuClocks
import dev.dwhipstock.pos.sync.MenuPage
import dev.dwhipstock.pos.sync.MenuSync
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A transport that also serves the menu feed (or fails, offline). */
class MenuFeedTransport(val fake: FakeTransport = FakeTransport()) : CloudTransport by fake {
    val pages = ArrayDeque<MenuPage>()
    var offline = false
    var legacyCloud = false
    var pulls = 0
    override fun push(installId: String, events: List<dev.dwhipstock.pos.sync.PushEvent>) =
        if (offline) dev.dwhipstock.pos.sync.PushResult(false, "offline") else fake.push(installId, events)
    override fun fetchMenuChanges(since: Long, epoch: String?, failed: Int): MenuPage? {
        pulls++
        if (offline) throw java.net.ConnectException("no internet")
        if (legacyCloud) return null
        // (a page of another epoch is the cloud serving its feed from the start: any cursor)
        return pages.removeFirstOrNull()?.takeIf { it.cursor > since || (it.epoch != null && it.epoch != epoch) }
            ?: MenuPage(since, System.currentTimeMillis(), emptyList())
    }
}

/**
 * Two-way menu sync, the store's side (CONTRACT §10): portal edits arrive in
 * the menu feed and go through the tablet's own menu code; tablet edits are
 * stamped and go up; the later write wins in both directions, deletes too;
 * nothing applied from the cloud is sent back as a new edit; replays change
 * nothing.
 */
class MenuTwoWaySyncStoreTest {

    /** A seeded store that has synced once (its first catalog snapshot set the "before sync" baseline). */
    private fun freshDb() {
        initDatabase(Files.createTempDirectory("pos-menusync").resolve("pos.db").toString())
        dev.dwhipstock.pos.customers.copperlantern.CopperLanternSeed.seedIfEmpty(
            dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue.VIEUX_PORT)
        transaction { dev.dwhipstock.pos.api.writeChunkedCatalogSnapshot() }
    }

    private fun price(variantId: String): Long = transaction {
        ItemVariants.selectAll().where { ItemVariants.id eq variantId }.first()[ItemVariants.priceCents]
    }
    private fun item(id: String) = transaction { Items.selectAll().where { Items.id eq id }.first() }
    private fun regStamp(entity: String, id: String, field: String) = transaction { MenuClock.regs(entity, id)[field]?.hlc }

    @Test
    fun portalEditReachesTheStoreThroughTheSyncLoop() {
        freshDb()
        val t = MenuFeedTransport()
        val sync = CloudSync(t, InMemoryPhotoStore())
        val stamp = CloudMenu.stamp()
        t.pages += CloudMenu.page(CloudMenu.item("lantern-lager", variants = mapOf("lantern-lager:pint" to mapOf("priceCents" to n(875))), stamp = stamp))
        sync.pullMenuOnce()
        assertEquals(875, price("lantern-lager:pint"))
        // the register holds the cloud's stamp; the cursor moved; the offset was learned
        assertEquals(stamp, regStamp("variant", "lantern-lager:pint", "priceCents"))
        assertTrue((transaction { SyncState.get(MenuSync.MENU_CURSOR) }?.toLong() ?: 0) > 0)
        assertNotNull(transaction { SyncState.get(MenuClock.OFFSET_KEY) })
    }

    @Test
    fun appliedCloudChangesAreNeverEchoedAsNewEdits() {
        freshDb()
        val before = lastOutboxId()
        val versionBefore = transaction { MenuClock.menuVersion() }
        val stamp = CloudMenu.stamp()
        CloudMenu.apply(CloudMenu.item("lantern-lager", fields = mapOf("nameEn" to s("Lantern Lager"), "names.es" to s("Lager Linterna")),
            variants = mapOf("lantern-lager:pint" to mapOf("priceCents" to n(900))), stamp = stamp))
        // nothing written while applying goes back to the cloud: it already has that state (no echo)
        assertEquals(emptyList(), outboxSince(before))
        assertEquals("Lager Linterna", transaction { Translations.get(Translations.ITEM, "lantern-lager", "es") })
        // the registers hold the cloud's stamps, never fresh store ones
        assertEquals(stamp, regStamp("item", "lantern-lager", "names.es"))
        assertEquals(stamp, regStamp("variant", "lantern-lager:pint", "priceCents"))
        // the tablets still see a new menu version
        assertTrue(transaction { MenuClock.menuVersion() } > versionBefore)
    }

    @Test
    fun aTabletEditIsStampedFreshAndGoesUp() {
        freshDb()
        val before = lastOutboxId()
        CatalogOps.patchVariant("lantern-lager", "lantern-lager:pint", VariantPatchRequest(priceCents = 850))
        val (type, payload) = outboxSince(before).single()
        assertEquals("item.variant_updated", type)
        assertNull(payload["origin"])
        val pint = payload["item"]!!.jsonObject["variants"]!!.jsonArray.first { it.jsonObject["id"]!!.jsonPrimitive.content == "lantern-lager:pint" }.jsonObject
        val stamp = pint["clock"]!!.jsonObject["priceCents"]!!.jsonPrimitive.content
        assertTrue(stamp.split('-')[2].startsWith("s") && Hlc.physical(stamp)!! > System.currentTimeMillis() - 60_000, stamp)
        // untouched fields keep the "before two-way sync" baseline (migration 058)
        assertEquals("", payload["item"]!!.jsonObject["clock"]!!.jsonObject["nameEn"]!!.jsonPrimitive.content)
    }

    @Test
    fun bothEditTheSameFieldTheNewerWriteWins() {
        freshDb()
        CatalogOps.patchVariant("lantern-lager", "lantern-lager:pint", VariantPatchRequest(priceCents = 850)) // tablet, now
        // the portal's edit was made a minute BEFORE the tablet's: the tablet's stays
        CloudMenu.apply(CloudMenu.item("lantern-lager", variants = mapOf("lantern-lager:pint" to mapOf("priceCents" to n(990))),
            stamp = CloudMenu.stamp(-60_000)))
        assertEquals(850, price("lantern-lager:pint"))
        // a NEWER portal edit wins
        CloudMenu.apply(CloudMenu.item("lantern-lager", variants = mapOf("lantern-lager:pint" to mapOf("priceCents" to n(995))),
            stamp = CloudMenu.stamp(5_000)))
        assertEquals(995, price("lantern-lager:pint"))
        // and per field: a portal rename doesn't undo the tablet's later 86
        CatalogOps.patchItem("lantern-lager", ItemPatchRequest(active = false))
        CloudMenu.apply(CloudMenu.item("lantern-lager", fields = mapOf("nameEn" to s("Lager"), "active" to b(true)),
            stamp = CloudMenu.stamp(-30_000)))
        assertFalse(item("lantern-lager")[Items.active])
    }

    @Test
    fun anOfflineStoreKeepsSellingAndEditingThenCatchesUp() {
        freshDb()
        val t = MenuFeedTransport().apply { offline = true }
        val sync = CloudSync(t, InMemoryPhotoStore())
        sync.tick() // nothing reachable: nothing breaks
        // offline edit on the tablet
        CatalogOps.patchItem("lantern-lager", ItemPatchRequest(descriptionEn = "Offline edit"))
        // meanwhile the portal changed the price (earlier) and the description (earlier than the tablet)
        val portal = CloudMenu.item("lantern-lager", fields = mapOf("descriptionEn" to s("Portal text")),
            variants = mapOf("lantern-lager:pint" to mapOf("priceCents" to n(777))), stamp = CloudMenu.stamp(-10_000))
        t.offline = false
        t.pages += CloudMenu.page(portal)
        sync.tick()
        assertEquals(777, price("lantern-lager:pint")) // no conflict: the portal's price lands
        assertEquals("Offline edit", item("lantern-lager")[Items.descriptionEn]) // the tablet's newer text stays
        // and the offline edit went up with its own stamp
        val pushed = t.fake.batches.flatten().filter { it.eventType == "item.updated" && it.payload["origin"] == null }
        assertTrue(pushed.any { it.payload["item"]!!.jsonObject["descriptionEn"]!!.jsonPrimitive.content == "Offline edit" })
    }

    @Test
    fun deleteVersusEditTheLaterOneWinsOnTheStore() {
        freshDb()
        // the tablet renamed it after the portal deleted it: it stays
        CatalogOps.patchItem("amber-ale", ItemPatchRequest(nameEn = "Amber (kept)"))
        CloudMenu.apply(CloudMenu.item("amber-ale", fields = mapOf("deleted" to b(true)), stamp = CloudMenu.stamp(-20_000)))
        assertNull(item("amber-ale")[Items.deletedAt])
        // a portal delete after the tablet's last edit deletes it
        CloudMenu.apply(CloudMenu.item("amber-ale", fields = mapOf("deleted" to b(true)), stamp = CloudMenu.stamp(5_000)))
        assertNotNull(item("amber-ale")[Items.deletedAt])
        // a portal rename stamped after that delete brings it back
        val revive = CloudMenu.item("amber-ale", fields = mapOf("nameEn" to s("Amber is back")), stamp = CloudMenu.stamp(10_000))
        CloudMenu.apply(revive)
        assertNull(item("amber-ale")[Items.deletedAt])
        assertEquals("Amber is back", item("amber-ale")[Items.nameEn])
        // but an older edit after a newer delete stays deleted (no ghost)
        CatalogOps.deleteItem("amber-ale")
        CloudMenu.apply(CloudMenu.item("amber-ale", fields = mapOf("nameFr" to s("Vieille")), stamp = CloudMenu.stamp(-120_000)))
        assertNotNull(item("amber-ale")[Items.deletedAt])
    }

    @Test
    fun replayingAPageChangesNothing() {
        freshDb()
        val change = CloudMenu.item("lantern-lager", fields = mapOf("nameEn" to s("Replay Lager")),
            variants = mapOf("lantern-lager:pitcher" to mapOf("priceCents" to n(2222))))
        CloudMenu.apply(change)
        val snapshot = transaction { itemSnapshotJson("lantern-lager") }
        val before = lastOutboxId()
        CloudMenu.apply(change)
        CloudMenu.apply(change)
        assertEquals(snapshot, transaction { itemSnapshotJson("lantern-lager") })
        assertEquals(emptyList(), outboxSince(before)) // a replay writes nothing at all
    }

    @Test
    fun aPortalCreatedItemAndCategoryArriveWithTheCloudsIds() {
        freshDb()
        val stamp = CloudMenu.stamp()
        val cat = CloudMenu.category("snacks-x7k2", mapOf("nameEn" to s("Snacks"), "nameFr" to s("Grignotines"),
            "sortOrder" to n(9), "deleted" to b(false), "names.es" to s("Botanas")), stamp)
        val base = JsonObject(mapOf("id" to s("nachos-ab12"), "variants" to kotlinx.serialization.json.JsonArray(emptyList())))
        val nachos = CloudMenu.itemFrom("nachos-ab12", base, fields = mapOf(
            "nameEn" to s("Nachos"), "nameFr" to s("Nachos"), "descriptionEn" to s(""), "descriptionFr" to s(""),
            "categoryId" to s("snacks-x7k2"), "abbrev" to s("NA"), "isAlcohol" to b(false), "active" to b(true),
            "deleted" to b(false), "names.es" to s("Nachos con queso")),
            variants = mapOf("nachos-ab12:regular" to mapOf("labelEn" to s("Regular"), "labelFr" to s("Régulier"),
                "priceCents" to n(1200), "sortOrder" to n(0), "deleted" to b(false))), stamp = stamp)
        CloudMenu.apply(cat, nachos)
        assertEquals("Grignotines", transaction { Categories.selectAll().where { Categories.id eq "snacks-x7k2" }.first()[Categories.nameFr] })
        assertEquals("snacks-x7k2", item("nachos-ab12")[Items.categoryId])
        assertEquals(1200, price("nachos-ab12:regular"))
        assertEquals("Nachos con queso", transaction { Translations.get(Translations.ITEM, "nachos-ab12", "es") })
        assertEquals("Botanas", transaction { Translations.get(Translations.CATEGORY, "snacks-x7k2", "es") })
        // nothing was minted by the store for any of it (fields this feed entry doesn't
        // stamp — the specials, from an older cloud — stay "before sync")
        assertTrue(transaction { MenuClock.regs("item", "nachos-ab12").values.all { it.hlc == stamp || it.hlc == Hlc.LEGACY } })
    }

    @Test
    fun aDeleteTheStoreCannotDoIsRefusedAndTheStoresStateWins() {
        freshDb()
        // a category with live items: the store keeps it and says so with a fresh stamp
        val catId = item("lantern-lager")[Items.categoryId]
        val before = lastOutboxId()
        CloudMenu.apply(CloudMenu.category(catId, mapOf("deleted" to b(true))))
        assertTrue(transaction { Categories.selectAll().where { Categories.id eq catId }.any() })
        val veto = outboxSince(before).last { it.second["origin"] == null }
        assertEquals("category.updated", veto.first)
        val clock = veto.second["category"]!!.jsonObject["clock"]!!.jsonObject["deleted"]!!.jsonPrimitive.content
        assertTrue(!clock.endsWith("-cloud") && clock.isNotEmpty(), clock)
    }

    @Test
    fun aRestampFromTheCloudFixesAStoreClockThatRanAhead() {
        freshDb()
        // a store clock that was an hour fast stamped the name
        val future = Hlc.of(System.currentTimeMillis() + 3_600_000, 0, "sbroken")
        transaction { MenuClock.write("item", "lantern-lager", mapOf("nameEn" to MenuClock.Reg("\"Lantern House Lager\"", future))) }
        // the cloud re-stamped it (same value, its own clock) and says so
        val restamp = CloudMenu.item("lantern-lager", fields = mapOf("nameEn" to s(item("lantern-lager")[Items.nameEn])))
            .let { it.copy(data = JsonObject(it.data + ("restamp" to b(true)))) }
        CloudMenu.apply(restamp)
        assertTrue(regStamp("item", "lantern-lager", "nameEn")!!.endsWith("-cloud"))
        // so a later portal rename is no longer beaten by the broken clock
        CloudMenu.apply(CloudMenu.item("lantern-lager", fields = mapOf("nameEn" to s("Renamed"))))
        assertEquals("Renamed", item("lantern-lager")[Items.nameEn])
    }

    @Test
    fun rapidAvailabilityTogglesFromBothSidesConverge() {
        freshDb()
        var last = true
        repeat(6) { i ->
            if (i % 2 == 0) {
                last = i % 4 == 0
                CatalogOps.patchItem("lantern-lager", ItemPatchRequest(active = last))
            } else {
                last = !last
                CloudMenu.apply(CloudMenu.item("lantern-lager", fields = mapOf("active" to b(last)), stamp = CloudMenu.stamp(i * 1000L)))
            }
        }
        assertEquals(last, item("lantern-lager")[Items.active])
        // an older toggle replayed late changes nothing
        CloudMenu.apply(CloudMenu.item("lantern-lager", fields = mapOf("active" to b(!last)), stamp = CloudMenu.stamp(-600_000)))
        assertEquals(last, item("lantern-lager")[Items.active])
    }

    @Test
    fun anOlderCloudWithoutAFeedIsLeftAlone() {
        freshDb()
        val t = MenuFeedTransport().apply { legacyCloud = true }
        val sync = CloudSync(t, InMemoryPhotoStore())
        sync.pullMenuOnce()
        sync.pullMenuOnce() // backed off: not asked again for 5 minutes
        assertEquals(1, t.pulls)
        assertNull(transaction { SyncState.get(MenuSync.MENU_CURSOR) })
    }

    @Test
    fun anUpgradedStoreBaselinesItsMenuWithoutMintingEdits() {
        val path = Files.createTempDirectory("pos-menusync-upgrade").resolve("pos.db").toString()
        val db = Database.connect("jdbc:sqlite:$path", driver = "org.sqlite.JDBC")
        Migrations.run(db, through = 56)
        seedOldStore(db, dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue.VIEUX_PORT)
        Migrations.run(db)
        transaction(db) {
            val rows = MenuClocks.selectAll().where { (MenuClocks.entity eq "item") and (MenuClocks.entityId eq "lantern-lager") }.toList()
            assertTrue(rows.isNotEmpty())
            assertTrue(rows.all { it[MenuClocks.hlc] == "" })
        }
    }
}
