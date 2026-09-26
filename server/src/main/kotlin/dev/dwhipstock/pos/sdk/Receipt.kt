package dev.dwhipstock.pos.sdk

import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import dev.dwhipstock.pos.sdk.i18n.MessageKey
import dev.dwhipstock.pos.sdk.i18n.MessageKey.RECEIPT_BILL
import dev.dwhipstock.pos.sdk.i18n.MessageKey.RECEIPT_BILL_BANNER
import dev.dwhipstock.pos.sdk.i18n.MessageKey.RECEIPT_CHANGE
import dev.dwhipstock.pos.sdk.i18n.MessageKey.RECEIPT_CLOSE
import dev.dwhipstock.pos.sdk.i18n.MessageKey.RECEIPT_NOT_A_RECEIPT
import dev.dwhipstock.pos.sdk.i18n.MessageKey.RECEIPT_OPEN
import dev.dwhipstock.pos.sdk.i18n.MessageKey.RECEIPT_PRINTED_AT
import dev.dwhipstock.pos.sdk.i18n.MessageKey.RECEIPT_ROUNDING
import dev.dwhipstock.pos.sdk.i18n.MessageKey.RECEIPT_SUBTOTAL
import dev.dwhipstock.pos.sdk.i18n.MessageKey.RECEIPT_TABLE
import dev.dwhipstock.pos.sdk.i18n.MessageKey.RECEIPT_TOTAL
import dev.dwhipstock.pos.sdk.i18n.MessageKey.RECEIPT_REGISTER
import dev.dwhipstock.pos.sdk.i18n.MessageKey.RECEIPT_SALE
import dev.dwhipstock.pos.sdk.i18n.MessageKey.RECEIPT_AGE_VERIFIED
import dev.dwhipstock.pos.sdk.i18n.MessageKey.RECEIPT_TAX_INCLUDED
import dev.dwhipstock.pos.sdk.i18n.MessageKey.RECEIPT_TAX_LINE
import dev.dwhipstock.pos.sdk.i18n.MessageKey.RECEIPT_TAX_REGISTRATION
import dev.dwhipstock.pos.sdk.i18n.Messages
import dev.dwhipstock.pos.sdk.i18n.dataText
import dev.dwhipstock.pos.sdk.i18n.dataTextOrNull
import java.time.LocalDateTime

/**
 * Structured receipt — everything the layout needs, no strings pre-baked.
 * Built by the vertical (Check → Receipt), rendered by [ReceiptRenderer]
 * against the customer's [ReceiptPolicy], printed via [PrinterAdapter].
 */
data class Receipt(
    val checkId: Int,
    val tableLabel: String,
    val openedAt: LocalDateTime,
    val closedAt: LocalDateTime,
    val items: List<ReceiptItem>,
    val fees: List<ReceiptFee>,
    val grandTotal: Money,
    /** Inclusive tax already inside grandTotal; policy decides whether it prints. */
    val taxIncluded: Money,
    val taxRatePercent: Int?,
    val tenders: List<ReceiptTender>,
    /** Taxes added on top of the pre-tax subtotal, one line each; empty = none. */
    val taxes: List<TaxLine> = emptyList(),
    /** The legal age a passing ID check cleared the sale at (retail); null = no check. */
    val ageVerifiedAt: Int? = null,
) {
    /** Pre-tax subtotal: the total less the taxes added on top. */
    val subtotal: Money get() = grandTotal - Money(taxes.sumOf { it.amount.cents })
}

data class ReceiptItem(
    val nameFr: String,
    val nameEn: String,
    /** Container/size label when the item has more than one (Bouteille / Pichet / Tour). */
    val variantLabelFr: String?,
    val variantLabelEn: String?,
    val qty: Int,
    val unitPrice: Money,
    val lineTotal: Money,
    val note: String?,
)

data class ReceiptFee(val labelFr: String, val labelEn: String, val amount: Money, val code: String = "")

data class ReceiptTender(
    val labelFr: String,
    val labelEn: String,
    val amountTendered: Money,
    val amountApplied: Money,
    val roundingAdjustment: Money,
    val change: Money,
    /** CASH | CARD | …: lets a locale pack name the tender ([Messages.dataLabel]). */
    val type: String = "",
)

/**
 * FINAL = post-payment reçu (proof of payment). PROVISIONAL = the "check please"
 * customer bill (déclaration) printed on request before payment — same layout, minus
 * the tender section, plus a CUSTOMER BILL header and a NOT A RECEIPT footer.
 */
enum class ReceiptKind { FINAL, PROVISIONAL }

/** Customer-tier receipt policy: header/footer identity + formatting decisions. */
sealed interface ReceiptPolicy {
    val logoFallbackText: String
    val headerLines: List<String>
    val footerText: String
    /** Print the included-tax line (label + rate) under the total when the tax policy has a rate. */
    val showTax: Boolean
    val locale: LocaleCode

    /**
     * Date format (yyyy-MM-dd HH:mm) is VENUE policy — continuity
     * with the venue's paper receipts — deliberately NOT locale-driven, so a
     * locale pack cannot change them. Per-locale date patterns would be a
     * future catalog key (e.g. receipt.date_pattern), not a format here.
     */
    fun formatDate(dt: LocalDateTime): String {
        return "%04d-%02d-%02d %02d:%02d".format(dt.year, dt.monthValue, dt.dayOfMonth, dt.hour, dt.minute)
    }

    /** A retail counter: "Register 1 · Sale #12" instead of "Table · Bill". */
    val retail: Boolean get() = false

    /** Same venue identity, different print locale — the check owner's preference wins at close time. */
    fun withLocale(locale: LocaleCode): ReceiptPolicy

    data class Standard(
        override val logoFallbackText: String,
        override val headerLines: List<String>,
        override val footerText: String,
        override val showTax: Boolean,
        override val locale: LocaleCode = LocaleCode.EN,
        override val retail: Boolean = false,
    ) : ReceiptPolicy {
        override fun withLocale(locale: LocaleCode) = copy(locale = locale)
    }
}

/** Layout as data: policy + receipt → ordered PrintLines. No device knowledge here. */
object ReceiptRenderer {

    fun render(
        receipt: Receipt,
        policy: ReceiptPolicy,
        kind: ReceiptKind = ReceiptKind.FINAL,
    ): List<PrintLine> = buildList {
        val locale = policy.locale
        fun msg(key: MessageKey, vararg args: Any) = Messages.get(key, locale, *args)
        val provisional = kind == ReceiptKind.PROVISIONAL

        add(PrintLine.LogoPlaceholder(policy.logoFallbackText))
        policy.headerLines.forEach { add(PrintLine.Text(it, Align.CENTER)) }
        add(PrintLine.Blank)
        if (provisional) {
            // Customer-facing banner: bilingual in every locale pack — anyone at
            // the table might read it, so it doesn't defer to the owner's locale.
            add(PrintLine.Header(msg(RECEIPT_BILL_BANNER)))
            add(PrintLine.Blank)
        }
        if (policy.retail) {
            add(PrintLine.KeyValue(msg(RECEIPT_REGISTER) + " " + receipt.tableLabel, msg(RECEIPT_SALE) + " #" + receipt.checkId))
        } else {
            add(PrintLine.KeyValue(msg(RECEIPT_TABLE) + " " + receipt.tableLabel, msg(RECEIPT_BILL) + " #" + receipt.checkId))
        }
        add(PrintLine.KeyValue(msg(RECEIPT_OPEN), policy.formatDate(receipt.openedAt)))
        // provisional: "Printed at" (this snapshot); final: the close/paid time
        add(PrintLine.KeyValue(
            if (provisional) msg(RECEIPT_PRINTED_AT) else msg(RECEIPT_CLOSE),
            policy.formatDate(receipt.closedAt)))
        add(PrintLine.Divider)

        for (item in receipt.items) {
            val name = locale.dataText(item.nameFr, item.nameEn)
            val variant = locale.dataTextOrNull(item.variantLabelFr, item.variantLabelEn)?.let { " ($it)" } ?: ""
            add(PrintLine.KeyValue("$name$variant ×${item.qty}", item.lineTotal.format()))
            if (item.qty > 1) add(PrintLine.Text("  @${item.unitPrice.format()}"))
            item.note?.let { add(PrintLine.Text("  • $it")) }
        }
        for (fee in receipt.fees) {
            val label = Messages.dataLabel("fee.${fee.code}", locale) ?: locale.dataText(fee.labelFr, fee.labelEn)
            add(PrintLine.KeyValue(label, fee.amount.format()))
        }
        add(PrintLine.Divider)

        // taxes added on top always print (they change the total): subtotal,
        // one line per tax with its rate, then the total
        if (receipt.taxes.isNotEmpty()) {
            add(PrintLine.KeyValue(msg(RECEIPT_SUBTOTAL), receipt.subtotal.format()))
            receipt.taxes.forEach { add(PrintLine.KeyValue(taxLineLabel(it.component, locale), it.amount.format())) }
        }
        add(PrintLine.KeyValue(msg(RECEIPT_TOTAL), receipt.grandTotal.format(), emphasized = true))
        if (policy.showTax && receipt.taxRatePercent != null) {
            add(PrintLine.KeyValue(
                msg(RECEIPT_TAX_INCLUDED, receipt.taxRatePercent),
                receipt.taxIncluded.format(),
            ))
        }
        receipt.taxes.filter { it.component.registrationNumber.isNotBlank() }
            .forEach { add(PrintLine.Text(taxRegistrationLine(it.component, locale))) }
        add(PrintLine.Blank)

        // A provisional bill has no payment yet — omit the tender section, and
        // close with a bold NOT-A-RECEIPT footer instead of the thank-you line.
        if (provisional) {
            add(PrintLine.Header(msg(RECEIPT_NOT_A_RECEIPT)))
            return@buildList
        }

        for (tender in receipt.tenders) {
            val label = Messages.dataLabel("tender.${tender.type}", locale) ?: locale.dataText(tender.labelFr, tender.labelEn)
            add(PrintLine.KeyValue(label, tender.amountTendered.format()))
            if (!tender.roundingAdjustment.isZero) {
                add(PrintLine.KeyValue(msg(RECEIPT_ROUNDING), tender.roundingAdjustment.format()))
            }
            if (!tender.change.isZero) {
                add(PrintLine.KeyValue(msg(RECEIPT_CHANGE), tender.change.format()))
            }
        }

        receipt.ageVerifiedAt?.let {
            add(PrintLine.Blank)
            add(PrintLine.Text(msg(RECEIPT_AGE_VERIFIED, it), Align.CENTER))
        }

        add(PrintLine.Blank)
        add(PrintLine.Text(policy.footerText, Align.CENTER))
    }

    /**
     * Both languages' names, the print locale's first: "GST/TPS" in English,
     * "TPS/GST" in French. A tax with one name ("Sales Tax") prints it once;
     * a locale pack may translate it (`data.tax.<code>`).
     */
    fun taxName(tax: TaxComponent, locale: LocaleCode): String {
        Messages.dataLabel("tax.${tax.code}", locale)?.let { return it }
        if (tax.labelFr == tax.labelEn) return tax.labelEn
        return locale.dataText("${tax.labelFr}/${tax.labelEn}", "${tax.labelEn}/${tax.labelFr}")
    }

    /** "GST/TPS 5%", "TVQ/QST 9,975 %". */
    fun taxLineLabel(tax: TaxComponent, locale: LocaleCode): String =
        Messages.get(RECEIPT_TAX_LINE, locale, taxName(tax, locale), rateText(tax, locale))

    /** "GST/TPS no. 123456789 RT0001". */
    fun taxRegistrationLine(tax: TaxComponent, locale: LocaleCode): String =
        Messages.get(RECEIPT_TAX_REGISTRATION, locale, taxName(tax, locale), tax.registrationNumber)

    /** The rate with the locale's decimal mark ("9.975" / "9,975"). */
    private fun rateText(tax: TaxComponent, locale: LocaleCode): String =
        locale.dataText(tax.rateText.replace('.', ','), tax.rateText)
}
