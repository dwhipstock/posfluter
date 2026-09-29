package dev.dwhipstock.pos.aimenu

import dev.dwhipstock.pos.aiphotos.AiPhotoService
import dev.dwhipstock.pos.aiphotos.ImageGenException
import dev.dwhipstock.pos.aiphotos.Scrub
import dev.dwhipstock.pos.api.CatalogOps
import dev.dwhipstock.pos.api.CategoryCreateRequest
import dev.dwhipstock.pos.api.CategoryPatchRequest
import dev.dwhipstock.pos.api.ItemCreateRequest
import dev.dwhipstock.pos.api.ItemPatchRequest
import dev.dwhipstock.pos.api.VariantCreateRequest
import dev.dwhipstock.pos.api.VariantPatchRequest
import dev.dwhipstock.pos.base.Categories
import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.Translations
import dev.dwhipstock.pos.restaurant.NotFoundException
import dev.dwhipstock.pos.sdk.MenuAiConfig
import dev.dwhipstock.pos.sdk.Money
import dev.dwhipstock.pos.sdk.StoreProfile
import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class MenuAiStatus(
    /** The add-on is on for this store (else no AI menu button). */
    val configured: Boolean,
    /** Usable now: configured, a provider + key, and online. */
    val available: Boolean,
    val provider: String,
    val model: String? = null,
    val online: Boolean = false,
    /** menu_ai_off | menu_ai_provider_off | menu_ai_key_missing | menu_ai_offline. */
    val reason: String? = null,
)

/** One line of a change's preview: [field] name / description / price / category / available / order. */
@Serializable
data class MenuChangeDetail(val field: String, val label: String? = null, val before: String? = null, val after: String? = null)

@Serializable
data class MenuChangeDto(
    val id: String,
    /** add_category | add_item | update_item | remove_item | rename_category | reorder_categories */
    val kind: String,
    val title: String,
    val category: String? = null,
    val details: List<MenuChangeDetail> = emptyList(),
    /** The new category this item needs (ticking the item applies it too). */
    val needs: String? = null,
)

@Serializable
data class MenuProposalDto(
    val proposalId: String,
    val provider: String,
    val model: String,
    val summary: String,
    val changes: List<MenuChangeDto>,
    /** Changes the model proposed that failed validation (unknown ids, bad prices…); never applied. */
    val rejected: List<String>,
    val elapsedMs: Long,
)

@Serializable
data class MenuApplyResult(val applied: Int, val createdItemIds: List<String>, val changeSetId: String)

/**
 * AI menu setup: "menu from photos" and "menu chat". The model only ever
 * proposes: its reply is validated into a change set ([MenuChangeSetParser])
 * against the live menu, kept here for a while, and shown to the manager as a
 * preview. [apply] runs the ticked changes, in one transaction, through the
 * same [CatalogOps] the menu editor uses (same validation, same outbox events,
 * so portal sync is exactly as for a hand edit) and is saved in the history
 * ([MenuChangeLog]); [revert] puts any applied set back the same way.
 *
 * Online-only and never on a selling path: off, no key or offline, every call
 * answers a coded error and nothing in the store changes.
 */
class MenuAiService(
    val config: MenuAiConfig.Resolved,
    private val profile: StoreProfile,
    private val provider: MenuAiProvider? = MenuAiProviders.from(config),
    private val reachable: (String) -> Boolean = AiPhotoService::tcpReachable,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(MenuAiService::class.java)

    private class Proposal(val ops: Map<String, MenuOp>, val source: String, val summary: String, val at: Long)
    private val proposals = ConcurrentHashMap<String, Proposal>()
    @Volatile private var probe: Pair<Boolean, Long>? = null

    companion object {
        const val MAX_PHOTOS = 6
        private const val PROPOSAL_TTL_MS = 60 * 60 * 1000L
        private const val MAX_PROPOSALS = 20
        private const val PROBE_TTL_MS = 20_000L
        private const val MAX_ITEMS_IN_PROMPT = 600
    }

    init { Scrub.register(config.apiKey) }

    val enabled: Boolean get() = config.enabled && provider != null
    private val bilingual = LocaleCode.FR in profile.locales && LocaleCode.EN in profile.locales
    /** The store's languages beyond the fr / en catalog slots (Copper Lantern: es, de). */
    private val extraLangs = profile.locales.map { it.tag }.filter { it !in Translations.SLOTS }.toSet()
    private val fractionDigits = runCatching { java.util.Currency.getInstance(profile.currency).defaultFractionDigits }
        .getOrDefault(2).coerceAtLeast(0)

    fun start() {
        config.warning?.let { log.warn("AI menu config ignored: $it") }
        log.info(config.describe())
    }

    private fun online(): Boolean {
        val p = provider ?: return false
        probe?.takeIf { now() - it.second < PROBE_TTL_MS }?.let { return it.first }
        val ok = runCatching { reachable(p.host) }.getOrDefault(false)
        probe = ok to now()
        return ok
    }

    fun status(): MenuAiStatus {
        if (!enabled) return MenuAiStatus(config.enabledFlag, false, config.provider.wire,
            reason = config.disabled?.code ?: MenuAiConfig.Disabled.KEY_MISSING.code)
        val online = online()
        return MenuAiStatus(true, online, provider!!.id, provider.model, online,
            reason = if (online) null else "menu_ai_offline")
    }

    private fun requireProvider(): MenuAiProvider = provider?.takeIf { config.enabled }
        ?: throw ImageGenException(409, "menu_ai_disabled",
            "AI menu setup is off on this store (${config.disabled?.code ?: "menu_ai_provider_off"})")

    // --- propose ---

    fun fromPhotos(images: List<MenuImage>, note: String? = null): MenuProposalDto {
        require(images.isNotEmpty()) { "at least one menu photo is required" }
        require(images.size <= MAX_PHOTOS) { "at most $MAX_PHOTOS photos at a time" }
        val task = "Read the attached photo(s) of our paper menu. Propose add_category / add_item for everything " +
            "on it that is not already on our menu (same name = already there). If an item is already there " +
            "but the photo shows a different price, propose update_item with the new price instead. " +
            "Copy names, descriptions and prices exactly as printed; do not invent anything." +
            (note?.takeIf { it.isNotBlank() }?.let { "\nManager's note: ${it.take(500)}" } ?: "")
        return propose(task, images, "photos")
    }

    fun chat(text: String): MenuProposalDto {
        val t = text.trim()
        require(t.isNotEmpty()) { "type what to change" }
        require(t.length <= 2000) { "that request is too long" }
        return propose("Manager's request: $t", emptyList(), "chat")
    }

    /**
     * Floor-plan "Add from photo": one photo of a thing in the room → a CUSTOM
     * object suggestion (name, icon key, shape, size). Same provider, key and
     * on/off switch as the menu; the photo lives only for this call.
     */
    fun suggestRoomObject(image: MenuImage): RoomObjectSuggestion {
        val p = requireProvider()
        val reply = try {
            p.complete(RoomObjectSuggest.systemPrompt(bilingual), "What is this? Suggest the floor-plan object.", listOf(image))
        } catch (e: ImageGenException) {
            if (e.code == ImageGenException.UNAVAILABLE) probe = false to now()
            log.info("AI room object via ${p.id} failed: ${e.code} ${e.message}")
            throw ImageGenException(e.status, e.code.replace("image_", "menu_ai_"), e.message ?: "AI suggestion failed",
                e.retryAfterSeconds, e)
        }
        return RoomObjectSuggest.parse(reply, bilingual, p.id, p.model)
    }

    /**
     * "Translate menu": the names of items and categories that have no name
     * yet in one of the store's extra languages (es, de) → a proposal of
     * set_name changes, previewed, applied and revertable like any other.
     * Nothing missing → an empty proposal, without calling the model.
     */
    fun translate(): MenuProposalDto {
        val p = requireProvider()
        require(extraLangs.isNotEmpty()) { "this store has no languages beyond French and English" }
        val missing = transaction { missingNames() }
        if (missing.isEmpty()) return MenuProposalDto("", p.id, p.model, "", emptyList(), emptyList(), 0)
        val list = missing.joinToString("\n") { (entity, id, en, fr, langs) ->
            "- $entity $id: en \"$en\" / fr \"$fr\" → ${langs.joinToString(", ")}"
        }
        val task = "Translate menu names. For each line below, propose one set_name op per language listed " +
            "after the arrow (${extraLangs.joinToString(", ")}), using the English and French names given. " +
            "Write natural menu names a restaurant in that language would print, short enough for a button; " +
            "keep brand and proper names (and dish names customers know as is). Propose nothing else.\n$list"
        return propose(task, emptyList(), "translate", includeMenu = false)
    }

    /** Items and categories lacking a name in some extra language: (entity, id, en, fr, langs). Inside a transaction. */
    private fun missingNames(): List<Missing> {
        val items = Translations.of(Translations.ITEM)
        val cats = Translations.of(Translations.CATEGORY)
        fun langs(have: Map<String, String>?) = extraLangs.filter { have?.get(it).isNullOrBlank() }
        return Categories.selectAll().orderBy(Categories.sortOrder).mapNotNull { c ->
            val id = c[Categories.id]
            langs(cats[id]).takeIf { it.isNotEmpty() }
                ?.let { Missing("category", id, c[Categories.nameEn], c[Categories.nameFr], it) }
        } + Items.selectAll().where { Items.deletedAt.isNull() }.take(MAX_ITEMS_IN_PROMPT).mapNotNull { i ->
            val id = i[Items.id]
            langs(items[id]).takeIf { it.isNotEmpty() }
                ?.let { Missing("item", id, i[Items.nameEn], i[Items.nameFr], it) }
        }
    }

    private data class Missing(val entity: String, val id: String, val en: String, val fr: String, val langs: List<String>)

    private fun propose(
        task: String, images: List<MenuImage>, source: String, includeMenu: Boolean = true,
    ): MenuProposalDto {
        val p = requireProvider()
        val (menuJson, facts) = transaction { menuContext() }
        val started = now()
        val reply = try {
            p.complete(systemPrompt(), if (includeMenu) "Current menu (JSON):\n$menuJson\n\n$task" else task, images)
        } catch (e: ImageGenException) {
            if (e.code == ImageGenException.UNAVAILABLE) probe = false to now()
            log.info("AI menu via ${p.id} failed: ${e.code} ${e.message}")
            // same meaning, the menu's own codes (image_timeout → menu_ai_timeout) so the tablet says "AI menu"
            throw ImageGenException(e.status, e.code.replace("image_", "menu_ai_"), e.message ?: "AI menu failed",
                e.retryAfterSeconds, e)
        }
        val elapsed = now() - started
        val parsed = try {
            MenuChangeSetParser.parse(reply, facts)
        } catch (e: MenuAiReplyException) {
            log.info("AI menu via ${p.id}: unusable reply (${e.message})")
            throw e
        }
        sweep()
        val ids = parsed.ops.indices.map { "c${it + 1}" }
        val ops = ids.zip(parsed.ops).toMap()
        val proposalId = UUID.randomUUID().toString()
        proposals[proposalId] = Proposal(ops, source, parsed.summary, now())
        val changes = transaction { ops.map { (id, op) -> preview(id, op, ops) } }
        log.info("AI menu via ${p.id}/${p.model}: ${changes.size} change(s), ${parsed.rejected.size} rejected, ${elapsed}ms")
        return MenuProposalDto(proposalId, p.id, p.model, parsed.summary, changes, parsed.rejected, elapsed)
    }

    private fun sweep() {
        val cutoff = now() - PROPOSAL_TTL_MS
        proposals.entries.removeIf { it.value.at < cutoff }
        if (proposals.size > MAX_PROPOSALS) proposals.entries.sortedBy { it.value.at }
            .take(proposals.size - MAX_PROPOSALS).forEach { proposals.remove(it.key) }
    }

    private fun systemPrompt(): String {
        val lang = if (bilingual)
            "The store is bilingual: every name has English (nameEn) and French (nameFr). Fill both only when " +
                "the menu or the manager gives both; otherwise fill the language you have and leave the other \"\"."
        else "Write names in nameEn; leave nameFr \"\"."
        return """
            You maintain the menu of a restaurant point of sale. You never change anything yourself: you
            propose a change set that the manager reviews. Reply with ONE JSON object and nothing else:
            {"summary": "<one short sentence for the manager>", "ops": [ ... ]}
            Each op is one of:
            {"op":"add_category","ref":"new:<short-slug>","nameEn":"","nameFr":""}
            {"op":"add_item","category":"<category id or new: ref>","nameEn":"","nameFr":"","descriptionEn":"","descriptionFr":"","isAlcohol":false,"variants":[{"labelEn":"Regular","labelFr":"","priceMinor":1400}]}
            {"op":"update_item","item":"<item id>", then only the fields that change: "nameEn","nameFr","descriptionEn","descriptionFr","category","active", "prices":[{"variant":"<variant id>","priceMinor":1500}]}
            {"op":"remove_item","item":"<item id>"}
            {"op":"rename_category","category":"<category id>","nameEn":"","nameFr":""}
            {"op":"reorder_categories","order":["<category id or new: ref>", ...]}
            ${if (extraLangs.isEmpty()) "" else "{\"op\":\"set_name\",\"entity\":\"item\" or \"category\",\"id\":\"<id>\",\"lang\":\"${extraLangs.joinToString("\" or \"")}\",\"name\":\"\"}  (only when asked to translate)"}
            Rules:
            - Prices are whole numbers in the minor unit of ${profile.currency} (${fractionDigits} decimals: ${
                "1" + "0".repeat(fractionDigits)} = 1 ${profile.currency}). Never a string, never a decimal.
            - Refer to existing categories, items and sizes only by the ids in the current menu. A new category
              gets a "new:" ref, used by the new items in it.
            - An item with one price has one variant (labelEn "Regular"). Sizes (small/large, glass/bottle) are variants.
            - "86" an item, "sold out", "take off" = update_item with "active": false. "Bring back" = "active": true.
              "Remove" / "delete" = remove_item. Price changes list every size of each item concerned.
            - Mark beer, wine, spirits and cocktails "isAlcohol": true.
            - $lang
            - If the request is unclear or impossible, return "ops": [] and say why in the summary.
        """.trimIndent()
    }

    /** The live menu as the model sees it, and the ids it may use. Call inside a transaction. */
    private fun menuContext(): Pair<String, MenuFacts> {
        val cats = Categories.selectAll().orderBy(Categories.sortOrder).toList()
        val items = Items.selectAll().where { Items.deletedAt.isNull() }.toList()
        val variants = ItemVariants.selectAll().where { ItemVariants.deletedAt.isNull() }
            .orderBy(ItemVariants.sortOrder).groupBy { it[ItemVariants.itemId] }
        val json = buildJsonObject {
            put("currency", profile.currency)
            putJsonArray("categories") {
                cats.forEach { c -> addJsonObject {
                    put("id", c[Categories.id]); put("nameEn", c[Categories.nameEn]); put("nameFr", c[Categories.nameFr])
                } }
            }
            putJsonArray("items") {
                items.take(MAX_ITEMS_IN_PROMPT).forEach { i -> addJsonObject {
                    put("id", i[Items.id]); put("category", i[Items.categoryId])
                    put("nameEn", i[Items.nameEn]); put("nameFr", i[Items.nameFr])
                    if (!i[Items.active]) put("active", false)
                    putJsonArray("variants") {
                        variants[i[Items.id]].orEmpty().forEach { v -> addJsonObject {
                            put("id", v[ItemVariants.id]); put("labelEn", v[ItemVariants.labelEn])
                            put("priceMinor", v[ItemVariants.priceCents])
                        } }
                    }
                } }
            }
        }
        val facts = MenuFacts(cats.map { it[Categories.id] },
            items.associate { i -> i[Items.id] to variants[i[Items.id]].orEmpty().map { it[ItemVariants.id] } },
            extraLangs)
        return json.toString() to facts
    }

    // --- preview ---

    private fun money(minor: Long) = profile.format(Money(minor))
    private fun pick(en: String, fr: String) = en.ifBlank { fr }

    private fun categoryName(ref: String, ops: Map<String, MenuOp>): String =
        ops.values.filterIsInstance<MenuOp.AddCategory>().firstOrNull { it.ref == ref }?.let { pick(it.nameEn, it.nameFr) }
            ?: Categories.selectAll().where { Categories.id eq ref }.firstOrNull()
                ?.let { pick(it[Categories.nameEn], it[Categories.nameFr]) }
            ?: ref

    private fun changed(field: String, before: String?, after: String?, label: String? = null) =
        if (after != null && after != before) MenuChangeDetail(field, label, before, after) else null

    private fun preview(id: String, op: MenuOp, ops: Map<String, MenuOp>): MenuChangeDto = when (op) {
        is MenuOp.AddCategory -> MenuChangeDto(id, "add_category", pick(op.nameEn, op.nameFr), details = listOfNotNull(
            MenuChangeDetail("nameEn", after = op.nameEn), MenuChangeDetail("nameFr", after = op.nameFr)))
        is MenuOp.AddItem -> MenuChangeDto(id, "add_item", pick(op.nameEn, op.nameFr), categoryName(op.category, ops),
            details = listOfNotNull(
                MenuChangeDetail("nameEn", after = op.nameEn),
                MenuChangeDetail("nameFr", after = op.nameFr),
                op.descriptionEn.takeIf { it.isNotBlank() }?.let { MenuChangeDetail("descriptionEn", after = it) },
                op.descriptionFr.takeIf { it.isNotBlank() }?.let { MenuChangeDetail("descriptionFr", after = it) },
            ) + op.variants.map { MenuChangeDetail("price", pick(it.labelEn, it.labelFr).ifBlank { null }, after = money(it.priceMinor)) },
            needs = ops.entries.firstOrNull { (it.value as? MenuOp.AddCategory)?.ref == op.category }?.key)
        is MenuOp.UpdateItem -> {
            val row = Items.selectAll().where { Items.id eq op.itemId }.first()
            val vs = ItemVariants.selectAll().where { ItemVariants.itemId eq op.itemId }.associateBy { it[ItemVariants.id] }
            MenuChangeDto(id, "update_item", pick(row[Items.nameEn], row[Items.nameFr]),
                categoryName(row[Items.categoryId], ops), details = listOfNotNull(
                    changed("nameEn", row[Items.nameEn], op.nameEn),
                    changed("nameFr", row[Items.nameFr], op.nameFr),
                    changed("descriptionEn", row[Items.descriptionEn], op.descriptionEn),
                    changed("descriptionFr", row[Items.descriptionFr], op.descriptionFr),
                    changed("category", categoryName(row[Items.categoryId], ops), op.category?.let { categoryName(it, ops) }),
                    changed("available", row[Items.active].toString(), op.active?.toString()),
                ) + op.prices.mapNotNull { (vid, price) ->
                    val v = vs.getValue(vid)
                    changed("price", money(v[ItemVariants.priceCents]), money(price), v[ItemVariants.labelEn])
                },
                needs = ops.entries.firstOrNull { (it.value as? MenuOp.AddCategory)?.ref == op.category }?.key)
        }
        is MenuOp.RemoveItem -> {
            val row = Items.selectAll().where { Items.id eq op.itemId }.first()
            MenuChangeDto(id, "remove_item", pick(row[Items.nameEn], row[Items.nameFr]), categoryName(row[Items.categoryId], ops))
        }
        is MenuOp.RenameCategory -> {
            val row = Categories.selectAll().where { Categories.id eq op.categoryId }.first()
            MenuChangeDto(id, "rename_category", pick(row[Categories.nameEn], row[Categories.nameFr]), details = listOfNotNull(
                changed("nameEn", row[Categories.nameEn], op.nameEn), changed("nameFr", row[Categories.nameFr], op.nameFr)))
        }
        is MenuOp.SetName -> {
            val title = if (op.entity == "item") itemTitle(op.id) else categoryName(op.id, ops)
            MenuChangeDto(id, "set_name", title, details = listOf(MenuChangeDetail("name", op.lang,
                Translations.get(op.entity, op.id, op.lang), op.name)))
        }
        is MenuOp.ReorderCategories -> {
            val before = Categories.selectAll().orderBy(Categories.sortOrder)
                .map { pick(it[Categories.nameEn], it[Categories.nameFr]) }
            MenuChangeDto(id, "reorder_categories", "", details = listOf(MenuChangeDetail("order",
                before = before.joinToString(", "), after = op.order.joinToString(", ") { categoryName(it, ops) })))
        }
    }

    // --- apply / history / revert ---

    /**
     * Apply the ticked [changeIds] of [proposalId] in one transaction (all or
     * nothing) and save it as a change set in the history. A ticked item pulls
     * in the new category it needs.
     */
    fun apply(proposalId: String, changeIds: List<String>, userId: String, approverId: String): MenuApplyResult {
        val proposal = proposals[proposalId] ?: throw NotFoundException("that AI proposal has expired; ask again")
        require(changeIds.isNotEmpty()) { "tick at least one change" }
        changeIds.forEach { require(it in proposal.ops) { "unknown change '$it'" } }
        val wanted = changeIds.toMutableSet()
        proposal.ops.forEach { (id, op) ->
            val needs = when (op) { is MenuOp.AddItem -> op.category; is MenuOp.UpdateItem -> op.category; else -> null }
            if (id in wanted && needs != null) proposal.ops.entries
                .firstOrNull { (it.value as? MenuOp.AddCategory)?.ref == needs }?.let { wanted += it.key }
        }
        val selected = proposal.ops.filterKeys { it in wanted }.values
        // categories first (items point at them), reorder last
        val ordered = selected.filterIsInstance<MenuOp.AddCategory>() +
            selected.filter { it !is MenuOp.AddCategory && it !is MenuOp.ReorderCategories } +
            selected.filterIsInstance<MenuOp.ReorderCategories>()
        val created = mutableListOf<String>()
        val setId = UUID.randomUUID().toString()
        transaction {
            val refs = mutableMapOf<String, String>()
            val rows = mutableListOf<MenuChangeLog.Row>()
            for (op in ordered) applyOne(op, refs, rows, created)
            MenuChangeLog.record(setId, userId, approverId, proposal.source, proposal.summary, rows)
        }
        proposals.remove(proposalId)
        log.info("AI menu: applied ${ordered.size} change(s) as $setId, ${created.size} new item(s)")
        return MenuApplyResult(ordered.size, created, setId)
    }

    private fun both(en: String, fr: String) = en.ifBlank { fr } to fr.ifBlank { en }

    private fun itemTitle(itemId: String) = Items.selectAll().where { Items.id eq itemId }.firstOrNull()
        ?.let { pick(it[Items.nameEn], it[Items.nameFr]) } ?: itemId

    private fun applyOne(
        op: MenuOp, refs: MutableMap<String, String>, rows: MutableList<MenuChangeLog.Row>, created: MutableList<String>,
    ) {
        fun cat(ref: String) = refs[ref] ?: ref
        when (op) {
            is MenuOp.AddCategory -> {
                val (en, fr) = both(op.nameEn, op.nameFr)
                val c = CatalogOps.createCategory(CategoryCreateRequest(nameFr = fr, nameEn = en))
                refs[op.ref] = c.id
                rows += MenuChangeLog.Row("category", c.id, "create", en, null)
            }
            is MenuOp.AddItem -> {
                val (en, fr) = both(op.nameEn, op.nameFr)
                val dto = CatalogOps.createItem(ItemCreateRequest(
                    nameFr = fr, nameEn = en,
                    descriptionFr = op.descriptionFr.ifBlank { if (bilingual) "" else op.descriptionEn },
                    descriptionEn = op.descriptionEn.ifBlank { op.descriptionFr },
                    categoryId = cat(op.category), abbrev = abbrev(en), isAlcohol = op.isAlcohol,
                    variants = op.variants.map { v ->
                        val (le, lf) = both(v.labelEn, v.labelFr)
                        VariantCreateRequest(labelFr = lf.ifBlank { "Standard" }, labelEn = le.ifBlank { "Regular" }, priceCents = v.priceMinor)
                    },
                ))
                created += dto.id
                rows += MenuChangeLog.Row("item", dto.id, "create", en, null)
            }
            is MenuOp.UpdateItem -> {
                val before = MenuChangeLog.itemState(op.itemId)?.takeIf { it["deleted"]?.jsonPrimitive?.boolean == false }
                    ?: throw NotFoundException("item ${op.itemId} not found")
                val title = itemTitle(op.itemId)
                val patch = ItemPatchRequest(nameFr = op.nameFr, nameEn = op.nameEn,
                    descriptionFr = op.descriptionFr, descriptionEn = op.descriptionEn,
                    categoryId = op.category?.let(::cat), active = op.active)
                if (patch != ItemPatchRequest()) {
                    CatalogOps.patchItem(op.itemId, patch)
                    rows += MenuChangeLog.Row("item", op.itemId, "update", title, before)
                }
                op.prices.forEach { (vid, price) ->
                    val old = MenuChangeLog.variantState(vid)
                    CatalogOps.patchVariant(op.itemId, vid, VariantPatchRequest(priceCents = price))
                    rows += MenuChangeLog.Row("variant", vid, "update", title, old)
                }
            }
            is MenuOp.RemoveItem -> {
                val before = MenuChangeLog.itemState(op.itemId) ?: throw NotFoundException("item ${op.itemId} not found")
                val title = itemTitle(op.itemId)
                CatalogOps.deleteItem(op.itemId) // soft delete: sales history keeps the row
                rows += MenuChangeLog.Row("item", op.itemId, "delete", title, before)
            }
            is MenuOp.RenameCategory -> {
                val before = MenuChangeLog.categoryState(op.categoryId)
                    ?: throw NotFoundException("category ${op.categoryId} not found")
                CatalogOps.patchCategory(op.categoryId, CategoryPatchRequest(nameFr = op.nameFr, nameEn = op.nameEn))
                val title = Categories.selectAll().where { Categories.id eq op.categoryId }.first()
                    .let { pick(it[Categories.nameEn], it[Categories.nameFr]) }
                rows += MenuChangeLog.Row("category", op.categoryId, "update", title, before)
            }
            is MenuOp.SetName -> {
                val key = MenuChangeLog.translationKey(op.entity, op.id, op.lang)
                val before = MenuChangeLog.translationState(key)
                val title = if (op.entity == "item") itemTitle(op.id)
                    else Categories.selectAll().where { Categories.id eq op.id }.firstOrNull()
                        ?.let { pick(it[Categories.nameEn], it[Categories.nameFr]) } ?: op.id
                Translations.set(op.entity, op.id, op.lang, op.name)
                rows += MenuChangeLog.Row("translation", key, "update", "$title (${op.lang})", before)
            }
            is MenuOp.ReorderCategories -> {
                val before = MenuChangeLog.orderState()
                val current = Categories.selectAll().orderBy(Categories.sortOrder).map { it[Categories.id] }
                val listed = op.order.map(::cat)
                // categories the model left out keep their relative order, after the listed ones
                CatalogOps.reorderCategories(listed + current.filter { it !in listed })
                rows += MenuChangeLog.Row("category_order", "*", "reorder", "", before)
            }
        }
    }

    fun history(): List<MenuChangeSetDto> = transaction { MenuChangeLog.history() }

    /** Revert one applied change set (see [MenuChangeLog.revert]); all or nothing. */
    fun revert(setId: String, userId: String, force: Boolean): Int {
        val n = transaction { MenuChangeLog.revert(setId, userId, force) }
        log.info("AI menu: reverted $setId ($n row(s))")
        return n
    }


    override fun toString() = "MenuAiService(${config.describe()})"
}

/** "Caesar Salad" → "CS"; "Poutine" → "PO". Always 1–4 characters. */
internal fun abbrev(name: String): String {
    val words = name.uppercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "NEW"
        words.size == 1 -> words[0].take(2)
        else -> words.take(2).joinToString("") { it.take(1) }
    }
}
