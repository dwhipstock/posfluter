package dev.dwhipstock.pos.restaurant

import dev.dwhipstock.pos.sdk.Align
import dev.dwhipstock.pos.sdk.Money
import dev.dwhipstock.pos.sdk.MoneyFormat
import dev.dwhipstock.pos.sdk.PrintLine
import dev.dwhipstock.pos.sdk.Receipt
import dev.dwhipstock.pos.sdk.ReceiptPolicy
import dev.dwhipstock.pos.sdk.ReceiptRenderer
import dev.dwhipstock.pos.sdk.i18n.MessageKey
import dev.dwhipstock.pos.sdk.i18n.Messages

/**
 * The guest's ticket from a self-order kiosk, printed on the store's receipt
 * printer the moment the order is placed: the brand, the order number big,
 * dine in / take out, the items, the total (and the cash total, to the
 * nickel), and "Please pay at the counter" — all in the language the guest
 * chose at the kiosk ([ReceiptPolicy.locale]). Money is North American in
 * every language ("$1,234.56").
 */
object KioskTicket {
    fun render(
        bill: Receipt,
        orderNumber: Int,
        takeOut: Boolean,
        alcohol: Boolean,
        policy: ReceiptPolicy,
        currency: String,
        /** The store's legal drinking age, printed on the ID note (21 in the US). */
        legalAge: Int = 21,
    ): List<PrintLine> = buildList {
        val locale = policy.locale
        fun msg(key: MessageKey, vararg args: Any) = Messages.get(key, locale, *args)
        fun money(m: Money) = MoneyFormat.format(m, currency, locale.tag)

        add(PrintLine.LogoPlaceholder(policy.logoFallbackText))
        policy.header().forEach { add(PrintLine.Text(it, Align.CENTER)) }
        add(PrintLine.Divider)
        add(PrintLine.Text(msg(MessageKey.KIOSK_TICKET_TITLE), Align.CENTER))
        add(PrintLine.Huge("#$orderNumber"))
        add(PrintLine.Header(msg(if (takeOut) MessageKey.KIOSK_TAKE_OUT else MessageKey.KIOSK_DINE_IN)))
        add(PrintLine.Text(policy.formatDate(bill.openedAt), Align.CENTER))
        add(PrintLine.Divider)
        for (item in bill.items) add(PrintLine.KeyValue(ReceiptRenderer.itemText(item, locale), money(item.lineTotal)))
        add(PrintLine.Divider)
        if (bill.taxes.isNotEmpty()) {
            add(PrintLine.KeyValue(Messages.get(MessageKey.RECEIPT_SUBTOTAL, locale), money(bill.subtotal)))
            bill.taxes.forEach { add(PrintLine.KeyValue(ReceiptRenderer.taxLineLabel(it.component, locale), money(it.amount))) }
        }
        add(PrintLine.KeyValue(Messages.get(MessageKey.RECEIPT_TOTAL, locale), money(bill.grandTotal), emphasized = true))
        val cash = bill.cashDue
        if (cash != null && !bill.cashRounding.isZero) {
            add(PrintLine.KeyValue(Messages.get(MessageKey.RECEIPT_CASH_TOTAL, locale), money(cash)))
        }
        add(PrintLine.Blank)
        if (alcohol) add(PrintLine.Text(msg(MessageKey.KIOSK_ID_CHECK, legalAge), Align.CENTER))
        add(PrintLine.Header(msg(MessageKey.KIOSK_PAY_AT_COUNTER)))
        add(PrintLine.Blank)
    }
}
