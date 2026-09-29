package dev.dwhipstock.pos.sdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.format.DateTimeFormatter

/**
 * The whole receipt layout is data. A renderer emits an ordered list of
 * PrintLines; adapters (virtual text file now, ESC/POS thermal in M2) consume
 * the same list — only the adapter changes per device.
 */
@Serializable
sealed interface PrintLine {
    /**
     * Emphasized centered text (shop name, the CUSTOMER BILL / NOT A RECEIPT banners). Thermal: large bold.
     * [exact]: never word-wrap (a Wi-Fi password) — it shrinks, then breaks between characters.
     */
    @Serializable
    data class Header(val text: String, val exact: Boolean = false) : PrintLine

    @Serializable
    data class Text(val text: String, val align: Align = Align.LEFT) : PrintLine

    /** Left label + right-aligned value on one line (item, total, tender rows). */
    @Serializable
    data class KeyValue(val left: String, val right: String, val emphasized: Boolean = false) : PrintLine

    @Serializable
    data object Divider : PrintLine

    @Serializable
    data object Blank : PrintLine

    /** Logo slot — the virtual printer prints the fallback text; thermal prints the bitmap (M2). */
    @Serializable
    data class LogoPlaceholder(val fallbackText: String) : PrintLine

    /**
     * QR slot for table "scan to order" slips. Thermal prints the QR bitmap
     * centered; the virtual/text printer falls back to the raw [data] URL plus
     * the optional [caption] so a spooled slip is still scannable-by-typing.
     */
    @Serializable
    data class QrCode(val data: String, val caption: String? = null) : PrintLine

    /** Kitchen tickets: a big bold line (an item and its quantity). Thermal: [ThermalLayout.Style.LARGE]. */
    @Serializable
    data class Large(val text: String, val align: Align = Align.LEFT) : PrintLine

    /** White on a black bar across the paper (a kitchen station name, VOID). */
    @Serializable
    data class Banner(val text: String) : PrintLine
}

enum class Align { LEFT, CENTER, RIGHT }

data class PrintJob(
    val checkId: Int,
    val lines: List<PrintLine>,
    /** Extra audit context stamped into the receipt.printed outbox event (e.g. shiftId). */
    val meta: Map<String, String> = emptyMap(),
)

/**
 * Device abstraction: UI and domain never talk to hardware directly.
 * Sealed for now — physical ESC/POS adapter joins in M2 hardware bring-up.
 */
sealed interface PrinterAdapter {
    /** Final receipt (proof of payment). Returns the rendered text (client preview / audit). */
    fun print(job: PrintJob): String

    /**
     * Provisional customer bill ("check please"): spooled separately from receipts
     * and — unlike [print] — it emits NO receipt.printed event. A bill isn't proof
     * of payment, and the caller owns its domain event (check.bill_printed) because
     * only it knows the line count and total. Returns the rendered text for preview.
     */
    fun printProvisional(job: PrintJob): String

    /**
     * Dev/M1 printer: renders 42-column text (80mm thermal width) into a spool dir
     * as <check-id>-<timestamp>.txt. Final receipts land in [receiptsDir] and record
     * a receipt.printed outbox event; provisional bills land in [billsDir] with no
     * event. Must be called inside the same DB transaction as the mutation.
     */
    data class VirtualPrinter(val receiptsDir: String, val billsDir: String = "bills") : PrinterAdapter {
        override fun print(job: PrintJob): String {
            val file = spool(receiptsDir, job)
            Outbox.write("receipt.printed", "check", job.checkId.toString(), buildJsonObject {
                put("checkId", job.checkId)
                put("printer", "virtual")
                put("file", file.path)
                job.meta.forEach { (k, v) -> put(k, v) }
            })
            return file.readText()
        }

        override fun printProvisional(job: PrintJob): String = spool(billsDir, job).readText()

        private fun spool(dir: String, job: PrintJob): File {
            val text = renderText(job.lines)
            val d = File(dir).apply { mkdirs() }
            val stamp = VenueClock.local(VenueClock.now()).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val file = File(d, "${job.checkId}-$stamp.txt")
            file.writeText(text)
            return file
        }
    }

    companion object {
        const val WIDTH = 42

        /**
         * The widest a text line may run (48 columns of an 80mm printer's
         * Font A): a row past it wraps at [WIDTH]; one that only passes
         * [WIDTH] prints as it always did.
         */
        const val PAPER = 48

        /**
         * Shared 42-col monospace rendering. NOTE: pads by codepoint count —
         * any decomposed (combining) accent would throw column math off slightly;
         * acceptable for the virtual printer. TODO(M2): proper width via ICU when thermal lands.
         */
        fun renderText(lines: List<PrintLine>): String = buildString {
            for (line in lines) {
                when (line) {
                    is PrintLine.Header -> appendLine(center(line.text))
                    is PrintLine.LogoPlaceholder -> {
                        appendLine(center("*".repeat(10)))
                        appendLine(center(line.fallbackText))
                        appendLine(center("*".repeat(10)))
                    }
                    // a line wider than the paper (a long address) wraps at spaces
                    is PrintLine.Text -> (if (line.text.length <= PAPER) listOf(line.text) else wrapWords(line.text, WIDTH)).forEach { row ->
                        appendLine(
                            when (line.align) {
                                Align.LEFT -> row
                                Align.CENTER -> center(row)
                                Align.RIGHT -> row.padStart(WIDTH)
                            },
                        )
                    }
                    is PrintLine.KeyValue -> {
                        // a label too long for the paper (a German item name) wraps at word
                        // breaks; the amount stays right-aligned on its last row
                        val rows = if (line.left.length + 1 + line.right.length <= PAPER) listOf(line.left)
                            else wrapWords(line.left, WIDTH - line.right.length - 1)
                        rows.dropLast(1).forEach { appendLine(it) }
                        val last = rows.last()
                        val pad = (WIDTH - last.length - line.right.length).coerceAtLeast(1)
                        appendLine(last + " ".repeat(pad) + line.right)
                    }
                    is PrintLine.QrCode -> {
                        line.caption?.let { appendLine(center(it)) }
                        appendLine(center(line.data))
                    }
                    is PrintLine.Large -> appendLine(
                        when (line.align) {
                            Align.LEFT -> line.text
                            Align.CENTER -> center(line.text)
                            Align.RIGHT -> line.text.padStart(WIDTH)
                        },
                    )
                    is PrintLine.Banner -> {
                        appendLine("#".repeat(WIDTH))
                        appendLine(center(line.text))
                        appendLine("#".repeat(WIDTH))
                    }
                    PrintLine.Divider -> appendLine("-".repeat(WIDTH))
                    PrintLine.Blank -> appendLine()
                }
            }
        }

        /** [text] cut at spaces into rows of at most [width] (a word longer than that is split). */
        internal fun wrapWords(text: String, width: Int): List<String> {
            val w = width.coerceAtLeast(8)
            if (text.length <= w) return listOf(text)
            val rows = mutableListOf<String>()
            var row = ""
            for (word in text.split(' ')) {
                var rest = word
                while (rest.length > w) {
                    if (row.isNotEmpty()) { rows += row; row = "" }
                    rows += rest.take(w); rest = rest.drop(w)
                }
                row = when {
                    row.isEmpty() -> rest
                    row.length + 1 + rest.length <= w -> "$row $rest"
                    else -> { rows += row; rest }
                }
            }
            rows += row
            return rows
        }

        private fun center(text: String): String {
            val pad = ((WIDTH - text.length) / 2).coerceAtLeast(0)
            return " ".repeat(pad) + text
        }
    }
}
