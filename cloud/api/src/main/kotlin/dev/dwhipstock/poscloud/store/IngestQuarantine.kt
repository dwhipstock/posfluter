package dev.dwhipstock.poscloud.store

import dev.dwhipstock.poscloud.db.IngestQuarantine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.insertIgnore
import org.slf4j.LoggerFactory

/**
 * The text of an ingested event, made storable: Postgres refuses U+0000 in
 * TEXT and JSONB, and a lone surrogate half is not valid UTF-8, so both are
 * dropped; other control characters (ESC, BEL...) are dropped too, since
 * nothing in a POS event needs them. Tabs and line breaks are kept. Every
 * string is cleaned, the payload's keys and values however deep included.
 */
object IngestText {
    fun clean(e: IngestEvent): IngestEvent = e.copy(
        eventId = text(e.eventId), eventType = text(e.eventType),
        aggregateType = text(e.aggregateType), aggregateId = text(e.aggregateId),
        createdAt = text(e.createdAt), payload = json(e.payload) as JsonObject,
    )

    private fun json(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.entries.associate { (k, v) -> text(k) to json(v) })
        is JsonArray -> JsonArray(e.map(::json))
        is JsonPrimitive -> if (e.isString) text(e.content).let { if (it === e.content) e else JsonPrimitive(it) } else e
    }

    private fun bad(c: Char) = (c.isISOControl() && c != '\t' && c != '\n' && c != '\r') || c.isSurrogate()

    fun text(raw: String): String {
        if (raw.none(::bad)) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c.isHighSurrogate() && i + 1 < raw.length && raw[i + 1].isLowSurrogate()) {
                out.append(c).append(raw[i + 1]); i += 2; continue
            }
            if (!bad(c)) out.append(c)
            i++
        }
        return out.toString()
    }
}

private val log = LoggerFactory.getLogger("dev.dwhipstock.poscloud.store.IngestQuarantine")

/**
 * Set [raw] aside (inside the batch's transaction): kept as it came, with the
 * error, so it can be looked at and replayed. A failure here is NOT caught: it
 * fails the batch, so the store resends it rather than losing the event.
 */
internal fun quarantine(tenant: String, venue: String, raw: IngestEvent, clean: IngestEvent, cause: Exception) {
    val why = (cause.message ?: cause::class.java.simpleName).lineSequence().first().take(500)
    IngestQuarantine.insertIgnore {
        it[tenantId] = tenant
        it[venueId] = venue
        it[eventId] = clean.eventId
        it[eventType] = clean.eventType
        it[aggregateType] = clean.aggregateType
        it[aggregateId] = clean.aggregateId
        it[storeSeq] = raw.seq
        it[storeCreatedAt] = clean.createdAt
        // JSON text escapes U+0000 as "\u0000": a TEXT column holds it as it came
        it[payload] = raw.payload.toString()
        it[error] = IngestText.text(why)
    }
    log.error("ingest: event ${clean.eventId} (${clean.eventType}, store seq ${raw.seq}) of $tenant/$venue " +
        "could not be stored ($why); set aside in ingest_quarantine, the rest of the batch goes on")
}
