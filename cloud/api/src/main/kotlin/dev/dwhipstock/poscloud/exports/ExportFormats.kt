package dev.dwhipstock.poscloud.exports

import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.Worksheet
import java.io.OutputStream
import java.io.Writer
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The cells of an export row, typed so each format writes them right: money
 * as a plain decimal (cents / 100, two places, no symbol — the row's currency
 * is its own column), timestamps as venue-local wall time (the zone is its own
 * column), text formula-neutralized.
 */
sealed interface Cell {
    data class Text(val value: String?) : Cell
    data class Money(val cents: Long?) : Cell
    data class Count(val value: Long?) : Cell
    data class Time(val value: LocalDateTime?) : Cell
    data class Day(val value: LocalDate?) : Cell
    data class Flag(val value: Boolean?) : Cell
}

fun text(v: String?): Cell = Cell.Text(v)
fun money(cents: Long?): Cell = Cell.Money(cents)
fun count(v: Long?): Cell = Cell.Count(v)
fun count(v: Int?): Cell = Cell.Count(v?.toLong())
fun time(v: LocalDateTime?): Cell = Cell.Time(v)
fun day(v: LocalDate?): Cell = Cell.Day(v)
fun flag(v: Boolean?): Cell = Cell.Flag(v)

/** Cents → "12.34" / "-0.05": the decimal the export writes (no float on the way). */
fun decimal(cents: Long): BigDecimal = BigDecimal.valueOf(cents, 2)

/**
 * Spreadsheet formula injection (OWASP "CSV injection"): text that starts with
 * = + - @ tab or CR runs as a formula in Excel / Sheets / Numbers — an item or
 * staff name like `=HYPERLINK(...)`. Such text gets a leading apostrophe so it
 * opens as text. A plain number ("-5.00") is left alone so it still sums.
 * Same rule as the portal's client-side exports (cloud/web/lib/export/csv.ts).
 */
object FormulaGuard {
    private val FORMULA_START = Regex("^[=+\\-@\t\r]")
    private val PLAIN_NUMBER = Regex("^-?\\d+(\\.\\d+)?$")

    fun neutralize(v: String): String =
        if (FORMULA_START.containsMatchIn(v) && !PLAIN_NUMBER.matches(v)) "'$v" else v
}

/** Where an export's rows go: one CSV file, or one XLSX sheet. */
interface RowSink {
    fun header(columns: List<String>)
    fun row(cells: List<Cell>)
}

private val WALL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * RFC 4180 CSV: comma-separated, CRLF line ends, fields with a comma, quote or
 * line break quoted. The caller writes the UTF-8 BOM ([CsvSink.BOM]) so Excel
 * reads accents right.
 */
class CsvSink(private val out: Writer) : RowSink {
    override fun header(columns: List<String>) = line(columns.map { FormulaGuard.neutralize(it) })

    override fun row(cells: List<Cell>) = line(cells.map(::render))

    private fun render(c: Cell): String = when (c) {
        is Cell.Text -> c.value?.let(FormulaGuard::neutralize).orEmpty()
        is Cell.Money -> c.cents?.let { decimal(it).toPlainString() }.orEmpty()
        is Cell.Count -> c.value?.toString().orEmpty()
        is Cell.Time -> c.value?.format(WALL).orEmpty()
        is Cell.Day -> c.value?.toString().orEmpty()
        is Cell.Flag -> c.value?.let { if (it) "true" else "false" }.orEmpty()
    }

    private fun line(fields: List<String>) {
        fields.forEachIndexed { i, f ->
            if (i > 0) out.write(",")
            out.write(if (f.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) "\"" + f.replace("\"", "\"\"") + "\"" else f)
        }
        out.write("\r\n")
    }

    companion object {
        const val BOM = "﻿"
    }
}

/**
 * One XLSX sheet, streamed: rows are flushed to the output every
 * [FLUSH_EVERY] rows, and text is written as inline strings (no shared-strings
 * table held in memory). Every cell is a typed value — a string, a number, a
 * date — never a formula.
 */
class XlsxSink(private val sheet: Worksheet) : RowSink {
    private var r = 0

    override fun header(columns: List<String>) {
        columns.forEachIndexed { c, h ->
            sheet.inlineString(r, c, FormulaGuard.neutralize(h))
            sheet.style(r, c).bold().set()
        }
        sheet.freezePane(0, 1)
        r++
    }

    override fun row(cells: List<Cell>) {
        cells.forEachIndexed { c, cell ->
            when (cell) {
                is Cell.Text -> cell.value?.let { sheet.inlineString(r, c, FormulaGuard.neutralize(it)) }
                is Cell.Money -> cell.cents?.let {
                    sheet.value(r, c, decimal(it))
                    sheet.style(r, c).format("0.00").set()
                }
                is Cell.Count -> cell.value?.let { sheet.value(r, c, it) }
                is Cell.Time -> cell.value?.let {
                    sheet.value(r, c, it)
                    sheet.style(r, c).format("yyyy-mm-dd hh:mm:ss").set()
                }
                is Cell.Day -> cell.value?.let {
                    sheet.value(r, c, it)
                    sheet.style(r, c).format("yyyy-mm-dd").set()
                }
                is Cell.Flag -> cell.value?.let { sheet.value(r, c, it) }
            }
        }
        r++
        if (r % FLUSH_EVERY == 0) sheet.flush()
    }

    companion object {
        private const val FLUSH_EVERY = 500

        /** Write one sheet named [name] to [out] with [fill], then finish the workbook. */
        fun write(out: OutputStream, name: String, fill: (RowSink) -> Unit) {
            val wb = Workbook(out, "POS portal", "1.0")
            val ws = wb.newWorksheet(name.take(31))
            fill(XlsxSink(ws))
            ws.finish()
            wb.finish()
        }
    }
}
