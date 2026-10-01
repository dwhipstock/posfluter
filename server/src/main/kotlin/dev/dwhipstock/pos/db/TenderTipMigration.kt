package dev.dwhipstock.pos.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.exposed.sql.Transaction

/**
 * Migration 061 (code): card tenders recorded before 060 kept their tip only
 * inside card_json ({"tipCents": 600, ...}). Copy it to tenders.tip_cents so
 * the reports count tips taken before the upgrade too. Raw SQL on the columns
 * as they are at 060; an unreadable card_json leaves the tip at 0.
 */
object TenderTipMigration {
    const val VERSION = 61
    const val NAME = "061_tender_tip_backfill (code)"

    fun run(tx: Transaction) {
        val tips = mutableListOf<Pair<Int, Long>>()
        tx.exec("SELECT id, card_json FROM tenders WHERE card_json IS NOT NULL") { rs ->
            while (rs.next()) {
                val tip = runCatching {
                    Json.parseToJsonElement(rs.getString(2)).jsonObject["tipCents"]?.jsonPrimitive?.longOrNull
                }.getOrNull() ?: 0L
                if (tip > 0) tips += rs.getInt(1) to tip
            }
        }
        for ((id, tip) in tips) tx.exec("UPDATE tenders SET tip_cents = $tip WHERE id = $id")
    }
}
