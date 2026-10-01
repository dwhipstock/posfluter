package dev.dwhipstock.pos

import dev.dwhipstock.pos.aimenu.AI_CALLS_MAX
import dev.dwhipstock.pos.aimenu.admitAiCall
import dev.dwhipstock.pos.aimenu.aiCallLimiter
import dev.dwhipstock.pos.aiphotos.ImageGenException
import dev.dwhipstock.pos.base.CleanText
import dev.dwhipstock.pos.base.KeyedRateLimiter
import dev.dwhipstock.pos.base.TooManyRequestsException
import dev.dwhipstock.pos.base.escapeHtml
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The shared base helpers that replaced per-package copies. */
class SharedHelpersTest {
    @Test
    fun `acquireAll counts every key or none`() {
        var now = Instant.ofEpochSecond(1_000)
        val l = KeyedRateLimiter(2, Duration.ofMinutes(10)) { now }
        l.acquireAll(listOf("manager:m", "user:a"))
        l.acquireAll(listOf("manager:m", "user:b"))
        // the manager is full: refused, and user:c is not counted
        val e = assertFailsWith<TooManyRequestsException> { l.acquireAll(listOf("user:c", "manager:m")) }
        assertEquals(600L, e.retryAfterSeconds)
        l.acquireAll(listOf("user:c")); l.acquireAll(listOf("user:c")) // still two left for user:c
        // the window slides: exactly 10 minutes later the first call has aged out
        now = now.plusSeconds(600)
        l.acquireAll(listOf("manager:m", "manager:m")) // a repeated key counts once
        l.acquireAll(listOf("manager:m"))
        assertFailsWith<TooManyRequestsException> { l.acquireAll(listOf("manager:m")) }
    }

    @Test
    fun `the AI limit is 20 calls per 10 minutes, a 429 menu_ai_too_many with Retry-After`() {
        var ms = 0L
        val l = aiCallLimiter { ms }
        repeat(AI_CALLS_MAX) { l.admitAiCall(listOf("manager:m", "device:d")) }
        ms = 60_500
        val e = assertFailsWith<ImageGenException> { l.admitAiCall(listOf("device:other", "manager:m")) }
        assertEquals(429, e.status)
        assertEquals("menu_ai_too_many", e.code)
        assertEquals(539L, e.retryAfterSeconds)
        assertEquals("too many AI requests: at most 20 every 10 minutes", e.message)
        ms = 600_000
        l.admitAiCall(listOf("manager:m"))
    }

    @Test
    fun `CleanText field keeps one printable line, without LRM or RLM, cut to max`() {
        assertNull(CleanText.field(null))
        assertNull(CleanText.field("  ‎ \n "))
        assertEquals("no onions please", CleanText.field("  no\tonions\nplease\u0000‮‏ "))
        assertEquals("abc", CleanText.field("abcdef", max = 3))
        assertEquals(200, CleanText.field("x".repeat(500))!!.length)
    }

    @Test
    fun `escapeHtml escapes text and both quote styles`() {
        assertEquals("&lt;b a=&quot;1&quot; c=&#39;2&#39;&gt;Fish &amp; Chips", "<b a=\"1\" c='2'>Fish & Chips".escapeHtml())
    }
}
