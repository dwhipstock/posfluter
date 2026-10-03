package dev.dwhipstock.poscloud.menuai

// How the store's specials are doing, from its sales (cloud only, like
// MenuAiSales.kt): "how is my Tuesday burger special doing?" (an answer: the
// lift on special days), "end specials that aren't working" (proposes removing
// the ones with under 10% lift; the manager ticks and applies as usual) and
// "what sells during happy hour?" (an answer). Every number is computed here.

import dev.dwhipstock.poscloud.CloudTime
import dev.dwhipstock.poscloud.reports.lit
import dev.dwhipstock.poscloud.reports.rowsOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** One item's sales in one hour of one business day. */
internal class SalesSlot(val date: LocalDate, val hour: Int, val units: Long, val revenue: Long)

/** Units by item, business day and hour over [win] (inside a transaction; [only] = these items). */
internal fun SalesFacts.slots(win: SalesWindow, only: Set<String>? = null): Map<String, List<SalesSlot>> {
    if (only != null && only.isEmpty()) return emptyMap()
    val z = lit(zone.id)
    return rowsOf("""
        SELECT l.item_id, (c.closed_at AT TIME ZONE $z)::date, extract(hour FROM c.closed_at AT TIME ZONE $z)::int,
               coalesce(sum(l.qty), 0), coalesce(sum(l.line_total_cents), 0)
        FROM check_lines l JOIN checks c
          ON c.tenant_id = l.tenant_id AND c.venue_id = l.venue_id AND c.check_id = l.check_id
        WHERE c.tenant_id = ${lit(tenantId)} AND c.venue_id = ${lit(venueId)} AND c.status = 'CLOSED'
          AND c.closed_at >= ${lit(CloudTime.startOfDay(win.from, zone))}
          AND c.closed_at < ${lit(CloudTime.startOfDay(win.to.plusDays(1), zone))} AND l.item_id IS NOT NULL
          ${only?.let { ids -> "AND l.item_id IN (${ids.joinToString(",") { lit(it) }})" } ?: ""}
        GROUP BY 1, 2, 3""") { rs ->
        rs.getString(1) to SalesSlot(rs.getDate(2).toLocalDate(), rs.getInt(3), rs.getLong(4), rs.getLong(5))
    }.groupBy({ it.first }, { it.second })
}

internal class SalesInsights(
    private val facts: SalesFacts,
    private val items: Map<String, SalesItem>,
    private val lang: String,
) {
    companion object {
        /** A special with less lift than this is "not working". */
        const val WEAK_LIFT_PCT = 10
        /** Fewer special days or comparable other days than this: too little data. */
        const val MIN_DAYS = 3
        private val WEEKDAYS = listOf("mon", "tue", "wed", "thu", "fri")
        private val WEEKEND = listOf("sat", "sun")

        fun dayCode(d: LocalDate): String = MenuChangeSetParser.DAYS[d.dayOfWeek.value - 1]

        /** The hours whose [h:00, h+1:00) overlaps [from, to) (all 24 without a window; overnight wraps). */
        fun hours(from: String?, to: String?): Set<Int> {
            if (from == null || to == null) return (0..23).toSet()
            fun min(t: String) = t.substringBefore(':').toInt() * 60 + t.substringAfter(':').toInt()
            val f = min(from); val t = min(to)
            fun overlaps(h: Int, a: Int, b: Int) = h * 60 < b && (h + 1) * 60 > a
            return (0..23).filter { h -> if (f < t) overlaps(h, f, t) else overlaps(h, f, 24 * 60) || overlaps(h, 0, t) }.toSet()
        }

        /** The other days a special is compared with: the rest of its group (weekdays / weekend), else every other day. */
        fun baseline(days: List<String>): Pair<String, List<String>>? = when {
            WEEKDAYS.containsAll(days) && WEEKDAYS.size > days.size -> "weekdays" to WEEKDAYS.filter { it !in days }
            WEEKEND.containsAll(days) && WEEKEND.size > days.size -> "weekend" to WEEKEND.filter { it !in days }
            days.size < 7 -> "other_days" to MenuChangeSetParser.DAYS.filter { it !in days }
            else -> null
        }

        private fun avg(total: Long, days: Int): Double =
            if (days == 0) 0.0 else BigDecimal.valueOf(total).divide(BigDecimal.valueOf(days.toLong()), 1, RoundingMode.HALF_UP).toDouble()
    }

    private fun window(o: JsonObject, default: Int): SalesWindow {
        val days = (o["days"] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }?.takeIf { it in 14..90 } ?: default
        return facts.window(days)
    }

    private fun pool(o: JsonObject): List<SalesItem> {
        val ids = (o["among"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.flatMap { id -> if (id in items) listOf(id) else items.values.filter { it.categoryId == id }.map { it.id } }
        return ids?.distinct()?.map { items.getValue(it) } ?: items.values.toList()
    }

    private fun basis(rank: String, win: SalesWindow, n: Int, rows: List<AiSalesRow>, special: String? = null) = AiSalesBasis(
        rank, "units", n, win.from.toString(), win.to.toString(), store = facts.store, currency = facts.currency,
        rows = rows, special = special)

    /** One special's numbers over [win]: units on its days (inside its hours) vs comparable other days (the same hours). */
    private fun lift(i: SalesItem, sp: NewSpecial, win: SalesWindow, slots: List<SalesSlot>): AiSalesRow {
        val hours = hours(sp.from, sp.to)
        val dates = generateSequence(win.from) { it.plusDays(1) }.takeWhile { it <= win.to }.toList()
        val base = baseline(sp.days)
        val onDates = dates.filter { dayCode(it) in sp.days }.toSet()
        val offDates = base?.second?.let { other -> dates.filter { dayCode(it) in other }.toSet() }.orEmpty()
        val inHours = slots.filter { it.hour in hours }
        val on = inHours.filter { it.date in onDates }
        val off = inHours.filter { it.date in offDates }
        val onUnits = on.sumOf { it.units }
        val offUnits = off.sumOf { it.units }
        val onAvg = avg(onUnits, onDates.size)
        val offAvg = avg(offUnits, offDates.size)
        val enough = base != null && onDates.size >= MIN_DAYS && offDates.size >= MIN_DAYS && offUnits > 0 && onUnits + offUnits >= 10
        // the lift from the exact per-day rates, not the rounded averages
        val lift = if (!enough) null else {
            val a = onUnits.toDouble() / onDates.size
            val b = offUnits.toDouble() / offDates.size
            Math.round((a - b) / b * 100).toInt()
        }
        return AiSalesRow(i.id, i.name, i.category, onUnits, on.sumOf { it.revenue }, facts.lastSold[i.id]?.toString(),
            special = MenuChangeSetParser.describeSpecial(sp, lang), onAvg = onAvg, offAvg = offAvg,
            baseline = base?.first, liftPct = lift, enough = enough)
    }

    /**
     * "How is my Tuesday burger special doing?" ([end] false: an answer) and
     * "end specials that aren't working" ([end]: removing every special with
     * enough data and under [WEAK_LIFT_PCT] lift, as set_specials ops).
     */
    fun specials(o: JsonObject, end: Boolean): SalesPlan {
        val win = window(o, if (end) 28 else 56)
        val withSpecials = pool(o).filter { it.specials.isNotEmpty() }
        val notes = mutableListOf<AiSalesNote>()
        if (withSpecials.isEmpty()) {
            notes += AiSalesNote("no_specials")
            return SalesPlan(basis("specials", win, 0, emptyList()), notes, emptyList(), !end, emptySet(), emptySet())
        }
        val slots = facts.slots(win, withSpecials.map { it.id }.toSet())
        val rows = mutableListOf<AiSalesRow>()
        val ops = mutableListOf<JsonObject>()
        for (i in withSpecials) {
            val weak = mutableListOf<NewSpecial>()
            for (sp in i.specials) {
                val row = lift(i, sp, win, slots[i.id].orEmpty())
                rows += row
                if (row.enough == true && (row.liftPct ?: 0) < WEAK_LIFT_PCT) weak += sp
            }
            if (end && weak.isNotEmpty()) ops += buildJsonObject {
                put("op", "set_specials"); put("item", i.id)
                putJsonArray("specials") {
                    i.specials.filter { it !in weak }.forEach { sp -> addJsonObject {
                        putJsonArray("days") { sp.days.forEach { add(JsonPrimitive(it)) } }
                        sp.from?.let { put("from", it) }; sp.to?.let { put("to", it) }
                        put("label", sp.label ?: "")
                        putJsonArray("prices") { sp.prices.forEach { (v, p) -> addJsonObject { put("variant", v); put("priceMinor", p) } } }
                    } }
                }
            }
        }
        // the weakest first when ending them; the best first when asked how they are doing
        val sorted = rows.sortedWith(
            if (end) compareBy<AiSalesRow> { if (it.enough == true) 0 else 1 }.thenBy { it.liftPct ?: 0 }.thenBy { it.name.lowercase() }
            else compareBy<AiSalesRow> { if (it.enough == true) 0 else 1 }.thenByDescending { it.liftPct ?: 0 }.thenBy { it.name.lowercase() })
        notes += AiSalesNote("assumes_whole_period")
        if (end && ops.isEmpty()) notes += AiSalesNote("all_working", n = WEAK_LIFT_PCT)
        return SalesPlan(basis("specials", win, 0, sorted), notes, ops, !end,
            if (end) withSpecials.map { it.id }.toSet() else emptySet(), if (end) setOf("set_specials") else emptySet())
    }

    /** "What sells during happy hour?": the top items inside the store's happy-hour windows (its timed specials). */
    fun happyHour(o: JsonObject, n: Int): SalesPlan {
        val win = window(o, 30)
        val timed = items.values.flatMap { it.specials }.filter { it.from != null && it.to != null }
        val named = timed.filter { s -> s.label?.let { Regex("(?i)happy|hora feliz|heure joyeuse|5 à 7|5 a 7").containsMatchIn(it) } == true }
        val windows = (named.ifEmpty { timed }).map { it.copy(label = null, prices = emptyMap()) }.distinct()
        if (windows.isEmpty())
            return SalesPlan(basis("happy_hour", win, n, emptyList()), listOf(AiSalesNote("no_happy_hour")), emptyList(), true, emptySet(), emptySet())
        val slotSet = windows.flatMap { w -> w.days.flatMap { d -> hours(w.from, w.to).map { d to it } } }.toSet()
        val slots = facts.slots(win, pool(o).map { it.id }.toSet())
        val rows = pool(o).mapNotNull { i ->
            val all = slots[i.id].orEmpty()
            val inside = all.filter { (dayCode(it.date) to it.hour) in slotSet }
            val u = inside.sumOf { it.units }
            val total = all.sumOf { it.units }
            if (u <= 0) null else AiSalesRow(i.id, i.name, i.category, u, inside.sumOf { it.revenue }, facts.lastSold[i.id]?.toString(),
                sharePct = Math.round(u * 100.0 / total).toInt())
        }.sortedWith(compareByDescending<AiSalesRow> { it.units }.thenByDescending { it.revenueMinor }.thenBy { it.name.lowercase() }).take(n)
        val notes = if (rows.isEmpty()) listOf(AiSalesNote("no_sales")) else emptyList()
        return SalesPlan(basis("happy_hour", win, n, rows, windows.joinToString("; ") { MenuChangeSetParser.describeSpecial(it, lang) }),
            notes, emptyList(), true, emptySet(), emptySet())
    }
}

