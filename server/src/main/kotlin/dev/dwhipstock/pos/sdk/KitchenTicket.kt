package dev.dwhipstock.pos.sdk

import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import dev.dwhipstock.pos.sdk.i18n.MessageKey
import dev.dwhipstock.pos.sdk.i18n.Messages
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** What a kitchen ticket says about itself (the header banner). */
enum class KitchenTicketKind {
    /** The first send of a check to a station. */
    ORDER,
    /** A later send: only the new or increased items. */
    ADD,
    /** Items taken off after they were sent. */
    VOID,
    /** A copy of what the station already has, on request. */
    REPRINT,
}

/** Which language(s) a station's paper speaks: the store's French, English, or both. */
enum class KitchenLanguage {
    FR, EN, BOTH;

    val wire: String get() = name.lowercase()

    /** The locales printed, French first when both. */
    val locales: List<LocaleCode>
        get() = when (this) {
            FR -> listOf(LocaleCode.FR)
            EN -> listOf(LocaleCode.EN)
            BOTH -> listOf(LocaleCode.FR, LocaleCode.EN)
        }

    companion object {
        fun parse(raw: String?): KitchenLanguage? = entries.firstOrNull { it.wire == raw?.trim()?.lowercase() }
    }
}

data class KitchenTicketItem(
    val qty: Int,
    val nameFr: String,
    val nameEn: String,
    val variantFr: String? = null,
    val variantEn: String? = null,
    val note: String? = null,
)

/** Everything one station ticket prints. Pure data: the store assembles it, [KitchenTicketRenderer] lays it out. */
data class KitchenTicketData(
    val kind: KitchenTicketKind,
    val stationNameFr: String,
    val stationNameEn: String,
    val tableLabel: String,
    val serverName: String,
    val checkId: Int,
    val sentAt: LocalDateTime,
    val items: List<KitchenTicketItem>,
    val guests: Int? = null,
    /** Short reference printed at the foot ("45-3"), so a duplicate is easy to spot. */
    val reference: String = "",
)

/**
 * Lays a station ticket out as [PrintLine]s for the thermal printer: big and
 * plain, read at arm's length on a busy pass. The station name sits on a
 * black bar; a VOID ticket carries a second black bar top and bottom so it
 * can't be mistaken for an order. Width-safe through [ThermalLayout] (58mm
 * or 80mm), so a long item name wraps rather than clips.
 */
object KitchenTicketRenderer {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    private fun label(key: MessageKey, language: KitchenLanguage, vararg args: Any): String =
        language.locales.map { Messages.get(key, it, *args) }.distinct().joinToString(" / ")

    private fun name(fr: String, en: String, language: KitchenLanguage): String = when (language) {
        KitchenLanguage.FR -> fr
        KitchenLanguage.EN -> en
        KitchenLanguage.BOTH -> fr
    }

    fun kindKey(kind: KitchenTicketKind): MessageKey = when (kind) {
        KitchenTicketKind.ORDER -> MessageKey.KITCHEN_ORDER
        KitchenTicketKind.ADD -> MessageKey.KITCHEN_ADD
        KitchenTicketKind.VOID -> MessageKey.KITCHEN_VOID
        KitchenTicketKind.REPRINT -> MessageKey.KITCHEN_REPRINT
    }

    fun render(t: KitchenTicketData, language: KitchenLanguage): List<PrintLine> = buildList {
        val station = listOf(t.stationNameFr, t.stationNameEn).let {
            when (language) {
                KitchenLanguage.FR -> it[0]
                KitchenLanguage.EN -> it[1]
                KitchenLanguage.BOTH -> it.distinct().joinToString(" / ")
            }
        }
        val kind = label(kindKey(t.kind), language)
        add(PrintLine.Banner(station))
        if (t.kind == KitchenTicketKind.VOID) add(PrintLine.Banner(kind)) else add(PrintLine.Header(kind))
        add(PrintLine.Large(label(MessageKey.KITCHEN_TABLE, language, t.tableLabel)))
        add(PrintLine.KeyValue(label(MessageKey.KITCHEN_CHECK, language, t.checkId), t.sentAt.format(TIME), emphasized = true))
        add(PrintLine.Text(label(MessageKey.KITCHEN_SERVER, language, t.serverName)))
        t.guests?.takeIf { it > 0 }?.let { add(PrintLine.Text(label(MessageKey.KITCHEN_GUESTS, language, it))) }
        add(PrintLine.Divider)
        for (item in t.items) {
            val prefix = if (t.kind == KitchenTicketKind.VOID) "-${item.qty}" else "${item.qty}"
            add(PrintLine.Large("$prefix × ${name(item.nameFr, item.nameEn, language)}"))
            if (language == KitchenLanguage.BOTH && item.nameEn != item.nameFr) {
                add(PrintLine.KeyValue("      ${item.nameEn}", ""))
            }
            val variant = when (language) {
                KitchenLanguage.FR -> item.variantFr
                KitchenLanguage.EN -> item.variantEn
                KitchenLanguage.BOTH -> listOfNotNull(item.variantFr, item.variantEn).distinct()
                    .joinToString(" / ").ifEmpty { null }
            }
            variant?.let { add(PrintLine.KeyValue("      $it", "", emphasized = true)) }
            item.note?.takeIf { it.isNotBlank() }?.let { add(PrintLine.KeyValue("      » ${it.trim()}", "", emphasized = true)) }
        }
        add(PrintLine.Divider)
        if (t.kind == KitchenTicketKind.VOID) add(PrintLine.Banner(kind))
        if (t.reference.isNotEmpty()) add(PrintLine.Text(t.reference, Align.CENTER))
        add(PrintLine.Blank)
    }

    /** A station's test page: its name, where it prints and the paper width. */
    fun testPage(stationNameFr: String, stationNameEn: String, target: String, paperMm: Int, language: KitchenLanguage): List<PrintLine> =
        buildList {
            add(PrintLine.Banner(when (language) {
                KitchenLanguage.FR -> stationNameFr
                KitchenLanguage.EN -> stationNameEn
                KitchenLanguage.BOTH -> listOf(stationNameFr, stationNameEn).distinct().joinToString(" / ")
            }))
            add(PrintLine.Header(label(MessageKey.KITCHEN_TEST, language)))
            add(PrintLine.Text(label(MessageKey.KITCHEN_TEST_BODY, language), Align.CENTER))
            add(PrintLine.Divider)
            add(PrintLine.Large("2 × Poutine"))
            add(PrintLine.KeyValue("      » àâçéèêëîïôûùüÿœ", "", emphasized = true))
            add(PrintLine.Divider)
            add(PrintLine.Text("$target · ${paperMm}mm", Align.CENTER))
            add(PrintLine.Blank)
        }
}
