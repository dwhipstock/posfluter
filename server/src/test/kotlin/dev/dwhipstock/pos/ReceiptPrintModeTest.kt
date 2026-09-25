package dev.dwhipstock.pos

import dev.dwhipstock.pos.db.initDatabase
import dev.dwhipstock.pos.sdk.EscPosTransport
import dev.dwhipstock.pos.sdk.NetworkThermalPrinter
import dev.dwhipstock.pos.sdk.PrintJob
import dev.dwhipstock.pos.sdk.PrintLine
import dev.dwhipstock.pos.sdk.PrinterAdapter
import dev.dwhipstock.pos.sdk.PrinterTarget
import dev.dwhipstock.pos.sdk.ReceiptPrintMode
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `print.receipts=paper|digital`: digital keeps the audit spool but skips the
 * thermal send for receipts and bills; manual prints (test page, table slips)
 * always reach the printer.
 */
class ReceiptPrintModeTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun tempDir(): File = Files.createTempDirectory("pos-test").toFile()
    private fun props(text: String): File = File(tempDir(), "store.properties").apply { writeText(text) }

    // --- parsing ---

    @Test
    fun parsesValidValues() {
        assertEquals(ReceiptPrintMode.PAPER, ReceiptPrintMode.parse("paper"))
        assertEquals(ReceiptPrintMode.DIGITAL, ReceiptPrintMode.parse(" Digital "))
        val r = ReceiptPrintMode.fromFile(props("# test\nprint.receipts=digital\n"))
        assertEquals(ReceiptPrintMode.DIGITAL, r.mode)
        assertNull(r.warning)
    }

    @Test
    fun invalidValueFallsBackToPaperWithWarning() {
        assertNull(ReceiptPrintMode.parse("email"))
        val r = ReceiptPrintMode.fromFile(props("print.receipts=email\n"))
        assertEquals(ReceiptPrintMode.PAPER, r.mode)
        assertNotNull(r.warning)
        val e = ReceiptPrintMode.fromEnv { if (it == ReceiptPrintMode.ENV) "nope" else null }
        assertEquals(ReceiptPrintMode.PAPER, e.mode)
        assertNotNull(e.warning)
    }

    @Test
    fun missingFileOrKeyIsPaper() {
        assertEquals(ReceiptPrintMode.PAPER, ReceiptPrintMode.fromFile(null).mode)
        val missing = ReceiptPrintMode.fromFile(File(tempDir(), "absent.properties"))
        assertEquals(ReceiptPrintMode.PAPER, missing.mode)
        assertNull(missing.warning)
        assertEquals(ReceiptPrintMode.PAPER, ReceiptPrintMode.fromFile(props("other=1\n")).mode)
        // a directory where the file should be is unreadable → paper, never throws
        val unreadable = ReceiptPrintMode.fromFile(tempDir())
        assertEquals(ReceiptPrintMode.PAPER, unreadable.mode)
        assertEquals(ReceiptPrintMode.PAPER, ReceiptPrintMode.fromEnv { null }.mode)
    }

    @Test
    fun envVarWinsOverConfigFile() {
        val file = props("print.receipts=digital\n").path
        val fromFile = ReceiptPrintMode.fromEnv { if (it == ReceiptPrintMode.ENV_CONFIG_FILE) file else null }
        assertEquals(ReceiptPrintMode.DIGITAL, fromFile.mode)
        val env = mapOf(ReceiptPrintMode.ENV to "paper", ReceiptPrintMode.ENV_CONFIG_FILE to file)
        assertEquals(ReceiptPrintMode.PAPER, ReceiptPrintMode.fromEnv(env::get).mode)
    }

    // --- printer gating ---

    private class CountingTransport(expected: Int = 0) : EscPosTransport {
        val sends = AtomicInteger()
        val latch = CountDownLatch(expected)
        override fun send(target: PrinterTarget, bytes: ByteArray) { sends.incrementAndGet(); latch.countDown() }
    }

    private fun printer(mode: ReceiptPrintMode, transport: EscPosTransport, dir: File) = NetworkThermalPrinter(
        audit = PrinterAdapter.VirtualPrinter(File(dir, "receipts").path, File(dir, "bills").path),
        target = { PrinterTarget("127.0.0.1", 9100) },
        transport = transport,
        receiptMode = mode,
    )

    private val lines = listOf(PrintLine.Header("TEST"), PrintLine.Text("line"))

    @Test
    fun digitalSkipsThermalForReceiptsButKeepsAuditFiles() {
        val dir = tempDir()
        initDatabase(File(dir, "pos.db").path)
        val t = CountingTransport()
        val p = printer(ReceiptPrintMode.DIGITAL, t, dir)
        transaction { p.print(PrintJob(1, lines)) }
        p.printProvisional(PrintJob(2, lines))
        Thread.sleep(300) // the worker would have sent by now
        assertEquals(0, t.sends.get())
        assertEquals(1, File(dir, "receipts").listFiles()!!.size)
        assertEquals(1, File(dir, "bills").listFiles()!!.size)
        assertEquals("digital", p.status().receiptMode)
    }

    @Test
    fun paperStillSendsReceipts() {
        val dir = tempDir()
        initDatabase(File(dir, "pos.db").path)
        val t = CountingTransport(expected = 2)
        val p = printer(ReceiptPrintMode.PAPER, t, dir)
        transaction { p.print(PrintJob(1, lines)) }
        p.printProvisional(PrintJob(2, lines))
        assertTrue(t.latch.await(5, TimeUnit.SECONDS))
        assertEquals(1, File(dir, "receipts").listFiles()!!.size)
        assertEquals("paper", p.status().receiptMode)
    }

    @Test
    fun manualPrintsStillSendInDigitalMode() {
        val t = CountingTransport()
        val p = printer(ReceiptPrintMode.DIGITAL, t, tempDir())
        assertTrue(p.testPrint().online)
        assertTrue(p.printNow(lines).online)
        assertEquals(2, t.sends.get())
    }

    // --- end to end through the app ---

    @Test
    fun digitalModeThroughTheApp() = testApplication {
        val dir = tempDir()
        application {
            module(
                dbPath = File(dir, "pos.db").path,
                receiptsDir = File(dir, "receipts").path,
                billsDir = File(dir, "bills").path,
                receiptPrintMode = ReceiptPrintMode.Resolved(ReceiptPrintMode.DIGITAL, "test"),
            )
        }
        val c = loginClient()

        // fake printer counting connections
        val server = ServerSocket(0).apply { soTimeout = 200 }
        val connections = AtomicInteger()
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val acceptor = thread {
            while (running.get()) {
                try { server.accept().use { s -> s.getInputStream().readBytes(); connections.incrementAndGet() } }
                catch (_: SocketTimeoutException) {}
                catch (_: Exception) { break }
            }
        }
        try {
            c.patch("/settings") {
                contentType(ContentType.Application.Json)
                setBody("""{"printerIp":"127.0.0.1","printerPort":${server.localPort}}""")
            }
            val status = json.parseToJsonElement(c.get("/printer/status").bodyAsText()).jsonObject
            assertEquals("digital", status["receiptMode"]!!.jsonPrimitive.content)

            // a closed check + a bill: saved, not printed
            c.post("/shifts") { contentType(ContentType.Application.Json); setBody("""{"openingFloatCents":100000,"managerPin":"1234"}""") }
            val checkId = json.parseToJsonElement(c.post("/tables/t3/checks").bodyAsText())
                .jsonObject["id"]!!.jsonPrimitive.int
            c.post("/checks/$checkId/lines") { contentType(ContentType.Application.Json); setBody("""{"itemId":"amber-ale","variantId":"amber-ale:pint","qty":1}""") }
            assertEquals(HttpStatusCode.OK, c.post("/checks/$checkId/bill").status)
            c.post("/checks/$checkId/tenders") { contentType(ContentType.Application.Json); setBody("""{"type":"CASH","amountTenderedCents":100000}""") }
            assertEquals(HttpStatusCode.OK, c.post("/checks/$checkId/finalize").status)
            Thread.sleep(500)
            assertEquals(0, connections.get(), "no receipt/bill reaches the printer in digital mode")
            assertTrue(File(dir, "receipts").listFiles()!!.isNotEmpty(), "receipt still spooled")
            assertTrue(File(dir, "bills").listFiles()!!.isNotEmpty(), "bill still spooled")

            // manual prints still go to paper
            assertEquals(HttpStatusCode.OK, c.post("/printer/test").status)
            assertEquals(HttpStatusCode.OK, c.post("/tables/t3/slip/print").status)
            val deadline = System.currentTimeMillis() + 3000
            while (connections.get() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertEquals(2, connections.get())
        } finally {
            running.set(false)
            acceptor.join(2000)
            server.close()
        }
    }

    @Test
    fun defaultIsPaper() = testApplication {
        application { module(dbPath = File(tempDir(), "pos.db").path) }
        val c = loginClient()
        val status = json.parseToJsonElement(c.get("/printer/status").bodyAsText()).jsonObject
        assertEquals("paper", status["receiptMode"]!!.jsonPrimitive.content)
    }
}
