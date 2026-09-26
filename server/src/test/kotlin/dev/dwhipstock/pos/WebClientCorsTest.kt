package dev.dwhipstock.pos

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The POS client also builds for the web (a demo on a Mac with no Xcode). A
 * browser sends a CORS preflight — no credentials, by design — before calls
 * with a JSON body or a bearer token; it must reach the CORS plugin, not the
 * auth gate. The real request is still gated.
 */
class WebClientCorsTest {

    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    @Test
    fun preflightsPassAndTheRealRequestIsStillGated() = testApplication {
        application { module(dbPath = tempDb()) }
        val preflight = client.options("/checks/recent") {
            header(HttpHeaders.Origin, "http://localhost:5055")
            header(HttpHeaders.AccessControlRequestMethod, "GET")
            header(HttpHeaders.AccessControlRequestHeaders, "authorization,content-type")
        }
        assertEquals(HttpStatusCode.OK, preflight.status)
        assertNotNull(preflight.headers[HttpHeaders.AccessControlAllowOrigin])
        val real = client.get("/checks/recent") { header(HttpHeaders.Origin, "http://localhost:5055") }
        assertEquals(HttpStatusCode.Unauthorized, real.status)
    }
}
