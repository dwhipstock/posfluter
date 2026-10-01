package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.auth.LoginRateLimiter
import dev.dwhipstock.poscloud.auth.Totp
import dev.dwhipstock.poscloud.db.CatalogItems
import dev.dwhipstock.poscloud.db.PortalUsers
import dev.dwhipstock.poscloud.db.Venues
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Red-team regression tests. Each asserts the SECURE behaviour, so each fails
 * on main @ 97e0d94 until the matching fix lands.
 */
class RedteamCloudTest {

    @Before
    fun setUp() {
        TestSupport.reset()
        LoginRateLimiter.reset()
    }

    private suspend fun ApplicationTestBuilder.login(email: String, pw: String): HttpResponse =
        client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(testJson.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                kotlinx.serialization.json.buildJsonObject {
                    put("email", kotlinx.serialization.json.JsonPrimitive(email))
                    put("password", kotlinx.serialization.json.JsonPrimitive(pw))
                }))
        }

    /** Login limiter key is the raw (untrimmed) email, but lookup trims it: padding gives a fresh bucket each time. */
    @Test
    fun loginRateLimitCannotBeBypassedWithWhitespacePaddedEmail() = testApplication {
        application { module(TestSupport.config) }
        seedTenant("copperlantern")
        seedUser("copperlantern", "rl-owner@test.dev", "the-real-password")
        val statuses = (0 until 15).map { i -> login(" ".repeat(i) + "rl-owner@test.dev", "guess-$i").status }
        assertTrue(HttpStatusCode.TooManyRequests in statuses,
            "15 wrong passwords for one account from one IP must hit 429; got $statuses")
    }

    /** Flooding >10,000 junk keys clears the whole limiter map, resetting the victim's counter. */
    @Test
    fun loginRateLimitSurvivesKeyFlood() = testApplication {
        application { module(TestSupport.config) }
        seedTenant("copperlantern")
        seedUser("copperlantern", "flood-owner@test.dev", "the-real-password")
        repeat(10) { login("flood-owner@test.dev", "guess-$it") }
        assertEquals(HttpStatusCode.TooManyRequests, login("flood-owner@test.dev", "guess-x").status)
        repeat(10_001) { LoginRateLimiter.record("junk-$it|127.0.0.1") } // what 10,001 cheap junk logins do
        assertEquals(HttpStatusCode.TooManyRequests, login("flood-owner@test.dev", "guess-y").status,
            "the victim's lockout must not be wiped by unrelated junk keys")
    }

    /** A TOTP code that already signed someone in must not sign in a second time. */
    @Test
    fun totpCodeCannotBeReplayed() = testApplication {
        application { module(TestSupport.config) }
        seedTenant("copperlantern")
        val secret = Totp.newSecret()
        seedUser("copperlantern", "replay@test.dev", "pw-replay-123", totpSecret = secret)
        val code = Totp.code(secret)
        suspend fun signIn(): HttpStatusCode {
            val pending = testJson.parseToJsonElement(login("replay@test.dev", "pw-replay-123").bodyAsText())
                .jsonObject["pendingToken"]!!.jsonPrimitive.content
            return client.post("/v1/auth/totp") {
                contentType(ContentType.Application.Json)
                setBody("""{"pendingToken":"$pending","code":"$code"}""")
            }.status
        }
        assertEquals(HttpStatusCode.OK, signIn())
        assertNotEquals(HttpStatusCode.OK, signIn(), "the same 6-digit code must be single-use")
    }

    private fun seedRetailItem() = transaction {
        Venues.update({ (Venues.tenantId eq "copperlantern") and (Venues.id eq "shop") }) { it[kind] = "retail" }
        CatalogItems.insert {
            it[tenantId] = "copperlantern"; it[venueId] = "shop"; it[id] = "wine-1"
            it[nameFr] = "Vin"; it[nameEn] = "Wine"; it[descriptionFr] = ""; it[descriptionEn] = ""
            it[categoryId] = "c"; it[isAlcohol] = true; it[active] = true; it[deleted] = false
            it[clock] = "{}"
        }
    }

    private fun viewerSession(): String {
        val viewer = seedUser("copperlantern", "viewer@test.dev", "pw-viewer-123")
        transaction { PortalUsers.update({ PortalUsers.id eq viewer }) { it[role] = "viewer" } }
        return seedSession("copperlantern", viewer)
    }

    /** 027: "viewer only looks". Recording stock is a write. */
    @Test
    fun viewerCannotRecordStockMovement() = testApplication {
        application { module(TestSupport.config) }
        seedTenant("copperlantern", "shop", "Shop")
        seedRetailItem()
        val s = viewerSession()
        val res = client.post("/v1/stock/movements?venue=shop") {
            header(HttpHeaders.Cookie, "pos_portal_session=$s")
            contentType(ContentType.Application.Json)
            setBody("""{"itemId":"wine-1","kind":"ADJUSTMENT","qty":-500,"note":"viewer"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, res.status, res.bodyAsText())
    }

    @Test
    fun viewerCannotSetReorderLevel() = testApplication {
        application { module(TestSupport.config) }
        seedTenant("copperlantern", "shop", "Shop")
        seedRetailItem()
        val s = viewerSession()
        val res = client.put("/v1/stock/reorder?venue=shop") {
            header(HttpHeaders.Cookie, "pos_portal_session=$s")
            contentType(ContentType.Application.Json)
            setBody("""{"itemId":"wine-1","reorderLevel":99999}""")
        }
        assertEquals(HttpStatusCode.Forbidden, res.status, res.bodyAsText())
    }
}
