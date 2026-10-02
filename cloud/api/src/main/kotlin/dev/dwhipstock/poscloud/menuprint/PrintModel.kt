package dev.dwhipstock.poscloud.menuprint

import dev.dwhipstock.poscloud.menu.MenuItemDto
import dev.dwhipstock.poscloud.menu.MenuResponse
import kotlinx.serialization.Serializable

/** The five printable menus. */
enum class MenuKind(val code: String) {
    /** Every item on sale, by category; day-only items tagged; the specials in a box. */
    FULL("full"),
    /** What is sold today (the store's business day), with today's special prices. */
    TODAY("today"),
    /** The drinks only. */
    DRINKS("drinks"),
    /** About 6–8 standouts (the AI's pick; without AI, photos and specials first). */
    HIGHLIGHTS("highlights"),
    /** One big page of the specials (happy hour, day prices): table-tent friendly. */
    FLYER("flyer");

    /** Every candidate is printed (the AI only orders and groups them); else the AI picks some. */
    val complete: Boolean get() = this == FULL || this == TODAY || this == DRINKS

    companion object {
        fun of(code: String?): MenuKind? = entries.firstOrNull { it.code == code?.trim()?.lowercase() }
    }
}

// --- the wire shapes (/v1/menu-print) ---

/** The portal's brand pack, as much of it as a printed page needs (validated in [PrintBrand.of]). */
@Serializable
data class PrintBrandInput(
    val name: String? = null,
    val primary: String? = null,
    val accent: String? = null,
    val text: String? = null,
    /** Secondary text (the portal sends a dark neutral; it is darkened further if it reads too light). */
    val muted: String? = null,
    /** inter | jakarta | barlow (the portal's brand fonts). */
    val font: String? = null,
    /** The logo as a PNG / JPEG data URL (the portal converts its brand mark first). */
    val logo: String? = null,
)

@Serializable
data class PrintRequest(
    /** full | today | drinks | highlights | flyer */
    val type: String,
    val lang: String? = null,
    /** letter (default) | a4 */
    val paper: String? = null,
    val photos: Boolean = false,
    /** Free text for the AI ("fall theme, mention the patio"): data, never instructions. */
    val notes: String? = null,
    val brand: PrintBrandInput? = null,
    /** auto (the AI picks) | one of [PrintStyles.KEYS]: the manager's choice wins. */
    val style: String? = null,
    /** With [photos]: draw a stand-in photo for the printed items that have none. */
    val fillPhotos: Boolean = false,
    /** With [fillPhotos]: also keep those pictures as the items' photos (an explicit tick). */
    val savePhotos: Boolean = false,
)

/** Step 1 (writing the menu): the plan is kept here; [jobId] names it for the artwork and the PDF. */
@Serializable
data class PrintPlanResult(
    val jobId: String,
    /** The look: one of [PrintStyles.KEYS]. */
    val style: String,
    /** manager | ai | notes | default */
    val styleBy: String,
    /** used | off | fallback */
    val ai: String,
    val aiReason: String? = null,
    val notesIgnored: Boolean = false,
    val items: Int,
    /** Pictures the artwork step will make (header, section art, background, stand-in photos). */
    val artToMake: Int,
    /** The image service is set up here (no: the style's built-in art is used). */
    val artAvailable: Boolean,
    val elapsedMs: Long,
)

@Serializable
data class PrintArtRequest(
    /** "New artwork": make every picture again instead of reusing the saved ones. */
    val fresh: Boolean = false,
)

/** Step 2 (making the artwork). */
@Serializable
data class PrintArtResult(
    val jobId: String,
    /** Made now / reused from earlier / the style's built-in art instead (failed, timed out, refused). */
    val made: Int,
    val reused: Int,
    val builtIn: Int,
    /** Stand-in dish photos on this print, and how many were kept as the items' photos. */
    val photos: Int = 0,
    val photosSaved: Int = 0,
    val elapsedMs: Long,
)

@Serializable
data class PrintResult(
    /** The PDF, base64. */
    val pdf: String,
    val fileName: String,
    val pages: Int,
    /** Page images for the preview (JPEG data URLs; the first pages only). */
    val previews: List<String>,
    /** used | off (no key here) | fallback (the AI was asked, its answer wasn't usable) */
    val ai: String,
    /** Why there is no AI wording: not_setup | timeout | unavailable | bad_reply | daily_limit | too_big; null with AI. */
    val aiReason: String? = null,
    /** The manager's notes were not passed on (they read like instructions, not a theme). */
    val notesIgnored: Boolean = false,
    val items: Int,
    val elapsedMs: Long,
)

@Serializable
data class PrintStatus(
    val canUse: Boolean,
    /** AI wording (a Gemini key here). */
    val ai: Boolean,
    /** AI artwork (an image service here); else every style's built-in art. */
    val art: Boolean,
    val styles: List<String>,
)

// --- the menu as the printer reads it (one store, one language) ---

data class PrintSize(val id: String, val label: String, val cents: Long)

data class PrintItem(
    val id: String,
    val name: String,
    /** The item's own description in the menu's language (else the other one), maybe blank. */
    val description: String,
    val categoryId: String,
    val isAlcohol: Boolean,
    val sizes: List<PrintSize>,
    /** Sold only on these days; empty = every day. */
    val availableDays: List<String>,
    val specials: List<PrintSpecial>,
    val hasPhoto: Boolean,
    /** English facts for image prompts (the models read English best): name, description, category. */
    val enName: String = name,
    val enDescription: String = description,
    val enCategory: String = "",
)

data class PrintCategory(val id: String, val name: String, val enName: String = name)

data class PrintCatalog(val categories: List<PrintCategory>, val items: List<PrintItem>) {
    private val byId = items.associateBy { it.id }
    private val catById = categories.associateBy { it.id }
    fun item(id: String): PrintItem? = byId[id]
    fun categoryName(id: String): String = catById[id]?.name ?: ""
    fun categoryIndex(id: String): Int = categories.indexOfFirst { it.id == id }.let { if (it < 0) Int.MAX_VALUE else it }

    companion object {
        /**
         * One store's menu ([MenuResponse] of a single venue) for printing in
         * [lang]: on-sale items only (an item switched off at the till or
         * deleted never prints), items with no size left out, every name in
         * [lang] when the store has it (else English, else French).
         */
        fun of(menu: MenuResponse, lang: String): PrintCatalog {
            fun pickName(fr: String, en: String, names: Map<String, String>): String = when (lang) {
                "fr" -> fr.ifBlank { en }
                "en" -> en.ifBlank { fr }
                else -> names[lang]?.takeIf { it.isNotBlank() } ?: en.ifBlank { fr }
            }.trim()
            fun desc(i: MenuItemDto) = (if (lang == "fr") i.descriptionFr.ifBlank { i.descriptionEn } else i.descriptionEn.ifBlank { i.descriptionFr })
                .replace(Regex("\\s+"), " ").trim()
            val items = menu.items.filter { it.active && it.variants.isNotEmpty() }.map { i ->
                PrintItem(
                    id = i.id,
                    name = pickName(i.nameFr, i.nameEn, i.names),
                    description = desc(i),
                    categoryId = i.categoryId,
                    isAlcohol = i.isAlcohol,
                    sizes = i.variants.sortedBy { it.sortOrder }.map { v -> PrintSize(v.id, pickName(v.labelFr, v.labelEn, v.names), v.priceCents) },
                    availableDays = i.availableDays.orEmpty(),
                    specials = i.specials.orEmpty().map { PrintSpecial(it.days, it.from, it.to, it.label, it.prices) },
                    hasPhoto = i.photoVersion != null,
                    enName = i.nameEn.ifBlank { i.nameFr }.trim(),
                    enDescription = i.descriptionEn.ifBlank { i.descriptionFr }.replace(Regex("\\s+"), " ").trim(),
                    enCategory = menu.categories.firstOrNull { it.id == i.categoryId }?.let { it.nameEn.ifBlank { it.nameFr } }.orEmpty(),
                )
            }
            val used = items.map { it.categoryId }.toSet()
            val cats = menu.categories.sortedBy { it.sortOrder }.filter { it.id in used }
                .map { PrintCategory(it.id, pickName(it.nameFr, it.nameEn, it.names), it.nameEn.ifBlank { it.nameFr }) }
            // an item whose category is gone still prints, under its own (unnamed) heading
            val known = cats.map { it.id }.toSet()
            val orphans = used.filter { it !in known }.map { PrintCategory(it, "") }
            val categories = cats + orphans
            val order = categories.withIndex().associate { it.value.id to it.index }
            return PrintCatalog(categories, items.sortedWith(compareBy({ order[it.categoryId] ?: Int.MAX_VALUE }, { it.name.lowercase() }, { it.id })))
        }
    }
}

/** One section of the printed menu: a heading, an optional intro, item ids (all on the menu). */
data class PlanSection(
    val title: String, val intro: String?, val itemIds: List<String>, val categoryId: String? = null,
    /** What the section's small illustration shows ("hops and barley"); null = the style's ornament. */
    val motif: String? = null,
)

/** What goes on the page: the code's numbers, the AI's (checked) words or the items' own. */
data class MenuPlan(
    val title: String,
    val tagline: String?,
    val sections: List<PlanSection>,
    /** item id → one line; missing = the item's own description. */
    val blurbs: Map<String, String>,
    val footer: String?,
) {
    val itemIds: List<String> get() = sections.flatMap { it.itemIds }
}
