package dev.dwhipstock.pos.base

import kotlinx.serialization.json.JsonObject

/**
 * API errors carry a machine code; the client translates. The message is
 * developer-facing English for logs/debugging — never shown to staff verbatim.
 */
class NotFoundException(message: String, val code: String = "not_found") : RuntimeException(message)
class ConflictException(
    message: String, val code: String = "conflict",
    /** Extra machine-readable fields for the error body (e.g. which bills block a shift close). */
    val details: JsonObject? = null,
) : RuntimeException(message)
class BadRequestException(message: String, val code: String = "bad_request") : RuntimeException(message)
