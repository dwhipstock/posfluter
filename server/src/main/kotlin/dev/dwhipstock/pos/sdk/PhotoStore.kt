package dev.dwhipstock.pos.sdk

import java.io.File

/**
 * Item photo storage. Filesystem for the M1 embedded deployment; the interface
 * exists so object storage (S3 etc.) can swap in later without touching call
 * sites — only the wiring in Application changes.
 */
interface PhotoStore {

    /** Stored photo bytes + content type, or null if none exists. */
    fun read(itemId: String): StoredPhoto?

    /**
     * Downscaled variant for grid thumbnails (max [maxWidth] px wide), or null
     * if no photo exists. May return the original when it is already small
     * enough or cannot be decoded — callers must not assume the exact width.
     */
    fun readScaled(itemId: String, maxWidth: Int): StoredPhoto?

    /** Save (replace) the photo; returns the stored path for items.photo_path. */
    fun save(itemId: String, bytes: ByteArray, contentType: String): String

    /**
     * Cache-busting version for the current photo (e.g. mtime), or null when
     * absent. Clients append it as ?v= so replacements bypass HTTP caches.
     */
    fun version(itemId: String): Long?

    data class StoredPhoto(val bytes: ByteArray, val contentType: String, val version: Long)
}

class FilesystemPhotoStore(private val dir: File) : PhotoStore {

    init { dir.mkdirs() }

    // Generated downscales live beside the originals, keyed by source version +
    // width, so a replaced photo naturally regenerates and old variants are swept.
    private val thumbsDir = File(dir, ".thumbs")

    private fun fileFor(itemId: String): File? =
        listOf("jpg", "png").map { File(dir, "$itemId.$it") }.firstOrNull { it.exists() }

    override fun read(itemId: String): PhotoStore.StoredPhoto? = fileFor(itemId)?.let {
        PhotoStore.StoredPhoto(
            bytes = it.readBytes(),
            contentType = if (it.extension == "png") "image/png" else "image/jpeg",
            version = it.lastModified(),
        )
    }

    override fun readScaled(itemId: String, maxWidth: Int): PhotoStore.StoredPhoto? {
        val src = fileFor(itemId) ?: return null
        val version = src.lastModified()
        val thumb = File(thumbsDir, "$itemId-$version-w$maxWidth.jpg")
        if (thumb.exists()) return PhotoStore.StoredPhoto(thumb.readBytes(), "image/jpeg", version)
        val scaled = Images.downscaleToJpeg(src.readBytes(), maxWidth)
            ?: return read(itemId) // already small or undecodable: the original is the answer
        thumbsDir.mkdirs()
        thumbsDir.listFiles { f -> f.name.startsWith("$itemId-") }?.forEach { it.delete() }
        // temp + rename so a reader never sees a half-written file; concurrent
        // first-requests race benignly — each serves its own scaled bytes
        val tmp = File.createTempFile("$itemId-", ".tmp", thumbsDir)
        tmp.writeBytes(scaled)
        if (!tmp.renameTo(thumb)) tmp.delete()
        return PhotoStore.StoredPhoto(scaled, "image/jpeg", version)
    }

    override fun save(itemId: String, bytes: ByteArray, contentType: String): String {
        val ext = when (contentType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            else -> throw IllegalArgumentException("only JPEG or PNG photos are supported")
        }
        // replace works across formats too: drop the other extension if present
        listOf("jpg", "png").forEach { File(dir, "$itemId.$it").delete() }
        val file = File(dir, "$itemId.$ext")
        file.writeBytes(bytes)
        return file.path
    }

    override fun version(itemId: String): Long? = fileFor(itemId)?.lastModified()
}
