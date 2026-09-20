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
