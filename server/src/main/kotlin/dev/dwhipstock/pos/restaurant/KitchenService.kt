package dev.dwhipstock.pos.restaurant

import dev.dwhipstock.pos.base.Categories
import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.SettingsRepository
import dev.dwhipstock.pos.base.Users
import dev.dwhipstock.pos.sdk.CustomerConfig
import dev.dwhipstock.pos.sdk.EscPosTransport
import dev.dwhipstock.pos.sdk.KitchenLanguage
import dev.dwhipstock.pos.sdk.KitchenTicketData
import dev.dwhipstock.pos.sdk.KitchenTicketItem
import dev.dwhipstock.pos.sdk.KitchenTicketKind
import dev.dwhipstock.pos.sdk.KitchenTicketRenderer
import dev.dwhipstock.pos.sdk.PrintLine
import dev.dwhipstock.pos.sdk.PrinterTarget
import dev.dwhipstock.pos.sdk.ThermalLayout
import dev.dwhipstock.pos.sdk.ThermalReceiptRenderer
import dev.dwhipstock.pos.sdk.VenueClock
import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/** What the check flow tells the kitchen side, after its own transaction commits. */
interface KitchenHook {
    /** The check was voided or cancelled: every sent item goes out as a VOID. */
    fun checkEnded(checkId: Int)
    /** Lines moved from [sourceId] to [destId] (a merge): the kitchen already has them. */
    fun checksMerged(sourceId: Int, destId: Int)
}

@Serializable
data class KitchenStationDto(
    val id: String = "",
    val nameFr: String,
    val nameEn: String,
    /** printer | screen | both */
    val output: String = "printer",
    /** Blank = the receipt printer. */
    val printerHost: String = "",
    val printerPort: Int = 9100,
    /** 58 or 80. */
    val paperMm: Int = 80,
    val sortOrder: Int = 0,
)

/** kind = category | item; stationId "" = no ticket. */
@Serializable
data class KitchenRouteDto(val kind: String, val refId: String, val stationId: String)

@Serializable
data class KitchenSettingsDto(
    /** fr | en | both; "" = the store's own language. */
    val language: String = "",
    /** Where an unmapped category (or an off-menu item) goes; "" = no ticket. */
    val defaultStationId: String = "",
    /** Kitchen screen: a card turns yellow after this many minutes… */
    val warnMinutes: Int = 10,
    /** …and red after this many. */
    val lateMinutes: Int = 20,
    /** Kitchen screen: chime when a new order arrives. */
    val sound: Boolean = true,
)

@Serializable
data class KitchenConfigDto(
    val settings: KitchenSettingsDto,
    /** The language in effect (settings.language, or the store's). */
    val language: String,
    val stations: List<KitchenStationDto>,
    val routes: List<KitchenRouteDto>,
)

@Serializable
data class KitchenSendResult(
    /** Ticket events written (printer and/or screen). */
    val tickets: Int,
    val printJobs: Int,
    /** Items sent as VOID. */
    val voided: Int,
    val stations: List<String>,
)

@Serializable
data class KitchenCheckState(
    val checkId: Int,
    val guests: Int? = null,
    /** Items (by quantity) a send would add: new, increased or changed. */
    val unsent: Int,
    /** Items (by quantity) a send would void. */
    val pendingVoids: Int,
    /** Items the stations already have. */
    val sent: Int,
)

@Serializable
data class KitchenTestResult(
    val ok: Boolean,
    val configured: Boolean,
    val target: String,
    val error: String? = null,
)

@Serializable
data class KdsItem(
    val lineId: Int,
    val qty: Int,
    val nameFr: String,
    val nameEn: String,
    val variantFr: String? = null,
    val variantEn: String? = null,
    val note: String? = null,
    /** Came in on a later send (an ADD ticket). */
    val add: Boolean = false,
    /** Taken off after it was sent: shown struck through. */
    val voided: Boolean = false,
    /** Of [qty], how many were voided (partly voided lines show the rest). */
    val voidedQty: Int = 0,
)

@Serializable
data class KdsCard(
    /** "checkId:stationId": what a bump names. */
    val key: String,
    val checkId: Int,
    val stationId: String,
    val stationNameFr: String,
    val stationNameEn: String,
    val tableLabel: String,
    val serverName: String,
    val guests: Int? = null,
    val firstSentAt: String,
    val elapsedSeconds: Long,
    /** ok | warn | late (the timer colour). */
    val level: String,
    val items: List<KdsItem>,
)

@Serializable
data class KdsBump(
    val bumpId: String,
    val checkId: Int,
    val stationId: String,
    val tableLabel: String,
    val bumpedAt: String,
)

@Serializable
data class KdsBoard(
    val cards: List<KdsCard>,
    /** Last bumps, newest first: what Recall brings back. */
    val recent: List<KdsBump>,
    /** Stations that show on the screen. */
    val stations: List<KitchenStationDto>,
    val warnMinutes: Int,
    val lateMinutes: Int,
    val sound: Boolean,
    val language: String,
    /** Highest ticket id on screen: a new order raises it (the chime). */
    val latestTicket: Int,
    val serverTime: String,
)

/**
 * Kitchen / station tickets (kitchen.printing=on, restaurants only).
 *
 * Sending an order compares each check line with what its station was last
 * told ([KitchenSentLines]) and writes ticket events: ORDER (first send to
 * that station), ADD (new or increased items), VOID (removed, reduced, or the
 * whole check voided). A changed note is a VOID of the old line plus an ADD
 * of the new one. Each ticket event goes to its station's output: the print
 * queue, the kitchen screen, or both — the same event, so they always agree.
 *
 * All of it is local SQLite; a printer problem never touches the check. The
 * send diff runs in one transaction under a lock, so a repeated or retried
 * send finds nothing new and prints nothing twice.
 */
class KitchenService(
    private val config: CustomerConfig,
    private val settings: SettingsRepository,
    transport: EscPosTransport = KitchenTcpTransport(),
    /** Test seam: the kitchen's clock in epoch millis (ticket times, timers, backoff). */
    private val clockMs: () -> Long = System::currentTimeMillis,
    /** False on the isolated test build: no printer is ever contacted. */
    private val printersEnabled: Boolean = true,
    /** Test seam: bytes for a ticket (the real raster by default). */
    render: (List<PrintLine>, Int) -> ByteArray = { lines, width -> ThermalReceiptRenderer.toEscPos(lines, width) },
) : KitchenHook {
    private val log = LoggerFactory.getLogger(KitchenService::class.java)
    private val sendLock = Any()
    private val transport = transport
    private val renderBytes = render

    val queue = KitchenPrintQueue(transport, ::targetFor, clockMs, render)

    companion object {
        val OUTPUTS = setOf("printer", "screen", "both")
        val DRINK_CATEGORIES = listOf("beer-cider", "wine", "cocktails")
        val FOOD_CATEGORIES = listOf("starters", "burgers-sandwiches", "mains-salads", "desserts")
        const val SUSHI_CATEGORY = "sushi"
        private val HOST = Regex("^[A-Za-z0-9.:-]{1,64}$")
        private val SLUG = Regex("^[a-z0-9-]{1,64}$")
        private const val RECENT_BUMPS = 8
    }

    private fun now(): Instant = Instant.ofEpochMilli(clockMs())

    // ------------------------------------------------------------------ config

    /** First start with kitchen tickets on: Kitchen + Bar (+ Sushi Bar where the menu has sushi). Once. */
    fun ensureDefaults() = transaction {
        if (KitchenConfigTable.get("seeded") != null) return@transaction
        val categories = Categories.selectAll().map { it[Categories.id] }.toSet()
        fun station(id: String, fr: String, en: String, order: Int) = KitchenStations.insert {
            it[KitchenStations.id] = id; it[nameFr] = fr; it[nameEn] = en
            it[output] = "both"; it[sortOrder] = order
        }
        station("kitchen", "Cuisine", "Kitchen", 0)
        station("bar", "Bar", "Bar", 1)
        if (SUSHI_CATEGORY in categories) station("sushi", "Bar à sushis", "Sushi Bar", 2)
        fun route(category: String, station: String) {
            if (category in categories) KitchenRoutes.insert {
                it[kind] = "category"; it[refId] = category; it[stationId] = station
            }
        }
        FOOD_CATEGORIES.forEach { route(it, "kitchen") }
        DRINK_CATEGORIES.forEach { route(it, "bar") }
        route(SUSHI_CATEGORY, "sushi")
        // a drink in a mixed category (Plateau Specials' cocktail) still goes to the bar
        val routed = FOOD_CATEGORIES + DRINK_CATEGORIES + SUSHI_CATEGORY
        Items.selectAll().where { Items.isAlcohol eq true }
            .filter { it[Items.categoryId] !in routed }
            .forEach { row ->
                KitchenRoutes.insert { it[kind] = "item"; it[refId] = row[Items.id]; it[stationId] = "bar" }
            }
        KitchenConfigTable.set("default_station", "kitchen")
        KitchenConfigTable.set("seeded", "1")
        log.info("Kitchen tickets: seeded default stations (${if (SUSHI_CATEGORY in categories) "Kitchen, Bar, Sushi Bar" else "Kitchen, Bar"})")
    }

    private fun storeLanguage(): KitchenLanguage =
        if (config.profile.defaultLocale == LocaleCode.FR) KitchenLanguage.FR else KitchenLanguage.EN

    fun settings(): KitchenSettingsDto = transaction {
        KitchenSettingsDto(
            language = KitchenConfigTable.get("language").orEmpty(),
            defaultStationId = KitchenConfigTable.get("default_station").orEmpty(),
            warnMinutes = KitchenConfigTable.get("warn_minutes")?.toIntOrNull() ?: 10,
            lateMinutes = KitchenConfigTable.get("late_minutes")?.toIntOrNull() ?: 20,
            sound = KitchenConfigTable.get("sound") != "off",
        )
    }

    fun language(s: KitchenSettingsDto = settings()): KitchenLanguage =
        KitchenLanguage.parse(s.language) ?: storeLanguage()

    fun updateSettings(next: KitchenSettingsDto): KitchenSettingsDto = transaction {
        require(next.language.isEmpty() || KitchenLanguage.parse(next.language) != null) { "language must be fr, en or both" }
        require(next.warnMinutes in 1..240) { "warn minutes must be 1–240" }
        require(next.lateMinutes in 1..240 && next.lateMinutes > next.warnMinutes) {
            "late minutes must be 1–240 and later than warn minutes"
        }
        require(next.defaultStationId.isEmpty() || stationRow(next.defaultStationId) != null) { "unknown station" }
        KitchenConfigTable.set("language", next.language.lowercase())
        KitchenConfigTable.set("default_station", next.defaultStationId)
        KitchenConfigTable.set("warn_minutes", next.warnMinutes.toString())
        KitchenConfigTable.set("late_minutes", next.lateMinutes.toString())
        KitchenConfigTable.set("sound", if (next.sound) "on" else "off")
        settings()
    }

    fun configView(): KitchenConfigDto = transaction {
        val s = settings()
        KitchenConfigDto(s, language(s).wire, stations(), routes())
    }

    fun stations(): List<KitchenStationDto> = transaction {
        KitchenStations.selectAll().orderBy(KitchenStations.sortOrder to SortOrder.ASC, KitchenStations.id to SortOrder.ASC)
            .map(::stationDto)
    }

    private fun stationDto(r: org.jetbrains.exposed.sql.ResultRow) = KitchenStationDto(
        r[KitchenStations.id], r[KitchenStations.nameFr], r[KitchenStations.nameEn], r[KitchenStations.output],
        r[KitchenStations.printerHost], r[KitchenStations.printerPort], r[KitchenStations.paperMm], r[KitchenStations.sortOrder],
    )

    private fun stationRow(id: String): KitchenStationDto? =
        KitchenStations.selectAll().where { KitchenStations.id eq id }.firstOrNull()?.let(::stationDto)

    fun routes(): List<KitchenRouteDto> = transaction {
        KitchenRoutes.selectAll().orderBy(KitchenRoutes.kind to SortOrder.ASC, KitchenRoutes.refId to SortOrder.ASC)
            .map { KitchenRouteDto(it[KitchenRoutes.kind], it[KitchenRoutes.refId], it[KitchenRoutes.stationId]) }
    }

    /** Create (blank id) or update a station. */
    fun saveStation(dto: KitchenStationDto): KitchenStationDto = transaction {
        val nameFr = dto.nameFr.trim()
        val nameEn = dto.nameEn.trim()
        require(nameFr.isNotEmpty() && nameEn.isNotEmpty()) { "station needs a French and an English name" }
        require(nameFr.length <= 100 && nameEn.length <= 100) { "station name too long" }
        require(dto.output in OUTPUTS) { "output must be printer, screen or both" }
        val host = dto.printerHost.trim()
        require(host.isEmpty() || host.matches(HOST)) { "printer must be a hostname or IPv4 address" }
        require(dto.printerPort in 1..65535) { "printer port must be 1–65535" }
        require(dto.paperMm == 58 || dto.paperMm == 80) { "paper must be 58 or 80 mm" }
        val id = dto.id.trim().ifEmpty {
            val base = nameEn.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "station" }.take(56)
            var candidate = base
            var n = 2
            while (stationRow(candidate) != null) candidate = "$base-${n++}"
            candidate
        }
        require(id.matches(SLUG)) { "station id must be lowercase letters, digits and dashes" }
        val exists = stationRow(id) != null
        if (exists) {
            KitchenStations.update({ KitchenStations.id eq id }) {
                it[KitchenStations.nameFr] = nameFr; it[KitchenStations.nameEn] = nameEn; it[output] = dto.output
                it[printerHost] = host; it[printerPort] = dto.printerPort; it[paperMm] = dto.paperMm
                it[sortOrder] = dto.sortOrder
            }
        } else {
            KitchenStations.insert {
                it[KitchenStations.id] = id; it[KitchenStations.nameFr] = nameFr; it[KitchenStations.nameEn] = nameEn
                it[output] = dto.output; it[printerHost] = host; it[printerPort] = dto.printerPort
                it[paperMm] = dto.paperMm
                it[sortOrder] = if (dto.sortOrder != 0) dto.sortOrder
                    else (KitchenStations.selectAll().maxOfOrNull { r -> r[KitchenStations.sortOrder] } ?: -1) + 1
            }
        }
        stationRow(id)!!
    }

    /** Remove a station: its routes go too (those items then follow the default station). */
    fun deleteStation(id: String) = transaction {
        if (stationRow(id) == null) throw NotFoundException("station $id not found")
        KitchenStations.deleteWhere { KitchenStations.id eq id }
        KitchenRoutes.deleteWhere { KitchenRoutes.stationId eq id }
        if (KitchenConfigTable.get("default_station") == id) KitchenConfigTable.set("default_station", "")
    }

    /** Map a category or an item to a station; null [stationId] removes the mapping. "" = no ticket. */
    fun setRoute(kind: String, refId: String, stationId: String?) = transaction {
        require(kind == "category" || kind == "item") { "kind must be category or item" }
        require(refId.isNotBlank()) { "refId required" }
        KitchenRoutes.deleteWhere { (KitchenRoutes.kind eq kind) and (KitchenRoutes.refId eq refId) }
        if (stationId != null) {
            require(stationId.isEmpty() || stationRow(stationId) != null) { "unknown station" }
            KitchenRoutes.insert { it[KitchenRoutes.kind] = kind; it[KitchenRoutes.refId] = refId; it[KitchenRoutes.stationId] = stationId }
        }
    }

    fun setGuests(checkId: Int, guests: Int?): KitchenCheckState {
        require(guests == null || guests in 0..99) { "guests must be 0–99" }
        transaction {
            requireCheckRow(checkId)
            KitchenCheckInfo.deleteWhere { KitchenCheckInfo.checkId eq checkId }
            if (guests != null && guests > 0) KitchenCheckInfo.insert { it[KitchenCheckInfo.checkId] = checkId; it[KitchenCheckInfo.guests] = guests }
        }
        return state(checkId)
    }

    // ------------------------------------------------------------------ routing

    /** Where a station prints, live: its own host, or the receipt printer when blank. */
    fun targetFor(stationId: String): PrinterTarget {
        if (!printersEnabled) return PrinterTarget("", 9100)
        val station = transaction { stationRow(stationId) }
        val host = station?.printerHost.orEmpty()
        if (host.isNotEmpty()) return PrinterTarget(host, station!!.printerPort)
        val s = settings.get()
        return PrinterTarget(s.printerIp, s.printerPort)
    }

    private class Routing(val routes: Map<Pair<String, String>, String>, val default: String?, val known: Set<String>) {
        /** Item override, then its category, then the default station. Null = no ticket. */
        fun stationFor(itemId: String?, categoryId: String?): String? {
            val chosen = itemId?.let { routes["item" to it] }
                ?: categoryId?.let { routes["category" to it] }
                ?: default
            return chosen?.takeIf { it.isNotEmpty() && it in known }
        }
    }

    private fun routing(): Routing = Routing(
        KitchenRoutes.selectAll().associate { (it[KitchenRoutes.kind] to it[KitchenRoutes.refId]) to it[KitchenRoutes.stationId] },
        KitchenConfigTable.get("default_station")?.takeIf { it.isNotEmpty() },
        KitchenStations.selectAll().map { it[KitchenStations.id] }.toSet(),
    )

    /** Which station a menu item goes to (tests, the manager screen). */
    fun stationForItem(itemId: String): String? = transaction {
        val category = Items.selectAll().where { Items.id eq itemId }.firstOrNull()?.get(Items.categoryId)
        routing().stationFor(itemId, category)
    }

    // ------------------------------------------------------------------ the send diff

    private data class Snap(val nameFr: String, val nameEn: String, val variantFr: String?, val variantEn: String?, val note: String?) {
        fun item(qty: Int) = KitchenTicketItem(qty, nameFr, nameEn, variantFr, variantEn, note)
    }

    private data class Cur(val lineId: Int, val itemId: String?, val categoryId: String?, val qty: Int, val snap: Snap)
    private data class Sent(val lineId: Int, val checkId: Int, val stationId: String, val qty: Int, val snap: Snap)
    private data class Op(val stationId: String, val lineId: Int, val qty: Int, val snap: Snap, val void: Boolean)

    private sealed interface Change {
        data class Upsert(val lineId: Int, val stationId: String, val qty: Int, val snap: Snap) : Change
        data class Delete(val lineId: Int) : Change
        data class Repoint(val lineId: Int, val toCheckId: Int) : Change
    }

    private data class Plan(val ops: List<Op>, val changes: List<Change>)

    private fun requireCheckRow(checkId: Int) =
        Checks.selectAll().where { Checks.id eq checkId }.firstOrNull() ?: throw NotFoundException("check $checkId not found")

    private val liveStatuses = setOf("OPEN", "TOTAL_LOCKED", "CLOSED")

    /** The check's ACTIVE lines as the kitchen sees them (empty once voided or cancelled). */
    private fun currentLines(checkId: Int, status: String): List<Cur> {
        if (status !in liveStatuses) return emptyList()
        val variantCounts = ItemVariants.selectAll().where { ItemVariants.deletedAt.isNull() }
            .groupBy { it[ItemVariants.itemId] }.mapValues { it.value.size }
        return CheckLines
            .join(Items, JoinType.LEFT, CheckLines.itemId, Items.id)
            .join(ItemVariants, JoinType.LEFT, CheckLines.variantId, ItemVariants.id)
            .selectAll()
            .where { (CheckLines.checkId eq checkId) and (CheckLines.status eq "ACTIVE") }
            .orderBy(CheckLines.id to SortOrder.ASC)
            .map { row ->
                val itemId = row[CheckLines.itemId]
                val showVariant = (variantCounts[itemId] ?: 1) > 1
                val open = row[CheckLines.displayName]
                Cur(
                    lineId = row[CheckLines.id].value,
                    itemId = itemId,
                    categoryId = row.getOrNull(Items.categoryId),
                    qty = row[CheckLines.qty],
                    snap = Snap(
                        nameFr = row.getOrNull(Items.nameFr) ?: open ?: "?",
                        nameEn = row.getOrNull(Items.nameEn) ?: open ?: "?",
                        variantFr = if (showVariant) row.getOrNull(ItemVariants.labelFr) else null,
                        variantEn = if (showVariant) row.getOrNull(ItemVariants.labelEn) else null,
                        note = row[CheckLines.note]?.trim()?.takeIf { it.isNotEmpty() },
                    ),
                )
            }
    }

    private fun sentRow(r: org.jetbrains.exposed.sql.ResultRow) = Sent(
        r[KitchenSentLines.lineId], r[KitchenSentLines.checkId], r[KitchenSentLines.stationId], r[KitchenSentLines.qty],
        Snap(r[KitchenSentLines.nameFr], r[KitchenSentLines.nameEn], r[KitchenSentLines.variantFr],
            r[KitchenSentLines.variantEn], r[KitchenSentLines.note]),
    )

    private fun plan(checkId: Int): Plan {
        val check = requireCheckRow(checkId)
        val lines = currentLines(checkId, check[Checks.status])
        val ids = lines.map { it.lineId }
        val sent = KitchenSentLines.selectAll()
            .where { (KitchenSentLines.checkId eq checkId) or (KitchenSentLines.lineId inList ids) }
            .map(::sentRow).associateBy { it.lineId }
        val routing = routing()
        val ops = mutableListOf<Op>()
        val changes = mutableListOf<Change>()
        for (cur in lines) {
            val s = sent[cur.lineId]
            if (s == null) {
                val station = routing.stationFor(cur.itemId, cur.categoryId) ?: continue
                ops += Op(station, cur.lineId, cur.qty, cur.snap, void = false)
                changes += Change.Upsert(cur.lineId, station, cur.qty, cur.snap)
                continue
            }
            if (s.checkId != checkId) changes += Change.Repoint(cur.lineId, checkId) // merged in
            when {
                cur.snap != s.snap -> {
                    // a changed note (or name): the old one off, the new one on
                    ops += Op(s.stationId, cur.lineId, s.qty, s.snap, void = true)
                    ops += Op(s.stationId, cur.lineId, cur.qty, cur.snap, void = false)
                    changes += Change.Upsert(cur.lineId, s.stationId, cur.qty, cur.snap)
                }
                cur.qty > s.qty -> {
                    ops += Op(s.stationId, cur.lineId, cur.qty - s.qty, cur.snap, void = false)
                    changes += Change.Upsert(cur.lineId, s.stationId, cur.qty, cur.snap)
                }
                cur.qty < s.qty -> {
                    ops += Op(s.stationId, cur.lineId, s.qty - cur.qty, s.snap, void = true)
                    changes += Change.Upsert(cur.lineId, s.stationId, cur.qty, cur.snap)
                }
            }
        }
        val present = ids.toSet()
        for (s in sent.values) {
            if (s.lineId in present) continue
            // moved to another live check by a merge: not a void, it just follows the line
            val moved = CheckLines.join(Checks, JoinType.INNER, CheckLines.checkId, Checks.id)
                .selectAll().where { (CheckLines.id eq s.lineId) and (CheckLines.status eq "ACTIVE") }
                .firstOrNull()
            if (moved != null && moved[CheckLines.checkId] != checkId && moved[Checks.status] in liveStatuses) {
                if (s.checkId != moved[CheckLines.checkId]) changes += Change.Repoint(s.lineId, moved[CheckLines.checkId])
                continue
            }
            ops += Op(s.stationId, s.lineId, s.qty, s.snap, void = true)
            changes += Change.Delete(s.lineId)
        }
        return Plan(ops, changes)
    }

    /** What a send would do right now, without doing it (the Send button's count). */
    fun state(checkId: Int): KitchenCheckState = transaction {
        val p = plan(checkId)
        val sentQty = KitchenSentLines.selectAll().where { KitchenSentLines.checkId eq checkId }.sumOf { it[KitchenSentLines.qty] }
        KitchenCheckState(
            checkId = checkId,
            guests = guestsOf(checkId),
            unsent = p.ops.filter { !it.void }.sumOf { it.qty },
            pendingVoids = p.ops.filter { it.void }.sumOf { it.qty },
            sent = sentQty,
        )
    }

    private fun guestsOf(checkId: Int): Int? =
        KitchenCheckInfo.selectAll().where { KitchenCheckInfo.checkId eq checkId }.firstOrNull()?.get(KitchenCheckInfo.guests)

    /**
     * Send a check to its stations: one ticket per station with only that
     * station's new or changed items (ORDER the first time, ADD after), and a
     * VOID ticket for anything taken off. [senderName] fills "Server" when
     * the check was opened by a guest's phone.
     */
    fun send(checkId: Int, senderName: String? = null): KitchenSendResult {
        val result = synchronized(sendLock) {
            transaction {
                val p = plan(checkId)
                apply(checkId, p, senderName)
            }
        }
        if (result.printJobs > 0) queue.wake()
        return result
    }

    private data class Header(val tableLabel: String, val serverName: String, val guests: Int?)

    private fun header(checkId: Int, senderName: String?): Header {
        val check = requireCheckRow(checkId)
        val table = DiningTables.selectAll().where { DiningTables.id eq check[Checks.tableId] }.firstOrNull()
        val label = table?.let { it[DiningTables.nameOverride] ?: it[DiningTables.label] } ?: check[Checks.tableId]
        val opener = Users.selectAll().where { Users.id eq check[Checks.openedBy] }.firstOrNull()?.get(Users.name)
        return Header(label, opener ?: senderName ?: "—", guestsOf(checkId))
    }

    private fun apply(checkId: Int, p: Plan, senderName: String?): KitchenSendResult {
        val now = now()
        for (c in p.changes) when (c) {
            is Change.Upsert -> {
                KitchenSentLines.deleteWhere { KitchenSentLines.lineId eq c.lineId }
                if (c.qty > 0) KitchenSentLines.insert {
                    it[lineId] = c.lineId; it[KitchenSentLines.checkId] = checkId; it[stationId] = c.stationId
                    it[qty] = c.qty; it[nameFr] = c.snap.nameFr; it[nameEn] = c.snap.nameEn
                    it[variantFr] = c.snap.variantFr; it[variantEn] = c.snap.variantEn; it[note] = c.snap.note
                    it[updatedAt] = now
                }
            }
            is Change.Delete -> KitchenSentLines.deleteWhere { KitchenSentLines.lineId eq c.lineId }
            is Change.Repoint -> KitchenSentLines.update({ KitchenSentLines.lineId eq c.lineId }) {
                it[KitchenSentLines.checkId] = c.toCheckId
            }
        }
        if (p.ops.isEmpty()) return KitchenSendResult(0, 0, 0, emptyList())
        val head = header(checkId, senderName)
        var tickets = 0
        var jobs = 0
        val touched = linkedSetOf<String>()
        val order = stations().map { it.id }
        val byStation = p.ops.groupBy { it.stationId }.toSortedMap(compareBy({ order.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it }))
        for ((station, ops) in byStation) {
            touched += station
            // voids first: the kitchen stops before it starts the replacement
            val voids = ops.filter { it.void }
            if (voids.isNotEmpty()) {
                jobs += writeTicket(checkId, station, KitchenTicketKind.VOID, voids, head, now)
                tickets++
            }
            val adds = ops.filter { !it.void }
            if (adds.isNotEmpty()) {
                val before = KitchenTickets.selectAll().where {
                    (KitchenTickets.checkId eq checkId) and (KitchenTickets.stationId eq station) and
                        (KitchenTickets.kind inList listOf("ORDER", "ADD"))
                }.count() > 0
                jobs += writeTicket(checkId, station, if (before) KitchenTicketKind.ADD else KitchenTicketKind.ORDER, adds, head, now)
                tickets++
            }
        }
        return KitchenSendResult(tickets, jobs, p.ops.filter { it.void }.sumOf { it.qty }, touched.toList())
    }

    /** One ticket event: saved for the screen and history, queued for the printer. Returns print jobs added. */
    private fun writeTicket(checkId: Int, stationId: String, kind: KitchenTicketKind, ops: List<Op>, head: Header, now: Instant): Int {
        val station = stationRow(stationId) ?: KitchenStationDto(stationId, stationId, stationId)
        val screen = kind != KitchenTicketKind.REPRINT && station.output in setOf("screen", "both")
        val printer = station.output in setOf("printer", "both")
        val rowId = KitchenTickets.insertAndGetId {
            it[ticketId] = "kt-" + UUID.randomUUID().toString()
            it[KitchenTickets.checkId] = checkId
            it[KitchenTickets.stationId] = stationId
            it[KitchenTickets.kind] = kind.name
            it[tableLabel] = head.tableLabel.take(100)
            it[serverName] = head.serverName.take(100)
            it[guests] = head.guests
            it[onScreen] = if (screen) 1 else 0
            it[createdAt] = now
        }.value
        for (op in ops) KitchenTicketItems.insert {
            it[ticketId] = rowId; it[lineId] = op.lineId; it[qty] = op.qty
            it[nameFr] = op.snap.nameFr; it[nameEn] = op.snap.nameEn
            it[variantFr] = op.snap.variantFr; it[variantEn] = op.snap.variantEn; it[note] = op.snap.note
        }
        if (!printer) return 0
        val data = KitchenTicketData(
            kind = kind,
            stationNameFr = station.nameFr,
            stationNameEn = station.nameEn,
            tableLabel = head.tableLabel,
            serverName = head.serverName,
            checkId = checkId,
            sentAt = VenueClock.local(now),
            items = ops.map { it.snap.item(it.qty) },
            guests = head.guests,
            reference = "$checkId-$rowId",
        )
        queue.enqueue(stationId, KitchenTicketRenderer.render(data, language()), station.paperMm, rowId)
        return 1
    }

    /** Print again what each printer station already has for this check, marked REPRINT. */
    fun reprint(checkId: Int, senderName: String? = null): KitchenSendResult {
        val result = synchronized(sendLock) {
            transaction {
                requireCheckRow(checkId)
                val sent = KitchenSentLines.selectAll().where { KitchenSentLines.checkId eq checkId }
                    .orderBy(KitchenSentLines.lineId to SortOrder.ASC).map(::sentRow).filter { it.qty > 0 }
                if (sent.isEmpty()) return@transaction KitchenSendResult(0, 0, 0, emptyList())
                val head = header(checkId, senderName)
                val now = now()
                var tickets = 0
                var jobs = 0
                val touched = mutableListOf<String>()
                for ((station, lines) in sent.groupBy { it.stationId }) {
                    val output = stationRow(station)?.output ?: "printer"
                    if (output == "screen") continue // nothing to reprint on a screen
                    jobs += writeTicket(checkId, station, KitchenTicketKind.REPRINT,
                        lines.map { Op(station, it.lineId, it.qty, it.snap, void = false) }, head, now)
                    tickets++
                    touched += station
                }
                KitchenSendResult(tickets, jobs, 0, touched)
            }
        }
        if (result.printJobs > 0) queue.wake()
        return result
    }

    override fun checkEnded(checkId: Int) {
        try { send(checkId) } catch (e: Exception) { log.warn("kitchen void for check $checkId failed: ${e.message}") }
    }

    override fun checksMerged(sourceId: Int, destId: Int) {
        try {
            transaction {
                KitchenSentLines.update({ KitchenSentLines.checkId eq sourceId }) { it[checkId] = destId }
                // open screen cards follow the lines to the new table
                KitchenTickets.update({ (KitchenTickets.checkId eq sourceId) and KitchenTickets.bumpedAt.isNull() }) {
                    it[checkId] = destId
                }
            }
        } catch (e: Exception) {
            log.warn("kitchen merge $sourceId → $destId failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ station test print

    /** Print a station's test page now (not queued): the manager wants pass/fail at once. */
    fun testPrint(stationId: String): KitchenTestResult {
        val station = transaction { stationRow(stationId) } ?: throw NotFoundException("station $stationId not found")
        val target = targetFor(stationId)
        val where = if (target.configured) "${target.ip}:${target.port}" else ""
        if (!target.configured) return KitchenTestResult(false, false, where, "printer_not_configured")
        return try {
            val lines = KitchenTicketRenderer.testPage(station.nameFr, station.nameEn, where, station.paperMm, language())
            transport.send(target, renderBytes(lines, ThermalLayout.widthFor(station.paperMm)))
            KitchenTestResult(true, true, where)
        } catch (e: PaperOutException) {
            KitchenTestResult(false, true, where, "paper_out")
        } catch (e: Exception) {
            KitchenTestResult(false, true, where, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ queue status

    fun queueStatus(): KitchenQueueStatus = transaction {
        val stations = stations()
        val names = stations.associateBy { it.id }
        val pending = KitchenPrintJobs.join(KitchenTickets, JoinType.LEFT, KitchenPrintJobs.ticketId, KitchenTickets.id)
            .selectAll().where { KitchenPrintJobs.status eq "PENDING" }
            .orderBy(KitchenPrintJobs.id to SortOrder.ASC)
            .map {
                val sid = it[KitchenPrintJobs.stationId]
                KitchenJobView(
                    jobId = it[KitchenPrintJobs.jobId],
                    stationId = sid,
                    stationName = names[sid]?.let { s -> "${s.nameFr} / ${s.nameEn}" } ?: sid,
                    status = it[KitchenPrintJobs.status],
                    attempts = it[KitchenPrintJobs.attempts],
                    lastError = it[KitchenPrintJobs.lastError],
                    createdAt = VenueClock.iso(it[KitchenPrintJobs.createdAt]),
                    checkId = it.getOrNull(KitchenTickets.checkId),
                    kind = it.getOrNull(KitchenTickets.kind),
                )
            }
        val health = stations.filter { it.output != "screen" }.map { s ->
            val mine = pending.filter { it.stationId == s.id }
            val err = mine.firstOrNull { it.attempts > 0 }?.lastError
            KitchenStationHealth(s.id, s.nameFr, s.nameEn, mine.size, err,
                queue.lastOkAt(s.id)?.let(VenueClock::iso), online = err == null)
        }
        KitchenQueueStatus(
            enabled = true,
            waiting = pending.size,
            failing = pending.any { it.attempts > 0 },
            lastError = pending.firstOrNull { it.lastError != null }?.lastError,
            stations = health,
            jobs = pending.take(50),
        )
    }

    // ------------------------------------------------------------------ kitchen screen

    /**
     * The kitchen screen: one card per check per station for everything not yet
     * bumped. ADD items are marked, voided items struck through. [stationId]
     * null = every screen station.
     */
    fun board(stationId: String? = null): KdsBoard = transaction {
        val s = settings()
        val now = now()
        val stationList = stations()
        val stations = stationList.associateBy { it.id }
        val open = KitchenTickets.selectAll().where {
            (KitchenTickets.onScreen eq 1) and KitchenTickets.bumpedAt.isNull() and
                (if (stationId == null) KitchenTickets.onScreen eq 1 else KitchenTickets.stationId eq stationId)
        }.orderBy(KitchenTickets.id to SortOrder.ASC).toList()
        val ticketIds = open.map { it[KitchenTickets.id].value }
        val items = if (ticketIds.isEmpty()) emptyMap() else KitchenTicketItems.selectAll()
            .where { KitchenTicketItems.ticketId inList ticketIds }
            .orderBy(KitchenTicketItems.id to SortOrder.ASC)
            .groupBy { it[KitchenTicketItems.ticketId] }
        val cards = open.groupBy { it[KitchenTickets.checkId] to it[KitchenTickets.stationId] }.map { (key, tickets) ->
            val (checkId, sid) = key
            data class Acc(var ordered: Int = 0, var voided: Int = 0, var add: Boolean = false, var snap: org.jetbrains.exposed.sql.ResultRow? = null)
            val acc = linkedMapOf<Int, Acc>()
            for (t in tickets) {
                val kind = t[KitchenTickets.kind]
                for (i in items[t[KitchenTickets.id].value].orEmpty()) {
                    val a = acc.getOrPut(i[KitchenTicketItems.lineId]) { Acc() }
                    if (kind == "VOID") {
                        a.voided += i[KitchenTicketItems.qty]
                        if (a.snap == null) a.snap = i
                    } else {
                        a.ordered += i[KitchenTicketItems.qty]
                        if (kind == "ADD") a.add = true
                        a.snap = i // latest text wins (a changed note)
                    }
                }
            }
            val rows = acc.mapNotNull { (lineId, a) ->
                val snap = a.snap ?: return@mapNotNull null
                val qty = if (a.ordered > 0) a.ordered else a.voided
                val voidedQty = minOf(a.voided, qty)
                KdsItem(lineId, qty, snap[KitchenTicketItems.nameFr], snap[KitchenTicketItems.nameEn],
                    snap[KitchenTicketItems.variantFr], snap[KitchenTicketItems.variantEn], snap[KitchenTicketItems.note],
                    add = a.add, voided = voidedQty >= qty, voidedQty = voidedQty)
            }
            val first = tickets.minOf { it[KitchenTickets.createdAt] }
            val last = tickets.last()
            val elapsed = java.time.Duration.between(first, now).seconds.coerceAtLeast(0)
            val station = stations[sid]
            KdsCard(
                key = "$checkId:$sid",
                checkId = checkId,
                stationId = sid,
                stationNameFr = station?.nameFr ?: sid,
                stationNameEn = station?.nameEn ?: sid,
                tableLabel = last[KitchenTickets.tableLabel],
                serverName = last[KitchenTickets.serverName],
                guests = last[KitchenTickets.guests],
                firstSentAt = VenueClock.iso(first),
                elapsedSeconds = elapsed,
                level = timerLevel(elapsed, s.warnMinutes, s.lateMinutes),
                items = rows,
            )
        }.sortedBy { it.firstSentAt }
        val recent = KitchenTickets.selectAll().where {
            KitchenTickets.bumpId.isNotNull() and
                (if (stationId == null) KitchenTickets.onScreen eq 1 else KitchenTickets.stationId eq stationId)
        }.orderBy(KitchenTickets.bumpedAt to SortOrder.DESC).limit(200).toList()
            .distinctBy { it[KitchenTickets.bumpId] }.take(RECENT_BUMPS)
            .map { KdsBump(it[KitchenTickets.bumpId]!!, it[KitchenTickets.checkId], it[KitchenTickets.stationId],
                it[KitchenTickets.tableLabel], VenueClock.iso(it[KitchenTickets.bumpedAt]!!)) }
        KdsBoard(
            cards = cards,
            recent = recent,
            stations = stationList.filter { it.output != "printer" },
            warnMinutes = s.warnMinutes,
            lateMinutes = s.lateMinutes,
            sound = s.sound,
            language = language(s).wire,
            latestTicket = open.maxOfOrNull { it[KitchenTickets.id].value } ?: 0,
            serverTime = VenueClock.iso(now),
        )
    }

    fun timerLevel(elapsedSeconds: Long, warnMinutes: Int, lateMinutes: Int): String = when {
        elapsedSeconds >= lateMinutes * 60L -> "late"
        elapsedSeconds >= warnMinutes * 60L -> "warn"
        else -> "ok"
    }

    /** Done: the card (a check at a station) leaves the screen. Returns the bump id for Recall. */
    fun bump(checkId: Int, stationId: String): String = transaction {
        val id = "kb-" + UUID.randomUUID().toString()
        val n = KitchenTickets.update({
            (KitchenTickets.checkId eq checkId) and (KitchenTickets.stationId eq stationId) and
                (KitchenTickets.onScreen eq 1) and KitchenTickets.bumpedAt.isNull()
        }) {
            it[bumpedAt] = now()
            it[bumpId] = id
        }
        if (n == 0) throw NotFoundException("no open card for check $checkId at $stationId", "card_not_found")
        id
    }

    /** Undo a bump ([bumpId], or the latest when null): the card comes back. */
    fun recall(bumpId: String?, stationId: String? = null): Int = transaction {
        val id = bumpId ?: KitchenTickets.selectAll().where {
            KitchenTickets.bumpId.isNotNull() and KitchenTickets.bumpedAt.isNotNull() and
                (if (stationId == null) KitchenTickets.onScreen eq 1 else KitchenTickets.stationId eq stationId)
        }.orderBy(KitchenTickets.bumpedAt to SortOrder.DESC).limit(1).firstOrNull()?.get(KitchenTickets.bumpId)
            ?: throw NotFoundException("nothing to recall", "nothing_to_recall")
        val n = KitchenTickets.update({ KitchenTickets.bumpId eq id }) {
            it[bumpedAt] = null
            it[KitchenTickets.bumpId] = null
        }
        if (n == 0) throw NotFoundException("bump $id not found", "nothing_to_recall")
        n
    }
}
