package dev.dwhipstock.poscloud.menuprint

import com.openhtmltopdf.extend.FSSupplier
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import com.openhtmltopdf.util.XRLog
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Color
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

enum class Paper(val css: String, val widthIn: Double, val heightIn: Double) {
    LETTER("letter", 8.5, 11.0), A4("A4", 8.27, 11.69);

    val ratio: Double get() = widthIn / heightIn

    companion object {
        fun of(raw: String?) = if (raw?.trim()?.lowercase() == "a4") A4 else LETTER
    }
}

/** Everything one PDF is made from. Names, sizes and prices come from [catalog]; words from [plan]. */
class PrintDoc(
    val kind: MenuKind,
    val lang: String,
    val paper: Paper,
    val style: PrintStyle,
    val brand: PrintBrand,
    val storeName: String,
    val currency: String,
    val plan: MenuPlan,
    val catalog: PrintCatalog,
    /** The store's business moment the menu is printed for (TODAY's day and date). */
    val today: TodayRules.Moment,
    val showPhotos: Boolean,
    /** item id → its photo (the store's, or a stand-in the AI drew). */
    val photos: Map<String, ByteArray> = emptyMap(),
    /** art slot id ("hero", "background", "deco:<section>", "photo:<item>") → the AI's picture; missing = built-in art. */
    val art: Map<String, ArtPicture> = emptyMap(),
)

/**
 * The printed menu: XHTML (everything escaped) rendered by openhtmltopdf
 * with the embedded fonts, then the page colour / flyer background laid
 * under every page with PDFBox. Items and section heads never split across
 * a page; the flyer is shrunk until it fits one page.
 */
object MenuPdf {
    init { runCatching { XRLog.setLoggingEnabled(false) } }

    class Rendered(val pdf: ByteArray, val pages: Int)

    fun render(doc: PrintDoc): Rendered {
        val assets = Assets.of(doc)
        if (doc.kind == MenuKind.HIGHLIGHTS) {
            // one page when a little tightening gets it there
            val first = stamp(pdfOf(html(doc, assets, 1.0), doc), doc, assets)
            if (first.pages <= 1) return first
            for (scale in listOf(0.9, 0.8)) stamp(pdfOf(html(doc, assets, scale), doc), doc, assets).let { if (it.pages <= 1) return it }
            return first
        }
        if (doc.kind != MenuKind.FLYER) {
            // a menu that just spills onto one more page (the specials box, a last section) is tightened a little
            val first = stamp(pdfOf(html(doc, assets, 1.0), doc), doc, assets)
            if (first.pages <= 1) return first
            for (scale in listOf(0.93, 0.87)) stamp(pdfOf(html(doc, assets, scale), doc), doc, assets).let { if (it.pages < first.pages) return it }
            return first
        }
        // one page, whatever it takes: smaller type first, then fewer items
        var scale = 1.0
        var d = doc
        while (true) {
            val r = stamp(pdfOf(html(d, assets, scale), d), d, assets)
            if (r.pages <= 1) return r
            if (scale > 0.62) scale -= 0.1
            else {
                val ids = d.plan.itemIds
                if (ids.size <= 1) return r
                val keep = ids.dropLast(1).toSet()
                d = PrintDoc(d.kind, d.lang, d.paper, d.style, d.brand, d.storeName, d.currency,
                    d.plan.copy(sections = d.plan.sections.map { s -> s.copy(itemIds = s.itemIds.filter { it in keep }) }.filter { it.itemIds.isNotEmpty() }),
                    d.catalog, d.today, d.showPhotos, d.photos, d.art)
            }
        }
    }

    /** JPEG pages for the preview: the first [max] at [dpi]. */
    fun previews(pdf: ByteArray, max: Int = 6, dpi: Float = 96f): List<String> = Loader.loadPDF(pdf).use { d ->
        val r = PDFRenderer(d)
        (0 until minOf(max, d.numberOfPages)).map { i ->
            PrintImages.dataUrl(PrintImages.jpeg(r.renderImageWithDPI(i, dpi, ImageType.RGB), 0.8f), "image/jpeg")
        }
    }

    private fun pdfOf(html: String, doc: PrintDoc): ByteArray {
        val out = ByteArrayOutputStream()
        val b = PdfRendererBuilder()
        b.useFastMode()
        for ((family, weight, file) in MenuFonts.all(doc.brand.font)) {
            b.useFont(FSSupplier { ByteArrayInputStream(MenuFonts.bytes(file)) }, family, weight, BaseRendererBuilder.FontStyle.NORMAL, true)
        }
        b.withHtmlContent(html, null)
        b.toStream(out)
        b.run()
        return out.toByteArray()
    }

    /** The page colour and the flyer's background under every page. */
    private fun stamp(pdf: ByteArray, doc: PrintDoc, assets: Assets): Rendered = Loader.loadPDF(pdf).use { d ->
        val white = doc.style.bg.equals("#FFFFFF", ignoreCase = true)
        if (white && assets.background == null) return Rendered(pdf, d.numberOfPages)
        val bg = assets.background?.let { JPEGFactory.createFromByteArray(d, it) }
        val (r, g, b) = Contrast.rgb(doc.style.bg)
        for (page in d.pages) {
            val box = page.mediaBox
            PDPageContentStream(d, page, PDPageContentStream.AppendMode.PREPEND, true).use { cs ->
                cs.setNonStrokingColor(Color(r, g, b))
                cs.addRect(0f, 0f, box.width, box.height); cs.fill()
                bg?.let { cs.drawImage(it, 0f, 0f, box.width, box.height) }
            }
        }
        val out = ByteArrayOutputStream()
        d.save(out)
        Rendered(out.toByteArray(), d.numberOfPages)
    }

    // --- the pictures, ready to embed ---

    private class Assets(
        val hero: String?, val heroBuiltIn: Boolean, val decos: Map<String, String>, val ornament: String,
        val photos: Map<String, String>, val background: ByteArray?,
    ) {
        companion object {
            fun of(doc: PrintDoc): Assets {
                val st = doc.style
                // a wide, low header: the menu starts on the first page, not under a picture
                val heroAspect = when (doc.kind) { MenuKind.HIGHLIGHTS -> 4.4; else -> 4.4 }
                val heroPic = doc.art["hero"]?.let { PrintImages.decode(it.bytes) }
                val hero = when {
                    doc.kind == MenuKind.FLYER -> null
                    heroPic != null -> PrintImages.dataUrl(PrintImages.jpeg(
                        PrintImages.meltInto(PrintImages.cover(heroPic, 1800, (1800 / heroAspect).toInt()), st.bg, st.dark, 0.05, 0.28), 0.85f), "image/jpeg")
                    else -> PrintImages.dataUrl(BuiltInArt.hero(st), "image/png")
                }
                val decos = doc.art.filterKeys { it.startsWith("deco:") }.mapNotNull { (k, pic) ->
                    PrintImages.decode(pic.bytes)?.let { img ->
                        k.removePrefix("deco:") to PrintImages.dataUrl(PrintImages.jpeg(PrintImages.meltInto(PrintImages.cover(img, 360, 360), st.bg, st.dark, 0.22, round = true), 0.85f), "image/jpeg")
                    }
                }.toMap()
                val thumb = if (doc.kind == MenuKind.HIGHLIGHTS) 900 to 580 else 300 to 300
                val photos = if (!doc.showPhotos) emptyMap() else doc.plan.itemIds.mapNotNull { id ->
                    doc.photos[id]?.let(PrintImages::decode)?.let { id to PrintImages.dataUrl(PrintImages.jpeg(PrintImages.cover(it, thumb.first, thumb.second), 0.82f), "image/jpeg") }
                }.toMap()
                val pw = 1650; val ph = (pw / doc.paper.ratio).toInt()
                val background = if (doc.kind != MenuKind.FLYER) null else
                    doc.art["background"]?.let { PrintImages.decode(it.bytes) }?.let { PrintImages.jpeg(PrintImages.cover(it, pw, ph), 0.8f) }
                        ?: BuiltInArt.background(st, 1275, (1275 / doc.paper.ratio).toInt())
                return Assets(hero, heroPic == null, decos, PrintImages.dataUrl(BuiltInArt.ornament(st), "image/png"), photos, background)
            }
        }
    }

    // --- the page ---

    private val XML_BAD = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\uFFFE\\uFFFF]")

    /** Text for XHTML: control characters out, & < > " ' escaped. */
    fun esc(s: String?): String = (s ?: "").replace(XML_BAD, "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    private fun money(doc: PrintDoc, cents: Long) = esc(PrintWords.money(cents, doc.currency))

    private fun css(doc: PrintDoc, scale: Double): String {
        val s = doc.style
        val head = s.headingFont?.let { "'$it', '${MenuFonts.BODY}'" } ?: "'${MenuFonts.BODY}'"
        fun pt(v: Double) = "%.1fpt".format(java.util.Locale.US, v * scale)
        val flyer = doc.kind == MenuKind.FLYER
        val upper = if (s.headingUpper) "text-transform: uppercase; letter-spacing: 0.04em;" else ""
        // Amatic is a narrow display face: it needs a larger size to read like the others
        val headBoost = if (s.headingFont == MenuFonts.AMATIC) 1.35 else 1.0
        return """
            @page { size: ${doc.paper.css}; margin: ${if (flyer) "0.5in" else "0.55in 0.62in 0.7in 0.62in"};
              ${if (flyer) "" else "@bottom-center { content: counter(page) \" / \" counter(pages); font-family: '${MenuFonts.BODY}'; font-size: 7.5pt; color: ${s.muted}; }"} }
            body { font-family: '${MenuFonts.BODY}'; color: ${s.ink}; font-size: ${pt(10.0)}; line-height: 1.32; margin: 0; }
            .head { text-align: center; }
            .logo { max-height: ${if (flyer) "0.9in" else "0.78in"}; max-width: 2.6in; }
            .wordmark { font-family: $head; font-weight: 700; font-size: ${pt(15.0 * headBoost)}; color: ${s.heading}; $upper }
            .venue { font-size: ${pt(8.0)}; letter-spacing: 0.16em; text-transform: uppercase; color: ${s.muted}; font-weight: 600; margin-top: 3pt; }
            h1 { font-family: $head; font-weight: 700; font-size: ${pt((if (flyer) 40.0 else 26.0) * headBoost)}; line-height: 1.08; color: ${s.heading};
                 margin: ${if (flyer) "10pt" else "4pt"} 0 2pt 0; $upper }
            .dateline { font-size: ${pt(9.0)}; color: ${s.accent}; font-weight: 700; letter-spacing: 0.12em; text-transform: uppercase; margin-top: 2pt; }
            .tagline { font-size: ${pt(if (flyer) 13.5 else 11.0)}; color: ${s.muted}; margin: 4pt 0.5in 0 0.5in; }
            .hero { margin: 6pt ${"%.1f".format(java.util.Locale.US, (1 - scale) * 50)}% 0 ${"%.1f".format(java.util.Locale.US, (1 - scale) * 50)}%; }
            .hero img { width: 100%; }
            .band { margin-top: 6pt; }
            .band img { width: 100%; }
            .section { margin-top: ${pt(10.0)}; }
            .keep { page-break-inside: avoid; }
            .sec-head { text-align: center; margin-bottom: 3pt; }
            table.sh { margin: 0 auto; border-collapse: collapse; }
            td.shp { width: 0.5in; vertical-align: middle; text-align: center; padding: 0; }
            td.sht { vertical-align: middle; padding: 0 6pt; }
            .deco { height: 0.44in; width: 0.44in; }
            .orn { height: 0.2in; width: 0.2in; }
            h2 { font-family: $head; font-weight: 700; font-size: ${pt((if (flyer) 21.0 else 15.0) * headBoost)}; color: ${s.heading}; margin: 0; line-height: 1.15; $upper }
            .rule { border-bottom: 1pt solid ${s.rule}; width: 1.4in; margin: 2pt auto 0 auto; }
            .intro { color: ${s.muted}; font-size: ${pt(if (flyer) 12.5 else 9.0)}; margin: 2pt 0.6in 0 0.6in; }
            .when { color: ${s.accent}; font-weight: 700; font-size: ${pt(12.5)}; margin-top: 3pt; }
            .item { page-break-inside: avoid; padding: ${pt(3.0)} 0 ${pt(3.0)} 0; }
            table.row { width: 100%; border-collapse: collapse; }
            td { padding: 0; vertical-align: top; }
            td.thumb { width: 0.68in; padding-right: 8pt; }
            img.thumb { width: 0.64in; height: 0.64in; }
            td.price { text-align: right; white-space: nowrap; font-weight: 700; padding-left: 10pt; width: ${if (flyer) "1.25in" else "0.95in"}; font-size: ${pt(if (flyer) 16.0 else 10.5)}; }
            .name { font-weight: 600; font-size: ${pt(if (flyer) 16.0 else 10.8)}; }
            .tag { font-size: ${pt(7.4)}; font-weight: 700; color: ${s.accent}; text-transform: uppercase; letter-spacing: 0.06em; }
            .desc { color: ${s.muted}; font-size: ${pt(if (flyer) 11.5 else 9.0)}; margin-top: 1pt; }
            .sizes { font-size: ${pt(9.2)}; margin-top: 1.5pt; }
            .sizes b { font-weight: 700; }
            .note { font-size: ${pt(if (flyer) 12.0 else 8.8)}; color: ${s.accent}; font-weight: 700; margin-top: 1.5pt; }
            .was { text-decoration: line-through; color: ${s.muted}; font-weight: 400; }
            .box { border: 1.2pt solid ${s.rule}; padding: 8pt 12pt; margin-top: 12pt; page-break-inside: avoid; }
            .box h3 { font-family: $head; font-weight: 700; font-size: ${pt(13.0 * headBoost)}; color: ${s.heading}; margin: 0 0 4pt 0; text-align: center; $upper }
            .box .grp { font-weight: 700; color: ${s.accent}; font-size: ${pt(8.8)}; margin-top: 5pt; text-transform: uppercase; letter-spacing: 0.05em; }
            .box .line { font-size: ${pt(9.3)}; }
            .footer { text-align: center; margin-top: 12pt; color: ${s.muted}; font-size: ${pt(9.0)}; page-break-inside: avoid; }
            table.grid { width: 100%; border-collapse: collapse; table-layout: fixed; margin-top: ${pt(8.0)}; }
            td.cell { width: 33.3%; padding: 0 5pt ${pt(12.0)} 5pt; page-break-inside: avoid; }
            td.cell2 { width: 50%; padding: 0 8pt ${pt(14.0)} 8pt; page-break-inside: avoid; }
            .cell img.ph { width: 100%; }
            .cell .name, .cell2 .name { margin-top: 4pt; }
            .card { border-top: 2pt solid ${s.rule}; padding-top: 6pt; }
            .panel { background-color: ${s.panel}; padding: 0.32in 0.4in; margin: 0.42in 0.42in 0 0.42in; }
        """.trimIndent()
    }

    fun html(doc: PrintDoc, scale: Double = 1.0): String = html(doc, Assets.of(doc), scale)

    private fun html(doc: PrintDoc, a: Assets, scale: Double): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"").append(esc(doc.lang)).append("\"><head><meta charset=\"UTF-8\"/>")
        append("<title>").append(esc(listOfNotNull(doc.plan.title, doc.brand.name ?: doc.storeName).joinToString(" · "))).append("</title>")
        append("<style>").append(css(doc, scale)).append("</style></head><body>")
        val flyer = doc.kind == MenuKind.FLYER
        if (flyer) append("<div class=\"panel\">")
        header(doc, a)
        when (doc.kind) {
            MenuKind.HIGHLIGHTS -> highlights(doc, a)
            else -> for ((i, s) in doc.plan.sections.withIndex()) section(doc, a, s, i)
        }
        if (doc.kind == MenuKind.FULL || doc.kind == MenuKind.DRINKS) specialsBox(doc)
        doc.plan.footer?.let { append("<div class=\"footer\">").append(esc(it)).append("</div>") }
        if (flyer) append("</div>")
        append("</body></html>")
    }

    private fun StringBuilder.header(doc: PrintDoc, a: Assets) {
        append("<div class=\"head\">")
        val brandName = doc.brand.name
        if (doc.brand.logo != null) append("<img class=\"logo\" alt=\"\" src=\"").append(PrintImages.dataUrl(doc.brand.logo, "image/png")).append("\"/>")
        else append("<div class=\"wordmark\">").append(esc(brandName ?: doc.storeName)).append("</div>")
        // the store's own name under the logo / brand, unless it is just the brand's name again
        val wordmark = if (doc.brand.logo != null) null else brandName ?: doc.storeName
        if (!doc.storeName.equals(wordmark, ignoreCase = true))
            append("<div class=\"venue\">").append(esc(doc.storeName)).append("</div>")
        append("<h1>").append(esc(doc.plan.title)).append("</h1>")
        val kindTitle = PrintWords.title(doc.kind, doc.lang)
        val dateline = when (doc.kind) {
            MenuKind.TODAY -> (if (kindTitle.equals(doc.plan.title, ignoreCase = true)) "" else "$kindTitle · ") + PrintWords.date(doc.today.date, doc.lang)
            else -> kindTitle.takeUnless { it.equals(doc.plan.title, ignoreCase = true) }
        }
        dateline?.let { append("<div class=\"dateline\">").append(esc(it)).append("</div>") }
        doc.plan.tagline?.let { append("<div class=\"tagline\">").append(esc(it)).append("</div>") }
        append("</div>")
        a.hero?.let { append(if (a.heroBuiltIn) "<div class=\"band\">" else "<div class=\"hero\">").append("<img alt=\"\" src=\"").append(it).append("\"/></div>") }
    }

    private fun StringBuilder.section(doc: PrintDoc, a: Assets, s: PlanSection, index: Int) {
        val items = s.itemIds.mapNotNull { doc.catalog.item(it) }
        if (items.isEmpty()) return
        append("<div class=\"section\"><div class=\"keep\">")
        if (s.title.isNotBlank() || s.intro != null) {
            append("<div class=\"sec-head\">")
            if (doc.kind == MenuKind.FLYER) {
                if (s.title.isNotBlank()) append("<h2>").append(esc(s.title)).append("</h2>")
            } else {
                // the section's picture sits beside its title (one line, not a stack): picture · title · picture's twin space
                val deco = a.decos[ArtPrompts.decoId(s)]
                val pic = if (deco != null) "<img class=\"deco\" alt=\"\" src=\"$deco\"/>" else "<img class=\"orn\" alt=\"\" src=\"${a.ornament}\"/>"
                append("<table class=\"sh\"><tr><td class=\"shp\">").append(pic).append("</td><td class=\"sht\">")
                if (s.title.isNotBlank()) append("<h2>").append(esc(s.title)).append("</h2>")
                append("</td><td class=\"shp\"></td></tr></table>")
                append("<div class=\"rule\"></div>")
            }
            s.intro?.let { append(if (doc.kind == MenuKind.FLYER) "<div class=\"when\">" else "<div class=\"intro\">").append(esc(it)).append("</div>") }
            append("</div>")
        }
        // the heading travels with its first item: never alone at a page's foot
        val sectionWhen = s.intro.takeIf { doc.kind == MenuKind.FLYER }
        item(doc, a, items.first(), sectionWhen)
        append("</div>")
        for (i in items.drop(1)) item(doc, a, i, sectionWhen)
        append("</div>")
    }

    /** One item: photo, name (+ day-only / special tag), blurb, sizes or price, special notes. */
    private fun StringBuilder.item(doc: PrintDoc, a: Assets, i: PrintItem, sectionWhen: String? = null) {
        val p = prices(doc, i, sectionWhen)
        append("<div class=\"item\"><table class=\"row\"><tr>")
        a.photos[i.id]?.let { append("<td class=\"thumb\"><img class=\"thumb\" alt=\"\" src=\"").append(it).append("\"/></td>") }
        append("<td class=\"main\"><div class=\"name\">").append(esc(i.name))
        p.tags.forEach { append(" &#160;<span class=\"tag\">").append(esc(it)).append("</span>") }
        append("</div>")
        blurb(doc, i)?.let { append("<div class=\"desc\">").append(esc(it)).append("</div>") }
        p.sizes?.let { append("<div class=\"sizes\">").append(it).append("</div>") }
        p.notes.forEach { append("<div class=\"note\">").append(it).append("</div>") }
        append("</td><td class=\"price\">").append(p.price ?: "").append("</td></tr></table></div>")
    }

    private fun blurb(doc: PrintDoc, i: PrintItem): String? =
        doc.plan.blurbs[i.id] ?: i.description.takeIf { it.isNotBlank() }?.let { PrintAi.clamp(it, 200) }

    /** The item's price column / sizes line / tags / notes, as escaped HTML. */
    private class Prices(val price: String?, val sizes: String?, val tags: List<String>, val notes: List<String>)

    private fun strike(doc: PrintDoc, was: Long, now: Long) = "<span class=\"was\">${money(doc, was)}</span> ${money(doc, now)}"

    /** [sectionWhen]: on a flyer, when the section's special runs (said once in its heading, not on every item). */
    private fun prices(doc: PrintDoc, i: PrintItem, sectionWhen: String? = null): Prices {
        val tags = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val multi = i.sizes.size > 1
        val day = doc.today.day
        if (doc.kind != MenuKind.TODAY && i.availableDays.isNotEmpty()) tags += PrintWords.onlyOn(i.availableDays, doc.lang)
        // what each size shows: its price, maybe struck through for today's all-day special
        val shown = i.sizes.map { size ->
            when (doc.kind) {
                MenuKind.TODAY -> {
                    val today = TodayRules.specialsOn(i.specials, size.id, size.cents, day)
                    val allDay = today.firstOrNull { it.from == null }
                    today.filter { it.from != null }.forEach { sp ->
                        notes += esc(PrintWords.specialName(sp.label, sp.days, sp.from, doc.lang) + " " + PrintWords.window(sp.from!!, sp.to!!, doc.lang) +
                            " · " + (if (multi) size.label + " " else "")) + strike(doc, size.cents, sp.prices.getValue(size.id))
                    }
                    if (allDay != null) {
                        PrintWords.specialName(allDay.label, allDay.days, null, doc.lang).let { if (it !in tags) tags += it }
                        size to strike(doc, size.cents, allDay.prices.getValue(size.id))
                    } else size to money(doc, size.cents)
                }
                MenuKind.FLYER -> {
                    PrintSelect.realSpecials(i).filter { (it.prices[size.id] ?: Long.MAX_VALUE) < size.cents }.forEach { sp ->
                        val w = PrintWords.whenText(sp.days, sp.from, sp.to, doc.lang).takeUnless { it == sectionWhen }
                        notes += esc(listOfNotNull(w, size.label.takeIf { multi }).joinToString(" · ") + " ") +
                            strike(doc, size.cents, sp.prices.getValue(size.id))
                    }
                    size to money(doc, size.cents)
                }
                else -> size to money(doc, size.cents)
            }
        }
        if (!multi) {
            // a flyer's one special price is the price
            val flyerPrice = if (doc.kind == MenuKind.FLYER && notes.size == 1) {
                val sp = PrintSelect.realSpecials(i).first { (it.prices[i.sizes[0].id] ?: Long.MAX_VALUE) < i.sizes[0].cents }
                notes.clear()
                PrintWords.whenText(sp.days, sp.from, sp.to, doc.lang).takeUnless { it == sectionWhen }?.let { notes += esc(it) }
                strike(doc, i.sizes[0].cents, sp.prices.getValue(i.sizes[0].id))
            } else null
            return Prices(flyerPrice ?: shown.single().second, null, tags, notes)
        }
        val line = shown.joinToString(" &#160;·&#160; ") { (s, m) -> esc(s.label) + " <b>" + m + "</b>" }
        // a flyer item with special prices shows just those
        return Prices(null, line.takeUnless { doc.kind == MenuKind.FLYER && notes.isNotEmpty() }, tags, notes)
    }

    /** FULL / DRINKS: every special on the printed items, by special (its name and when it runs). */
    private fun StringBuilder.specialsBox(doc: PrintDoc) {
        val printed = doc.plan.itemIds.mapNotNull { doc.catalog.item(it) }
        val groups = LinkedHashMap<String, MutableList<String>>()
        for (i in printed) for (sp in PrintSelect.realSpecials(i)) {
            val head = PrintWords.specialName(sp.label, sp.days, sp.from, doc.lang) + " · " + PrintWords.whenText(sp.days, sp.from, sp.to, doc.lang)
            for (size in i.sizes) {
                val p = sp.prices[size.id] ?: continue
                if (p >= size.cents) continue
                groups.getOrPut(head) { mutableListOf() } +=
                    esc(i.name + if (i.sizes.size > 1) " (${size.label})" else "") + " &#160;" + strike(doc, size.cents, p)
            }
        }
        if (groups.isEmpty()) return
        append("<div class=\"box\"><h3>").append(esc(PrintWords.specials(doc.lang))).append("</h3>")
        for ((head, lines) in groups) {
            append("<div class=\"grp\">").append(esc(head)).append("</div>")
            lines.forEach { append("<div class=\"line\">").append(it).append("</div>") }
        }
        append("</div>")
    }

    /** HIGHLIGHTS: a grid of cards (3 across with photos, 2 without). */
    private fun StringBuilder.highlights(doc: PrintDoc, a: Assets) {
        val items = doc.plan.itemIds.mapNotNull { doc.catalog.item(it) }
        if (items.isEmpty()) return
        val photos = items.any { a.photos[it.id] != null }
        val cols = if (photos) 3 else 2
        doc.plan.sections.firstOrNull()?.let { s ->
            if (s.title.isNotBlank()) append("<div class=\"sec-head\" style=\"margin-top: 12pt\"><h2>").append(esc(s.title)).append("</h2></div>")
            s.intro?.let { append("<div class=\"sec-head\"><div class=\"intro\">").append(esc(it)).append("</div></div>") }
        }
        append("<table class=\"grid\">")
        for (row in items.chunked(cols)) {
            append("<tr>")
            for (i in row) {
                val p = prices(doc, i)
                append("<td class=\"").append(if (cols == 3) "cell" else "cell2").append("\">")
                a.photos[i.id]?.let { append("<img class=\"ph\" alt=\"\" src=\"").append(it).append("\"/>") } ?: append("<div class=\"card\"></div>")
                append("<table class=\"row\"><tr><td><div class=\"name\">").append(esc(i.name)).append("</div>")
                p.tags.forEach { append("<div class=\"tag\">").append(esc(it)).append("</div>") }
                append("</td><td class=\"price\" style=\"width: auto\"><div class=\"name\">").append(p.price ?: "").append("</div></td></tr></table>")
                blurb(doc, i)?.let { append("<div class=\"desc\">").append(esc(it)).append("</div>") }
                p.sizes?.let { append("<div class=\"sizes\">").append(it).append("</div>") }
                append("</td>")
            }
            repeat(cols - row.size) { append("<td class=\"").append(if (cols == 3) "cell" else "cell2").append("\"></td>") }
            append("</tr>")
        }
        append("</table>")
    }
}
