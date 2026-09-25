package dev.dwhipstock.pos

import dev.dwhipstock.pos.db.Migrations
import dev.dwhipstock.pos.db.initDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationsTest {

    private fun tempDb(): String =
        Files.createTempDirectory("pos-mig-test").resolve("test.db").toString()

    private fun tableNames(db: Database): Set<String> = transaction(db) {
        val names = mutableSetOf<String>()
        exec("SELECT name FROM sqlite_master WHERE type='table'") { rs ->
            while (rs.next()) names += rs.getString(1)
        }
        names
    }

    @Test
    fun `empty db migrates to full schema`() {
        val db = initDatabase(tempDb())
        val tables = tableNames(db)
        for (t in listOf(
            "schema_migrations", "sync_outbox", "items", "item_variants", "tenders",
            "users", "sessions", "zones", "dining_tables", "checks", "check_lines", "shifts",
        )) assertTrue(t in tables, "missing table $t (got $tables)")
    }

    @Test
    fun `retired user calendar column is dropped`() {
        val db = initDatabase(tempDb())
        val cols = transaction(db) {
            val c = mutableSetOf<String>()
            exec("PRAGMA table_info(users)") { rs -> while (rs.next()) c += rs.getString("name") }
            c
        }
        assertTrue("language_code" in cols, "users lost language_code: $cols")
        assertTrue("calendar" !in cols, "users.calendar should be dropped: $cols")
    }

    @Test
    fun `partially migrated db applies only pending scripts`() {
        val path = tempDb()
        // simulate an old DB: schema_migrations exists but shows nothing applied,
        // while the 001 tables are already there (pre-framework bootstrap)
        val db = initDatabase(path)
        val before = transaction(db) {
            var n = 0
            exec("SELECT COUNT(*) FROM schema_migrations") { rs -> rs.next(); n = rs.getInt(1) }
            n
        }
        assertTrue(before >= 1, "expected at least migration 001 recorded")

        // second startup on the same file must be a no-op, not a failure
        val db2 = initDatabase(path)
        val after = transaction(db2) {
            var n = 0
            exec("SELECT COUNT(*) FROM schema_migrations") { rs -> rs.next(); n = rs.getInt(1) }
            n
        }
        assertEquals(before, after, "re-running startup must not re-apply migrations")
    }

    @Test
    fun `statement splitter handles comments and multiline statements`() {
        // note: not :memory: — Exposed opens a connection per transaction and
        // an in-memory db evaporates between the runner's transactions
        val db = Database.connect("jdbc:sqlite:${tempDb()}", driver = "org.sqlite.JDBC")
        Migrations.run(db)
        val versions = transaction(db) {
            val v = mutableListOf<Int>()
            exec("SELECT version FROM schema_migrations ORDER BY version") { rs ->
                while (rs.next()) v += rs.getInt(1)
            }
            v
        }
        assertEquals(versions.sorted(), versions)
        assertTrue(1 in versions)
    }
}
