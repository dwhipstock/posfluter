package dev.dwhipstock.poscloud.menu

import dev.dwhipstock.poscloud.BadRequestException
import dev.dwhipstock.poscloud.ConflictException
import dev.dwhipstock.poscloud.ForbiddenException
import dev.dwhipstock.poscloud.NotFoundException
import dev.dwhipstock.poscloud.VenueScope
import dev.dwhipstock.poscloud.auth.Principal
import dev.dwhipstock.poscloud.catalog.Scope
import dev.dwhipstock.poscloud.db.CatalogCategories
import dev.dwhipstock.poscloud.db.CatalogItems
import dev.dwhipstock.poscloud.db.MenuEdits
import dev.dwhipstock.poscloud.db.MenuFeed
import dev.dwhipstock.poscloud.db.Venues
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Manager-portal menu editing (two-way menu sync, CONTRACT §10).
 *
 * Scope follows the store picker: `?venue=<id>` edits that store's menu; no
 * venue = **all stores** (a chain-wide change), applied to every store that
 * has the thing (or, for a new one, to every store). Each edit is stamped by
 * the cloud's clock ([CloudHlc]), merged into the cloud's copy, and appended
 * to each store's menu feed; the store applies it the next time it is online.
 * A store whose app predates two-way sync (it never pulled the feed) is
 * skipped — it would never receive the edit.
 *
 * Only owners and managers edit (portal_users.role). Every request may carry
 * an `Idempotency-Key`: a retry with the same key gets the first answer and
 * changes nothing.
 */

@Serializable
data class MenuVariantInput(
    val labelEn: String, val labelFr: String? = null, val priceCents: Long,
    val names: Map<String, String>? = null,
)

@Serializable
data class MenuItemCreate(
    val nameEn: String, val nameFr: String? = null, val names: Map<String, String>? = null,
    val descriptionEn: String? = null, val descriptionFr: String? = null,
    val categoryId: String, val isAlcohol: Boolean? = null, val active: Boolean? = null,
    val abbrev: String? = null, val variants: List<MenuVariantInput>,
)

/** Every field optional: only what is sent changes. `names`: lang → text ("" removes that name). */
@Serializable
data class MenuItemPatch(
    val nameEn: String? = null, val nameFr: String? = null, val names: Map<String, String>? = null,
    val descriptionEn: String? = null, val descriptionFr: String? = null,
    val categoryId: String? = null, val isAlcohol: Boolean? = null, val active: Boolean? = null,
    val abbrev: String? = null,
)

@Serializable
data class MenuVariantPatch(
    val labelEn: String? = null, val labelFr: String? = null, val priceCents: Long? = null,
    val sortOrder: Int? = null, val names: Map<String, String>? = null,
)

@Serializable
data class MenuCategoryCreate(val nameEn: String, val nameFr: String? = null, val names: Map<String, String>? = null)

@Serializable
data class MenuCategoryPatch(
    val nameEn: String? = null, val nameFr: String? = null, val names: Map<String, String>? = null,
    val sortOrder: Int? = null,
)

@Serializable
data class MenuCategoryOrder(val orderedIds: List<String>)

@Serializable
data class SkippedStore(val venueId: String, val reason: String)

/**
 * [applied]: the stores the edit went to (each gets it on its next sync);
 * [skipped]: the ones it could not, with a machine reason
 * (store_not_upgraded | not_found | category_not_found | last_variant | category_not_empty).
 */
@Serializable
data class MenuEditResult(
    val applied: List<String>, val skipped: List<SkippedStore> = emptyList(),
    val id: String? = null, val duplicate: Boolean = false,
)

/** Per store: can the portal edit its menu, and is the store keeping up. */
@Serializable
data class MenuSyncStoreDto(
    val venueId: String, val name: String, val editable: Boolean,
    val lastPullAt: String?, val pending: Long,
)

@Serializable
data class MenuSyncStatus(val canEdit: Boolean, val role: String, val stores: List<MenuSyncStoreDto>)

private val LANG = Regex("^[a-z]{2,8}(-[a-z0-9]{2,8})?$")
private const val MAX_PRICE_CENTS = 10_000_000L

private fun cleanText(v: String?, field: String, max: Int, required: Boolean = false): String? {
    val t = v?.trim() ?: return null
    if (required && t.isEmpty()) throw BadRequestException("$field must not be blank", "blank_$field")
    if (t.length > max) throw BadRequestException("$field is longer than $max characters", "too_long")
    return t
}

private fun cleanNames(names: Map<String, String>?): Map<String, String>? = names?.map { (lang, text) ->
    val code = lang.trim().lowercase()
    if (!LANG.matches(code) || code == "en" || code == "fr")
        throw BadRequestException("bad language code '$lang' (en and fr have their own fields)", "bad_lang")
    code to (cleanText(text, "name", 500) ?: "")
}?.toMap()

private fun cleanPrice(p: Long?): Long? {
    if (p != null && (p < 0 || p > MAX_PRICE_CENTS)) throw BadRequestException("price must be between 0 and 100000.00", "bad_price")
    return p
}

private fun cleanAbbrev(a: String?): String? {
    val t = a?.trim() ?: return null
    if (t.isEmpty() || t.length > 4) throw BadRequestException("abbrev must be 1-4 characters", "bad_abbrev")
    return t
}

private fun defaultAbbrev(nameEn: String): String =
    nameEn.filter { it.isLetterOrDigit() }.take(2).uppercase().ifEmpty { "IT" }

private fun slug(source: String): String =
    source.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "item" }

private val idRandom = java.security.SecureRandom()
private fun suffix(): String = (1..4).map { "abcdefghijkmnpqrstuvwxyz23456789"[idRandom.nextInt(32)] }.joinToString("")

/** Set [f] to [v] with [stamp] when it differs (or always, for a write the manager just made). */
private fun Regs.write(f: String, v: JsonElement, stamp: String) = set(f, v, stamp)

private fun Regs.writeNames(names: Map<String, String>?, stamp: String) {
    names?.forEach { (lang, text) ->
        write(MenuFields.NAMES + lang, if (text.isEmpty()) JsonNull else JsonPrimitive(text), stamp)
    }
}

fun requireMenuEditor(principal: Principal) {
    if (!principal.canEditMenu) throw ForbiddenException("only owners and managers can edit the menu", "menu_edit_forbidden")
}

private fun editable(venueId: String, tenantId: String): Boolean =
    Venues.selectAll().where { (Venues.tenantId eq tenantId) and (Venues.id eq venueId) }
        .firstOrNull()?.get(Venues.menuSyncAt) != null

/**
 * Runs one portal edit: authorizes, replays a seen Idempotency-Key, runs
 * [block] in a transaction with one fresh cloud stamp, and refuses with the
 * single skip reason when nothing could be applied.
 */
private suspend fun RoutingContext.menuEdit(
    status: HttpStatusCode = HttpStatusCode.OK,
    block: (principal: Principal, venues: List<VenueScope>, stamp: String) -> MenuEditResult,
) {
    val (principal, venues) = dev.dwhipstock.poscloud.portalScopes(call)
    requireMenuEditor(principal)
    val key = call.request.headers["Idempotency-Key"]?.trim()?.take(100)?.takeIf { it.isNotEmpty() }
    val result = transaction {
        if (key != null) {
            MenuEdits.selectAll().where { (MenuEdits.tenantId eq principal.tenantId) and (MenuEdits.editId eq key) }
                .firstOrNull()?.let { row ->
                    return@transaction Json.decodeFromString(MenuEditResult.serializer(), row[MenuEdits.response])
                        .copy(duplicate = true)
                }
        }
        val stamp = CloudHlc.now(principal.tenantId)
        val r = block(principal, venues, stamp)
        if (r.applied.isEmpty()) {
            val reason = r.skipped.firstOrNull()?.reason ?: "not_found"
            when (reason) {
                "not_found" -> throw NotFoundException("not on this store's menu", "not_found")
                "category_not_found" -> throw BadRequestException("no such category at this store", "category_not_found")
                "store_not_upgraded" -> throw ConflictException(
                    "this store's app is too old to take menu changes from the portal; edit on the tablet or update it",
                    "store_not_upgraded")
                else -> throw ConflictException("can't do that: $reason", reason)
            }
        }
        if (key != null) MenuEdits.insert {
            it[tenantId] = principal.tenantId
            it[editId] = key
            it[response] = Json.encodeToString(MenuEditResult.serializer(), r)
            it[createdAt] = dev.dwhipstock.poscloud.CloudTime.now()
        }
        r
    }
    call.respond(if (result.duplicate) HttpStatusCode.OK else status, result)
}

/** Load one item at one store, apply [change], canonicalize, save, feed. Null = applied, else the skip reason. */
private fun editItem(scope: Scope, itemId: String, stamp: String, change: (ItemState) -> String?): String? {
    if (!editable(scope.venueId, scope.tenantId)) return "store_not_upgraded"
    val state = MenuState.loadItems(scope, listOf(itemId))[itemId]
    if (state == null || state.item.deleted) return "not_found"
    change(state)?.let { return it }
    state.canonicalize()
    MenuState.saveItems(scope, listOf(state))
    MenuState.appendFeed(scope, MenuFields.ITEM, itemId, state.wire(), "portal")
    return null
}

private fun liveCategory(scope: Scope, id: String): Boolean = CatalogCategories.selectAll().where {
    (CatalogCategories.tenantId eq scope.tenantId) and (CatalogCategories.venueId eq scope.venueId) and
        (CatalogCategories.id eq id) and (CatalogCategories.deleted eq false)
}.any()

private fun result(outcomes: List<Pair<String, String?>>, id: String? = null) = MenuEditResult(
    applied = outcomes.filter { it.second == null }.map { it.first },
    skipped = outcomes.mapNotNull { (v, r) -> r?.let { SkippedStore(v, it) } },
    id = id,
)

fun Route.menuEditRoutes() {

    /** Who may edit, and each in-scope store's sync state (for the portal's banner). */
    get("/menu/sync-status") {
        val (principal, venues) = dev.dwhipstock.poscloud.portalScopes(call)
        val stores = transaction {
            venues.map { v ->
                val row = Venues.selectAll().where { (Venues.tenantId eq principal.tenantId) and (Venues.id eq v.venueId) }.first()
                val cursor = row[Venues.menuCursor] ?: 0L
                val pending = MenuFeed.selectAll().where {
                    (MenuFeed.tenantId eq principal.tenantId) and (MenuFeed.venueId eq v.venueId) and (MenuFeed.seq greater cursor)
                }.count()
                MenuSyncStoreDto(
                    v.venueId, v.name, row[Venues.menuSyncAt] != null,
                    row[Venues.menuSyncAt]?.let { dev.dwhipstock.poscloud.CloudTime.iso(it, v.zone) }, pending,
                )
            }
        }
        call.respond(MenuSyncStatus(principal.canEditMenu, principal.role, stores))
    }

    // --- items ---

    post("/menu/items") {
        val req = call.receive<MenuItemCreate>()
        val nameEn = cleanText(req.nameEn, "nameEn", 200, required = true)!!
        val nameFr = cleanText(req.nameFr, "nameFr", 200)?.takeIf { it.isNotEmpty() } ?: nameEn
        val names = cleanNames(req.names)
        val abbrev = cleanAbbrev(req.abbrev) ?: defaultAbbrev(nameEn)
        val descEn = cleanText(req.descriptionEn, "descriptionEn", 500) ?: ""
        val descFr = cleanText(req.descriptionFr, "descriptionFr", 500) ?: ""
        if (req.variants.isEmpty()) throw BadRequestException("at least one size (with a price) is required", "no_variants")
        val variants = req.variants.map { v ->
            val en = cleanText(v.labelEn, "labelEn", 100, required = true)!!
            Triple(en, cleanText(v.labelFr, "labelFr", 100)?.takeIf { it.isNotEmpty() } ?: en, v) to cleanNames(v.names)
        }
        variants.forEach { cleanPrice(it.first.third.priceCents) }
        menuEdit(HttpStatusCode.Created) { principal, venues, stamp ->
            // one id at every store: the same new dish is the same thing everywhere
            val itemId = generateSequence { "${slug(nameEn)}-${suffix()}" }.first { candidate ->
                CatalogItems.selectAll().where { (CatalogItems.tenantId eq principal.tenantId) and (CatalogItems.id eq candidate) }.empty()
            }
            val outcomes = venues.map { v ->
                val scope = v.scope
                v.venueId to when {
                    !editable(v.venueId, principal.tenantId) -> "store_not_upgraded"
                    !liveCategory(scope, req.categoryId) -> "category_not_found"
                    else -> {
                        val item = Regs(itemId)
                        item.write("nameEn", JsonPrimitive(nameEn), stamp)
                        item.write("nameFr", JsonPrimitive(nameFr), stamp)
                        item.write("descriptionEn", JsonPrimitive(descEn), stamp)
                        item.write("descriptionFr", JsonPrimitive(descFr), stamp)
                        item.write("categoryId", JsonPrimitive(req.categoryId), stamp)
                        item.write("abbrev", JsonPrimitive(abbrev), stamp)
                        item.write("isAlcohol", JsonPrimitive(req.isAlcohol ?: false), stamp)
                        item.write("active", JsonPrimitive(req.active ?: true), stamp)
                        item.write("deleted", JsonPrimitive(false), stamp)
                        item.writeNames(names?.filterValues { it.isNotEmpty() }, stamp)
                        val state = ItemState(item)
                        val used = mutableSetOf<String>()
                        variants.forEachIndexed { i, (labels, vNames) ->
                            val (en, fr, input) = labels
                            var vid = "$itemId:${slug(en)}"
                            var n = 2
                            while (vid in used) vid = "$itemId:${slug(en)}-${n++}"
                            used += vid
                            val r = Regs(vid)
                            r.write("labelEn", JsonPrimitive(en), stamp)
                            r.write("labelFr", JsonPrimitive(fr), stamp)
                            r.write("priceCents", JsonPrimitive(input.priceCents), stamp)
                            r.write("sortOrder", JsonPrimitive(i), stamp)
                            r.write("deleted", JsonPrimitive(false), stamp)
                            r.writeNames(vNames?.filterValues { it.isNotEmpty() }, stamp)
                            state.variants[vid] = r
                        }
                        MenuState.saveItems(scope, listOf(state))
                        MenuState.appendFeed(scope, MenuFields.ITEM, itemId, state.wire(), "portal")
                        null
                    }
                }
            }
            result(outcomes, itemId)
        }
    }

    patch("/menu/items/{itemId}") {
        val itemId = call.parameters["itemId"]!!
        val req = call.receive<MenuItemPatch>()
        val nameEn = cleanText(req.nameEn, "nameEn", 200, required = true)
        val nameFr = cleanText(req.nameFr, "nameFr", 200, required = true)
        val descEn = cleanText(req.descriptionEn, "descriptionEn", 500)
        val descFr = cleanText(req.descriptionFr, "descriptionFr", 500)
        val abbrev = cleanAbbrev(req.abbrev)
        val names = cleanNames(req.names)
        menuEdit { principal, venues, stamp ->
            result(venues.map { v ->
                v.venueId to editItem(v.scope, itemId, stamp) { s ->
                    if (req.categoryId != null && !liveCategory(v.scope, req.categoryId)) return@editItem "category_not_found"
                    val r = s.item
                    nameEn?.let { r.write("nameEn", JsonPrimitive(it), stamp) }
                    nameFr?.let { r.write("nameFr", JsonPrimitive(it), stamp) }
                    descEn?.let { r.write("descriptionEn", JsonPrimitive(it), stamp) }
                    descFr?.let { r.write("descriptionFr", JsonPrimitive(it), stamp) }
                    req.categoryId?.let { r.write("categoryId", JsonPrimitive(it), stamp) }
                    abbrev?.let { r.write("abbrev", JsonPrimitive(it), stamp) }
                    req.isAlcohol?.let { r.write("isAlcohol", JsonPrimitive(it), stamp) }
                    req.active?.let { r.write("active", JsonPrimitive(it), stamp) }
                    r.writeNames(names, stamp)
                    null
                }
            }, itemId)
        }
    }

    /** Soft delete: the item leaves every store's menu; sales history keeps it. */
    delete("/menu/items/{itemId}") {
        val itemId = call.parameters["itemId"]!!
        menuEdit { _, venues, stamp ->
            result(venues.map { v ->
                v.venueId to editItem(v.scope, itemId, stamp) { s -> s.item.write("deleted", JsonPrimitive(true), stamp); null }
            }, itemId)
        }
    }

    // --- sizes ---

    post("/menu/items/{itemId}/variants") {
        val itemId = call.parameters["itemId"]!!
        val req = call.receive<MenuVariantInput>()
        val en = cleanText(req.labelEn, "labelEn", 100, required = true)!!
        val fr = cleanText(req.labelFr, "labelFr", 100)?.takeIf { it.isNotEmpty() } ?: en
        cleanPrice(req.priceCents)
        val names = cleanNames(req.names)
        menuEdit(HttpStatusCode.Created) { _, venues, stamp ->
            var vid: String? = null
            val outcomes = venues.map { v ->
                v.venueId to editItem(v.scope, itemId, stamp) { s ->
                    val id = vid ?: generateSequence { "$itemId:${slug(en)}-${suffix()}" }.first { it !in s.variants }.also { vid = it }
                    val r = Regs(id)
                    r.write("labelEn", JsonPrimitive(en), stamp)
                    r.write("labelFr", JsonPrimitive(fr), stamp)
                    r.write("priceCents", JsonPrimitive(req.priceCents), stamp)
                    r.write("sortOrder", JsonPrimitive((s.variants.values.mapNotNull { it.int("sortOrder") }.maxOrNull() ?: -1) + 1), stamp)
                    r.write("deleted", JsonPrimitive(false), stamp)
                    r.writeNames(names?.filterValues { it.isNotEmpty() }, stamp)
                    s.variants[id] = r
                    null
                }
            }
            result(outcomes, vid)
        }
    }

    patch("/menu/items/{itemId}/variants/{variantId}") {
        val itemId = call.parameters["itemId"]!!
        val variantId = call.parameters["variantId"]!!
        val req = call.receive<MenuVariantPatch>()
        val en = cleanText(req.labelEn, "labelEn", 100, required = true)
        val fr = cleanText(req.labelFr, "labelFr", 100, required = true)
        val price = cleanPrice(req.priceCents)
        val names = cleanNames(req.names)
        menuEdit { _, venues, stamp ->
            result(venues.map { v ->
                v.venueId to editItem(v.scope, itemId, stamp) { s ->
                    val r = s.variants[variantId]?.takeIf { !it.deleted } ?: return@editItem "not_found"
                    en?.let { r.write("labelEn", JsonPrimitive(it), stamp) }
                    fr?.let { r.write("labelFr", JsonPrimitive(it), stamp) }
                    price?.let { r.write("priceCents", JsonPrimitive(it), stamp) }
                    req.sortOrder?.let { r.write("sortOrder", JsonPrimitive(it), stamp) }
                    r.writeNames(names, stamp)
                    null
                }
            }, variantId)
        }
    }

    delete("/menu/items/{itemId}/variants/{variantId}") {
        val itemId = call.parameters["itemId"]!!
        val variantId = call.parameters["variantId"]!!
        menuEdit { _, venues, stamp ->
            result(venues.map { v ->
                v.venueId to editItem(v.scope, itemId, stamp) { s ->
                    val r = s.variants[variantId]?.takeIf { !it.deleted } ?: return@editItem "not_found"
                    if (s.variants.values.count { !it.deleted } <= 1) return@editItem "last_variant"
                    r.write("deleted", JsonPrimitive(true), stamp)
                    null
                }
            }, variantId)
        }
    }

    // --- categories ---

    post("/menu/categories") {
        val req = call.receive<MenuCategoryCreate>()
        val en = cleanText(req.nameEn, "nameEn", 100, required = true)!!
        val fr = cleanText(req.nameFr, "nameFr", 100)?.takeIf { it.isNotEmpty() } ?: en
        val names = cleanNames(req.names)
        menuEdit(HttpStatusCode.Created) { principal, venues, stamp ->
            val id = generateSequence { "${slug(en)}-${suffix()}" }.first { candidate ->
                CatalogCategories.selectAll().where {
                    (CatalogCategories.tenantId eq principal.tenantId) and (CatalogCategories.id eq candidate)
                }.empty()
            }
            result(venues.map { v ->
                v.venueId to if (!editable(v.venueId, principal.tenantId)) "store_not_upgraded" else {
                    val sort = (MenuState.loadCategories(v.scope).values.filter { !it.deleted }
                        .mapNotNull { it.int("sortOrder") }.maxOrNull() ?: -1) + 1
                    val r = Regs(id)
                    r.write("nameEn", JsonPrimitive(en), stamp)
                    r.write("nameFr", JsonPrimitive(fr), stamp)
                    r.write("sortOrder", JsonPrimitive(sort), stamp)
                    r.write("deleted", JsonPrimitive(false), stamp)
                    r.writeNames(names?.filterValues { it.isNotEmpty() }, stamp)
                    MenuState.saveCategories(v.scope, listOf(r))
                    MenuState.appendFeed(v.scope, MenuFields.CATEGORY, id, kotlinx.serialization.json.JsonObject(r.wire(MenuFields.CATEGORY)), "portal")
                    null
                }
            }, id)
        }
    }

    patch("/menu/categories/{categoryId}") {
        val categoryId = call.parameters["categoryId"]!!
        val req = call.receive<MenuCategoryPatch>()
        val en = cleanText(req.nameEn, "nameEn", 100, required = true)
        val fr = cleanText(req.nameFr, "nameFr", 100, required = true)
        val names = cleanNames(req.names)
        menuEdit { _, venues, stamp ->
            result(venues.map { v ->
                v.venueId to editCategory(v.scope, categoryId) { r ->
                    en?.let { r.write("nameEn", JsonPrimitive(it), stamp) }
                    fr?.let { r.write("nameFr", JsonPrimitive(it), stamp) }
                    req.sortOrder?.let { r.write("sortOrder", JsonPrimitive(it), stamp) }
                    r.writeNames(names, stamp)
                    null
                }
            }, categoryId)
        }
    }

    /**
     * A category goes only when it is empty (move or delete its items first).
     * If a store put an item in it meanwhile, that store keeps the category
     * and it comes back in the portal (the store's state wins there).
     */
    delete("/menu/categories/{categoryId}") {
        val categoryId = call.parameters["categoryId"]!!
        menuEdit { _, venues, stamp ->
            result(venues.map { v ->
                v.venueId to editCategory(v.scope, categoryId) { r ->
                    val liveItems = CatalogItems.selectAll().where {
                        (CatalogItems.tenantId eq v.scope.tenantId) and (CatalogItems.venueId eq v.venueId) and
                            (CatalogItems.categoryId eq categoryId) and (CatalogItems.deleted eq false)
                    }.count()
                    if (liveItems > 0) return@editCategory "category_not_empty"
                    r.write("deleted", JsonPrimitive(true), stamp)
                    null
                }
            }, categoryId)
        }
    }

    /** The categories' order: index in [MenuCategoryOrder.orderedIds] becomes the sort order (unlisted ones follow). */
    put("/menu/categories/order") {
        val req = call.receive<MenuCategoryOrder>()
        if (req.orderedIds.isEmpty()) throw BadRequestException("orderedIds must not be empty", "bad_request")
        menuEdit { principal, venues, stamp ->
            result(venues.map { v ->
                v.venueId to if (!editable(v.venueId, principal.tenantId)) "store_not_upgraded" else {
                    val all = MenuState.loadCategories(v.scope).values.filter { !it.deleted }
                    val listed = req.orderedIds.filter { id -> all.any { it.id == id } }
                    if (listed.isEmpty()) "not_found" else {
                        val order = listed + all.sortedBy { it.int("sortOrder") ?: 0 }.map { it.id }.filter { it !in listed }
                        val changed = order.mapIndexedNotNull { i, id ->
                            val r = all.first { it.id == id }
                            if (r.int("sortOrder") == i) null else r.also { it.write("sortOrder", JsonPrimitive(i), stamp) }
                        }
                        MenuState.saveCategories(v.scope, changed)
                        changed.forEach { MenuState.appendFeed(v.scope, MenuFields.CATEGORY, it.id,
                            kotlinx.serialization.json.JsonObject(it.wire(MenuFields.CATEGORY)), "portal") }
                        null
                    }
                }
            })
        }
    }
}

private fun editCategory(scope: Scope, id: String, change: (Regs) -> String?): String? {
    if (!editable(scope.venueId, scope.tenantId)) return "store_not_upgraded"
    val r = MenuState.loadCategories(scope, listOf(id))[id]
    if (r == null || r.deleted) return "not_found"
    change(r)?.let { return it }
    r.canonicalize()
    MenuState.saveCategories(scope, listOf(r))
    MenuState.appendFeed(scope, MenuFields.CATEGORY, id, kotlinx.serialization.json.JsonObject(r.wire(MenuFields.CATEGORY)), "portal")
    return null
}
