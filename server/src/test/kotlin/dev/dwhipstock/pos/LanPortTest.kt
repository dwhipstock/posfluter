package dev.dwhipstock.pos

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two brand apps can run on one tablet (Copper Lantern :8080, Sage & Poppy
 * :8082). The LAN address a store hands to staff/customer phones must carry
 * its own port, not a hardcoded 8080.
 */
class LanPortTest {

    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    private suspend fun ApplicationTestBuilder.storeUrl(): String? {
        val body = loginClient().get("/cloud/info").bodyAsText()
        return Json.parseToJsonElement(body).jsonObject["storeUrl"]
            ?.jsonPrimitive?.takeIf { it.isString }?.content
    }

    @Test
    fun autoDetectedLanAddressUsesTheStoresOwnPort() = testApplication {
        application { module(dbPath = tempDb(), venueId = "sage-poppy", publicUrl = null, lanPort = 8082) }
        // null only on a machine with no LAN address at all
        val url = storeUrl()
        if (url != null) assertTrue(url.endsWith(":8082"), url)
        if (detectLanIpv4() != null) assertEquals("http://${detectLanIpv4()}:8082", url)
    }

    @Test
    fun anExplicitPublicUrlStillWins() = testApplication {
        application { module(dbPath = tempDb(), publicUrl = "http://10.1.2.3:9000", lanPort = 8082) }
        assertEquals("http://10.1.2.3:9000", storeUrl())
    }
}
