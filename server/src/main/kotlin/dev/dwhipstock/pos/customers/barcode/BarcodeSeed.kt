package dev.dwhipstock.pos.customers.barcode

import dev.dwhipstock.pos.base.AuthService
import dev.dwhipstock.pos.base.Users
import dev.dwhipstock.pos.base.VenueSettings
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Barcode Pub's first boot: a BLANK store. No menu, no rooms, no tables (the
 * owner builds them in the portal or with the AI); just the receipt header,
 * the corkage fee and ONE bootstrap manager (PIN 1234, changed on first
 * sign-in). Whatever POS_SEED says, this is all Barcode ever gets.
 *
 * The early migrations INSERT the demo pub's menu, floor plan and settings
 * into every store, so a never-synced, never-used store clears them first.
 * Gate: no staff and no install_id. Once a store has staff or has synced,
 * nothing here touches it again.
 */
object BarcodeSeed {
    fun seedIfEmpty() = transaction {
        if (Users.selectAll().count() > 0) return@transaction
        if (dev.dwhipstock.pos.db.SyncState.get("install_id") == null) {
            exec("DELETE FROM item_variants")
            exec("DELETE FROM items")
            exec("DELETE FROM categories")
            exec("DELETE FROM floor_objects")
            exec("DELETE FROM dining_tables")
            exec("DELETE FROM zones")
            exec("DELETE FROM sync_outbox")
            VenueSettings.update({ VenueSettings.id eq 1 }) {
                it[cardProcessor] = ""
                it[bankName] = ""
                it[bankAccountNumber] = ""
                it[bankAccountName] = ""
                it[serviceChargePercent] = 0
                it[corkagePerBottleCents] = Barcode.CORKAGE_PER_BOTTLE_SATANG
                it[receiptFooter] = Barcode.RECEIPT_FOOTER
                it[venuePhone] = Barcode.PHONE
                it[venueAddress] = Barcode.ADDRESS
            }
        }
        // ONE manager, PIN 1234: nobody could ever sign in to an empty store
        Users.insert {
            it[id] = "manager"; it[name] = "Manager"; it[role] = "MANAGER"
            it[pin] = AuthService.hashPin("1234"); it[languageCode] = "th"
        }
    }
}
