package dev.dwhipstock.pos.orders

import dev.dwhipstock.pos.restaurant.DiningTables
import dev.dwhipstock.pos.restaurant.Zones
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Where a sale that is not at a table sits (arch review A4). A check still
 * points at a dining table, so a retail register, the quick-serve counter
 * and a restaurant's carry-out orders each get one "table" in a zone of its
 * own. This is the one place that creates them.
 *
 * Zones in [OFF_FLOOR] are never a room on the floor plan: the floor, the
 * table slips, the portal's zone names and the table-only operations
 * (open by table, move, merge) leave them out. The counter / register zones
 * are not listed there because their stores have no floor at all.
 */
object SaleLocations {
    /** Every carry-out order's check sits on this "table" (never shown on the floor). */
    const val CARRY_OUT_ZONE = "carry-out"
    const val CARRY_OUT_TABLE = "carry-out-1"

    /** Zones that hold a sale location in a store that also has a floor plan. */
    val OFF_FLOOR: Set<String> = setOf(CARRY_OUT_ZONE)

    fun isOffFloor(zoneId: String?): Boolean = zoneId != null && zoneId in OFF_FLOOR

    /**
     * The zone and its one "table" exist (seeded; re-created if someone removed
     * them). Idempotent; joins the caller's transaction.
     */
    fun ensureRegister(
        zoneId: String, tableId: String, nameFr: String, nameEn: String,
        labelPrefix: String, tableLabel: String = "1",
    ) = transaction {
        Zones.insertIgnore {
            it[id] = zoneId; it[Zones.nameFr] = nameFr; it[Zones.nameEn] = nameEn; it[sortOrder] = 0
            it[Zones.labelPrefix] = labelPrefix
        }
        if (DiningTables.selectAll().where { DiningTables.id eq tableId }.empty()) {
            DiningTables.insert {
                it[id] = tableId; it[DiningTables.zoneId] = zoneId; it[label] = tableLabel
                it[shape] = "SQUARE"; it[seats] = 0
            }
        }
    }

    /**
     * The carry-out location. Its label is what reports and the portal show
     * for these sales ("Carry-out · Carry-out" in the journal, one row in the
     * tables report). Nobody can open a check "at" it by table: see
     * [CheckService.openCheck][dev.dwhipstock.pos.restaurant.CheckService.openCheck].
     */
    fun ensureCarryOut() =
        ensureRegister(CARRY_OUT_ZONE, CARRY_OUT_TABLE, "À emporter", "Carry-out", "CO", tableLabel = "Carry-out")

    /** True when [tableId] is a sale location off the floor (call inside a transaction). */
    fun isOffFloorTable(tableId: String): Boolean =
        DiningTables.selectAll().where { DiningTables.id eq tableId }.firstOrNull()
            ?.let { isOffFloor(it[DiningTables.zoneId]) } ?: false
}
