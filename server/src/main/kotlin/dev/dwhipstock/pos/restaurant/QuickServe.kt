package dev.dwhipstock.pos.restaurant

import dev.dwhipstock.pos.base.DeviceRegistry
import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.LoginRateLimiter
import dev.dwhipstock.pos.db.utcTimestamp
import dev.dwhipstock.pos.sdk.CustomerConfig
import dev.dwhipstock.pos.sdk.KitchenLanguage
import dev.dwhipstock.pos.sdk.VenueClock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** One quick-serve order (055): its check, number, dine in / take out, source and pickup status. */
object CounterOrders : Table("counter_orders") {
    val checkId = integer("check_id")
    val businessDate = varchar("business_date", 10)
    val orderNumber = integer("order_number")
    /** DINE_IN | TAKE_OUT */
    val serviceMode = varchar("service_mode", 10)
    /** POS | KIOSK */
    val orderSource = varchar("source", 10)
    /** NEW | PREPARING | READY | PICKED_UP */
    val status = varchar("status", 12)
    val createdAt = utcTimestamp("created_at")
    val readyAt = utcTimestamp("ready_at").nullable()
    val pickedUpAt = utcTimestamp("picked_up_at").nullable()
    override val primaryKey = PrimaryKey(checkId)
}

/** Paired devices that are self-order kiosks (055). */
object KioskDevices : Table("kiosk_devices") {
    val deviceId = varchar("device_id", 64)
    val name = varchar("name", 100)
    val pairedAt = utcTimestamp("paired_at")
    override val primaryKey = PrimaryKey(deviceId)
}

@Serializable
data class CounterOrderView(
    val checkId: Int,
    val orderNumber: Int,
    val serviceMode: String,
    val source: String,
    val status: String,
    /** The check's own status: OPEN (unpaid) | TOTAL_LOCKED | CLOSED (paid) | VOID | CANCELLED. */
    val checkStatus: String,
    val totalCents: Long,
    val outstandingCents: Long,
    val itemCount: Int,
    val hasAlcohol: Boolean,
    val createdAt: String,
    val businessDate: String,
)

@Serializable
data class KioskOrderLine(val itemId: String, val variantId: String, val qty: Int = 1, val note: String? = null)

@Serializable
data class KioskOrderRequest(val serviceMode: String, val lines: List<KioskOrderLine>)

@Serializable
data class KioskOrderResult(
    val orderNumber: Int,
    val checkId: Int,
    val serviceMode: String,
    val totalCents: Long,
    /** An alcohol item is on the order: staff check ID at the counter (never blocks the order). */
    val idCheckAtCounter: Boolean,
)

@Serializable
data class PickupBoard(
    val venue: String,
    val preparing: List<Int>,
    val ready: List<Int>,
    val serverTime: String,
)

@Serializable
data class KioskPairingCode(val code: String, val expiresInSeconds: Int)

@Serializable
data class KioskPairResponse(val deviceId: String, val deviceToken: String, val storeName: String)

/**
 * The quick-serve counter (Copper Lantern Express): no tables, every order is
 * its own check on the one counter "table", called by a short number that
 * restarts each business day at 101. Orders come from the POS or from a
 * self-order kiosk; a kiosk order is not paid there, it goes straight to the
 * kitchen (no staff approval, unlike QR orders) and is paid at the counter.
 * Pickup status: NEW (being rung) -> PREPARING -> READY -> PICKED_UP.
 */
class QuickServeService(
    private val config: CustomerConfig,
    private val checks: CheckService,
    /** Test seam: the business day the next number belongs to. */
    private val today: () -> LocalDate = { VenueClock.today() },
    private val clock: () -> Instant = { VenueClock.now() },
) {
    private val log = LoggerFactory.getLogger(QuickServeService::class.java)

    /** Kitchen tickets, when on: kiosk orders are sent the moment they are placed. */
    var kitchen: KitchenService? = null

    companion object {
        const val COUNTER_ZONE = "counter"
        const val COUNTER_TABLE = "counter-1"
        const val FIRST_NUMBER = 101
        val MODES = setOf("DINE_IN", "TAKE_OUT")
        val STATUSES = listOf("NEW", "PREPARING", "READY", "PICKED_UP")
        private const val CODE_TTL_SECONDS = 600
        private const val MAX_LINES = 40
        private val LIVE = listOf("OPEN", "TOTAL_LOCKED")
    }

    /** The counter "table" every order sits on (seeded; re-created if someone removed it). */
    fun ensureCounter() = transaction {
        Zones.insertIgnore {
            it[id] = COUNTER_ZONE; it[nameFr] = "Comptoir"; it[nameEn] = "Counter"; it[sortOrder] = 0
            it[labelPrefix] = "C"
        }
        if (DiningTables.selectAll().where { DiningTables.id eq COUNTER_TABLE }.empty()) {
            DiningTables.insert {
                it[id] = COUNTER_TABLE; it[zoneId] = COUNTER_ZONE; it[label] = "1"
                it[shape] = "SQUARE"; it[seats] = 0
            }
        }
    }

    private fun normMode(mode: String): String {
        val m = mode.trim().uppercase().replace('-', '_')
        require(m in MODES) { "serviceMode must be DINE_IN or TAKE_OUT" }
        return m
    }

    /** The next number for [date]: 101 on a new day, then one more each order. Call inside a transaction. */
    private fun nextNumber(date: String): Int =
        (CounterOrders.selectAll().where { CounterOrders.businessDate eq date }
            .maxOfOrNull { it[CounterOrders.orderNumber] } ?: (FIRST_NUMBER - 1)) + 1

    private fun create(mode: String, source: String, userId: String): Int = transaction {
        ensureCounter()
        val checkId = checks.openCounterCheck(COUNTER_TABLE, userId)
        val date = today().toString()
        CounterOrders.insert {
            it[CounterOrders.checkId] = checkId
            it[businessDate] = date
            it[orderNumber] = nextNumber(date)
            it[serviceMode] = normMode(mode)
            it[orderSource] = source
            it[status] = "NEW"
            it[createdAt] = clock()
        }
        checkId
    }

    /** A new order rung at the POS (NEW until it is placed or sent). */
    fun createAtPos(mode: String, userId: String): CounterOrderView = view(create(mode, "POS", userId))

    /**
     * A kiosk order, all at once: a new numbered check, its lines live on the
     * bill (never PENDING), PREPARING, and sent to the kitchen straight away.
     * Unpaid: the counter takes the money on the POS.
     */
    fun placeKioskOrder(req: KioskOrderRequest, deviceName: String): KioskOrderResult {
        val mode = normMode(req.serviceMode)
        require(req.lines.isNotEmpty()) { "empty order" }
        require(req.lines.size <= MAX_LINES) { "too many lines" }
        val checkId = transaction {
            for (l in req.lines) {
                require(l.qty in 1..20) { "qty must be 1-20" }
                require((l.note?.length ?: 0) <= 200) { "note too long" }
                val item = Items.selectAll().where { (Items.id eq l.itemId) and Items.deletedAt.isNull() }.firstOrNull()
                    ?: throw NotFoundException("item ${l.itemId} not found", "item_not_found")
                if (!item[Items.active]) throw ConflictException("${item[Items.nameEn]} is not available", "item_inactive")
                ItemVariants.selectAll().where {
                    (ItemVariants.id eq l.variantId) and (ItemVariants.itemId eq l.itemId) and ItemVariants.deletedAt.isNull()
                }.firstOrNull() ?: throw NotFoundException("variant ${l.variantId} not found", "item_not_found")
            }
            val id = create(mode, "KIOSK", "kiosk")
            for (l in req.lines) checks.addLine(id, l.itemId, l.variantId, l.qty, l.note?.trim()?.takeIf { it.isNotEmpty() })
            CounterOrders.update({ CounterOrders.checkId eq id }) { it[status] = "PREPARING" }
            id
        }
        sendToKitchen(checkId, deviceName)
        val v = view(checkId)
        log.info("Kiosk order #${v.orderNumber} (${v.serviceMode}, ${v.itemCount} items) from '$deviceName'")
        return KioskOrderResult(v.orderNumber, checkId, v.serviceMode, v.totalCents, v.hasAlcohol)
    }

    private fun sendToKitchen(checkId: Int, sender: String) {
        val k = kitchen ?: return
        try { k.send(checkId, sender) } catch (e: Exception) { log.warn("kitchen send for order $checkId failed: ${e.message}") }
    }

    /** The POS is done ringing: NEW -> PREPARING (when something is on it), and the kitchen gets it. */
    fun place(checkId: Int, senderName: String?): CounterOrderView {
        transaction {
            val row = requireOrder(checkId)
            val lines = CheckLines.selectAll().where { (CheckLines.checkId eq checkId) and (CheckLines.status eq "ACTIVE") }.count()
            if (row[CounterOrders.status] == "NEW" && lines > 0) {
                CounterOrders.update({ CounterOrders.checkId eq checkId }) { it[status] = "PREPARING" }
            }
        }
        sendToKitchen(checkId, senderName ?: "POS")
        return view(checkId)
    }

    /** Staff move an order along (or back): PREPARING, READY, PICKED_UP. */
    fun setStatus(checkId: Int, status: String): CounterOrderView {
        val s = status.trim().uppercase().replace('-', '_')
        require(s in STATUSES) { "status must be one of $STATUSES" }
        transaction {
            requireOrder(checkId)
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

    /** The kitchen screen bumped the order's last card: it is ready (once; a picked-up order stays so). */
    fun kitchenDone(checkId: Int) {
        val moved = transaction {
            CounterOrders.update({
                (CounterOrders.checkId eq checkId) and (CounterOrders.status inList listOf("NEW", "PREPARING"))
            }) { it[status] = "READY"; it[readyAt] = clock() }
        }
        if (moved > 0) log.info("Order for check $checkId is ready (kitchen screen)")
    }

    private fun requireOrder(checkId: Int) =
        CounterOrders.selectAll().where { CounterOrders.checkId eq checkId }.firstOrNull()
            ?: throw NotFoundException("no counter order for check $checkId", "order_not_found")

    fun view(checkId: Int): CounterOrderView = transaction { viewOf(requireOrder(checkId)) }

    private fun viewOf(row: org.jetbrains.exposed.sql.ResultRow): CounterOrderView {
        val id = row[CounterOrders.checkId]
        val c = checks.getCheck(id)
        val alcohol = transaction {
            val ids = c.lines.mapNotNull { it.itemId }.toSet()
            ids.isNotEmpty() && Items.selectAll().where { (Items.id inList ids) and (Items.isAlcohol eq true) }.count() > 0
        }
        return CounterOrderView(
            checkId = id,
            orderNumber = row[CounterOrders.orderNumber],
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
        )
    }

    /**
     * The POS order list: today's orders still to be paid or handed over, and
     * any older one still unpaid. Voided / cancelled orders drop off.
     */
    fun list(): List<CounterOrderView> = transaction {
        val date = today().toString()
        CounterOrders.join(Checks, org.jetbrains.exposed.sql.JoinType.INNER, CounterOrders.checkId, Checks.id)
            .selectAll().where {
                (Checks.status inList LIVE) or
                    ((CounterOrders.businessDate eq date) and (Checks.status eq "CLOSED") and (CounterOrders.status neq "PICKED_UP"))
            }
            .orderBy(CounterOrders.businessDate to SortOrder.ASC, CounterOrders.orderNumber to SortOrder.ASC)
            .map { viewOf(it) }
    }

    /** The pickup board: today's numbers being prepared and ready (a voided order is not called). */
    fun board(): PickupBoard = transaction {
        val date = today().toString()
        val rows = CounterOrders.join(Checks, org.jetbrains.exposed.sql.JoinType.INNER, CounterOrders.checkId, Checks.id)
            .selectAll().where {
                (CounterOrders.businessDate eq date) and (CounterOrders.status inList listOf("PREPARING", "READY")) and
                    (Checks.status notInList listOf("VOID", "CANCELLED"))
            }.orderBy(CounterOrders.orderNumber to SortOrder.ASC).toList()
        PickupBoard(
            venue = config.displayName,
            preparing = rows.filter { it[CounterOrders.status] == "PREPARING" }.map { it[CounterOrders.orderNumber] },
            ready = rows.filter { it[CounterOrders.status] == "READY" }
                .sortedBy { it[CounterOrders.readyAt] }.map { it[CounterOrders.orderNumber] },
            serverTime = VenueClock.iso(clock()),
        )
    }

    /** What a kitchen ticket / card says instead of a table: "#101 · Take out". Null = not a counter order. */
    fun ticketLabel(checkId: Int, language: KitchenLanguage): String? = transaction {
        val row = CounterOrders.selectAll().where { CounterOrders.checkId eq checkId }.firstOrNull() ?: return@transaction null
        val takeOut = row[CounterOrders.serviceMode] == "TAKE_OUT"
        val mode = when (language) {
            KitchenLanguage.FR -> if (takeOut) "Pour emporter" else "Sur place"
            KitchenLanguage.EN -> if (takeOut) "Take out" else "Dine in"
            KitchenLanguage.BOTH -> if (takeOut) "Pour emporter / Take out" else "Sur place / Dine in"
        }
        "#${row[CounterOrders.orderNumber]} · $mode"
    }

    // ------------------------------------------------------------------ kiosks

    private val random = SecureRandom()
    private val codes = ConcurrentHashMap<String, Instant>()
    private val pairLimiter = LoginRateLimiter()

    /** A one-time 6-digit code the manager reads off the POS and types on a kiosk (10 minutes). */
    fun newPairingCode(): KioskPairingCode {
        val now = clock()
        codes.entries.removeIf { it.value.isBefore(now) }
        val code = (100000 + random.nextInt(900000)).toString()
        codes[code] = now.plusSeconds(CODE_TTL_SECONDS.toLong())
        return KioskPairingCode(code, CODE_TTL_SECONDS)
    }

    /** The kiosk's side: a good code mints a paired device (its own token; many kiosks per store). */
    fun pairKiosk(code: String, deviceName: String): KioskPairResponse {
        pairLimiter.checkNotLocked()
        val expires = codes.remove(code.trim())
        if (expires == null || expires.isBefore(clock())) {
            pairLimiter.recordFailure()
            throw NotFoundException("unknown or expired kiosk code", "bad_pairing_code")
        }
        pairLimiter.recordSuccess()
        val name = "Kiosk" + deviceName.trim().take(60).let { if (it.isEmpty()) "" else " — $it" }
        val (deviceId, token) = DeviceRegistry.pair(name)
        transaction {
            KioskDevices.insert { it[KioskDevices.deviceId] = deviceId; it[KioskDevices.name] = name; it[pairedAt] = clock() }
        }
        log.info("kiosk paired: $deviceId ('$name')")
        return KioskPairResponse(deviceId, token, config.displayName)
    }

    fun isKiosk(deviceId: String): Boolean = transaction {
        !KioskDevices.selectAll().where { KioskDevices.deviceId eq deviceId }.empty()
    }
}
