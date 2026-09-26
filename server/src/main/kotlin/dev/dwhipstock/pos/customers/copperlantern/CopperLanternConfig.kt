package dev.dwhipstock.pos.customers.copperlantern

import dev.dwhipstock.pos.base.SettingsRepository
import dev.dwhipstock.pos.sdk.AuthPolicy
import dev.dwhipstock.pos.sdk.BankTransferMethod
import dev.dwhipstock.pos.sdk.CardMethod
import dev.dwhipstock.pos.sdk.CashRounding
import dev.dwhipstock.pos.sdk.CustomerConfig
import dev.dwhipstock.pos.sdk.Fee
import dev.dwhipstock.pos.sdk.Money
import dev.dwhipstock.pos.sdk.PrinterAdapter
import dev.dwhipstock.pos.sdk.ReceiptPolicy
import dev.dwhipstock.pos.sdk.RoundingPolicy
import dev.dwhipstock.pos.sdk.StoreProfile
import dev.dwhipstock.pos.sdk.TaxComponent
import dev.dwhipstock.pos.sdk.TaxPolicy
import dev.dwhipstock.pos.sdk.TenderMethod
import java.math.BigDecimal

/**
 * Copper Lantern — fictional Canadian venue configuration, one per store
 * ([CopperLanternVenue]). Owner-editable
 * payment, fee, and receipt details read live from
 * [SettingsRepository] so a settings change applies on the next transaction.
 */
class CopperLanternConfig(
    val venue: CopperLanternVenue = CopperLanternVenue.VIEUX_PORT,
    private val settings: SettingsRepository,
    override val printer: PrinterAdapter,
    publicBaseUrl: String,
    private val publicUrlProvider: (() -> String)? = null,
    /** cash.rounding / POS_CASH_ROUNDING: nickel (default) or off. */
    cashRounding: CashRounding = CashRounding.DEFAULT,
) : CustomerConfig {

    override val publicBaseUrl: String
        get() = publicUrlProvider?.invoke() ?: initialPublicBaseUrl

    private val initialPublicBaseUrl = publicBaseUrl

    override val customerId = "copperlantern"
    override val displayName = venue.displayName
    override val venueId = venue.id
    override val brand = "copper-lantern"
    // Montréal: Canada, CAD, French + English (StoreProfile.QUEBEC_PUB)
    override val profile = StoreProfile.QUEBEC_PUB

    // ---- policy: typed, changing these is a deploy, on purpose ----
    // Québec: menu prices are pre-tax; GST (TPS) and QST (TVQ) are added on
    // top. Registration numbers are fictional, in the real formats.
    override val taxPolicy = TaxPolicy.AddedTaxes(QUEBEC_TAXES)
    // Canada has no penny: a cash payment rounds to the nearest five cents
    // (cash.rounding=off charges cash to the cent)
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
            )
        }

    override val electronicTenders: List<TenderMethod>
        get() = settings.get().let { s ->
            listOf(
                CardMethod(s.cardProcessor),
                BankTransferMethod(s.bankName, s.bankAccountNumber, s.bankAccountName),
            )
        }

    companion object {
        /** GST 5% and QST 9.975%, both on the same pre-tax base (QST is not charged on GST). */
        val QUEBEC_TAXES = listOf(
            TaxComponent("GST", labelFr = "TPS", labelEn = "GST", ratePercent = BigDecimal("5"),
                registrationNumber = "123456789 RT0001"),
            TaxComponent("QST", labelFr = "TVQ", labelEn = "QST", ratePercent = BigDecimal("9.975"),
                registrationNumber = "1234567890 TQ0001"),
        )
    }
}
