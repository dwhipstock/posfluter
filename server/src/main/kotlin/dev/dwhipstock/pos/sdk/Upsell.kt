package dev.dwhipstock.pos.sdk

/**
 * The kiosk's "Add a drink?" step, by menu category (quick-serve): when an
 * order has a main from [mains], each rule whose [UpsellRule.satisfiedBy]
 * categories are all missing from the order offers items from its
 * [UpsellRule.offer] categories. Rules are tried in order and at most
 * [maxRows] are shown, each with at most [maxItems] items.
 *
 * Alcohol is never offered, whatever the categories say. A brand whose
 * category ids differ gives its own config ([CustomerConfig.upsell]).
 */
data class UpsellConfig(
    val mains: Set<String>,
    val rules: List<UpsellRule>,
    val maxRows: Int = 2,
    val maxItems: Int = 4,
) {
    companion object {
        /** Off: no suggestions (the pubs, the shops). */
        val NONE = UpsellConfig(emptySet(), emptyList())

        /** A burger counter's usual ids: a drink first, then fries, then a dessert. */
        val QUICK_SERVE_DEFAULT = UpsellConfig(
            mains = setOf("burgers", "chicken", "salads"),
            rules = listOf(
                // a beer or a glass of wine counts as the drink (never offered, though)
                UpsellRule("drink", offer = listOf("soft-drinks"), satisfiedBy = listOf("soft-drinks", "beer-wine")),
                UpsellRule("side", offer = listOf("fries-sides")),
                UpsellRule("dessert", offer = listOf("desserts")),
            ),
        )
    }
}

/**
 * One suggestion row: [reason] is what the kiosk titles it by ("drink" →
 * *Add a drink?*, "side" → *Add fries?*, "dessert" → *Something sweet?*).
 */
data class UpsellRule(
    val reason: String,
    val offer: List<String>,
    val satisfiedBy: List<String> = offer,
)
