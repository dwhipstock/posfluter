package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.Tenants
import dev.dwhipstock.poscloud.db.Venues
import dev.dwhipstock.poscloud.venues.storeLinkStatus
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Before
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The portal Devices page: every in-scope store's POS with its heartbeat
 * liveness, scoped exactly like the other portal pages; plus the group name
 * following VENUE_NAME at boot.
 */
class StoreDevicesTest {

    private val keyVp = "store-key-vieux-port"
    private val keyPl = "store-key-plateau"
    private lateinit var session: String
    private lateinit var otherSession: String

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("copperlantern", "vieux-port", "Copper Lantern — Vieux-Port")
        seedTenant("copperlantern", "plateau", "Copper Lantern — Plateau")
        seedStoreKey("copperlantern", "vieux-port", keyVp)
        seedStoreKey("copperlantern", "plateau", keyPl)
        seedTenant("othertenant", "elsewhere", "Other Group")
        seedStoreKey("othertenant", "elsewhere", "store-key-other")
        session = seedSession("copperlantern", seedUser("copperlantern", "owner@test.dev", "password-d"))
        otherSession = seedSession("othertenant", seedUser("othertenant", "other@test.dev", "password-o"))
    }

    private val config = TestSupport.config.copy(
        stores = listOf(StoreSeed("vieux-port", "Copper Lantern — Vieux-Port"), StoreSeed("plateau", "Copper Lantern — Plateau")),
    )

    // --- liveness rule ---

    @Test
    fun onlineStaleOfflineThresholds() {
        val now = OffsetDateTime.of(2026, 9, 25, 12, 0, 0, 0, ZoneOffset.UTC)
        assertEquals("offline", storeLinkStatus(null, now))
        assertEquals("online", storeLinkStatus(now, now))
        assertEquals("online", storeLinkStatus(now.minusSeconds(10), now))
        assertEquals("online", storeLinkStatus(now.minusSeconds(60), now))
        assertEquals("stale", storeLinkStatus(now.minusSeconds(61), now))
        assertEquals("stale", storeLinkStatus(now.minusMinutes(10), now))
        assertEquals("offline", storeLinkStatus(now.minusMinutes(10).minusSeconds(1), now))
        assertEquals("offline", storeLinkStatus(now.minusDays(3), now))
        // a beat stamped a hair in the future (clock step) still reads online
        assertEquals("online", storeLinkStatus(now.plusSeconds(2), now))
    }

    // --- endpoint ---

    private suspend fun ApplicationTestBuilder.heartbeat(key: String, body: String) {
        val res = client.post("/v1/store/heartbeat") {
            header(HttpHeaders.Authorization, "Bearer $key")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.stores(token: String, venue: String? = null): Pair<HttpStatusCode, List<JsonObject>> {
        val res = getWithCookie("/v1/devices" + (venue?.let { "?venue=$it" } ?: ""), token)
        if (res.status != HttpStatusCode.OK) return res.status to emptyList()
        return res.status to testJson.parseToJsonElement(res.bodyAsText()).jsonObject["stores"]!!
            .jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.takeIf { it.isString }?.content

    private fun setSeenAt(venueId: String, at: OffsetDateTime?) = transaction {
        Venues.update({ (Venues.tenantId eq "copperlantern") and (Venues.id eq venueId) }) { it[storeSeenAt] = at }
    }

    @Test
    fun everyStoreShowsItsPosInAllStoresAndSingleStoreScope() = testApplication {
        application { module(config) }

        heartbeat(keyVp, """{"installId":"3f0c9a1e-aaaa-bbbb-cccc-000000000001",
            "lanBaseUrl":"http://192.168.1.50:8080","appVersion":"1.4.0","contractVersion":2,
            "devices":[{"id":"dev-1","name":"Bar tablet","pairedAt":"2026-09-20T10:00:00-04:00","revoked":false}]}""")
        heartbeat(keyPl, """{"lanBaseUrl":"http://10.0.0.7:8080"}""")
        transaction {
            Venues.update({ (Venues.tenantId eq "copperlantern") and (Venues.id eq "vieux-port") }) {
                it[storeInstallId] = "3f0c9a1e-aaaa-bbbb-cccc-000000000001"
            }
        }
        // Plateau's last beat was 5 minutes ago → stale
        setSeenAt("plateau", CloudTime.now().minusMinutes(5))

        val (status, all) = stores(session)
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(listOf("plateau", "vieux-port"), all.map { it.str("venueId") })

        val vp = all.single { it.str("venueId") == "vieux-port" }
        assertEquals("Copper Lantern — Vieux-Port", vp.str("venueName"))
        assertEquals("online", vp.str("status"))
        assertEquals("http://192.168.1.50:8080", vp.str("lanUrl"))
        assertEquals("3f0c9a1e-aaaa-bbbb-cccc-000000000001", vp.str("installId"))
        assertEquals("1.4.0", vp.str("appVersion"))
        assertEquals(2, vp["contractVersion"]!!.jsonPrimitive.intOrNull)
        val devices = vp["devices"]!!.jsonArray
        assertEquals(listOf("Bar tablet"), devices.map { it.jsonObject.str("name") })

        val pl = all.single { it.str("venueId") == "plateau" }
        assertEquals("stale", pl.str("status"))
        assertEquals("http://10.0.0.7:8080", pl.str("lanUrl"))
        assertNull(pl.str("appVersion"))
        assertEquals(0, pl["devices"]!!.jsonArray.size)
        val age = pl["secondsSinceSeen"]!!.jsonPrimitive.longOrNull!!
        assertEquals(true, age in 290..330, "age $age")

        // single-store scope: exactly that store, nothing of the other
        val (_, onlyPl) = stores(session, "plateau")
        assertEquals(listOf("plateau"), onlyPl.map { it.str("venueId") })

        // offline: no beat in over 10 minutes, and never
        setSeenAt("plateau", CloudTime.now().minusMinutes(30))
        assertEquals("offline", stores(session, "plateau").second.single().str("status"))
        setSeenAt("plateau", null)
        val never = stores(session, "plateau").second.single()
        assertEquals("offline", never.str("status"))
        assertNull(never.str("lastSeenAt"))
    }

    @Test
    fun scopeNeverCrossesTenants() = testApplication {
        application { module(config) }
        heartbeat(keyVp, """{"lanBaseUrl":"http://192.168.1.50:8080","devices":[{"id":"dev-1","name":"Bar"}]}""")
        heartbeat("store-key-other", """{"lanBaseUrl":"http://192.168.9.9:8080","devices":[{"id":"x","name":"Theirs"}]}""")

        // another tenant's venue id is a 404, never a fall-through
        assertEquals(HttpStatusCode.NotFound, stores(session, "elsewhere").first)
        assertEquals(HttpStatusCode.NotFound, stores(otherSession, "vieux-port").first)

        // each tenant's "all stores" is only its own
        assertEquals(listOf("plateau", "vieux-port"), stores(session).second.map { it.str("venueId") })
        val theirs = stores(otherSession).second
        assertEquals(listOf("elsewhere"), theirs.map { it.str("venueId") })
        assertEquals(listOf("Theirs"), theirs.single()["devices"]!!.jsonArray.map { it.jsonObject.str("name") })

        // no session → 401
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/devices").status)
    }

    @Test
    fun revokeStillWorksForAHeartbeatDevice() = testApplication {
        application { module(config) }
        heartbeat(keyVp, """{"lanBaseUrl":"http://192.168.1.50:8080","devices":[{"id":"dev-1","name":"Bar"}]}""")
        val res = client.post("/v1/venues/vieux-port/devices/dev-1/revoke") {
            header(HttpHeaders.Cookie, "pos_portal_session=$session")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val dev = stores(session, "vieux-port").second.single()["devices"]!!.jsonArray.single().jsonObject
        assertEquals(true, dev.str("revokeRequestedAt") != null)
    }

    // --- group name ---

    @Test
    fun groupNameFollowsConfigOnBootButTimezoneDoesNot() {
        // a tenant row seeded long ago under the old name, venue in another zone
        transaction {
            Tenants.update({ Tenants.id eq "copperlantern" }) { it[name] = "The Old Group Name" }
            Venues.update({ (Venues.tenantId eq "copperlantern") and (Venues.id eq "vieux-port") }) {
                it[timezone] = "America/New_York"
            }
        }
        testApplication {
            application { module(config.copy(venueName = "Copper Lantern", venueTz = "America/Chicago")) }
            startApplication()
            val me = getWithCookie("/v1/auth/me", session)
            assertEquals(HttpStatusCode.OK, me.status)
            assertEquals("Copper Lantern",
                testJson.parseToJsonElement(me.bodyAsText()).jsonObject["tenantName"]!!.jsonPrimitive.content)
        }
        transaction {
            assertEquals("Copper Lantern",
                Tenants.selectAll().where { Tenants.id eq "copperlantern" }.single()[Tenants.name])
            assertEquals("America/New_York",
                Venues.selectAll().where { (Venues.tenantId eq "copperlantern") and (Venues.id eq "vieux-port") }
                    .single()[Venues.timezone])
            // another tenant is never renamed
            assertEquals("Other Group", Tenants.selectAll().where { Tenants.id eq "othertenant" }.single()[Tenants.name])
        }
    }

    @Test
    fun defaultGroupNameIsCopperLantern() {
        // only the code default is under test; a dev box may export VENUE_NAME
        org.junit.Assume.assumeTrue(System.getenv("VENUE_NAME").isNullOrBlank())
        assertEquals("Copper Lantern", CloudConfig().venueName)
    }
}
