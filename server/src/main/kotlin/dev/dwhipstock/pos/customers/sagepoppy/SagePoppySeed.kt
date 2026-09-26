package dev.dwhipstock.pos.customers.sagepoppy

import dev.dwhipstock.pos.base.AuthService
import dev.dwhipstock.pos.base.Categories
import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.Upc
import dev.dwhipstock.pos.base.Users
import dev.dwhipstock.pos.base.VenueSettings
import dev.dwhipstock.pos.restaurant.DiningTables
import dev.dwhipstock.pos.restaurant.Zones
import dev.dwhipstock.pos.retail.RetailService
import dev.dwhipstock.pos.sdk.Crv
import dev.dwhipstock.pos.sdk.Outbox
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Sage & Poppy's first-boot shelf: ~50 fictional products (generic names, no
 * real brands) across beer (singles, 6-packs, 12s), wine, spirits, hard
 * seltzers and coolers, mixers, snacks and ice. Prices are USD cents.
 *
 * Barcodes are real-looking UPC-A codes with valid check digits under a
 * made-up prefix in number system 4 — the range reserved for a retailer's own
 * in-store codes, which no manufacturer is ever assigned — so none can be a
 * real product's code.
 */
object SagePoppySeed {
    /** Number system 4 (in-store use) + a made-up 5-digit "manufacturer" block. */
    const val UPC_PREFIX = "487230"

    enum class Cat(val id: String, val en: String) {
        BEER("beer", "Beer & Cider"),
        WINE("wine", "Wine"),
        SPIRITS("spirits", "Spirits"),
        SELTZERS("seltzers", "Seltzers & Coolers"),
        MIXERS("mixers", "Mixers & Soda"),
        SNACKS("snacks", "Snacks"),
        ICE("ice", "Ice"),
        SUNDRIES("sundries", "Sundries"),
    }

    data class Product(
        val id: String,
        val name: String,
        val cat: Cat,
        val cents: Long,
        val seq: Int,
        val ageRestricted: Boolean,
        val crv: Crv.Size = Crv.Size.NONE,
        val pack: Int = 1,
        val taxable: Boolean = true,
        // catalog facets (SagePoppyCatalog): producer, style/varietal/type, size or pack
        val brand: String? = null,
        val subcategory: String? = null,
        val size: String? = null,
        /** Demo popularity: relative share of seeded sales (1/rank long tail). */
        val salesWeight: Int = 0,
        /** Sold by a quick key only (a paper bag, a lime): no barcode. */
        val barcodeless: Boolean = false,
    ) {
        /** UPC-A: prefix + 5-digit item number + check digit. */
        val barcode: String get() = Upc.upcA(UPC_PREFIX + seq.toString().padStart(5, '0'))
        /** What goes on the shelf: [barcode], or none for a barcodeless product. */
        val shelfCode: String? get() = if (barcodeless) null else barcode
        val abbrev: String get() = name.split(' ').filter { it.firstOrNull()?.isLetter() == true }
            .take(2).joinToString("") { it.first().uppercase() }
    }

    private fun beer(id: String, name: String, cents: Long, seq: Int, crv: Crv.Size, pack: Int) =
        Product(id, name, Cat.BEER, cents, seq, true, crv, pack)
    private fun wine(id: String, name: String, cents: Long, seq: Int) = Product(id, name, Cat.WINE, cents, seq, true)
    private fun spirit(id: String, name: String, cents: Long, seq: Int) = Product(id, name, Cat.SPIRITS, cents, seq, true)
    private fun seltzer(id: String, name: String, cents: Long, seq: Int, crv: Crv.Size, pack: Int) =
        Product(id, name, Cat.SELTZERS, cents, seq, true, crv, pack)

    val products: List<Product> = listOf(
        // beer & cider: singles, 6-packs, 12s; 5¢ per container under 24 oz, 10¢ at 24 oz+
        beer("golden-lager-can", "Golden Hour Lager 12 oz can", 199, 101, Crv.Size.SMALL, 1),
        beer("golden-lager-6", "Golden Hour Lager 6-pack 12 oz cans", 999, 102, Crv.Size.SMALL, 6),
        beer("golden-lager-12", "Golden Hour Lager 12-pack 12 oz cans", 1799, 103, Crv.Size.SMALL, 12),
        beer("hazy-ipa-can", "Hazy Hills IPA 16 oz can", 349, 104, Crv.Size.SMALL, 1),
        beer("hazy-ipa-4", "Hazy Hills IPA 4-pack 16 oz cans", 1299, 105, Crv.Size.SMALL, 4),
        beer("silver-pils-6", "Silver Lake Pilsner 6-pack 12 oz bottles", 1099, 106, Crv.Size.SMALL, 6),
        beer("coast-lager-12", "Coastline Mexican-Style Lager 12-pack", 1899, 107, Crv.Size.SMALL, 12),
        beer("night-owl-stout", "Night Owl Stout 22 oz bottle", 699, 108, Crv.Size.SMALL, 1),
        beer("canyon-amber-6", "Canyon Amber Ale 6-pack 12 oz bottles", 1049, 109, Crv.Size.SMALL, 6),
        beer("sunset-wheat-6", "Sunset Wheat Ale 6-pack 12 oz cans", 999, 110, Crv.Size.SMALL, 6),
        beer("big-pour-lager", "Big Pour Lager 25 oz can", 299, 111, Crv.Size.LARGE, 1),
        beer("backyard-light", "Backyard Light Lager 24 oz can", 249, 112, Crv.Size.LARGE, 1),
        beer("orchard-cider-4", "Orchard Dry Cider 4-pack 12 oz cans", 1099, 113, Crv.Size.SMALL, 4),
        beer("na-hop-lager-6", "Zero Proof Hop Lager 6-pack (non-alcoholic)", 999, 114, Crv.Size.SMALL, 6)
            .copy(ageRestricted = false),

        // wine (no CRV)
        wine("coastal-cab", "Coastal Ridge Cabernet Sauvignon 750 ml", 1899, 201),
        wine("valley-chard", "Valley Oak Chardonnay 750 ml", 1599, 202),
        wine("sunlit-rose", "Sunlit Rosé 750 ml", 1499, 203),
        wine("hillside-pinot", "Hillside Pinot Noir 750 ml", 2299, 204),
        wine("everyday-red", "Everyday Red Blend 1.5 L", 1799, 205),
        wine("bright-bubbles", "Bright Bubbles Sparkling Wine 750 ml", 1399, 206),
        wine("crisp-sauv", "Crisp Sauvignon Blanc 750 ml", 1299, 207),
        wine("boxed-white", "Table White Box 3 L", 2499, 208),

        // spirits (no CRV)
        spirit("coast-vodka", "Silver Coast Vodka 750 ml", 1999, 301),
        spirit("coast-vodka-big", "Silver Coast Vodka 1.75 L", 3499, 302),
        spirit("agave-blanco", "Agave Sun Blanco Tequila 750 ml", 2999, 303),
        spirit("old-barn-bourbon", "Old Barn Bourbon Whiskey 750 ml", 3299, 304),
        spirit("harbor-rum", "Harbor Spiced Rum 750 ml", 1899, 305),
        spirit("juniper-gin", "Juniper Row Dry Gin 750 ml", 2499, 306),
        spirit("highland-scotch", "Highland Blended Scotch 750 ml", 2799, 307),
        spirit("vodka-mini", "Silver Coast Vodka 50 ml", 199, 308),
        spirit("coffee-liqueur", "Night Shift Coffee Liqueur 375 ml", 1599, 309),

        // hard seltzers & coolers
        seltzer("wave-lime", "Wave Hard Seltzer Lime 12 oz can", 249, 401, Crv.Size.SMALL, 1),
        seltzer("wave-variety-12", "Wave Hard Seltzer Variety 12-pack", 1999, 402, Crv.Size.SMALL, 12),
        seltzer("wave-cherry-6", "Wave Hard Seltzer Black Cherry 6-pack", 1099, 403, Crv.Size.SMALL, 6),
        seltzer("paloma-4", "Paloma Canned Cocktail 4-pack", 1399, 404, Crv.Size.SMALL, 4),
        seltzer("marg-cooler-4", "Margarita Cooler 4-pack 11 oz bottles", 1199, 405, Crv.Size.SMALL, 4),
        seltzer("hard-tea", "Hard Iced Tea 24 oz can", 349, 406, Crv.Size.LARGE, 1),

        // mixers & soda: carbonated drinks are taxable and carry CRV; juice and mixes don't
        Product("club-soda", "Club Soda 1 L", Cat.MIXERS, 229, 501, false, Crv.Size.LARGE, 1),
        Product("tonic", "Tonic Water 1 L", Cat.MIXERS, 249, 502, false, Crv.Size.LARGE, 1),
        Product("ginger-beer-4", "Ginger Beer 4-pack 12 oz bottles", Cat.MIXERS, 699, 503, false, Crv.Size.SMALL, 4),
        Product("cola-2l", "Cola 2 L", Cat.MIXERS, 299, 504, false, Crv.Size.LARGE, 1),
        Product("sparkling-12", "Sparkling Water 12-pack 12 oz cans", Cat.MIXERS, 699, 505, false, Crv.Size.SMALL, 12),
        Product("lime-juice", "Lime Juice 16 oz", Cat.MIXERS, 399, 506, false, taxable = false),
        Product("marg-mix", "Margarita Mix 1 L", Cat.MIXERS, 499, 507, false, taxable = false),
        Product("bloody-mix", "Bloody Mary Mix 32 oz", Cat.MIXERS, 549, 508, false, taxable = false),

        // snacks: food, not taxed (party cups are taxable goods)
        Product("chips-sea-salt", "Sea Salt Potato Chips 8 oz", Cat.SNACKS, 449, 601, false, taxable = false),
        Product("tortilla-chips", "Tortilla Chips 13 oz", Cat.SNACKS, 499, 602, false, taxable = false),
        Product("peanuts", "Salted Peanuts 6 oz", Cat.SNACKS, 349, 603, false, taxable = false),
        Product("jerky", "Beef Jerky 3 oz", Cat.SNACKS, 799, 604, false, taxable = false),
        Product("pretzels", "Pretzel Twists 10 oz", Cat.SNACKS, 399, 605, false, taxable = false),
        Product("choc-bar", "Dark Chocolate Bar", Cat.SNACKS, 199, 606, false, taxable = false),
        Product("party-cups", "Party Cups 50-count", Cat.SNACKS, 399, 607, false),

        // ice: not taxed
        Product("ice-7", "Party Ice 7 lb bag", Cat.ICE, 349, 701, false, taxable = false),
        Product("ice-20", "Party Ice 20 lb bag", Cat.ICE, 699, 702, false, taxable = false),
    )

    /** Batch-insert [list] as items + their one "Each" price (inside a transaction). */
    fun insertProducts(list: List<Product>) {
        Items.batchInsert(list, shouldReturnGeneratedValues = false) { p ->
            this[Items.id] = p.id
            this[Items.nameFr] = p.name
            this[Items.nameEn] = p.name
            this[Items.categoryId] = p.cat.id
            this[Items.abbrev] = p.abbrev.ifEmpty { "?" }
            this[Items.isAlcohol] = p.ageRestricted
            this[Items.ageRestricted] = p.ageRestricted
            this[Items.taxable] = p.taxable
            this[Items.crvSize] = p.crv.name
            this[Items.packUnits] = p.pack
            this[Items.barcode] = p.shelfCode
            this[Items.brand] = p.brand
            this[Items.subcategory] = p.subcategory
            this[Items.sizeLabel] = p.size
            this[Items.salesWeight] = p.salesWeight
        }
        ItemVariants.batchInsert(list, shouldReturnGeneratedValues = false) { p ->
            this[ItemVariants.id] = "${p.id}:each"
            this[ItemVariants.itemId] = p.id
            this[ItemVariants.labelFr] = "Each"
            this[ItemVariants.labelEn] = "Each"
            this[ItemVariants.priceCents] = p.cents
            this[ItemVariants.sortOrder] = 0
        }
    }

    /** Empty-mode stores (POS_SEED=none): one manager so the owner can sign in and set up. */
    fun seedBootstrapManagerIfNoStaff() = transaction {
        if (Users.selectAll().count() > 0) return@transaction
        Users.insert { it[id] = "manager"; it[name] = "Manager"; it[role] = "MANAGER"; it[pin] = AuthService.hashPin("1234"); it[languageCode] = "en" }
    }

    /**
     * First boot only (no staff yet). The pub rows the early migrations insert
     * for every store (their menu, floor plan, venue settings and the outbox
     * echoes) are cleared first: this store has never synced, and none of it is
     * Sage & Poppy's.
     */
    fun seedIfEmpty() = transaction {
        if (Users.selectAll().count() > 0) return@transaction
        exec("DELETE FROM item_variants")
        exec("DELETE FROM items")
        exec("DELETE FROM categories")
        exec("DELETE FROM floor_objects")
        exec("DELETE FROM dining_tables")
        exec("DELETE FROM zones")
        exec("DELETE FROM sync_outbox")
        VenueSettings.update({ VenueSettings.id eq 1 }) {
            it[cardProcessor] = "External card terminal"
            it[bankName] = ""
            it[bankAccountNumber] = ""
            it[bankAccountName] = ""
            it[serviceChargePercent] = 0
            it[corkagePerBottleCents] = 0
            it[receiptFooter] = "Thank you! · ¡Gracias! · 21+ for alcohol / 21+ para alcohol"
            it[venuePhone] = SagePoppy.PHONE
            it[venueAddress] = SagePoppy.ADDRESS
        }
        Cat.entries.forEachIndexed { i, c ->
            Categories.insert { it[id] = c.id; it[sortOrder] = i; it[nameFr] = c.en; it[nameEn] = c.en }
        }
        // the full ~5,000-product shelf (the hand-written ones included)
        insertProducts(SagePoppyCatalog.products)
        SagePoppyCatalogUpgrade.markCurrent()
        // one register, as a check's "table" (no floor plan in a shop)
        Zones.insert {
            it[id] = RetailService.COUNTER_ZONE; it[nameFr] = "Comptoir"; it[nameEn] = "Counter"
            it[sortOrder] = 0; it[labelPrefix] = "R"
        }
        DiningTables.insert {
            it[id] = RetailService.REGISTER_TABLE; it[zoneId] = RetailService.COUNTER_ZONE; it[label] = "1"
            it[shape] = "SQUARE"; it[seats] = 0
        }
        Users.insert { it[id] = "manager"; it[name] = "Demo Manager"; it[role] = "MANAGER"; it[pin] = AuthService.hashPin("1234"); it[languageCode] = "en" }
        Users.insert { it[id] = "cashier"; it[name] = "Demo Cashier"; it[role] = "SERVER"; it[pin] = AuthService.hashPin("9999"); it[languageCode] = "en" }
        Users.insert { it[id] = "cajera"; it[name] = "Cajera Demo"; it[role] = "SERVER"; it[pin] = AuthService.hashPin("5555"); it[languageCode] = "es" }
        Outbox.write("catalog.seeded", "catalog", "sage-poppy", buildJsonObject {
            put("items", SagePoppyCatalog.products.size)
            put("categories", Cat.entries.size)
        })
    }
}
