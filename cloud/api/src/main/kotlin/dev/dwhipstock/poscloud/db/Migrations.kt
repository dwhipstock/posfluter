package dev.dwhipstock.poscloud.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Minimal forward-only migration runner (ported from the store server).
 * Numbered SQL scripts live on the filesystem (MIGRATIONS_DIR, default
 * cloud/migrations); each is applied once, in version order, inside its own
 * transaction, and recorded in schema_migrations. No down-migrations —
 * fix-forward with a new script.
 *
 * Statement splitting: a statement ends at a `;` at end of line. Don't put
 * `;` mid-line inside string literals in migration scripts.
 */
object Migrations {

    private val log = LoggerFactory.getLogger(Migrations::class.java)

    data class Script(val version: Int, val name: String, val sql: String)

    fun run(db: Database, dir: File) {
        transaction(db) {
            exec(
                """CREATE TABLE IF NOT EXISTS schema_migrations (
                   version INTEGER NOT NULL PRIMARY KEY,
                   name VARCHAR(200) NOT NULL,
                   applied_at TIMESTAMP NOT NULL DEFAULT now())"""
            )
        }
        val applied = transaction(db) {
            val versions = mutableSetOf<Int>()
            exec("SELECT version FROM schema_migrations") { rs ->
                while (rs.next()) versions += rs.getInt(1)
            }
            versions
        }
        val pending = discover(dir).filter { it.version !in applied }.sortedBy { it.version }
        for (script in pending) {
            // one transaction per script: a failure stops startup with earlier
            // scripts committed, so a fixed re-run resumes where it stopped
            transaction(db) {
                statements(script.sql).forEach { exec(it) }
                exec(
                    "INSERT INTO schema_migrations (version, name, applied_at) " +
                        "VALUES (${script.version}, '${script.name}', now())"
                )
            }
            log.info("applied migration ${script.name}")
        }
        if (pending.isEmpty()) log.info("schema up to date (${applied.size} migrations)")
    }

    private fun discover(dir: File): List<Script> {
        require(dir.isDirectory) { "migrations dir not found: ${dir.absolutePath}" }
        val scripts = (dir.listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".sql") }
            .map { file ->
                val version = file.name.substringBefore('_').toIntOrNull()
                    ?: error("migration file '${file.name}' must start with a number (e.g. 002_add_x.sql)")
                Script(version, file.name, file.readText())
            }
        scripts.groupBy { it.version }.forEach { (v, group) ->
            require(group.size == 1) { "duplicate migration version $v: ${group.map { it.name }}" }
        }
        return scripts
    }

    private fun statements(sql: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (line in sql.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("--")) continue
            current.appendLine(line)
            if (trimmed.endsWith(";")) {
                out += current.toString().trim().removeSuffix(";")
                current.clear()
            }
        }
        require(current.isBlank()) { "migration ends mid-statement (missing trailing ';'?)" }
        return out
    }
}
