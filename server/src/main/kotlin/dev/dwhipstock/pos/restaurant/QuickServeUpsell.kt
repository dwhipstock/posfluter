package dev.dwhipstock.pos.restaurant

import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.retail.QuickKeys
import dev.dwhipstock.pos.sdk.UpsellConfig
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Clock

@Serializable
data class KioskUpsellRequest(val lines: List<KioskOrderLine> = emptyList())

/** One row of the kiosk's "Add a drink?" step: why ("drink", "side", "dessert"), and what, best first. */
@Serializable
data class UpsellRow(val reason: String, val categoryId: String, val itemIds: List<String>)

@Serializable
data class KioskUpsell(val rows: List<UpsellRow>)

/**
 * The kiosk's upsell rules, on the store (the kiosk only shows the rows):
 * an order with a main and no drink gets soft drinks, no side gets fries,
 * no dessert gets desserts — at most [UpsellConfig.maxRows] rows, in the
 * config's order. Only live items on sale are offered, never alcohol, best
 * sellers of the last [WINDOW_DAYS] days first, then the menu's order.
 */
class QuickServeUpsell(
    private val config: () -> UpsellConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    companion object {
        const val WINDOW_DAYS = 7
    }

    fun suggest(lines: List<KioskOrderLine>): KioskUpsell = transaction {
        val cfg = config()
        if (cfg.rules.isEmpty() || lines.isEmpty()) return@transaction KioskUpsell(emptyList())
        val ids = lines.map { it.itemId }.toSet()
        val inOrder = Items.selectAll().where { Items.id inList ids }.map { it[Items.categoryId] }.toSet()
        if (inOrder.none { it in cfg.mains }) return@transaction KioskUpsell(emptyList())

        val wanted = cfg.rules.filter { r -> r.satisfiedBy.none { it in inOrder } }
        if (wanted.isEmpty()) return@transaction KioskUpsell(emptyList())
        val categories = wanted.flatMap { it.offer }.toSet()
        val sellable = ItemVariants.selectAll().where { ItemVariants.deletedAt.isNull() }
            .map { it[ItemVariants.itemId] }.toSet()
        // menu order is the order the items were added (the seed's order)
        val offered = Items.selectAll().where {
            (Items.categoryId inList categories) and (Items.active eq true) and Items.deletedAt.isNull() and
                (Items.isAlcohol eq false)
        }.filter { !it[Items.ageRestricted] && it[Items.id] in sellable && it[Items.id] !in ids }
            .map { it[Items.id] to it[Items.categoryId] }
        val sold = QuickKeys(clock).unitsSold(WINDOW_DAYS)
        val rows = wanted.mapNotNull { r ->
            val items = offered.filter { it.second in r.offer }
                .withIndex()
                .sortedWith(compareByDescending<IndexedValue<Pair<String, String>>> { sold[it.value.first] ?: 0L }.thenBy { it.index })
                .map { it.value.first }
                .take(cfg.maxItems.coerceAtLeast(1))
            if (items.isEmpty()) null else UpsellRow(r.reason, r.offer.first(), items)
        }.take(cfg.maxRows.coerceAtLeast(0))
        KioskUpsell(rows)
    }
}
