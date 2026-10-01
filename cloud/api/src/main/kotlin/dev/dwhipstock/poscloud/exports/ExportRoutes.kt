package dev.dwhipstock.poscloud.exports

import dev.dwhipstock.poscloud.BadRequestException
import dev.dwhipstock.poscloud.CloudTime
import dev.dwhipstock.poscloud.ForbiddenException
import dev.dwhipstock.poscloud.Fx
import dev.dwhipstock.poscloud.NotFoundException
import dev.dwhipstock.poscloud.PayloadTooLargeException
import dev.dwhipstock.poscloud.RateLimitException
import dev.dwhipstock.poscloud.auth.Principal
import dev.dwhipstock.poscloud.auth.ROLE_MANAGER
import dev.dwhipstock.poscloud.auth.ROLE_OWNER
import dev.dwhipstock.poscloud.auth.requirePortal
import dev.dwhipstock.poscloud.db.ExportLog
import dev.dwhipstock.poscloud.portalScopes
import dev.dwhipstock.poscloud.reportingCurrencyOf
import dev.dwhipstock.poscloud.reports.ReportCtx
import dev.dwhipstock.poscloud.reports.VenueRange
import dev.dwhipstock.poscloud.reports.resolveRange
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.LocalDate
import java.util.concurrent.Semaphore
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Data exports for the manager portal (API.md "Exports"):
 *
 *  - `GET /v1/exports/{dataset}.csv|.xlsx?from&to&venue` — one dataset
 *    ([DATASETS]); owners and managers only (viewers 403 `export_forbidden`).
 *  - `GET /v1/exports/all.zip?from&to&venue` — the owner's "all my data":
 *    every dataset as CSV plus a README.txt; owner only (403 `owner_only`),
 *    [ZIP_PER_HOUR] per user per hour (429 + Retry-After). Without from/to it
 *    covers every date.
 *
 * Scoped like the reports: the caller's tenant, one store (`venue=`) or all of
 * them, each over its own business days. Every export is recorded in
 * export_log (who, what, which stores, which dates) and logged by user id.
 * Before a byte is sent the rows are counted: a file past [maxExportRows]
 * (the zip: [maxZipRows] per file) is refused with 413 `export_too_large`.
 */

private val log = LoggerFactory.getLogger("exports")

/** One XLSX sheet holds 1,048,576 rows; one CSV / sheet export stops well short of it. */
@Volatile internal var maxExportRows = 1_000_000L

/** The whole-data zip streams CSV only, so it may run longer per file. */
@Volatile internal var maxZipRows = 5_000_000L

const val ZIP_PER_HOUR = 3

/** The owner's whole-data zip: [ZIP_PER_HOUR] per user per rolling hour. */
internal val zipLimiter = WindowLimiter(ZIP_PER_HOUR, 60 * 60_000L, "too many full-data downloads; try again later")

/**
 * Streams running at once, per API process. Each holds one database
 * connection (of 10) for as long as the download takes, so a few slow
 * downloads must never take the pool from the reports; past this, 429 with a
 * short Retry-After.
 */
private const val MAX_STREAMS = 3
private val streams = Semaphore(MAX_STREAMS)

/** Run [body] holding a stream slot, or refuse with 429 when all are busy. */
private suspend fun streaming(body: suspend () -> Unit) {
    if (!streams.tryAcquire()) throw RateLimitException("other exports are running; try again shortly", 10)
    try { body() } finally { streams.release() }
}

private val CSV = ContentType.parse("text/csv; charset=utf-8")
private val XLSX = ContentType.parse("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")

/** The first day "all dates" covers (the zip without from/to). */
private val ALL_DATES_FROM: LocalDate = LocalDate.of(2000, 1, 1)

fun Principal.requireExporter() {
    if (role != ROLE_OWNER && role != ROLE_MANAGER)
        throw ForbiddenException("only owners and managers can export data", "export_forbidden")
}

private class ExportRequest(val principal: Principal, val ctx: ReportCtx, val allDates: Boolean)

/** Session → role check → the request's stores and dates (call outside a transaction). */
private fun exportRequest(call: ApplicationCall, fx: Fx.Rates, defaultAllDates: Boolean, check: (Principal) -> Unit): ExportRequest {
    check(requirePortal(call))
    val (principal, venues) = portalScopes(call)
    val q = call.request.queryParameters
    val allDates = defaultAllDates && q["from"].isNullOrBlank() && q["to"].isNullOrBlank()
    val reporting = transaction { reportingCurrencyOf(principal.tenantId) }
    val ranges = venues.map { v ->
        if (allDates) VenueRange(v, ALL_DATES_FROM, LocalDate.now(v.zone).plusDays(1))
        else resolveRange(call, v.zone).let { (from, to) -> VenueRange(v, from, to) }
    }
    return ExportRequest(principal, ReportCtx(principal.tenantId, ranges, reporting, fx), allDates)
}

private fun audit(req: ExportRequest, dataset: String, format: String, dated: Boolean) {
    val first = req.ctx.venues.first()
    val stores = req.ctx.venues.joinToString(",") { it.id }
    transaction {
        ExportLog.insert {
            it[tenantId] = req.principal.tenantId
            it[userId] = req.principal.userId
            it[ExportLog.dataset] = dataset
            it[ExportLog.format] = format
            it[venueIds] = stores
            it[fromDate] = if (dated) first.from else null
            it[toDate] = if (dated) first.to else null
            it[createdAt] = CloudTime.now()
        }
    }
    log.info("export tenant=${req.principal.tenantId} user=${req.principal.userId} dataset=$dataset format=$format " +
        "stores=$stores range=${if (dated) "${first.from}..${first.to}" else "current"}")
}

private fun requireRows(ctx: ReportCtx, dataset: Dataset, cap: Long) {
    val n = transaction { dataset.count(ctx) }
    if (n > cap) throw PayloadTooLargeException(
        "${dataset.id}: $n rows in this range, more than $cap; pick fewer days or one store", "export_too_large")
}

/** `sales_plateau_2026-07-01_2026-07-31.csv`: dataset, the store when one, the dates when dated. */
private fun fileName(req: ExportRequest, base: String, dated: Boolean, ext: String): String {
    val v = req.ctx.venues
    val parts = mutableListOf(base)
    if (v.size == 1) parts += v.first().id.replace(Regex("[^A-Za-z0-9_-]"), "")
    if (dated && !req.allDates) parts += listOf(v.first().from.toString(), v.first().to.toString())
    if (!dated || req.allDates) parts += CloudTime.localDate(CloudTime.now(), v.first().zone).toString()
    return parts.filter { it.isNotEmpty() }.joinToString("_") + ".$ext"
}

private fun ApplicationCall.attachment(name: String) {
    response.header(HttpHeaders.ContentDisposition,
        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, name).toString())
    response.header(HttpHeaders.CacheControl, "no-store")
}

private fun writeCsv(out: OutputStream, ctx: ReportCtx, dataset: Dataset) {
    val w = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), 64 * 1024)
    w.write(CsvSink.BOM)
    transaction { dataset.write(ctx, CsvSink(w)) }
    w.flush()
}

fun Route.exportRoutes(fx: Fx.Rates = Fx.Rates.NONE) {

    /** The owner's whole-data download: every dataset (CSV) + README.txt in one zip. */
    get("/exports/all.zip") {
        val req = exportRequest(call, fx, defaultAllDates = true) { p ->
            if (!p.isOwner) throw ForbiddenException("only the owner can download all the data", "owner_only")
        }
        DATASETS.forEach { requireRows(req.ctx, it, maxZipRows) }
        streaming {
            zipLimiter.record("${req.principal.tenantId}|${req.principal.userId}")
            audit(req, "all", "zip", dated = true)
            call.attachment(fileName(req, "all-data", dated = true, ext = "zip"))
            call.respondOutputStream(ContentType.Application.Zip) {
                withContext(Dispatchers.IO) {
                    val zip = ZipOutputStream(this@respondOutputStream)
                    zip.putNextEntry(ZipEntry("README.txt"))
                    zip.write(readme(req.ctx, req.allDates).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    DATASETS.forEach { d ->
                        zip.putNextEntry(ZipEntry("${d.id}.csv"))
                        writeCsv(zip, req.ctx, d)
                        zip.closeEntry()
                    }
                    zip.finish()
                    zip.flush()
                }
            }
        }
    }

    get("/exports/{file}") {
        val file = call.parameters["file"].orEmpty()
        val ext = file.substringAfterLast('.', "")
        val dataset = datasetOf(file.substringBeforeLast('.'))
            ?: throw NotFoundException("no export '$file'", "unknown_export")
        if (ext != "csv" && ext != "xlsx") throw BadRequestException("format must be csv or xlsx", "bad_format")
        val req = exportRequest(call, fx, defaultAllDates = false) { it.requireExporter() }
        requireRows(req.ctx, dataset, maxExportRows)
        streaming {
            audit(req, dataset.id, ext, dataset.dated)
            call.attachment(fileName(req, dataset.id, dataset.dated, ext))
            call.respondOutputStream(if (ext == "csv") CSV else XLSX) {
                withContext(Dispatchers.IO) {
                    if (ext == "csv") writeCsv(this@respondOutputStream, req.ctx, dataset)
                    else transaction { XlsxSink.write(this@respondOutputStream, dataset.id) { dataset.write(req.ctx, it) } }
                }
            }
        }
    }
}

/** README.txt of the whole-data zip: what each file is and every column. */
internal fun readme(ctx: ReportCtx, allDates: Boolean): String = buildString {
    val nl = "\r\n"
    val first = ctx.venues.first()
    append("Data export").append(nl).append(nl)
    append("Generated: ").append(CloudTime.iso(CloudTime.now(), first.zone)).append(nl)
    append("Stores: ").append(ctx.venues.joinToString(", ") { "${it.venue.name} (${it.id}, ${it.zone.id}, ${it.currency})" }).append(nl)
    append("Dates: ").append(if (allDates) "all dates up to today" else "${first.from} to ${first.to}")
        .append(" (each store's own business days); menu-items and staff are as they are now").append(nl).append(nl)
    append("About these files").append(nl)
    append("- UTF-8 CSV with a byte-order mark, comma-separated, one header row; Excel, Numbers and Google Sheets open them.").append(nl)
    append("- Money is a plain decimal (12.34, -0.05) in the currency named in the row's currency column; no symbols, no thousands separators.").append(nl)
    append("- Times are wall-clock times at the store (yyyy-MM-dd HH:mm:ss), in the time zone named in the row's timezone column.").append(nl)
    append("- Text that would start with = + - @ or a tab is written with a leading apostrophe (') so a spreadsheet never runs it as a formula.").append(nl)
    append("- Ids (store_id + check_id, shift_id, item_id) join the files together; check and shift numbers repeat across stores.").append(nl)
    append("- Every figure is what the stores recorded; nothing here is estimated. No passwords, PINs or other credentials are included.").append(nl)
    DATASETS.forEach { d ->
        append(nl).append(d.id).append(".csv").append(nl)
        append(d.about).append(nl)
        d.columns.forEach { c -> append("  ").append(c.name).append(": ").append(c.about).append(nl) }
    }
}
