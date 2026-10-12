package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.menuai.HouseStyle
import dev.dwhipstock.poscloud.menuai.houseStyleFor
import kotlin.test.Test
import kotlin.test.assertEquals

class HouseStyleChoiceTest {
    @Test
    fun barcodeGetsItsOwnShootOthersKeepTheOldRule() {
        assertEquals(HouseStyle.BARCODE, houseStyleFor("barcode", retail = false))
        assertEquals(HouseStyle.BARCODE, houseStyleFor("barcode", retail = true))
        assertEquals(HouseStyle.COPPER_LANTERN, houseStyleFor("copperlantern", retail = false))
        assertEquals(HouseStyle.SAGE_POPPY, houseStyleFor("copperlantern", retail = true))
        assertEquals(HouseStyle.SAGE_POPPY, houseStyleFor("sagepoppy", retail = true))
        assertEquals(HouseStyle.BARCODE, HouseStyle.forBrand("barcode"))
    }
}
