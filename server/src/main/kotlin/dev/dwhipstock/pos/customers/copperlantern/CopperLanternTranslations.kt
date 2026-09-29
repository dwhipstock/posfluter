package dev.dwhipstock.pos.customers.copperlantern

/**
 * Spanish and German names for the Copper Lantern demo seed (items,
 * categories, zones, floor-object captions, size labels), beyond the fr/en
 * catalog slots.
 * Written by hand and seeded into the translations table.
 */
internal object CopperLanternTranslations {
    /** entity (item | category | zone | floor_object), id, Spanish, German. */
    val rows: List<Array<String>> = listOf(
        // categories
        arrayOf("category", "beer-cider", "Cervezas y sidras", "Bier & Cidre"),
        arrayOf("category", "wine", "Vinos", "Weine"),
        arrayOf("category", "cocktails", "Cócteles", "Cocktails"),
        arrayOf("category", "starters", "Entradas", "Vorspeisen"),
        arrayOf("category", "burgers-sandwiches", "Hamburguesas y sándwiches", "Burger & Sandwiches"),
        arrayOf("category", "mains-salads", "Platos fuertes y ensaladas", "Hauptgerichte & Salate"),
        arrayOf("category", "desserts", "Postres", "Desserts"),
        arrayOf("category", "sushi", "Sushi y sake", "Sushi & Sake"),
        arrayOf("category", "plateau-specials", "Especialidades del Plateau", "Plateau-Spezialitäten"),

        // beer & cider
        arrayOf("item", "lantern-lager", "Lager de la casa Lantern", "Lantern Hauslager"),
        arrayOf("item", "amber-ale", "Ámbar Copper", "Copper Amber Ale"),
        arrayOf("item", "north-ipa", "IPA North Trail", "North Trail IPA"),
        arrayOf("item", "maple-stout", "Stout de avena y maple", "Hafer-Stout mit Ahorn"),
        arrayOf("item", "wheat-beer", "Cerveza de trigo cítrica", "Weißbier mit Zitrus"),
        arrayOf("item", "canadian-lager", "Lager canadiense", "Kanadisches Lager"),
        arrayOf("item", "pilsner-can", "Pilsner en lata", "Pils (Dose)"),
        arrayOf("item", "porter-can", "Porter robusta", "Kräftiges Porter"),
        arrayOf("item", "hazy-ipa", "IPA turbia local", "Hazy IPA (lokal)"),
        arrayOf("item", "saison", "Saison de granja", "Farmhouse Saison"),
        arrayOf("item", "belgian-blonde", "Rubia belga", "Belgisches Blond"),
        arrayOf("item", "irish-stout", "Stout irlandesa", "Irish Stout"),
        arrayOf("item", "mexican-lager", "Lager mexicana", "Mexikanisches Lager"),
        arrayOf("item", "dry-cider", "Sidra seca de Québec", "Trockener Cidre aus Québec"),
        arrayOf("item", "berry-cider", "Sidra de frutos rojos", "Beeren-Cidre"),
        arrayOf("item", "na-lager", "Lager sin alcohol", "Alkoholfreies Lager"),
        arrayOf("item", "hop-water", "Agua con gas de lúpulo", "Hopfenwasser"),

        // wine
        arrayOf("item", "pinot-noir", "Pinot Noir de los Cantons-de-l'Est", "Pinot Noir aus den Cantons-de-l'Est"),
        arrayOf("item", "cab-merlot", "Cabernet Merlot", "Cabernet Merlot"),
        arrayOf("item", "malbec", "Malbec argentino", "Malbec aus Argentinien"),
        arrayOf("item", "riesling", "Riesling de los Cantons-de-l'Est", "Riesling aus den Cantons-de-l'Est"),
        arrayOf("item", "chardonnay", "Chardonnay con barrica", "Chardonnay (Barrique)"),
        arrayOf("item", "sauvignon-blanc", "Sauvignon Blanc", "Sauvignon Blanc"),
        arrayOf("item", "rose", "Rosado de Montérégie", "Rosé aus der Montérégie"),
        arrayOf("item", "sparkling", "Espumoso brut de Québec", "Schaumwein Brut aus Québec"),
        arrayOf("item", "icewine", "Sidra de hielo de Québec", "Eiscidre aus Québec"),

        // cocktails
        arrayOf("item", "copper-old-fashioned", "Old Fashioned Copper", "Copper Old Fashioned"),
        arrayOf("item", "lantern-mule", "Lantern Mule", "Lantern Mule"),
        arrayOf("item", "smoked-caesar", "Caesar ahumado", "Smoked Caesar"),
        arrayOf("item", "maple-sour", "Whisky sour con maple", "Ahorn-Whisky-Sour"),
        arrayOf("item", "elderflower-gin", "Gin fizz de flor de saúco", "Holunderblüten-Gin-Fizz"),
        arrayOf("item", "espresso-martini", "Espresso Martini", "Espresso Martini"),
        arrayOf("item", "dark-stormy", "Dark and Stormy", "Dark and Stormy"),
        arrayOf("item", "zero-gimlet", "Gimlet sin alcohol", "Alkoholfreier Gimlet"),

        // starters
        arrayOf("item", "pretzel", "Pretzel gigante", "Riesenbrezel"),
        arrayOf("item", "wings", "Alitas de pollo", "Chicken Wings"),
        arrayOf("item", "nachos", "Nachos de la casa", "Nachos „Pub Style“"),
        arrayOf("item", "calamari", "Calamares crujientes", "Knusprige Calamari"),
        arrayOf("item", "spinach-dip", "Dip de espinaca y alcachofa", "Spinat-Artischocken-Dip"),
        arrayOf("item", "poutine", "Poutine clásica", "Klassische Poutine"),
        arrayOf("item", "late-fries", "Papas fritas de medianoche", "Mitternachts-Pommes"),
        arrayOf("item", "mini-burgers", "Mini hamburguesas", "Mini-Burger"),
        arrayOf("item", "grilled-cheese", "Sándwich de queso a la plancha", "Grilled Cheese"),
        arrayOf("item", "onion-rings", "Aros de cebolla", "Zwiebelringe"),

        // burgers & sandwiches
        arrayOf("item", "lantern-burger", "Hamburguesa Copper Lantern", "Copper Lantern Burger"),
        arrayOf("item", "mushroom-burger", "Hamburguesa de champiñones y queso suizo", "Champignon-Burger mit Bergkäse"),
        arrayOf("item", "veggie-burger", "Hamburguesa vegetariana", "Veggie-Burger"),
        arrayOf("item", "club", "Club sándwich de pollo", "Chicken Club Sandwich"),
        arrayOf("item", "reuben", "Reuben de Montréal", "Montréal Reuben"),
        arrayOf("item", "fish-sandwich", "Sándwich de pescado crujiente", "Backfisch-Sandwich"),

        // mains & salads
        arrayOf("item", "fish-chips", "Fish and chips con rebozado de cerveza", "Fish & Chips im Bierteig"),
        arrayOf("item", "steak-frites", "Filete con papas fritas", "Steak mit Pommes"),
        arrayOf("item", "shepherd-pie", "Pastel de carne y papa", "Shepherd’s Pie"),
        arrayOf("item", "mac-cheese", "Macarrones con tres quesos", "Makkaroni mit drei Käsesorten"),
        arrayOf("item", "salmon", "Salmón glaseado con maple", "Lachs mit Ahornglasur"),
        arrayOf("item", "chicken-pot-pie", "Pastel de pollo", "Hähnchen-Pastete"),
        arrayOf("item", "caesar-salad", "Ensalada César", "Caesar Salad"),
        arrayOf("item", "harvest-salad", "Ensalada de cosecha de Québec", "Herbstsalat aus Québec"),
        arrayOf("item", "falafel-bowl", "Bowl de falafel", "Falafel-Bowl"),
        arrayOf("item", "cauliflower", "Filete de coliflor asada", "Geröstetes Blumenkohl-Steak"),

        // desserts
        arrayOf("item", "sticky-pudding", "Pudín de toffee", "Sticky Toffee Pudding"),
        arrayOf("item", "cheesecake", "Pay de queso con maple", "Ahorn-Käsekuchen"),
        arrayOf("item", "brownie", "Brownie de stout", "Stout-Brownie"),

        // Plateau: sushi & sake
        arrayOf("item", "salmon-maki", "Maki de salmón", "Lachs-Maki"),
        arrayOf("item", "spicy-tuna-maki", "Maki de atún picante", "Scharfe Thunfisch-Maki"),
        arrayOf("item", "avocado-maki", "Maki de aguacate y pepino", "Avocado-Gurken-Maki"),
        arrayOf("item", "salmon-nigiri", "Nigiri de salmón", "Lachs-Nigiri"),
        arrayOf("item", "tuna-nigiri", "Nigiri de atún", "Thunfisch-Nigiri"),
        arrayOf("item", "scallop-nigiri", "Nigiri de callo de hacha", "Jakobsmuschel-Nigiri"),
        arrayOf("item", "junmai-sake", "Sake junmai", "Junmai-Sake"),
        arrayOf("item", "sparkling-sake", "Sake espumoso", "Perlender Sake"),

        // Plateau specials
        arrayOf("item", "smoked-meat-poutine", "Poutine con carne ahumada", "Poutine mit Smoked Meat"),
        arrayOf("item", "maple-miso-bowl", "Bowl de salmón con maple y miso", "Ahorn-Miso-Lachs-Bowl"),
        arrayOf("item", "bagel-board", "Tabla de bagels del Plateau", "Plateau-Bagelplatte"),
        arrayOf("item", "yuzu-sour", "Yuzu Lantern Sour", "Yuzu Lantern Sour"),

        // zones
        arrayOf("zone", "upper", "Comedor y bar", "Gastraum & Bar"),
        arrayOf("zone", "outside", "Terraza", "Terrasse"),
        arrayOf("zone", "lower", "Sala de juegos", "Spielbereich"),
        arrayOf("zone", "sushi", "Barra de sushi", "Sushi-Bar"),

        // floor-object captions
        arrayOf("floor_object", "upper-bar", "Barra de cobre", "Kupfertheke"),
        arrayOf("floor_object", "lower-pool", "Billar", "Billard"),
        arrayOf("floor_object", "sushi-counter", "Barra de sushi", "Sushi-Theke"),
    )

    /** Size / container labels by their English label: Spanish, German. */
    val variantLabels: Map<String, Pair<String, String>> = mapOf(
        "Regular" to ("Normal" to "Normal"),
        "Glass" to ("Copa" to "Glas"),
        "Bottle" to ("Botella" to "Flasche"),
        "20 oz pint" to ("Pinta de 20 oz" to "Pint (20 oz)"),
        "60 oz pitcher" to ("Jarra de 60 oz" to "Krug (60 oz)"),
        "2 oz glass" to ("Copa de 2 oz" to "Glas (2 oz)"),
        "375 ml bottle" to ("Botella de 375 ml" to "Flasche (375 ml)"),
        "180 ml carafe" to ("Jarrita de 180 ml" to "Karaffe (180 ml)"),
        "720 ml bottle" to ("Botella de 720 ml" to "Flasche (720 ml)"),
    )
}
