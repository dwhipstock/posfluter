package dev.dwhipstock.pos.sdk

/**
 * Platform-neutral thermal layout: turns [PrintLine]s into rows that each fit
 * the printable width, so no text is ever clipped at the paper edge. Shared by
 * the desktop (Java2D) and the tablet (Android Canvas) renderers. Each renderer
 * supplies only a [TextMeasurer] for its own fonts, so the fitting is exact for
 * the glyphs it actually draws.
 *
 * Rules, for every text line measured against [CONTENT] dots (80mm head,
 * 512 dots ≈ 48 columns of Font A, minus the side margins):
 * - The venue name splits on " — ": brand in the title font, location below it
 *   in a smaller bold font ("Copper Lantern" / "Vieux-Port").
 * - A bilingual "fr / en" line that doesn't fit wraps at the " / ".
 * - Otherwise a line that doesn't fit shrinks one size step; if it still doesn't
 *   fit it word-wraps onto more lines at its normal size.
 * - A single word wider than the paper shrinks down to [MIN_SIZE], then breaks
 *   between characters. Nothing is ever dropped.
 */
object ThermalLayout {
    const val WIDTH = 512
    const val MARGIN = 8
    const val CONTENT = WIDTH - MARGIN * 2

    /** One size step down (e.g. 40 → 34, 26 → 22). */
    const val STEP = 0.85f
    const val MIN_SIZE = 16f

    private const val VENUE_SEPARATOR = " — "
    private const val BILINGUAL_SEPARATOR = " / "

    enum class Style(val size: Float, val bold: Boolean) {
        BODY(26f, false),
        BOLD(26f, true),
        SUBTITLE(30f, true),
        TITLE(40f, true),
    }

    fun interface TextMeasurer {
        /** Width in dots of [text] drawn in [style] at [size] px. */
        fun width(text: String, style: Style, size: Float): Float
    }

    sealed interface Row {
        /** One line of text in [style] at [size] px. */
        data class Text(val text: String, val style: Style, val size: Float, val align: Align) : Row
        data class Pair(val left: String, val right: String, val style: Style, val size: Float) : Row
        data class Qr(val data: String) : Row
        data object Divider : Row
        data object Blank : Row
    }

    fun layout(lines: List<PrintLine>, m: TextMeasurer): List<Row> = lines.flatMap { line ->
        when (line) {
            is PrintLine.LogoPlaceholder -> venueName(line.fallbackText, m)
            is PrintLine.Header ->
                if (line.exact) exact(line.text, Style.TITLE, Align.CENTER, m)
                else fit(line.text, Style.TITLE, Align.CENTER, m)
            is PrintLine.Text -> fit(line.text, Style.BODY, line.align, m)
            is PrintLine.KeyValue -> pair(line.left, line.right, if (line.emphasized) Style.BOLD else Style.BODY, m)
            is PrintLine.QrCode -> line.caption?.let { fit(it, Style.BODY, Align.CENTER, m) }.orEmpty() + Row.Qr(line.data)
            PrintLine.Divider -> listOf(Row.Divider)
            PrintLine.Blank -> listOf(Row.Blank)
        }
    }

    /** "Brand — Location": brand as the title, location a smaller bold line below. */
    fun venueName(name: String, m: TextMeasurer): List<Row> {
        val i = name.indexOf(VENUE_SEPARATOR)
        if (i < 0) return fit(name.trim(), Style.TITLE, Align.CENTER, m)
        val brand = name.substring(0, i).trim()
        val location = name.substring(i + VENUE_SEPARATOR.length).trim()
        return fit(brand, Style.TITLE, Align.CENTER, m) +
            if (location.isEmpty()) emptyList() else fit(location, Style.SUBTITLE, Align.CENTER, m)
    }

    private fun fits(text: String, style: Style, size: Float, m: TextMeasurer) =
        m.width(text, style, size) <= CONTENT

    /** Fit [text]: as-is, else wrap bilingual at " / ", else one step smaller, else word-wrap. */
    fun fit(text: String, style: Style, align: Align, m: TextMeasurer): List<Row> {
        val size = style.size
        if (fits(text, style, size, m)) return listOf(Row.Text(text, style, size, align))
        val halves = text.split(BILINGUAL_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        if (halves.size > 1) return halves.flatMap { fit(it, style, align, m) }
        val smaller = size * STEP
        if (fits(text, style, smaller, m)) return listOf(Row.Text(text, style, smaller, align))
        return wrapWords(text, style, align, m)
    }

    /** Greedy word wrap at the style's size; an over-wide word goes through [exact]. */
    private fun wrapWords(text: String, style: Style, align: Align, m: TextMeasurer): List<Row> {
        val rows = mutableListOf<Row>()
        var current = ""
        fun flush() { if (current.isNotEmpty()) rows += Row.Text(current, style, style.size, align); current = "" }
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            when {
                fits(candidate, style, style.size, m) -> current = candidate
                fits(word, style, style.size, m) -> { flush(); current = word }
                else -> { flush(); rows += exact(word, style, align, m) }
            }
        }
        flush()
        return rows
    }

    /**
     * Keep every character (a Wi-Fi password, an unbreakable word): shrink down
     * to [MIN_SIZE], then break between characters at that size.
     */
    fun exact(text: String, style: Style, align: Align, m: TextMeasurer): List<Row> {
        var size = style.size
        while (!fits(text, style, size, m) && size * STEP >= MIN_SIZE) size *= STEP
        if (fits(text, style, size, m)) return listOf(Row.Text(text, style, size, align))
        val rows = mutableListOf<Row>()
        var start = 0
        while (start < text.length) {
            var end = start + 1
            while (end < text.length && fits(text.substring(start, end + 1), style, size, m)) end++
            rows += Row.Text(text.substring(start, end), style, size, align)
            start = end
        }
        return rows
    }

    /**
     * Label + right-aligned value. Too wide: one step smaller; still too wide:
     * the label wraps and the value sits on the label's last line, or on a line
     * of its own when even that has no room.
     */
    fun pair(left: String, right: String, style: Style, m: TextMeasurer): List<Row> {
        val gap = m.width("  ", style, style.size)
        fun pairFits(l: String, size: Float) = m.width(l, style, size) + gap + m.width(right, style, size) <= CONTENT
        if (pairFits(left, style.size)) return listOf(Row.Pair(left, right, style, style.size))
        if (pairFits(left, style.size * STEP)) return listOf(Row.Pair(left, right, style, style.size * STEP))
        val wrapped = wrapWords(left, style, Align.LEFT, m).map { it as Row.Text }
        val last = wrapped.lastOrNull()
        return if (last != null && last.size == style.size && pairFits(last.text, style.size))
            wrapped.dropLast(1) + Row.Pair(last.text, right, style, style.size)
        else wrapped + fit(right, style, Align.RIGHT, m)
    }
}
