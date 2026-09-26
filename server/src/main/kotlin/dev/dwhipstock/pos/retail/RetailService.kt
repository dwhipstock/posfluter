package dev.dwhipstock.pos.retail

import dev.dwhipstock.pos.base.AgeChecks
import dev.dwhipstock.pos.base.AgeVerdict
import dev.dwhipstock.pos.base.Aamva
import dev.dwhipstock.pos.base.AgeMath
import dev.dwhipstock.pos.base.Categories
import dev.dwhipstock.pos.base.IdDates
import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.Upc
import dev.dwhipstock.pos.restaurant.BadRequestException
import dev.dwhipstock.pos.restaurant.CheckLines
import dev.dwhipstock.pos.restaurant.CheckService
import dev.dwhipstock.pos.restaurant.CheckView
import dev.dwhipstock.pos.restaurant.Checks
import dev.dwhipstock.pos.restaurant.ConflictException
import dev.dwhipstock.pos.restaurant.DiningTables
import dev.dwhipstock.pos.restaurant.NotFoundException
import dev.dwhipstock.pos.restaurant.Zones
import dev.dwhipstock.pos.sdk.CustomerConfig
import dev.dwhipstock.pos.sdk.Crv
import dev.dwhipstock.pos.sdk.Outbox
import dev.dwhipstock.pos.sdk.VenueClock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Retail counter sales on the SDK Transaction (a check on the store's one
 * register — no tables, no floor plan, no kitchen). Scan or pick a product →
 * a basket line; payment, receipts, refunds and sync are the check pipeline's.
 * Adds what a bottle shop needs on top: barcode lookup, the ID check for
 * age-restricted items, and adding an unknown product on the spot.
 */
class RetailService(
    private val config: CustomerConfig,
    private val checks: CheckService,
    private val lookup: ProductLookup = ProductLookup.NONE,
    /** age.check: an ID for every restricted sale (default), or a visual check over N (never for tobacco). */
    private val ageCheckMode: dev.dwhipstock.pos.sdk.AgeCheckMode = dev.dwhipstock.pos.sdk.AgeCheckMode.ALWAYS,
) {
    companion object {
        const val COUNTER_ZONE = "counter"
        const val REGISTER_TABLE = "register-1"
    }

    /** The register exists on every retail store (seeded; re-created if someone removed it). */
    fun ensureRegister() = transaction {
        Zones.insertIgnore {
            it[id] = COUNTER_ZONE; it[nameFr] = "Comptoir"; it[nameEn] = "Counter"; it[sortOrder] = 0
            it[labelPrefix] = "R"
        }
        if (DiningTables.selectAll().where { DiningTables.id eq REGISTER_TABLE }.empty()) {
            DiningTables.insert {
                it[id] = REGISTER_TABLE; it[zoneId] = COUNTER_ZONE; it[label] = "1"
                it[shape] = "SQUARE"; it[seats] = 0
            }
        }
    }

    /** The sale in progress on the register, or a new one — idempotent like opening a table. */
    fun openSale(userId: String): CheckView {
        ensureRegister()
        return checks.openCheck(REGISTER_TABLE, userId)
    }

    /** The sale in progress, if any (a restarted terminal picks it back up). */
    fun currentSale(): CheckView? = checks.openCheckForTable(REGISTER_TABLE)

    /**
     * A scanned (or typed) barcode → one more of that product on the sale: the
     * same product again bumps its line's qty. Unknown → 404 `unknown_barcode`
     * so the terminal can offer to add it.
     */
    fun scan(checkId: Int, rawBarcode: String): CheckView {
        val code = Upc.normalize(rawBarcode)
        require(code.isNotEmpty() && code.length <= 32) { "barcode must be 1-32 characters" }
        val (itemId, variantId) = transaction {
            val item = Items.selectAll().where {
                (Items.barcode eq code) and Items.deletedAt.isNull()
            }.firstOrNull() ?: throw NotFoundException("no product with barcode $code", "unknown_barcode")
            if (!item[Items.active]) throw ConflictException("${item[Items.nameEn]} is off sale", "item_inactive")
            val variant = ItemVariants.selectAll().where {
                (ItemVariants.itemId eq item[Items.id]) and ItemVariants.deletedAt.isNull()
            }.orderBy(ItemVariants.sortOrder).firstOrNull()
                ?: throw NotFoundException("product ${item[Items.id]} has no price", "unknown_barcode")
            item[Items.id] to variant[ItemVariants.id]
        }
        return addOne(checkId, itemId, variantId)
    }

    /** One more [itemId]/[variantId]: an existing plain line's qty goes up, else a new line. */
    fun addOne(checkId: Int, itemId: String, variantId: String): CheckView {
        val existing = transaction {
            CheckLines.selectAll().where {
                (CheckLines.checkId eq checkId) and (CheckLines.itemId eq itemId) and
                    (CheckLines.variantId eq variantId) and (CheckLines.status eq "ACTIVE") and CheckLines.note.isNull()
            }.firstOrNull()?.let { it[CheckLines.id].value to it[CheckLines.qty] }
        }
        return if (existing != null) checks.setLineQty(checkId, existing.first, existing.second + 1)
        else checks.addLine(checkId, itemId, variantId, 1, null)
    }

    /**
     * Record an ID check. [scan] is the text a 2D scanner typed from the back
     * of a US / Canadian licence (AAMVA); otherwise [dateOfBirth] (YYYY-MM-DD)
     * from the cashier's dropdowns, with [cashierSawId] confirming they held
     * the ID. Age is computed on the store's own business day. Only the
     * outcome is kept and synced (`age.checked`) — never the scan, the date of
     * birth or anything else from the card.
     */
    fun checkAge(
        checkId: Int, method: String, scan: String?, dateOfBirth: String?, cashierSawId: Boolean, userId: String,
    ): AgeCheckResult = transaction {
        val check = Checks.selectAll().where { Checks.id eq checkId }.firstOrNull()
            ?: throw NotFoundException("check $checkId not found")
        if (check[Checks.status] !in listOf("OPEN", "TOTAL_LOCKED"))
            throw ConflictException("check $checkId is ${check[Checks.status]}", "check_not_open")
        val m = method.trim().uppercase()
        if (m == dev.dwhipstock.pos.restaurant.AgeGate.VISUAL) return@transaction visualCheck(checkId, cashierSawId, userId)
        val dates: IdDates? = when (m) {
            "SCAN" -> Aamva.parse(scan)
            "MANUAL" -> AgeMath.parseIsoDate(dateOfBirth)?.let { IdDates(it, null) }
            else -> throw BadRequestException("method must be SCAN or MANUAL", "bad_method")
        }
        val legalAge = config.legalAge
        val verdict = AgeVerdict.of(dates, legalAge, VenueClock.today(), cashierSawId = m == "SCAN" || cashierSawId)
        val now = VenueClock.now()
        AgeChecks.insertAndGetId {
            it[AgeChecks.checkId] = checkId
            it[AgeChecks.method] = m
            it[passed] = verdict.passed
            it[ageYears] = verdict.ageYears
            it[AgeChecks.legalAge] = legalAge
            it[reason] = verdict.reason
            it[checkedBy] = userId
            it[checkedAt] = now
        }
        Outbox.write("age.checked", "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
            put("method", m)
            put("passed", verdict.passed)
            verdict.ageYears?.let { put("ageYears", it) }
            put("legalAge", legalAge)
            verdict.reason?.let { put("reason", it) }
            put("checkedBy", userId)
            put("checkedAt", VenueClock.iso(now))
        })
        AgeCheckResult(verdict.passed, verdict.ageYears, legalAge, verdict.reason, checks.getCheck(checkId))
    }

    /**
     * `age.check=looks-under:N`: the cashier confirms the customer clearly looks
     * over N, no ID taken. Refused while the store asks for an ID every time,
     * and for a sale with tobacco or vape (always an ID). Only the outcome is kept.
     */
    private fun visualCheck(checkId: Int, confirmed: Boolean, userId: String): AgeCheckResult {
        val over = ageCheckMode.looksOver
            ?: throw ConflictException("this store checks an ID for every age-restricted sale", "id_required")
        if (dev.dwhipstock.pos.restaurant.AgeGate.tobaccoOnSale(checkId))
            throw ConflictException("tobacco and vape always need an ID check", "id_required_tobacco")
        if (!confirmed) throw BadRequestException("confirm the customer looks over $over", "not_confirmed")
        val now = VenueClock.now()
        val legalAge = config.legalAge
        AgeChecks.insertAndGetId {
            it[AgeChecks.checkId] = checkId
            it[AgeChecks.method] = dev.dwhipstock.pos.restaurant.AgeGate.VISUAL
            it[passed] = true
            it[ageYears] = null
            it[AgeChecks.legalAge] = legalAge
            it[reason] = null
            it[checkedBy] = userId
            it[checkedAt] = now
        }
        Outbox.write("age.checked", "check", checkId.toString(), buildJsonObject {
            put("checkId", checkId)
            put("method", dev.dwhipstock.pos.restaurant.AgeGate.VISUAL)
            put("passed", true)
            put("legalAge", legalAge)
            put("looksOver", over)
            put("checkedBy", userId)
            put("checkedAt", VenueClock.iso(now))
        })
        return AgeCheckResult(true, null, legalAge, null, checks.getCheck(checkId))
    }

    /** A suggested name for an unknown barcode (Open Food Facts), or null. Never throws. */
    fun suggest(barcode: String): ProductLookup.Suggestion? =
        runCatching { lookup.suggest(Upc.normalize(barcode)) }.getOrNull()

    /**
     * A manager adds the product behind an unknown barcode, right at the
     * counter: one item, one price. It syncs up like any menu edit.
     */
    fun addProduct(req: NewProductRequest): NewProductResult = transaction {
        val code = Upc.normalize(req.barcode)
        require(code.isNotEmpty() && code.length <= 32 && code.all(Char::isLetterOrDigit)) { "barcode must be 1-32 letters or digits" }
        val name = req.name.trim()
        require(name.isNotEmpty() && name.length <= 200) { "name is required" }
        require(req.priceCents in 1..10_000_000) { "price must be positive" }
        require(req.packUnits in 1..48) { "units in the pack must be 1-48" }
        val size = Crv.size(req.crvSize)
        Categories.selectAll().where { Categories.id eq req.categoryId }.firstOrNull()
            ?: throw NotFoundException("category ${req.categoryId} not found")
        if (Items.selectAll().where { (Items.barcode eq code) and Items.deletedAt.isNull() }.any())
            throw ConflictException("barcode $code already belongs to a product", "barcode_taken")
        val base = "p-" + name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "item" }
        var itemId = base
        var n = 2
        while (Items.selectAll().where { Items.id eq itemId }.any()) itemId = "$base-${n++}"
        val abbrev = name.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
            .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
        Items.insert {
            it[id] = itemId
            it[nameFr] = name
            it[nameEn] = name
            it[categoryId] = req.categoryId
            it[Items.abbrev] = abbrev
            it[isAlcohol] = req.ageRestricted
            it[ageRestricted] = req.ageRestricted
            it[taxable] = req.taxable
            it[crvSize] = size.name
            it[packUnits] = req.packUnits
            it[barcode] = code
            it[active] = true
        }
        val variantId = "$itemId:each"
        ItemVariants.insert {
            it[id] = variantId
            it[ItemVariants.itemId] = itemId
            it[labelFr] = "Each"
            it[labelEn] = "Each"
            it[priceCents] = req.priceCents
            it[sortOrder] = 0
        }
        Outbox.write("item.created", "item", itemId, buildJsonObject {
            put("itemId", itemId)
            put("nameFr", name)
            put("nameEn", name)
            put("categoryId", req.categoryId)
            put("variantCount", 1)
            put("barcode", code)
            put("addedAt", "counter")
            put("item", dev.dwhipstock.pos.api.itemSnapshotJson(itemId))
        })
        NewProductResult(itemId, variantId, code)
    }
}

@Serializable
data class ScanRequest(val barcode: String)

@Serializable
data class AgeCheckRequest(
    /** SCAN | MANUAL */
    val method: String,
    /** The text a 2D scanner typed from the ID's barcode (SCAN). Used once, never stored. */
    val scan: String? = null,
    /** YYYY-MM-DD from the dropdowns (MANUAL). Used once, never stored. */
    val dateOfBirth: String? = null,
    /** MANUAL: the cashier confirms they saw the ID. */
    val cashierSawId: Boolean = false,
)

@Serializable
data class AgeCheckResult(
    val passed: Boolean,
    val ageYears: Int?,
    val legalAge: Int,
    /** under_age | expired | unreadable | not_confirmed; null when passed */
    val reason: String?,
    val check: CheckView,
)

@Serializable
data class NewProductRequest(
    val barcode: String,
    val name: String,
    val priceCents: Long,
    val categoryId: String,
    val ageRestricted: Boolean = false,
    /** NONE | SMALL (under 24 oz) | LARGE (24 oz or more) */
    val crvSize: String = "NONE",
    val packUnits: Int = 1,
    val taxable: Boolean = true,
    val managerPin: String? = null,
)

@Serializable
data class NewProductResult(val itemId: String, val variantId: String, val barcode: String)

@Serializable
data class LookupResponse(val barcode: String, val name: String? = null, val source: String? = null)
