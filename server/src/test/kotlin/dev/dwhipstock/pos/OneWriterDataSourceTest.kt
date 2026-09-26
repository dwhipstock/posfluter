package dev.dwhipstock.pos

import dev.dwhipstock.pos.db.OneWriterDataSource
import dev.dwhipstock.pos.db.SyncState
import dev.dwhipstock.pos.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The store's database access under concurrency (load test, docs/load-test-report.md):
 * a transaction that reads and then writes — nearly every POS action — used to
 * fail with SQLITE_BUSY / SQLITE_BUSY_SNAPSHOT whenever another device wrote in
 * between, because SQLite cannot upgrade a stale read snapshot to a write.
 */
class OneWriterDataSourceTest {

    private fun dbFile() = Files.createTempDirectory("pos-one-writer").resolve("pos.db").toString()

    @Test
    fun `read-then-write transactions from many devices never fail`() {
        val db = initDatabase(dbFile())
        val threads = 16
        val perThread = 40
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val errors = ConcurrentLinkedQueue<Throwable>()
        repeat(threads) { t ->
            Thread {
                try {
                    start.await()
                    repeat(perThread) { i ->
                        // maxAttempts = 1: Exposed's retry must not be what hides a lost race
                        transaction(db) {
                            maxAttempts = 1
                            val seen = SyncState.selectAll().count() // the read snapshot…
                            SyncState.set("t$t-i$i", seen.toString()) // …then the upgrade to a write
                        }
                    }
                } catch (e: Throwable) {
                    errors += e
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        assertTrue(done.await(120, TimeUnit.SECONDS), "transactions deadlocked or timed out")
        assertTrue(errors.isEmpty(), "${errors.size} transaction(s) failed, first: ${errors.firstOrNull()?.message}")
        assertEquals((threads * perThread).toLong(), transaction(db) { SyncState.selectAll().count() })
    }

    /** Counts the physical connections the wrapped data source really opens. */
    private class Counting(private val inner: DataSource) : DataSource by inner {
        val opened = AtomicInteger()
        override fun getConnection(): Connection = inner.connection.also { opened.incrementAndGet() }
    }

    private fun sqlite(path: String): SQLiteDataSource {
        val config = SQLiteConfig().apply {
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setBusyTimeout(5000)
            setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE)
        }
        return SQLiteDataSource(config).apply { url = "jdbc:sqlite:$path" }
    }

    @Test
    fun `one connection serves every transaction`() {
        val counting = Counting(sqlite(dbFile()))
        val db = Database.connect(OneWriterDataSource(counting))
        transaction(db) { exec("CREATE TABLE t (x INTEGER)") }
        repeat(200) { i -> transaction(db) { exec("INSERT INTO t VALUES ($i)") } }
        val n = transaction(db) {
            var c = 0
            exec("SELECT count(*) FROM t") { rs -> if (rs.next()) c = rs.getInt(1) }
            c
        }
        assertEquals(200, n)
        assertEquals(1, counting.opened.get(), "the connection must be reused, not reopened per transaction")
    }

    @Test
    fun `a failed transaction is rolled back and frees the connection`() {
        val db = Database.connect(OneWriterDataSource(sqlite(dbFile())))
        transaction(db) { exec("CREATE TABLE t (x INTEGER)") }
        runCatching {
            transaction(db) {
                maxAttempts = 1
                exec("INSERT INTO t VALUES (1)")
                error("the sale failed half way")
            }
        }
        val n = transaction(db) {
            var c = -1
            exec("SELECT count(*) FROM t") { rs -> if (rs.next()) c = rs.getInt(1) }
            c
        }
        assertEquals(0, n, "the half-done write must not survive")
    }

    @Test
    fun `a waiter that cannot get the connection fails instead of hanging`() {
        val ds = OneWriterDataSource(sqlite(dbFile()), waitSeconds = 1)
        val held = ds.connection // never returned
        try {
            val t0 = System.nanoTime()
            assertFailsWith<SQLException> { ds.connection }
            assertTrue((System.nanoTime() - t0) / 1_000_000 in 900..5000, "waits about waitSeconds")
        } finally {
            held.close()
        }
        ds.connection.close() // free again once returned
    }
}
