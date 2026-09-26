package dev.dwhipstock.poscloud

/** All runtime knobs come from env; the defaults are the local dev setup. */
data class CloudConfig(
    val databaseUrl: String = env("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/pos_cloud",
    val dbUser: String = env("DB_USER") ?: System.getProperty("user.name"),
    val dbPassword: String = env("DB_PASSWORD") ?: "",
    val port: Int = env("PORT")?.toInt() ?: 8081,
    val migrationsDir: String = env("MIGRATIONS_DIR") ?: "../migrations",
    val adminEmail: String? = env("ADMIN_EMAIL"),
    val adminPassword: String? = env("ADMIN_PASSWORD"),
    // Demo-only escape hatch. Production keeps the secure default; isolated
    // sales demos can opt out when account friction matters more than 2FA.
    val totpRequired: Boolean = env("TOTP_REQUIRED")?.toBoolean() ?: true,
    // Break-glass lockout recovery: set to the owner's email, restart, and their
    // TOTP is wiped so the next login re-enrolls from scratch. Clear it afterwards.
    val resetTotpEmail: String? = env("RESET_TOTP_EMAIL"),
    // The primary store's bearer key (→ the first of [stores]). Further stores get
    // theirs from STORE_API_KEYS="<venueId>=<key>,<venueId>=<key>".
    val storeApiKey: String? = env("STORE_API_KEY"),
    val storeApiKeys: Map<String, String> = parsePairs(env("STORE_API_KEYS")),
    val cookieSecure: Boolean = env("COOKIE_SECURE")?.toBoolean() ?: false,
    // Portal sessions: signed out after this long with no user activity (background
    // polls don't count), and never live past the absolute cap regardless of activity.
    val sessionIdleMinutes: Long = env("PORTAL_SESSION_IDLE_MINUTES")?.toLongOrNull()?.takeIf { it > 0 } ?: 60,
    val sessionMaxHours: Long = env("PORTAL_SESSION_MAX_HOURS")?.toLongOrNull()?.takeIf { it > 0 } ?: 12,
    // This deployment's tenant id. One client = one portal instance with its own
    // database, so a database holds one tenant; the id only has to be stable for
    // that database (never change it on an existing one). Default: the first
    // client's id, which every existing database already uses.
    val tenantId: String = env("TENANT_ID")
        ?.also { require(TENANT_ID_RE.matches(it)) { "TENANT_ID must match ${TENANT_ID_RE.pattern}" } }
        ?: Bootstrap.TENANT,
    // The group (tenant) name shown in the portal; synced onto the tenant row at boot.
    val venueName: String = env("VENUE_NAME") ?: "Copper Lantern",
    val venueTz: String = env("VENUE_TZ") ?: "America/New_York",
    // The tenant's stores, seeded at boot: STORES="<venueId>=<name>,…" (order kept;
    // the first is the primary store). Unset → one store, "vieux-port", named VENUE_NAME.
    val stores: List<StoreSeed> = parsePairs(env("STORES")).map { (id, name) -> StoreSeed(id, name) }
        .ifEmpty { listOf(StoreSeed(PRIMARY_VENUE, venueName)) },
    // Base domain for cloud-hosted venue stores (<subdomain>.<this>); unset = no
    // public store URLs are minted or accepted (pure on-prem deployment).
    val publicBaseDomain: String? = env("PUBLIC_BASE_DOMAIN"),
    // Per-store profile (017), "<venueId>=<value>,…". A store not listed keeps the
    // defaults: VENUE_TZ, CAD, CA, restaurant. The zone is applied when the store
    // is first created only (it decides history's business days); currency,
    // country and kind follow env on every boot for the stores listed.
    val storeZones: Map<String, String> = parsePairs(env("STORE_ZONES")),
    val storeCurrencies: Map<String, String> = parsePairs(env("STORE_CURRENCIES"))
        .mapValues { it.value.uppercase() },
    val storeCountries: Map<String, String> = parsePairs(env("STORE_COUNTRIES"))
        .mapValues { it.value.uppercase() },
    // Retail stores (stock page, counter sales): RETAIL_STORES="sage-poppy,…"
    val retailStores: Set<String> = (env("RETAIL_STORES") ?: "").split(',')
        .map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
    // The currency "All stores" shows its approximate converted total in; a
    // tenant setting seeded from env (default CAD).
    val reportingCurrency: String = (env("REPORTING_CURRENCY") ?: "CAD").uppercase(),
    // Fixed conversion rates, FX_<FROM>_<TO>=<rate> (e.g. FX_USD_CAD=1.37). No
    // live rates: the portal labels the converted figure approximate and shows
    // the rate it used.
    val fxRates: Fx.Rates = Fx.Rates.fromEnv(System.getenv()),
)

/** One store (venue) the boot seed provisions for the tenant. */
data class StoreSeed(val venueId: String, val name: String)

/** A tenant id: lowercase letters, digits and dashes (it also names the client's portal instance). */
internal val TENANT_ID_RE = Regex("^[a-z0-9][a-z0-9-]{1,39}$")

/** The primary store's venue id (formerly "main"; migration 014 renames it). */
const val PRIMARY_VENUE = "vieux-port"

/** "a=1,b=2" → ordered map; blank entries and entries without '=' are ignored. */
internal fun parsePairs(raw: String?): Map<String, String> =
    (raw ?: "").split(',').mapNotNull { part ->
        val k = part.substringBefore('=', "").trim()
        val v = part.substringAfter('=', "").trim()
        if (k.isEmpty() || v.isEmpty()) null else k to v
    }.toMap(LinkedHashMap())

private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
