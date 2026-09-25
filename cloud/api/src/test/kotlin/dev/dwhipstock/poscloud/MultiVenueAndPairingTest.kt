package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.CatalogChanges
import dev.dwhipstock.poscloud.db.PairingCodes
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Before
import org.junit.Test
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Group model under one-way sync: every store pushes its own staff and menu
 * (venue-scoped projections, read-only in the portal); the only cloud → store
 * feed is device revocations, and it is venue-scoped; pairing codes are
 * single-use and venue-bound; /reports/by-venue combines venues.
 */
class MultiVenueAndPairingTest {

    private val keyA = "store-key-venue-a"
    private val keyB = "store-key-venue-b"
    private lateinit var session: String

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("copperlantern", "vieux-port")
        seedTenant("copperlantern", "patio") // second venue, same tenant/group
        seedStoreKey("copperlantern", "vieux-port", keyA)
        seedStoreKey("copperlantern", "patio", keyB)
        session = seedSession("copperlantern", seedUser("copperlantern", "owner@test.dev", "password-g"))
    }

    private fun cookie() = "pos_portal_session=$session"

    private suspend fun ApplicationTestBuilder.feed(key: String, since: Long = 0): List<JsonObject> {
        val res = client.get("/v1/store/revocations?since=$since") {
            header(HttpHeaders.Authorization, "Bearer $key")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        return testJson.parseToJsonElement(res.bodyAsText()).jsonObject["changes"]!!
            .jsonArray.map { it.jsonObject }
    }

    private fun staffJson(id: String, name: String, role: String = "SERVER", deleted: Boolean = false) =
        buildJsonObject {
            put("id", id); put("name", name); put("role", role)
            put("active", true); put("deleted", deleted)
            put("overrides", buildJsonObject { if (role == "SERVER") put("refund", true) })
        }

    private suspend fun ApplicationTestBuilder.staffIds(venue: String? = null): List<String> {
        val res = client.get("/v1/staff" + (venue?.let { "?venue=$it" } ?: "")) {
            header(HttpHeaders.Cookie, cookie())
        }
        assertEquals(HttpStatusCode.OK, res.status)
        return testJson.parseToJsonElement(res.bodyAsText()).jsonObject["staff"]!!
            .jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
    }

    // --- store-owned staff (one-way sync) ---

    @Test
    fun storeStaffSnapshotsProjectPerVenueAndStayReadOnly() = testApplication {
        application { module(TestSupport.config) }
        ingest(keyA, event("staff.snapshot", buildJsonObject {
            put("staff", buildJsonArray { add(staffJson("manager", "Manager", "MANAGER")); add(staffJson("camille", "Camille")) })
            put("roles", buildJsonObject {
                put("SERVER", buildJsonObject { put("void", true) })
            })
        }, seq = 1))
        // the same id at another store is a different person
        ingest(keyB, event("staff.created", buildJsonObject {
            put("staffId", "camille"); put("staff", staffJson("camille", "Camille B."))
        }, seq = 1))

        assertEquals(listOf("manager", "camille"), staffIds("vieux-port"))
        assertEquals(listOf("camille"), staffIds("patio"))

        val main = testJson.parseToJsonElement(client.get("/v1/staff?venue=vieux-port") {
            header(HttpHeaders.Cookie, cookie())
        }.bodyAsText()).jsonObject
        val camille = main["staff"]!!.jsonArray.map { it.jsonObject }.single { it["id"]!!.jsonPrimitive.content == "camille" }
        assertEquals("true", camille["overrides"]!!.jsonObject["refund"]!!.jsonPrimitive.content)
        assertEquals("true", main["roleGrants"]!!.jsonObject["SERVER"]!!.jsonObject["void"]!!.jsonPrimitive.content)

        // a store-side delete drops the member from the portal list
        ingest(keyA, event("staff.deleted", buildJsonObject {
            put("staffId", "camille"); put("staff", staffJson("camille", "Camille", deleted = true))
        }, seq = 2))
        assertEquals(listOf("manager"), staffIds("vieux-port"))

        // the portal cannot write staff any more, and nothing is ever queued for a store
        val post = client.post("/v1/staff") {
            header(HttpHeaders.Cookie, cookie())
            contentType(ContentType.Application.Json)
            setBody("""{"name":"X","role":"SERVER","pin":"4321"}""")
        }
        assertTrue(post.status == HttpStatusCode.NotFound || post.status == HttpStatusCode.MethodNotAllowed)
        assertEquals(0, transaction { CatalogChanges.selectAll().count() })
    }

    @Test
    fun capabilitiesAdvertiseInstantTimestampsToAKeyedStoreOnly() = testApplication {
        application { module(TestSupport.config) }
        val res = client.get("/v1/store/capabilities") { header(HttpHeaders.Authorization, "Bearer $keyA") }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = testJson.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals("instant", body["timestampFormat"]!!.jsonPrimitive.content)
        assertEquals("2", body["contractVersion"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/store/capabilities").status)
    }

    @Test
    fun venueOutsideTheTenantIs404() = testApplication {
        application { module(TestSupport.config) }
        val foreign = client.get("/v1/staff?venue=nope") { header(HttpHeaders.Cookie, cookie()) }
        assertEquals(HttpStatusCode.NotFound, foreign.status)
    }

    @Test
    fun revocationFeedServesOnlyRevocationsEvenOnTheLegacyPath() = testApplication {
        application { module(TestSupport.config) }
        // a leftover distribution row from before one-way sync
        transaction {
            CatalogChanges.insert {
                it[tenantId] = "copperlantern"; it[venueId] = "vieux-port"; it[kind] = "item"
                it[entityId] = "x"; it[op] = "upsert"; it[data] = "{}"; it[createdAt] = dev.dwhipstock.poscloud.CloudTime.now()
            }
        }
        assertEquals(0, feed(keyA).size)
        val legacy = client.get("/v1/store/catalog/changes?since=0") {
            header(HttpHeaders.Authorization, "Bearer $keyA")
        }
        assertEquals(HttpStatusCode.OK, legacy.status)
        assertEquals(0, testJson.parseToJsonElement(legacy.bodyAsText()).jsonObject["changes"]!!.jsonArray.size)
        // the old photo download endpoint is gone
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/store/photos/x") {
            header(HttpHeaders.Authorization, "Bearer $keyA")
        }.status)
    }

    @Test
    fun ghostDeviceCanBeDeletedFromTheRegistry() = testApplication {
        // review F15: a device the store no longer reports lingers; DELETE removes it.
        application { module(TestSupport.config) }
        client.post("/v1/store/heartbeat") {
            header(HttpHeaders.Authorization, "Bearer $keyA")
            contentType(ContentType.Application.Json)
            setBody("""{"lanBaseUrl":"http://192.168.1.9:8080","devices":[{"id":"ghost","name":"Old"}]}""")
        }
        val del = client.delete("/v1/venues/vieux-port/devices/ghost") { header(HttpHeaders.Cookie, cookie()) }
        assertEquals(HttpStatusCode.OK, del.status)
        val list = client.get("/v1/venues/vieux-port/devices") { header(HttpHeaders.Cookie, cookie()) }
        assertEquals(0, testJson.parseToJsonElement(list.bodyAsText()).jsonObject["devices"]!!.jsonArray.size)
        // a second delete is a clean 404
        assertEquals(HttpStatusCode.NotFound,
            client.delete("/v1/venues/vieux-port/devices/ghost") { header(HttpHeaders.Cookie, cookie()) }.status)
    }

    // --- pairing ---

    private suspend fun ApplicationTestBuilder.mintCode(venue: String = "vieux-port"): String {
        val res = client.post("/v1/venues/$venue/pairing-codes") {
            header(HttpHeaders.Cookie, cookie())
            contentType(ContentType.Application.Json)
            setBody("""{"label":"Bar tablet"}""")
        }
        assertEquals(HttpStatusCode.Created, res.status)
        return testJson.parseToJsonElement(res.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.claim(key: String, code: String): HttpResponse =
        client.post("/v1/store/pairing/claim") {
            header(HttpHeaders.Authorization, "Bearer $key")
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$code"}""")
        }

    @Test
    fun pairingCodeIsSingleUseAndVenueBound() = testApplication {
        application { module(TestSupport.config) }

        val code = mintCode("vieux-port")
        // the wrong venue's store can never redeem it — and must not learn why
        assertEquals(HttpStatusCode.NotFound, claim(keyB, code).status)
        // the right venue redeems once (case/dash-insensitively)…
        assertEquals(HttpStatusCode.OK, claim(keyA, code.lowercase().replace("-", " ")).status)
        // …and only once
        assertEquals(HttpStatusCode.NotFound, claim(keyA, code).status)
    }

    @Test
    fun expiredPairingCodeRefuses() = testApplication {
        application { module(TestSupport.config) }

        val code = mintCode("vieux-port")
        transaction {
            PairingCodes.update { it[expiresAt] = dev.dwhipstock.poscloud.CloudTime.now().minusMinutes(1) }
        }
        assertEquals(HttpStatusCode.NotFound, claim(keyA, code).status)
    }

    // --- devices ---

    private suspend fun ApplicationTestBuilder.heartbeat(key: String, devices: String): HttpResponse =
        client.post("/v1/store/heartbeat") {
            header(HttpHeaders.Authorization, "Bearer $key")
            contentType(ContentType.Application.Json)
            setBody("""{"lanBaseUrl":"http://192.168.1.50:8080","devices":$devices}""")
        }

    @Test
    fun heartbeatMirrorsDevicesAndRevokeRidesTheFeed() = testApplication {
        application { module(TestSupport.config) }

        val hb = heartbeat(keyA,
            """[{"id":"dev-1","name":"Bar","pairedAt":"2026-07-20T10:00:00","lastSeenAt":"2026-07-21T09:00:00","revoked":false}]""")
        assertEquals(HttpStatusCode.OK, hb.status)

        val list = client.get("/v1/venues/vieux-port/devices") { header(HttpHeaders.Cookie, cookie()) }
        assertEquals(HttpStatusCode.OK, list.status)
        val devices = testJson.parseToJsonElement(list.bodyAsText()).jsonObject["devices"]!!.jsonArray
        assertEquals(1, devices.size)
        assertEquals("Bar", devices[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertNull(devices[0].jsonObject["revokeRequestedAt"]!!.jsonPrimitive.contentOrNull())

        val revoke = client.post("/v1/venues/vieux-port/devices/dev-1/revoke") { header(HttpHeaders.Cookie, cookie()) }
        assertEquals(HttpStatusCode.OK, revoke.status)

        // the revocation is on MAIN's feed only
        val change = feed(keyA).lastOrNull { it["kind"]!!.jsonPrimitive.content == "device_revocation" }
        assertNotNull(change)
        assertEquals("dev-1", change["data"]!!.jsonObject["deviceId"]!!.jsonPrimitive.content)
        assertTrue(feed(keyB).none { it["kind"]!!.jsonPrimitive.content == "device_revocation" })

        // intent is visible until the store confirms; store truth stays revoked=false for now
        val after = client.get("/v1/venues/vieux-port/devices") { header(HttpHeaders.Cookie, cookie()) }
        val dev = testJson.parseToJsonElement(after.bodyAsText()).jsonObject["devices"]!!.jsonArray[0].jsonObject
        assertNotNull(dev["revokeRequestedAt"]!!.jsonPrimitive.contentOrNull())
        assertEquals(false, dev["revoked"]!!.jsonPrimitive.content.toBoolean())

        val missing = client.post("/v1/venues/vieux-port/devices/ghost/revoke") { header(HttpHeaders.Cookie, cookie()) }
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }

    // --- combined reporting ---

    @Test
    fun byVenueRollupSeesBothVenues() = testApplication {
        application { module(TestSupport.config) }

        suspend fun close(key: String, checkId: Int, totalCents: Long) {
            ingest(key, event("check.closed", buildJsonObject {
                put("checkId", checkId)
                put("status", "CLOSED")
                put("closedAt", "2026-07-21T12:00:00")
                put("grandTotalCents", totalCents)
                put("taxIncludedCents", totalCents * 13 / 113)
            }, seq = 1, aggregateId = checkId.toString()))
        }
        close(keyA, 1, 10000)
        close(keyB, 1, 25000)

        val res = client.get("/v1/reports/by-venue?from=2026-07-21&to=2026-07-21") {
            header(HttpHeaders.Cookie, cookie())
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = testJson.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals(35000, body["grossCents"]!!.jsonPrimitive.content.toLong())
        val rows = body["venues"]!!.jsonArray.associate {
            it.jsonObject["venueId"]!!.jsonPrimitive.content to
                it.jsonObject["grossCents"]!!.jsonPrimitive.content.toLong()
        }
        assertEquals(10000, rows["vieux-port"])
        assertEquals(25000, rows["patio"])
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content
