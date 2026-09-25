package dev.dwhipstock.pos.customers.copperlantern

import dev.dwhipstock.pos.base.SettingsRepository
import dev.dwhipstock.pos.sdk.AuthPolicy
import dev.dwhipstock.pos.sdk.BankTransferMethod
import dev.dwhipstock.pos.sdk.CardMethod
import dev.dwhipstock.pos.sdk.CustomerConfig
import dev.dwhipstock.pos.sdk.Fee
import dev.dwhipstock.pos.sdk.Money
import dev.dwhipstock.pos.sdk.PrinterAdapter
import dev.dwhipstock.pos.sdk.ReceiptPolicy
import dev.dwhipstock.pos.sdk.RoundingPolicy
import dev.dwhipstock.pos.sdk.TaxPolicy
import dev.dwhipstock.pos.sdk.TenderMethod

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
) : CustomerConfig {

    override val publicBaseUrl: String
        get() = publicUrlProvider?.invoke() ?: initialPublicBaseUrl

    private val initialPublicBaseUrl = publicBaseUrl

    override val customerId = "copperlantern"
    override val displayName = venue.displayName

    // ---- policy: typed, changing these is a deploy, on purpose ----
    // The demo makes no jurisdiction-specific tax assumption.
    override val taxPolicy = TaxPolicy.NoTax
    // Canadian cash transactions round to the nearest five cents.
    override val roundingPolicy =
        RoundingPolicy.RoundToUnit(unit = Money(5), mode = RoundingPolicy.RoundToUnit.Mode.NEAREST)
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
                headerLines = listOf(s.venueAddress, "Phone / Téléphone: ${s.venuePhone}"),
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
}
