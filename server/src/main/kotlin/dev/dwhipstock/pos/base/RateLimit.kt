package dev.dwhipstock.pos.base

import java.time.Duration
import java.time.Instant

/** → 429 with Retry-After. */
class RateLimitException(val retryAfterSeconds: Long) :
    RuntimeException("too many failed PIN attempts; retry in ${retryAfterSeconds}s")

/**
 * In-memory PIN attempt throttle: N failures inside the window locks the
 * terminal for the lockout period. Deliberately not persisted — single
 * terminal, single process; a restart clearing it is acceptable for v1.
 * Failed PIN entries don't identify a user, so the lock is terminal-wide.
 */
class LoginRateLimiter(
    private val maxFailures: Int = 5,
    private val window: Duration = Duration.ofMinutes(5),
    private val lockout: Duration = Duration.ofMinutes(15),
    private val clock: () -> Instant = Instant::now,
) {
    private val failures = ArrayDeque<Instant>()
    private var lockedUntil: Instant? = null

    /** Call before verifying a PIN; throws while locked out. */
    @Synchronized
    fun checkNotLocked() {
        val until = lockedUntil ?: return
        val now = clock()
        if (now.isBefore(until)) {
            throw RateLimitException(Duration.between(now, until).seconds.coerceAtLeast(1))
        }
        lockedUntil = null
    }

    @Synchronized
    fun recordFailure() {
        val now = clock()
        failures.addLast(now)
        while (failures.isNotEmpty() && failures.first().isBefore(now.minus(window))) {
            failures.removeFirst()
        }
        if (failures.size >= maxFailures) {
            lockedUntil = now.plus(lockout)
            failures.clear()
        }
    }

    @Synchronized
    fun recordSuccess() = failures.clear()
}

/**
 * Who is typing a PIN, for [PinAttemptLimiter]. [key] is the paired device
 * (`dev:<id>`) when one is presented, else the direct peer address
 * (`ip:<addr>`; X-Forwarded-For is NOT trusted — a guest could spoof it).
 * [trusted] = the store's own counter: the POS app on the tablet itself
 * (loopback — the embedded store and the desktop store both listen on
 * 127.0.0.1) or a paired terminal.
 */
data class PinClient(val key: String, val trusted: Boolean) {
    companion object {
        /** Direct service calls (unit tests, internal callers): the tablet itself. */
        val LOCAL = PinClient("ip:loopback", trusted = true)

        private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")

        fun isLoopback(host: String): Boolean = host in LOOPBACK || host.startsWith("127.")

        /** [deviceId] = the live paired device presenting the call, [peer] = the socket's remote host. */
        fun of(deviceId: String?, peer: String): PinClient = when {
            deviceId != null -> PinClient("dev:$deviceId", trusted = true)
            isLoopback(peer) -> LOCAL
            else -> PinClient("ip:$peer", trusted = false)
        }
    }
}

/**
 * PIN brute-force guard, keyed per client instead of terminal-wide (red-team:
 * five junk PINs on the open POST /login from any guest phone used to freeze
 * every sign-in and manager approval on the store for 15 minutes).
 *
 * - Every client ([PinClient.key]) has its own bucket, so one phone's guesses
 *   never lock anyone else out. LAN clients: 5 failures / 5 min → 15 min lock.
 * - The store's own counter (loopback / paired device, [PinClient.trusted]) has
 *   a separate, roomier bucket (10 failures / 5 min → 5 min lock): staff fat-
 *   finger PINs at the counter, and nothing a guest does touches it. 10 tries
 *   per ~10 min still makes a 4-digit PIN search take weeks.
 * - Rotating addresses doesn't buy an attacker more guesses: every untrusted
 *   client that has not signed in successfully recently shares a store-wide
 *   ceiling (30 failures / 5 min → 15 min). A client that signed in with a
 *   correct PIN in the last 12 hours is exempt from that ceiling, and the
 *   trusted counter never sees it — so a correct PIN from the tablet, a paired
 *   device or a phone already in use is not blocked by other clients' failures.
 * - Bounded memory: past [maxKeys] the least recently used client is dropped.
 *
 * In memory only, like [LoginRateLimiter]; a restart clears it.
 */
class PinAttemptLimiter(
    private val clock: () -> Instant = Instant::now,
    private val maxKeys: Int = 4096,
) {
    private val perClient = object : LinkedHashMap<String, LoginRateLimiter>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LoginRateLimiter>) = size > maxKeys
    }
    private val knownGood = object : LinkedHashMap<String, Instant>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Instant>) = size > maxKeys
    }
    private val untrustedCeiling = LoginRateLimiter(
        maxFailures = 30, window = Duration.ofMinutes(5), lockout = Duration.ofMinutes(15), clock = clock)

    private fun bucket(c: PinClient): LoginRateLimiter = perClient.getOrPut(c.key) {
        if (c.trusted) LoginRateLimiter(maxFailures = 10, window = Duration.ofMinutes(5),
            lockout = Duration.ofMinutes(5), clock = clock)
        else LoginRateLimiter(clock = clock)
    }

    private fun recentlyGood(c: PinClient): Boolean {
        val at = knownGood[c.key] ?: return false
        if (at.isAfter(clock().minus(KNOWN_GOOD))) return true
        knownGood.remove(c.key)
        return false
    }

    private fun sharesCeiling(c: PinClient) = !c.trusted && !recentlyGood(c)

    /** Call before verifying a PIN; throws [RateLimitException] while [c] is locked out. */
    @Synchronized
    fun checkNotLocked(c: PinClient) {
        bucket(c).checkNotLocked()
        if (sharesCeiling(c)) untrustedCeiling.checkNotLocked()
    }

    @Synchronized
    fun recordFailure(c: PinClient) {
        bucket(c).recordFailure()
        if (sharesCeiling(c)) untrustedCeiling.recordFailure()
    }

    /**
     * A correct PIN. Only the store's own counter gets its failures wiped: on a
     * LAN client they just age out of the window, or anyone who knows one PIN
     * (the demo sheet prints the server's) could reset the counter between
     * guesses at the manager's.
     */
    @Synchronized
    fun recordSuccess(c: PinClient) {
        if (c.trusted) bucket(c).recordSuccess()
        knownGood[c.key] = clock()
    }

    companion object {
        private val KNOWN_GOOD: Duration = Duration.ofHours(12)
    }
}
