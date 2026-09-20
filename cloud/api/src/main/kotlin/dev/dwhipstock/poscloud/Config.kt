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
    // Break-glass lockout recovery: set to the owner's email, restart, and their
    // TOTP is wiped so the next login re-enrolls from scratch. Clear it afterwards.
    val resetTotpEmail: String? = env("RESET_TOTP_EMAIL"),
    val storeApiKey: String? = env("STORE_API_KEY"),
    val cookieSecure: Boolean = env("COOKIE_SECURE")?.toBoolean() ?: false,
    val venueName: String = env("VENUE_NAME") ?: "The Copper Lantern Pub",
    val venueTz: String = env("VENUE_TZ") ?: "America/Toronto",
    // Base domain for cloud-hosted venue stores (<subdomain>.<this>); unset = no
    // public store URLs are minted or accepted (pure on-prem deployment).
    val publicBaseDomain: String? = env("PUBLIC_BASE_DOMAIN"),
)

private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
