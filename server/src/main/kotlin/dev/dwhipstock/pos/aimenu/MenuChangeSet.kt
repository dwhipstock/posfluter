package dev.dwhipstock.pos.aimenu

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
}

/** What the model may refer to: the live menu when the proposal was made. */
class MenuFacts(
    val categoryIds: List<String>,
    /** item id → its live variant ids. */
    val itemVariants: Map<String, List<String>>,
    /** The store's languages beyond fr / en, which set_name may fill. */
    val extraLangs: Set<String> = emptySet(),
)

class ParsedChangeSet(val summary: String, val ops: List<MenuOp>, val rejected: List<String>)

/**
 * Strict reader of the model's reply. The reply must be one JSON object
 * `{summary?, ops: [...]}` (a ```json fence around it is tolerated); anything
 * else is a [MenuAiReplyException]. Each op is checked on its own: an unknown
 * op, an unknown id, a bad price or a missing name drops that op into
 * [ParsedChangeSet.rejected] with the reason; the rest stay.
 */
object MenuChangeSetParser {
    const val MAX_OPS = 300
    const val MAX_PRICE_MINOR = 10_000_000L
    private const val MAX_ITEM_NAME = 200
    private const val MAX_CATEGORY_NAME = 100
    private const val MAX_DESCRIPTION = 500
    private const val MAX_VARIANTS = 8

    private val json = Json { isLenient = false }

    fun parse(reply: String, facts: MenuFacts): ParsedChangeSet {
        val text = reply.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw MenuAiReplyException("the AI reply was not a JSON object")
        val rawOps = root["ops"] as? JsonArray ?: throw MenuAiReplyException("the AI reply has no \"ops\" list")
        if (rawOps.size > MAX_OPS) throw MenuAiReplyException("the AI proposed too many changes (${rawOps.size})")
        val summary = root["summary"].s()?.take(500) ?: ""

        val newRefs = mutableSetOf<String>()
        val ops = mutableListOf<MenuOp>()
        val rejected = mutableListOf<String>()
        rawOps.forEachIndexed { i, el ->
            val o = el as? JsonObject
            if (o == null) { rejected += "#${i + 1}: not an object"; return@forEachIndexed }
            try {
                val op = one(o, facts, newRefs)
                if (op is MenuOp.AddCategory) newRefs += op.ref
                ops += op
            } catch (e: IllegalArgumentException) {
                rejected += "#${i + 1} (${o["op"].s() ?: "?"}): ${e.message}"
            }
        }
        return ParsedChangeSet(summary, ops, rejected)
    }

    private fun one(o: JsonObject, facts: MenuFacts, newRefs: Set<String>): MenuOp {
        fun category(raw: String?): String {
            require(!raw.isNullOrBlank()) { "category missing" }
            require(raw in facts.categoryIds || raw in newRefs) { "unknown category '$raw'" }
            return raw
        }
        fun item(): String {
            val id = o["item"].s()
            require(!id.isNullOrBlank()) { "item missing" }
            require(id in facts.itemVariants) { "unknown item '$id'" }
            return id
        }
        fun name(key: String, max: Int): String? = o[key].s()?.trim()?.also {
            require(it.length <= max) { "$key longer than $max characters" }
        }
        return when (val kind = o["op"].s()) {
            "add_category" -> {
                val ref = o["ref"].s()?.trim()
                require(!ref.isNullOrBlank() && ref.startsWith("new:")) { "ref must look like \"new:...\"" }
                require(ref !in newRefs) { "duplicate ref '$ref'" }
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
                    NewVariant(vo["labelEn"].s()?.trim()?.take(60).orEmpty(), vo["labelFr"].s()?.trim()?.take(60).orEmpty(),
                        price(vo["priceMinor"]))
                } ?: o["priceMinor"]?.let { listOf(NewVariant("", "", price(it))) }
                require(!variants.isNullOrEmpty()) { "a price is required" }
                require(variants.size <= MAX_VARIANTS) { "too many sizes" }
                MenuOp.AddItem(
                    category = category(o["category"].s()), nameEn = en, nameFr = fr,
                    descriptionEn = o["descriptionEn"].s()?.trim()?.take(MAX_DESCRIPTION).orEmpty(),
                    descriptionFr = o["descriptionFr"].s()?.trim()?.take(MAX_DESCRIPTION).orEmpty(),
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
                    require(variant != null && variant in live) { "unknown size '$variant' of $id" }
                    prices[variant] = price(po["priceMinor"])
                }
                o["priceMinor"]?.takeUnless { it is JsonNull }?.let {
                    require(live.size == 1) { "$id has several sizes; give a price per size" }
                    prices[live.single()] = price(it)
                }
                val op = MenuOp.UpdateItem(
                    itemId = id,
                    nameEn = name("nameEn", MAX_ITEM_NAME)?.takeIf { it.isNotEmpty() },
                    nameFr = name("nameFr", MAX_ITEM_NAME)?.takeIf { it.isNotEmpty() },
                    descriptionEn = o["descriptionEn"].s()?.trim()?.take(MAX_DESCRIPTION),
                    descriptionFr = o["descriptionFr"].s()?.trim()?.take(MAX_DESCRIPTION),
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
                require(id != null && id in facts.categoryIds) { "unknown category '$id'" }
                val en = name("nameEn", MAX_CATEGORY_NAME)?.takeIf { it.isNotEmpty() }
                val fr = name("nameFr", MAX_CATEGORY_NAME)?.takeIf { it.isNotEmpty() }
                require(en != null || fr != null) { "a new name is required" }
                MenuOp.RenameCategory(id, en, fr)
            }
            "set_name" -> {
                val entity = o["entity"].s()
                val id = o["id"].s()
                when (entity) {
                    "item" -> require(id != null && id in facts.itemVariants) { "unknown item '$id'" }
                    "category" -> require(id != null && id in facts.categoryIds) { "unknown category '$id'" }
                    else -> throw IllegalArgumentException("entity must be item or category")
                }
                val lang = o["lang"].s()?.trim()?.lowercase()
                require(lang != null && lang in facts.extraLangs) { "language '$lang' is not one of ${facts.extraLangs}" }
                val text = name("name", MAX_ITEM_NAME)
                require(!text.isNullOrEmpty()) { "a name is required" }
                MenuOp.SetName(entity, id!!, lang, text)
            }
            "reorder_categories" -> {
                val order = (o["order"] as? JsonArray)?.map { it.s() ?: "" }
                require(!order.isNullOrEmpty()) { "order missing" }
                require(order.toSet().size == order.size) { "a category appears twice" }
                order.forEach { category(it) }
                MenuOp.ReorderCategories(order)
            }
            else -> throw IllegalArgumentException("unknown op '$kind'")
        }
    }

    private fun price(el: JsonElement?): Long {
        val p = el as? JsonPrimitive
        require(p != null && !p.isString) { "priceMinor must be a whole number" }
        val v = p.longOrNull ?: throw IllegalArgumentException("priceMinor must be a whole number (minor units)")
        require(v in 0..MAX_PRICE_MINOR) { "price out of range" }
        return v
    }

    private fun JsonElement?.s(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

/** The model answered, but not with a usable change set (HTTP 502, code menu_ai_bad_reply). */
class MenuAiReplyException(message: String) : RuntimeException(message)
