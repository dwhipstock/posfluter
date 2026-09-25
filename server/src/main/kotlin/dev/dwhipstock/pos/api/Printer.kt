package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.restaurant.DiningTables
import dev.dwhipstock.pos.restaurant.Zones
import dev.dwhipstock.pos.sdk.Align
import dev.dwhipstock.pos.sdk.CustomerConfig
import dev.dwhipstock.pos.sdk.NetworkThermalPrinter
import dev.dwhipstock.pos.sdk.PrintLine
import dev.dwhipstock.pos.restaurant.NotFoundException
import dev.dwhipstock.pos.restaurant.TableTokens
import dev.dwhipstock.pos.restaurant.ConflictException
import dev.dwhipstock.pos.base.SettingsRepository
import dev.dwhipstock.pos.sdk.GuestWifi
import dev.dwhipstock.pos.sdk.WifiSecurity
import dev.dwhipstock.pos.sdk.i18n.LocaleCode
import dev.dwhipstock.pos.sdk.i18n.MessageKey
import dev.dwhipstock.pos.sdk.i18n.Messages
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** Outcome of the bulk "print all table QR slips" action (manager venue setup). */
@Serializable
data class PrintAllResult(
    /** Slips the printer actually accepted before it (if ever) went offline. */
    val printed: Int,
    /** Active tables we tried to print — the denominator for the toast. */
    val attempted: Int,
    val configured: Boolean,
    val online: Boolean,
)

/** Optional POST /printer/wifi/print body. */
@Serializable
data class WifiSlipPrintRequest(val copies: Int = 1)

/**
 * Outcome of printing guest Wi-Fi slips: the [dev.dwhipstock.pos.sdk.PrinterStatus]
 * fields (so it reads like every other print route) plus how many copies the
 * printer took before it (if ever) went offline.
 */
@Serializable
data class WifiSlipPrintResult(
    val configured: Boolean,
    val online: Boolean,
    val lastError: String? = null,
    val lastOkAt: String? = null,
    val printed: Int,
    val copies: Int,
)

const val MAX_WIFI_SLIP_COPIES = 20

/**
 * Network thermal printer control: a manager test-print, a staff-readable
 * health check, and printing a table's "scan to order" QR slip on the receipt
 * printer. All gated by the auth plugin (none are in [isOpenRoute]); the test
 * print and the bulk print-all are additionally manager-only since they're part
 * of venue setup.
 */
fun Route.printerRoutes(printer: NetworkThermalPrinter, config: CustomerConfig, settings: SettingsRepository) {

    /** Owner setup: fire a test page and report whether the printer answered. */
    post("/printer/test") {
        requireManagerSession(call)
        call.respond(printer.testPrint())
    }

    /** Live printer health — drives the "printer offline" toast after a sale. */
    get("/printer/status") {
        call.respond(printer.status())
    }

    /** Print a table's QR "scan to order" slip on the thermal printer. */
    post("/tables/{tableId}/slip/print") {
        val tableId = call.parameters["tableId"]!!
        val wifi = settings.guestWifi()
        val lines = transaction {
            DiningTables.join(Zones, JoinType.INNER, DiningTables.zoneId, Zones.id)
                .selectAll()
                .where { (DiningTables.id eq tableId) and DiningTables.deletedAt.isNull() }
                .firstOrNull()
                ?.let { slipLines(it, config, wifi) }
        } ?: throw NotFoundException("table $tableId not found")
        call.respond(printer.printNow(lines))
    }

    /**
     * Bulk venue setup: print every active table's QR slip, ordered by zone then
     * table number. Manager-only. Uses the synchronous [printNow] per slip (not
     * the fire-and-forget sale queue), so there's no bounded-queue overflow to
     * drop; a single blocked/offline printer just stops the run early rather than
     * hanging on every remaining table.
     */
    post("/tables/slips/print-all") {
        requireManagerSession(call)
        val wifi = settings.guestWifi()
        val slips = transaction {
            DiningTables.join(Zones, JoinType.INNER, DiningTables.zoneId, Zones.id)
                .selectAll()
                .where { DiningTables.deletedAt.isNull() }
                .orderBy(Zones.sortOrder to SortOrder.ASC, DiningTables.sortOrder to SortOrder.ASC)
                .map { slipLines(it, config, wifi) }
        }
        // Nothing set up yet — report it without opening a socket per table.
        if (!printer.status().configured) {
            call.respond(PrintAllResult(0, slips.size, configured = false, online = false))
            return@post
        }
        var printed = 0
        for (lines in slips) {
            val status = printer.printNow(lines)
            if (!status.online) {
                // printer went unreachable mid-run — don't hammer a dead socket
                // for every remaining table; report what got through.
                call.respond(PrintAllResult(printed, slips.size, configured = true, online = false))
                return@post
            }
            printed++
        }
        call.respond(PrintAllResult(printed, slips.size, configured = true, online = true))
    }

    /**
     * Guest Wi-Fi join slip(s): venue name, the join QR, and the network name and
     * password in text. Manager-only (it prints the password). 409
     * `wifi_not_configured` until a network is set up; an unset or offline
     * printer answers 200 with configured/online false, like the other prints.
     * Local only: settings + LAN printer, no internet involved.
     */
    post("/printer/wifi/print") {
        requireManagerSession(call)
        val body = call.receiveText()
        val copies = if (body.isBlank()) 1 else wifiPrintJson.decodeFromString<WifiSlipPrintRequest>(body).copies
        require(copies in 1..MAX_WIFI_SLIP_COPIES) { "copies must be 1–$MAX_WIFI_SLIP_COPIES" }
        val wifi = settings.guestWifi()
            ?: throw ConflictException("guest Wi-Fi is not configured", "wifi_not_configured")
        val lines = wifiSlipLines(config.displayName, wifi)
        var printed = 0
        var status = printer.status()
        if (status.configured) {
            for (i in 1..copies) {
                status = printer.printNow(lines)
                if (!status.online) break // don't hammer a dead socket for every copy
                printed++
            }
        } else {
            status = printer.printNow(lines) // → configured=false, no socket opened
        }
        call.respond(WifiSlipPrintResult(
            status.configured, status.online, status.lastError, status.lastOkAt, printed, copies))
    }
}

private val wifiPrintJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/** Guests read these: each message printed in English, then French. */
private fun bilingual(key: MessageKey): List<String> =
    listOf(Messages.get(key, LocaleCode.EN), Messages.get(key, LocaleCode.FR)).distinct()

private fun bilingualLines(key: MessageKey): List<PrintLine> =
    bilingual(key).map { PrintLine.Text(it, Align.CENTER) }

/**
 * The join QR plus the network name and password in text, for guests whose
 * camera won't read it. Printed straight to the thermal printer (printNow), never
 * spooled to disk, so the password isn't left in receipts/ or bills/.
 */
private fun wifiJoinBlock(wifi: GuestWifi): List<PrintLine> = buildList {
    add(PrintLine.QrCode(wifi.qrPayload()))
    add(PrintLine.Text(bilingual(MessageKey.WIFI_NETWORK).joinToString(" / "), Align.CENTER))
    add(PrintLine.Header(wifi.ssid, exact = true))
    if (wifi.security == WifiSecurity.NOPASS) {
        add(PrintLine.Text(bilingual(MessageKey.WIFI_NO_PASSWORD).joinToString(" / "), Align.CENTER))
    } else {
        add(PrintLine.Text(bilingual(MessageKey.WIFI_PASSWORD).joinToString(" / "), Align.CENTER))
        add(PrintLine.Header(wifi.password, exact = true))
    }
}

/** The stand-alone guest Wi-Fi slip. */
internal fun wifiSlipLines(venueName: String, wifi: GuestWifi): List<PrintLine> = buildList {
    add(PrintLine.LogoPlaceholder(venueName))
    add(PrintLine.Blank)
    add(PrintLine.Header(bilingual(MessageKey.WIFI_FREE).joinToString(" / ")))
    addAll(bilingualLines(MessageKey.WIFI_SCAN_TO_CONNECT))
    addAll(wifiJoinBlock(wifi))
    add(PrintLine.Blank)
}

/**
 * The one place a table's "scan to order" slip is assembled: logo, table label,
 * zone, and the QR of the table's tokenised link ([TableTokens.menuPath]), the
 * same path the on-screen QR shows. Shared by the single-table and
 * print-all endpoints. [row] must be a DiningTables⨝Zones join row.
 */
private fun slipLines(row: ResultRow, config: CustomerConfig, wifi: GuestWifi?): List<PrintLine> {
    val label = row[DiningTables.nameOverride] ?: row[DiningTables.label]
    val url = config.publicBaseUrl + TableTokens.menuPath(row[DiningTables.publicToken]!!)
    return tableSlipLines(config.displayName, label, "${row[Zones.nameFr]} / ${row[Zones.nameEn]}", url, wifi)
}

/**
 * A table slip's lines. Without guest Wi-Fi it is the classic single-QR slip.
 * With it, guests need the venue network first (the menu URL is the tablet's
 * LAN address), so the slip becomes two numbered steps stacked vertically:
 * join the Wi-Fi, then scan to order. Both QRs keep the full print size.
 */
internal fun tableSlipLines(
    venueName: String, label: String, zone: String, menuUrl: String, wifi: GuestWifi?,
): List<PrintLine> {
    val head = listOf(
        PrintLine.LogoPlaceholder(venueName),
        PrintLine.Blank,
        PrintLine.Header(label),
        PrintLine.Text(zone, Align.CENTER),
        PrintLine.Blank,
    )
    if (wifi == null) return head + listOf(
        PrintLine.QrCode(menuUrl),
        PrintLine.Text("Scannez pour commander de la nourriture", Align.CENTER),
        PrintLine.Text("Scan to order", Align.CENTER),
        PrintLine.Blank,
    )
    return head + buildList {
        addAll(bilingualLines(MessageKey.SLIP_STEP_JOIN_WIFI))
        addAll(wifiJoinBlock(wifi))
        add(PrintLine.Blank)
        add(PrintLine.Divider)
        add(PrintLine.Blank)
        addAll(bilingualLines(MessageKey.SLIP_STEP_SCAN_TO_ORDER))
        add(PrintLine.QrCode(menuUrl))
        add(PrintLine.Blank)
    }
}
