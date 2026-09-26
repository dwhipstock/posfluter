package dev.dwhipstock.pos.restaurant

import dev.dwhipstock.pos.db.utcTimestamp
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

/**
 * Kitchen / station tickets (migration 045, used only with
 * kitchen.printing=on). Local to the store; never synced.
 */

object KitchenStations : Table("kitchen_stations") {
    val id = varchar("id", 64)
    val nameFr = varchar("name_fr", 100)
    val nameEn = varchar("name_en", 100)
    /** printer | screen | both */
    val output = varchar("output", 10).default("printer")
    /** Blank = the receipt printer from venue settings. */
    val printerHost = varchar("printer_host", 64).default("")
    val printerPort = integer("printer_port").default(9100)
    /** 58 or 80. */
    val paperMm = integer("paper_mm").default(80)
    val sortOrder = integer("sort_order").default(0)
    override val primaryKey = PrimaryKey(id)
}

/** kind = category | item; station_id '' = no ticket. */
object KitchenRoutes : Table("kitchen_routes") {
    val kind = varchar("kind", 10)
    val refId = varchar("ref_id", 96)
    val stationId = varchar("station_id", 64).default("")
    override val primaryKey = PrimaryKey(kind, refId)
}

object KitchenConfigTable : Table("kitchen_config") {
    val key = varchar("key", 64)
    val value = text("value")
    override val primaryKey = PrimaryKey(key)

    fun get(k: String): String? = selectAll().where { key eq k }.firstOrNull()?.get(value)

    fun set(k: String, v: String) {
        if (update({ key eq k }) { it[value] = v } == 0) insert { it[key] = k; it[value] = v }
    }
}

object KitchenSentLines : Table("kitchen_sent_lines") {
    val lineId = integer("line_id")
    val checkId = integer("check_id")
    val stationId = varchar("station_id", 64)
    val qty = integer("qty")
    val nameFr = varchar("name_fr", 200)
    val nameEn = varchar("name_en", 200)
    val variantFr = varchar("variant_fr", 100).nullable()
    val variantEn = varchar("variant_en", 100).nullable()
    val note = varchar("note", 500).nullable()
    val updatedAt = utcTimestamp("updated_at")
    override val primaryKey = PrimaryKey(lineId)
}

object KitchenTickets : IntIdTable("kitchen_tickets") {
    val ticketId = varchar("ticket_id", 40)
    val checkId = integer("check_id")
    val stationId = varchar("station_id", 64)
    /** ORDER | ADD | VOID | REPRINT */
    val kind = varchar("kind", 10)
    val tableLabel = varchar("table_label", 100)
    val serverName = varchar("server_name", 100)
    val guests = integer("guests").nullable()
    /** 1 = shown on the kitchen screen (the station's output includes it). */
    val onScreen = integer("on_screen").default(0)
    val createdAt = utcTimestamp("created_at")
    val bumpedAt = utcTimestamp("bumped_at").nullable()
    val bumpId = varchar("bump_id", 40).nullable()
}

object KitchenTicketItems : IntIdTable("kitchen_ticket_items") {
    val ticketId = integer("ticket_id")
    val lineId = integer("line_id")
    val qty = integer("qty")
    val nameFr = varchar("name_fr", 200)
    val nameEn = varchar("name_en", 200)
    val variantFr = varchar("variant_fr", 100).nullable()
    val variantEn = varchar("variant_en", 100).nullable()
    val note = varchar("note", 500).nullable()
}

object KitchenPrintJobs : IntIdTable("kitchen_print_jobs", "seq") {
    val jobId = varchar("job_id", 40)
    val ticketId = integer("ticket_id").nullable()
    val stationId = varchar("station_id", 64)
    val linesJson = text("lines_json")
    val paperMm = integer("paper_mm").default(80)
    /** PENDING | DONE | CANCELLED */
    val status = varchar("status", 10).default("PENDING")
    val attempts = integer("attempts").default(0)
    val nextAttemptMs = long("next_attempt_ms").default(0)
    val lastError = varchar("last_error", 300).nullable()
    val createdAt = utcTimestamp("created_at")
    val printedAt = utcTimestamp("printed_at").nullable()
}

object KitchenCheckInfo : Table("kitchen_check_info") {
    val checkId = integer("check_id")
    val guests = integer("guests").nullable()
    override val primaryKey = PrimaryKey(checkId)
}
