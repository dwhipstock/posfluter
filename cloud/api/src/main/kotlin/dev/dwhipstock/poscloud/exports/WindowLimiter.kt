package dev.dwhipstock.poscloud.exports

import dev.dwhipstock.poscloud.RateLimitException

/**
 * In-memory sliding-window throttle, the same shape as the login limiter
 * (auth/PortalAuth.kt): at most [limit] hits per key per [windowMs]; one more
 * is a [RateLimitException] (429 + Retry-After). Keys are signed-in users, so
 * the map is bounded by the tenant's users; [MAX_KEYS] is a backstop.
 */
class WindowLimiter(private val limit: Int, private val windowMs: Long, private val message: String) {
    private val hits = LinkedHashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun record(key: String, nowMs: Long = System.currentTimeMillis()) {
        val cutoff = nowMs - windowMs
        hits.entries.removeAll { (_, times) ->
            while (times.isNotEmpty() && times.first() <= cutoff) times.removeFirst()
            times.isEmpty()
        }
        val times = hits.getOrPut(key) { ArrayDeque() }
        if (times.size >= limit) {
            val retry = ((times.first() + windowMs - nowMs) / 1000).coerceAtLeast(1)
            throw RateLimitException(message, retry)
        }
        times.addLast(nowMs)
        if (hits.size > MAX_KEYS) hits.keys.iterator().let { it.next(); it.remove() }
    }

    /** Test hook. */
    @Synchronized
    internal fun reset() = hits.clear()

    private companion object {
        const val MAX_KEYS = 10_000
    }
}
