package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.Upc
import dev.dwhipstock.pos.customers.sagepoppy.SagePoppyCatalog
import dev.dwhipstock.pos.customers.sagepoppy.SagePoppySeed
import dev.dwhipstock.pos.customers.sagepoppy.SagePoppySeed.Cat
import dev.dwhipstock.pos.sdk.Crv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The generated ~5,000-product Sage & Poppy shelf: size and mix, barcodes,
 * fictional names only, CRV / age / tax rules, price bands, facets on every
 * product, determinism, and the 80/20 long tail its demo sales follow.
 */
class SagePoppyCatalogTest {
    private val all = SagePoppyCatalog.products

    @Test
    fun aboutFiveThousandProductsInARealShopsMix() {
        assertEquals(SagePoppyCatalog.TOTAL, all.size)
        assertTrue(all.size in 4800..5200)
        val byCat = all.groupingBy { it.cat }.eachCount()
        fun share(vararg cats: Cat) = cats.sumOf { byCat[it] ?: 0 } / all.size.toDouble()
        assertEquals(0.35, share(Cat.BEER), 0.01)
        assertEquals(0.30, share(Cat.WINE), 0.01)
        assertEquals(0.25, share(Cat.SPIRITS), 0.01)
        assertEquals(0.05, share(Cat.SELTZERS), 0.01)
        assertEquals(0.05, share(Cat.MIXERS, Cat.SNACKS, Cat.ICE, Cat.SUNDRIES), 0.01)
        assertTrue(Cat.entries.all { (byCat[it] ?: 0) > 0 }, "every department stocked: $byCat")
    }

    @Test
    fun theHandWrittenProductsAreKeptExactly() {
        val byId = all.associateBy { it.id }
        for (p in SagePoppySeed.products) {
            val q = byId.getValue(p.id)
            assertEquals(p.name, q.name)
            assertEquals(p.cents, q.cents)
            assertEquals(p.barcode, q.barcode)
            assertEquals(p.crv, q.crv)
            assertEquals(p.taxable, q.taxable)
        }
    }

    @Test
    fun barcodesAreValidUniqueUpcAInTheInStoreRange() {
        val coded = all.filter { !it.barcodeless }
        val codes = coded.map { it.barcode }
        assertEquals(codes.size, codes.toSet().size, "unique barcodes")
        for (c in codes) {
            assertEquals(12, c.length)
            assertTrue(Upc.isValid(c), "$c check digit")
            // number system 4: reserved for a retailer's own codes, never a real product's
            assertTrue(c.startsWith(SagePoppySeed.UPC_PREFIX), c)
        }
        // only the few unscannables (sold from a quick key) have none
        val unscannable = all.filter { it.barcodeless }
        assertTrue(unscannable.size in 1..6, "unscannables: ${unscannable.map { it.name }}")
        assertEquals(all.size, all.map { it.id }.toSet().size, "unique ids")
        assertTrue(all.all { it.id.length <= 64 && it.name.length <= 200 })
        assertEquals(all.size, all.map { it.name.lowercase() }.toSet().size, "unique names")
    }

    @Test
    fun noRealBrandNames() {
        val denylist = listOf(
            "budweiser", "bud light", "coors", "miller", "corona", "modelo", "heineken", "stella artois", "guinness",
            "pabst", "michelob", "blue moon", "sierra nevada", "lagunitas", "ballast point", "firestone", "anchor steam",
            "stone brewing", "white claw", "truly", "high noon", "smirnoff", "absolut", "tito", "grey goose", "bacardi",
            "captain morgan", "jack daniel", "jim beam", "maker's mark", "jameson", "johnnie walker", "patron",
            "jose cuervo", "don julio", "casamigos", "fireball", "hennessy", "tanqueray", "bombay", "barefoot",
            "yellow tail", "kendall-jackson", "josh cellars", "la crema", "meiomi", "kim crawford", "gallo", "franzia",
            "coca-cola", "coke", "pepsi", "sprite", "schweppes", "canada dry", "fever-tree", "doritos", "lay's",
            "pringles", "cheetos", "iron horse", "pebble beach", "kendall", "silver oak", "duckhorn", "snickers", "hershey", "jack link", "slim jim", "red bull", "monster",
        )
        val offenders = all.filter { p ->
            val text = (p.name + " " + p.brand).lowercase()
            denylist.any { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(text) }
        }
        assertTrue(offenders.isEmpty(), "real brand names: ${offenders.take(5).map { it.name }}")
    }

    @Test
    fun crvAgeAndTaxFollowTheRules() {
        for (p in all) {
            when (p.cat) {
                Cat.BEER, Cat.SELTZERS -> {
                    assertTrue(p.crv != Crv.Size.NONE, "${p.name}: beer and coolers carry CRV")
                    val oz = Regex("(\\d+) oz").find(p.name)?.groupValues?.get(1)?.toDouble()
                    if (oz != null) assertEquals(Crv.sizeFor(oz), p.crv, "${p.name}: CRV by container size")
                    assertTrue(p.taxable)
                    assertEquals(p.subcategory != "Non-Alcoholic", p.ageRestricted, p.name)
                }
                Cat.WINE, Cat.SPIRITS -> {
                    assertEquals(Crv.Size.NONE, p.crv, "${p.name}: no CRV on wine or spirits")
                    assertTrue(p.ageRestricted && p.taxable, p.name)
                    assertEquals(1, p.pack)
                }
                Cat.SNACKS -> assertTrue(!p.ageRestricted && (!p.taxable || p.id == "party-cups"), p.name)
                Cat.ICE -> assertTrue(!p.ageRestricted && !p.taxable && p.crv == Crv.Size.NONE, p.name)
                Cat.MIXERS -> {
                    assertTrue(!p.ageRestricted, p.name)
                    // carbonated → CRV and taxed; juice, mixes and garnish neither
                    assertEquals(p.crv != Crv.Size.NONE, p.taxable, p.name)
                }
                Cat.SUNDRIES -> assertTrue(!p.ageRestricted && p.crv == Crv.Size.NONE, p.name)
            }
            // the pack multiplies the deposit: a 6-pack is 6 containers
            val packs = Regex("(\\d+)-pack").find(p.name)?.groupValues?.get(1)?.toInt()
            if (packs != null && p.cat in setOf(Cat.BEER, Cat.SELTZERS)) assertEquals(packs, p.pack, p.name)
        }
    }

    @Test
    fun pricesSitInRealisticBandsPerFormat() {
        fun band(filter: (SagePoppySeed.Product) -> Boolean, lo: Long, hi: Long) {
            val ps = all.filter(filter)
            assertTrue(ps.isNotEmpty())
            ps.forEach { assertTrue(it.cents in lo..hi, "${it.name}: ${it.cents}¢ outside $lo..$hi") }
        }
        band({ it.cat == Cat.BEER && it.size == "12 oz can" }, 99, 499)
        band({ it.cat == Cat.BEER && it.size == "6-pack" }, 599, 2599)
        band({ it.cat == Cat.BEER && it.size == "24-pack" }, 1499, 4999)
        band({ it.cat == Cat.WINE && it.size == "750 ml" }, 599, 12_999)
        band({ it.cat == Cat.WINE && it.size == "3 L box" }, 1799, 3499)
        band({ it.cat == Cat.SPIRITS && it.size == "50 ml" }, 99, 799)
        band({ it.cat == Cat.SPIRITS && it.size == "1.75 L" }, 1499, 9999)
        assertTrue(all.all { it.cents > 0 })
    }

    @Test
    fun everyProductHasASubcategoryAndASizeForTheFilters() {
        val bare = all.filter { it.subcategory.isNullOrBlank() || it.size.isNullOrBlank() || it.brand.isNullOrBlank() }
        assertTrue(bare.isEmpty(), "no facets: ${bare.take(3)}")
        val beerStyles = all.filter { it.cat == Cat.BEER }.map { it.subcategory }.toSet()
        assertTrue("IPA" in beerStyles && "Lager" in beerStyles && beerStyles.size >= 12, "$beerStyles")
        val sizes = all.map { it.size }.toSet()
        assertTrue(sizes.containsAll(listOf("6-pack", "12-pack", "750 ml", "1.75 L", "50 ml", "16 oz tallboy")), "$sizes")
        // the counter's example: Beer › IPA › 6-pack has products
        val ipa6 = all.count { it.cat == Cat.BEER && it.subcategory == "IPA" && it.size == "6-pack" }
        assertTrue(ipa6 >= 5, "IPA 6-packs: $ipa6")
    }

    @Test
    fun theCatalogIsDeterministic() {
        val again = SagePoppyCatalog.generate()
        assertEquals(all, again)
    }

    @Test
    fun aboutTwentyPercentOfProductsMakeEightyPercentOfSeededSales() {
        // draw sales the way the demo seeders do: each unit by sales weight
        val rng = java.util.Random(7)
        val weights = all.map { it.salesWeight.toLong() }
        assertTrue(weights.all { it > 0 })
        val cumulative = weights.runningReduce(Long::plus)
        val total = cumulative.last()
        val units = IntArray(all.size)
        repeat(60_000) {
            val r = (rng.nextDouble() * total).toLong()
            var i = cumulative.binarySearch(r + 1).let { if (it < 0) -it - 1 else it }
            if (i >= all.size) i = all.size - 1
            units[i]++
        }
        val sorted = units.sortedDescending()
        val top = sorted.take(all.size / 5).sum() / sorted.sum().toDouble()
        assertTrue(top in 0.74..0.88, "top 20% share of units = $top")
        // and the weights themselves say the same
        val w = weights.sortedDescending()
        assertEquals(0.8, w.take(all.size / 5).sum() / w.sum().toDouble(), 0.05)
    }
}
