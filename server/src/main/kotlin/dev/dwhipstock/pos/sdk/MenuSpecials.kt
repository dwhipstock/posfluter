package dev.dwhipstock.pos.sdk

import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import dev.dwhipstock.pos.sdk.i18n.MessageKey
import dev.dwhipstock.pos.sdk.i18n.Messages
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Menu specials, for any kind of store (CONTRACT §10, "Specials"):
 *
 * - a **day price**: a product is cheaper on some days of the week, maybe only
 *   in a time window ("burgers $9.95 on Tuesdays", "happy hour 4–6 pm, beers
 *   $5"), per size;
 * - **day availability**: a product is sold only on some days ("prime rib on
 *   Fridays and Saturdays"); on the other days it can't be rung.
 *
 * Days are the venue's BUSINESS days in its own zone: a business day starts at
 * [DAY_STARTS_AT_HOUR] (4 a.m.), so a sale at 1 a.m. on Saturday still belongs
 * to Friday night. Times are the venue's wall clock. A window is half-open
 * (`from` included, `to` not): happy hour 16:00–18:00 ends at 18:00 sharp.
 *
 * The price is decided when the line is added and kept on the line. The
 * special price is the line's price: promotions and taxes work on it like on
 * any other price. Pure functions, no database.
 */
object MenuSpecials {
    /** A business day runs from 4 a.m. to 4 a.m. the next calendar day. */
    const val DAY_STARTS_AT_HOUR = 4

    /** Day codes, Monday first (the canonical order). */
    val DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    const val MAX_SPECIALS = 10
    const val MAX_LABEL = 40

    /**
     * One special: [days] (codes, canonical order), an optional window
     * ([from]/[to], "HH:mm"; both or neither), the special unit price of each
     * size it covers ([prices], size id → cents) and an optional own name
     * ([label]); without one the name is made from the days ("Tuesday
     * special") or, with a window, "Happy hour", in the reader's language.
     */
    @Serializable
    data class Special(
        val days: List<String>,
        val from: String? = null,
        val to: String? = null,
        val prices: Map<String, Long>,
        val label: String? = null,
    )

    /** An item's schedule: [availableDays] empty = every day. */
    data class Schedule(val availableDays: List<String> = emptyList(), val specials: List<Special> = emptyList()) {
        val isEmpty: Boolean get() = availableDays.isEmpty() && specials.isEmpty()
    }

    /** What a line carries when it was rung at a special price (shown on the check and the receipt). */
    @Serializable
    data class Tag(val days: List<String>, val from: String? = null, val to: String? = null, val label: String? = null)

    fun Special.tag() = Tag(days, from, to, label)

    /** A moment of the venue's business: the business date, its weekday code and the minutes since its midnight (up to 28 h). */
    data class Moment(val date: LocalDate, val day: String, val minute: Int)

    fun moment(instant: Instant, zone: ZoneId): Moment {
        val local = instant.atZone(zone).toLocalDateTime()
        val date = local.minusHours(DAY_STARTS_AT_HOUR.toLong()).toLocalDate()
        val minute = ((local.toLocalDate().toEpochDay() - date.toEpochDay()) * 1440 + local.hour * 60 + local.minute).toInt()
        return Moment(date, code(date.dayOfWeek), minute)
    }

    fun code(d: DayOfWeek): String = DAYS[d.value - 1]

    // --- evaluation ---

    /** Whether the item can be sold at [m]. */
    fun available(s: Schedule, m: Moment): Boolean = s.availableDays.isEmpty() || m.day in s.availableDays

    /** Whether [sp] is in force at [m]. */
    fun inForce(sp: Special, m: Moment): Boolean {
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

    /** The special of [variantId] in force at [m] (the cheapest when several are), or null. */
    fun specialFor(s: Schedule, variantId: String, m: Moment): Special? =
        s.specials.filter { variantId in it.prices && inForce(it, m) }.minByOrNull { it.prices.getValue(variantId) }

    /** The unit price of [variantId] at [m]: its special price when one is in force and cheaper, else [regularCents]. */
    fun priceAt(s: Schedule, variantId: String, regularCents: Long, m: Moment): Pair<Long, Special?> {
        val sp = specialFor(s, variantId, m) ?: return regularCents to null
        val p = sp.prices.getValue(variantId)
        return if (p < regularCents) p to sp else regularCents to null
    }

    // --- canonical form (both sides of menu sync compare JSON text) ---

    /** Days as given → codes, distinct, Monday first. Unknown codes are an error. */
    fun normalizeDays(days: Collection<String>): List<String> {
        val wanted = days.map { it.trim().lowercase().take(3) }.filter { it.isNotEmpty() }
        wanted.forEach { require(it in DAYS) { "unknown day '$it' (use mon, tue, wed, thu, fri, sat, sun)" } }
        return DAYS.filter { it in wanted }
    }

    /** "9:5" → "09:05"; null/blank → null. */
    fun normalizeTime(t: String?): String? {
        val raw = t?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(raw) ?: throw IllegalArgumentException("time must be HH:mm, got '$raw'")
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        require(h in 0..24 && mi in 0..59 && (h < 24 || mi == 0)) { "time must be HH:mm, got '$raw'" }
        return "%02d:%02d".format(h % 24, mi)
    }

    /** A special, validated and in its canonical form. [variantIds]: the item's sizes (null = don't check). */
    fun normalize(sp: Special, variantIds: Set<String>? = null): Special {
        val days = normalizeDays(sp.days)
        require(days.isNotEmpty()) { "a special needs at least one day" }
        val from = normalizeTime(sp.from); val to = normalizeTime(sp.to)
        require((from == null) == (to == null)) { "a special's time window needs both a start and an end" }
        require(from == null || from != to) { "a special's time window can't start and end at the same time" }
        val prices = sp.prices.filterKeys { it.isNotBlank() }.toSortedMap()
        require(prices.isNotEmpty()) { "a special needs a price for at least one size" }
        prices.forEach { (vid, cents) ->
            require(cents >= 0) { "a special price must be >= 0" }
            MoneyLimits.requireUnitPrice(cents)
            if (variantIds != null) require(vid in variantIds) { "size $vid is not one of this item's sizes" }
        }
        val label = sp.label?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_LABEL)?.takeIf { it.isNotEmpty() }
        return Special(days, from, to, LinkedHashMap(prices), label)
    }

    fun normalizeAll(list: List<Special>, variantIds: Set<String>? = null): List<Special> {
        require(list.size <= MAX_SPECIALS) { "at most $MAX_SPECIALS specials per item" }
        return list.map { normalize(it, variantIds) }.distinct()
    }

    fun toJson(sp: Special): JsonObject = buildJsonObject {
        put("days", JsonArray(sp.days.map(::JsonPrimitive)))
        sp.from?.let { put("from", it) }
        sp.to?.let { put("to", it) }
        sp.label?.let { put("label", it) }
        put("prices", JsonObject(sp.prices.toSortedMap().mapValues { JsonPrimitive(it.value) }))
    }

    /** The wire / register value of an item's specials: null when there are none. */
    fun specialsJson(list: List<Special>): JsonElement =
        if (list.isEmpty()) JsonNull else JsonArray(list.map(::toJson))

    /** The wire / register value of an item's days: null = every day. */
    fun daysJson(days: List<String>): JsonElement =
        if (days.isEmpty()) JsonNull else JsonArray(days.map(::JsonPrimitive))

    /** Lenient read of a wire value (bad entries are dropped, not fatal: a sync must not wedge). */
    fun specialsOf(el: JsonElement?): List<Special> = (el as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        runCatching {
            normalize(Special(
                days = (o["days"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                from = (o["from"] as? JsonPrimitive)?.contentOrNull,
                to = (o["to"] as? JsonPrimitive)?.contentOrNull,
                prices = (o["prices"] as? JsonObject).orEmpty()
                    .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { k to it } }.toMap(),
                label = (o["label"] as? JsonPrimitive)?.contentOrNull,
            ))
        }.getOrNull()
    }.take(MAX_SPECIALS).distinct()

    fun daysOf(el: JsonElement?): List<String> =
        runCatching { normalizeDays((el as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }) }
            .getOrDefault(emptyList())

    fun minutes(hhmm: String): Int {
        val (h, m) = hhmm.split(':').map { it.toInt() }
        return h * 60 + m
    }

    // --- words (receipts and other server-rendered text) ---

    private val DAY_KEYS = listOf(
        MessageKey.DAY_MON, MessageKey.DAY_TUE, MessageKey.DAY_WED, MessageKey.DAY_THU,
        MessageKey.DAY_FRI, MessageKey.DAY_SAT, MessageKey.DAY_SUN,
    )

    fun dayName(code: String, locale: LocaleCode): String =
        DAYS.indexOf(code).takeIf { it >= 0 }?.let { Messages.get(DAY_KEYS[it], locale) } ?: code

    /** "Friday & Saturday", "Mon–Fri" style: every listed day, joined in the reader's language. */
    fun daysText(days: List<String>, locale: LocaleCode): String {
        if (days.size == 7) return Messages.get(MessageKey.SPECIAL_EVERY_DAY, locale)
        val names = days.map { dayName(it, locale) }
        return when (names.size) {
            0 -> ""
            1 -> names[0]
            else -> Messages.get(MessageKey.SPECIAL_DAYS_AND, locale, names.dropLast(1).joinToString(", "), names.last())
        }
    }

    /** A special's name: its own label, else "Happy hour" (a time window) or "Tuesday special". */
    fun label(tag: Tag, locale: LocaleCode): String = tag.label ?: when {
        tag.from != null -> Messages.get(MessageKey.SPECIAL_HAPPY_HOUR, locale)
        tag.days.size == 1 -> Messages.get(MessageKey.SPECIAL_DAY, locale, dayName(tag.days[0], locale))
        else -> Messages.get(MessageKey.SPECIAL_GENERIC, locale)
    }
}
