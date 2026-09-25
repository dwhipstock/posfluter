package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.base.AuthService
import dev.dwhipstock.pos.restaurant.ConflictException
import dev.dwhipstock.pos.restaurant.DiningTables
import dev.dwhipstock.pos.restaurant.FloorObjects
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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Zone (room) management: add / rename / delete / reorder the venue's rooms in
 * app, so the whole floor structure is self-serviceable and layout never needs a
 * migration. A new zone starts empty — the floor-plan editor fills it with tables
 * and objects.
 *
 * Mirrors the table/object editor's trust model: every mutation carries an inline
 * manager PIN (the edit-mode unlock collects it once, each request re-verifies
 * server-side). Delete is guarded like category delete — refused while the room
 * still holds tables or floor objects (open checks are transitively blocked: a
 * table with an open bill can't be removed, so the room can't be emptied).
 */

@Serializable
data class ZoneCreateRequest(
    val nameFr: String, val nameEn: String, val sortOrder: Int? = null,
    /** Table-label prefix (U/O/B/L); defaults to the EN name's first letter. */
    val labelPrefix: String? = null,
    val managerPin: String? = null,
)

@Serializable
data class ZoneRenameRequest(
    val nameFr: String? = null, val nameEn: String? = null,
    val managerPin: String? = null,
)

@Serializable
data class ZoneDeleteRequest(val managerPin: String? = null)

@Serializable
data class ZoneReorderRequest(val orderedIds: List<String>, val managerPin: String? = null)

fun Route.zoneManagementRoutes(auth: AuthService) {

    /** Create an empty room; the floor-plan editor fills it with tables/objects. */
    post("/zones") {
        val req = call.receive<ZoneCreateRequest>()
        requireManagerApproval(auth, req.managerPin)
        require(req.nameFr.isNotBlank() && req.nameEn.isNotBlank()) { "zone names must not be blank" }
        val dto = transaction {
            val zoneId = uniqueZoneId(req.nameEn)
            val maxSort = Zones.selectAll().maxOfOrNull { it[Zones.sortOrder] } ?: -1
            val prefix = labelPrefixFor(req.labelPrefix, req.nameEn)
            Zones.insert {
                it[id] = zoneId
                it[nameFr] = req.nameFr.trim()
                it[nameEn] = req.nameEn.trim()
                it[sortOrder] = req.sortOrder ?: (maxSort + 1)
                it[status] = "OPEN"
                it[labelPrefix] = prefix
            }
            Outbox.write("zone.created", "zone", zoneId, buildJsonObject {
                put("zoneId", zoneId)
                put("nameFr", req.nameFr.trim())
                put("nameEn", req.nameEn.trim())
                put("labelPrefix", prefix)
            })
            zoneDto(zoneId)
        }
        call.respond(HttpStatusCode.Created, dto)
    }

    /** Rename a room (name only; status has its own route, ids never change). */
    patch("/zones/{zoneId}") {
        val zoneId = call.parameters["zoneId"]!!
        val req = call.receive<ZoneRenameRequest>()
        requireManagerApproval(auth, req.managerPin)
        req.nameFr?.let { require(it.isNotBlank()) { "nameFr must not be blank" } }
        req.nameEn?.let { require(it.isNotBlank()) { "nameEn must not be blank" } }
        val dto = transaction {
            requireZone(zoneId)
            Zones.update({ Zones.id eq zoneId }) { row ->
                req.nameFr?.let { row[nameFr] = it.trim() }
                req.nameEn?.let { row[nameEn] = it.trim() }
            }
            Outbox.write("zone.renamed", "zone", zoneId, buildJsonObject {
                put("zoneId", zoneId)
                req.nameFr?.let { put("nameFr", it.trim()) }
                req.nameEn?.let { put("nameEn", it.trim()) }
            })
            zoneDto(zoneId)
        }
        call.respond(dto)
    }

    /**
     * Remove a room. POST + inline manager PIN like table delete (destructive).
     * Guarded like category delete: refused while the room still holds tables or
     * floor objects — clear them first (localized zone_not_empty).
     */
    post("/zones/{zoneId}/delete") {
        val zoneId = call.parameters["zoneId"]!!
        val req = call.receive<ZoneDeleteRequest>()
        requireManagerApproval(auth, req.managerPin)
        transaction {
            requireZone(zoneId)
            val liveTables = DiningTables.selectAll().where {
                (DiningTables.zoneId eq zoneId) and DiningTables.deletedAt.isNull()
            }.count()
            if (liveTables > 0) throw ConflictException(
                "zone $zoneId still has $liveTables table(s)", "zone_not_empty")
            val objects = FloorObjects.selectAll().where { FloorObjects.zoneId eq zoneId }.count()
            if (objects > 0) throw ConflictException(
                "zone $zoneId still has $objects floor object(s)", "zone_not_empty")
            Zones.deleteWhere { Zones.id eq zoneId }
            Outbox.write("zone.deleted", "zone", zoneId, buildJsonObject { put("zoneId", zoneId) })
        }
        call.respond(mapOf("zoneId" to zoneId, "deleted" to "true"))
    }

    /** Drag-reorder the room switcher: index in the list becomes sort_order. */
    patch("/zones/order") {
        val req = call.receive<ZoneReorderRequest>()
        requireManagerApproval(auth, req.managerPin)
        require(req.orderedIds.isNotEmpty()) { "orderedIds must not be empty" }
        transaction {
            req.orderedIds.forEachIndexed { index, id ->
                requireZone(id)
                Zones.update({ Zones.id eq id }) { it[sortOrder] = index }
            }
            Outbox.write("zones.reordered", "zone", "*", buildJsonObject {
                put("orderedIds", req.orderedIds.joinToString(","))
            })
        }
        call.respond(mapOf("ok" to "true"))
    }
}

// --- helpers (call inside a transaction) ---

private fun requireZone(zoneId: String): ResultRow =
    Zones.selectAll().where { Zones.id eq zoneId }.firstOrNull()
        ?: throw NotFoundException("zone $zoneId not found")

/**
 * Zone label prefix: an explicit request wins (upper-cased, letters only);
 * otherwise the EN name's first letter (Upper→U, Bar front→B). Falls back to
 * "Z" for an EN name with no letters and no explicit prefix.
 */
private fun labelPrefixFor(requested: String?, nameEn: String): String {
    requested?.trim()?.uppercase()?.filter { it.isLetter() }?.take(4)?.ifBlank { null }?.let { return it }
    return nameEn.trim().firstOrNull { it.isLetter() }?.uppercaseChar()?.toString() ?: "Z"
}

/** "Patio Bar" → "patio-bar"; names with no ASCII letters or digits fall back to "zone". */
private fun uniqueZoneId(source: String): String {
    val base = source.trim().lowercase()
        .replace(Regex("[^a-z0-9]+"), "-").trim('-')
        .ifBlank { "zone" }
    fun taken(candidate: String) =
        Zones.selectAll().where { Zones.id eq candidate }.any()
    if (!taken(base)) return base
    var n = 2
    while (taken("$base-$n")) n++
    return "$base-$n"
}

/** A freshly created/renamed room carries no tables or objects yet in its DTO. */
private fun zoneDto(zoneId: String): ZoneDto {
    val row = Zones.selectAll().where { Zones.id eq zoneId }.first()
    return ZoneDto(
        row[Zones.id], row[Zones.nameFr], row[Zones.nameEn], row[Zones.status],
        emptyList(), emptyList(), labelPrefix = row[Zones.labelPrefix],
    )
}
