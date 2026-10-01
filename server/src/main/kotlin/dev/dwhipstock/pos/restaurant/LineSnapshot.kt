package dev.dwhipstock.pos.restaurant

import dev.dwhipstock.pos.base.ItemVariants
import dev.dwhipstock.pos.base.Items
import dev.dwhipstock.pos.base.Translations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder

/**
 * A check line keeps the menu as it was when it was rung (migration 059).
 * With two-way menu sync a manager can rename, reprice, re-categorise or
 * delete an item or a size from the portal while it is on an open check: the
 * line keeps its price and tax facts ([captureShelfFacts]) and, through this,
 * its names, size labels, category, extra-language names and whether its
 * size is shown. Every reader (the check, bills, receipts and reprints,
 * kitchen tickets, the check.closed event, the store's reports, the deals)
 * prefers the line's copy and falls back to the live menu only for a line
 * from before 059 that had nothing to backfill.
 */
internal object LineSnapshot {

    /** Freeze [item] / [variant] onto the line being inserted. Inside its transaction. */
    fun capture(st: UpdateBuilder<*>, item: ResultRow, variant: ResultRow?) {
        st[CheckLines.nameFr] = item[Items.nameFr]
        st[CheckLines.nameEn] = item[Items.nameEn]
        st[CheckLines.categoryId] = item[Items.categoryId]
        if (variant != null) {
            st[CheckLines.variantLabelFr] = variant[ItemVariants.labelFr]
            st[CheckLines.variantLabelEn] = variant[ItemVariants.labelEn]
            Translations.namesOf(Translations.VARIANT, variant[ItemVariants.id]).takeIf { it.isNotEmpty() }
                ?.let { st[CheckLines.variantNamesJson] = encode(it) }
        }
        Translations.namesOf(Translations.ITEM, item[Items.id]).takeIf { it.isNotEmpty() }
            ?.let { st[CheckLines.namesJson] = encode(it) }
        val liveSizes = ItemVariants.selectAll()
            .where { (ItemVariants.itemId eq item[Items.id]) and ItemVariants.deletedAt.isNull() }.count()
        st[CheckLines.showVariant] = liveSizes > 1
    }

    fun nameFr(row: ResultRow): String =
        row[CheckLines.nameFr] ?: row.getOrNull(Items.nameFr) ?: row[CheckLines.displayName] ?: "?"

    fun nameEn(row: ResultRow): String =
        row[CheckLines.nameEn] ?: row.getOrNull(Items.nameEn) ?: row[CheckLines.displayName] ?: "?"

    fun labelFr(row: ResultRow): String? = row[CheckLines.variantLabelFr] ?: row.getOrNull(ItemVariants.labelFr)
    fun labelEn(row: ResultRow): String? = row[CheckLines.variantLabelEn] ?: row.getOrNull(ItemVariants.labelEn)

    fun categoryId(row: ResultRow): String? =
        if (row[CheckLines.itemId] == null) null else row[CheckLines.categoryId] ?: row.getOrNull(Items.categoryId)

    /** Whether the line shows its size: as at ring-up, else [counts] (the reader's own rule for older lines). */
    fun showVariant(row: ResultRow, counts: Map<String, Int>): Boolean =
        row[CheckLines.showVariant] ?: ((counts[row[CheckLines.itemId]] ?: 1) > 1)

    fun names(row: ResultRow, live: Map<String, Map<String, String>>): Map<String, String> =
        row[CheckLines.namesJson]?.let(::decode) ?: row[CheckLines.itemId]?.let { live[it] }.orEmpty()

    fun variantNames(row: ResultRow, live: Map<String, Map<String, String>>): Map<String, String> =
        row[CheckLines.variantNamesJson]?.let(::decode) ?: row[CheckLines.variantId]?.let { live[it] }.orEmpty()

    private fun encode(m: Map<String, String>): String = JsonObject(m.toSortedMap().mapValues { JsonPrimitive(it.value) }).toString()

    private fun decode(s: String): Map<String, String> = runCatching {
        (Json.parseToJsonElement(s) as JsonObject).mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())
}
