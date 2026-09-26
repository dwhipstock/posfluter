package dev.dwhipstock.pos.forecourt

import dev.dwhipstock.pos.db.utcTimestamp
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.selectAll

/** The store's record of each fuelling it takes money for (046); see the migration for the life cycle. */
object FuelSales : IntIdTable("fuel_sales") {
    val pump = integer("pump")
    val mode = varchar("mode", 8) // PREPAY | POSTPAY
    val status = varchar("status", 16)
    val checkId = integer("check_id").nullable()
    val lineId = integer("line_id").nullable()
    val prepaidCents = long("prepaid_cents").nullable()
    val fdcTrxId = varchar("fdc_trx_id", 40).nullable()
    val fdcAuthId = varchar("fdc_auth_id", 40).nullable()
    val nozzle = integer("nozzle").nullable()
    val grade = varchar("grade", 8).nullable()
    val gradeName = varchar("grade_name", 40).nullable()
    val volumeMilli = long("volume_milli").nullable()
    val priceMills = long("price_mills").nullable()
    val amountCents = long("amount_cents").nullable()
    val refundCents = long("refund_cents").nullable()
    val refundId = integer("refund_id").nullable()
    val changeGiven = bool("change_given").default(false)
    val fdcCleared = bool("fdc_cleared").default(false)
    val error = varchar("error", 200).nullable()
    val createdAt = utcTimestamp("created_at")
    val completedAt = utcTimestamp("completed_at").nullable()
    val settledAt = utcTimestamp("settled_at").nullable()
}

object FuelStatus {
    const val IN_BASKET = "IN_BASKET"
    const val AUTHORISED = "AUTHORISED"
    const val AUTH_FAILED = "AUTH_FAILED"
    const val SETTLED = "SETTLED"
    const val RELEASED = "RELEASED"
    const val CANCELLED = "CANCELLED"
}

/** A fuel or prepay line's facts, as the basket, receipts and sync show them. */
@kotlinx.serialization.Serializable
data class FuelLineView(
    val fuelSaleId: Int,
    val pump: Int,
    /** PREPAY | POSTPAY */
    val mode: String,
    val status: String,
    val nozzle: Int? = null,
    val grade: String? = null,
    val gradeName: String? = null,
    val volumeMilli: Long? = null,
    val priceMills: Long? = null,
    val prepaidCents: Long? = null,
    val fdcTransactionId: String? = null,
)

/** [ids] → their fuel facts. Inside a transaction. */
fun fuelLineViews(ids: Collection<Int>): Map<Int, FuelLineView> {
    if (ids.isEmpty()) return emptyMap()
    return FuelSales.selectAll().where { FuelSales.id inList ids.toList() }.associate { r ->
        r[FuelSales.id].value to FuelLineView(
            fuelSaleId = r[FuelSales.id].value,
            pump = r[FuelSales.pump],
            mode = r[FuelSales.mode],
            status = r[FuelSales.status],
            nozzle = r[FuelSales.nozzle],
            grade = r[FuelSales.grade],
            gradeName = r[FuelSales.gradeName],
            volumeMilli = r[FuelSales.volumeMilli],
            priceMills = r[FuelSales.priceMills],
            prepaidCents = r[FuelSales.prepaidCents],
            fdcTransactionId = r[FuelSales.fdcTrxId],
        )
    }
}
