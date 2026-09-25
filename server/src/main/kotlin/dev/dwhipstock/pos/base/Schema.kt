package dev.dwhipstock.pos.base

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

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
    val deletedAt = datetime("deleted_at").nullable() // soft delete (M6); history keeps the row
    override val primaryKey = PrimaryKey(id)
}

object ItemVariants : Table("item_variants") {
    val id = varchar("id", 96) // e.g. "lantern-lager:bottle"
    val itemId = varchar("item_id", 64).references(Items.id)
    val labelFr = varchar("label_fr", 100)
    val labelEn = varchar("label_en", 100)
    val priceCents = long("price_cents")
    val sortOrder = integer("sort_order").default(0)
    val deletedAt = datetime("deleted_at").nullable() // soft delete (M6)
    override val primaryKey = PrimaryKey(id)
}

/**
 * Tenders attach to the abstract SDK Transaction by id — no FK into the
 * restaurant add-on. A Cart tender and a Check tender are the same row shape.
 */
object Tenders : IntIdTable("tenders") {
    val transactionId = integer("transaction_id")
    val type = varchar("type", 20) // CASH | CARD | BANK_TRANSFER
    val amountTenderedCents = long("amount_tendered_cents")
    val amountAppliedCents = long("amount_applied_cents")
    val roundingAdjustmentCents = long("rounding_adjustment_cents").default(0)
    val changeCents = long("change_cents").default(0)
    // opaque settlement sub-scope (restaurant split checks); no FK by design —
    // base never references the add-on tiers. NULL = whole-transaction tender.
    val billGroupId = integer("bill_group_id").nullable()
    val createdAt = datetime("created_at")
}

object Users : Table("users") {
    val id = varchar("id", 64)
    val name = varchar("name", 100)
    val role = varchar("role", 20) // MANAGER | SERVER
    val pin = varchar("pin", 100) // BCrypt hash ($2a$...); plaintext auto-upgraded at startup
    val languageCode = varchar("language_code", 8).default("en")
    val active = bool("active").default(true) // cloud can deactivate (024)
    val deletedAt = datetime("deleted_at").nullable() // cloud soft-delete (024); row kept for session/check FKs
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
    val createdAt = datetime("created_at")
    val revokedAt = datetime("revoked_at").nullable()
    val expiresAt = datetime("expires_at").nullable() // absolute; null = legacy row → createdAt+12h
    val lastUsedAt = datetime("last_used_at").nullable() // sliding; refreshed per authenticated call
    val deviceId = varchar("device_id", 36).nullable() // paired terminal that minted it (027); null = staff-app phone
    override val primaryKey = PrimaryKey(token)
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
    val pairedAt = datetime("paired_at")
    val lastSeenAt = datetime("last_seen_at").nullable()
    val revokedAt = datetime("revoked_at").nullable()
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
    val activatedAt = datetime("activated_at").nullable()
    // last accepted 30s step index — a code is single-use within its window (replay guard)
    val lastStep = long("last_step").nullable()
    val createdAt = datetime("created_at")
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
    val createdAt = datetime("created_at")
    val expiresAt = datetime("expires_at")
    val lastUsedAt = datetime("last_used_at").nullable()
    val label = varchar("label", 100).nullable()
    override val primaryKey = PrimaryKey(token)
}
