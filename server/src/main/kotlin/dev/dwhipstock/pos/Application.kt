package dev.dwhipstock.pos

import dev.dwhipstock.pos.api.ManagerApprovalException
import dev.dwhipstock.pos.api.authRoutes
import dev.dwhipstock.pos.api.catalogRoutes
import dev.dwhipstock.pos.api.cloudRoutes
import dev.dwhipstock.pos.api.customerRoutes
import dev.dwhipstock.pos.api.tableLinkRoutes
import dev.dwhipstock.pos.api.SlipTicketException
import dev.dwhipstock.pos.api.portalUrlFrom
import dev.dwhipstock.pos.api.installAuthGate
import dev.dwhipstock.pos.api.pairingRoutes
import dev.dwhipstock.pos.api.PairingService
import dev.dwhipstock.pos.api.floorObjectRoutes
import dev.dwhipstock.pos.api.photoRoutes
import dev.dwhipstock.pos.api.aiPhotoRoutes
import dev.dwhipstock.pos.api.posRoutes
import dev.dwhipstock.pos.api.retailRoutes
import dev.dwhipstock.pos.api.stockRoutes
import dev.dwhipstock.pos.api.settingsRoutes
import dev.dwhipstock.pos.api.shiftRoutes
import dev.dwhipstock.pos.api.staffAdminRoutes
import dev.dwhipstock.pos.api.tableRoutes
import dev.dwhipstock.pos.api.zoneManagementRoutes
import dev.dwhipstock.pos.base.AuthService
import dev.dwhipstock.pos.base.RateLimitException
import dev.dwhipstock.pos.base.SettingsRepository
import dev.dwhipstock.pos.restaurant.ShiftService
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternConfig
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternSeed
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternVenue
import dev.dwhipstock.pos.customers.sagepoppy.SagePoppy
import dev.dwhipstock.pos.customers.sagepoppy.SagePoppyConfig
import dev.dwhipstock.pos.customers.sagepoppy.SagePoppySeed
import dev.dwhipstock.pos.db.initDatabase
import dev.dwhipstock.pos.restaurant.CheckService
import dev.dwhipstock.pos.sdk.FilesystemPhotoStore
import dev.dwhipstock.pos.sdk.PhotoStore
import dev.dwhipstock.pos.sdk.PrinterAdapter
import dev.dwhipstock.pos.sdk.NetworkThermalPrinter
import dev.dwhipstock.pos.sdk.PrinterTarget
import dev.dwhipstock.pos.sdk.ReceiptPrintMode
import dev.dwhipstock.pos.sdk.StripeConfig
import dev.dwhipstock.pos.sdk.StoreProfile
import dev.dwhipstock.pos.payments.StripeException
import dev.dwhipstock.pos.payments.StripeHttp
import dev.dwhipstock.pos.payments.StripeService
import dev.dwhipstock.pos.api.stripeRoutes
import dev.dwhipstock.pos.api.printerRoutes
import dev.dwhipstock.pos.api.kitchenRoutes
import dev.dwhipstock.pos.restaurant.BadRequestException
import dev.dwhipstock.pos.restaurant.ConflictException
import dev.dwhipstock.pos.restaurant.NotFoundException
import dev.dwhipstock.pos.sync.CloudSync
import dev.dwhipstock.pos.sync.HttpCloudTransport
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/**
 * Primary non-loopback IPv4 — the address venue phones can reach when the
 * tablet and the guests share the venue wifi. Null on airplane-mode dev boxes.
 */
fun detectLanIpv4(): String? = runCatching {
    java.net.NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<java.net.Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }?.hostAddress
}.getOrNull()

fun Application.module(
    dbPath: String = System.getenv("POS_DB") ?: "pos.db",
    receiptsDir: String = System.getenv("POS_RECEIPTS_DIR") ?: "receipts",
    billsDir: String = System.getenv("POS_BILLS_DIR") ?: "bills",
    photosDir: String = System.getenv("POS_PHOTOS_DIR") ?: "data/photos",
    // test seams (env-driven in production): device gate + a fake pairing transport + seed mode
    requireDeviceTokenOverride: Boolean? = null,
    pairingTransport: dev.dwhipstock.pos.sync.CloudTransport? = null,
    seedMode: String = System.getenv("POS_SEED") ?: "copperlantern",
    // which store this is (POS_VENUE=vieux-port|plateau|sage-poppy): its config,
    // display name and first-boot seed. sage-poppy is the US retail store.
    venueId: String? = System.getenv("POS_VENUE"),
    venue: CopperLanternVenue = if (SagePoppy.matches(venueId)) CopperLanternVenue.VIEUX_PORT else CopperLanternVenue.of(venueId),
    sagePoppy: Boolean = SagePoppy.matches(venueId),
    // retail: optional online name lookup for unknown barcodes (test seam)
    productLookup: dev.dwhipstock.pos.retail.ProductLookup? = null,
    // retail: minimum age for age-restricted items. The tablet passes its
    // store.properties `legal.age`; desktop reads POS_LEGAL_AGE. Unset → the
    // store's own default (21 for the US store).
    legalAgeOverride: Int? = null,
    cloudSyncUrl: String? = System.getenv("CLOUD_SYNC_URL"),
    cloudSyncApiKey: String? = System.getenv("CLOUD_SYNC_API_KEY"),
    publicUrl: String? = System.getenv("POS_PUBLIC_URL"),
    // the port this store listens on, for the auto-detected LAN address (table
    // QRs, /cloud/info, the portal heartbeat) when publicUrl is unset. The
    // tablet passes its build's port (8080 Copper Lantern, 8082 Sage & Poppy).
    lanPort: Int = System.getenv("POS_PORT")?.toIntOrNull() ?: 8080,
    reportingPortalUrl: String? = System.getenv("REPORTING_PORTAL_URL"),
    physicalPrinterEnabled: Boolean = true,
    // staff.app.mfa=on|off (POS_STAFF_APP_MFA / POS_CONFIG_FILE; the tablet
    // passes its store.properties). Default on; off = staff-app sign-in by PIN only.
    staffAppMfa: dev.dwhipstock.pos.sdk.StaffAppMfa.Resolved = dev.dwhipstock.pos.sdk.StaffAppMfa.fromEnv(),
    // print.receipts=paper|digital (POS_PRINT_RECEIPTS / POS_CONFIG_FILE; the
    // tablet passes its store.properties). Local config only, never the network.
    receiptPrintMode: ReceiptPrintMode.Resolved = ReceiptPrintMode.fromEnv(),
    // cash.rounding=nickel|off (POS_CASH_ROUNDING / POS_CONFIG_FILE; the tablet
    // passes its store.properties). Default nickel for every store.
    cashRounding: dev.dwhipstock.pos.sdk.CashRounding.Resolved = dev.dwhipstock.pos.sdk.CashRounding.fromEnv(),
    // Optional Stripe card tender, TEST MODE only (STRIPE_KEY / stripe.secretKey;
    // the tablet passes its store.properties). No key or a non-sk_test_ key →
    // disabled. Never contacted at startup on the request path.
    stripeConfig: StripeConfig.Resolved = StripeConfig.fromEnv(),
    // test seam: a fake Stripe HTTP layer
    stripeHttp: StripeHttp? = null,
    // AI menu photos (paid add-on): image.generation=on|off + image.provider=
    // flux|gemini|openai|off + the provider's key (POS_IMAGE_* / BFL_API_KEY /
    // GEMINI_API_KEY / OPENAI_API_KEY; the tablet passes its store.properties).
    // Default off. Online-only; never contacted at startup or on a selling path.
    imageGenConfig: dev.dwhipstock.pos.sdk.ImageGenConfig.Resolved = dev.dwhipstock.pos.sdk.ImageGenConfig.fromEnv(),
    // test seams: a fake image provider and the online probe
    imageProvider: dev.dwhipstock.pos.aiphotos.ImageProvider? = null,
    imageReachable: ((String) -> Boolean)? = null,
    // kitchen.printing=on|off (POS_KITCHEN_PRINTING / POS_CONFIG_FILE; the
    // tablet passes its store.properties). Default off; restaurants only.
    kitchenPrinting: dev.dwhipstock.pos.sdk.KitchenPrinting.Resolved = dev.dwhipstock.pos.sdk.KitchenPrinting.fromEnv(),
    // test seam: the station printers' transport
    kitchenTransport: dev.dwhipstock.pos.sdk.EscPosTransport? = null,
) {
    // a brand-new store starts in its own zone when VENUE_TZ is unset (Los
    // Angeles for the US store); an existing store keeps its settings row's
    dev.dwhipstock.pos.sdk.VenueClock.fallbackZone =
        if (sagePoppy) SagePoppy.TIME_ZONE else dev.dwhipstock.pos.sdk.VenueClock.DEFAULT_ZONE
    initDatabase(dbPath)
    // discover i18n message catalogs now so missing-key warnings surface at
    // boot, not on the first printed receipt
    dev.dwhipstock.pos.sdk.i18n.Messages.ensureLoaded()
    // POS_SEED=none starts a store with an EMPTY menu and floor plan, which the
    // owner builds on the tablet (sync is one-way: the tablet owns its menu and
    // staff; anything present at first sync is pushed UP by the bootstrap
    // snapshots). Skipping CopperLanternSeed is not enough: migrations
    // 005/008/011/… INSERT the CopperLantern menu + floor plan directly (they
    // mirror the seed for existing installs), so a never-synced empty-mode store
    // also wipes that residue. Gate = no install_id yet: once a store has synced,
    // its data is never touched here again.
    if (seedMode != "none") {
        if (sagePoppy) SagePoppySeed.seedIfEmpty() else CopperLanternSeed.seedIfEmpty(venue)
        // a store seeded with an older shelf adds the rest of the current
        // catalog, once, without touching anything already there
        if (sagePoppy) dev.dwhipstock.pos.customers.sagepoppy.SagePoppyCatalogUpgrade.upgradeIfNeeded()
    } else {
        wipeMigrationSeedResidueIfNeverSynced()
        // nobody could ever sign in to an empty store (staff no longer arrive
        // from the cloud), so it gets ONE bootstrap manager — PIN 1234, to be
        // changed on first sign-in
        if (sagePoppy) SagePoppySeed.seedBootstrapManagerIfNoStaff() else CopperLanternSeed.seedBootstrapManagerIfNoStaff()
    }
    // every table must have its customer link token (033); covers any row a
    // raw-SQL path wrote without one
    org.jetbrains.exposed.sql.transactions.transaction { dev.dwhipstock.pos.restaurant.TableTokens.ensureAll() }
    // pre-M5 databases carry plaintext PINs — hash them in place, once
    AuthService.upgradePlaintextPins()
    // seed the default role→grant matrix (CONTRACT §7) if absent — covers existing
    // stores too (migration 024 only creates the table). Cloud edits override it.
    dev.dwhipstock.pos.base.GrantsRepo.seedDefaultRoleGrantsIfEmpty()
    // Customer tier: Copper Lantern, the store picked by POS_VENUE.
    // POS_PUBLIC_URL wins; otherwise auto-detect the LAN address so printed
    // table QRs work out of the box at the venue.
    val publicBaseUrl = publicUrl
        ?: detectLanIpv4()?.let { "http://$it:$lanPort" }
        ?: "http://192.168.1.100:$lanPort"
    val settingsRepo = SettingsRepository()
    // Real ESC/POS network printer, wrapping the virtual printer for the audit
    // spool + receipt.printed outbox (unchanged), then also pushing a French-capable
    // raster to the physical printer. Target IP/port read live from venue settings
    // so a DHCP change applies without a restart; sends are async/non-blocking so
    // an offline printer never blocks or rolls back a sale.
    receiptPrintMode.warning?.let { log.warn("Receipt printing config ignored: $it") }
    log.info("Receipt printing: ${receiptPrintMode.mode.wire} (${receiptPrintMode.source})" +
        if (receiptPrintMode.mode == ReceiptPrintMode.DIGITAL) " — receipts/bills saved digitally only; manual prints still use paper" else "")
    val thermalPrinter = NetworkThermalPrinter(
        audit = PrinterAdapter.VirtualPrinter(receiptsDir, billsDir),
        receiptMode = receiptPrintMode.mode,
        target = {
            if (physicalPrinterEnabled) settingsRepo.get().let { PrinterTarget(it.printerIp, it.printerPort) }
            else PrinterTarget("", 9100)
        },
    )
    val publicUrlProvider = {
        publicUrl ?: detectLanIpv4()?.let { "http://$it:$lanPort" } ?: publicBaseUrl
    }
    val config: dev.dwhipstock.pos.sdk.CustomerConfig = if (sagePoppy) SagePoppyConfig(
        settings = settingsRepo,
        printer = thermalPrinter,
        publicBaseUrl = publicBaseUrl,
        publicUrlProvider = publicUrlProvider,
        legalAge = dev.dwhipstock.pos.sdk.LegalAge.resolve(
            dev.dwhipstock.pos.sdk.LegalAge.fromEnv(SagePoppy.LEGAL_AGE), legalAgeOverride?.toString()),
        cashRounding = cashRounding.rounding,
    ) else CopperLanternConfig(
        venue = venue,
        settings = settingsRepo,
        printer = thermalPrinter,
        publicBaseUrl = publicBaseUrl,
        publicUrlProvider = publicUrlProvider,
        cashRounding = cashRounding.rounding,
    )
    cashRounding.warning?.let { log.warn("Cash rounding config ignored: $it") }
    log.info("Cash rounding: ${cashRounding.rounding.wire} (${cashRounding.source})")
    log.info("Store: ${config.displayName} (POS_VENUE=${config.venueId}, ${config.profile.country}, " +
        "${config.profile.currency}, ${config.profile.locales.joinToString("/")}, ${config.profile.kind.wire})")
    if (config.profile.kind == StoreProfile.Kind.RETAIL) log.info("Legal age for age-restricted items: ${config.legalAge}")
    log.info("Customers scan: $publicBaseUrl/m/t/{token} (a random link per table, on its QR slip; a manager can regenerate it)  — print slips from the tablet")
    val checkService = CheckService(config)
    // Kitchen / station tickets: opt-in, restaurants only. Off = null, and the
    // store behaves exactly as before (no hook, no queue, no worker thread).
    kitchenPrinting.warning?.let { log.warn("Kitchen tickets config ignored: $it") }
    val kitchenService = when {
        !kitchenPrinting.enabled -> null
        config.profile.kind == StoreProfile.Kind.RETAIL -> {
            log.warn("Kitchen tickets: ignored for a retail store (${kitchenPrinting.source})")
            null
        }
        else -> dev.dwhipstock.pos.restaurant.KitchenService(
            config, settingsRepo,
            transport = kitchenTransport ?: dev.dwhipstock.pos.restaurant.KitchenTcpTransport(),
            printersEnabled = physicalPrinterEnabled,
        ).also {
            it.ensureDefaults()
            checkService.kitchen = it
            it.queue.start()
            monitor.subscribe(ApplicationStopped) { _ -> it.queue.stop() }
        }
    }
    log.info(if (kitchenService != null) kitchenPrinting.describe() else "Kitchen tickets: off (${kitchenPrinting.source})")
    // background account lookup only; a missing key or no internet changes nothing else
    // Stripe is Canada-only (a CAD account) for now: a store in another country
    // takes cash and its own external card terminal, and never contacts Stripe
    val storeStripe = if (config.profile.currency == "CAD") stripeConfig else {
        if (stripeConfig.enabled) log.info("Stripe: off for this store (${config.profile.currency}); the Stripe integration is CAD-only")
        StripeConfig.OFF
    }
    val stripeService = StripeService(storeStripe, checkService, config.venueId, config.displayName, stripeHttp)
        .also { it.start() }
    val retailService = dev.dwhipstock.pos.retail.RetailService(
        config, checkService, productLookup ?: dev.dwhipstock.pos.retail.OpenFoodFactsLookup())
    if (config.profile.kind == StoreProfile.Kind.RETAIL) retailService.ensureRegister()
    // stock counting / receiving in the store (retail); on hand stays the cloud's
    val stockService = dev.dwhipstock.pos.retail.StockService(config)
    val shiftService = ShiftService(config)
    staffAppMfa.warning?.let { log.warn("Staff app MFA config ignored: $it") }
    log.info(staffAppMfa.describe())
    val authService = AuthService(settingsRepo, staffAppMfa.required)
    val photoStore: PhotoStore = FilesystemPhotoStore(java.io.File(photosDir))
    val aiPhotos = dev.dwhipstock.pos.aiphotos.AiPhotoService(
        imageGenConfig, config.brand,
        provider = imageProvider ?: dev.dwhipstock.pos.aiphotos.ImageProviders.from(imageGenConfig),
        reachable = imageReachable ?: dev.dwhipstock.pos.aiphotos.AiPhotoService::tcpReachable,
    ).also { it.start() }

    // Cloud sync (CONTRACT.md): one-way outbox pusher + revocation pull. Never constructed
    // unless both env vars are set — offline-first stays the default (and tests).
    val syncUrl = cloudSyncUrl
    val syncKey = cloudSyncApiKey
    var pairingService: PairingService? = pairingTransport?.let { PairingService(it) }
    if (!syncUrl.isNullOrBlank() && !syncKey.isNullOrBlank()) {
        val interval = System.getenv("CLOUD_SYNC_INTERVAL_SECONDS")?.toLongOrNull() ?: 10L
        // Re-evaluated each tick (DHCP-safe). Null when nothing real is reachable
        // (airplane-mode dev) — the heartbeat is skipped rather than reporting the
        // fake QR fallback, so the portal shows "offline" instead of a dead IP.
        val lanBaseUrlProvider: () -> String? = {
            publicUrl?.takeIf { it.isNotBlank() }
                ?: detectLanIpv4()?.let { "http://$it:$lanPort" }
        }
        val transport = HttpCloudTransport(syncUrl, syncKey)
        if (pairingService == null) pairingService = PairingService(transport)
        CloudSync(
            transport, photoStore, interval,
            reEmitReportHistory = checkService::backfillReportCompleteClosedEvents,
            lanBaseUrl = lanBaseUrlProvider,
            // retail: the slow, best-effort on-hand pull for the count screen's
            // "expected" hint (CONTRACT §9) — read-only, never gates anything
            stock = if (config.profile.kind == StoreProfile.Kind.RETAIL) stockService else null,
            stockIntervalSeconds = System.getenv("CLOUD_STOCK_PULL_SECONDS")?.toLongOrNull() ?: 300L,
        ).start(this)
        log.info("cloud sync enabled → $syncUrl (every ${interval}s)")
    }

    // Cloud-hosted venues run with the device gate on: terminals must pair before
    // they can list staff or log in, and sessions stay bound to their device.
    // On-prem/embedded stores keep the LAN behavior (off).
    val requireDeviceToken = requireDeviceTokenOverride
        ?: (System.getenv("POS_REQUIRE_DEVICE_TOKEN")?.toBoolean() ?: false)
    if (requireDeviceToken) log.info("device gate ON: terminals must pair (POS_REQUIRE_DEVICE_TOKEN)")

    install(ContentNegotiation) { json() }
    // JSON payloads only (zones/items are 10-20KB of very compressible JSON —
    // matters for staff phones on venue Wi-Fi); photos are already JPEG.
    install(Compression) {
        gzip {
            matchContentType(ContentType.Application.Json, ContentType.Text.Html)
            minimumSize(1024)
        }
    }
    installAuthGate(authService, requireDeviceToken)
    install(CallLogging)
    install(StatusPages) {
        // error bodies: machine `code` for client-side translation + english
        // `error` message for logs/debugging. The server never localizes.
        exception<NotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound,
                mapOf("error" to (cause.message ?: "not found"), "code" to cause.code))
        }
        exception<ConflictException> { call, cause ->
            call.respond(HttpStatusCode.Conflict,
                mapOf("error" to (cause.message ?: "conflict"), "code" to cause.code))
        }
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest,
                mapOf("error" to (cause.message ?: "bad request"), "code" to cause.code))
        }
        exception<SlipTicketException> { call, _ ->
            call.respond(HttpStatusCode.Unauthorized,
                mapOf("error" to "open the slips from the tablet", "code" to "slip_ticket_required"))
        }
        exception<ManagerApprovalException> { call, cause ->
            call.respond(HttpStatusCode.Forbidden,
                mapOf("error" to (cause.message ?: "manager approval required"),
                    "code" to "manager_approval_required"))
        }
        exception<StripeException> { call, cause ->
            call.respond(HttpStatusCode.fromValue(cause.status), buildMap {
                put("error", cause.message ?: "stripe error")
                put("code", cause.code)
                cause.declineCode?.let { put("declineCode", it) }
            })
        }
        exception<dev.dwhipstock.pos.aiphotos.ImageGenException> { call, cause ->
            cause.retryAfterSeconds?.let { call.response.header(HttpHeaders.RetryAfter, it.toString()) }
            call.respond(HttpStatusCode.fromValue(cause.status),
                mapOf("error" to (cause.message ?: "image generation failed"), "code" to cause.code))
        }
        exception<RateLimitException> { call, cause ->
            call.response.header(HttpHeaders.RetryAfter, cause.retryAfterSeconds.toString())
            call.respond(HttpStatusCode.TooManyRequests,
                mapOf("error" to (cause.message ?: "rate limited"), "code" to "rate_limited"))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest,
                mapOf("error" to (cause.message ?: "bad request"), "code" to "bad_request"))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("unhandled", cause)
            call.respond(HttpStatusCode.InternalServerError,
                mapOf("error" to (cause.message ?: "unknown"), "code" to "internal"))
        }
    }
    install(CORS) {
        anyHost() // TODO: lock down for production
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
    }
    routing {
        get("/") { call.respond(mapOf("service" to "pos-server", "version" to "0.1.0")) }
        // pairingRequired lets the terminal decide between the pairing screen and
        // the legacy LAN flow before it has any credentials
        // venue = the store's display name ("Copper Lantern — Vieux-Port") so the
        // sign-in screen can say which store this terminal serves before login
        get("/health") {
            call.respond(HealthResponse.of(config, requireDeviceToken, kitchenService != null))
        }
        // Staff ordering web app (M7): a mobile-first page served from the store.
        // Public shell (like the customer menu); it authenticates via POST /login
        // inside and drives the gated ordering API with the returned bearer token.
        // Read once — the resource is baked into the jar, and re-reading the
        // 50KB file per request showed up as the slowest route in the logs.
        // The brand skin dresses it: Sage & Poppy's own colours, type and sign-in.
        val staffAppHtml = StaffAppBrand.apply(StoreAssets.readText("staff-app.html"), config.brand)
        get("/staff-app") {
            call.respondText(staffAppHtml, ContentType.Text.Html)
        }
        get("/staff-app/fonts/{file}") {
            val file = call.parameters["file"]!!
            val bytes = file.takeIf { it.matches(Regex("[A-Za-z0-9-]+\\.ttf")) }
                ?.let { StoreAssets.readBytesOrNull("staff-app-fonts/$it") }
            if (bytes == null) call.respond(HttpStatusCode.NotFound)
            else {
                call.response.header(HttpHeaders.CacheControl, "public, max-age=604800")
                call.respondBytes(bytes, ContentType("font", "ttf"))
            }
        }
        customerRoutes(checkService, config)
        tableLinkRoutes(config)
        authRoutes(authService)
        staffAdminRoutes(authService)
        pairingRoutes(pairingService)
        posRoutes(checkService, authService, photoStore, stripeService)
        retailRoutes(retailService, authService)
        stockRoutes(stockService, authService)
        stripeRoutes(stripeService)
        tableRoutes(authService)
        floorObjectRoutes(authService)
        zoneManagementRoutes(authService)
        catalogRoutes()
        photoRoutes(photoStore, authService)
        aiPhotoRoutes(aiPhotos, photoStore, authService)
        shiftRoutes(shiftService, authService)
        settingsRoutes(settingsRepo)
        printerRoutes(thermalPrinter, config, settingsRepo)
        kitchenRoutes(kitchenService)
        // Reporting portal lives at the root of the cloud host (CLOUD_SYNC_URL) in
        // production, where Caddy fronts the sync API and the Next.js portal on one
        // host — so the derived scheme://host is correct. On a SPLIT deployment
        // (e.g. the local compose stack: sync host is the internal `api:8081`, but
        // the owner portal is a separate LAN service on :3000) that derivation is
        // an unreachable URL, so an explicit REPORTING_PORTAL_URL wins when set.
        // Unset/blank → derive from CLOUD_SYNC_URL exactly as before.
        val portalUrl = reportingPortalUrl?.takeIf { it.isNotBlank() }
            ?: portalUrlFrom(syncUrl)
        cloudRoutes(portalUrl) {
            publicUrl?.takeIf { it.isNotBlank() }
                ?: detectLanIpv4()?.let { "http://$it:$lanPort" }
        }
    }
}

/** See the POS_SEED=none branch in [module]. Clears demo seed residue, but ONLY
 *  while the store has never synced. The
 *  outbox goes too: pre-first-sync it holds nothing but migration echoes
 *  (table.relabeled etc.), which would push up as junk venue history.
 *
 *  venue_settings is RESET, not deleted — SettingsRepository reads row id=1 with
 *  .first() and would crash on an empty table. Migration 006 carries fictional
 *  demo values, which must not be inherited by a newly provisioned venue. Blank
 *  the payment and identity fields to neutral defaults; the
 *  owner sets their own values in the portal or Settings. Later-migration columns
 *  (printer, idle, alerts) already default to neutral values. */
private fun wipeMigrationSeedResidueIfNeverSynced() =
    org.jetbrains.exposed.sql.transactions.transaction {
        if (dev.dwhipstock.pos.db.SyncState.get("install_id") != null) return@transaction
        exec("DELETE FROM item_variants")
        exec("DELETE FROM items")
        exec("DELETE FROM categories")
        exec("DELETE FROM floor_objects")
        exec("DELETE FROM dining_tables")
        exec("DELETE FROM zones")
        exec("DELETE FROM sync_outbox")
        exec(
            "UPDATE venue_settings SET card_processor='', bank_name='', bank_account_number='', " +
                "bank_account_name='', receipt_footer='', venue_phone='', venue_address='', " +
                "service_charge_percent=0, corkage_per_bottle_cents=0 WHERE id=1"
        )
    }

/**
 * Public, pre-login: enough for the terminal to pick its screens, brand,
 * languages and money format before anyone signs in. Nothing secret.
 */
@Serializable
data class HealthResponse(
    val status: String,
    val pairingRequired: Boolean = false,
    val venue: String = "",
    val venueId: String = "",
    val brand: String = "",
    /** restaurant | retail */
    val kind: String = StoreProfile.Kind.RESTAURANT.wire,
    val country: String = "CA",
    val currency: String = "CAD",
    /** Languages staff can pick; the first is the store's default. */
    val locales: List<String> = listOf("fr", "en"),
    val legalAge: Int = 18,
    /** Cash payments round to the nickel ("nickel") or are charged to the cent ("off"). */
    val cashRounding: String = "nickel",
    /**
     * kitchen.printing=on: the terminal shows Send, the Kitchen view and station
     * setup. Left out of the JSON while off, so a store without kitchen tickets
     * answers exactly as it always did.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val kitchenPrinting: Boolean = false,
) {
    companion object {
        fun of(config: dev.dwhipstock.pos.sdk.CustomerConfig, pairingRequired: Boolean, kitchenPrinting: Boolean = false) = HealthResponse(
            status = "ok",
            pairingRequired = pairingRequired,
            venue = config.displayName,
            venueId = config.venueId,
            brand = config.brand,
            kind = config.profile.kind.wire,
            country = config.profile.country,
            currency = config.profile.currency,
            locales = config.profile.locales.map { it.tag },
            legalAge = config.legalAge,
            cashRounding = if (config.roundingPolicy == dev.dwhipstock.pos.sdk.RoundingPolicy.NoRounding) "off" else "nickel",
            kitchenPrinting = kitchenPrinting,
        )
    }
}
