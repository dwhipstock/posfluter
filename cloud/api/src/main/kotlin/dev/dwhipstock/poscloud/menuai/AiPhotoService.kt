package dev.dwhipstock.poscloud.menuai

import dev.dwhipstock.poscloud.BadRequestException
import dev.dwhipstock.poscloud.CloudConfig
import dev.dwhipstock.poscloud.CloudTime
import dev.dwhipstock.poscloud.ConflictException
import dev.dwhipstock.poscloud.NotFoundException
import dev.dwhipstock.poscloud.catalog.Scope
import dev.dwhipstock.poscloud.db.CatalogCategories
import dev.dwhipstock.poscloud.db.CatalogItems
import dev.dwhipstock.poscloud.db.ItemPhotos
import dev.dwhipstock.poscloud.db.MenuAiLog
import dev.dwhipstock.poscloud.db.MenuAiPhotos
import dev.dwhipstock.poscloud.db.Venues
import dev.dwhipstock.poscloud.menu.MenuPhotos
import dev.dwhipstock.poscloud.menu.MenuState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO

/** A generated photo waiting for Accept / Retry / Discard. */
@Serializable
data class AiPhotoPreviewDto(
    val photoId: String,
    val itemId: String,
    val itemName: String,
    /** ai_generated | ai_enhanced */
    val source: String,
    val provider: String,
    val model: String,
    val contentType: String,
    /** The picture itself (a ~1024 px JPEG), shown before anything changes. */
    val dataBase64: String,
    val elapsedMs: Long,
    /** The item already has a photo (Accept replaces it; Undo puts it back). */
    val replaces: Boolean,
)

@Serializable
data class AiPhotoRequest(val itemId: String, val mode: String = "generate", val lang: String? = null)

/** [stores]: every store the photo became the item's photo at (the asked one first). */
@Serializable
data class AiPhotoAcceptResult(
    val photoId: String, val itemId: String, val photoVersion: Long, val photoSource: String,
    val stores: List<String> = emptyList(),
)

@Serializable
data class AiPhotoUndoResult(val photoId: String, val itemId: String, val photoVersion: Long? = null, val photoSource: String? = null)

/**
 * AI item photos for the portal: the store's "Generate photo" / "Enhance"
 * (server/.../aiphotos/AiPhotoService.kt) for one item of one store. The
 * picture is made in the store's house style ([PhotoPrompts], ported), shown
 * as a preview, and only becomes the item's photo when the manager accepts
 * it. Accepting writes item_photos like a store upload and appends a `photo`
 * entry to the store's menu feed: the store fetches the binary
 * (`GET /v1/store/menu/photos/{itemId}`), checks it is a real JPEG / PNG, and
 * makes it the item's photo. Undo puts the previous photo back the same way.
 *
 * Limits: the assistant's (20 calls per 10 minutes per user and per store,
 * MENU_AI_DAILY_CAP a day) plus MENU_AI_PHOTO_DAILY_CAP pictures per store per
 * rolling day. Every generation, accept and undo is in menu_ai_log; the
 * prompt, the picture and the keys never are.
 */
class AiPhotoService internal constructor(
    private val ai: MenuAiService,
    private val config: CloudConfig,
    /** The photo makers (printed menus draw their artwork with the same ones). */
    internal val images: ImageGen,
    private val now: () -> Long,
) {
    private val log = LoggerFactory.getLogger(AiPhotoService::class.java)

    val enabled: Boolean get() = images.enabled

    companion object {
        const val GENERATE = "generate"
        const val ENHANCE = "enhance"
        /** A preview not accepted within this long is dropped. */
        const val PREVIEW_TTL_MS = 60 * 60_000L
        /** What the store accepts (server/.../api/Photos.kt MAX_PHOTO_BYTES). */
        const val MAX_BYTES = 2 * 1024 * 1024
        /** At most this many photos from one assistant request ("photos for every drink"). */
        const val MAX_PER_REQUEST = 10
        const val FEED_ENTITY = MenuPhotos.FEED_ENTITY
    }

    private class ItemRow(val id: String, val nameEn: String, val nameFr: String, val descriptionEn: String, val category: String)

    private fun loadItem(scope: Scope, itemId: String): ItemRow? = transaction {
        val row = CatalogItems.selectAll().where {
            (CatalogItems.tenantId eq scope.tenantId) and (CatalogItems.venueId eq scope.venueId) and
                (CatalogItems.id eq itemId) and (CatalogItems.deleted eq false)
        }.firstOrNull() ?: return@transaction null
        val cat = CatalogCategories.selectAll().where {
            (CatalogCategories.tenantId eq scope.tenantId) and (CatalogCategories.venueId eq scope.venueId) and
                (CatalogCategories.id eq row[CatalogItems.categoryId])
        }.firstOrNull()
        ItemRow(row[CatalogItems.id], row[CatalogItems.nameEn], row[CatalogItems.nameFr], row[CatalogItems.descriptionEn],
            cat?.get(CatalogCategories.nameEn)?.ifBlank { cat[CatalogCategories.nameFr] } ?: "")
    }

    private fun storeSpeaksSync(scope: Scope): Boolean = transaction {
        Venues.selectAll().where { (Venues.tenantId eq scope.tenantId) and (Venues.id eq scope.venueId) }
            .firstOrNull()?.get(Venues.menuSyncAt) != null
    }

    private fun currentPhoto(scope: Scope, itemId: String) = ItemPhotos.selectAll().where {
        (ItemPhotos.tenantId eq scope.tenantId) and (ItemPhotos.venueId eq scope.venueId) and (ItemPhotos.itemId eq itemId)
    }.firstOrNull()

    private fun photosToday(who: AiCaller): Long = transaction {
        MenuAiLog.selectAll().where {
            (MenuAiLog.tenantId eq who.principal.tenantId) and (MenuAiLog.venueId eq who.venue.venueId) and
                (MenuAiLog.kind eq "photo") and (MenuAiLog.outcome eq "proposed") and
                (MenuAiLog.createdAt greater CloudTime.now().minusHours(24))
        }.count()
    }

    // --- generate ---

    fun generate(who: AiCaller, req: AiPhotoRequest): AiPhotoPreviewDto {
        if (!images.enabled) throw MenuAiException(409, "menu_ai_photos_disabled", "AI photos are not set up on this portal")
        val mode = req.mode.lowercase().takeIf { it == GENERATE || it == ENHANCE }
            ?: throw BadRequestException("mode is generate or enhance", "bad_request")
        val scope = who.venue.scope
        val item = loadItem(scope, req.itemId.take(200)) ?: throw NotFoundException("that item is not on this store's menu", "not_found")
        if (!storeSpeaksSync(scope)) throw ConflictException(
            "this store's app is too old to take menu changes from the portal", "store_not_upgraded")
        // the item's text goes into the image prompt: checked like the assistant's names first (the
        // store's PhotoPrompts.safe also drops a bad name, but here nothing is drawn at all)
        val name = item.nameEn.ifBlank { item.nameFr }
        if (name.isBlank() || AiGuard.checkText(name) != null || AiGuard.hateful(name) || AiGuard.offTopic(name)) {
            ai.record(who, "photo", "refused_name", ref = item.id)
            throw MenuAiException(422, "menu_ai_photo_name", "this item's name can't be used for an AI photo")
        }
        val existing = transaction { currentPhoto(scope, item.id)?.let { it[ItemPhotos.content] to it[ItemPhotos.contentType] } }
        if (mode == ENHANCE && existing == null) throw BadRequestException("this item has no photo to enhance", "menu_ai_photo_none")
        if (photosToday(who) >= config.menuAiPhotoDailyCap) {
            ai.record(who, "photo", "photo_daily_limit")
            throw MenuAiException(429, "menu_ai_photo_daily_limit",
                "today's AI photo limit for this store is reached (${config.menuAiPhotoDailyCap} a day)", 3600)
        }
        ai.gate(who, "photo")
        val style = HouseStyle.forBrand(if (who.venue.retail) HouseStyle.SAGE_POPPY.brand else HouseStyle.COPPER_LANTERN.brand)
        val facts = ItemFacts(name, item.descriptionEn, item.category)
        val started = now()
        val (image, provider) = try {
            images.run { p ->
                if (mode == ENHANCE) p.enhance(existing!!.first, existing.second, PhotoPrompts.enhance(facts, style))
                else p.generate(PhotoPrompts.generate(facts, style))
            }
        } catch (e: ImageGenException) {
            ai.record(who, "photo", e.code, elapsedMs = now() - started, ref = item.id)
            log.info("AI photo ($mode) for ${who.venue.venueId}/${item.id} failed: ${e.code}")
            throw MenuAiException(e.status, if (e.code == ImageGenException.REFUSED) "menu_ai_photo_refused" else "menu_ai_photo_failed",
                if (e.code == ImageGenException.REFUSED) "the image service declined this picture" else "the picture could not be made; try again")
        }
        val clean = try {
            PhotoCheck.normalize(image)
        } catch (e: ImageGenException) {
            ai.record(who, "photo", "bad_image", elapsedMs = now() - started, ref = item.id)
            throw MenuAiException(502, "menu_ai_photo_failed", "the picture could not be made; try again")
        }
        val elapsed = now() - started
        val source = if (mode == ENHANCE) "ai_enhanced" else "ai_generated"
        val photoId = UUID.randomUUID().toString()
        transaction {
            sweep()
            // a Retry replaces this user's earlier preview of the same item
            MenuAiPhotos.update({
                (MenuAiPhotos.tenantId eq who.principal.tenantId) and (MenuAiPhotos.venueId eq who.venue.venueId) and
                    (MenuAiPhotos.itemId eq item.id) and (MenuAiPhotos.userId eq who.principal.userId) and
                    (MenuAiPhotos.status eq "pending")
            }) { it[status] = "discarded"; it[content] = null; it[decidedAt] = CloudTime.now() }
            MenuAiPhotos.insert {
                it[id] = photoId
                it[tenantId] = who.principal.tenantId
                it[venueId] = who.venue.venueId
                it[itemId] = item.id
                it[userId] = who.principal.userId
                it[MenuAiPhotos.photoSource] = source
                it[status] = "pending"
                it[content] = clean.bytes
                it[contentType] = clean.contentType
                it[MenuAiPhotos.provider] = provider.id
                it[model] = provider.model
                it[createdAt] = CloudTime.now()
            }
        }
        ai.record(who, "photo", "proposed", 1, elapsedMs = elapsed, ref = photoId)
        log.info("AI photo ($mode) for ${who.venue.venueId}/${item.id} via ${provider.id}/${provider.model}: ${clean.bytes.size} bytes in ${elapsed}ms")
        return AiPhotoPreviewDto(photoId, item.id, name, source, provider.id, provider.model, clean.contentType,
            Base64.getEncoder().encodeToString(clean.bytes), elapsed, existing != null)
    }

    /** Previews nobody decided on within [PREVIEW_TTL_MS] lose their bytes (inside a transaction). */
    private fun sweep() {
        val cutoff = CloudTime.now().minusNanos(PREVIEW_TTL_MS * 1_000_000)
        MenuAiPhotos.update({ (MenuAiPhotos.status eq "pending") and (MenuAiPhotos.createdAt less cutoff) }) {
            it[status] = "discarded"; it[content] = null; it[decidedAt] = CloudTime.now()
        }
        // decided rows that hold nothing any more are only clutter after a while
        MenuAiPhotos.deleteWhere {
            (MenuAiPhotos.status eq "discarded") and (MenuAiPhotos.createdAt less CloudTime.now().minusDays(7))
        }
    }

    private fun mine(who: AiCaller, photoId: String) = MenuAiPhotos.selectAll().where {
        (MenuAiPhotos.id eq photoId) and (MenuAiPhotos.tenantId eq who.principal.tenantId) and
            (MenuAiPhotos.venueId eq who.venue.venueId)
    }.forUpdate().firstOrNull()

    /**
     * A printed menu's stand-in photo for an item that has none, kept as the
     * item's photo because the manager ticked "save them to the items": the
     * same pending row and [accept] as a preview, so the store gets it on its
     * next sync and Undo works. Null when it can't be kept (not a usable
     * picture, an old store app, the item got a photo meanwhile).
     */
    internal fun saveForPrint(who: AiCaller, itemId: String, image: GeneratedImage, providerId: String, providerModel: String): AiPhotoAcceptResult? {
        val clean = runCatching { PhotoCheck.normalize(image) }.getOrNull() ?: return null
        val scope = who.venue.scope
        if (!storeSpeaksSync(scope)) return null
        if (transaction { currentPhoto(scope, itemId) } != null) return null
        val photoId = UUID.randomUUID().toString()
        transaction {
            MenuAiPhotos.insert {
                it[id] = photoId
                it[tenantId] = who.principal.tenantId
                it[venueId] = who.venue.venueId
                it[MenuAiPhotos.itemId] = itemId
                it[userId] = who.principal.userId
                it[MenuAiPhotos.photoSource] = "ai_generated"
                it[status] = "pending"
                it[content] = clean.bytes
                it[contentType] = clean.contentType
                it[MenuAiPhotos.provider] = providerId.take(40)
                it[model] = providerModel.take(80)
                it[createdAt] = CloudTime.now()
            }
        }
        return runCatching { accept(who, photoId) }.onFailure {
            log.info("print photo for ${who.venue.venueId}/$itemId not kept: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    // --- accept / discard / undo ---

    /**
     * [everyStore] ("All stores" in the portal): the photo also becomes the
     * item's photo at every other store that carries the item (and takes
     * portal edits), each with its own feed entry and its own Undo record
     * (a row `<photoId>@<venueId>`, undone with [photoId]).
     */
    fun accept(who: AiCaller, photoId: String, everyStore: Boolean = false): AiPhotoAcceptResult {
        val result = transaction {
            val row = mine(who, photoId)?.takeIf { it[MenuAiPhotos.userId] == who.principal.userId }
                ?: throw NotFoundException("that AI photo has expired; make a new one", "menu_ai_photo_expired")
            if (row[MenuAiPhotos.status] == "accepted") throw ConflictException("that photo is already the item's photo", "menu_ai_photo_already_accepted")
            val bytes = row[MenuAiPhotos.content]
            if (row[MenuAiPhotos.status] != "pending" || bytes == null ||
                row[MenuAiPhotos.createdAt].isBefore(CloudTime.now().minusNanos(PREVIEW_TTL_MS * 1_000_000)))
                throw NotFoundException("that AI photo has expired; make a new one", "menu_ai_photo_expired")
            val scope = who.venue.scope
            val itemId = row[MenuAiPhotos.itemId]
            if (!storeSpeaksSync(scope)) throw ConflictException(
                "this store's app is too old to take menu changes from the portal", "store_not_upgraded")
            val item = CatalogItems.selectAll().where {
                (CatalogItems.tenantId eq scope.tenantId) and (CatalogItems.venueId eq scope.venueId) and
                    (CatalogItems.id eq itemId) and (CatalogItems.deleted eq false)
            }.firstOrNull() ?: throw NotFoundException("that item is no longer on the menu", "not_found")
            val prev = currentPhoto(scope, itemId)
            val source = row[MenuAiPhotos.photoSource]
            val version = writePhoto(scope, itemId, bytes, row[MenuAiPhotos.contentType], source, prev?.get(ItemPhotos.version))
            MenuAiPhotos.update({ MenuAiPhotos.id eq photoId }) {
                it[status] = "accepted"
                it[MenuAiPhotos.version] = version
                it[hadPrev] = prev != null
                it[prevContent] = prev?.get(ItemPhotos.content)
                it[prevContentType] = prev?.get(ItemPhotos.contentType)
                it[prevSource] = item[CatalogItems.photoSource]
                it[decidedAt] = CloudTime.now()
            }
            val others = if (!everyStore) emptyList() else otherCarryingStores(who, itemId).map { other ->
                val oPrev = currentPhoto(other, itemId)
                val oPrevSource = CatalogItems.selectAll().where {
                    (CatalogItems.tenantId eq other.tenantId) and (CatalogItems.venueId eq other.venueId) and (CatalogItems.id eq itemId)
                }.first()[CatalogItems.photoSource]
                val v = writePhoto(other, itemId, bytes, row[MenuAiPhotos.contentType], source, oPrev?.get(ItemPhotos.version))
                MenuAiPhotos.insert {
                    it[id] = siblingId(photoId, other.venueId)
                    it[tenantId] = other.tenantId
                    it[venueId] = other.venueId
                    it[MenuAiPhotos.itemId] = itemId
                    it[userId] = who.principal.userId
                    it[MenuAiPhotos.photoSource] = source
                    it[status] = "accepted"
                    it[contentType] = row[MenuAiPhotos.contentType]
                    it[provider] = row[MenuAiPhotos.provider]
                    it[model] = row[MenuAiPhotos.model]
                    it[MenuAiPhotos.version] = v
                    it[hadPrev] = oPrev != null
                    it[prevContent] = oPrev?.get(ItemPhotos.content)
                    it[prevContentType] = oPrev?.get(ItemPhotos.contentType)
                    it[prevSource] = oPrevSource
                    it[createdAt] = CloudTime.now()
                    it[decidedAt] = CloudTime.now()
                }
                other.venueId
            }
            AiPhotoAcceptResult(photoId, itemId, version, source, listOf(scope.venueId) + others)
        }
        ai.record(who, "photo_accept", "applied", 1, ref = photoId)
        log.info("AI photo $photoId accepted for ${who.venue.venueId}/${result.itemId}")
        return result
    }

    fun discard(who: AiCaller, photoId: String) {
        transaction {
            MenuAiPhotos.update({
                (MenuAiPhotos.id eq photoId) and (MenuAiPhotos.tenantId eq who.principal.tenantId) and
                    (MenuAiPhotos.venueId eq who.venue.venueId) and (MenuAiPhotos.userId eq who.principal.userId) and
                    (MenuAiPhotos.status eq "pending")
            }) { it[status] = "discarded"; it[content] = null; it[decidedAt] = CloudTime.now() }
        }
    }

    /** Put back the photo [photoId] replaced (or remove it, when the item had none) — only while it is still the item's photo. */
    fun undo(who: AiCaller, photoId: String): AiPhotoUndoResult {
        val result = transaction {
            val row = mine(who, photoId) ?: throw NotFoundException("no such AI photo", "menu_ai_photo_not_found")
            if (row[MenuAiPhotos.status] == "undone") throw ConflictException("that was already undone", "menu_ai_already_reverted")
            if (row[MenuAiPhotos.status] != "accepted") throw NotFoundException("that AI photo was never used", "menu_ai_photo_not_found")
            val out = restore(who.venue.scope, row) ?: throw ConflictException(
                "the item's photo was changed since; nothing was undone", "menu_ai_photo_changed")
            // the same photo accepted at the other stores ("All stores"): each gets its own previous photo
            // back, unless that store's photo was changed since (then it keeps that one)
            MenuAiPhotos.selectAll().where {
                (MenuAiPhotos.tenantId eq who.principal.tenantId) and (MenuAiPhotos.id like "$photoId@%") and
                    (MenuAiPhotos.status eq "accepted")
            }.forUpdate().toList().forEach { sib ->
                if (restore(Scope(who.principal.tenantId, sib[MenuAiPhotos.venueId]), sib) == null)
                    log.info("AI photo $photoId: ${sib[MenuAiPhotos.venueId]} changed its photo since; kept")
            }
            out
        }
        ai.record(who, "photo_undo", "reverted", 1, ref = photoId)
        log.info("AI photo $photoId undone for ${who.venue.venueId}/${result.itemId}")
        return result
    }

    /**
     * Put back what the accepted photo [row] replaced at [scope] (inside a
     * transaction), or null when the item's photo there was changed since
     * (nothing touched). Marks the row undone.
     */
    private fun restore(scope: Scope, row: org.jetbrains.exposed.sql.ResultRow): AiPhotoUndoResult? {
        val id = row[MenuAiPhotos.id]
        val itemId = row[MenuAiPhotos.itemId]
        val current = currentPhoto(scope, itemId)
        if (current == null || current[ItemPhotos.version] != row[MenuAiPhotos.version]) return null
        val out = if (row[MenuAiPhotos.hadPrev] == true && row[MenuAiPhotos.prevContent] != null) {
            val source = row[MenuAiPhotos.prevSource]
            val v = writePhoto(scope, itemId, row[MenuAiPhotos.prevContent]!!, row[MenuAiPhotos.prevContentType] ?: "image/jpeg",
                source, current[ItemPhotos.version])
            AiPhotoUndoResult(id, itemId, v, source)
        } else {
            removePhoto(scope, itemId)
            AiPhotoUndoResult(id, itemId)
        }
        MenuAiPhotos.update({ MenuAiPhotos.id eq id }) {
            it[status] = "undone"; it[content] = null; it[prevContent] = null; it[decidedAt] = CloudTime.now()
        }
        return out
    }

    private fun siblingId(photoId: String, venueId: String) = "$photoId@$venueId"

    /** The tenant's other stores that carry [itemId] (live) and take portal edits (inside a transaction). */
    private fun otherCarryingStores(who: AiCaller, itemId: String): List<Scope> =
        Venues.selectAll().where { (Venues.tenantId eq who.principal.tenantId) and Venues.menuSyncAt.isNotNull() }
            .orderBy(Venues.id).map { it[Venues.id] }
            .filter { it != who.venue.venueId }
            .map { Scope(who.principal.tenantId, it) }
            .filter { s ->
                CatalogItems.selectAll().where {
                    (CatalogItems.tenantId eq s.tenantId) and (CatalogItems.venueId eq s.venueId) and
                        (CatalogItems.id eq itemId) and (CatalogItems.deleted eq false)
                }.any()
            }

    // --- the item's photo, and the store's copy of it ---

    /**
     * Make [bytes] the item's photo (inside a transaction): item_photos like a
     * store upload, the item's photo version and source, and a `photo` feed
     * entry for the store. The version only ever goes up, so the store can
     * tell this photo from a newer one.
     */
    private fun writePhoto(scope: Scope, itemId: String, bytes: ByteArray, contentType: String, source: String?, prevVersion: Long?): Long =
        MenuPhotos.write(scope, itemId, bytes, contentType, source, prevVersion, now())

    private fun removePhoto(scope: Scope, itemId: String) {
        ItemPhotos.deleteWhere {
            (ItemPhotos.tenantId eq scope.tenantId) and (ItemPhotos.venueId eq scope.venueId) and (ItemPhotos.itemId eq itemId)
        }
        CatalogItems.update({
            (CatalogItems.tenantId eq scope.tenantId) and (CatalogItems.venueId eq scope.venueId) and (CatalogItems.id eq itemId)
        }) {
            it[photoVersion] = null
            it[photoSource] = null
        }
        MenuState.appendFeed(scope, FEED_ENTITY, itemId, buildJsonObject {
            put("itemId", itemId); put("deleted", true)
        }, "portal")
    }
}

/**
 * What a picture must be before the cloud keeps it: a real JPEG or PNG (by
 * its bytes, not by what the provider said), decodable, at most 2 MB. A
 * large one is re-encoded as a JPEG of at most 1600 px a side.
 */
object PhotoCheck {
    /** "image/jpeg" | "image/png" from the first bytes, or null. */
    fun sniff(b: ByteArray): String? = when {
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() &&
            b[3] == 'G'.code.toByte() && b[4] == 0x0D.toByte() && b[5] == 0x0A.toByte() -> "image/png"
        else -> null
    }

    fun normalize(img: GeneratedImage): GeneratedImage {
        val type = sniff(img.bytes) ?: throw ImageGenException(502, ImageGenException.ERROR, "the provider sent something that is not a picture")
        val decoded = runCatching { ImageIO.read(ByteArrayInputStream(img.bytes)) }.getOrNull()
            ?: throw ImageGenException(502, ImageGenException.ERROR, "the picture could not be read")
        if (decoded.width < 16 || decoded.height < 16) throw ImageGenException(502, ImageGenException.ERROR, "the picture is too small")
        if (img.bytes.size <= REENCODE && decoded.width <= SIDE && decoded.height <= SIDE) return GeneratedImage(img.bytes, type)
        val scale = minOf(1.0, SIDE.toDouble() / maxOf(decoded.width, decoded.height))
        val w = (decoded.width * scale).toInt().coerceAtLeast(1)
        val h = (decoded.height * scale).toInt().coerceAtLeast(1)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            color = java.awt.Color.WHITE
            fillRect(0, 0, w, h)
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(decoded, 0, 0, w, h, null)
            dispose()
        }
        val bytes = ByteArrayOutputStream().use { ImageIO.write(out, "jpg", it); it.toByteArray() }
        if (bytes.size > AiPhotoService.MAX_BYTES) throw ImageGenException(502, ImageGenException.ERROR, "the picture is too large")
        return GeneratedImage(bytes, "image/jpeg")
    }

    private const val REENCODE = 1_500_000
    private const val SIDE = 1600
}
