package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.restaurant.DiningTables
import dev.dwhipstock.pos.restaurant.Zones
import dev.dwhipstock.pos.sdk.Align
import dev.dwhipstock.pos.sdk.CustomerConfig
import dev.dwhipstock.pos.sdk.NetworkThermalPrinter
import dev.dwhipstock.pos.sdk.PrintLine
import dev.dwhipstock.pos.restaurant.NotFoundException
import io.ktor.server.application.*
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

/**
 * Network thermal printer control: a manager test-print, a staff-readable
 * health check, and printing a table's "scan to order" QR slip on the receipt
 * printer. All gated by the auth plugin (none are in [isOpenRoute]); the test
 * print and the bulk print-all are additionally manager-only since they're part
 * of venue setup.
 */
fun Route.printerRoutes(printer: NetworkThermalPrinter, config: CustomerConfig) {

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
        val lines = transaction {
            DiningTables.join(Zones, JoinType.INNER, DiningTables.zoneId, Zones.id)
                .selectAll()
                .where { (DiningTables.id eq tableId) and DiningTables.deletedAt.isNull() }
                .firstOrNull()
                ?.let { slipLines(it, config) }
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
        val slips = transaction {
            DiningTables.join(Zones, JoinType.INNER, DiningTables.zoneId, Zones.id)
                .selectAll()
                .where { DiningTables.deletedAt.isNull() }
                .orderBy(Zones.sortOrder to SortOrder.ASC, DiningTables.sortOrder to SortOrder.ASC)
                .map { slipLines(it, config) }
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
}

/**
 * The one place a table's "scan to order" slip is assembled: logo, table label,
 * zone, and the QR whose payload is built SERVER-SIDE from [menuPathFor] so the
 * printed code always matches the on-screen one. Shared by the single-table and
 * print-all endpoints. [row] must be a DiningTables⨝Zones join row.
 */
private fun slipLines(row: ResultRow, config: CustomerConfig): List<PrintLine> {
    val label = row[DiningTables.nameOverride] ?: row[DiningTables.label]
    val url = config.publicBaseUrl +
        menuPathFor(row[DiningTables.zoneId], row[DiningTables.label], row[DiningTables.id])
    return listOf(
        PrintLine.LogoPlaceholder(config.displayName),
        PrintLine.Blank,
        PrintLine.Header(label),
        PrintLine.Text("${row[Zones.nameFr]} / ${row[Zones.nameEn]}", Align.CENTER),
        PrintLine.Blank,
        PrintLine.QrCode(url),
        PrintLine.Text("Scannez pour commander de la nourriture", Align.CENTER),
        PrintLine.Text("Scan to order", Align.CENTER),
        PrintLine.Blank,
    )
}
