package dev.dwhipstock.poscloud.db

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.postgresql.util.PGobject

/** jsonb carried as a raw JSON string (the same binding as Schema.kt's). */
private class RoomJsonb : ColumnType<String>() {
    override fun sqlType() = "jsonb"
    override fun valueFromDB(value: Any): String = when (value) {
        is PGobject -> value.value ?: "null"
        else -> value.toString()
    }
    override fun notNullValueToDB(value: String): Any = PGobject().apply {
        type = "jsonb"
        this.value = value
    }
}

private fun Table.roomJsonb(name: String): Column<String> = registerColumn(name, RoomJsonb())

/** The cloud's copy of each store's floor (035): rooms, tables and floor objects as last-write-wins registers. */
object FloorThings : Table("floor_things") {
    val tenantId = text("tenant_id")
    val venueId = text("venue_id")
    val entity = text("entity")
    val id = text("id")
    val zoneId = text("zone_id").nullable()
    val values = roomJsonb("fields")
    val clock = roomJsonb("clock")
    val deleted = bool("deleted")
    val locked = bool("locked")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(tenantId, venueId, entity, id)
}

/** Each applied AI room change and its undo (035). */
object RoomAiApplies : Table("room_ai_applies") {
    val id = text("id")
    val tenantId = text("tenant_id")
    val venueId = text("venue_id")
    val userId = long("user_id")
    val roomId = text("room_id")
    val kind = text("kind")
    val summary = text("summary")
    val changes = integer("changes")
    val undo = roomJsonb("undo")
    val createdAt = timestampWithTimeZone("created_at")
    val revertedAt = timestampWithTimeZone("reverted_at").nullable()
    val revertedBy = long("reverted_by").nullable()
    override val primaryKey = PrimaryKey(id)
}
