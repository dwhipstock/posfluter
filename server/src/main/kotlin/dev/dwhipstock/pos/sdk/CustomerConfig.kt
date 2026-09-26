package dev.dwhipstock.pos.sdk

/**
 * Customer-tier configuration. Two kinds of members, deliberately distinct:
 *
 * - POLICY (taxPolicy, roundingPolicy, authPolicy): typed choices baked into
 *   the customer's code. Changing one is a deploy, on purpose.
 * - DATA (fees, receiptPolicy, electronicTenders): assembled from runtime
 *   venue settings by the implementing class — `get()` members so an owner
 *   edit takes effect on the next transaction, no restart.
 *
 * One instance per tenant. No feature flags, no config trees.
 */
interface CustomerConfig {
    val customerId: String
    val displayName: String
    val taxPolicy: TaxPolicy
    val roundingPolicy: RoundingPolicy
    val fees: List<Fee>
    val authPolicy: AuthPolicy
    val receiptPolicy: ReceiptPolicy
    val printer: PrinterAdapter
    /** Electronic tender methods this customer offers (cash is always available). */
    val electronicTenders: List<TenderMethod>
    /** Base URL customer phones can reach — printed into table QR codes (M3). */
    val publicBaseUrl: String
    /** Which store this is (POS_VENUE): "vieux-port", "plateau", "sage-poppy". */
    val venueId: String get() = customerId
    /** Which look the terminal and receipts wear: "copper-lantern" or "sage-poppy". */
    val brand: String get() = customerId
    /** Country, currency, languages, zone and kind of this store (policy: a deploy to change). */
    val profile: StoreProfile get() = StoreProfile.QUEBEC_PUB
    /** Minimum age for age-restricted items: POS_LEGAL_AGE / legal.age, else the profile's. */
    val legalAge: Int get() = profile.legalAge

    fun tenderMethod(type: TenderType): TenderMethod? = electronicTenders.firstOrNull { it.type == type }
}
