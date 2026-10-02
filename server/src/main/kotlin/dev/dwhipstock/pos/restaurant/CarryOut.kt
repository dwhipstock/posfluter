package dev.dwhipstock.pos.restaurant

import dev.dwhipstock.pos.base.CleanText
import dev.dwhipstock.pos.base.ConflictException
import dev.dwhipstock.pos.orders.CounterOrderView
import dev.dwhipstock.pos.orders.CounterOrders
import dev.dwhipstock.pos.orders.OrderSource
import dev.dwhipstock.pos.orders.PickupOrders
import dev.dwhipstock.pos.orders.SaleLocations
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

/** A new carry-out order; a call-in can give the customer's name and phone (both optional). */
@Serializable
data class NewCarryOutRequest(val customerName: String? = null, val customerPhone: String? = null)

/** The customer on a carry-out order: what is given replaces what was there (blank clears it). */
@Serializable
data class CarryOutCustomerRequest(val customerName: String? = null, val customerPhone: String? = null)

@Serializable
data class CarryOutSettings(
    /** Show "Carry-out" in the floor's header even when no Carry-out spot is on the floor plan. */
    val headerButton: Boolean = false,
)

@Serializable
data class CarryOutSettingsUpdate(val headerButton: Boolean? = null)

/** What the floor needs for its Carry-out spots and header button, in one poll. */
@Serializable
data class CarryOutSummary(
    /** Carry-out orders not picked up yet (paid or not). */
    val openCount: Int,
    /** Carry-out spots placed on the floor plan (any room). */
    val spots: Int,
    val headerButton: Boolean,
) {
    /** The floor shows a way in: a spot on the plan, or the header button the store turned on. */
    val reachable: Boolean get() = spots > 0 || headerButton
}

/**
 * Carry-out (to-go) at a table-service restaurant, built on the store's
 * numbered orders ([PickupOrders], the quick-serve counter's): a new order
 * gets its number at once (the caller is told "#105"), is a normal check on
 * the off-floor carry-out location (same menu, split, discounts, bill,
 * kitchen send), goes to the kitchen when it is sent, and is paid now or at
 * pickup. TO GO and the number print on the kitchen tickets; the bill and
 * receipt say "Order #105 · Carry-out"; the pickup board shows it once it is
 * at the kitchen. Picked up only once paid.
 */
class CarryOutService(
    private val checks: CheckService,
    val orders: PickupOrders,
) {
    private val log = LoggerFactory.getLogger(CarryOutService::class.java)

    companion object {
        private const val HEADER_KEY = "carry_out_header"
        const val SPOT_TYPE = "CARRY_OUT"
    }

    /** The off-floor location every carry-out check sits on (startup; idempotent). */
    fun ensureLocation() = SaleLocations.ensureCarryOut()

    private fun name(raw: String?): String? = CleanText.field(raw, PickupOrders.NAME_MAX)

    private fun phone(raw: String?): String? {
        val p = CleanText.field(raw, PickupOrders.PHONE_MAX) ?: return null
        require(p.all { it.isDigit() || it in " +-().#" }) { "phone may hold digits, spaces and + - ( ) . # only" }
        return p
    }

    /** A new carry-out order (an empty check, numbered now). */
    fun create(userId: String, req: NewCarryOutRequest = NewCarryOutRequest()): CounterOrderView {
        val customer = name(req.customerName)
        val tel = phone(req.customerPhone)
        val checkId = transaction {
            ensureLocation()
            val id = checks.openCounterCheck(SaleLocations.CARRY_OUT_TABLE, userId)
            orders.open(id, OrderSource.CARRY_OUT, "TAKE_OUT", status = PickupOrders.OPEN, numberNow = true,
                customerName = customer, customerPhone = tel)
            id
        }
        val v = orders.view(checkId)
        log.info("Carry-out order #${v.orderNumber} opened (check $checkId)")
        return v
    }

    private fun requireCarryOut(checkId: Int) = transaction {
        val row = orders.requireOrder(checkId)
        if (row[CounterOrders.orderSource] != OrderSource.CARRY_OUT.wire)
            throw ConflictException("check $checkId is not a carry-out order", "not_carry_out")
        row
    }

    fun view(checkId: Int): CounterOrderView {
        requireCarryOut(checkId)
        return orders.view(checkId)
    }

    /** The customer's name / phone on an order (until it is picked up). */
    fun setCustomer(checkId: Int, req: CarryOutCustomerRequest): CounterOrderView {
        val customer = name(req.customerName)
        val tel = phone(req.customerPhone)
        transaction {
            val row = requireCarryOut(checkId)
            if (row[CounterOrders.status] in listOf("PICKED_UP", PickupOrders.CANCELLED))
                throw ConflictException("order is closed", "order_closed")
            CounterOrders.update({ CounterOrders.checkId eq checkId }) {
                it[customerName] = customer; it[customerPhone] = tel
            }
        }
        return orders.view(checkId)
    }

    /** Staff move an order along: PREPARING, READY, PICKED_UP (only once paid). */
    fun setStatus(checkId: Int, status: String): CounterOrderView {
        requireCarryOut(checkId)
        return orders.setStatus(checkId, status)
    }

    /**
     * An order left with nothing on it (opened, then not wanted): dropped. One
     * with items is voided like any bill (manager), never here.
     */
    fun discard(checkId: Int) {
        requireCarryOut(checkId)
        val c = checks.getCheck(checkId)
        if (c.lines.isNotEmpty() || c.pendingLines.isNotEmpty())
            throw ConflictException("order has items; void it instead", "order_has_items")
        if (c.status in listOf("OPEN", "TOTAL_LOCKED")) checks.cancelUnpaid(checkId, "discarded")
    }

    /**
     * The carry-out list: every order not picked up yet, oldest first. An
     * unpaid one stays from any day (it is an open bill); a paid one only
     * from today (a paid order nobody marked picked up yesterday is gone).
     */
    fun list(): List<CounterOrderView> = transaction {
        openOrders().orderBy(CounterOrders.createdAt to SortOrder.ASC).limit(200).map { orders.viewOf(it) }
    }

    private fun openOrders() = orders.joined().selectAll().where {
        val today = orders.today().toString()
        (CounterOrders.orderSource eq OrderSource.CARRY_OUT.wire) and
            (CounterOrders.status inList listOf(PickupOrders.OPEN, "PREPARING", "READY")) and
            ((Checks.status inList listOf("OPEN", "TOTAL_LOCKED")) or
                ((Checks.status eq "CLOSED") and (CounterOrders.businessDate eq today)))
    }

    fun settings(): CarryOutSettings = transaction {
        CarryOutSettings(
            headerButton = CounterConfig.selectAll().where { CounterConfig.key eq HEADER_KEY }
                .firstOrNull()?.get(CounterConfig.value) == "on",
        )
    }

    fun updateSettings(next: CarryOutSettingsUpdate): CarryOutSettings = transaction {
        next.headerButton?.let { on ->
            val v = if (on) "on" else "off"
            if (CounterConfig.update({ CounterConfig.key eq HEADER_KEY }) { it[value] = v } == 0)
                CounterConfig.insert { it[key] = HEADER_KEY; it[value] = v }
        }
        settings()
    }

    fun summary(): CarryOutSummary = transaction {
        CarryOutSummary(
            openCount = openOrders().count().toInt(),
            spots = FloorObjects.selectAll().where { FloorObjects.type eq SPOT_TYPE }.count().toInt(),
            headerButton = settings().headerButton,
        )
    }
}
