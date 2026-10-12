package dev.dwhipstock.pos

/**
 * The staff web app (`/staff-app`) in the store's brand skin. The page is one
 * vanilla HTML file written for the pubs; a store with its own brand gets its
 * name, a skin class on `<body>` and a stylesheet of overrides added once at
 * startup, so the page, its script and its logic stay the same code.
 *
 * Sage & Poppy: its own type (Plus Jakarta Sans, served from the store —
 * nothing is fetched from the internet), bright sage-and-poppy colours, and
 * its own sign-in composition: a left-aligned greeting, staff as a list of
 * avatar pills, a flat round-key PIN pad and a segmented PIN indicator.
 * The page takes the store's languages from GET /health (English and Spanish
 * here), so the injected greeting carries ids the page fills per language.
 */
object StaffAppBrand {
    fun apply(html: String, brand: String): String = when (brand) {
        "sage-poppy" -> sagePoppy(html)
        "barcode" -> barcode(html)
        else -> html
    }

    /**
     * Barcode Pub: its name, a near-black header and sign-in (the neon
     * sign's #1E1E20) with light text and the sign's violet, on the page's
     * light working screens (the order and cart sheets stay light so every
     * line is readable on a phone in a dark bar). Violet text on white is a
     * deeper shade (#7E22CE, 7:1); the sign's bright violet (#D45CFF) only
     * sits on near-black (5.4:1). Hot pink is a spark: the dot in the name.
     */
    private fun barcode(html: String): String = html
        .replace("<title>Copper Lantern POS — Staff</title>", "<title>Barcode Pub — Staff</title>")
        .replace("<h1>Copper Lantern POS</h1>", "<h1>Barcode<span class=\"bc-dot\">.</span> Pub</h1>")
        .replace("<div class=\"brand\">Copper Lantern POS</div>", "<div class=\"brand\">Barcode<span class=\"bc-dot\">.</span> Pub</div>")
        .replace("<body>", "<body class=\"brand-bc\">")
        .replace("</head>", BARCODE_CSS + "\n</head>")

    private val BARCODE_CSS = """
<style>
  /* Barcode Pub skin (StaffAppBrand.kt) */
  body.brand-bc {
    --ink: #141414; --paper: #ffffff; --paper-alt: #f4f4f5; --surface-alt: #ececee; --line: #d4d4d8;
    --muted: #5c5c63; --muted-strong: #3f3f46; --accent: #7e22ce; --accent-soft: #f3e8ff; --accent-ink: #ffffff;
    --ok: #15803d; --danger: #b91c1c; --bc-night: #1e1e20; --bc-violet: #d45cff; --bc-pink: #ff1f8e;
  }
  body.brand-bc .hbtn { border-color: #d8b4fe; }
  body.brand-bc header { background: var(--bc-night); border-bottom: 0; }
  body.brand-bc header .brand { color: #ffffff; }
  body.brand-bc header .who { color: #b8b8b8; }
  body.brand-bc header .hbtn { background: #26262a; border-color: #3a3a3e; color: #ffffff; }
  body.brand-bc .bc-dot { color: var(--bc-pink); }
  body.brand-bc #login { background: var(--bc-night); }
  body.brand-bc #login h1 { color: #ffffff; font-size: 30px; letter-spacing: -.4px; }
  body.brand-bc #login .sub { color: #b8b8b8; }
  body.brand-bc #login .errline { color: #ff8a8a; }
  body.brand-bc #login .tile { background: #26262a; border-color: #3a3a3e; color: #ececef; }
  body.brand-bc #login .tile .rl { color: #b8b8b8; }
  body.brand-bc #login .tile.sel { border-color: var(--bc-violet); background: #2a1736; }
  body.brand-bc #login .pad button:active { background: #2a1736; }
  body.brand-bc #login .pad .blank { background: none; border: 0; }
  body.brand-bc #login .enroll-manual code { color: var(--ink); }
  body.brand-bc #login .pindot { border-color: #8a8a8a; }
  body.brand-bc #login .pindot.f { background: var(--bc-violet); border-color: var(--bc-violet); }
  body.brand-bc #login .pad button { background: #26262a; border-color: #3a3a3e; color: #ffffff; box-shadow: none; }
  body.brand-bc #login .code-prompt, body.brand-bc #login .enroll-hint, body.brand-bc #login .enroll-manual { color: #ececef; }
  body.brand-bc #login .linkbtn { color: #e08cff; }
  body.brand-bc #login .primary { background: var(--bc-violet); color: #0a0a0a; }
  body.brand-bc #login-lang { background: #26262a !important; color: #ffffff !important; border-color: #3a3a3e !important; }
</style>"""

    private fun sagePoppy(html: String): String = html
        .replace("<title>Copper Lantern POS — Staff</title>", "<title>Sage &amp; Poppy — Staff</title>")
        .replace("<h1>Copper Lantern POS</h1>", "<div class=\"sp-kicker\" id=\"sp-kicker\">Sage &amp; Poppy · Bottle Shop</div><h1 id=\"sp-welcome\">Welcome back</h1>")
        .replace("<div class=\"brand\">Copper Lantern POS</div>", "<div class=\"brand\">Sage &amp; Poppy</div>")
        .replace("<body>", "<body class=\"brand-sp\">")
        .replace("</head>", SAGE_POPPY_CSS + "\n</head>")

    private val SAGE_POPPY_CSS = """
<style>
  /* Sage & Poppy skin (StaffAppBrand.kt) */
  @font-face { font-family: 'SP Sans'; src: url('/staff-app/fonts/PlusJakartaSans-400.ttf') format('truetype'); font-weight: 400; font-display: swap; }
  @font-face { font-family: 'SP Sans'; src: url('/staff-app/fonts/PlusJakartaSans-700.ttf') format('truetype'); font-weight: 600 700; font-display: swap; }
  @font-face { font-family: 'SP Sans'; src: url('/staff-app/fonts/PlusJakartaSans-800.ttf') format('truetype'); font-weight: 750 900; font-display: swap; }
  body.brand-sp {
    --ink: #172016; --paper: #ffffff; --paper-alt: #f4f6f1; --surface-alt: #edf1e9; --line: #dfe5da;
    --muted: #586255; --muted-strong: #3f4a3c; --accent: #4a6b47; --accent-soft: #e5eee1; --accent-ink: #ffffff;
    --ok: #2b6a34; --danger: #b0261c; --deep: #263d26; --poppy: #c2511a; --poppy-soft: #fde9dc;
  }
  body.brand-sp * { font-family: 'SP Sans', 'Noto Sans', system-ui, sans-serif; }
  body.brand-sp header { border-bottom: 0; background: var(--paper-alt); }
  body.brand-sp header .brand { color: var(--deep); font-weight: 800; letter-spacing: -.2px; }
  body.brand-sp .hbtn { border: 0; border-radius: 40px; background: var(--accent-soft); color: var(--deep); }
  body.brand-sp .tabbar { box-shadow: none; }
  body.brand-sp .tabbar button.on { color: var(--deep); }
  body.brand-sp .tcard, body.brand-sp .tile { box-shadow: none; }

  /* sign-in: a left-aligned stack, not the pubs' centred tiles */
  body.brand-sp #login { align-items: stretch; justify-content: flex-start; max-width: 420px; margin: 0 auto;
    padding-top: calc(56px + env(safe-area-inset-top)); background: var(--paper-alt); position: relative; overflow: hidden; }
  body.brand-sp #login::before { content: ""; position: fixed; right: -90px; top: -110px; width: 280px; height: 280px;
    border-radius: 50%; background: var(--accent-soft); z-index: 0; }
  body.brand-sp #login::after { content: ""; position: fixed; left: -70px; bottom: -90px; width: 200px; height: 200px;
    border-radius: 50%; background: var(--poppy-soft); z-index: 0; }
  body.brand-sp #login > * { position: relative; z-index: 1; }
  body.brand-sp .sp-kicker { color: var(--poppy); font-size: 13px; font-weight: 800; letter-spacing: 1.6px; text-transform: uppercase; }
  body.brand-sp #login h1 { color: var(--deep); font-size: 34px; font-weight: 800; letter-spacing: -.6px; margin-top: 6px; }
  body.brand-sp #login .sub { margin: 4px 0 22px; font-size: 15px; }
  body.brand-sp .tiles { flex-direction: column; max-width: none; gap: 8px; justify-content: flex-start; }
  body.brand-sp .tile { width: 100%; display: grid; grid-template-columns: 44px 1fr; column-gap: 12px; align-items: center;
    text-align: left; border: 0; border-radius: 40px; padding: 6px 18px 6px 6px; background: var(--paper); }
  body.brand-sp .tile .ic { grid-row: 1 / span 2; width: 44px; height: 44px; border-radius: 50%; background: var(--accent-soft);
    display: grid; place-items: center; font-size: 18px; }
  body.brand-sp .tile .nm { margin: 0; font-weight: 700; font-size: 15px; align-self: end; }
  body.brand-sp .tile .rl { align-self: start; text-transform: lowercase; }
  body.brand-sp .tile .rl::first-letter { text-transform: uppercase; }
  body.brand-sp .tile.sel { background: var(--deep); color: #fff; }
  body.brand-sp .tile.sel .rl { color: #d8e6d4; }
  body.brand-sp #login-pin { display: flex; flex-direction: column; align-items: center; width: 100%; }
  body.brand-sp .tiles { align-self: stretch; width: 100%; }
  body.brand-sp .pinrow { gap: 8px; height: 12px; margin: 10px 0 8px; align-items: center; justify-content: center; }
  body.brand-sp .pindot { width: 22px; height: 8px; border-radius: 8px; border: 0; background: var(--line); transition: width .12s; }
  body.brand-sp .pindot.f { width: 32px; background: var(--accent); }
  body.brand-sp .pad { gap: 14px 22px; justify-content: center; }
  body.brand-sp .pad button { border: 0; background: var(--surface-alt); box-shadow: none; font-weight: 700; font-size: 28px; }
  body.brand-sp .pad button:active { background: var(--accent-soft); }
  body.brand-sp .pad .blank { background: none; }
  body.brand-sp .code-input:focus { border-color: var(--accent); }
</style>"""
}
