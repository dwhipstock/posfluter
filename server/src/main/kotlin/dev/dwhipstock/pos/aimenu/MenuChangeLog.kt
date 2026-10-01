package dev.dwhipstock.pos.aimenu

import dev.dwhipstock.pos.api.CatalogOps
import dev.dwhipstock.pos.api.CategoryCreateRequest
import dev.dwhipstock.pos.api.CategoryPatchRequest
import dev.dwhipstock.pos.api.ItemPatchRequest
import dev.dwhipstock.pos.api.VariantPatchRequest
import dev.dwhipstock.pos.base.Categories
import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.Translations
import dev.dwhipstock.pos.base.Users
import dev.dwhipstock.pos.db.utcTimestamp
import dev.dwhipstock.pos.restaurant.Checks
import dev.dwhipstock.pos.restaurant.ConflictException
import dev.dwhipstock.pos.restaurant.DiningTables
import dev.dwhipstock.pos.restaurant.FloorObjects
import dev.dwhipstock.pos.restaurant.NotFoundException
import dev.dwhipstock.pos.sdk.VenueClock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

object MenuChangeSets : Table("menu_change_sets") {
    val id = varchar("id", 40)
    val createdAt = utcTimestamp("created_at")
    val userId = varchar("user_id", 64)
    val approverId = varchar("approver_id", 64)
    val sourceKind = varchar("source", 16) // photos | chat | translate | room
    val summary = varchar("summary", 500)
    val revertedAt = utcTimestamp("reverted_at").nullable()
    val revertedBy = varchar("reverted_by", 64).nullable()
    override val primaryKey = PrimaryKey(id)
}

object MenuChangeRows : Table("menu_change_rows") {
    val id = integer("id").autoIncrement()
    val setId = varchar("set_id", 40)
    val seq = integer("seq")
    val entity = varchar("entity", 16) // item | variant | category | category_order
    val entityId = varchar("entity_id", 160)
    val action = varchar("action", 16) // create | update | delete | reorder
    val title = varchar("title", 200)
    val beforeJson = text("before_json").nullable()
    val afterJson = text("after_json").nullable()
    override val primaryKey = PrimaryKey(id)
}

@Serializable
data class MenuChangeSetDto(
    val id: String,
    val createdAt: String,
    val source: String,
    val summary: String,
    val appliedBy: String,
    val changeCount: Int,
    val titles: List<String>,
    val reverted: Boolean,
    val revertedAt: String? = null,
)

/** A revert would overwrite later edits of these things; the manager must confirm (force). */
class MenuRevertConflictException(val titles: List<String>) : RuntimeException(
    "changed since this AI update: ${titles.joinToString(", ")}")

/**
 * The AI menu history: what each Apply changed (before / after per row) and
 * how to put it back. Everything goes through [CatalogOps], so a revert is an
 * ordinary menu edit: same validation, same outbox events, same portal sync.
 * Removals are soft deletes (items.deleted_at), so reverting one un-deletes
 * the item; old sales and receipts never lose their rows. Call inside a
 * transaction.
 */
internal object MenuChangeLog {
    private val json = Json
    const val ROOM = "room"

    class Row(val entity: String, val entityId: String, val action: String, val title: String, val before: JsonObject?)

    // --- states: the fields an AI change can touch, compared to detect later edits ---

    fun itemState(itemId: String): JsonObject? {
        val row = Items.selectAll().where { Items.id eq itemId }.firstOrNull() ?: return null
        val prices = ItemVariants.selectAll()
            .where { (ItemVariants.itemId eq itemId) and ItemVariants.deletedAt.isNull() }
            .associate { it[ItemVariants.id] to it[ItemVariants.priceCents] }
        return buildJsonObject {
            put("nameEn", row[Items.nameEn]); put("nameFr", row[Items.nameFr])
            put("descriptionEn", row[Items.descriptionEn]); put("descriptionFr", row[Items.descriptionFr])
            put("categoryId", row[Items.categoryId]); put("active", row[Items.active])
            put("deleted", row[Items.deletedAt] != null)
            putJsonObject("prices") { prices.forEach { (k, v) -> put(k, v) } }
        }
    }

    fun variantState(variantId: String): JsonObject? {
        val row = ItemVariants.selectAll().where { ItemVariants.id eq variantId }.firstOrNull() ?: return null
        return buildJsonObject {
            put("itemId", row[ItemVariants.itemId]); put("priceCents", row[ItemVariants.priceCents])
            put("deleted", row[ItemVariants.deletedAt] != null)
        }
    }

    fun categoryState(categoryId: String): JsonObject? {
        val row = Categories.selectAll().where { Categories.id eq categoryId }.firstOrNull() ?: return null
        return buildJsonObject { put("nameEn", row[Categories.nameEn]); put("nameFr", row[Categories.nameFr]) }
    }

    fun orderState(): JsonObject = buildJsonObject {
        putJsonArray("order") {
            Categories.selectAll().orderBy(Categories.sortOrder).forEach { add(JsonPrimitive(it[Categories.id])) }
        }
    }

    /** A translations-table name: key "entity:id:lang" ([translationKey]). */
    fun translationState(key: String): JsonObject {
        val (entity, id, lang) = splitTranslationKey(key)
        return buildJsonObject { put("text", Translations.get(entity, id, lang)) }
    }

    fun translationKey(entity: String, id: String, lang: String) = "$entity:$id:$lang"

    private fun splitTranslationKey(key: String): Triple<String, String, String> =
        Triple(key.substringBefore(':'), key.substringAfter(':').substringBeforeLast(':'), key.substringAfterLast(':'))

    /** A floor-plan table ("set up from picture"): its geometry, label and whether it is removed. */
    fun tableState(tableId: String): JsonObject? {
        val row = DiningTables.selectAll().where { DiningTables.id eq tableId }.firstOrNull() ?: return null
        return buildJsonObject {
            put("label", row[DiningTables.label]); put("x", row[DiningTables.x]); put("y", row[DiningTables.y])
            put("width", row[DiningTables.width]); put("height", row[DiningTables.height])
            put("rotation", row[DiningTables.rotation]); put("shape", row[DiningTables.shape])
            put("seats", row[DiningTables.seats]); put("deleted", row[DiningTables.deletedAt] != null)
        }
    }

    /** A floor object, every column (hard deleted, so a revert re-creates it from this). */
    fun objectState(objectId: String): JsonObject? {
        val row = FloorObjects.selectAll().where { FloorObjects.id eq objectId }.firstOrNull() ?: return null
        return buildJsonObject {
            put("zoneId", row[FloorObjects.zoneId]); put("type", row[FloorObjects.type])
            put("x", row[FloorObjects.x]); put("y", row[FloorObjects.y])
            put("width", row[FloorObjects.width]); put("height", row[FloorObjects.height])
            put("rotation", row[FloorObjects.rotation])
            put("labelFr", row[FloorObjects.labelFr]); put("labelEn", row[FloorObjects.labelEn])
            put("icon", row[FloorObjects.icon]); put("shape", row[FloorObjects.shape])
        }
    }

    fun state(entity: String, id: String): JsonObject? = when (entity) {
        "table" -> tableState(id)
        "floor_object" -> objectState(id)
        "translation" -> translationState(id)
        "item" -> itemState(id)
        "variant" -> variantState(id)
        "category" -> categoryState(id)
        UNDO_OF -> null
        else -> orderState()
    }

    // --- record ---

    /** Saves one Apply. The after states are read now, once every change of the set is in. */
    fun record(setId: String, userId: String, approverId: String, source: String, summary: String, rows: List<Row>) {
        MenuChangeSets.insert {
            it[id] = setId
            it[createdAt] = VenueClock.now()
            it[MenuChangeSets.userId] = userId
            it[MenuChangeSets.approverId] = approverId
            it[sourceKind] = source
            it[MenuChangeSets.summary] = summary.take(500)
        }
        rows.forEachIndexed { i, r ->
            MenuChangeRows.insert {
                it[MenuChangeRows.setId] = setId
                it[seq] = i
                it[entity] = r.entity
                it[entityId] = r.entityId
                it[action] = r.action
                it[title] = r.title.take(200)
                it[beforeJson] = r.before?.toString()
                it[afterJson] = state(r.entity, r.entityId)?.toString()
            }
        }
    }

    // --- history ---

    /** [rooms]: the floor-plan sets ("room") only; else the menu ones only. */
    fun history(limit: Int = 20, rooms: Boolean = false): List<MenuChangeSetDto> {
        val sets = MenuChangeSets.selectAll()
            .where { if (rooms) MenuChangeSets.sourceKind eq ROOM else MenuChangeSets.sourceKind neq ROOM }
            .orderBy(MenuChangeSets.createdAt, SortOrder.DESC).limit(limit).toList()
        val names = Users.selectAll().associate { it[Users.id] to it[Users.name] }
        return sets.map { s ->
            val rows = MenuChangeRows.selectAll().where { MenuChangeRows.setId eq s[MenuChangeSets.id] }.toList()
            MenuChangeSetDto(
                id = s[MenuChangeSets.id],
                createdAt = s[MenuChangeSets.createdAt].toString(),
                source = s[MenuChangeSets.sourceKind],
                summary = s[MenuChangeSets.summary],
                appliedBy = names[s[MenuChangeSets.userId]] ?: s[MenuChangeSets.userId],
                changeCount = rows.count { it[MenuChangeRows.entity] != UNDO_OF },
                titles = rows.map { it[MenuChangeRows.title] }.filter { it.isNotBlank() }.distinct().take(8),
                reverted = s[MenuChangeSets.revertedAt] != null,
                revertedAt = s[MenuChangeSets.revertedAt]?.toString(),
            )
        }
    }

    // --- revert ---

    /**
     * Put back the before state of every row of [setId], newest row first.
     * Unless [force], refuses with [MenuRevertConflictException] when anything
     * it would touch changed after the set was applied (a later AI update or a
     * hand edit).
     */
    fun revert(setId: String, userId: String, force: Boolean): Int {
        val set = MenuChangeSets.selectAll().where { MenuChangeSets.id eq setId }.firstOrNull()
            ?: throw NotFoundException("AI menu change $setId not found")
        if (set[MenuChangeSets.revertedAt] != null) throw ConflictException("already reverted", "menu_ai_already_reverted")
        val rows = MenuChangeRows.selectAll().where { MenuChangeRows.setId eq setId }
            .orderBy(MenuChangeRows.seq, SortOrder.DESC).toList()

        // A table this set touched now has a live order on it: reverting could move, reshape
        // or delete it out from under the guests. Refused even with force — unlike the "changed
        // since" conflict below, there is no safe way to override this one.
        val tableIds = rows.filter { it[MenuChangeRows.entity] == "table" }.map { it[MenuChangeRows.entityId] }.distinct()
        if (tableIds.isNotEmpty()) {
            val open = Checks.selectAll()
                .where { (Checks.tableId inList tableIds) and (Checks.status inList listOf("OPEN", "TOTAL_LOCKED")) }
                .map { it[Checks.tableId] }.toSet()
            if (open.isNotEmpty()) {
                val titles = rows.filter { it[MenuChangeRows.entityId] in open }
                    .map { it[MenuChangeRows.title].ifBlank { it[MenuChangeRows.entityId] } }.distinct()
                throw ConflictException(
                    "${titles.joinToString(", ")} now has an open check: not reverted", "menu_ai_revert_open_check")
            }
        }

        // A category this set created that now holds items the set did not create (added by hand,
        // or from the portal): undoing would have to delete a category that is not empty. Asked
        // first like any later edit; forced, the category stays (with those items) and the rest reverts.
        val createdItems = rows.filter { it[MenuChangeRows.entity] == "item" && it[MenuChangeRows.action] == "create" }
            .map { it[MenuChangeRows.entityId] }.toSet()
        val keptCategories = rows.filter { it[MenuChangeRows.entity] == "category" && it[MenuChangeRows.action] == "create" }
            .filter { r -> categoryHasOtherItems(r[MenuChangeRows.entityId], createdItems) }
            .map { it[MenuChangeRows.entityId] }.toSet()

        if (!force) {
            val changed = rows.filter { r ->
                val after = r[MenuChangeRows.afterJson]?.let { json.parseToJsonElement(it) }
                r[MenuChangeRows.entityId] in keptCategories && r[MenuChangeRows.entity] == "category" ||
                    state(r[MenuChangeRows.entity], r[MenuChangeRows.entityId]) != after
            }.map { it[MenuChangeRows.title].ifBlank { it[MenuChangeRows.entityId] } }.distinct()
            if (changed.isNotEmpty()) throw MenuRevertConflictException(changed)
        }

        // the undo is itself a change set (rows that put back what this revert changes), so an
        // undo can be undone: newest-first here, so reverting the undo replays the original order
        val undoRows = mutableListOf<Row>()
        fun undone(r: org.jetbrains.exposed.sql.ResultRow, beforeRevert: JsonObject?) {
            val inverse = when (r[MenuChangeRows.action]) { "create" -> "delete"; "delete" -> "create"; else -> r[MenuChangeRows.action] }
            undoRows += Row(r[MenuChangeRows.entity], r[MenuChangeRows.entityId], inverse, r[MenuChangeRows.title], beforeRevert)
        }

        for (r in rows) {
            val id = r[MenuChangeRows.entityId]
            val before = r[MenuChangeRows.beforeJson]?.let { json.parseToJsonElement(it).jsonObject }
            val entity = r[MenuChangeRows.entity]
            if (entity == UNDO_OF) continue
            if (entity == "category" && r[MenuChangeRows.action] == "create" && id in keptCategories) continue
            undone(r, state(entity, id)?.let { s ->
                // a category re-created by an undo of this undo goes back where it was in the list
                if (entity == "category") categoryStateWithOrder(id) ?: s else s
            })
            when (entity to r[MenuChangeRows.action]) {
                "category" to "create" -> if (categoryState(id) != null) CatalogOps.deleteCategory(id)
                // only an undo's row: the category it deleted comes back with the same id (items point at it)
                "category" to "delete" -> if (categoryState(id) == null && before != null) CatalogOps.createCategory(
                    CategoryCreateRequest(nameFr = before.s("nameFr"), nameEn = before.s("nameEn"),
                        sortOrder = (before["sortOrder"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()), fixedId = id)
                "item" to "create" -> if (itemState(id)?.get("deleted")?.jsonPrimitive?.boolean == false) CatalogOps.deleteItem(id)
                "item" to "delete" -> restoreIfDeleted(id, before!!)
                "item" to "update" -> {
                    restoreIfDeleted(id, before!!)
                    CatalogOps.patchItem(id, ItemPatchRequest(
                        nameFr = before.s("nameFr"), nameEn = before.s("nameEn"),
                        descriptionFr = before.s("descriptionFr"), descriptionEn = before.s("descriptionEn"),
                        categoryId = before.s("categoryId"), active = before["active"]!!.jsonPrimitive.boolean))
                }
                // a size deleted since (e.g. from the manager portal) stays deleted
                "variant" to "update" -> if (variantLive(id)) CatalogOps.patchVariant(before!!.s("itemId"), id,
                    VariantPatchRequest(priceCents = before["priceCents"]!!.jsonPrimitive.long))
                "category" to "update" -> CatalogOps.patchCategory(id,
                    CategoryPatchRequest(nameFr = before!!.s("nameFr"), nameEn = before.s("nameEn")))
                "translation" to "update" -> {
                    val (entity, eid, lang) = splitTranslationKey(id)
                    Translations.set(entity, eid, lang, (before!!["text"] as? JsonPrimitive)?.contentOrNull)
                }
                "table" to "create" -> RoomLayoutAi.removeTable(id)
                "table" to "delete" -> RoomLayoutAi.restoreTable(id)
                "table" to "update" -> FloorEditAi.restoreTable(id, before!!)
                "floor_object" to "update" -> FloorEditAi.restoreObject(id, before!!)
                "floor_object" to "create" -> RoomLayoutAi.removeObject(id)
                "floor_object" to "delete" -> RoomLayoutAi.restoreObject(id, before!!)
                "category_order" to "reorder" -> {
                    val old = (before!!["order"] as JsonArray).map { it.jsonPrimitive.content }
                    val now = orderState()["order"]!!.let { it as JsonArray }.map { it.jsonPrimitive.content }
                    CatalogOps.reorderCategories(old.filter { it in now } + now.filter { it !in old })
                }
            }
        }
        MenuChangeSets.update({ MenuChangeSets.id eq setId }) {
            it[revertedAt] = VenueClock.now()
            it[revertedBy] = userId
        }
        // undoing an undo: the set it undid is applied again, so it can be undone again
        rows.firstOrNull { it[MenuChangeRows.entity] == UNDO_OF }?.let { link ->
            MenuChangeSets.update({ MenuChangeSets.id eq link[MenuChangeRows.entityId] }) {
                it[revertedAt] = null
                it[revertedBy] = null
            }
        }
        if (undoRows.isNotEmpty()) {
            val undoId = java.util.UUID.randomUUID().toString()
            val summary = "Undo: " + set[MenuChangeSets.summary].removePrefix("Undo: ")
            record(undoId, userId, set[MenuChangeSets.approverId], set[MenuChangeSets.sourceKind], summary,
                undoRows + Row(UNDO_OF, setId, "link", "", null))
        }
        return rows.count { it[MenuChangeRows.entity] != UNDO_OF }
    }

    /** The marker row of an undo's change set: entity_id = the set it undid. */
    private const val UNDO_OF = "undo_of"

    private fun categoryHasOtherItems(categoryId: String, createdItems: Set<String>): Boolean =
        Items.selectAll().where { (Items.categoryId eq categoryId) and Items.deletedAt.isNull() }
            .any { it[Items.id] !in createdItems }

    private fun categoryStateWithOrder(categoryId: String): JsonObject? {
        val row = Categories.selectAll().where { Categories.id eq categoryId }.firstOrNull() ?: return null
        return buildJsonObject {
            put("nameEn", row[Categories.nameEn]); put("nameFr", row[Categories.nameFr])
            put("sortOrder", row[Categories.sortOrder])
        }
    }

    private fun variantLive(variantId: String): Boolean =
        ItemVariants.selectAll().where { (ItemVariants.id eq variantId) and ItemVariants.deletedAt.isNull() }.any()

    private fun restoreIfDeleted(itemId: String, before: JsonObject) {
        val deletedNow = itemState(itemId)?.get("deleted")?.jsonPrimitive?.boolean ?: return
        if (deletedNow && before["deleted"]?.jsonPrimitive?.boolean == false)
            CatalogOps.restoreItem(itemId, before["active"]!!.jsonPrimitive.boolean)
    }

    private fun JsonObject.s(key: String): String = (this[key] as JsonElement).jsonPrimitive.content
}
