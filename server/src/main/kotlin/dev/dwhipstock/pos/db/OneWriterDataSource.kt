package dev.dwhipstock.pos.db

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/**
 * The store database, one transaction at a time, first come first served, on
 * one long-lived SQLite connection.
 *
 * SQLite has a single writer. Exposed used to open a fresh connection per
 * transaction, and a transaction that reads and then writes (open a check, add
 * a line — most of them) has to upgrade its read snapshot to the write lock.
 * When another connection wrote in between, SQLite fails that upgrade at once
 * with SQLITE_BUSY / SQLITE_BUSY_SNAPSHOT; the busy timeout cannot help,
 * because the snapshot is already stale. In the load test a third of the sales
 * failed that way with 10 devices, and most of them with 25
 * (docs/load-test-report.md).
 *
 * Handing out the connection to one transaction at a time, in arrival order,
 * removes the race inside the process. The connection also begins IMMEDIATE
 * (see [initDatabase]), so another process on the same file (a backup, the
 * sqlite3 shell) makes a sale wait for the lock instead of failing. Keeping
 * the one connection open saves what a fresh connection costs on every
 * transaction: opening the file and parsing the whole schema before the first
 * statement. Store transactions take a few milliseconds, so the queue stays
 * short; a fair queue in the JVM also beats SQLite's own busy loop, which
 * sleeps in growing steps (with it a request waited up to a second at 25
 * devices).
 *
 * Exposed "closes" the connection when its transaction ends: that ends any
 * transaction still open on it, puts it back in auto-commit and hands it to
 * the next waiter. A connection that fails is dropped and reopened. A caller
 * that waits longer than [waitSeconds] gets an SQLException (a 500) instead of
 * hanging.
 */
class OneWriterDataSource(
    private val inner: DataSource,
    private val waitSeconds: Long = 30,
) : DataSource by inner {

    private val permit = Semaphore(1, true)
    private var physical: Connection? = null // guarded by [permit]

    override fun getConnection(): Connection = lease { inner.connection }

    override fun getConnection(username: String?, password: String?): Connection =
        lease { inner.getConnection(username, password) }

    private fun lease(open: () -> Connection): Connection {
        if (!permit.tryAcquire(waitSeconds, TimeUnit.SECONDS)) {
            throw SQLException("store database busy for ${waitSeconds}s", "SQLITE_BUSY")
        }
        val connection = try {
            physical?.takeUnless { it.isClosed } ?: open().also { physical = it }
        } catch (e: Throwable) {
            physical = null
            permit.release()
            throw e
        }
        val returned = AtomicBoolean(false)
        fun giveBack() {
            if (!returned.compareAndSet(false, true)) return
            try {
                if (!connection.autoCommit) {
                    connection.rollback() // whatever was left open; Exposed has committed its work
                    connection.autoCommit = true
                }
            } catch (_: SQLException) {
                runCatching { connection.close() }
                physical = null
            } finally {
                permit.release()
            }
        }
        return Proxy.newProxyInstance(
            Connection::class.java.classLoader, arrayOf(Connection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "close" -> giveBack().let { null }
                "isClosed" -> returned.get() || connection.isClosed
                else -> {
                    if (returned.get()) throw SQLException("connection already returned")
                    try {
                        method.invoke(connection, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                }
            }
        } as Connection
    }
}
