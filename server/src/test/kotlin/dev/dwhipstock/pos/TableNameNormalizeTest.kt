package dev.dwhipstock.pos

import dev.dwhipstock.pos.api.normalizeTableName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A VIP table name is saved as one line: newlines/tabs become single spaces. */
class TableNameNormalizeTest {
    @Test
    fun `line breaks tabs and runs of spaces collapse to one space`() {
        assertEquals("VIP VIP VIP", normalizeTableName("VIP\nVIP\r\n\tVIP"))
        assertEquals("Board of Directors", normalizeTableName("  Board  of\u0000 Directors \n"))
        assertEquals("Alex Morgan", normalizeTableName("Alex Morgan"))
    }

    @Test
    fun `blank or null clears the name`() {
        assertNull(normalizeTableName(null))
        assertNull(normalizeTableName(" \n\t "))
    }
}
