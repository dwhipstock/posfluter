package dev.dwhipstock.poscloud.store

import dev.dwhipstock.poscloud.catalog.Catalog
import dev.dwhipstock.poscloud.catalog.Scope
import dev.dwhipstock.poscloud.catalog.arr
import dev.dwhipstock.poscloud.catalog.dateTime
import dev.dwhipstock.poscloud.catalog.int
import dev.dwhipstock.poscloud.catalog.long
import dev.dwhipstock.poscloud.catalog.obj
import dev.dwhipstock.poscloud.catalog.str
import dev.dwhipstock.poscloud.db.CashMovements
import dev.dwhipstock.poscloud.db.CheckLines
import dev.dwhipstock.poscloud.db.CheckTenders
import dev.dwhipstock.poscloud.db.Checks
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
import java.time.LocalDateTime

/**
 * Event → report-table projections. Caller guarantees: inside the batch
 * transaction, first insert of this event, seq order. LEGACY TOLERANCE is the
 * rule here — a year of thin payloads exists, so every key is optional and a
 * missing money/table field lands as NULL (reports render 0/—). Never reject.
 */
object Projections {

    private val STAFF_EVENTS = setOf("staff.created", "staff.updated", "staff.deleted")

    fun apply(scope: Scope, event: IngestEvent) {
        val payload = event.payload
        val createdAt = runCatching { LocalDateTime.parse(event.createdAt) }.getOrElse { LocalDateTime.now() }
        when {
            event.eventType == "check.closed" -> checkClosed(scope, payload, createdAt)
            event.eventType == "check.voided" -> checkVoided(scope, payload, createdAt)
            event.eventType == "refund.created" -> refundCreated(scope, payload, createdAt)
            event.eventType == "cash.movement" -> cashMovement(scope, payload, createdAt)
            event.eventType == "shift.opened" -> shiftOpened(scope, payload, createdAt)
            event.eventType == "shift.closed" -> shiftClosed(scope, payload, createdAt)
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

    private fun checkClosed(scope: Scope, p: JsonObject, createdAt: LocalDateTime) {
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
            it[openedAt] = p.dateTime("openedAt")
            it[closedAt] = p.dateTime("closedAt") ?: createdAt
            it[openedBy] = p.str("openedBy")
            it[grandTotalCents] = p.long("grandTotalCents") ?: p.long("grandTotal")
            it[taxIncludedCents] = p.long("taxIncludedCents")
            it[corkageBottles] = p.int("corkageBottles")
            it[corkageCents] = fees?.let { f -> feeSum(f, "corkage") }
            it[serviceChargeCents] = fees?.let { f -> feeSum(f, "service_charge") }
            it[voidReason] = null
            it[voidedBy] = null
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
                it[tenderedAt] = tender.dateTime("tenderedAt") ?: p.dateTime("closedAt") ?: createdAt
            }
        }
    }

    private fun checkVoided(scope: Scope, p: JsonObject, createdAt: LocalDateTime) {
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
            it[openedAt] = p.dateTime("openedAt")
            it[closedAt] = p.dateTime("voidedAt") ?: createdAt
            it[grandTotalCents] = p.long("amountCents")
            it[taxIncludedCents] = p.long("taxIncludedCents")
            it[voidReason] = p.str("reason")
            it[voidedBy] = p.str("authorizedBy") ?: p.str("voidedBy")
        }
    }

    /**
     * A refund on a finalized check. The store already decomposed the reversed
     * inclusive VAT (gross/net/tax); we only store it. Idempotent by refund_id so
     * a replay is a no-op. Reports net these out of sales + VAT.
     */
    private fun refundCreated(scope: Scope, p: JsonObject, createdAt: LocalDateTime) {
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
            it[Refunds.createdAt] = p.dateTime("createdAt") ?: createdAt
        }
    }

    /** A non-sale cash movement (IN/OUT). Idempotent by movement_id. */
    private fun cashMovement(scope: Scope, p: JsonObject, createdAt: LocalDateTime) {
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
            it[CashMovements.createdAt] = p.dateTime("createdAt") ?: createdAt
        }
    }

    private fun shiftOpened(scope: Scope, p: JsonObject, createdAt: LocalDateTime) {
        val shiftId = p.long("shiftId") ?: return
        // a late replay must not reopen a shift the Z-close already projected
        Shifts.upsert(where = { Shifts.status neq "CLOSED" }) {
            it[tenantId] = scope.tenantId
            it[venueId] = scope.venueId
            it[Shifts.shiftId] = shiftId
            it[status] = "OPEN"
            it[openedAt] = p.dateTime("openedAt") ?: createdAt
            it[openedBy] = p.str("openedBy")
            it[openingFloatCents] = p.long("openingFloatCents")
        }
    }

    private fun shiftClosed(scope: Scope, p: JsonObject, createdAt: LocalDateTime) {
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
            it[openedAt] = p.dateTime("openedAt") ?: existing?.get(openedAt)
            it[openedBy] = p.str("openedBy") ?: existing?.get(openedBy)
            it[openingFloatCents] = p.long("openingFloatCents") ?: existing?.get(openingFloatCents)
            it[closedAt] = p.dateTime("closedAt") ?: createdAt
            it[closedBy] = p.str("closedBy")
            it[revenueCents] = p.long("revenueCents")
            it[transactionCount] = p.int("transactionCount")
            it[avgCheckCents] = p.long("avgCheckCents")
            it[corkageCents] = p.long("corkageCents")
            it[tenderBreakdown] = p.arr("tenderBreakdown")?.toString()
            it[expectedCashCents] = p.long("expectedCashCents")
            it[closingCountCents] = p.long("closingCountCents")
            it[overShortCents] = p.long("overShortCents")
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

    /** Bootstrap upload of the menu the store already has. */
    private fun catalogSnapshot(scope: Scope, p: JsonObject) {
        p.arr("categories")?.filterIsInstance<JsonObject>()?.forEach { Catalog.applyCategorySnapshot(scope, it) }
        p.arr("items")?.filterIsInstance<JsonObject>()?.forEach { Catalog.applyItemSnapshot(scope, it) }
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
}
