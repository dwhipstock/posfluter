package dev.dwhipstock.poscloud.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dwhipstock.poscloud.CloudConfig
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.postgresql.util.PGobject
import java.util.concurrent.ConcurrentHashMap

/**
 * Query-DSL mirrors of the tables owned by the cloud/migrations scripts — keep in
 * sync with the scripts. Every table leads with tenant_id; there is no query
 * path that doesn't filter on it.
 */

/** jsonb column carried as a raw JSON string; PGobject makes the driver bind it as jsonb. */
private class JsonbColumnType : ColumnType<String>() {
    override fun sqlType() = "jsonb"
    override fun valueFromDB(value: Any): String = when (value) {
        is PGobject -> value.value ?: "null"
        else -> value.toString()
    }
    override fun notNullValueToDB(value: String): Any = PGobject().apply {
        type = "jsonb"
        this.value = value
    }
}

private fun Table.jsonb(name: String): Column<String> = registerColumn(name, JsonbColumnType())

object Db {
    private val databases = ConcurrentHashMap<String, Database>()

    /** One pool per JDBC url per process (tests and prod both use exactly one). */
    fun connect(config: CloudConfig): Database = databases.computeIfAbsent(config.databaseUrl) { url ->
        val ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = config.dbUser
            password = config.dbPassword
            maximumPoolSize = 10
        })
        Database.connect(ds)
    }
}

object Tenants : Table("tenants") {
    val id = text("id")
    val name = text("name")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Venues : Table("venues") {
    val tenantId = text("tenant_id")
    val id = text("id")
    val name = text("name")
    val timezone = text("timezone")
    // pinned on first push (002): a different store DB must not reuse this venue's ids
    val storeInstallId = text("store_install_id").nullable()
    // the store's current LAN base URL + last heartbeat (007): drives the portal's
    // /staff-app redirect to the in-store staff ordering app.
    val storeLanUrl = text("store_lan_url").nullable()
    val storeSeenAt = timestampWithTimeZone("store_seen_at").nullable()
    // optional versions the store reports on its heartbeat (015); NULL = not reported
    val storeAppVersion = text("store_app_version").nullable()
    val storeContractVersion = integer("store_contract_version").nullable()
    // hostname label of the venue's cloud-hosted store container (008); NULL = on-prem
    val subdomain = text("subdomain").nullable()
    override val primaryKey = PrimaryKey(tenantId, id)
}

object StoreApiKeys : Table("store_api_keys") {
    val id = long("id").autoIncrement()
    val tenantId = text("tenant_id")
    val venueId = text("venue_id")
    val keySha256 = text("key_sha256").uniqueIndex()
    val label = text("label")
    val createdAt = timestampWithTimeZone("created_at")
    val lastSeenAt = timestampWithTimeZone("last_seen_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object PortalUsers : Table("portal_users") {
    val id = long("id").autoIncrement()
    val tenantId = text("tenant_id")
    val email = text("email")
    val passwordHash = text("password_hash")
    val totpSecret = text("totp_secret").nullable()
    val totpEnabled = bool("totp_enabled")
    val displayName = text("display_name")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object PortalSessions : Table("portal_sessions") {
    val tokenSha256 = text("token_sha256")
    val tenantId = text("tenant_id")
    val userId = long("user_id")
    val createdAt = timestampWithTimeZone("created_at")
    val expiresAt = timestampWithTimeZone("expires_at")
    val lastUsedAt = timestampWithTimeZone("last_used_at")
    override val primaryKey = PrimaryKey(tokenSha256)
}

object PortalBackupCodes : Table("portal_backup_codes") {
    val id = long("id").autoIncrement()
    val tenantId = text("tenant_id")
    val userId = long("user_id")
    val codeSha256 = text("code_sha256")
    val createdAt = timestampWithTimeZone("created_at")
    val usedAt = timestampWithTimeZone("used_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object LoginPending : Table("login_pending") {
    val tokenSha256 = text("token_sha256")
    val tenantId = text("tenant_id")
    val userId = long("user_id")
    val purpose = text("purpose")
    val secret = text("secret")
    val createdAt = timestampWithTimeZone("created_at")
    val expiresAt = timestampWithTimeZone("expires_at")
    override val primaryKey = PrimaryKey(tokenSha256)
}

object Events : Table("events") {
    val tenantId = text("tenant_id")
    val venueId = text("venue_id")
    val eventId = text("event_id")
    val eventType = text("event_type")
    val aggregateType = text("aggregate_type")
    val aggregateId = text("aggregate_id")
    val payload = jsonb("payload")
    val storeSeq = long("store_seq")
    val storeCreatedAt = timestampWithTimeZone("store_created_at")
    val receivedAt = timestampWithTimeZone("received_at")
    override val primaryKey = PrimaryKey(tenantId, eventId)
}

object Checks : Table("checks") {
    val tenantId = text("tenant_id")
    val venueId = text("venue_id")
    val checkId = integer("check_id")
    val status = text("status")
    val tableId = text("table_id").nullable()
    val tableLabel = text("table_label").nullable()
    val zoneId = text("zone_id").nullable()
    val zoneNameFr = text("zone_name_fr").nullable()
    val zoneNameEn = text("zone_name_en").nullable()
    val shiftId = long("shift_id").nullable()
    val openedAt = timestampWithTimeZone("opened_at").nullable()
    val closedAt = timestampWithTimeZone("closed_at").nullable()
    val openedBy = text("opened_by").nullable()
    val grandTotalCents = long("grand_total_cents").nullable()
    val taxIncludedCents = long("tax_included_cents").nullable()
    val corkageBottles = integer("corkage_bottles").nullable()
    val corkageCents = long("corkage_cents").nullable()
    val serviceChargeCents = long("service_charge_cents").nullable()
    val voidReason = text("void_reason").nullable()
    val voidedBy = text("voided_by").nullable()
    // per-sale taxes added on top (016); NULL = no breakdown sent (older store)
    val gstCents = long("gst_cents").nullable()
    val qstCents = long("qst_cents").nullable()
    val taxes = jsonb("taxes").nullable()
    override val primaryKey = PrimaryKey(tenantId, venueId, checkId)
}

object CheckLines : Table("check_lines") {
    val id = long("id").autoIncrement()
    val tenantId = text("tenant_id")
    val venueId = text("venue_id")
    val checkId = integer("check_id")
    val lineId = long("line_id").nullable()
    val itemId = text("item_id").nullable()
    val variantId = text("variant_id").nullable()
    val categoryId = text("category_id").nullable()
    val nameFr = text("name_fr").nullable()
    val nameEn = text("name_en").nullable()
    val variantLabelFr = text("variant_label_fr").nullable()
    val variantLabelEn = text("variant_label_en").nullable()
    val displayName = text("display_name").nullable()
    val qty = integer("qty")
    val unitPriceCents = long("unit_price_cents")
    val lineTotalCents = long("line_total_cents")
    override val primaryKey = PrimaryKey(id)
}

object CheckTenders : Table("check_tenders") {
    val tenantId = text("tenant_id")
    val venueId = text("venue_id")
    val tenderId = long("tender_id")
    val checkId = integer("check_id")
    val type = text("type")
    val amountTenderedCents = long("amount_tendered_cents").nullable()
    val amountAppliedCents = long("amount_applied_cents").nullable()
    val roundingAdjustmentCents = long("rounding_adjustment_cents").nullable()
    val changeCents = long("change_cents").nullable()
    val tenderedAt = timestampWithTimeZone("tendered_at").nullable()
    override val primaryKey = PrimaryKey(tenantId, venueId, tenderId)
}

object Shifts : Table("shifts") {
    val tenantId = text("tenant_id")
    val venueId = text("venue_id")
    val shiftId = long("shift_id")
    val status = text("status")
    val openedAt = timestampWithTimeZone("opened_at").nullable()
    val openedBy = text("opened_by").nullable()
    val openingFloatCents = long("opening_float_cents").nullable()
    val closedAt = timestampWithTimeZone("closed_at").nullable()
    val closedBy = text("closed_by").nullable()
    val revenueCents = long("revenue_cents").nullable()
    val transactionCount = integer("transaction_count").nullable()
    val avgCheckCents = long("avg_check_cents").nullable()
    val corkageCents = long("corkage_cents").nullable()
    val tenderBreakdown = jsonb("tender_breakdown").nullable()
    val expectedCashCents = long("expected_cash_cents").nullable()
    val closingCountCents = long("closing_count_cents").nullable()
    val overShortCents = long("over_short_cents").nullable()
    override val primaryKey = PrimaryKey(tenantId, venueId, shiftId)
}

object Refunds : Table("refunds") {
    val tenantId = text("tenant_id")
    val venueId = text("venue_id")
    val refundId = long("refund_id")
    val checkId = integer("check_id").nullable()
    val shiftId = long("shift_id").nullable()
    val grossCents = long("gross_cents").nullable()
    val netCents = long("net_cents").nullable()
    val taxIncludedCents = long("tax_included_cents").nullable()
    val tenderType = text("tender_type").nullable()
    val reason = text("reason").nullable()
    val refundedBy = text("refunded_by").nullable()
    val tableLabel = text("table_label").nullable()
    val zoneId = text("zone_id").nullable()
    val zoneNameFr = text("zone_name_fr").nullable()
    val zoneNameEn = text("zone_name_en").nullable()
    val createdAt = timestampWithTimeZone("created_at").nullable()
    // the added taxes this refund reversed (016); NULL = no breakdown sent
    val gstCents = long("gst_cents").nullable()
    val qstCents = long("qst_cents").nullable()
    override val primaryKey = PrimaryKey(tenantId, venueId, refundId)
}

object CashMovements : Table("cash_movements") {
    val tenantId = text("tenant_id")
    val venueId = text("venue_id")
    val movementId = long("movement_id")
    val shiftId = long("shift_id").nullable()
    val direction = text("direction").nullable()
    val amountCents = long("amount_cents").nullable()
    val reason = text("reason").nullable()
    val createdBy = text("created_by").nullable()
    val createdAt = timestampWithTimeZone("created_at").nullable()
    override val primaryKey = PrimaryKey(tenantId, venueId, movementId)
}

object CatalogCategories : Table("catalog_categories") {
    val tenantId = text("tenant_id")
    val venueId = text("venue_id")
    val id = text("id")
    val nameFr = text("name_fr")
    val nameEn = text("name_en")
    val sortOrder = integer("sort_order")
    val deleted = bool("deleted")
    override val primaryKey = PrimaryKey(tenantId, venueId, id)
}

object CatalogItems : Table("catalog_items") {
    val tenantId = text("tenant_id")
    val venueId = text("venue_id")
    val id = text("id")
    val nameFr = text("name_fr")
    val nameEn = text("name_en")
    val descriptionFr = text("description_fr")
    val descriptionEn = text("description_en")
    val categoryId = text("category_id")
    val abbrev = text("abbrev").nullable()
    val isAlcohol = bool("is_alcohol")
    val active = bool("active")
    val deleted = bool("deleted")
    val photoVersion = long("photo_version").nullable()
    override val primaryKey = PrimaryKey(tenantId, venueId, id)
}

object CatalogVariants : Table("catalog_variants") {
    val tenantId = text("tenant_id")
    val venueId = text("venue_id")
    val id = text("id")
    val itemId = text("item_id")
    val labelFr = text("label_fr")
    val labelEn = text("label_en")
    val priceCents = long("price_cents")
    val sortOrder = integer("sort_order")
    val deleted = bool("deleted")
    override val primaryKey = PrimaryKey(tenantId, venueId, id)
}

object CatalogChanges : Table("catalog_changes") {
    val version = long("version").autoIncrement()
    val tenantId = text("tenant_id")
    val venueId = text("venue_id")
    val kind = text("kind")
    val entityId = text("entity_id")
    val op = text("op")
    val data = jsonb("data")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(version)
}

object ItemPhotos : Table("item_photos") {
    val tenantId = text("tenant_id")
    val venueId = text("venue_id")
    val itemId = text("item_id")
    val content = binary("content")
    val contentType = text("content_type")
    val version = long("version")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(tenantId, venueId, itemId)
}

/** Role default grants (006): one row per (role, permission). */
object RoleGrants : Table("role_grants") {
    val tenantId = text("tenant_id")
    val venueId = text("venue_id")
    val role = text("role")
    val permission = text("permission")
    val granted = bool("granted")
    override val primaryKey = PrimaryKey(tenantId, venueId, role, permission)
}

/** Per-staff grant overrides (006): presence overrides the role default. */
object StaffGrants : Table("staff_grants") {
    val tenantId = text("tenant_id")
    val venueId = text("venue_id")
    val staffId = text("staff_id")
    val permission = text("permission")
    val granted = bool("granted")
    override val primaryKey = PrimaryKey(tenantId, venueId, staffId, permission)
}

/**
 * Store-owned staff projection (012, one-way sync). Venue-scoped: each store's
 * staff list is its own. Display-only — no PIN hash ever reaches the cloud.
 */
object StoreStaff : Table("store_staff") {
    val tenantId = text("tenant_id")
    val venueId = text("venue_id")
    val id = text("id")
    val name = text("name")
    val role = text("role")
    val active = bool("active")
    val deleted = bool("deleted")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(tenantId, venueId, id)
}

/** Single-use terminal pairing codes (009); only the SHA-256 is stored. */
object PairingCodes : Table("pairing_codes") {
    val id = long("id").autoIncrement()
    val tenantId = text("tenant_id")
    val venueId = text("venue_id")
    val codeSha256 = text("code_sha256").uniqueIndex()
    val label = text("label")
    val createdBy = text("created_by")
    val createdAt = timestampWithTimeZone("created_at")
    val expiresAt = timestampWithTimeZone("expires_at")
    val usedAt = timestampWithTimeZone("used_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * Device registry projection (009). The store container owns the tokens; this
 * mirrors its summaries from heartbeats. revoke_requested_at is the portal's
 * intent; revoked flips when the store confirms it applied the revocation.
 */
object Devices : Table("devices") {
    val tenantId = text("tenant_id")
    val venueId = text("venue_id")
    val deviceId = text("device_id")
    val name = text("name")
    val pairedAt = timestampWithTimeZone("paired_at").nullable()
    val lastSeenAt = timestampWithTimeZone("last_seen_at").nullable()
    val revoked = bool("revoked")
    val revokeRequestedAt = timestampWithTimeZone("revoke_requested_at").nullable()
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(tenantId, venueId, deviceId)
}
