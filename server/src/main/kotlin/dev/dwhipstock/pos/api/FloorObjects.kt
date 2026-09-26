package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.base.AuthService
import dev.dwhipstock.pos.restaurant.ConflictException
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
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Floor-object management: inert structural props a manager drops on the plan so
 * it matches the real room — pool tables, the bar front, pillars. Deliberately
 * separate from tableRoutes: objects are never orderable, carry no check, no
 * seats, no status. The API mirrors the table geometry endpoints (add / move /
 * resize / delete + a batch layout save), same inline manager-PIN gate.
 */

val FLOOR_OBJECT_TYPES = setOf("POOL", "BAR_FRONT", "PILLAR")

@Serializable
data class FloorObjectCreateRequest(
    val type: String,
    val x: Int, val y: Int,
    val width: Int = 100, val height: Int = 100,
    val rotation: Int = 0,
    val labelFr: String? = null,
    val labelEn: String? = null,
    /** One caption for both languages (older clients); labelFr / labelEn win. */
    val label: String? = null,
    val managerPin: String? = null,
)

@Serializable
data class LayoutObjectEntry(
    val id: String, val x: Int, val y: Int, val width: Int, val height: Int,
    val rotation: Int,
)

@Serializable
data class SaveObjectsLayoutRequest(
    val objects: List<LayoutObjectEntry>, val managerPin: String? = null,
)

@Serializable
data class FloorObjectDeleteRequest(val managerPin: String? = null)

fun Route.floorObjectRoutes(auth: AuthService) {

    post("/zones/{zoneId}/objects") {
        val zoneId = call.parameters["zoneId"]!!
        val req = call.receive<FloorObjectCreateRequest>()
        requireManagerApproval(auth, req.managerPin)
        validateObjectGeometry(req.x, req.y, req.width, req.height, req.rotation, req.type)
        val dto = transaction {
            requireObjectZone(zoneId)
            val objectId = uniqueObjectId(zoneId, req.type)
            FloorObjects.insert {
                it[id] = objectId
                it[FloorObjects.zoneId] = zoneId
                it[type] = req.type
                it[x] = req.x
                it[y] = req.y
                it[width] = req.width
                it[height] = req.height
                it[rotation] = req.rotation
                val shared = req.label?.trim()?.ifBlank { null }
                it[labelFr] = req.labelFr?.trim()?.ifBlank { null } ?: shared
                it[labelEn] = req.labelEn?.trim()?.ifBlank { null } ?: shared
            }
            Outbox.write("floor_object.added", "floor_object", objectId, buildJsonObject {
                put("objectId", objectId)
                put("zoneId", zoneId)
                put("type", req.type)
            })
            floorObjectDto(objectId)
        }
        call.respond(HttpStatusCode.Created, dto)
    }

    /** "Save layout": batch geometry write for every object in a zone. */
    put("/zones/{zoneId}/objects-layout") {
        val zoneId = call.parameters["zoneId"]!!
        val req = call.receive<SaveObjectsLayoutRequest>()
        requireManagerApproval(auth, req.managerPin)
        val updated = transaction {
            requireObjectZone(zoneId)
            req.objects.map { entry ->
                val row = requireObject(entry.id)
                if (row[FloorObjects.zoneId] != zoneId) throw ConflictException(
                    "object ${entry.id} is not in zone $zoneId", "object_not_in_zone")
                applyObjectGeometry(row, entry)
                entry.id
            }
        }
        call.respond(mapOf("zoneId" to zoneId, "updated" to updated.size.toString()))
    }

    /** Hard delete — nothing references a floor object, so it just goes away. */
    post("/objects/{objectId}/delete") {
        val objectId = call.parameters["objectId"]!!
        val req = call.receive<FloorObjectDeleteRequest>()
        requireManagerApproval(auth, req.managerPin)
        transaction {
            val row = requireObject(objectId)
            FloorObjects.deleteWhere { FloorObjects.id eq objectId }
            Outbox.write("floor_object.removed", "floor_object", objectId, buildJsonObject {
                put("objectId", objectId)
                put("type", row[FloorObjects.type])
            })
        }
        call.respond(mapOf("objectId" to objectId, "deleted" to "true"))
    }
}

// --- helpers (call inside a transaction) ---

/** Write one object's geometry, emit an outbox event only if something moved. */
private fun applyObjectGeometry(row: ResultRow, entry: LayoutObjectEntry) {
    validateObjectGeometry(entry.x, entry.y, entry.width, entry.height,
        entry.rotation, row[FloorObjects.type])
    val changed = entry.x != row[FloorObjects.x] || entry.y != row[FloorObjects.y] ||
        entry.width != row[FloorObjects.width] || entry.height != row[FloorObjects.height] ||
        entry.rotation != row[FloorObjects.rotation]
    if (!changed) return
    FloorObjects.update({ FloorObjects.id eq entry.id }) {
        it[x] = entry.x
        it[y] = entry.y
        it[width] = entry.width
        it[height] = entry.height
        it[rotation] = entry.rotation
    }
    Outbox.write("floor_object.moved", "floor_object", entry.id, buildJsonObject {
        put("objectId", entry.id)
        put("x", entry.x)
        put("y", entry.y)
        put("width", entry.width)
        put("height", entry.height)
        put("rotation", entry.rotation)
    })
}

private fun validateObjectGeometry(x: Int, y: Int, width: Int, height: Int,
                                   rotation: Int, type: String) {
    require(x in 0..1000 && y in 0..1000) { "x/y must be within the 0–1000 canvas" }
    require(width in 20..1000 && height in 20..1000) { "width/height must be 20–1000" }
    require(rotation in 0..359) { "rotation must be 0–359 degrees" }
    require(type in FLOOR_OBJECT_TYPES) { "type must be one of $FLOOR_OBJECT_TYPES" }
}

private fun requireObjectZone(zoneId: String): ResultRow =
    Zones.selectAll().where { Zones.id eq zoneId }.firstOrNull()
        ?: throw NotFoundException("zone $zoneId not found")

private fun requireObject(objectId: String): ResultRow =
    FloorObjects.selectAll().where { FloorObjects.id eq objectId }.firstOrNull()
        ?: throw NotFoundException("floor object $objectId not found")

/** Ids need only be unique; type + a counter reads fine in the outbox stream. */
private fun uniqueObjectId(zoneId: String, type: String): String {
    val base = "$zoneId-${type.lowercase()}"
    fun taken(candidate: String) =
        FloorObjects.selectAll().where { FloorObjects.id eq candidate }.any()
    if (!taken(base)) return base
    var n = 2
    while (taken("$base-$n")) n++
    return "$base-$n"
}

private fun floorObjectDto(objectId: String): FloorObjectDto {
    val row = FloorObjects.selectAll().where { FloorObjects.id eq objectId }.first()
    return FloorObjectDto(
        row[FloorObjects.id], row[FloorObjects.type],
        row[FloorObjects.x], row[FloorObjects.y],
        row[FloorObjects.width], row[FloorObjects.height],
        row[FloorObjects.rotation], row[FloorObjects.labelFr], row[FloorObjects.labelEn],
    )
}
