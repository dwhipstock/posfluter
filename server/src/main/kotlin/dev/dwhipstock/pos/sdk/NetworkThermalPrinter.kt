package dev.dwhipstock.pos.sdk

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.Socket
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Where the network printer lives. Empty [ip] means "not configured". */
data class PrinterTarget(val ip: String, val port: Int) {
    val configured: Boolean get() = ip.isNotBlank()
}

/** Snapshot of the printer's health, surfaced to the owner (settings screen / test print). */
@Serializable
data class PrinterStatus(
    val configured: Boolean,
    val online: Boolean,
    val lastError: String? = null,
    val lastOkAt: String? = null,
    /** `paper` | `digital` — whether sale receipts/bills reach the thermal printer. */
    val receiptMode: String = ReceiptPrintMode.PAPER.wire,
)

/** Sends a raw ESC/POS byte stream to a network thermal printer. */
interface EscPosTransport {
    /** Throws on any failure (connect timeout, refused, I/O). */
    fun send(target: PrinterTarget, bytes: ByteArray)
}

/**
 * Raw TCP (JetDirect/RAW, port 9100). No CUPS, no OS driver — open a socket,
 * write the bytes, close. A short connect timeout is the guard that keeps an
 * offline printer from stalling the print worker.
 */
class TcpEscPosTransport(
    private val connectTimeoutMs: Int = 3000,
    private val ioTimeoutMs: Int = 5000,
) : EscPosTransport {
    override fun send(target: PrinterTarget, bytes: ByteArray) {
        Socket().use { s ->
            s.connect(InetSocketAddress(target.ip, target.port), connectTimeoutMs)
            s.soTimeout = ioTimeoutMs
            s.getOutputStream().apply { write(bytes); flush() }
        }
    }
}

/**
 * Real ESC/POS network printer. Delegates the audit/outbox side (spool file +
 * `receipt.printed` event) to a wrapped [PrinterAdapter.VirtualPrinter] so all
 * existing reporting/sync semantics are unchanged, then ALSO pushes the receipt
 * as a French-capable raster bitmap to the physical printer.
 *
 * HARD RULE: a print failure must never block or roll back a sale. The domain
 * calls [print]/[printProvisional] inside the sale's DB transaction, so the
 * network I/O is handed to a background worker and the call returns immediately
 * with the rendered text. An offline printer is logged and reflected in
 * [status]; it never throws into the transaction.
 */
class NetworkThermalPrinter(
    private val audit: PrinterAdapter.VirtualPrinter,
    /** Read live each send so a settings change (new DHCP IP) applies at once. */
    private val target: () -> PrinterTarget,
    private val transport: EscPosTransport = TcpEscPosTransport(),
    /** [ReceiptPrintMode.DIGITAL]: receipts/bills are audit-spooled only; [printNow] is unaffected. */
    val receiptMode: ReceiptPrintMode = ReceiptPrintMode.PAPER,
) : PrinterAdapter {

    private val log = LoggerFactory.getLogger(NetworkThermalPrinter::class.java)

    // Single worker; bounded queue so a wedged printer can't grow memory without
    // bound. Overflow drops the job (logged) rather than blocking the caller.
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(64),
        { r -> Thread(r, "thermal-printer").apply { isDaemon = true } },
    )

    @Volatile private var lastError: String? = null
    @Volatile private var lastOkAt: java.time.Instant? = null

    override fun print(job: PrintJob): String {
        val text = audit.print(job)      // spool file + receipt.printed outbox (in-txn, local, fast)
        enqueueReceipt(job.lines)        // physical print, off the sale's thread
        return text
    }

    override fun printProvisional(job: PrintJob): String {
        val text = audit.printProvisional(job)
        enqueueReceipt(job.lines)
        return text
    }

    private fun enqueueReceipt(lines: List<PrintLine>) {
        if (receiptMode == ReceiptPrintMode.DIGITAL) return // audit spool only, no paper
        val t = target()
        if (!t.configured) return // no printer set up yet — silently skip, no error
        try {
            worker.execute { sendNow(t, ThermalReceiptRenderer.toEscPos(lines)) }
        } catch (e: Exception) {
            // queue full (RejectedExecutionException) — never propagate to the sale
            log.warn("thermal print dropped (queue full): ${e.message}")
        }
    }

    private fun sendNow(t: PrinterTarget, bytes: ByteArray) {
        try {
            transport.send(t, bytes)
            lastOkAt = VenueClock.now()
            lastError = null
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            log.warn("thermal print failed → ${t.ip}:${t.port}: ${e.message}")
        }
    }

    /**
     * Synchronous ad-hoc print (test page, table QR slip) — a manual staff
     * action that wants immediate pass/fail feedback, unlike the fire-and-forget
     * sale receipts. Still never throws; failure is reported via [PrinterStatus].
     */
    fun printNow(lines: List<PrintLine>): PrinterStatus {
        val t = target()
        if (!t.configured) return PrinterStatus(configured = false, online = false, lastError = "printer_not_configured")
        return try {
            transport.send(t, ThermalReceiptRenderer.toEscPos(lines))
            lastOkAt = VenueClock.now()
            lastError = null
            status()
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            log.warn("ad-hoc print failed → ${t.ip}:${t.port}: ${e.message}")
            status()
        }
    }

    /** Synchronous test page — the owner wants immediate pass/fail feedback. */
    fun testPrint(): PrinterStatus = printNow(testLines())

    fun status(): PrinterStatus {
        val t = target()
        return PrinterStatus(
            configured = t.configured,
            online = t.configured && lastError == null && lastOkAt != null,
            lastError = lastError,
            lastOkAt = lastOkAt?.let(VenueClock::iso),
            receiptMode = receiptMode.wire,
        )
    }

    private fun testLines(): List<PrintLine> {
        val t = target()
        return listOf(
            PrintLine.Header("COPPERLANTERN POS"),
            PrintLine.Blank,
            PrintLine.Text("Test imprimante / Test print", Align.CENTER),
            PrintLine.Divider,
            PrintLine.KeyValue("langue française", "OK"),
            PrintLine.KeyValue("English", "OK"),
            PrintLine.KeyValue("Ventes", "$1,234.50", emphasized = true),
            PrintLine.Divider,
            PrintLine.Text("${t.ip}:${t.port}", Align.CENTER),
            PrintLine.Blank,
        )
    }
}
