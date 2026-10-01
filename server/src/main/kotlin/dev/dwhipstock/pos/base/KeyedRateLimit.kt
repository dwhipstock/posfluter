package dev.dwhipstock.pos.base

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * At most [max] calls per [window] for each key (a kiosk's device id): the
 * next one throws [TooManyRequestsException] (429 with Retry-After) until the oldest
 * call in the window ages out. In memory, like the PIN limiter: a restart
 * clears it, which is fine for a flood guard.
 */
class KeyedRateLimiter(
    private val max: Int,
    private val window: Duration,
    private val clock: () -> Instant = Instant::now,
) {
    private val calls = ConcurrentHashMap<String, ArrayDeque<Instant>>()

    fun acquire(key: String) {
        val q = calls.computeIfAbsent(key) { ArrayDeque() }
        synchronized(q) {
            val now = clock()
            val cutoff = now.minus(window)
            while (q.isNotEmpty() && !q.first().isAfter(cutoff)) q.removeFirst()
            if (q.size >= max) {
                val retry = Duration.between(now, q.first().plus(window)).seconds.coerceAtLeast(1)
                throw TooManyRequestsException(retry)
            }
            q.addLast(now)
        }
    }

    /**
     * One call against every key at once (a manager, a user and a device), all
     * or nothing: when any key is over, it throws for the first such key (in
     * the given order) and counts nothing.
     */
    fun acquireAll(keys: List<String>) {
        val distinct = keys.distinct()
        val queues = distinct.map { k -> calls.computeIfAbsent(k) { ArrayDeque() } }
        // lock in key order, so two callers sharing keys never deadlock
        val lockOrder = distinct.indices.sortedBy { distinct[it] }.map { queues[it] }
        withLocks(lockOrder, 0) {
            val now = clock()
            val cutoff = now.minus(window)
            queues.forEach { q -> while (q.isNotEmpty() && !q.first().isAfter(cutoff)) q.removeFirst() }
            queues.firstOrNull { it.size >= max }?.let { q ->
                val retry = Duration.between(now, q.first().plus(window)).seconds.coerceAtLeast(1)
                throw TooManyRequestsException(retry)
            }
            queues.forEach { it.addLast(now) }
        }
    }

    private fun withLocks(locks: List<Any>, i: Int, block: () -> Unit) {
        if (i == locks.size) block() else synchronized(locks[i]) { withLocks(locks, i + 1, block) }
    }
}

/** → 429 `rate_limited` with Retry-After (a flood guard, not a PIN lockout). */
class TooManyRequestsException(val retryAfterSeconds: Long) :
    RuntimeException("too many requests; retry in ${retryAfterSeconds}s")
