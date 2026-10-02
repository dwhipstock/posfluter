package dev.dwhipstock.pos.customers.copperlantern

import dev.dwhipstock.pos.api.CatalogOps
import dev.dwhipstock.pos.api.ItemCreateRequest
import dev.dwhipstock.pos.api.ItemPatchRequest
import dev.dwhipstock.pos.api.VariantCreateRequest
import dev.dwhipstock.pos.base.Categories
import dev.dwhipstock.pos.base.ItemSchedules
import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.Translations
import dev.dwhipstock.pos.db.SyncState
import dev.dwhipstock.pos.sdk.MenuSpecials
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * The demo's menu specials (fictional), added once to a seeded Copper Lantern
 * store, through the menu's own code so they reach the portal like a
 * manager's edit:
 *
 * - the pubs: Prime Rib on Fridays and Saturdays, a Sunday Roast on Sundays,
 *   the Copper Lantern Burger at $14.95 on Tuesdays, and happy hour Monday to
 *   Friday 4–6 pm (lager pints $5.00, Cabernet Merlot glasses $7.00);
 * - Express: the Double Cheeseburger at $9.95 on Tuesdays, and the same happy
 *   hour (lager $5.00, Pinot Noir glasses $7.50).
 *
 * Happy hour has no name of its own, so every screen and receipt calls it
 * "Happy hour" in the reader's language. A dish or special a manager already
 * set is never touched, and it runs once per store ([SEEDED_KEY]).
 */
object CopperLanternSpecials {
    const val SEEDED_KEY = "menu_specials_seeded_v1"

    private val WEEKDAYS = listOf("mon", "tue", "wed", "thu", "fri")
    private const val HAPPY_FROM = "16:00"
    private const val HAPPY_TO = "18:00"

    private data class NewDish(
        val id: String, val category: String, val abbrev: String,
        val fr: String, val en: String, val es: String, val de: String, val af: String,
        val dfr: String, val den: String, val cents: Long, val days: List<String>,
    )

    private val pubDishes = listOf(
        NewDish("prime-rib", "mains-salads", "PR", "Côte de bœuf rôtie", "Prime Rib", "Costilla de res asada", "Prime Rib", "Ribbetjie-braaistuk",
            "Côte de bœuf rôtie lentement, jus, purée et raifort.", "Slow-roasted prime rib, au jus, mash and horseradish.",
            3495, listOf("fri", "sat")),
        NewDish("sunday-roast", "mains-salads", "SR", "Rôti du dimanche", "Sunday Roast", "Asado del domingo", "Sonntagsbraten", "Sondagbraaivleis",
            "Bœuf rôti, Yorkshire pudding, légumes rôtis et sauce.", "Roast beef, Yorkshire pudding, roast vegetables and gravy.",
            2495, listOf("sun")),
    )

    /** [enabled]: `POS_DEMO_SPECIALS=off` leaves them out (the test suite: its totals don't follow the clock). */
    fun seed(venue: CopperLanternVenue, enabled: Boolean = System.getenv("POS_DEMO_SPECIALS") != "off") = transaction {
        if (!enabled || !ItemSchedules.present() || SyncState.get(SEEDED_KEY) != null) return@transaction
        // a store whose menu isn't the demo's (empty, or the owner's own) gets nothing
        if (Items.selectAll().where { Items.id eq "lantern-lager" }.empty()) return@transaction
        if (venue.quickServe) {
            special("double-cheeseburger", listOf("tue"), null, null, mapOf("double-cheeseburger:regular" to 995L))
            special("lantern-lager", WEEKDAYS, HAPPY_FROM, HAPPY_TO, mapOf("lantern-lager:16oz" to 500L))
            special("pinot-noir", WEEKDAYS, HAPPY_FROM, HAPPY_TO, mapOf("pinot-noir:glass" to 750L))
        } else {
            pubDishes.forEach(::dish)
            special("lantern-burger", listOf("tue"), null, null, mapOf("lantern-burger:regular" to 1495L))
            special("lantern-lager", WEEKDAYS, HAPPY_FROM, HAPPY_TO, mapOf("lantern-lager:pint" to 500L))
            special("cab-merlot", WEEKDAYS, HAPPY_FROM, HAPPY_TO, mapOf("cab-merlot:glass" to 700L))
        }
        SyncState.set(SEEDED_KEY, "1")
    }

    private fun live(id: String) = Items.selectAll().where { Items.id eq id }.firstOrNull()?.takeIf { it[Items.deletedAt] == null }

    /** One more special on [itemId], where the store has that dish and those sizes and no specials of its own yet. */
    private fun special(itemId: String, days: List<String>, from: String?, to: String?, prices: Map<String, Long>) {
        live(itemId) ?: return
        val sizes = ItemVariants.selectAll().where { ItemVariants.itemId eq itemId }
            .filter { it[ItemVariants.deletedAt] == null }.map { it[ItemVariants.id] }.toSet()
        val p = prices.filterKeys { it in sizes }.takeIf { it.isNotEmpty() } ?: return
        if (ItemSchedules.of(itemId).specials.isNotEmpty()) return
        CatalogOps.patchItem(itemId, ItemPatchRequest(specials = listOf(MenuSpecials.Special(days, from, to, p))))
    }

    /** A day-only dish, created when the store doesn't have it (and its category is there). */
    private fun dish(d: NewDish) {
        if (Items.selectAll().where { Items.id eq d.id }.any()) return
        if (Categories.selectAll().where { Categories.id eq d.category }.empty()) return
        CatalogOps.createItem(ItemCreateRequest(
            nameFr = d.fr, nameEn = d.en, descriptionFr = d.dfr, descriptionEn = d.den,
            categoryId = d.category, abbrev = d.abbrev, isAlcohol = false,
            variants = listOf(VariantCreateRequest("Standard", "Regular", d.cents)),
        ), fixedId = d.id, variantIds = listOf("${d.id}:regular"))
        Translations.set(Translations.ITEM, d.id, "es", d.es, sync = false)
        Translations.set(Translations.ITEM, d.id, "de", d.de, sync = false)
        Translations.set(Translations.ITEM, d.id, "af", d.af, sync = false)
        // the days go up with the names in one snapshot
        CatalogOps.patchItem(d.id, ItemPatchRequest(availableDays = d.days))
    }
}
