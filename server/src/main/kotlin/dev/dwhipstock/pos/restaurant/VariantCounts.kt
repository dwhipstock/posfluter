package dev.dwhipstock.pos.restaurant

import dev.dwhipstock.pos.base.ItemVariants
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.select

/**
 * How many sizes (variants) each item on [checkId] has: a check, a receipt or
 * a kitchen ticket shows a line's size only when its item has more than one.
 * [liveOnly] counts the sizes not deleted.
 *
 * Only the check's own items are counted. This used to read the whole
 * variants table on every check view — every scan, tender and receipt — which
 * on the 5,000-product bottle shop cost about 5 ms each time (load test,
 * docs/load-test-report.md). Must run inside a transaction.
 */
internal fun variantCountsOnCheck(checkId: Int, liveOnly: Boolean = false): Map<String, Int> {
    val itemIds = CheckLines.select(CheckLines.itemId)
        .where { CheckLines.checkId eq checkId }
        .mapNotNull { it[CheckLines.itemId] }
        .distinct()
    if (itemIds.isEmpty()) return emptyMap()
    val n = ItemVariants.id.count()
    return ItemVariants.select(ItemVariants.itemId, n)
        .where {
            val onCheck = ItemVariants.itemId inList itemIds
            if (liveOnly) onCheck and ItemVariants.deletedAt.isNull() else onCheck
        }
        .groupBy(ItemVariants.itemId)
        .associate { it[ItemVariants.itemId] to it[n].toInt() }
}
