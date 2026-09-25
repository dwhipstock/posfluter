package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.DeviceRegistry
import dev.dwhipstock.pos.sync.ChangesPage
import dev.dwhipstock.pos.sync.CloudTransport
import dev.dwhipstock.pos.sync.PushEvent
import dev.dwhipstock.pos.sync.PushResult
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M8 terminal pairing + device gate: /pair exchanges a cloud-verified code for a
 * device token; with POS_REQUIRE_DEVICE_TOKEN the terminal surface (staff tiles,
 * PIN login, and every device-bound session call) demands that token; a
 * device_revocation from the changes feed cuts the device AND its sessions off;
 * customer-facing routes and staff-app 2FA never need a device.
 */
class DevicePairingTest {

    private fun tempDb() = Files.createTempDirectory("pos-test").resolve("pos.db").toString()

    /** Claims succeed for [validCodes]; everything else is the cloud's bad_pairing_code refusal. */
    private class FakeTransport(private val validCodes: MutableSet<String>) : CloudTransport {
        override fun push(installId: String, events: List<PushEvent>) = PushResult(true)
        override fun fetchRevocations(since: Long) = ChangesPage(since, emptyList())
        override fun pushPhoto(itemId: String, bytes: ByteArray, contentType: String) = PushResult(true)
        // detail carries the cloud's machine code, matching HttpCloudTransport.claimPairing
        // (which parses `code` out of the error body) rather than raw HTTP text.
        override fun claimPairing(code: String): PushResult =
            if (validCodes.remove(code.trim())) PushResult(true)
            else PushResult(false, "bad_pairing_code")
    }

    private suspend fun ApplicationTestBuilder.pair(code: String, name: String = "Bar"): HttpResponse =
        client.post("/pair") {
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$code","deviceName":"$name"}""")
        }

    @Test
    fun pairingFlowAndDeviceGate() = testApplication {
        val transport = FakeTransport(mutableSetOf("GOODCODE"))
        application {
            module(dbPath = tempDb(), requireDeviceTokenOverride = true, pairingTransport = transport)
        }

        // health advertises the gate so the client shows the pairing screen
        val health = Json.parseToJsonElement(client.get("/health").bodyAsText()).jsonObject
        assertEquals(true, health["pairingRequired"]!!.jsonPrimitive.content.toBoolean())

        // unpaired: the terminal surface is walled off…
        assertEquals(HttpStatusCode.Unauthorized, client.get("/staff").status)
        val deniedLogin = client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"1234"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, deniedLogin.status)
        assertEquals("device_required",
            Json.parseToJsonElement(deniedLogin.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
        // …but customer-facing routes stay open
        assertEquals(HttpStatusCode.OK, client.get("/items").status)
        assertEquals(HttpStatusCode.OK, client.get("/m/t5").status)

        // a bad code is refused; the good one pairs exactly once
        assertEquals(HttpStatusCode.NotFound, pair("WRONG").status)
        val paired = pair("GOODCODE")
        assertEquals(HttpStatusCode.OK, paired.status)
        val body = Json.parseToJsonElement(paired.bodyAsText()).jsonObject
        val deviceToken = body["deviceToken"]!!.jsonPrimitive.content
        val deviceId = body["deviceId"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.NotFound, pair("GOODCODE").status) // single-use at the cloud

        // with the device token the terminal surface opens up
        assertEquals(HttpStatusCode.OK, client.get("/staff") {
            header("X-Device-Token", deviceToken)
        }.status)
        val login = client.post("/login") {
            header("X-Device-Token", deviceToken)
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"1234"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val session = Json.parseToJsonElement(login.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content

        // the session is BOUND to the device: bearer alone is not enough anymore
        assertEquals(HttpStatusCode.Unauthorized, client.get("/zones") {
            header(HttpHeaders.Authorization, "Bearer $session")
        }.status)
        assertEquals(HttpStatusCode.OK, client.get("/zones") {
            header(HttpHeaders.Authorization, "Bearer $session")
            header("X-Device-Token", deviceToken)
        }.status)

        // cloud revocation cuts off the device and its sessions at once
        DeviceRegistry.applyRevocation(deviceId)
        val revoked = client.get("/zones") {
            header(HttpHeaders.Authorization, "Bearer $session")
            header("X-Device-Token", deviceToken)
        }
        assertEquals(HttpStatusCode.Unauthorized, revoked.status)
        assertEquals("device_revoked",
            Json.parseToJsonElement(revoked.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
        val reLogin = client.post("/login") {
            header("X-Device-Token", deviceToken)
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"1234"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, reLogin.status)
        assertEquals("device_revoked",
            Json.parseToJsonElement(reLogin.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)

        // the registry summary reports both devices' states for the heartbeat
        val summary = DeviceRegistry.summaries()
        assertNotNull(summary.firstOrNull { it.id == deviceId && it.revoked })
    }

    @Test
    fun revocationInvalidatesTheCacheOnlyAfterThePullCommits() = testApplication {
        // The device cache must be cleared on COMMIT of the enclosing pull tx, not
        // eagerly: otherwise a concurrent read in the window re-caches the device as
        // live (forever), and a rollback would wrongly mutate the cache.
        val transport = FakeTransport(mutableSetOf("GOODCODE"))
        application {
            module(dbPath = tempDb(), requireDeviceTokenOverride = true, pairingTransport = transport)
        }
        val paired = Json.parseToJsonElement(pair("GOODCODE").bodyAsText()).jsonObject
        val deviceToken = paired["deviceToken"]!!.jsonPrimitive.content
        val deviceId = paired["deviceId"]!!.jsonPrimitive.content

        // prime the cache with a LIVE entry, as a request from the terminal would
        assertEquals(false, DeviceRegistry.byToken(deviceToken)!!.revoked)

        // revoke inside an explicit tx we control, mirroring CloudSync's enclosing pull tx
        org.jetbrains.exposed.sql.transactions.transaction {
            DeviceRegistry.applyRevocation(deviceId)
            // window: uncommitted — a concurrent read still sees the cached LIVE entry,
            // and the invalidation must NOT have fired yet
            assertEquals(false, DeviceRegistry.byToken(deviceToken)!!.revoked,
                "cache must not be invalidated before the pull tx commits")
        }
        // once committed, the afterCommit hook cleared the cache → DB re-read shows revoked
        assertEquals(true, DeviceRegistry.byToken(deviceToken)!!.revoked,
            "device must be revoked and the cache must reflect it once committed")
    }

    @Test
    fun seedNoneBootsTrulyEmpty() = testApplication {
        // POS_SEED=none must yield an empty store, with no demo catalog residue
        // available to the first cloud snapshot.
        application { module(dbPath = tempDb(), seedMode = "none") }
        val items = Json.parseToJsonElement(client.get("/items").bodyAsText())
        assertEquals(0, (items as kotlinx.serialization.json.JsonArray).size)
        val categories = Json.parseToJsonElement(client.get("/categories").bodyAsText())
        assertEquals(0, (categories as kotlinx.serialization.json.JsonArray).size)
        // A fresh cloud venue must not inherit demo payment or fee settings.
        val settings = dev.dwhipstock.pos.base.SettingsRepository().get()
        assertEquals("", settings.cardProcessor, "card processor must be blank on a fresh cloud venue")
        assertEquals("", settings.bankName)
        assertEquals(0L, settings.corkagePerBottleCents)
    }

    @Test
    fun offStoresKeepLegacyBehaviorAndPairingIsUnavailableWithoutCloud() = testApplication {
        application { module(dbPath = tempDb()) } // gate off, no cloud transport

        // LAN flow untouched: login without any device token
        val login = client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"1234"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val session = Json.parseToJsonElement(login.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.OK, client.get("/zones") {
            header(HttpHeaders.Authorization, "Bearer $session")
        }.status)

        // pairing without a cloud connection is a clear 503, not a mystery
        val res = client.post("/pair") {
            contentType(ContentType.Application.Json)
            setBody("""{"code":"ANYTHING"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
        assertEquals("pairing_unavailable",
            Json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun staffAppPreAuthIsWalledOffOnDeviceGatedVenues() = testApplication {
        // Security regression (review F4/F5): on a device-gated cloud venue the
        // staff-app 2FA pre-auth surface must NOT be reachable without a paired
        // device — otherwise an unpaired internet client could PIN-guess and be
        // handed a not-yet-enrolled staff's TOTP secret, then a device-unbound
        // session that passes the whole gate.
        val transport = FakeTransport(mutableSetOf())
        application {
            module(dbPath = tempDb(), requireDeviceTokenOverride = true, pairingTransport = transport)
        }

        // /staff-app/login and /staff-app/totp demand a device on a gated venue
        for (path in listOf("/staff-app/login", "/staff-app/totp")) {
            val res = client.post(path) {
                contentType(ContentType.Application.Json)
                setBody("""{"pin":"1234","code":"000000"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, res.status, "$path must be device-gated")
            assertEquals("device_required",
                Json.parseToJsonElement(res.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
        }

        // the /staff-app shell page itself stays public (it's just HTML)
        val shell = client.get("/staff-app")
        assertEquals(HttpStatusCode.OK, shell.status)
        val html = shell.bodyAsText()
        assertTrue("--accent: #1565c0" in html)
        assertTrue("const cad =" in html)
        assertTrue("const CAD =" !in html)
    }
}
