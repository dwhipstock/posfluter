package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.Permissions
import dev.dwhipstock.pos.restaurant.DiningTables
import dev.dwhipstock.pos.restaurant.FloorObjects
import dev.dwhipstock.pos.restaurant.Zones
import dev.dwhipstock.pos.restaurant.CheckService
import dev.dwhipstock.pos.restaurant.CheckView
import dev.dwhipstock.pos.restaurant.LineView
import dev.dwhipstock.pos.restaurant.NotFoundException
import dev.dwhipstock.pos.restaurant.TableTokens
import dev.dwhipstock.pos.restaurant.TenderView
import dev.dwhipstock.pos.sdk.Outbox
import dev.dwhipstock.pos.sdk.TenderType
import dev.dwhipstock.pos.sdk.VenueClock
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

@Serializable
data class VariantDto(val id: String, val labelFr: String, val labelEn: String, val priceCents: Long)

@Serializable
data class CategoryDto(val id: String, val nameFr: String, val nameEn: String, val sortOrder: Int)

@Serializable
data class StaffDto(val id: String, val name: String, val role: String)

@Serializable
data class ItemDto(
    val id: String, val nameFr: String, val nameEn: String,
    val descriptionFr: String, val descriptionEn: String, val category: String,
    val abbrev: String, val isAlcohol: Boolean, val active: Boolean, val variants: List<VariantDto>,
    /** Cache-busting photo version (file mtime); null = no photo → tile shows the badge. */
    val photoVersion: Long? = null,
)

@Serializable
data class TableDto(
    val id: String, val label: String, val parentTableId: String?,
    val nameOverride: String?, val openCheckId: Int?, val openCheckStatus: String?,
    val pendingCount: Int = 0,
    /** ISO timestamp of the oldest un-actioned PENDING line — drives the age on
     *  the terminal's escalation banner. Null when nothing is pending. */
    val oldestPendingAt: String? = null,
    /** For the tables-screen card: running total + elapsed time of the open check. */
    val openCheckTotalCents: Long? = null,
    val openCheckOpenedAt: String? = null,
    // floor-plan geometry: logical units on a 0–1000 canvas per zone
    val x: Int = 0, val y: Int = 0,
    val width: Int = 100, val height: Int = 100,
    val rotation: Int = 0, val shape: String = "SQUARE", val seats: Int = 4,
    /** Customer link path "/m/t/{token}" for the on-screen QR (staff-only view). */
    val menuPath: String? = null,
)

@Serializable
data class SubmitPendingRequest(val lines: List<dev.dwhipstock.pos.restaurant.PendingLineRequest>)

/**
 * Customer-facing bill line: display fields only — no line/item ids, no unit
 * costs, no staff data. Everything a guest's phone renders comes from here.
 */
@Serializable
data class CustomerBillLineDto(
    val nameFr: String, val nameEn: String,
    val variantLabelFr: String? = null, val variantLabelEn: String? = null,
    val qty: Int, val lineTotalCents: Long, val note: String? = null,
)

@Serializable
data class CustomerBillFeeDto(val labelFr: String, val labelEn: String, val amountCents: Long)

/** A tax added on top of the guest's subtotal: label pair, rate ("9.975") and amount. */
@Serializable
data class CustomerBillTaxDto(val labelFr: String, val labelEn: String, val ratePercent: String, val amountCents: Long)

/**
 * The running bill a guest sees at /m/{tableId}/bill. Strictly the table's
 * CURRENT open check — `open=false` (all else empty) when there is none; a
 * closed/previous party's check is never served. Totals are the server
 * pipeline's, exactly like the printed bill: subtotal, the taxes added on
 * top, then the total.
 */
@Serializable
data class CustomerBillDto(
    val open: Boolean,
    /** True once payment started (TOTAL_LOCKED) — the total won't change anymore. */
    val locked: Boolean = false,
    val lines: List<CustomerBillLineDto> = emptyList(),
    val fees: List<CustomerBillFeeDto> = emptyList(),
    /** Customer-submitted, awaiting staff confirmation. Not in the total. */
    val pendingLines: List<CustomerBillLineDto> = emptyList(),
    val grandTotalCents: Long = 0,
    /** Pre-tax subtotal; subtotalCents + sum(taxes) = grandTotalCents. */
    val subtotalCents: Long = 0,
    val taxes: List<CustomerBillTaxDto> = emptyList(),
)

/**
 * Inert structural prop on the floor plan (pool table / bar front / pillar).
 * Renders as quiet background context — never orderable, never a check, no status.
 */
@Serializable
data class FloorObjectDto(
    val id: String, val type: String,
    val x: Int, val y: Int, val width: Int, val height: Int,
    val rotation: Int, val label: String? = null,
)

@Serializable
data class ZoneDto(
    val id: String, val nameFr: String, val nameEn: String,
    /** OPEN | CLOSED — a CLOSED zone blocks new checks and hides the customer menu. */
    val status: String, val tables: List<TableDto>,
    /** Structural props (pool/bar/pillar) rendered beneath the tables. */
    val objects: List<FloorObjectDto> = emptyList(),
    /** Table-label prefix (U/O/B/L): every table here is labelled "{prefix}-{n}". */
    val labelPrefix: String = "",
)

@Serializable
data class ZoneStatusRequest(val status: String, val managerPin: String? = null)

@Serializable
data class AddLineRequest(val itemId: String, val variantId: String, val qty: Int = 1, val note: String? = null)

@Serializable
data class AddOpenLineRequest(val name: String, val unitPriceCents: Long, val qty: Int = 1, val note: String? = null)

@Serializable
data class CorkageRequest(val bottles: Int)

@Serializable
data class TenderRequest(val type: String = "CASH", val amountTenderedCents: Long, val groupId: Int? = null)

@Serializable
data class InitiateTenderRequest(val type: String, val amountCents: Long? = null, val groupId: Int? = null)

@Serializable
data class ConfirmTenderRequest(val type: String, val amountCents: Long, val groupId: Int? = null)

@Serializable
data class CreateSplitRequest(val groups: Int = 2, val even: Boolean = false)

@Serializable
data class AssignLineRequest(val lineId: Int, val qty: Int = 1)

@Serializable
data class UnassignQtyRequest(val qty: Int = 1)

@Serializable
data class MoveCorkageRequest(val groupId: Int)

@Serializable
data class MoveCheckRequest(val tableId: String)

@Serializable
data class MergeCheckRequest(val intoCheckId: Int)

@Serializable
data class ReceiptResponse(val checkId: Int, val text: String)

@Serializable
data class OpenShiftRequest(val openingFloatCents: Long, val managerPin: String? = null)

@Serializable
data class CloseShiftRequest(val closingCountCents: Long, val managerPin: String? = null)

@Serializable
data class VoidRequest(val reason: String, val managerPin: String? = null)

@Serializable
data class RefundRequest(
    /** Refund a flat amount (cents). Ignored when [lines] is non-empty. */
    val amountCents: Long? = null,
    /** Refund specific lines/quantities (by-line refund). Takes precedence over [amountCents]. */
    val lines: List<dev.dwhipstock.pos.restaurant.RefundLineRequest>? = null,
    val tenderType: String = "CASH", // CASH | CARD | BANK_TRANSFER | STRIPE
    val reason: String,
    val managerPin: String? = null,
)

@Serializable
data class CashMovementRequest(
    val direction: String, // IN | OUT
    val amountCents: Long,
    val reason: String,
    val managerPin: String? = null,
)

@Serializable
data class AvailabilityRequest(val active: Boolean, val managerPin: String? = null)

@Serializable
data class TenderResponse(val tender: TenderView, val check: CheckView)

/**
 * Customer-facing scan-to-order routes. Unauthenticated by design — they run on
 * guests' phones — so every one resolves the table from its random token
 * (`/m/t/{token}`, see [TableTokens]); nothing a guest can type (a table id, a
 * zone + number) reaches a table.
 */
fun Route.customerRoutes(checkService: CheckService, config: dev.dwhipstock.pos.sdk.CustomerConfig) {

    get("/m/t/{token}") {
        val tableId = customerTable(call) ?: return@get scanAtTable(call, config.displayName)
        serveCustomerMenu(call, tableId, call.parameters["token"]!!, config.displayName)
    }

    // Running bill for the guest's phone. It exposes only what a printed
    // provisional bill on that table would show.
    get("/m/t/{token}/bill") {
        val tableId = customerTable(call)
            ?: throw NotFoundException("unknown table link", "table_link_invalid")
        call.respond(customerBill(checkService.openCheckForTable(tableId)))
    }

    post("/m/t/{token}/pending-lines") {
        val tableId = customerTable(call)
            ?: throw NotFoundException("unknown table link", "table_link_invalid")
        val req = call.receive<SubmitPendingRequest>()
        call.respond(HttpStatusCode.Created, checkService.submitPendingLines(tableId, req.lines))
    }

    // Every other /m/... (the retired /m/{tableId}, /m/{zone}/{n} and
    // /m/{tableId}/bill forms, a typo, an edited number) is a calm 404 page.
    get("/m/{...}") { scanAtTable(call, config.displayName) }

    /**
     * Printable slips: A6 card per table — label, zone, QR, "scan to order" in
     * FR+EN. Opened in the tablet's browser with a short-lived slip ticket
     * (they carry every table's link, so they are not public).
     */
    get("/tables/{tableId}/slip") {
        SlipTickets.require(call)
        val tableId = call.parameters["tableId"]!!
        val slip = transaction {
            DiningTables.join(Zones, org.jetbrains.exposed.sql.JoinType.INNER,
                    DiningTables.zoneId, Zones.id)
                .selectAll()
                .where { (DiningTables.id eq tableId) and DiningTables.deletedAt.isNull() }
                .firstOrNull()?.let { SlipData(it, config.publicBaseUrl) }
        } ?: throw NotFoundException("table $tableId not found")
        call.respondText(slipPage(listOf(slip), config.displayName), ContentType.Text.Html)
    }

    /** All tables on one printable page, page break per slip ("print all table slips"). */
    get("/slips") {
        SlipTickets.require(call)
        val slips = transaction {
            DiningTables.join(Zones, org.jetbrains.exposed.sql.JoinType.INNER,
                    DiningTables.zoneId, Zones.id)
                .selectAll().where { DiningTables.deletedAt.isNull() }
                .orderBy(Zones.sortOrder).orderBy(DiningTables.sortOrder)
                .map { SlipData(it, config.publicBaseUrl) }
        }
        call.respondText(slipPage(slips, config.displayName), ContentType.Text.Html)
    }
}

/**
 * Staff-side table link management (authenticated): the table QR PNG, the slip
 * ticket for the printable pages, and rotating a table's customer link.
 */
fun Route.tableLinkRoutes(config: dev.dwhipstock.pos.sdk.CustomerConfig) {

    /** Table QR: payload is the table's tokenised public menu URL. */
    get("/tables/{tableId}/qr") {
        val tableId = call.parameters["tableId"]!!
        val url = transaction { liveTableToken(tableId) }
            ?.let { config.publicBaseUrl + TableTokens.menuPath(it) }
            ?: throw NotFoundException("table $tableId not found")
        call.respondBytes(qrPng(url, 512), ContentType.Image.PNG)
    }

    /** A short-lived ticket so the tablet's browser can open /slips and /tables/{id}/slip. */
    post("/slips/ticket") {
        call.sessionUser()
        call.respond(SlipTickets.issue())
    }

    /**
     * "Regenerate table link": a new random token, so every slip already
     * printed for this table stops working. Manager-only; reprint the slip.
     */
    post("/tables/{tableId}/link/regenerate") {
        requireManagerSession(call)
        val tableId = call.parameters["tableId"]!!
        val token = transaction {
            liveTableToken(tableId) ?: throw NotFoundException("table $tableId not found")
            val token = TableTokens.rotate(tableId)
            // audit only the fact; the token itself never leaves the store
            Outbox.write("table.link_regenerated", "table", tableId, buildJsonObject {
                put("tableId", tableId)
            })
            token
        }
        call.respond(TableLinkDto(tableId, TableTokens.menuPath(token)))
    }
}

@Serializable
data class TableLinkDto(val tableId: String, val menuPath: String)

@Serializable
data class SlipTicketDto(val ticket: String, val expiresInSeconds: Long)

/**
 * Short-lived, reusable tickets for the printable slip pages, which the tablet
 * opens in a browser that has no bearer token. In memory only: a restart just
 * means tapping "Print slips" again.
 */
object SlipTickets {
    private const val TTL_SECONDS = 600L
    private val tickets = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun issue(): SlipTicketDto {
        val now = System.currentTimeMillis()
        tickets.entries.removeIf { it.value < now }
        val ticket = TableTokens.newToken()
        tickets[ticket] = now + TTL_SECONDS * 1000
        return SlipTicketDto(ticket, TTL_SECONDS)
    }

    fun valid(ticket: String?): Boolean =
        ticket != null && (tickets[ticket] ?: 0L) >= System.currentTimeMillis()

    /** 401 unless the request carries a live `?ticket=`. */
    fun require(call: io.ktor.server.application.ApplicationCall) {
        if (!valid(call.request.queryParameters["ticket"])) throw SlipTicketException()
    }
}

class SlipTicketException : RuntimeException("slip ticket required")

/** The live table's token, or null if there is no such live table. Call in a transaction. */
internal fun liveTableToken(tableId: String): String? =
    DiningTables.selectAll()
        .where { (DiningTables.id eq tableId) and DiningTables.deletedAt.isNull() }
        .firstOrNull()?.get(DiningTables.publicToken)

/** Resolve `{token}` to a live table id, or null. */
private fun customerTable(call: io.ktor.server.application.ApplicationCall): String? =
    transaction { TableTokens.tableIdFor(call.parameters["token"] ?: "") }

private fun qrPng(data: String, size: Int): ByteArray {
    val matrix = com.google.zxing.MultiFormatWriter()
        .encode(data, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
    return dev.dwhipstock.pos.sdk.QrPng.encode(matrix)
}

/**
 * The page a guest sees for any link that isn't a live table token: an old or
 * rotated slip, an edited number, a typed id. 404, but calm and bilingual.
 */
private suspend fun scanAtTable(call: io.ktor.server.application.ApplicationCall, venueName: String) {
    call.respondText(scanAtTablePage(venueName), ContentType.Text.Html, HttpStatusCode.NotFound)
}

fun Route.shiftRoutes(shiftService: dev.dwhipstock.pos.restaurant.ShiftService, auth: dev.dwhipstock.pos.base.AuthService) {

    get("/shifts/current") {
        shiftService.currentShift()?.let { call.respond(it) }
            ?: call.respond(HttpStatusCode.NotFound, mapOf("error" to "no open shift"))
    }

    post("/shifts") {
        val req = call.receive<OpenShiftRequest>()
        requireGrant(auth, call, Permissions.OPEN_SHIFT, req.managerPin)
        call.respond(HttpStatusCode.Created,
            shiftService.openShift(call.sessionUser().userId, req.openingFloatCents))
    }

    get("/shifts/current/report") { call.respond(shiftService.xReport()) }

    // Tablet date presets must use the venue's business day, not Android's
    // timezone (which may reflect the tablet's physical location).
    get("/reports/today") { call.respond(mapOf("date" to VenueClock.today().toString())) }

    /** X-report layout over closed-at dates (?from=YYYY-MM-DD&to=YYYY-MM-DD, inclusive). */
    get("/reports/range") {
        val from = call.request.queryParameters["from"]
            ?: throw IllegalArgumentException("from required (YYYY-MM-DD)")
        val to = call.request.queryParameters["to"] ?: from
        val fromDate = runCatching { java.time.LocalDate.parse(from) }
            .getOrElse { throw IllegalArgumentException("invalid from date") }
        val toDate = runCatching { java.time.LocalDate.parse(to) }
            .getOrElse { throw IllegalArgumentException("invalid to date") }
        call.respond(shiftService.rangeReport(fromDate, toDate))
    }

    post("/shifts/current/close") {
        val req = call.receive<CloseShiftRequest>()
        requireGrant(auth, call, Permissions.CLOSE_SHIFT, req.managerPin)
        call.respond(shiftService.closeShift(call.sessionUser().userId, req.closingCountCents))
    }

    // Till: non-sale cash movements against the open shift. Manager-gated.
    get("/cash-movements") {
        call.respond(shiftService.currentShiftCashMovements())
    }
    post("/cash-movements") {
        val req = call.receive<CashMovementRequest>()
        val approverId = requireGrant(auth, call, Permissions.CASH_MOVEMENT, req.managerPin)
        call.respond(HttpStatusCode.Created,
            shiftService.recordCashMovement(req.direction, req.amountCents, req.reason, approverId))
    }
}

fun Route.posRoutes(
    checkService: CheckService,
    auth: dev.dwhipstock.pos.base.AuthService,
    photos: dev.dwhipstock.pos.sdk.PhotoStore,
    stripe: dev.dwhipstock.pos.payments.StripeService? = null,
) {

    post("/checks/{id}/pending-lines/{lineId}/accept") {
        call.respond(checkService.acceptPendingLine(checkId(call), lineIdParam(call)))
    }

    post("/checks/{id}/pending-lines/{lineId}/reject") {
        call.respond(checkService.rejectPendingLine(checkId(call), lineIdParam(call)))
    }

    get("/items") {
        // ?all=true includes 86'ed items (menu-management view)
        val includeInactive = call.request.queryParameters["all"] == "true"
        val items = transaction {
            val variantsByItem = ItemVariants.selectAll()
                .where { ItemVariants.deletedAt.isNull() }
                .orderBy(ItemVariants.sortOrder)
                .groupBy({ it[ItemVariants.itemId] }) {
                    VariantDto(it[ItemVariants.id], it[ItemVariants.labelFr], it[ItemVariants.labelEn], it[ItemVariants.priceCents])
                }
            // deleted items never list; ?all=true additionally shows 86'ed ones
            val query = if (includeInactive) Items.selectAll().where { Items.deletedAt.isNull() }
            else Items.selectAll().where { (Items.active eq true) and (Items.deletedAt.isNull()) }
            query.map {
                ItemDto(
                    it[Items.id], it[Items.nameFr], it[Items.nameEn],
                    it[Items.descriptionFr], it[Items.descriptionEn], it[Items.categoryId],
                    it[Items.abbrev], it[Items.isAlcohol], it[Items.active],
                    variantsByItem[it[Items.id]] ?: emptyList(),
                    photoVersion = it[Items.photoPath]?.let { _ -> photos.version(it[Items.id]) },
                )
            }
        }
        call.respond(items)
    }

    // open route: the customer menu page renders category chips from this too
    get("/categories") {
        val categories = transaction {
            dev.dwhipstock.pos.base.Categories.selectAll()
                .orderBy(dev.dwhipstock.pos.base.Categories.sortOrder).map {
                    CategoryDto(
                        it[dev.dwhipstock.pos.base.Categories.id],
                        it[dev.dwhipstock.pos.base.Categories.nameFr],
                        it[dev.dwhipstock.pos.base.Categories.nameEn],
                        it[dev.dwhipstock.pos.base.Categories.sortOrder],
                    )
                }
        }
        call.respond(categories)
    }

    get("/zones") {
        val zones = transaction {
            val tablesByZone = DiningTables.selectAll()
                .where { DiningTables.deletedAt.isNull() }
                .orderBy(DiningTables.sortOrder)
                .groupBy({ it[DiningTables.zoneId] }) { row ->
                    val open = checkService.openCheckForTable(row[DiningTables.id])
                    TableDto(
                        row[DiningTables.id], row[DiningTables.label], row[DiningTables.parentTableId],
                        row[DiningTables.nameOverride], open?.id, open?.status,
                        pendingCount = open?.pendingLines?.size ?: 0,
                        oldestPendingAt = open?.id?.let { checkService.oldestPendingAt(it) },
                        openCheckTotalCents = open?.grandTotalCents,
                        openCheckOpenedAt = open?.openedAt,
                        x = row[DiningTables.x], y = row[DiningTables.y],
                        width = row[DiningTables.width], height = row[DiningTables.height],
                        rotation = row[DiningTables.rotation], shape = row[DiningTables.shape],
                        seats = row[DiningTables.seats],
                        menuPath = row[DiningTables.publicToken]?.let(TableTokens::menuPath),
                    )
                }
            val objectsByZone = FloorObjects.selectAll()
                .groupBy({ o -> o[FloorObjects.zoneId] }) { o ->
                    FloorObjectDto(
                        o[FloorObjects.id], o[FloorObjects.type],
                        o[FloorObjects.x], o[FloorObjects.y],
                        o[FloorObjects.width], o[FloorObjects.height],
                        o[FloorObjects.rotation], o[FloorObjects.label],
                    )
                }
            Zones.selectAll().orderBy(Zones.sortOrder).map {
                ZoneDto(it[Zones.id], it[Zones.nameFr], it[Zones.nameEn], it[Zones.status],
                    tablesByZone[it[Zones.id]] ?: emptyList(),
                    objectsByZone[it[Zones.id]] ?: emptyList(),
                    labelPrefix = it[Zones.labelPrefix])
            }
        }
        call.respond(zones)
    }

    // Close/reopen a zone (rain, unstaffed section, reset). Inline manager approval
    // like void/86 — a manager PINs it on the staff member's screen.
    patch("/zones/{zoneId}/status") {
        val zoneId = call.parameters["zoneId"]!!
        val req = call.receive<ZoneStatusRequest>()
        requireGrant(auth, call, Permissions.ZONE_OPEN_CLOSE, req.managerPin)
        require(req.status in setOf("OPEN", "CLOSED")) { "status must be OPEN or CLOSED" }
        val result = transaction {
            val zone = Zones.selectAll().where { Zones.id eq zoneId }.firstOrNull()
                ?: throw NotFoundException("zone $zoneId not found")
            val previous = zone[Zones.status]
            if (previous != req.status) {
                Zones.update({ Zones.id eq zoneId }) { it[status] = req.status }
                Outbox.write("zone.status_changed", "zone", zoneId, buildJsonObject {
                    put("zoneId", zoneId)
                    put("previous", previous)
                    put("status", req.status)
                })
            }
            mapOf("zoneId" to zoneId, "status" to req.status)
        }
        call.respond(result)
    }

    post("/tables/{tableId}/checks") {
        val tableId = call.parameters["tableId"]!!
        // the session user owns the check — receipt language keys off this
        call.respond(HttpStatusCode.Created, checkService.openCheck(tableId, call.sessionUser().userId))
    }

    get("/checks/{id}") {
        call.respond(checkService.getCheck(checkId(call)))
    }

    post("/checks/{id}/lines") {
        val req = call.receive<AddLineRequest>()
        call.respond(HttpStatusCode.Created, checkService.addLine(checkId(call), req.itemId, req.variantId, req.qty, req.note))
    }

    // Open / misc item: off-menu line rung as name + price + qty. No catalog
    // row, no manager gate in v1 — check.line_open_added is the audit trail.
    post("/checks/{id}/open-lines") {
        val req = call.receive<AddOpenLineRequest>()
        call.respond(HttpStatusCode.Created,
            checkService.addOpenLine(checkId(call), req.name, req.unitPriceCents, req.qty, req.note))
    }

    delete("/checks/{id}/lines/{lineId}") {
        call.respond(checkService.removeLine(checkId(call), lineIdParam(call)))
    }

    post("/checks/{id}/lines/{lineId}/qty") {
        val qty = call.receive<Map<String, Int>>()["qty"] ?: throw IllegalArgumentException("qty required")
        call.respond(checkService.setLineQty(checkId(call), lineIdParam(call), qty))
    }

    post("/checks/{id}/corkage") {
        val req = call.receive<CorkageRequest>()
        call.respond(checkService.setCorkage(checkId(call), req.bottles))
    }

    post("/checks/{id}/tenders") {
        val req = call.receive<TenderRequest>()
        require(req.type == "CASH") { "use /tenders/initiate + /tenders/confirm for electronic tenders" }
        val id = checkId(call)
        val tender = checkService.tenderCash(id, req.amountTenderedCents, req.groupId)
        call.respond(HttpStatusCode.Created, TenderResponse(tender, checkService.getCheck(id)))
    }

    // Electronic tenders: confirm-then-record. Initiate returns payment
    // instructions (card terminal / bank account details); staff confirms
    // after seeing the money land, and only the confirm creates a tender row.
    post("/checks/{id}/tenders/initiate") {
        val req = call.receive<InitiateTenderRequest>()
        val type = tenderType(req.type)
        call.respond(checkService.initiateElectronicTender(checkId(call), type, req.amountCents, req.groupId))
    }

    post("/checks/{id}/tenders/confirm") {
        val req = call.receive<ConfirmTenderRequest>()
        val type = tenderType(req.type)
        val id = checkId(call)
        val tender = checkService.confirmElectronicTender(id, type, req.amountCents, req.groupId)
        call.respond(HttpStatusCode.Created, TenderResponse(tender, checkService.getCheck(id)))
    }

    // --- settlement-time split: bill groups. The check stays the single
    // system-of-record; groups partition its lines for per-group bills/tenders.
    post("/checks/{id}/split") {
        val req = call.receive<CreateSplitRequest>()
        val id = checkId(call)
        call.respond(HttpStatusCode.Created,
            if (req.even) checkService.createEvenSplit(id, req.groups)
            else checkService.createSplit(id, req.groups))
    }

    delete("/checks/{id}/split") {
        call.respond(checkService.clearSplit(checkId(call)))
    }

    post("/checks/{id}/split/groups") {
        call.respond(HttpStatusCode.Created, checkService.addSplitGroup(checkId(call)))
    }

    delete("/checks/{id}/split/groups/{groupId}") {
        call.respond(checkService.deleteSplitGroup(checkId(call), groupIdParam(call)))
    }

    post("/checks/{id}/split/groups/{groupId}/lines") {
        val req = call.receive<AssignLineRequest>()
        call.respond(checkService.assignLineToGroup(checkId(call), groupIdParam(call), req.lineId, req.qty))
    }

    post("/checks/{id}/split/groups/{groupId}/lines/{lineId}/unassign") {
        val req = call.receive<UnassignQtyRequest>()
        call.respond(checkService.unassignLineFromGroup(
            checkId(call), groupIdParam(call), lineIdParam(call), req.qty))
    }

    post("/checks/{id}/split/corkage") {
        val req = call.receive<MoveCorkageRequest>()
        call.respond(checkService.moveCorkage(checkId(call), req.groupId))
    }

    get("/checks/{id}/receipt") {
        val id = checkId(call)
        call.respond(ReceiptResponse(id, checkService.receiptText(id)))
    }

    // "Check please": render + spool a provisional customer bill. Non-mutating —
    // the check stays OPEN, so staff can reprint after adding/removing items.
    // ?groupId=N prints one bill group of a split check.
    post("/checks/{id}/bill") {
        val id = checkId(call)
        val groupId = call.request.queryParameters["groupId"]?.let {
            it.toIntOrNull() ?: throw IllegalArgumentException("invalid groupId")
        }
        call.respond(ReceiptResponse(id, checkService.printBill(id, groupId)))
    }

    // Table ops: one staff gesture — pick a destination table. Empty → move
    // (this endpoint); occupied → merge into its open check (endpoint below).
    post("/checks/{id}/move") {
        val req = call.receive<MoveCheckRequest>()
        call.respond(checkService.moveCheck(checkId(call), req.tableId))
    }

    post("/checks/{id}/merge") {
        val req = call.receive<MergeCheckRequest>()
        call.respond(checkService.mergeCheck(checkId(call), req.intoCheckId))
    }

    post("/checks/{id}/finalize") {
        call.respond(checkService.finalizeCheck(checkId(call)))
    }

    post("/checks/{id}/void") {
        val req = call.receive<VoidRequest>()
        // grant gate (CONTRACT §7): the acting user's `void` grant, else an inline
        // manager-PIN override collected on the staff screen. approverId = whoever authorized.
        val approverId = requireGrant(auth, call, Permissions.VOID, req.managerPin)
        call.respond(checkService.voidCheck(checkId(call), req.reason, approverId))
    }

    // Refunds: return money on a finalized check. The picker lists recent CLOSED
    // checks; the refund screen reads a check's refund state, then posts a refund.
    // Constant path beats the {id} parameter route, so /checks/recent resolves here.
    get("/checks/recent") {
        call.respond(checkService.recentClosedChecks())
    }
    get("/checks/{id}/refunds") {
        call.respond(checkService.refundInfo(checkId(call)))
    }
    post("/checks/{id}/refund") {
        val req = call.receive<RefundRequest>()
        val approverId = requireGrant(auth, call, Permissions.REFUND, req.managerPin)
        // back to the Stripe card: refunded AT Stripe first, recorded only if Stripe did it
        if (req.tenderType == TenderType.STRIPE.name && stripe != null) {
            val id = checkId(call)
            call.respond(HttpStatusCode.Created, onIo { stripe.refund(id, req.amountCents, req.lines, req.reason, approverId) })
            return@post
        }
        call.respond(HttpStatusCode.Created, checkService.refundCheck(
            checkId(call), req.amountCents, req.lines, req.tenderType, req.reason, approverId))
    }

    // 86'ing: item disappears from ordering (GET /items filters active).
    // TODO: move to a base-tier CatalogService when catalog logic grows.
    post("/items/{itemId}/availability") {
        val itemId = call.parameters["itemId"]!!
        val req = call.receive<AvailabilityRequest>()
        requireGrant(auth, call, Permissions.EDIT_MENU, req.managerPin)
        val updated = transaction {
            val count = Items.update({ Items.id eq itemId }) { it[active] = req.active }
            if (count == 0) throw NotFoundException("item $itemId not found")
            Outbox.write("item.availability_changed", "item", itemId, buildJsonObject {
                put("itemId", itemId)
                put("active", req.active)
                put("item", itemSnapshotJson(itemId))
            })
            mapOf("itemId" to itemId, "active" to req.active.toString())
        }
        call.respond(updated)
    }
}

/**
 * Customer-facing "this section is closed" page for a scanned QR on a CLOSED zone.
 * 200, not a 4xx — a guest scanned a code; keep it calm. Both languages inline so
 * the server does no localization (matches the slip pages).
 */
private fun zoneClosedMenuPage(tableLabel: String, venueName: String): String = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${venueName.escapeHtml()}</title>
<style>
  * { box-sizing: border-box; margin: 0; font-family: 'Noto Sans', system-ui, sans-serif; }
  body { background: #F3F7FC; color: #17263A; min-height: 100vh; display: flex;
         align-items: center; justify-content: center; padding: 24px; }
  .card { max-width: 420px; text-align: center; background: #FFFFFF; border: 1px solid #C8D5E6;
          border-radius: 16px; padding: 36px 28px; box-shadow: 0 8px 30px rgba(32,78,128,.08); }
  .icon { font-size: 44px; margin-bottom: 16px; }
  .table { font-size: 13px; color: #1565C0; letter-spacing: .08em; text-transform: uppercase; margin-bottom: 8px; }
  .fr { font-size: 22px; font-weight: 700; line-height: 1.5; }
  .en { font-size: 16px; color: #5B6D82; margin-top: 10px; line-height: 1.5; }
</style>
</head>
<body>
  <div class="card">
    <div class="icon">🌧️</div>
    <div class="table">${tableLabel.escapeHtml()}</div>
    <div class="fr">Cette zone est temporairement fermée.<br>Veuillez contacter le personnel.</div>
    <div class="en">This section is temporarily closed.<br>Please ask staff to help.</div>
  </div>
</body>
</html>"""

/** "Please scan the code at your table" — for any link that isn't a live table token. */
private fun scanAtTablePage(venueName: String): String = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${venueName.escapeHtml()}</title>
<style>
  * { box-sizing: border-box; margin: 0; font-family: 'Noto Sans', system-ui, sans-serif; }
  body { background: #F3F7FC; color: #17263A; min-height: 100vh; display: flex;
         align-items: center; justify-content: center; padding: 24px; }
  .card { max-width: 420px; text-align: center; background: #FFFFFF; border: 1px solid #C8D5E6;
          border-radius: 16px; padding: 36px 28px; box-shadow: 0 8px 30px rgba(32,78,128,.08); }
  .venue { font-size: 13px; color: #1565C0; letter-spacing: .08em; text-transform: uppercase; margin-bottom: 8px; }
  .fr { font-size: 22px; font-weight: 700; line-height: 1.5; }
  .en { font-size: 16px; color: #5B6D82; margin-top: 10px; line-height: 1.5; }
</style>
</head>
<body>
  <div class="card">
    <div class="venue">${venueName.escapeHtml()}</div>
    <div class="fr">Veuillez scanner le code QR sur votre table.</div>
    <div class="en">Please scan the QR code at your table.</div>
  </div>
</body>
</html>"""

/** Customer-safe projection of a CheckView; null check (no open check) → explicit empty state. */
private fun customerBill(check: CheckView?): CustomerBillDto {
    if (check == null) return CustomerBillDto(open = false)
    fun line(l: LineView) = CustomerBillLineDto(
        nameFr = l.nameFr, nameEn = l.nameEn,
        variantLabelFr = l.variantLabelFr, variantLabelEn = l.variantLabelEn,
        qty = l.qty, lineTotalCents = l.lineTotalCents, note = l.note,
    )
    return CustomerBillDto(
        open = true,
        locked = check.status == "TOTAL_LOCKED",
        lines = check.lines.map(::line),
        fees = check.fees.map { CustomerBillFeeDto(it.labelFr, it.labelEn, it.amountCents) },
        pendingLines = check.pendingLines.map(::line),
        grandTotalCents = check.grandTotalCents,
        subtotalCents = check.subtotalCents,
        taxes = check.taxes.map { CustomerBillTaxDto(it.labelFr, it.labelEn, it.ratePercent, it.amountCents) },
    )
}

/**
 * Render the customer menu for a table resolved from its [token]. The page keys
 * its own API calls (bill, pending) off the same token; the internal table id
 * never reaches the guest's phone.
 */
private suspend fun serveCustomerMenu(
    call: io.ktor.server.application.ApplicationCall, tableId: String, token: String, venueName: String,
) {
    val row = transaction {
        DiningTables.join(Zones, org.jetbrains.exposed.sql.JoinType.INNER,
                DiningTables.zoneId, Zones.id)
            .selectAll()
            .where { (DiningTables.id eq tableId) and DiningTables.deletedAt.isNull() }
            .firstOrNull()
            ?.let { (it[DiningTables.nameOverride] ?: it[DiningTables.label]) to it[Zones.status] }
    } ?: throw NotFoundException("table $tableId not found")
    val (label, zoneStatus) = row
    // Closed zone: friendly banner, not a scary 4xx — the guest just scanned a QR.
    if (zoneStatus == "CLOSED") {
        call.respondText(zoneClosedMenuPage(label, venueName), ContentType.Text.Html)
        return
    }
    val html = dev.dwhipstock.pos.StoreAssets.readText("customer-menu.html")
        .replace("{{TABLE_TOKEN}}", token) // [A-Za-z0-9_-] only: safe in the page's JS string
        // label is a user-authored nameOverride; escape it (like zoneClosedMenuPage does for
        // the same value) so it can't inject markup/script into the customer menu page.
        .replace("{{TABLE_LABEL}}", label.escapeHtml())
        .replace("{{VENUE_NAME}}", venueName.escapeHtml())
    call.respondText(html, ContentType.Text.Html)
}

private fun String.escapeHtml(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** One printable table slip: label + zone + the QR of its tokenised menu URL. */
private class SlipData(row: org.jetbrains.exposed.sql.ResultRow, publicBaseUrl: String) {
    val label: String = row[DiningTables.nameOverride] ?: row[DiningTables.label]
    val zoneFr: String = row[Zones.nameFr]
    val zoneEn: String = row[Zones.nameEn]
    val menuUrl: String = publicBaseUrl + TableTokens.menuPath(row[DiningTables.publicToken]!!)
}

/**
 * A6 print layout, one slip per page. QR images are inline data URIs, so the
 * page needs nothing else from the (authenticated) API.
 */
private fun slipPage(slips: List<SlipData>, venueName: String): String {
    val venue = venueName.escapeHtml()
    val cards = slips.joinToString("\n") { s ->
        """
        <div class="slip">
          <div class="venue">$venue</div>
          <div class="label">${s.label.escapeHtml()}</div>
          <div class="zone">${s.zoneFr.escapeHtml()} / ${s.zoneEn.escapeHtml()}</div>
          <img class="qr" src="data:image/png;base64,${java.util.Base64.getEncoder().encodeToString(qrPng(s.menuUrl, 512))}" alt="QR ${s.label.escapeHtml()}">
          <div class="cta">Scannez pour commander de la nourriture</div>
          <div class="cta-en">Scan to order</div>
        </div>"""
    }
    return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Table slips — $venue</title>
<style>
  @page { size: A6; margin: 0; }
  * { box-sizing: border-box; margin: 0; font-family: 'Noto Sans', system-ui, sans-serif; }
  body { background: #eee; }
  .slip { width: 105mm; height: 148mm; padding: 10mm; background: #fff; margin: 8px auto;
          display: flex; flex-direction: column; align-items: center; justify-content: center;
          text-align: center; border: 1px dashed #bbb; page-break-after: always; }
  .venue { font-size: 16pt; font-weight: 700; color: #8c3b2e; }
  .label { font-size: 42pt; font-weight: 700; line-height: 1.2; }
  .zone { font-size: 12pt; color: #666; margin-bottom: 4mm; }
  .qr { width: 60mm; height: 60mm; }
  .cta { font-size: 16pt; font-weight: 700; margin-top: 4mm; }
  .cta-en { font-size: 12pt; color: #666; }
  @media print { body { background: #fff; } .slip { border: none; margin: 0; } }
</style>
</head>
<body>
$cards
</body>
</html>"""
}

private fun checkId(call: io.ktor.server.application.ApplicationCall): Int =
    call.parameters["id"]?.toIntOrNull() ?: throw IllegalArgumentException("invalid check id")

private fun lineIdParam(call: io.ktor.server.application.ApplicationCall): Int =
    call.parameters["lineId"]?.toIntOrNull() ?: throw IllegalArgumentException("invalid line id")

private fun groupIdParam(call: io.ktor.server.application.ApplicationCall): Int =
    call.parameters["groupId"]?.toIntOrNull() ?: throw IllegalArgumentException("invalid group id")

private fun tenderType(raw: String): TenderType =
    runCatching { TenderType.valueOf(raw) }.getOrElse {
        throw IllegalArgumentException("unknown tender type $raw")
    }.also {
        require(it != TenderType.CASH) { "CASH uses POST /checks/{id}/tenders directly" }
    }
