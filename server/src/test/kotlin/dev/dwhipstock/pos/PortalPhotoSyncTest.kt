package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.db.initDatabase
import dev.dwhipstock.pos.sdk.PhotoStore
import dev.dwhipstock.pos.sync.CloudSync
import dev.dwhipstock.pos.sync.MenuChange
import dev.dwhipstock.pos.sync.MenuPage
import dev.dwhipstock.pos.sync.PhotoDownload
import dev.dwhipstock.pos.sync.PhotoSync
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Photos from the manager portal (CONTRACT §10 "Photos from the portal"): a
 * `photo` menu feed entry makes the store fetch the binary, check it is a real
 * JPEG / PNG matching the entry, and make it the item's photo — without
 * sending it back up. Anything off is refused; offline it waits.
 */
class PortalPhotoSyncTest {

    private class Photos : PhotoStore {
        val stored = mutableMapOf<String, Pair<ByteArray, String>>()
        override fun read(itemId: String) = stored[itemId]?.let { PhotoStore.StoredPhoto(it.first, it.second, 1L) }
        override fun readScaled(itemId: String, maxWidth: Int) = read(itemId)
        override fun save(itemId: String, bytes: ByteArray, contentType: String): String {
            stored[itemId] = bytes to contentType
            return "mem/$itemId"
        }
        override fun version(itemId: String): Long? = if (itemId in stored) 1L else null
        override fun delete(itemId: String) = stored.remove(itemId) != null
    }

    /** The cloud: a feed page, and the item's current photo as GET /v1/store/menu/photos serves it. */
    private class Cloud(val feed: MenuFeedTransport = MenuFeedTransport()) : dev.dwhipstock.pos.sync.CloudTransport by feed {
        var photo: PhotoDownload? = null
        var offline = false
        var fetches = 0
        override fun fetchMenuChanges(since: Long, epoch: String?, failed: Int): MenuPage? = feed.fetchMenuChanges(since, epoch, failed)
        override fun fetchMenuPhoto(itemId: String, maxBytes: Int): PhotoDownload? {
            fetches++
            if (offline) throw java.net.ConnectException("no internet")
            return photo ?: PhotoDownload(404, ByteArray(0), null, null, null)
        }
    }

    private var seq = 1000L
    private val item = "lantern-lager"

    private fun freshDb() {
        initDatabase(Files.createTempDirectory("pos-portal-photo").resolve("pos.db").toString())
        dev.dwhipstock.pos.customers.copperlantern.CopperLanternSeed.seedIfEmpty(
            dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue.VIEUX_PORT)
        transaction { dev.dwhipstock.pos.api.writeChunkedCatalogSnapshot() }
    }

    private fun jpeg(w: Int = 64, h: Int = 48): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply { color = java.awt.Color.ORANGE; fillRect(0, 0, w, h); dispose() }
        return ByteArrayOutputStream().use { ImageIO.write(img, "jpg", it); it.toByteArray() }
    }

    private fun png(w: Int = 40, h: Int = 30): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().use { ImageIO.write(img, "png", it); it.toByteArray() }
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun entry(bytes: ByteArray, version: Long, type: String = "image/jpeg", source: String = "ai_generated"): JsonObject =
        buildJsonObject {
            put("itemId", item); put("deleted", false); put("version", version); put("contentType", type)
            put("bytes", bytes.size); put("sha256", sha(bytes)); put("source", source)
        }

    private fun page(data: JsonObject) = MenuPage(++seq, System.currentTimeMillis(), listOf(MenuChange(seq, PhotoSync.ENTITY, item, data)))

    private fun photoSource() = transaction { Items.selectAll().where { Items.id eq item }.first()[Items.photoSource] }

    @Test
    fun anAcceptedPortalPhotoBecomesTheItemsPhotoAndIsNotSentBack() {
        freshDb()
        val cloud = Cloud()
        val photos = Photos()
        val sync = CloudSync(cloud, photos)
        val bytes = jpeg()
        cloud.photo = PhotoDownload(200, bytes, "image/jpeg", 77L, "ai_generated")
        cloud.feed.pages += page(entry(bytes, 77L))
        val before = lastOutboxId()
        sync.pullMenuOnce()
        assertTrue(photos.stored[item]!!.first.contentEquals(bytes))
        assertEquals("image/jpeg", photos.stored[item]!!.second)
        assertEquals("ai_generated", photoSource())
        // applied from the cloud: no item.photo_uploaded goes back up (no echo)
        assertEquals(emptyList(), outboxSince(before).map { it.first }.filter { it.startsWith("item.") })
        assertEquals(0, PhotoSync.pendingCount())
        // a replay of the same entry is harmless
        cloud.feed.pages += page(entry(bytes, 77L))
        sync.pullMenuOnce()
        assertEquals(0, PhotoSync.pendingCount())
    }

    @Test
    fun anythingThatIsNotTheRealPictureIsRefused() {
        freshDb()
        val good = jpeg()
        val cases = listOf(
            "not an image" to PhotoDownload(200, "<html>hi</html>".toByteArray(), "image/jpeg", 5L, null),
            "type lies" to PhotoDownload(200, good, "image/png", 5L, null),
            "too big" to PhotoDownload(200, ByteArray(2 * 1024 * 1024 + 10).also { good.copyInto(it) }, "image/jpeg", 5L, null),
            "checksum" to PhotoDownload(200, jpeg(65, 48), "image/jpeg", 5L, null),
        )
        for ((why, download) in cases) {
            val cloud = Cloud()
            val photos = Photos()
            cloud.photo = download
            cloud.feed.pages += page(entry(good, 5L))
            CloudSync(cloud, photos).pullMenuOnce()
            assertNull(photos.stored[item], why)
            assertEquals(0, PhotoSync.pendingCount(), why) // dropped, never retried forever
        }
        assertNull(photoSource())
        // the checks themselves
        assertEquals(64 to 48, PhotoSync.dimensions(good, "image/jpeg"))
        assertEquals(40 to 30, PhotoSync.dimensions(png(), "image/png"))
        assertEquals("image/png", PhotoSync.sniff(png()))
        assertNull(PhotoSync.check(png(), "image/png", buildJsonObject {}))
        assertNotNull(PhotoSync.check(png(4, 4), "image/png", buildJsonObject {})) // a 4 px "photo"
    }

    @Test
    fun aNewerPhotoOnTheCloudSupersedesTheEntry() {
        freshDb()
        val cloud = Cloud()
        val photos = Photos()
        val old = jpeg()
        // the store uploaded its own photo after the portal's: the cloud now serves version 90
        cloud.photo = PhotoDownload(200, jpeg(70, 50), "image/jpeg", 90L, "original")
        cloud.feed.pages += page(entry(old, 80L))
        CloudSync(cloud, photos).pullMenuOnce()
        assertNull(photos.stored[item])
        assertEquals(0, PhotoSync.pendingCount())
    }

    @Test
    fun offlineItWaitsAndArrivesLater() {
        freshDb()
        val cloud = Cloud()
        val photos = Photos()
        val sync = CloudSync(cloud, photos)
        val bytes = png()
        cloud.offline = true
        cloud.feed.pages += page(entry(bytes, 12L, "image/png", "ai_enhanced"))
        sync.pullMenuOnce()
        assertNull(photos.stored[item])
        assertEquals(1, PhotoSync.pendingCount())
        cloud.offline = false
        cloud.photo = PhotoDownload(200, bytes, "image/png", 12L, "ai_enhanced")
        sync.pullMenuOnce()
        assertTrue(photos.stored[item]!!.first.contentEquals(bytes))
        assertEquals("ai_enhanced", photoSource())
        assertEquals(0, PhotoSync.pendingCount())
    }

    @Test
    fun anUndoToNoPhotoRemovesIt() {
        freshDb()
        val cloud = Cloud()
        val photos = Photos()
        val sync = CloudSync(cloud, photos)
        val bytes = jpeg()
        cloud.photo = PhotoDownload(200, bytes, "image/jpeg", 3L, "ai_generated")
        cloud.feed.pages += page(entry(bytes, 3L))
        sync.pullMenuOnce()
        assertNotNull(photos.stored[item])
        cloud.photo = null // the cloud has no photo for it any more
        cloud.feed.pages += page(buildJsonObject { put("itemId", item); put("deleted", true) })
        sync.pullMenuOnce()
        assertNull(photos.stored[item])
        assertNull(transaction { Items.selectAll().where { Items.id eq item }.first()[Items.photoPath] })
    }
}
