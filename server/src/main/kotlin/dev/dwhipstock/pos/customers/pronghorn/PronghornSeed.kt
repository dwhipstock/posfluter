package dev.dwhipstock.pos.customers.pronghorn

import dev.dwhipstock.pos.base.AuthService
import dev.dwhipstock.pos.base.Categories
import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.Users
import dev.dwhipstock.pos.base.VenueSettings
import dev.dwhipstock.pos.customers.pronghorn.PronghornCatalog.Cat
import dev.dwhipstock.pos.customers.pronghorn.PronghornCatalog.Product
import dev.dwhipstock.pos.restaurant.DiningTables
import dev.dwhipstock.pos.restaurant.Zones
import dev.dwhipstock.pos.retail.RetailService
import dev.dwhipstock.pos.sdk.Outbox
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Pronghorn Fuel & Market's first boot: the c-store shelf and the fuel items
 * ([PronghornCatalog]), one register, and three demo staff (manager 1234,
 * cashier 9999, a Spanish-speaking cashier 5555).
 */
object PronghornSeed {

    fun insertProducts(list: List<Product>) {
        Items.batchInsert(list, shouldReturnGeneratedValues = false) { p ->
            this[Items.id] = p.id
            this[Items.nameFr] = p.name
            this[Items.nameEn] = p.name
            this[Items.categoryId] = p.cat.id
            this[Items.abbrev] = p.abbrev.ifEmpty { "?" }
            this[Items.isAlcohol] = p.cat == Cat.BEER && p.ageRestricted
            this[Items.ageRestricted] = p.ageRestricted
            this[Items.taxable] = p.taxable
            this[Items.crvSize] = "NONE"
            this[Items.packUnits] = 1
            this[Items.barcode] = p.shelfCode
            this[Items.brand] = p.brand
            this[Items.subcategory] = p.subcategory
            this[Items.sizeLabel] = p.size
            this[Items.salesWeight] = p.salesWeight
            this[Items.active] = p.active
        }
        // one variant per product, or one per cup size (fountain, slush, coffee)
        val variants = list.flatMap { p -> p.variants.mapIndexed { i, v -> Triple(p, v, i) } }
        ItemVariants.batchInsert(variants, shouldReturnGeneratedValues = false) { (p, v, i) ->
            this[ItemVariants.id] = v.id
            this[ItemVariants.itemId] = p.id
            this[ItemVariants.labelFr] = v.label
            this[ItemVariants.labelEn] = v.label
            this[ItemVariants.priceCents] = v.cents
            this[ItemVariants.sortOrder] = i
            if (v.costCents > 0) this[ItemVariants.costCents] = v.costCents
        }
    }

    /**
     * The fuel items on any Pronghorn store, even one seeded empty
     * (POS_SEED=none): the forecourt rings fuel up as these.
     */
    fun ensureFuelItems() = transaction {
        Categories.insertIgnore { it[id] = Cat.FUEL.id; it[sortOrder] = 0; it[nameFr] = Cat.FUEL.en; it[nameEn] = Cat.FUEL.en }
        val have = Items.selectAll().where { Items.categoryId eq Cat.FUEL.id }.map { it[Items.id] }.toSet()
        val missing = PronghornCatalog.fuel.filter { it.id !in have }
        if (missing.isNotEmpty()) insertProducts(missing)
    }

    fun seedBootstrapManagerIfNoStaff() = transaction {
        if (Users.selectAll().count() > 0) return@transaction
        Users.insert { it[id] = "manager"; it[name] = "Manager"; it[role] = "MANAGER"; it[pin] = AuthService.hashPin("1234"); it[languageCode] = "en" }
    }

    /** First boot only (no staff yet): clears the pub rows the early migrations insert, then seeds the store. */
    fun seedIfEmpty() = transaction {
        if (Users.selectAll().count() > 0) return@transaction
        exec("DELETE FROM item_variants")
        exec("DELETE FROM items")
        exec("DELETE FROM categories")
        exec("DELETE FROM floor_objects")
        exec("DELETE FROM dining_tables")
        exec("DELETE FROM zones")
        exec("DELETE FROM sync_outbox")
        VenueSettings.update({ VenueSettings.id eq 1 }) {
            it[cardProcessor] = "External card terminal"
            it[bankName] = ""
            it[bankAccountNumber] = ""
            it[bankAccountName] = ""
            it[serviceChargePercent] = 0
            it[corkagePerBottleCents] = 0
            it[receiptFooter] = "Thanks for stopping! · ¡Gracias por su visita! · 21+ for beer, tobacco & vape"
            it[venuePhone] = Pronghorn.PHONE
            it[venueAddress] = Pronghorn.ADDRESS
        }
        Cat.entries.forEachIndexed { i, c ->
            Categories.insert { it[id] = c.id; it[sortOrder] = i; it[nameFr] = c.en; it[nameEn] = c.en }
        }
        insertProducts(PronghornCatalog.products)
        Zones.insert {
            it[id] = RetailService.COUNTER_ZONE; it[nameFr] = "Comptoir"; it[nameEn] = "Counter"
            it[sortOrder] = 0; it[labelPrefix] = "R"
        }
        DiningTables.insert {
            it[id] = RetailService.REGISTER_TABLE; it[zoneId] = RetailService.COUNTER_ZONE; it[label] = "1"
            it[shape] = "SQUARE"; it[seats] = 0
        }
        Users.insert { it[id] = "manager"; it[name] = "Demo Manager"; it[role] = "MANAGER"; it[pin] = AuthService.hashPin("1234"); it[languageCode] = "en" }
        Users.insert { it[id] = "cashier"; it[name] = "Demo Cashier"; it[role] = "SERVER"; it[pin] = AuthService.hashPin("9999"); it[languageCode] = "en" }
        Users.insert { it[id] = "cajera"; it[name] = "Cajera Demo"; it[role] = "SERVER"; it[pin] = AuthService.hashPin("5555"); it[languageCode] = "es" }
        Outbox.write("catalog.seeded", "catalog", Pronghorn.VENUE_ID, buildJsonObject {
            put("items", PronghornCatalog.products.size)
            put("categories", Cat.entries.size)
        })
    }
}
