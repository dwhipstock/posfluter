package dev.dwhipstock.pos.orders

import dev.dwhipstock.pos.base.ConflictException
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.NotFoundException
import dev.dwhipstock.pos.db.utcTimestamp
import dev.dwhipstock.pos.restaurant.CheckService
import dev.dwhipstock.pos.restaurant.Checks
import dev.dwhipstock.pos.restaurant.CounterHook
import dev.dwhipstock.pos.restaurant.KitchenService
import dev.dwhipstock.pos.sdk.CustomerConfig
import dev.dwhipstock.pos.sdk.KitchenLanguage
import dev.dwhipstock.pos.sdk.ReceiptOrder
import dev.dwhipstock.pos.sdk.VenueClock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * One numbered order (055, 056, 063): its check, dine in / take out, source,
 * status and (carry-out) the customer. [orderNumber] (101...) is the
 * customer's number. [kioskNumber] (K12) is only on orders from before 056.
 * Local to the store (never synced; the check itself is).
 */
object CounterOrders : Table("counter_orders") {
    val checkId = integer("check_id")
    val businessDate = varchar("business_date", 10)
    val orderNumber = integer("order_number").nullable()
    val kioskNumber = integer("kiosk_number").nullable()
    /** DINE_IN | TAKE_OUT */
    val serviceMode = varchar("service_mode", 10)
    /** POS | KIOSK | CARRY_OUT ([OrderSource]) */
    val orderSource = varchar("source", 10)
    /**
     * Pay first (POS, KIOSK): DRAFT | WAITING (unpaid) -> PREPARING -> READY -> PICKED_UP (paid);
     * CANCELLED (a numbered kiosk order never paid).
     * Carry-out: OPEN (taken, not at the kitchen yet) -> PREPARING -> READY -> PICKED_UP, paid
     * at any point before it is picked up; CANCELLED.
     */
    val status = varchar("status", 12)
    val createdAt = utcTimestamp("created_at")
    val paidAt = utcTimestamp("paid_at").nullable()
    val readyAt = utcTimestamp("ready_at").nullable()
    val pickedUpAt = utcTimestamp("picked_up_at").nullable()
    /** A call-in order's name and phone (063); optional, carry-out only so far. */
    val customerName = varchar("customer_name", 60).nullable()
    val customerPhone = varchar("customer_phone", 32).nullable()
    override val primaryKey = PrimaryKey(checkId)
}

/**
 * Where an order came from, and so how it moves.
 *
 *  - [payFirst] (the quick-serve counter, a kiosk): nothing reaches the
 *    kitchen or the board until it is paid; an unpaid one expires.
 *  - otherwise (a restaurant's carry-out): numbered when it is taken, sent to
 *    the kitchen like any check (Send, or leaving the bill), paid now or at
 *    pickup; never expires; picked up only once paid.
 */
enum class OrderSource(val wire: String, val payFirst: Boolean) {
    POS("POS", true),
    KIOSK("KIOSK", true),
    CARRY_OUT("CARRY_OUT", false);

    companion object {
        fun of(wire: String): OrderSource? = entries.firstOrNull { it.wire == wire }
        val PAY_FIRST: List<String> = entries.filter { it.payFirst }.map { it.wire }
    }
}

@Serializable
data class CounterOrderView(
    val checkId: Int,
    /** The customer's number: a kiosk / carry-out order's from when it is placed, a counter order's from when it is paid. */
    val orderNumber: Int? = null,
    /** An older kiosk order's waiting number (K12); new kiosk orders have their [orderNumber] instead. */
    val kioskNumber: Int? = null,
    val serviceMode: String,
    val source: String,
    /** DRAFT | WAITING | OPEN | PREPARING | READY | PICKED_UP */
    val status: String,
    /** The check's own status: OPEN (unpaid) | TOTAL_LOCKED | CLOSED (paid) | VOID | CANCELLED. */
    val checkStatus: String,
    val totalCents: Long,
    val outstandingCents: Long,
    val itemCount: Int,
    val hasAlcohol: Boolean,
    val createdAt: String,
    val businessDate: String,
    val customerName: String? = null,
    val customerPhone: String? = null,
)

@Serializable
data class PickupBoard(
    val venue: String,
    val preparing: List<Int>,
    val ready: List<Int>,
    val serverTime: String,
    /** The numbers (of both columns) that are take out; the rest are dine in. */
    val takeOut: List<Int> = emptyList(),
)

/**
 * Numbered orders and the pickup board, for any store that switches them on
 * ([dev.dwhipstock.pos.sdk.Capability]): the quick-serve counter and its
 * kiosks, a restaurant's carry-out. One number sequence per store and
 * business day (101, 102...), whatever the order's source. It is the check's
 * [CounterHook]: told when a check is paid or cancelled, and asked for the
 * receipt's order headline. The kitchen asks it whether to hold an order,
 * what to print instead of a table, and tells it when an order was sent or
 * finished.
 */
class PickupOrders(
    private val config: CustomerConfig,
    private val checks: CheckService,
    /** Test seam: the business day the next number belongs to. */
    val today: () -> LocalDate = { VenueClock.today() },
    val clock: () -> Instant = { VenueClock.now() },
    /**
     * An unpaid pay-first order idle this long is dropped (a guest who never
     * came to pay): counted from its last activity on a POS screen, else from
     * when it was placed (store.properties `orders.unpaidExpireMinutes`).
     */
    val expireAfter: Duration = Duration.ofMinutes(30),
) : CounterHook {
    private val log = LoggerFactory.getLogger(PickupOrders::class.java)

    /**
     * When a staff screen last had each check open (loaded, polled, edited).
     * In memory: the counter's check screen polls every few seconds, so this
     * is fresh while an order is on a screen. Pruned as orders expire.
     */
    private val lastActive = java.util.concurrent.ConcurrentHashMap<Int, Instant>()

    /** After a restart nobody's poll has been seen yet: nothing expires for one [expireAfter]. */
    private val startedAt: Instant = clock()

    /** Kitchen tickets, when on: a pay-first order is sent the moment it is paid. */
    var kitchen: KitchenService? = null

    init {
        checks.counter = this
    }

    companion object {
        const val FIRST_NUMBER = 101
        val MODES = setOf("DINE_IN", "TAKE_OUT")
        /** Where staff can move a paid order. */
        val STATUSES = listOf("PREPARING", "READY", "PICKED_UP")
        val UNPAID = listOf("DRAFT", "WAITING")
        /** A carry-out order taken but not at the kitchen yet. */
        const val OPEN = "OPEN"
        /** A numbered order that was never paid (expired, cleared): its number is not given again. */
        const val CANCELLED = "CANCELLED"
        internal val LIVE = listOf("OPEN", "TOTAL_LOCKED")
        const val NAME_MAX = 60
        const val PHONE_MAX = 32
    }

    fun normMode(mode: String): String {
        val m = mode.trim().uppercase().replace('-', '_')
        require(m in MODES) { "serviceMode must be DINE_IN or TAKE_OUT" }
        return m
    }

    // ------------------------------------------------------------ new orders

    /**
     * A new order row for [checkId]. [numberNow] gives it its number at once
     * (a kiosk ticket, a carry-out order the caller is told), else it is
     * numbered when paid. Inside the caller's transaction. Returns the number.
     */
    fun open(
        checkId: Int, source: OrderSource, mode: String, status: String, numberNow: Boolean,
        customerName: String? = null, customerPhone: String? = null,
    ): Int? = transaction {
        val date = today().toString()
        val number = if (numberNow) nextNumber(date) else null
        CounterOrders.insert {
            it[CounterOrders.checkId] = checkId
            it[businessDate] = date
            it[orderNumber] = number
            it[kioskNumber] = null
            it[serviceMode] = normMode(mode)
            it[orderSource] = source.wire
            it[CounterOrders.status] = status
            it[createdAt] = clock()
            it[CounterOrders.customerName] = customerName
            it[CounterOrders.customerPhone] = customerPhone
        }
        number
    }

    /** The next number for [date]: 101 on a new day, then one more each order. Inside a transaction. */
    private fun nextNumber(date: String): Int =
        (CounterOrders.selectAll().where { CounterOrders.businessDate eq date }
            .mapNotNull { it[CounterOrders.orderNumber] }.maxOrNull() ?: (FIRST_NUMBER - 1)) + 1

    private fun sourceOf(row: ResultRow): OrderSource = OrderSource.of(row[CounterOrders.orderSource]) ?: OrderSource.POS

    private fun row(checkId: Int): ResultRow? =
        CounterOrders.selectAll().where { CounterOrders.checkId eq checkId }.firstOrNull()

    fun requireOrder(checkId: Int): ResultRow =
        row(checkId) ?: throw NotFoundException("no counter order for check $checkId", "order_not_found")

    /** The order's source, or null when [checkId] is not a numbered order. */
    fun sourceOf(checkId: Int): OrderSource? = transaction { row(checkId)?.let(::sourceOf) }

    // ------------------------------------------------------------ the check's side (CounterHook)

    /**
     * Paid in full, inside the closing transaction. A pay-first order is
     * committed (PREPARING, and a counter order's number); a carry-out order
     * just notes when it was paid.
     */
    override fun paid(checkId: Int) {
        lastActive.remove(checkId)
        val row = row(checkId) ?: return
        if (!sourceOf(row).payFirst) {
            CounterOrders.update({ CounterOrders.checkId eq checkId }) { it[paidAt] = clock() }
            return
        }
        if (row[CounterOrders.status] !in UNPAID) return
        val numbered = row[CounterOrders.orderNumber] != null
        val date = today().toString()
        CounterOrders.update({ CounterOrders.checkId eq checkId }) {
            // a counter order is numbered on the day it is paid; a kiosk order
            // keeps the number on the guest's ticket (and the day it was placed)
            if (!numbered) {
                it[businessDate] = date
                it[orderNumber] = nextNumber(date)
            }
            it[status] = "PREPARING"
            it[paidAt] = clock()
        }
    }

    /**
     * The close committed: the kitchen gets the order now. A carry-out order
     * paid before anything was sent goes too (whatever is not sent yet); one
     * already at the kitchen keeps its place on the board.
     */
    override fun afterPaid(checkId: Int) {
        val v = transaction { row(checkId) } ?: return
        if (!sourceOf(v).payFirst) {
            sendToKitchen(checkId, null)
            transaction {
                CounterOrders.update({ (CounterOrders.checkId eq checkId) and (CounterOrders.status eq OPEN) }) {
                    it[status] = "PREPARING"
                }
            }
            log.info("Carry-out order #${v[CounterOrders.orderNumber]} paid")
            return
        }
        if (v[CounterOrders.status] != "PREPARING") return
        sendToKitchen(checkId, "POS")
        log.info("Order #${v[CounterOrders.orderNumber]} paid (${v[CounterOrders.serviceMode]}), to the kitchen")
    }

    /**
     * Its check was cancelled (last item removed, discarded, expired): an
     * unpaid order is not kept. A kiosk order's number stays taken (the guest
     * may still hold the ticket): its row is kept as CANCELLED, a gap in the
     * day. A carry-out order the kitchen never saw is simply gone; one it did
     * see stays CANCELLED.
     */
    override fun touched(checkId: Int) {
        lastActive[checkId] = clock()
    }

    override fun cancelled(checkId: Int) {
        lastActive.remove(checkId)
        transaction {
            val row = row(checkId) ?: return@transaction
            if (!sourceOf(row).payFirst) {
                if (row[CounterOrders.status] == OPEN) CounterOrders.deleteWhere { CounterOrders.checkId eq checkId }
                else CounterOrders.update({ CounterOrders.checkId eq checkId }) { it[status] = CANCELLED }
                return@transaction
            }
            CounterOrders.deleteWhere {
                (CounterOrders.checkId eq checkId) and (CounterOrders.status inList UNPAID) and CounterOrders.orderNumber.isNull()
            }
            CounterOrders.update({ (CounterOrders.checkId eq checkId) and (CounterOrders.status inList UNPAID) }) {
                it[status] = CANCELLED
            }
        }
    }

    // the receipt says "Dine in" / "Take out" (or "Order #105 · Carry-out") in
    // its own print language (a reprint in French or Spanish too), never two
    // languages at once. A carry-out order has its number from the start, so
    // its bill carries it too.
    override fun receiptOrder(checkId: Int): ReceiptOrder? = transaction {
        val row = row(checkId) ?: return@transaction null
        if (!sourceOf(row).payFirst) {
            val n = row[CounterOrders.orderNumber] ?: return@transaction null
            if (row[CounterOrders.status] == CANCELLED) return@transaction null
            return@transaction ReceiptOrder(
                number = "#$n", takeOut = true, carryOut = true, customer = row[CounterOrders.customerName],
            )
        }
        row.takeIf { it[CounterOrders.orderNumber] != null && it[CounterOrders.status] in STATUSES }?.let {
            ReceiptOrder(
                number = it[CounterOrders.orderNumber]?.let { n -> "#$n" } ?: "—",
                takeOut = it[CounterOrders.serviceMode] == "TAKE_OUT",
            )
        }
    }

    // ------------------------------------------------------------ the kitchen's side

    /** The kitchen never gets a pay-first order before it is paid (a numbered kiosk order neither). */
    fun holdKitchen(checkId: Int): Boolean = transaction {
        row(checkId)?.let { sourceOf(it).payFirst && it[CounterOrders.status] !in STATUSES } ?: false
    }

    /** Something of the order reached the kitchen: a carry-out order is being prepared (on the board). */
    fun kitchenSent(checkId: Int) {
        transaction {
            CounterOrders.update({
                (CounterOrders.checkId eq checkId) and (CounterOrders.status eq OPEN) and
                    (CounterOrders.orderSource eq OrderSource.CARRY_OUT.wire)
            }) { it[status] = "PREPARING" }
        }
    }

    /** The kitchen screen bumped the order's last card: an order being prepared is ready. */
    fun kitchenDone(checkId: Int) {
        val moved = transaction {
            CounterOrders.update({
                (CounterOrders.checkId eq checkId) and (CounterOrders.status eq "PREPARING") and
                    CounterOrders.orderNumber.isNotNull()
            }) { it[status] = "READY"; it[readyAt] = clock() }
        }
        if (moved > 0) log.info("Order for check $checkId is ready (kitchen screen)")
    }

    /**
     * What a kitchen ticket / card says instead of a table: "#101 · Take out",
     * or for carry-out "#105 · TO GO · Sam". Null = not a numbered order.
     */
    fun ticketLabel(checkId: Int, language: KitchenLanguage): String? = transaction {
        val row = row(checkId) ?: return@transaction null
        val number = row[CounterOrders.orderNumber]?.let { "#$it" } ?: row[CounterOrders.kioskNumber]?.let { "K$it" } ?: "—"
        if (!sourceOf(row).payFirst) {
            val toGo = when (language) {
                KitchenLanguage.FR -> "À EMPORTER"
                KitchenLanguage.EN -> "TO GO"
                KitchenLanguage.BOTH -> "À EMPORTER / TO GO"
            }
            return@transaction listOfNotNull(number, toGo, row[CounterOrders.customerName]).joinToString(" · ")
        }
        val takeOut = row[CounterOrders.serviceMode] == "TAKE_OUT"
        val mode = when (language) {
            KitchenLanguage.FR -> if (takeOut) "Pour emporter" else "Sur place"
            KitchenLanguage.EN -> if (takeOut) "Take out" else "Dine in"
            KitchenLanguage.BOTH -> if (takeOut) "Pour emporter / Take out" else "Sur place / Dine in"
        }
        "$number · $mode"
    }

    fun sendToKitchen(checkId: Int, sender: String?) {
        val k = kitchen ?: return
        try { k.send(checkId, sender) } catch (e: Exception) { log.warn("kitchen send for order $checkId failed: ${e.message}") }
    }

    // ------------------------------------------------------------ status

    /**
     * Staff move an order along (or back): PREPARING, READY, PICKED_UP. A
     * pay-first order must be paid first; a carry-out order can be prepared
     * and ready unpaid (pay at pickup) but is picked up only once paid.
     */
    fun setStatus(checkId: Int, status: String): CounterOrderView {
        val s = status.trim().uppercase().replace('-', '_')
        require(s in STATUSES) { "status must be one of $STATUSES" }
        transaction {
            val row = requireOrder(checkId)
            val checkStatus = checks.getCheck(checkId).status
            if (sourceOf(row).payFirst) {
                if (row[CounterOrders.orderNumber] == null || row[CounterOrders.status] !in STATUSES || checkStatus != "CLOSED")
                    throw ConflictException("order is not paid", "order_not_paid")
            } else {
                if (row[CounterOrders.status] == CANCELLED || checkStatus !in LIVE + "CLOSED")
                    throw ConflictException("order is cancelled", "order_cancelled")
                if (s == "PICKED_UP" && checkStatus != "CLOSED")
                    throw ConflictException("order is not paid", "order_not_paid")
            }
            CounterOrders.update({ CounterOrders.checkId eq checkId }) {
                it[CounterOrders.status] = s
                when (s) {
                    "READY" -> { it[readyAt] = clock(); it[pickedUpAt] = null }
                    "PICKED_UP" -> it[pickedUpAt] = clock()
                    else -> { it[readyAt] = null; it[pickedUpAt] = null }
                }
            }
        }
        return view(checkId)
    }

    // ------------------------------------------------------------ views

    fun view(checkId: Int): CounterOrderView = transaction { viewOf(requireOrder(checkId)) }

    fun viewOf(row: ResultRow): CounterOrderView {
        val id = row[CounterOrders.checkId]
        val c = checks.getCheck(id)
        val alcohol = transaction {
            val ids = c.lines.mapNotNull { it.itemId }.toSet()
            ids.isNotEmpty() && Items.selectAll().where { (Items.id inList ids) and (Items.isAlcohol eq true) }.count() > 0
        }
        return CounterOrderView(
            checkId = id,
            orderNumber = row[CounterOrders.orderNumber],
            kioskNumber = row[CounterOrders.kioskNumber],
            serviceMode = row[CounterOrders.serviceMode],
            source = row[CounterOrders.orderSource],
            status = row[CounterOrders.status],
            checkStatus = c.status,
            totalCents = c.grandTotalCents,
            outstandingCents = c.outstandingCents,
            itemCount = c.lines.sumOf { it.qty },
            hasAlcohol = alcohol,
            createdAt = VenueClock.iso(row[CounterOrders.createdAt]),
            businessDate = row[CounterOrders.businessDate],
            customerName = row[CounterOrders.customerName],
            customerPhone = row[CounterOrders.customerPhone],
        )
    }

    fun joined() = CounterOrders.join(Checks, JoinType.INNER, CounterOrders.checkId, Checks.id)

    /**
     * Unpaid pay-first orders idle longer than [expireAfter], nothing tendered:
     * dropped (their checks cancelled). Idle = placed that long ago and not
     * open on any staff screen since ([touched]): an order a cashier has on
     * the counter is never pulled from under them. Carry-out never expires.
     */
    fun expire(): Int {
        val cutoff = clock().minus(expireAfter)
        lastActive.entries.removeIf { it.value < cutoff }
        if (startedAt > cutoff) return 0
        val stale = transaction {
            joined().selectAll().where {
                (CounterOrders.status inList UNPAID) and (Checks.status inList LIVE) and
                    (CounterOrders.orderSource inList OrderSource.PAY_FIRST) and
                    (CounterOrders.createdAt less cutoff)
            }.map { it[CounterOrders.checkId] }
        }
        var n = 0
        for (id in stale) {
            if (lastActive[id]?.let { it >= cutoff } == true) continue // open on a screen
            if (checks.hasTenders(id)) continue
            try { checks.cancelUnpaid(id, "expired"); n++ } catch (e: Exception) { log.warn("expiring order $id failed: ${e.message}") }
        }
        if (n > 0) log.info("Dropped $n unpaid counter order(s) idle for ${expireAfter.toMinutes()} min")
        return n
    }

    /**
     * The pickup board: today's numbers being prepared and ready. A pay-first
     * order is there once paid; a carry-out order once it is at the kitchen,
     * paid or not (it is paid at pickup).
     */
    fun board(): PickupBoard = transaction {
        val date = today().toString()
        val payFirst = (CounterOrders.orderSource inList OrderSource.PAY_FIRST) and (Checks.status eq "CLOSED")
        val payLater: Op<Boolean> = (CounterOrders.orderSource eq OrderSource.CARRY_OUT.wire) and
            (Checks.status inList LIVE + "CLOSED")
        val rows = joined().selectAll().where {
            (CounterOrders.businessDate eq date) and (CounterOrders.status inList listOf("PREPARING", "READY")) and
                CounterOrders.orderNumber.isNotNull() and (payFirst or payLater)
        }.orderBy(CounterOrders.orderNumber to SortOrder.ASC).toList()
        PickupBoard(
            venue = config.displayName,
            preparing = rows.filter { it[CounterOrders.status] == "PREPARING" }.map { it[CounterOrders.orderNumber]!! },
            ready = rows.filter { it[CounterOrders.status] == "READY" }
                .sortedBy { it[CounterOrders.readyAt] }.map { it[CounterOrders.orderNumber]!! },
            serverTime = VenueClock.iso(clock()),
            takeOut = rows.filter { it[CounterOrders.serviceMode] == "TAKE_OUT" }.map { it[CounterOrders.orderNumber]!! },
        )
    }
}
