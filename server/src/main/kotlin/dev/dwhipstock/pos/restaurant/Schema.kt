package dev.dwhipstock.pos.restaurant

import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.ItemVariants
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Table
import dev.dwhipstock.pos.db.utcTimestamp

/**
 * Restaurant add-on tier — stacks on POS Base, depends on it (never the reverse).
 * Adds the concepts a table-service venue needs: Zone, Table, Check.
 * TODO(M2): Server assignment, Course, structured Modifier, KitchenFire.
 */

object Zones : Table("zones") {
    val id = varchar("id", 64)
    val nameFr = varchar("name_fr", 100)
    val nameEn = varchar("name_en", 100)
    val sortOrder = integer("sort_order").default(0)
    val status = varchar("status", 10).default("OPEN") // OPEN | CLOSED (temporarily closed)
    // Table-label prefix (018): U/O/B/L. Every table in the zone is labelled
    // "{prefix}-{n}", so labels can't drift out of their zone (no B1 in Lower)
    // (Customer links use the table's random token, not these labels: 033.)
    val labelPrefix = varchar("label_prefix", 8).default("")
    override val primaryKey = PrimaryKey(id)
}

object DiningTables : Table("dining_tables") {
    val id = varchar("id", 64) // "t5", "t5-5"
    val zoneId = varchar("zone_id", 64).references(Zones.id)
    val label = varchar("label", 32) // "5", "5-5"
    // sub-table: same physical spot, independent bill (5 vs 5-5)
    val parentTableId = varchar("parent_table_id", 64).nullable()
    val nameOverride = varchar("name_override", 100).nullable() // VIP label, e.g. Alex Morgan
    val sortOrder = integer("sort_order").default(0)
    // floor-plan geometry, logical units on a 0–1000 canvas per zone (016)
    val x = integer("x").default(0)
    val y = integer("y").default(0)
    val width = integer("width").default(100)
    val height = integer("height").default(100)
    val rotation = integer("rotation").default(0) // degrees
    val shape = varchar("shape", 10).default("SQUARE") // ROUND | SQUARE | RECT | BAR
    val seats = integer("seats").default(4)
    // soft delete: closed checks reference table ids forever (FK RESTRICT)
    val deletedAt = utcTimestamp("deleted_at").nullable()
    // unguessable customer link /m/t/{token} (033); unique, rotatable, never synced
    val publicToken = varchar("public_token", 32).nullable().clientDefault { TableTokens.newToken() }
    override val primaryKey = PrimaryKey(id)
}

/**
 * Non-orderable structural props on the floor plan — pool tables, the bar front,
 * pillars. Inert context, not tables: no check, no seats, no status. Geometry is
 * the same LOGICAL 0–1000 canvas per zone (016). Hard-deletable (nothing FKs here).
 */
object FloorObjects : Table("floor_objects") {
    val id = varchar("id", 64)
    val zoneId = varchar("zone_id", 64).references(Zones.id)
    val type = varchar("type", 12) // POOL | BAR_FRONT | PILLAR
    val x = integer("x").default(0)
    val y = integer("y").default(0)
    val width = integer("width").default(100)
    val height = integer("height").default(100)
    val rotation = integer("rotation").default(0) // degrees
    // optional caption, per language like zone names (043); the type drives the shape
    val labelFr = varchar("label_fr", 64).nullable()
    val labelEn = varchar("label_en", 64).nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * Check = the restaurant implementation of the SDK Transaction: bound to a
 * table, stays open across visits to the terminal. Superset of the base Cart.
 * TODO: suspend/split/transfer (M2).
 */
object Checks : IntIdTable("checks") {
    val tableId = varchar("table_id", 64).references(DiningTables.id)
    val status = varchar("status", 20) // OPEN | TOTAL_LOCKED | CLOSED | VOID
    val openedBy = varchar("opened_by", 64)
    val openedAt = utcTimestamp("opened_at")
    val closedAt = utcTimestamp("closed_at").nullable()
    val corkageBottles = integer("corkage_bottles").default(0)
    // locked at first tender (stage 4); null while OPEN
    val lockedGrandTotalCents = long("locked_grand_total_cents").nullable()
    val lockedTaxIncludedCents = long("locked_tax_included_cents").nullable()
    // fee lines assessed at lock time (021): [{code,labelFr,labelEn,amountCents}].
    // Fees read from live settings would re-price history; this freezes them.
    val lockedFeesJson = text("locked_fees_json").nullable()
    // taxes added on top, frozen at lock time (036): their sum, and one entry
    // per tax [{code,labelFr,labelEn,ratePercent,registrationNumber,amountCents}]
    val lockedTaxAddedCents = long("locked_tax_added_cents").nullable()
    val lockedTaxesJson = text("locked_taxes_json").nullable()
    // stamped when the check closes or voids; null = closed outside any shift
    val shiftId = integer("shift_id").nullable()
    val voidReason = varchar("void_reason", 300).nullable()
    val voidedBy = varchar("voided_by", 64).nullable()
}

object CheckLines : IntIdTable("check_lines") {
    val checkId = integer("check_id").references(Checks.id)
    // null item/variant = an OPEN line: rung off-menu as (display_name, price, qty)
    val itemId = varchar("item_id", 64).references(Items.id).nullable()
    val variantId = varchar("variant_id", 96).references(ItemVariants.id).nullable()
    val displayName = varchar("display_name", 200).nullable() // open lines only; catalog lines render from Items
    val qty = integer("qty")
    val unitPriceCents = long("unit_price_cents") // captured at entry time
    val note = varchar("note", 500).nullable() // free-text modifier (v1)
    // ACTIVE = on the bill. PENDING = customer-submitted via QR, awaiting staff
    // accept — excluded from totals and receipts until promoted.
    val status = varchar("status", 10).default("ACTIVE")
    val createdAt = utcTimestamp("created_at")
    // captured at ring-up (038), like the price: taxable, bottle deposit per
    // unit, and whether the line needs an ID check before payment
    val taxable = bool("taxable").databaseGenerated() // DEFAULT 1
    val depositCents = long("deposit_cents").databaseGenerated() // DEFAULT 0
    val ageRestricted = bool("age_restricted").databaseGenerated() // DEFAULT 0
    // a fuel or prepay line's row in fuel_sales (046); NULL on every other line
    val fuelSaleId = integer("fuel_sale_id").nullable().databaseGenerated()
}

/**
 * Settlement-time split: a check's lines are partitioned into bill groups, each
 * printed and paid independently. A split never creates child checks — the check
 * stays the single system-of-record; groups are a settlement overlay.
 * The split is locked once any group has a tender (further edits require void).
 */
object BillGroups : IntIdTable("bill_groups") {
    val checkId = integer("check_id").references(Checks.id)
    val groupNumber = integer("group_number") // 1..N, display only, stable
    val includesCorkage = bool("includes_corkage").default(false) // exactly one group carries the check-level corkage fee
    val fixedAmountCents = long("fixed_amount_cents").nullable() // even-split (÷N) money-only group; null = by-item
    val lockedTotalCents = long("locked_total_cents").nullable() // stamped at first group tender, like checks.locked_grand_total_cents
    val lockedTaxesJson = text("locked_taxes_json").nullable() // the group's share of the check's taxes (036)
    val createdAt = utcTimestamp("created_at")
}

/** qty of a check line allocated to a group. Quantities split across groups; lines are never cloned. */
object BillGroupAllocations : IntIdTable("bill_group_allocations") {
    val groupId = integer("group_id").references(BillGroups.id)
    val lineId = integer("line_id").references(CheckLines.id)
    val qty = integer("qty")
}

/**
 * A refund returns money on a finalized (CLOSED) check — full or partial.
 * Distinct from a void (which cancels before money is applied). The store
 * decomposes the reversed included tax proportionally against the check's
 * locked totals: gross = money returned, tax = the tax inside it, net = gross - tax.
 * The cloud only aggregates these — it never recomputes tax.
 */
object Refunds : IntIdTable("refunds") {
    val checkId = integer("check_id").references(Checks.id)
    // stamped to the open shift at refund time; null = refunded outside any shift
    val shiftId = integer("shift_id").nullable()
    val grossCents = long("gross_cents")
    val netCents = long("net_cents")
    val taxCents = long("tax_cents")
    val tenderType = varchar("tender_type", 20) // CASH | CARD | BANK_TRANSFER | STRIPE
    val reason = varchar("reason", 300)
    // by-line refunds: [{lineId,qty,amountCents}]. NULL on a by-amount refund.
    val linesJson = text("lines_json").nullable()
    val refundedBy = varchar("refunded_by", 64) // the approving manager
    val createdAt = utcTimestamp("created_at")
    // STRIPE refunds only (035): written after Stripe confirmed the refund
    val stripePaymentIntentId = varchar("stripe_payment_intent_id", 64).nullable()
    val stripeRefundId = varchar("stripe_refund_id", 64).nullable()
    // the added taxes this refund reverses, one entry per tax (036); NULL = none
    val taxesJson = text("taxes_json").nullable()
    // CASH refunds (039): cash handed back − gross, to the nickel; 0 otherwise
    val roundingAdjustmentCents = long("rounding_adjustment_cents").default(0)
}

/**
 * One PaymentIntent the store created at Stripe for a check (or bill group).
 * status: CREATING → CREATED → CAPTURED → RECORDED, or CANCELED / FAILED.
 * Only RECORDED has a tender row. [publicId] is the id the client holds.
 */
object StripePayments : IntIdTable("stripe_payments") {
    val publicId = varchar("public_id", 40)
    val checkId = integer("check_id")
    val billGroupId = integer("bill_group_id").nullable()
    val amountCents = long("amount_cents")
    val currency = varchar("currency", 3)
    val paymentIntentId = varchar("payment_intent_id", 64).nullable()
    val status = varchar("status", 20)
    val tenderId = integer("tender_id").nullable()
    val lastError = varchar("last_error", 300).nullable()
    val createdAt = utcTimestamp("created_at")
    val updatedAt = utcTimestamp("updated_at")
}

/**
 * Non-sale cash into/out of the till (float top-up, petty cash, supplier paid in
 * cash, owner draw). Manager-gated, posted to the current shift, folded into the
 * shift's expected-cash reconciliation. Free-text reason (v1).
 */
object CashMovements : IntIdTable("cash_movements") {
    val shiftId = integer("shift_id").nullable()
    val direction = varchar("direction", 4) // IN | OUT
    val amountCents = long("amount_cents")
    val reason = varchar("reason", 300)
    val createdBy = varchar("created_by", 64) // the approving manager
    val createdAt = utcTimestamp("created_at")
}

/**
 * Business day = a shift (single-terminal, single-open). Float/count are
 * bookkeeping only — CopperLantern has no cash drawer hardware.
 */
object Shifts : IntIdTable("shifts") {
    val status = varchar("status", 10) // OPEN | CLOSED
    val openedAt = utcTimestamp("opened_at")
    val openedBy = varchar("opened_by", 64)
    val openingFloatCents = long("opening_float_cents")
    val closedAt = utcTimestamp("closed_at").nullable()
    val closedBy = varchar("closed_by", 64).nullable()
    val closingCountCents = long("closing_count_cents").nullable()
    val expectedCashCents = long("expected_cash_cents").nullable()
    val overShortCents = long("over_short_cents").nullable()
}
