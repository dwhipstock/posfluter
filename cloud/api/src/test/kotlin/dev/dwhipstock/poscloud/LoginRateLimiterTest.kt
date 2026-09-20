package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.auth.LoginRateLimiter
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The unauthenticated /auth/login throttle map must stay bounded: keys whose
 * attempts have aged out of the rolling window are evicted, and a flood of fresh
 * distinct keys can't grow the map without limit. Regression for the map that
 * previously only pruned timestamps within a key but never evicted the keys.
 */
class LoginRateLimiterTest {

    @Before
    fun setUp() = LoginRateLimiter.reset()

    @Test
    fun agedOutKeysAreEvicted() {
        val t0 = 10_000_000_000L
        repeat(2_000) { LoginRateLimiter.record("aged-$it", nowMs = t0) }
        assertEquals(2_000, LoginRateLimiter.keyCount, "each distinct key is tracked")
        // One call a full window later prunes every stale bucket, leaving only it.
        LoginRateLimiter.record("survivor", nowMs = t0 + 61_000L)
        assertEquals(1, LoginRateLimiter.keyCount, "aged-out keys must be evicted after their window")
    }

    @Test
    fun distinctKeyFloodStaysBounded() {
        val t0 = 20_000_000_000L
        // Far more fresh distinct keys than the hard cap, all within one window.
        repeat(25_000) { LoginRateLimiter.record("flood-$it", nowMs = t0) }
        assertTrue(
            LoginRateLimiter.keyCount <= 10_000,
            "map must stay bounded under a distinct-key flood, was ${LoginRateLimiter.keyCount}",
        )
    }
}
