package dev.dwhipstock.pos.customers.pronghorn

import dev.dwhipstock.pos.base.Upc
import kotlin.math.exp
import kotlin.math.roundToLong

/**
 * Pronghorn's convenience store: about 1,150 products, the size of a real
 * c-store (800–1,500 SKUs), plus the fuel items rung up at the pump.
 * Generated from fixed word lists and a fixed seed, so every store, test and
 * demo gets the same shelf, barcodes and prices. Every name is made up.
 *
 * The mix follows a real highway c-store: cold drinks and beer up front,
 * snacks and candy, hot food and coffee (sold by quick key, no barcode),
 * automotive, health & beauty, general merchandise, a small grocery corner,
 * tobacco & vape behind the counter, and ice.
 *
 * Texas sales tax (simplified): soft drinks, candy, beer, prepared food and
 * general merchandise are taxable; groceries, snack foods (chips, nuts,
 * jerky), bottled water, milk, 100% juice, over-the-counter medicine and ice
 * are not. Beer, tobacco and vape are 21+. Fuel is never taxed at the
 * counter (its taxes are in the pump price).
 */
object PronghornCatalog {
    const val SEED = 20_260_926L

    /** Number system 4 (in-store use) + a made-up 5-digit block: never a real product's code. */
    const val UPC_PREFIX = "486120"
    const val FIRST_SEQ = 10_000

    enum class Cat(val id: String, val en: String) {
        FUEL("fuel", "Fuel"),
        DRINKS("drinks", "Cold Drinks"),
        BEER("beer", "Beer & Seltzer"),
        SNACKS("snacks", "Snacks"),
        CANDY("candy", "Candy & Gum"),
        HOT("hot-food", "Hot Food & Coffee"),
        GROCERY("grocery", "Grocery & Dairy"),
        AUTO("automotive", "Automotive"),
        HEALTH("health", "Health & Beauty"),
        GENERAL("general", "General Merchandise"),
        TOBACCO("tobacco", "Tobacco & Vape"),
        ICE("ice", "Ice"),
    }

    data class Product(
        val id: String,
        val name: String,
        val cat: Cat,
        val cents: Long,
        val seq: Int,
        val ageRestricted: Boolean = false,
        val taxable: Boolean = true,
        val brand: String? = null,
        val subcategory: String? = null,
        val size: String? = null,
        val salesWeight: Int = 0,
        /** Sold by a quick key (coffee, a taco) or at the pump (fuel): no barcode. */
        val barcodeless: Boolean = false,
        /** On the shelf; fuel items are sold at the pump only. */
        val active: Boolean = true,
        /** "Each", or "Gallon" for fuel. */
        val unitLabel: String = "Each",
    ) {
        val barcode: String get() = Upc.upcA(UPC_PREFIX + seq.toString().padStart(5, '0'))
        val shelfCode: String? get() = if (barcodeless) null else barcode
        val abbrev: String get() = name.split(' ').filter { it.firstOrNull()?.isLetter() == true }
            .take(2).joinToString("") { it.first().uppercase() }
        val variantId: String get() = if (cat == Cat.FUEL && id != "fuel-prepay") "$id:gal" else "$id:each"
    }

    /**
     * The fuel items: one per grade (a fuel line is one fuelling at the pump's
     * price) and the prepay. Not on the shelf, not taxed. Their catalog price is
     * the posted price per gallon rounded to the cent, for the portal's menu.
     */
    val fuel: List<Product> = Pronghorn.GRADES.mapIndexed { i, g ->
        Product(
            id = "fuel-" + g.code.lowercase(), name = g.name, cat = Cat.FUEL, cents = (g.priceMills + 5) / 10,
            seq = 1 + i, taxable = false, brand = "Pronghorn", subcategory = "Fuel", size = "per gallon",
            barcodeless = true, active = false, unitLabel = "Gallon",
        )
    } + Product(
        id = "fuel-prepay", name = "Fuel prepay", cat = Cat.FUEL, cents = 0, seq = 9, taxable = false,
        brand = "Pronghorn", subcategory = "Prepay", size = null, barcodeless = true, active = false,
    )

    /** The shelf (fuel excluded), weighted. */
    val shelf: List<Product> by lazy { generate() }

    /** Everything the store seeds. */
    val products: List<Product> get() = fuel + shelf

    // ------------------------------------------------------------------ generation

    private class Rng(seed: Long) {
        private val r = java.util.Random(seed)
        fun int(n: Int) = r.nextInt(n)
        fun double() = r.nextDouble()
        fun gauss() = r.nextGaussian()
        fun <T> pick(list: List<T>): T = list[r.nextInt(list.size)]
        fun <T> sample(list: List<T>, n: Int): List<T> = list.shuffled(r).take(n.coerceAtMost(list.size))
        fun price(lo: Double, hi: Double): Long {
            val v = lo + (hi - lo) * r.nextDouble()
            val dollars = v.toLong().coerceAtLeast(0)
            val cents = listOf(29, 49, 79, 99, 99, 99)[r.nextInt(6)]
            return (dollars * 100 + cents).coerceAtLeast(49)
        }
    }

    private class Draft(
        val name: String, val brand: String, val cat: Cat, val sub: String, val size: String,
        val cents: Long, val taxable: Boolean, val appeal: Double,
        val ageRestricted: Boolean = false, val barcodeless: Boolean = false,
    )

    private fun generate(): List<Product> {
        val rng = Rng(SEED)
        val drafts = mutableListOf<Draft>()
        val names = mutableSetOf<String>()
        fun add(d: Draft) { if (names.add(d.name.lowercase())) drafts += d }

        hotFood(::add)
        ice(::add)
        drinks(rng, ::add)
        beer(rng, ::add)
        snacks(rng, ::add)
        candy(rng, ::add)
        grocery(rng, ::add)
        automotive(rng, ::add)
        health(rng, ::add)
        general(rng, ::add)
        tobacco(rng, ::add)

        // long-tail weights: appeal × a random factor, 1/rank
        data class Scored(val p: Product, val score: Double)
        var seq = FIRST_SEQ
        val scored = drafts.map { d ->
            val s = seq++
            val boost = when (d.cat) {
                Cat.HOT -> 4.0; Cat.ICE -> 2.5; Cat.DRINKS -> 1.6; Cat.TOBACCO -> 1.6; Cat.BEER -> 1.3
                Cat.SNACKS -> 1.2; Cat.CANDY -> 1.1; Cat.AUTO -> 0.8; Cat.GROCERY -> 0.7; Cat.HEALTH -> 0.6
                Cat.GENERAL -> 0.5; Cat.FUEL -> 0.0
            }
            val p = Product(
                id = if (d.barcodeless) "ph-" + slug(d.name) else "ph$s", name = d.name, cat = d.cat, cents = d.cents,
                seq = s, ageRestricted = d.ageRestricted, taxable = d.taxable, brand = d.brand, subcategory = d.sub,
                size = d.size, barcodeless = d.barcodeless,
            )
            Scored(p, d.appeal * boost * exp(rng.gauss() * 1.0))
        }
        val ranked = scored.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.p.seq })
        val weight = ranked.mapIndexed { i, s -> s.p.id to (1_000_000.0 / (i + 1)).roundToLong().coerceAtLeast(1) }.toMap()
        return scored.map { it.p.copy(salesWeight = weight.getValue(it.p.id).toInt()) }
    }

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)

    // ---- departments ----

    /** The counter's own: coffee, fountain drinks, the roller grill, kolaches and tacos. Quick keys, no barcodes. */
    private fun hotFood(add: (Draft) -> Unit) {
        fun hot(name: String, sub: String, size: String, cents: Long, appeal: Double) =
            add(Draft(name, "Pronghorn Kitchen", Cat.HOT, sub, size, cents, true, appeal, barcodeless = true))
        hot("Coffee 12 oz", "Coffee", "12 oz", 149, 3.0)
        hot("Coffee 16 oz", "Coffee", "16 oz", 179, 3.4)
        hot("Coffee 20 oz", "Coffee", "20 oz", 209, 2.6)
        hot("Iced Coffee 24 oz", "Coffee", "24 oz", 279, 1.6)
        hot("Hot Chocolate 16 oz", "Coffee", "16 oz", 179, 0.8)
        hot("Cappuccino 16 oz", "Coffee", "16 oz", 229, 1.0)
        hot("Fountain Drink 22 oz", "Fountain", "22 oz", 129, 2.4)
        hot("Fountain Drink 32 oz", "Fountain", "32 oz", 159, 3.0)
        hot("Fountain Drink 44 oz", "Fountain", "44 oz", 189, 2.4)
        hot("Fountain Drink 64 oz", "Fountain", "64 oz", 219, 1.2)
        hot("Frozen Slush 32 oz", "Fountain", "32 oz", 249, 1.2)
        hot("Sweet Tea 32 oz", "Fountain", "32 oz", 149, 1.5)
        hot("Kolache Sausage & Cheese", "Bakery", "Each", 199, 2.2)
        hot("Kolache Sausage & Jalapeño", "Bakery", "Each", 219, 1.8)
        hot("Kolache Fruit", "Bakery", "Each", 169, 1.1)
        hot("Breakfast Taco Egg & Bacon", "Tacos", "Each", 249, 2.4)
        hot("Breakfast Taco Potato & Egg", "Tacos", "Each", 229, 1.9)
        hot("Breakfast Taco Chorizo & Egg", "Tacos", "Each", 249, 1.6)
        hot("Brisket Taco", "Tacos", "Each", 399, 1.5)
        hot("Hot Dog", "Roller Grill", "Each", 179, 1.8)
        hot("Jalapeño Cheddar Sausage", "Roller Grill", "Each", 229, 1.4)
        hot("Taquito Beef", "Roller Grill", "Each", 169, 1.3)
        hot("Taquito Chicken", "Roller Grill", "Each", 169, 1.1)
        hot("Pizza Slice Pepperoni", "Pizza", "Slice", 299, 1.2)
        hot("Pizza Slice Cheese", "Pizza", "Slice", 279, 0.9)
        hot("Brisket Sandwich", "Sandwiches", "Each", 649, 0.9)
        hot("Chicken Tenders 3 pc", "Hot Case", "3 pc", 499, 0.9)
        hot("Corn Dog", "Hot Case", "Each", 199, 0.8)
        hot("Burrito Bean & Cheese", "Hot Case", "Each", 299, 0.8)
        hot("Nachos with Cheese", "Hot Case", "Each", 349, 0.7)
    }

    private fun ice(add: (Draft) -> Unit) {
        add(Draft("Party Ice 7 lb bag", "Pronghorn", Cat.ICE, "Bagged Ice", "7 lb", 249, false, 1.8))
        add(Draft("Party Ice 10 lb bag", "Pronghorn", Cat.ICE, "Bagged Ice", "10 lb", 329, false, 1.6))
        add(Draft("Party Ice 20 lb bag", "Pronghorn", Cat.ICE, "Bagged Ice", "20 lb", 549, false, 1.2))
        add(Draft("Block Ice 10 lb", "Pronghorn", Cat.ICE, "Block Ice", "10 lb", 399, false, 0.6))
    }

    private fun drinks(rng: Rng, add: (Draft) -> Unit) {
        val sodaBrands = listOf("Canyon Fizz", "Big Sky Pop", "Lone Mesa", "Red River Soda", "Prairie Pop", "Hill Country")
        val sodas = listOf("Cola", "Diet Cola", "Zero Sugar Cola", "Lemon-Lime", "Orange", "Root Beer", "Cream Soda",
            "Spiced Cherry", "Grape", "Strawberry", "Ginger Ale", "Citrus Kick")
        val sodaSizes = listOf(
            Triple("12 oz can", 0.99 to 1.49, 0.9), Triple("20 oz bottle", 2.19 to 2.69, 1.6),
            Triple("1 L bottle", 2.49 to 2.99, 0.8), Triple("2 L bottle", 2.99 to 3.79, 1.2),
            Triple("12-pack cans", 7.99 to 9.49, 1.1),
        )
        for (b in sodaBrands) for (s in rng.sample(sodas, 5 + rng.int(3))) for ((size, band, appeal) in rng.sample(sodaSizes, 2 + rng.int(3)))
            add(Draft("$b $s $size", b, Cat.DRINKS, "Soda", size, rng.price(band.first, band.second), true, appeal))
        val energy = listOf("Volt Rush", "Stampede", "Night Rider", "Jackrabbit", "Thunderhead")
        val energyFlavors = listOf("Original", "Sugar Free", "Tropical", "Mango", "Berry Blast", "Citrus", "Watermelon", "Peach")
        for (b in energy) for (f in rng.sample(energyFlavors, 4 + rng.int(3))) {
            add(Draft("$b Energy $f 16 oz can", b, Cat.DRINKS, "Energy", "16 oz can", rng.price(2.99, 3.49), true, 1.7))
            if (rng.int(3) == 0) add(Draft("$b Energy $f 4-pack", b, Cat.DRINKS, "Energy", "4-pack", rng.price(8.99, 10.99), true, 0.7))
        }
        val sports = listOf("Trailhead Hydrate", "Endurance Plus")
        for (b in sports) for (f in listOf("Lemon-Lime", "Fruit Punch", "Glacier Blue", "Orange", "Grape", "Cool Berry")) {
            add(Draft("$b $f 28 oz", b, Cat.DRINKS, "Sports Drink", "28 oz", rng.price(2.29, 2.79), true, 1.1))
            add(Draft("$b $f 20 oz", b, Cat.DRINKS, "Sports Drink", "20 oz", rng.price(1.99, 2.39), true, 0.9))
        }
        val waters = listOf("Spring Creek", "Limestone Springs", "Clearfork")
        for (b in waters) {
            add(Draft("$b Water 16.9 oz", b, Cat.DRINKS, "Water", "16.9 oz", rng.price(1.29, 1.79), false, 1.6))
            add(Draft("$b Water 1 L", b, Cat.DRINKS, "Water", "1 L", rng.price(1.79, 2.29), false, 1.2))
            add(Draft("$b Water 1 gal", b, Cat.DRINKS, "Water", "1 gal", rng.price(1.99, 2.49), false, 0.9))
            add(Draft("$b Water 24-pack", b, Cat.DRINKS, "Water", "24-pack", rng.price(5.99, 7.49), false, 0.9))
            add(Draft("$b Sparkling Water Lime 20 oz", b, Cat.DRINKS, "Sparkling Water", "20 oz", rng.price(1.79, 2.19), true, 0.6))
        }
        val teas = listOf("Porch Swing", "Sun Tea Co.")
        for (b in teas) for (f in listOf("Sweet Tea", "Unsweet Tea", "Peach Tea", "Lemon Tea", "Half & Half")) {
            add(Draft("$b $f 23 oz can", b, Cat.DRINKS, "Iced Tea", "23 oz can", rng.price(0.99, 1.29), true, 1.0))
            add(Draft("$b $f 18.5 oz bottle", b, Cat.DRINKS, "Iced Tea", "18.5 oz", rng.price(2.19, 2.69), true, 0.7))
        }
        for (f in listOf("Black", "Vanilla", "Mocha", "Caramel", "Salted Caramel"))
            add(Draft("Ranch Road Cold Brew $f 11 oz", "Ranch Road", Cat.DRINKS, "Coffee Drinks", "11 oz", rng.price(3.29, 3.99), true, 0.8))
        for ((what, size, band) in listOf(
            Triple("Whole Milk", "1 pint", 1.49 to 1.99), Triple("Whole Milk", "1 gal", 4.29 to 4.99),
            Triple("2% Milk", "1 gal", 4.29 to 4.99), Triple("Chocolate Milk", "1 pint", 1.99 to 2.49),
            Triple("Strawberry Milk", "1 pint", 1.99 to 2.49),
        )) add(Draft("Bluestem Dairy $what $size", "Bluestem Dairy", Cat.DRINKS, "Milk", size, rng.price(band.first, band.second), false, 0.8))
        for (f in listOf("Orange", "Apple", "Cranberry Blend", "Grape", "Pineapple"))
            add(Draft("Orchard Gate 100% $f Juice 15 oz", "Orchard Gate", Cat.DRINKS, "Juice", "15 oz", rng.price(2.29, 2.99), false, 0.6))
        for (f in listOf("Lemonade", "Pink Lemonade", "Fruit Punch", "Mango Nectar"))
            add(Draft("Orchard Gate $f 20 oz", "Orchard Gate", Cat.DRINKS, "Juice Drinks", "20 oz", rng.price(1.99, 2.49), true, 0.6))
    }

    private fun beer(rng: Rng, add: (Draft) -> Unit) {
        val breweries = listOf("Caliche Creek", "Pecan Bayou", "Limestone Ledge", "Mesquite Draw", "Bluebonnet Bend",
            "Big Bend Basin", "Cedar Break", "Guadalupe Gravel", "Hackberry Hollow", "Windmill Flats", "Brazos Bottom",
            "Armadillo Alley")
        data class Style(val name: String, val macro: Boolean, val appeal: Double)
        val styles = listOf(Style("Light Lager", true, 1.8), Style("Lager", true, 1.4), Style("Mexican-Style Lager", true, 1.6),
            Style("IPA", false, 1.0), Style("Hazy IPA", false, 0.8), Style("Amber Ale", false, 0.6),
            Style("Wheat Beer", false, 0.6), Style("Blonde Ale", false, 0.7), Style("Stout", false, 0.3),
            Style("Non-Alcoholic", true, 0.4))
        data class Fmt(val size: String, val lo: Double, val hi: Double, val appeal: Double)
        val macroFmts = listOf(Fmt("12 oz can", 1.29, 1.79, 0.9), Fmt("16 oz tallboy", 1.99, 2.49, 1.4),
            Fmt("24 oz can", 2.79, 3.49, 1.6), Fmt("6-pack cans", 8.49, 10.99, 1.2), Fmt("12-pack cans", 14.99, 17.99, 1.6),
            Fmt("18-pack cans", 19.99, 23.99, 1.2), Fmt("24-pack cans", 24.99, 29.99, 1.0))
        val craftFmts = listOf(Fmt("16 oz tallboy", 2.99, 3.99, 1.0), Fmt("6-pack cans", 10.99, 13.99, 1.0),
            Fmt("12-pack cans", 17.99, 21.99, 0.8), Fmt("4-pack 16 oz cans", 11.99, 14.99, 0.8))
        for (b in breweries) for (st in rng.sample(styles, 3 + rng.int(3))) {
            val fmts = if (st.macro) macroFmts else craftFmts
            for (f in rng.sample(fmts, 2 + rng.int(3))) {
                val label = if (st.name == "Non-Alcoholic") "Non-Alcoholic Brew" else st.name
                add(Draft("$b $label ${f.size}", "$b Brewing", Cat.BEER, st.name, f.size, rng.price(f.lo, f.hi), true,
                    st.appeal * f.appeal, ageRestricted = st.name != "Non-Alcoholic"))
            }
        }
        for (f in listOf("Lime", "Black Cherry", "Mango", "Grapefruit", "Variety"))
            for ((size, lo, hi) in listOf(Triple("12 oz can", 2.29, 2.79), Triple("12-pack cans", 17.99, 20.99), Triple("24 oz can", 3.29, 3.79)))
                add(Draft("Dry Creek Hard Seltzer $f $size", "Dry Creek", Cat.BEER, "Hard Seltzer", size, rng.price(lo, hi), true,
                    0.9, ageRestricted = true))
    }

    private fun snacks(rng: Rng, add: (Draft) -> Unit) {
        val chipBrands = listOf("Caprock Crunch", "Pecan Street", "Dust Devil", "Rio Grande Kitchen", "Trail Boss")
        val chips = listOf("Original Potato Chips", "Kettle Sea Salt Chips", "Jalapeño Chips", "BBQ Chips",
            "Salt & Vinegar Chips", "Sour Cream & Onion Chips", "Tortilla Chips", "Hot Corn Chips", "Cheese Puffs",
            "Flamin' Chile Puffs", "Pork Rinds", "Chile Limón Tortilla Rolls")
        for (b in chipBrands) for (c in rng.sample(chips, 6 + rng.int(4))) for ((size, lo, hi, ap) in listOf(
            Quad("1 oz bag", 0.99, 1.29, 1.2), Quad("2.5 oz bag", 1.99, 2.49, 1.4), Quad("8 oz bag", 4.29, 5.49, 0.8),
        ).let { rng.sample(it, 1 + rng.int(3)) })
            add(Draft("$b $c $size", b, Cat.SNACKS, "Chips", size, rng.price(lo, hi), false, ap))
        val nutBrands = listOf("Rancher's Pride", "Pecan Street")
        for (b in nutBrands) for (n in listOf("Salted Peanuts", "Honey Roasted Peanuts", "Sunflower Seeds", "Spicy Sunflower Seeds",
            "Roasted Pecans", "Trail Mix", "Cashews", "Pistachios"))
            add(Draft("$b $n", b, Cat.SNACKS, "Nuts & Seeds", "Single", rng.price(1.49, 4.99), false, 0.8))
        for (b in listOf("Cowpoke", "Smokehouse Trail")) for (j in listOf("Original Beef Jerky 3 oz", "Peppered Beef Jerky 3 oz",
            "Teriyaki Beef Jerky 3 oz", "Jalapeño Beef Jerky 3 oz", "Meat Stick Original", "Meat Stick Hot", "Brisket Bites 2 oz"))
            add(Draft("$b $j", b, Cat.SNACKS, "Jerky & Meat Snacks", "Single", rng.price(1.49, 8.99), false, 1.0))
        for (b in listOf("Sunrise Bakery", "Front Porch")) for (p in listOf("Honey Bun", "Glazed Donuts 6 ct", "Cinnamon Roll",
            "Chocolate Cupcakes 2 ct", "Oatmeal Cream Pies", "Fruit Pie Cherry", "Fruit Pie Apple", "Pecan Pie Single",
            "Chocolate Chip Cookies", "Peanut Butter Crackers 8 ct", "Cheese Crackers 8 ct", "Mini Muffins"))
            add(Draft("$b $p", b, Cat.SNACKS, "Pastries & Cookies", "Single", rng.price(0.99, 3.99), false, 0.8))
        for (p in listOf("Microwave Popcorn Butter", "Kettle Corn 6 oz", "Pretzel Twists 8 oz", "Pretzel Rods 10 oz",
            "Beef & Cheese Snack Pack", "Salsa Con Queso 15 oz", "Chunky Salsa 16 oz", "Bean Dip 9 oz"))
            add(Draft("Trail Boss $p", "Trail Boss", Cat.SNACKS, "Snack Aisle", "Single", rng.price(1.99, 4.99), false, 0.6))
    }

    private fun candy(rng: Rng, add: (Draft) -> Unit) {
        val brands = listOf("Sweet Mesa", "Sugar Road", "Lone Star Sweets", "Candy Corral")
        val bars = listOf("Milk Chocolate Bar", "Dark Chocolate Bar", "Peanut Caramel Bar", "Crispy Rice Bar", "Coconut Bar",
            "Almond Chocolate Bar", "Peanut Butter Cups", "Chocolate Covered Pecans", "Nougat Bar", "Mint Patties")
        val chewy = listOf("Gummy Bears", "Sour Worms", "Fruit Chews", "Licorice Twists", "Sour Belts", "Chile Mango Gummies",
            "Cinnamon Candies", "Jelly Beans")
        for (b in brands) {
            for (x in rng.sample(bars, 6 + rng.int(3))) {
                add(Draft("$b $x", b, Cat.CANDY, "Chocolate", "Single", rng.price(1.49, 2.49), true, 1.0))
                if (rng.int(3) == 0) add(Draft("$b $x King Size", b, Cat.CANDY, "Chocolate", "King Size", rng.price(2.49, 3.49), true, 0.6))
            }
            for (x in rng.sample(chewy, 5 + rng.int(3))) {
                add(Draft("$b $x", b, Cat.CANDY, "Chewy & Sour", "Single", rng.price(1.29, 2.29), true, 0.8))
                if (rng.int(2) == 0) add(Draft("$b $x Peg Bag", b, Cat.CANDY, "Chewy & Sour", "Peg Bag", rng.price(3.49, 4.99), true, 0.5))
            }
        }
        for (b in listOf("Fresh Trail", "Cool Front")) for (g in listOf("Spearmint Gum", "Peppermint Gum", "Wintergreen Gum",
            "Bubble Gum", "Cinnamon Gum", "Peppermint Mints", "Wintergreen Mints"))
            add(Draft("$b $g", b, Cat.CANDY, "Gum & Mints", "Single", rng.price(1.29, 2.79), true, 0.9))
    }

    private fun grocery(rng: Rng, add: (Draft) -> Unit) {
        val b = "Hill Country Pantry"
        listOf(
            "White Bread Loaf", "Wheat Bread Loaf", "Hot Dog Buns 8 ct", "Hamburger Buns 8 ct", "Flour Tortillas 10 ct",
            "Corn Tortillas 30 ct", "Large Eggs Dozen", "Large Eggs Half Dozen", "Butter 1 lb", "American Cheese Slices",
            "Shredded Cheddar 8 oz", "Sliced Ham 9 oz", "Sliced Turkey 9 oz", "Bologna 1 lb", "Hot Dogs 8 ct",
            "Breakfast Sausage 12 oz", "Bacon 12 oz", "Yogurt Strawberry", "Yogurt Vanilla", "Sour Cream 16 oz",
            "Peanut Butter 16 oz", "Grape Jelly 18 oz", "Strawberry Jam 18 oz", "Honey 12 oz", "Maple Syrup 12 oz",
            "Sugar Flakes Cereal", "Corn Flakes Cereal", "Oat Rings Cereal", "Instant Oatmeal 10 ct", "Pancake Mix",
            "Macaroni & Cheese", "Spaghetti 1 lb", "Pasta Sauce 24 oz", "Ramen Chicken", "Ramen Beef", "Ramen Cup Spicy",
            "Chili with Beans 15 oz", "Chicken Noodle Soup", "Tomato Soup", "Pinto Beans 15 oz", "Black Beans 15 oz",
            "Refried Beans 16 oz", "Canned Corn", "Green Beans", "Tuna 5 oz", "Vienna Sausage", "Potted Meat",
            "White Rice 2 lb", "Sugar 4 lb", "Flour 5 lb", "Coffee Ground 12 oz", "Coffee Creamer 32 oz", "Tea Bags 24 ct",
            "Ketchup 20 oz", "Yellow Mustard 14 oz", "Mayonnaise 30 oz", "Pickle Spears 24 oz", "Jalapeño Slices 12 oz",
            "Hot Sauce 5 oz", "BBQ Sauce 18 oz", "Salt 26 oz", "Black Pepper 4 oz", "Frozen Pizza Pepperoni",
            "Frozen Burritos 8 ct", "Ice Cream Vanilla Pint", "Ice Cream Pecan Pint", "Ice Cream Sandwich", "Fudge Bar",
            "Bananas each", "Apples each", "Limes each", "Onion each",
        ).forEach { w ->
            val sub = when {
                w.contains("Bread") || w.contains("Buns") || w.contains("Tortillas") -> "Bread & Tortillas"
                w.contains("Egg") || w.contains("Butter") || w.contains("Cheese") || w.contains("Yogurt") || w.contains("Sour Cream") -> "Dairy & Eggs"
                w.contains("Ham") || w.contains("Turkey") || w.contains("Bologna") || w.contains("Hot Dogs") || w.contains("Sausage") || w.contains("Bacon") -> "Meat & Deli"
                w.contains("Frozen") || w.contains("Ice Cream") || w.contains("Fudge") -> "Frozen"
                w.endsWith("each") -> "Produce"
                w.contains("Cereal") || w.contains("Oatmeal") || w.contains("Pancake") || w.contains("Syrup") -> "Breakfast"
                else -> "Pantry"
            }
            add(Draft("$b $w", b, Cat.GROCERY, sub, "Single", rng.price(0.79, 6.99), false, 0.7))
        }
    }

    private fun automotive(rng: Rng, add: (Draft) -> Unit) {
        val oilBrands = listOf("Roadrunner Lube", "High Plains Motor Oil", "Iron Horse")
        for (b in oilBrands) for (grade in listOf("0W-20", "5W-20", "5W-30", "10W-30", "10W-40", "15W-40 Diesel", "20W-50")) {
            add(Draft("$b $grade Motor Oil 1 qt", b, Cat.AUTO, "Motor Oil", "1 qt", rng.price(6.99, 9.99), true, 1.0))
            if (rng.int(2) == 0) add(Draft("$b $grade Motor Oil 5 qt", b, Cat.AUTO, "Motor Oil", "5 qt", rng.price(24.99, 32.99), true, 0.4))
            if (rng.int(3) == 0) add(Draft("$b $grade Full Synthetic 1 qt", b, Cat.AUTO, "Motor Oil", "1 qt", rng.price(9.99, 12.99), true, 0.6))
        }
        val care = "Roadrunner Lube"
        listOf(
            Triple("Windshield Washer Fluid 1 gal", "Fluids", 3.49 to 4.99), Triple("De-Icer Washer Fluid 1 gal", "Fluids", 3.99 to 5.49),
            Triple("Antifreeze 50/50 1 gal", "Fluids", 12.99 to 16.99), Triple("Antifreeze Concentrate 1 gal", "Fluids", 16.99 to 21.99),
            Triple("Brake Fluid 12 oz", "Fluids", 5.99 to 7.99), Triple("Power Steering Fluid 12 oz", "Fluids", 5.99 to 7.99),
            Triple("Transmission Fluid 1 qt", "Fluids", 7.99 to 10.99), Triple("Diesel Exhaust Fluid 2.5 gal", "Fluids", 12.99 to 16.99),
            Triple("Distilled Water 1 gal", "Fluids", 1.49 to 1.99), Triple("Octane Booster 12 oz", "Additives", 5.99 to 8.99),
            Triple("Fuel Injector Cleaner 12 oz", "Additives", 5.99 to 8.99), Triple("Fuel Stabilizer 8 oz", "Additives", 7.99 to 10.99),
            Triple("Diesel Anti-Gel 32 oz", "Additives", 9.99 to 13.99), Triple("Stop Leak Radiator 11 oz", "Additives", 7.99 to 10.99),
            Triple("Tire Pressure Gauge", "Tools", 3.99 to 7.99), Triple("Tire Inflator Can 12 oz", "Tools", 9.99 to 12.99),
            Triple("Jumper Cables 12 ft", "Tools", 24.99 to 34.99), Triple("Funnel", "Tools", 1.99 to 3.49),
            Triple("Gas Can 2 gal", "Tools", 14.99 to 19.99), Triple("Gas Can 5 gal", "Tools", 22.99 to 29.99),
            Triple("Ice Scraper", "Tools", 3.99 to 6.99), Triple("Tow Strap 20 ft", "Tools", 19.99 to 26.99),
            Triple("Bungee Cords 6 pc", "Tools", 6.99 to 9.99), Triple("Ratchet Straps 2 pc", "Tools", 14.99 to 19.99),
            Triple("Work Gloves", "Tools", 4.99 to 8.99), Triple("Shop Towels 55 ct", "Cleaning", 3.99 to 5.99),
            Triple("Glass Cleaner 19 oz", "Cleaning", 3.99 to 5.99), Triple("Tire Shine 16 oz", "Cleaning", 6.99 to 8.99),
            Triple("Car Wash Soap 64 oz", "Cleaning", 6.99 to 9.99), Triple("Interior Wipes 30 ct", "Cleaning", 4.99 to 6.99),
            Triple("Bug & Tar Remover 16 oz", "Cleaning", 5.99 to 7.99), Triple("Fuses Assorted", "Parts", 3.99 to 5.99),
            Triple("Headlight Bulb H11", "Parts", 12.99 to 17.99), Triple("Headlight Bulb 9006", "Parts", 12.99 to 17.99),
            Triple("Brake Light Bulb 1157 2 pc", "Parts", 4.99 to 6.99),
        ).forEach { (w, sub, band) -> add(Draft("$care $w", care, Cat.AUTO, sub, "Single", rng.price(band.first, band.second), true, 0.7)) }
        for (inch in listOf(16, 18, 19, 20, 21, 22, 24, 26))
            add(Draft("Clearview Wiper Blade $inch in", "Clearview", Cat.AUTO, "Wipers", "$inch in", rng.price(9.99, 14.99), true, 0.5))
        val scents = listOf("New Car", "Black Ice", "Vanilla", "Pine", "Cherry", "Ocean Breeze", "Leather", "Mesquite Smoke", "Coconut", "Bluebonnet")
        for (s in scents) {
            add(Draft("Road Scents Air Freshener $s", "Road Scents", Cat.AUTO, "Air Fresheners", "Single", rng.price(1.29, 1.99), true, 1.0))
            add(Draft("Road Scents Vent Clip $s", "Road Scents", Cat.AUTO, "Air Fresheners", "Vent Clip", rng.price(3.99, 4.99), true, 0.6))
        }
    }

    private fun health(rng: Rng, add: (Draft) -> Unit) {
        val b = "Wayside"
        // over-the-counter medicine: not taxed in Texas
        listOf("Pain Reliever Ibuprofen 24 ct", "Pain Reliever Acetaminophen 24 ct", "Aspirin 24 ct", "Pain Reliever 2-Pack",
            "Antacid Tablets 12 ct", "Heartburn Relief 14 ct", "Allergy Relief 12 ct", "Cold & Flu Daytime", "Cold & Flu Nighttime",
            "Cough Drops Honey Lemon", "Cough Drops Cherry", "Motion Sickness Tablets", "Anti-Diarrheal 12 ct", "Eye Drops",
            "Sleep Aid 16 ct", "Energy Shot Berry", "Energy Shot Grape", "Hydration Powder 6 ct", "Bandages Assorted 30 ct",
            "First Aid Kit Travel",
        ).forEach { w -> add(Draft("$b $w", b, Cat.HEALTH, if (w.startsWith("Energy") || w.startsWith("Hydration")) "Energy & Hydration" else "Medicine & First Aid",
            "Single", rng.price(1.99, 9.99), w.startsWith("Energy") || w.startsWith("Hydration") || w.startsWith("Bandages") || w.startsWith("First Aid"), 0.7)) }
        listOf("Lip Balm Original", "Lip Balm Cherry", "Sunscreen SPF 50 3 oz", "Sunscreen Stick SPF 30", "Aloe Gel 8 oz",
            "Hand Sanitizer 2 oz", "Hand Lotion 3 oz", "Deodorant Travel", "Toothbrush", "Toothpaste Travel", "Mouthwash 8 oz",
            "Floss Picks 30 ct", "Disposable Razors 4 ct", "Shaving Cream Travel", "Shampoo Travel", "Body Wash Travel",
            "Comb", "Hair Ties 12 ct", "Tissues Pocket 3 pk", "Wet Wipes 20 ct", "Feminine Care 10 ct", "Contact Lens Solution 2 oz",
            "Reading Glasses +1.50", "Reading Glasses +2.00", "Ear Plugs 4 pr", "Insect Repellent 6 oz", "Anti-Itch Cream 1 oz",
            "Nail Clippers",
        ).forEach { w -> add(Draft("$b $w", b, Cat.HEALTH, "Personal Care", "Single", rng.price(1.49, 8.99), true, 0.5)) }
    }

    private fun general(rng: Rng, add: (Draft) -> Unit) {
        val b = "Pronghorn Goods"
        listOf(
            Triple("USB-C Charging Cable 3 ft", "Phone", 9.99 to 14.99), Triple("Lightning Charging Cable 3 ft", "Phone", 9.99 to 14.99),
            Triple("Car Charger Dual USB", "Phone", 12.99 to 19.99), Triple("Wall Charger USB-C", "Phone", 14.99 to 19.99),
            Triple("Phone Mount Vent", "Phone", 12.99 to 16.99), Triple("Earbuds Wired", "Phone", 7.99 to 12.99),
            Triple("AA Batteries 4 pk", "Batteries", 5.99 to 7.99), Triple("AAA Batteries 4 pk", "Batteries", 5.99 to 7.99),
            Triple("9V Battery", "Batteries", 4.99 to 6.99), Triple("Flashlight LED", "Batteries", 7.99 to 12.99),
            Triple("Lighter Classic", "Lighters", 1.49 to 2.49), Triple("Lighter Long Reach", "Lighters", 4.99 to 6.99),
            Triple("Matches 10 boxes", "Lighters", 1.99 to 2.99), Triple("Sunglasses Classic", "Travel", 9.99 to 14.99),
            Triple("Sunglasses Sport", "Travel", 12.99 to 16.99), Triple("Ball Cap Pronghorn", "Travel", 14.99 to 19.99),
            Triple("Road Atlas Texas", "Travel", 9.99 to 12.99), Triple("Travel Pillow", "Travel", 12.99 to 16.99),
            Triple("Umbrella Compact", "Travel", 9.99 to 12.99), Triple("Rain Poncho", "Travel", 2.99 to 4.99),
            Triple("Foam Cooler 28 qt", "Outdoors", 4.99 to 7.99), Triple("Charcoal 8 lb", "Outdoors", 7.99 to 9.99),
            Triple("Lighter Fluid 32 oz", "Outdoors", 4.99 to 6.99), Triple("Propane Cylinder 1 lb", "Outdoors", 5.99 to 7.99),
            Triple("Firewood Bundle", "Outdoors", 6.99 to 8.99), Triple("Fishing Bait Worms", "Outdoors", 3.99 to 5.49),
            Triple("Paper Towels 2 roll", "Household", 3.99 to 5.49), Triple("Toilet Paper 4 roll", "Household", 4.99 to 6.99),
            Triple("Trash Bags 13 gal 20 ct", "Household", 5.99 to 7.99), Triple("Dish Soap 16 oz", "Household", 2.99 to 3.99),
            Triple("Laundry Detergent 50 oz", "Household", 8.99 to 11.99), Triple("Aluminum Foil 30 ft", "Household", 3.99 to 4.99),
            Triple("Plastic Cups 50 ct", "Party", 3.99 to 5.49), Triple("Paper Plates 40 ct", "Party", 3.99 to 5.49),
            Triple("Napkins 100 ct", "Party", 1.99 to 2.99), Triple("Birthday Candles", "Party", 1.99 to 2.99),
            Triple("Greeting Card", "Party", 2.99 to 4.99), Triple("Gift Bag", "Party", 1.99 to 3.49),
            Triple("Dog Treats 6 oz", "Pets", 4.99 to 6.99), Triple("Dog Food 4 lb", "Pets", 7.99 to 9.99),
            Triple("Cat Food Can", "Pets", 1.29 to 1.79), Triple("Travel Mug", "Travel", 9.99 to 14.99),
            Triple("Postcard Hill Country", "Travel", 0.99 to 1.49), Triple("Keychain Texas", "Travel", 3.99 to 5.99),
        ).forEach { (w, sub, band) -> add(Draft("$b $w", b, Cat.GENERAL, sub, "Single", rng.price(band.first, band.second), true, 0.5)) }
    }

    /** Behind the counter, 21+. Generic, fictional, no cigarette brands. */
    private fun tobacco(rng: Rng, add: (Draft) -> Unit) {
        for (b in listOf("Prairie", "Cold Front")) for (f in listOf("Mint", "Wintergreen", "Citrus", "Coffee", "Cool Berry"))
            for (mg in listOf(3, 6))
                add(Draft("$b Nicotine Pouches $f ${mg} mg", b, Cat.TOBACCO, "Nicotine Pouches", "${mg} mg", rng.price(4.99, 5.99), true, 1.2, ageRestricted = true))
        for (f in listOf("Mint", "Menthol", "Tobacco", "Blue Razz", "Watermelon", "Strawberry"))
            add(Draft("Vapor Ridge Disposable Vape $f", "Vapor Ridge", Cat.TOBACCO, "Vape", "Single", rng.price(14.99, 19.99), true, 0.9, ageRestricted = true))
        for (f in listOf("Sweet", "Wine", "Grape", "Original"))
            add(Draft("Mesa Cigarillos $f 2 pk", "Mesa", Cat.TOBACCO, "Cigars", "2 pk", rng.price(1.29, 1.79), true, 0.7, ageRestricted = true))
        for (f in listOf("Wintergreen Long Cut", "Straight Long Cut", "Mint Fine Cut"))
            add(Draft("Ranch Hand Smokeless $f", "Ranch Hand", Cat.TOBACCO, "Smokeless", "Can", rng.price(5.99, 7.49), true, 0.6, ageRestricted = true))
        add(Draft("Rolling Papers 1¼", "Prairie", Cat.TOBACCO, "Accessories", "Pack", 199, true, 0.3, ageRestricted = true))
    }

    private data class Quad(val size: String, val lo: Double, val hi: Double, val appeal: Double)
}
