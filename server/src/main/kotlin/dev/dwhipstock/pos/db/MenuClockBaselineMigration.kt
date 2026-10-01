package dev.dwhipstock.pos.db

import dev.dwhipstock.pos.sync.Hlc
import dev.dwhipstock.pos.sync.MenuClocks
import dev.dwhipstock.pos.sync.MenuFields
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.batchInsert

/**
 * Migration 058 (code): every item, size and category this store already has
 * gets its last-write-wins registers (057) with the empty stamp — "set before
 * two-way sync" — so the first edit after the upgrade is a real, fresh write
 * and the cloud's later edits still win over the old values. Nothing goes in
 * the outbox (the portal already mirrors these values). Raw SQL on the
 * columns as they are at 057, so a later schema change can't break it.
 * Re-running inserts nothing new (INSERT OR IGNORE semantics via the key).
 */
object MenuClockBaselineMigration {
    const val VERSION = 58
    const val NAME = "058_menu_clock_baseline (code)"

    private data class Row(val entity: String, val id: String, val field: String, val value: String)

    fun run(tx: Transaction) {
        val rows = mutableListOf<Row>()
        fun str(v: String?) = JsonPrimitive(v ?: "").toString()
        tx.exec(
            "SELECT id, name_fr, name_en, description_fr, description_en, category_id, abbrev, is_alcohol, active, " +
                "deleted_at FROM items"
        ) { rs ->
            while (rs.next()) {
                val id = rs.getString(1)
                val values = listOf(
                    "nameFr" to str(rs.getString(2)), "nameEn" to str(rs.getString(3)),
                    "descriptionFr" to str(rs.getString(4)), "descriptionEn" to str(rs.getString(5)),
                    "categoryId" to str(rs.getString(6)), "abbrev" to str(rs.getString(7)),
                    "isAlcohol" to JsonPrimitive(rs.getInt(8) != 0).toString(),
                    "active" to JsonPrimitive(rs.getInt(9) != 0).toString(),
                    "deleted" to JsonPrimitive(rs.getString(10) != null).toString(),
                )
                values.forEach { (f, v) -> rows += Row(MenuFields.ITEM, id, f, v) }
            }
        }
        tx.exec("SELECT id, label_fr, label_en, price_cents, sort_order, deleted_at FROM item_variants") { rs ->
            while (rs.next()) {
                val id = rs.getString(1)
                rows += Row(MenuFields.VARIANT, id, "labelFr", str(rs.getString(2)))
                rows += Row(MenuFields.VARIANT, id, "labelEn", str(rs.getString(3)))
                rows += Row(MenuFields.VARIANT, id, "priceCents", JsonPrimitive(rs.getLong(4)).toString())
                rows += Row(MenuFields.VARIANT, id, "sortOrder", JsonPrimitive(rs.getInt(5)).toString())
                rows += Row(MenuFields.VARIANT, id, "deleted", JsonPrimitive(rs.getString(6) != null).toString())
            }
        }
        tx.exec("SELECT id, name_fr, name_en, sort_order FROM categories") { rs ->
            while (rs.next()) {
                val id = rs.getString(1)
                rows += Row(MenuFields.CATEGORY, id, "nameFr", str(rs.getString(2)))
                rows += Row(MenuFields.CATEGORY, id, "nameEn", str(rs.getString(3)))
                rows += Row(MenuFields.CATEGORY, id, "sortOrder", JsonPrimitive(rs.getInt(4)).toString())
                rows += Row(MenuFields.CATEGORY, id, "deleted", JsonPrimitive(false).toString())
            }
        }
        tx.exec(
            "SELECT entity, entity_id, lang, text FROM translations WHERE entity IN ('item', 'variant', 'category')"
        ) { rs ->
            while (rs.next()) {
                val text = rs.getString(4)?.trim().orEmpty()
                if (text.isNotEmpty()) rows += Row(rs.getString(1), rs.getString(2), MenuFields.NAMES + rs.getString(3),
                    JsonPrimitive(text).toString())
            }
        }
        if (rows.isEmpty()) return
        MenuClocks.batchInsert(rows, ignore = true, shouldReturnGeneratedValues = false) { r ->
            this[MenuClocks.entity] = r.entity
            this[MenuClocks.entityId] = r.id
            this[MenuClocks.field] = r.field
            this[MenuClocks.value] = r.value
            this[MenuClocks.hlc] = Hlc.LEGACY
        }
    }
}
