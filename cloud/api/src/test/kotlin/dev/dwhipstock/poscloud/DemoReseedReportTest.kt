package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.Tenants
import dev.dwhipstock.poscloud.db.Venues
import io.ktor.client.statement.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * scripts/demo-reseed.py, read back through the portal: its SQL runs against
 * the schema the migrations build (as on the hosted box), then the endpoints
 * the Dashboard and Reports call (summary, by-venue, payments, items, hourly,
 * tax, shifts) must show exactly the totals the generator says it wrote, per
 * store and per day. Also: it wipes a store's older synced sales and keeps
 * today's. Needs python3 (skipped without it).
 */
class DemoReseedReportTest {

    private val key = "reseed-key"
    private lateinit var session: String
    private val repo = File("../..").canonicalFile
    private val from = "2026-09-20"
    private val to = "2026-10-03"

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("copperlantern", "vieux-port", "Copper Lantern — Glenwood South")
        seedTenant("copperlantern", "express", "Copper Lantern — Express")
        transaction {
            Tenants.update({ Tenants.id eq "copperlantern" }) { it[reportingCurrency] = "USD" }
            Venues.update({ Venues.tenantId eq "copperlantern" }) { it[currency] = "USD"; it[country] = "US" }
        }
        seedStoreKey("copperlantern", "vieux-port", key)
        session = seedSession("copperlantern", seedUser("copperlantern", "owner@reseed.test", "password-reseed"))
    }

    private fun python(): String? = listOf("python3", "python").firstOrNull { cmd ->
        runCatching { ProcessBuilder(cmd, "--version").start().waitFor(20, TimeUnit.SECONDS) }.getOrDefault(false)
    }

    /** Runs the generator offline (the fixture catalog) and returns (sql, plan). */
    private fun generate(py: String, vararg extra: String): Pair<File, JsonObject> {
        val sql = File.createTempFile("reseed", ".sql").apply { deleteOnExit() }
        val plan = File.createTempFile("reseed", ".json").apply { deleteOnExit() }
        val proc = ProcessBuilder(
            py, File(repo, "scripts/demo-reseed.py").path,
            "--menu-json", File(repo, "scripts/tests/fixtures/copperlantern-catalog.json").path,
            "--today", "2026-10-04", "--days", "14", "--seed", "99",
            "--dry-run-sql", sql.path, "--plan-json", plan.path, *extra,
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue(proc.waitFor(300, TimeUnit.SECONDS) && proc.exitValue() == 0, out)
        return sql to testJson.parseToJsonElement(plan.readText()).jsonObject
    }

    /**
     * Runs the script's SQL the way psql does: statements as they come, and each
     * `COPY ... FROM STDIN;` block's data (up to `\.`) through the driver's COPY. Same connection
     * throughout, so the script's own BEGIN ... COMMIT is the one transaction.
     */
    private fun exec(sql: File) {
        DriverManager.getConnection(TestSupport.config.databaseUrl, TestSupport.config.dbUser, TestSupport.config.dbPassword).use { c ->
            c.autoCommit = true
            val copy = org.postgresql.copy.CopyManager(c.unwrap(org.postgresql.core.BaseConnection::class.java))
            val statements = StringBuilder()
            fun flush() {
                if (statements.isNotBlank()) c.createStatement().use { it.execute(statements.toString()) }
                statements.setLength(0)
            }
            sql.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("COPY ") && line.endsWith("FROM STDIN;")) {
                        flush()
                        val into = copy.copyIn(line.removeSuffix(";"))  // streamed: a year is ~80 MB
                        while (true) {
                            val d = reader.readLine() ?: error("COPY block without its end marker")
                            if (d == "\\.") break
                            val bytes = (d + "\n").toByteArray()
                            into.writeToCopy(bytes, 0, bytes.size)
                        }
                        into.endCopy()
                    } else statements.append(line).append('\n')
                }
            }
            flush()
        }
    }

    private suspend fun ApplicationTestBuilder.get(path: String): JsonObject =
        testJson.parseToJsonElement(getWithCookie(path, session).bodyAsText()).jsonObject

    private fun JsonObject.long(k: String) = this[k]!!.jsonPrimitive.long
    private fun JsonObject.int(k: String) = this[k]!!.jsonPrimitive.int

    @Test
    fun reportsShowExactlyWhatTheGeneratorWrote() = testApplication {
        val py = python()
        assumeTrue("python3 not on PATH", py != null)
        application { module(TestSupport.config) }

        // a synced sale from last month (wiped) and one from today (kept)
        val taxes = buildJsonArray {
            add(buildJsonObject { put("code", "NC_SALES"); put("ratePercent", "7.25"); put("amountCents", 73) })
            add(buildJsonObject { put("code", "WAKE_FOOD"); put("ratePercent", "1"); put("amountCents", 10) })
        }
        fun sale(id: Int, at: String) = buildJsonObject {
            checkClosedPayload(id, 1083, 83, at, tenders = buildJsonArray {
                add(buildJsonObject { put("tenderId", id.toLong()); put("type", "CARD"); put("amountAppliedCents", 1083) })
            }).forEach { (k, v) -> put(k, v) }
            put("taxes", taxes)
        }
        ingest(key, event("check.closed", sale(41, "2026-09-25T19:00:00-04:00"), seq = 1),
            event("check.closed", sale(42, "2026-10-04T12:00:00-04:00"), seq = 2))

        val (sql, plan) = generate(py!!)
        exec(sql)
        val stores = plan["stores"]!!.jsonObject
        assertEquals(from, plan["from"]!!.jsonPrimitive.content)
        assertEquals(to, plan["to"]!!.jsonPrimitive.content)

        for (venue in listOf("vieux-port", "express")) {
            val want = stores[venue]!!.jsonObject
            val wt = want["totals"]!!.jsonObject
            val range = "venue=$venue&from=$from&to=$to"

            val summary = get("/v1/reports/summary?$range")
            for (k in listOf("grossCents", "taxCents", "netCents", "avgCheckCents", "voidAmountCents", "refundAmountCents",
                    "cashRoundingCents"))
                assertEquals(wt.long(k), summary.long(k), "$venue summary $k")
            for (k in listOf("checkCount", "voidCount", "refundCount")) assertEquals(wt.int(k), summary.int(k), "$venue summary $k")
            val days = summary["byDay"]!!.jsonArray.associate { it.jsonObject["date"]!!.jsonPrimitive.content to it.jsonObject }
            val wantDays = want["days"]!!.jsonObject
            assertEquals(14, wantDays.size, "$venue: a sale every day")
            assertEquals(wantDays.keys, days.keys, "$venue days")
            wantDays.forEach { (d, w) ->
                assertEquals(w.jsonObject.long("grossCents"), days[d]!!.long("grossCents"), "$venue $d gross")
                assertEquals(w.jsonObject.int("checkCount"), days[d]!!.int("checkCount"), "$venue $d checks")
            }

            val pay = get("/v1/reports/payments?$range")
            val rows = pay["rows"]!!.jsonArray.associate { it.jsonObject["type"]!!.jsonPrimitive.content to it.jsonObject }
            want["payments"]!!.jsonObject.forEach { (type, w) ->
                assertEquals(w.jsonObject.long("amountCents"), rows[type]!!.long("amountCents"), "$venue $type")
                assertEquals(w.jsonObject.int("count"), rows[type]!!.int("count"), "$venue $type count")
            }
            // every sale is paid in full
            assertEquals(summary.long("grossCents") + summary.long("refundAmountCents"), pay.long("totalCents"))

            val items = get("/v1/reports/items?$range")["rows"]!!.jsonArray
                .associate { it.jsonObject["itemId"]!!.jsonPrimitive.content to it.jsonObject }
            val wantItems = want["items"]!!.jsonObject
            assertEquals(wantItems.keys, items.keys, "$venue items")
            wantItems.forEach { (id, w) ->
                assertEquals(w.jsonObject.long("revenueCents"), items[id]!!.long("revenueCents"), "$venue $id")
                assertEquals(w.jsonObject.int("qty"), items[id]!!.int("qty"), "$venue $id qty")
            }

            val hourly = get("/v1/reports/hourly?$range")["rows"]!!.jsonArray
                .associate { it.jsonObject["hour"]!!.jsonPrimitive.int.toString() to it.jsonObject }
            want["hourly"]!!.jsonObject.forEach { (h, w) ->
                assertEquals(w.jsonObject.long("grossCents"), hourly[h]!!.long("grossCents"), "$venue $h:00")
            }

            // the tax report splits the 8.25% into what is remitted to NCDOR and Wake County
            val tax = get("/v1/reports/tax?$range")
            val byTax = tax["byTax"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("NC_SALES" to "7.25", "WAKE_FOOD" to "1"),
                byTax.map { it["code"]!!.jsonPrimitive.content to it["ratePercent"]!!.jsonPrimitive.content })
            assertEquals(wt.long("taxCents"), byTax.sumOf { it.long("amountCents") })

            // one closed Z-report a day, with its tender breakdown readable
            val shifts = get("/v1/reports/shifts?$range")["rows"]!!.jsonArray.map { it.jsonObject }
            assertEquals(14, shifts.size)
            assertTrue(shifts.all { it["status"]!!.jsonPrimitive.content == "CLOSED" && it["tenderBreakdown"]!!.jsonArray.isNotEmpty() })
        }

        // all stores, as the Dashboard opens: the two stores added up
        val all = get("/v1/reports/by-venue?from=$from&to=$to")
        assertEquals(stores.values.sumOf { it.jsonObject["totals"]!!.jsonObject.long("grossCents") }, all.long("grossCents"))

        // the older synced sale is gone, today's is still there
        val journal = get("/v1/reports/journal?venue=vieux-port&from=2026-09-25&to=2026-09-25&limit=200")["rows"]!!.jsonArray
        assertTrue(journal.none { it.jsonObject.int("checkId") == 41 })
        // generated checks are numbered from 20000, like a store's own
        assertTrue(journal.all { it.jsonObject.int("checkId") in 20_000..99_999 })
        val today = get("/v1/reports/summary?venue=vieux-port&from=2026-10-04&to=2026-10-04")
        assertEquals(1, today.int("checkCount"))
        assertEquals(1083, today.long("grossCents"))
    }

    /** The categories and tables reports read a range's lines in chunks of check ids: same answer as one go. */
    @Test
    fun reportsReadChildRowsInChunks() = testApplication {
        val py = python()
        assumeTrue("python3 not on PATH", py != null)
        application { module(TestSupport.config) }
        exec(generate(py!!).first)
        val range = "from=$from&to=$to"
        fun byCategory(o: JsonObject) = o["rows"]!!.jsonArray.associate {
            it.jsonObject["categoryId"].toString() to (it.jsonObject.long("revenueCents") to it.jsonObject.int("qty"))
        }
        val whole = byCategory(get("/v1/reports/categories?$range"))
        assertTrue(whole.size >= 5)
        val saved = dev.dwhipstock.poscloud.reports.childRowChunk
        try {
            dev.dwhipstock.poscloud.reports.childRowChunk = 7
            assertEquals(whole, byCategory(get("/v1/reports/categories?$range")))
        } finally {
            dev.dwhipstock.poscloud.reports.childRowChunk = saved
        }
    }

    /**
     * A year of both stores (the tool's default), through the portal: the Dashboard's calls for
     * today, 30 days and the year, and the heaviest reports, each under a few seconds. Opt-in
     * (DEMO_RESEED_YEAR=1): it loads ~80,000 sales.
     */
    @Test
    fun aYearOfSalesLoadsFast() = testApplication {
        val py = python()
        assumeTrue("python3 not on PATH", py != null)
        assumeTrue("set DEMO_RESEED_YEAR=1", System.getenv("DEMO_RESEED_YEAR") == "1")
        application { module(TestSupport.config) }
        val (sql, plan) = generate(py!!, "--days", "365", "--today-until", "12:30")
        val t0 = System.nanoTime()
        exec(sql)
        println("year loaded in ${(System.nanoTime() - t0) / 1_000_000} ms (${sql.length() / 1_000_000} MB of SQL)")
        val ranges = mapOf("today" to ("2026-10-04" to "2026-10-04"), "30 days" to ("2026-09-04" to "2026-10-03"),
            "year" to ("2025-10-04" to "2026-10-03"))
        for ((label, r) in ranges) {
            for (ep in listOf("summary", "payments", "hourly", "items", "by-venue", "tax", "categories", "tables", "exceptions")) {
                val t = System.nanoTime()
                val body = get("/v1/reports/$ep?from=${r.first}&to=${r.second}")
                val ms = (System.nanoTime() - t) / 1_000_000
                println("$label $ep: $ms ms")
                assertTrue(ms < 5_000, "$label $ep took $ms ms")
                if (ep == "summary" && label == "year") {
                    val want = plan["stores"]!!.jsonObject.values.sumOf { it.jsonObject["totals"]!!.jsonObject.long("grossCents") }
                    assertEquals(want, body.long("grossCents"))
                }
                if (ep == "summary" && label == "today") assertTrue(body.int("checkCount") > 0)
            }
        }
    }
}
