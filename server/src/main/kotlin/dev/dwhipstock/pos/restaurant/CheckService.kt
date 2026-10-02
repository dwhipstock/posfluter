package dev.dwhipstock.pos.restaurant

import dev.dwhipstock.pos.base.NotFoundException
import dev.dwhipstock.pos.orders.CounterOrders
import dev.dwhipstock.pos.orders.SaleLocations
import dev.dwhipstock.pos.base.ConflictException
import dev.dwhipstock.pos.base.BadRequestException
import dev.dwhipstock.pos.sdk.VenueClock
import dev.dwhipstock.pos.base.ItemSchedules

import dev.dwhipstock.pos.base.CleanText
import dev.dwhipstock.pos.base.GrantsRepo
import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.Permissions
import dev.dwhipstock.pos.base.Tenders
import dev.dwhipstock.pos.base.Users
import dev.dwhipstock.pos.sdk.BasketLine
import dev.dwhipstock.pos.sdk.CustomerConfig
import dev.dwhipstock.pos.sdk.FeeLine
import dev.dwhipstock.pos.sdk.Money
import dev.dwhipstock.pos.sdk.MoneyLimits
import dev.dwhipstock.pos.sdk.sumOfExact
import dev.dwhipstock.pos.sdk.Outbox
import dev.dwhipstock.pos.sdk.PrintJob
import dev.dwhipstock.pos.sdk.PrinterAdapter
import dev.dwhipstock.pos.sdk.Receipt
import dev.dwhipstock.pos.sdk.ReceiptFee
import dev.dwhipstock.pos.sdk.ReceiptItem
import dev.dwhipstock.pos.sdk.ReceiptKind
import dev.dwhipstock.pos.sdk.ReceiptRenderer
import dev.dwhipstock.pos.sdk.ReceiptTender
import dev.dwhipstock.pos.sdk.TaxComponent
import dev.dwhipstock.pos.sdk.TaxLine
import dev.dwhipstock.pos.sdk.TaxPolicy
import dev.dwhipstock.pos.sdk.TenderInstructions
import dev.dwhipstock.pos.sdk.TenderType
import dev.dwhipstock.pos.sdk.Totals
import dev.dwhipstock.pos.sdk.TransactionPipeline
import dev.dwhipstock.pos.sdk.putMoneyContext
import dev.dwhipstock.pos.sdk.Align
import dev.dwhipstock.pos.sdk.PrintLine
import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import dev.dwhipstock.pos.sdk.i18n.MessageKey
import dev.dwhipstock.pos.sdk.i18n.MessageKey.RECEIPT_ROUNDING
import dev.dwhipstock.pos.sdk.i18n.MessageKey.RECEIPT_SUBTOTAL
import dev.dwhipstock.pos.sdk.i18n.MessageKey.REFUND_CASH_BACK
import dev.dwhipstock.pos.sdk.i18n.MessageKey.RECEIPT_TAX_INCLUDED
import dev.dwhipstock.pos.sdk.i18n.MessageKey.REFUND_HEADER
import dev.dwhipstock.pos.sdk.i18n.MessageKey.REFUND_NUMBER
import dev.dwhipstock.pos.sdk.i18n.MessageKey.REFUND_REF_BILL
import dev.dwhipstock.pos.sdk.i18n.MessageKey.REFUND_TOTAL
import dev.dwhipstock.pos.sdk.i18n.MessageKey.REFUND_VIA
import dev.dwhipstock.pos.sdk.i18n.MessageKey.SLIP_REASON
import dev.dwhipstock.pos.sdk.i18n.MessageKey.SLIP_TIME
import dev.dwhipstock.pos.sdk.i18n.MessageKey.TENDER_CASH
import dev.dwhipstock.pos.sdk.i18n.Messages
import dev.dwhipstock.pos.sdk.i18n.dataText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

@kotlinx.serialization.Serializable
data class PendingLineRequest(
    val itemId: String, val variantId: String, val qty: Int = 1, val note: String? = null,
    /** The unit price the guest saw; a different price now refuses the line (price_changed). */
    val expectedPriceCents: Long? = null,
)

/** One line + qty to refund (by-line refund). */
@kotlinx.serialization.Serializable
data class RefundLineRequest(val lineId: Int, val qty: Int = 1)

@kotlinx.serialization.Serializable
data class RefundView(
    val id: Int,
    val checkId: Int,
    val grossCents: Long,
    val netCents: Long,
    val taxCents: Long,
    val tenderType: String,
    val reason: String,
    val refundedBy: String,
    val createdAt: String,
    /** The added taxes this refund reverses (inside [taxCents]). */
    val taxes: List<TaxView> = emptyList(),
    /**
     * CASH refunds: cash handed back − [grossCents], signed (the cash rounds to
     * the nickel; gross, net and tax stay exact). 0 for card / transfer refunds.
     */
    val roundingAdjustmentCents: Long = 0,
    /** Money that actually went back: gross + rounding. */
    val paidOutCents: Long = 0,
)

@kotlinx.serialization.Serializable
data class RefundResult(val refund: RefundView, val check: CheckView, val slipText: String)

/** A check's grand total, what's been refunded so far, and its refund rows. */
@kotlinx.serialization.Serializable
data class RefundInfo(
    val checkId: Int,
    val grandTotalCents: Long,
    val refundedCents: Long,
    val refundableCents: Long,
    val refunds: List<RefundView>,
    /** Still refundable back to the card through Stripe (0 = no Stripe tender). */
    val stripeRefundableCents: Long = 0,
    /** Still refundable back to the card through the integrated terminal (0 = no TERMINAL tender). */
    val terminalRefundableCents: Long = 0,
)

/** A CLOSED check in the refund picker: what it was, what's left to refund. */
@kotlinx.serialization.Serializable
data class ClosedCheckSummary(
    val id: Int,
    val tableLabel: String,
    val closedAt: String,
    val grandTotalCents: Long,
    val refundedCents: Long,
    val refundableCents: Long,
)

/**
 * Restaurant-vertical Check: a Transaction bound to a table.
 * Status flow: OPEN → TOTAL_LOCKED (first tender) → CLOSED, or → VOID
 * (manager-gated, only while no money is applied), or → CANCELLED (auto, when a
 * mutation strips the check to zero lines + zero balance — nothing was rung, so no
 * reason and no manager gate; see cancelIfEmpty), or → MERGED (its lines were
 * folded into another table's check; see mergeCheck).
 */
private val CARD_JSON = Json { ignoreUnknownKeys = true }

/** A tender's card fields as stored in tenders.card_json. */
internal fun encodeCard(card: dev.dwhipstock.pos.payments.terminal.CardDetails): String =
    CARD_JSON.encodeToString(dev.dwhipstock.pos.payments.terminal.CardDetails.serializer(), card)

internal fun decodeCard(json: String?): dev.dwhipstock.pos.payments.terminal.CardDetails? = json?.let {
    runCatching { CARD_JSON.decodeFromString(dev.dwhipstock.pos.payments.terminal.CardDetails.serializer(), it) }.getOrNull()
}

/** What the receipt prints under a card tender. */
internal fun receiptCardOf(json: String?): dev.dwhipstock.pos.sdk.ReceiptCard? = decodeCard(json)?.let { c ->
    dev.dwhipstock.pos.sdk.ReceiptCard(
        brand = c.brand, last4 = c.last4, entryMode = c.entryMode.wire, authCode = c.authCode,
        aid = c.aid, tvr = c.tvr, tsi = c.tsi, appLabel = c.appLabel, cvm = c.cvm, tip = Money(c.tipCents),
        processorRef = c.processorRef, processor = c.processor,
    )
}

/**
 * The numbered-order side of a check ([dev.dwhipstock.pos.orders.PickupOrders]:
 * the quick-serve counter, a restaurant's carry-out). [paid] runs inside the
 * closing transaction (the order gets its number there, before the receipt
 * prints); the others after, and never block a sale.
 */
interface CounterHook {
    /** The check is paid in full and closing: commit the counter order. Inside the transaction. */
    fun paid(checkId: Int)
    /** After the close committed: send the order to the kitchen. */
    fun afterPaid(checkId: Int)
    /** The check was cancelled (emptied, discarded, expired): the unpaid order is gone. */
    fun cancelled(checkId: Int)
    /** The order number and dine in / take out for the receipt ("#101 · Take out", "Order #105 · Carry-out"); null = not a numbered order. */
    fun receiptOrder(checkId: Int): dev.dwhipstock.pos.sdk.ReceiptOrder?
    /** A staff screen has the check open (loaded, polled, edited): an unpaid order there is not abandoned. */
    fun touched(checkId: Int) {}
}

class CheckService(private val config: CustomerConfig) {

    private val log = LoggerFactory.getLogger(CheckService::class.java)

    /**
     * Kitchen tickets (kitchen.printing=on), else null and nothing here changes.
     * Called only after the check's own transaction has committed, and never
     * allowed to throw back into it: a printer problem can't block a sale.
     */
    var kitchen: KitchenHook? = null

    /**
     * The forecourt (a gas station), else null. Told after a sale closes, loses
     * a line or ends, once that transaction has committed; never throws back.
     */
    var forecourt: dev.dwhipstock.pos.forecourt.ForecourtHook? = null

    /** Numbered orders (the quick-serve counter, carry-out), else null. See [CounterHook]. */
    var counter: CounterHook? = null

    /**
     * Refuses money and close/void on a check while a card payment for it is
     * still on the reader (a store restart mid-payment): see [CardPaymentGuard].
     * Null = no integrated card payments (and the old behaviour).
     */
    var cardGuard: CardPaymentGuard? = null

    /**
     * [statement] in a transaction, after the [cardGuard] has made sure no card
     * payment for [checkId] / [groupId] is still on the reader. The guard talks
     * to the reader, so it runs before (never inside) the transaction.
     */
    private fun <T> cardSafeTransaction(checkId: Int, groupId: Int?, statement: org.jetbrains.exposed.sql.Transaction.() -> T): T {
        cardGuard?.requireNoCardInFlight(checkId, groupId)
        return transaction(statement = statement)
    }

    private fun afterForecourt(view: CheckView): CheckView {
        val hook = forecourt ?: return view
        try { hook.checkChanged(view.id) } catch (e: Exception) { log.warn("forecourt hook failed: ${e.message}") }
        return view
    }

    private fun afterKitchen(view: CheckView): CheckView {
        afterForecourt(view)
        if (view.status == "CANCELLED") counter?.let { c ->
            try { c.cancelled(view.id) } catch (e: Exception) { log.warn("counter hook failed: ${e.message}") }
        }
        val hook = kitchen ?: return view
        if (view.status == "VOID" || view.status == "CANCELLED") {
            try { hook.checkEnded(view.id) } catch (e: Exception) { log.warn("kitchen hook failed: ${e.message}") }
        }
        return view
    }

    /** A line quantity within 1..[max] (400 qty_out_of_range): a typo can't ring 2 billion of anything. */
    private fun requireQty(qty: Int, max: Int = MoneyLimits.MAX_LINE_QTY) {
        if (qty !in 1..max) throw BadRequestException("qty must be 1-$max", "qty_out_of_range")
    }

    /** One unit at most $99,999.99 (400 price_too_high): no line total can overflow into a negative bill. */
    private fun requireUnitPrice(cents: Long) {
        if (cents <= 0) throw BadRequestException("price must be positive", "price_non_positive")
        if (cents > MoneyLimits.MAX_UNIT_PRICE_CENTS)
            throw BadRequestException("price must be at most ${MoneyLimits.MAX_UNIT_PRICE_CENTS} cents", "price_too_high")
    }

    /** Refuse when the table's zone is CLOSED. Call inside a transaction. */
    private fun requireZoneOpenForTable(tableId: String) {
        val status = DiningTables
            .join(Zones, JoinType.INNER, DiningTables.zoneId, Zones.id)
            .selectAll().where { DiningTables.id eq tableId }
            .firstOrNull()?.get(Zones.status)
        if (status == "CLOSED")
            throw ConflictException("table $tableId is in a closed zone", "zone_closed")
    }

    fun openCheck(tableId: String, userId: String): CheckView = transaction {
        // deleted tables refuse new checks — an old QR slip must not revive one
        val table = DiningTables.selectAll()
            .where { (DiningTables.id eq tableId) and DiningTables.deletedAt.isNull() }
            .firstOrNull() ?: throw NotFoundException("table $tableId not found")
        // the carry-out "table" holds many orders, each opened by number (CarryOutService)
        if (SaleLocations.isOffFloor(table[DiningTables.zoneId]))
            throw ConflictException("$tableId is not a table", "not_a_table")

        // idempotent: reopening a table with a live check returns that check
        val existing = Checks.selectAll()
            .where { (Checks.tableId eq tableId) and (Checks.status inList listOf("OPEN", "TOTAL_LOCKED")) }
            .firstOrNull()
        if (existing != null) return@transaction loadCheck(existing[Checks.id].value)

        // new checks only — existing open checks (returned above) stay usable in a
        // closed zone so staff can finish editing and tender them
        requireZoneOpenForTable(tableId)
        loadCheck(insertCheck(tableId, userId))
    }

    /**
     * A quick-serve counter order: always a NEW check on the counter's one
     * "table" (many orders are open there at once, each with its number).
     * Joins the caller's transaction. Returns the check id.
     */
    fun openCounterCheck(tableId: String, userId: String): Int = transaction {
        DiningTables.selectAll()
            .where { (DiningTables.id eq tableId) and DiningTables.deletedAt.isNull() }
            .firstOrNull() ?: throw NotFoundException("table $tableId not found")
        insertCheck(tableId, userId)
    }

    private fun insertCheck(tableId: String, userId: String): Int {
        val checkId = Checks.insertAndGetId {
            it[Checks.tableId] = tableId
            it[status] = "OPEN"
            it[openedBy] = userId
            it[openedAt] = VenueClock.now()
        }.value

        Outbox.write("check.opened", "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
            put("tableId", tableId)
            put("openedBy", userId)
        })
        return checkId
    }

    /**
     * Ring a menu item. The store checks it against the menu as it is NOW: an
     * item deleted or 86'd (maybe from the manager portal, synced down) or a
     * deleted size is refused (409 item_unavailable), and with
     * [expectedPriceCents] a price that changed since the client showed it is
     * refused too (409 price_changed + the new price) — never rung silently.
     */
    fun addLine(
        checkId: Int, itemId: String, variantId: String, qty: Int, note: String?, expectedPriceCents: Long? = null,
    ): CheckView = transaction {
        requireQty(qty)
        val note = CleanText.field(note)
        val check = requireCheck(checkId)
        if (check[Checks.status] != "OPEN") throw ConflictException("check $checkId is ${check[Checks.status]}; basket is closed", "check_not_open")
        MenuGuard.require(itemId, variantId, expectedPriceCents)

        val variant = ItemVariants.selectAll()
            .where { (ItemVariants.id eq variantId) and (ItemVariants.itemId eq itemId) and
                ItemVariants.deletedAt.isNull() }
            .firstOrNull() ?: throw NotFoundException("variant $variantId of item $itemId not found")
        val item = Items.selectAll().where { Items.id eq itemId }.first()
        val priced = ItemSchedules.price(itemId, variantId, variant[ItemVariants.priceCents])

        val lineId = CheckLines.insertAndGetId {
            it[CheckLines.checkId] = checkId
            it[CheckLines.itemId] = itemId
            it[CheckLines.variantId] = variantId
            it[CheckLines.qty] = qty
            it[unitPriceCents] = priced.unitPriceCents
            it[CheckLines.note] = note
            it[createdAt] = VenueClock.now()
            captureShelfFacts(it, item)
            LineSnapshot.capture(it, item, variant)
            captureSpecial(it, priced)
            variant[ItemVariants.costCents]?.let { c -> it[unitCostCents] = c }
        }.value

        // the same item tapped again: one line, qty 2 — never two lines of 1
        mergeTarget(checkId, lineId, qty)?.let { (target, merged) ->
            CheckLines.deleteWhere { CheckLines.id eq lineId }
            CheckLines.update({ CheckLines.id eq target }) { it[CheckLines.qty] = merged }
            Outbox.write("check.line_qty_changed", "check", checkId.toString(), buildJsonObject {
                put("checkId", checkId)
                put("lineId", target)
                put("qty", merged)
            })
            return@transaction loadCheck(checkId)
        }

        Outbox.write("check.line_added", "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
            put("lineId", lineId)
            put("itemId", itemId)
            put("variantId", variantId)
            put("qty", qty)
            put("unitPriceCents", priced.unitPriceCents)
            priced.tag?.let { put("regularUnitPriceCents", priced.regularCents) }
            note?.let { n -> put("note", n) }
        })
        loadCheck(checkId)
    }

    /**
     * The line the just-inserted [newLineId] folds into, and its new qty: an
     * earlier ACTIVE line on the check that is the very same thing as rung —
     * same item and size, no note on either, the same price and the same
     * snapshot (names, sizes, category, translations, tax / deposit / ID
     * facts, cost: [LineSnapshot]) — and still only on the bill: not sent to
     * the kitchen, not handed out to a split bill, not a fuel line. A line the
     * menu changed under (repriced, renamed) stays its own line, and so does
     * everything already in the kitchen. Null = keep the new line.
     */
    private fun mergeTarget(checkId: Int, newLineId: Int, addQty: Int): Pair<Int, Int>? {
        val new = CheckLines.selectAll().where { CheckLines.id eq newLineId }.first()
        if (new[CheckLines.note] != null || new[CheckLines.itemId] == null) return null
        val same = listOf(
            CheckLines.itemId, CheckLines.variantId, CheckLines.displayName, CheckLines.unitPriceCents,
            CheckLines.taxable, CheckLines.depositCents, CheckLines.ageRestricted, CheckLines.unitCostCents,
            CheckLines.nameFr, CheckLines.nameEn, CheckLines.variantLabelFr, CheckLines.variantLabelEn,
            CheckLines.categoryId, CheckLines.namesJson, CheckLines.variantNamesJson, CheckLines.showVariant,
            CheckLines.regularUnitPriceCents, CheckLines.specialJson,
        )
        val sent = KitchenSentLines.select(KitchenSentLines.lineId)
            .where { KitchenSentLines.checkId eq checkId }.map { it[KitchenSentLines.lineId] }.toSet()
        return CheckLines.selectAll().where {
            (CheckLines.checkId eq checkId) and (CheckLines.status eq "ACTIVE") and
                (CheckLines.id neq newLineId) and CheckLines.note.isNull() and
                (CheckLines.itemId eq new[CheckLines.itemId]) and (CheckLines.variantId eq new[CheckLines.variantId])
        }.orderBy(CheckLines.id).firstOrNull { row ->
            row[CheckLines.fuelSaleId] == null &&
                row[CheckLines.id].value !in sent &&
                same.all { col -> row[col] == new[col] } &&
                allocatedQtyForLine(row[CheckLines.id].value) == 0 &&
                row[CheckLines.qty] + addQty <= MoneyLimits.MAX_LINE_QTY
        }?.let { it[CheckLines.id].value to it[CheckLines.qty] + addQty }
    }

    /**
     * Open / misc item: ring something that isn't in the catalog — a name, a
     * price and a qty. Taxed and totalled like any line (the pipeline only ever
     * reads unit_price_cents × qty); renderers show [name] wherever a catalog
     * line would show the item name. No manager gate in v1 — the outbox event
     * is the audit trail. TODO: manager-gate behind a venue setting if abused.
     */
    fun addOpenLine(checkId: Int, rawName: String, unitPriceCents: Long, qty: Int, rawNote: String?): CheckView = transaction {
        requireQty(qty)
        val name = CleanText.field(rawName, max = 100) ?: throw IllegalArgumentException("name is required")
        val note = CleanText.field(rawNote)
        // capped, so unit × qty can never wrap past Long into a negative line (a free "−$12 gift card")
        requireUnitPrice(unitPriceCents)
        val check = requireCheck(checkId)
        if (check[Checks.status] != "OPEN") throw ConflictException("check $checkId is ${check[Checks.status]}; basket is closed", "check_not_open")

        val lineId = CheckLines.insertAndGetId {
            it[CheckLines.checkId] = checkId
            it[displayName] = name
            it[CheckLines.qty] = qty
            it[CheckLines.unitPriceCents] = unitPriceCents
            it[CheckLines.note] = note
            it[createdAt] = VenueClock.now()
        }.value

        Outbox.write("check.line_open_added", "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
            put("lineId", lineId)
            put("name", name)
            put("qty", qty)
            put("unitPriceCents", unitPriceCents)
            note?.let { n -> put("note", n) }
        })
        loadCheck(checkId)
    }

    /**
     * A fuel line (a completed postpay fuelling) or a prepay line: a catalog
     * fuel item at the price the pump or the customer set, qty 1, tied to its
     * [fuelSaleId] row. Only the forecourt adds these ([ForecourtService]);
     * the fuel items carry no added sales tax. Call inside its transaction.
     * Returns the new line's id.
     */
    fun addFuelLine(
        checkId: Int, itemId: String, variantId: String, unitPriceCents: Long, fuelSaleId: Int, unitCostCents: Long? = null,
    ): Int = transaction {
        require(unitPriceCents > 0) { "price must be positive" }
        val check = requireCheck(checkId)
        if (check[Checks.status] != "OPEN") throw ConflictException("check $checkId is ${check[Checks.status]}; basket is closed", "check_not_open")
        val item = Items.selectAll().where { Items.id eq itemId }.firstOrNull()
            ?: throw NotFoundException("fuel item $itemId not found", "fuel_item_missing")
        val lineId = CheckLines.insertAndGetId {
            it[CheckLines.checkId] = checkId
            it[CheckLines.itemId] = itemId
            it[CheckLines.variantId] = variantId
            it[qty] = 1
            it[CheckLines.unitPriceCents] = unitPriceCents
            it[createdAt] = VenueClock.now()
            it[CheckLines.fuelSaleId] = fuelSaleId
            captureShelfFacts(it, item)
            LineSnapshot.capture(it, item, ItemVariants.selectAll().where { ItemVariants.id eq variantId }.firstOrNull())
            unitCostCents?.let { c -> it[CheckLines.unitCostCents] = c }
        }.value
        dev.dwhipstock.pos.forecourt.FuelSales.update({ dev.dwhipstock.pos.forecourt.FuelSales.id eq fuelSaleId }) {
            it[dev.dwhipstock.pos.forecourt.FuelSales.lineId] = lineId
        }
        Outbox.write("check.line_added", "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
            put("lineId", lineId)
            put("itemId", itemId)
            put("variantId", variantId)
            put("qty", 1)
            put("unitPriceCents", unitPriceCents)
            put("fuelSaleId", fuelSaleId)
        })
        lineId
    }

    /**
     * Customer scan-to-order (M3): submitted basket lands as PENDING lines on the
     * table's open check (auto-opened if none). Staff accept-before-fire — pending
     * lines don't count, don't print, and block tendering until resolved.
     *
     * Partial: a line whose item was deleted / 86'd or repriced since the
     * guest's menu loaded is refused on its own ([CheckView.rejected]) and the
     * rest go through; every line refused → 409 lines_rejected, nothing added.
     */
    fun submitPendingLines(tableId: String, lines: List<PendingLineRequest>): CheckView = transaction {
        require(lines.isNotEmpty()) { "empty basket" }
        // an unauthenticated phone: kiosk-sized baskets only (red team 2026-10-01)
        if (lines.size > MoneyLimits.MAX_GUEST_BASKET_LINES)
            throw BadRequestException("at most ${MoneyLimits.MAX_GUEST_BASKET_LINES} lines per order", "too_many_lines")
        lines.forEach { requireQty(it.qty, MoneyLimits.MAX_GUEST_LINE_QTY) }
        // machine surface: reject QR orders for a closed zone outright (the customer
        // menu already hides ordering, this guards the raw endpoint)
        requireZoneOpenForTable(tableId)
        val rejected = lines.mapIndexedNotNull { i, l -> MenuGuard.check(i, l.itemId, l.variantId, l.expectedPriceCents) }
        if (rejected.size == lines.size) throw LineRejectedException(rejected, "lines_rejected")
        val refused = rejected.map { it.index }.toSet()
        val check = openCheck(tableId, userId = "qr-customer")
        if (check.status != "OPEN") throw ConflictException("table $tableId bill is being paid; ask staff", "bill_locked")
        // pending lines block paying the bill until staff resolve them: never a wall of them
        if (check.pendingLines.size + lines.size - refused.size > MoneyLimits.MAX_PENDING_LINES_PER_CHECK)
            throw ConflictException("too many orders waiting on this table; ask staff", "too_many_pending")
        for ((index, line) in lines.withIndex()) {
            if (index in refused) continue
            val variant = ItemVariants.selectAll()
                .where { (ItemVariants.id eq line.variantId) and (ItemVariants.itemId eq line.itemId) and
                    ItemVariants.deletedAt.isNull() }
                .firstOrNull() ?: throw NotFoundException("variant ${line.variantId} not found")
            val item = Items.selectAll().where { Items.id eq line.itemId }.first()
            val priced = ItemSchedules.price(line.itemId, line.variantId, variant[ItemVariants.priceCents])
            val lineId = CheckLines.insertAndGetId {
                it[checkId] = check.id
                it[itemId] = line.itemId
                it[variantId] = line.variantId
                it[qty] = line.qty
                it[unitPriceCents] = priced.unitPriceCents
                captureSpecial(it, priced)
                it[note] = CleanText.field(line.note)
                it[status] = "PENDING"
                it[createdAt] = VenueClock.now()
                captureShelfFacts(it, item)
                LineSnapshot.capture(it, item, variant)
            }.value
            Outbox.write("check.pending_line_submitted", "check", check.id.toString(), buildJsonObject {
                put("checkId", check.id)
                put("lineId", lineId)
                put("itemId", line.itemId)
                put("variantId", line.variantId)
                put("qty", line.qty)
                CleanText.field(line.note)?.let { n -> put("note", n) }
            })
        }
        loadCheck(check.id).copy(rejected = rejected)
    }

    fun acceptPendingLine(checkId: Int, lineId: Int): CheckView = transaction {
        val check = requireCheck(checkId)
        if (check[Checks.status] != "OPEN") throw ConflictException("check $checkId is ${check[Checks.status]}")
        val updated = CheckLines.update({
            (CheckLines.id eq lineId) and (CheckLines.checkId eq checkId) and (CheckLines.status eq "PENDING")
        }) { it[status] = "ACTIVE" }
        if (updated == 0) throw NotFoundException("pending line $lineId not on check $checkId")
        Outbox.write("check.pending_line_accepted", "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
            put("lineId", lineId)
        })
        loadCheck(checkId)
    }

    fun rejectPendingLine(checkId: Int, lineId: Int): CheckView = afterKitchen(rejectPendingLineTx(checkId, lineId))

    private fun rejectPendingLineTx(checkId: Int, lineId: Int): CheckView = transaction {
        val removed = CheckLines.deleteWhere {
            (CheckLines.id eq lineId) and (CheckLines.checkId eq checkId) and (CheckLines.status eq "PENDING")
        }
        if (removed == 0) throw NotFoundException("pending line $lineId not on check $checkId")
        Outbox.write("check.pending_line_rejected", "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
            put("lineId", lineId)
        })
        cancelIfEmpty(checkId)
        loadCheck(checkId)
    }

    /** Staff turn away every guest line still waiting on this check at once (a flood of QR orders). */
    fun rejectAllPendingLines(checkId: Int): CheckView = afterKitchen(transaction {
        requireCheck(checkId)
        val ids = CheckLines.selectAll()
            .where { (CheckLines.checkId eq checkId) and (CheckLines.status eq "PENDING") }
            .map { it[CheckLines.id].value }
        if (ids.isNotEmpty()) {
            CheckLines.deleteWhere { (CheckLines.id inList ids) and (CheckLines.status eq "PENDING") }
            Outbox.write("check.pending_lines_rejected", "check", checkId.toString(), buildJsonObject {
                put("checkId", checkId)
                put("lineIds", JsonArray(ids.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            })
            cancelIfEmpty(checkId)
        }
        loadCheck(checkId)
    })

    /** Stage-1 basket edit: change quantity on an ACTIVE line while OPEN. */
    fun setLineQty(checkId: Int, lineId: Int, qty: Int): CheckView = transaction {
        requireQty(qty)
        val check = requireCheck(checkId)
        if (check[Checks.status] != "OPEN") throw ConflictException("check $checkId is ${check[Checks.status]}; basket is closed", "check_not_open")
        // more of an item that has since been 86'd or deleted is refused like a new line
        // (409 item_unavailable); fewer is always fine
        CheckLines.selectAll().where { (CheckLines.id eq lineId) and (CheckLines.checkId eq checkId) }.firstOrNull()
            ?.let { row ->
                val itemId = row[CheckLines.itemId]
                val variantId = row[CheckLines.variantId]
                if (qty > row[CheckLines.qty] && itemId != null && variantId != null && row[CheckLines.fuelSaleId] == null)
                    MenuGuard.require(itemId, variantId, expectedPriceCents = null)
            }
        // can't shrink below what the split has already handed out — unassign first
        val allocated = allocatedQtyForLine(lineId)
        if (qty < allocated)
            throw ConflictException("line $lineId has $allocated allocated to bill groups; unassign first", "qty_below_allocated")
        // one fuelling is one line: its amount is what the pump says
        val fuel = CheckLines.selectAll().where { CheckLines.id eq lineId }.firstOrNull()?.get(CheckLines.fuelSaleId)
        if (fuel != null && qty != 1) throw ConflictException("a fuel line is one fuelling", "fuel_line_fixed")
        val updated = CheckLines.update({
            (CheckLines.id eq lineId) and (CheckLines.checkId eq checkId) and (CheckLines.status eq "ACTIVE")
        }) { it[CheckLines.qty] = qty }
        if (updated == 0) throw NotFoundException("line $lineId not on check $checkId")
        Outbox.write("check.line_qty_changed", "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
            put("lineId", lineId)
            put("qty", qty)
        })
        loadCheck(checkId)
    }

    /** Stage-1 basket edit. Only while OPEN — after total lock the basket is frozen. */
    fun removeLine(checkId: Int, lineId: Int): CheckView = afterKitchen(removeLineTx(checkId, lineId))

    private fun removeLineTx(checkId: Int, lineId: Int): CheckView = transaction {
        val check = requireCheck(checkId)
        if (check[Checks.status] != "OPEN") throw ConflictException("check $checkId is ${check[Checks.status]}; basket is closed", "check_not_open")
        val removed = CheckLines.deleteWhere {
            (CheckLines.id eq lineId) and (CheckLines.checkId eq checkId) and (CheckLines.status eq "ACTIVE")
        }
        if (removed == 0) throw NotFoundException("line $lineId not on check $checkId")
        // a deleted item leaves the split too — its allocations go with it
        BillGroupAllocations.deleteWhere { BillGroupAllocations.lineId eq lineId }
        Outbox.write("check.line_removed", "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
            put("lineId", lineId)
        })
        cancelIfEmpty(checkId)
        loadCheck(checkId)
    }

    fun setCorkage(checkId: Int, bottles: Int): CheckView = transaction {
        require(bottles >= 0) { "bottles must be >= 0" }
        val check = requireCheck(checkId)
        if (check[Checks.status] != "OPEN") throw ConflictException("check $checkId is ${check[Checks.status]}")
        Checks.update({ Checks.id eq checkId }) { it[corkageBottles] = bottles }
        Outbox.write("check.corkage_set", "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
            put("bottles", bottles)
        })
        loadCheck(checkId)
    }

    // --- settlement-time split: bill groups ---
    //
    // A split partitions the check's ACTIVE lines into bill groups via per-group
    // qty allocations (2 of 3 beers in group A, 1 in B — lines are never cloned).
    // Each group's total runs through the same pricing pipeline (computeTotals)
    // over its allocated quantities; percentage fees therefore assess per group
    // with integer floor division, so a split's group totals can drop cents
    // relative to the unsplit check — that per-group DOWN drop is deliberate and
    // becomes the check's locked grand total (sum of group totals). Cash rounding
    // stays a tender-time concern: each group's cash due rounds to the nickel on
    // its own through the same RoundingPolicy; electronic tenders settle exact cents.
    //
    // The split is editable only while the check is OPEN. The first group tender
    // locks totals (check → TOTAL_LOCKED), which freezes the split too — further
    // changes require a void. The check finalizes only when EVERY group is covered.

    /**
     * Create a split with [groups] empty by-item groups (or money-only ÷N groups
     * when [evenAmounts] is set). Refused once any money is involved — splitting
     * after payment starts is out of scope; clear guards instead of surprises.
     */
    fun createSplit(checkId: Int, groups: Int): CheckView = transaction {
        requireSplittable(checkId, groups)
        for (n in 1..groups) insertGroup(checkId, n, includesCorkage = n == 1, fixedAmountCents = null)
        Outbox.write("split.created", "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
            put("groups", groups)
        })
        loadCheck(checkId)
    }

    /**
     * Even split ÷N: money-only groups, no line assignment. floor(total/N)
     * each, and the leftover cents one each to the first guests (never all on
     * guest 1), so no two shares differ by more than 1¢. Group totals always
     * sum exactly to the check's grand total; paid in cash, the shares round
     * once for the whole bill ([TransactionPipeline.evenSplitCashDue]).
     */
    fun createEvenSplit(checkId: Int, groups: Int): CheckView = transaction {
        requireSplittable(checkId, groups)
        val total = computeTotals(requireCheck(checkId)).grandTotal.cents
        val share = total / groups
        val leftover = total - share * groups
        for (n in 1..groups) {
            val amount = share + if (n <= leftover) 1 else 0
            insertGroup(checkId, n, includesCorkage = n == 1, fixedAmountCents = amount)
        }
        Outbox.write("split.created", "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
            put("groups", groups)
            put("even", true)
            put("totalCents", total)
        })
        loadCheck(checkId)
    }

    fun addSplitGroup(checkId: Int): CheckView = transaction {
        val groups = requireEditableSplit(checkId, byItemOnly = true)
        val next = groups.maxOf { it[BillGroups.groupNumber] } + 1
        val groupId = insertGroup(checkId, next, includesCorkage = false, fixedAmountCents = null)
        Outbox.write("split.group_added", "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
            put("groupId", groupId)
            put("groupNumber", next)
        })
        loadCheck(checkId)
    }

    /** Delete a group; its allocations return to unassigned. The last group can't go — use clearSplit. */
    fun deleteSplitGroup(checkId: Int, groupId: Int): CheckView = transaction {
        val groups = requireEditableSplit(checkId, byItemOnly = true)
        val group = groups.find { it[BillGroups.id].value == groupId }
            ?: throw NotFoundException("group $groupId not on check $checkId", "group_not_found")
        if (groups.size == 1) throw ConflictException("can't delete the last group; clear the split instead", "last_group")
        BillGroupAllocations.deleteWhere { BillGroupAllocations.groupId eq groupId }
        BillGroups.deleteWhere { BillGroups.id eq groupId }
        // the corkage carrier must always exist while split — hand it to the lowest survivor
        if (group[BillGroups.includesCorkage]) {
            val heir = groups.filter { it[BillGroups.id].value != groupId }
                .minBy { it[BillGroups.groupNumber] }[BillGroups.id].value
            BillGroups.update({ BillGroups.id eq heir }) { it[includesCorkage] = true }
        }
        Outbox.write("split.group_deleted", "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
            put("groupId", groupId)
        })
        loadCheck(checkId)
    }

    /** Assign [qty] of a line's unassigned quantity to a group (upsert: adds to any existing allocation). */
    fun assignLineToGroup(checkId: Int, groupId: Int, lineId: Int, qty: Int): CheckView = transaction {
        require(qty > 0) { "qty must be positive" }
        val groups = requireEditableSplit(checkId, byItemOnly = true)
        if (groups.none { it[BillGroups.id].value == groupId })
            throw NotFoundException("group $groupId not on check $checkId", "group_not_found")
        val line = CheckLines.selectAll()
            .where { (CheckLines.id eq lineId) and (CheckLines.checkId eq checkId) and (CheckLines.status eq "ACTIVE") }
            .firstOrNull() ?: throw NotFoundException("line $lineId not on check $checkId")
        val allocated = allocatedQtyForLine(lineId)
        if (qty > line[CheckLines.qty] - allocated)
            throw ConflictException("only ${line[CheckLines.qty] - allocated} of line $lineId unassigned", "qty_exceeds_unassigned")

        val existing = BillGroupAllocations.selectAll()
            .where { (BillGroupAllocations.groupId eq groupId) and (BillGroupAllocations.lineId eq lineId) }
            .firstOrNull()
        if (existing != null) {
            BillGroupAllocations.update({ BillGroupAllocations.id eq existing[BillGroupAllocations.id] }) {
                it[BillGroupAllocations.qty] = existing[BillGroupAllocations.qty] + qty
            }
        } else {
            BillGroupAllocations.insert {
                it[BillGroupAllocations.groupId] = groupId
                it[BillGroupAllocations.lineId] = lineId
                it[BillGroupAllocations.qty] = qty
            }
        }
        Outbox.write("split.line_assigned", "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
            put("groupId", groupId)
            put("lineId", lineId)
            put("qty", qty)
        })
        loadCheck(checkId)
    }

    /** Return [qty] of a group's allocation back to unassigned. */
    fun unassignLineFromGroup(checkId: Int, groupId: Int, lineId: Int, qty: Int): CheckView = transaction {
        require(qty > 0) { "qty must be positive" }
        requireEditableSplit(checkId, byItemOnly = true)
        val allocation = BillGroupAllocations.selectAll()
            .where { (BillGroupAllocations.groupId eq groupId) and (BillGroupAllocations.lineId eq lineId) }
            .firstOrNull() ?: throw NotFoundException("line $lineId not allocated to group $groupId")
        if (qty > allocation[BillGroupAllocations.qty])
            throw ConflictException("only ${allocation[BillGroupAllocations.qty]} allocated", "qty_exceeds_allocated")
        if (qty == allocation[BillGroupAllocations.qty]) {
            BillGroupAllocations.deleteWhere { BillGroupAllocations.id eq allocation[BillGroupAllocations.id] }
        } else {
            BillGroupAllocations.update({ BillGroupAllocations.id eq allocation[BillGroupAllocations.id] }) {
                it[BillGroupAllocations.qty] = allocation[BillGroupAllocations.qty] - qty
            }
        }
        Outbox.write("split.line_unassigned", "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
            put("groupId", groupId)
            put("lineId", lineId)
            put("qty", qty)
        })
        loadCheck(checkId)
    }

    /** Drop the whole split; all lines return to the single-bill flow. */
    fun clearSplit(checkId: Int): CheckView = transaction {
        val groups = requireEditableSplit(checkId)
        val ids = groups.map { it[BillGroups.id].value }
        BillGroupAllocations.deleteWhere { BillGroupAllocations.groupId inList ids }
        BillGroups.deleteWhere { BillGroups.checkId eq checkId }
        Outbox.write("split.cleared", "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
        })
        loadCheck(checkId)
    }

    /** Move the check-level corkage fee to another group (defaults to group 1 at split creation). */
    fun moveCorkage(checkId: Int, groupId: Int): CheckView = transaction {
        val groups = requireEditableSplit(checkId, byItemOnly = true)
        if (groups.none { it[BillGroups.id].value == groupId })
            throw NotFoundException("group $groupId not on check $checkId", "group_not_found")
        BillGroups.update({ BillGroups.checkId eq checkId }) { it[includesCorkage] = false }
        BillGroups.update({ BillGroups.id eq groupId }) { it[includesCorkage] = true }
        Outbox.write("split.corkage_moved", "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
            put("groupId", groupId)
        })
        loadCheck(checkId)
    }

    private fun insertGroup(checkId: Int, number: Int, includesCorkage: Boolean, fixedAmountCents: Long?): Int =
        BillGroups.insertAndGetId {
            it[BillGroups.checkId] = checkId
            it[groupNumber] = number
            it[BillGroups.includesCorkage] = includesCorkage
            it[BillGroups.fixedAmountCents] = fixedAmountCents
            it[createdAt] = VenueClock.now()
        }.value

    private fun splitGroups(checkId: Int): List<ResultRow> =
        BillGroups.selectAll().where { BillGroups.checkId eq checkId }
            .orderBy(BillGroups.groupNumber).toList()

    private fun allocatedQtyForLine(lineId: Int): Int =
        BillGroupAllocations.selectAll().where { BillGroupAllocations.lineId eq lineId }
            .sumOf { it[BillGroupAllocations.qty] }

    private fun requireSplittable(checkId: Int, groups: Int) {
        require(groups in 2..20) { "groups must be 2..20" }
        val check = requireCheck(checkId)
        when (check[Checks.status]) {
            "OPEN" -> {}
            "TOTAL_LOCKED" -> throw ConflictException("check $checkId already has money applied; can't split", "split_locked")
            else -> throw ConflictException("check $checkId is ${check[Checks.status]}; can't split", "check_not_open")
        }
        val pending = CheckLines.selectAll()
            .where { (CheckLines.checkId eq checkId) and (CheckLines.status eq "PENDING") }.count()
        if (pending > 0) throw ConflictException("check $checkId has $pending pending QR lines; accept or reject them first", "pending_lines_unresolved")
        val active = CheckLines.selectAll()
            .where { (CheckLines.checkId eq checkId) and (CheckLines.status eq "ACTIVE") }.count()
        if (active == 0L) throw ConflictException("check $checkId has no items to split", "empty_check")
        if (splitGroups(checkId).isNotEmpty()) throw ConflictException("check $checkId is already split", "split_exists")
    }

    /**
     * The split is editable only while the check is OPEN — the first group tender
     * flips it to TOTAL_LOCKED, freezing the partition (void to undo). By-item
     * mutations (assign/move/add group) are meaningless on an even ÷N split.
     */
    private fun requireEditableSplit(checkId: Int, byItemOnly: Boolean = false): List<ResultRow> {
        val check = requireCheck(checkId)
        val groups = splitGroups(checkId)
        if (groups.isEmpty()) throw ConflictException("check $checkId is not split", "no_split")
        if (check[Checks.status] != "OPEN")
            throw ConflictException("check $checkId is ${check[Checks.status]}; split is locked once money is applied", "split_locked")
        if (byItemOnly && groups.any { it[BillGroups.fixedAmountCents] != null })
            throw ConflictException("check $checkId is split evenly; by-item edits don't apply", "even_split")
        return groups
    }

    /**
     * Cash tender. Stage 4 (total lock) happens implicitly at first tender;
     * stage 5 rounds the CASH due only (to the nickel, per the store's
     * cash.rounding) — and only when this payment settles the check (partial
     * cash applies at face value). Change is given from the rounded amount.
     * On a split check [groupId] is required and the tender pays into that
     * group: rounding applies to the GROUP's cash due, independently per group.
     */
    fun tenderCash(checkId: Int, amountTenderedCents: Long, groupId: Int? = null): TenderView = cardSafeTransaction(checkId, groupId) {
        val outstanding = lockAndOutstanding(checkId, groupId)
        val cashDue = groupId?.let { evenShareCashDue(checkId, it) }
        val due = cashDue ?: config.roundingPolicy.roundCashDue(outstanding)
        // a sane bound on the change: one fat-fingered amount used to be recorded and
        // overflow the X / Z reports for the rest of the shift (the whole call rolls back)
        if (amountTenderedCents > due.cents + MoneyLimits.MAX_CASH_OVER_DUE_CENTS)
            throw BadRequestException("cash tendered is more than ${MoneyLimits.MAX_CASH_OVER_DUE_CENTS} cents over the amount due",
                "cash_amount_too_high")
        val result = TransactionPipeline.tenderCash(outstanding, Money(amountTenderedCents), config, cashDue)
        recordTender(checkId, TenderType.CASH, "check.tendered",
            amountTenderedCents, result.amountApplied, result.roundingAdjustment, result.change, groupId)
    }

    /**
     * An even ÷N group's cash due while nothing is paid on it yet: its share
     * of the bill rounded once for the whole table
     * ([TransactionPipeline.evenSplitCashDue]). Null = round its balance as usual
     * (a by-item group, or a group already partly paid).
     */
    private fun evenShareCashDue(checkId: Int, groupId: Int): Money? {
        val groups = splitGroups(checkId)
        if (groups.none { it[BillGroups.fixedAmountCents] != null }) return null
        val index = groups.indexOfFirst { it[BillGroups.id].value == groupId }
        if (index < 0 || !groupTenderedSoFar(groupId).isZero) return null
        val shares = groups.map { Money(it[BillGroups.lockedTotalCents] ?: it[BillGroups.fixedAmountCents] ?: 0L) }
        return TransactionPipeline.evenSplitCashDue(shares, config.roundingPolicy)[index]
    }

    /**
     * Confirm-then-record electronic tender, step 1: lock totals if needed and
     * hand back payment instructions (card terminal / bank details).
     * No tender row yet — money hasn't moved.
     */
    fun initiateElectronicTender(checkId: Int, type: TenderType, amountCents: Long?, groupId: Int? = null): TenderInstructions = cardSafeTransaction(checkId, groupId) {
        val method = config.tenderMethod(type)
            ?: throw ConflictException("${config.displayName} does not accept $type", "tender_type_not_accepted")
        val outstanding = lockAndOutstanding(checkId, groupId)
        val amount = Money(amountCents ?: outstanding.cents)
        require(amount > Money.ZERO && amount <= outstanding) { "amount must be within outstanding balance" }

        val event = "check.tender_initiated"
        Outbox.write(event, "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
            put("type", type.name)
            put("amountCents", amount.cents)
            groupId?.let { g -> put("groupId", g) }
        })
        method.instructions(amount)
    }

    /** Step 2: staff saw the money arrive in their bank app — record the tender. Exact cents, no rounding. */
    fun confirmElectronicTender(checkId: Int, type: TenderType, amountCents: Long, groupId: Int? = null): TenderView = cardSafeTransaction(checkId, groupId) {
        if (config.tenderMethod(type) == null) throw ConflictException("${config.displayName} does not accept $type", "tender_type_not_accepted")
        val outstanding = lockAndOutstanding(checkId, groupId)
        val applied = TransactionPipeline.tenderElectronic(outstanding, Money(amountCents))
        recordTender(checkId, type, "check.tender_confirmed", amountCents, applied, Money.ZERO, Money.ZERO, groupId)
    }

    /**
     * Stripe step 1: lock totals if needed and return what is still due on the
     * check (or bill group) — the most a card PaymentIntent may be for. Same
     * guards as every tender (open shift, split rules, not already paid).
     */
    fun lockForElectronicPayment(checkId: Int, groupId: Int? = null): Money = transaction {
        lockAndOutstanding(checkId, groupId)
    }

    /**
     * Stripe final step: record the tender for a PaymentIntent Stripe reports as
     * captured. Exact cents, no rounding, through the ordinary tender path (same
     * outbox event as any confirmed electronic tender, type STRIPE + the PI id).
     */
    fun recordStripeTender(
        checkId: Int, amountCents: Long, paymentIntentId: String, groupId: Int? = null,
        card: dev.dwhipstock.pos.payments.terminal.CardDetails? = null,
    ): TenderView = transaction {
        val outstanding = lockAndOutstanding(checkId, groupId)
        val applied = TransactionPipeline.tenderElectronic(outstanding, Money(amountCents))
        recordTender(checkId, TenderType.STRIPE, "check.tender_confirmed", amountCents, applied,
            Money.ZERO, Money.ZERO, groupId, stripePaymentIntentId = paymentIntentId, card = card, tipCents = card?.tipCents ?: 0L)
    }

    /**
     * Integrated terminal (simulator, J.P. Morgan) final step: record the
     * tender for a payment the terminal approved. Same path and outbox event as
     * a Stripe tender, type TERMINAL + the terminal's payment id + [provider].
     */
    fun recordTerminalTender(
        checkId: Int, amountCents: Long, terminalRef: String, provider: String, groupId: Int? = null,
        card: dev.dwhipstock.pos.payments.terminal.CardDetails? = null,
    ): TenderView = transaction {
        val outstanding = lockAndOutstanding(checkId, groupId)
        val applied = TransactionPipeline.tenderElectronic(outstanding, Money(amountCents))
        recordTender(checkId, TenderType.TERMINAL, "check.tender_confirmed", amountCents, applied,
            Money.ZERO, Money.ZERO, groupId, terminalPaymentRef = terminalRef, terminalProvider = provider, card = card, tipCents = card?.tipCents ?: 0L)
    }

    private fun lockAndOutstanding(checkId: Int, groupId: Int? = null): Money {
        // money movement needs a shift to land in — otherwise the Z-report's
        // drawer math can never account for this cash (walkthrough 2026-07-08)
        currentOpenShiftId()
            ?: throw ConflictException("no open shift; open a shift before taking payment", "no_open_shift")
        var check = requireCheck(checkId)
        val groups = splitGroups(checkId)
        // a split check settles per group; an unsplit check must not name one
        if (groups.isNotEmpty() && groupId == null)
            throw ConflictException("check $checkId is split; tender a specific group", "group_required")
        if (groups.isEmpty() && groupId != null)
            throw ConflictException("check $checkId is not split", "no_split")
        // age-restricted items wait for a passing ID check (retail; no pub line is restricted)
        AgeGate.requireCleared(checkId)
        when (check[Checks.status]) {
            "OPEN" -> { lockTotals(checkId); check = requireCheck(checkId) }
            "TOTAL_LOCKED" -> {}
            else -> throw ConflictException("check $checkId is ${check[Checks.status]}", "check_not_open")
        }
        if (groupId == null) {
            val outstanding = Money(check[Checks.lockedGrandTotalCents]!!) - tenderedSoFar(checkId)
            if (outstanding.isZero) throw ConflictException("check $checkId is fully tendered", "already_paid")
            return outstanding
        }
        val group = BillGroups.selectAll()
            .where { (BillGroups.id eq groupId) and (BillGroups.checkId eq checkId) }.firstOrNull()
            ?: throw NotFoundException("group $groupId not on check $checkId", "group_not_found")
        val outstanding = Money(group[BillGroups.lockedTotalCents]!!) - groupTenderedSoFar(groupId)
        if (outstanding.isZero) throw ConflictException("group $groupId is fully tendered", "group_already_paid")
        return outstanding
    }

    private fun recordTender(
        checkId: Int, type: TenderType, eventType: String,
        tenderedCents: Long, applied: Money, rounding: Money, change: Money,
        groupId: Int? = null,
        stripePaymentIntentId: String? = null,
        terminalPaymentRef: String? = null,
        terminalProvider: String? = null,
        card: dev.dwhipstock.pos.payments.terminal.CardDetails? = null,
        /** A card tip on top of the bill (reader / Stripe); never part of [applied]. */
        tipCents: Long = 0,
    ): TenderView {
        require(tipCents >= 0) { "tip must not be negative" }
        val tenderId = Tenders.insertAndGetId {
            it[Tenders.tipCents] = tipCents
            it[Tenders.stripePaymentIntentId] = stripePaymentIntentId
            it[Tenders.terminalPaymentRef] = terminalPaymentRef
            it[Tenders.cardJson] = card?.let(::encodeCard)
            it[transactionId] = checkId
            it[Tenders.type] = type.name
            it[amountTenderedCents] = tenderedCents
            it[amountAppliedCents] = applied.cents
            it[roundingAdjustmentCents] = rounding.cents
            it[changeCents] = change.cents
            it[billGroupId] = groupId
            it[createdAt] = VenueClock.now()
        }.value
        Outbox.write(eventType, "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
            put("tenderId", tenderId)
            put("type", type.name)
            put("amountTenderedCents", tenderedCents)
            put("amountAppliedCents", applied.cents)
            put("roundingAdjustmentCents", rounding.cents)
            put("changeCents", change.cents)
            groupId?.let { g -> put("groupId", g) }
            if (tipCents > 0) put("tipCents", tipCents)
            // processor reference only — never a key, card data or client secret
            stripePaymentIntentId?.let { pi -> put("processor", "stripe"); put("stripePaymentIntentId", pi) }
            terminalPaymentRef?.let { ref -> put("processor", terminalProvider ?: "terminal"); put("terminalPaymentRef", ref) }
            // brand + last 4 only (what the receipt shows); never a full card number
            card?.let { c -> c.brand?.let { put("cardBrand", it) }; c.last4?.let { put("cardLast4", it) }; put("entryMode", c.entryMode.wire) }
        })
        if (groupId != null) {
            val group = BillGroups.selectAll().where { BillGroups.id eq groupId }.first()
            val outstanding = Money(group[BillGroups.lockedTotalCents]!!) - groupTenderedSoFar(groupId)
            Outbox.write("split.group_tendered", "check", checkId.toString(), buildJsonObject {
                put("checkId", checkId)
                put("groupId", groupId)
                put("tenderId", tenderId)
                put("amountAppliedCents", applied.cents)
                put("groupOutstandingCents", outstanding.cents)
            })
        }
        return TenderView(tenderId, type.name, tenderedCents, applied.cents, rounding.cents, change.cents, groupId, tipCents)
    }

    fun finalizeCheck(checkId: Int): CheckView = afterForecourt(cardGuard?.requireNoCardInFlight(checkId, null).let { finalizeCheckTx(checkId) }).also { view ->
        counter?.let { c ->
            try { c.afterPaid(view.id) } catch (e: Exception) { log.warn("counter hook failed: ${e.message}") }
        }
    }

    private fun finalizeCheckTx(checkId: Int): CheckView = transaction {
        val check = requireCheck(checkId)
        if (check[Checks.status] != "TOTAL_LOCKED") throw ConflictException("check $checkId is ${check[Checks.status]}; tender first")
        val outstanding = Money(check[Checks.lockedGrandTotalCents]!!) - tenderedSoFar(checkId)
        if (!outstanding.isZero) throw ConflictException("check $checkId has ${outstanding.cents} cents outstanding", "outstanding_balance")
        // split guard: every group must be individually covered, not just the sum
        // (per-group tenders can't overshoot, so this is belt-and-braces — but the
        // rule is the spec's invariant, so enforce it explicitly)
        for (group in splitGroups(checkId)) {
            val gid = group[BillGroups.id].value
            val due = Money(group[BillGroups.lockedTotalCents]!!) - groupTenderedSoFar(gid)
            if (!due.isZero) throw ConflictException("group $gid has ${due.cents} cents outstanding", "group_outstanding")
        }

        // a sale closes into a shift, like every tender: one closed outside any
        // shift was in no Z report ever (red team 2026-10-01)
        val shift = currentOpenShiftId()
            ?: throw ConflictException("no open shift; open a shift before closing a bill", "no_open_shift")
        val now = VenueClock.now()
        Checks.update({ Checks.id eq checkId }) {
            it[status] = "CLOSED"
            it[closedAt] = now
            it[shiftId] = shift
        }
        // a counter order is committed here, before its receipt prints (its number is on it)
        counter?.paid(checkId)
        Outbox.write("check.closed", "check", checkId.toString(),
            closedCheckPayload(check, shift, now))
        // stage 5 epilogue: cashier receipt through the customer's printer adapter
        val lines = ReceiptRenderer.render(buildReceipt(checkId), receiptPolicyFor(check))
        config.printer.print(PrintJob(checkId, lines, meta = buildMap {
            shift?.let { s -> put("shiftId", s.toString()) }
        }))
        loadCheck(checkId)
    }

    /**
     * Provisional customer bill ("L’addition, s’il vous plaît" / check please): render the check's current
     * state and spool it to the bills/ directory. Deliberately non-mutating — the check
     * stays OPEN and editable, so this is callable any number of times as items come and
     * go. Refuses on a check that's no longer live (400 check_not_billable) and on
     * unresolved QR lines (409, same guard as tender). Rounding is a tender-time concern
     * (pipeline stage 5): the bill shows the exact grand total, then — when paying
     * in cash would round — the rounding and the cash total underneath it.
     * Returns the rendered text for the client preview.
     */
    /**
     * The check as the bill shows it (items, taxes, total), with what paying it
     * all in cash comes to (to the nickel). Nothing is printed or recorded: the
     * kiosk's guest ticket lays it out its own way.
     */
    fun billSnapshot(checkId: Int): Receipt = transaction {
        val built = buildReceipt(checkId)
        val due = built.grandTotal - tenderedSoFar(checkId)
        built.copy(
            cashDue = config.roundingPolicy.roundCashDue(due),
            cashRounding = config.roundingPolicy.cashAdjustment(due),
        )
    }

    fun printBill(checkId: Int, groupId: Int? = null, lang: String? = null): String = transaction {
        val check = requireCheck(checkId)
        if (check[Checks.status] !in listOf("OPEN", "TOTAL_LOCKED")) {
            throw BadRequestException("check $checkId is ${check[Checks.status]}; no bill to print", "check_not_billable")
        }
        val pending = CheckLines.selectAll()
            .where { (CheckLines.checkId eq checkId) and (CheckLines.status eq "PENDING") }.count()
        if (pending > 0) throw ConflictException("check $checkId has $pending pending QR lines; accept or reject them first", "pending_lines_unresolved")

        val built = if (groupId == null) buildReceipt(checkId) else buildGroupReceipt(checkId, groupId)
        // what is still due, and what it comes to in cash (to the nickel)
        val paid = if (groupId == null) tenderedSoFar(checkId) else groupTenderedSoFar(groupId)
        val due = built.grandTotal - paid
        val cashDue = groupId?.let { evenShareCashDue(checkId, it) } ?: config.roundingPolicy.roundCashDue(due)
        val receipt = built.copy(
            cashDue = cashDue,
            cashRounding = cashDue - due,
        )
        val lines = ReceiptRenderer.render(receipt, receiptPolicyFor(check, lang), ReceiptKind.PROVISIONAL)
        val text = config.printer.printProvisional(PrintJob(checkId, lines))
        Outbox.write("check.bill_printed", "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
            put("lineCount", receipt.items.size)
            put("grandTotalCents", receipt.grandTotal.cents)
            groupId?.let { g -> put("groupId", g) }
        })
        text
    }

    /**
     * Provisional bill for ONE group of a split check: only its allocated
     * quantities, its own fee lines, its own total. The table label carries a
     * "· 2/3" marker so bills laid on the table are tellable apart.
     */
    private fun buildGroupReceipt(checkId: Int, groupId: Int): Receipt {
        val check = requireCheck(checkId)
        val groups = splitGroups(checkId)
        val group = groups.find { it[BillGroups.id].value == groupId }
            ?: throw NotFoundException("group $groupId not on check $checkId", "group_not_found")
        val table = DiningTables.selectAll().where { DiningTables.id eq check[Checks.tableId] }.first()
        val totals = groupTotals(check, groups)[groups.indexOf(group)]

        val variantCounts = variantCountsOnCheck(checkId)
        val itemNames = dev.dwhipstock.pos.base.Translations.of(dev.dwhipstock.pos.base.Translations.ITEM)
        val variantNames = dev.dwhipstock.pos.base.Translations.of(dev.dwhipstock.pos.base.Translations.VARIANT)
        val items = BillGroupAllocations
            .join(CheckLines, JoinType.INNER, BillGroupAllocations.lineId, CheckLines.id)
            .join(Items, JoinType.LEFT, CheckLines.itemId, Items.id)
            .join(ItemVariants, JoinType.LEFT, CheckLines.variantId, ItemVariants.id)
            .selectAll().where { BillGroupAllocations.groupId eq groupId }
            .map { row ->
                val showVariant = LineSnapshot.showVariant(row, variantCounts)
                ReceiptItem(
                    nameFr = LineSnapshot.nameFr(row),
                    nameEn = LineSnapshot.nameEn(row),
                    variantLabelFr = if (showVariant) LineSnapshot.labelFr(row) else null,
                    variantLabelEn = if (showVariant) LineSnapshot.labelEn(row) else null,
                    qty = row[BillGroupAllocations.qty],
                    unitPrice = Money(row[CheckLines.unitPriceCents]),
                    lineTotal = Money(row[CheckLines.unitPriceCents] * row[BillGroupAllocations.qty]),
                    note = row[CheckLines.note],
                    names = LineSnapshot.names(row, itemNames),
                    variantNames = if (showVariant) LineSnapshot.variantNames(row, variantNames) else emptyMap(),
                    special = ItemSchedules.tagOf(row[CheckLines.specialJson]),
                )
            }
        val tenders = Tenders.selectAll()
            .where { (Tenders.transactionId eq checkId) and (Tenders.billGroupId eq groupId) }
            .map { row ->
                val (labelFr, labelEn) = tenderLabels(row[Tenders.type])
                ReceiptTender(
                    labelFr = labelFr,
                    labelEn = labelEn,
                    amountTendered = Money(row[Tenders.amountTenderedCents]),
                    amountApplied = Money(row[Tenders.amountAppliedCents]),
                    roundingAdjustment = Money(row[Tenders.roundingAdjustmentCents]),
                    change = Money(row[Tenders.changeCents]),
                    type = row[Tenders.type],
                    card = receiptCardOf(row[Tenders.cardJson]),
                )
            }
        return Receipt(
            checkId = checkId,
            tableLabel = "${table[DiningTables.nameOverride] ?: table[DiningTables.label]} · " +
                "${group[BillGroups.groupNumber]}/${groups.size}",
            openedAt = VenueClock.local(check[Checks.openedAt]),
            closedAt = VenueClock.local(check[Checks.closedAt] ?: VenueClock.now()),
            items = items,
            fees = totals.feeLines.map { ReceiptFee(it.labelFr, it.labelEn, it.amount, it.code) },
            grandTotal = Money(group[BillGroups.lockedTotalCents] ?: totals.grandTotal.cents),
            taxIncluded = totals.taxIncluded,
            taxRatePercent = (config.taxPolicy as? TaxPolicy.InclusiveTax)?.ratePercent,
            tenders = tenders,
            taxes = groupTaxLines(group, totals),
            taxDisplay = config.taxPolicy.guestDisplay,
        )
    }

    /** Re-render the receipt for a closed check (client preview; deterministic). */
    /** (fr, en) receipt label for a stored tender type. */
    private fun tenderLabels(type: String): Pair<String, String> {
        val tt = runCatching { TenderType.valueOf(type) }.getOrNull()
        if (tt == TenderType.STRIPE) return "Carte (Stripe)" to "Card (Stripe)"
        if (tt == TenderType.TERMINAL) return "Carte" to "Card"
        val method = tt?.let { runCatching { config.tenderMethod(it) }.getOrNull() }
        return (method?.labelFr ?: "Comptant") to (method?.labelEn ?: "Cash")
    }

    fun receiptText(checkId: Int, lang: String? = null): String = transaction {
        val check = requireCheck(checkId)
        if (check[Checks.status] != "CLOSED") throw ConflictException("check $checkId is ${check[Checks.status]}; no receipt yet", "no_receipt_yet")
        PrinterAdapter.renderText(ReceiptRenderer.render(buildReceipt(checkId), receiptPolicyFor(check, lang)))
    }

    /**
     * Print the final receipt of a closed check again (a copy for the guest,
     * maybe in another of the store's languages: [lang]). Returns its text.
     */
    fun reprintReceipt(checkId: Int, lang: String? = null): String = transaction {
        val check = requireCheck(checkId)
        if (check[Checks.status] != "CLOSED") throw ConflictException("check $checkId is ${check[Checks.status]}; no receipt yet", "no_receipt_yet")
        val lines = ReceiptRenderer.render(buildReceipt(checkId), receiptPolicyFor(check, lang))
        config.printer.print(PrintJob(checkId, lines, meta = mapOf("reprint" to "true")))
        PrinterAdapter.renderText(lines)
    }

    /**
     * Print language follows the check owner's (opener's) preference whenever a
     * message catalog for it is loaded — the venue default policy covers
     * qr-customer-opened checks and unknown users. [lang] (one copy in another
     * language, the tablet's long-press) wins when it is one of the store's
     * languages; any other value is refused (400 unsupported_lang). It never
     * changes the owner's preference.
     */
    private fun receiptPolicyFor(check: ResultRow, lang: String? = null): dev.dwhipstock.pos.sdk.ReceiptPolicy {
        if (lang != null) {
            val chosen = LocaleCode.of(lang)
            if (chosen !in config.profile.locales || !Messages.supports(chosen))
                throw BadRequestException("this store does not print in '$lang'", "unsupported_lang")
            return config.receiptPolicy.withLocale(chosen)
        }
        val locale = Users.selectAll().where { Users.id eq check[Checks.openedBy] }
            .firstOrNull()?.get(Users.languageCode)?.let { LocaleCode.of(it) }
        return if (locale != null && Messages.supports(locale)) config.receiptPolicy.withLocale(locale)
        else config.receiptPolicy
    }

    private fun buildReceipt(checkId: Int): Receipt {
        val check = requireCheck(checkId)
        val table = DiningTables.selectAll().where { DiningTables.id eq check[Checks.tableId] }.first()
        val totals = computeTotals(check)

        // variant label only matters when the item actually has multiple sizes
        val variantCounts = variantCountsOnCheck(checkId)

        // LEFT joins: an open line (null item/variant) renders from display_name
        val itemRows = CheckLines
            .join(Items, JoinType.LEFT, CheckLines.itemId, Items.id)
            .join(ItemVariants, JoinType.LEFT, CheckLines.variantId, ItemVariants.id)
            .selectAll().where { (CheckLines.checkId eq checkId) and (CheckLines.status eq "ACTIVE") }
            .toList()
        val fuel = dev.dwhipstock.pos.forecourt.fuelLineViews(itemRows.mapNotNull { it[CheckLines.fuelSaleId] })
        val itemNames = dev.dwhipstock.pos.base.Translations.of(dev.dwhipstock.pos.base.Translations.ITEM)
        val variantNames = dev.dwhipstock.pos.base.Translations.of(dev.dwhipstock.pos.base.Translations.VARIANT)
        val items = itemRows
            .map { row ->
                val showVariant = LineSnapshot.showVariant(row, variantCounts)
                ReceiptItem(
                    nameFr = LineSnapshot.nameFr(row),
                    nameEn = LineSnapshot.nameEn(row),
                    variantLabelFr = if (showVariant) LineSnapshot.labelFr(row) else null,
                    variantLabelEn = if (showVariant) LineSnapshot.labelEn(row) else null,
                    qty = row[CheckLines.qty],
                    unitPrice = Money(row[CheckLines.unitPriceCents]),
                    lineTotal = Money(row[CheckLines.unitPriceCents] * row[CheckLines.qty]),
                    note = row[CheckLines.note],
                    fuel = row[CheckLines.fuelSaleId]?.let { fuel[it] }?.let { f ->
                        dev.dwhipstock.pos.sdk.ReceiptFuel(f.pump, f.mode == "PREPAY", f.volumeMilli, f.priceMills)
                    },
                    names = LineSnapshot.names(row, itemNames),
                    variantNames = if (showVariant) LineSnapshot.variantNames(row, variantNames) else emptyMap(),
                    special = ItemSchedules.tagOf(row[CheckLines.specialJson]),
                )
            }
        val tenders = Tenders.selectAll().where { Tenders.transactionId eq checkId }.map { row ->
            val (labelFr, labelEn) = tenderLabels(row[Tenders.type])
            ReceiptTender(
                labelFr = labelFr,
                labelEn = labelEn,
                amountTendered = Money(row[Tenders.amountTenderedCents]),
                amountApplied = Money(row[Tenders.amountAppliedCents]),
                roundingAdjustment = Money(row[Tenders.roundingAdjustmentCents]),
                change = Money(row[Tenders.changeCents]),
                type = row[Tenders.type],
                card = receiptCardOf(row[Tenders.cardJson]),
            )
        }
        return Receipt(
            checkId = checkId,
            tableLabel = table[DiningTables.nameOverride] ?: table[DiningTables.label],
            order = counter?.receiptOrder(checkId),
            openedAt = VenueClock.local(check[Checks.openedAt]),
            closedAt = VenueClock.local(check[Checks.closedAt] ?: VenueClock.now()),
            items = items,
            fees = totals.feeLines.map { ReceiptFee(it.labelFr, it.labelEn, it.amount, it.code) },
            grandTotal = Money(check[Checks.lockedGrandTotalCents] ?: totals.grandTotal.cents),
            taxIncluded = Money(check[Checks.lockedTaxIncludedCents] ?: totals.taxIncluded.cents),
            taxRatePercent = (config.taxPolicy as? TaxPolicy.InclusiveTax)?.ratePercent,
            tenders = tenders,
            taxDisplay = config.taxPolicy.guestDisplay,
            taxes = taxLinesOf(check, totals),
            ageVerifiedAt = AgeGate.passedAt(checkId),
            discounts = discountsOf(check, totals).map { dev.dwhipstock.pos.sdk.ReceiptDiscount(it.label, it.labelEs, Money(it.amountCents)) },
        )
    }

    /**
     * Void with reason, manager-gated. Allowed while OPEN or TOTAL_LOCKED. With
     * money already applied (a partly paid bill the guests walked away from)
     * the void needs [reverseTenders]: the payments are handed back first —
     * cash out of the drawer, a hand-keyed card or transfer refunded on its own
     * terminal / bank — and the approver needs the refund grant too. A card
     * taken on the integrated reader or through Stripe can't be given back
     * here (409 void_card_on_reader): finish the bill and refund that card.
     * The tenders stay on the check, stamped reversed; a VOID check's money is
     * never in the drawer math, so the cash in and the cash back cancel out.
     */
    fun voidCheck(checkId: Int, reason: String, managerId: String, reverseTenders: Boolean = false): CheckView {
        cardGuard?.requireNoCardInFlight(checkId, null)
        return afterKitchen(voidCheckTx(checkId, reason, managerId, reverseTenders = reverseTenders))
    }

    /** The store itself voids a check (no one signed in approves it): old test orders being cleaned up. */
    fun systemVoid(checkId: Int, reason: String): CheckView {
        cardGuard?.requireNoCardInFlight(checkId, null)
        return afterKitchen(voidCheckTx(checkId, reason, "system", system = true))
    }

    /**
     * Drop an unpaid check that was never sold: nothing tendered, so nothing to
     * void. Like removing its last line (the check is CANCELLED, not VOID), with
     * [reason] on the sync event. Quick-serve: a discarded or expired order.
     */
    fun cancelUnpaid(checkId: Int, reason: String): CheckView = afterKitchen(cardSafeTransaction(checkId, null) {
        val check = requireCheck(checkId)
        if (check[Checks.status] !in listOf("OPEN", "TOTAL_LOCKED"))
            throw ConflictException("check $checkId is ${check[Checks.status]}", "check_not_open")
        if (!tenderedSoFar(checkId).isZero)
            throw ConflictException("check $checkId has tenders applied", "void_has_tenders")
        Checks.update({ Checks.id eq checkId }) {
            it[status] = "CANCELLED"
            it[closedAt] = VenueClock.now()
        }
        Outbox.write("check.cancelled", "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
            put("reason", reason)
        })
        loadCheck(checkId)
    })

    /**
     * A card attempt ended with nothing taken (declined, cancelled, timed out):
     * a check that was locked only for it opens again, as before the card was
     * pressed (items can be added, the split edited; the next tender re-locks
     * with fresh totals). Kept locked when any money is on it or another card
     * payment is still in flight. True = unlocked.
     */
    fun releaseLockIfUnpaid(checkId: Int): Boolean = transaction {
        val check = Checks.selectAll().where { Checks.id eq checkId }.firstOrNull() ?: return@transaction false
        if (check[Checks.status] != "TOTAL_LOCKED") return@transaction false
        if (Tenders.selectAll().where { Tenders.transactionId eq checkId }.any()) return@transaction false
        if (CardInFlight.on(checkId).isNotEmpty()) return@transaction false
        Checks.update({ Checks.id eq checkId }) {
            it[status] = "OPEN"
            it[lockedGrandTotalCents] = null
            it[lockedTaxIncludedCents] = null
            it[lockedTaxAddedCents] = null
            it[lockedTaxesJson] = null
            it[lockedFeesJson] = null
            it[lockedDiscountsJson] = null
        }
        BillGroups.update({ BillGroups.checkId eq checkId }) {
            it[lockedTotalCents] = null
            it[lockedTaxesJson] = null
        }
        Outbox.write("check.total_unlocked", "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
            put("reason", "card_not_taken")
        })
        true
    }

    /** Money already applied to [checkId] (quick-serve: an unpaid order with a tender can't just expire). */
    fun hasTenders(checkId: Int): Boolean = transaction { !tenderedSoFar(checkId).isZero }

    private fun voidCheckTx(
        checkId: Int, reason: String, managerId: String, system: Boolean = false, reverseTenders: Boolean = false,
    ): CheckView = transaction {
        require(reason.isNotBlank()) { "void reason is required" }
        // defense-in-depth: the route already authorized this; re-check the grant on the authorizer
        if (!system && !GrantsRepo.has(managerId, Permissions.VOID))
            throw ConflictException("void requires the void grant or a manager's approval", "manager_approval_required")

        val check = requireCheck(checkId)
        if (check[Checks.status] !in listOf("OPEN", "TOTAL_LOCKED")) {
            throw ConflictException("check $checkId is ${check[Checks.status]}")
        }
        val now = VenueClock.now()
        val paid = Tenders.selectAll()
            .where { (Tenders.transactionId eq checkId) and Tenders.reversedAt.isNull() }.toList()
        if (paid.isNotEmpty() && !reverseTenders)
            throw ConflictException("check $checkId has payments; void with reverseTenders to hand them back", "void_has_tenders")
        if (paid.isNotEmpty()) {
            if (system || !GrantsRepo.has(managerId, Permissions.REFUND))
                throw ConflictException("handing payments back needs the refund grant or a manager's approval", "manager_approval_required")
            currentOpenShiftId()
                ?: throw ConflictException("no open shift; open a shift before handing money back", "no_open_shift")
            if (paid.any { it[Tenders.type] == TenderType.STRIPE.name || it[Tenders.type] == TenderType.TERMINAL.name })
                throw ConflictException("a card taken on the reader can't be handed back from an open bill; " +
                    "finish the bill and refund the card", "void_card_on_reader")
        }
        val reversed = paid.map { row ->
            Tenders.update({ Tenders.id eq row[Tenders.id] }) { it[reversedAt] = now }
            // what goes back to the guest: what the drawer kept (tendered − change)
            val back = row[Tenders.amountTenderedCents] - row[Tenders.changeCents]
            val entry = buildJsonObject {
                put("tenderId", row[Tenders.id].value)
                put("type", row[Tenders.type])
                put("amountAppliedCents", row[Tenders.amountAppliedCents])
                put("amountReturnedCents", back)
                row[Tenders.billGroupId]?.let { g -> put("groupId", g) }
            }
            Outbox.write("check.tender_reversed", "check", checkId.toString(), buildJsonObject {
                put("checkId", checkId)
                entry.forEach { (k, v) -> put(k, v) }
                put("authorizedBy", managerId)
                put("reversedAt", VenueClock.iso(now))
            })
            entry
        }

        val shift = currentOpenShiftId()
        // TOTAL_LOCKED (tender initiated, no money confirmed): the locked totals
        // are what the screen showed — live math would re-price a settings change
        // and re-floor a split. Only an OPEN void computes fresh.
        val (voidAmount, voidTax, voidTaxes) = check[Checks.lockedGrandTotalCents]?.let {
            Triple(it, totalTax(check), lockedTaxLines(check))
        } ?: computeTotals(check).let {
            Triple(it.grandTotal.cents, it.taxIncluded.cents + it.taxAdded.cents, it.taxLines)
        }
        Checks.update({ Checks.id eq checkId }) {
            it[status] = "VOID"
            it[closedAt] = now
            it[voidReason] = reason
            it[voidedBy] = managerId
            it[shiftId] = shift
        }
        val tz = tableZoneRow(check[Checks.tableId])
        Outbox.write("check.voided", "check", checkId.toString(), buildJsonObject {
            putMoneyContext(config.profile)
            put("checkId", checkId)
            put("reason", reason)
            put("authorizedBy", managerId)
            put("tableId", check[Checks.tableId])
            put("tableLabel", tz[DiningTables.nameOverride] ?: tz[DiningTables.label])
            put("zoneId", tz[Zones.id])
            put("zoneNameFr", tz[Zones.nameFr])
            put("zoneNameEn", tz[Zones.nameEn])
            shift?.let { s -> put("shiftId", s) }
            put("openedAt", VenueClock.iso(check[Checks.openedAt]))
            put("voidedAt", VenueClock.iso(now))
            put("amountCents", voidAmount)
            put("taxIncludedCents", voidTax)
            put("taxes", taxLinesToJson(voidTaxes))
            if (reversed.isNotEmpty()) put("reversedTenders", JsonArray(reversed))
        })
        loadCheck(checkId)
    }

    // --- refunds: return money on a finalized (CLOSED) check. Where void cancels
    // a check before money is applied, a refund unwinds money already taken. Full
    // or partial (by line or by amount), manager-gated, reason required, and it
    // posts to the current shift so cash refunds land in the drawer math. The
    // reversed included tax is decomposed proportionally against the check's
    // locked totals (a full refund reverses tax exactly) — the store computes it,
    // the cloud only aggregates. See CheckService.voidCheck for the manager gate.

    /**
     * Refund [amountCents] (by amount) or [lines] (by line) of a CLOSED check
     * back via [tenderType] (CASH | CARD | BANK_TRANSFER — no card refunds).
     * Guarded so cumulative refunds never exceed the check's grand total.
     */
    fun refundCheck(
        checkId: Int,
        amountCents: Long?,
        lines: List<RefundLineRequest>?,
        tenderType: String,
        reason: String,
        managerId: String,
        /** A manager approved giving it back another way than the guest paid (see [planRefund]). */
        overrideTender: Boolean = false,
    ): RefundResult = transaction {
        val plan = planRefund(checkId, amountCents, lines, tenderType, reason, managerId, overrideTender)
        // a card refund through Stripe must happen AT Stripe first — see payments.StripePayments.refund
        if (plan.tenderType == TenderType.STRIPE)
            throw ConflictException("Stripe refunds go through the Stripe refund path", "stripe_refund_via_stripe")
        // likewise a card taken on an integrated terminal is refunded ON the terminal first
        if (plan.tenderType == TenderType.TERMINAL)
            throw ConflictException("terminal card refunds go through the terminal", "terminal_refund_via_terminal")
        recordRefund(plan)
    }

    /** A validated refund, not yet written. See [planRefund] / [recordRefund]. */
    data class RefundPlan(
        val checkId: Int,
        val gross: Long,
        val net: Long,
        val tax: Long,
        val tenderType: TenderType,
        val reason: String,
        val managerId: String,
        val linesJson: JsonArray?,
        /** The added taxes reversed (their sum is inside [tax]). */
        val taxLines: List<TaxLine> = emptyList(),
        /** CASH only: cash handed back − [gross] (to the nickel); 0 otherwise. */
        val rounding: Long = 0,
        /** The manager who let it go back another way than the guest paid; null = the way they paid. */
        val overrideBy: String? = null,
    ) {
        /** What actually goes back to the customer. */
        val paidOut: Long get() = gross + rounding
    }

    /**
     * Validate a refund (grant, closed check, amount/lines, cumulative cap) and
     * compute its tax split — without writing anything. Stripe refunds plan
     * first, refund at Stripe, then [recordRefund]: a refund Stripe did not make
     * is never recorded.
     */
    fun planRefund(
        checkId: Int,
        amountCents: Long?,
        lines: List<RefundLineRequest>?,
        tenderType: String,
        reason: String,
        managerId: String,
        /**
         * Give it back another way than the guest paid (a card sale in cash):
         * only a manager (role MANAGER) may approve it, and it is recorded.
         */
        overrideTender: Boolean = false,
    ): RefundPlan = transaction {
        require(reason.isNotBlank()) { "refund reason is required" }
        // defense-in-depth: the route already authorized this; re-check the grant on the authorizer
        if (!GrantsRepo.has(managerId, Permissions.REFUND))
            throw ConflictException("refund requires the refund grant or a manager's approval", "manager_approval_required")
        val tt = runCatching { TenderType.valueOf(tenderType) }.getOrNull()
            ?: throw BadRequestException("unknown tender type $tenderType", "refund_bad_tender")
        // money leaves the store into a shift's books, like every tender
        currentOpenShiftId()
            ?: throw ConflictException("no open shift; open a shift before refunding", "no_open_shift")

        val check = requireCheck(checkId)
        if (check[Checks.status] != "CLOSED")
            throw ConflictException("check $checkId is ${check[Checks.status]}; only a closed check can be refunded", "refund_not_closed")
        val grandTotal = check[Checks.lockedGrandTotalCents]
            ?: throw ConflictException("check $checkId has no locked total", "refund_no_total")
        val checkTax = check[Checks.lockedTaxIncludedCents] ?: 0L

        val already = refundedSoFar(checkId)
        val remaining = grandTotal - already
        // by-line takes precedence when present; otherwise a flat amount
        val (refundGross, linesJson) = if (!lines.isNullOrEmpty()) {
            val (preTax, json) = computeLineRefund(checkId, lines)
            withAddedTax(check, preTax, remaining) to json
        } else {
            (amountCents ?: throw BadRequestException("refund needs an amount or lines", "refund_no_amount")) to null
        }
        if (refundGross <= 0) throw BadRequestException("refund amount must be positive", "refund_non_positive")
        // compared against what is left, never summed: already + a huge amount wrapped
        // past Long and let a $92-quadrillion refund through (red team 2026-10-01)
        if (refundGross > remaining)
            throw ConflictException(
                "refund exceeds remaining refundable (${grandTotal - already} cents left on check $checkId)",
                "refund_exceeds_total",
            )

        // back the way the guest paid: a card sale to the card, cash to cash. What
        // each payment type took less what already went back that way bounds it,
        // unless a manager approves another way (recorded on the refund)
        val overrideBy = if (tt == TenderType.STRIPE || tt == TenderType.TERMINAL) {
            null // back to the card through Stripe / the reader: their own per-payment caps apply
        } else if (overrideTender) {
            val role = Users.selectAll().where { Users.id eq managerId }.firstOrNull()?.get(Users.role)
            if (role != "MANAGER")
                throw ConflictException("refunding another way than the guest paid needs a manager", "manager_approval_required")
            managerId
        } else {
            val paidThatWay = Tenders.selectAll()
                .where { (Tenders.transactionId eq checkId) and (Tenders.type eq tt.name) and Tenders.reversedAt.isNull() }
                .sumOfExact { it[Tenders.amountAppliedCents] }
            val backThatWay = Refunds.selectAll()
                .where { (Refunds.checkId eq checkId) and (Refunds.tenderType eq tt.name) }
                .sumOfExact { it[Refunds.grossCents] }
            if (refundGross > paidThatWay - backThatWay)
                throw ConflictException(
                    "check $checkId took ${paidThatWay - backThatWay} cents by ${tt.name} still refundable that way",
                    "refund_tender_mismatch",
                )
            null
        }

        // reverse the included tax proportionally against the LOCKED totals:
        // full refund → tax reverses exactly; partials stay bounded and additive.
        val includedTax = if (grandTotal == 0L) 0L
        else Math.round(checkTax.toDouble() * refundGross / grandTotal)
        val addedTaxes = reverseAddedTaxes(check, already, refundGross)
        val refundTax = includedTax + addedTaxes.sumOf { it.amount.cents }
        val refundNet = refundGross - refundTax
        // cash back rounds to the nickel like a cash sale; card refunds stay exact
        val rounding = if (tt == TenderType.CASH) config.roundingPolicy.cashAdjustment(Money(refundGross)).cents else 0L
        RefundPlan(checkId, refundGross, refundNet, refundTax, tt, reason, managerId, linesJson, addedTaxes, rounding, overrideBy)
    }

    /**
     * Line prices are pre-tax; the guest paid them plus the taxes added on top.
     * A by-line refund returns the lines' share of the check total
     * (half-up), capped at what is still refundable so rounding never lets
     * line-by-line refunds exceed the total. No added tax → the amount as is.
     */
    private fun withAddedTax(check: ResultRow, preTax: Long, remaining: Long): Long {
        val added = check[Checks.lockedTaxAddedCents] ?: 0L
        val grandTotal = check[Checks.lockedGrandTotalCents] ?: return preTax
        val subtotal = grandTotal - added
        if (added == 0L || subtotal <= 0L) return preTax
        val gross = java.math.BigDecimal(preTax).multiply(java.math.BigDecimal(grandTotal))
            .divide(java.math.BigDecimal(subtotal), 0, java.math.RoundingMode.HALF_UP).toLong()
        return if (remaining > 0) minOf(gross, remaining) else gross
    }

    /**
     * Each added tax this refund reverses, in proportion to the money returned.
     * Cumulative: after all refunds so far ([alreadyRefunded] + [gross]) the
     * check's tax is reversed by round-half-up(tax × refunded ÷ total), less
     * what earlier refunds already reversed — so partial refunds never
     * over-reverse and a full refund reverses every tax to the cent.
     */
    private fun reverseAddedTaxes(check: ResultRow, alreadyRefunded: Long, gross: Long): List<TaxLine> {
        val grandTotal = check[Checks.lockedGrandTotalCents] ?: return emptyList()
        if (grandTotal <= 0L) return emptyList()
        val reversedSoFar = sumTaxLines(Refunds.selectAll().where { Refunds.checkId eq check[Checks.id].value }
            .mapNotNull { it[Refunds.taxesJson]?.let(::taxLinesFromJson) }).associate { it.component.code to it.amount.cents }
        val refundedAfter = java.math.BigDecimal(alreadyRefunded + gross)
        return lockedTaxLines(check).map { line ->
            val dueAfter = java.math.BigDecimal(line.amount.cents).multiply(refundedAfter)
                .divide(java.math.BigDecimal(grandTotal), 0, java.math.RoundingMode.HALF_UP).toLong()
            line.copy(amount = Money(dueAfter - (reversedSoFar[line.component.code] ?: 0L)))
        }
    }

    /**
     * Write a planned refund: row, report-complete outbox event, slip. The
     * cumulative cap is re-checked here unless Stripe already returned the money
     * ([stripeRefundId] set) — then the refund happened and must be recorded.
     */
    fun recordRefund(
        plan: RefundPlan,
        stripePaymentIntentId: String? = null,
        stripeRefundId: String? = null,
        /** A forecourt refund (unused prepay): the fuel sale it belongs to. */
        fuelSaleId: Int? = null,
        terminalPaymentRef: String? = null,
        terminalRefundRef: String? = null,
        terminalProvider: String? = null,
    ): RefundResult = transaction {
        val checkId = plan.checkId
        val check = requireCheck(checkId)
        val grandTotal = check[Checks.lockedGrandTotalCents] ?: 0L
        // money already back at Stripe / on the terminal must be recorded whatever
        // happens; a refund the store pays out itself is re-checked here
        val external = stripeRefundId != null || terminalRefundRef != null
        if (!external && plan.gross > grandTotal - refundedSoFar(checkId))
            throw ConflictException(
                "refund exceeds remaining refundable (${grandTotal - refundedSoFar(checkId)} cents left on check $checkId)",
                "refund_exceeds_total",
            )
        // a staff refund lands in a shift's books (the forecourt's automatic change
        // for unused prepaid fuel may come after the shift closed: it still records)
        if (!external && fuelSaleId == null && currentOpenShiftId() == null)
            throw ConflictException("no open shift; open a shift before refunding", "no_open_shift")
        val refundGross = plan.gross
        val refundNet = plan.net
        val refundTax = plan.tax
        val tt = plan.tenderType
        val reason = plan.reason
        val managerId = plan.managerId
        val linesJson = plan.linesJson

        val shift = currentOpenShiftId()
        val now = VenueClock.now()
        val refundId = Refunds.insertAndGetId {
            it[Refunds.checkId] = checkId
            it[shiftId] = shift
            it[grossCents] = refundGross
            it[netCents] = refundNet
            it[taxCents] = refundTax
            it[Refunds.tenderType] = tt.name
            it[Refunds.reason] = reason
            it[Refunds.linesJson] = linesJson?.toString()
            it[refundedBy] = managerId
            it[createdAt] = now
            it[Refunds.stripePaymentIntentId] = stripePaymentIntentId
            it[Refunds.stripeRefundId] = stripeRefundId
            it[Refunds.terminalPaymentRef] = terminalPaymentRef
            it[Refunds.terminalRefundRef] = terminalRefundRef
            it[taxesJson] = if (plan.taxLines.isEmpty()) null else taxLinesToJson(plan.taxLines).toString()
            it[roundingAdjustmentCents] = plan.rounding
            it[Refunds.overrideBy] = plan.overrideBy
        }.value

        val tz = tableZoneRowOrNull(check[Checks.tableId])
        // report-complete refund.created (CONTRACT.md §2): the cloud nets these
        // out of sales + tax from the decomposition alone, no store join.
        Outbox.write("refund.created", "refund", refundId.toString(), buildJsonObject {
            put("refundId", refundId)
            put("checkId", checkId)
            shift?.let { s -> put("shiftId", s) }
            put("grossCents", refundGross)
            put("netCents", refundNet)
            // every tax inside grossCents (included + added)
            put("taxIncludedCents", refundTax)
            put("taxes", taxLinesToJson(plan.taxLines))
            put("tenderType", tt.name)
            // cash back − gross (CASH refunds round to the nickel; 0 otherwise)
            put("roundingAdjustmentCents", plan.rounding)
            put("reason", reason)
            put("refundedBy", managerId)
            plan.overrideBy?.let { put("tenderOverrideBy", it) }
            put("tableId", check[Checks.tableId])
            put("tableLabel", tz?.let { it[DiningTables.nameOverride] ?: it[DiningTables.label] })
            put("zoneId", tz?.get(Zones.id))
            put("zoneNameFr", tz?.get(Zones.nameFr))
            put("zoneNameEn", tz?.get(Zones.nameEn))
            put("createdAt", VenueClock.iso(now))
            putMoneyContext(config.profile)
            linesJson?.let { put("lines", it) }
            fuelSaleId?.let { f -> put("fuelSaleId", f) }
            stripeRefundId?.let { r ->
                put("processor", "stripe")
                put("stripePaymentIntentId", stripePaymentIntentId)
                put("stripeRefundId", r)
            }
            terminalRefundRef?.let { r ->
                put("processor", terminalProvider ?: "terminal")
                put("terminalPaymentRef", terminalPaymentRef)
                put("terminalRefundRef", r)
            }
        })

        RefundResult(
            refund = refundView(Refunds.selectAll().where { Refunds.id eq refundId }.first()),
            check = loadCheck(checkId),
            slipText = renderRefundSlip(check, refundId, refundGross, refundTax, plan.taxLines, tt, reason, now, plan.rounding),
        )
    }

    /** Recent CLOSED checks with their refund state — the POS refund picker. */
    fun recentClosedChecks(limit: Int = 50): List<ClosedCheckSummary> = transaction {
        val refundsByCheck = Refunds.selectAll().toList()
            .groupBy { it[Refunds.checkId] }
            .mapValues { e -> e.value.sumOf { it[Refunds.grossCents] } }
        Checks.selectAll().where { Checks.status eq "CLOSED" }
            .orderBy(Checks.closedAt to SortOrder.DESC)
            .limit(limit)
            .map { row ->
                val id = row[Checks.id].value
                val grand = row[Checks.lockedGrandTotalCents] ?: 0
                val refunded = refundsByCheck[id] ?: 0
                val tz = tableZoneRowOrNull(row[Checks.tableId])
                ClosedCheckSummary(
                    id = id,
                    tableLabel = tz?.let { it[DiningTables.nameOverride] ?: it[DiningTables.label] } ?: "?",
                    closedAt = row[Checks.closedAt]?.let(VenueClock::iso) ?: "",
                    grandTotalCents = grand,
                    refundedCents = refunded,
                    refundableCents = grand - refunded,
                )
            }
    }

    /** A check's refund history + how much is still refundable. */
    fun refundInfo(checkId: Int): RefundInfo = transaction {
        val check = requireCheck(checkId)
        val grand = check[Checks.lockedGrandTotalCents] ?: 0
        val refunds = Refunds.selectAll().where { Refunds.checkId eq checkId }
            .orderBy(Refunds.id to SortOrder.ASC)
            .map { refundView(it) }
        val refunded = refunds.sumOf { it.grossCents }
        val stripeLeft = stripeRefundCapacity(checkId).sumOf { it.remainingCents }
        val terminalLeft = terminalRefundCapacity(checkId).sumOf { it.remainingCents }
        RefundInfo(checkId, grand, refunded, grand - refunded, refunds,
            stripeRefundableCents = minOf(stripeLeft, grand - refunded),
            terminalRefundableCents = minOf(terminalLeft, grand - refunded))
    }

    /**
     * Per integrated-terminal payment on this check (TERMINAL tenders): what
     * its tenders applied minus what was already refunded through the terminal
     * against it. A terminal refunds one payment at a time.
     */
    fun terminalRefundCapacity(checkId: Int): List<TerminalCapacity> = transaction {
        val rows = Tenders.selectAll()
            .where { (Tenders.transactionId eq checkId) and (Tenders.type eq TenderType.TERMINAL.name) }
            .filter { it[Tenders.terminalPaymentRef] != null }
        val paid = rows.groupBy({ it[Tenders.terminalPaymentRef]!! }, { it[Tenders.amountAppliedCents] }).mapValues { it.value.sum() }
        val processorRefs = rows.associate { it[Tenders.terminalPaymentRef]!! to decodeCard(it[Tenders.cardJson])?.processorRef }
        val refunded = Refunds.selectAll()
            .where { (Refunds.checkId eq checkId) and Refunds.terminalPaymentRef.isNotNull() }
            .groupBy({ it[Refunds.terminalPaymentRef]!! }, { it[Refunds.grossCents] })
            .mapValues { it.value.sum() }
        paid.map { (ref, amount) ->
            TerminalCapacity(ref, amount, (amount - (refunded[ref] ?: 0L)).coerceAtLeast(0L), processorRefs[ref])
        }
    }

    data class TerminalCapacity(
        val terminalRef: String, val paidCents: Long, val remainingCents: Long,
        /** The processor's transaction id behind the reader (J.P. Morgan), if any. */
        val processorRef: String? = null,
    )

    /** The tender already recorded for an integrated terminal's payment id, if any. */
    fun tenderIdForTerminalRef(terminalRef: String): Int? = transaction {
        Tenders.selectAll().where { Tenders.terminalPaymentRef eq terminalRef }.firstOrNull()?.get(Tenders.id)?.value
    }

    /**
     * Per Stripe PaymentIntent on this check: (PI id, cents still refundable to
     * that card) = what its tenders applied minus what was already refunded
     * through Stripe against it. Stripe refunds one PI at a time.
     */
    fun stripeRefundCapacity(checkId: Int): List<StripeCapacity> = transaction {
        val paid = Tenders.selectAll()
            .where { (Tenders.transactionId eq checkId) and (Tenders.type eq TenderType.STRIPE.name) }
            .mapNotNull { r -> r[Tenders.stripePaymentIntentId]?.let { it to r[Tenders.amountAppliedCents] } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
        val refunded = Refunds.selectAll()
            .where { (Refunds.checkId eq checkId) and Refunds.stripePaymentIntentId.isNotNull() }
            .groupBy({ it[Refunds.stripePaymentIntentId]!! }, { it[Refunds.grossCents] })
            .mapValues { it.value.sum() }
        paid.map { (pi, amount) -> StripeCapacity(pi, amount, (amount - (refunded[pi] ?: 0L)).coerceAtLeast(0L)) }
    }

    data class StripeCapacity(val paymentIntentId: String, val paidCents: Long, val remainingCents: Long)

    /** The tender already recorded for a Stripe PaymentIntent, if any. */
    fun tenderIdForPaymentIntent(paymentIntentId: String): Int? = transaction {
        Tenders.selectAll().where { Tenders.stripePaymentIntentId eq paymentIntentId }.firstOrNull()?.get(Tenders.id)?.value
    }

    fun tenderView(tenderId: Int): TenderView = transaction {
        val r = Tenders.selectAll().where { Tenders.id eq tenderId }.firstOrNull()
            ?: throw NotFoundException("tender $tenderId not found", "tender_not_found")
        TenderView(tenderId, r[Tenders.type], r[Tenders.amountTenderedCents], r[Tenders.amountAppliedCents],
            r[Tenders.roundingAdjustmentCents], r[Tenders.changeCents], r[Tenders.billGroupId], r[Tenders.tipCents])
    }

    private fun refundedSoFar(checkId: Int): Long =
        Refunds.selectAll().where { Refunds.checkId eq checkId }.sumOf { it[Refunds.grossCents] }

    /** Sum selected line quantities into a refund gross + a JSON breakdown. */
    private fun computeLineRefund(checkId: Int, lines: List<RefundLineRequest>): Pair<Long, JsonArray> {
        val lineRows = CheckLines.selectAll()
            .where { (CheckLines.checkId eq checkId) and (CheckLines.status eq "ACTIVE") }
            .associate { it[CheckLines.id].value to (it[CheckLines.qty] to it[CheckLines.unitPriceCents]) }
        // the product behind each line: a retail refund puts it back in stock (cloud ledger)
        val lineItems = CheckLines.selectAll()
            .where { (CheckLines.checkId eq checkId) and (CheckLines.status eq "ACTIVE") }
            .associate { it[CheckLines.id].value to it[CheckLines.itemId] }
        // what earlier by-line refunds already gave back, per line: one beer is
        // refundable once, not again and again (red team 2026-10-01)
        val refundedQty = mutableMapOf<Int, Int>()
        Refunds.selectAll().where { Refunds.checkId eq checkId }.forEach { r ->
            val json = r[Refunds.linesJson] ?: return@forEach
            runCatching { Json.parseToJsonElement(json).jsonArray }.getOrNull()?.forEach { e ->
                val o = e.jsonObject
                val id = o["lineId"]?.jsonPrimitive?.longOrNull?.toInt() ?: return@forEach
                val q = o["qty"]?.jsonPrimitive?.longOrNull?.toInt() ?: return@forEach
                refundedQty[id] = (refundedQty[id] ?: 0) + q
            }
        }
        var gross = 0L
        val arr = buildJsonArray {
            for (l in lines) {
                if (l.qty <= 0) continue
                val row = lineRows[l.lineId]
                    ?: throw BadRequestException("line ${l.lineId} not on check $checkId", "refund_bad_line")
                val (origQty, unit) = row
                if (l.qty > origQty)
                    throw BadRequestException("line ${l.lineId}: refund qty ${l.qty} exceeds $origQty", "refund_qty_too_high")
                val before = refundedQty[l.lineId] ?: 0
                if (before + l.qty > origQty)
                    throw ConflictException(
                        "line ${l.lineId}: ${origQty - before} of $origQty still refundable", "refund_line_already_refunded")
                refundedQty[l.lineId] = before + l.qty
                val amount = Math.multiplyExact(unit, l.qty.toLong())
                gross = Math.addExact(gross, amount)
                addJsonObject {
                    put("lineId", l.lineId)
                    lineItems[l.lineId]?.let { put("itemId", it) }
                    put("qty", l.qty)
                    put("amountCents", amount)
                }
            }
        }
        return gross to arr
    }

    private fun refundView(r: ResultRow) = RefundView(
        id = r[Refunds.id].value,
        checkId = r[Refunds.checkId],
        grossCents = r[Refunds.grossCents],
        netCents = r[Refunds.netCents],
        taxCents = r[Refunds.taxCents],
        tenderType = r[Refunds.tenderType],
        reason = r[Refunds.reason],
        refundedBy = r[Refunds.refundedBy],
        createdAt = VenueClock.iso(r[Refunds.createdAt]),
        taxes = r[Refunds.taxesJson]?.let(::taxLinesFromJson).orEmpty().map { it.toView() },
        roundingAdjustmentCents = r[Refunds.roundingAdjustmentCents],
        paidOutCents = r[Refunds.grossCents] + r[Refunds.roundingAdjustmentCents],
    )

    /** 42-col refund slip, same virtual printer as receipts. Language follows the check owner. */
    private fun renderRefundSlip(
        check: ResultRow, refundId: Int, gross: Long, tax: Long, addedTaxes: List<TaxLine>,
        tt: TenderType, reason: String, now: java.time.Instant, rounding: Long = 0,
    ): String {
        val policy = receiptPolicyFor(check)
        val locale = policy.locale
        fun msg(key: MessageKey, vararg args: Any) = Messages.get(key, locale, *args)
        val method = runCatching { config.tenderMethod(tt) }.getOrNull()
        val tenderLabel = when (tt) {
            TenderType.CASH -> msg(TENDER_CASH)
            TenderType.STRIPE, TenderType.TERMINAL -> tenderLabels(tt.name).let { (fr, en) -> locale.dataText(fr, en) }
            else -> method?.let { locale.dataText(it.labelFr, it.labelEn) } ?: tt.name
        }
        val taxRate = (config.taxPolicy as? TaxPolicy.InclusiveTax)?.ratePercent
        val lines = buildList {
            add(PrintLine.LogoPlaceholder(policy.logoFallbackText))
            policy.header().forEach { add(PrintLine.Text(it, Align.CENTER)) }
            add(PrintLine.Blank)
            add(PrintLine.Header(msg(REFUND_HEADER)))
            add(PrintLine.Blank)
            add(PrintLine.KeyValue(
                msg(REFUND_REF_BILL) + " " + msg(MessageKey.RECEIPT_NUMBER, check[Checks.id].value),
                msg(REFUND_NUMBER) + " " + msg(MessageKey.RECEIPT_NUMBER, refundId),
            ))
            add(PrintLine.KeyValue(msg(SLIP_TIME), policy.formatDate(VenueClock.local(now))))
            add(PrintLine.Divider)
            // the added taxes handed back, then the total they are part of
            if (addedTaxes.isNotEmpty()) {
                val reversed = addedTaxes.sumOf { it.amount.cents }
                add(PrintLine.KeyValue(msg(RECEIPT_SUBTOTAL), policy.money(Money(gross - reversed))))
                dev.dwhipstock.pos.sdk.guestTaxLines(addedTaxes, config.taxPolicy.guestDisplay).forEach {
                    add(PrintLine.KeyValue(ReceiptRenderer.taxLineLabel(it.component, locale), policy.money(it.amount)))
                }
            }
            add(PrintLine.KeyValue(msg(REFUND_TOTAL), policy.money(Money(gross)), emphasized = true))
            if (policy.showTax && taxRate != null) {
                add(PrintLine.KeyValue(msg(RECEIPT_TAX_INCLUDED, taxRate), policy.money(Money(tax - addedTaxes.sumOf { it.amount.cents }))))
            }
            // cash handed back rounds to the nickel: show the adjustment and the cash
            if (rounding != 0L) {
                add(PrintLine.KeyValue(msg(RECEIPT_ROUNDING), ReceiptRenderer.signed(Money(rounding), policy::money)))
                add(PrintLine.KeyValue(msg(REFUND_CASH_BACK), policy.money(Money(gross + rounding)), emphasized = true))
            }
            add(PrintLine.KeyValue(msg(REFUND_VIA), tenderLabel))
            add(PrintLine.Blank)
            add(PrintLine.Text(msg(SLIP_REASON) + " " + reason))
            add(PrintLine.Blank)
            add(PrintLine.Text(policy.footerText, Align.CENTER))
        }
        return dev.dwhipstock.pos.sdk.PrinterAdapter.renderText(lines)
    }

    // --- table ops: move & merge. One staff gesture (pick a destination table);
    // an empty table means move, an occupied one means merge. Both only while the
    // money is still fluid: no tender, no split, no unresolved QR lines.

    /** Reassign a live check to a different, empty table (party changed seats). */
    fun moveCheck(checkId: Int, toTableId: String): CheckView = transaction {
        val check = requireMovable(checkId)
        val fromTableId = check[Checks.tableId]
        if (fromTableId == toTableId)
            throw ConflictException("check $checkId is already on table $toTableId", "same_table")
        if (SaleLocations.isOffFloorTable(toTableId))
            throw ConflictException("$toTableId is not a table", "not_a_table")
        DiningTables.selectAll()
            .where { (DiningTables.id eq toTableId) and DiningTables.deletedAt.isNull() }
            .firstOrNull() ?: throw NotFoundException("table $toTableId not found")
        requireZoneOpenForTable(toTableId)
        val occupied = Checks.selectAll()
            .where { (Checks.tableId eq toTableId) and (Checks.status inList listOf("OPEN", "TOTAL_LOCKED")) }
            .firstOrNull()
        if (occupied != null)
            throw ConflictException("table $toTableId already has bill #${occupied[Checks.id].value}; merge instead", "table_occupied")

        Checks.update({ Checks.id eq checkId }) { it[tableId] = toTableId }
        Outbox.write("check.moved", "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
            put("fromTableId", fromTableId)
            put("toTableId", toTableId)
        })
        loadCheck(checkId)
    }

    /**
     * Fold the source check's lines into the destination check (two parties
     * became one table). Line rows MOVE — notes, captured unit prices and
     * entry order all survive; corkage bottles sum. The source closes as
     * MERGED: like CANCELLED it's a no-receipt, no-manager-gate close, and the
     * occupancy reads ignore it so the source table frees immediately.
     */
    fun mergeCheck(sourceCheckId: Int, destCheckId: Int): CheckView =
        mergeCheckTx(sourceCheckId, destCheckId).also { view ->
            kitchen?.let { hook ->
                try { hook.checksMerged(sourceCheckId, view.id) } catch (e: Exception) { log.warn("kitchen hook failed: ${e.message}") }
            }
        }

    private fun mergeCheckTx(sourceCheckId: Int, destCheckId: Int): CheckView = transaction {
        if (sourceCheckId == destCheckId)
            throw ConflictException("can't merge check $sourceCheckId into itself", "same_check")
        val source = requireMovable(sourceCheckId)
        val dest = requireMovable(destCheckId)
        requireZoneOpenForTable(dest[Checks.tableId])

        val movedLines = CheckLines.update({ CheckLines.checkId eq sourceCheckId }) {
            it[checkId] = destCheckId
        }
        val movedCorkage = source[Checks.corkageBottles]
        if (movedCorkage > 0) {
            Checks.update({ Checks.id eq destCheckId }) {
                it[corkageBottles] = dest[Checks.corkageBottles] + movedCorkage
            }
        }
        Checks.update({ Checks.id eq sourceCheckId }) {
            it[status] = "MERGED"
            it[closedAt] = VenueClock.now()
        }
        mergeCounterOrders(sourceCheckId, destCheckId)
        Outbox.write("check.merged", "check", destCheckId.toString(), buildJsonObject {
            put("sourceCheckId", sourceCheckId)
            put("destCheckId", destCheckId)
            put("lineCount", movedLines)
            put("corkageBottles", movedCorkage)
        })
        loadCheck(destCheckId)
    }

    /**
     * Quick-serve: one number per guest. When a kiosk order (WAITING, its
     * number already on the guest's ticket) is folded into another unpaid
     * order, the bill they pay together carries the kiosk's number: an
     * unnumbered counter order takes it over (and is WAITING like it), so the
     * guest is called by the number they hold. If both already have a number
     * the destination keeps its own. The source row never stays WAITING on a
     * MERGED check: it closes out as CANCELLED (its number, if it still has
     * one, stays taken for the day, a gap like an expired kiosk order).
     * Inside the merge's transaction; nothing for a check that isn't a counter order.
     */
    private fun mergeCounterOrders(sourceCheckId: Int, destCheckId: Int) {
        val src = CounterOrders.selectAll().where { CounterOrders.checkId eq sourceCheckId }.firstOrNull() ?: return
        if (src[CounterOrders.status] !in QuickServeService.UNPAID) return
        val dest = CounterOrders.selectAll().where { CounterOrders.checkId eq destCheckId }.firstOrNull()
        val number = src[CounterOrders.orderNumber]
        val handOver = number != null && dest != null && dest[CounterOrders.orderNumber] == null &&
            dest[CounterOrders.status] in QuickServeService.UNPAID
        if (number == null || handOver) {
            // nothing on a ticket to keep (or the number moves on): the row goes,
            // first, since a number is unique per day
            CounterOrders.deleteWhere { CounterOrders.checkId eq sourceCheckId }
        } else {
            CounterOrders.update({ CounterOrders.checkId eq sourceCheckId }) { it[status] = QuickServeService.CANCELLED }
        }
        if (handOver) CounterOrders.update({ CounterOrders.checkId eq destCheckId }) {
            it[orderNumber] = number
            it[businessDate] = src[CounterOrders.businessDate]
        }
    }

    /**
     * Move/merge shared guard: the check must be OPEN (not finalized and no
     * money applied — the first tender flips to TOTAL_LOCKED), not mid-split,
     * and have no pending QR lines (they belong to whoever is at THIS table;
     * resolve before relocating the bill).
     */
    private fun requireMovable(checkId: Int): ResultRow {
        val check = requireCheck(checkId)
        if (check[Checks.status] != "OPEN")
            throw ConflictException("check $checkId is ${check[Checks.status]}", "check_not_open")
        // a carry-out order has its number, not a table: never moved onto one or merged
        if (SaleLocations.isOffFloorTable(check[Checks.tableId]))
            throw ConflictException("check $checkId is a carry-out order, not at a table", "not_a_table")
        if (splitGroups(checkId).isNotEmpty())
            throw ConflictException("check $checkId is split; clear the split first", "clear_split_first")
        val pending = CheckLines.selectAll()
            .where { (CheckLines.checkId eq checkId) and (CheckLines.status eq "PENDING") }.count()
        if (pending > 0)
            throw ConflictException("check $checkId has $pending pending QR lines; accept or reject them first", "pending_lines_unresolved")
        return check
    }

    fun getCheck(checkId: Int): CheckView = transaction { loadCheck(checkId) }

    /** A staff screen has [checkId] open (loads, polls or edits it): see [CounterHook.touched]. Never throws. */
    fun touch(checkId: Int) {
        try { counter?.touched(checkId) } catch (_: Exception) {}
    }

    fun openCheckForTable(tableId: String): CheckView? = transaction {
        Checks.selectAll()
            .where { (Checks.tableId eq tableId) and (Checks.status inList listOf("OPEN", "TOTAL_LOCKED")) }
            .firstOrNull()?.let { loadCheck(it[Checks.id].value) }
    }

    /**
     * ISO timestamp of the oldest un-actioned PENDING (customer QR) line on this
     * check, or null if none. The staff terminal uses it to age the escalation
     * banner — server-derived so it survives an app restart. Cheap indexed read.
     */
    fun oldestPendingAt(checkId: Int): String? = transaction {
        CheckLines.selectAll()
            .where { (CheckLines.checkId eq checkId) and (CheckLines.status eq "PENDING") }
            .orderBy(CheckLines.createdAt)
            .limit(1)
            .firstOrNull()?.get(CheckLines.createdAt)?.let(VenueClock::iso)
    }

    // --- internals ---

    private fun tableZoneRow(tableId: String): ResultRow =
        DiningTables.join(Zones, JoinType.INNER, DiningTables.zoneId, Zones.id)
            .selectAll().where { DiningTables.id eq tableId }.first()

    /**
     * Like [tableZoneRow] but tolerates a check whose table or zone is gone —
     * a hard-deleted or dangling ref on a legacy row. The report-complete
     * payload only uses the zone for labels; the money it carries (grand total,
     * tax, tenders) is independent, so a missing table degrades to null labels
     * instead of throwing "Collection is empty." and wedging the backfill.
     */
    private fun tableZoneRowOrNull(tableId: String): ResultRow? =
        DiningTables.join(Zones, JoinType.INNER, DiningTables.zoneId, Zones.id)
            .selectAll().where { DiningTables.id eq tableId }.firstOrNull()

    /** Added taxes frozen at lock time; a check locked before 036 had none. */
    private fun lockedTaxLines(check: ResultRow): List<TaxLine> =
        check[Checks.lockedTaxesJson]?.let(::taxLinesFromJson) ?: emptyList()

    /** Locked checks read their frozen taxes; an OPEN one shows the live pipeline's. */
    private fun taxLinesOf(check: ResultRow, live: Totals): List<TaxLine> =
        if (check[Checks.lockedGrandTotalCents] != null) lockedTaxLines(check) else live.taxLines

    /** Every tax in the check's (or refund's) money: included in the price plus added on top. */
    private fun totalTax(check: ResultRow): Long =
        (check[Checks.lockedTaxIncludedCents] ?: 0L) + (check[Checks.lockedTaxAddedCents] ?: 0L)

    private fun feeLinesToJson(fees: List<FeeLine>): JsonArray = JsonArray(fees.map { f ->
        buildJsonObject {
            put("code", f.code)
            put("labelFr", f.labelFr)
            put("labelEn", f.labelEn)
            put("amountCents", f.amount.cents)
        }
    })

    /**
     * Report-complete check.closed snapshot (CONTRACT.md §2): everything the
     * cloud's sales reports need, so it never joins back into store internals.
     * [check] is the pre-close row (TOTAL_LOCKED, locked totals stamped).
     */
    private fun closedCheckPayload(check: ResultRow, shift: Int?, closedAt: java.time.Instant): JsonObject {
        val checkId = check[Checks.id].value
        val tz = tableZoneRowOrNull(check[Checks.tableId])
        // fees as assessed at lock time (021) — live settings must not re-price a
        // closed check. Pre-021 rows (locked before the upgrade) fall back to a
        // re-assessment, the old behaviour.
        val fees = check[Checks.lockedFeesJson]?.let { Json.parseToJsonElement(it).jsonArray }
            ?: feeLinesToJson(computeTotals(check).feeLines)

        // same disambiguation rule as receipts: variant label only when the
        // item has more than one live size
        val liveVariantCounts = variantCountsOnCheck(checkId, liveOnly = true)
        val activeRows = CheckLines
            .join(Items, JoinType.LEFT, CheckLines.itemId, Items.id)
            .join(ItemVariants, JoinType.LEFT, CheckLines.variantId, ItemVariants.id)
            .selectAll().where { (CheckLines.checkId eq checkId) and (CheckLines.status eq "ACTIVE") }
            .toList()
        val fuel = dev.dwhipstock.pos.forecourt.fuelLineViews(activeRows.mapNotNull { it[CheckLines.fuelSaleId] })
        val lines = activeRows
            .map { row ->
                buildJsonObject {
                    put("lineId", row[CheckLines.id].value)
                    val itemId = row[CheckLines.itemId]
                    put("itemId", itemId)
                    put("variantId", row[CheckLines.variantId])
                    // the menu as rung (LineSnapshot): a later rename / delete doesn't retell the sale
                    put("categoryId", LineSnapshot.categoryId(row))
                    if (itemId == null) {
                        // off-menu open line: null ids, displayName instead of names
                        put("displayName", row[CheckLines.displayName] ?: "?")
                    } else {
                        put("nameFr", LineSnapshot.nameFr(row))
                        put("nameEn", LineSnapshot.nameEn(row))
                        if (row[CheckLines.showVariant] ?: ((liveVariantCounts[itemId] ?: 0) > 1)) {
                            put("variantLabelFr", LineSnapshot.labelFr(row))
                            put("variantLabelEn", LineSnapshot.labelEn(row))
                        }
                    }
                    put("qty", row[CheckLines.qty])
                    put("unitPriceCents", row[CheckLines.unitPriceCents])
                    put("lineTotalCents", row[CheckLines.unitPriceCents] * row[CheckLines.qty])
                    put("note", row[CheckLines.note])
                    // retail shelf facts, only where they differ from a pub line
                    if (!row[CheckLines.taxable]) put("taxable", false)
                    if (row[CheckLines.depositCents] > 0) put("depositCents", row[CheckLines.depositCents])
                    if (row[CheckLines.ageRestricted]) put("ageRestricted", true)
                    row[CheckLines.unitCostCents]?.let { put("unitCostCents", it) }
                    // a gas station's fuel / prepay line (CONTRACT §2, Fuel)
                    row[CheckLines.fuelSaleId]?.let { fuel[it] }?.let { f ->
                        put("fuel", buildJsonObject {
                            put("pump", f.pump)
                            put("mode", f.mode)
                            if (f.mode == "PREPAY") {
                                put("prepaidCents", f.prepaidCents ?: row[CheckLines.unitPriceCents])
                            } else {
                                f.nozzle?.let { n -> put("nozzle", n) }
                                put("grade", f.grade)
                                put("volumeMilli", f.volumeMilli)
                                put("priceMills", f.priceMills)
                                put("fdcTransactionId", f.fdcTransactionId)
                            }
                        })
                    }
                }
            }
        val tenders = Tenders.selectAll().where { Tenders.transactionId eq checkId }.map { row ->
            buildJsonObject {
                put("tenderId", row[Tenders.id].value)
                put("type", row[Tenders.type])
                put("amountTenderedCents", row[Tenders.amountTenderedCents])
                put("amountAppliedCents", row[Tenders.amountAppliedCents])
                put("roundingAdjustmentCents", row[Tenders.roundingAdjustmentCents])
                put("changeCents", row[Tenders.changeCents])
                put("groupId", row[Tenders.billGroupId])
                row[Tenders.stripePaymentIntentId]?.let { pi -> put("stripePaymentIntentId", pi) }
                if (row[Tenders.tipCents] > 0) put("tipCents", row[Tenders.tipCents])
            }
        }
        return buildJsonObject {
            putMoneyContext(config.profile)
            put("checkId", checkId)
            put("tableId", check[Checks.tableId])
            put("tableLabel", tz?.let { it[DiningTables.nameOverride] ?: it[DiningTables.label] })
            put("zoneId", tz?.get(Zones.id))
            put("zoneNameFr", tz?.get(Zones.nameFr))
            put("zoneNameEn", tz?.get(Zones.nameEn))
            shift?.let { s -> put("shiftId", s) }
            put("openedAt", VenueClock.iso(check[Checks.openedAt]))
            put("closedAt", VenueClock.iso(closedAt))
            put("openedBy", check[Checks.openedBy])
            put("grandTotalCents", check[Checks.lockedGrandTotalCents]!!)
            // every tax inside grandTotalCents (included + added), so net = gross − tax
            put("taxIncludedCents", totalTax(check))
            put("subtotalCents", check[Checks.lockedGrandTotalCents]!! - (check[Checks.lockedTaxAddedCents] ?: 0L))
            put("taxes", taxLinesToJson(lockedTaxLines(check)))
            put("corkageBottles", check[Checks.corkageBottles])
            put("fees", fees)
            check[Checks.lockedDiscountsJson]?.let { put("discounts", Json.parseToJsonElement(it)) }
            put("lines", JsonArray(lines))
            put("tenders", JsonArray(tenders))
        }
    }

    /**
     * One-time backfill: re-emit a report-complete `check.closed` for every
     * historically CLOSED check. Checks closed before the report-complete
     * payload shipped (commit fd09a6f) were pushed thin — grand total only, no
     * decomposed tax and no tender rows — so the cloud's tax and payment-mix
     * reports undercount (they count ~1 detailed check while gross counts all).
     *
     * The store DB still holds the full history (locked tax + the Tenders
     * rows), so re-running [closedCheckPayload] rebuilds a complete event; the
     * cloud's idempotent upsert-by-checkId backfills tax + tenders in place. We
     * never fabricate money here — the store is authoritative and simply
     * re-emits what it already recorded. Returns the number of events written.
     */
    fun backfillReportCompleteClosedEvents(): Int = transaction {
        var emitted = 0
        Checks.selectAll().where { Checks.status eq "CLOSED" }
            .orderBy(Checks.id).forEach { check ->
                val checkId = check[Checks.id].value
                try {
                    val closedAt = check[Checks.closedAt]
                    // Every CLOSED check passed TOTAL_LOCKED, which stamps both locked
                    // totals — a null here is a pathological row, so skip loudly rather
                    // than re-emit another thin event (or NPE mid-batch).
                    if (check[Checks.lockedGrandTotalCents] == null ||
                        check[Checks.lockedTaxIncludedCents] == null || closedAt == null) {
                        log.warn("report backfill: check $checkId lacks locked totals/closedAt; skipped")
                        return@forEach
                    }
                    Outbox.write("check.closed", "check", checkId.toString(),
                        closedCheckPayload(check, check[Checks.shiftId], closedAt))
                    emitted++
                } catch (e: Exception) {
                    // One pathological legacy check must never abort the whole
                    // re-emit: if it did, the tick would throw before the marker is
                    // set, so the backfill retries every 10s forever and no sync —
                    // not just this event — ever drains. Skip loudly and continue.
                    log.warn("report backfill: check $checkId failed to re-emit; skipped", e)
                }
            }
        emitted
    }

    private fun requireCheck(checkId: Int): ResultRow =
        Checks.selectAll().where { Checks.id eq checkId }.firstOrNull()
            ?: throw NotFoundException("check $checkId not found")

    /**
     * Auto-cancel an OPEN check the moment nothing is on it — no ACTIVE lines, no
     * PENDING lines, and no corkage (so a genuinely $0 balance). Distinct from VOID:
     * nothing was ever rung, so there's no reason to capture and no manager gate. The
     * table frees up immediately because openCheckForTable / the /zones occupancy read
     * only match OPEN|TOTAL_LOCKED — a CANCELLED check disappears like a CLOSED/VOID one,
     * with no receipt (there's nothing to print).
     *
     * Called after the two mutations that can strip a check to empty: line delete
     * (removeLine) and pending-line reject (rejectPendingLine). A corkage-only check
     * (bottles > 0, no lines) still owes money, so it is deliberately left OPEN.
     * Existing pre-fix orphans are never touched — this only fires on a live mutation.
     */
    private fun cancelIfEmpty(checkId: Int) {
        val check = requireCheck(checkId)
        if (check[Checks.status] != "OPEN") return
        if (check[Checks.corkageBottles] > 0) return
        val liveLines = CheckLines.selectAll()
            .where { (CheckLines.checkId eq checkId) and
                (CheckLines.status inList listOf("ACTIVE", "PENDING")) }
            .count()
        if (liveLines > 0L) return
        Checks.update({ Checks.id eq checkId }) {
            it[status] = "CANCELLED"
            it[closedAt] = VenueClock.now()
        }
        Outbox.write("check.cancelled", "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
            put("reason", "empty")
        })
    }

    /**
     * Stage 4: freeze the pipeline output onto the check row. On a split check
     * every ACTIVE quantity must be allocated to a group first (an unassigned
     * beer would belong to no bill and never get paid); each group's total is
     * stamped, and the check's locked grand total is the SUM of group totals —
     * per-group fee floors are authoritative once split (documented above).
     */
    private fun lockTotals(checkId: Int) {
        val pending = CheckLines.selectAll()
            .where { (CheckLines.checkId eq checkId) and (CheckLines.status eq "PENDING") }.count()
        if (pending > 0) throw ConflictException("check $checkId has $pending pending QR lines; accept or reject them first", "pending_lines_unresolved")
        val check = requireCheck(checkId)
        val groups = splitGroups(checkId)
        val totals: Totals = if (groups.isEmpty()) {
            computeTotals(check)
        } else {
            val even = groups.any { it[BillGroups.fixedAmountCents] != null }
            if (!even) {
                val unassigned = CheckLines.selectAll()
                    .where { (CheckLines.checkId eq checkId) and (CheckLines.status eq "ACTIVE") }
                    .sumOf { it[CheckLines.qty] - allocatedQtyForLine(it[CheckLines.id].value) }
                if (unassigned > 0)
                    throw ConflictException("check $checkId has $unassigned unassigned item(s); assign everything before paying", "split_unassigned_lines")
            }
            val perGroup = groupTotals(check, groups)
            // even shares were cut from the total at split time: a basket edited
            // since then would lock shares that no longer add up to the bill
            if (even && perGroup.sumOf { it.grandTotal.cents } != computeTotals(check).grandTotal.cents)
                throw ConflictException("check $checkId changed since it was split evenly; split it again", "split_stale")
            groups.zip(perGroup).forEach { (group, t) ->
                BillGroups.update({ BillGroups.id eq group[BillGroups.id] }) {
                    it[lockedTotalCents] = t.grandTotal.cents
                    it[lockedTaxesJson] = taxLinesToJson(t.taxLines).toString()
                }
            }
            // one line per fee code; on a split the per-group sums (their floors)
            // are what the locked grand total actually charged
            val fees = perGroup.flatMap { it.feeLines }.groupBy { it.code }.map { (_, lines) ->
                lines.first().copy(amount = Money(lines.sumOf { it.amount.cents }))
            }
            Totals(
                itemsSubtotal = Money(perGroup.sumOf { it.itemsSubtotal.cents }),
                feeLines = fees,
                grandTotal = Money(perGroup.sumOf { it.grandTotal.cents }),
                taxIncluded = Money(perGroup.sumOf { it.taxIncluded.cents }),
                taxVisibleOnReceipt = perGroup.first().taxVisibleOnReceipt,
                taxLines = sumTaxLines(perGroup.map { it.taxLines }),
            )
        }
        Checks.update({ Checks.id eq checkId }) {
            it[status] = "TOTAL_LOCKED"
            it[lockedGrandTotalCents] = totals.grandTotal.cents
            it[lockedTaxIncludedCents] = totals.taxIncluded.cents
            it[lockedTaxAddedCents] = totals.taxAdded.cents
            it[lockedTaxesJson] = taxLinesToJson(totals.taxLines).toString()
            it[lockedFeesJson] = feeLinesToJson(totals.feeLines).toString()
            if (totals.promotions.isNotEmpty()) it[lockedDiscountsJson] = discountsToJson(
                totals.promotions.map { p -> DiscountView(p.code, p.label, p.labelEs, p.amount.cents, p.taxableAmount.cents) },
            ).toString()
        }
        Outbox.write("check.total_locked", "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
            put("grandTotalCents", totals.grandTotal.cents)
            put("taxIncludedCents", totals.taxIncluded.cents + totals.taxAdded.cents)
            put("subtotalCents", totals.subtotal.cents)
            put("taxes", taxLinesToJson(totals.taxLines))
            if (groups.isNotEmpty()) put("groups", groups.size)
        })
    }

    /**
     * Per-group totals of a split check, in [groups] order. By-item groups run
     * their allocated quantities through the SAME pipeline as the whole check
     * (the corkage fee lands on the one group that carries it); the check's
     * taxes are then assessed once and apportioned to the groups, so the groups
     * sum exactly to the check. Even ÷N groups are fixed shares of the check's
     * total, each carrying its proportional share of the check's taxes.
     */
    private fun groupTotals(check: ResultRow, groups: List<ResultRow>): List<Totals> {
        if (groups.isEmpty()) return emptyList()
        if (groups.any { it[BillGroups.fixedAmountCents] != null }) {
            val whole = computeTotals(check)
            val shares = groups.map { Money(it[BillGroups.fixedAmountCents] ?: 0L) }
            return TransactionPipeline.taxOfShares(whole, shares).mapIndexed { i, (included, lines) ->
                val added = Money(lines.sumOf { it.amount.cents })
                Totals(shares[i] - added, emptyList(), shares[i], included, whole.taxVisibleOnReceipt, taxLines = lines)
            }
        }
        val raw = groups.map { group ->
            val basket = BillGroupAllocations
                .join(CheckLines, JoinType.INNER, BillGroupAllocations.lineId, CheckLines.id)
                .selectAll().where { BillGroupAllocations.groupId eq group[BillGroups.id].value }
                .map { basketLine(it, it[BillGroupAllocations.qty]) }
            val corkage = if (group[BillGroups.includesCorkage]) check[Checks.corkageBottles] else 0
            TransactionPipeline.computeTotals(basket, corkage, config)
        }
        return TransactionPipeline.apportionTax(raw, config)
    }

    /** One group's taxes: frozen at lock, else its live share. */
    private fun groupTaxLines(group: ResultRow, live: Totals): List<TaxLine> =
        group[BillGroups.lockedTaxesJson]?.let(::taxLinesFromJson)
            ?: if (group[BillGroups.lockedTotalCents] != null) emptyList() else live.taxLines

    private fun computeTotals(check: ResultRow): Totals {
        val checkId = check[Checks.id].value
        // PENDING (customer-submitted, unaccepted) lines never count toward totals
        val rows = CheckLines
            .join(Items, JoinType.LEFT, CheckLines.itemId, Items.id)
            .join(ItemVariants, JoinType.LEFT, CheckLines.variantId, ItemVariants.id)
            .selectAll()
            .where { (CheckLines.checkId eq checkId) and (CheckLines.status eq "ACTIVE") }
            .toList()
        val basket = rows.map { basketLine(it, it[CheckLines.qty]) }
        return TransactionPipeline.computeTotals(basket, check[Checks.corkageBottles], config,
            promotions = promotionHits(rows))
    }

    /** The store's deals on these lines (none for a store without any). */
    private fun promotionHits(rows: List<ResultRow>): List<dev.dwhipstock.pos.sdk.PromoHit> {
        if (config.promotions.isEmpty()) return emptyList()
        val fuel = dev.dwhipstock.pos.forecourt.fuelLineViews(rows.mapNotNull { it[CheckLines.fuelSaleId] })
        return dev.dwhipstock.pos.sdk.Promotions.apply(config.promotions, rows.map { r ->
            val f = r[CheckLines.fuelSaleId]?.let { fuel[it] }
            dev.dwhipstock.pos.sdk.PromoItem(
                lineId = r[CheckLines.id].value,
                itemId = r[CheckLines.itemId],
                category = LineSnapshot.categoryId(r),
                subcategory = r.getOrNull(Items.subcategory),
                size = r.getOrNull(Items.sizeLabel),
                variantLabel = LineSnapshot.labelEn(r),
                qty = r[CheckLines.qty],
                unitPriceCents = r[CheckLines.unitPriceCents],
                taxable = r[CheckLines.taxable],
                fuelVolumeMilli = f?.takeIf { it.mode == "POSTPAY" }?.volumeMilli,
            )
        })
    }

    /** A sale's promotions: frozen at lock, else live. */
    private fun discountsOf(check: ResultRow, live: Totals): List<DiscountView> =
        check[Checks.lockedDiscountsJson]?.let(::discountsFromJson)
            ?: if (check[Checks.lockedGrandTotalCents] != null) emptyList()
            else live.promotions.map { DiscountView(it.code, it.label, it.labelEs, it.amount.cents, it.taxableAmount.cents) }

    /** A line as the pipeline sees it: price, qty, and its ring-up tax / deposit facts. */
    private fun basketLine(row: ResultRow, qty: Int) = BasketLine(
        unitPrice = Money(row[CheckLines.unitPriceCents]),
        qty = qty,
        taxable = row[CheckLines.taxable],
        depositPerUnit = Money(row[CheckLines.depositCents]),
    )

    private fun tenderedSoFar(checkId: Int): Money =
        Money(Tenders.selectAll().where { Tenders.transactionId eq checkId }.sumOf { it[Tenders.amountAppliedCents] })

    private fun groupTenderedSoFar(groupId: Int): Money =
        Money(Tenders.selectAll().where { Tenders.billGroupId eq groupId }.sumOf { it[Tenders.amountAppliedCents] })

    /** Settlement overlay for the client: live per-group totals + allocations + unassigned pool. */
    private fun buildSplitView(check: ResultRow): SplitView? {
        val checkId = check[Checks.id].value
        val groups = splitGroups(checkId)
        if (groups.isEmpty()) return null
        val allocationsByGroup = BillGroupAllocations.selectAll()
            .where { BillGroupAllocations.groupId inList groups.map { it[BillGroups.id].value } }
            .groupBy { it[BillGroupAllocations.groupId] }
        val even = groups.any { it[BillGroups.fixedAmountCents] != null }
        // an even split's cash shares round once for the whole bill
        val evenCash = if (!even) null else TransactionPipeline.evenSplitCashDue(
            groups.map { Money(it[BillGroups.lockedTotalCents] ?: it[BillGroups.fixedAmountCents] ?: 0L) },
            config.roundingPolicy)
        val groupViews = groups.zip(groupTotals(check, groups)).mapIndexed { index, (group, totals) ->
            val gid = group[BillGroups.id].value
            val grand = group[BillGroups.lockedTotalCents] ?: totals.grandTotal.cents
            val taxes = groupTaxLines(group, totals)
            val paid = groupTenderedSoFar(gid).cents
            val cashDue = evenCash?.takeIf { paid == 0L }?.get(index)?.cents
                ?: config.roundingPolicy.roundCashDue(Money(grand - paid)).cents
            GroupView(
                id = gid,
                number = group[BillGroups.groupNumber],
                includesCorkage = group[BillGroups.includesCorkage],
                fixedAmountCents = group[BillGroups.fixedAmountCents],
                allocations = (allocationsByGroup[gid] ?: emptyList()).map {
                    AllocationView(it[BillGroupAllocations.lineId], it[BillGroupAllocations.qty])
                },
                itemsSubtotalCents = totals.itemsSubtotal.cents,
                fees = totals.feeLines.map { FeeView(it.code, it.labelFr, it.labelEn, it.amount.cents) },
                grandTotalCents = grand,
                paidCents = paid,
                outstandingCents = grand - paid,
                subtotalCents = grand - taxes.sumOf { it.amount.cents },
                taxes = taxes.map { it.toView() },
                cashDueCents = cashDue,
                cashRoundingCents = cashDue - (grand - paid),
            )
        }
        val allocatedByLine = allocationsByGroup.values.flatten()
            .groupBy({ it[BillGroupAllocations.lineId] }) { it[BillGroupAllocations.qty] }
            .mapValues { it.value.sum() }
        // an even ÷N split is money-only: lines are never assigned, so there is
        // no "unassigned pool" — reporting one would read as an incomplete split
        val unassigned = if (even) emptyList() else CheckLines.selectAll()
            .where { (CheckLines.checkId eq checkId) and (CheckLines.status eq "ACTIVE") }
            .mapNotNull { row ->
                val remaining = row[CheckLines.qty] - (allocatedByLine[row[CheckLines.id].value] ?: 0)
                if (remaining > 0) AllocationView(row[CheckLines.id].value, remaining) else null
            }
        return SplitView(
            groups = groupViews,
            unassigned = unassigned,
            even = even,
            locked = check[Checks.status] != "OPEN",
        )
    }

    private fun loadCheck(checkId: Int): CheckView {
        val check = requireCheck(checkId)
        val totals = computeTotals(check)
        // same rule as the receipt: the variant label only disambiguates when
        // the item actually has multiple sizes (bottle/pitcher/tower)
        val variantCounts = variantCountsOnCheck(checkId)
        val itemNames = dev.dwhipstock.pos.base.Translations.of(dev.dwhipstock.pos.base.Translations.ITEM)
        val variantNames = dev.dwhipstock.pos.base.Translations.of(dev.dwhipstock.pos.base.Translations.VARIANT)
        val lineRows = CheckLines
            .join(Items, JoinType.LEFT, CheckLines.itemId, Items.id)
            .join(ItemVariants, JoinType.LEFT, CheckLines.variantId, ItemVariants.id)
            .selectAll()
            .where { CheckLines.checkId eq checkId }
            .toList()
        val fuel = dev.dwhipstock.pos.forecourt.fuelLineViews(lineRows.mapNotNull { it[CheckLines.fuelSaleId] })
        val allLines = lineRows
            .map { row ->
                val showVariant = LineSnapshot.showVariant(row, variantCounts)
                Pair(row[CheckLines.status], LineView(
                    id = row[CheckLines.id].value,
                    itemId = row[CheckLines.itemId],
                    variantId = row[CheckLines.variantId],
                    nameFr = LineSnapshot.nameFr(row),
                    nameEn = LineSnapshot.nameEn(row),
                    variantLabelFr = if (showVariant) LineSnapshot.labelFr(row) else null,
                    variantLabelEn = if (showVariant) LineSnapshot.labelEn(row) else null,
                    qty = row[CheckLines.qty],
                    unitPriceCents = row[CheckLines.unitPriceCents],
                    lineTotalCents = row[CheckLines.unitPriceCents] * row[CheckLines.qty],
                    note = row[CheckLines.note],
                    ageRestricted = row[CheckLines.ageRestricted],
                    depositCents = row[CheckLines.depositCents],
                    taxable = row[CheckLines.taxable],
                    fuel = row[CheckLines.fuelSaleId]?.let { fuel[it] },
                    names = LineSnapshot.names(row, itemNames),
                    variantNames = if (showVariant) LineSnapshot.variantNames(row, variantNames) else emptyMap(),
                    regularUnitPriceCents = row[CheckLines.regularUnitPriceCents],
                    special = ItemSchedules.tagOf(row[CheckLines.specialJson]),
                ))
            }
        val lines = allLines.filter { it.first == "ACTIVE" }.map { it.second }
        val pendingLines = allLines.filter { it.first == "PENDING" }.map { it.second }
        val tenders = Tenders.selectAll().where { Tenders.transactionId eq checkId }.map {
            TenderView(it[Tenders.id].value, it[Tenders.type], it[Tenders.amountTenderedCents],
                it[Tenders.amountAppliedCents], it[Tenders.roundingAdjustmentCents], it[Tenders.changeCents],
                it[Tenders.billGroupId], it[Tenders.tipCents])
        }
        val grandTotal = check[Checks.lockedGrandTotalCents] ?: totals.grandTotal.cents
        val taxes = taxLinesOf(check, totals)
        val applied = tenders.sumOf { it.amountAppliedCents }
        return CheckView(
            id = checkId,
            tableId = check[Checks.tableId],
            status = check[Checks.status],
            openedAt = VenueClock.iso(check[Checks.openedAt]),
            corkageBottles = check[Checks.corkageBottles],
            lines = lines,
            pendingLines = pendingLines,
            itemsSubtotalCents = totals.itemsSubtotal.cents,
            fees = totals.feeLines.map { FeeView(it.code, it.labelFr, it.labelEn, it.amount.cents) },
            grandTotalCents = grandTotal,
            taxIncludedCents = check[Checks.lockedTaxIncludedCents] ?: totals.taxIncluded.cents,
            paidCents = applied,
            outstandingCents = grandTotal - applied,
            cashDueCents = config.roundingPolicy.roundCashDue(Money(grandTotal - applied)).cents,
            cashRoundingCents = config.roundingPolicy.cashAdjustment(Money(grandTotal - applied)).cents,
            tenders = tenders,
            split = buildSplitView(check),
            subtotalCents = grandTotal - taxes.sumOf { it.amount.cents },
            taxes = taxes.map { it.toView() },
            discounts = discountsOf(check, totals),
            ageCheckRequired = AgeGate.required(checkId),
            ageCleared = AgeGate.cleared(checkId),
            ageCheckFailed = AgeGate.latest(checkId)?.let { !it[dev.dwhipstock.pos.base.AgeChecks.passed] } == true &&
                AgeGate.passedAt(checkId) == null,
            cardPaymentPending = check[Checks.status] in listOf("OPEN", "TOTAL_LOCKED") && CardInFlight.on(checkId).isNotEmpty(),
        )
    }
}

/**
 * Asked before any other tender, a void, or a close on a check: a card payment
 * for it (or for [groupId]'s bill group) may still be on the reader — the store
 * restarted mid-payment — and the guest must not pay twice. Settles those
 * payments with the reader first, then throws 409 `card_payment_pending` if
 * one is still in progress. Never called inside a database transaction.
 */
fun interface CardPaymentGuard {
    fun requireNoCardInFlight(checkId: Int, groupId: Int?)
}

/** Card payments still in flight on a check, from the database alone (no reader call). */
object CardInFlight {
    /** terminal_payments: CREATING = the start call itself, PENDING = on the reader. */
    val TERMINAL = listOf("CREATING", "PENDING")
    /** stripe_payments without a tender: no PaymentIntent yet, created (on the tablet's reader), captured but not recorded. */
    val STRIPE = listOf("CREATING", "CREATED", "CAPTURED")

    /** Bill group ids (null = the whole check) of in-flight card payments on [checkId]. Inside or outside a transaction. */
    fun on(checkId: Int): List<Int?> = transaction {
        TerminalPayments.selectAll()
            .where { (TerminalPayments.checkId eq checkId) and (TerminalPayments.status inList TERMINAL) }
            .map { it[TerminalPayments.billGroupId] } +
            StripePayments.selectAll()
                .where { (StripePayments.checkId eq checkId) and (StripePayments.status inList STRIPE) and StripePayments.tenderId.isNull() }
                .map { it[StripePayments.billGroupId] }
    }

    /** A payment for [rowGroup] blocks money on [groupId]: the same group, or either side is the whole check. */
    fun blocks(rowGroup: Int?, groupId: Int?): Boolean = groupId == null || rowGroup == null || rowGroup == groupId
}

// These views double as API DTOs for now. TODO: split a real DTO layer when the
// shared contracts package (M0 leftover) materializes.
@kotlinx.serialization.Serializable
data class CheckView(
    val id: Int,
    val tableId: String,
    val status: String,
    val openedAt: String = "",
    val corkageBottles: Int,
    val lines: List<LineView>,
    /** Customer-submitted via QR, awaiting staff accept/reject. Not in totals. */
    val pendingLines: List<LineView> = emptyList(),
    val itemsSubtotalCents: Long,
    val fees: List<FeeView>,
    val grandTotalCents: Long,
    val taxIncludedCents: Long, // tax inside the shelf price (inclusive policies only)
    val paidCents: Long,
    val outstandingCents: Long,
    /**
     * What settling [outstandingCents] in CASH takes: rounded to the nickel
     * (unless cash rounding is off). [cashRoundingCents] = cashDue − outstanding,
     * signed (−2, +1). Card and other electronic payments are the exact amount.
     */
    val cashDueCents: Long = 0,
    val cashRoundingCents: Long = 0,
    val tenders: List<TenderView>,
    /** Settlement-time bill groups; null = not split (the default single-bill flow). */
    val split: SplitView? = null,
    /** Pre-tax: items + fees. subtotalCents + sum(taxes) = grandTotalCents. */
    val subtotalCents: Long = 0,
    /** Taxes added on top of [subtotalCents], one per tax (GST, QST). */
    val taxes: List<TaxView> = emptyList(),
    /** Promotions taken off before tax (a c-store's deals); their sum is inside the total. */
    val discounts: List<DiscountView> = emptyList(),
    /** Lines of the request just made that the menu refused (a partial guest basket); empty otherwise. */
    val rejected: List<RejectedLine> = emptyList(),
    /** Retail: an age-restricted line is on the sale, so payment needs an ID check. */
    val ageCheckRequired: Boolean = false,
    /** No ID check needed, or one passed. */
    val ageCleared: Boolean = true,
    /** The latest ID check failed (under age / expired) and none has passed. */
    val ageCheckFailed: Boolean = false,
    /**
     * A card payment for this check is still on the reader (the store or the
     * tablet restarted mid-payment): other tenders, void and close wait for it.
     * GET /checks/{id}/card-pending has the details and settles it.
     */
    val cardPaymentPending: Boolean = false,
)

@kotlinx.serialization.Serializable
data class SplitView(
    val groups: List<GroupView>,
    /** ACTIVE quantity not yet allocated to any group (lineId → remaining qty). */
    val unassigned: List<AllocationView>,
    /** Even ÷N split: money-only groups, no line allocation. */
    val even: Boolean,
    /** True once any group has a tender (check TOTAL_LOCKED) — no further split edits. */
    val locked: Boolean,
)

@kotlinx.serialization.Serializable
data class GroupView(
    val id: Int,
    val number: Int,
    val includesCorkage: Boolean,
    val fixedAmountCents: Long? = null,
    val allocations: List<AllocationView>,
    val itemsSubtotalCents: Long,
    val fees: List<FeeView>,
    val grandTotalCents: Long,
    val paidCents: Long,
    val outstandingCents: Long,
    /** The group's pre-tax share; subtotalCents + sum(taxes) = grandTotalCents. */
    val subtotalCents: Long = 0,
    /** The group's share of the check's taxes (the groups' shares sum to the check's). */
    val taxes: List<TaxView> = emptyList(),
    /** The group's outstanding paid in cash, rounded to the nickel; see [CheckView.cashDueCents]. */
    val cashDueCents: Long = 0,
    val cashRoundingCents: Long = 0,
)

@kotlinx.serialization.Serializable
data class AllocationView(val lineId: Int, val qty: Int)

@kotlinx.serialization.Serializable
data class LineView(
    val id: Int,
    /** Null on OPEN lines (rung off-menu by name + price). */
    val itemId: String? = null,
    val variantId: String? = null,
    val nameFr: String,
    val nameEn: String,
    /** Only set when the item has >1 variant — same disambiguation rule as the receipt. */
    val variantLabelFr: String? = null,
    val variantLabelEn: String? = null,
    val qty: Int,
    val unitPriceCents: Long,
    val lineTotalCents: Long,
    val note: String?,
    /** Retail shelf facts captured at ring-up (038). */
    val ageRestricted: Boolean = false,
    /** Container deposit (CRV) per unit. */
    val depositCents: Long = 0,
    val taxable: Boolean = true,
    /** A fuel or prepay line (a gas station): pump, grade, gallons, price per gallon. */
    val fuel: dev.dwhipstock.pos.forecourt.FuelLineView? = null,
    /** The item's / size's names in the store's other languages, as rung. */
    val names: Map<String, String> = emptyMap(),
    val variantNames: Map<String, String> = emptyMap(),
    /** Rung at a menu special: the menu price it replaced, and which special (days, window, own name). */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val regularUnitPriceCents: Long? = null,
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val special: dev.dwhipstock.pos.sdk.MenuSpecials.Tag? = null,
)

/** One promotion on a sale: [amountCents] off, [taxableCents] of it off taxable goods. */
@kotlinx.serialization.Serializable
data class DiscountView(
    val code: String,
    val label: String,
    val labelEs: String,
    val amountCents: Long,
    val taxableCents: Long = 0,
)

fun discountsToJson(list: List<DiscountView>): JsonArray = JsonArray(list.map { d ->
    buildJsonObject {
        put("code", d.code)
        put("label", d.label)
        put("labelEs", d.labelEs)
        put("amountCents", d.amountCents)
        put("taxableCents", d.taxableCents)
    }
})

fun discountsFromJson(text: String): List<DiscountView> = Json.parseToJsonElement(text).jsonArray.map { e ->
    val o = e.jsonObject
    fun s(key: String) = o[key]?.jsonPrimitive?.contentOrNull ?: ""
    DiscountView(s("code"), s("label"), s("labelEs"), o["amountCents"]?.jsonPrimitive?.longOrNull ?: 0,
        o["taxableCents"]?.jsonPrimitive?.longOrNull ?: 0)
}

@kotlinx.serialization.Serializable
data class FeeView(val code: String, val labelFr: String, val labelEn: String, val amountCents: Long)

/** One tax added on top of the subtotal. [ratePercent] is a decimal string ("9.975"). */
@kotlinx.serialization.Serializable
data class TaxView(
    val code: String,
    val labelFr: String,
    val labelEn: String,
    val ratePercent: String,
    val registrationNumber: String,
    val amountCents: Long,
    /** Who the tax is paid to ("NCDOR"); empty = not said. Back office only. */
    val remitTo: String = "",
)

fun TaxLine.toView() = TaxView(
    component.code, component.labelFr, component.labelEn, component.rateText, component.registrationNumber, amount.cents,
    component.remitTo,
)

/** [views] as a guest sees them under [display] ([dev.dwhipstock.pos.sdk.guestTaxLines]). */
fun guestTaxViews(views: List<TaxView>, display: dev.dwhipstock.pos.sdk.TaxDisplay): List<TaxView> =
    if (display !is dev.dwhipstock.pos.sdk.TaxDisplay.Combined || views.size < 2) views
    else dev.dwhipstock.pos.sdk.guestTaxLines(views.map { v ->
        TaxLine(TaxComponent(v.code, v.labelFr, v.labelEn, v.ratePercent.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO, v.registrationNumber), Money(v.amountCents))
    }, display).map { it.toView() }

/**
 * The stored / synced form of a tax breakdown (checks.locked_taxes_json,
 * bill_groups.locked_taxes_json, refunds.taxes_json and the outbox payloads):
 * [{code, labelFr, labelEn, ratePercent, registrationNumber, amountCents, remitTo?}].
 * Labels, rate, number and remittance authority travel with the amount, so history reads as charged.
 */
fun taxLinesToJson(lines: List<TaxLine>): JsonArray = JsonArray(lines.map { t ->
    buildJsonObject {
        put("code", t.component.code)
        put("labelFr", t.component.labelFr)
        put("labelEn", t.component.labelEn)
        put("ratePercent", t.component.rateText)
        put("registrationNumber", t.component.registrationNumber)
        if (t.component.remitTo.isNotEmpty()) put("remitTo", t.component.remitTo)
        put("amountCents", t.amount.cents)
    }
})

fun taxLinesFromJson(text: String): List<TaxLine> = Json.parseToJsonElement(text).jsonArray.map { e ->
    val o = e.jsonObject
    fun s(key: String) = o[key]?.jsonPrimitive?.contentOrNull ?: ""
    TaxLine(
        TaxComponent(s("code"), s("labelFr"), s("labelEn"), s("ratePercent").toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO,
            s("registrationNumber"), s("remitTo")),
        Money(o["amountCents"]?.jsonPrimitive?.longOrNull ?: 0L),
    )
}

/** Per-tax sums over several breakdowns, first-seen order (a check's groups, a check's refunds). */
fun sumTaxLines(breakdowns: List<List<TaxLine>>): List<TaxLine> {
    val out = LinkedHashMap<String, TaxLine>()
    for (line in breakdowns.flatten()) {
        out[line.component.code] = out[line.component.code]?.let { it.copy(amount = it.amount + line.amount) } ?: line
    }
    return out.values.toList()
}

@kotlinx.serialization.Serializable
data class TenderView(
    val id: Int,
    val type: String,
    val amountTenderedCents: Long,
    val amountAppliedCents: Long,
    val roundingAdjustmentCents: Long,
    val changeCents: Long,
    /** Bill group this tender paid into; null = whole-check tender. */
    val groupId: Int? = null,
    /** A card tip taken on top of the bill (card reader / Stripe); not in [amountAppliedCents]. */
    val tipCents: Long = 0,
)

/**
 * Freeze a catalog item's shelf facts onto the line being rung (038), like
 * its price: taxable, the container deposit per unit sold, and whether the
 * line needs an ID check. Pub items carry the defaults (taxable, no deposit,
 * not restricted).
 */
internal fun captureShelfFacts(st: org.jetbrains.exposed.sql.statements.UpdateBuilder<*>, item: ResultRow) {
    st[CheckLines.taxable] = item[Items.taxable]
    st[CheckLines.depositCents] =
        dev.dwhipstock.pos.sdk.Crv.perUnit(dev.dwhipstock.pos.sdk.Crv.size(item[Items.crvSize]), item[Items.packUnits]).cents
    st[CheckLines.ageRestricted] = item[Items.ageRestricted]
}

/** A line rung at a menu special keeps the menu price it replaced and which special it was (064). */
internal fun captureSpecial(st: org.jetbrains.exposed.sql.statements.UpdateBuilder<*>, priced: ItemSchedules.Priced) {
    val tag = priced.tag ?: return
    st[CheckLines.regularUnitPriceCents] = priced.regularCents
    st[CheckLines.specialJson] = ItemSchedules.tagJson(tag)
}
