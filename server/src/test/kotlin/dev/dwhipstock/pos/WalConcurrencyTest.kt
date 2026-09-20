package dev.dwhipstock.pos

import dev.dwhipstock.pos.db.SyncState
import dev.dwhipstock.pos.db.initDatabase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the SQLITE_BUSY fix in [initDatabase]: WAL + a 5s busy_timeout, applied
 * by the driver to every connection Exposed opens (one per transaction). Without
 * the busy_timeout, concurrent writers throw "SQLITE_BUSY: database is locked"
 * instead of waiting for the single write lock.
 *
 * The concurrency test pins maxAttempts=1: Exposed's default 3x retry would
 * otherwise absorb a reverted busy_timeout (the tiny writes usually succeed on a
 * retry), so the test would stay green even with the fix removed. One attempt
 * makes the assertion depend on busy_timeout alone, so it fails if the fix is
 * reverted. (The pragma test below is the other, direct fail-on-revert guard.)
 */
class WalConcurrencyTest {

    private fun freshDb() =
        initDatabase(Files.createTempDirectory("pos-wal-test").resolve("pos.db").toString())

    @Test
    fun `every connection gets WAL and the busy timeout`() {
        val db = freshDb()
        // Each transaction is a fresh connection. busy_timeout is per-connection
        // and not persisted, so reading 5000 back proves the driver re-applied it
        // on this connection (not just once at init). journal_mode lives in the
        // file header, so reading 'wal' proves WAL is active for every connection.
        val journalMode = transaction(db) {
            var mode = ""
            exec("PRAGMA journal_mode") { rs -> if (rs.next()) mode = rs.getString(1) }
            mode
        }
        val busyTimeout = transaction(db) {
            var ms = -1
            exec("PRAGMA busy_timeout") { rs -> if (rs.next()) ms = rs.getInt(1) }
            ms
        }
        assertEquals("wal", journalMode.lowercase(), "journal_mode must be WAL")
        assertEquals(5000, busyTimeout, "busy_timeout must be 5000ms on every connection")
    }

    @Test
    fun `concurrent writers do not hit SQLITE_BUSY`() {
        val db = freshDb()
        val threads = 8
        val insertsPerThread = 40
        val start = CountDownLatch(1) // release all threads at once for max contention
        val done = CountDownLatch(threads)
        val errors = ConcurrentLinkedQueue<Throwable>()

        repeat(threads) { t ->
            Thread {
                try {
                    start.await()
                    repeat(insertsPerThread) { i ->
                        // one write per transaction → one connection contending for
                        // the write lock each time; exactly the sync-loop-vs-handler
                        // race. maxAttempts=1 disables Exposed's retry so a lost race
                        // surfaces as SQLITE_BUSY unless busy_timeout made it wait.
                        // Unique keys (no cross-thread collision) → exactly 320 rows.
                        transaction(db) {
                            maxAttempts = 1
                            SyncState.set("t$t-i$i", "v")
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
        assertTrue(done.await(60, TimeUnit.SECONDS), "writers deadlocked or timed out")

        assertTrue(
            errors.isEmpty(),
            "concurrent writes threw ${errors.size} error(s), first: ${errors.firstOrNull()?.message}",
        )
        val rows = transaction(db) { SyncState.selectAll().count() }
        assertEquals((threads * insertsPerThread).toLong(), rows, "every write must have committed")
    }
}
