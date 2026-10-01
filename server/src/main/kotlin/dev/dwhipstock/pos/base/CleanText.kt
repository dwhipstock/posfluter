package dev.dwhipstock.pos.base

/**
 * Free text from outside the staff app (a kiosk note, a device name) is kept
 * printable and single-line: control characters are dropped (U+0000 cannot be
 * stored in the cloud's Postgres JSONB at all, and one in an outbox event used
 * to stop every later event from syncing), tabs and line breaks become a
 * space, and the bidi overrides that make a ticket read backwards are dropped.
 * Ordinary letters, accents and emoji are untouched.
 */
object CleanText {
    private fun bidiControl(c: Char) = c in '‪'..'‮' || c in '⁦'..'⁩'

    fun line(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\t' || c == '\n' || c == '\r' -> out.append(' ')
                c.isISOControl() || bidiControl(c) -> {}
                // a lone surrogate half is not text (and is not valid UTF-8)
                c.isHighSurrogate() && i + 1 < raw.length && raw[i + 1].isLowSurrogate() -> {
                    out.append(c).append(raw[i + 1]); i++
                }
                c.isSurrogate() -> {}
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /**
     * Only what no database can hold: U+0000 and lone surrogate halves are
     * dropped; line breaks, tabs and everything else are kept as they are
     * (a sync payload's multi-line descriptions stay multi-line).
     */
    fun storable(raw: String): String {
        if (raw.none { it == '\u0000' || it.isSurrogate() }) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\u0000' -> {}
                c.isHighSurrogate() && i + 1 < raw.length && raw[i + 1].isLowSurrogate() -> {
                    out.append(c).append(raw[i + 1]); i++
                }
                c.isSurrogate() -> {}
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** [line], trimmed; null when nothing is left. */
    fun lineOrNull(raw: String?): String? = raw?.let(::line)?.trim()?.takeIf { it.isNotEmpty() }
}
