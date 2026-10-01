package dev.dwhipstock.pos.customers.copperlantern

import dev.dwhipstock.pos.base.SettingsRepository
import dev.dwhipstock.pos.sdk.AuthPolicy
import dev.dwhipstock.pos.sdk.BankTransferMethod
import dev.dwhipstock.pos.sdk.CardMethod
import dev.dwhipstock.pos.sdk.CashRounding
import dev.dwhipstock.pos.sdk.CustomerConfig
import dev.dwhipstock.pos.sdk.Fee
import dev.dwhipstock.pos.sdk.LegalAge
import dev.dwhipstock.pos.sdk.Money
import dev.dwhipstock.pos.sdk.PrinterAdapter
import dev.dwhipstock.pos.sdk.ReceiptPolicy
import dev.dwhipstock.pos.sdk.RoundingPolicy
import dev.dwhipstock.pos.sdk.StoreProfile
import dev.dwhipstock.pos.sdk.TaxComponent
import dev.dwhipstock.pos.sdk.TaxDisplay
import dev.dwhipstock.pos.sdk.TaxPolicy
import dev.dwhipstock.pos.sdk.TaxRounding
import dev.dwhipstock.pos.sdk.TenderMethod
import dev.dwhipstock.pos.sdk.UpsellConfig
import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import java.math.BigDecimal

/**
 * Copper Lantern — a fictional pub brand in Raleigh, North Carolina (it moved
 * there from Montréal: [CopperLanternRaleighMove]), one configuration per
 * store ([CopperLanternVenue]). USD, English first, Eastern time, ID checks
 * at 21 and North Carolina's taxes ([NC_TAXES]). Owner-editable payment, fee
 * and receipt details read live from [SettingsRepository] so a settings
 * change applies on the next transaction.
 */
class CopperLanternConfig(
    val venue: CopperLanternVenue = CopperLanternVenue.VIEUX_PORT,
    private val settings: SettingsRepository,
    override val printer: PrinterAdapter,
    publicBaseUrl: String,
    private val publicUrlProvider: (() -> String)? = null,
    /** cash.rounding / POS_CASH_ROUNDING: nickel (default) or off. */
    cashRounding: CashRounding = CashRounding.DEFAULT,
    /** Alcohol: 21 in the US. POS_LEGAL_AGE / legal.age overrides. */
    override val legalAge: Int = LegalAge.fromEnv(LEGAL_AGE),
) : CustomerConfig {

    override val publicBaseUrl: String
        get() = publicUrlProvider?.invoke() ?: initialPublicBaseUrl

    private val initialPublicBaseUrl = publicBaseUrl

    override val customerId = "copperlantern"
    override val displayName = venue.displayName
    override val venueId = venue.id
    override val brand = "copper-lantern"
    // Raleigh: US, USD, English (the default) with French, Spanish, German and
    // Afrikaans selectable on the staff screens, the kiosk and reprints (the
    // menu's extra names live in the translations table). Express is the
    // quick-serve counter (no floor plan, numbered orders).
    override val profile = profileFor(venue)
    // table QR and Wi-Fi slips: English only, like the receipts
    override val guestSlipLocales = listOf(LocaleCode.EN)

    // ---- policy: typed, changing these is a deploy, on purpose ----
    // North Carolina: menu prices are pre-tax; the state + Wake County + Wake
    // Transit sales tax and Wake County's prepared food & beverage tax are added
    // on top of everything a pub sells (its food and drinks are all prepared
    // food and beverages). Guests see one "Tax (8.25%)" line, rounded once at
    // 8.25%; the reports keep the two taxes apart for remittance ([NC_TAXES]).
    // US receipts print no tax registration number.
    override val taxPolicy = TaxPolicy.AddedTaxes(NC_TAXES, rounding = TaxRounding.COMBINED, display = TaxDisplay.Combined())
    // the US no longer makes pennies either: a cash payment rounds to the
    // nearest five cents, card is exact (cash.rounding=off charges cash to the cent)
    override val roundingPolicy: RoundingPolicy = cashRounding.policy
    override val authPolicy = AuthPolicy.PinLogin(pinLength = 4)

    // ---- data: assembled from live venue settings ----

    override val fees: List<Fee>
        get() = settings.get().let { s ->
            buildList {
                if (s.serviceChargePercent > 0) add(Fee.ServiceCharge(percent = s.serviceChargePercent))
                if (s.corkagePerBottleCents > 0) add(Fee.Corkage(perBottle = Money(s.corkagePerBottleCents)))
            }
        }

    override val receiptPolicy: ReceiptPolicy
        get() = settings.get().let { s ->
            ReceiptPolicy.Standard(
                logoFallbackText = displayName, // TODO(M2): real logo bitmap for the thermal printer
                headerLines = listOf(s.venueAddress),
                phone = s.venuePhone,
                footerText = s.receiptFooter,
                showTax = false,
                locale = LocaleCode.EN,
                // US receipts: "09/25/2026 5:57 PM"
                usDates = true,
            )
        }

    // the kiosk's "Add a drink?" step: Express only (the pubs have no kiosk)
    override val upsell: UpsellConfig
        get() = if (venue.quickServe) CopperLanternExpressSeed.upsell else UpsellConfig.NONE

    // Stripe Terminal (test mode) unless payment.terminal says otherwise. The
    // Stripe account must be in USD now (STRIPE_KEY_US; StripeService checks).
    override val defaultPaymentTerminal get() = dev.dwhipstock.pos.payments.terminal.TerminalKind.STRIPE

    override val electronicTenders: List<TenderMethod>
        get() = settings.get().let { s ->
            listOf(
                CardMethod(s.cardProcessor),
                BankTransferMethod(s.bankName, s.bankAccountNumber, s.bankAccountName),
            )
        }

    companion object {
        const val COUNTRY = "US"
        const val CURRENCY = "USD"
        const val TIME_ZONE = "America/New_York"

        /** North Carolina: 21 to buy alcohol. POS_LEGAL_AGE / legal.age overrides. */
        const val LEGAL_AGE = 21

        /** English is the default; the others stay selectable (staff screens, kiosk, reprints). */
        val LOCALES = listOf(LocaleCode.EN, LocaleCode.FR, LocaleCode.ES, LocaleCode.DE, LocaleCode.AF)

        fun profileFor(venue: CopperLanternVenue) = StoreProfile(
            country = COUNTRY, currency = CURRENCY,
            locales = LOCALES,
            timeZone = TIME_ZONE,
            kind = if (venue.quickServe) StoreProfile.Kind.QUICK_SERVE else StoreProfile.Kind.RESTAURANT,
            legalAge = LEGAL_AGE,
        )

        /**
         * Raleigh (Wake County), both on the same pre-tax base, never compounded:
         * - sales tax 7.25% = North Carolina's 4.75% + Wake County's 2% +
         *   the Wake Transit 0.5% (Raleigh adds none), remitted to the NC
         *   Department of Revenue (NCDOR);
         * - Wake County's 1% prepared food and beverage tax on restaurant food
         *   and drinks, remitted to Wake County — so a pub check pays 8.25%.
         * Guests see "Tax (8.25%)"; the reports show the two apart. The codes
         * are what reports and sync group by (with the rate: a check closed
         * before this change keeps its 6.75%).
         */
        val NC_SALES_TAX = TaxComponent(
            code = "NC_SALES", labelFr = "NC sales tax", labelEn = "NC sales tax",
            ratePercent = BigDecimal("7.25"), registrationNumber = "", remitTo = "NCDOR",
        )
        val WAKE_FOOD_TAX = TaxComponent(
            code = "WAKE_FOOD", labelFr = "Wake prepared food tax", labelEn = "Wake prepared food tax",
            ratePercent = BigDecimal("1"), registrationNumber = "", remitTo = "Wake County",
        )
        val NC_TAXES = listOf(NC_SALES_TAX, WAKE_FOOD_TAX)
    }
}
