package dev.dwhipstock.pos.customers.sagepoppy

import dev.dwhipstock.pos.base.SettingsRepository
import dev.dwhipstock.pos.sdk.AuthPolicy
import dev.dwhipstock.pos.sdk.CardMethod
import dev.dwhipstock.pos.sdk.CashRounding
import dev.dwhipstock.pos.sdk.CustomerConfig
import dev.dwhipstock.pos.sdk.Fee
import dev.dwhipstock.pos.sdk.LegalAge
import dev.dwhipstock.pos.sdk.PrinterAdapter
import dev.dwhipstock.pos.sdk.ReceiptPolicy
import dev.dwhipstock.pos.sdk.RoundingPolicy
import dev.dwhipstock.pos.sdk.StoreProfile
import dev.dwhipstock.pos.sdk.TaxComponent
import dev.dwhipstock.pos.sdk.TaxPolicy
import dev.dwhipstock.pos.sdk.TenderMethod
import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import java.math.BigDecimal

/**
 * Sage & Poppy Bottle Shop — a fictional neighbourhood liquor store in Los
 * Angeles, the owner's third store and first outside Canada (`POS_VENUE=sage-poppy`).
 * Its own brand, USD, English + Spanish, Pacific time; a retail counter with
 * barcode scanning, ID checks at 21 and California's bottle deposit (CRV).
 */
object SagePoppy {
    const val VENUE_ID = "sage-poppy"
    const val DISPLAY_NAME = "Sage & Poppy Bottle Shop"
    const val ADDRESS = "1427 Poppy Field Ave, Los Angeles, CA 90026"
    const val PHONE = "+1 213 555 0148"
    const val TIME_ZONE = "America/Los_Angeles"

    /** California: 21 to buy alcohol. POS_LEGAL_AGE / legal.age overrides. */
    const val LEGAL_AGE = 21

    val PROFILE = StoreProfile(
        country = "US", currency = "USD",
        locales = listOf(LocaleCode.EN, LocaleCode.ES),
        timeZone = TIME_ZONE,
        kind = StoreProfile.Kind.RETAIL,
        legalAge = LEGAL_AGE,
    )

    /** Los Angeles-area combined sales tax, 9.5% by default; POS_SALES_TAX_PERCENT overrides. */
    val DEFAULT_SALES_TAX: BigDecimal = BigDecimal("9.5")

    fun salesTax(percent: BigDecimal = DEFAULT_SALES_TAX) = TaxComponent(
        code = "US_SALES", labelFr = "Sales Tax", labelEn = "Sales Tax",
        ratePercent = percent,
        // US receipts don't print a seller's permit number
        registrationNumber = "",
    )

    fun salesTaxFromEnv(env: (String) -> String? = System::getenv): BigDecimal =
        env("POS_SALES_TAX_PERCENT")?.trim()?.toBigDecimalOrNull()
            ?.takeIf { it.signum() >= 0 && it < BigDecimal(30) } ?: DEFAULT_SALES_TAX

    fun matches(venueId: String?): Boolean = venueId?.trim()?.lowercase() == VENUE_ID
}

class SagePoppyConfig(
    private val settings: SettingsRepository,
    override val printer: PrinterAdapter,
    publicBaseUrl: String,
    private val publicUrlProvider: (() -> String)? = null,
    override val legalAge: Int = LegalAge.fromEnv(SagePoppy.LEGAL_AGE),
    salesTaxPercent: BigDecimal = SagePoppy.salesTaxFromEnv(),
    /** cash.rounding / POS_CASH_ROUNDING: nickel (default) or off. */
    cashRounding: CashRounding = CashRounding.DEFAULT,
) : CustomerConfig {

    override val publicBaseUrl: String
        get() = publicUrlProvider?.invoke() ?: initialPublicBaseUrl

    private val initialPublicBaseUrl = publicBaseUrl

    override val customerId = "sagepoppy"
    override val displayName = SagePoppy.DISPLAY_NAME
    override val venueId = SagePoppy.VENUE_ID
    override val brand = "sage-poppy"
    override val profile = SagePoppy.PROFILE

    // ---- policy ----
    // shelf prices are pre-tax; one sales tax is added on top of taxable items
    // (snacks and ice are exempt, per product)
    override val taxPolicy = TaxPolicy.AddedTaxes(listOf(SagePoppy.salesTax(salesTaxPercent)))
    // the US no longer makes pennies either: a cash payment rounds to the
    // nearest five cents (cash.rounding=off charges cash to the cent)
    override val roundingPolicy: RoundingPolicy = cashRounding.policy
    override val authPolicy = AuthPolicy.PinLogin(pinLength = 4)

    // ---- data ----
    // the bottle deposit (CRV) is the one fee: never taxed, its own receipt line
    override val fees: List<Fee> = listOf(Fee.ContainerDeposit())

    override val receiptPolicy: ReceiptPolicy
        get() = settings.get().let { s ->
            ReceiptPolicy.Standard(
                // the brand's letterhead, as the on-screen receipt draws it: the
                // name in capitals, "BOTTLE SHOP" below (ThermalLayout.venueName
                // splits on the dash), the address and phone, then a rule
                logoFallbackText = "SAGE & POPPY — BOTTLE SHOP",
                headerLines = listOf(s.venueAddress, "Tel. ${s.venuePhone}"),
                headerRule = true,
                footerText = s.receiptFooter,
                showTax = false,
                locale = LocaleCode.EN,
                retail = true,
                alwaysCents = true,
                usDates = true,
            )
        }

    // cash, plus a card on the counter's own external terminal. No Stripe:
    // the Stripe integration is Canada-only (a CAD account) for now.
    override val electronicTenders: List<TenderMethod>
        get() = listOf(CardMethod(settings.get().cardProcessor.ifBlank { "External card terminal" }))
}
