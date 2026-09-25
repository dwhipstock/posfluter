package dev.dwhipstock.pos

import dev.dwhipstock.pos.api.tableSlipLines
import dev.dwhipstock.pos.api.wifiSlipLines
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue
import dev.dwhipstock.pos.sdk.Align
import dev.dwhipstock.pos.sdk.GuestWifi
import dev.dwhipstock.pos.sdk.Money
import dev.dwhipstock.pos.sdk.PrintLine
import dev.dwhipstock.pos.sdk.Receipt
import dev.dwhipstock.pos.sdk.ReceiptItem
import dev.dwhipstock.pos.sdk.ReceiptKind
import dev.dwhipstock.pos.sdk.ReceiptPolicy
import dev.dwhipstock.pos.sdk.ReceiptRenderer
import dev.dwhipstock.pos.sdk.ThermalLayout
import dev.dwhipstock.pos.sdk.ThermalLayout.Row
import dev.dwhipstock.pos.sdk.ThermalLayout.Style
import dev.dwhipstock.pos.sdk.ThermalReceiptRenderer
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Nothing on a thermal slip or receipt may run past the printable width of the
 * 80mm head (512 dots, ≈ 48 columns of Font A). Rendered with the real fonts at
 * the real width: every laid-out row is measured, and the raster itself must
 * carry no ink in the side margins (where clipped text would land).
 */
class ThermalFitTest {

    private val zone = "Salle à manger et bar / Dining Room & Bar"
    private val url = "http://192.168.1.50:8080/m/t/AbCdEfGhIjKlMnOpQrStUv"
    private val wifi = GuestWifi("Lantern Guests", "Sup3r-Secret-Pass")

    private fun texts(rows: List<Row>) = rows.filterIsInstance<Row.Text>()

    /** Every row fits the content width, and the bitmap has no ink in the margins. */
    private fun assertFits(lines: List<PrintLine>) {
        val rows = ThermalReceiptRenderer.layout(lines)
        for (row in rows) {
            val w = ThermalReceiptRenderer.rowWidth(row)
            assertTrue(w <= ThermalLayout.CONTENT, "row wider than the paper (${w} > ${ThermalLayout.CONTENT}): $row")
        }
        val img = ThermalReceiptRenderer.renderImage(lines)
        assertEquals(ThermalLayout.WIDTH, img.width)
        val edge = ThermalLayout.MARGIN / 2
        for (y in 0 until img.height) for (x in (0 until edge) + (img.width - edge until img.width)) {
            val rgb = img.getRGB(x, y) and 0xFFFFFF
            assertTrue(rgb > 0xA0A0A0, "ink at the paper edge x=$x y=$y")
        }
    }

    @Test
    fun `venue name splits into brand and location`() {
        for (venue in CopperLanternVenue.entries) {
            val rows = texts(ThermalReceiptRenderer.layout(listOf(PrintLine.LogoPlaceholder(venue.displayName))))
            assertEquals(2, rows.size, venue.displayName)
            assertEquals("Copper Lantern", rows[0].text)
            assertEquals(Style.TITLE, rows[0].style)
            assertEquals(venue.displayName.substringAfter(" — "), rows[1].text)
            assertEquals(Style.SUBTITLE, rows[1].style)
            assertTrue(rows[1].size < rows[0].size)
            assertTrue(rows.all { it.align == Align.CENTER })
        }
    }

    @Test
    fun `bilingual zone line wraps at the slash`() {
        val rows = texts(ThermalReceiptRenderer.layout(listOf(PrintLine.Text(zone, Align.CENTER))))
        // it only wraps when it doesn't fit; measured at the real width either way
        if (rows.size > 1) assertEquals(listOf("Salle à manger et bar", "Dining Room & Bar"), rows.map { it.text })
        // a narrower measurer (the tablet's wider glyphs) must force the same wrap
        val wide = ThermalLayout.TextMeasurer { t, _, size -> t.length * size * 0.62f }
        val forced = texts(ThermalLayout.layout(listOf(PrintLine.Text(zone, Align.CENTER)), wide))
        assertEquals(listOf("Salle à manger et bar", "Dining Room & Bar"), forced.map { it.text })
    }

    @Test
    fun `long venue name without a separator wraps and never clips`() {
        val name = "The Grand Copper Lantern Brasserie and Oyster Bar of the Old Port"
        val rows = texts(ThermalReceiptRenderer.layout(listOf(PrintLine.LogoPlaceholder(name))))
        assertTrue(rows.size > 1, "expected a wrap, got $rows")
        assertEquals(name, rows.joinToString(" ") { it.text }) // every word kept, in order
        assertFits(listOf(PrintLine.LogoPlaceholder(name)))
    }

    @Test
    fun `an unbreakable word shrinks then breaks, keeping every character`() {
        val pw = "x".repeat(63)
        val rows = texts(ThermalReceiptRenderer.layout(listOf(PrintLine.Header(pw, exact = true))))
        assertEquals(pw, rows.joinToString("") { it.text })
        assertFits(listOf(PrintLine.Header(pw, exact = true), PrintLine.Header("W".repeat(80))))
    }

    @Test
    fun `table slips fit for both stores, with and without wifi`() {
        for (venue in CopperLanternVenue.entries) for (w in listOf(null, wifi)) {
            assertFits(tableSlipLines(venue.displayName, "U-2", zone, url, w))
        }
    }

    @Test
    fun `wifi slips fit, including a long network name and password`() {
        for (venue in CopperLanternVenue.entries) assertFits(wifiSlipLines(venue.displayName, wifi))
        val long = GuestWifi("Copper Lantern Guest Network 5G", "correct horse battery staple " + "z".repeat(34))
        val lines = wifiSlipLines("Copper Lantern — Plateau", long)
        assertFits(lines)
        // the password survives wrapping intact (spaces included)
        val rows = texts(ThermalReceiptRenderer.layout(listOf(PrintLine.Header(long.password, exact = true))))
        assertEquals(long.password, rows.joinToString("") { it.text })
    }

    @Test
    fun `receipt header and long item rows fit`() {
        val money = Money(1234)
        val receipt = Receipt(
            checkId = 42, tableLabel = "U-2",
            openedAt = LocalDateTime.of(2026, 9, 1, 18, 0), closedAt = LocalDateTime.of(2026, 9, 1, 19, 0),
            items = listOf(ReceiptItem(
                "Planche de charcuterie québécoise et fromages affinés de la région",
                "Quebec charcuterie board with aged regional cheeses", "Grande", "Large",
                1, money, money, null)),
            fees = emptyList(), grandTotal = money, taxIncluded = Money(0), taxRatePercent = null,
            tenders = emptyList(),
        )
        for (venue in CopperLanternVenue.entries) {
            val policy = ReceiptPolicy.Standard(venue.displayName,
                listOf(venue.address, venue.phone), "Merci ! / Thank you!", showTax = false)
            val lines = ReceiptRenderer.render(receipt, policy, ReceiptKind.PROVISIONAL)
            assertFits(lines)
            val head = texts(ThermalReceiptRenderer.layout(lines.take(1)))
            assertEquals(listOf("Copper Lantern", venue.displayName.substringAfter(" — ")), head.map { it.text })
        }
    }
}
