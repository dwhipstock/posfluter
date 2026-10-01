package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.base.BadRequestException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The JSON reader for the open routes guests' phones and kiosks call (the
 * QR menu's basket, the kiosk's pairing, cart and order). Lenient about what
 * it does not know (an older or newer app may send extra fields), strict about
 * what it needs: a body that is not JSON, or misses a field, or has a field of
 * the wrong type, is a 400 `bad_body`, never a 500 with a stack trace.
 */
val publicJson = Json { ignoreUnknownKeys = true }

suspend inline fun <reified T> ApplicationCall.receivePublic(): T {
    val text = receiveText()
    return try {
        publicJson.decodeFromString<T>(text)
    } catch (e: SerializationException) {
        throw BadRequestException("malformed request body", "bad_body")
    } catch (e: IllegalArgumentException) {
        // a data class's own require(...) while decoding
        throw BadRequestException(e.message ?: "malformed request body", "bad_body")
    }
}
