package dev.dwhipstock.poscloud.menuprint

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.GeneralPath
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Each style's own decorations, drawn by code: what a printed menu wears
 * when there is no AI artwork (no image service here, or it failed, refused
 * or ran out of time). Flat shapes in the style's own colours, so a menu
 * without AI art still looks designed. Deterministic (seeded): the same
 * style always draws the same art.
 */
object BuiltInArt {
    private fun color(hex: String, alpha: Int = 255) = Contrast.rgb(hex).let { (r, g, b) -> Color(r, g, b, alpha) }

    private fun canvas(w: Int, h: Int, bg: String?): Pair<BufferedImage, Graphics2D> {
        val img = BufferedImage(w, h, if (bg == null) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        if (bg != null) { g.color = color(bg); g.fillRect(0, 0, w, h) }
        return img to g
    }

    /** A thin header band under the title (transparent PNG), [w] × [h]. */
    fun hero(style: PrintStyle, w: Int = 1800, h: Int = 150): ByteArray {
        val (img, g) = canvas(w, h, null)
        val mid = h / 2.0
        val rule = color(style.rule); val accent = color(style.accent)
        when (style.key) {
            PrintStyles.CLASSIC -> {
                g.color = rule; g.stroke = BasicStroke(3f)
                g.draw(Line2D.Double(w * 0.08, mid - 7, w * 0.44, mid - 7)); g.draw(Line2D.Double(w * 0.56, mid - 7, w * 0.92, mid - 7))
                g.stroke = BasicStroke(1.5f)
                g.draw(Line2D.Double(w * 0.12, mid + 7, w * 0.44, mid + 7)); g.draw(Line2D.Double(w * 0.56, mid + 7, w * 0.88, mid + 7))
                g.color = color(style.heading); diamond(g, w / 2.0, mid, 26.0)
                g.color = rule; diamond(g, w * 0.47, mid, 9.0); diamond(g, w * 0.53, mid, 9.0)
            }
            PrintStyles.CHALKBOARD -> {
                val r = Random(7)
                g.color = color(style.muted, 200); g.stroke = BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                chalkLine(g, w * 0.06, mid, w * 0.94, mid, r)
                g.color = accent
                for (x in listOf(0.2, 0.5, 0.8)) star(g, w * x, mid, 22.0, r)
            }
            PrintStyles.AUTUMN -> {
                val r = Random(11)
                val palette = listOf("#B5541C", "#8A3A10", "#C98A2B", "#6F7A2E", "#9C2F2F").map { color(it, 225) }
                g.color = rule; g.stroke = BasicStroke(2f)
                g.draw(Line2D.Double(w * 0.06, mid, w * 0.94, mid))
                for (i in 0 until 9) {
                    g.color = palette[i % palette.size]
                    leaf(g, w * (0.12 + i * 0.095) + r.nextDouble(-18.0, 18.0), mid + r.nextDouble(-22.0, 22.0), 30.0 + r.nextDouble(0.0, 14.0), r.nextDouble(0.0, 2 * PI))
                }
            }
            PrintStyles.SUMMER -> {
                g.color = color(style.rule); g.stroke = BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                wave(g, w * 0.05, w * 0.42, mid, 10.0); wave(g, w * 0.58, w * 0.95, mid, 10.0)
                sun(g, w / 2.0, mid, 30.0, color(style.rule), color(style.accent))
            }
            else -> {
                g.color = rule; g.stroke = BasicStroke(2f)
                g.draw(Line2D.Double(w * 0.1, mid, w * 0.46, mid)); g.draw(Line2D.Double(w * 0.54, mid, w * 0.9, mid))
                g.color = accent
                for (dx in listOf(-24.0, 0.0, 24.0)) g.fill(Ellipse2D.Double(w / 2.0 + dx - 5, mid - 5, 10.0, 10.0))
            }
        }
        g.dispose()
        return PrintImages.png(img)
    }

    /** A section's small ornament (transparent PNG, square). */
    fun ornament(style: PrintStyle, size: Int = 220): ByteArray {
        val (img, g) = canvas(size, size, null)
        val c = size / 2.0
        when (style.key) {
            PrintStyles.CLASSIC -> {
                g.color = color(style.rule); g.stroke = BasicStroke(4f)
                g.draw(Ellipse2D.Double(c - 70, c - 70, 140.0, 140.0))
                g.color = color(style.heading); diamond(g, c, c, 46.0)
                g.color = color(style.rule); diamond(g, c - 70, c, 12.0); diamond(g, c + 70, c, 12.0)
            }
            PrintStyles.CHALKBOARD -> {
                g.color = color(style.accent); g.stroke = BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                star(g, c, c, 70.0, Random(3))
            }
            PrintStyles.AUTUMN -> {
                g.color = color("#B5541C"); leaf(g, c - 18, c + 8, 78.0, -0.6)
                g.color = color("#C98A2B"); leaf(g, c + 26, c - 10, 64.0, 0.7)
            }
            PrintStyles.SUMMER -> sun(g, c, c, 52.0, color(style.rule), color(style.accent))
            else -> {
                g.color = color(style.accent)
                g.fill(Ellipse2D.Double(c - 18, c - 18, 36.0, 36.0))
                g.color = color(style.rule); g.stroke = BasicStroke(4f)
                g.draw(Ellipse2D.Double(c - 60, c - 60, 120.0, 120.0))
            }
        }
        g.dispose()
        return PrintImages.png(img)
    }

    /** A flyer's page background (JPEG, the page's size): the style's colour with decorations around the edges. */
    fun background(style: PrintStyle, w: Int, h: Int): ByteArray {
        val (img, g) = canvas(w, h, style.bg)
        val r = Random(23)
        val m = w * 0.045
        when (style.key) {
            PrintStyles.CHALKBOARD -> {
                // chalk dust, then a hand-drawn double frame and stars in the corners
                for (i in 0 until 2600) {
                    g.color = Color(255, 255, 255, r.nextInt(6, 22))
                    val s = r.nextDouble(1.0, 4.0)
                    g.fill(Ellipse2D.Double(r.nextDouble(0.0, w.toDouble()), r.nextDouble(0.0, h.toDouble()), s, s))
                }
                g.color = color(style.muted, 210); g.stroke = BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                chalkRect(g, m, m, w - 2 * m, h - 2 * m, r)
                g.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                chalkRect(g, m * 1.5, m * 1.5, w - 3 * m, h - 3 * m, r)
                g.color = color(style.accent)
                for ((x, y) in corners(w, h, m * 2.4)) star(g, x, y, m * 0.55, r)
            }
            PrintStyles.AUTUMN -> {
                val palette = listOf("#B5541C", "#8A3A10", "#C98A2B", "#6F7A2E", "#9C2F2F").map { color(it, 215) }
                for (i in 0 until 70) {
                    val (x, y) = edgePoint(w, h, r, 0.13)
                    g.color = palette[i % palette.size]
                    leaf(g, x, y, w * r.nextDouble(0.03, 0.06), r.nextDouble(0.0, 2 * PI))
                }
            }
            PrintStyles.SUMMER -> {
                g.color = color(style.rule, 90)
                g.fillRect(0, 0, w, (h * 0.05).toInt()); g.fillRect(0, (h * 0.95).toInt(), w, h)
                for ((x, y) in corners(w, h, m * 2.2)) sun(g, x, y, m * 0.7, color(style.rule), color(style.accent))
                g.color = color(style.heading, 120); g.stroke = BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                wave(g, m * 3.6, w - m * 3.6, h * 0.075, 12.0); wave(g, m * 3.6, w - m * 3.6, h * 0.925, 12.0)
            }
            PrintStyles.CLASSIC -> {
                g.color = color(style.rule); g.stroke = BasicStroke(6f)
                g.drawRect(m.toInt(), m.toInt(), (w - 2 * m).toInt(), (h - 2 * m).toInt())
                g.stroke = BasicStroke(2f)
                g.drawRect((m * 1.4).toInt(), (m * 1.4).toInt(), (w - 2.8 * m).toInt(), (h - 2.8 * m).toInt())
                g.color = color(style.heading)
                for ((x, y) in corners(w, h, m * 1.4)) diamond(g, x, y, m * 0.35)
            }
            else -> {
                g.color = color(style.heading); g.fillRect(0, 0, w, (h * 0.018).toInt())
                g.color = color(style.accent); g.fillRect(0, h - (h * 0.018).toInt(), w, h)
            }
        }
        g.dispose()
        return PrintImages.jpeg(img, 0.85f)
    }

    // --- shapes ---

    private fun corners(w: Int, h: Int, inset: Double) = listOf(inset to inset, w - inset to inset, inset to h - inset, w - inset to h - inset)

    private fun edgePoint(w: Int, h: Int, r: Random, band: Double): Pair<Double, Double> {
        val bw = w * band; val bh = h * band * 0.75
        return when (r.nextInt(4)) {
            0 -> r.nextDouble(0.0, w.toDouble()) to r.nextDouble(0.0, bh)
            1 -> r.nextDouble(0.0, w.toDouble()) to r.nextDouble(h - bh, h.toDouble())
            2 -> r.nextDouble(0.0, bw) to r.nextDouble(0.0, h.toDouble())
            else -> r.nextDouble(w - bw, w.toDouble()) to r.nextDouble(0.0, h.toDouble())
        }
    }

    private fun diamond(g: Graphics2D, x: Double, y: Double, s: Double) {
        val p = Path2D.Double(); p.moveTo(x, y - s); p.lineTo(x + s * 0.7, y); p.lineTo(x, y + s); p.lineTo(x - s * 0.7, y); p.closePath(); g.fill(p)
    }

    private fun leaf(g: Graphics2D, x: Double, y: Double, len: Double, angle: Double) {
        val p = GeneralPath()
        p.moveTo(0.0, -len / 2); p.quadTo(len * 0.42, -len * 0.1, 0.0, len / 2); p.quadTo(-len * 0.42, -len * 0.1, 0.0, -len / 2)
        val old = g.transform
        g.transform(AffineTransform.getTranslateInstance(x, y)); g.rotate(angle)
        g.fill(p)
        val c = g.color
        g.color = Color(255, 255, 255, 90); g.stroke = BasicStroke((len / 26).toFloat())
        g.draw(Line2D.Double(0.0, -len * 0.42, 0.0, len * 0.62))
        g.color = c
        g.transform = old
    }

    private fun star(g: Graphics2D, x: Double, y: Double, r: Double, rnd: Random) {
        val p = Path2D.Double()
        for (i in 0 until 10) {
            val a = -PI / 2 + i * PI / 5
            val rr = (if (i % 2 == 0) r else r * 0.45) * rnd.nextDouble(0.92, 1.08)
            if (i == 0) p.moveTo(x + cos(a) * rr, y + sin(a) * rr) else p.lineTo(x + cos(a) * rr, y + sin(a) * rr)
        }
        p.closePath()
        g.stroke = BasicStroke((r / 9).toFloat().coerceAtLeast(2f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(p)
    }

    private fun sun(g: Graphics2D, x: Double, y: Double, r: Double, ray: Color, core: Color) {
        g.color = ray; g.stroke = BasicStroke((r / 6).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.CAP_ROUND)
        for (i in 0 until 12) {
            val a = i * PI / 6
            g.draw(Line2D.Double(x + cos(a) * r * 1.25, y + sin(a) * r * 1.25, x + cos(a) * r * 1.7, y + sin(a) * r * 1.7))
        }
        g.color = ray; g.fill(Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r))
        g.color = core; g.fill(Ellipse2D.Double(x - r * 0.45, y - r * 0.45, r * 0.9, r * 0.9))
    }

    private fun wave(g: Graphics2D, x0: Double, x1: Double, y: Double, amp: Double) {
        val p = Path2D.Double(); p.moveTo(x0, y)
        var x = x0; var up = true
        val step = amp * 4
        while (x < x1) { val nx = minOf(x + step, x1); p.quadTo((x + nx) / 2, if (up) y - amp * 2 else y + amp * 2, nx, y); x = nx; up = !up }
        g.draw(p)
    }

    private fun chalkLine(g: Graphics2D, x0: Double, y0: Double, x1: Double, y1: Double, r: Random) {
        val p = Path2D.Double(); p.moveTo(x0, y0)
        val n = 24
        for (i in 1..n) { val t = i / n.toDouble(); p.lineTo(x0 + (x1 - x0) * t + r.nextDouble(-2.0, 2.0), y0 + (y1 - y0) * t + r.nextDouble(-2.5, 2.5)) }
        g.draw(p)
    }

    private fun chalkRect(g: Graphics2D, x: Double, y: Double, w: Double, h: Double, r: Random) {
        chalkLine(g, x, y, x + w, y, r); chalkLine(g, x + w, y, x + w, y + h, r)
        chalkLine(g, x + w, y + h, x, y + h, r); chalkLine(g, x, y + h, x, y, r)
    }
}
