package dev.dwhipstock.pos.aiphotos

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
) {
    companion object {
        val COPPER_LANTERN = HouseStyle(
            brand = "copper-lantern",
            shot = "professional food and drink menu photograph for a pub",
            scene = "rustic pub setting: dark, worn wooden table, warm low tungsten light with a soft " +
                "glow from the left, gentle shadows, camera at a 45-degree angle, shallow depth of field, " +
                "blurred background of brick and copper tones",
        )
        val SAGE_POPPY = HouseStyle(
            brand = "sage-poppy",
            shot = "clean e-commerce product photograph for a bottle shop",
            scene = "clean bright studio: seamless white backdrop fading to a soft sage-green, even diffused " +
                "softbox light, straight-on eye-level angle, a crisp soft shadow under the product",
        )

        /** The brand's style ([CustomerConfig.brand]); [override] replaces its scene line. */
        fun forBrand(brand: String, override: String? = null): HouseStyle {
            val base = if (brand == SAGE_POPPY.brand) SAGE_POPPY else COPPER_LANTERN
            return override?.trim()?.takeIf { it.isNotEmpty() }?.let { base.copy(scene = it) } ?: base
        }
    }
}

/** What the prompts know about the item (English text; the models read English best). */
data class ItemFacts(val name: String, val description: String, val category: String)

/**
 * The two prompt templates. Both keep the subject honest: no text, logos or
 * people, and (for an enhance) no food added, removed or swapped.
 */
object PhotoPrompts {
    private const val CLEAN = "No text, no captions, no logos or readable brand names, no watermark, " +
        "no people or hands, no cutlery clutter."

    fun generate(item: ItemFacts, style: HouseStyle): String = buildString {
        append("A ${style.shot} of ${item.name.trim()}")
        item.description.trim().takeIf { it.isNotEmpty() }?.let { append(": ").append(it.trimEnd('.')) }
        append(". Menu category: ${item.category.trim()}. ")
        append("House style, shared by every photo on this menu: ${style.scene}. ")
        append("One single serving is the only subject, centred and filling most of the frame, realistic, " +
            "appetising and true to how it is actually served. ")
        append(CLEAN)
    }

    fun enhance(item: ItemFacts, style: HouseStyle): String = buildString {
        append("Improve this real photo of ${item.name.trim()} (${item.category.trim()}) for a menu. ")
        append("Keep the actual food and drink exactly as photographed: do not add, remove, replace or " +
            "rearrange any food items, garnishes, sauces or ingredients, and keep the portion size, colours " +
            "and plating recognisable as the same dish. ")
        append("Only improve the lighting, white balance, sharpness and background, and tidy the plate or " +
            "glass edges, to match the house style: ${style.scene}. ")
        append(CLEAN)
    }
}
