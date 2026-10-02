package dev.dwhipstock.poscloud.menu

import dev.dwhipstock.poscloud.BadRequestException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Menu specials, the cloud's half (CONTRACT §10 "Specials"): the two item
 * registers `availableDays` and `specials`, always in the canonical JSON form
 * the store writes (server sdk/MenuSpecials.kt), so both sides compare the
 * same text. The cloud only stores and validates them; the store decides what
 * a line costs.
 *
 * Canonical form: days distinct, Monday first (all seven or none = null);
 * a special is `{days, from?, to?, label?, prices}` in that key order, times
 * "HH:mm", label whitespace-collapsed and at most 40 characters, price keys
 * sorted; at most 10 specials, duplicates dropped; no specials = null.
 */
object MenuSpecials {
    val DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    const val MAX_SPECIALS = 10
    const val MAX_LABEL = 40
    /** The store's MoneyLimits.MAX_UNIT_PRICE_CENTS: a special price can't exceed what a menu price may be. */
    const val MAX_UNIT_PRICE_CENTS = 9_999_999L

    data class Special(
        val days: List<String>, val from: String?, val to: String?,
        val label: String?, val prices: Map<String, Long>,
    )

    // --- validation (portal edits): BadRequestException with a clear code ---

    fun normalizeDays(days: Collection<String>): List<String> {
        val wanted = days.map { it.trim().lowercase().take(3) }.filter { it.isNotEmpty() }
        wanted.firstOrNull { it !in DAYS }?.let {
            throw BadRequestException("unknown day '$it' (use mon, tue, wed, thu, fri, sat, sun)", "bad_day")
        }
        return DAYS.filter { it in wanted }
    }

    /** Selling days as stored: every day (none or all seven) is empty. */
    fun sellingDays(days: Collection<String>): List<String> = normalizeDays(days).let { if (it.size == 7) emptyList() else it }

    fun normalizeTime(t: String?): String? {
        val raw = t?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(raw)
            ?: throw BadRequestException("time must be HH:mm, got '$raw'", "bad_time")
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        if (h !in 0..24 || mi !in 0..59 || (h == 24 && mi != 0)) throw BadRequestException("time must be HH:mm, got '$raw'", "bad_time")
        return "%02d:%02d".format(h % 24, mi)
    }

    /** [sizes]: the item's size ids at this store (null = don't check). */
    fun normalize(sp: Special, sizes: Set<String>? = null): Special {
        val days = normalizeDays(sp.days)
        if (days.isEmpty()) throw BadRequestException("a special needs at least one day", "special_no_days")
        val from = normalizeTime(sp.from); val to = normalizeTime(sp.to)
        if ((from == null) != (to == null))
            throw BadRequestException("a special's time window needs both a start and an end", "bad_time")
        if (from != null && from == to)
            throw BadRequestException("a special's time window can't start and end at the same time", "bad_time")
        val prices = sp.prices.filterKeys { it.isNotBlank() }.toSortedMap()
        if (prices.isEmpty()) throw BadRequestException("a special needs a price for at least one size", "special_no_price")
        prices.forEach { (vid, cents) ->
            if (cents < 0 || cents > MAX_UNIT_PRICE_CENTS)
                throw BadRequestException("a special price must be between 0 and $MAX_UNIT_PRICE_CENTS cents", "bad_price")
            if (sizes != null && vid !in sizes) throw SizeMissing(vid)
        }
        val label = sp.label?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_LABEL)?.takeIf { it.isNotEmpty() }
        return Special(days, from, to, label, LinkedHashMap(prices))
    }

    /** A special prices a size this store's item doesn't have (the store is skipped, not the whole edit). */
    class SizeMissing(val variantId: String) : RuntimeException("size $variantId is not one of this item's sizes")

    fun normalizeAll(list: List<Special>, sizes: Set<String>? = null): List<Special> {
        if (list.size > MAX_SPECIALS) throw BadRequestException("at most $MAX_SPECIALS specials per item", "too_many_specials")
        return list.map { normalize(it, sizes) }.distinct()
    }

    // --- the canonical register values ---

    fun toJson(sp: Special): JsonObject = buildJsonObject {
        put("days", JsonArray(sp.days.map(::JsonPrimitive)))
        sp.from?.let { put("from", it) }
        sp.to?.let { put("to", it) }
        sp.label?.let { put("label", it) }
        put("prices", JsonObject(sp.prices.toSortedMap().mapValues { JsonPrimitive(it.value) }))
    }

    fun specialsJson(list: List<Special>): JsonElement = if (list.isEmpty()) JsonNull else JsonArray(list.map(::toJson))

    fun daysJson(days: List<String>): JsonElement = if (days.isEmpty()) JsonNull else JsonArray(days.map(::JsonPrimitive))

    /** Lenient read of a wire / stored value: bad entries are dropped, never fatal (a sync must not wedge). */
    fun specialsOf(el: JsonElement?): List<Special> = (el as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        runCatching {
            normalize(Special(
                days = (o["days"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                from = (o["from"] as? JsonPrimitive)?.contentOrNull,
                to = (o["to"] as? JsonPrimitive)?.contentOrNull,
                label = (o["label"] as? JsonPrimitive)?.contentOrNull,
                prices = (o["prices"] as? JsonObject).orEmpty()
                    .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { k to it } }.toMap(),
            ))
        }.getOrNull()
    }.take(MAX_SPECIALS).distinct()

    fun daysOf(el: JsonElement?): List<String> =
        runCatching { sellingDays((el as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }) }
            .getOrDefault(emptyList())

    /** The canonical value of one of the two registers, from any JSON (a jsonb copy reorders keys). */
    fun canonical(field: String, v: JsonElement?): JsonElement = when (field) {
        "availableDays" -> daysJson(daysOf(v))
        "specials" -> specialsJson(specialsOf(v))
        else -> v ?: JsonNull
    }

    /** Stored text → register value. */
    fun parse(field: String, raw: String?): JsonElement =
        if (raw.isNullOrBlank()) JsonNull
        else canonical(field, runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) }.getOrNull())

    /** Register value → stored text (null = none). */
    fun text(v: JsonElement?): String? = v?.takeIf { it !is JsonNull }?.toString()

    /**
     * A feed entry as stored (jsonb: its keys reordered) with the two
     * registers back in canonical form, so the store's text compare holds.
     */
    fun canonicalFeedData(entity: String, data: JsonElement): JsonElement {
        if (entity != MenuFields.ITEM || data !is JsonObject) return data
        if ("availableDays" !in data && "specials" !in data) return data
        val out = LinkedHashMap(data)
        for (f in listOf("availableDays", "specials")) if (f in data) out[f] = canonical(f, data[f])
        return JsonObject(out)
    }
}
