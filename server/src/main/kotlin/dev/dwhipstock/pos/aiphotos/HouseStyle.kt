package dev.dwhipstock.pos.aiphotos

import java.text.Normalizer

/**
 * A client's photo "shoot": the surface, light, angle and background every
 * picture on its menu shares, so generated and enhanced photos look like one
 * session rather than a pile of stock images. One per brand; a store can
 * replace the scene line with `image.style` in its config.
 */
data class HouseStyle(
    val brand: String,
    /** What kind of picture this is ("menu photograph", "product shot"). */
    val shot: String,
    /** Surface, lighting, angle, background. */
    val scene: String,
    /**
     * Words taken out of an item's name before it goes in a prompt: the house
     * brand and the place names its menu uses. An image model paints a branded
     * name onto the glass or plate as garbled lettering, however firmly it is
     * told not to, so a prompt only ever says what the thing is.
     */
    val nameWordsToDrop: List<String> = emptyList(),
) {
    companion object {
        val COPPER_LANTERN = HouseStyle(
            brand = "copper-lantern",
            shot = "professional food and drink menu photograph for a pub",
            scene = "a pub table of natural medium-toned wood by a large window: soft natural daylight, " +
                "neutral white balance, true-to-life colours with clean whites, no orange or amber colour cast, " +
                "no heavy vignette, moderate contrast, camera at a 45-degree angle, gentle depth of field, " +
                "softly blurred pub interior in the background, looks like an unretouched photo taken by a " +
                "professional food photographer with a DSLR",
            nameWordsToDrop = listOf(
                "Copper", "Lantern", "Montreal", "Quebec", "Eastern", "Townships", "Monteregie", "Plateau",
                "North", "Trail",
            ),
        )
        val SAGE_POPPY = HouseStyle(
            brand = "sage-poppy",
            shot = "clean e-commerce product photograph for a bottle shop",
            scene = "clean bright studio: seamless white backdrop fading to a very pale sage-green, even diffused " +
                "softbox light, neutral white balance, true-to-life colours with clean whites, moderate contrast, " +
                "straight-on eye-level angle, a crisp soft shadow under the product, looks like an unretouched " +
                "catalogue photo",
            nameWordsToDrop = listOf("Sage", "Poppy"),
        )

        /** The brand's style ([CustomerConfig.brand]); [override] replaces its scene line. */
        fun forBrand(brand: String, override: String? = null): HouseStyle {
            val base = if (brand == SAGE_POPPY.brand) SAGE_POPPY else COPPER_LANTERN
            return override?.trim()?.takeIf { it.isNotEmpty() }?.let { base.copy(scene = it) } ?: base
        }
    }
}

/**
 * What the prompts know about the item (English text; the models read English
 * best). [brand] and [subcategory] are a retail shelf's facets (null on a pub).
 */
data class ItemFacts(
    val name: String,
    val description: String,
    val category: String,
    val brand: String? = null,
    val subcategory: String? = null,
)

/**
 * The two prompt templates. Both keep the subject honest: no text, logos or
 * people, and (for an enhance) no food added, removed or swapped.
 *
 * The item's name never goes in as a name: a model asked for "a photo of
 * Lantern House Lager" prints those words on the glass. The subject is the
 * description, led by a plain "what it is" phrase (the name without brand or
 * place words, plus a generic noun from the category) when the description
 * does not say what the thing is ("Beef, cheddar, bacon..." needs "burger").
 */
object PhotoPrompts {
    private const val CLEAN = "Plain, unbranded glassware, bottles and plates with no printing, labels or " +
        "engraving. No text, no captions, no logos or readable brand names, no watermark, " +
        "no people or hands, no cutlery clutter."

    fun generate(item: ItemFacts, style: HouseStyle): String = buildString {
        append("A ${style.shot}. The subject: ${subject(item, style)}. ")
        append("Menu category: ${categoryLine(item)}. ")
        append("House style, shared by every photo on this menu: ${style.scene}. ")
        append("One single serving is the only subject, centred and filling most of the frame, realistic, " +
            "appetising and true to how it is actually served. ")
        append(CLEAN)
    }

    fun enhance(item: ItemFacts, style: HouseStyle): String = buildString {
        append("Improve this real photo of ${whatItIs(item, style)} (${categoryLine(item)}) for a menu. ")
        append("Keep the actual food and drink exactly as photographed: do not add, remove, replace or " +
            "rearrange any food items, garnishes, sauces or ingredients, and keep the portion size, colours " +
            "and plating recognisable as the same dish. ")
        append("Only improve the lighting, white balance, sharpness and background, and tidy the plate or " +
            "glass edges, to match the house style: ${style.scene}. ")
        append(CLEAN)
    }

    /** The description, led by [whatItIs] when it does not name the thing itself. */
    internal fun subject(item: ItemFacts, style: HouseStyle): String {
        val desc = item.description.trim().trimEnd('.').trim()
        if (desc.isEmpty()) return whatItIs(item, style)
        val head = plainName(item, style).lastOrNull()
        if (head != null && mentions(desc, head)) return desc
        return "${whatItIs(item, style)}. $desc"
    }

    /** "burger", "pinot noir wine", "old fashioned cocktail", "lager 6-pack 12 oz cans". */
    internal fun whatItIs(item: ItemFacts, style: HouseStyle): String {
        val words = plainName(item, style).toMutableList()
        val sub = item.subcategory?.trim()?.takeIf { it.isNotEmpty() }
        val noun = sub?.lowercase() ?: categoryNoun(item.category)
        val said = words.joinToString(" ") + " " + item.description
        // only when nothing already says it ("wheat ale" is enough for a Wheat Beer)
        if (noun != null && noun.split(WS).none { mentions(said, it) }) words += noun
        return words.joinToString(" ").ifEmpty { item.category.trim().lowercase() }
    }

    private fun categoryLine(item: ItemFacts): String {
        val sub = item.subcategory?.trim()?.takeIf { it.isNotEmpty() }
        return item.category.trim() + (sub?.let { " ($it)" } ?: "")
    }

    /**
     * The name's words minus the item's brand and the house's brand/place words, except words of
     * the subcategory ("Hazy" in "Hazy Hills IPA", a Hazy IPA); all-caps words kept as they are (IPA).
     */
    private fun plainName(item: ItemFacts, style: HouseStyle): List<String> {
        val keep = item.subcategory?.split(WS)?.map(::fold)?.toSet() ?: emptySet()
        val drop = (style.nameWordsToDrop + (item.brand?.split(WS) ?: emptyList())).map(::fold).toSet() - keep
        return item.name.trim().split(WS)
            .filter { it.isNotEmpty() && fold(it) !in drop }
            .map { if (it.any(Char::isLetter) && it == it.uppercase()) it else it.lowercase() }
    }

    /**
     * A one-word category's singular when it names a kind of thing ("Cocktails" -> "cocktail",
     * "Wine"); null for "Beer & Cider" and for a course ("Starters" says nothing about what it is).
     */
    private fun categoryNoun(category: String): String? {
        val c = category.trim().lowercase()
        if (c.isEmpty() || WS.containsMatchIn(c) || c.contains('&') || c.contains(',')) return null
        val noun = if (c.length > 3 && c.endsWith("s") && !c.endsWith("ss")) c.dropLast(1) else c
        return noun.takeIf { it !in COURSES }
    }

    private val COURSES = setOf("starter", "appetizer", "appetiser", "main", "entree", "side", "special",
        "snack", "other", "extra")

    /** Does [text] use [word] (or its plural or singular), ignoring case and accents? */
    private fun mentions(text: String, word: String): Boolean {
        val w = fold(word)
        if (w.isEmpty()) return true
        val stem = if (w.length > 3 && w.endsWith("s")) w.dropLast(1) else w
        return Regex("(^|[^a-z0-9])" + Regex.escape(stem)).containsMatchIn(fold(text))
    }

    /** Lowercase, accents off, surrounding punctuation off: "Montréal," -> "montreal". */
    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase()
            .trim { !it.isLetterOrDigit() }

    private val WS = Regex("\\s+")
    private val MARKS = Regex("\\p{M}+")
}
