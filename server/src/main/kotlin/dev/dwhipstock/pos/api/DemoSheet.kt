package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.base.SettingsRepository
import dev.dwhipstock.pos.base.ConflictException
import dev.dwhipstock.pos.restaurant.DiningTables
import dev.dwhipstock.pos.restaurant.TableTokens
import dev.dwhipstock.pos.restaurant.Zones
import dev.dwhipstock.pos.sdk.Align
import dev.dwhipstock.pos.sdk.CustomerConfig
import dev.dwhipstock.pos.sdk.DemoMode
import dev.dwhipstock.pos.sdk.GuestWifi
import dev.dwhipstock.pos.sdk.NetworkThermalPrinter
import dev.dwhipstock.pos.sdk.PrintLine
import dev.dwhipstock.pos.sdk.StoreProfile
import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import dev.dwhipstock.pos.sdk.i18n.MessageKey
import dev.dwhipstock.pos.sdk.i18n.Messages
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * The demo seed's server PIN — the only PIN the demo sheet prints. The
 * manager PIN is never on the slip: in demo mode a manager signs in and
 * approves only on the POS tablet itself ([dev.dwhipstock.pos.base.ManagerOnPosOnlyException]).
 */
internal const val DEMO_SERVER_PIN = "9999"

/** What the demo QR sheet lists; each null / false block is left off the slip. */
internal data class DemoSheet(
    val venueName: String,
    /** The store's LAN address, the same host the table slips print. */
    val baseUrl: String,
    /** First table's label + its scan-to-order link; null for a quick-serve or table-less store. */
    val firstTable: Pair<String, String>?,
    val kitchen: Boolean,
    val pickup: Boolean,
    val demo: DemoMode,
    val wifi: GuestWifi?,
)

/**
 * Demo mode only (demo.mode=on): one slip with a QR for every app a guest can
 * try at the demo, and how to sign in to each (the server PIN only — never the
 * manager's). A manager on the POS itself only ([requireManagerOnPos]: it prints
 * the portal sign-in and the Wi-Fi password). 409 `demo_mode_off` otherwise.
 * `?lang=` prints in the POS's current language (English when absent or unknown).
 *
 * Printed straight to the thermal printer with [NetworkThermalPrinter.printNow],
 * like the table and Wi-Fi slips: one ESC/POS job, never spooled to disk and
 * never logged, so the portal password lives only on the paper.
 */
fun Route.demoSheetRoutes(
    printer: NetworkThermalPrinter, config: CustomerConfig, settings: SettingsRepository,
    demo: DemoMode, kitchenOn: Boolean,
) {
    post("/printer/demo-sheet/print") {
        // a manager on the POS itself — never a staff-app bearer or a phone on the
        // Wi-Fi: the slip carries the portal password, so it must not be reprintable remotely
        requireManagerOnPos(call)
        if (!demo.on) throw ConflictException("demo mode is off", "demo_mode_off")
        val quickServe = config.profile.kind == StoreProfile.Kind.QUICK_SERVE
        val firstTable = if (quickServe) null else transaction {
            DiningTables.join(Zones, JoinType.INNER, DiningTables.zoneId, Zones.id)
                .selectAll()
                .where { DiningTables.deletedAt.isNull() and DiningTables.publicToken.isNotNull() }
                .orderBy(Zones.sortOrder to SortOrder.ASC, DiningTables.sortOrder to SortOrder.ASC)
                .firstOrNull()
                ?.let {
                    (it[DiningTables.nameOverride] ?: it[DiningTables.label]) to
                        config.publicBaseUrl + TableTokens.menuPath(it[DiningTables.publicToken]!!)
                }
        }
        val sheet = DemoSheet(
            venueName = config.displayName,
            baseUrl = config.publicBaseUrl,
            firstTable = firstTable,
            kitchen = kitchenOn,
            pickup = quickServe,
            demo = demo,
            wifi = settings.guestWifi(),
        )
        // always English: the demo audience reads English; the other languages are shown on the POS itself
        call.respond(printer.printNow(demoSheetLines(sheet, LocaleCode.EN)))
    }
}

/** The slip's lines, in [locale]. */
internal fun demoSheetLines(sheet: DemoSheet, locale: LocaleCode = LocaleCode.EN): List<PrintLine> = buildList {
    fun m(key: MessageKey, vararg args: Any) = Messages.get(key, locale, *args)
    fun centered(text: String) = add(PrintLine.Text(text, Align.CENTER))
    fun block(title: MessageKey, about: MessageKey, url: String?, vararg signIn: String) {
        add(PrintLine.Divider)
        add(PrintLine.Blank)
        add(PrintLine.Large(m(title), Align.CENTER))
        centered(m(about))
        if (url != null) {
            add(PrintLine.QrCode(url))
            centered(url)
        }
        signIn.forEach(::centered)
        add(PrintLine.Blank)
    }
    val base = sheet.baseUrl.trimEnd('/')
    val pins = m(MessageKey.DEMO_SIGN_IN_PINS, DEMO_SERVER_PIN)
    val noSignIn = m(MessageKey.DEMO_NO_SIGN_IN)

    add(PrintLine.LogoPlaceholder(sheet.venueName))
    add(PrintLine.Blank)
    add(PrintLine.Header(m(MessageKey.DEMO_HEADER)))
    centered(m(MessageKey.DEMO_NOTE))
    add(PrintLine.Blank)

    sheet.firstTable?.let { (label, url) ->
        add(PrintLine.Divider)
        add(PrintLine.Blank)
        add(PrintLine.Large(m(MessageKey.DEMO_GUEST_TITLE), Align.CENTER))
        centered(m(MessageKey.DEMO_GUEST_ABOUT, label))
        add(PrintLine.QrCode(url))
        centered(url)
        centered(noSignIn)
        add(PrintLine.Blank)
    }
    block(MessageKey.DEMO_STAFF_TITLE, MessageKey.DEMO_STAFF_ABOUT, "$base/staff-app", pins)
    if (sheet.kitchen) block(MessageKey.DEMO_KITCHEN_TITLE, MessageKey.DEMO_KITCHEN_ABOUT, "$base/kitchen", pins)
    if (sheet.pickup) block(MessageKey.DEMO_PICKUP_TITLE, MessageKey.DEMO_PICKUP_ABOUT, "$base/pickup", noSignIn)

    val ask = m(MessageKey.DEMO_ASK_PRESENTER)
    val portal = sheet.demo.portalUrl
    block(
        MessageKey.DEMO_PORTAL_TITLE, MessageKey.DEMO_PORTAL_ABOUT, portal,
        *listOfNotNull(
            if (portal == null) m(MessageKey.DEMO_PORTAL_ADDRESS, ask) else null,
            m(MessageKey.DEMO_PORTAL_USER, sheet.demo.portalUser ?: ask),
            m(MessageKey.DEMO_PORTAL_PASSWORD, sheet.demo.portalPassword ?: ask),
        ).toTypedArray(),
    )

    sheet.wifi?.let { wifi ->
        add(PrintLine.Divider)
        add(PrintLine.Blank)
        add(PrintLine.Large(m(MessageKey.DEMO_WIFI_TITLE), Align.CENTER))
        centered(m(MessageKey.DEMO_WIFI_ABOUT))
        addAll(wifiJoinBlock(wifi, listOf(locale)))
        add(PrintLine.Blank)
    }
}
