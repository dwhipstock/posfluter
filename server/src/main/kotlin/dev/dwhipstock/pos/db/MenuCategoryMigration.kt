package dev.dwhipstock.pos.db

import dev.dwhipstock.pos.sdk.Outbox
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.IColumnType
import org.jetbrains.exposed.sql.IntegerColumnType
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.Transaction

/**
 * Migration 034 (code: it writes outbox events): fold the Copper Lantern demo
 * menu's 15 categories into 7 fuller ones, in place. Stores own their catalog
 * (one-way sync), so live databases are converted here rather than re-seeded.
 *
 * Detection: the demo catalog is recognised by its retired category ids — at
 * least [MIN_RETIRED_PRESENT] of the 12 must exist. An owner-built menu can
 * slug to a few of them ("Draft Beer" → draft-beer), never to most, and is
 * left alone. After conversion none exist, so the step is a no-op on re-run.
 *
 * What changes: the 7 target categories are created (or, if the id already
 * exists, re-sorted); every item in a retired category — live, 86'ed or
 * soft-deleted — moves to its target, keeping its id, variants, prices and
 * photo; the emptied retired categories are hard-deleted, as the catalog's own
 * DELETE /categories does for an empty category. Any other category (Plateau's
 * Sushi & Sake and Specials, owner additions) keeps its relative order after
 * the 7. Items carry no sort order: they list in insertion order, which already
 * keeps each old sub-group together in the order below.
 *
 * Open checks are untouched — lines reference item and variant ids, which do
 * not change.
 *
 * Every change is recorded as a normal catalog event (category.created,
 * item.updated, category.deleted, categories.reordered) carrying the full
 * snapshot, so the cloud portal's menu mirrors it. A store with no items yet
 * (a brand-new database, where 005's categories are migration residue) emits
 * nothing: its first sync's catalog.snapshot carries the result.
 *
 * Raw SQL and a frozen snapshot shape on purpose: later migrations must not
 * change what this step read or wrote.
 */
object MenuCategoryMigration {
    const val VERSION = 34
    const val NAME = "034_seven_menu_categories (code)"
    const val MIN_RETIRED_PRESENT = 10

    data class Target(val id: String, val nameFr: String, val nameEn: String, val from: List<String>)

    /** Display order = list order. */
    val TARGETS = listOf(
        Target("beer-cider", "Bières et cidres", "Beer & Cider",
            listOf("draft-beer", "bottles-cans", "craft-beer", "imported-beer", "cider-na")),
        Target("wine", "Vins", "Wine", listOf("red-wine", "white-wine", "rose-sparkling")),
        Target("cocktails", "Cocktails", "Cocktails", listOf("cocktails")),
        Target("starters", "Entrées", "Starters", listOf("appetizers", "late-night")),
        Target("burgers-sandwiches", "Burgers et sandwichs", "Burgers & Sandwiches", listOf("burgers-sandwiches")),
        Target("mains-salads", "Plats et salades", "Mains & Salads", listOf("mains", "salads-vegetarian")),
        Target("desserts", "Desserts", "Desserts", listOf("desserts")),
    )

    val RETIRED: List<String> = TARGETS.flatMap { t -> t.from.filter { it != t.id } }

    private data class Cat(val id: String, val sortOrder: Int, val nameFr: String, val nameEn: String)

    fun run(tx: Transaction) { remap(tx) }

    /** Converts the demo catalog in place; returns false (and touches nothing) otherwise. */
    fun remap(tx: Transaction): Boolean {
        val before = categories(tx)
        val presentRetired = RETIRED.filter { it in before }
        if (presentRetired.size < MIN_RETIRED_PRESENT) return false
        val emit = count(tx, "SELECT COUNT(*) FROM items") > 0

        val created = mutableListOf<String>()
        TARGETS.forEachIndexed { index, t ->
            if (t.id in before) {
                tx.exec("UPDATE categories SET sort_order = ? WHERE id = ?", listOf(int(index), text(t.id)))
            } else {
                tx.exec(
                    "INSERT INTO categories (id, sort_order, name_fr, name_en) VALUES (?, ?, ?, ?)",
                    listOf(text(t.id), int(index), text(t.nameFr), text(t.nameEn)),
                )
                created += t.id
            }
        }

        // moved item ids in insertion order, so events replay in menu order
        val moved = mutableListOf<String>()
        for (t in TARGETS) for (old in t.from.filter { it != t.id && it in before }) {
            tx.exec("SELECT id FROM items WHERE category_id = ? ORDER BY rowid", listOf(text(old))) { rs ->
                while (rs.next()) moved += rs.getString(1)
            }
            tx.exec("UPDATE items SET category_id = ? WHERE category_id = ?", listOf(text(t.id), text(old)))
        }

        for (old in presentRetired) tx.exec("DELETE FROM categories WHERE id = ?", listOf(text(old)))

        // everything that is not one of the 7 keeps its relative order after them
        val targetIds = TARGETS.map { it.id }.toSet()
        before.values.filter { it.id !in targetIds && it.id !in presentRetired }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
            .forEachIndexed { i, c ->
                tx.exec("UPDATE categories SET sort_order = ? WHERE id = ?", listOf(int(TARGETS.size + i), text(c.id)))
            }

        if (!emit) return true
        val after = categories(tx)
        for (id in created) {
            val c = after.getValue(id)
            Outbox.write("category.created", "category", id, buildJsonObject {
                put("categoryId", id); put("nameFr", c.nameFr); put("nameEn", c.nameEn)
                put("category", categoryJson(c, deleted = false))
            })
        }
        for (itemId in moved) {
            val snapshot = itemJson(tx, itemId)
            Outbox.write("item.updated", "item", itemId, buildJsonObject {
                put("itemId", itemId)
                put("categoryId", snapshot["categoryId"]!!)
                put("item", snapshot)
            })
        }
        for (old in presentRetired) {
            Outbox.write("category.deleted", "category", old, buildJsonObject {
                put("categoryId", old)
                put("category", categoryJson(before.getValue(old), deleted = true))
            })
        }
        val ordered = after.values.sortedBy { it.sortOrder }
        Outbox.write("categories.reordered", "category", "*", buildJsonObject {
            put("orderedIds", ordered.joinToString(",") { it.id })
            put("categories", JsonArray(ordered.map { categoryJson(it, deleted = false) }))
        })
        return true
    }

    private fun categories(tx: Transaction): Map<String, Cat> {
        val out = LinkedHashMap<String, Cat>()
        tx.exec("SELECT id, sort_order, name_fr, name_en FROM categories ORDER BY sort_order, id") { rs ->
            while (rs.next()) out[rs.getString(1)] = Cat(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4))
        }
        return out
    }

    private fun categoryJson(c: Cat, deleted: Boolean): JsonObject = buildJsonObject {
        put("id", c.id); put("nameFr", c.nameFr); put("nameEn", c.nameEn)
        put("sortOrder", c.sortOrder); put("deleted", deleted)
    }

    /** Same shape as CatalogSnapshots.itemSnapshotJson (CONTRACT.md §2), frozen here. */
    private fun itemJson(tx: Transaction, itemId: String): JsonObject {
        val variants = mutableListOf<JsonObject>()
        tx.exec(
            "SELECT id, label_fr, label_en, price_cents, sort_order, deleted_at FROM item_variants " +
                "WHERE item_id = ? ORDER BY sort_order", listOf(text(itemId)),
        ) { rs ->
            while (rs.next()) variants += buildJsonObject {
                put("id", rs.getString(1)); put("labelFr", rs.getString(2)); put("labelEn", rs.getString(3))
                put("priceCents", rs.getLong(4)); put("sortOrder", rs.getInt(5))
                put("deleted", rs.getString(6) != null)
            }
        }
        var item: JsonObject? = null
        tx.exec(
            "SELECT name_fr, name_en, description_fr, description_en, category_id, abbrev, is_alcohol, active, deleted_at " +
                "FROM items WHERE id = ?", listOf(text(itemId)),
        ) { rs ->
            if (rs.next()) item = buildJsonObject {
                put("id", itemId)
                put("nameFr", rs.getString(1)); put("nameEn", rs.getString(2))
                put("descriptionFr", rs.getString(3)); put("descriptionEn", rs.getString(4))
                put("categoryId", rs.getString(5)); put("abbrev", rs.getString(6))
                put("isAlcohol", rs.getBoolean(7)); put("active", rs.getBoolean(8))
                put("deleted", rs.getString(9) != null)
                put("variants", JsonArray(variants))
            }
        }
        return item ?: error("item $itemId vanished mid-migration")
    }

    private fun count(tx: Transaction, sql: String): Long {
        var n = 0L
        tx.exec(sql) { rs -> if (rs.next()) n = rs.getLong(1) }
        return n
    }

    private fun text(v: String): Pair<IColumnType<*>, Any?> = TextColumnType() to v
    private fun int(v: Int): Pair<IColumnType<*>, Any?> = IntegerColumnType() to v
}
