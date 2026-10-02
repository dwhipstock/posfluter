package dev.dwhipstock.poscloud.menuprint

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The printed menu's looks: a short curated set, each a fixed palette, a
 * font pairing and its decorations, so whatever the AI picks the page looks
 * designed. The AI only names one of [KEYS]; it never gives a colour.
 *
 * Every colour is checked against its page (and the flyer's text panel) when
 * the style is built: body text at least 7:1, everything else 4.5:1
 * ([Contrast.ensure] darkens or lightens what falls short), so a dark style
 * gets light text and a light one never does. "modern" wears the client's
 * own brand colours and font (the portal's brand pack).
 */
data class PrintStyle(
    val key: String,
    /** Page background. */
    val bg: String,
    /** Body text (item names). */
    val ink: String,
    /** Secondary text (descriptions, notes): a dark neutral on light pages. */
    val muted: String,
    /** Headings (title, sections). */
    val heading: String,
    /** Tags, special prices, small accents. */
    val accent: String,
    /** Rules and frames (decoration only, never text). */
    val rule: String,
    /** The solid panel text sits on over a flyer's artwork. */
    val panel: String,
    /** Heading font family ([MenuFonts]); null = the brand's own font. */
    val headingFont: String?,
    val headingUpper: Boolean,
    /** A dark page (light text): the art is drawn light on dark too. */
    val dark: Boolean,
    /** How the AI artwork is drawn (for the image prompt; English). */
    val art: String,
    /** The plain background the art is drawn on (so it melts into the page). */
    val artGround: String,
    /** One line for the AI choosing a style. */
    val describe: String,
) {
    /** The same style with every text colour readable on [bg] and on [panel]. */
    fun readable(): PrintStyle = copy(
        ink = Contrast.ensure(Contrast.ensure(ink, bg, 7.0), panel, 7.0),
        muted = Contrast.ensure(Contrast.ensure(muted, bg, 4.8), panel, 4.8),
        heading = Contrast.ensure(Contrast.ensure(heading, bg, 4.5), panel, 4.5),
        accent = Contrast.ensure(Contrast.ensure(accent, bg, 4.5), panel, 4.5),
    )
}

object PrintStyles {
    const val CLASSIC = "classic"
    const val MODERN = "modern"
    const val CHALKBOARD = "chalkboard"
    const val AUTUMN = "autumn"
    const val SUMMER = "summer"
    val KEYS = listOf(CLASSIC, MODERN, CHALKBOARD, AUTUMN, SUMMER)

    private val CLASSIC_STYLE = PrintStyle(
        key = CLASSIC, bg = "#F7F1E3", ink = "#221A12", muted = "#4A3F33", heading = "#5B1A18", accent = "#7A4E0E",
        rule = "#B89B6A", panel = "#F7F1E3", headingFont = MenuFonts.PLAYFAIR, headingUpper = false, dark = false,
        art = "vintage engraved pen-and-ink illustration with fine cross-hatching, sepia and dark brown ink, classic British pub style",
        artGround = "plain warm cream paper",
        describe = "classic pub: cream paper, oxblood serif headings, engraved vintage illustrations",
    )
    private val CHALKBOARD_STYLE = PrintStyle(
        key = CHALKBOARD, bg = "#23292C", ink = "#F4F1EA", muted = "#D9D4C9", heading = "#FFFFFF", accent = "#F2C14E",
        rule = "#7E888C", panel = "#23292C", headingFont = MenuFonts.AMATIC, headingUpper = true, dark = true,
        art = "hand-drawn white and pastel chalk drawing on a dark slate chalkboard, loose sketchy chalk strokes",
        artGround = "plain dark slate-grey chalkboard",
        describe = "chalkboard: dark slate page, light chalk lettering and chalk drawings (daily boards, happy hour)",
    )
    private val AUTUMN_STYLE = PrintStyle(
        key = AUTUMN, bg = "#FBF3E6", ink = "#2A1C12", muted = "#57412F", heading = "#8A3A10", accent = "#6E4A0E",
        rule = "#C99A63", panel = "#FBF3E6", headingFont = MenuFonts.FRAUNCES, headingUpper = false, dark = false,
        art = "warm loose watercolour illustration in rust, amber, burgundy and olive tones, autumn harvest mood",
        artGround = "plain warm off-white paper",
        describe = "seasonal autumn: warm cream page, rust serif headings, watercolour leaves and harvest art",
    )
    private val SUMMER_STYLE = PrintStyle(
        key = SUMMER, bg = "#FFFDF6", ink = "#10292E", muted = "#2F4B4D", heading = "#0B6468", accent = "#A33A12",
        rule = "#F0B43C", panel = "#FFFDF6", headingFont = MenuFonts.POPPINS, headingUpper = true, dark = false,
        art = "bright cheerful flat vector illustration with bold shapes in teal, coral, sunny yellow and leafy green, summer patio mood",
        artGround = "plain white",
        describe = "bright summer / patio: crisp light page, teal and coral, sunny flat illustrations",
    )

    /** The default palette when the portal sends no brand (a neutral house look). */
    private val NEUTRAL = PrintBrand(name = null, primary = "#1F3A4D", accent = "#8A4B12", text = "#1A1A1A", muted = "#3D3D3D",
        font = MenuFonts.INTER, logo = null)

    /** "modern": the client's brand colours and font on a white page. */
    fun modern(brand: PrintBrand): PrintStyle = PrintStyle(
        key = MODERN, bg = "#FFFFFF", ink = brand.text ?: NEUTRAL.text!!, muted = brand.muted ?: NEUTRAL.muted!!,
        heading = brand.primary ?: NEUTRAL.primary!!, accent = brand.accent ?: NEUTRAL.accent!!,
        rule = brand.primary ?: NEUTRAL.primary!!, panel = "#FFFFFF", headingFont = null, headingUpper = false, dark = false,
        art = "minimal modern single-line art illustration, thin even black line with one or two flat soft colour accents, lots of white space",
        artGround = "plain white",
        describe = "modern minimal: white page, the client's own brand colours and font, fine line art",
    )

    fun of(key: String, brand: PrintBrand): PrintStyle = when (key) {
        CLASSIC -> CLASSIC_STYLE
        CHALKBOARD -> CHALKBOARD_STYLE
        AUTUMN -> AUTUMN_STYLE
        SUMMER -> SUMMER_STYLE
        else -> modern(brand)
    }.readable()

    /** The list the AI chooses from (one line each). */
    fun menuForAi(): String = KEYS.joinToString("\n") { k -> "- $k: " + of(k, NEUTRAL).describe }

    /** Who chose the look. */
    enum class By(val code: String) { MANAGER("manager"), AI("ai"), NOTES("notes"), DEFAULT("default") }

    private val NOTE_WORDS = listOf(
        AUTUMN to Regex("""\b(fall|autumn|harvest|thanksgiving|halloween|pumpkin|october|november|automne|récolte|herbst|ernte|otoño|cosecha|herfs|oes)\b"""),
        SUMMER to Regex("""\b(summer|patio|terrace|sunny|sun|beach|bbq|barbecue|été|terrasse|soleil|sommer|terrasse|sonne|verano|terraza|somer|stoep)\b"""),
        CHALKBOARD to Regex("""\b(chalk|chalkboard|blackboard|board|rustic|craie|ardoise|tafel|kreide|pizarra|tiza|bord|kryt)\b"""),
        CLASSIC to Regex("""\b(classic|traditional|vintage|old[- ]school|heritage|pub|tavern|classique|traditionnel|klassisch|traditionell|clásico|tradicional|klassiek|tradisioneel)\b"""),
        MODERN to Regex("""\b(modern|minimal|minimalist|clean|simple|moderne|épuré|minimalistisch|schlicht|moderno|minimalista|modern|eenvoudig)\b"""),
    )

    /** The look for a print: the manager's pick, else the AI's, else what the notes say, else the menu kind's usual one. */
    fun choose(requested: String?, aiPick: String?, notes: String?, kind: MenuKind): Pair<String, By> {
        requested?.trim()?.lowercase()?.takeIf { it in KEYS }?.let { return it to By.MANAGER }
        aiPick?.trim()?.lowercase()?.takeIf { it in KEYS }?.let { return it to By.AI }
        val folded = notes?.lowercase().orEmpty()
        NOTE_WORDS.firstOrNull { (_, re) -> re.containsMatchIn(folded) }?.let { return it.first to By.NOTES }
        return defaultFor(kind) to By.DEFAULT
    }

    fun defaultFor(kind: MenuKind): String = when (kind) {
        MenuKind.TODAY, MenuKind.FLYER -> CHALKBOARD
        MenuKind.FULL, MenuKind.DRINKS, MenuKind.HIGHLIGHTS -> MODERN
    }

    // --- section illustrations: what a section is about, when the AI doesn't say ---

    private val MOTIFS = listOf(
        Regex("""\b(beers?|ales?|lagers?|ipas?|draft|draught|stouts?|bières?|biere|pression|biere|bier|biere|cervezas?|bier)\b""") to "hops and barley",
        Regex("""\b(wines?|vins?|wein|weine|vinos?|wyn)\b""") to "a wine glass and a bunch of grapes",
        Regex("""\b(cocktails?|spirits?|whiske?y|gin|rum|vodka|tequila|liquors?|spiritueux|spirituosen|licores|sterk drank)\b""") to "a cocktail glass with a citrus twist",
        Regex("""\b(coffee|café|cafés|kaffee|espresso|tea|thé|tee|té|koffie)\b""") to "a coffee cup with a curl of steam",
        Regex("""\b(soft drinks?|sodas?|juices?|mocktails?|non-alcoholic|sans alcool|alkoholfrei|refrescos|sin alcohol|koeldrank|sap)\b""") to "a glass of lemonade with a lemon slice",
        Regex("""\b(desserts?|sweets?|cakes?|pastr(y|ies)|douceurs|nachspeisen?|nachtisch|postres?|nageregte?)\b""") to "a slice of layered cake",
        Regex("""\b(starters?|appetizers?|apps|small plates|snacks?|sharing|to share|tapas|entrées|à partager|vorspeisen?|entrantes|voorgeregte?|happies)\b""") to "a wooden sharing board with small bites",
        Regex("""\b(salads?|salades?|salate?|ensaladas?|slaai)\b""") to "fresh salad leaves and herbs",
        Regex("""\b(soups?|soupes?|suppen?|sopas?|sop)\b""") to "a steaming bowl of soup",
        Regex("""\b(burgers?|hamburgers?)\b""") to "a burger",
        Regex("""\b(pizzas?)\b""") to "a pizza",
        Regex("""\b(pastas?|pâtes|nudeln)\b""") to "a bowl of pasta",
        Regex("""\b(fish|seafood|poissons?|fruits de mer|fisch|meeresfrüchte|pescados?|mariscos|vis|seekos)\b""") to "a fish and a lemon",
        Regex("""\b(mains?|main courses?|plats?|plats principaux|hauptgerichte?|platos principales|principales|hoofgeregte?|steaks?|grill|grills)\b""") to "a steak with rosemary",
        Regex("""\b(breakfast|brunch|petit[- ]déjeuner|frühstück|desayunos?|ontbyt)\b""") to "eggs and toast",
        Regex("""\b(sides?|accompagnements?|beilagen?|guarniciones|bygeregte?|fries|frites)\b""") to "a cone of fries",
        Regex("""\b(kids|children|enfants?|kinder|niños|kinders)\b""") to "a small toy sailboat",
    )

    /** The section's illustration subject from its category name (any of the portal's languages), or null. */
    fun motifFor(vararg names: String): String? {
        for (n in names) {
            val t = n.lowercase()
            MOTIFS.firstOrNull { (re, _) -> re.containsMatchIn(t) }?.let { return it.second }
        }
        return null
    }
}

/** The portal's brand, checked: colours #rrggbb or null, a known font, a short name, the logo decoded elsewhere. */
data class PrintBrand(
    val name: String?, val primary: String?, val accent: String?, val text: String?, val muted: String?,
    val font: String, val logo: ByteArray?,
) {
    companion object {
        private val HEX = Regex("^#[0-9a-fA-F]{6}$")
        fun of(input: PrintBrandInput?): PrintBrand {
            fun hex(v: String?) = v?.trim()?.takeIf { HEX.matches(it) }?.uppercase()
            val name = input?.name?.replace(Regex("[\\p{Cc}\\p{Cf}]"), "")?.replace(Regex("\\s+"), " ")?.trim()?.take(60)?.takeIf { it.isNotEmpty() }
            return PrintBrand(
                name = name, primary = hex(input?.primary), accent = hex(input?.accent), text = hex(input?.text),
                muted = hex(input?.muted), font = MenuFonts.brandFamily(input?.font), logo = PrintImages.logo(input?.logo),
            )
        }
    }
}

/** WCAG contrast. */
object Contrast {
    private fun channel(c: Int): Double = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }

    fun rgb(hex: String): Triple<Int, Int, Int> {
        val n = hex.removePrefix("#").toInt(16)
        return Triple((n shr 16) and 255, (n shr 8) and 255, n and 255)
    }

    fun hex(r: Int, g: Int, b: Int) = "#%02X%02X%02X".format(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))

    fun luminance(hex: String): Double = rgb(hex).let { (r, g, b) -> 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b) }

    fun ratio(a: String, b: String): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** [fg] moved toward black (on a light [bg]) or white (on a dark one) until it reads at [min]:1. */
    fun ensure(fg: String, bg: String, min: Double): String {
        if (ratio(fg, bg) >= min) return fg
        val towardWhite = luminance(bg) < 0.18
        val (r, g, b) = rgb(fg)
        for (step in 1..20) {
            val t = step / 20.0
            val c = if (towardWhite) hex((r + (255 - r) * t).toInt(), (g + (255 - g) * t).toInt(), (b + (255 - b) * t).toInt())
            else hex((r * (1 - t)).toInt(), (g * (1 - t)).toInt(), (b * (1 - t)).toInt())
            if (ratio(c, bg) >= min) return c
        }
        return if (towardWhite) "#FFFFFF" else "#000000"
    }
}
