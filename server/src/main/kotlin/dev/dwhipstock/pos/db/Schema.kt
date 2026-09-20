package dev.dwhipstock.pos.db

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.update
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.sql.Connection

/**
 * SDK-tier infrastructure: outbox table + database init. Schema is owned by
 * the numbered scripts in resources/migrations/ (see Migrations); the Exposed
 * table objects across the tiers are the query DSL only and must stay in sync
 * with the migrations that create them.
 */

/** Every mutation writes here, same DB transaction. No consumer until M6. */
object SyncOutbox : IntIdTable("sync_outbox") {
    val eventId = varchar("event_id", 36).uniqueIndex() // uuid
    val eventType = varchar("event_type", 64) // e.g. check.opened
    val aggregateType = varchar("aggregate_type", 32)
    val aggregateId = varchar("aggregate_id", 64)
    val payload = text("payload") // json
    val createdAt = datetime("created_at")
}

/**
 * Cloud sync bookkeeping (CONTRACT.md §5): push_hwm, catalog_cursor,
 * catalog_snapshot_seq. Helpers must run inside a transaction, like Outbox.
 */
object SyncState : Table("sync_state") {
    val key = varchar("key", 64)
    val value = text("value")
    override val primaryKey = PrimaryKey(key)

    fun get(k: String): String? =
        selectAll().where { key eq k }.firstOrNull()?.get(value)

    fun set(k: String, v: String) {
        val updated = update({ key eq k }) { it[value] = v }
        if (updated == 0) insert { it[key] = k; it[value] = v }
    }
}

fun initDatabase(dbPath: String): Database {
    // WAL + a busy timeout are what keep this single-writer SQLite file from
    // throwing SQLITE_BUSY under concurrent load (the sync loop, request
    // handlers, and the staff app all hit it at once). Exposed opens a fresh
    // connection per transaction, so both must be set by the driver on *every*
    // connection — SQLiteConfig applies them at connection open, before any
    // transaction, so no connection can miss them and WAL is switched outside a
    // transaction (the only place PRAGMA journal_mode may run):
    //   journal_mode=WAL  readers and the single writer stop blocking each
    //                     other; persisted in the file header and reasserted
    //                     per connection, so it self-heals.
    //   busy_timeout=5000 a connection that meets the write lock waits up to 5s
    //                     for it instead of failing immediately.
    val config = SQLiteConfig().apply {
        setJournalMode(SQLiteConfig.JournalMode.WAL)
        setBusyTimeout(5000)
    }
    val dataSource = SQLiteDataSource(config).apply { url = "jdbc:sqlite:$dbPath" }
    val db = Database.connect(dataSource)
    // SQLite: one writer at a time, serializable is the honest level
    TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
    Migrations.run(db)
    return db
}
