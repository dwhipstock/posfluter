package dev.dwhipstock.poscloud.store

import dev.dwhipstock.poscloud.catalog.Catalog
import dev.dwhipstock.poscloud.catalog.Scope
import dev.dwhipstock.poscloud.catalog.arr
import dev.dwhipstock.poscloud.catalog.instant
import dev.dwhipstock.poscloud.catalog.int
import dev.dwhipstock.poscloud.catalog.long
import dev.dwhipstock.poscloud.catalog.obj
import dev.dwhipstock.poscloud.catalog.str
import dev.dwhipstock.poscloud.db.CashMovements
import dev.dwhipstock.poscloud.db.CheckLines
import dev.dwhipstock.poscloud.db.CheckTenders
import dev.dwhipstock.poscloud.db.Checks
import dev.dwhipstock.poscloud.db.FuelSales
import dev.dwhipstock.poscloud.db.Refunds
import dev.dwhipstock.poscloud.db.Shifts
import dev.dwhipstock.poscloud.staff.StaffProjection
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.upsert
import java.time.OffsetDateTime

/**
 * Event → report-table projections. Caller guarantees: inside the batch
 * transaction, first insert of this event, seq order. LEGACY TOLERANCE is the
 * rule here — a year of thin payloads exists, so every key is optional and a
 * missing money/table field lands as NULL (reports render 0/—). Never reject.
 */
object Projections {

    private val STAFF_EVENTS = setOf("staff.created", "staff.updated", "staff.deleted")

    /**
     * [venueCurrency] is what a money payload without a `currency` is in: an
     * older store that sent none sold in its venue's currency (CAD for every
     * such store).
     */
    fun apply(scope: Scope, event: IngestEvent, zone: java.time.ZoneId, venueCurrency: String = "CAD") {
        val payload = event.payload
        val createdAt = dev.dwhipstock.poscloud.CloudTime.parse(event.createdAt, zone) ?: dev.dwhipstock.poscloud.CloudTime.now()
        val cur = currencyOf(payload, venueCurrency)
        when {
            event.eventType == "check.closed" -> checkClosed(scope, payload, createdAt, zone, cur)
            event.eventType == "check.voided" -> checkVoided(scope, payload, createdAt, zone, cur)
            event.eventType == "refund.created" -> refundCreated(scope, payload, createdAt, zone, cur)
            event.eventType == "cash.movement" -> cashMovement(scope, payload, createdAt, zone, cur)
            event.eventType == "fuel.sale" -> fuelSale(scope, payload, createdAt, zone, cur)
            event.eventType == "shift.opened" -> shiftOpened(scope, payload, createdAt, zone)
            event.eventType == "shift.closed" -> shiftClosed(scope, payload, createdAt, zone, cur)
            event.eventType == "stock.counted" ->
                dev.dwhipstock.poscloud.stock.StockProjection.counted(scope, payload, createdAt, zone)
            event.eventType == "stock.received" ->
                dev.dwhipstock.poscloud.stock.StockProjection.received(scope, payload, createdAt, zone)
            event.eventType == "catalog.snapshot" -> catalogSnapshot(scope, payload)
            event.eventType == "staff.snapshot" -> staffSnapshot(scope, payload)
            event.eventType in STAFF_EVENTS -> staffEvent(scope, payload)
            event.eventType == "role_grants.updated" ->
                payload.obj("roles")?.let { StaffProjection.applyRoleGrants(scope, it) }
            event.eventType == "categories.reordered" -> categoriesReordered(scope, payload)
            event.eventType.startsWith("item.") -> itemEvent(scope, payload)
            event.eventType.startsWith("category.") -> categoryEvent(scope, payload)
            // check.total_locked / check.cancelled / check.merged / unknown: stored, not projected
            else -> {}
        }
    }

    /** The payload's own `currency` (ISO 4217), else [fallback]. */
    fun currencyOf(p: JsonObject, fallback: String): String =
        p.str("currency")?.trim()?.uppercase()?.takeIf { it.length == 3 && it.all(Char::isLetter) } ?: fallback

    private fun checkClosed(scope: Scope, p: JsonObject, createdAt: OffsetDateTime, zone: java.time.ZoneId, cur: String) {
        val checkId = p.int("checkId") ?: return
        val fees = p.arr("fees")?.filterIsInstance<JsonObject>()
        Checks.upsert {
            it[tenantId] = scope.tenantId
            it[venueId] = scope.venueId
            it[Checks.checkId] = checkId
            it[status] = "CLOSED"
            it[tableId] = p.str("tableId")
            it[tableLabel] = p.str("tableLabel")
            it[zoneId] = p.str("zoneId")
            it[zoneNameFr] = p.str("zoneNameFr")
            it[zoneNameEn] = p.str("zoneNameEn")
            it[shiftId] = p.long("shiftId")
            it[openedAt] = p.instant("openedAt", zone)
            it[closedAt] = p.instant("closedAt", zone) ?: createdAt
            it[openedBy] = p.str("openedBy")
            it[grandTotalCents] = p.long("grandTotalCents") ?: p.long("grandTotal")
            it[taxIncludedCents] = p.long("taxIncludedCents")
            it[corkageBottles] = p.int("corkageBottles")
            it[corkageCents] = fees?.let { f -> feeSum(f, "corkage") }
            it[serviceChargeCents] = fees?.let { f -> feeSum(f, "service_charge") }
            it[voidReason] = null
            it[voidedBy] = null
            it[gstCents] = taxSum(p, "GST")
            it[qstCents] = taxSum(p, "QST")
            it[taxes] = p.arr("taxes")?.toString()
            it[currency] = cur
            val discounts = p.arr("discounts")?.filterIsInstance<JsonObject>()
            it[Checks.discounts] = p.arr("discounts")?.toString()
            it[discountCents] = discounts?.sumOf { d -> d.long("amountCents") ?: 0 }
        }
        p.arr("lines")?.filterIsInstance<JsonObject>()?.let { lines ->
            CheckLines.deleteWhere {
                (tenantId eq scope.tenantId) and (venueId eq scope.venueId) and (CheckLines.checkId eq checkId)
            }
            lines.forEach { line ->
                CheckLines.insert {
                    it[tenantId] = scope.tenantId
                    it[venueId] = scope.venueId
                    it[CheckLines.checkId] = checkId
                    it[lineId] = line.long("lineId")
                    it[itemId] = line.str("itemId")
                    it[variantId] = line.str("variantId")
                    it[categoryId] = line.str("categoryId")
                    it[nameFr] = line.str("nameFr")
                    it[nameEn] = line.str("nameEn")
                    it[variantLabelFr] = line.str("variantLabelFr")
                    it[variantLabelEn] = line.str("variantLabelEn")
                    it[displayName] = line.str("displayName")
                    it[qty] = line.int("qty") ?: 0
                    it[unitPriceCents] = line.long("unitPriceCents") ?: 0
                    it[lineTotalCents] = line.long("lineTotalCents") ?: 0
                    it[fuel] = line.obj("fuel")?.toString()
                    it[unitCostCents] = line.long("unitCostCents")
                }
            }
        }
        p.arr("tenders")?.filterIsInstance<JsonObject>()?.forEach { tender ->
            val tenderId = tender.long("tenderId") ?: return@forEach
            CheckTenders.upsert {
                it[tenantId] = scope.tenantId
                it[venueId] = scope.venueId
                it[CheckTenders.tenderId] = tenderId
                it[CheckTenders.checkId] = checkId
                it[type] = tender.str("type") ?: "CASH"
                it[amountTenderedCents] = tender.long("amountTenderedCents")
                it[amountAppliedCents] = tender.long("amountAppliedCents")
                it[roundingAdjustmentCents] = tender.long("roundingAdjustmentCents")
                it[changeCents] = tender.long("changeCents")
                it[tenderedAt] = tender.instant("tenderedAt", zone) ?: p.instant("closedAt", zone) ?: createdAt
            }
        }
    }

    private fun checkVoided(scope: Scope, p: JsonObject, createdAt: OffsetDateTime, zone: java.time.ZoneId, cur: String) {
        val checkId = p.int("checkId") ?: return
        Checks.upsert {
            it[tenantId] = scope.tenantId
            it[venueId] = scope.venueId
            it[Checks.checkId] = checkId
            it[status] = "VOID"
            it[tableId] = p.str("tableId")
            it[tableLabel] = p.str("tableLabel")
            it[zoneId] = p.str("zoneId")
            it[zoneNameFr] = p.str("zoneNameFr")
            it[zoneNameEn] = p.str("zoneNameEn")
            it[shiftId] = p.long("shiftId")
            it[openedAt] = p.instant("openedAt", zone)
            it[closedAt] = p.instant("voidedAt", zone) ?: createdAt
            it[grandTotalCents] = p.long("amountCents")
            it[taxIncludedCents] = p.long("taxIncludedCents")
            it[voidReason] = p.str("reason")
            it[voidedBy] = p.str("authorizedBy") ?: p.str("voidedBy")
            it[gstCents] = taxSum(p, "GST")
            it[qstCents] = taxSum(p, "QST")
            it[taxes] = p.arr("taxes")?.toString()
            it[currency] = cur
        }
    }

    /**
     * A refund on a finalized check. The store already decomposed the reversed
     * included tax (gross/net/tax); we only store it. Idempotent by refund_id so
     * a replay is a no-op. Reports net these out of sales + tax.
     */
    private fun refundCreated(scope: Scope, p: JsonObject, createdAt: OffsetDateTime, zone: java.time.ZoneId, cur: String) {
        val refundId = p.long("refundId") ?: return
        Refunds.upsert {
            it[tenantId] = scope.tenantId
            it[venueId] = scope.venueId
            it[Refunds.refundId] = refundId
            it[checkId] = p.int("checkId")
            it[shiftId] = p.long("shiftId")
            it[grossCents] = p.long("grossCents")
            it[netCents] = p.long("netCents")
            it[taxIncludedCents] = p.long("taxIncludedCents")
            it[tenderType] = p.str("tenderType")
            it[reason] = p.str("reason")
            it[refundedBy] = p.str("refundedBy")
            it[tableLabel] = p.str("tableLabel")
            it[zoneId] = p.str("zoneId")
            it[zoneNameFr] = p.str("zoneNameFr")
            it[zoneNameEn] = p.str("zoneNameEn")
            it[Refunds.createdAt] = p.instant("createdAt", zone) ?: createdAt
            it[gstCents] = taxSum(p, "GST")
            it[qstCents] = taxSum(p, "QST")
            it[taxes] = p.arr("taxes")?.toString()
            it[currency] = cur
            // cash back − gross on a CASH refund; an older store sends none (NULL → 0)
            it[roundingAdjustmentCents] = p.long("roundingAdjustmentCents")
        }
        // a by-line refund's products go back on hand (retail stock ledger)
        dev.dwhipstock.poscloud.stock.StockProjection.refundLines(
            scope, p, refundId, p.instant("createdAt", zone) ?: createdAt)
    }

    /** A non-sale cash movement (IN/OUT). Idempotent by movement_id. */
    private fun cashMovement(scope: Scope, p: JsonObject, createdAt: OffsetDateTime, zone: java.time.ZoneId, cur: String) {
        val movementId = p.long("movementId") ?: return
        CashMovements.upsert {
            it[tenantId] = scope.tenantId
            it[venueId] = scope.venueId
            it[CashMovements.movementId] = movementId
            it[shiftId] = p.long("shiftId")
            it[direction] = p.str("direction")
            it[amountCents] = p.long("amountCents")
            it[reason] = p.str("reason")
            it[createdBy] = p.str("user")
            it[CashMovements.createdAt] = p.instant("createdAt", zone) ?: createdAt
            it[currency] = cur
        }
    }

    /**
     * One settled fuelling (a gas station). Idempotent by fuel_sale_id: a
     * re-delivery overwrites the row with the same figures. amountCents is
     * what was dispensed; a prepay's unused change arrives separately as a
     * refund.created and refundCents is only a note of it.
     */
    private fun fuelSale(scope: Scope, p: JsonObject, createdAt: OffsetDateTime, zone: java.time.ZoneId, cur: String) {
        val fuelSaleId = p.long("fuelSaleId") ?: return
        FuelSales.upsert {
            it[tenantId] = scope.tenantId
            it[venueId] = scope.venueId
            it[FuelSales.fuelSaleId] = fuelSaleId
            it[checkId] = p.int("checkId")
            it[pump] = p.int("pump")
            it[nozzle] = p.int("nozzle")
            it[grade] = p.str("grade")
            it[gradeName] = p.str("gradeName")
            it[volumeMilli] = p.long("volumeMilli")
            it[priceMills] = p.long("priceMills")
            it[amountCents] = p.long("amountCents")
            it[mode] = p.str("mode")
            it[prepaidCents] = p.long("prepaidCents")
            it[refundCents] = p.long("refundCents")
            it[fdcTransactionId] = p.str("fdcTransactionId")
            it[completedAt] = p.instant("completedAt", zone) ?: createdAt
            it[currency] = cur
            it[costMills] = p.long("costMills")
            it[costCents] = p.long("costCents")
        }
    }

    private fun shiftOpened(scope: Scope, p: JsonObject, createdAt: OffsetDateTime, zone: java.time.ZoneId) {
        val shiftId = p.long("shiftId") ?: return
        // a late replay must not reopen a shift the Z-close already projected
        Shifts.upsert(where = { Shifts.status neq "CLOSED" }) {
            it[tenantId] = scope.tenantId
            it[venueId] = scope.venueId
            it[Shifts.shiftId] = shiftId
            it[status] = "OPEN"
            it[openedAt] = p.instant("openedAt", zone) ?: createdAt
            it[openedBy] = p.str("openedBy")
            it[openingFloatCents] = p.long("openingFloatCents")
        }
    }

    private fun shiftClosed(scope: Scope, p: JsonObject, createdAt: OffsetDateTime, zone: java.time.ZoneId, cur: String) {
        val shiftId = p.long("shiftId") ?: return
        // Exposed's upsert ON CONFLICT UPDATE covers every non-key column, so an
        // unassigned column gets nulled — read-merge keeps the shift.opened
        // projection alive under legacy thin closes that lack the open-side keys
        val existing = Shifts.selectAll().where {
            (Shifts.tenantId eq scope.tenantId) and (Shifts.venueId eq scope.venueId) and
                (Shifts.shiftId eq shiftId)
        }.firstOrNull()
        Shifts.upsert {
            it[tenantId] = scope.tenantId
            it[venueId] = scope.venueId
            it[Shifts.shiftId] = shiftId
            it[status] = "CLOSED"
            it[openedAt] = p.instant("openedAt", zone) ?: existing?.get(openedAt)
            it[openedBy] = p.str("openedBy") ?: existing?.get(openedBy)
            it[openingFloatCents] = p.long("openingFloatCents") ?: existing?.get(openingFloatCents)
            it[closedAt] = p.instant("closedAt", zone) ?: createdAt
            it[closedBy] = p.str("closedBy")
            it[revenueCents] = p.long("revenueCents")
            it[transactionCount] = p.int("transactionCount")
            it[avgCheckCents] = p.long("avgCheckCents")
            it[corkageCents] = p.long("corkageCents")
            it[tenderBreakdown] = p.arr("tenderBreakdown")?.toString()
            it[expectedCashCents] = p.long("expectedCashCents")
            it[closingCountCents] = p.long("closingCountCents")
            it[overShortCents] = p.long("overShortCents")
            it[currency] = cur
            it[cashRoundingCents] = p.long("cashRoundingCents")
        }
    }

    /**
     * Store menu edit → mirror it for display (one-way sync: the tablet owns the
     * menu, nothing is redistributed). Legacy origin:"cloud" echoes (from the
     * era when the portal edited the menu) are audit-only: the state they carry
     * already landed in the mirror when the portal edit was made.
     */
    private fun itemEvent(scope: Scope, p: JsonObject) {
        if (p.str("origin") == "cloud") return
        val item = p.obj("item") ?: return
        Catalog.applyItemSnapshot(scope, item)
    }

    private fun categoryEvent(scope: Scope, p: JsonObject) {
        if (p.str("origin") == "cloud") return
        val category = p.obj("category") ?: return
        Catalog.applyCategorySnapshot(scope, category)
    }

    private fun categoriesReordered(scope: Scope, p: JsonObject) {
        if (p.str("origin") == "cloud") return
        p.arr("categories")?.filterIsInstance<JsonObject>()?.forEach { Catalog.applyCategorySnapshot(scope, it) }
    }

    /**
     * Bootstrap upload of the menu the store already has — possibly in several
     * chunks (a big retail catalog), and later chunks when a store adds a
     * batch of products. Always additive: nothing absent from a chunk is
     * removed (deletions arrive as item.deleted snapshots).
     */
    private fun catalogSnapshot(scope: Scope, p: JsonObject) {
        p.arr("categories")?.filterIsInstance<JsonObject>()?.forEach { Catalog.applyCategorySnapshot(scope, it) }
        p.arr("items")?.filterIsInstance<JsonObject>()?.let { Catalog.applyItemSnapshots(scope, it) }
    }

    /** staff.created / staff.updated / staff.deleted carry one full `staff` snapshot. */
    private fun staffEvent(scope: Scope, p: JsonObject) {
        p.obj("staff")?.let { StaffProjection.applyStaff(scope, it) }
    }

    /** One-time bootstrap: every staff row + the role matrix. */
    private fun staffSnapshot(scope: Scope, p: JsonObject) {
        p.arr("staff")?.filterIsInstance<JsonObject>()?.forEach { StaffProjection.applyStaff(scope, it) }
        p.obj("roles")?.let { StaffProjection.applyRoleGrants(scope, it) }
    }

    private fun feeSum(fees: List<JsonObject>, code: String): Long =
        fees.filter { it.str("code") == code }.sumOf { it.long("amountCents") ?: 0 }

    /**
     * One tax's amount from the store's `taxes` breakdown. NULL when the store
     * sent no breakdown at all (an older store) — reports show 0, never a guess;
     * 0 when it sent one without that tax.
     */
    private fun taxSum(p: JsonObject, code: String): Long? =
        p.arr("taxes")?.filterIsInstance<JsonObject>()?.let { taxes -> feeSum(taxes, code) }
}
