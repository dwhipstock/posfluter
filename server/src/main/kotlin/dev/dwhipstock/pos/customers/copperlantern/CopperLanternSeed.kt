package dev.dwhipstock.pos.customers.copperlantern

import dev.dwhipstock.pos.base.AuthService
import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.Users
import dev.dwhipstock.pos.restaurant.DiningTables
import dev.dwhipstock.pos.restaurant.FloorObjects
import dev.dwhipstock.pos.restaurant.Zones
import dev.dwhipstock.pos.sdk.Outbox
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** Fictional Canadian pub demo data. All prices are CAD cents. */
object CopperLanternSeed {
    private data class Variant(val key: String, val fr: String, val en: String, val cents: Long)
    private data class SeedItem(
        val id: String, val nameFr: String, val nameEn: String,
        val descriptionFr: String, val descriptionEn: String,
        val category: String, val abbrev: String, val alcohol: Boolean,
        val variants: List<Variant>,
    )
    private data class SeedTable(
        val id: String, val zoneId: String, val label: String, val sortOrder: Int,
        val x: Int, val y: Int, val width: Int, val height: Int,
        val shape: String, val seats: Int,
        val parentId: String? = null, val nameOverride: String? = null,
    )
    private data class SeedObject(
        val id: String, val zoneId: String, val type: String,
        val x: Int, val y: Int, val width: Int, val height: Int,
        val label: String? = null,
    )

    private fun one(cents: Long) = listOf(Variant("regular", "Régulier", "Regular", cents))
    private fun pour(glass: Long, bottle: Long) = listOf(
        Variant("glass", "Verre", "Glass", glass), Variant("bottle", "Bouteille", "Bottle", bottle))
    private fun beer(pint: Long, pitcher: Long) = listOf(
        Variant("pint", "Pinte 20 oz", "20 oz pint", pint), Variant("pitcher", "Pichet 60 oz", "60 oz pitcher", pitcher))
    private fun item(id: String, fr: String, en: String, dfr: String, den: String,
                     category: String, abbrev: String, alcohol: Boolean, cents: Long) =
        SeedItem(id, fr, en, dfr, den, category, abbrev, alcohol, one(cents))

    private val menu = listOf(
        SeedItem("lantern-lager", "Lager de la Lanterne", "Lantern House Lager", "Lager vive et maltée brassée en Ontario.", "Crisp, malty lager brewed in Ontario.", "draft-beer", "LL", true, beer(825, 2250)),
        SeedItem("amber-ale", "Ale ambrée", "Copper Amber Ale", "Ale ambrée aux notes de caramel et de noix.", "Amber ale with caramel and toasted-nut notes.", "draft-beer", "AA", true, beer(875, 2400)),
        SeedItem("north-ipa", "IPA du Nord", "North Trail IPA", "IPA houblonnée aux arômes d'agrumes et de pin.", "Hop-forward IPA with citrus and pine.", "draft-beer", "NI", true, beer(925, 2550)),
        SeedItem("maple-stout", "Stout à l'érable", "Maple Oat Stout", "Stout crémeux à l'avoine avec une touche d'érable.", "Creamy oatmeal stout with a hint of maple.", "draft-beer", "MS", true, beer(950, 2650)),
        SeedItem("wheat-beer", "Blanche aux agrumes", "Citrus Wheat", "Bière de blé légère, orange et coriandre.", "Light wheat beer with orange and coriander.", "draft-beer", "CW", true, beer(850, 2350)),
        item("canadian-lager", "Lager canadienne", "Canadian Lager", "Lager légère et rafraîchissante.", "Clean, refreshing pale lager.", "bottles-cans", "CL", true, 725),
        item("pilsner-can", "Pilsner en canette", "Pilsner Can", "Pilsner sèche et herbacée.", "Dry, herbal pilsner.", "bottles-cans", "PC", true, 775),
        item("porter-can", "Porter robuste", "Robust Porter", "Porter torréfié au cacao.", "Roasty porter with cocoa notes.", "craft-beer", "RP", true, 875),
        item("hazy-ipa", "IPA voilée locale", "Local Hazy IPA", "IPA juteuse brassée à Toronto.", "Juicy IPA brewed in Toronto.", "craft-beer", "HI", true, 925),
        item("saison", "Saison fermière", "Farmhouse Saison", "Saison sèche, poivrée et effervescente.", "Dry, peppery and lively farmhouse ale.", "craft-beer", "FS", true, 900),
        item("belgian-blonde", "Blonde belge", "Belgian Blonde", "Blonde importée, fruitée et douce.", "Fruity, smooth imported blonde ale.", "imported-beer", "BB", true, 975),
        item("irish-stout", "Stout irlandais", "Irish Stout", "Stout sec importé au col crémeux.", "Imported dry stout with a creamy head.", "imported-beer", "IS", true, 950),
        item("mexican-lager", "Lager mexicaine", "Mexican Lager", "Lager légère servie avec lime.", "Light lager served with lime.", "imported-beer", "ML", true, 875),
        item("dry-cider", "Cidre sec", "Ontario Dry Cider", "Cidre de pommes ontariennes, vif et sec.", "Crisp dry cider made with Ontario apples.", "cider-na", "DC", true, 875),
        item("berry-cider", "Cidre aux petits fruits", "Berry Cider", "Cidre demi-sec aux petits fruits.", "Off-dry cider with mixed berries.", "cider-na", "BC", true, 900),
        item("na-lager", "Lager sans alcool", "Non-Alcoholic Lager", "Lager maltée à moins de 0,5 %.", "Malty lager with less than 0.5% alcohol.", "cider-na", "NA", false, 650),
        item("hop-water", "Eau houblonnée", "Sparkling Hop Water", "Eau pétillante houblonnée sans alcool.", "Alcohol-free sparkling water infused with hops.", "cider-na", "HW", false, 550),

        SeedItem("pinot-noir", "Pinot noir de Niagara", "Niagara Pinot Noir", "Rouge léger, cerise et épices.", "Light red with cherry and spice.", "red-wine", "PN", true, pour(1250, 4800)),
        SeedItem("cab-merlot", "Cabernet-merlot", "Ontario Cabernet Merlot", "Rouge souple, mûre et cèdre.", "Smooth red with blackberry and cedar.", "red-wine", "CM", true, pour(1150, 4400)),
        SeedItem("malbec", "Malbec argentin", "Argentinian Malbec", "Rouge corsé aux notes de prune.", "Full-bodied red with plum notes.", "red-wine", "MB", true, pour(1300, 5000)),
        SeedItem("riesling", "Riesling de Niagara", "Niagara Riesling", "Blanc vif, pomme et agrumes.", "Bright white with apple and citrus.", "white-wine", "RI", true, pour(1100, 4200)),
        SeedItem("chardonnay", "Chardonnay boisé", "Oaked Chardonnay", "Blanc rond, poire et vanille.", "Round white with pear and vanilla.", "white-wine", "CH", true, pour(1200, 4600)),
        SeedItem("sauvignon-blanc", "Sauvignon blanc", "Sauvignon Blanc", "Blanc sec, herbacé et citronné.", "Dry white with herbs and lemon.", "white-wine", "SB", true, pour(1250, 4800)),
        SeedItem("rose", "Rosé de la péninsule", "Peninsula Rosé", "Rosé sec aux notes de fraise.", "Dry rosé with strawberry notes.", "rose-sparkling", "RO", true, pour(1150, 4400)),
        SeedItem("sparkling", "Brut de Niagara", "Niagara Brut", "Bulles fines, pomme verte et brioche.", "Fine bubbles with green apple and brioche.", "rose-sparkling", "BR", true, pour(1400, 5400)),
        SeedItem("icewine", "Vin de glace", "Ontario Icewine", "Vin de dessert riche aux notes d'abricot.", "Rich dessert wine with apricot notes.", "rose-sparkling", "IW", true, listOf(Variant("2oz", "Verre 2 oz", "2 oz glass", 1650), Variant("375ml", "Bouteille 375 ml", "375 ml bottle", 7200))),

        item("copper-old-fashioned", "Old fashioned cuivré", "Copper Old Fashioned", "Whisky canadien, érable, amers et orange.", "Canadian whisky, maple, bitters and orange.", "cocktails", "OF", true, 1550),
        item("lantern-mule", "Mule de la Lanterne", "Lantern Mule", "Vodka, bière de gingembre, lime et canneberge.", "Vodka, ginger beer, lime and cranberry.", "cocktails", "LM", true, 1450),
        item("smoked-caesar", "César fumé", "Smoked Caesar", "Vodka, clamato épicé et sel fumé.", "Vodka, spiced clamato and smoked salt.", "cocktails", "SC", true, 1450),
        item("maple-sour", "Whisky sour à l'érable", "Maple Whisky Sour", "Whisky, citron, érable et blanc d'œuf.", "Whisky, lemon, maple and egg white.", "cocktails", "WS", true, 1500),
        item("elderflower-gin", "Gin pétillant au sureau", "Elderflower Gin Fizz", "Gin, sureau, citron et soda.", "Gin, elderflower, lemon and soda.", "cocktails", "GF", true, 1500),
        item("espresso-martini", "Martini espresso", "Espresso Martini", "Vodka, liqueur de café et espresso.", "Vodka, coffee liqueur and espresso.", "cocktails", "EM", true, 1550),
        item("dark-stormy", "Dark and Stormy", "Dark and Stormy", "Rhum brun, gingembre et lime.", "Dark rum, ginger beer and lime.", "cocktails", "DS", true, 1450),
        item("zero-gimlet", "Gimlet sans alcool", "Zero-Proof Gimlet", "Botanique sans alcool, lime et romarin.", "Alcohol-free botanical spirit, lime and rosemary.", "cocktails", "ZG", false, 950),

        item("pretzel", "Bretzel géant", "Giant Pub Pretzel", "Bretzel chaud, moutarde à la bière et fromage.", "Warm pretzel with beer mustard and cheese dip.", "appetizers", "PR", false, 1350),
        item("wings", "Ailes de poulet", "Chicken Wings", "Une livre d'ailes avec sauce au choix.", "One pound of wings with your choice of sauce.", "appetizers", "WG", false, 1850),
        item("nachos", "Nachos de la maison", "Loaded Pub Nachos", "Fromage, haricots, jalapeños, salsa et crème sure.", "Cheese, beans, jalapeños, salsa and sour cream.", "appetizers", "NC", false, 1950),
        item("calamari", "Calmars croustillants", "Crispy Calamari", "Calmars frits, citron et aïoli.", "Fried calamari with lemon and aioli.", "appetizers", "CA", false, 1850),
        item("spinach-dip", "Trempette épinards-artichauts", "Spinach Artichoke Dip", "Trempette chaude avec croustilles de pita.", "Hot dip served with pita chips.", "appetizers", "SD", false, 1650),
        item("poutine", "Poutine classique", "Classic Poutine", "Frites, fromage en grains et sauce brune.", "Fries, cheese curds and savoury gravy.", "appetizers", "PO", false, 1450),
        item("lantern-burger", "Burger de la Lanterne", "Copper Lantern Burger", "Bœuf, cheddar, bacon, oignons et sauce maison.", "Beef, cheddar, bacon, onions and house sauce.", "burgers-sandwiches", "LB", false, 2150),
        item("mushroom-burger", "Burger aux champignons", "Mushroom Swiss Burger", "Bœuf, champignons, suisse et aïoli.", "Beef, mushrooms, Swiss cheese and aioli.", "burgers-sandwiches", "MB", false, 2200),
        item("veggie-burger", "Burger végétarien", "Garden Veggie Burger", "Galette végétale, avocat et légumes marinés.", "Plant-based patty, avocado and pickled vegetables.", "burgers-sandwiches", "VB", false, 1950),
        item("club", "Club au poulet", "Grilled Chicken Club", "Poulet grillé, bacon, tomate et laitue.", "Grilled chicken, bacon, tomato and lettuce.", "burgers-sandwiches", "GC", false, 2050),
        item("reuben", "Reuben montréalais", "Montreal Reuben", "Viande fumée, suisse, choucroute et sauce russe.", "Smoked meat, Swiss cheese, sauerkraut and Russian dressing.", "burgers-sandwiches", "RE", false, 2150),
        item("fish-sandwich", "Sandwich au poisson", "Crispy Fish Sandwich", "Aiglefin pané, salade de chou et tartare.", "Battered haddock, slaw and tartar sauce.", "burgers-sandwiches", "FS", false, 1950),
        item("fish-chips", "Poisson-frites", "Beer-Battered Fish and Chips", "Aiglefin, frites, salade de chou et tartare.", "Haddock, fries, slaw and tartar sauce.", "mains", "FC", false, 2250),
        item("steak-frites", "Steak-frites", "Steak Frites", "Bavette grillée, beurre aux herbes et frites.", "Grilled flank steak, herb butter and fries.", "mains", "SF", false, 2950),
        item("shepherd-pie", "Pâté chinois", "Shepherd's Pie", "Bœuf braisé, légumes et purée de pommes de terre.", "Braised beef, vegetables and mashed potato.", "mains", "SP", false, 2150),
        item("mac-cheese", "Macaroni au fromage", "Three-Cheese Mac", "Macaroni crémeux gratiné avec chapelure.", "Creamy baked macaroni with three cheeses.", "mains", "MC", false, 1850),
        item("salmon", "Saumon à l'érable", "Maple-Glazed Salmon", "Saumon, riz sauvage et légumes de saison.", "Salmon, wild rice and seasonal vegetables.", "mains", "SA", false, 2750),
        item("chicken-pot-pie", "Pâté au poulet", "Chicken Pot Pie", "Poulet, légumes et pâte feuilletée.", "Chicken and vegetables under puff pastry.", "mains", "CP", false, 2050),
        item("caesar-salad", "Salade César", "Caesar Salad", "Romaine, parmesan, croûtons et vinaigrette César.", "Romaine, parmesan, croutons and Caesar dressing.", "salads-vegetarian", "CS", false, 1450),
        item("harvest-salad", "Salade des récoltes", "Ontario Harvest Salad", "Verdure, pommes, courge, noix et chèvre.", "Greens, apples, squash, walnuts and goat cheese.", "salads-vegetarian", "HS", false, 1650),
        item("falafel-bowl", "Bol de falafels", "Falafel Grain Bowl", "Falafels, quinoa, houmous et légumes.", "Falafel, quinoa, hummus and vegetables.", "salads-vegetarian", "FB", false, 1850),
        item("cauliflower", "Chou-fleur rôti", "Roasted Cauliflower Steak", "Chou-fleur, lentilles, tahini et fines herbes.", "Cauliflower, lentils, tahini and herbs.", "salads-vegetarian", "RC", false, 1950),
        item("sticky-pudding", "Pouding au caramel", "Sticky Toffee Pudding", "Gâteau aux dattes, caramel et crème glacée.", "Date cake, toffee sauce and ice cream.", "desserts", "ST", false, 950),
        item("cheesecake", "Gâteau au fromage", "Maple Cheesecake", "Gâteau au fromage à l'érable et noix de pacane.", "Maple cheesecake with toasted pecans.", "desserts", "CK", false, 950),
        item("brownie", "Brownie au stout", "Stout Brownie", "Brownie chaud, sauce chocolat et crème glacée.", "Warm brownie with chocolate sauce and ice cream.", "desserts", "BR", false, 900),
        item("late-fries", "Frites de nuit", "Late-Night Fries", "Panier de frites avec aïoli maison.", "Basket of fries with house aioli.", "late-night", "LF", false, 850),
        item("mini-burgers", "Mini-burgers", "Midnight Sliders", "Trois mini-burgers au cheddar et cornichons.", "Three cheddar sliders with pickles.", "late-night", "SL", false, 1450),
        item("grilled-cheese", "Fromage grillé", "Grilled Cheese", "Cheddar vieilli sur pain au levain.", "Aged cheddar on sourdough.", "late-night", "GR", false, 1150),
        item("onion-rings", "Rondelles d'oignon", "Onion Rings", "Rondelles croustillantes et sauce barbecue.", "Crisp onion rings with barbecue sauce.", "late-night", "OR", false, 1050),
    )

    // Stable internal IDs preserve API compatibility; only fictional pub labels are shown.
    private val zones = listOf(
        arrayOf("upper", "Salle à manger et bar", "Dining Room & Bar", "U"),
        arrayOf("outside", "Terrasse", "Patio", "O"),
        arrayOf("lower", "Salle de jeux", "Games Room", "L"),
    )
    private val tables = listOf(
        // Seven individual barstools plus varied dining tables: 47 seats total.
        SeedTable("t5", "upper", "U-1", 1, 50, 145, 65, 65, "ROUND", 1),
        SeedTable("t5-5", "upper", "U-2", 2, 180, 145, 65, 65, "ROUND", 1, parentId = "t5"),
        SeedTable("t6", "upper", "U-3", 3, 310, 145, 65, 65, "ROUND", 1),
        SeedTable("b1", "upper", "U-4", 4, 440, 145, 65, 65, "ROUND", 1),
        SeedTable("b2", "upper", "U-5", 5, 570, 145, 65, 65, "ROUND", 1),
        SeedTable("b3", "upper", "U-6", 6, 700, 145, 65, 65, "ROUND", 1),
        SeedTable("b4", "upper", "U-7", 7, 830, 145, 65, 65, "ROUND", 1),
        SeedTable("t1", "upper", "U-8", 8, 75, 340, 180, 110, "RECT", 6),
        SeedTable("t1-1", "upper", "U-9", 9, 365, 330, 110, 110, "ROUND", 4, parentId = "t1"),
        SeedTable("t2", "upper", "U-10", 10, 650, 340, 180, 110, "RECT", 6),
        SeedTable("u2-2", "upper", "U-11", 11, 75, 650, 110, 110, "SQUARE", 4, parentId = "t2"),
        SeedTable("u3", "upper", "U-12", 12, 300, 635, 220, 120, "RECT", 8),
        SeedTable("u3-3", "upper", "U-13", 13, 680, 650, 110, 110, "ROUND", 4, parentId = "u3"),
        SeedTable("u4", "upper", "U-14", 14, 75, 835, 110, 110, "ROUND", 4),
        SeedTable("u4-4", "upper", "U-15", 15, 300, 835, 110, 110, "SQUARE", 4, parentId = "u4"),
        SeedTable("u5", "upper", "U-16", 16, 500, 835, 110, 110, "ROUND", 4),
        SeedTable("u5-5", "upper", "U-17", 17, 700, 835, 110, 110, "SQUARE", 4, parentId = "u5"),

        SeedTable("t3", "outside", "O-1", 1, 120, 150, 110, 110, "ROUND", 4),
        SeedTable("t4", "outside", "O-2", 2, 390, 150, 110, 110, "ROUND", 4),
        SeedTable("o3", "outside", "O-3", 3, 660, 150, 110, 110, "ROUND", 4),
        SeedTable("o4", "outside", "O-4", 4, 390, 470, 180, 110, "RECT", 6),

        // Eight perimeter tables leave the centre open for games.
        SeedTable("t7", "lower", "L-1", 1, 60, 60, 110, 110, "ROUND", 4),
        SeedTable("t8", "lower", "L-2", 2, 250, 60, 110, 110, "ROUND", 4, nameOverride = "Alex Morgan"),
        SeedTable("t101", "lower", "L-3", 3, 640, 60, 110, 110, "ROUND", 4),
        SeedTable("l9", "lower", "L-4", 4, 830, 60, 110, 110, "ROUND", 4),
        SeedTable("l10", "lower", "L-5", 5, 60, 690, 110, 110, "SQUARE", 4),
        SeedTable("l11", "lower", "L-6", 6, 250, 690, 110, 110, "SQUARE", 4),
        SeedTable("l12", "lower", "L-7", 7, 640, 690, 110, 110, "SQUARE", 4),
        SeedTable("l13", "lower", "L-8", 8, 830, 690, 110, 110, "SQUARE", 4),
    )
    private val floorObjects = listOf(
        SeedObject("upper-bar", "upper", "BAR_FRONT", 40, 30, 920, 80, "Copper Bar"),
        SeedObject("upper-pillar-1", "upper", "PILLAR", 275, 470, 65, 65),
        SeedObject("upper-pillar-2", "upper", "PILLAR", 610, 470, 65, 65),
        SeedObject("lower-pool", "lower", "POOL", 250, 260, 500, 280, "Pool"),
        SeedObject("lower-pillar-1", "lower", "PILLAR", 70, 360, 65, 65),
        SeedObject("lower-pillar-2", "lower", "PILLAR", 865, 360, 65, 65),
    )

    fun seedIfEmpty() = transaction {
        if (Users.selectAll().count() > 0) return@transaction
        Items.batchInsert(menu) { m ->
            this[Items.id] = m.id; this[Items.nameFr] = m.nameFr; this[Items.nameEn] = m.nameEn
            this[Items.descriptionFr] = m.descriptionFr; this[Items.descriptionEn] = m.descriptionEn
            this[Items.categoryId] = m.category; this[Items.abbrev] = m.abbrev; this[Items.isAlcohol] = m.alcohol
        }
        menu.forEach { m -> ItemVariants.batchInsert(m.variants.withIndex().toList()) { (i, v) ->
            this[ItemVariants.id] = "${m.id}:${v.key}"; this[ItemVariants.itemId] = m.id
            this[ItemVariants.labelFr] = v.fr; this[ItemVariants.labelEn] = v.en
            this[ItemVariants.priceCents] = v.cents; this[ItemVariants.sortOrder] = i
        } }
        Zones.batchInsert(zones.withIndex().toList()) { (i, z) ->
            this[Zones.id] = z[0]; this[Zones.nameFr] = z[1]; this[Zones.nameEn] = z[2]
            this[Zones.sortOrder] = i; this[Zones.labelPrefix] = z[3]
        }
        DiningTables.batchInsert(tables) { t ->
            this[DiningTables.id] = t.id; this[DiningTables.zoneId] = t.zoneId; this[DiningTables.label] = t.label
            this[DiningTables.parentTableId] = t.parentId; this[DiningTables.nameOverride] = t.nameOverride
            this[DiningTables.sortOrder] = t.sortOrder
            this[DiningTables.x] = t.x; this[DiningTables.y] = t.y
            this[DiningTables.width] = t.width; this[DiningTables.height] = t.height
            this[DiningTables.shape] = t.shape; this[DiningTables.seats] = t.seats
        }
        FloorObjects.batchInsert(floorObjects) { o ->
            this[FloorObjects.id] = o.id; this[FloorObjects.zoneId] = o.zoneId; this[FloorObjects.type] = o.type
            this[FloorObjects.x] = o.x; this[FloorObjects.y] = o.y
            this[FloorObjects.width] = o.width; this[FloorObjects.height] = o.height
            this[FloorObjects.label] = o.label
        }
        Users.insert { it[id] = "manager"; it[name] = "Demo Manager"; it[role] = "MANAGER"; it[pin] = AuthService.hashPin("1234"); it[languageCode] = "en"; it[calendar] = "CE" }
        Users.insert { it[id] = "server1"; it[name] = "Demo Server"; it[role] = "SERVER"; it[pin] = AuthService.hashPin("9999"); it[languageCode] = "en"; it[calendar] = "CE" }
        Outbox.write("catalog.seeded", "catalog", "copper-lantern", buildJsonObject { put("items", menu.size); put("zones", zones.size); put("tables", tables.size) })
    }
}
