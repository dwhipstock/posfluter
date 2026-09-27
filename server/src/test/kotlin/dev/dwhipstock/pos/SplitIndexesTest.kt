package dev.dwhipstock.pos

import dev.dwhipstock.pos.db.initDatabase
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A split check reads its groups' payments and its lines' allocations on every
 * view, tender and close. Those lookups must use an index, or a split check
 * slows down with every sale the store has ever made (load test, a year of
 * restaurant sales: 670 → 2,170 sales/min with the index).
 */
class SplitIndexesTest {

    private fun plan(sql: String): String {
        val db = initDatabase(Files.createTempDirectory("pos-idx").resolve("pos.db").toString())
        return transaction(db) {
            val out = StringBuilder()
            exec("EXPLAIN QUERY PLAN $sql", explicitStatementType = org.jetbrains.exposed.sql.statements.StatementType.SELECT) { rs -> while (rs.next()) out.append(rs.getString("detail")).append('\n') }
            out.toString()
        }
    }

    @Test
    fun `a bill group's payments are found by index`() {
        val p = plan("SELECT amount_applied_cents FROM tenders WHERE bill_group_id = 7")
        assertTrue("USING INDEX idx_tenders_bill_group" in p, p)
    }

    @Test
    fun `the forecourt's open fuellings are found by index`() {
        // every poll (300 ms) and every pump screen: never a scan of the store's whole fuel history
        val reconcile = plan("SELECT id FROM fuel_sales WHERE status IN ('IN_BASKET','AUTHORISED','AUTH_FAILED') " +
            "OR (status = 'SETTLED' AND fdc_cleared = 0)")
        assertTrue("SCAN fuel_sales" !in reconcile, reconcile)
        val view = plan("SELECT id FROM fuel_sales WHERE status IN ('IN_BASKET','AUTHORISED','AUTH_FAILED') " +
            "OR (change_given = 0 AND refund_cents > 0)")
        assertTrue("SCAN fuel_sales" !in view, view)
    }

    @Test
    fun `a line's allocations are found by index`() {
        val p = plan("SELECT qty FROM bill_group_allocations WHERE line_id = 7")
        assertTrue("USING INDEX idx_bill_group_allocations_line" in p, p)
    }
}
