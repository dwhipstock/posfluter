package dev.dwhipstock.pos.sdk

import kotlinx.serialization.Serializable

/**
 * STRIPE = card taken through Stripe Terminal (optional, test mode). It is never
 * a [TenderMethod] in [CustomerConfig.electronicTenders]: staff cannot confirm
 * it by hand; it is recorded only after Stripe reports the payment captured.
 */
enum class TenderType { CASH, CARD, BANK_TRANSFER, STRIPE }

/**
 * An electronic tender method the customer offers. Open (not sealed) on purpose:
 * payment adapters plug their own methods in at the SDK layer. Confirm-then-record:
 * [instructions] tells the payer how to pay;
 * staff confirms in the client after seeing the money arrive, and only the
 * confirm creates a tender row. No PSP integration in v1.
 *
 * labelFr/labelEn (and [DisplayField]'s) are part of the client wire contract —
 * the Flutter client picks a side itself — so folding them into the sdk.i18n
 * message catalog is a coordinated client+server change, left as a seam.
 * Server-side rendering already goes through LocaleCode.dataText().
 */
interface TenderMethod {
    val type: TenderType
    val labelFr: String
    val labelEn: String
    fun instructions(amount: Money): TenderInstructions
}

@Serializable
data class TenderInstructions(
    val type: String,
    val amountCents: Long,
    /** QR payload for a method that shows one (none ship today); null otherwise. */
    val qrPayload: String? = null,
    /** Ordered display fields (label → value), e.g. bank name / account no. / account name. */
    val displayFields: List<DisplayField> = emptyList(),
)

@Serializable
data class DisplayField(val labelFr: String, val labelEn: String, val value: String)

/** Generic card-terminal tender; staff confirms after the terminal approves. */
data class CardMethod(val processorLabel: String = "Card terminal") : TenderMethod {
    override val type = TenderType.CARD
    override val labelFr = "Carte"
    override val labelEn = "Card"
    override fun instructions(amount: Money) = TenderInstructions(
        type = type.name,
        amountCents = amount.cents,
        displayFields = listOf(DisplayField("Terminal", "Terminal", processorLabel)),
    )
}

/** Manual bank transfer: show account details for staff confirmation. */
data class BankTransferMethod(
    val bankName: String,
    val accountNumber: String,
    val accountName: String,
) : TenderMethod {
    override val type = TenderType.BANK_TRANSFER
    override val labelFr = "Virement bancaire"
    override val labelEn = "Bank transfer"

    override fun instructions(amount: Money) = TenderInstructions(
        type = type.name,
        amountCents = amount.cents,
        displayFields = listOf(
            DisplayField("Banque", "Bank", bankName),
            DisplayField("Numéro de compte", "Account no.", accountNumber),
            DisplayField("Titulaire", "Account name", accountName),
        ),
    )
}
