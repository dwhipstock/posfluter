package dev.dwhipstock.poscloud.menu

import dev.dwhipstock.poscloud.NotFoundException
import dev.dwhipstock.poscloud.catalog.Scope
import dev.dwhipstock.poscloud.db.CatalogCategories
import dev.dwhipstock.poscloud.db.CatalogItems
import dev.dwhipstock.poscloud.db.CatalogVariants
import dev.dwhipstock.poscloud.db.ItemPhotos
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * The portal's menu view: a READ-ONLY mirror of what each store pushes up
 * (one-way sync — the tablet owns its menu; CONTRACT §2). Nothing here writes
 * the catalog or sends anything down to a store.
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
    val photoVersion: Long?, val variants: List<MenuVariantDto>,
    val venueId: String)

@Serializable
data class MenuCategoryDto(val id: String, val nameFr: String, val nameEn: String, val sortOrder: Int)

@Serializable
data class MenuResponse(val categories: List<MenuCategoryDto>, val items: List<MenuItemDto>)

fun Route.menuRoutes() {

    get("/menu") {
        val venues = dev.dwhipstock.poscloud.portalScopes(call).second // one store, or all of them
        call.respond(transaction { menuOf(venues.map { it.scope }) })
    }

    get("/menu/items/{id}/photo") {
        val scope = dev.dwhipstock.poscloud.portalVenueScope(call)
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

/**
 * Live categories and items across [scopes] (inside a transaction). Categories
 * with the same id at several stores are listed once (first store's names win);
 * every item keeps its store id so a combined view can label it.
 */
internal fun menuOf(scopes: List<Scope>): MenuResponse {
    val categories = linkedMapOf<String, MenuCategoryDto>()
    val items = mutableListOf<MenuItemDto>()
    for (scope in scopes) {
        CatalogCategories.selectAll().where {
            (CatalogCategories.tenantId eq scope.tenantId) and (CatalogCategories.venueId eq scope.venueId) and
                (CatalogCategories.deleted eq false)
        }.orderBy(CatalogCategories.sortOrder).forEach {
            categories.putIfAbsent(it[CatalogCategories.id], MenuCategoryDto(
                it[CatalogCategories.id], it[CatalogCategories.nameFr],
                it[CatalogCategories.nameEn], it[CatalogCategories.sortOrder],
            ))
        }
        val variants = CatalogVariants.selectAll().where {
            (CatalogVariants.tenantId eq scope.tenantId) and (CatalogVariants.venueId eq scope.venueId) and
                (CatalogVariants.deleted eq false)
        }.orderBy(CatalogVariants.sortOrder).groupBy { it[CatalogVariants.itemId] }
        CatalogItems.selectAll().where {
            (CatalogItems.tenantId eq scope.tenantId) and (CatalogItems.venueId eq scope.venueId) and
                (CatalogItems.deleted eq false)
        }.orderBy(CatalogItems.id).forEach { items += itemDto(scope, it, variants[it[CatalogItems.id]].orEmpty()) }
    }
    return MenuResponse(categories.values.toList(), items)
}

private fun itemDto(scope: Scope, row: ResultRow, variants: List<ResultRow>) = MenuItemDto(
    row[CatalogItems.id], row[CatalogItems.nameFr], row[CatalogItems.nameEn],
    row[CatalogItems.descriptionFr], row[CatalogItems.descriptionEn],
    row[CatalogItems.categoryId], row[CatalogItems.abbrev], row[CatalogItems.isAlcohol],
    row[CatalogItems.active], row[CatalogItems.photoVersion],
    variants.map {
        MenuVariantDto(
            it[CatalogVariants.id], it[CatalogVariants.labelFr], it[CatalogVariants.labelEn],
            it[CatalogVariants.priceCents], it[CatalogVariants.sortOrder],
        )
    },
    scope.venueId,
)
