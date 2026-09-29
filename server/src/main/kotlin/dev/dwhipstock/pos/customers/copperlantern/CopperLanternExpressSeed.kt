package dev.dwhipstock.pos.customers.copperlantern

import dev.dwhipstock.pos.base.AuthService
import dev.dwhipstock.pos.base.Categories
import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.Translations
import dev.dwhipstock.pos.base.Users
import dev.dwhipstock.pos.base.VenueSettings
import dev.dwhipstock.pos.sdk.Outbox
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Copper Lantern Express, the quick-serve counter (fictional, Montréal): a
 * short counter menu — burgers, chicken, fries and sides, salads, desserts,
 * soft drinks, and beer and wine only (no cocktails). CAD cents, pre-tax like
 * the pubs. French and English on the rows, Spanish and German in the
 * translations table.
 *
 * Dishes the pubs also serve keep the pub's item id (lantern-burger, poutine,
 * late-fries…), so `scripts/ai-menu-photos.py --copy-to` gives them the pub's
 * photos as they are; the new ones (tenders, soft drinks…) start with none.
 */
object CopperLanternExpressSeed {
    /** A size or option: key, then French, English, Spanish, German, and the price. */
    private data class V(val key: String, val fr: String, val en: String, val es: String, val de: String, val cents: Long)
    private data class E(
        val id: String, val category: String, val abbrev: String, val alcohol: Boolean,
        val fr: String, val en: String, val es: String, val de: String,
        val dfr: String, val den: String,
        val variants: List<V>,
    )

    private fun one(cents: Long) = listOf(V("regular", "Standard", "Regular", "Normal", "Normal", cents))
    private fun sml(s: Long, m: Long, l: Long) = listOf(
        V("small", "Petit", "Small", "Pequeño", "Klein", s),
        V("medium", "Moyen", "Medium", "Mediano", "Mittel", m),
        V("large", "Grand", "Large", "Grande", "Groß", l),
    )

    /** id, French, English, Spanish, German. */
    val categories: List<Array<String>> = listOf(
        arrayOf("burgers", "Burgers", "Burgers", "Hamburguesas", "Burger"),
        arrayOf("chicken", "Poulet", "Chicken", "Pollo", "Hähnchen"),
        arrayOf("fries-sides", "Frites et accompagnements", "Fries & Sides", "Papas y acompañamientos", "Pommes & Beilagen"),
        arrayOf("salads", "Salades", "Salads", "Ensaladas", "Salate"),
        arrayOf("desserts", "Desserts", "Desserts", "Postres", "Desserts"),
        arrayOf("soft-drinks", "Boissons", "Soft Drinks", "Refrescos", "Erfrischungsgetränke"),
        arrayOf("beer-wine", "Bières et vins", "Beer & Wine", "Cervezas y vinos", "Bier & Wein"),
    )

    private val menu = listOf(
        // burgers (the pub's own burgers keep their ids and photos)
        E("lantern-burger", "burgers", "LB", false, "Burger de la Lanterne", "Copper Lantern Burger", "Hamburguesa Copper Lantern", "Copper-Lantern-Burger",
            "Bœuf, cheddar, bacon, oignons et sauce maison.", "Beef, cheddar, bacon, onions and house sauce.", one(1195)),
        E("mushroom-burger", "burgers", "MB", false, "Burger aux champignons", "Mushroom Swiss Burger", "Hamburguesa de champiñones y suizo", "Pilz-Käse-Burger",
            "Bœuf, champignons, fromage suisse et aïoli.", "Beef, mushrooms, Swiss cheese and aioli.", one(1245)),
        E("veggie-burger", "burgers", "VB", false, "Burger végétarien", "Garden Veggie Burger", "Hamburguesa vegetariana", "Gemüseburger",
            "Galette végétale, avocat et légumes marinés.", "Plant-based patty, avocado and pickled vegetables.", one(1145)),
        E("double-cheeseburger", "burgers", "DC", false, "Double cheeseburger", "Double Cheeseburger", "Hamburguesa doble con queso", "Doppel-Cheeseburger",
            "Deux galettes de bœuf, cheddar fondu, cornichons.", "Two beef patties, melted cheddar and pickles.", one(1295)),
        // chicken
        E("wings", "chicken", "WG", false, "Ailes de poulet", "Chicken Wings", "Alitas de pollo", "Chicken Wings",
            "Ailes croustillantes, sauce au choix.", "Crispy wings with your choice of sauce.",
            listOf(V("6pc", "6 morceaux", "6 pieces", "6 piezas", "6 Stück", 995), V("12pc", "12 morceaux", "12 pieces", "12 piezas", "12 Stück", 1795))),
        E("club", "chicken", "GC", false, "Sandwich au poulet grillé", "Grilled Chicken Sandwich", "Sándwich de pollo a la parrilla", "Sandwich mit gegrilltem Hähnchen",
            "Poulet grillé, bacon, tomate et laitue.", "Grilled chicken, bacon, tomato and lettuce.", one(1195)),
        E("chicken-tenders", "chicken", "CT", false, "Lanières de poulet", "Chicken Tenders", "Tiras de pollo", "Hähnchenstreifen",
            "Lanières panées, sauce miel-moutarde.", "Breaded tenders with honey-mustard dip.",
            listOf(V("3pc", "3 morceaux", "3 pieces", "3 piezas", "3 Stück", 895), V("5pc", "5 morceaux", "5 pieces", "5 piezas", "5 Stück", 1295))),
        // fries & sides
        E("late-fries", "fries-sides", "FR", false, "Frites", "Fries", "Papas fritas", "Pommes frites",
            "Frites maison, sel de mer.", "House-cut fries with sea salt.", sml(395, 495, 595)),
        E("poutine", "fries-sides", "PO", false, "Poutine classique", "Classic Poutine", "Poutine clásica", "Klassische Poutine",
            "Frites, fromage en grains et sauce brune.", "Fries, cheese curds and savoury gravy.",
            listOf(V("regular", "Standard", "Regular", "Normal", "Normal", 895), V("large", "Grande", "Large", "Grande", "Groß", 1195))),
        E("onion-rings", "fries-sides", "OR", false, "Rondelles d'oignon", "Onion Rings", "Aros de cebolla", "Zwiebelringe",
            "Rondelles croustillantes, sauce barbecue.", "Crisp onion rings with barbecue sauce.", one(595)),
        // salads
        E("caesar-salad", "salads", "CS", false, "Salade César", "Caesar Salad", "Ensalada César", "Caesar Salat",
            "Romaine, parmesan, croûtons et vinaigrette César.", "Romaine, parmesan, croutons and Caesar dressing.", one(1095)),
        E("harvest-salad", "salads", "HS", false, "Salade des récoltes", "Harvest Salad", "Ensalada de la cosecha", "Erntesalat",
            "Jeunes pousses, pommes, courge, noix et fromage de chèvre.", "Greens, apples, squash, walnuts and goat cheese.", one(1195)),
        // desserts
        E("brownie", "desserts", "BR", false, "Brownie au chocolat", "Chocolate Brownie", "Brownie de chocolate", "Schokoladen-Brownie",
            "Brownie fondant, sauce chocolat.", "Fudgy brownie with chocolate sauce.", one(495)),
        E("maple-sundae", "desserts", "MS", false, "Coupe glacée à l'érable", "Maple Sundae", "Copa helada de maple", "Ahorn-Eisbecher",
            "Crème glacée molle, sirop d'érable et pacanes.", "Soft-serve, maple syrup and pecans.", one(545)),
        // soft drinks
        E("fountain-soda", "soft-drinks", "SO", false, "Boisson gazeuse", "Fountain Soda", "Refresco", "Softdrink",
            "Cola, cola diète, soda citron-lime ou soda gingembre.", "Cola, diet cola, lemon-lime or ginger ale.", sml(245, 295, 345)),
        E("lemonade", "soft-drinks", "LE", false, "Limonade maison", "House Lemonade", "Limonada de la casa", "Hausgemachte Limonade",
            "Citron pressé, un peu pétillante.", "Fresh-squeezed lemon, lightly sparkling.", one(395)),
        // beer & wine only (the pubs' own beers and wines keep their ids and photos)
        E("lantern-lager", "beer-wine", "LL", true, "Lager de la Lanterne", "Lantern House Lager", "Lager de la casa Lantern", "Lantern Hauslager",
            "Lager désaltérante et maltée, brassée à Montréal.", "Crisp, malty lager brewed in Montréal.",
            listOf(V("16oz", "Verre 16 oz", "16 oz glass", "Vaso de 16 oz", "Glas (16 oz)", 725))),
        E("north-ipa", "beer-wine", "NI", true, "IPA du Nord", "North Trail IPA", "IPA North Trail", "North Trail IPA",
            "IPA houblonnée aux arômes d'agrumes et de pin.", "Hop-forward IPA with citrus and pine.",
            listOf(V("16oz", "Verre 16 oz", "16 oz glass", "Vaso de 16 oz", "Glas (16 oz)", 775))),
        E("pinot-noir", "beer-wine", "PN", true, "Pinot noir des Cantons-de-l'Est", "Eastern Townships Pinot Noir", "Pinot noir de los Cantones del Este", "Pinot Noir aus den Eastern Townships",
            "Rouge léger, notes de cerise et d'épices.", "Light red with cherry and spice.",
            listOf(V("glass", "Verre", "Glass", "Copa", "Glas", 1050))),
        E("riesling", "beer-wine", "RI", true, "Riesling des Cantons-de-l'Est", "Eastern Townships Riesling", "Riesling de los Cantones del Este", "Riesling aus den Eastern Townships",
            "Blanc vif, notes de pomme et d'agrumes.", "Bright white with apple and citrus.",
            listOf(V("glass", "Verre", "Glass", "Copa", "Glas", 950))),
    )

    /** Item ids on the Express menu. */
    val menuItemIds: List<String> get() = menu.map { it.id }

    /** First boot of an Express store: its menu and two demo staff (the counter is QuickServeService.ensureCounter). Once (no staff yet). */
    fun seedIfEmpty() = transaction {
        if (Users.selectAll().count() > 0) return@transaction
        // the older migrations leave the pub's categories and floor plan in
        // every new database; a counter has neither
        exec("DELETE FROM item_variants")
        exec("DELETE FROM items")
        exec("DELETE FROM categories")
        exec("DELETE FROM floor_objects")
        exec("DELETE FROM dining_tables")
        exec("DELETE FROM zones")
        exec("DELETE FROM translations")
        exec("DELETE FROM sync_outbox")
        VenueSettings.update({ VenueSettings.id eq 1 }) {
            it[venueAddress] = CopperLanternVenue.EXPRESS.address
            it[venuePhone] = CopperLanternVenue.EXPRESS.phone
            it[serviceChargePercent] = 0
            it[corkagePerBottleCents] = 0
        }
        categories.forEachIndexed { i, c ->
            Categories.insert { it[id] = c[0]; it[sortOrder] = i; it[nameFr] = c[1]; it[nameEn] = c[2] }
            Translations.set(Translations.CATEGORY, c[0], "es", c[3], sync = false)
            Translations.set(Translations.CATEGORY, c[0], "de", c[4], sync = false)
        }
        for (m in menu) {
            Items.insert {
                it[id] = m.id; it[nameFr] = m.fr; it[nameEn] = m.en
                it[descriptionFr] = m.dfr; it[descriptionEn] = m.den
                it[categoryId] = m.category; it[abbrev] = m.abbrev; it[isAlcohol] = m.alcohol
            }
            Translations.set(Translations.ITEM, m.id, "es", m.es, sync = false)
            Translations.set(Translations.ITEM, m.id, "de", m.de, sync = false)
            m.variants.forEachIndexed { i, v ->
                val vid = "${m.id}:${v.key}"
                ItemVariants.insert {
                    it[id] = vid; it[itemId] = m.id; it[labelFr] = v.fr; it[labelEn] = v.en
                    it[priceCents] = v.cents; it[sortOrder] = i
                }
                Translations.set(Translations.VARIANT, vid, "es", v.es, sync = false)
                Translations.set(Translations.VARIANT, vid, "de", v.de, sync = false)
            }
        }
        Users.insert { it[id] = "manager"; it[name] = "Demo Manager"; it[role] = "MANAGER"; it[pin] = AuthService.hashPin("1234"); it[languageCode] = "en" }
        Users.insert { it[id] = "server1"; it[name] = "Demo Cashier"; it[role] = "SERVER"; it[pin] = AuthService.hashPin("9999"); it[languageCode] = "en" }
        Outbox.write("catalog.seeded", "catalog", "copper-lantern", buildJsonObject {
            put("items", menu.size); put("zones", 0); put("tables", 0)
        })
    }
}
