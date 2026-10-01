package dev.dwhipstock.pos.restaurant

import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.Translations
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.selectAll

/**
 * One line a menu change refused (two-way menu sync: an item can be deleted,
 * 86'd or repriced in the manager portal while a guest's cart, a kiosk order
 * or a staff phone still shows it). [index] is the line's position in the
 * request. [code]: `item_unavailable` (deleted, off sale, or that size
 * deleted) or `price_changed` ([priceCents] is the price now — the client
 * asks the guest to confirm and sends it again with that expected price).
 * The names let the client say which line, in the guest's language.
 */
@Serializable
data class RejectedLine(
    val index: Int, val itemId: String, val variantId: String, val code: String,
    val priceCents: Long? = null,
    val nameEn: String = "", val nameFr: String = "", val names: Map<String, String> = emptyMap(),
)

/**
 * Lines refused because the menu changed: 409 with `rejected` (and, for one
 * line, its own code and `priceCents`). [code] is `item_unavailable`,
 * `price_changed`, or `lines_rejected` when every line of a basket was.
 */
class LineRejectedException(val rejected: List<RejectedLine>, val code: String) :
    RuntimeException(when (code) {
        "price_changed" -> "the price changed; confirm the new price"
        "lines_rejected" -> "nothing in this order is available as shown"
        else -> "no longer available"
    }) {
    companion object {
        fun single(r: RejectedLine) = LineRejectedException(listOf(r), r.code)
    }
}

/** The store's check before a menu line is added: the store is the source of truth for orders. */
object MenuGuard {
    /**
     * Null = the line may be added at [expectedPriceCents] (or at the current
     * price when the client sent none). An item / size that never existed is
     * a 404 (a bad request, not a menu change). Inside a transaction.
     */
    fun check(index: Int, itemId: String, variantId: String, expectedPriceCents: Long?): RejectedLine? {
        val item = Items.selectAll().where { Items.id eq itemId }.firstOrNull()
        val variant = ItemVariants.selectAll().where { ItemVariants.id eq variantId }.firstOrNull()
            ?.takeIf { it[ItemVariants.itemId] == itemId }
        if (item == null || variant == null) throw NotFoundException("variant $variantId of item $itemId not found")
        fun rejected(code: String, price: Long? = null) = RejectedLine(
            index, itemId, variantId, code, price, item[Items.nameEn], item[Items.nameFr],
            if (Translations.present()) Translations.namesOf(Translations.ITEM, itemId) else emptyMap(),
        )
        if (item[Items.deletedAt] != null || !item[Items.active] || variant[ItemVariants.deletedAt] != null)
            return rejected("item_unavailable")
        val price = variant[ItemVariants.priceCents]
        if (expectedPriceCents != null && expectedPriceCents != price) return rejected("price_changed", price)
        return null
    }

    /** [check], throwing the single-line 409. */
    fun require(itemId: String, variantId: String, expectedPriceCents: Long?) {
        check(0, itemId, variantId, expectedPriceCents)?.let { throw LineRejectedException.single(it) }
    }
}
