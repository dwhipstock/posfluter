package dev.dwhipstock.pos.base

/**
 * Product barcodes: UPC-A (12 digits) and EAN-13 (13). A UPC-A is an EAN-13
 * with a leading zero, so a scanner that reports either form finds the same
 * product ([normalize]). The last digit is a checksum ([checkDigit]).
 */
object Upc {
    /** The check digit for the first 11 (UPC-A) or 12 (EAN-13) digits. */
    fun checkDigit(body: String): Int {
        require(body.all(Char::isDigit) && (body.length == 11 || body.length == 12)) {
            "a UPC-A body is 11 digits, an EAN-13 body 12"
        }
        // weights 3,1,3,1… from the rightmost body digit
        val sum = body.reversed().mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 3 else 1 }.sum()
        return (10 - sum % 10) % 10
    }

    /** [body] (11 digits) plus its check digit. */
    fun upcA(body: String): String = body + checkDigit(body)

    fun isValid(code: String): Boolean {
        val c = code.trim()
        if (!c.all(Char::isDigit) || (c.length != 12 && c.length != 13)) return false
        return checkDigit(c.dropLast(1)) == c.last() - '0'
    }

    /**
     * The form products are stored under: a valid EAN-13 starting with 0 is
     * the UPC-A without it; anything else (other lengths, in-store codes) is
     * kept as typed, trimmed.
     */
    fun normalize(code: String): String {
        val c = code.trim()
        return if (c.length == 13 && c.startsWith("0") && isValid(c)) c.drop(1) else c
    }
}
