package dev.dwhipstock.pos.db

import dev.dwhipstock.pos.sdk.Outbox
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.IColumnType
import org.jetbrains.exposed.sql.LongColumnType
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.Transaction

/**
 * Migration 037 (code: it writes outbox events): lower the Copper Lantern demo
 * menu prices by about 10%, now that GST and QST are added on top (036). Stores
 * own their catalog (one-way sync), so live databases are updated here; a new
 * database is seeded with the new prices directly (CopperLanternSeed).
 *
 * Only a variant whose price still equals its old seeded value changes — a
 * price a manager edited on the tablet is never overwritten, and an owner-built
 * menu (other ids) is untouched. Re-running finds nothing at the old prices.
 * Open checks keep the prices they were rung at (lines capture unit prices).
 *
 * Each change is a normal item.variant_updated event with the full item
 * snapshot, so the cloud portal's menu mirrors it. Raw SQL and the frozen
 * snapshot shape of 034 on purpose.
 */
object MenuPriceMigration {
    const val VERSION = 37
    const val NAME = "037_menu_prices_before_tax (code)"

    /** variant id → (old seeded cents, new cents). */
    val PRICES: Map<String, Pair<Long, Long>> = linkedMapOf(
        "lantern-lager:pint" to (825L to 750L),
        "lantern-lager:pitcher" to (2250L to 2025L),
        "amber-ale:pint" to (875L to 795L),
        "amber-ale:pitcher" to (2400L to 2150L),
        "north-ipa:pint" to (925L to 825L),
        "north-ipa:pitcher" to (2550L to 2295L),
        "maple-stout:pint" to (950L to 850L),
        "maple-stout:pitcher" to (2650L to 2375L),
        "wheat-beer:pint" to (850L to 775L),
        "wheat-beer:pitcher" to (2350L to 2125L),
        "canadian-lager:regular" to (725L to 650L),
        "pilsner-can:regular" to (775L to 695L),
        "porter-can:regular" to (875L to 795L),
        "hazy-ipa:regular" to (925L to 825L),
        "saison:regular" to (900L to 800L),
        "belgian-blonde:regular" to (975L to 875L),
        "irish-stout:regular" to (950L to 850L),
        "mexican-lager:regular" to (875L to 795L),
        "dry-cider:regular" to (875L to 795L),
        "berry-cider:regular" to (900L to 800L),
        "na-lager:regular" to (650L to 575L),
        "hop-water:regular" to (550L to 495L),
        "pinot-noir:glass" to (1250L to 1125L),
        "pinot-noir:bottle" to (4800L to 4325L),
        "cab-merlot:glass" to (1150L to 1025L),
        "cab-merlot:bottle" to (4400L to 3950L),
        "malbec:glass" to (1300L to 1175L),
        "malbec:bottle" to (5000L to 4500L),
        "riesling:glass" to (1100L to 995L),
        "riesling:bottle" to (4200L to 3775L),
        "chardonnay:glass" to (1200L to 1075L),
        "chardonnay:bottle" to (4600L to 4150L),
        "sauvignon-blanc:glass" to (1250L to 1125L),
        "sauvignon-blanc:bottle" to (4800L to 4325L),
        "rose:glass" to (1150L to 1025L),
        "rose:bottle" to (4400L to 3950L),
        "sparkling:glass" to (1400L to 1250L),
        "sparkling:bottle" to (5400L to 4850L),
        "icewine:2oz" to (1650L to 1475L),
        "icewine:375ml" to (7200L to 6475L),
        "copper-old-fashioned:regular" to (1550L to 1395L),
        "lantern-mule:regular" to (1450L to 1300L),
        "smoked-caesar:regular" to (1450L to 1300L),
        "maple-sour:regular" to (1500L to 1350L),
        "elderflower-gin:regular" to (1500L to 1350L),
        "espresso-martini:regular" to (1550L to 1395L),
        "dark-stormy:regular" to (1450L to 1300L),
        "zero-gimlet:regular" to (950L to 850L),
        "pretzel:regular" to (1350L to 1225L),
        "wings:regular" to (1850L to 1675L),
        "nachos:regular" to (1950L to 1750L),
        "calamari:regular" to (1850L to 1675L),
        "spinach-dip:regular" to (1650L to 1475L),
        "poutine:regular" to (1450L to 1300L),
        "lantern-burger:regular" to (2150L to 1925L),
        "mushroom-burger:regular" to (2200L to 1975L),
        "veggie-burger:regular" to (1950L to 1750L),
        "club:regular" to (2050L to 1850L),
        "reuben:regular" to (2150L to 1925L),
        "fish-sandwich:regular" to (1950L to 1750L),
        "fish-chips:regular" to (2250L to 2025L),
        "steak-frites:regular" to (2950L to 2650L),
        "shepherd-pie:regular" to (2150L to 1925L),
        "mac-cheese:regular" to (1850L to 1675L),
        "salmon:regular" to (2750L to 2475L),
        "chicken-pot-pie:regular" to (2050L to 1850L),
        "caesar-salad:regular" to (1450L to 1300L),
        "harvest-salad:regular" to (1650L to 1475L),
        "falafel-bowl:regular" to (1850L to 1675L),
        "cauliflower:regular" to (1950L to 1750L),
        "sticky-pudding:regular" to (950L to 850L),
        "cheesecake:regular" to (950L to 850L),
        "brownie:regular" to (900L to 800L),
        "late-fries:regular" to (850L to 775L),
        "mini-burgers:regular" to (1450L to 1300L),
        "grilled-cheese:regular" to (1150L to 1025L),
        "onion-rings:regular" to (1050L to 950L),
        "salmon-maki:regular" to (1150L to 1025L),
        "spicy-tuna-maki:regular" to (1250L to 1125L),
        "avocado-maki:regular" to (950L to 850L),
        "salmon-nigiri:regular" to (850L to 775L),
        "tuna-nigiri:regular" to (950L to 850L),
        "scallop-nigiri:regular" to (1050L to 950L),
        "junmai-sake:carafe" to (1400L to 1250L),
        "junmai-sake:bottle" to (5200L to 4675L),
        "sparkling-sake:regular" to (1800L to 1625L),
        "smoked-meat-poutine:regular" to (1850L to 1675L),
        "maple-miso-bowl:regular" to (2250L to 2025L),
        "bagel-board:regular" to (1650L to 1475L),
        "yuzu-sour:regular" to (1550L to 1395L),
    )

    fun run(tx: Transaction) { reprice(tx) }

    /** Returns the variant ids that changed. */
    fun reprice(tx: Transaction): List<String> {
        val changed = mutableListOf<Pair<String, String>>() // (item id, variant id)
        for ((variantId, prices) in PRICES) {
            val (old, new) = prices
            var itemId: String? = null
            tx.exec("SELECT item_id FROM item_variants WHERE id = ? AND price_cents = ?", listOf(text(variantId), long(old))) { rs ->
                if (rs.next()) itemId = rs.getString(1)
            }
            val item = itemId ?: continue
            tx.exec("UPDATE item_variants SET price_cents = ? WHERE id = ?", listOf(long(new), text(variantId)))
            changed += item to variantId
        }
        for ((itemId, variantId) in changed) {
            Outbox.write("item.variant_updated", "item", itemId, buildJsonObject {
                put("itemId", itemId)
                put("variantId", variantId)
                put("priceCents", PRICES.getValue(variantId).second)
                put("item", MenuCategoryMigration.itemJson(tx, itemId))
            })
        }
        return changed.map { it.second }
    }

    private fun text(v: String): Pair<IColumnType<*>, Any?> = TextColumnType() to v
    private fun long(v: Long): Pair<IColumnType<*>, Any?> = LongColumnType() to v
}
