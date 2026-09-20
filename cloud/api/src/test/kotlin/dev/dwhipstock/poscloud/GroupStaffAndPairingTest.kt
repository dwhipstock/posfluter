package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.PairingCodes
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M8 group model: staff are tenant-scoped with per-venue assignments; the
 * changes feed is venue-scoped (a store key only ever sees its own venue's
 * changes); pairing codes are single-use and venue-bound; device revocations
 * ride the feed; /reports/by-venue combines venues.
 */
class GroupStaffAndPairingTest {

    private val keyA = "store-key-venue-a"
    private val keyB = "store-key-venue-b"
    private lateinit var session: String

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("copperlantern", "main")
        seedTenant("copperlantern", "patio") // second venue, same tenant/group
        seedStoreKey("copperlantern", "main", keyA)
        seedStoreKey("copperlantern", "patio", keyB)
        session = seedSession("copperlantern", seedUser("copperlantern", "owner@test.dev", "password-g"))
    }

    private fun cookie() = "pos_portal_session=$session"

    private suspend fun ApplicationTestBuilder.feed(key: String, since: Long = 0): List<JsonObject> {
        val res = client.get("/v1/store/catalog/changes?since=$since") {
            header(HttpHeaders.Authorization, "Bearer $key")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        return testJson.parseToJsonElement(res.bodyAsText()).jsonObject["changes"]!!
            .jsonArray.map { it.jsonObject }
    }

    private suspend fun ApplicationTestBuilder.createStaff(name: String, pin: String = "4321"): String {
        val res = client.post("/v1/staff") {
            header(HttpHeaders.Cookie, cookie())
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","role":"SERVER","pin":"$pin"}""")
        }
        assertEquals(HttpStatusCode.Created, res.status)
        return testJson.parseToJsonElement(res.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.assignVenues(staffId: String, vararg pairs: Pair<String, String>): HttpResponse =
        client.put("/v1/staff/$staffId/venues") {
            header(HttpHeaders.Cookie, cookie())
            contentType(ContentType.Application.Json)
            setBody(buildString {
                append("""{"venues":[""")
                append(pairs.joinToString(",") { """{"venueId":"${it.first}","role":"${it.second}"}""" })
                append("]}")
            })
        }

    // --- venue-scoped feed ---

    @Test
    fun feedIsVenueScoped() = testApplication {
        application { module(TestSupport.config) }

        // staff created under the default venue (main) must never reach patio's feed
        val staffId = createStaff("Somchai")
        val a = feed(keyA)
        val b = feed(keyB)
        assertTrue(a.any { it["kind"]!!.jsonPrimitive.content == "staff" &&
            it["data"]!!.jsonObject["id"]!!.jsonPrimitive.content == staffId })
        assertTrue(b.none { it["kind"]!!.jsonPrimitive.content == "staff" })
    }

    // --- group staff ---

    @Test
    fun staffAssignedToTwoVenuesDistributesToBoth() = testApplication {
        application { module(TestSupport.config) }

        val staffId = createStaff("Nok")
        val res = assignVenues(staffId, "main" to "SERVER", "patio" to "MANAGER")
        assertEquals(HttpStatusCode.OK, res.status)
        val dto = testJson.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals("MANAGER", dto["venues"]!!.jsonObject["patio"]!!.jsonPrimitive.content)

        // each venue's feed carries the snapshot with the role AT THAT venue
        val aSnap = feed(keyA).last { it["kind"]!!.jsonPrimitive.content == "staff" &&
            it["data"]!!.jsonObject["id"]!!.jsonPrimitive.content == staffId }
        val bSnap = feed(keyB).last { it["kind"]!!.jsonPrimitive.content == "staff" &&
            it["data"]!!.jsonObject["id"]!!.jsonPrimitive.content == staffId }
        assertEquals("SERVER", aSnap["data"]!!.jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("MANAGER", bSnap["data"]!!.jsonObject["role"]!!.jsonPrimitive.content)

        // PIN reset ripples to every assigned venue
        val beforeA = feed(keyA).size
        val beforeB = feed(keyB).size
        val pin = client.post("/v1/staff/$staffId/pin") {
            header(HttpHeaders.Cookie, cookie())
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"7777"}""")
        }
        assertEquals(HttpStatusCode.OK, pin.status)
        assertTrue(feed(keyA).size > beforeA)
        assertTrue(feed(keyB).size > beforeB)

        // unassigning patio emits a delete THERE only. Nok is patio's lone manager,
        // so removing them outright would trip the lockout guard — cover patio with
        // the seeded manager first (which also exercises the guard's regression rule).
        assertEquals(HttpStatusCode.Conflict, assignVenues(staffId, "main" to "SERVER").status)
        assertEquals(HttpStatusCode.OK,
            assignVenues("manager", "main" to "MANAGER", "patio" to "MANAGER").status)
        assertEquals(HttpStatusCode.OK, assignVenues(staffId, "main" to "SERVER").status)
        val bAfter = feed(keyB).last { it["kind"]!!.jsonPrimitive.content == "staff" &&
            it["data"]!!.jsonObject["id"]!!.jsonPrimitive.content == staffId }
        assertEquals("delete", bAfter["op"]!!.jsonPrimitive.content)
        val aAfter = feed(keyA).last { it["kind"]!!.jsonPrimitive.content == "staff" &&
            it["data"]!!.jsonObject["id"]!!.jsonPrimitive.content == staffId }
        assertEquals("upsert", aAfter["op"]!!.jsonPrimitive.content)
    }

    @Test
    fun venueQueryParamScopesStaffList() = testApplication {
        application { module(TestSupport.config) }

        val staffId = createStaff("Lek")
        assignVenues(staffId, "patio" to "SERVER").let { assertEquals(HttpStatusCode.OK, it.status) }

        val patio = client.get("/v1/staff?venue=patio") { header(HttpHeaders.Cookie, cookie()) }
        assertEquals(HttpStatusCode.OK, patio.status)
        val patioIds = testJson.parseToJsonElement(patio.bodyAsText()).jsonObject["staff"]!!
            .jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf(staffId), patioIds)

        // a venue outside the tenant is a 404, never a fall-through
        val foreign = client.get("/v1/staff?venue=nope") { header(HttpHeaders.Cookie, cookie()) }
        assertEquals(HttpStatusCode.NotFound, foreign.status)
    }

    @Test
    fun defaultScopePrefersMainSoASecondVenueCantHijackThePortal() = testApplication {
        // review F3: 'zzz' sorts AFTER 'main', but 'aaa' sorts BEFORE — the default
        // must stay 'main' regardless of what venue ids get added,
        // or provisioning a venue like 'copperlanternpub'/'aaa' would flip the whole portal.
        seedTenant("copperlantern", "aaa")
        application { module(TestSupport.config) }

        // no ?venue → 'main' (seeded staff live there; 'aaa' is empty)
        val res = client.get("/v1/staff") { header(HttpHeaders.Cookie, cookie()) }
        assertEquals(HttpStatusCode.OK, res.status)
        val ids = testJson.parseToJsonElement(res.bodyAsText()).jsonObject["staff"]!!
            .jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue("manager" in ids, "default scope must resolve to 'main', not the alphabetically-first venue")
    }

    @Test
    fun emptyVenueListIsRefusedSoAnIdentityCantBeOrphaned() = testApplication {
        // review F37: {"venues":[]} would unassign everywhere yet leave the identity
        // live and invisible — refuse it; DELETE is the way to remove a member.
        application { module(TestSupport.config) }
        val staffId = createStaff("Ghost")
        assignVenues(staffId, "patio" to "SERVER").let { assertEquals(HttpStatusCode.OK, it.status) }
        val res = client.put("/v1/staff/$staffId/venues") {
            header(HttpHeaders.Cookie, cookie())
            contentType(ContentType.Application.Json)
            setBody("""{"venues":[]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("no_venues",
            testJson.parseToJsonElement(res.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
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
        val del = client.delete("/v1/venues/main/devices/ghost") { header(HttpHeaders.Cookie, cookie()) }
        assertEquals(HttpStatusCode.OK, del.status)
        val list = client.get("/v1/venues/main/devices") { header(HttpHeaders.Cookie, cookie()) }
        assertEquals(0, testJson.parseToJsonElement(list.bodyAsText()).jsonObject["devices"]!!.jsonArray.size)
        // a second delete is a clean 404
        assertEquals(HttpStatusCode.NotFound,
            client.delete("/v1/venues/main/devices/ghost") { header(HttpHeaders.Cookie, cookie()) }.status)
    }

    @Test
    fun lastManagerGuardHoldsPerVenue() = testApplication {
        application { module(TestSupport.config) }

        // Bootstrap seeded manager only at 'main' — pulling them out of main must refuse
        val res = assignVenues("manager", "patio" to "MANAGER")
        assertEquals(HttpStatusCode.Conflict, res.status)
        val body = testJson.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals("last_manager", body["code"]!!.jsonPrimitive.content)
    }

    // --- pairing ---

    private suspend fun ApplicationTestBuilder.mintCode(venue: String = "main"): String {
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

        val code = mintCode("main")
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

        val code = mintCode("main")
        transaction {
            PairingCodes.update { it[expiresAt] = LocalDateTime.now().minusMinutes(1) }
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

        val list = client.get("/v1/venues/main/devices") { header(HttpHeaders.Cookie, cookie()) }
        assertEquals(HttpStatusCode.OK, list.status)
        val devices = testJson.parseToJsonElement(list.bodyAsText()).jsonObject["devices"]!!.jsonArray
        assertEquals(1, devices.size)
        assertEquals("Bar", devices[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertNull(devices[0].jsonObject["revokeRequestedAt"]!!.jsonPrimitive.contentOrNull())

        val revoke = client.post("/v1/venues/main/devices/dev-1/revoke") { header(HttpHeaders.Cookie, cookie()) }
        assertEquals(HttpStatusCode.OK, revoke.status)

        // the revocation is on MAIN's feed only
        val change = feed(keyA).lastOrNull { it["kind"]!!.jsonPrimitive.content == "device_revocation" }
        assertNotNull(change)
        assertEquals("dev-1", change["data"]!!.jsonObject["deviceId"]!!.jsonPrimitive.content)
        assertTrue(feed(keyB).none { it["kind"]!!.jsonPrimitive.content == "device_revocation" })

        // intent is visible until the store confirms; store truth stays revoked=false for now
        val after = client.get("/v1/venues/main/devices") { header(HttpHeaders.Cookie, cookie()) }
        val dev = testJson.parseToJsonElement(after.bodyAsText()).jsonObject["devices"]!!.jsonArray[0].jsonObject
        assertNotNull(dev["revokeRequestedAt"]!!.jsonPrimitive.contentOrNull())
        assertEquals(false, dev["revoked"]!!.jsonPrimitive.content.toBoolean())

        val missing = client.post("/v1/venues/main/devices/ghost/revoke") { header(HttpHeaders.Cookie, cookie()) }
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
        assertEquals(10000, rows["main"])
        assertEquals(25000, rows["patio"])
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content
