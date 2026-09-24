package dev.dwhipstock.pos.sdk

import dev.dwhipstock.pos.db.SyncOutbox
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.insert
import java.time.LocalDateTime
import java.util.UUID

/**
 * Outbox writer. MUST be called inside the same Exposed transaction as the
 * mutation it describes — atomicity is the whole point of the pattern.
 * No consumer exists until M6; events accumulate for replay.
 */
object Outbox {
    fun write(eventType: String, aggregateType: String, aggregateId: String, payload: JsonObject) {
        SyncOutbox.insert {
            it[eventId] = UUID.randomUUID().toString()
            it[SyncOutbox.eventType] = eventType
            it[SyncOutbox.aggregateType] = aggregateType
            it[SyncOutbox.aggregateId] = aggregateId
            it[SyncOutbox.payload] = payload.toString()
            it[createdAt] = VenueClock.now()
        }
    }
}
