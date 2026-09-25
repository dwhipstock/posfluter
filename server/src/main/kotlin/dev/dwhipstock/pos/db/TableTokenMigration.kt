package dev.dwhipstock.pos.db

import dev.dwhipstock.pos.restaurant.TableTokens
import org.jetbrains.exposed.sql.Transaction

/**
 * Migration 033 (code: tokens come from SecureRandom, not SQLite's PRNG): add
 * `dining_tables.public_token`, give every existing table (live or deleted) its
 * own random token, then enforce uniqueness. New tables get one on insert (the
 * column's client default), including sub-tables.
 */
object TableTokenMigration {
    const val VERSION = 33
    const val NAME = "033_table_public_tokens (code)"

    fun run(tx: Transaction) {
        tx.exec("ALTER TABLE dining_tables ADD COLUMN public_token VARCHAR(32)")
        val ids = mutableListOf<String>()
        tx.exec("SELECT id FROM dining_tables") { rs -> while (rs.next()) ids += rs.getString(1) }
        for (id in ids) {
            // token alphabet is [A-Za-z0-9_-] (no quoting needed); ids are quoted
            val quoted = id.replace("'", "''")
            tx.exec("UPDATE dining_tables SET public_token = '${TableTokens.newToken()}' WHERE id = '$quoted'")
        }
        tx.exec("CREATE UNIQUE INDEX idx_dining_tables_public_token ON dining_tables(public_token)")
    }
}
