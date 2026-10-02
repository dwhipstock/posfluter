package dev.dwhipstock.poscloud.menuprint

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// PORTED from the store: server/src/main/kotlin/dev/dwhipstock/pos/sdk/MenuSpecials.kt
// (moment, available, inForce, priceAt). The store decides what a line costs; a
// printed "Today's menu" must say the same, so the rules are copied verbatim:
// business days start at 4 a.m. in the store's own zone, a window is half-open
// and a time before 4 a.m. is late that night. Change both copies together
// (MenuPrintAiTest pins the 4 a.m. day, the zone and the half-open windows).

/** A special as the printed menu reads it (the cloud's canonical register value). */
data class PrintSpecial(
    val days: List<String>, val from: String?, val to: String?, val label: String?, val prices: Map<String, Long>,
)

object TodayRules {
    /** A business day runs from 4 a.m. to 4 a.m. the next calendar day. */
    const val DAY_STARTS_AT_HOUR = 4

    val DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    /** A moment of the venue's business: the business date, its weekday code and the minutes since its midnight (up to 28 h). */
    data class Moment(val date: LocalDate, val day: String, val minute: Int)

    fun moment(instant: Instant, zone: ZoneId): Moment {
        val local = instant.atZone(zone).toLocalDateTime()
        val date = local.minusHours(DAY_STARTS_AT_HOUR.toLong()).toLocalDate()
        val minute = ((local.toLocalDate().toEpochDay() - date.toEpochDay()) * 1440 + local.hour * 60 + local.minute).toInt()
        return Moment(date, code(date.dayOfWeek), minute)
    }

    fun code(d: DayOfWeek): String = DAYS[d.value - 1]

    /** Sold on [day] ([availableDays] empty = every day). */
    fun soldOn(availableDays: List<String>, day: String): Boolean = availableDays.isEmpty() || day in availableDays

    /** Whether [sp] is in force at [m]. */
    fun inForce(sp: PrintSpecial, m: Moment): Boolean {
        if (m.day !in sp.days) return false
        val from = sp.from?.let(::minutes) ?: return true
        val to = sp.to?.let(::minutes) ?: return true
        val dayStart = DAY_STARTS_AT_HOUR * 60
        // a time before the business day starts is late that night (01:00 = 25:00)
        val s = if (from < dayStart) from + 1440 else from
        // the end is the first `to` after the start: 22:00–02:00 runs past midnight
        var e = to
        while (e <= s) e += 1440
        return m.minute in s until e
    }

    /**
     * The specials of one size that run at some point of business day [day]
     * and actually lower its price (the store only ever rings a special that
     * is cheaper): cheapest first.
     */
    fun specialsOn(specials: List<PrintSpecial>, variantId: String, regularCents: Long, day: String): List<PrintSpecial> =
        specials.filter { day in it.days && (it.prices[variantId] ?: Long.MAX_VALUE) < regularCents }
            .sortedBy { it.prices.getValue(variantId) }

    /** The price of [variantId] at [m] (the store's priceAt): its special when one is in force and cheaper. */
    fun priceAt(specials: List<PrintSpecial>, variantId: String, regularCents: Long, m: Moment): Pair<Long, PrintSpecial?> {
        val sp = specials.filter { variantId in it.prices && inForce(it, m) }.minByOrNull { it.prices.getValue(variantId) }
            ?: return regularCents to null
        val p = sp.prices.getValue(variantId)
        return if (p < regularCents) p to sp else regularCents to null
    }

    fun minutes(hhmm: String): Int {
        val (h, m) = hhmm.split(':').map { it.toInt() }
        return h * 60 + m
    }
}
