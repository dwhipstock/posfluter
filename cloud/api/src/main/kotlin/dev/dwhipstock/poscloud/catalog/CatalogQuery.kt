package dev.dwhipstock.poscloud.catalog

import dev.dwhipstock.poscloud.BadRequestException
import io.ktor.server.application.*
import kotlinx.serialization.Serializable

/**
 * Search, filters and paging for the portal's product lists (Products, Stock,
 * reorder suggestions) — built for a retail store's ~5,000 products.
 *
 * The lists are read with a handful of indexed queries per store and filtered
 * here, in memory: at this size that is a few milliseconds, and it keeps one
 * set of rules (tokens, facets, "2-way" category → subcategory + size) for
 * every list instead of three SQL dialects of it.
 *
 * - `q`: every word must appear in the name (either language), the brand,
 *   the subcategory or the size label; digits also match the barcode.
 * - `category`, `subcategory`, `size`: exact matches; they combine.
 * - `limit` / `offset`: a page. No parameter at all = the legacy full list.
 */
data class CatalogQuery(
    val q: String? = null,
    val category: String? = null,
    val subcategory: String? = null,
    val size: String? = null,
    val limit: Int? = null,
    val offset: Int = 0,
) {
    private val tokens: List<String> = q?.let(::split).orEmpty()

    /** True when the caller asked for anything beyond the legacy full list. */
    val paged: Boolean get() = limit != null || tokens.isNotEmpty() || category != null || subcategory != null || size != null

    fun matchesText(f: Facts): Boolean {
        if (tokens.isEmpty()) return true
        return tokens.all { t ->
            f.words.any { it.startsWith(t) } || (t.all(Char::isDigit) && (f.barcode ?: "").contains(t))
        }
    }

    fun matches(f: Facts, ignoreCategory: Boolean = false, ignoreSub: Boolean = false, ignoreSize: Boolean = false): Boolean =
        matchesText(f) &&
            (ignoreCategory || category == null || f.categoryId == category) &&
            (ignoreSub || subcategory == null || f.subcategory == subcategory) &&
            (ignoreSize || size == null || f.size == size)

    /** The facet counts for [rows] (each counted once), under the other filters. */
    fun <R> facets(rows: List<R>, facts: (R) -> Facts): CatalogFacets {
        fun count(pick: (Facts) -> String?, keep: (Facts) -> Boolean): List<FacetCount> {
            val counts = LinkedHashMap<String, Int>()
            for (r in rows) {
                val f = facts(r)
                if (!keep(f)) continue
                val v = pick(f) ?: continue
                counts[v] = (counts[v] ?: 0) + 1
            }
            return counts.entries.sortedWith(compareBy({ -it.value }, { it.key })).map { FacetCount(it.key, it.value) }
        }
        return CatalogFacets(
            categories = count({ it.categoryId }) { matches(it, ignoreCategory = true, ignoreSub = true, ignoreSize = true) },
            // subcategories within the picked category (the "2-way" filter's second step)
            subcategories = count({ it.subcategory }) { matches(it, ignoreSub = true, ignoreSize = true) },
            sizes = count({ it.size }) { matches(it, ignoreSize = true) },
        )
    }

    /** One page of [rows] (already filtered and sorted). */
    fun <R> page(rows: List<R>): List<R> {
        val from = offset.coerceAtMost(rows.size)
        val to = if (limit == null) rows.size else (from + limit).coerceAtMost(rows.size)
        return rows.subList(from, to)
    }

    /** The searchable facts of one product. */
    data class Facts(
        val names: List<String>,
        val categoryId: String,
        val brand: String? = null,
        val subcategory: String? = null,
        val size: String? = null,
        val barcode: String? = null,
    ) {
        /** Lower-case words of the names, brand, subcategory and size; a query word matches a word's start. */
        val words: List<String> = split((names + listOfNotNull(brand, subcategory, size)).joinToString(" "))
    }

    companion object {
        const val MAX_LIMIT = 10_000
        private val SPLIT = Regex("[^\\p{L}\\p{N}.]+")

        /** "Hazy IPA 6-pack 12 oz" → [hazy, ipa, 6, pack, 12, oz]; "1.75 L" keeps "1.75". */
        fun split(text: String): List<String> =
            text.lowercase().split(SPLIT).map { it.trim('.') }.filter { it.isNotEmpty() }.distinct()

        fun from(call: ApplicationCall): CatalogQuery {
            val p = call.request.queryParameters
            fun text(name: String) = p[name]?.trim()?.takeIf { it.isNotEmpty() }?.take(200)
            fun int(name: String, range: IntRange): Int? {
                val raw = p[name]?.takeIf { it.isNotBlank() } ?: return null
                val n = raw.toIntOrNull() ?: throw BadRequestException("$name must be a number", "bad_param")
                if (n !in range) throw BadRequestException("$name must be ${range.first}-${range.last}", "bad_param")
                return n
            }
            return CatalogQuery(
                q = text("q"),
                category = text("category"),
                subcategory = text("subcategory"),
                size = text("size"),
                limit = int("limit", 1..MAX_LIMIT),
                offset = int("offset", 0..10_000_000) ?: 0,
            )
        }
    }
}

@Serializable
data class FacetCount(val value: String, val count: Int)

/**
 * The values present under the current filters: every category (for the
 * search), the subcategories of the picked category, and the sizes of the
 * picked category + subcategory — so a dropdown never offers an empty choice.
 */
@Serializable
data class CatalogFacets(
    val categories: List<FacetCount> = emptyList(),
    val subcategories: List<FacetCount> = emptyList(),
    val sizes: List<FacetCount> = emptyList(),
)
