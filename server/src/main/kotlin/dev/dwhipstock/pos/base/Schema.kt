package dev.dwhipstock.pos.base

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Table
import dev.dwhipstock.pos.db.utcTimestamp

/**
 * POS Base tier — everything a plain retail store needs. Complete on its own;
 * a retail-only customer ships with just this + locale + overrides.
 * All money columns are integer cents.
 *
 * TODO: Cart (the base retail Transaction: walk-up, no location, closes on
 * tender) is not built yet — this venue uses Check. When Cart lands, Checks
 * and Carts likely become one transactions table with a type discriminator.
 */

object Categories : Table("categories") {
    val id = varchar("id", 64)
    val sortOrder = integer("sort_order").default(0)
    val nameFr = varchar("name_fr", 100)
    val nameEn = varchar("name_en", 100)
    override val primaryKey = PrimaryKey(id)
}

object Items : Table("items") {
    val id = varchar("id", 64) // slug, e.g. "lantern-lager"
    val nameFr = varchar("name_fr", 200)
    val nameEn = varchar("name_en", 200)
    val descriptionFr = varchar("description_fr", 500).default("")
    val descriptionEn = varchar("description_en", 500).default("")
    val categoryId = varchar("category_id", 64)
    val abbrev = varchar("abbrev", 4) // Ocha-style 2-char tile badge
    val isAlcohol = bool("is_alcohol").default(false)
    val active = bool("active").default(true) // 86'ing flips this at runtime (M2)
    val photoPath = varchar("photo_path", 300).nullable() // PhotoStore path (M5)
    // original | ai_generated | ai_enhanced (044); NULL = an older photo, read as original
    val photoSource = varchar("photo_source", 16).nullable().databaseGenerated()
    val deletedAt = utcTimestamp("deleted_at").nullable() // soft delete (M6); history keeps the row
    // retail shelf facts (038); the pubs keep the defaults. The defaults are
    // the database's (databaseGenerated: an insert that doesn't set them leaves
    // them to the column DEFAULT), so code that writes items keeps working on
    // any schema version.
    val barcode = varchar("barcode", 32).nullable().databaseGenerated() // UPC-A / EAN-13, unique when set
    val ageRestricted = bool("age_restricted").databaseGenerated() // ID check before payment (DEFAULT 0)
    val taxable = bool("taxable").databaseGenerated() // DEFAULT 1
    val crvSize = varchar("crv_size", 8).databaseGenerated() // NONE | SMALL (<24 oz) | LARGE (≥24 oz)
    val packUnits = integer("pack_units").databaseGenerated() // containers in the pack (a 6-pack = 6)
    // catalog facets (041) for a big shelf's filters and search: producer,
    // style / varietal / type, and a size or pack label ("6-pack", "750 ml")
    val brand = varchar("brand", 100).nullable().databaseGenerated()
    val subcategory = varchar("subcategory", 64).nullable().databaseGenerated()
    val sizeLabel = varchar("size_label", 32).nullable().databaseGenerated()
    // demo popularity (041): a seeded store's relative share of sales; also the
    // quick keys' cold start before the store has sales of its own. 0 = none.
    val salesWeight = integer("sales_weight").databaseGenerated() // DEFAULT 0
    override val primaryKey = PrimaryKey(id)
}

/**
 * Counter quick keys a manager pinned (041, retail). The rest of the grid is
 * auto-filled from the store's own recent sales; a pin keeps its tile through
 * every refresh until it is unpinned.
 */
object QuickKeyPins : Table("quick_key_pins") {
    val itemId = varchar("item_id", 64)
    val sortOrder = integer("sort_order").default(0)
    val pinnedBy = varchar("pinned_by", 64)
    val pinnedAt = utcTimestamp("pinned_at")
    override val primaryKey = PrimaryKey(itemId)
}

object ItemVariants : Table("item_variants") {
    val id = varchar("id", 96) // e.g. "lantern-lager:bottle"
    val itemId = varchar("item_id", 64).references(Items.id)
    val labelFr = varchar("label_fr", 100)
    val labelEn = varchar("label_en", 100)
    val priceCents = long("price_cents")
    val sortOrder = integer("sort_order").default(0)
    val deletedAt = utcTimestamp("deleted_at").nullable() // soft delete (M6)
    // what the store pays for one (047); NULL = unknown
    val costCents = long("cost_cents").nullable().databaseGenerated()
    override val primaryKey = PrimaryKey(id)
}

/**
 * Tenders attach to the abstract SDK Transaction by id — no FK into the
 * restaurant add-on. A Cart tender and a Check tender are the same row shape.
 */
object Tenders : IntIdTable("tenders") {
    val transactionId = integer("transaction_id")
    val type = varchar("type", 20) // CASH | CARD | BANK_TRANSFER | STRIPE
    val amountTenderedCents = long("amount_tendered_cents")
    val amountAppliedCents = long("amount_applied_cents")
    val roundingAdjustmentCents = long("rounding_adjustment_cents").default(0)
    val changeCents = long("change_cents").default(0)
    // opaque settlement sub-scope (restaurant split checks); no FK by design —
    // base never references the add-on tiers. NULL = whole-transaction tender.
    val billGroupId = integer("bill_group_id").nullable()
    val createdAt = utcTimestamp("created_at")
    // STRIPE tenders only (035): the PaymentIntent this tender settled
    val stripePaymentIntentId = varchar("stripe_payment_intent_id", 64).nullable()
    // TERMINAL tenders only (048): the terminal's id for the payment this settled
    val terminalPaymentRef = varchar("terminal_payment_ref", 64).nullable()
    // card-present tenders (048): brand, last 4, entry mode, auth code, EMV fields (JSON)
    val cardJson = text("card_json").nullable()
}

object Users : Table("users") {
    val id = varchar("id", 64)
    val name = varchar("name", 100)
    val role = varchar("role", 20) // MANAGER | SERVER
    val pin = varchar("pin", 100) // BCrypt hash ($2a$...); plaintext auto-upgraded at startup
    val languageCode = varchar("language_code", 8).default("en")
    val active = bool("active").default(true) // cloud can deactivate (024)
    val deletedAt = utcTimestamp("deleted_at").nullable() // cloud soft-delete (024); row kept for session/check FKs
    override val primaryKey = PrimaryKey(id)
}

/**
 * Local grant model (024, CONTRACT §7), owned by the tablet. Role defaults + per-staff
 * overrides are pushed up for display; the effective grant is enforced offline. Query DSL
 * only — see [dev.dwhipstock.pos.base.GrantsRepo] for the logic.
 */
object RoleGrants : Table("role_grants") {
    val role = varchar("role", 20)
    val permission = varchar("permission", 40)
    val granted = bool("granted")
    override val primaryKey = PrimaryKey(role, permission)
}

object StaffGrants : Table("staff_grants") {
    val staffId = varchar("staff_id", 64)
    val permission = varchar("permission", 40)
    val granted = bool("granted")
    override val primaryKey = PrimaryKey(staffId, permission)
}

/**
 * Owner-tunable venue settings (single venue → single row, id=1). The DATA
 * half of the data-vs-policy split: values the owner edits at runtime.
 * Policy choices stay typed in the customer config.
 */
object VenueSettings : Table("venue_settings") {
    val id = integer("id")
    val cardProcessor = varchar("card_processor", 100)
    val bankName = varchar("bank_name", 100)
    val bankAccountNumber = varchar("bank_account_number", 30)
    val bankAccountName = varchar("bank_account_name", 100)
    val serviceChargePercent = integer("service_charge_percent").default(0)
    val corkagePerBottleCents = long("corkage_per_bottle_cents").default(0)
    val receiptFooter = varchar("receipt_footer", 300)
    val venuePhone = varchar("venue_phone", 30)
    val venueAddress = varchar("venue_address", 200)
    val sessionIdleMinutes = integer("session_idle_minutes").default(15)
    // Pending-order alerts (customer QR orders awaiting staff accept/reject).
    val pendingAlertsEnabled = integer("pending_alerts_enabled").default(1) // 0|1
    val pendingAlertEscalateSeconds = integer("pending_alert_escalate_seconds").default(90)
    val pendingAlertVolume = integer("pending_alert_volume").default(80) // 0..100
    // Network thermal receipt printer (ESC/POS over raw TCP). Empty ip = unconfigured.
    val printerIp = varchar("printer_ip", 64).default("")
    val printerPort = integer("printer_port").default(9100)
    // IANA zone (030): seeded from VENUE_TZ once, then authoritative for display,
    // business days and reports. Timestamps themselves are UTC instants.
    val timezone = varchar("timezone", 64).default("")
    // Guest Wi-Fi (032) for the join-QR slips. Empty ssid = not configured. The
    // password never leaves the store (not in the outbox, not to the staff app).
    val wifiSsid = varchar("wifi_ssid", 32).default("")
    val wifiPassword = varchar("wifi_password", 63).default("")
    val wifiSecurity = varchar("wifi_security", 8).default("WPA") // WPA | WEP | nopass
    val wifiHidden = integer("wifi_hidden").default(0) // 0|1
    override val primaryKey = PrimaryKey(id)
}

/**
 * Opaque session tokens (not JWT — simpler, revocable). A user may have several
 * live rows at once — one per signed-in device/surface; a new login no longer
 * evicts the others. Only the oldest are trimmed past the per-user cap (see
 * AuthService.MAX_SESSIONS_PER_USER). Absolute 12h + sliding idle expiry.
 */
object Sessions : Table("sessions") {
    val token = varchar("token", 36)
    val userId = varchar("user_id", 64).references(Users.id)
    val createdAt = utcTimestamp("created_at")
    val revokedAt = utcTimestamp("revoked_at").nullable()
    val expiresAt = utcTimestamp("expires_at").nullable() // absolute; null = legacy row → createdAt+12h
    val lastUsedAt = utcTimestamp("last_used_at").nullable() // sliding; refreshed per authenticated call
    val deviceId = varchar("device_id", 36).nullable() // paired terminal that minted it (027); null = staff-app phone
    val surface = varchar("surface", 16).default(SessionSurface.POS) // 032: pos | staff_app
    override val primaryKey = PrimaryKey(token)
}

/** Which client minted a session (032). On-prem both can be device-unbound. */
object SessionSurface {
    const val POS = "pos"
    const val STAFF_APP = "staff_app"
}

/**
 * Paired terminals (027, M8). token_sha256 is the SHA-256 of the device token
 * the terminal holds; the plaintext never touches this database. A revoked
 * device is kept (audit + heartbeat reporting) but fails validation.
 */
object Devices : Table("devices") {
    val id = varchar("id", 36)
    val name = varchar("name", 100)
    val tokenSha256 = varchar("token_sha256", 64).uniqueIndex()
    val pairedAt = utcTimestamp("paired_at")
    val lastSeenAt = utcTimestamp("last_seen_at").nullable()
    val revokedAt = utcTimestamp("revoked_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * Per-staff TOTP secret for the staff-app 2FA (M7, migration 025). Store-local
 * and verified offline. activatedAt is null until the first code verifies
 * (enrollment); a manager reset deletes the row so the staff re-enrolls.
 */
object StaffTotp : Table("staff_totp") {
    val userId = varchar("user_id", 64).references(Users.id)
    val secret = varchar("secret", 64)
    val activatedAt = utcTimestamp("activated_at").nullable()
    // last accepted 30s step index — a code is single-use within its window (replay guard)
    val lastStep = long("last_step").nullable()
    val createdAt = utcTimestamp("created_at")
    override val primaryKey = PrimaryKey(userId)
}

/**
 * A (staff, device) pair that cleared TOTP; the opaque token lives in the phone's
 * localStorage and lets that device log in PIN-only until expiresAt (~90 days).
 * Bound to userId — a stolen token is useless without that staff member's PIN.
 */
object TrustedDevices : Table("trusted_devices") {
    val token = varchar("token", 36)
    val userId = varchar("user_id", 64).references(Users.id)
    val createdAt = utcTimestamp("created_at")
    val expiresAt = utcTimestamp("expires_at")
    val lastUsedAt = utcTimestamp("last_used_at").nullable()
    val label = varchar("label", 100).nullable()
    override val primaryKey = PrimaryKey(token)
}
