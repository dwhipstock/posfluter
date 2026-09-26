package dev.dwhipstock.pos.customers.pronghorn

import dev.dwhipstock.pos.base.SettingsRepository
import dev.dwhipstock.pos.forecourt.FuelGrade
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
 * Pronghorn Fuel & Market — a fictional Texas Hill Country gas station with a
 * convenience store (`POS_VENUE=pronghorn`), the third client. Its own brand,
 * USD, English + Spanish, Central time; a retail counter with a forecourt of
 * eight pumps, barcode scanning and ID checks at 21 (beer, tobacco and vape).
 *
 * Texas keeps it simple: the fuel taxes are inside the posted pump price, so
 * fuel lines get no sales tax; in-store taxable goods get one added sales
 * tax (8.25%: the 6.25% state rate + 2% local, the usual combined maximum).
 * Groceries and snack foods are exempt; candy, soft drinks and prepared food
 * are taxable. No bottle deposit in Texas.
 */
object Pronghorn {
    const val VENUE_ID = "pronghorn"
    const val DISPLAY_NAME = "Pronghorn Fuel & Market"
    const val ADDRESS = "4400 Pronghorn Trail, Dripping Springs, TX 78620"
    const val PHONE = "+1 512 555 0163"
    const val TIME_ZONE = "America/Chicago"

    /** Beer, tobacco and vape: 21 in Texas (and federally for tobacco). POS_LEGAL_AGE / legal.age overrides. */
    const val LEGAL_AGE = 21

    /** The forecourt: eight dispensers, four grades each. FORECOURT_PUMPS overrides the count. */
    const val PUMPS = 8

    val PROFILE = StoreProfile(
        country = "US", currency = "USD",
        locales = listOf(LocaleCode.EN, LocaleCode.ES),
        timeZone = TIME_ZONE,
        kind = StoreProfile.Kind.RETAIL,
        legalAge = LEGAL_AGE,
    )

    /** Texas combined sales tax, 8.25% by default; POS_SALES_TAX_PERCENT overrides. */
    val DEFAULT_SALES_TAX: BigDecimal = BigDecimal("8.25")

    /**
     * The grades on every dispenser and their posted prices per gallon, in
     * thousandths of a dollar (US pump prices end in 9/10 of a cent). The
     * store tells the controller these when it connects.
     */
    val GRADES = listOf(
        FuelGrade("REG", "Regular", "Regular", 2_899),
        FuelGrade("MID", "Mid-Grade", "Intermedia", 3_299),
        FuelGrade("PRE", "Premium", "Premium", 3_699),
        FuelGrade("DSL", "Diesel", "Diésel", 3_499),
    )

    fun salesTax(percent: BigDecimal = DEFAULT_SALES_TAX) = TaxComponent(
        code = "US_SALES", labelFr = "Sales Tax", labelEn = "Sales Tax",
        ratePercent = percent,
        registrationNumber = "",
    )

    fun salesTaxFromEnv(env: (String) -> String? = System::getenv): BigDecimal =
        env("POS_SALES_TAX_PERCENT")?.trim()?.toBigDecimalOrNull()
            ?.takeIf { it.signum() >= 0 && it < BigDecimal(30) } ?: DEFAULT_SALES_TAX

    fun pumpsFromEnv(env: (String) -> String? = System::getenv): Int =
        env("FORECOURT_PUMPS")?.trim()?.toIntOrNull()?.takeIf { it in 1..32 } ?: PUMPS

    fun matches(venueId: String?): Boolean = venueId?.trim()?.lowercase() == VENUE_ID
}

class PronghornConfig(
    private val settings: SettingsRepository,
    override val printer: PrinterAdapter,
    publicBaseUrl: String,
    private val publicUrlProvider: (() -> String)? = null,
    override val legalAge: Int = LegalAge.fromEnv(Pronghorn.LEGAL_AGE),
    salesTaxPercent: BigDecimal = Pronghorn.salesTaxFromEnv(),
    cashRounding: CashRounding = CashRounding.DEFAULT,
) : CustomerConfig {

    override val publicBaseUrl: String
        get() = publicUrlProvider?.invoke() ?: initialPublicBaseUrl

    private val initialPublicBaseUrl = publicBaseUrl

    override val customerId = "pronghorn"
    override val displayName = Pronghorn.DISPLAY_NAME
    override val venueId = Pronghorn.VENUE_ID
    override val brand = "pronghorn"
    override val profile = Pronghorn.PROFILE

    // shelf prices are pre-tax, one sales tax on taxable goods; fuel is sold
    // at the pump price with its taxes inside, so fuel items are not taxable
    override val taxPolicy = TaxPolicy.AddedTaxes(listOf(Pronghorn.salesTax(salesTaxPercent)))
    override val roundingPolicy: RoundingPolicy = cashRounding.policy
    override val authPolicy = AuthPolicy.PinLogin(pinLength = 4)
    // no bottle deposit in Texas
    override val fees: List<Fee> = emptyList()

    override val receiptPolicy: ReceiptPolicy
        get() = settings.get().let { s ->
            ReceiptPolicy.Standard(
                logoFallbackText = "PRONGHORN — FUEL & MARKET",
                headerLines = listOf(s.venueAddress),
                phone = s.venuePhone,
                headerRule = true,
                footerText = s.receiptFooter,
                showTax = false,
                locale = LocaleCode.EN,
                retail = true,
                alwaysCents = true,
                usDates = true,
            )
        }

    // cash, and a card on the counter's own terminal (no Stripe: CAD-only for now)
    override val electronicTenders: List<TenderMethod>
        get() = listOf(CardMethod(settings.get().cardProcessor.ifBlank { "External card terminal" }))
}
