package dev.dwhipstock.pos.aiphotos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HouseStyleTest {
    private val burger = ItemFacts("Copper Lantern Burger", "Beef, cheddar, bacon, onions and house sauce.", "Burgers & Sandwiches")
    private val lager = ItemFacts("Lantern House Lager", "Crisp, malty lager brewed in Montréal.", "Beer & Cider")
    private val cpr = HouseStyle.COPPER_LANTERN

    @Test
    fun eachBrandHasItsOwnShoot() {
        val cpr = HouseStyle.forBrand("copper-lantern")
        val sp = HouseStyle.forBrand("sage-poppy")
        assertTrue(cpr.scene.contains("wood") && cpr.scene.contains("daylight") && cpr.scene.contains("45-degree"))
        assertTrue(cpr.scene.contains("neutral white balance") && cpr.scene.contains("no orange or amber colour cast"))
        assertFalse(cpr.scene.contains("tungsten"))
        assertTrue(sp.scene.contains("white") && sp.scene.contains("sage") && sp.scene.contains("studio"))
        assertTrue(sp.scene.contains("neutral white balance") && sp.scene.contains("true-to-life colours"))
        assertFalse(sp.scene.contains("wood"))
        // unknown brands fall back to the pub style rather than failing
        assertEquals(cpr, HouseStyle.forBrand("something-else"))
    }

    @Test
    fun aStoreCanOverrideTheSceneLine() {
        val custom = HouseStyle.forBrand("sage-poppy", "marble counter, morning window light")
        assertEquals("marble counter, morning window light", custom.scene)
        assertEquals(HouseStyle.SAGE_POPPY.shot, custom.shot)
        assertEquals(HouseStyle.SAGE_POPPY.nameWordsToDrop, custom.nameWordsToDrop)
        assertEquals(HouseStyle.SAGE_POPPY, HouseStyle.forBrand("sage-poppy", "  "))
    }

    @Test
    fun generatePromptLeadsWithTheDescriptionNotTheName() {
        // the tested fix: a named item got its name printed on the glass
        val p = PhotoPrompts.generate(lager, cpr)
        assertEquals(
            "A professional food and drink menu photograph for a pub. " +
                "The subject: Crisp, malty lager brewed in Montréal. Menu category: Beer & Cider. " +
                "House style, shared by every photo on this menu: ${cpr.scene}. " +
                "One single serving is the only subject, centred and filling most of the frame, realistic, " +
                "appetising and true to how it is actually served. " +
                "Plain, unbranded glassware, bottles and plates with no printing, labels or engraving. " +
                "No text, no captions, no logos or readable brand names, no watermark, " +
                "no people or hands, no cutlery clutter.",
            p,
        )
        assertFalse(p.contains("Lantern"))
    }

    @Test
    fun theBrandNeverReachesThePrompt() {
        val p = PhotoPrompts.generate(burger, cpr)
        assertFalse(p.contains("Copper") || p.contains("Lantern"), p)
        assertTrue(p.contains("The subject: burger. Beef, cheddar, bacon, onions and house sauce. " +
            "Menu category: Burgers & Sandwiches."), p)
        val e = PhotoPrompts.enhance(burger, cpr)
        assertFalse(e.contains("Copper") || e.contains("Lantern"), e)
        assertTrue(e.contains("Improve this real photo of burger (Burgers & Sandwiches)"), e)
    }

    /** Same cases as scripts/tests/test_image_bakeoff.py (keeps the two in step). */
    @Test
    fun whatItIsWhenTheDescriptionDoesNotSay() {
        val cases = listOf(
            ItemFacts("Maple Cheesecake", "Maple cheesecake with toasted pecans.", "Desserts") to
                "Maple cheesecake with toasted pecans",
            ItemFacts("Eastern Townships Pinot Noir", "Light red with cherry and spice.", "Wine") to
                "pinot noir wine. Light red with cherry and spice",
            ItemFacts("Copper Old Fashioned", "Canadian whisky, maple, bitters and orange.", "Cocktails") to
                "old fashioned cocktail. Canadian whisky, maple, bitters and orange",
            ItemFacts("Classic Poutine", "Fries, cheese curds and savoury gravy.", "Starters") to
                "classic poutine. Fries, cheese curds and savoury gravy",
            ItemFacts("Avocado Cucumber Maki", "Six vegetarian pieces.", "Sushi & Sake") to
                "avocado cucumber maki. Six vegetarian pieces",
            ItemFacts("North Trail IPA", "", "Beer & Cider") to "IPA",
            ItemFacts("Hazy Hills IPA 4-pack 16 oz cans", "", "Beer", "Hazy Hills", "Hazy IPA") to
                "hazy IPA 4-pack 16 oz cans",
            ItemFacts("Silver Coast Vodka 750 ml", "", "Spirits", "Silver Coast", "Vodka") to "vodka 750 ml",
            ItemFacts("Cola 2 L", "", "Mixers & Soda", "House", "Soda") to "cola 2 L soda",
            ItemFacts("Copper Lantern", "", "Cocktails") to "cocktail",
        )
        for ((item, want) in cases) assertEquals(want, PhotoPrompts.subject(item, cpr), item.name)
    }

    @Test
    fun aRetailProductNamesItsStyleNotItsBrand() {
        val p = PhotoPrompts.generate(
            ItemFacts("Golden Hour Lager 6-pack 12 oz cans", "", "Beer", "Golden Hour", "Lager"), HouseStyle.SAGE_POPPY)
        assertTrue(p.contains("The subject: lager 6-pack 12 oz cans. Menu category: Beer (Lager)."), p)
        assertFalse(p.contains("Golden") || p.contains("Hour"), p)
        assertTrue(p.contains(HouseStyle.SAGE_POPPY.scene))
    }

    @Test
    fun enhancePromptForbidsAddingOrRemovingFood() {
        val p = PhotoPrompts.enhance(burger, cpr)
        assertTrue(p.contains("do not add, remove, replace or rearrange any food items"))
        assertTrue(p.contains("recognisable as the same dish"))
        assertTrue(p.contains(cpr.scene))
        assertTrue(p.contains("Plain, unbranded glassware, bottles and plates with no printing, labels or engraving."))
    }
}
