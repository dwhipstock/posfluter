package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.escapeHtml

/**
 * The guest-facing pages in the store's brand skin, the way [StaffAppBrand]
 * dresses the staff web app: the scan-to-order menu (`/m/t/{token}`), its
 * calm "scan the code" / "section closed" pages, the printable table slips
 * and the pickup board. The pages keep their markup and script; a branded
 * store gets a skin class on `<body>` and a stylesheet of overrides. The
 * venue name already comes from the store's config ([dev.dwhipstock.pos.sdk.CustomerConfig.displayName]).
 *
 * Barcode Pub: the staff app's palette exactly. A warm near-black header
 * band (#1A1816) with the name in white and the sign's LED yellow
 * (#F7CD46, 11.6:1 on near-black) for the active tab; the menu cards, cart
 * and bill stay light for a phone in a dark bar, prices in near-black; the
 * active chip and the add / cart / send buttons are the sign yellow with
 * near-black ink (12.4:1), like the counter's Pay.
 *
 * Sage & Poppy: its staff app's sage colours and Plus Jakarta Sans (served
 * by the store under /staff-app/fonts, nothing from the internet).
 *
 * Any other brand (the pubs, Copper Lantern) gets the page unchanged.
 */
object GuestPageBrand {

    /** The customer menu page (customer-menu.html, after its placeholders are filled). */
    fun menu(html: String, brand: String): String = when (brand) {
        "barcode" -> skin(html, "brand-bc", BARCODE_MENU_CSS)
        "sage-poppy" -> skin(html, "brand-sp", SAGE_POPPY_MENU_CSS)
        else -> html
    }

    /** The small one-card pages: "please scan the code at your table", "this section is closed". */
    fun card(html: String, brand: String): String = when (brand) {
        "barcode" -> skin(html, "brand-bc", BARCODE_CARD_CSS)
        "sage-poppy" -> skin(html, "brand-sp", SAGE_POPPY_CARD_CSS)
        else -> html
    }

    /** The printable A6 table slips: the venue name in the brand's ink instead of copper. */
    fun slips(html: String, brand: String): String = when (brand) {
        "barcode" -> skin(html, "brand-bc", "<style>body.brand-bc .venue { color: #1a1816; }</style>")
        "sage-poppy" -> skin(html, "brand-sp", "<style>body.brand-sp .venue { color: #263d26; }</style>")
        else -> html
    }

    /** The pickup board for a TV (pickup.html): the venue's name and colours, not "Copper Lantern Express". */
    fun pickup(html: String, brand: String, venueName: String): String = when (brand) {
        "barcode" -> skin(
            html.replace("<h1 id=\"venue\">Copper Lantern <span>Express</span></h1>",
                "<h1 id=\"venue\">${venueName.escapeHtml()}</h1>")
                .replace("<title>Pickup board</title>", "<title>${venueName.escapeHtml()}</title>"),
            "brand-bc", BARCODE_PICKUP_CSS)
        else -> html
    }

    private fun skin(html: String, cls: String, css: String): String = html
        .replaceFirst("<body>", "<body class=\"$cls\">")
        .replaceFirst("</head>", css + "\n</head>")

    /**
     * Noto Sans Thai from the store (`/m/fonts`, the same files the receipt
     * printer uses), so Thai shows on any phone without the internet. The
     * unicode-range keeps it from downloading on a page with no Thai.
     */
    const val THAI_FONT_FACE = """  @font-face { font-family: 'Noto Sans Thai'; src: local('Noto Sans Thai'), url('/m/fonts/NotoSansThai-Regular.ttf') format('truetype');
               font-weight: 400 600; font-display: swap; unicode-range: U+0E00-0E7F; }
  @font-face { font-family: 'Noto Sans Thai'; src: local('Noto Sans Thai Bold'), url('/m/fonts/NotoSansThai-Bold.ttf') format('truetype');
               font-weight: 650 900; font-display: swap; unicode-range: U+0E00-0E7F; }"""

    private val BARCODE_MENU_CSS = """
<style>
  /* Barcode Pub guest skin (GuestPageBrand.kt) */
  body.brand-bc {
    --ink: #121110; --paper: #ffffff; --paper-alt: #f5f4f1; --surface-alt: #eceae5; --line: #d9d5ce;
    --muted: #57524b; --muted-strong: #45413b; --accent: #f7cd46; --accent-soft: #fdf3d0; --accent-ink: #121110;
    --bc-night: #1a1816; --bc-panel: #24221f; --bc-edge: #3a3733; --bc-yellow: #f7cd46; --bc-gold: #d9a92b;
  }
  body.brand-bc header { background: var(--bc-night); color: #ffffff; border-bottom: 0; }
  body.brand-bc header h1 { color: #ffffff; }
  body.brand-bc header .table { color: #b8b2a8; }
  body.brand-bc header .lang { background: var(--bc-panel); border-color: var(--bc-edge); color: #ffffff; }
  body.brand-bc .tabs { background: var(--bc-night); border-bottom: 0; }
  body.brand-bc .tabs button { color: #b8b2a8; }
  body.brand-bc .tabs button.on { color: var(--bc-yellow); border-bottom-color: var(--bc-yellow); }
  body.brand-bc .chips { background: rgba(245,244,241,.97); }
  body.brand-bc .card .price { color: var(--ink); }
  body.brand-bc .chip { border-color: var(--line); color: var(--ink); }
  body.brand-bc .chip.on { background: var(--bc-yellow); border-color: var(--bc-gold); color: var(--ink); }
  body.brand-bc .card .add, body.brand-bc .bar button, body.brand-bc .cfoot button {
    background: var(--bc-yellow); border-color: var(--bc-gold); color: var(--ink);
  }
  body.brand-bc .card, body.brand-bc .bsec { box-shadow: 0 3px 12px rgba(26,24,22,.07); }
  body.brand-bc .bar { box-shadow: 0 -4px 16px rgba(26,24,22,.12); }
  body.brand-bc .card .add:active, body.brand-bc .bar button:active, body.brand-bc .cfoot button:active { background: var(--bc-gold); }
  body.brand-bc .bline .note, body.brand-bc .brow .note, body.brand-bc .bempty { color: var(--muted); }
  body.brand-bc #done h2 { color: var(--ink); }
</style>"""

    private val BARCODE_CARD_CSS = """
<style>
  /* Barcode Pub guest skin (GuestPageBrand.kt) */
  body.brand-bc { background: #1a1816; color: #121110; }
  body.brand-bc .card { border-color: #3a3733; box-shadow: none; }
  body.brand-bc .venue, body.brand-bc .table { color: var(--ink); font-weight: 700; }
  body.brand-bc .en { color: #57524b; }
</style>"""

    private val BARCODE_PICKUP_CSS = """
<style>
  /* Barcode Pub pickup board (GuestPageBrand.kt) */
  body.brand-bc { --bg: #1a1816; --panel: #24221f; --ink: #ffffff; --muted: #b8b2a8; --copper: #f7cd46; --ready: #f7cd46; }
  body.brand-bc #ready .n { background: rgba(247,205,70,.16); }
  body.brand-bc #ready .n.fresh { background: var(--ready); color: #121110; }
</style>"""

    private val SAGE_POPPY_FONTS = """
  @font-face { font-family: 'SP Sans'; src: url('/staff-app/fonts/PlusJakartaSans-400.ttf') format('truetype'); font-weight: 400; font-display: swap; }
  @font-face { font-family: 'SP Sans'; src: url('/staff-app/fonts/PlusJakartaSans-700.ttf') format('truetype'); font-weight: 600 700; font-display: swap; }
  @font-face { font-family: 'SP Sans'; src: url('/staff-app/fonts/PlusJakartaSans-800.ttf') format('truetype'); font-weight: 750 900; font-display: swap; }
  body.brand-sp * { font-family: 'SP Sans', 'Noto Sans', 'Noto Sans Thai', system-ui, sans-serif; }"""

    private val SAGE_POPPY_MENU_CSS = """
<style>
  /* Sage & Poppy guest skin (GuestPageBrand.kt) */$SAGE_POPPY_FONTS
  body.brand-sp {
    --ink: #172016; --paper: #ffffff; --paper-alt: #f4f6f1; --surface-alt: #edf1e9; --line: #dfe5da;
    --muted: #586255; --muted-strong: #3f4a3c; --accent: #4a6b47; --accent-soft: #e5eee1; --accent-ink: #ffffff;
    --deep: #263d26; --poppy: #c2511a;
  }
  body.brand-sp header { background: var(--paper-alt); border-bottom: 0; }
  body.brand-sp header h1 { color: var(--deep); font-weight: 800; }
  body.brand-sp header .lang { border: 0; border-radius: 40px; background: var(--accent-soft); color: var(--deep); }
  body.brand-sp .tabs button.on { color: var(--deep); border-bottom-color: var(--accent); }
  body.brand-sp .chips { background: rgba(244,246,241,.97); }
  body.brand-sp .card, body.brand-sp .bsec { box-shadow: none; }
  body.brand-sp .bar { box-shadow: 0 -4px 16px rgba(23,32,22,.08); }
  body.brand-sp .card .add, body.brand-sp .bar button, body.brand-sp .cfoot button { border-radius: 40px; }
  body.brand-sp .bline .note, body.brand-sp .brow .note, body.brand-sp .bempty { color: var(--muted); }
</style>"""

    private val SAGE_POPPY_CARD_CSS = """
<style>
  /* Sage & Poppy guest skin (GuestPageBrand.kt) */$SAGE_POPPY_FONTS
  body.brand-sp { background: #f4f6f1; color: #172016; }
  body.brand-sp .card { border-color: #dfe5da; box-shadow: none; }
  body.brand-sp .venue, body.brand-sp .table { color: #4a6b47; font-weight: 700; }
  body.brand-sp .en { color: #586255; }
</style>"""
}
