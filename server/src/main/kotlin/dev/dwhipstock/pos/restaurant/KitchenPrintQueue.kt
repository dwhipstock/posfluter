package dev.dwhipstock.pos.restaurant

import dev.dwhipstock.pos.sdk.EscPosTransport
import dev.dwhipstock.pos.sdk.PrintLine
import dev.dwhipstock.pos.sdk.PrinterTarget
import dev.dwhipstock.pos.sdk.ThermalLayout
import dev.dwhipstock.pos.sdk.ThermalReceiptRenderer
import dev.dwhipstock.pos.sdk.VenueClock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The printer answered its status query with "paper end". */
class PaperOutException : RuntimeException("paper_out")

/**
 * Raw TCP (port 9100) for station printers, with a paper check first: ESC/POS
 * real-time status `DLE EOT 4` (paper roll sensor). Bits 5–6 set = paper end,
 * so the job is refused and waits instead of vanishing into an empty printer.
 * A printer that doesn't answer the query within [statusTimeoutMs] is printed
 * to anyway (not every model supports it).
 */
class KitchenTcpTransport(
    private val connectTimeoutMs: Int = 3000,
    private val ioTimeoutMs: Int = 5000,
    private val statusTimeoutMs: Int = 600,
) : EscPosTransport {
    override fun send(target: PrinterTarget, bytes: ByteArray) {
        Socket().use { s ->
            s.connect(InetSocketAddress(target.ip, target.port), connectTimeoutMs)
            val out = s.getOutputStream()
            out.write(byteArrayOf(0x10, 0x04, 0x04)); out.flush()
            s.soTimeout = statusTimeoutMs
            val status = readStatus(s.getInputStream())
            if (status != null && (status and 0x60) == 0x60) throw PaperOutException()
            s.soTimeout = ioTimeoutMs
            out.write(bytes)
            out.flush()
        }
    }

    private fun readStatus(input: InputStream): Int? = try {
        input.read().takeIf { it >= 0 }
    } catch (_: SocketTimeoutException) {
        null
    }
}

@Serializable
data class KitchenJobView(
    val jobId: String,
    val stationId: String,
    val stationName: String,
    val status: String,
    val attempts: Int,
    val lastError: String? = null,
    val createdAt: String,
    val checkId: Int? = null,
    val kind: String? = null,
)

@Serializable
data class KitchenStationHealth(
    val stationId: String,
    val nameFr: String,
    val nameEn: String,
    val waiting: Int,
    val lastError: String? = null,
    val lastOkAt: String? = null,
    /** Printer answered its last job (or has no job yet). */
    val online: Boolean,
)

/** GET /kitchen/status: what the tablet's banner shows. */
@Serializable
data class KitchenQueueStatus(
    val enabled: Boolean,
    val waiting: Int = 0,
    /** Some waiting job has failed at least once: a printer is offline or out of paper. */
    val failing: Boolean = false,
    val lastError: String? = null,
    val stations: List<KitchenStationHealth> = emptyList(),
    val jobs: List<KitchenJobView> = emptyList(),
)

/**
 * The persistent kitchen print queue (SQLite `kitchen_print_jobs`). Jobs are
 * written in the same transaction as the ticket they print, so nothing is
 * lost on a crash or a restart; a background worker sends them.
 *
 * - Order: per station, strictly by sequence. A station whose head job is
 *   waiting holds its later jobs back; other stations carry on.
 * - Waiting: an unreachable or out-of-paper printer backs off (2s, 4s, 8s …
 *   up to a minute) and never gives up; staff can retry now or cancel.
 * - Idempotent: each job has a job id and becomes DONE the moment its bytes
 *   are written; a DONE job is never sent again, so a retry after a partial
 *   failure (the kitchen printed, the bar didn't) only prints what's missing.
 * - Never blocks a sale: nothing here runs on a request's thread except the
 *   enqueue insert.
 */
class KitchenPrintQueue(
    private val transport: EscPosTransport,
    /** Where a station prints, read live (a settings change applies at once). */
    private val targetFor: (stationId: String) -> PrinterTarget,
    private val clock: () -> Long = System::currentTimeMillis,
    private val render: (lines: List<PrintLine>, width: Int) -> ByteArray =
        { lines, width -> ThermalReceiptRenderer.toEscPos(lines, width) },
) {
    private val log = LoggerFactory.getLogger(KitchenPrintQueue::class.java)
    private val lock = Object()
    @Volatile private var running = false
    private var thread: Thread? = null
    private val lastOk = ConcurrentHashMap<String, java.time.Instant>()

    companion object {
        val json = Json { ignoreUnknownKeys = true }
        private val linesSerializer = ListSerializer(PrintLine.serializer())
        const val MAX_BACKOFF_MS = 60_000L

        fun backoffMs(attempts: Int): Long =
            (2_000L shl (attempts - 1).coerceIn(0, 10)).coerceAtMost(MAX_BACKOFF_MS)

        fun encode(lines: List<PrintLine>): String = json.encodeToString(linesSerializer, lines)
        fun decode(text: String): List<PrintLine> = json.decodeFromString(linesSerializer, text)
    }

    /** Add a job. Call inside the ticket's transaction. Returns the job id. */
    fun enqueue(stationId: String, lines: List<PrintLine>, paperMm: Int, ticketRowId: Int?): String {
        val id = "kj-" + UUID.randomUUID().toString()
        KitchenPrintJobs.insert {
            it[jobId] = id
            it[ticketId] = ticketRowId
            it[KitchenPrintJobs.stationId] = stationId
            it[linesJson] = encode(lines)
            it[KitchenPrintJobs.paperMm] = paperMm
            it[status] = "PENDING"
            it[createdAt] = VenueClock.now()
        }
        return id
    }

    /** Wake the worker (after a commit that added jobs). */
    fun wake() = synchronized(lock) { lock.notifyAll() }

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            while (running) {
                try { processDue() } catch (e: Exception) { log.warn("kitchen queue pass failed: ${e.message}") }
                synchronized(lock) { if (running) lock.wait(1000) }
            }
        }, "kitchen-printer").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        wake()
    }

    private data class Job(val seq: Int, val jobId: String, val stationId: String, val lines: String,
                           val paperMm: Int, val attempts: Int, val nextAttemptMs: Long)

    private val passLock = Any()

    /**
     * One pass: for each station, send its waiting jobs in order until one
     * fails or none are due. Returns how many printed. Used by the worker and
     * directly by tests.
     */
    fun processDue(): Int = synchronized(passLock) {
        val pending = transaction {
            KitchenPrintJobs.selectAll().where { KitchenPrintJobs.status eq "PENDING" }
                .orderBy(KitchenPrintJobs.id to SortOrder.ASC)
                .map {
                    Job(it[KitchenPrintJobs.id].value, it[KitchenPrintJobs.jobId], it[KitchenPrintJobs.stationId],
                        it[KitchenPrintJobs.linesJson], it[KitchenPrintJobs.paperMm], it[KitchenPrintJobs.attempts],
                        it[KitchenPrintJobs.nextAttemptMs])
                }
        }
        var printed = 0
        for ((station, jobs) in pending.groupBy { it.stationId }) {
            for (job in jobs) {
                // the station's head is waiting out its backoff: hold the rest (order)
                if (job.nextAttemptMs > clock()) break
                if (!sendOne(station, job)) break
                printed++
            }
        }
        printed
    }

    private fun sendOne(station: String, job: Job): Boolean {
        val target = targetFor(station)
        val error: String? = if (!target.configured) "printer_not_configured" else try {
            val bytes = render(decode(job.lines), ThermalLayout.widthFor(job.paperMm))
            transport.send(target, bytes)
            null
        } catch (e: PaperOutException) {
            "paper_out"
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}".take(290)
        }
        return transaction {
            // only a still-PENDING job changes: a cancel that raced the send wins
            if (error == null) {
                KitchenPrintJobs.update({ (KitchenPrintJobs.jobId eq job.jobId) and (KitchenPrintJobs.status eq "PENDING") }) {
                    it[status] = "DONE"
                    it[printedAt] = VenueClock.now()
                    it[attempts] = job.attempts + 1
                    it[lastError] = null
                }
                lastOk[station] = VenueClock.now()
                true
            } else {
                val n = job.attempts + 1
                KitchenPrintJobs.update({ (KitchenPrintJobs.jobId eq job.jobId) and (KitchenPrintJobs.status eq "PENDING") }) {
                    it[attempts] = n
                    it[nextAttemptMs] = clock() + backoffMs(n)
                    it[lastError] = error
                }
                if (n == 1 || n % 10 == 0) log.warn("kitchen ticket waiting (station $station, try $n): $error")
                false
            }
        }
    }

    /** Try every waiting job again now (the "Retry" button). */
    fun retryNow(): Int {
        val n = transaction {
            KitchenPrintJobs.update({ KitchenPrintJobs.status eq "PENDING" }) { it[nextAttemptMs] = 0 }
        }
        wake()
        return n
    }

    /** Cancel one waiting job, or every waiting job when [jobId] is null. */
    fun cancel(jobId: String?): Int = transaction {
        KitchenPrintJobs.update({
            (KitchenPrintJobs.status eq "PENDING") and
                (if (jobId == null) KitchenPrintJobs.status eq "PENDING" else KitchenPrintJobs.jobId eq jobId)
        }) { it[status] = "CANCELLED" }
    }

    fun lastOkAt(stationId: String): java.time.Instant? = lastOk[stationId]
}
