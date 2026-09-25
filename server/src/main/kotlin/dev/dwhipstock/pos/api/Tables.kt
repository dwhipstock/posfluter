package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.sdk.VenueClock

import dev.dwhipstock.pos.base.AuthService
import dev.dwhipstock.pos.restaurant.Checks
import dev.dwhipstock.pos.restaurant.ConflictException
import dev.dwhipstock.pos.restaurant.DiningTables
import dev.dwhipstock.pos.restaurant.NotFoundException
import dev.dwhipstock.pos.restaurant.Zones
import dev.dwhipstock.pos.sdk.Outbox
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Floor-plan table management (M7): the in-app table editor. Geometry lives in
 * LOGICAL units on a 0–1000 canvas per zone — the server is authoritative, the
 * client only renders and sends updates.
 *
 * All mutations take an inline manager PIN (askManagerPin pattern, like
 * void/86/zone-status): the edit-mode unlock collects it once and each request
 * re-verifies server-side. Tables soft-delete (closed checks reference their
 * ids forever); every read here filters deleted_at.
 */

val TABLE_SHAPES = setOf("ROUND", "SQUARE", "RECT", "BAR")

@Serializable
data class TableGeometryRequest(
    val x: Int? = null, val y: Int? = null,
    val width: Int? = null, val height: Int? = null,
    val rotation: Int? = null, val shape: String? = null, val seats: Int? = null,
    val managerPin: String? = null,
)

@Serializable
data class LayoutTableEntry(
    val id: String, val x: Int, val y: Int, val width: Int, val height: Int,
    val rotation: Int, val shape: String, val seats: Int,
)

@Serializable
data class SaveLayoutRequest(val tables: List<LayoutTableEntry>, val managerPin: String? = null)

@Serializable
data class TableCreateRequest(
    /** Requested label; coerced to the zone's "{prefix}-{n}" form. Null/blank → next free number. */
    val label: String? = null,
    val x: Int, val y: Int,
    val width: Int = 100, val height: Int = 100,
    val rotation: Int = 0, val shape: String = "SQUARE", val seats: Int = 4,
    /** VIP display name; null = none. */
    val nameOverride: String? = null,
    /** Sub-table anchor: same physical spot, independent bill. */
    val parentTableId: String? = null,
    val managerPin: String? = null,
)

@Serializable
data class TableRenameRequest(
    val label: String? = null,
    /** New VIP name; empty string clears it, null/absent leaves it unchanged. */
    val nameOverride: String? = null,
    val managerPin: String? = null,
)

@Serializable
data class TableDeleteRequest(val managerPin: String? = null)

fun Route.tableRoutes(auth: AuthService) {

    patch("/tables/{tableId}/geometry") {
        val tableId = call.parameters["tableId"]!!
        val req = call.receive<TableGeometryRequest>()
        requireManagerApproval(auth, req.managerPin)
        val dto = transaction {
            val row = requireLiveTable(tableId)
            val entry = LayoutTableEntry(
                id = tableId,
                x = req.x ?: row[DiningTables.x], y = req.y ?: row[DiningTables.y],
                width = req.width ?: row[DiningTables.width],
                height = req.height ?: row[DiningTables.height],
                rotation = req.rotation ?: row[DiningTables.rotation],
                shape = req.shape ?: row[DiningTables.shape],
                seats = req.seats ?: row[DiningTables.seats],
            )
            applyGeometry(row, entry)
            tableManagementDto(tableId)
        }
        call.respond(dto)
    }

    /** "Save layout": batch geometry write for a whole zone after an edit session. */
    put("/zones/{zoneId}/layout") {
        val zoneId = call.parameters["zoneId"]!!
        val req = call.receive<SaveLayoutRequest>()
        requireManagerApproval(auth, req.managerPin)
        val updated = transaction {
            requireZone(zoneId)
            req.tables.map { entry ->
                val row = requireLiveTable(entry.id)
                if (row[DiningTables.zoneId] != zoneId) throw ConflictException(
                    "table ${entry.id} is not in zone $zoneId", "table_not_in_zone")
                applyGeometry(row, entry)
                entry.id
            }
        }
        call.respond(mapOf("zoneId" to zoneId, "updated" to updated.size.toString()))
    }

    post("/zones/{zoneId}/tables") {
        val zoneId = call.parameters["zoneId"]!!
        val req = call.receive<TableCreateRequest>()
        requireManagerApproval(auth, req.managerPin)
        validateGeometry(req.x, req.y, req.width, req.height, req.rotation, req.shape, req.seats)
        val dto = transaction {
            val zone = requireZone(zoneId)
            // Server owns the label: coerce to the zone's "{prefix}-{n}" form so a
            // hand-typed mismatch (a B1 in Lower) can't happen, and it's unique.
            val label = zoneLabel(zoneId, zone[Zones.labelPrefix], req.label, excludeId = null)
            req.parentTableId?.let { requireLiveTable(it) }
            val tableId = uniqueTableId(zoneId, label)
            val maxSort = DiningTables.selectAll()
                .where { DiningTables.zoneId eq zoneId }
                .maxOfOrNull { it[DiningTables.sortOrder] } ?: 0
            DiningTables.insert {
                it[id] = tableId
                it[DiningTables.zoneId] = zoneId
                it[DiningTables.label] = label
                it[parentTableId] = req.parentTableId
                it[nameOverride] = req.nameOverride?.trim()?.ifBlank { null }
                it[sortOrder] = maxSort + 1
                it[x] = req.x
                it[y] = req.y
                it[width] = req.width
                it[height] = req.height
                it[rotation] = req.rotation
                it[shape] = req.shape
                it[seats] = req.seats
            }
            Outbox.write("table.added", "table", tableId, buildJsonObject {
                put("tableId", tableId)
                put("zoneId", zoneId)
                put("label", label)
                put("shape", req.shape)
                put("seats", req.seats)
            })
            tableManagementDto(tableId)
        }
        call.respond(HttpStatusCode.Created, dto)
    }

    /**
     * Remove a table (soft delete — closed checks keep referencing it). POST
     * like /checks/{id}/void: destructive action + inline manager PIN body.
     * Refused while a check is open on it (same pattern as item_in_use) or
     * while live sub-tables anchor to it.
     */
    post("/tables/{tableId}/delete") {
        val tableId = call.parameters["tableId"]!!
        val req = call.receive<TableDeleteRequest>()
        requireManagerApproval(auth, req.managerPin)
        transaction {
            val row = requireLiveTable(tableId)
            val open = Checks.selectAll().where {
                (Checks.tableId eq tableId) and (Checks.status inList listOf("OPEN", "TOTAL_LOCKED"))
            }.firstOrNull()
            if (open != null) throw ConflictException(
                "table $tableId has open bill #${open[Checks.id].value}", "table_in_use")
            val subTables = DiningTables.selectAll().where {
                (DiningTables.parentTableId eq tableId) and DiningTables.deletedAt.isNull()
            }.count()
            if (subTables > 0) throw ConflictException(
                "table $tableId has $subTables sub-table(s); remove them first", "has_sub_tables")
            DiningTables.update({ DiningTables.id eq tableId }) {
                it[deletedAt] = VenueClock.now()
            }
            Outbox.write("table.removed", "table", tableId, buildJsonObject {
                put("tableId", tableId)
                put("label", row[DiningTables.label])
            })
        }
        call.respond(mapOf("tableId" to tableId, "deleted" to "true"))
    }

    /** Rename: display label and/or the VIP name_override. Ids never change. */
    patch("/tables/{tableId}") {
        val tableId = call.parameters["tableId"]!!
        val req = call.receive<TableRenameRequest>()
        requireManagerApproval(auth, req.managerPin)
        req.label?.let { require(it.isNotBlank()) { "label must not be blank" } }
        val dto = transaction {
            val row = requireLiveTable(tableId)
            val zoneId = row[DiningTables.zoneId]
            // Coerce a rename to the zone prefix too, so an edit can't reintroduce drift.
            val newLabel = req.label?.let {
                zoneLabel(zoneId, requireZone(zoneId)[Zones.labelPrefix], it, excludeId = tableId)
            }
            val changedLabel = newLabel != null && newLabel != row[DiningTables.label]
            val newOverride = req.nameOverride?.trim()?.ifBlank { null }
            val changedOverride =
                req.nameOverride != null && newOverride != row[DiningTables.nameOverride]
            if (changedLabel || changedOverride) {
                DiningTables.update({ DiningTables.id eq tableId }) {
                    if (changedLabel) it[label] = newLabel!!
                    if (changedOverride) it[nameOverride] = newOverride
                }
                Outbox.write("table.renamed", "table", tableId, buildJsonObject {
                    put("tableId", tableId)
                    put("previousLabel", row[DiningTables.label])
                    put("label", newLabel ?: row[DiningTables.label])
                    if (changedOverride) put("nameOverride", newOverride ?: "")
                })
            }
            tableManagementDto(tableId)
        }
        call.respond(dto)
    }
}

// --- helpers (call inside a transaction) ---

/**
 * Write one table's geometry and emit the matching outbox events. Split by
 * concern so the sync stream stays readable: moved (x/y), resized (w/h),
 * reshaped (shape/rotation/seats). Unchanged fields emit nothing.
 */
private fun applyGeometry(row: ResultRow, entry: LayoutTableEntry) {
    validateGeometry(entry.x, entry.y, entry.width, entry.height,
        entry.rotation, entry.shape, entry.seats)
    val moved = entry.x != row[DiningTables.x] || entry.y != row[DiningTables.y]
    val resized = entry.width != row[DiningTables.width] || entry.height != row[DiningTables.height]
    val reshaped = entry.shape != row[DiningTables.shape] ||
        entry.rotation != row[DiningTables.rotation] || entry.seats != row[DiningTables.seats]
    if (!moved && !resized && !reshaped) return

    DiningTables.update({ DiningTables.id eq entry.id }) {
        it[x] = entry.x
        it[y] = entry.y
        it[width] = entry.width
        it[height] = entry.height
        it[rotation] = entry.rotation
        it[shape] = entry.shape
        it[seats] = entry.seats
    }
    if (moved) Outbox.write("table.moved", "table", entry.id, buildJsonObject {
        put("tableId", entry.id)
        put("x", entry.x)
        put("y", entry.y)
    })
    if (resized) Outbox.write("table.resized", "table", entry.id, buildJsonObject {
        put("tableId", entry.id)
        put("width", entry.width)
        put("height", entry.height)
    })
    if (reshaped) Outbox.write("table.reshaped", "table", entry.id, buildJsonObject {
        put("tableId", entry.id)
        put("shape", entry.shape)
        put("rotation", entry.rotation)
        put("seats", entry.seats)
    })
}

private fun validateGeometry(x: Int, y: Int, width: Int, height: Int,
                             rotation: Int, shape: String, seats: Int) {
    require(x in 0..1000 && y in 0..1000) { "x/y must be within the 0–1000 canvas" }
    require(width in 20..1000 && height in 20..1000) { "width/height must be 20–1000" }
    require(rotation in 0..359) { "rotation must be 0–359 degrees" }
    require(shape in TABLE_SHAPES) { "shape must be one of $TABLE_SHAPES" }
    require(seats in 0..50) { "seats must be 0–50" }
}

private fun requireZone(zoneId: String): ResultRow =
    Zones.selectAll().where { Zones.id eq zoneId }.firstOrNull()
        ?: throw NotFoundException("zone $zoneId not found")

fun requireLiveTable(tableId: String): ResultRow =
    DiningTables.selectAll().where {
        (DiningTables.id eq tableId) and DiningTables.deletedAt.isNull()
    }.firstOrNull() ?: throw NotFoundException("table $tableId not found")

/** Trailing digits of a label ("L-12" → 12, "B1" → 1, "Patio" → null). */
private fun trailingInt(label: String): Int? =
    Regex("(\\d+)$").find(label.trim())?.groupValues?.get(1)?.toIntOrNull()

/**
 * Coerce a requested label to the zone's prefix, returning "{prefix}-{n}". The
 * number the user typed is honoured when it's free in the zone (among live
 * tables, excluding [excludeId]); otherwise — or when nothing numeric was
 * given — the next free number is used. Guarantees a label that is
 * zone-prefixed, sequential and unique-per-zone, so drift (a B1 in Lower) and
 * duplicates are both impossible.
 */
private fun zoneLabel(zoneId: String, prefix: String, requested: String?, excludeId: String?): String {
    val p = prefix.ifBlank { zoneId.take(1).uppercase() }
    val used = DiningTables.selectAll().where {
        (DiningTables.zoneId eq zoneId) and DiningTables.deletedAt.isNull()
    }.filter { it[DiningTables.id] != excludeId }
        .mapNotNull { trailingInt(it[DiningTables.label]) }
        .toSet()
    val wanted = requested?.let { trailingInt(it) }
    val n = if (wanted != null && wanted > 0 && wanted !in used) wanted
    else generateSequence(1) { it + 1 }.first { it !in used }
    return "$p-$n"
}

/**
 * Table ids are permanent (QR slips, check FKs), so they must stay unique
 * across live AND deleted rows. "upper" + "U-11" → "upper-u-11".
 */
private fun uniqueTableId(zoneId: String, label: String): String {
    val base = "$zoneId-$label".lowercase()
        .replace(Regex("[^a-z0-9]+"), "-").trim('-')
        .ifBlank { "table" }
    fun taken(candidate: String) =
        DiningTables.selectAll().where { DiningTables.id eq candidate }.any()
    if (!taken(base)) return base
    var n = 2
    while (taken("$base-$n")) n++
    return "$base-$n"
}

/** Management-view DTO: identity + geometry, no occupancy (that's /zones' job). */
private fun tableManagementDto(tableId: String): TableDto {
    val row = DiningTables.selectAll().where { DiningTables.id eq tableId }.first()
    return TableDto(
        row[DiningTables.id], row[DiningTables.label], row[DiningTables.parentTableId],
        row[DiningTables.nameOverride], openCheckId = null, openCheckStatus = null,
        x = row[DiningTables.x], y = row[DiningTables.y],
        width = row[DiningTables.width], height = row[DiningTables.height],
        rotation = row[DiningTables.rotation], shape = row[DiningTables.shape],
        seats = row[DiningTables.seats],
    )
}
