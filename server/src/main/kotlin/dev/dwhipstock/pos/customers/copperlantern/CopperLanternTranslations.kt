package dev.dwhipstock.pos.customers.copperlantern

/**
 * Spanish, German and Afrikaans names for the Copper Lantern demo seed (items,
 * categories, zones, floor-object captions, size labels), beyond the fr/en
 * catalog slots.
 * Written by hand and seeded into the translations table.
 */
internal object CopperLanternTranslations {
    /** entity (item | category | zone | floor_object), id, Spanish, German, Afrikaans. */
    val rows: List<Array<String>> = listOf(
        // categories
        arrayOf("category", "beer-cider", "Cervezas y sidras", "Bier & Cidre", "Bier en sider"),
        arrayOf("category", "wine", "Vinos", "Weine", "Wyne"),
        arrayOf("category", "cocktails", "Cócteles", "Cocktails", "Skemerkelkies"),
        arrayOf("category", "starters", "Entradas", "Vorspeisen", "Voorgeregte"),
        arrayOf("category", "burgers-sandwiches", "Hamburguesas y sándwiches", "Burger & Sandwiches", "Burgers en toebroodjies"),
        arrayOf("category", "mains-salads", "Platos fuertes y ensaladas", "Hauptgerichte & Salate", "Hoofgeregte en slaaie"),
        arrayOf("category", "desserts", "Postres", "Desserts", "Nageregte"),
        arrayOf("category", "sushi", "Sushi y sake", "Sushi & Sake", "Sushi en sake"),
        arrayOf("category", "plateau-specials", "Especialidades del Plateau", "Plateau-Spezialitäten", "Plateau-spesialiteite"),

        // beer & cider
        arrayOf("item", "lantern-lager", "Lager de la casa Lantern", "Lantern Hauslager", "Lantern-huislager"),
        arrayOf("item", "amber-ale", "Ámbar Copper", "Copper Amber Ale", "Copper Amber Ale"),
        arrayOf("item", "north-ipa", "IPA North Trail", "North Trail IPA", "North Trail IPA"),
        arrayOf("item", "maple-stout", "Stout de avena y maple", "Hafer-Stout mit Ahorn", "Hawer-stout met esdoring"),
        arrayOf("item", "wheat-beer", "Cerveza de trigo cítrica", "Weißbier mit Zitrus", "Sitrus-koringbier"),
        arrayOf("item", "canadian-lager", "Lager canadiense", "Kanadisches Lager", "Kanadese lager"),
        arrayOf("item", "pilsner-can", "Pilsner en lata", "Pils (Dose)", "Pilsner (blikkie)"),
        arrayOf("item", "porter-can", "Porter robusta", "Kräftiges Porter", "Sterk porter"),
        arrayOf("item", "hazy-ipa", "IPA turbia local", "Hazy IPA (lokal)", "Plaaslike Hazy IPA"),
        arrayOf("item", "saison", "Saison de granja", "Farmhouse Saison", "Plaas-saison"),
        arrayOf("item", "belgian-blonde", "Rubia belga", "Belgisches Blond", "Belgiese blonde bier"),
        arrayOf("item", "irish-stout", "Stout irlandesa", "Irish Stout", "Ierse stout"),
        arrayOf("item", "mexican-lager", "Lager mexicana", "Mexikanisches Lager", "Meksikaanse lager"),
        arrayOf("item", "dry-cider", "Sidra seca de Québec", "Trockener Cidre aus Québec", "Droë sider uit Québec"),
        arrayOf("item", "berry-cider", "Sidra de frutos rojos", "Beeren-Cidre", "Bessiesider"),
        arrayOf("item", "na-lager", "Lager sin alcohol", "Alkoholfreies Lager", "Alkoholvrye lager"),
        arrayOf("item", "hop-water", "Agua con gas de lúpulo", "Hopfenwasser", "Hopwater"),

        // wine
        arrayOf("item", "pinot-noir", "Pinot Noir de los Cantons-de-l'Est", "Pinot Noir aus den Cantons-de-l'Est", "Pinot Noir van die Cantons-de-l’Est"),
        arrayOf("item", "cab-merlot", "Cabernet Merlot", "Cabernet Merlot", "Cabernet Merlot"),
        arrayOf("item", "malbec", "Malbec argentino", "Malbec aus Argentinien", "Argentynse Malbec"),
        arrayOf("item", "riesling", "Riesling de los Cantons-de-l'Est", "Riesling aus den Cantons-de-l'Est", "Riesling van die Cantons-de-l’Est"),
        arrayOf("item", "chardonnay", "Chardonnay con barrica", "Chardonnay (Barrique)", "Gehoute Chardonnay"),
        arrayOf("item", "sauvignon-blanc", "Sauvignon Blanc", "Sauvignon Blanc", "Sauvignon Blanc"),
        arrayOf("item", "rose", "Rosado de Montérégie", "Rosé aus der Montérégie", "Rosé van Montérégie"),
        arrayOf("item", "sparkling", "Espumoso brut de Québec", "Schaumwein Brut aus Québec", "Brut-vonkelwyn uit Québec"),
        arrayOf("item", "icewine", "Sidra de hielo de Québec", "Eiscidre aus Québec", "Yssider uit Québec"),

        // cocktails
        arrayOf("item", "copper-old-fashioned", "Old Fashioned Copper", "Copper Old Fashioned", "Copper Old Fashioned"),
        arrayOf("item", "lantern-mule", "Lantern Mule", "Lantern Mule", "Lantern Mule"),
        arrayOf("item", "smoked-caesar", "Caesar ahumado", "Smoked Caesar", "Gerookte Caesar"),
        arrayOf("item", "maple-sour", "Whisky sour con maple", "Ahorn-Whisky-Sour", "Esdoring-whisky-sour"),
        arrayOf("item", "elderflower-gin", "Gin fizz de flor de saúco", "Holunderblüten-Gin-Fizz", "Vlierblom-jenewer-fizz"),
        arrayOf("item", "espresso-martini", "Espresso Martini", "Espresso Martini", "Espresso Martini"),
        arrayOf("item", "dark-stormy", "Dark and Stormy", "Dark and Stormy", "Dark and Stormy"),
        arrayOf("item", "zero-gimlet", "Gimlet sin alcohol", "Alkoholfreier Gimlet", "Alkoholvrye gimlet"),

        // starters
        arrayOf("item", "pretzel", "Pretzel gigante", "Riesenbrezel", "Reuse-pretzel"),
        arrayOf("item", "wings", "Alitas de pollo", "Chicken Wings", "Hoendervlerkies"),
        arrayOf("item", "nachos", "Nachos de la casa", "Nachos „Pub Style“", "Kroeg-nachos"),
        arrayOf("item", "calamari", "Calamares crujientes", "Knusprige Calamari", "Bros kalamari"),
        arrayOf("item", "spinach-dip", "Dip de espinaca y alcachofa", "Spinat-Artischocken-Dip", "Spinasie-en-artisjokdoop"),
        arrayOf("item", "poutine", "Poutine clásica", "Klassische Poutine", "Klassieke poutine"),
        arrayOf("item", "late-fries", "Papas fritas de medianoche", "Mitternachts-Pommes", "Middernag-slaptjips"),
        arrayOf("item", "mini-burgers", "Mini hamburguesas", "Mini-Burger", "Mini-burgers"),
        arrayOf("item", "grilled-cheese", "Sándwich de queso a la plancha", "Grilled Cheese", "Geroosterde kaastoebroodjie"),
        arrayOf("item", "onion-rings", "Aros de cebolla", "Zwiebelringe", "Uieringe"),

        // burgers & sandwiches
        arrayOf("item", "lantern-burger", "Hamburguesa Copper Lantern", "Copper Lantern Burger", "Copper Lantern-burger"),
        arrayOf("item", "mushroom-burger", "Hamburguesa de champiñones y queso suizo", "Champignon-Burger mit Bergkäse", "Sampioen-en-Switserse-kaasburger"),
        arrayOf("item", "veggie-burger", "Hamburguesa vegetariana", "Veggie-Burger", "Groenteburger"),
        arrayOf("item", "club", "Club sándwich de pollo", "Chicken Club Sandwich", "Hoender-klubtoebroodjie"),
        arrayOf("item", "reuben", "Reuben de Montréal", "Montréal Reuben", "Montréal Reuben"),
        arrayOf("item", "fish-sandwich", "Sándwich de pescado crujiente", "Backfisch-Sandwich", "Bros vistoebroodjie"),

        // mains & salads
        arrayOf("item", "fish-chips", "Fish and chips con rebozado de cerveza", "Fish & Chips im Bierteig", "Vis en tjips in bierbeslag"),
        arrayOf("item", "steak-frites", "Filete con papas fritas", "Steak mit Pommes", "Steak en tjips"),
        arrayOf("item", "shepherd-pie", "Pastel de carne y papa", "Shepherd’s Pie", "Herderspastei"),
        arrayOf("item", "mac-cheese", "Macarrones con tres quesos", "Makkaroni mit drei Käsesorten", "Macaroni met drie kase"),
        arrayOf("item", "salmon", "Salmón glaseado con maple", "Lachs mit Ahornglasur", "Salm met esdoringglasuur"),
        arrayOf("item", "chicken-pot-pie", "Pastel de pollo", "Hähnchen-Pastete", "Hoenderpastei"),
        arrayOf("item", "caesar-salad", "Ensalada César", "Caesar Salad", "Caesar-slaai"),
        arrayOf("item", "harvest-salad", "Ensalada de cosecha de Québec", "Herbstsalat aus Québec", "Québec-oesslaai"),
        arrayOf("item", "falafel-bowl", "Bowl de falafel", "Falafel-Bowl", "Falafel-bak"),
        arrayOf("item", "cauliflower", "Filete de coliflor asada", "Geröstetes Blumenkohl-Steak", "Geroosterde blomkoolsteak"),

        // desserts
        arrayOf("item", "sticky-pudding", "Pudín de toffee", "Sticky Toffee Pudding", "Taai toffiepoeding"),
        arrayOf("item", "cheesecake", "Pay de queso con maple", "Ahorn-Käsekuchen", "Esdoring-kaaskoek"),
        arrayOf("item", "brownie", "Brownie de stout", "Stout-Brownie", "Stout-brownie"),

        // Plateau: sushi & sake
        arrayOf("item", "salmon-maki", "Maki de salmón", "Lachs-Maki", "Salm-maki"),
        arrayOf("item", "spicy-tuna-maki", "Maki de atún picante", "Scharfe Thunfisch-Maki", "Pittige tuna-maki"),
        arrayOf("item", "avocado-maki", "Maki de aguacate y pepino", "Avocado-Gurken-Maki", "Avokado-en-komkommer-maki"),
        arrayOf("item", "salmon-nigiri", "Nigiri de salmón", "Lachs-Nigiri", "Salm-nigiri"),
        arrayOf("item", "tuna-nigiri", "Nigiri de atún", "Thunfisch-Nigiri", "Tuna-nigiri"),
        arrayOf("item", "scallop-nigiri", "Nigiri de callo de hacha", "Jakobsmuschel-Nigiri", "Kammossel-nigiri"),
        arrayOf("item", "junmai-sake", "Sake junmai", "Junmai-Sake", "Junmai-sake"),
        arrayOf("item", "sparkling-sake", "Sake espumoso", "Perlender Sake", "Vonkel-sake"),

        // Plateau specials
        arrayOf("item", "smoked-meat-poutine", "Poutine con carne ahumada", "Poutine mit Smoked Meat", "Poutine met gerookte vleis"),
        arrayOf("item", "maple-miso-bowl", "Bowl de salmón con maple y miso", "Ahorn-Miso-Lachs-Bowl", "Esdoring-miso-salmbak"),
        arrayOf("item", "bagel-board", "Tabla de bagels del Plateau", "Plateau-Bagelplatte", "Plateau-bagelbord"),
        arrayOf("item", "yuzu-sour", "Yuzu Lantern Sour", "Yuzu Lantern Sour", "Yuzu Lantern Sour"),

        // zones
        arrayOf("zone", "upper", "Comedor y bar", "Gastraum & Bar", "Eetsaal en kroeg"),
        arrayOf("zone", "outside", "Terraza", "Terrasse", "Terras"),
        arrayOf("zone", "lower", "Sala de juegos", "Spielbereich", "Speelkamer"),
        arrayOf("zone", "sushi", "Barra de sushi", "Sushi-Bar", "Sushi-toonbank"),

        // floor-object captions
        arrayOf("floor_object", "upper-bar", "Barra de cobre", "Kupfertheke", "Koperkroeg"),
        arrayOf("floor_object", "lower-pool", "Billar", "Billard", "Snoeker"),
        arrayOf("floor_object", "sushi-counter", "Barra de sushi", "Sushi-Theke", "Sushi-toonbank"),
    )

    /** Size / container labels by their English label: Spanish, German, Afrikaans. */
    val variantLabels: Map<String, Triple<String, String, String>> = mapOf(
        "Regular" to Triple("Normal", "Normal", "Gewoon"),
        "Glass" to Triple("Copa", "Glas", "Glas"),
        "Bottle" to Triple("Botella", "Flasche", "Bottel"),
        "20 oz pint" to Triple("Pinta de 20 oz", "Pint (20 oz)", "Pint (20 oz)"),
        "60 oz pitcher" to Triple("Jarra de 60 oz", "Krug (60 oz)", "Kan (60 oz)"),
        "2 oz glass" to Triple("Copa de 2 oz", "Glas (2 oz)", "Glas (2 oz)"),
        "375 ml bottle" to Triple("Botella de 375 ml", "Flasche (375 ml)", "Bottel (375 ml)"),
        "180 ml carafe" to Triple("Jarrita de 180 ml", "Karaffe (180 ml)", "Karaf (180 ml)"),
        "720 ml bottle" to Triple("Botella de 720 ml", "Flasche (720 ml)", "Bottel (720 ml)"),
    )
}
