package dev.dwhipstock.pos.customers.barcode

import dev.dwhipstock.pos.base.SettingsRepository
import dev.dwhipstock.pos.sdk.AuthPolicy
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
import dev.dwhipstock.pos.sdk.TaxPolicy
import dev.dwhipstock.pos.sdk.TenderMethod
import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import java.math.BigDecimal

/**
 * Barcode Pub (บาร์โค้ด) — a neighbourhood pub in Bangkok (`POS_VENUE=barcode-pub`,
 * tenant `barcode`). Its own brand (its neon sign: violet on near-black), THB, Thai first with
 * English, Bangkok time. Starts with a BLANK menu and no rooms or tables: the
 * owner builds them in the portal (or with the AI). [BarcodeSeed] only sets
 * the receipt header and the bootstrap manager.
 */
object Barcode {
    const val VENUE_ID = "barcode-pub"
    const val CUSTOMER_ID = "barcode"
    const val BRAND = "barcode"

    const val DISPLAY_NAME = "Barcode Pub"
    /** The Thai name on the sign. */
    const val DISPLAY_NAME_TH = "บาร์โค้ด"

    /** The receipt address: English, and Thai on a Thai receipt ([addressLines]). */
    const val ADDRESS = "679/15 Itsaraphap Rd, Wat Tha Phra, Bangkok Yai, Bangkok 10600, Thailand"
    const val ADDRESS_TH = "679/15 ถนนอิสรภาพ แขวงวัดท่าพระ เขตบางกอกใหญ่ กรุงเทพมหานคร 10600"
    const val PHONE = "096-951-9822"
    const val RECEIPT_FOOTER = "ขอบคุณที่ใช้บริการ · Thank you"

    const val COUNTRY = "TH"
    const val CURRENCY = "THB"
    const val TIME_ZONE = "Asia/Bangkok"

    /** Thailand: 20 to buy alcohol. POS_LEGAL_AGE / legal.age overrides. */
    const val LEGAL_AGE = 20

    /**
     * Thai VAT, 7%, already inside the menu prices: computed per check for
     * the reports, never added to the total. The second label is the store's
     * other language (Thai: the *_fr slot follows the store's locales).
     */
    val VAT = TaxComponent(
        code = "VAT", labelFr = "ภาษีมูลค่าเพิ่ม", labelEn = "VAT",
        ratePercent = BigDecimal("7"), registrationNumber = "", remitTo = "Revenue Department",
    )

    /** Corkage per bottle a guest brings: ฿200 (satang on the wire). The owner can change it in settings. */
    const val CORKAGE_PER_BOTTLE_SATANG = 20_000L

    val TH = LocaleCode("th")

    val PROFILE = StoreProfile(
        country = COUNTRY, currency = CURRENCY,
        // Thai first, English second. The store's menu language columns follow
        // this list once feat/thai-locale lands (Thai in the *_fr columns).
        locales = listOf(TH, LocaleCode.EN),
        timeZone = TIME_ZONE,
        kind = StoreProfile.Kind.RESTAURANT,
        legalAge = LEGAL_AGE,
    )

    fun matches(venueId: String?): Boolean = venueId?.trim()?.lowercase() == VENUE_ID

    /**
     * The address block per receipt language, while the store still has the
     * address it was set up with (in either language). An address the owner
     * typed in settings prints as typed, in every language.
     */
    fun addressLines(settingsAddress: String): Map<LocaleCode, List<String>> =
        if (settingsAddress.trim() == ADDRESS || settingsAddress.trim() == ADDRESS_TH)
            mapOf(TH to listOf(ADDRESS_TH), LocaleCode.EN to listOf(ADDRESS))
        else emptyMap()
}

class BarcodeConfig(
    private val settings: SettingsRepository,
    override val printer: PrinterAdapter,
    publicBaseUrl: String,
    private val publicUrlProvider: (() -> String)? = null,
    override val legalAge: Int = LegalAge.fromEnv(Barcode.LEGAL_AGE),
    /** cash.rounding / POS_CASH_ROUNDING; unset, THB's default: cash DOWN to the whole baht. */
    cashRounding: CashRounding = CashRounding.defaultFor(Barcode.CURRENCY),
) : CustomerConfig {

    override val publicBaseUrl: String
        get() = publicUrlProvider?.invoke() ?: initialPublicBaseUrl

    private val initialPublicBaseUrl = publicBaseUrl

    override val customerId = Barcode.CUSTOMER_ID
    override val displayName = Barcode.DISPLAY_NAME
    override val brandName = Barcode.DISPLAY_NAME
    override val venueId = Barcode.VENUE_ID
    override val brand = Barcode.BRAND
    override val profile = Barcode.PROFILE

    // ---- policy ----
    // 7% VAT included in the menu prices: on the reports, not printed on receipts
    override val taxPolicy: TaxPolicy = TaxPolicy.InclusiveTax(Barcode.VAT, showOnReceipt = false)
    override val roundingPolicy: RoundingPolicy = cashRounding.policy
    override val authPolicy = AuthPolicy.PinLogin(pinLength = 4)

    // ---- data: from live venue settings ----
    // service charge off by default; corkage ฿200 per bottle (seeded, owner-editable)
    override val fees: List<Fee>
        get() = settings.get().let { s ->
            buildList {
                if (s.serviceChargePercent > 0) add(Fee.ServiceCharge(percent = s.serviceChargePercent))
                if (s.corkagePerBottleCents > 0) add(Fee.Corkage(perBottle = Money(s.corkagePerBottleCents)))
            }
        }

    override val receiptPolicy: ReceiptPolicy
        get() = settings.get().let { s ->
            val byLocale = Barcode.addressLines(s.venueAddress)
            ReceiptPolicy.Standard(
                // receipts print the name as text (no store prints a logo
                // bitmap yet; branding/barcode-logo-receipt.png is ready).
                // TODO(feat/thai-locale): "Barcode Pub — บาร์โค้ด" once the
                // receipt raster font has Thai glyphs
                logoFallbackText = Barcode.DISPLAY_NAME,
                headerLines = byLocale[LocaleCode.EN] ?: listOf(s.venueAddress),
                phone = s.venuePhone,
                footerText = s.receiptFooter,
                showTax = false,
                // TODO(feat/thai-locale): print in Thai (Barcode.TH) by default once
                // messages_th and the Thai receipt font exist; a Thai reprint
                // (withLocale) already gets the Thai address
                locale = LocaleCode.EN,
                headerLinesByLocale = byLocale,
            )
        }

    // cash, plus the built-in card simulator for now (the default terminal).
    // TODO(barcode): PromptPay QR comes later.
    override val electronicTenders: List<TenderMethod>
        get() = listOf(CardMethod(settings.get().cardProcessor.ifBlank { "Card" }))
}
