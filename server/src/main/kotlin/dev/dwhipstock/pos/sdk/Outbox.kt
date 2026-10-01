package dev.dwhipstock.pos.sdk

import dev.dwhipstock.pos.db.SyncOutbox
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.insert
import java.util.UUID

/**
 * Outbox writer. MUST be called inside the same Exposed transaction as the
 * mutation it describes — atomicity is the whole point of the pattern.
 * Menu events get their field clocks here ([dev.dwhipstock.pos.sync.MenuClock]),
 * whichever code path wrote them.
 */
object Outbox {
    fun write(eventType: String, aggregateType: String, aggregateId: String, payload: JsonObject) {
        // menu snapshots carry their last-write-wins clocks (two-way menu sync, CONTRACT §10)
        val body = dev.dwhipstock.pos.sync.MenuClock.stampPayload(eventType, payload).toString()
        SyncOutbox.insert {
            it[eventId] = UUID.randomUUID().toString()
            it[SyncOutbox.eventType] = eventType
            it[SyncOutbox.aggregateType] = aggregateType
            it[SyncOutbox.aggregateId] = aggregateId
            it[SyncOutbox.payload] = body
            it[createdAt] = VenueClock.now()
        }
    }
}
