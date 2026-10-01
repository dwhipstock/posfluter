package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.ExportLog
import dev.dwhipstock.poscloud.db.PortalUsers
import dev.dwhipstock.poscloud.exports.FormulaGuard
import dev.dwhipstock.poscloud.exports.ZIP_PER_HOUR
import dev.dwhipstock.poscloud.exports.maxExportRows
import dev.dwhipstock.poscloud.exports.zipLimiter
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.dhatim.fastexcel.reader.CellType
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Portal data exports (/v1/exports): who may export, what each file holds,
 * that it is the caller's tenant and stores only, that nothing in a cell can
 * run as a formula, and that the tax summary is the tax report's figures.
 */
class ExportsTest {

    private val key = "store-key-exports"
    private val plateauKey = "store-key-exports-plateau"
    private val otherKey = "store-key-exports-other"
    private lateinit var owner: String
    private lateinit var manager: String
    private lateinit var viewer: String
    private lateinit var otherOwner: String
    private val range = "from=2026-07-01&to=2026-07-02"

    @Before
    fun setUp() {
        TestSupport.reset()
        zipLimiter.reset()
        seedTenant("copperlantern")
        seedTenant("copperlantern", "plateau", "Copper Lantern — Plateau")
        seedTenant("rival", "rival-main", "Rival Diner")
        seedStoreKey("copperlantern", "vieux-port", key)
        seedStoreKey("copperlantern", "plateau", plateauKey)
        seedStoreKey("rival", "rival-main", otherKey)
        owner = seedSession("copperlantern", seedUser("copperlantern", "owner@test.dev", "password-o"))
        manager = sessionWithRole("manager@test.dev", "manager")
        viewer = sessionWithRole("viewer@test.dev", "viewer")
        otherOwner = seedSession("rival", seedUser("rival", "owner@rival.dev", "password-r"))
    }

    @After
    fun tearDown() {
        maxExportRows = 1_000_000L
        zipLimiter.reset()
    }

    private fun sessionWithRole(email: String, role: String): String {
        val id = seedUser("copperlantern", email, "password-$role")
        transaction { PortalUsers.update({ PortalUsers.id eq id }) { it[PortalUsers.role] = role } }
        return seedSession("copperlantern", id)
    }

    /** A North Carolina sale: state and county sales tax, each saying who it is paid to. */
    private fun ncTaxes(state: Long, county: Long): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("code", "NC"); put("labelFr", "Taxe d'État"); put("labelEn", "State sales tax")
            put("ratePercent", "4.75"); put("amountCents", state); put("remitTo", "NCDOR")
        })
        add(buildJsonObject {
            put("code", "WAKE"); put("labelFr", "Taxe de comté"); put("labelEn", "County sales tax")
            put("ratePercent", "2"); put("amountCents", county); put("remitTo", "Wake County")
        })
    }

    private suspend fun ApplicationTestBuilder.seedSales() {
        ingest(
            key,
            event("check.closed", buildJsonObject {
                checkClosedPayload(
                    1, 10000 + 475 + 200, 675, "2026-07-01T19:00:00.000-04:00", shiftId = 3,
                    lines = buildJsonArray {
                        add(buildJsonObject {
                            put("lineId", 11); put("itemId", "burger"); put("nameEn", "=HYPERLINK(\"http://evil\")")
                            put("nameFr", "Burger"); put("qty", 2); put("unitPriceCents", 5000); put("lineTotalCents", 10000)
                        })
                    },
                    tenders = buildJsonArray { add(buildJsonObject {
                        put("tenderId", 1); put("type", "CARD"); put("amountAppliedCents", 10675)
                    }) },
                    tableLabel = "@T1",
                ).forEach { (k, v) -> put(k, v) }
                put("taxes", ncTaxes(475, 200))
                put("openedAt", "2026-07-01T18:00:00.000-04:00")
            }, seq = 1),
            event("check.closed", buildJsonObject {
                checkClosedPayload(2, 2000 + 95 + 40, 135, "2026-07-02T12:00:00.000-04:00").forEach { (k, v) -> put(k, v) }
                put("taxes", ncTaxes(95, 40))
            }, seq = 2),
            event("refund.created", buildJsonObject {
                put("refundId", 1); put("checkId", 2); put("grossCents", 2135); put("netCents", 2000)
                put("taxIncludedCents", 135); put("tenderType", "CASH"); put("reason", "-cold")
                put("createdAt", "2026-07-02T13:00:00.000-04:00"); put("taxes", ncTaxes(95, 40))
            }, seq = 3),
            event("cash.movement", buildJsonObject {
                put("movementId", 1); put("direction", "OUT"); put("amountCents", 1500); put("reason", "+ice")
                put("user", "manager"); put("createdAt", "2026-07-01T17:00:00.000-04:00")
            }, seq = 4, aggregateType = "cash"),
            event("shift.opened", buildJsonObject {
                put("shiftId", 3); put("openedBy", "1234"); put("openingFloatCents", 20000)
                put("openedAt", "2026-07-01T16:00:00.000-04:00")
            }, seq = 5),
        )
        ingest(plateauKey,
            event("check.closed", qcCheckClosedPayload(1, 4090, "2026-07-01T20:00:00.000-04:00"), seq = 1))
        ingest(otherKey,
            event("check.closed", checkClosedPayload(77, 99999, 0, "2026-07-01T20:00:00.000-04:00"), seq = 1))
        transaction {
            exec("""INSERT INTO store_staff (tenant_id, venue_id, id, name, role, active, deleted, updated_at) VALUES
                ('copperlantern', 'vieux-port', 's1', '=cmd|'' /C calc''!A0', 'MANAGER', true, false, now()),
                ('copperlantern', 'vieux-port', 's2', 'Ana', 'SERVER', true, false, now()),
                ('copperlantern', 'vieux-port', 's3', 'Gone', 'SERVER', true, true, now()),
                ('rival', 'rival-main', 'r1', 'Rival Person', 'MANAGER', true, false, now())""")
            exec("""INSERT INTO catalog_categories (tenant_id, venue_id, id, name_fr, name_en, sort_order, deleted) VALUES
                ('copperlantern', 'vieux-port', 'mains', 'Plats', 'Mains', 1, false)""")
            exec("""INSERT INTO catalog_items (tenant_id, venue_id, id, name_fr, name_en, category_id) VALUES
                ('copperlantern', 'vieux-port', 'burger', 'Burger', 'Burger', 'mains'),
                ('rival', 'rival-main', 'secret', 'Secret', 'Secret', 'x')""")
            exec("""INSERT INTO catalog_variants (tenant_id, venue_id, id, item_id, label_fr, label_en, price_cents, sort_order, deleted) VALUES
                ('copperlantern', 'vieux-port', 'burger:single', 'burger', 'Simple', 'Single', 1500, 1, false),
                ('copperlantern', 'vieux-port', 'burger:double', 'burger', 'Double', 'Double', 2100, 2, false)""")
        }
    }

    private suspend fun ApplicationTestBuilder.get(path: String, session: String): HttpResponse = getWithCookie(path, session)

    /** A CSV body as rows of fields (RFC 4180 quoting), BOM stripped. */
    private fun csv(body: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = if (body.startsWith("﻿")) 1 else 0
        while (i < body.length) {
            val ch = body[i]
            if (quoted) {
                if (ch == '"' && body.getOrNull(i + 1) == '"') { field.append('"'); i++ }
                else if (ch == '"') quoted = false
                else field.append(ch)
            } else when (ch) {
                '"' -> quoted = true
                ',' -> { row += field.toString(); field.clear() }
                '\r' -> {}
                '\n' -> { row += field.toString(); field.clear(); rows += row; row = mutableListOf() }
                else -> field.append(ch)
            }
            i++
        }
        return rows
    }

    private fun List<List<String>>.records(): List<Map<String, String>> =
        drop(1).map { r -> first().zip(r).toMap() }

    @Test
    fun rolesViewerForbiddenManagerAllowedZipOwnerOnly() = testApplication {
        application { module(TestSupport.config) }
        seedSales()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/exports/sales.csv?$range").status)
        val denied = get("/v1/exports/sales.csv?$range", viewer)
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        assertTrue("export_forbidden" in denied.bodyAsText())
        assertEquals(HttpStatusCode.Forbidden, get("/v1/exports/staff.xlsx", viewer).status)
        assertEquals(HttpStatusCode.Forbidden, get("/v1/exports/all.zip", viewer).status)

        val ok = get("/v1/exports/sales.csv?$range", manager)
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue(ok.headers[HttpHeaders.ContentType]!!.startsWith("text/csv"))
        assertTrue("attachment" in ok.headers[HttpHeaders.ContentDisposition]!!)
        assertEquals(HttpStatusCode.OK, get("/v1/exports/sales.xlsx?$range", manager).status)

        val managerZip = get("/v1/exports/all.zip", manager)
        assertEquals(HttpStatusCode.Forbidden, managerZip.status)
        assertTrue("owner_only" in managerZip.bodyAsText())
        assertEquals(HttpStatusCode.OK, get("/v1/exports/all.zip", owner).status)

        assertEquals(HttpStatusCode.NotFound, get("/v1/exports/passwords.csv", owner).status)
        assertEquals(HttpStatusCode.BadRequest, get("/v1/exports/sales.pdf", owner).status)

        // every successful export is on record: who, what, which stores, which dates
        val logged = transaction { ExportLog.selectAll().map { it[ExportLog.dataset] to it[ExportLog.format] } }
        assertEquals(listOf("sales" to "csv", "sales" to "xlsx", "all" to "zip"), logged)
        val first = transaction { ExportLog.selectAll().first() }
        assertEquals("2026-07-01", first[ExportLog.fromDate].toString())
        assertEquals("plateau,vieux-port", first[ExportLog.venueIds])
    }

    @Test
    fun salesRowsAreTheTenantsAndTheStoresAsked() = testApplication {
        application { module(TestSupport.config) }
        seedSales()
        val all = csv(get("/v1/exports/sales.csv?$range", owner).bodyAsText())
        val header = all.first()
        assertEquals(listOf("store_id", "store_name", "check_id", "status", "opened_at", "closed_at", "timezone"), header.take(7))
        assertTrue("tax_NC_4.75_amount" in header && "tax_WAKE_2_remit_to" in header && "tax_QST_9.975_amount" in header, header.toString())
        val rows = all.records()
        assertEquals(listOf("plateau" to "1", "vieux-port" to "1", "vieux-port" to "2"), rows.map { it["store_id"] to it["check_id"] })
        assertFalse(all.flatten().any { "rival" in it.lowercase() || it == "77" || it == "999.99" })

        val sale = rows[1]
        assertEquals("2026-07-01 18:00:00", sale["opened_at"])
        assertEquals("2026-07-01 19:00:00", sale["closed_at"])
        assertEquals("America/New_York", sale["timezone"])
        assertEquals("CAD", sale["currency"])
        assertEquals("100.00", sale["subtotal"])
        assertEquals("6.75", sale["tax_total"])
        assertEquals("4.75", sale["tax_NC_4.75_amount"])
        assertEquals("NCDOR", sale["tax_NC_4.75_remit_to"])
        assertEquals("Wake County", sale["tax_WAKE_2_remit_to"])
        assertEquals("106.75", sale["total"])
        assertEquals("CARD 106.75", sale["tenders"])
        assertEquals("'@T1", sale["table"]) // formula-start text neutralized

        // one store: only its rows
        val one = csv(get("/v1/exports/sales.csv?$range&venue=plateau", owner).bodyAsText()).records()
        assertEquals(listOf("plateau"), one.map { it["store_id"] }.distinct())
        // another tenant's store is not found, never served
        assertEquals(HttpStatusCode.NotFound, get("/v1/exports/sales.csv?$range&venue=rival-main", owner).status)
        // and the other tenant sees only its own
        val theirs = csv(get("/v1/exports/sales.csv?$range", otherOwner).bodyAsText()).records()
        assertEquals(listOf("77"), theirs.map { it["check_id"] })
        val theirStaff = csv(get("/v1/exports/staff.csv", otherOwner).bodyAsText()).records()
        assertEquals(listOf("Rival Person"), theirStaff.map { it["name"] })
    }

    @Test
    fun everyTextCellThatCouldRunAsAFormulaIsNeutralized() = testApplication {
        application { module(TestSupport.config) }
        seedSales()
        assertEquals("'=1+1", FormulaGuard.neutralize("=1+1"))
        assertEquals("'+ice", FormulaGuard.neutralize("+ice"))
        assertEquals("'-cold", FormulaGuard.neutralize("-cold"))
        assertEquals("'@x", FormulaGuard.neutralize("@x"))
        assertEquals("'\tx", FormulaGuard.neutralize("\tx"))
        assertEquals("'\rx", FormulaGuard.neutralize("\rx"))
        assertEquals("-5.00", FormulaGuard.neutralize("-5.00"))
        assertEquals("Ana", FormulaGuard.neutralize("Ana"))

        val lines = csv(get("/v1/exports/sale-lines.csv?$range&venue=vieux-port", owner).bodyAsText()).records()
        assertEquals("'=HYPERLINK(\"http://evil\")", lines.single()["item_name_en"])
        assertEquals("50.00", lines.single()["unit_price"])
        val staff = csv(get("/v1/exports/staff.csv", owner).bodyAsText())
        assertEquals(listOf("store_id", "store_name", "name", "role", "active"), staff.first())
        assertEquals(setOf("'=cmd|' /C calc'!A0", "Ana"), staff.records().map { it["name"] }.toSet()) // deleted staff left out
        val refunds = csv(get("/v1/exports/refunds.csv?$range", owner).bodyAsText()).records()
        assertEquals("'-cold", refunds.single()["reason"])
        assertEquals("NC 4.75% 0.95 (remit to NCDOR); WAKE 2% 0.40 (remit to Wake County)", refunds.single()["taxes"])
        val cash = csv(get("/v1/exports/cash-movements.csv?$range", owner).bodyAsText()).records()
        assertEquals("'+ice", cash.single()["reason"])
        assertEquals("15.00", cash.single()["amount"])

        // XLSX: the same text, written as a string cell — never a formula
        val wb = ReadableWorkbook(ByteArrayInputStream(get("/v1/exports/staff.xlsx", owner).readRawBytes()))
        val rows = wb.firstSheet.read()
        assertEquals(3, rows.size)
        assertTrue(rows.drop(1).any { it.getCell(2).text == "'=cmd|' /C calc'!A0" && it.getCell(2).type == CellType.STRING })
        rows.flatMap { it.toList() }.filterNotNull().forEach { assertTrue(it.type != CellType.FORMULA, it.toString()) }
        wb.close()
    }

    @Test
    fun taxSummaryIsTheTaxReport() = testApplication {
        application { module(TestSupport.config) }
        seedSales()
        val report = testJson.parseToJsonElement(get("/v1/reports/tax?$range", owner).bodyAsText()).jsonObject
        val summary = csv(get("/v1/exports/tax-summary.csv?$range", owner).bodyAsText()).records()

        fun cents(s: String) = BigDecimal(s).movePointRight(2).longValueExact()
        fun keyOf(o: JsonObject) = Triple(o["code"]!!.jsonPrimitive.content, o["ratePercent"]!!.jsonPrimitive.content, o["currency"]!!.jsonPrimitive.content)

        // all-stores totals = the report's "by tax"
        val byTax = report["byTax"]!!.jsonArray.associate { keyOf(it.jsonObject) to it.jsonObject["amountCents"]!!.jsonPrimitive.content.toLong() }
        val allRows = summary.filter { it["period"] == "total" && it["store_id"] == "ALL" }
        assertEquals(byTax, allRows.associate { Triple(it["tax_code"]!!, it["rate_percent"]!!, it["currency"]!!) to cents(it["amount"]!!) })
        assertEquals("NCDOR", allRows.first { it["tax_code"] == "NC" }["remit_to"])
        // NC 4.75 + 0.95 − 0.95 refunded
        assertEquals(475L, byTax[Triple("NC", "4.75", "CAD")])

        // per-store totals = the report's byVenue[].taxes
        report["byVenue"]!!.jsonArray.map { it.jsonObject }.forEach { v ->
            val id = v["venueId"]!!.jsonPrimitive.content
            val want = v["taxes"]!!.jsonArray.associate { keyOf(it.jsonObject) to it.jsonObject["amountCents"]!!.jsonPrimitive.content.toLong() }
            val got = summary.filter { it["period"] == "total" && it["store_id"] == id }
                .associate { Triple(it["tax_code"]!!, it["rate_percent"]!!, it["currency"]!!) to cents(it["amount"]!!) }
            assertEquals(want, got, id)
        }

        // days add up to the totals
        val days = summary.filter { it["period"] == "day" }
        assertEquals(setOf("2026-07-01", "2026-07-02"), days.map { it["date"] }.toSet())
        days.groupBy { Triple(it["tax_code"]!!, it["rate_percent"]!!, it["currency"]!!) }.forEach { (k, rows) ->
            assertEquals(byTax[k], rows.sumOf { cents(it["amount"]!!) }, k.toString())
        }
        // day 2 at Vieux-Port: the 0.95 sale and its 0.95 refund net to zero
        assertEquals("0.00", days.single { it["date"] == "2026-07-02" && it["tax_code"] == "NC" }["amount"])
    }

    @Test
    fun xlsxOpensWithTypedCellsAndTheRightHeaders() = testApplication {
        application { module(TestSupport.config) }
        seedSales()
        val res = get("/v1/exports/sales.xlsx?$range&venue=vieux-port", manager)
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", res.headers[HttpHeaders.ContentType])
        assertTrue("sales_vieux-port_2026-07-01_2026-07-02.xlsx" in res.headers[HttpHeaders.ContentDisposition]!!)
        val wb = ReadableWorkbook(ByteArrayInputStream(res.readRawBytes()))
        val rows = wb.firstSheet.read()
        val header = rows[0].map { it.text }
        val csvHeader = csv(get("/v1/exports/sales.csv?$range&venue=vieux-port", manager).bodyAsText()).first()
        assertEquals(csvHeader, header)
        assertEquals(3, rows.size) // header + 2 checks
        val first = rows[1]
        fun cell(name: String) = first.getCell(header.indexOf(name))
        assertEquals(CellType.NUMBER, cell("total").type)
        assertEquals(BigDecimal("106.75"), cell("total").asNumber().setScale(2))
        assertEquals(CellType.NUMBER, cell("check_id").type)
        assertEquals(CellType.STRING, cell("store_id").type)
        assertEquals("vieux-port", cell("store_id").text)
        assertEquals(CellType.STRING, cell("tax_NC_4.75_remit_to").type)
        assertEquals("2026-07-01T19:00", cell("closed_at").asDate().toString())
        wb.close()

        // menu: one row per variant, with its category and price
        val menu = ReadableWorkbook(ByteArrayInputStream(get("/v1/exports/menu-items.xlsx", manager).readRawBytes()))
        val m = menu.firstSheet.read()
        assertEquals(listOf("store_id", "store_name", "item_id", "item_name_en"), m[0].map { it.text }.take(4))
        assertEquals(3, m.size)
        val mh = m[0].map { it.text }
        assertEquals("Mains", m[1].getCell(mh.indexOf("category_en")).text)
        assertEquals(BigDecimal("21.00"), m[2].getCell(mh.indexOf("price")).asNumber().setScale(2))
        menu.close()
    }

    @Test
    fun allMyDataZipHasEveryFileAndAReadmeAndIsRateLimited() = testApplication {
        application { module(TestSupport.config) }
        seedSales()
        val res = get("/v1/exports/all.zip", owner)
        assertEquals(HttpStatusCode.OK, res.status)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(res.readRawBytes())).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                entries[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(
            listOf("README.txt", "sales.csv", "sale-lines.csv", "refunds.csv", "tenders.csv", "shifts.csv",
                "cash-movements.csv", "menu-items.csv", "staff.csv", "tax-summary.csv"),
            entries.keys.toList())
        val readme = entries["README.txt"]!!
        listOf("sales.csv", "tax-summary.csv", "remit_to", "timezone", "currency", "apostrophe").forEach {
            assertTrue(it in readme, it)
        }
        // no date range given: every date
        assertEquals(3, csv(entries["sales.csv"]!!).records().size)
        assertEquals(1, csv(entries["shifts.csv"]!!).records().size)
        assertFalse(entries.values.any { "Rival" in it })

        repeat(ZIP_PER_HOUR - 1) { assertEquals(HttpStatusCode.OK, get("/v1/exports/all.zip", owner).status) }
        val limited = get("/v1/exports/all.zip", owner)
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertTrue(limited.headers[HttpHeaders.RetryAfter]!!.toLong() > 0)
        // single files are not limited by the zip's budget
        assertEquals(HttpStatusCode.OK, get("/v1/exports/sales.csv?$range", owner).status)
    }

    @Test
    fun anExportPastTheCapIsRefusedBeforeAnythingIsSent() = testApplication {
        application { module(TestSupport.config) }
        seedSales()
        maxExportRows = 2
        val res = get("/v1/exports/sales.csv?$range", owner)
        assertEquals(HttpStatusCode.PayloadTooLarge, res.status)
        assertTrue("export_too_large" in res.bodyAsText())
        assertEquals(HttpStatusCode.OK, get("/v1/exports/sales.csv?$range&venue=plateau", owner).status)
        assertEquals(HttpStatusCode.BadRequest, get("/v1/exports/sales.csv?from=2026-07-02&to=2026-07-01", owner).status)
    }
}
