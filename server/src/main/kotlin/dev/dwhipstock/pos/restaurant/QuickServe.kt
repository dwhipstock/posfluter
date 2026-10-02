package dev.dwhipstock.pos.restaurant

import dev.dwhipstock.pos.base.NotFoundException
import dev.dwhipstock.pos.base.ConflictException
import dev.dwhipstock.pos.base.CleanText
import dev.dwhipstock.pos.base.DeviceRegistry
import dev.dwhipstock.pos.base.Devices
import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.KeyedRateLimiter
import dev.dwhipstock.pos.base.LoginRateLimiter
import dev.dwhipstock.pos.db.utcTimestamp
import dev.dwhipstock.pos.sdk.CustomerConfig
import dev.dwhipstock.pos.sdk.KitchenLanguage
import dev.dwhipstock.pos.sdk.PrintJob
import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import dev.dwhipstock.pos.sdk.i18n.Messages
import dev.dwhipstock.pos.sdk.VenueClock
import dev.dwhipstock.pos.orders.CounterOrderView
import dev.dwhipstock.pos.orders.CounterOrders
import dev.dwhipstock.pos.orders.OrderSource
import dev.dwhipstock.pos.orders.PickupBoard
import dev.dwhipstock.pos.orders.PickupOrders
import dev.dwhipstock.pos.orders.SaleLocations
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** The counter's settings (056): the default dine in / take out. */
object CounterConfig : Table("counter_config") {
    val key = varchar("config_key", 64)
    val value = varchar("config_value", 200)
    override val primaryKey = PrimaryKey(key)
}

/** Paired devices that are self-order kiosks (055). */
object KioskDevices : Table("kiosk_devices") {
    val deviceId = varchar("device_id", 64)
    val name = varchar("name", 100)
    val pairedAt = utcTimestamp("paired_at")
    override val primaryKey = PrimaryKey(deviceId)
}

@Serializable
data class KioskOrderLine(
    val itemId: String, val variantId: String, val qty: Int = 1, val note: String? = null,
    /** The unit price the kiosk / counter showed (price_changed when it moved since). */
    val expectedPriceCents: Long? = null,
)

@Serializable
data class KioskOrderRequest(
    val serviceMode: String,
    val lines: List<KioskOrderLine>,
    /** The language the guest chose at the kiosk: their ticket prints in it. */
    val lang: String? = null,
    /**
     * The kiosk's own id for this order (one per guest's Pay tap): sent again
     * (a double tap, a retry after a dropped connection), the store answers
     * with the order it already placed instead of placing a second one.
     */
    val clientOrderId: String? = null,
)

@Serializable
data class KioskOrderResult(
    /** The guest's order number (101...), the same at the counter, the kitchen and the board. */
    val orderNumber: Int,
    val checkId: Int,
    val serviceMode: String,
    val totalCents: Long,
    /** An alcohol item is on the order: staff check ID at the counter (never blocks the order). */
    val idCheckAtCounter: Boolean,
    /** What the kiosk and its ticket show: "#101". */
    val displayNumber: String = "#$orderNumber",
    /** The store prints the guest a ticket for this order (the counter setting): the kiosk says to take it. */
    val ticket: Boolean = false,
    /** Lines the menu refused (deleted / 86'd / repriced since the kiosk loaded it); the order has the rest. */
    val rejected: List<RejectedLine> = emptyList(),
)

@Serializable
data class CounterSettings(
    val defaultServiceMode: String = "TAKE_OUT",
    /** Print the guest's ticket (number, items, total) on the receipt printer when a kiosk order is placed. */
    val kioskTicket: Boolean = true,
)

/** A change to the counter's settings: only what is given changes. */
@Serializable
data class CounterSettingsUpdate(val defaultServiceMode: String? = null, val kioskTicket: Boolean? = null)

@Serializable
data class KioskPairingCode(val code: String, val expiresInSeconds: Int)

@Serializable
data class KioskPairResponse(val deviceId: String, val deviceToken: String, val storeName: String)

/** A paired kiosk, for the manager's list (and its Unpair button). */
@Serializable
data class KioskDeviceView(val deviceId: String, val name: String, val pairedAt: String, val lastSeenAt: String? = null)

/**
 * The quick-serve counter (Copper Lantern Express), one flow for every order,
 * like a burger chain: ring it, pay it, and only then is it an order. No
 * tables; every order is its own check on the one counter "table".
 *
 *  - DRAFT: rung at the POS. It exists from its first item (an empty order is
 *    never stored) and can go dine in / take out until it is paid.
 *  - WAITING: placed at a kiosk, unpaid. It has its number (101...) from the
 *    start, printed on the guest's ticket, and keeps it until the counter takes
 *    the money. Expires after [expireAfter].
 *  - Paid in full (the check closes): the order is committed. It gets its
 *    number (101, 102... restarting each business day), is PREPARING on the
 *    pickup board and goes to the kitchen. Nothing unpaid ever does.
 *  - READY (the kitchen screen's last bump, or staff) -> PICKED_UP (staff).
 *    Only a paid order can be ready or picked up.
 *
 * The numbering, the pickup board and the kitchen / receipt side are the
 * store-wide [PickupOrders] (a restaurant's carry-out uses them too); this is
 * the counter's own part: ringing at the POS, the kiosks, the counter settings.
 */
class QuickServeService(
    private val config: CustomerConfig,
    private val checks: CheckService,
    /** Test seam: the business day the next number belongs to. */
    private val today: () -> LocalDate = { VenueClock.today() },
    private val clock: () -> Instant = { VenueClock.now() },
    /** An unpaid order older than this is dropped (a guest who never came to pay). */
    expireAfter: Duration = Duration.ofMinutes(30),
    /** The store's numbered orders (shared with carry-out when both are on). */
    val orders: PickupOrders = PickupOrders(config, checks, today, clock, expireAfter),
) {
    private val log = LoggerFactory.getLogger(QuickServeService::class.java)

    /** Kitchen tickets, when on: an order is sent the moment it is paid. */
    var kitchen: KitchenService?
        get() = orders.kitchen
        set(value) { orders.kitchen = value }

    companion object {
        const val COUNTER_ZONE = "counter"
        const val COUNTER_TABLE = "counter-1"
        const val FIRST_NUMBER = PickupOrders.FIRST_NUMBER
        val MODES = PickupOrders.MODES
        /** Where staff can move a paid order. */
        val STATUSES = PickupOrders.STATUSES
        val UNPAID = PickupOrders.UNPAID
        /** A numbered kiosk order that was never paid (expired, cleared): its number is not given again. */
        const val CANCELLED = PickupOrders.CANCELLED
        /** The first counter's statuses (PR #58): an unpaid row in one of these is an old test order. */
        private val LEGACY = listOf("NEW", "PREPARING", "READY", "PICKED_UP")
        private const val CODE_TTL_SECONDS = 600
        private const val MAX_LINES = 40
        /** Per kiosk; a flood (a stuck button, a script with a stolen token) gets 429 rate_limited. */
        const val KIOSK_ORDERS_PER_MINUTE = 10
        /** How long a kiosk's order id is remembered (a retry comes within seconds). */
        private val ORDER_ID_TTL: Duration = Duration.ofMinutes(15)
        private val LIVE = listOf("OPEN", "TOTAL_LOCKED")
        const val LEGACY_VOID_REASON = "Test order (old counter flow)"
    }

    /** The counter "table" every order sits on (seeded; re-created if someone removed it). */
    fun ensureCounter() = SaleLocations.ensureRegister(COUNTER_ZONE, COUNTER_TABLE, "Comptoir", "Counter", "C")

    private fun normMode(mode: String): String = orders.normMode(mode)

    // ------------------------------------------------------------ settings

    fun settings(): CounterSettings = transaction {
        val all = CounterConfig.selectAll().associate { it[CounterConfig.key] to it[CounterConfig.value] }
        CounterSettings(
            defaultServiceMode = all["default_service_mode"] ?: "TAKE_OUT",
            kioskTicket = all["kiosk_ticket"] != "off",
        )
    }

    fun updateSettings(next: CounterSettingsUpdate): CounterSettings = transaction {
        fun put(k: String, v: String) {
            if (CounterConfig.update({ CounterConfig.key eq k }) { it[value] = v } == 0)
                CounterConfig.insert { it[key] = k; it[value] = v }
        }
        next.defaultServiceMode?.let { put("default_service_mode", normMode(it)) }
        next.kioskTicket?.let { put("kiosk_ticket", if (it) "on" else "off") }
        settings()
    }

    // ------------------------------------------------------------ new orders

    /** Checks one line against the menu (live item, its own size). Inside a transaction. */
    private fun validate(l: KioskOrderLine) {
        require(l.qty in 1..20) { "qty must be 1-20" }
        require((cleanNote(l.note)?.length ?: 0) <= 200) { "note too long" }
        val item = Items.selectAll().where { (Items.id eq l.itemId) and Items.deletedAt.isNull() }.firstOrNull()
            ?: throw NotFoundException("item ${l.itemId} not found", "item_not_found")
        if (!item[Items.active]) throw ConflictException("${item[Items.nameEn]} is not available", "item_inactive")
        ItemVariants.selectAll().where {
            (ItemVariants.id eq l.variantId) and (ItemVariants.itemId eq l.itemId) and ItemVariants.deletedAt.isNull()
        }.firstOrNull() ?: throw NotFoundException("variant ${l.variantId} not found", "item_not_found")
    }

    /** A note as it is kept: printable, one line ([CleanText]); null when empty. */
    private fun cleanNote(note: String?): String? = CleanText.lineOrNull(note)

    /** A new unpaid order with its [lines] (never empty), no number yet. Returns the check id. */
    private fun create(mode: String, source: String, userId: String, lines: List<KioskOrderLine>): Int = transaction {
        val m = normMode(mode)
        require(lines.isNotEmpty()) { "empty order" }
        require(lines.size <= MAX_LINES) { "too many lines" }
        lines.forEach(::validate)
        ensureCounter()
        val checkId = checks.openCounterCheck(COUNTER_TABLE, userId)
        for (l in lines) checks.addLine(checkId, l.itemId, l.variantId, l.qty, cleanNote(l.note))
        val kiosk = source == "KIOSK"
        // a kiosk guest gets the number now (it is on their ticket); the counter's on payment
        orders.open(checkId, if (kiosk) OrderSource.KIOSK else OrderSource.POS, m,
            status = if (kiosk) "WAITING" else "DRAFT", numberNow = kiosk)
        checkId
    }

    /** The POS rings the first item of a new order: that is when it is stored (DRAFT). */
    fun createAtPos(mode: String, first: KioskOrderLine, userId: String): CounterOrderView {
        transaction { MenuGuard.require(first.itemId, first.variantId, first.expectedPriceCents) }
        return view(create(mode, "POS", userId, listOf(first)))
    }

    /**
     * A kiosk order: a new check with its lines live on the bill (never
     * PENDING), WAITING to be paid at the counter under its order number. It
     * goes to the kitchen only once it is paid, like any order.
     */
    fun placeKioskOrder(req: KioskOrderRequest, deviceName: String, deviceId: String = deviceName): KioskOrderResult {
        val clientId = req.clientOrderId?.trim()?.takeIf { it.isNotEmpty() }
        if (clientId == null) {
            kioskOrderLimiter.acquire(deviceId)
            return place(req, deviceName)
        }
        require(clientId.length <= 64 && clientId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "clientOrderId must be 1-64 letters, digits, - or _"
        }
        val key = "$deviceId|$clientId"
        val lock = placing.computeIfAbsent(key) { Any() }
        synchronized(lock) {
            forgetOldOrderIds()
            // the same Pay tap again: the order it already placed, nothing new
            placed[key]?.let {
                log.info("Kiosk order #${it.result.orderNumber} sent again by '$deviceName' (same order id): not placed twice")
                return it.result
            }
            kioskOrderLimiter.acquire(deviceId)
            val result = place(req, deviceName)
            placed[key] = Placed(clock(), result)
            return result
        }
    }

    /** A kiosk places at most this many orders a minute (a guest takes longer than that to order). */
    private val kioskOrderLimiter = KeyedRateLimiter(KIOSK_ORDERS_PER_MINUTE, Duration.ofMinutes(1), clock)

    private class Placed(val at: Instant, val result: KioskOrderResult)

    /** "device id|the kiosk's order id" → the order it placed, kept for [ORDER_ID_TTL]. */
    private val placed = ConcurrentHashMap<String, Placed>()
    private val placing = ConcurrentHashMap<String, Any>()

    private fun forgetOldOrderIds() {
        val cutoff = clock().minus(ORDER_ID_TTL)
        val old = placed.filterValues { it.at.isBefore(cutoff) }.keys
        old.forEach { placed.remove(it); placing.remove(it) }
    }

    private fun place(req: KioskOrderRequest, deviceName: String): KioskOrderResult {
        // the menu may have changed since the kiosk loaded it: refuse only the
        // lines it hit, take the rest (all refused → 409 lines_rejected)
        require(req.lines.isNotEmpty()) { "empty order" }
        val rejected = transaction {
            req.lines.mapIndexedNotNull { i, l -> MenuGuard.check(i, l.itemId, l.variantId, l.expectedPriceCents) }
        }
        if (rejected.size == req.lines.size) throw LineRejectedException(rejected, "lines_rejected")
        val refused = rejected.map { it.index }.toSet()
        val v = view(create(req.serviceMode, "KIOSK", "kiosk", req.lines.filterIndexed { i, _ -> i !in refused }))
        log.info("Kiosk order #${v.orderNumber} (${v.serviceMode}, ${v.itemCount} items) from '$deviceName', waiting to pay")
        val ticket = settings().kioskTicket
        if (ticket) printTicket(v, req.lang)
        return KioskOrderResult(v.orderNumber!!, v.checkId, v.serviceMode, v.totalCents, v.hasAlcohol, ticket = ticket,
            rejected = rejected)
    }

    /**
     * The guest's ticket, in their language, on the receipt printer's queue
     * (an offline printer is logged, like a receipt). Never fails the order.
     */
    private fun printTicket(v: CounterOrderView, lang: String?) {
        try {
            val locale = lang?.let { LocaleCode.of(it) }
                ?.takeIf { it in config.profile.locales && Messages.supports(it) }
                ?: config.receiptPolicy.locale
            val lines = KioskTicket.render(
                checks.billSnapshot(v.checkId), v.orderNumber!!, v.serviceMode == "TAKE_OUT", v.hasAlcohol,
                config.receiptPolicy.withLocale(locale), config.profile.currency, config.legalAge,
            )
            config.printer.printTicket(PrintJob(v.checkId, lines))
        } catch (e: Exception) {
            log.warn("kiosk ticket for #${v.orderNumber} not printed: ${e.message}")
        }
    }

    private val upsellRules = QuickServeUpsell({ config.upsell })

    /** The kiosk's "Add a drink?" step for the guest's cart ([QuickServeUpsell]); nothing is stored. */
    fun kioskUpsell(req: KioskUpsellRequest): KioskUpsell {
        require(req.lines.size <= MAX_LINES) { "too many lines" }
        return upsellRules.suggest(req.lines)
    }

    /** Dine in / take out: changeable until the order is paid. */
    fun setMode(checkId: Int, mode: String): CounterOrderView {
        val m = normMode(mode)
        transaction {
            val row = requireOrder(checkId)
            if (row[CounterOrders.status] !in UNPAID || checks.getCheck(checkId).status !in LIVE)
                throw ConflictException("order is already paid", "order_paid")
            CounterOrders.update({ CounterOrders.checkId eq checkId }) { it[serviceMode] = m }
        }
        return view(checkId)
    }

    /** The cashier drops an unpaid order (nothing tendered): its check is cancelled, the order is gone. */
    fun discard(checkId: Int) {
        val row = transaction { requireOrder(checkId) }
        if (row[CounterOrders.status] !in UNPAID) throw ConflictException("order is already paid", "order_paid")
        checks.cancelUnpaid(checkId, "discarded")
    }

    // ------------------------------------------------------------ the kitchen's side ([PickupOrders])

    /** The kitchen never gets a counter order before it is paid (a numbered kiosk order neither). */
    fun holdKitchen(checkId: Int): Boolean = orders.holdKitchen(checkId)

    // ------------------------------------------------------------ status

    /** Staff move a paid order along (or back): PREPARING, READY, PICKED_UP. Unpaid: refused. */
    fun setStatus(checkId: Int, status: String): CounterOrderView = orders.setStatus(checkId, status)

    /** The kitchen screen bumped the order's last card: a paid order being prepared is ready. */
    fun kitchenDone(checkId: Int) = orders.kitchenDone(checkId)

    private fun requireOrder(checkId: Int) = orders.requireOrder(checkId)

    fun view(checkId: Int): CounterOrderView = orders.view(checkId)

    private fun viewOf(row: ResultRow): CounterOrderView = orders.viewOf(row)

    private fun joined() = orders.joined()

    /**
     * The counter's Orders panel: today's paid orders, newest first
     * (preparing, ready and picked up). Unpaid ones are never listed here.
     */
    fun list(): List<CounterOrderView> = transaction {
        val date = today().toString()
        joined().selectAll().where {
            (CounterOrders.businessDate eq date) and CounterOrders.orderNumber.isNotNull() and
                (Checks.status eq "CLOSED") and (CounterOrders.status inList STATUSES) and
                (CounterOrders.orderSource inList OrderSource.PAY_FIRST)
        }.orderBy(CounterOrders.orderNumber to SortOrder.DESC).limit(200).map { viewOf(it) }
    }

    /** Kiosk orders waiting to be paid at the counter, oldest first (expired ones dropped first). */
    fun waiting(): List<CounterOrderView> {
        expire()
        return transaction {
            joined().selectAll().where {
                (CounterOrders.status eq "WAITING") and (Checks.status inList LIVE) and
                    (CounterOrders.orderSource inList OrderSource.PAY_FIRST)
            }.orderBy(CounterOrders.createdAt to SortOrder.ASC).map { viewOf(it) }
        }
    }

    /** Unpaid orders older than the expiry, nothing tendered: dropped (their checks cancelled). */
    fun expire(): Int = orders.expire()

    /**
     * Once, at startup: the first counter (PR #58) numbered and listed orders
     * before they were paid. Its leftovers are cleaned up so the counter starts
     * clean: an empty one is dropped, an unpaid one with items becomes a voided
     * test order. Unpaid orders with money on them are left as they are.
     */
    fun cleanupLegacy(): Int {
        val rows = transaction {
            joined().selectAll().where {
                (CounterOrders.status inList LEGACY) and (Checks.status inList LIVE) and
                    (CounterOrders.orderSource inList OrderSource.PAY_FIRST)
            }.map { it[CounterOrders.checkId] }
        }
        var n = 0
        for (id in rows) {
            try {
                if (checks.hasTenders(id)) continue
                if (checks.getCheck(id).lines.isEmpty()) {
                    checks.cancelUnpaid(id, "empty")
                    transaction { CounterOrders.deleteWhere { CounterOrders.checkId eq id } }
                } else {
                    checks.systemVoid(id, LEGACY_VOID_REASON)
                    transaction { CounterOrders.update({ CounterOrders.checkId eq id }) { it[status] = "VOIDED" } }
                }
                n++
            } catch (e: Exception) {
                log.warn("cleaning up old counter order $id failed: ${e.message}")
            }
        }
        // a paid order the first counter never placed: off the board
        transaction {
            CounterOrders.update({ (CounterOrders.status eq "NEW") and (CounterOrders.orderSource inList OrderSource.PAY_FIRST) }) {
                it[status] = "PICKED_UP"
            }
        }
        if (n > 0) log.info("Counter: cleaned up $n old unpaid / empty test order(s)")
        return n
    }

    /** The pickup board: today's paid numbers being prepared and ready. */
    fun board(): PickupBoard = orders.board()

    /** What a kitchen ticket / card says instead of a table: "#101 · Take out". Null = not a counter order. */
    fun ticketLabel(checkId: Int, language: KitchenLanguage): String? = orders.ticketLabel(checkId, language)

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
        val name = "Kiosk" + CleanText.line(deviceName).trim().take(60).let { if (it.isEmpty()) "" else " — $it" }
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

    /** The paired kiosks (unpaired ones are gone from the list), oldest first. */
    fun kiosks(): List<KioskDeviceView> = transaction {
        KioskDevices.join(Devices, JoinType.INNER, KioskDevices.deviceId, Devices.id)
            .selectAll().where { Devices.revokedAt.isNull() }
            .orderBy(KioskDevices.pairedAt to SortOrder.ASC)
            .map {
                KioskDeviceView(
                    it[KioskDevices.deviceId], it[KioskDevices.name], VenueClock.iso(it[KioskDevices.pairedAt]),
                    it[Devices.lastSeenAt]?.let(VenueClock::iso),
                )
            }
    }

    /**
     * A manager unpairs a kiosk (lost, stolen, replaced) right here, with no
     * portal or internet: its token stops working at once (the kiosk goes
     * back to its pairing screen) and the portal's device list shows it
     * revoked on the next heartbeat. Pairing it again takes a new code.
     */
    fun unpairKiosk(deviceId: String, byUserId: String): List<KioskDeviceView> {
        if (!isKiosk(deviceId)) throw NotFoundException("no kiosk $deviceId", "kiosk_not_found")
        DeviceRegistry.applyRevocation(deviceId)
        log.info("kiosk unpaired: $deviceId (by $byUserId)")
        return kiosks()
    }
}
