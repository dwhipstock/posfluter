package dev.dwhipstock.poscloud.menuprint

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** The fonts a printed menu embeds (OFL, in resources/menuprint/fonts; full Latin incl. ß, umlauts, accents, ê/ë). */
object MenuFonts {
    const val INTER = "inter"
    const val JAKARTA = "jakarta"
    const val BARLOW = "barlow"
    /** Heading families of the curated styles. */
    const val PLAYFAIR = "Playfair"
    const val AMATIC = "Amatic"
    const val FRAUNCES = "Fraunces"
    const val POPPINS = "Poppins"
    /** The family name the brand's body font is registered under. */
    const val BODY = "MenuBody"

    /** A portal brand font id (lib/brand/brand.ts BRAND_FONTS), else Inter. */
    fun brandFamily(id: String?): String = id?.trim()?.lowercase()?.takeIf { it in setOf(INTER, JAKARTA, BARLOW) } ?: INTER

    private val BODY_FILES = mapOf(
        INTER to listOf(400 to "Inter-Regular.ttf", 600 to "Inter-SemiBold.ttf", 700 to "Inter-Bold.ttf"),
        JAKARTA to listOf(400 to "PlusJakartaSans-Regular.ttf", 600 to "PlusJakartaSans-SemiBold.ttf", 700 to "PlusJakartaSans-Bold.ttf"),
        BARLOW to listOf(400 to "Barlow-Regular.ttf", 600 to "Barlow-SemiBold.ttf", 700 to "Barlow-Bold.ttf"),
    )
    private val HEADING_FILES = mapOf(
        PLAYFAIR to "PlayfairDisplay-Bold.ttf", AMATIC to "AmaticSC-Bold.ttf",
        FRAUNCES to "Fraunces-SemiBold.ttf", POPPINS to "Poppins-Bold.ttf",
    )

    private val cache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    fun bytes(file: String): ByteArray = cache.getOrPut(file) {
        MenuFonts::class.java.getResourceAsStream("/menuprint/fonts/$file")?.use { it.readBytes() }
            ?: error("font $file is missing from the build")
    }

    /** (family, weight, file) for the body font [brandFont] and every heading family. */
    fun all(brandFont: String): List<Triple<String, Int, String>> =
        BODY_FILES.getValue(brandFamily(brandFont)).map { (w, f) -> Triple(BODY, w, f) } +
            HEADING_FILES.map { (family, f) -> Triple(family, 700, f) }
}

/** Picture handling for the print: decode, crop, scale, blend into the page, JPEG out. Pure JDK (ImageIO, Java2D). */
object PrintImages {
    const val MAX_LOGO_BYTES = 2 * 1024 * 1024

    /** The portal's logo (a PNG / JPEG data URL), re-encoded as PNG at most 600 px a side; null if it isn't one. */
    fun logo(dataUrl: String?): ByteArray? {
        val raw = dataUrl?.trim() ?: return null
        val m = Regex("^data:image/(png|jpeg|jpg);base64,([A-Za-z0-9+/=\\s]+)$").find(raw) ?: return null
        if (m.groupValues[2].length > MAX_LOGO_BYTES * 4 / 3 + 8) return null
        val bytes = runCatching { Base64.getMimeDecoder().decode(m.groupValues[2]) }.getOrNull() ?: return null
        val img = decode(bytes) ?: return null
        if (img.width < 8 || img.height < 8) return null
        val scaled = fit(img, 600, 600, keepAlpha = true)
        return ByteArrayOutputStream().use { ImageIO.write(scaled, "png", it); it.toByteArray() }
    }

    fun decode(bytes: ByteArray): BufferedImage? = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()

    /** Scaled down (never up) to fit [maxW] × [maxH]. */
    fun fit(img: BufferedImage, maxW: Int, maxH: Int, keepAlpha: Boolean = false): BufferedImage {
        val s = minOf(1.0, maxW.toDouble() / img.width, maxH.toDouble() / img.height)
        return draw(img, (img.width * s).toInt().coerceAtLeast(1), (img.height * s).toInt().coerceAtLeast(1), 0, 0, img.width, img.height, keepAlpha)
    }

    /** Centre-cropped to [w]:[h] and scaled to at most [w] × [h]. */
    fun cover(img: BufferedImage, w: Int, h: Int): BufferedImage {
        val want = w.toDouble() / h
        val have = img.width.toDouble() / img.height
        val (cw, ch) = if (have > want) ((img.height * want).toInt() to img.height) else (img.width to (img.width / want).toInt())
        val sx = (img.width - cw) / 2; val sy = (img.height - ch) / 2
        val s = minOf(1.0, w.toDouble() / cw)
        return draw(img, (cw * s).toInt().coerceAtLeast(1), (ch * s).toInt().coerceAtLeast(1), sx, sy, cw, ch, false)
    }

    private fun draw(img: BufferedImage, w: Int, h: Int, sx: Int, sy: Int, sw: Int, sh: Int, keepAlpha: Boolean): BufferedImage {
        val out = BufferedImage(w, h, if (keepAlpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            if (!keepAlpha) { color = Color.WHITE; fillRect(0, 0, w, h) }
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            // big reductions in halving steps look sharper than one bicubic jump
            var src = img; var cx = sx; var cy = sy; var cw = sw; var ch = sh
            while (cw / 2 >= w * 2 && ch / 2 >= h * 2) {
                val half = BufferedImage(cw / 2, ch / 2, if (keepAlpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
                half.createGraphics().apply {
                    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    drawImage(src, 0, 0, cw / 2, ch / 2, cx, cy, cx + cw, cy + ch, null); dispose()
                }
                src = half; cx = 0; cy = 0; cw /= 2; ch /= 2
            }
            drawImage(src, 0, 0, w, h, cx, cy, cx + cw, cy + ch, null)
            dispose()
        }
        return out
    }

    /**
     * The art melted into a page of colour [bg]: the picture's own plain
     * ground is mapped onto the page colour (scaled toward white on a light
     * page, toward black on a dark one), then its edges fade into the page
     * ([feather] of the shorter side; [bottom] fades the lower part more,
     * for a header; [round] fades a spot illustration out in a circle).
     */
    fun meltInto(img: BufferedImage, bg: String, dark: Boolean, feather: Double, bottom: Double = feather, round: Boolean = false): BufferedImage {
        val (br, bgc, bb) = Contrast.rgb(bg)
        val w = img.width; val h = img.height
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val f = (minOf(w, h) * feather).coerceAtLeast(1.0)
        val fb = (minOf(w, h) * bottom).coerceAtLeast(1.0)
        // the picture's own ground (the median of its border) is mapped onto the page colour, so a cream
        // drawing on a slightly different cream doesn't sit in a visible box
        val (gr, gg, gb) = ground(img)
        fun mix(c: Int, k: Int, g: Int) = (if (dark) 255 - (255 - c) * (255 - k) / (255 - g).coerceAtLeast(1) else c * k / g.coerceAtLeast(1)).coerceIn(0, 255)
        for (y in 0 until h) for (x in 0 until w) {
            val p = img.getRGB(x, y)
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            var nr = mix(r, br, gr); var ng = mix(g, bgc, gg); var nb = mix(b, bb, gb)
            val edge = if (round) {
                // a spot illustration fades out in a circle: no square corners left on the page
                val rad = minOf(w, h) / 2.0; val d = kotlin.math.hypot(x - w / 2.0, y - h / 2.0)
                ((rad - d) / (rad * feather * 2)).coerceIn(0.0, 1.0)
            } else minOf(minOf(x, w - 1 - x) / f, y / f, (h - 1 - y) / fb).coerceIn(0.0, 1.0)
            val a = edge * edge * (3 - 2 * edge) // smoothstep
            nr = (br + (nr - br) * a).toInt(); ng = (bgc + (ng - bgc) * a).toInt(); nb = (bb + (nb - bb) * a).toInt()
            out.setRGB(x, y, (nr shl 16) or (ng shl 8) or nb)
        }
        return out
    }

    /** The median colour of the picture's outer 4% (its background, for art drawn on a plain ground). */
    fun ground(img: BufferedImage): Triple<Int, Int, Int> {
        val rs = mutableListOf<Int>(); val gs = mutableListOf<Int>(); val bs = mutableListOf<Int>()
        val band = maxOf(1, minOf(img.width, img.height) / 25)
        val step = maxOf(1, maxOf(img.width, img.height) / 200)
        fun take(x: Int, y: Int) { val p = img.getRGB(x, y); rs += (p shr 16) and 255; gs += (p shr 8) and 255; bs += p and 255 }
        for (x in 0 until img.width step step) for (d in 0 until band step maxOf(1, band / 3)) { take(x, d); take(x, img.height - 1 - d) }
        for (y in 0 until img.height step step) for (d in 0 until band step maxOf(1, band / 3)) { take(d, y); take(img.width - 1 - d, y) }
        fun med(l: MutableList<Int>) = l.sorted()[l.size / 2]
        return Triple(med(rs), med(gs), med(bs))
    }

    /** A JPEG of [img] at [quality] (0–1). */
    fun jpeg(img: BufferedImage, quality: Float = 0.84f): ByteArray {
        val rgb = if (img.type == BufferedImage.TYPE_INT_RGB) img else BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB).also {
            it.createGraphics().apply { color = Color.WHITE; fillRect(0, 0, img.width, img.height); composite = AlphaComposite.SrcOver; drawImage(img, 0, 0, null); dispose() }
        }
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            val p = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = quality }
            writer.write(null, IIOImage(rgb, null, null), p)
        }
        writer.dispose()
        return out.toByteArray()
    }

    fun png(img: BufferedImage): ByteArray = ByteArrayOutputStream().use { ImageIO.write(img, "png", it); it.toByteArray() }

    fun dataUrl(bytes: ByteArray, type: String) = "data:$type;base64," + Base64.getEncoder().encodeToString(bytes)
}
