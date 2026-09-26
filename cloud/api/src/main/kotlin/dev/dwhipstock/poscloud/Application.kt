package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.auth.authRoutes
import dev.dwhipstock.poscloud.auth.installPortalSessions
import dev.dwhipstock.poscloud.db.Db
import dev.dwhipstock.poscloud.db.Migrations
import dev.dwhipstock.poscloud.menu.menuRoutes
import dev.dwhipstock.poscloud.reports.reportRoutes
import dev.dwhipstock.poscloud.staff.staffRoutes
import dev.dwhipstock.poscloud.stock.stockRoutes
import dev.dwhipstock.poscloud.store.staffEndpointRoute
import dev.dwhipstock.poscloud.store.storeRoutes
import dev.dwhipstock.poscloud.venues.venueRoutes
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import java.io.File

fun main() {
    val config = CloudConfig()
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { module(config) }
        .start(wait = true)
}

fun Application.module(config: CloudConfig = CloudConfig()) {
    val db = Db.connect(config)
    Migrations.run(db, File(config.migrationsDir))
    Bootstrap.run(config)
    installPortalSessions(config)

    install(ContentNegotiation) {
        // explicit nulls: API.md promises null fields (open-shift close columns,
        // photoVersion) and the portal checks `=== null` — absent keys read as
        // undefined there and rendered $NaN / broken ?v=undefined photo URLs
        json(Json { encodeDefaults = true; ignoreUnknownKeys = true })
    }
    // Caddy fronts this in production; the rate limiter needs real client IPs
    install(XForwardedHeaders)
    install(CallLogging)
    install(StatusPages) {
        // error bodies: machine `code` for the portal + english `error` for logs (API.md)
        exception<NotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound,
                mapOf("error" to (cause.message ?: "not found"), "code" to cause.code))
        }
        exception<ConflictException> { call, cause ->
            call.respond(HttpStatusCode.Conflict,
                mapOf("error" to (cause.message ?: "conflict"), "code" to cause.code))
        }
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest,
                mapOf("error" to (cause.message ?: "bad request"), "code" to cause.code))
        }
        exception<UnauthorizedException> { call, cause ->
            call.respond(HttpStatusCode.Unauthorized,
                mapOf("error" to (cause.message ?: "not authenticated"), "code" to cause.code))
        }
        exception<PayloadTooLargeException> { call, cause ->
            call.respond(HttpStatusCode.PayloadTooLarge,
                mapOf("error" to (cause.message ?: "payload too large"), "code" to cause.code))
        }
        exception<RateLimitException> { call, cause ->
            call.response.header(HttpHeaders.RetryAfter, cause.retryAfterSeconds.toString())
            call.respond(HttpStatusCode.TooManyRequests,
                mapOf("error" to (cause.message ?: "rate limited"), "code" to "rate_limited"))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest,
                mapOf("error" to (cause.message ?: "bad request"), "code" to "bad_request"))
        }
        exception<io.ktor.server.plugins.BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest,
                mapOf("error" to (cause.message ?: "malformed request"), "code" to "bad_request"))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("unhandled", cause)
            call.respond(HttpStatusCode.InternalServerError,
                mapOf("error" to (cause.message ?: "unknown"), "code" to "internal"))
        }
    }
    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }
        route("/v1") {
            authRoutes(config)
            storeRoutes(config)
            staffEndpointRoute() // public: portal /staff-app redirect reads this
            reportRoutes(config.fxRates)
            stockRoutes()
            menuRoutes()
            staffRoutes()
            venueRoutes(config)
        }
    }
}
