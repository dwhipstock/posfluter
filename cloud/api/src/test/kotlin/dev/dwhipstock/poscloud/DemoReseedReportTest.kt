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
    private fun generate(py: String): Pair<String, JsonObject> {
        val sql = File.createTempFile("reseed", ".sql").apply { deleteOnExit() }
        val plan = File.createTempFile("reseed", ".json").apply { deleteOnExit() }
        val proc = ProcessBuilder(
            py, File(repo, "scripts/demo-reseed.py").path,
            "--menu-json", File(repo, "scripts/tests/fixtures/copperlantern-catalog.json").path,
            "--today", "2026-10-04", "--days", "14", "--seed", "99",
            "--dry-run-sql", sql.path, "--plan-json", plan.path,
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue(proc.waitFor(120, TimeUnit.SECONDS) && proc.exitValue() == 0, out)
        return sql.readText() to testJson.parseToJsonElement(plan.readText()).jsonObject
    }

    private fun exec(sql: String) {
        DriverManager.getConnection(TestSupport.config.databaseUrl, TestSupport.config.dbUser, TestSupport.config.dbPassword).use { c ->
            c.autoCommit = true
            c.createStatement().use { it.execute(sql) }
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
        assertTrue(journal.all { it.jsonObject.int("checkId") >= 1_000_000 })
        val today = get("/v1/reports/summary?venue=vieux-port&from=2026-10-04&to=2026-10-04")
        assertEquals(1, today.int("checkCount"))
        assertEquals(1083, today.long("grossCents"))
    }
}
