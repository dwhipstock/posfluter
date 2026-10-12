package dev.dwhipstock.poscloud.menuai

/**
 * Which house style a portal-made photo wears. The store knows its brand
 * (CustomerConfig.brand); the cloud knows its tenant and each store's kind.
 * A client with its own shoot is matched by tenant id (one portal instance
 * per client); everyone else keeps the old rule: a retail store gets the
 * bottle-shop studio, a restaurant the pub table.
 *
 * Kept out of HouseStyle.kt: that file is a byte-for-byte copy of the
 * store's (MenuAiPhotosTest.theHouseStyleIsTheStoresHouseStyle).
 */
fun houseStyleFor(tenantId: String, retail: Boolean): HouseStyle = when {
    tenantId == HouseStyle.BARCODE.brand -> HouseStyle.BARCODE
    retail -> HouseStyle.SAGE_POPPY
    else -> HouseStyle.COPPER_LANTERN
}
