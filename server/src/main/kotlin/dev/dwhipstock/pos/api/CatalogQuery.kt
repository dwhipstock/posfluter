package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.base.Items
import io.ktor.http.Parameters
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll

/**
 * `GET /items` paging, search and filters (a 5,000-product shelf). Every part
 * is optional: no parameters is the whole live list in its usual order.
 *
 * Search ([q]) matches words, not substrings in the middle of a word: every
 * word of the query must start a word of the product's name, brand,
 * subcategory or size ("haz ipa 6" finds "Hazy Hills IPA 6-pack"); a number
 * also matches the pack size ("ipa 6" = IPA six-packs) or, from 4 digits, the
 * barcode. Results come best match first, then the more popular product.
 * The counter searches its own in-memory copy the same way (catalog_index.dart);
 * this is for the lighter clients (the portal-style lists, phones, tests).
 */
data class CatalogQuery(
    val includeInactive: Boolean = false,
    val q: String? = null,
    val category: String? = null,
    val subcategory: String? = null,
    val size: String? = null,
    val limit: Int? = null,
    val offset: Int = 0,
) {
    val paged: Boolean get() = limit != null || offset > 0

    fun page(rows: List<ResultRow>): List<ResultRow> =
        rows.drop(offset).let { if (limit != null) it.take(limit) else it }

    companion object {
        const val MAX_LIMIT = 1000

        fun from(p: Parameters): CatalogQuery {
            fun str(k: String) = p[k]?.trim()?.takeIf { it.isNotEmpty() }
            val limit = p["limit"]?.let { raw ->
                raw.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT } ?: throw IllegalArgumentException("limit must be 1-$MAX_LIMIT")
            }
            val offset = p["offset"]?.let { raw ->
                raw.toIntOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException("offset must be 0 or more")
            } ?: 0
            return CatalogQuery(
                includeInactive = p["all"] == "true",
                q = str("q"), category = str("category"), subcategory = str("subcategory"), size = str("size"),
                limit = limit, offset = offset,
            )
        }

        /** The matching live items (inside a transaction), ordered as described above. */
        fun select(query: CatalogQuery): List<ResultRow> {
            var where: Op<Boolean> = Items.deletedAt.isNull()
            if (!query.includeInactive) where = where and (Items.active eq true)
            query.category?.let { where = where and (Items.categoryId eq it) }
            query.subcategory?.let { where = where and (Items.subcategory eq it) }
            query.size?.let { where = where and (Items.sizeLabel eq it) }
            val rows = Items.selectAll().where { where }.toList()
            val q = query.q
            if (q == null) {
                return if (query.paged) rows.sortedWith(compareBy({ it[Items.nameEn].lowercase() }, { it[Items.id] })) else rows
            }
            val terms = tokens(q).map { SYNONYMS[it] ?: it }
            if (terms.isEmpty()) return rows
            return rows.mapNotNull { row -> score(row, terms)?.let { row to it } }
                .sortedWith(compareByDescending<Pair<ResultRow, Int>> { it.second }
                    .thenByDescending { it.first[Items.salesWeight] }
                    .thenBy { it.first[Items.nameEn].lowercase() })
                .map { it.first }
        }

        /** Shelf shorthand people type: "6pk", "btl", "tall boy" … */
        val SYNONYMS = mapOf("pk" to "pack", "pks" to "pack", "packs" to "pack", "btl" to "bottle", "btls" to "bottles", "cn" to "can", "cans" to "can")

        /** "IPA 6-pack 12oz" → [ipa, 6, pack, 12, oz]: words and numbers, split where letters meet digits. */
        fun tokens(text: String): List<String> {
            val folded = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
            return Regex("[0-9]+(?:\\.[0-9]+)?|[a-z]+").findAll(folded).map { it.value }.toList()
        }

        /** Null when a query word matches nothing; otherwise higher = better. */
        private fun score(row: ResultRow, terms: List<String>): Int? {
            val words = tokens(listOfNotNull(
                row[Items.nameEn], row[Items.brand], row[Items.subcategory], row[Items.sizeLabel],
            ).joinToString(" "))
            val nameWords = tokens(row[Items.nameEn])
            val barcode = row[Items.barcode].orEmpty()
            var score = 0
            for (t in terms) {
                val numeric = t.first().isDigit()
                score += when {
                    numeric && t.length >= 4 && barcode.contains(t) -> 3
                    numeric && t == row[Items.packUnits].toString() && row[Items.packUnits] > 1 -> 3
                    words.any { it == t } -> 3
                    !numeric && words.any { it.startsWith(t) } -> 2
                    else -> return null
                }
            }
            if (nameWords.isNotEmpty() && nameWords.first().startsWith(terms.first())) score += 1
            return score
        }
    }
}
