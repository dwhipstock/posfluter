package dev.dwhipstock.pos.aiphotos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HouseStyleTest {
    private val burger = ItemFacts("Lantern Burger", "Two smashed patties, aged cheddar, pickles.", "Burgers & Sandwiches")

    @Test
    fun eachBrandHasItsOwnShoot() {
        val cpr = HouseStyle.forBrand("copper-lantern")
        val sp = HouseStyle.forBrand("sage-poppy")
        assertTrue(cpr.scene.contains("wood") && cpr.scene.contains("warm") && cpr.scene.contains("45-degree"))
        assertTrue(sp.scene.contains("white") && sp.scene.contains("sage") && sp.scene.contains("studio"))
        assertFalse(sp.scene.contains("wood"))
        // unknown brands fall back to the pub style rather than failing
        assertEquals(cpr, HouseStyle.forBrand("something-else"))
    }

    @Test
    fun aStoreCanOverrideTheSceneLine() {
        val custom = HouseStyle.forBrand("sage-poppy", "marble counter, morning window light")
        assertEquals("marble counter, morning window light", custom.scene)
        assertEquals(HouseStyle.SAGE_POPPY.shot, custom.shot)
        assertEquals(HouseStyle.SAGE_POPPY, HouseStyle.forBrand("sage-poppy", "  "))
    }

    @Test
    fun generatePromptCarriesTheItemAndTheHouseStyle() {
        val p = PhotoPrompts.generate(burger, HouseStyle.COPPER_LANTERN)
        assertTrue(p.contains("Lantern Burger"))
        assertTrue(p.contains("Two smashed patties, aged cheddar, pickles"))
        assertTrue(p.contains("Burgers & Sandwiches"))
        assertTrue(p.contains(HouseStyle.COPPER_LANTERN.scene))
        assertTrue(p.contains("No text"))
        val sp = PhotoPrompts.generate(ItemFacts("Hazy IPA 6-pack", "", "Beer"), HouseStyle.SAGE_POPPY)
        assertTrue(sp.contains(HouseStyle.SAGE_POPPY.scene))
        assertTrue(sp.contains("Hazy IPA 6-pack. Menu category: Beer."), sp)
    }

    @Test
    fun enhancePromptForbidsAddingOrRemovingFood() {
        val p = PhotoPrompts.enhance(burger, HouseStyle.COPPER_LANTERN)
        assertTrue(p.contains("do not add, remove, replace or rearrange any food items"))
        assertTrue(p.contains("recognisable as the same dish"))
        assertTrue(p.contains(HouseStyle.COPPER_LANTERN.scene))
    }
}
