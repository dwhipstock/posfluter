package dev.dwhipstock.pos.payments.taptopay

import dev.dwhipstock.pos.db.SyncState
import dev.dwhipstock.pos.payments.terminal.TerminalException
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Where the paired phone's token lives (only its hash). The default is sync_state: local, never synced. */
interface PhoneTokenStore {
    fun get(): String?
    fun set(value: String?)

    object SyncStateStore : PhoneTokenStore {
        private const val KEY = "terminal.taptopay.token"
        private const val KEY_NAME = "terminal.taptopay.name"
        override fun get() = transaction { SyncState.get(KEY) }?.takeIf { it.isNotBlank() }
        override fun set(value: String?) = transaction { SyncState.set(KEY, value ?: "") }
        fun name() = transaction { SyncState.get(KEY_NAME) }?.takeIf { it.isNotBlank() }
        fun setName(v: String?) = transaction { SyncState.set(KEY_NAME, v ?: "") }
    }

    class InMemory : PhoneTokenStore {
        @Volatile private var v: String? = null
        override fun get() = v
        override fun set(value: String?) { v = value }
    }
}

/** A payment waiting for (or on) the phone. The client secret goes to the paired phone only. */
@Serializable
data class PhoneJobView(
    val paymentIntentId: String,
    val clientSecret: String,
    val amountCents: Long,
    val currency: String,
    val description: String? = null,
    /** queued | collecting | processing | collected | failed | canceled */
    val state: String,
)

/** What the phone says about a payment. */
@Serializable
data class PhoneReport(
    /** collecting | processing | collected | failed | canceled */
    val status: String,
    /** The Terminal SDK's code (declinedByStripeApi, tapToPayInsecureEnvironment…) or a Stripe decline code. */
    val code: String? = null,
    val message: String? = null,
    val declined: Boolean = false,
)

/** The phone's heartbeat: how its Tap to Pay reader is doing. */
@Serializable
data class PhoneHeartbeat(
    /** starting | connecting | ready | collecting | error */
    val state: String = "ready",
    val readerName: String? = null,
    val message: String? = null,
)

@Serializable
data class PhoneLogLine(val seq: Long, val at: Long, val text: String)

/**
 * The phone card reader (`payment.terminal=tap_to_pay`), as the store sees it:
 * who is paired, whether it's alive, and the queue of payments it should take.
 *
 * Pairing is the other way round from the LAN simulator: the STORE shows a
 * 6-digit code (Settings → Card terminal on the tablet, and the store log), the
 * phone sends it and gets a bearer token back. One phone at a time; pairing a
 * new one replaces the old. Only a hash of the token is kept.
 *
 * The phone polls for work (every poll is a heartbeat). No heartbeat for
 * [HEARTBEAT_TIMEOUT_MS] → the reader is OFFLINE.
 *
 * Every event goes to a short in-memory log (the transaction monitor), never
 * a client secret.
 */
class PhoneReaderHub(
    private val clock: () -> Long = System::currentTimeMillis,
    private val tokens: PhoneTokenStore = PhoneTokenStore.SyncStateStore,
    private val random: java.util.Random = SecureRandom(),
) {
    companion object {
        const val HEARTBEAT_TIMEOUT_MS = 15_000L
        /** Wrong codes before the code is replaced (a brute-force brake). */
        const val MAX_WRONG_CODES = 5
        private val log = LoggerFactory.getLogger(PhoneReaderHub::class.java)
        private val ACTIVE = setOf("queued", "collecting", "processing")

        fun hash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    class Job(
        val paymentIntentId: String,
        val clientSecret: String,
        val amountCents: Long,
        val currency: String,
        val description: String?,
        val createdAt: Long,
        @Volatile var state: String = "queued",
        @Volatile var code: String? = null,
        @Volatile var message: String? = null,
        @Volatile var declined: Boolean = false,
    ) {
        val active: Boolean get() = state in ACTIVE
        fun view() = PhoneJobView(paymentIntentId, clientSecret, amountCents, currency, description, state)
    }

    private val lock = Any()
    private val jobs = LinkedHashMap<String, Job>()
    private var wrongCodes = 0
    @Volatile private var lastSeen = 0L
    @Volatile var heartbeat: PhoneHeartbeat? = null
        private set
    @Volatile private var phoneName: String? = null

    /** What the tablet shows so the phone can pair. A new one after each pairing. */
    @Volatile var pairingCode: String = newCode()
        private set

    private fun newCode() = (100000 + random.nextInt(900000)).toString()

    // --- the transaction monitor ---------------------------------------------

    private val logLines = ArrayDeque<PhoneLogLine>()
    private var logSeq = 0L
    fun log(text: String) = synchronized(logLines) {
        logLines.addLast(PhoneLogLine(++logSeq, clock(), text))
        while (logLines.size > 200) logLines.removeFirst()
    }
    fun logSince(since: Long): List<PhoneLogLine> = synchronized(logLines) { logLines.filter { it.seq > since } }

    // --- pairing -------------------------------------------------------------

    val paired: Boolean get() = tokens.get() != null

    fun pair(code: String, deviceName: String?): String = synchronized(lock) {
        if (code.trim() != pairingCode) {
            if (++wrongCodes >= MAX_WRONG_CODES) { pairingCode = newCode(); wrongCodes = 0 }
            log("PAIR refused: wrong code")
            throw TerminalException(401, "terminal_pairing_code_wrong", "That is not the pairing code shown on the POS")
        }
        val token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
        tokens.set(hash(token))
        phoneName = deviceName?.trim()?.take(60)?.ifBlank { null }
        (tokens as? PhoneTokenStore.SyncStateStore)?.setName(phoneName)
        pairingCode = newCode()
        wrongCodes = 0
        lastSeen = clock()
        log("PAIR phone paired (${phoneName ?: "phone"})")
        log.info("Tap to Pay: phone paired (${phoneName ?: "phone"}); next pairing code $pairingCode")
        token
    }

    fun authorized(token: String?): Boolean {
        val stored = tokens.get() ?: return false
        return !token.isNullOrBlank() && MessageDigest.isEqual(hash(token.trim()).toByteArray(), stored.toByteArray())
    }

    /** Throws 401 unless [token] is the paired phone's; counts as a heartbeat. */
    fun requirePhone(token: String?) {
        if (!authorized(token)) throw TerminalException(401, TerminalException.NOT_PAIRED, "pair this phone with the store first (code on the POS)")
        lastSeen = clock()
    }

    fun beat(hb: PhoneHeartbeat) {
        val prev = heartbeat
        heartbeat = hb
        lastSeen = clock()
        if (prev?.state != hb.state) log("READER ${hb.state}" + (hb.message?.let { " — $it" } ?: ""))
    }

    fun unpair() = synchronized(lock) {
        tokens.set(null)
        (tokens as? PhoneTokenStore.SyncStateStore)?.setName(null)
        heartbeat = null
        lastSeen = 0
        pairingCode = newCode()
        log("PAIR phone unpaired")
    }

    fun online(): Boolean = paired && clock() - lastSeen <= HEARTBEAT_TIMEOUT_MS

    fun name(): String = phoneName ?: (tokens as? PhoneTokenStore.SyncStateStore)?.name()?.also { phoneName = it } ?: "Phone"

    // --- the queue -----------------------------------------------------------

    fun enqueue(job: Job) = synchronized(lock) {
        jobs[job.paymentIntentId] = job
        while (jobs.size > 50) jobs.remove(jobs.keys.first())
        log("QUEUE ${job.paymentIntentId} ${fmt(job.amountCents)} ${job.currency.uppercase()} — waiting for the phone")
    }

    fun job(paymentIntentId: String): Job? = synchronized(lock) { jobs[paymentIntentId] }

    fun active(): Job? = synchronized(lock) { jobs.values.firstOrNull { it.active } }

    /** The payment the phone should take now (oldest active), or null. */
    fun next(): PhoneJobView? = active()?.view()

    fun report(paymentIntentId: String, r: PhoneReport): Job = synchronized(lock) {
        val j = jobs[paymentIntentId] ?: throw TerminalException(404, "terminal_payment_not_found", "no such payment for the phone")
        if (!j.active) return j // the POS cancelled it (or it timed out) meanwhile
        when (r.status) {
            "collecting", "processing" -> j.state = r.status
            "collected" -> j.state = "collected"
            "canceled" -> { j.state = "canceled"; j.code = r.code ?: "customer_canceled"; j.message = r.message }
            "failed" -> { j.state = "failed"; j.code = r.code; j.message = r.message; j.declined = r.declined }
            else -> throw IllegalArgumentException("status must be collecting, processing, collected, failed or canceled")
        }
        log("PHONE ${j.paymentIntentId} ${r.status}" + (r.code?.let { " ($it)" } ?: "") + (r.message?.let { " — $it" } ?: ""))
        j
    }

    /** The POS side ended it (cancel, timeout): the phone stops collecting on its next poll. */
    fun end(paymentIntentId: String, state: String, code: String? = null) = synchronized(lock) {
        jobs[paymentIntentId]?.takeIf { it.active }?.let {
            it.state = state
            it.code = code
            log("POS ${it.paymentIntentId} $state" + (code?.let { c -> " ($c)" } ?: ""))
        }
    }

    private fun fmt(cents: Long) = "%d.%02d".format(cents / 100, cents % 100)
}
