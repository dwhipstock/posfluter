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
import dev.dwhipstock.pos.restaurant.TenderView
import dev.dwhipstock.pos.sdk.Outbox
import dev.dwhipstock.pos.sdk.TenderType
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

/**
 * The running bill a guest sees at /m/{tableId}/bill. Strictly the table's
 * CURRENT open check — `open=false` (all else empty) when there is none; a
 * closed/previous party's check is never served. Totals are the server
 * pipeline's, VAT-inclusive and hidden, exactly like the printed bill.
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
    val tenderType: String = "CASH", // CASH | CARD | BANK_TRANSFER
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

/** Customer-facing scan-to-order routes. Unauthenticated by design — they run on guests' phones. */
fun Route.customerRoutes(checkService: CheckService, config: dev.dwhipstock.pos.sdk.CustomerConfig) {

    // Opaque-id form: the fallback path already-printed QR slips carry forever.
    get("/m/{tableId}") {
        serveCustomerMenu(call, call.parameters["tableId"]!!)
    }

    // Readable URL that mirrors the floor plan: /m/lower/8 → Lower's table L-8.
    // Two path segments, so it never collides with /m/{tableId} (one segment) or
    // /m/{tableId}/bill (the literal "bill" beats the {number} param). Resolves
    // (zone id + "{prefix}-{number}") → the live table, else a clear 404.
    get("/m/{zone}/{number}") {
        val zoneId = call.parameters["zone"]!!
        val numberSeg = call.parameters["number"]!!
        val tableId = numberSeg.toIntOrNull()?.let { n ->
            transaction { tableIdForZoneNumber(zoneId, n) }
        } ?: throw NotFoundException("no table $zoneId/$numberSeg", "table_not_found")
        serveCustomerMenu(call, tableId)
    }

    // Running bill for the guest's phone. Unauthenticated like the menu — it
    // exposes only what a printed provisional bill on that table would show.
    get("/m/{tableId}/bill") {
        val tableId = call.parameters["tableId"]!!
        transaction {
            DiningTables.selectAll()
                .where { (DiningTables.id eq tableId) and DiningTables.deletedAt.isNull() }
                .firstOrNull()
        } ?: throw NotFoundException("table $tableId not found")
        call.respond(customerBill(checkService.openCheckForTable(tableId)))
    }

    post("/tables/{tableId}/pending-lines") {
        val tableId = call.parameters["tableId"]!!
        val req = call.receive<SubmitPendingRequest>()
        call.respond(HttpStatusCode.Created, checkService.submitPendingLines(tableId, req.lines))
    }

    /**
     * Printable slips: A6 card per table — label, zone, QR, "scan to order" in
     * FR+EN. Open at a copy shop, print, laminate (M5 setup).
     */
    get("/tables/{tableId}/slip") {
        val tableId = call.parameters["tableId"]!!
        val slip = transaction {
            DiningTables.join(Zones, org.jetbrains.exposed.sql.JoinType.INNER,
                    DiningTables.zoneId, Zones.id)
                .selectAll()
                .where { (DiningTables.id eq tableId) and DiningTables.deletedAt.isNull() }
                .firstOrNull()?.let(::SlipData)
        } ?: throw NotFoundException("table $tableId not found")
        call.respondText(slipPage(listOf(slip)), ContentType.Text.Html)
    }

    /** All tables on one printable page, page break per slip ("print all table slips"). */
    get("/slips") {
        val slips = transaction {
            DiningTables.join(Zones, org.jetbrains.exposed.sql.JoinType.INNER,
                    DiningTables.zoneId, Zones.id)
                .selectAll().where { DiningTables.deletedAt.isNull() }
                .orderBy(Zones.sortOrder).orderBy(DiningTables.sortOrder)
                .map(::SlipData)
        }
        call.respondText(slipPage(slips), ContentType.Text.Html)
    }

    /** Table QR slip: payload is the readable public menu URL. Staff print/laminate these (M5 setup). */
    get("/tables/{tableId}/qr") {
        val tableId = call.parameters["tableId"]!!
        val path = transaction {
            DiningTables.join(Zones, org.jetbrains.exposed.sql.JoinType.INNER,
                    DiningTables.zoneId, Zones.id)
                .selectAll()
                .where { (DiningTables.id eq tableId) and DiningTables.deletedAt.isNull() }
                .firstOrNull()
                ?.let { menuPathFor(it[DiningTables.zoneId], it[DiningTables.label], tableId) }
        } ?: throw NotFoundException("table $tableId not found")
        val url = "${config.publicBaseUrl}$path"
        val matrix = com.google.zxing.MultiFormatWriter()
            .encode(url, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
        val png = java.io.ByteArrayOutputStream().use { out ->
            javax.imageio.ImageIO.write(
                com.google.zxing.client.j2se.MatrixToImageWriter.toBufferedImage(matrix), "png", out)
            out.toByteArray()
        }
        call.respondBytes(png, ContentType.Image.PNG)
    }
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

fun Route.posRoutes(checkService: CheckService, auth: dev.dwhipstock.pos.base.AuthService, photos: dev.dwhipstock.pos.sdk.PhotoStore) {

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
    // instructions (Card QR payload / bank account details); staff confirms
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
private fun zoneClosedMenuPage(tableLabel: String): String = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>The Copper Lantern Pub</title>
<style>
  * { box-sizing: border-box; margin: 0; font-family: 'Noto Sans', system-ui, sans-serif; }
  body { background: #0F1419; color: #E8ECEF; min-height: 100vh; display: flex;
         align-items: center; justify-content: center; padding: 24px; }
  .card { max-width: 420px; text-align: center; }
  .icon { font-size: 44px; margin-bottom: 16px; }
  .table { font-size: 13px; color: #8B96A3; letter-spacing: .08em; text-transform: uppercase; margin-bottom: 8px; }
  .fr { font-size: 22px; font-weight: 700; line-height: 1.5; }
  .en { font-size: 16px; color: #8B96A3; margin-top: 10px; line-height: 1.5; }
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
    )
}

/**
 * Render the customer menu for a resolved internal table id — the single path
 * both /m/{tableId} and /m/{zone}/{number} funnel into. The page always keys its
 * own API calls (bill, pending) off the internal id injected here, so the URL
 * form the guest arrived by doesn't matter downstream.
 */
private suspend fun serveCustomerMenu(call: io.ktor.server.application.ApplicationCall, tableId: String) {
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
        call.respondText(zoneClosedMenuPage(label), ContentType.Text.Html)
        return
    }
    val html = Thread.currentThread().contextClassLoader
        .getResource("customer-menu.html")!!.readText()
        .replace("{{TABLE_ID}}", tableId)
        // label is a user-authored nameOverride; escape it (like zoneClosedMenuPage does for
        // the same value) so it can't inject markup/script into the customer menu page.
        .replace("{{TABLE_LABEL}}", label.escapeHtml())
    call.respondText(html, ContentType.Text.Html)
}

/** Resolve (zone id + number) → the live table id via its "{prefix}-{number}" label; null if none. */
private fun tableIdForZoneNumber(zoneId: String, number: Int): String? {
    val zone = Zones.selectAll().where { Zones.id eq zoneId }.firstOrNull() ?: return null
    val label = "${zone[Zones.labelPrefix]}-$number"
    return DiningTables.selectAll().where {
        (DiningTables.zoneId eq zoneId) and (DiningTables.label eq label) and
            DiningTables.deletedAt.isNull()
    }.firstOrNull()?.get(DiningTables.id)
}

/**
 * The menu path a table's QR should encode: the readable "/m/{zone}/{number}"
 * when the label carries a number, else the opaque "/m/{id}" fallback. The
 * number is the trailing digits of the label, mirroring [tableIdForZoneNumber].
 */
internal fun menuPathFor(zoneId: String, label: String, tableId: String): String {
    val n = Regex("(\\d+)$").find(label)?.groupValues?.get(1)
    return if (n != null) "/m/$zoneId/$n" else "/m/$tableId"
}

private fun String.escapeHtml(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** One printable table slip: label + zone + QR. */
private class SlipData(row: org.jetbrains.exposed.sql.ResultRow) {
    val tableId: String = row[DiningTables.id]
    val label: String = row[DiningTables.nameOverride] ?: row[DiningTables.label]
    val zoneTh: String = row[Zones.nameFr]
    val zoneEn: String = row[Zones.nameEn]
}

/**
 * A6 print layout, one slip per page. QR images come from /tables/{id}/qr
 * (relative src → same host), which embeds the resolved publicBaseUrl.
 */
private fun slipPage(slips: List<SlipData>): String {
    val cards = slips.joinToString("\n") { s ->
        """
        <div class="slip">
          <div class="venue">The Copper Lantern Pub</div>
          <div class="label">${s.label}</div>
          <div class="zone">${s.zoneTh} / ${s.zoneEn}</div>
          <img class="qr" src="/tables/${s.tableId}/qr" alt="QR ${s.label}">
          <div class="cta">Scannez pour commander de la nourriture</div>
          <div class="cta-en">Scan to order</div>
        </div>"""
    }
    return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Table slips — The Copper Lantern Pub</title>
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
