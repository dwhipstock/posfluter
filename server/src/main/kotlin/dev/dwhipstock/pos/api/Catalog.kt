package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.sdk.VenueClock

import dev.dwhipstock.pos.base.Categories
import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.restaurant.CheckLines
import dev.dwhipstock.pos.restaurant.Checks
import dev.dwhipstock.pos.restaurant.ConflictException
import dev.dwhipstock.pos.restaurant.NotFoundException
import dev.dwhipstock.pos.sdk.Outbox
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Owner-editable catalog (M6): menu items, variants (sizes/prices), and
 * categories — the owner changes a price and the next open-check fetch (and
 * customer scan) sees it. No redeploy, same story as venue settings.
 *
 * Manager-session-gated like /settings: this is the venue's menu and money.
 * 86'ing stays on its own inline-PIN route so a server can ask a manager to
 * approve without switching sessions.
 *
 * Soft deletes only — closed checks and receipts reference these rows.
 */

@Serializable
data class VariantCreateRequest(
    val labelFr: String, val labelEn: String, val priceCents: Long, val sortOrder: Int? = null)

@Serializable
data class VariantPatchRequest(
    val labelFr: String? = null, val labelEn: String? = null,
    val priceCents: Long? = null, val sortOrder: Int? = null)

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

@Serializable
data class CategoryCreateRequest(val nameFr: String, val nameEn: String, val sortOrder: Int? = null)

@Serializable
data class CategoryPatchRequest(
    val nameFr: String? = null, val nameEn: String? = null, val sortOrder: Int? = null)

@Serializable
data class CategoryReorderRequest(val orderedIds: List<String>)

fun Route.catalogRoutes() {

    // --- items ---

    post("/items") {
        requireManagerSession(call)
        val req = call.receive<ItemCreateRequest>()
        validateItemFields(req.nameFr, req.nameEn, req.abbrev)
        require(req.variants.isNotEmpty()) { "at least one variant (size + price) is required" }
        req.variants.forEach { validateVariantFields(it.labelFr, it.labelEn, it.priceCents) }

        val dto = transaction {
            requireCategory(req.categoryId)
            val itemId = uniqueSlug(req.nameEn, taken = { candidate ->
                Items.selectAll().where { Items.id eq candidate }.any()
            })
            Items.insert {
                it[id] = itemId
                it[nameFr] = req.nameFr.trim()
                it[nameEn] = req.nameEn.trim()
                it[descriptionFr] = req.descriptionFr.trim()
                it[descriptionEn] = req.descriptionEn.trim()
                it[categoryId] = req.categoryId
                it[abbrev] = req.abbrev.trim()
                it[isAlcohol] = req.isAlcohol
                it[active] = true
            }
            req.variants.forEachIndexed { index, v ->
                insertVariant(itemId, v.copy(sortOrder = v.sortOrder ?: index))
            }
            Outbox.write("item.created", "item", itemId, buildJsonObject {
                put("itemId", itemId)
                put("nameFr", req.nameFr.trim())
                put("nameEn", req.nameEn.trim())
                put("descriptionFr", req.descriptionFr.trim())
                put("descriptionEn", req.descriptionEn.trim())
                put("categoryId", req.categoryId)
                put("variantCount", req.variants.size)
                put("item", itemSnapshotJson(itemId))
            })
            itemDto(itemId)
        }
        call.respond(HttpStatusCode.Created, dto)
    }

    patch("/items/{itemId}") {
        requireManagerSession(call)
        val itemId = call.parameters["itemId"]!!
        val req = call.receive<ItemPatchRequest>()
        req.abbrev?.let { require(it.isNotBlank() && it.trim().length <= 4) { "abbrev must be 1-4 characters" } }
        req.nameFr?.let { require(it.isNotBlank()) { "nameFr must not be blank" } }
        req.nameEn?.let { require(it.isNotBlank()) { "nameEn must not be blank" } }

        val dto = transaction {
            requireLiveItem(itemId)
            req.categoryId?.let { requireCategory(it) }
            Items.update({ Items.id eq itemId }) { row ->
                req.nameFr?.let { row[nameFr] = it.trim() }
                req.nameEn?.let { row[nameEn] = it.trim() }
                req.descriptionFr?.let { row[descriptionFr] = it.trim() }
                req.descriptionEn?.let { row[descriptionEn] = it.trim() }
                req.categoryId?.let { row[categoryId] = it }
                req.abbrev?.let { row[abbrev] = it.trim() }
                req.isAlcohol?.let { row[isAlcohol] = it }
                req.active?.let { row[active] = it }
            }
            Outbox.write("item.updated", "item", itemId, buildJsonObject {
                put("itemId", itemId)
                req.nameFr?.let { put("nameFr", it.trim()) }
                req.nameEn?.let { put("nameEn", it.trim()) }
                req.descriptionFr?.let { put("descriptionFr", it.trim()) }
                req.descriptionEn?.let { put("descriptionEn", it.trim()) }
                req.categoryId?.let { put("categoryId", it) }
                req.abbrev?.let { put("abbrev", it.trim()) }
                req.isAlcohol?.let { put("isAlcohol", it) }
                req.active?.let { put("active", it) }
                put("item", itemSnapshotJson(itemId))
            })
            itemDto(itemId)
        }
        call.respond(dto)
    }

    /** Soft delete: the item vanishes from every list; closed checks keep their rows. */
    delete("/items/{itemId}") {
        requireManagerSession(call)
        val itemId = call.parameters["itemId"]!!
        transaction {
            requireLiveItem(itemId)
            val openLines = openCheckLineCount(CheckLines.itemId eq itemId)
            if (openLines > 0) throw ConflictException(
                "item $itemId is on $openLines open check line(s)", "item_in_use")
            val now = VenueClock.now()
            Items.update({ Items.id eq itemId }) {
                it[active] = false
                it[deletedAt] = now
            }
            Outbox.write("item.deleted", "item", itemId, buildJsonObject {
                put("itemId", itemId)
                put("item", itemSnapshotJson(itemId)) // post-mutation: deleted = true
            })
        }
        call.respond(mapOf("itemId" to itemId, "deleted" to "true"))
    }

    // --- variants ---

    post("/items/{itemId}/variants") {
        requireManagerSession(call)
        val itemId = call.parameters["itemId"]!!
        val req = call.receive<VariantCreateRequest>()
        validateVariantFields(req.labelFr, req.labelEn, req.priceCents)
        val dto = transaction {
            requireLiveItem(itemId)
            val maxSort = ItemVariants.selectAll()
                .where { ItemVariants.itemId eq itemId }
                .maxOfOrNull { it[ItemVariants.sortOrder] } ?: -1
            val variantId = insertVariant(itemId, req.copy(sortOrder = req.sortOrder ?: (maxSort + 1)))
            Outbox.write("item.variant_added", "item", itemId, buildJsonObject {
                put("itemId", itemId)
                put("variantId", variantId)
                put("priceCents", req.priceCents)
                put("item", itemSnapshotJson(itemId))
            })
            itemDto(itemId)
        }
        call.respond(HttpStatusCode.Created, dto)
    }

    patch("/items/{itemId}/variants/{variantId}") {
        requireManagerSession(call)
        val itemId = call.parameters["itemId"]!!
        val variantId = call.parameters["variantId"]!!
        val req = call.receive<VariantPatchRequest>()
        req.priceCents?.let { require(it >= 0) { "price must be >= 0" } }
        req.labelFr?.let { require(it.isNotBlank()) { "labelFr must not be blank" } }
        req.labelEn?.let { require(it.isNotBlank()) { "labelEn must not be blank" } }

        val dto = transaction {
            requireLiveVariant(itemId, variantId)
            ItemVariants.update({ ItemVariants.id eq variantId }) { row ->
                req.labelFr?.let { row[labelFr] = it.trim() }
                req.labelEn?.let { row[labelEn] = it.trim() }
                req.priceCents?.let { row[priceCents] = it }
                req.sortOrder?.let { row[sortOrder] = it }
            }
            Outbox.write("item.variant_updated", "item", itemId, buildJsonObject {
                put("itemId", itemId)
                put("variantId", variantId)
                req.priceCents?.let { put("priceCents", it) }
                req.labelFr?.let { put("labelFr", it.trim()) }
                req.labelEn?.let { put("labelEn", it.trim()) }
                put("item", itemSnapshotJson(itemId))
            })
            itemDto(itemId)
        }
        call.respond(dto)
    }

    /**
     * Soft delete a size. Refused while an OPEN/TOTAL_LOCKED check line
     * references it, and for the item's last remaining size.
     */
    delete("/items/{itemId}/variants/{variantId}") {
        requireManagerSession(call)
        val itemId = call.parameters["itemId"]!!
        val variantId = call.parameters["variantId"]!!
        val dto = transaction {
            requireLiveVariant(itemId, variantId)
            val openLines = openCheckLineCount(CheckLines.variantId eq variantId)
            if (openLines > 0) throw ConflictException(
                "variant $variantId is on $openLines open check line(s)", "variant_in_use")
            val liveSiblings = ItemVariants.selectAll().where {
                (ItemVariants.itemId eq itemId) and ItemVariants.deletedAt.isNull()
            }.count()
            if (liveSiblings <= 1) throw ConflictException(
                "cannot delete the last size of $itemId; delete the item instead", "last_variant")
            ItemVariants.update({ ItemVariants.id eq variantId }) {
                it[deletedAt] = VenueClock.now()
            }
            Outbox.write("item.variant_deleted", "item", itemId, buildJsonObject {
                put("itemId", itemId)
                put("variantId", variantId)
                put("item", itemSnapshotJson(itemId)) // post-mutation: variant deleted = true
            })
            itemDto(itemId)
        }
        call.respond(dto)
    }

    // --- categories ---

    post("/categories") {
        requireManagerSession(call)
        val req = call.receive<CategoryCreateRequest>()
        require(req.nameFr.isNotBlank() && req.nameEn.isNotBlank()) { "category names must not be blank" }
        val dto = transaction {
            val categoryId = uniqueSlug(req.nameEn, taken = { candidate ->
                Categories.selectAll().where { Categories.id eq candidate }.any()
            })
            val maxSort = Categories.selectAll().maxOfOrNull { it[Categories.sortOrder] } ?: -1
            Categories.insert {
                it[id] = categoryId
                it[nameFr] = req.nameFr.trim()
                it[nameEn] = req.nameEn.trim()
                it[sortOrder] = req.sortOrder ?: (maxSort + 1)
            }
            Outbox.write("category.created", "category", categoryId, buildJsonObject {
                put("categoryId", categoryId)
                put("nameFr", req.nameFr.trim())
                put("nameEn", req.nameEn.trim())
                put("category", categorySnapshotJson(categoryId))
            })
            categoryDto(categoryId)
        }
        call.respond(HttpStatusCode.Created, dto)
    }

    patch("/categories/{categoryId}") {
        requireManagerSession(call)
        val categoryId = call.parameters["categoryId"]!!
        val req = call.receive<CategoryPatchRequest>()
        req.nameFr?.let { require(it.isNotBlank()) { "nameFr must not be blank" } }
        req.nameEn?.let { require(it.isNotBlank()) { "nameEn must not be blank" } }
        val dto = transaction {
            requireCategory(categoryId)
            Categories.update({ Categories.id eq categoryId }) { row ->
                req.nameFr?.let { row[nameFr] = it.trim() }
                req.nameEn?.let { row[nameEn] = it.trim() }
                req.sortOrder?.let { row[sortOrder] = it }
            }
            Outbox.write("category.updated", "category", categoryId, buildJsonObject {
                put("categoryId", categoryId)
                req.nameFr?.let { put("nameFr", it.trim()) }
                req.nameEn?.let { put("nameEn", it.trim()) }
                req.sortOrder?.let { put("sortOrder", it) }
                put("category", categorySnapshotJson(categoryId))
            })
            categoryDto(categoryId)
        }
        call.respond(dto)
    }

    /** Hard delete is fine here — only allowed while no live item references it. */
    delete("/categories/{categoryId}") {
        requireManagerSession(call)
        val categoryId = call.parameters["categoryId"]!!
        transaction {
            requireCategory(categoryId)
            val liveItems = Items.selectAll().where {
                (Items.categoryId eq categoryId) and Items.deletedAt.isNull()
            }.count()
            if (liveItems > 0) throw ConflictException(
                "category $categoryId still has $liveItems item(s)", "category_not_empty")
            // snapshot before the row goes — the delete event still carries full state
            val snapshot = categorySnapshotJson(categoryId, deleted = true)
            Categories.deleteWhere { Categories.id eq categoryId }
            Outbox.write("category.deleted", "category", categoryId, buildJsonObject {
                put("categoryId", categoryId)
                put("category", snapshot)
            })
        }
        call.respond(mapOf("categoryId" to categoryId, "deleted" to "true"))
    }

    /** Drag-reorder: index in the list becomes sort_order. */
    patch("/categories/order") {
        requireManagerSession(call)
        val req = call.receive<CategoryReorderRequest>()
        require(req.orderedIds.isNotEmpty()) { "orderedIds must not be empty" }
        transaction {
            req.orderedIds.forEachIndexed { index, id ->
                requireCategory(id)
                Categories.update({ Categories.id eq id }) { it[sortOrder] = index }
            }
            Outbox.write("categories.reordered", "category", "*", buildJsonObject {
                put("orderedIds", req.orderedIds.joinToString(","))
                put("categories", allCategoriesJson())
            })
        }
        call.respond(mapOf("ok" to "true"))
    }
}

// --- helpers (call inside a transaction) ---

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

private fun requireCategory(categoryId: String) {
    Categories.selectAll().where { Categories.id eq categoryId }.firstOrNull()
        ?: throw NotFoundException("category $categoryId not found")
}

private fun requireLiveItem(itemId: String) {
    Items.selectAll().where { (Items.id eq itemId) and Items.deletedAt.isNull() }.firstOrNull()
        ?: throw NotFoundException("item $itemId not found")
}

private fun requireLiveVariant(itemId: String, variantId: String) {
    ItemVariants.selectAll().where {
        (ItemVariants.id eq variantId) and (ItemVariants.itemId eq itemId) and
            ItemVariants.deletedAt.isNull()
    }.firstOrNull() ?: throw NotFoundException("variant $variantId of item $itemId not found")
}

/** Lines on OPEN/TOTAL_LOCKED checks matching [matchLine] — these block deletion. */
private fun openCheckLineCount(matchLine: Op<Boolean>): Long {
    val openCheckIds = Checks.selectAll()
        .where { Checks.status inList listOf("OPEN", "TOTAL_LOCKED") }
        .map { it[Checks.id].value }
    if (openCheckIds.isEmpty()) return 0
    return CheckLines.selectAll()
        .where { (CheckLines.checkId inList openCheckIds) and matchLine }
        .count()
}

/** "Lantern House Lager Tower" → "lantern-lager-tower"; French-only names fall back to "item". */
private fun uniqueSlug(source: String, taken: (String) -> Boolean): String {
    val base = source.trim().lowercase()
        .replace(Regex("[^a-z0-9]+"), "-").trim('-')
        .ifBlank { "item" }
    if (!taken(base)) return base
    var n = 2
    while (taken("$base-$n")) n++
    return "$base-$n"
}

private fun insertVariant(itemId: String, v: VariantCreateRequest): String {
    // seed style: "lantern-lager:bottle" — item id + ':' + slug of the EN label
    val labelSlug = uniqueSlug(v.labelEn, taken = { candidate ->
        ItemVariants.selectAll().where { ItemVariants.id eq "$itemId:$candidate" }.any()
    })
    val variantId = "$itemId:$labelSlug"
    ItemVariants.insert {
        it[id] = variantId
        it[ItemVariants.itemId] = itemId
        it[labelFr] = v.labelFr.trim()
        it[labelEn] = v.labelEn.trim()
        it[priceCents] = v.priceCents
        it[sortOrder] = v.sortOrder ?: 0
    }
    return variantId
}

private fun itemDto(itemId: String): ItemDto {
    val variants = ItemVariants.selectAll()
        .where { (ItemVariants.itemId eq itemId) and ItemVariants.deletedAt.isNull() }
        .orderBy(ItemVariants.sortOrder)
        .map { VariantDto(it[ItemVariants.id], it[ItemVariants.labelFr], it[ItemVariants.labelEn], it[ItemVariants.priceCents]) }
    val row = Items.selectAll().where { Items.id eq itemId }.first()
    return ItemDto(
        row[Items.id], row[Items.nameFr], row[Items.nameEn],
        row[Items.descriptionFr], row[Items.descriptionEn], row[Items.categoryId],
        row[Items.abbrev], row[Items.isAlcohol], row[Items.active], variants,
    )
}

private fun categoryDto(categoryId: String): CategoryDto {
    val row = Categories.selectAll().where { Categories.id eq categoryId }.first()
    return CategoryDto(row[Categories.id], row[Categories.nameFr], row[Categories.nameEn], row[Categories.sortOrder])
}
