package dev.dwhipstock.pos.sdk

import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import dev.dwhipstock.pos.sdk.i18n.MessageKey
import dev.dwhipstock.pos.sdk.i18n.MessageKey.RECEIPT_BILL
import dev.dwhipstock.pos.sdk.i18n.MessageKey.RECEIPT_BILL_BANNER
import dev.dwhipstock.pos.sdk.i18n.MessageKey.RECEIPT_CASH_TOTAL
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
    /**
     * Bill only: what is still due, paid in cash (rounded to the nickel), and
     * the signed rounding inside it. null / zero = nothing to show.
     */
    val cashDue: Money? = null,
    val cashRounding: Money = Money.ZERO,
    /** Promotions taken off before tax, one line each (a c-store's deals). */
    val discounts: List<ReceiptDiscount> = emptyList(),
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
    /** A gas station's fuel or prepay line: what the pump did. */
    val fuel: ReceiptFuel? = null,
)

/** Fuel on a receipt: "Pump 3 · 10.052 gal @ 3.299/gal", or a prepay for a pump. */
data class ReceiptFuel(
    val pump: Int,
    val prepay: Boolean,
    val volumeMilli: Long? = null,
    val priceMills: Long? = null,
)

/** "2 for $5 energy drinks  -0.98": [label] in English, [labelEs] on a Spanish receipt. */
data class ReceiptDiscount(val label: String, val labelEs: String, val amount: Money)

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
    /** Card-present tenders: the card slip fields (null = print the tender line only). */
    val card: ReceiptCard? = null,
)

/**
 * What a card receipt shows about the card, like a terminal's own slip: brand
 * and masked last four, how it was read, the auth code and the EMV fields.
 * Never a full card number.
 */
data class ReceiptCard(
    val brand: String? = null,
    val last4: String? = null,
    /** tap | insert | swipe | keyed | unknown */
    val entryMode: String = "unknown",
    val authCode: String? = null,
    val aid: String? = null,
    val tvr: String? = null,
    val tsi: String? = null,
    val appLabel: String? = null,
    val cvm: String? = null,
    /** Tip added on the reader, charged on top of the tender amount. */
    val tip: Money = Money.ZERO,
    /** The processor's transaction id and name ("J.P. Morgan sandbox"). */
    val processorRef: String? = null,
    val processor: String? = null,
)

/**
 * FINAL = post-payment reçu (proof of payment). PROVISIONAL = the "check please"
 * customer bill (addition) printed on request before payment — same layout, minus
 * the tender section, plus a CUSTOMER BILL header and a NOT A RECEIPT footer.
 */
enum class ReceiptKind { FINAL, PROVISIONAL }

/** Customer-tier receipt policy: header/footer identity + formatting decisions. */
sealed interface ReceiptPolicy {
    val logoFallbackText: String
    /** Fixed lines under the name (the address); the phone line is added by [header]. */
    val headerLines: List<String>
    /** The store's phone number from settings; its label follows the receipt language. */
    val phone: String get() = ""
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

    /** A rule under the store's name and address block (a brand's letterhead). */
    val headerRule: Boolean get() = false

    /** Always print the cents ("40.00"), US shelf style; the pubs print "40". */
    val alwaysCents: Boolean get() = false

    /**
     * Money on the receipt, in this policy's style: bare figures, no symbol.
     * French prints the French way ("10,50", "1 010" with a no-break space).
     */
    fun money(m: Money): String = (if (alwaysCents) m.formatCents() else m.format()).let {
        if (locale.tag == LocaleCode.FR.tag) Money.frenchFigure(it) else it
    }

    /**
     * The printed block under the store's name: [headerLines], then the phone
     * with its label in this receipt's language ("Tél." / "Tel."). A blank
     * phone prints no line.
     */
    fun header(): List<String> =
        if (phone.isBlank()) headerLines
        else headerLines + Messages.get(MessageKey.RECEIPT_PHONE, locale, phone.trim())

    /** Same venue identity, different print locale — the check owner's preference wins at close time. */
    fun withLocale(locale: LocaleCode): ReceiptPolicy

    data class Standard(
        override val logoFallbackText: String,
        override val headerLines: List<String>,
        override val footerText: String,
        override val showTax: Boolean,
        override val locale: LocaleCode = LocaleCode.EN,
        override val retail: Boolean = false,
        override val alwaysCents: Boolean = false,
        /** US receipts: "09/25/2026 5:57 PM" (month first, 12-hour clock). */
        val usDates: Boolean = false,
        override val headerRule: Boolean = false,
        override val phone: String = "",
    ) : ReceiptPolicy {
        override fun withLocale(locale: LocaleCode) = copy(locale = locale)

        override fun formatDate(dt: LocalDateTime): String {
            if (!usDates) return super.formatDate(dt)
            val h = dt.hour % 12
            val ampm = if (dt.hour < 12) "AM" else "PM"
            return "%02d/%02d/%04d %d:%02d %s".format(dt.monthValue, dt.dayOfMonth, dt.year, if (h == 0) 12 else h, dt.minute, ampm)
        }
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
        policy.header().forEach { add(PrintLine.Text(it, Align.CENTER)) }
        if (policy.headerRule) add(PrintLine.Divider)
        add(PrintLine.Blank)
        if (provisional) {
            // Customer-facing banner: bilingual in every locale pack — anyone at
            // the table might read it, so it doesn't defer to the owner's locale.
            add(PrintLine.Header(msg(RECEIPT_BILL_BANNER)))
            add(PrintLine.Blank)
        }
        if (policy.retail) {
            add(PrintLine.KeyValue(msg(RECEIPT_REGISTER) + " " + receipt.tableLabel, msg(RECEIPT_SALE) + " " + msg(MessageKey.RECEIPT_NUMBER, receipt.checkId)))
        } else {
            add(PrintLine.KeyValue(msg(RECEIPT_TABLE) + " " + receipt.tableLabel, msg(RECEIPT_BILL) + " " + msg(MessageKey.RECEIPT_NUMBER, receipt.checkId)))
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
            add(PrintLine.KeyValue("$name$variant ×${item.qty}", item.lineTotal.let(policy::money)))
            if (item.qty > 1) add(PrintLine.Text("  @${item.unitPrice.let(policy::money)}"))
            item.note?.let { add(PrintLine.Text("  • $it")) }
            item.fuel?.let { f ->
                val v = f.volumeMilli; val p = f.priceMills
                add(PrintLine.Text("  " + when {
                    f.prepay -> msg(MessageKey.RECEIPT_FUEL_PREPAY, f.pump)
                    v != null && p != null -> msg(MessageKey.RECEIPT_FUEL_PUMP, f.pump,
                        "%d.%03d".format(v / 1000, v % 1000), "%d.%03d".format(p / 1000, p % 1000))
                    else -> msg(MessageKey.RECEIPT_FUEL_PREPAY, f.pump)
                }))
            }
        }
        for (d in receipt.discounts) {
            add(PrintLine.KeyValue(if (locale.tag == "es") d.labelEs else d.label, "-" + policy.money(d.amount)))
        }
        for (fee in receipt.fees) {
            val label = Messages.dataLabel("fee.${fee.code}", locale) ?: locale.dataText(fee.labelFr, fee.labelEn)
            add(PrintLine.KeyValue(label, fee.amount.let(policy::money)))
        }
        add(PrintLine.Divider)

        // taxes added on top always print (they change the total): subtotal,
        // one line per tax with its rate, then the total
        if (receipt.taxes.isNotEmpty()) {
            add(PrintLine.KeyValue(msg(RECEIPT_SUBTOTAL), receipt.subtotal.let(policy::money)))
            receipt.taxes.forEach { add(PrintLine.KeyValue(taxLineLabel(it.component, locale), it.amount.let(policy::money))) }
        }
        add(PrintLine.KeyValue(msg(RECEIPT_TOTAL), receipt.grandTotal.let(policy::money), emphasized = true))
        if (policy.showTax && receipt.taxRatePercent != null) {
            add(PrintLine.KeyValue(
                msg(RECEIPT_TAX_INCLUDED, receipt.taxRatePercent),
                receipt.taxIncluded.let(policy::money),
            ))
        }
        // the bill: paying cash rounds to the nickel — show the adjustment and
        // the cash amount under the exact total, so nobody is surprised at the till
        val cashDue = receipt.cashDue
        if (provisional && cashDue != null && !receipt.cashRounding.isZero) {
            add(PrintLine.KeyValue(msg(RECEIPT_ROUNDING), signed(receipt.cashRounding, policy::money)))
            add(PrintLine.KeyValue(msg(RECEIPT_CASH_TOTAL), cashDue.let(policy::money)))
        }
        receipt.taxes.filter { it.component.registrationNumber.isNotBlank() }
            .forEach { add(PrintLine.Text(taxRegistrationLine(it.component, locale))) }
        // fuel is sold at the posted pump price, its taxes inside it
        if (receipt.items.any { it.fuel != null }) add(PrintLine.Text(msg(MessageKey.RECEIPT_FUEL_TAX)))
        add(PrintLine.Blank)

        // A provisional bill has no payment yet — omit the tender section, and
        // close with a bold NOT-A-RECEIPT footer instead of the thank-you line.
        if (provisional) {
            add(PrintLine.Header(msg(RECEIPT_NOT_A_RECEIPT)))
            return@buildList
        }

        for (tender in receipt.tenders) {
            val label = Messages.dataLabel("tender.${tender.type}", locale) ?: locale.dataText(tender.labelFr, tender.labelEn)
            // a cash payment that settled the balance: the nickel rounding, then
            // the rounded cash amount the customer actually paid
            if (!tender.roundingAdjustment.isZero) {
                add(PrintLine.KeyValue(msg(RECEIPT_ROUNDING), signed(tender.roundingAdjustment, policy::money)))
                add(PrintLine.KeyValue(msg(RECEIPT_CASH_TOTAL),
                    (tender.amountApplied + tender.roundingAdjustment).let(policy::money), emphasized = true))
            }
            add(PrintLine.KeyValue(label, tender.amountTendered.let(policy::money)))
            if (!tender.change.isZero) {
                add(PrintLine.KeyValue(msg(RECEIPT_CHANGE), tender.change.let(policy::money)))
            }
            tender.card?.let { card -> addAll(cardLines(card, tender.amountTendered, policy::money) { msg(it) }) }
        }

        receipt.ageVerifiedAt?.let {
            add(PrintLine.Blank)
            add(PrintLine.Text(msg(RECEIPT_AGE_VERIFIED, it), Align.CENTER))
        }

        add(PrintLine.Blank)
        add(PrintLine.Text(policy.footerText, Align.CENTER))
    }

    /**
     * The card slip block under a card tender, laid out like a terminal's own
     * receipt: "VISA **** 4242 · Contactless", the auth code, EMV AID / TVR /
     * TSI (chip and tap only), the application label and CVM, then APPROVED.
     * A tip added on the reader prints with the total charged.
     */
    fun cardLines(
        card: ReceiptCard, amount: Money, money: (Money) -> String, msg: (MessageKey) -> String,
    ): List<PrintLine> = buildList {
        val entry = when (card.entryMode) {
            "tap" -> msg(MessageKey.RECEIPT_CARD_ENTRY_TAP)
            "insert" -> msg(MessageKey.RECEIPT_CARD_ENTRY_INSERT)
            "swipe" -> msg(MessageKey.RECEIPT_CARD_ENTRY_SWIPE)
            "keyed" -> msg(MessageKey.RECEIPT_CARD_ENTRY_KEYED)
            else -> ""
        }
        if (!card.tip.isZero) {
            add(PrintLine.KeyValue(msg(MessageKey.RECEIPT_CARD_TIP), money(card.tip)))
            add(PrintLine.KeyValue(msg(MessageKey.RECEIPT_CARD_TOTAL), money(amount + card.tip), emphasized = true))
        }
        val cardName = listOfNotNull(card.brand?.uppercase(), card.last4?.let { "**** $it" }).joinToString(" ")
        if (cardName.isNotEmpty() || entry.isNotEmpty()) add(PrintLine.KeyValue(cardName, entry))
        card.authCode?.let { add(PrintLine.KeyValue(msg(MessageKey.RECEIPT_CARD_AUTH), it)) }
        card.aid?.let { add(PrintLine.KeyValue("AID", it)) }
        if (card.tvr != null || card.tsi != null)
            add(PrintLine.KeyValue(card.tvr?.let { "TVR $it" } ?: "", card.tsi?.let { "TSI $it" } ?: ""))
        if (card.appLabel != null || card.cvm != null)
            add(PrintLine.KeyValue(card.appLabel ?: "", card.cvm ?: ""))
        card.processorRef?.let {
            card.processor?.let { p -> add(PrintLine.Text(p)) }
            add(PrintLine.KeyValue("Ref", it))
        }
        add(PrintLine.Text(msg(MessageKey.RECEIPT_CARD_APPROVED), Align.CENTER))
    }

    /** A rounding adjustment with its sign: "-0.02", "+0.01". */
    fun signed(m: Money, format: (Money) -> String = Money::format): String =
        if (m > Money.ZERO) "+" + format(m) else format(m)

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
