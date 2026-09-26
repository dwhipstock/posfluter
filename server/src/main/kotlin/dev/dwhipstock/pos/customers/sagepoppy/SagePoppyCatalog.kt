package dev.dwhipstock.pos.customers.sagepoppy

import dev.dwhipstock.pos.customers.sagepoppy.SagePoppySeed.Cat
import dev.dwhipstock.pos.customers.sagepoppy.SagePoppySeed.Product
import dev.dwhipstock.pos.sdk.Crv
import kotlin.math.exp
import kotlin.math.roundToLong

/**
 * Sage & Poppy's full shelf: about 5,000 products, the size of a real
 * independent bottle shop (1,500–5,000 SKUs). Generated, not hand-written,
 * but deterministic: a fixed seed and fixed word lists, so every store, test
 * and demo gets the same catalog, barcodes and prices.
 *
 * The mix follows a real shop: beer ~35% (styles × fictional breweries ×
 * formats), wine ~30% (varietals × fictional wineries × regions, California
 * heavy), spirits ~25% (types × fictional brands × sizes), seltzers and
 * ready-to-drink ~5%, and snacks, mixers, ice and sundries ~5%. Every name is
 * made up; nothing is a real brand or producer.
 *
 * Each product carries a subcategory (style / varietal / type) and a size or
 * pack label for the counter's filters, and a demo sales weight: a long tail
 * (1/rank) so about 20% of the products make about 80% of seeded sales.
 *
 * The ~50 hand-written first-boot products ([SagePoppySeed.products]) are
 * part of it, unchanged (same ids, names, prices and barcodes); the generated
 * ones take item numbers from [FIRST_SEQ] up under the same in-store prefix.
 */
object SagePoppyCatalog {
    /** Bump when the generated catalog changes; existing stores add what's new (see [SagePoppyCatalogUpgrade]). */
    const val VERSION = 1
    const val SEED = 20_260_925L
    const val FIRST_SEQ = 10_000

    /** Target counts per department, including the hand-written products. */
    const val BEER = 1750
    const val WINE = 1500
    const val SPIRITS = 1250
    const val SELTZERS = 250
    const val OTHER = 250 // mixers + snacks + ice + sundries
    const val TOTAL = BEER + WINE + SPIRITS + SELTZERS + OTHER

    /** Brand, subcategory and size of the hand-written products (they predate the facets). */
    val curatedFacets: Map<String, Triple<String, String, String>> = mapOf(
        "golden-lager-can" to Triple("Golden Hour", "Lager", "12 oz can"),
        "golden-lager-6" to Triple("Golden Hour", "Lager", "6-pack"),
        "golden-lager-12" to Triple("Golden Hour", "Lager", "12-pack"),
        "hazy-ipa-can" to Triple("Hazy Hills", "Hazy IPA", "16 oz tallboy"),
        "hazy-ipa-4" to Triple("Hazy Hills", "Hazy IPA", "4-pack"),
        "silver-pils-6" to Triple("Silver Lake", "Pilsner", "6-pack"),
        "coast-lager-12" to Triple("Coastline", "Mexican-Style Lager", "12-pack"),
        "night-owl-stout" to Triple("Night Owl", "Stout", "22 oz bomber"),
        "canyon-amber-6" to Triple("Canyon", "Amber Ale", "6-pack"),
        "sunset-wheat-6" to Triple("Sunset", "Wheat Beer", "6-pack"),
        "big-pour-lager" to Triple("Big Pour", "Lager", "25 oz can"),
        "backyard-light" to Triple("Backyard", "Light Lager", "24 oz can"),
        "orchard-cider-4" to Triple("Orchard", "Cider", "4-pack"),
        "na-hop-lager-6" to Triple("Zero Proof", "Non-Alcoholic", "6-pack"),
        "coastal-cab" to Triple("Coastal Ridge", "Cabernet Sauvignon", "750 ml"),
        "valley-chard" to Triple("Valley Oak", "Chardonnay", "750 ml"),
        "sunlit-rose" to Triple("Sunlit", "Rosé", "750 ml"),
        "hillside-pinot" to Triple("Hillside", "Pinot Noir", "750 ml"),
        "everyday-red" to Triple("Everyday", "Red Blend", "1.5 L"),
        "bright-bubbles" to Triple("Bright Bubbles", "Sparkling", "750 ml"),
        "crisp-sauv" to Triple("Crisp", "Sauvignon Blanc", "750 ml"),
        "boxed-white" to Triple("Table", "White Blend", "3 L box"),
        "coast-vodka" to Triple("Silver Coast", "Vodka", "750 ml"),
        "coast-vodka-big" to Triple("Silver Coast", "Vodka", "1.75 L"),
        "agave-blanco" to Triple("Agave Sun", "Tequila Blanco", "750 ml"),
        "old-barn-bourbon" to Triple("Old Barn", "Bourbon", "750 ml"),
        "harbor-rum" to Triple("Harbor", "Spiced Rum", "750 ml"),
        "juniper-gin" to Triple("Juniper Row", "Gin", "750 ml"),
        "highland-scotch" to Triple("Highland", "Blended Scotch", "750 ml"),
        "vodka-mini" to Triple("Silver Coast", "Vodka", "50 ml"),
        "coffee-liqueur" to Triple("Night Shift", "Liqueur", "375 ml"),
        "wave-lime" to Triple("Wave", "Hard Seltzer", "12 oz can"),
        "wave-variety-12" to Triple("Wave", "Hard Seltzer", "12-pack"),
        "wave-cherry-6" to Triple("Wave", "Hard Seltzer", "6-pack"),
        "paloma-4" to Triple("Paloma", "Canned Cocktail", "4-pack"),
        "marg-cooler-4" to Triple("Margarita", "Cooler", "4-pack"),
        "hard-tea" to Triple("Hard Tea", "Hard Tea", "24 oz can"),
        "club-soda" to Triple("House", "Club Soda", "1 L"),
        "tonic" to Triple("House", "Tonic", "1 L"),
        "ginger-beer-4" to Triple("House", "Ginger Beer", "4-pack"),
        "cola-2l" to Triple("House", "Soda", "2 L"),
        "sparkling-12" to Triple("House", "Sparkling Water", "12-pack"),
        "lime-juice" to Triple("House", "Juice", "16 oz"),
        "marg-mix" to Triple("House", "Cocktail Mix", "1 L"),
        "bloody-mix" to Triple("House", "Cocktail Mix", "32 oz"),
        "chips-sea-salt" to Triple("House", "Chips", "8 oz"),
        "tortilla-chips" to Triple("House", "Chips", "13 oz"),
        "peanuts" to Triple("House", "Nuts", "6 oz"),
        "jerky" to Triple("House", "Jerky", "3 oz"),
        "pretzels" to Triple("House", "Pretzels", "10 oz"),
        "choc-bar" to Triple("House", "Candy", "Single"),
        "party-cups" to Triple("House", "Party Supplies", "50-count"),
        "ice-7" to Triple("Party Ice", "Bagged Ice", "7 lb"),
        "ice-20" to Triple("Party Ice", "Bagged Ice", "20 lb"),
    )

    /** Every product: the hand-written ones (with their facets) then the generated ones, weighted. */
    val products: List<Product> by lazy { generate() }

    /** The generated products only (what an existing store adds). */
    val generated: List<Product> get() = products.filter { it.seq >= FIRST_SEQ || it.barcodeless }

    // ------------------------------------------------------------------ word lists

    private val breweries = listOf(
        "Fogline", "Tule Elk", "Quartz Hill", "Marigold", "Kelp Forest", "Sundial", "Jackrabbit Flats",
        "Poppy Field", "Salt Marsh", "Driftline", "Granite Pass", "Yucca Wash", "Condor Ridge", "Brass Tack",
        "Low Tide", "Night Heron", "Otter Cove", "Sagebrush", "Loma Linda Lane", "Pelican Point", "Wild Plum",
        "Twin Pines", "Hummingbird", "Red Clay", "Buckeye", "Gull Rock", "Canyon Wren", "Pacific Fog",
        "Golden Poppy", "Sand Dollar", "Bishop Pine", "Mesa Grove", "Lupine", "Manzanita", "Tumbleweed",
        "Zephyr", "Quail Run", "Trestle", "Whistlestop", "Cable Car", "Orchard Row", "Bottlebrush",
        "Starling", "Riptide", "Coyote Moon", "Ironwood Alley", "Harvest Moon Hollow", "Sea Lion",
    )
    private val brewerySuffix = listOf("Brewing", "Beer Co.", "Ales", "Brewery", "Brewing Co.")
    private val beerWordA = listOf(
        "Low", "High", "Golden", "Salty", "Sunny", "Wild", "Lazy", "Big", "Little", "Coastal", "Desert",
        "Midnight", "Morning", "Summer", "Canyon", "Harbor", "Orchard", "Pacific", "Foggy", "Rusty", "Silver",
        "Velvet", "Crooked", "Easy", "Bright", "Dusty", "Hidden", "Lucky", "Sleepy", "Electric",
    )
    private val beerWordB = listOf(
        "Tide", "Wave", "Ridge", "Trail", "Swell", "Bloom", "Drift", "Dune", "Pier", "Grove", "Mesa",
        "Current", "Break", "Horizon", "Hollow", "Road", "Porch", "Meadow", "Summit", "Harvest", "Haze",
        "Sunrise", "Tumble", "Rover", "Lookout", "Switchback", "Boardwalk", "Campfire", "Getaway", "Daydream",
    )

    /** Beer styles: subcategory, how "big/light" (drives formats), price multiplier. */
    private data class Style(val name: String, val macro: Boolean, val mult: Double, val appeal: Double)
    private val styles = listOf(
        Style("Lager", true, 0.85, 1.6), Style("Light Lager", true, 0.8, 1.5),
        Style("Mexican-Style Lager", true, 0.9, 1.5), Style("Pilsner", false, 1.0, 1.0),
        Style("IPA", false, 1.1, 1.6), Style("Hazy IPA", false, 1.25, 1.5), Style("Double IPA", false, 1.35, 0.9),
        Style("West Coast IPA", false, 1.15, 1.1), Style("Pale Ale", false, 1.0, 1.0),
        Style("Amber Ale", false, 1.0, 0.7), Style("Brown Ale", false, 1.0, 0.5),
        Style("Wheat Beer", false, 1.0, 0.8), Style("Blonde Ale", false, 0.95, 0.8),
        Style("Stout", false, 1.15, 0.7), Style("Porter", false, 1.1, 0.5), Style("Sour", false, 1.3, 0.6),
        Style("Saison", false, 1.2, 0.4), Style("Cider", false, 1.1, 0.9), Style("Non-Alcoholic", false, 0.95, 0.6),
    )

    /** A pack/container format: size label, the words in the name, units, container size, price band ($). */
    private data class Format(
        val size: String, val nameTail: String, val pack: Int, val crv: Crv.Size,
        val lo: Double, val hi: Double, val appeal: Double,
    )
    private val macroFormats = listOf(
        Format("12 oz can", "12 oz can", 1, Crv.Size.SMALL, 1.49, 2.29, 1.3),
        Format("25 oz can", "25 oz can", 1, Crv.Size.LARGE, 2.49, 3.49, 1.2),
        Format("6-pack", "6-pack 12 oz cans", 6, Crv.Size.SMALL, 7.99, 10.99, 1.6),
        Format("12-pack", "12-pack 12 oz cans", 12, Crv.Size.SMALL, 13.99, 18.99, 1.8),
        Format("24-pack", "24-pack 12 oz cans", 24, Crv.Size.SMALL, 22.99, 32.99, 1.3),
        Format("18-pack", "18-pack 12 oz cans", 18, Crv.Size.SMALL, 17.99, 24.99, 1.0),
    )
    private val craftFormats = listOf(
        Format("12 oz can", "12 oz can", 1, Crv.Size.SMALL, 2.29, 3.49, 1.0),
        Format("16 oz tallboy", "16 oz tallboy", 1, Crv.Size.SMALL, 2.99, 4.99, 1.2),
        Format("4-pack", "4-pack 16 oz cans", 4, Crv.Size.SMALL, 11.99, 18.99, 1.3),
        Format("6-pack", "6-pack 12 oz bottles", 6, Crv.Size.SMALL, 9.99, 15.99, 1.5),
        Format("12-pack", "12-pack 12 oz cans", 12, Crv.Size.SMALL, 17.99, 25.99, 1.1),
        Format("22 oz bomber", "22 oz bomber", 1, Crv.Size.SMALL, 5.99, 11.99, 0.6),
    )

    private val wineries = listOf(
        "Oak Shadow", "Quiet Creek", "Hawk Hill", "Sunstone Bend", "Morning Fog", "Two Ravens", "Coyote Brush",
        "Painted Ridge", "Lark Meadow", "Cedar Gulch", "Silver Sage", "Firefly Lane", "Rolling Mesa", "Tall Grass",
        "Bluebird Bench", "Copper Hills", "Lone Cypress", "River Bend", "Wind Gap Road", "Owl Canyon",
        "Crescent Moon", "Juniper Flat", "Fox Hollow", "Stag Rock", "Honey Oak", "Terrace Hill", "Sparrow Lane",
        "Poppy Ridge", "Driftwood", "Saltbrush", "Golden Bough", "Long Shadow Lane", "Heron Creek", "Pebble Flat",
        "Sycamore Row", "Blue Heron Bend", "Moonrise", "Buckthorn", "Wildflower Way", "Fern Gully", "Laurel Bench",
        "Coast Live Oak", "Chaparral", "Dry Creek Bend", "Summer Hill", "Canyon Sky", "Starlight", "Hidden Spring",
        "Tin Roof", "Olive Grove", "Mission Bell", "Meadowlark", "Gold Rush Road", "Bristlecone", "Sea Smoke Hollow",
        "Paper Kite", "Wagon Trail", "Lantana", "Dune Grass", "Vista Point",
    )
    private val winerySuffix = listOf("Cellars", "Vineyards", "Winery", "Estate", "Wine Co.")
    private data class Varietal(val name: String, val red: Boolean, val mult: Double, val appeal: Double)
    private val varietals = listOf(
        Varietal("Cabernet Sauvignon", true, 1.3, 1.6), Varietal("Pinot Noir", true, 1.25, 1.4),
        Varietal("Chardonnay", false, 1.0, 1.6), Varietal("Sauvignon Blanc", false, 0.95, 1.2),
        Varietal("Zinfandel", true, 1.05, 0.8), Varietal("Merlot", true, 1.0, 0.8),
        Varietal("Syrah", true, 1.1, 0.5), Varietal("Rosé", false, 0.95, 1.1),
        Varietal("Red Blend", true, 1.0, 1.4), Varietal("White Blend", false, 0.9, 0.6),
        Varietal("Pinot Grigio", false, 0.9, 1.0), Varietal("Riesling", false, 0.95, 0.5),
        Varietal("Sparkling", false, 1.05, 1.1), Varietal("Malbec", true, 1.0, 0.7),
        Varietal("Petite Sirah", true, 1.1, 0.4), Varietal("Grenache", true, 1.1, 0.4),
        Varietal("Viognier", false, 1.05, 0.3), Varietal("Moscato", false, 0.85, 0.6),
    )
    /** Regions (wine areas, not producers), California heavy. */
    private val regions = listOf(
        "Napa Valley" to 1.6, "Sonoma Coast" to 1.4, "Paso Robles" to 1.2, "Central Coast" to 1.0,
        "Lodi" to 0.85, "Santa Barbara County" to 1.3, "Mendocino" to 1.1, "Russian River Valley" to 1.45,
        "Anderson Valley" to 1.3, "Sierra Foothills" to 1.0, "Monterey" to 1.05, "Livermore Valley" to 1.0,
        "Temecula Valley" to 1.0, "California" to 0.8, "Willamette Valley" to 1.35, "Columbia Valley" to 1.1,
        "Mendoza" to 0.9, "Central Valley" to 0.8,
    )
    private val wineSizes = listOf(
        Format("750 ml", "750 ml", 1, Crv.Size.NONE, 9.99, 34.99, 1.4),
        Format("1.5 L", "1.5 L", 1, Crv.Size.NONE, 11.99, 24.99, 1.0),
        Format("3 L box", "3 L box", 1, Crv.Size.NONE, 19.99, 29.99, 0.9),
        Format("375 ml", "375 ml", 1, Crv.Size.NONE, 7.99, 17.99, 0.6),
    )

    private val distilleries = listOf(
        "Silver Coast", "Agave Sun", "Old Barn", "Harbor", "Juniper Row", "Highland", "Night Shift",
        "Black Bear Hollow", "Canyon Road", "Blue Agave Moon", "Redwood Still", "Salt Flats", "Iron Gate",
        "Rattlesnake Ridge", "Coastal Fog", "Sierra Pine", "Lost Mine", "Pacific Crest", "Golden Gate Row",
        "Twin Lanterns", "Desert Rose", "Hollow Oak", "Sunset Cask", "Foghorn Point", "Mesa Verde Row",
        "Cinder Cone", "Blue Lupine", "Wildcat Creek", "North Star", "Prospector", "Condor", "Sea Glass",
        "Bramble Lane", "Obsidian", "Twelve Mile", "Horsetail Falls", "Quarry Road", "Tin Cup Canyon",
        "Morning Star", "Cask & Compass", "Tidewater", "Marble Canyon", "Ember Hill", "Lighthouse Row",
    )
    private data class SpiritType(val name: String, val mult: Double, val appeal: Double, val expressions: List<String>)
    private val spiritTypes = listOf(
        SpiritType("Vodka", 0.9, 1.8, listOf("", "Premium", "Citrus", "Vanilla", "Pepper", "Cucumber")),
        SpiritType("Gin", 1.0, 0.9, listOf("", "London Dry", "Botanical", "Navy Strength", "Old Tom")),
        SpiritType("Bourbon", 1.25, 1.3, listOf("", "Small Batch", "Single Barrel", "Bottled-in-Bond", "Wheated")),
        SpiritType("Rye Whiskey", 1.25, 0.7, listOf("", "Straight", "Barrel Proof")),
        SpiritType("American Whiskey", 1.05, 0.6, listOf("", "Honey", "Blended")),
        SpiritType("Irish Whiskey", 1.15, 0.6, listOf("", "Triple Distilled", "Stout Cask")),
        SpiritType("Blended Scotch", 1.1, 0.6, listOf("", "12 Year", "Smoky")),
        SpiritType("Single Malt Scotch", 1.8, 0.4, listOf("12 Year", "15 Year", "Sherry Cask")),
        SpiritType("Tequila Blanco", 1.15, 1.4, listOf("", "Silver", "High Proof")),
        SpiritType("Tequila Reposado", 1.3, 0.9, listOf("", "Oak Rested")),
        SpiritType("Tequila Anejo", 1.6, 0.4, listOf("", "Extra")),
        SpiritType("Mezcal", 1.5, 0.4, listOf("Espadin", "Joven")),
        SpiritType("White Rum", 0.85, 0.8, listOf("", "Silver", "Coconut")),
        SpiritType("Spiced Rum", 0.9, 0.8, listOf("", "Dark Spiced")),
        SpiritType("Dark Rum", 1.0, 0.4, listOf("", "Aged 8 Year")),
        SpiritType("Brandy", 0.95, 0.5, listOf("", "VS", "VSOP", "Apple")),
        SpiritType("Liqueur", 0.85, 0.6, listOf("Coffee", "Orange", "Amaretto", "Peach", "Herbal", "Cream")),
        SpiritType("Cinnamon Whisky", 0.8, 0.7, listOf("")),
    )
    private val spiritSizes = listOf(
        Format("50 ml", "50 ml", 1, Crv.Size.NONE, 1.49, 3.99, 1.4),
        Format("375 ml", "375 ml", 1, Crv.Size.NONE, 8.99, 19.99, 0.8),
        Format("750 ml", "750 ml", 1, Crv.Size.NONE, 15.99, 44.99, 1.5),
        Format("1 L", "1 L", 1, Crv.Size.NONE, 19.99, 39.99, 0.8),
        Format("1.75 L", "1.75 L", 1, Crv.Size.NONE, 24.99, 54.99, 1.2),
    )

    private val rtdBrands = listOf(
        "Wave", "Sun Porch", "Palm Shade", "Fizz Theory", "Salt & Lime", "Lagoon", "Tall Order", "Chill Pier",
        "Backporch", "Sunny Side",
    )
    private val rtdKinds = listOf(
        "Hard Seltzer" to listOf("Lime", "Black Cherry", "Grapefruit", "Mango", "Watermelon", "Pineapple", "Lemon", "Passion Fruit", "Variety"),
        "Canned Cocktail" to listOf("Margarita", "Paloma", "Mojito", "Vodka Soda", "Gin & Tonic", "Mule", "Ranch Water"),
        "Hard Tea" to listOf("Lemon", "Peach", "Half & Half", "Raspberry"),
        "Hard Lemonade" to listOf("Classic", "Strawberry", "Blueberry"),
    )
    private val rtdFormats = listOf(
        Format("12 oz can", "12 oz can", 1, Crv.Size.SMALL, 2.49, 3.49, 1.0),
        Format("24 oz can", "24 oz can", 1, Crv.Size.LARGE, 3.49, 4.49, 1.0),
        Format("4-pack", "4-pack 12 oz cans", 4, Crv.Size.SMALL, 11.99, 15.99, 0.9),
        Format("6-pack", "6-pack 12 oz cans", 6, Crv.Size.SMALL, 9.99, 13.99, 1.3),
        Format("12-pack", "12-pack 12 oz cans", 12, Crv.Size.SMALL, 17.99, 22.99, 1.5),
    )

    // ------------------------------------------------------------------ generation

    private class Rng(seed: Long) {
        private val r = java.util.Random(seed)
        fun int(n: Int) = r.nextInt(n)
        fun double() = r.nextDouble()
        fun gauss() = r.nextGaussian()
        fun <T> pick(list: List<T>): T = list[r.nextInt(list.size)]
        fun <T> sample(list: List<T>, n: Int): List<T> = list.shuffled(r).take(n.coerceAtMost(list.size))
        fun price(lo: Double, hi: Double, mult: Double = 1.0): Long {
            val v = (lo + (hi - lo) * r.nextDouble()) * mult
            val dollars = v.toLong().coerceAtLeast(0)
            val cents = if (r.nextInt(3) == 0) 49 else 99
            return (dollars * 100 + cents).coerceAtLeast(99)
        }
    }

    /** A generated product before its weight is known. */
    private class Draft(
        val name: String, val brand: String, val cat: Cat, val sub: String, val size: String,
        val cents: Long, val ageRestricted: Boolean, val crv: Crv.Size, val pack: Int, val taxable: Boolean,
        val appeal: Double, val barcodeless: Boolean = false,
    )

    /** A fresh build (the same list every time — [products] caches one). */
    fun generate(): List<Product> {
        val rng = Rng(SEED)
        val curated = SagePoppySeed.products
        val curatedCount = curated.groupingBy { it.cat }.eachCount()
        fun need(cat: Cat, total: Int) = total - (curatedCount[cat] ?: 0)
        val names = curated.map { it.name.lowercase() }.toMutableSet()
        val drafts = mutableListOf<Draft>()
        fun add(d: Draft): Boolean {
            if (!names.add(d.name.lowercase())) return false
            drafts += d
            return true
        }

        // beer & cider
        run {
            val target = need(Cat.BEER, BEER)
            var made = 0
            var round = 0
            while (made < target) {
                for (brewery in breweries) {
                    if (made >= target) break
                    val brand = "$brewery ${brewerySuffix[(breweries.indexOf(brewery) + round) % brewerySuffix.size]}"
                    val short = brewery
                    for (style in rng.sample(styles, 3 + rng.int(4))) {
                        if (made >= target) break
                        val beerName = "${rng.pick(beerWordA)} ${rng.pick(beerWordB)}"
                        val label = if (style.name == "Non-Alcoholic") "Non-Alcoholic Brew" else style.name
                        val formats = if (style.macro) macroFormats else craftFormats
                        for (f in rng.sample(formats, 2 + rng.int(3))) {
                            if (made >= target) break
                            val ok = add(Draft(
                                name = "$short $beerName $label ${f.nameTail}", brand = brand, cat = Cat.BEER,
                                sub = style.name, size = f.size,
                                cents = rng.price(f.lo, f.hi, style.mult),
                                ageRestricted = style.name != "Non-Alcoholic", crv = f.crv, pack = f.pack, taxable = true,
                                appeal = style.appeal * f.appeal,
                            ))
                            if (ok) made++
                        }
                    }
                }
                round++
            }
        }

        // wine
        run {
            val target = need(Cat.WINE, WINE)
            var made = 0
            var round = 0
            while (made < target) {
                for ((i, winery) in wineries.withIndex()) {
                    if (made >= target) break
                    val brand = "$winery ${winerySuffix[(i + round) % winerySuffix.size]}"
                    for (v in rng.sample(varietals, 4 + rng.int(4))) {
                        if (made >= target) break
                        // California about 80% of the shelf
                        val (region, regionMult) = if (rng.double() < 0.8) rng.pick(regions.take(14)) else rng.pick(regions.drop(14))
                        val sizes = if (rng.double() < 0.7) listOf(wineSizes[0]) else rng.sample(wineSizes, 2)
                        for (f in sizes) {
                            if (made >= target) break
                            val mult = if (f.size == "750 ml" || f.size == "375 ml") v.mult * regionMult else 1.0
                            val ok = add(Draft(
                                name = "$winery ${v.name} $region ${f.nameTail}", brand = brand, cat = Cat.WINE,
                                sub = v.name, size = f.size, cents = rng.price(f.lo, f.hi, mult).coerceAtMost(12_999),
                                ageRestricted = true, crv = Crv.Size.NONE, pack = 1, taxable = true,
                                appeal = v.appeal * f.appeal / regionMult,
                            ))
                            if (ok) made++
                        }
                    }
                }
                round++
            }
        }

        // spirits
        run {
            val target = need(Cat.SPIRITS, SPIRITS)
            var made = 0
            while (made < target) {
                for (brand in distilleries) {
                    if (made >= target) break
                    for (t in rng.sample(spiritTypes, 3 + rng.int(3))) {
                        if (made >= target) break
                        val expr = rng.pick(t.expressions)
                        val title = listOf(brand, expr, t.name).filter { it.isNotEmpty() }.joinToString(" ")
                        for (f in rng.sample(spiritSizes, 2 + rng.int(3))) {
                            if (made >= target) break
                            val ok = add(Draft(
                                name = "$title ${f.nameTail}", brand = brand, cat = Cat.SPIRITS, sub = t.name,
                                size = f.size, cents = rng.price(f.lo, f.hi, t.mult),
                                ageRestricted = true, crv = Crv.Size.NONE, pack = 1, taxable = true,
                                appeal = t.appeal * f.appeal,
                            ))
                            if (ok) made++
                        }
                    }
                }
            }
        }

        // hard seltzers & ready-to-drink
        run {
            val target = need(Cat.SELTZERS, SELTZERS)
            var made = 0
            val combos = rtdBrands.flatMap { b -> rtdKinds.flatMap { (kind, flavors) -> flavors.map { Triple(b, kind, it) } } }
            for ((brand, kind, flavor) in combos.shuffled(java.util.Random(SEED + 4))) {
                if (made >= target) break
                for (f in rng.sample(rtdFormats, 1 + rng.int(2))) {
                    if (made >= target) break
                    val ok = add(Draft(
                        name = "$brand $kind $flavor ${f.nameTail}", brand = brand, cat = Cat.SELTZERS, sub = kind,
                        size = f.size, cents = rng.price(f.lo, f.hi), ageRestricted = true, crv = f.crv,
                        pack = f.pack, taxable = true, appeal = f.appeal * if (kind == "Hard Seltzer") 1.4 else 0.9,
                    ))
                    if (ok) made++
                }
            }
        }

        // snacks, mixers, ice, sundries
        run {
            val target = OTHER - curated.count { it.cat in setOf(Cat.MIXERS, Cat.SNACKS, Cat.ICE) }
            val other = otherProducts(rng)
            other.take(target).forEach { add(it) }
        }

        // weights: rank by appeal × a long-tailed random factor; the store's
        // familiar first-boot products sit near the top
        data class Scored(val product: Product, val score: Double)
        val scored = mutableListOf<Scored>()
        curated.forEach { p ->
            val (brand, sub, size) = curatedFacets.getValue(p.id)
            scored += Scored(p.copy(brand = brand, subcategory = sub, size = size), 6.0 * exp(rng.gauss() * 0.5))
        }
        var seq = FIRST_SEQ
        drafts.forEach { d ->
            val s = seq++
            val deptBoost = when (d.cat) {
                Cat.ICE -> 5.0; Cat.BEER -> 1.3; Cat.SELTZERS -> 1.2; Cat.SPIRITS -> 1.0; Cat.WINE -> 0.9
                Cat.SNACKS -> 1.1; Cat.MIXERS -> 1.0; Cat.SUNDRIES -> 0.6
            }
            val p = Product(
                id = if (d.barcodeless) "sp-" + slug(d.name) else "sp$s", name = d.name, cat = d.cat,
                cents = d.cents, seq = s, ageRestricted = d.ageRestricted, crv = d.crv, pack = d.pack,
                taxable = d.taxable, brand = d.brand, subcategory = d.sub, size = d.size,
                barcodeless = d.barcodeless,
            )
            scored += Scored(p, d.appeal * deptBoost * exp(rng.gauss() * 1.1))
        }
        val ranked = scored.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.product.seq })
        val weightById = ranked.mapIndexed { i, s -> s.product.id to (1_000_000.0 / (i + 1)).roundToLong().coerceAtLeast(1) }.toMap()
        return scored.map { it.product.copy(salesWeight = weightById.getValue(it.product.id).toInt()) }
    }

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)

    /** The small departments, written out: real shops stock a few of each. */
    private fun otherProducts(rng: Rng): List<Draft> {
        val out = mutableListOf<Draft>()
        fun snack(brand: String, sub: String, what: String, size: String, lo: Double, hi: Double, appeal: Double = 1.0) {
            out += Draft("$brand $what $size", brand, Cat.SNACKS, sub, size, rng.price(lo, hi), false, Crv.Size.NONE, 1, false, appeal)
        }
        fun mixer(brand: String, sub: String, what: String, size: String, oz: Double?, pack: Int, taxable: Boolean, lo: Double, hi: Double) {
            // carbonated drinks carry CRV (by container size) and sales tax; juice and mixes don't
            val crv = if (oz != null) Crv.sizeFor(oz) else Crv.Size.NONE
            out += Draft("$brand $what $size", brand, Cat.MIXERS, sub, size, rng.price(lo, hi), false, crv, pack, taxable, 0.9)
        }
        fun sundry(what: String, size: String, sub: String, lo: Double, hi: Double) {
            out += Draft("$what $size".trim(), "House", Cat.SUNDRIES, sub, size, rng.price(lo, hi), false, Crv.Size.NONE, 1, true, 0.5)
        }
        // ice first (the top sellers of any bottle shop), then the rest interleaved
        out += Draft("Party Ice 5 lb bag", "Party Ice", Cat.ICE, "Bagged Ice", "5 lb", 299, false, Crv.Size.NONE, 1, false, 1.5)
        out += Draft("Party Ice 10 lb bag", "Party Ice", Cat.ICE, "Bagged Ice", "10 lb", 449, false, Crv.Size.NONE, 1, false, 1.2)
        out += Draft("Block Ice 10 lb", "Party Ice", Cat.ICE, "Block Ice", "10 lb", 499, false, Crv.Size.NONE, 1, false, 0.5)
        out += Draft("Crushed Ice 7 lb bag", "Party Ice", Cat.ICE, "Bagged Ice", "7 lb", 399, false, Crv.Size.NONE, 1, false, 0.8)
        // the unscannables: sold by tapping a quick key, never by barcode
        out += Draft("Paper Bag", "House", Cat.SUNDRIES, "Bags", "Single", 10, false, Crv.Size.NONE, 1, false, 3.0, barcodeless = true)
        out += Draft("Lime (each)", "House", Cat.MIXERS, "Fresh Garnish", "Single", 35, false, Crv.Size.NONE, 1, false, 1.5, barcodeless = true)
        out += Draft("Lemon (each)", "House", Cat.MIXERS, "Fresh Garnish", "Single", 45, false, Crv.Size.NONE, 1, false, 0.8, barcodeless = true)

        val snackBrands = listOf("Golden Crunch", "Sierra Kettle", "Pacific Pantry", "Trail Hound", "Sunny Acres", "Dockside", "Hilltop Snack Co.", "Porchlight Pantry", "Tidal Treats")
        val chips = listOf("Sea Salt Kettle Chips", "Salt & Vinegar Chips", "Jalapeno Chips", "BBQ Chips", "Tortilla Rounds", "Lime Tortilla Chips", "Pita Chips")
        val nuts = listOf("Salted Peanuts", "Honey Roasted Peanuts", "Smoked Almonds", "Cashews", "Trail Mix", "Pistachios")
        val jerky = listOf("Original Beef Jerky", "Teriyaki Beef Jerky", "Peppered Beef Jerky", "Meat Sticks")
        val candy = listOf("Dark Chocolate Bar", "Milk Chocolate Bar", "Gummy Bears", "Sour Worms", "Peanut Butter Cups", "Mints")
        val other = listOf("Pretzel Twists" to "Pretzels", "Pretzel Rods" to "Pretzels", "Butter Popcorn" to "Popcorn", "Kettle Corn" to "Popcorn", "Cheese Crackers" to "Crackers", "Beef Chili Cup" to "Snack Meals")
        for (b in snackBrands) {
            chips.forEach { snack(b, "Chips", it, if (rng.int(2) == 0) "2 oz" else "8 oz", 1.49, 5.49) }
            nuts.take(3 + rng.int(3)).forEach { snack(b, "Nuts", it, "6 oz", 2.99, 7.99) }
            jerky.take(2 + rng.int(2)).forEach { snack(b, "Jerky", it, "3 oz", 5.99, 9.99) }
            candy.take(3 + rng.int(3)).forEach { snack(b, "Candy", it, "Single", 0.99, 2.99, 0.8) }
            other.take(2 + rng.int(4)).forEach { (what, sub) -> snack(b, sub, what, "10 oz", 2.99, 5.49) }
        }
        val mixerBrands = listOf("Clear Creek", "Bubble & Brine", "Harbor Mixers", "Sunny Grove", "Fizzwell")
        for (b in mixerBrands) {
            mixer(b, "Club Soda", "Club Soda", "1 L", 33.8, 1, true, 1.79, 2.99)
            mixer(b, "Tonic", "Tonic Water", "1 L", 33.8, 1, true, 1.99, 3.49)
            mixer(b, "Tonic", "Tonic Water 4-pack", "7.5 oz cans", 7.5, 4, true, 4.49, 6.99)
            mixer(b, "Ginger Beer", "Ginger Beer 4-pack", "12 oz bottles", 12.0, 4, true, 5.99, 8.99)
            mixer(b, "Soda", "Lemon-Lime Soda", "2 L", 67.6, 1, true, 2.29, 3.49)
            mixer(b, "Soda", "Cola 12-pack", "12 oz cans", 12.0, 12, true, 6.99, 9.99)
            mixer(b, "Sparkling Water", "Sparkling Water Lime 8-pack", "12 oz cans", 12.0, 8, true, 4.99, 6.99)
            mixer(b, "Juice", "Orange Juice", "32 oz", null, 1, false, 3.49, 5.49)
            mixer(b, "Juice", "Cranberry Juice", "64 oz", null, 1, false, 3.99, 5.99)
            mixer(b, "Cocktail Mix", "Margarita Mix", "1.75 L", null, 1, false, 4.99, 7.99)
            mixer(b, "Cocktail Mix", "Bloody Mary Mix", "1 L", null, 1, false, 4.99, 7.49)
            mixer(b, "Cocktail Mix", "Simple Syrup", "12 oz", null, 1, false, 3.99, 6.99)
        }
        sundry("Plastic Cups", "50-count", "Party Supplies", 3.49, 5.99)
        sundry("Party Cups", "100-count", "Party Supplies", 5.99, 8.99)
        sundry("Paper Plates", "40-count", "Party Supplies", 3.99, 5.99)
        sundry("Napkins", "100-count", "Party Supplies", 1.99, 3.49)
        sundry("Bottle Opener", "Single", "Barware", 2.99, 5.99)
        sundry("Waiter's Corkscrew", "Single", "Barware", 5.99, 9.99)
        sundry("Wine Gift Bag", "Single", "Gifts", 1.99, 3.99)
        sundry("Two-Bottle Gift Box", "Single", "Gifts", 4.99, 7.99)
        sundry("Insulated Can Sleeve", "Single", "Barware", 3.99, 6.99)
        sundry("Foam Cooler", "28 qt", "Coolers", 4.99, 7.99)
        sundry("Reusable Tote", "Single", "Bags", 0.99, 1.99)
        sundry("Birthday Candles", "24-count", "Party Supplies", 1.99, 3.49)
        sundry("AA Batteries", "4-pack", "Household", 4.99, 7.99)
        sundry("Paper Towels", "2-roll", "Household", 2.99, 4.99)
        sundry("Cocktail Picks", "50-count", "Barware", 1.99, 3.49)
        sundry("Drink Stirrers", "100-count", "Barware", 1.49, 2.99)
        sundry("Ice Scoop", "Single", "Barware", 2.99, 4.99)
        sundry("Gift Card Envelope", "Single", "Gifts", 0.99, 1.49)
        // ice and the unscannables first; everything else interleaved so a
        // trimmed list still has a bit of every department
        val head = out.take(7)
        val rest = out.drop(7).shuffled(java.util.Random(SEED + 7))
        return head + rest
    }
}
