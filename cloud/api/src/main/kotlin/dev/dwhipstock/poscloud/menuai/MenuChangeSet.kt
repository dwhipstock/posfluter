package dev.dwhipstock.poscloud.menuai

// PORTED verbatim from the store: server/src/main/kotlin/dev/dwhipstock/pos/aimenu/MenuChangeSet.kt
// (the strict reader of the model's change set). Keep both copies in step; see AiGuard.kt here for why
// it is a copy and not a shared module. Ids are the cloud catalog's (the same ids the store uses).

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** One size of a new item. */
data class NewVariant(val labelEn: String, val labelFr: String, val priceMinor: Long)

/**
 * One validated change the model proposed. Existing things are referred to by
 * their store ids (checked against the live menu); a new category by the
 * model's `ref` ("new:starters"), which new items and reorders may use.
 */
sealed class MenuOp {
    data class AddCategory(val ref: String, val nameEn: String, val nameFr: String) : MenuOp()
    data class AddItem(
        val category: String, val nameEn: String, val nameFr: String,
        val descriptionEn: String, val descriptionFr: String,
        val isAlcohol: Boolean, val variants: List<NewVariant>,
    ) : MenuOp()
    data class UpdateItem(
        val itemId: String,
        val nameEn: String? = null, val nameFr: String? = null,
        val descriptionEn: String? = null, val descriptionFr: String? = null,
        val category: String? = null, val active: Boolean? = null,
        /** variant id → new price (minor units). */
        val prices: Map<String, Long> = emptyMap(),
    ) : MenuOp()
    data class RemoveItem(val itemId: String) : MenuOp()
    data class RenameCategory(val categoryId: String, val nameEn: String?, val nameFr: String?) : MenuOp()
    data class ReorderCategories(val order: List<String>) : MenuOp()
    /** "Translate menu": an item's or category's name in one of the store's extra languages (es, de). */
    data class SetName(val entity: String, val id: String, val lang: String, val name: String) : MenuOp()
    /** Menu specials: the only days the item is sold ("fri", "sat"); empty = every day again. */
    data class SetDays(val itemId: String, val days: List<String>) : MenuOp()
    /** Menu specials: the item's whole list of day prices after the change (empty = none). */
    data class SetSpecials(val itemId: String, val specials: List<NewSpecial>) : MenuOp()
}

/**
 * One day price (CONTRACT §10 "Specials"): days (mon..sun, Monday first), an
 * optional "HH:mm" window (both or neither), an optional own name, and the
 * special price of each size it covers (size id → minor units).
 */
data class NewSpecial(
    val days: List<String>, val from: String? = null, val to: String? = null,
    val label: String? = null, val prices: Map<String, Long>,
)

/** What the model may refer to: the live menu when the proposal was made. */
class MenuFacts(
    val categoryIds: List<String>,
    /** item id → its live variant ids. */
    val itemVariants: Map<String, List<String>>,
    /** The store's languages beyond fr / en, which set_name may fill. */
    val extraLangs: Set<String> = emptySet(),
    /** variant id → its live price: a size may stay at 0 only if it already is. */
    val variantPrices: Map<String, Long> = emptyMap(),
    /** The highest price the AI may set (1000 in the store currency). */
    val maxPriceMinor: Long = 100_000,
    /** item id → today's (nameEn, nameFr) — for the rename language-bleed guard. */
    val itemNames: Map<String, Pair<String, String>> = emptyMap(),
    /** category id → today's (nameEn, nameFr) — for the rename language-bleed guard. */
    val categoryNames: Map<String, Pair<String, String>> = emptyMap(),
    /** "item:<id>" / "category:<id>" → lang → today's extra-language (es/de) name. */
    val extraNames: Map<String, Map<String, String>> = emptyMap(),
    /** The acting user's own UI language: what an unspecified-language rename means. */
    val requestLang: String = "en",
    /** item id → the days it is sold now (empty / absent = every day). */
    val itemDays: Map<String, List<String>> = emptyMap(),
    /** item id → its specials now. */
    val itemSpecials: Map<String, List<NewSpecial>> = emptyMap(),
)

class ParsedChangeSet(
    val summary: String, val ops: List<MenuOp>, val rejected: List<String>,
    /** The model said this is not a menu request (its `refusal` field). */
    val refused: Boolean = false,
    /** Ops dropped because a name or description was offensive ([AiGuard.BLOCKED_WORD]). */
    val offensive: Int = 0,
)

/**
 * What one AI task may propose. Whatever the model (or text in a photo, or a
 * name stored in the menu) says, an op outside the scope is rejected.
 */
enum class MenuScope(val ops: Set<String>, val pricesOnlyUpdates: Boolean = false) {
    /** Menu chat (typed or spoken): everything. */
    CHAT(MenuChangeSetParser.KNOWN_OPS),
    /** "Translate menu": names in the store's extra languages, nothing else. */
    TRANSLATE(setOf("set_name")),
    /** "Menu from photos": new categories and items, and new prices of existing items; never a removal or rename. */
    PHOTOS(setOf("add_category", "add_item", "update_item"), pricesOnlyUpdates = true),
}

/**
 * Strict reader of the model's reply. The reply must be one JSON object
 * `{summary?, ops: [...]}` (a ```json fence around it is tolerated); anything
 * else is a [MenuAiReplyException]. Each op is checked on its own: an unknown
 * op, an unknown id, a bad price or a missing name drops that op into
 * [ParsedChangeSet.rejected] with the reason; the rest stay.
 */
object MenuChangeSetParser {
    const val MAX_OPS = 300
    /** New items and categories per request. */
    const val MAX_ADDS = 150
    /** Removals per request: more and every removal is rejected (no "delete everything"). */
    const val MAX_REMOVES = 25
    const val MAX_ITEM_NAME = 80
    const val MAX_CATEGORY_NAME = 40
    const val MAX_DESCRIPTION = 300
    private const val MAX_LABEL = 30
    private const val MAX_VARIANTS = 8

    val KNOWN_OPS = setOf("add_category", "add_item", "update_item", "remove_item", "rename_category",
        "reorder_categories", "set_name", "set_days", "set_specials")

    private val json = Json { isLenient = false }

    fun parse(reply: String, facts: MenuFacts, scope: MenuScope = MenuScope.CHAT): ParsedChangeSet {
        val text = reply.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw MenuAiReplyException("the AI reply was not a JSON object")
        val rawOps = root["ops"] as? JsonArray ?: throw MenuAiReplyException("the AI reply has no \"ops\" list")
        if (rawOps.size > MAX_OPS) throw MenuAiReplyException(
            "the AI proposed too many changes (${rawOps.size})", tooMany = true)
        val refused = (root["refusal"] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.isNotBlank() } == true
        if (refused) return ParsedChangeSet("", emptyList(), emptyList(), refused = true)
        // the model's own words reach the screen only when they are plain menu text
        val summary = root["summary"].s()?.trim()?.take(200)?.takeIf { AiGuard.checkText(it) == null } ?: ""
        val removes = rawOps.count { (it as? JsonObject)?.get("op").s() == "remove_item" }
        var adds = 0

        val newRefs = mutableSetOf<String>()
        val ops = mutableListOf<MenuOp>()
        val rejected = mutableListOf<String>()
        var offensive = 0
        rawOps.forEachIndexed { i, el ->
            val o = el as? JsonObject
            if (o == null) { rejected += "#${i + 1}: not an object"; return@forEachIndexed }
            try {
                val kind = o["op"].s()
                // only the server's own op names reach the screen, never the model's text
                require(kind in KNOWN_OPS) { "unknown op" }
                require(kind in scope.ops) { "not allowed for this task" }
                if (kind == "remove_item") require(removes <= MAX_REMOVES) {
                    "too many removals at once ($removes); at most $MAX_REMOVES per request"
                }
                if (kind == "add_item" || kind == "add_category") require(++adds <= MAX_ADDS) {
                    "too many new items at once; at most $MAX_ADDS per request"
                }
                var op = one(o, facts, newRefs)
                if (scope.pricesOnlyUpdates && op is MenuOp.UpdateItem) {
                    require(op.prices.isNotEmpty()) { "only new prices are allowed for this task" }
                    op = MenuOp.UpdateItem(op.itemId, prices = op.prices)
                }
                if (op is MenuOp.AddCategory) newRefs += op.ref
                ops += op
            } catch (e: IllegalArgumentException) {
                if (e.message?.endsWith(AiGuard.BLOCKED_WORD) == true) offensive++
                val kind = o["op"].s()?.takeIf { it in KNOWN_OPS } ?: "?"
                rejected += AiGuard.quote("#${i + 1} ($kind): ${e.message}", 160)
            }
        }
        return ParsedChangeSet(summary, guardExtraLanguageBleed(ops, facts, rejected), rejected, offensive = offensive)
    }

    /**
     * Cross-language bleed guard, part 2 (QA-3): a rename in one core language
     * ([one]'s nameEn/nameFr guard) must not leak into the store's OTHER
     * languages (es/de) either — a set_name proposed alongside a rename that
     * just copies the new text into an extra language, overwriting a real,
     * different existing translation, is dropped (kept as it was) rather than
     * applied. A first-time translation (no existing extra name yet, the
     * "translate menu" flow) is never touched by this.
     */
    private fun guardExtraLanguageBleed(ops: List<MenuOp>, facts: MenuFacts, rejected: MutableList<String>): List<MenuOp> {
        if (ops.none { it is MenuOp.SetName }) return ops
        fun newNames(entity: String, id: String): Pair<String?, String?> {
            val old = if (entity == "item") facts.itemNames[id] else facts.categoryNames[id]
            val update = ops.firstOrNull {
                (entity == "item" && it is MenuOp.UpdateItem && it.itemId == id) ||
                    (entity == "category" && it is MenuOp.RenameCategory && it.categoryId == id)
            }
            val newEn = (update as? MenuOp.UpdateItem)?.nameEn ?: (update as? MenuOp.RenameCategory)?.nameEn ?: old?.first
            val newFr = (update as? MenuOp.UpdateItem)?.nameFr ?: (update as? MenuOp.RenameCategory)?.nameFr ?: old?.second
            return newEn to newFr
        }
        return ops.filter { op ->
            if (op !is MenuOp.SetName) return@filter true
            val old = if (op.entity == "item") facts.itemNames[op.id] else facts.categoryNames[op.id]
            val oldExtra = facts.extraNames["${op.entity}:${op.id}"]?.get(op.lang)
            // nothing existed before (a translate proposal): never a bleed, always keep
            if (old == null || oldExtra == null) return@filter true
            val (newEn, newFr) = newNames(op.entity, op.id)
            val copiedFromEn = op.name == newEn && oldExtra != old.first
            val copiedFromFr = op.name == newFr && oldExtra != old.second
            if (copiedFromEn || copiedFromFr) {
                rejected += "set_name ${op.entity} ${op.id} (${op.lang}): looks like a copy of the rename, not a translation"
                false
            } else true
        }
    }

    /**
     * Cross-language bleed guard, part 1 (QA-3): "rename X to Y" must change
     * only the language the manager's request used. If the model proposed the
     * SAME text for nameEn and nameFr, but the item/category actually had two
     * different names before, only one language really changed — the other is
     * a copy, not a translation. Keep the change in the manager's own request
     * language ([MenuFacts.requestLang]) and drop the other back to unchanged
     * (null = no change), unless the two names were already the same word.
     */
    private fun guardNameBleed(
        nameEn: String?, nameFr: String?, old: Pair<String, String>?, requestLang: String,
    ): Pair<String?, String?> {
        if (nameEn == null || nameFr == null || nameEn != nameFr) return nameEn to nameFr
        val (oldEn, oldFr) = old ?: return nameEn to nameFr
        if (oldEn == oldFr) return nameEn to nameFr // already one word in both languages
        return if (requestLang == "fr") null to nameFr else nameEn to null
    }

    private fun one(o: JsonObject, facts: MenuFacts, newRefs: Set<String>): MenuOp {
        fun category(raw: String?): String {
            require(!raw.isNullOrBlank()) { "category missing" }
            require(raw in facts.categoryIds || raw in newRefs) { "unknown category" }
            return raw
        }
        fun item(): String {
            val id = o["item"].s()
            require(!id.isNullOrBlank()) { "item missing" }
            require(id in facts.itemVariants) { "unknown item" }
            return id
        }
        fun name(key: String, max: Int): String? = o[key].s()?.trim()?.also {
            require(it.length <= max) { "$key longer than $max characters" }
            AiGuard.checkText(it)?.let { why -> throw IllegalArgumentException("$key: $why") }
        }
        fun text(key: String, max: Int): String? = o[key].s()?.trim()?.take(max)?.also {
            AiGuard.checkText(it)?.let { why -> throw IllegalArgumentException("$key: $why") }
        }
        return when (val kind = o["op"].s()) {
            "add_category" -> {
                val ref = o["ref"].s()?.trim()
                require(!ref.isNullOrBlank() && ref.startsWith("new:")) { "ref must look like \"new:...\"" }
                require(ref !in newRefs) { "duplicate ref" }
                val en = name("nameEn", MAX_CATEGORY_NAME).orEmpty()
                val fr = name("nameFr", MAX_CATEGORY_NAME).orEmpty()
                require(en.isNotEmpty() || fr.isNotEmpty()) { "a name is required" }
                MenuOp.AddCategory(ref, en, fr)
            }
            "add_item" -> {
                val en = name("nameEn", MAX_ITEM_NAME).orEmpty()
                val fr = name("nameFr", MAX_ITEM_NAME).orEmpty()
                require(en.isNotEmpty() || fr.isNotEmpty()) { "a name is required" }
                val variants = (o["variants"] as? JsonArray)?.map { v ->
                    val vo = v as? JsonObject ?: throw IllegalArgumentException("a variant is not an object")
                    val labels = listOf("labelEn", "labelFr").map { k ->
                        vo[k].s()?.trim()?.take(MAX_LABEL).orEmpty().also {
                            AiGuard.checkText(it)?.let { why -> throw IllegalArgumentException("$k: $why") }
                        }
                    }
                    NewVariant(labels[0], labels[1], price(vo["priceMinor"], facts))
                } ?: o["priceMinor"]?.let { listOf(NewVariant("", "", price(it, facts))) }
                require(!variants.isNullOrEmpty()) { "a price is required" }
                require(variants.size <= MAX_VARIANTS) { "too many sizes" }
                MenuOp.AddItem(
                    category = category(o["category"].s()), nameEn = en, nameFr = fr,
                    descriptionEn = text("descriptionEn", MAX_DESCRIPTION).orEmpty(),
                    descriptionFr = text("descriptionFr", MAX_DESCRIPTION).orEmpty(),
                    isAlcohol = (o["isAlcohol"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    variants = variants,
                )
            }
            "update_item" -> {
                val id = item()
                val live = facts.itemVariants.getValue(id)
                val prices = linkedMapOf<String, Long>()
                (o["prices"] as? JsonArray)?.forEach { p ->
                    val po = p as? JsonObject ?: throw IllegalArgumentException("a price is not an object")
                    val variant = po["variant"].s()
                    require(variant != null && variant in live) { "unknown size of $id" }
                    prices[variant] = price(po["priceMinor"], facts, facts.variantPrices[variant] == 0L)
                }
                o["priceMinor"]?.takeUnless { it is JsonNull }?.let {
                    require(live.size == 1) { "$id has several sizes; give a price per size" }
                    prices[live.single()] = price(it, facts, facts.variantPrices[live.single()] == 0L)
                }
                val (guardedEn, guardedFr) = guardNameBleed(
                    name("nameEn", MAX_ITEM_NAME)?.takeIf { it.isNotEmpty() },
                    name("nameFr", MAX_ITEM_NAME)?.takeIf { it.isNotEmpty() },
                    facts.itemNames[id], facts.requestLang,
                )
                val op = MenuOp.UpdateItem(
                    itemId = id,
                    nameEn = guardedEn,
                    nameFr = guardedFr,
                    descriptionEn = text("descriptionEn", MAX_DESCRIPTION),
                    descriptionFr = text("descriptionFr", MAX_DESCRIPTION),
                    category = o["category"].s()?.let { category(it) },
                    active = (o["active"] as? JsonPrimitive)?.booleanOrNull,
                    prices = prices,
                )
                require(op != MenuOp.UpdateItem(id)) { "nothing to change" }
                op
            }
            "remove_item" -> MenuOp.RemoveItem(item())
            "rename_category" -> {
                val id = o["category"].s()
                require(id != null && id in facts.categoryIds) { "unknown category" }
                val (en, fr) = guardNameBleed(
                    name("nameEn", MAX_CATEGORY_NAME)?.takeIf { it.isNotEmpty() },
                    name("nameFr", MAX_CATEGORY_NAME)?.takeIf { it.isNotEmpty() },
                    facts.categoryNames[id], facts.requestLang,
                )
                require(en != null || fr != null) { "a new name is required" }
                MenuOp.RenameCategory(id, en, fr)
            }
            "set_name" -> {
                val entity = o["entity"].s()
                val id = o["id"].s()
                when (entity) {
                    "item" -> require(id != null && id in facts.itemVariants) { "unknown item" }
                    "category" -> require(id != null && id in facts.categoryIds) { "unknown category" }
                    else -> throw IllegalArgumentException("entity must be item or category")
                }
                val lang = o["lang"].s()?.trim()?.lowercase()
                require(lang != null && lang in facts.extraLangs) { "that language is not one of ${facts.extraLangs}" }
                val text = name("name", MAX_ITEM_NAME)
                require(!text.isNullOrEmpty()) { "a name is required" }
                MenuOp.SetName(entity, id!!, lang, text)
            }
            "set_days" -> {
                val id = item()
                val d = days(o["days"], allowEmpty = true)
                require(d != facts.itemDays[id].orEmpty()) { "nothing to change" }
                MenuOp.SetDays(id, d)
            }
            "set_specials" -> {
                val id = item()
                val arr = o["specials"] as? JsonArray ?: throw IllegalArgumentException("specials must be a list")
                require(arr.size <= MAX_SPECIALS) { "at most $MAX_SPECIALS specials per item" }
                val list = arr.map { special(it, id, facts) }.distinct()
                require(list != facts.itemSpecials[id].orEmpty()) { "nothing to change" }
                MenuOp.SetSpecials(id, list)
            }
            "reorder_categories" -> {
                val order = (o["order"] as? JsonArray)?.map { it.s() ?: "" }
                require(!order.isNullOrEmpty()) { "order missing" }
                require(order.toSet().size == order.size) { "a category appears twice" }
                order.forEach { category(it) }
                MenuOp.ReorderCategories(order)
            }
            else -> throw IllegalArgumentException("unknown op")
        }
    }

    /** 0 < price <= [MenuFacts.maxPriceMinor]; 0 only for a size that is already free ([zeroOk]). */
    private fun price(el: JsonElement?, facts: MenuFacts, zeroOk: Boolean = false): Long {
        val p = el as? JsonPrimitive
        require(p != null && !p.isString) { "priceMinor must be a whole number" }
        val v = p.longOrNull ?: throw IllegalArgumentException("priceMinor must be a whole number (minor units)")
        require(v >= 0 && v <= facts.maxPriceMinor) { "price out of range" }
        require(v > 0 || zeroOk) { "a price of 0 is not allowed" }
        return v
    }

    // --- menu specials (CONTRACT §10 "Specials"; the store's sdk/MenuSpecials.kt) ---

    /** Day codes, Monday first. */
    val DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    const val MAX_SPECIALS = 10
    private const val MAX_SPECIAL_LABEL = 40

    /**
     * The specials ops and rules: appended word for word to both assistants'
     * prompts (the store's and the portal's), so the drift test that compares
     * this object covers them.
     */
    val SPECIALS_PROMPT = """
Menu specials (two more ops):
{"op":"set_days","item":"<item id>","days":["fri","sat"]}  (the only days the item is sold; [] = every day again)
{"op":"set_specials","item":"<item id>","specials":[{"days":["tue"],"from":"16:00","to":"18:00","label":"","prices":[{"variant":"<variant id>","priceMinor":995}]}]}
Specials rules:
- Days are always the codes mon tue wed thu fri sat sun, in any language of the request. "Weekdays" = mon to fri, "weekends" = sat and sun.
- "<item> only on Fridays and Saturdays", "only sold on Sundays" = set_days for that item. "Every day again" = set_days with [].
- A cheaper price on some days or hours ("burgers 9.95 on Tuesdays", "happy hour 3-6 beers 5") = set_specials, one op per item concerned: every item that IS what was named (every burger; every beer), judged by its name, not by its category (a sandwich in "Burgers & Sandwiches" is not a burger). Never change the menu price itself (update_item prices) for a special.
- A special price is below the item's menu price. Give it for each size it applies to: a "5 dollar beer" is the smaller size (the pint, glass or can), not a pitcher, unless the manager says otherwise.
- set_specials lists ALL of the item's specials after the change: keep the ones the current menu shows under "specials" unless asked to remove them; [] removes every special.
- "from" and "to" are 24-hour "HH:mm" on the store's clock (3-6 pm = "15:00" to "18:00"); leave both out for the whole day. A happy hour with no days said runs every day (all seven codes). "label" is the special's own short name only when the manager's request says it ("happy hour", "Taco Tuesday"), else "": never invent one.
""".trim()

    private val SHORT_DAYS = mapOf(
        "en" to listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
        "fr" to listOf("lun", "mar", "mer", "jeu", "ven", "sam", "dim"),
        "es" to listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom"),
        "de" to listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"),
        "af" to listOf("Ma", "Di", "Wo", "Do", "Vr", "Sa", "So"),
    )
    private val EVERY_DAY = mapOf("en" to "Every day", "fr" to "Tous les jours", "es" to "Todos los días", "de" to "Jeden Tag", "af" to "Elke dag")
    private val AND = mapOf("en" to " & ", "fr" to " et ", "es" to " y ", "de" to " & ", "af" to " en ")
    private val ONLY = mapOf("en" to "Only ", "fr" to "Seulement ", "es" to "Solo ", "de" to "Nur ", "af" to "Net ")
    private val NONE = mapOf("en" to "No specials", "fr" to "Aucun spécial", "es" to "Sin especiales", "de" to "Keine Angebote", "af" to "Geen spesiale")

    private fun l(lang: String) = lang.lowercase().take(2).takeIf { it in SHORT_DAYS } ?: "en"

    /** "Mon–Fri", "Fri & Sat", "Every day" in [lang]. */
    fun describeDays(days: List<String>, lang: String): String {
        val k = l(lang)
        val idx = days.map { DAYS.indexOf(it) }.filter { it >= 0 }.distinct().sorted()
        if (idx.isEmpty() || idx.size == 7) return EVERY_DAY.getValue(k)
        val names = SHORT_DAYS.getValue(k)
        if (idx.size >= 3 && idx.last() - idx.first() == idx.size - 1) return names[idx.first()] + "–" + names[idx.last()]
        val n = idx.map { names[it] }
        return if (n.size == 1) n[0] else n.dropLast(1).joinToString(", ") + AND.getValue(k) + n.last()
    }

    /** Where an item is sold: "Every day" or "Only Fri & Sat". */
    fun describeAvailability(days: List<String>, lang: String): String =
        if (days.isEmpty() || days.size == 7) EVERY_DAY.getValue(l(lang)) else ONLY.getValue(l(lang)) + describeDays(days, lang)

    /** "Happy hour · Mon–Fri 16:00–18:00", "Tue". */
    fun describeSpecial(s: NewSpecial, lang: String): String =
        listOfNotNull(s.label, describeDays(s.days, lang) + (s.from?.let { " $it–${s.to}" } ?: "")).joinToString(" · ")

    /** A whole list, for the before / after of a change: "No specials" when empty. */
    fun describeSpecials(list: List<NewSpecial>, lang: String): String =
        if (list.isEmpty()) NONE.getValue(l(lang)) else list.joinToString("; ") { describeSpecial(it, lang) }

    /** The synced (canonical) form of an item's days, read leniently: null / bad = every day. */
    fun daysOf(el: JsonElement?): List<String> =
        runCatching { days(el, allowEmpty = true) }.getOrDefault(emptyList())

    /** The synced (canonical) form of an item's specials (`prices` an object), read leniently. */
    fun specialsOf(el: JsonElement?): List<NewSpecial> = (el as? JsonArray).orEmpty().mapNotNull { e ->
        val so = e as? JsonObject ?: return@mapNotNull null
        runCatching {
            val from = time(so["from"]); val to = time(so["to"])
            val prices = (so["prices"] as? JsonObject).orEmpty()
                .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { k to it } }.toMap().toSortedMap()
            if (prices.isEmpty() || (from == null) != (to == null)) null
            else NewSpecial(days(so["days"], allowEmpty = false), from, to, so["label"].s()?.trim()?.takeIf { it.isNotEmpty() }, LinkedHashMap(prices))
        }.getOrNull()
    }

    private fun days(el: JsonElement?, allowEmpty: Boolean): List<String> {
        if (el == null || el is JsonNull) { require(allowEmpty) { "at least one day is required" }; return emptyList() }
        val arr = el as? JsonArray ?: throw IllegalArgumentException("days must be a list like [\"fri\",\"sat\"]")
        val wanted = arr.map { d -> d.s()?.trim()?.lowercase()?.take(3).orEmpty() }
        wanted.forEach { require(it in DAYS) { "unknown day" } }
        val out = DAYS.filter { it in wanted }
        require(allowEmpty || out.isNotEmpty()) { "at least one day is required" }
        return if (allowEmpty && out.size == 7) emptyList() else out
    }

    private fun time(el: JsonElement?): String? {
        val raw = el.s()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(raw) ?: throw IllegalArgumentException("times must be HH:mm")
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        require(h in 0..24 && mi in 0..59 && (h < 24 || mi == 0)) { "times must be HH:mm" }
        return "%02d:%02d".format(h % 24, mi)
    }

    private fun special(el: JsonElement, id: String, facts: MenuFacts): NewSpecial {
        val so = el as? JsonObject ?: throw IllegalArgumentException("a special is not an object")
        val live = facts.itemVariants.getValue(id)
        val from = time(so["from"])
        val to = time(so["to"])
        require((from == null) == (to == null)) { "a time window needs both from and to" }
        require(from == null || from != to) { "a time window can't start and end at the same time" }
        val label = so["label"].s()?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.also {
            require(it.length <= MAX_SPECIAL_LABEL) { "label longer than $MAX_SPECIAL_LABEL characters" }
            AiGuard.checkText(it)?.let { why -> throw IllegalArgumentException("label: $why") }
        }
        val prices = sortedMapOf<String, Long>()
        (so["prices"] as? JsonArray)?.forEach { p ->
            val po = p as? JsonObject ?: throw IllegalArgumentException("a price is not an object")
            val variant = po["variant"].s()
            require(variant != null && variant in live) { "unknown size of $id" }
            val v = price(po["priceMinor"], facts)
            facts.variantPrices[variant]?.let { regular -> require(v < regular) { "a special price must be below the menu price" } }
            prices[variant] = v
        }
        // one price for every size: only the sizes it is cheaper for
        so["priceMinor"]?.takeUnless { it is JsonNull }?.let { p ->
            val v = price(p, facts)
            live.filter { vid -> (facts.variantPrices[vid] ?: Long.MAX_VALUE) > v }.forEach { vid -> prices.putIfAbsent(vid, v) }
            require(prices.isNotEmpty()) { "a special price must be below the menu price" }
        }
        require(prices.isNotEmpty()) { "a special needs a price" }
        return NewSpecial(days(so["days"], allowEmpty = false), from, to, label, LinkedHashMap(prices))
    }

    private fun JsonElement?.s(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

/** The model answered, but not with a usable change set (HTTP 502, code menu_ai_bad_reply).
 *  [tooMany]: specifically over the change-count limit — a different, still retryable, message
 *  than a truncated or unparseable reply. */
class MenuAiReplyException(message: String, val tooMany: Boolean = false) : RuntimeException(message)
