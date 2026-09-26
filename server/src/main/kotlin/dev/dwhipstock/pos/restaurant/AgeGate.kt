package dev.dwhipstock.pos.restaurant

import dev.dwhipstock.pos.base.AgeChecks
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll

/**
 * Payment waits for an ID check whenever the basket holds an age-restricted
 * line (038): a passing check on the sale clears it. A failed check (under
 * age, expired ID) keeps the gate shut; the cashier removes the restricted
 * items and sells the rest. A sale with no restricted line — every pub check
 * — never meets the gate. Call inside a transaction.
 */
object AgeGate {

    /** Is any ACTIVE line of [checkId] age-restricted? */
    fun required(checkId: Int): Boolean =
        CheckLines.selectAll().where {
            (CheckLines.checkId eq checkId) and (CheckLines.status eq "ACTIVE") and (CheckLines.ageRestricted eq true)
        }.any()

    /** The latest ID check on [checkId], or null. */
    fun latest(checkId: Int) = AgeChecks.selectAll().where { AgeChecks.checkId eq checkId }
        .orderBy(AgeChecks.id, SortOrder.DESC).limit(1).firstOrNull()

    /**
     * The legal age a passing check cleared [checkId] at; null = never passed.
     * A cashier's visual check ("clearly over N", [AgeCheckMode]) never clears
     * tobacco or vape: with any on the sale only an ID check counts.
     */
    fun passedAt(checkId: Int): Int? {
        val tobacco = tobaccoOnSale(checkId)
        return AgeChecks.selectAll()
            .where { (AgeChecks.checkId eq checkId) and (AgeChecks.passed eq true) }
            .orderBy(AgeChecks.id, SortOrder.DESC)
            .firstOrNull { !tobacco || it[AgeChecks.method] != VISUAL }?.get(AgeChecks.legalAge)
    }

    const val VISUAL = "VISUAL"

    /** Tobacco or vape (the `tobacco` department) on [checkId]: always an ID check. */
    fun tobaccoOnSale(checkId: Int): Boolean =
        CheckLines.join(dev.dwhipstock.pos.base.Items, org.jetbrains.exposed.sql.JoinType.INNER,
            CheckLines.itemId, dev.dwhipstock.pos.base.Items.id)
            .selectAll().where {
                (CheckLines.checkId eq checkId) and (CheckLines.status eq "ACTIVE") and
                    (dev.dwhipstock.pos.base.Items.categoryId eq "tobacco")
            }.any()

    fun cleared(checkId: Int): Boolean = !required(checkId) || passedAt(checkId) != null

    /** Refuse payment while restricted items wait for (or failed) an ID check. */
    fun requireCleared(checkId: Int) {
        if (!required(checkId) || passedAt(checkId) != null) return
        val last = latest(checkId)
        if (last != null && !last[AgeChecks.passed])
            throw ConflictException(
                "check $checkId failed its ID check (${last[AgeChecks.reason]}); remove the age-restricted items",
                "age_check_failed")
        throw ConflictException("check $checkId holds age-restricted items; check the customer's ID first",
            "age_check_required")
    }
}
