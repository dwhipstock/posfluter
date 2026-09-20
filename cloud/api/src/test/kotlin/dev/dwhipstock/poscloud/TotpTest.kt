package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.auth.Totp
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TOTP conformance. The self-consistent AuthTotpTest (generate with Totp.code,
 * verify with Totp.verify) can't catch a spec deviation — both sides would drift
 * together. These vectors come from the RFC, so they fail if our HOTP/base32/
 * time-step math disagrees with what a real authenticator app computes.
 */
class TotpTest {

    // RFC 4226 §D uses ASCII seed "12345678901234567890"; base32 of that:
    private val rfcSecret = Totp.base32Encode("12345678901234567890".toByteArray())

    @Test
    fun base32RoundTripsAndMatchesRfcSeed() {
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", rfcSecret)
        val secret = Totp.newSecret()
        assertEquals(32, secret.length) // 20 random bytes → 32 base32 chars, no padding
    }

    @Test
    fun matchesRfc4226HotpVectors() {
        // RFC 4226 §D: HOTP(secret, counter) for counter 0..9. TOTP at epoch
        // `counter * 30` selects that same counter (epoch / 30 = counter).
        val expected = listOf(
            "755224", "287082", "359152", "969429", "338314",
            "254676", "287922", "162583", "399871", "520489",
        )
        expected.forEachIndexed { counter, code ->
            assertEquals(code, Totp.code(rfcSecret, epochSeconds = counter * 30L), "counter $counter")
        }
    }

    @Test
    fun matchesRfc6238TotpVectors() {
        // RFC 6238 §B (SHA1 rows), truncated to the low 6 digits.
        val vectors = listOf(
            59L to "287082",
            1111111109L to "081804",
            1111111111L to "050471",
            1234567890L to "005924",
            2000000000L to "279037",
        )
        for ((epoch, code) in vectors) {
            assertEquals(code, Totp.code(rfcSecret, epochSeconds = epoch), "t=$epoch")
        }
    }

    @Test
    fun verifyAcceptsCurrentAndOneStepSkew() {
        val secret = Totp.newSecret()
        val now = 1_700_000_000L
        assertTrue(Totp.verify(secret, Totp.code(secret, now), now))
        // code from the previous / next 30 s window still accepted (±1 step)
        assertTrue(Totp.verify(secret, Totp.code(secret, now - 30), now))
        assertTrue(Totp.verify(secret, Totp.code(secret, now + 30), now))
        // two steps away is rejected
        assertFalse(Totp.verify(secret, Totp.code(secret, now - 60), now))
        assertFalse(Totp.verify(secret, Totp.code(secret, now + 60), now))
    }

    @Test
    fun verifyRejectsWrongCode() {
        val secret = Totp.newSecret()
        val now = 1_700_000_000L
        val real = Totp.code(secret, now)
        val wrong = if (real == "000000") "111111" else "000000"
        assertFalse(Totp.verify(secret, wrong, now))
    }
}
