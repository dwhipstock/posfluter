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
