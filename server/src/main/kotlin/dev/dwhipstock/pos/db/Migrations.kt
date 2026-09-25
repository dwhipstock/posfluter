package dev.dwhipstock.pos.db

import dev.dwhipstock.pos.StoreAssets
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.net.JarURLConnection

/**
 * Minimal forward-only migration runner. Numbered SQL scripts live in
 * resources/migrations/ (e.g. 002_user_prefs.sql); each is applied once, in
 * version order, inside its own transaction, and recorded in schema_migrations.
 * No down-migrations in v1 — fix-forward with a new script.
 *
 * Statement splitting: a statement ends at a `;` at end of line. Don't put
 * `;` mid-line inside string literals in migration scripts.
 */
object Migrations {

    private val log = LoggerFactory.getLogger(Migrations::class.java)
    private const val DIR = "migrations"

    /** A numbered step: SQL text from resources, or (rarely) Kotlin when SQL can't do it. */
    data class Script(
        val version: Int, val name: String, val sql: String,
        val code: (org.jetbrains.exposed.sql.Transaction.() -> Unit)? = null,
    )

    /** Kotlin-only steps, interleaved with the SQL scripts by version. */
    private val codeMigrations = listOf(
        Script(UtcTimestampMigration.VERSION, UtcTimestampMigration.NAME, "") { UtcTimestampMigration.run(this) },
        Script(TableTokenMigration.VERSION, TableTokenMigration.NAME, "") { TableTokenMigration.run(this) },
    )

    fun run(db: Database) {
        transaction(db) {
            exec(
                """CREATE TABLE IF NOT EXISTS schema_migrations (
                   version INTEGER NOT NULL PRIMARY KEY,
                   name VARCHAR(200) NOT NULL,
                   applied_at TEXT NOT NULL)"""
            )
        }
        val applied = transaction(db) {
            val versions = mutableSetOf<Int>()
            exec("SELECT version FROM schema_migrations") { rs ->
                while (rs.next()) versions += rs.getInt(1)
            }
            versions
        }
        val all = discover() + codeMigrations
        all.groupBy { it.version }.forEach { (v, group) ->
            require(group.size == 1) { "duplicate migration version $v: ${group.map { it.name }}" }
        }
        val pending = all.filter { it.version !in applied }.sortedBy { it.version }
        for (script in pending) {
            // one transaction per script: a failure stops startup with earlier
            // scripts committed, so a fixed re-run resumes where it stopped
            transaction(db) {
                script.code?.invoke(this) ?: statements(script.sql).forEach { exec(it) }
                exec(
                    "INSERT INTO schema_migrations (version, name, applied_at) " +
                        "VALUES (${script.version}, '${script.name}', datetime('now'))"
                )
            }
            log.info("applied migration ${script.name}")
        }
        if (pending.isEmpty()) log.info("schema up to date (${applied.size} migrations)")
    }

    /** List *.sql under resources/migrations — works from a classes dir or a fat jar. */
    private fun discover(): List<Script> {
        StoreAssets.list(DIR)?.let { files ->
            return scripts(files.filter { it.endsWith(".sql") }.toSet()) {
                StoreAssets.readText("$DIR/$it")
            }
        }
        val loader = Thread.currentThread().contextClassLoader
        val names = mutableSetOf<String>()
        for (url in loader.getResources(DIR)) {
            when (url.protocol) {
                "file" -> java.io.File(url.toURI()).listFiles()
                    ?.forEach { if (it.name.endsWith(".sql")) names += it.name }
                "jar" -> {
                    val jar = (url.openConnection() as JarURLConnection).jarFile
                    for (entry in jar.entries()) {
                        val n = entry.name
                        if (n.startsWith("$DIR/") && n.endsWith(".sql")) names += n.removePrefix("$DIR/")
                    }
                }
            }
        }
        return scripts(names) { loader.getResource("$DIR/$it")!!.readText() }
    }

    private fun scripts(names: Set<String>, read: (String) -> String): List<Script> {
        val scripts = names.map { file ->
            val version = file.substringBefore('_').toIntOrNull()
                ?: error("migration file '$file' must start with a number (e.g. 002_add_x.sql)")
            Script(version, file, read(file))
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
