package dev.dwhipstock.poscloud.exports

import dev.dwhipstock.poscloud.reports.ReportCtx
import dev.dwhipstock.poscloud.reports.lenientJson
import dev.dwhipstock.poscloud.reports.lit
import dev.dwhipstock.poscloud.reports.rowsOf
import dev.dwhipstock.poscloud.reports.scopeSql
import dev.dwhipstock.poscloud.reports.taxSummary
import dev.dwhipstock.poscloud.reports.taxesOf
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.sql.ResultSet
import java.time.LocalDateTime
import java.time.OffsetDateTime

/**
 * The datasets a manager can export (/v1/exports). Each one is a flat table,
 * one row per thing (a check, a line, a refund …), read straight from the
 * cloud's projection of what the stores sent — no figure is recomputed: money
 * is the stores' own cents, written as decimals. Rows stream from Postgres
 * (a server-side cursor) to the response, so a big range never sits in memory.
 *
 * Scoping is the reports': the caller's tenant, the stores of the request
 * (one, or all of the tenant's), each store over its own business days
 * ([scopeSql]). Menu and staff are the current state, not dated.
 */

/** One column, with the line the README gives it. */
class Column(val name: String, val about: String)

internal class Dataset(
    val id: String,
    /** The README's one-line description of the file. */
    val about: String,
    val columns: List<Column>,
    /** false = current state (menu, staff): the date range does not apply. */
    val dated: Boolean = true,
    /** Rows the export would write (checked against the caps before anything is sent). */
    val count: (ReportCtx) -> Long,
    val write: (ReportCtx, RowSink) -> Unit,
)

// --- helpers (call inside a transaction) ---

/** Rows of [sql] through a Postgres cursor ([FETCH] at a time), never the whole result in memory. */
private fun stream(sql: String, each: (ResultSet) -> Unit) {
    val jdbc = TransactionManager.current().connection.connection as java.sql.Connection
    jdbc.createStatement().use { st ->
        st.fetchSize = FETCH
        st.executeQuery(sql).use { rs -> while (rs.next()) each(rs) }
    }
}

private const val FETCH = 1000

private fun countOf(sql: String): Long = rowsOf("SELECT count(*) $sql") { it.getLong(1) }.first()

/** tenant AND venue in the request's stores (for current-state tables). */
private fun storesSql(ctx: ReportCtx, a: String): String =
    "$a.tenant_id = ${lit(ctx.tenantId)} AND $a.venue_id IN (${ctx.venues.joinToString(",") { lit(it.id) }})"

private fun ResultSet.long(col: String): Long? = getLong(col).takeUnless { wasNull() }
private fun ResultSet.int(col: String): Long? = getInt(col).takeUnless { wasNull() }?.toLong()
private fun ResultSet.bool(col: String): Boolean? = getBoolean(col).takeUnless { wasNull() }

/** A stored instant as the store's wall-clock time (its zone is the row's `timezone` column). */
private fun ReportCtx.local(rs: ResultSet, col: String, venueId: String): LocalDateTime? =
    rs.getObject(col, OffsetDateTime::class.java)?.atZoneSameInstant(zoneOf(venueId))?.toLocalDateTime()

private fun ReportCtx.storeName(venueId: String): String =
    venues.firstOrNull { it.id == venueId }?.venue?.name ?: venueId

private fun ReportCtx.store(venueId: String): List<Cell> = listOf(text(venueId), text(storeName(venueId)))

private fun ReportCtx.zoneCell(venueId: String): Cell = text(zoneOf(venueId).id)

/** The row's currency as sent, else (older stores) its store's. */
private fun ReportCtx.currency(rs: ResultSet, venueId: String): Cell =
    text(rs.getString("currency")?.takeIf { it.isNotBlank() } ?: currencyOf(venueId))

private val STORE_COLUMNS = listOf(
    Column("store_id", "The store's id in the portal."),
    Column("store_name", "The store's name."),
)

private fun zoneColumn(what: String) = Column("timezone", "The store's time zone (IANA name); $what are wall-clock times there.")

private val CURRENCY = Column("currency", "ISO 4217 code of every money column in the row.")

private fun moneyCol(name: String, about: String) = Column(name, "$about Decimal, in the row's currency.")

@Serializable
private class TenderBit(val type: String = "", val cents: Long? = null)

@Serializable
private class DiscountBit(val code: String = "", val label: String = "", val amountCents: Long = 0)

private fun tendersText(json: String?): String =
    json?.let { runCatching { lenientJson.decodeFromString<List<TenderBit>>(it) }.getOrNull() }.orEmpty()
        .joinToString("; ") { "${it.type} ${decimal(it.cents ?: 0).toPlainString()}" }

private fun discountsText(json: String?): String =
    json?.let { runCatching { lenientJson.decodeFromString<List<DiscountBit>>(it) }.getOrNull() }.orEmpty()
        .joinToString("; ") { "${it.label.ifBlank { it.code }} ${decimal(it.amountCents).toPlainString()}" }

private fun taxesText(json: String?): String = taxesOf(json).joinToString("; ") { t ->
    buildString {
        append(t.code)
        if (t.ratePercent.isNotEmpty()) append(" ").append(t.ratePercent).append("%")
        append(" ").append(decimal(t.amountCents).toPlainString())
        if (t.remitTo.isNotEmpty()) append(" (remit to ").append(t.remitTo).append(")")
    }
}

/** A tax column's key: the code and rate the store stamped on the sale. */
private data class TaxKey(val code: String, val rate: String) {
    val stem: String get() = if (rate.isEmpty()) "tax_$code" else "tax_${code}_$rate"
}

// --- the datasets ---

private fun checksWhere(ctx: ReportCtx) =
    "FROM checks c WHERE ${scopeSql(ctx, "c", "closed_at")} AND c.status IN ('CLOSED', 'VOID')"

private val sales = Dataset(
    id = "sales",
    about = "One row per settled or voided check (sale) closed in the date range.",
    columns = STORE_COLUMNS + listOf(
        Column("check_id", "The check's number at its store (numbers repeat across stores)."),
        Column("status", "CLOSED (paid) or VOID (voided)."),
        Column("opened_at", "When the check was opened."),
        Column("closed_at", "When it was paid or voided; the date range applies to this."),
        zoneColumn("opened_at and closed_at"),
        Column("shift_id", "The till shift it was closed in, if any."),
        Column("table", "Table label, if any."),
        Column("zone", "Floor zone, if any."),
        Column("opened_by", "Staff id that opened the check."),
        CURRENCY,
        moneyCol("subtotal", "Before tax: total − tax_total."),
        moneyCol("discount_total", "Promotions taken off before tax."),
        Column("discounts", "Each promotion and its amount, as the store sent them."),
        moneyCol("tax_total", "All taxes on the check."),
        Column("tax_<CODE>_<RATE>_amount", "One column per tax code and rate charged in the range (e.g. tax_GST_5_amount): that tax's amount on this check, decimal."),
        Column("tax_<CODE>_<RATE>_remit_to", "Who the store pays that tax to (e.g. a state or county), when the store says."),
        moneyCol("corkage", "Corkage fee."),
        moneyCol("service_charge", "Service charge."),
        moneyCol("total", "What the guest paid (tax included)."),
        Column("tenders", "Each payment's type and applied amount."),
        Column("void_reason", "Why it was voided (VOID only)."),
        Column("voided_by", "Staff id that voided it (VOID only)."),
    ),
    count = { ctx -> countOf(checksWhere(ctx)) },
    write = { ctx, sink ->
        val keys = rowsOf("""
            SELECT DISTINCT t->>'code', coalesce(t->>'ratePercent', '')
            FROM checks c CROSS JOIN LATERAL jsonb_array_elements(
                   CASE WHEN jsonb_typeof(c.taxes) = 'array' THEN c.taxes ELSE '[]'::jsonb END) t
            WHERE ${scopeSql(ctx, "c", "closed_at")} AND c.status IN ('CLOSED', 'VOID') AND t->>'code' IS NOT NULL
            ORDER BY 1, 2""") { TaxKey(it.getString(1), it.getString(2)) }
        val head = listOf("store_id", "store_name", "check_id", "status", "opened_at", "closed_at", "timezone",
            "shift_id", "table", "zone", "opened_by", "currency", "subtotal", "discount_total", "discounts", "tax_total")
        sink.header(head + keys.flatMap { listOf("${it.stem}_amount", "${it.stem}_remit_to") } +
            listOf("corkage", "service_charge", "total", "tenders", "void_reason", "voided_by"))
        stream("""
            SELECT c.venue_id, c.check_id, c.status, c.opened_at, c.closed_at, c.shift_id, c.table_label,
                   coalesce(c.zone_name_en, c.zone_name_fr) AS zone, c.opened_by, c.currency,
                   c.grand_total_cents, c.tax_included_cents, c.discount_cents, c.discounts::text AS discounts,
                   c.taxes::text AS taxes, c.corkage_cents, c.service_charge_cents, c.void_reason, c.voided_by,
                   (SELECT json_agg(json_build_object('type', t.type, 'cents', t.amount_applied_cents) ORDER BY t.tender_id)
                      FROM check_tenders t
                     WHERE t.tenant_id = c.tenant_id AND t.venue_id = c.venue_id AND t.check_id = c.check_id)::text AS tenders
            ${checksWhere(ctx)}
            ORDER BY c.venue_id, c.closed_at, c.check_id""") { rs ->
            val v = rs.getString("venue_id")
            val total = rs.long("grand_total_cents")
            val tax = rs.long("tax_included_cents")
            val taxes = taxesOf(rs.getString("taxes"))
            val byKey = taxes.groupBy { TaxKey(it.code, it.ratePercent) }
            sink.row(ctx.store(v) + listOf(
                count(rs.int("check_id")), text(rs.getString("status")),
                time(ctx.local(rs, "opened_at", v)), time(ctx.local(rs, "closed_at", v)), ctx.zoneCell(v),
                count(rs.long("shift_id")), text(rs.getString("table_label")), text(rs.getString("zone")),
                text(rs.getString("opened_by")), ctx.currency(rs, v),
                money(total?.let { it - (tax ?: 0) }), money(rs.long("discount_cents")),
                text(discountsText(rs.getString("discounts"))), money(tax),
            ) + keys.flatMap { k ->
                val hit = byKey[k]
                listOf(money(hit?.sumOf { it.amountCents }), text(hit?.firstOrNull { it.remitTo.isNotEmpty() }?.remitTo))
            } + listOf(
                money(rs.long("corkage_cents")), money(rs.long("service_charge_cents")), money(total),
                text(tendersText(rs.getString("tenders"))), text(rs.getString("void_reason")), text(rs.getString("voided_by")),
            ))
        }
    },
)

private fun linesWhere(ctx: ReportCtx) = """
    FROM check_lines l JOIN checks c
      ON c.tenant_id = l.tenant_id AND c.venue_id = l.venue_id AND c.check_id = l.check_id
    WHERE ${scopeSql(ctx, "c", "closed_at")} AND c.status IN ('CLOSED', 'VOID')"""

private val saleLines = Dataset(
    id = "sale-lines",
    about = "One row per item line on the checks in sales.csv (same checks, same range).",
    columns = STORE_COLUMNS + listOf(
        Column("check_id", "The check the line is on (join to sales.csv on store_id + check_id)."),
        Column("check_status", "CLOSED or VOID."),
        Column("closed_at", "When the check was closed."),
        zoneColumn("closed_at"),
        Column("line_id", "The line's id at its store."),
        Column("item_id", "The menu item's id (join to menu-items.csv)."),
        Column("item_name_en", "Item name (English) as rung up."),
        Column("item_name_fr", "Item name (French) as rung up."),
        Column("variant_id", "The variant (size, pour …) id, if any."),
        Column("variant_en", "Variant label (English)."),
        Column("variant_fr", "Variant label (French)."),
        Column("category_id", "The item's category id."),
        Column("qty", "Quantity."),
        CURRENCY,
        moneyCol("unit_price", "Price of one."),
        moneyCol("line_total", "The line's total before check-level discounts and tax."),
    ),
    count = { ctx -> countOf(linesWhere(ctx)) },
    write = { ctx, sink ->
        sink.header(listOf("store_id", "store_name", "check_id", "check_status", "closed_at", "timezone", "line_id",
            "item_id", "item_name_en", "item_name_fr", "variant_id", "variant_en", "variant_fr", "category_id", "qty",
            "currency", "unit_price", "line_total"))
        stream("""
            SELECT l.venue_id, l.check_id, c.status, c.closed_at, l.line_id, l.item_id,
                   coalesce(l.name_en, l.display_name) AS name_en, coalesce(l.name_fr, l.display_name) AS name_fr,
                   l.variant_id, l.variant_label_en, l.variant_label_fr, l.category_id, l.qty,
                   c.currency, l.unit_price_cents, l.line_total_cents
            ${linesWhere(ctx)}
            ORDER BY l.venue_id, c.closed_at, l.check_id, l.id""") { rs ->
            val v = rs.getString("venue_id")
            sink.row(ctx.store(v) + listOf(
                count(rs.int("check_id")), text(rs.getString("status")), time(ctx.local(rs, "closed_at", v)),
                ctx.zoneCell(v), count(rs.long("line_id")), text(rs.getString("item_id")),
                text(rs.getString("name_en")), text(rs.getString("name_fr")), text(rs.getString("variant_id")),
                text(rs.getString("variant_label_en")), text(rs.getString("variant_label_fr")),
                text(rs.getString("category_id")), count(rs.int("qty")), ctx.currency(rs, v),
                money(rs.long("unit_price_cents")), money(rs.long("line_total_cents")),
            ))
        }
    },
)

private fun refundsWhere(ctx: ReportCtx) = "FROM refunds r WHERE ${scopeSql(ctx, "r", "created_at")}"

private val refunds = Dataset(
    id = "refunds",
    about = "One row per refund issued in the date range (by refund date, not the original sale's).",
    columns = STORE_COLUMNS + listOf(
        Column("refund_id", "The refund's id at its store."),
        Column("check_id", "The check refunded, if any."),
        Column("shift_id", "The till shift it was issued in, if any."),
        Column("refunded_at", "When it was issued; the date range applies to this."),
        zoneColumn("refunded_at"),
        CURRENCY,
        moneyCol("gross", "Amount given back, tax included."),
        moneyCol("net", "Before tax."),
        moneyCol("tax", "Tax reversed."),
        Column("taxes", "Each tax reversed: code, rate, amount and who it is paid to."),
        Column("tender_type", "How it was paid back (CASH, CARD …)."),
        moneyCol("cash_rounding", "Cash refunds: cash handed back − gross (nickel rounding)."),
        Column("reason", "Reason given."),
        Column("refunded_by", "Staff id that issued it."),
        Column("table", "Table label, if any."),
    ),
    count = { ctx -> countOf(refundsWhere(ctx)) },
    write = { ctx, sink ->
        sink.header(listOf("store_id", "store_name", "refund_id", "check_id", "shift_id", "refunded_at", "timezone",
            "currency", "gross", "net", "tax", "taxes", "tender_type", "cash_rounding", "reason", "refunded_by", "table"))
        stream("""
            SELECT r.venue_id, r.refund_id, r.check_id, r.shift_id, r.created_at, r.currency, r.gross_cents,
                   r.net_cents, r.tax_included_cents, r.taxes::text AS taxes, r.tender_type,
                   r.rounding_adjustment_cents, r.reason, r.refunded_by, r.table_label
            ${refundsWhere(ctx)}
            ORDER BY r.venue_id, r.created_at, r.refund_id""") { rs ->
            val v = rs.getString("venue_id")
            sink.row(ctx.store(v) + listOf(
                count(rs.long("refund_id")), count(rs.int("check_id")), count(rs.long("shift_id")),
                time(ctx.local(rs, "created_at", v)), ctx.zoneCell(v), ctx.currency(rs, v),
                money(rs.long("gross_cents")), money(rs.long("net_cents")), money(rs.long("tax_included_cents")),
                text(taxesText(rs.getString("taxes"))), text(rs.getString("tender_type")),
                money(rs.long("rounding_adjustment_cents")), text(rs.getString("reason")),
                text(rs.getString("refunded_by")), text(rs.getString("table_label")),
            ))
        }
    },
)

private fun tendersWhere(ctx: ReportCtx) = """
    FROM check_tenders t JOIN checks c
      ON c.tenant_id = t.tenant_id AND c.venue_id = t.venue_id AND c.check_id = t.check_id
    WHERE ${scopeSql(ctx, "c", "closed_at")} AND c.status IN ('CLOSED', 'VOID')"""

private val tenders = Dataset(
    id = "tenders",
    about = "One row per payment (tender) on the checks in sales.csv. The portal's payments report counts CLOSED checks only.",
    columns = STORE_COLUMNS + listOf(
        Column("tender_id", "The payment's id at its store."),
        Column("check_id", "The check it paid (join to sales.csv on store_id + check_id)."),
        Column("check_status", "CLOSED or VOID."),
        Column("tendered_at", "When it was taken."),
        zoneColumn("tendered_at"),
        Column("type", "CASH, CARD, …"),
        CURRENCY,
        moneyCol("amount_tendered", "What the guest handed over."),
        moneyCol("amount_applied", "What went to the bill."),
        moneyCol("change", "Change given."),
        moneyCol("cash_rounding", "Cash: the nickel rounding on this payment."),
    ),
    count = { ctx -> countOf(tendersWhere(ctx)) },
    write = { ctx, sink ->
        sink.header(listOf("store_id", "store_name", "tender_id", "check_id", "check_status", "tendered_at", "timezone",
            "type", "currency", "amount_tendered", "amount_applied", "change", "cash_rounding"))
        stream("""
            SELECT t.venue_id, t.tender_id, t.check_id, c.status, t.tendered_at, t.type, c.currency,
                   t.amount_tendered_cents, t.amount_applied_cents, t.change_cents, t.rounding_adjustment_cents
            ${tendersWhere(ctx)}
            ORDER BY t.venue_id, c.closed_at, t.tender_id""") { rs ->
            val v = rs.getString("venue_id")
            sink.row(ctx.store(v) + listOf(
                count(rs.long("tender_id")), count(rs.int("check_id")), text(rs.getString("status")),
                time(ctx.local(rs, "tendered_at", v)), ctx.zoneCell(v), text(rs.getString("type")), ctx.currency(rs, v),
                money(rs.long("amount_tendered_cents")), money(rs.long("amount_applied_cents")),
                money(rs.long("change_cents")), money(rs.long("rounding_adjustment_cents")),
            ))
        }
    },
)

private fun shiftsWhere(ctx: ReportCtx) = "FROM shifts s WHERE ${scopeSql(ctx, "s", "opened_at")}"

private val shifts = Dataset(
    id = "shifts",
    about = "One row per till shift opened in the date range.",
    columns = STORE_COLUMNS + listOf(
        Column("shift_id", "The shift's id at its store."),
        Column("status", "OPEN or CLOSED."),
        Column("opened_at", "When it was opened; the date range applies to this."),
        Column("opened_by", "Staff id that opened it."),
        Column("closed_at", "When it was closed (empty while open)."),
        Column("closed_by", "Staff id that closed it."),
        zoneColumn("opened_at and closed_at"),
        CURRENCY,
        moneyCol("opening_float", "Cash in the drawer at open."),
        moneyCol("revenue", "Sales in the shift, as the store counted them."),
        Column("transactions", "Checks closed in the shift."),
        moneyCol("avg_check", "Average check."),
        moneyCol("expected_cash", "Cash the drawer should hold at close."),
        moneyCol("counted_cash", "Cash counted at close."),
        moneyCol("over_short", "counted − expected."),
        moneyCol("cash_rounding", "Net nickel rounding on the shift's cash."),
    ),
    count = { ctx -> countOf(shiftsWhere(ctx)) },
    write = { ctx, sink ->
        sink.header(listOf("store_id", "store_name", "shift_id", "status", "opened_at", "opened_by", "closed_at",
            "closed_by", "timezone", "currency", "opening_float", "revenue", "transactions", "avg_check",
            "expected_cash", "counted_cash", "over_short", "cash_rounding"))
        stream("""
            SELECT s.* ${shiftsWhere(ctx)} ORDER BY s.venue_id, s.opened_at, s.shift_id""") { rs ->
            val v = rs.getString("venue_id")
            sink.row(ctx.store(v) + listOf(
                count(rs.long("shift_id")), text(rs.getString("status")), time(ctx.local(rs, "opened_at", v)),
                text(rs.getString("opened_by")), time(ctx.local(rs, "closed_at", v)), text(rs.getString("closed_by")),
                ctx.zoneCell(v), ctx.currency(rs, v), money(rs.long("opening_float_cents")),
                money(rs.long("revenue_cents")), count(rs.int("transaction_count")), money(rs.long("avg_check_cents")),
                money(rs.long("expected_cash_cents")), money(rs.long("closing_count_cents")),
                money(rs.long("over_short_cents")), money(rs.long("cash_rounding_cents")),
            ))
        }
    },
)

private fun cashWhere(ctx: ReportCtx) = "FROM cash_movements m WHERE ${scopeSql(ctx, "m", "created_at")}"

private val cashMovements = Dataset(
    id = "cash-movements",
    about = "One row per cash paid in to or out of a till in the date range.",
    columns = STORE_COLUMNS + listOf(
        Column("movement_id", "The movement's id at its store."),
        Column("shift_id", "The shift it was in (join to shifts.csv on store_id + shift_id)."),
        Column("created_at", "When; the date range applies to this."),
        zoneColumn("created_at"),
        Column("direction", "IN (paid in) or OUT (paid out)."),
        CURRENCY,
        moneyCol("amount", "Amount, always positive; see direction."),
        Column("reason", "Reason given."),
        Column("created_by", "Staff id."),
    ),
    count = { ctx -> countOf(cashWhere(ctx)) },
    write = { ctx, sink ->
        sink.header(listOf("store_id", "store_name", "movement_id", "shift_id", "created_at", "timezone", "direction",
            "currency", "amount", "reason", "created_by"))
        stream("SELECT m.* ${cashWhere(ctx)} ORDER BY m.venue_id, m.created_at, m.movement_id") { rs ->
            val v = rs.getString("venue_id")
            sink.row(ctx.store(v) + listOf(
                count(rs.long("movement_id")), count(rs.long("shift_id")), time(ctx.local(rs, "created_at", v)),
                ctx.zoneCell(v), text(rs.getString("direction")), ctx.currency(rs, v), money(rs.long("amount_cents")),
                text(rs.getString("reason")), text(rs.getString("created_by")),
            ))
        }
    },
)

private fun menuWhere(ctx: ReportCtx) = """
    FROM catalog_items i
    LEFT JOIN catalog_categories k
      ON k.tenant_id = i.tenant_id AND k.venue_id = i.venue_id AND k.id = i.category_id AND NOT k.deleted
    LEFT JOIN catalog_variants vr
      ON vr.tenant_id = i.tenant_id AND vr.venue_id = i.venue_id AND vr.item_id = i.id AND NOT vr.deleted
    WHERE ${storesSql(ctx, "i")} AND NOT i.deleted"""

private val menuItems = Dataset(
    id = "menu-items",
    about = "The menu as it is now: one row per item and variant (size, pour …), with its category and price.",
    dated = false,
    columns = STORE_COLUMNS + listOf(
        Column("item_id", "The item's id."),
        Column("item_name_en", "Name (English)."),
        Column("item_name_fr", "Name (French)."),
        Column("category_id", "Category id."),
        Column("category_en", "Category (English)."),
        Column("category_fr", "Category (French)."),
        Column("variant_id", "Variant id (empty if the item has none)."),
        Column("variant_en", "Variant label (English)."),
        Column("variant_fr", "Variant label (French)."),
        CURRENCY,
        moneyCol("price", "The variant's price."),
        Column("active", "true = on sale."),
        Column("alcohol", "true = an alcoholic item."),
        Column("barcode", "UPC, if any."),
        Column("brand", "Producer / brand, if any."),
        Column("subcategory", "Style, varietal or type, if any."),
        Column("size", "Size / pack label, if any."),
    ),
    count = { ctx -> countOf(menuWhere(ctx)) },
    write = { ctx, sink ->
        sink.header(listOf("store_id", "store_name", "item_id", "item_name_en", "item_name_fr", "category_id",
            "category_en", "category_fr", "variant_id", "variant_en", "variant_fr", "currency", "price", "active",
            "alcohol", "barcode", "brand", "subcategory", "size"))
        stream("""
            SELECT i.venue_id, i.id, i.name_en, i.name_fr, i.category_id, k.name_en AS cat_en, k.name_fr AS cat_fr,
                   vr.id AS variant_id, vr.label_en, vr.label_fr, vr.price_cents, i.active, i.is_alcohol,
                   i.barcode, i.brand, i.subcategory, i.size_label, NULL::text AS currency
            ${menuWhere(ctx)}
            ORDER BY i.venue_id, k.sort_order NULLS LAST, i.category_id, i.name_en, i.id, vr.sort_order, vr.id""") { rs ->
            val v = rs.getString("venue_id")
            sink.row(ctx.store(v) + listOf(
                text(rs.getString("id")), text(rs.getString("name_en")), text(rs.getString("name_fr")),
                text(rs.getString("category_id")), text(rs.getString("cat_en")), text(rs.getString("cat_fr")),
                text(rs.getString("variant_id")), text(rs.getString("label_en")), text(rs.getString("label_fr")),
                ctx.currency(rs, v), money(rs.long("price_cents")), flag(rs.bool("active")), flag(rs.bool("is_alcohol")),
                text(rs.getString("barcode")), text(rs.getString("brand")), text(rs.getString("subcategory")),
                text(rs.getString("size_label")),
            ))
        }
    },
)

private fun staffWhere(ctx: ReportCtx) = "FROM store_staff s WHERE ${storesSql(ctx, "s")} AND NOT s.deleted"

/** Names and roles only: the cloud never holds a PIN or its hash, and none of the rest is exported. */
private val staff = Dataset(
    id = "staff",
    about = "Each store's staff as they are now: names and roles only (no PINs or other credentials).",
    dated = false,
    columns = STORE_COLUMNS + listOf(
        Column("name", "Staff member's name."),
        Column("role", "MANAGER or SERVER."),
        Column("active", "true = can sign in at the store."),
    ),
    count = { ctx -> countOf(staffWhere(ctx)) },
    write = { ctx, sink ->
        sink.header(listOf("store_id", "store_name", "name", "role", "active"))
        stream("SELECT s.venue_id, s.name, s.role, s.active ${staffWhere(ctx)} ORDER BY s.venue_id, s.name, s.id") { rs ->
            val v = rs.getString("venue_id")
            sink.row(ctx.store(v) + listOf(text(rs.getString("name")), text(rs.getString("role")), flag(rs.bool("active"))))
        }
    },
)

private const val ALL_STORES_ID = "ALL"

private val taxSummaryDataset = Dataset(
    id = "tax-summary",
    about = "Tax collected less tax refunded, by tax and authority: per store per business day, per store for the " +
        "range, and (several stores) all stores per currency. The totals are the portal tax report's figures.",
    columns = listOf(
        Column("period", "day = one store's business day; total = the whole range."),
        Column("date", "The business day (period = day); empty for totals."),
        Column("store_id", "The store, or ALL for the all-stores totals."),
        Column("store_name", "The store's name, or All stores."),
        CURRENCY,
        Column("tax_code", "The tax's code as the store stamped it (GST, QST, …)."),
        Column("tax_label_en", "Its label (English)."),
        Column("tax_label_fr", "Its label (French)."),
        Column("rate_percent", "Its rate, as charged (a rate change gets its own rows)."),
        Column("remit_to", "Who the store pays it to (e.g. a state or county), when the store says."),
        moneyCol("amount", "Tax on closed sales less tax on refunds."),
    ),
    count = { 0 }, // a few rows per store-day: never near a cap
    write = { ctx, sink ->
        sink.header(listOf("period", "date", "store_id", "store_name", "currency", "tax_code", "tax_label_en",
            "tax_label_fr", "rate_percent", "remit_to", "amount"))
        taxSummary(ctx).forEach { r ->
            val venue = r.venueId
            sink.row(listOf(
                text(if (r.date == null) "total" else "day"), day(r.date),
                text(venue ?: ALL_STORES_ID), text(venue?.let { ctx.storeName(it) } ?: "All stores"),
                text(r.tax.currency), text(r.tax.code), text(r.tax.labelEn), text(r.tax.labelFr),
                text(r.tax.ratePercent), text(r.tax.remitTo), money(r.tax.amountCents),
            ))
        }
    },
)

/** Every export, in the order the portal and the README list them. */
internal val DATASETS: List<Dataset> = listOf(
    sales, saleLines, refunds, tenders, shifts, cashMovements, menuItems, staff, taxSummaryDataset,
)

internal fun datasetOf(id: String): Dataset? = DATASETS.firstOrNull { it.id == id }
