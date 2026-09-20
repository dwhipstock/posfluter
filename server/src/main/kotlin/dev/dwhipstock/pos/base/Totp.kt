package dev.dwhipstock.pos.base

import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 TOTP (HMAC-SHA1, 6 digits, 30 s step, ±1 step tolerance at verify)
 * plus RFC 4648 base32 (no padding) for secrets. Hand-rolled on javax.crypto —
 * no extra dependency for 40 lines of spec. Ported verbatim from the cloud's
 * owner-portal TOTP (dev.dwhipstock.poscloud.auth.Totp) so staff-app 2FA is
 * verified OFFLINE on the store, with the identical algorithm the portal uses.
 */
object Totp {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val STEP_SECONDS = 30L
    private const val DIGITS = 6
    private val random = SecureRandom()

    fun newSecret(): String = base32Encode(ByteArray(20).also { random.nextBytes(it) })

    fun code(secret: String, epochSeconds: Long = System.currentTimeMillis() / 1000): String {
        val counter = epochSeconds / STEP_SECONDS
        return hotp(base32Decode(secret), counter)
    }

    fun verify(secret: String, code: String, epochSeconds: Long = System.currentTimeMillis() / 1000): Boolean =
        matchingCounter(secret, code, epochSeconds) != null

    /**
     * The 30-second step index whose code matches [code] (within the ±1 tolerance),
     * or null if none. Callers persist the returned counter to make each code
     * single-use within its window (replay prevention): reject a code whose matched
     * step is ≤ the last accepted one.
     */
    fun matchingCounter(secret: String, code: String, epochSeconds: Long = System.currentTimeMillis() / 1000): Long? {
        val clean = code.trim()
        if (clean.length != DIGITS || !clean.all { it.isDigit() }) return null
        val key = base32Decode(secret)
        val counter = epochSeconds / STEP_SECONDS
        return (-1L..1L).map { counter + it }.firstOrNull { hotp(key, it) == clean }
    }

    fun otpauthUri(issuer: String, account: String, secret: String): String {
        // URLEncoder is form-encoding: '+' for space is wrong inside a URI path
        fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
        return "otpauth://totp/${enc(issuer)}:${enc(account)}?secret=$secret&issuer=${enc(issuer)}"
    }

    private fun hotp(key: ByteArray, counter: Long): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(DIGITS, '0')
    }

    fun base32Encode(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0L
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toLong() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[((buffer shr (bits - 5)) and 0x1f).toInt()])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[((buffer shl (5 - bits)) and 0x1f).toInt()])
        return out.toString()
    }

    fun base32Decode(s: String): ByteArray {
        val out = mutableListOf<Byte>()
        var buffer = 0L
        var bits = 0
        for (c in s.trimEnd('=')) {
            val v = ALPHABET.indexOf(c.uppercaseChar())
            require(v >= 0) { "invalid base32 character '$c'" }
            buffer = (buffer shl 5) or v.toLong()
            bits += 5
            if (bits >= 8) {
                out += ((buffer shr (bits - 8)) and 0xff).toByte()
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
