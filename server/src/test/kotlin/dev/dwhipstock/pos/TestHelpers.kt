package dev.dwhipstock.pos

import io.ktor.client.HttpClient
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll

/** Log in (default: manager PIN) and return a client that sends the bearer token. */
suspend fun ApplicationTestBuilder.loginClient(pin: String = "1234"): HttpClient {
    val res = client.post("/login") {
        contentType(ContentType.Application.Json)
        setBody("""{"pin":"$pin"}""")
    }
    check(res.status == HttpStatusCode.OK) { "login failed: ${res.status}" }
    val token = Json.parseToJsonElement(res.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    return createClient {
        install(DefaultRequest) { header(HttpHeaders.Authorization, "Bearer $token") }
    }
}

/**
 * A table's customer link token, read straight from the store db. Starts the
 * test application first so the lookup hits THIS test's database.
 */
suspend fun ApplicationTestBuilder.tableToken(tableId: String): String {
    startApplication()
    return rawTableToken(tableId)
}

private fun rawTableToken(tableId: String): String = org.jetbrains.exposed.sql.transactions.transaction {
    dev.dwhipstock.pos.restaurant.DiningTables.selectAll()
        .where { dev.dwhipstock.pos.restaurant.DiningTables.id eq tableId }
        .first()[dev.dwhipstock.pos.restaurant.DiningTables.publicToken]!!
}

/** The guest-facing path for a table: /m/t/{token}. */
suspend fun ApplicationTestBuilder.customerPath(tableId: String): String = "/m/t/${tableToken(tableId)}"

/** A slip-page ticket from an authenticated client. */
suspend fun HttpClient.slipTicket(): String =
    Json.parseToJsonElement(post("/slips/ticket").bodyAsText()).jsonObject["ticket"]!!.jsonPrimitive.content
