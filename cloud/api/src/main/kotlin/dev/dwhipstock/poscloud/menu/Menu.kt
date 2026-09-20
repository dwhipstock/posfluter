package dev.dwhipstock.poscloud.menu

import dev.dwhipstock.poscloud.ConflictException
import dev.dwhipstock.poscloud.NotFoundException
import dev.dwhipstock.poscloud.auth.requirePortal
import dev.dwhipstock.poscloud.catalog.Catalog
import dev.dwhipstock.poscloud.catalog.Scope
import dev.dwhipstock.poscloud.catalog.uniqueSlug
import dev.dwhipstock.poscloud.db.CatalogCategories
import dev.dwhipstock.poscloud.db.CatalogItems
import dev.dwhipstock.poscloud.db.CatalogVariants
import dev.dwhipstock.poscloud.db.ItemPhotos
import dev.dwhipstock.poscloud.db.Venues
import dev.dwhipstock.poscloud.store.receivePhoto
import dev.dwhipstock.poscloud.store.storePhoto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Portal menu editor over the cloud-authoritative catalog (API.md). Every
 * mutation appends a full post-mutation snapshot to catalog_changes — the
 * store converges on its next poll (CONTRACT §4). Ids are immutable slugs;
 * deletes are soft (snapshot deleted:true soft-deletes at the store).
 */

@Serializable
data class MenuVariantDto(
    val id: String, val labelFr: String, val labelEn: String,
    val priceCents: Long, val sortOrder: Int)

@Serializable
data class MenuItemDto(
    val id: String, val nameFr: String, val nameEn: String,
    val descriptionFr: String, val descriptionEn: String, val categoryId: String,
    val abbrev: String?, val isAlcohol: Boolean, val active: Boolean,
    val photoVersion: Long?, val variants: List<MenuVariantDto>)

@Serializable
data class MenuCategoryDto(val id: String, val nameFr: String, val nameEn: String, val sortOrder: Int)

@Serializable
data class MenuResponse(val categories: List<MenuCategoryDto>, val items: List<MenuItemDto>)

@Serializable
data class CategoryCreateRequest(val nameFr: String, val nameEn: String)

@Serializable
data class CategoryPatchRequest(val nameFr: String? = null, val nameEn: String? = null)

@Serializable
data class CategoryReorderRequest(val orderedIds: List<String>)

@Serializable
data class VariantCreateRequest(val labelFr: String, val labelEn: String, val priceCents: Long)

@Serializable
data class VariantPatchRequest(
    val labelFr: String? = null, val labelEn: String? = null, val priceCents: Long? = null)

@Serializable
data class ItemCreateRequest(
    val nameFr: String, val nameEn: String,
    val descriptionFr: String = "", val descriptionEn: String = "",
    val categoryId: String, val abbrev: String,
    val isAlcohol: Boolean = false, val variants: List<VariantCreateRequest>)

@Serializable
data class ItemPatchRequest(
    val nameFr: String? = null, val nameEn: String? = null, val categoryId: String? = null,
    val descriptionFr: String? = null, val descriptionEn: String? = null,
    val abbrev: String? = null, val isAlcohol: Boolean? = null, val active: Boolean? = null)

fun Route.menuRoutes() {

    get("/menu") {
        val scope = menuScope(call)
        val response = transaction {
            val categories = CatalogCategories.selectAll().where {
                scoped(scope) and (CatalogCategories.deleted eq false)
            }.orderBy(CatalogCategories.sortOrder).map {
                MenuCategoryDto(
                    it[CatalogCategories.id], it[CatalogCategories.nameFr],
                    it[CatalogCategories.nameEn], it[CatalogCategories.sortOrder],
                )
            }
            val items = CatalogItems.selectAll().where {
                (CatalogItems.tenantId eq scope.tenantId) and (CatalogItems.venueId eq scope.venueId) and
                    (CatalogItems.deleted eq false)
            }.orderBy(CatalogItems.id).map { itemDto(scope, it) }
            MenuResponse(categories, items)
        }
        call.respond(response)
    }

    // --- categories ---

    post("/menu/categories") {
        val scope = menuScope(call)
        val req = call.receive<CategoryCreateRequest>()
        require(req.nameFr.isNotBlank() && req.nameEn.isNotBlank()) { "category names must not be blank" }
        val dto = transaction {
            val categoryId = uniqueSlug(req.nameEn, fallback = "category") { candidate ->
                CatalogCategories.selectAll().where { scoped(scope) and (CatalogCategories.id eq candidate) }.any()
            }
            val maxSort = CatalogCategories.selectAll().where { scoped(scope) }
                .maxOfOrNull { it[CatalogCategories.sortOrder] } ?: -1
            CatalogCategories.insert {
                it[tenantId] = scope.tenantId
                it[venueId] = scope.venueId
                it[id] = categoryId
                it[nameFr] = req.nameFr.trim()
                it[nameEn] = req.nameEn.trim()
                it[sortOrder] = maxSort + 1
                it[deleted] = false
            }
            appendCategoryChange(scope, categoryId)
            categoryDto(scope, categoryId)
        }
        call.respond(HttpStatusCode.Created, dto)
    }

    patch("/menu/categories/order") {
        val scope = menuScope(call)
        val req = call.receive<CategoryReorderRequest>()
        require(req.orderedIds.isNotEmpty()) { "orderedIds must not be empty" }
        transaction {
            req.orderedIds.forEachIndexed { index, id ->
                requireLiveCategory(scope, id)
                CatalogCategories.update({ scoped(scope) and (CatalogCategories.id eq id) }) {
                    it[sortOrder] = index
                }
            }
            req.orderedIds.forEach { appendCategoryChange(scope, it) }
        }
        call.respond(mapOf("ok" to true))
    }

    patch("/menu/categories/{id}") {
        val scope = menuScope(call)
        val categoryId = call.parameters["id"]!!
        val req = call.receive<CategoryPatchRequest>()
        req.nameFr?.let { require(it.isNotBlank()) { "nameFr must not be blank" } }
        req.nameEn?.let { require(it.isNotBlank()) { "nameEn must not be blank" } }
        val dto = transaction {
            requireLiveCategory(scope, categoryId)
            CatalogCategories.update({ scoped(scope) and (CatalogCategories.id eq categoryId) }) { row ->
                req.nameFr?.let { row[nameFr] = it.trim() }
                req.nameEn?.let { row[nameEn] = it.trim() }
            }
            appendCategoryChange(scope, categoryId)
            categoryDto(scope, categoryId)
        }
        call.respond(dto)
    }

    delete("/menu/categories/{id}") {
        val scope = menuScope(call)
        val categoryId = call.parameters["id"]!!
        transaction {
            requireLiveCategory(scope, categoryId)
            val liveItems = CatalogItems.selectAll().where {
                (CatalogItems.tenantId eq scope.tenantId) and (CatalogItems.venueId eq scope.venueId) and
                    (CatalogItems.categoryId eq categoryId) and (CatalogItems.deleted eq false)
            }.count()
            if (liveItems > 0) throw ConflictException(
                "category $categoryId still has $liveItems item(s)", "category_in_use")
            CatalogCategories.update({ scoped(scope) and (CatalogCategories.id eq categoryId) }) {
                it[deleted] = true
            }
            appendCategoryChange(scope, categoryId)
        }
        call.respond(mapOf("ok" to true))
    }

    // --- items ---

    post("/menu/items") {
        val scope = menuScope(call)
        val req = call.receive<ItemCreateRequest>()
        validateItemFields(req.nameFr, req.nameEn, req.abbrev)
        require(req.variants.isNotEmpty()) { "at least one variant (size + price) is required" }
        req.variants.forEach { validateVariantFields(it.labelFr, it.labelEn, it.priceCents) }
        val dto = transaction {
            requireLiveCategory(scope, req.categoryId)
            val itemId = uniqueSlug(req.nameEn) { candidate ->
                CatalogItems.selectAll().where {
                    (CatalogItems.tenantId eq scope.tenantId) and (CatalogItems.venueId eq scope.venueId) and
                        (CatalogItems.id eq candidate)
                }.any()
            }
            CatalogItems.insert {
                it[tenantId] = scope.tenantId
                it[venueId] = scope.venueId
                it[id] = itemId
                it[nameFr] = req.nameFr.trim()
                it[nameEn] = req.nameEn.trim()
                it[categoryId] = req.categoryId
                it[abbrev] = req.abbrev.trim()
                it[isAlcohol] = req.isAlcohol
                it[active] = true
                it[deleted] = false
            }
            req.variants.forEachIndexed { index, v -> insertVariant(scope, itemId, v, index) }
            appendItemChange(scope, itemId)
            itemDtoById(scope, itemId)
        }
        call.respond(HttpStatusCode.Created, dto)
    }

    patch("/menu/items/{id}") {
        val scope = menuScope(call)
        val itemId = call.parameters["id"]!!
        val req = call.receive<ItemPatchRequest>()
        req.nameFr?.let { require(it.isNotBlank()) { "nameFr must not be blank" } }
        req.nameEn?.let { require(it.isNotBlank()) { "nameEn must not be blank" } }
        req.abbrev?.let { require(it.isNotBlank() && it.trim().length <= 4) { "abbrev must be 1-4 characters" } }
        val dto = transaction {
            requireLiveItem(scope, itemId)
            req.categoryId?.let { requireLiveCategory(scope, it) }
            CatalogItems.update({ itemScoped(scope, itemId) }) { row ->
                req.nameFr?.let { row[nameFr] = it.trim() }
                req.nameEn?.let { row[nameEn] = it.trim() }
                req.categoryId?.let { row[categoryId] = it }
                req.abbrev?.let { row[abbrev] = it.trim() }
                req.isAlcohol?.let { row[isAlcohol] = it }
                req.active?.let { row[active] = it }
            }
            appendItemChange(scope, itemId)
            itemDtoById(scope, itemId)
        }
        call.respond(dto)
    }

    delete("/menu/items/{id}") {
        val scope = menuScope(call)
        val itemId = call.parameters["id"]!!
        transaction {
            requireLiveItem(scope, itemId)
            CatalogItems.update({ itemScoped(scope, itemId) }) {
                it[active] = false
                it[deleted] = true
            }
            appendItemChange(scope, itemId)
        }
        call.respond(mapOf("ok" to true))
    }

    // --- variants ---

    post("/menu/items/{id}/variants") {
        val scope = menuScope(call)
        val itemId = call.parameters["id"]!!
        val req = call.receive<VariantCreateRequest>()
        validateVariantFields(req.labelFr, req.labelEn, req.priceCents)
        val dto = transaction {
            requireLiveItem(scope, itemId)
            val maxSort = CatalogVariants.selectAll().where {
                (CatalogVariants.tenantId eq scope.tenantId) and (CatalogVariants.venueId eq scope.venueId) and
                    (CatalogVariants.itemId eq itemId)
            }.maxOfOrNull { it[CatalogVariants.sortOrder] } ?: -1
            insertVariant(scope, itemId, req, maxSort + 1)
            appendItemChange(scope, itemId)
            itemDtoById(scope, itemId)
        }
        call.respond(HttpStatusCode.Created, dto)
    }

    patch("/menu/items/{id}/variants/{variantId}") {
        val scope = menuScope(call)
        val itemId = call.parameters["id"]!!
        val variantId = call.parameters["variantId"]!!
        val req = call.receive<VariantPatchRequest>()
        req.labelFr?.let { require(it.isNotBlank()) { "labelFr must not be blank" } }
        req.labelEn?.let { require(it.isNotBlank()) { "labelEn must not be blank" } }
        req.priceCents?.let { require(it >= 0) { "price must be >= 0" } }
        val dto = transaction {
            requireLiveVariant(scope, itemId, variantId)
            CatalogVariants.update({ variantScoped(scope, variantId) }) { row ->
                req.labelFr?.let { row[labelFr] = it.trim() }
                req.labelEn?.let { row[labelEn] = it.trim() }
                req.priceCents?.let { row[priceCents] = it }
            }
            appendItemChange(scope, itemId)
            itemDtoById(scope, itemId)
        }
        call.respond(dto)
    }

    delete("/menu/items/{id}/variants/{variantId}") {
        val scope = menuScope(call)
        val itemId = call.parameters["id"]!!
        val variantId = call.parameters["variantId"]!!
        val dto = transaction {
            requireLiveVariant(scope, itemId, variantId)
            val liveSiblings = CatalogVariants.selectAll().where {
                (CatalogVariants.tenantId eq scope.tenantId) and (CatalogVariants.venueId eq scope.venueId) and
                    (CatalogVariants.itemId eq itemId) and (CatalogVariants.deleted eq false)
            }.count()
            if (liveSiblings <= 1) throw ConflictException(
                "cannot delete the last size of $itemId; delete the item instead", "last_variant")
            CatalogVariants.update({ variantScoped(scope, variantId) }) { it[deleted] = true }
            appendItemChange(scope, itemId)
            itemDtoById(scope, itemId)
        }
        call.respond(dto)
    }

    // --- photos ---

    put("/menu/items/{id}/photo") {
        val scope = menuScope(call)
        val itemId = call.parameters["id"]!!
        transaction { requireLiveItem(scope, itemId) }
        val (bytes, contentType) = receivePhoto(call)
        val version = storePhoto(scope, itemId, bytes, contentType)
        call.respond(mapOf("itemId" to itemId, "photoVersion" to version.toString()))
    }

    get("/menu/items/{id}/photo") {
        val scope = menuScope(call)
        val itemId = call.parameters["id"]!!
        val row = transaction {
            ItemPhotos.selectAll().where {
                (ItemPhotos.tenantId eq scope.tenantId) and (ItemPhotos.venueId eq scope.venueId) and
                    (ItemPhotos.itemId eq itemId)
            }.firstOrNull()
        } ?: throw NotFoundException("no photo for item $itemId")
        val etag = "\"${row[ItemPhotos.version]}\""
        if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) {
            call.respond(HttpStatusCode.NotModified)
            return@get
        }
        call.response.header(HttpHeaders.ETag, etag)
        call.respondBytes(row[ItemPhotos.content], ContentType.parse(row[ItemPhotos.contentType]))
    }
}

// --- helpers ---

// venue-aware since M8: ?venue=<id> picks the venue, default stays the first one
private fun menuScope(call: ApplicationCall): Scope = dev.dwhipstock.poscloud.portalVenueScope(call)

private fun org.jetbrains.exposed.sql.SqlExpressionBuilder.scoped(scope: Scope) =
    (CatalogCategories.tenantId eq scope.tenantId) and (CatalogCategories.venueId eq scope.venueId)

private fun org.jetbrains.exposed.sql.SqlExpressionBuilder.itemScoped(scope: Scope, itemId: String) =
    (CatalogItems.tenantId eq scope.tenantId) and (CatalogItems.venueId eq scope.venueId) and
        (CatalogItems.id eq itemId)

private fun org.jetbrains.exposed.sql.SqlExpressionBuilder.variantScoped(scope: Scope, variantId: String) =
    (CatalogVariants.tenantId eq scope.tenantId) and (CatalogVariants.venueId eq scope.venueId) and
        (CatalogVariants.id eq variantId)

private fun validateItemFields(nameFr: String, nameEn: String, abbrev: String) {
    require(nameFr.isNotBlank()) { "nameFr must not be blank" }
    require(nameEn.isNotBlank()) { "nameEn must not be blank" }
    require(abbrev.isNotBlank() && abbrev.trim().length <= 4) { "abbrev must be 1-4 characters" }
}

private fun validateVariantFields(labelFr: String, labelEn: String, priceCents: Long) {
    require(labelFr.isNotBlank()) { "labelFr must not be blank" }
    require(labelEn.isNotBlank()) { "labelEn must not be blank" }
    require(priceCents >= 0) { "price must be >= 0" }
}

private fun requireLiveCategory(scope: Scope, categoryId: String) {
    CatalogCategories.selectAll().where {
        (CatalogCategories.tenantId eq scope.tenantId) and (CatalogCategories.venueId eq scope.venueId) and
            (CatalogCategories.id eq categoryId) and (CatalogCategories.deleted eq false)
    }.firstOrNull() ?: throw NotFoundException("category $categoryId not found")
}

private fun requireLiveItem(scope: Scope, itemId: String) {
    CatalogItems.selectAll().where {
        (CatalogItems.tenantId eq scope.tenantId) and (CatalogItems.venueId eq scope.venueId) and
            (CatalogItems.id eq itemId) and (CatalogItems.deleted eq false)
    }.firstOrNull() ?: throw NotFoundException("item $itemId not found")
}

private fun requireLiveVariant(scope: Scope, itemId: String, variantId: String) {
    CatalogVariants.selectAll().where {
        (CatalogVariants.tenantId eq scope.tenantId) and (CatalogVariants.venueId eq scope.venueId) and
            (CatalogVariants.id eq variantId) and (CatalogVariants.itemId eq itemId) and
            (CatalogVariants.deleted eq false)
    }.firstOrNull() ?: throw NotFoundException("variant $variantId of item $itemId not found")
}

/** Variant id mirrors the store seed style: "lantern-lager:bottle" — item id + ':' + EN label slug. */
private fun insertVariant(scope: Scope, itemId: String, v: VariantCreateRequest, sortOrder: Int) {
    val labelSlug = uniqueSlug(v.labelEn, fallback = "size") { candidate ->
        CatalogVariants.selectAll().where {
            (CatalogVariants.tenantId eq scope.tenantId) and (CatalogVariants.venueId eq scope.venueId) and
                (CatalogVariants.id eq "$itemId:$candidate")
        }.any()
    }
    CatalogVariants.insert {
        it[tenantId] = scope.tenantId
        it[venueId] = scope.venueId
        it[id] = "$itemId:$labelSlug"
        it[CatalogVariants.itemId] = itemId
        it[labelFr] = v.labelFr.trim()
        it[labelEn] = v.labelEn.trim()
        it[priceCents] = v.priceCents
        it[CatalogVariants.sortOrder] = sortOrder
        it[deleted] = false
    }
}

private fun appendItemChange(scope: Scope, itemId: String) {
    Catalog.appendChange(scope, "item", itemId, "upsert", Catalog.itemSnapshot(scope, itemId))
}

private fun appendCategoryChange(scope: Scope, categoryId: String) {
    Catalog.appendChange(scope, "category", categoryId, "upsert", Catalog.categorySnapshot(scope, categoryId))
}

private fun itemDto(scope: Scope, row: ResultRow): MenuItemDto {
    val variants = CatalogVariants.selectAll().where {
        (CatalogVariants.tenantId eq scope.tenantId) and (CatalogVariants.venueId eq scope.venueId) and
            (CatalogVariants.itemId eq row[CatalogItems.id]) and (CatalogVariants.deleted eq false)
    }.orderBy(CatalogVariants.sortOrder).map {
        MenuVariantDto(
            it[CatalogVariants.id], it[CatalogVariants.labelFr], it[CatalogVariants.labelEn],
            it[CatalogVariants.priceCents], it[CatalogVariants.sortOrder],
        )
    }
    return MenuItemDto(
        row[CatalogItems.id], row[CatalogItems.nameFr], row[CatalogItems.nameEn],
        row[CatalogItems.descriptionFr], row[CatalogItems.descriptionEn],
        row[CatalogItems.categoryId], row[CatalogItems.abbrev], row[CatalogItems.isAlcohol],
        row[CatalogItems.active], row[CatalogItems.photoVersion], variants,
    )
}

private fun itemDtoById(scope: Scope, itemId: String): MenuItemDto {
    val row = CatalogItems.selectAll().where {
        (CatalogItems.tenantId eq scope.tenantId) and (CatalogItems.venueId eq scope.venueId) and
            (CatalogItems.id eq itemId)
    }.first()
    return itemDto(scope, row)
}

private fun categoryDto(scope: Scope, categoryId: String): MenuCategoryDto {
    val row = CatalogCategories.selectAll().where {
        (CatalogCategories.tenantId eq scope.tenantId) and (CatalogCategories.venueId eq scope.venueId) and
            (CatalogCategories.id eq categoryId)
    }.first()
    return MenuCategoryDto(
        row[CatalogCategories.id], row[CatalogCategories.nameFr],
        row[CatalogCategories.nameEn], row[CatalogCategories.sortOrder],
    )
}
