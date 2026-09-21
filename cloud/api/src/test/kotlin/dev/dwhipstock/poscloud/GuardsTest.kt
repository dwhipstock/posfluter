package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.auth.Totp
import dev.dwhipstock.poscloud.db.CatalogItems
import dev.dwhipstock.poscloud.db.Checks
import dev.dwhipstock.poscloud.db.Venues
import dev.dwhipstock.poscloud.store.IngestRequest
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Review-pass guards: install pinning, TOTP burn, photoVersion preservation, poison timestamps. */
class GuardsTest {

    private val key = "test-store-key-guards"

    @Before
    fun setUp() {
        TestSupport.reset()
        seedTenant("copperlantern")
        seedStoreKey("copperlantern", "main", key)
    }

    private suspend fun ApplicationTestBuilder.ingestWith(installId: String?, vararg events: dev.dwhipstock.poscloud.store.IngestEvent): HttpResponse =
        client.post("/v1/ingest") {
            header(HttpHeaders.Authorization, "Bearer $key")
            contentType(ContentType.Application.Json)
            setBody(testJson.encodeToString(IngestRequest.serializer(), IngestRequest(events.toList(), installId)))
        }

    @Test
    fun bootstrapRestartPreservesStoreRoutingIdentity() {
        transaction {
            Venues.update({ (Venues.tenantId eq "copperlantern") and (Venues.id eq "main") }) {
                it[subdomain] = "copperlantern"
                it[storeInstallId] = "install-A"
            }
        }

        Bootstrap.run(TestSupport.config)

        transaction {
            val venue = Venues.selectAll().where {
                (Venues.tenantId eq "copperlantern") and (Venues.id eq "main")
            }.first()
            assertEquals("copperlantern", venue[Venues.subdomain])
            assertEquals("install-A", venue[Venues.storeInstallId])
        }
    }

    @Test
    fun firstPushPinsInstallIdAndMismatchIs409() = testApplication {
        application { module(TestSupport.config) }
        val e1 = event("check.closed", checkClosedPayload(1, 10000, storeTax(10000)), seq = 1)
        assertEquals(HttpStatusCode.OK, ingestWith("install-A", e1).status)
        transaction {
            assertEquals("install-A", Venues.selectAll().where { Venues.id eq "main" }
                .first()[Venues.storeInstallId])
        }
        // same install keeps flowing; a DIFFERENT store database is refused
        val e2 = event("check.closed", checkClosedPayload(2, 20000, storeTax(20000)), seq = 2)
        assertEquals(HttpStatusCode.OK, ingestWith("install-A", e2).status)
        val refused = ingestWith("install-B",
            event("check.closed", checkClosedPayload(1, 99999, storeTax(99999)), seq = 1))
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertTrue("install_mismatch" in refused.bodyAsText())
        transaction {
            // nothing from install-B landed: check 1 still carries install-A's total
            val row = Checks.selectAll().where { (Checks.tenantId eq "copperlantern") and (Checks.checkId eq 1) }.first()
            assertEquals(10000L, row[Checks.grandTotalCents])
        }
        // legacy pusher without an installId is tolerated
        val e3 = event("check.closed", checkClosedPayload(3, 30000, storeTax(30000)), seq = 3)
        assertEquals(HttpStatusCode.OK, ingestWith(null, e3).status)
    }

    @Test
    fun pendingTokenBurnsAfterFiveWrongTotpCodes() = testApplication {
        application { module(TestSupport.config) }
        val secret = Totp.newSecret()
        seedUser("copperlantern", "burn@example.com", "pw-123456", totpSecret = secret)
        val login = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"burn@example.com","password":"pw-123456"}""")
        }
        val pending = testJson.parseToJsonElement(login.bodyAsText())
            .let { it as kotlinx.serialization.json.JsonObject }["pendingToken"]!!.jsonPrimitive.content

        repeat(6) {
            val res = client.post("/v1/auth/totp") {
                contentType(ContentType.Application.Json)
                setBody("""{"pendingToken":"$pending","code":"000000"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, res.status)
        }
        // budget spent: even the CORRECT code is refused — token is gone
        val afterBurn = client.post("/v1/auth/totp") {
            contentType(ContentType.Application.Json)
            setBody("""{"pendingToken":"$pending","code":"${Totp.code(secret)}"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, afterBurn.status)
        assertTrue("bad_pending_token" in afterBurn.bodyAsText())
    }

    @Test
    fun posSnapshotWithoutPhotoVersionPreservesStoredOne() = testApplication {
        application { module(TestSupport.config) }
        val snapshotWithPhoto = buildJsonObject {
            put("item", buildJsonObject {
                put("id", "lantern-lager")
                put("nameFr", "éléphant"); put("nameEn", "Lantern House Lager")
                put("categoryId", "beer"); put("abbrev", "CH")
                put("isAlcohol", true); put("active", true); put("deleted", false)
                put("photoVersion", 12345L)
                put("variants", buildJsonArray {})
            })
        }
        ingest(key, event("item.updated", snapshotWithPhoto, seq = 1, aggregateId = "lantern-lager"))
        // a later POS edit omits the optional photoVersion hint — must not wipe it
        val snapshotWithout = buildJsonObject {
            put("item", buildJsonObject {
                put("id", "lantern-lager")
                put("nameFr", "nouvel éléphant"); put("nameEn", "Lantern House Lager New")
                put("categoryId", "beer"); put("abbrev", "CH")
                put("isAlcohol", true); put("active", true); put("deleted", false)
                put("variants", buildJsonArray {})
            })
        }
        ingest(key, event("item.updated", snapshotWithout, seq = 2, aggregateId = "lantern-lager"))
        transaction {
            val row = CatalogItems.selectAll().where { CatalogItems.id eq "lantern-lager" }.first()
            assertEquals("Lantern House Lager New", row[CatalogItems.nameEn])
            assertEquals(12345L, row[CatalogItems.photoVersion])
        }
    }

    @Test
    fun malformedCreatedAtStillIngests() = testApplication {
        application { module(TestSupport.config) }
        val res = ingest(key, event("check.closed",
            checkClosedPayload(9, 5000, storeTax(5000)), seq = 9, createdAt = "not-a-timestamp"))
        assertEquals(1, res["accepted"]!!.jsonPrimitive.content.toInt())
        transaction {
            assertEquals(1, Checks.selectAll().where { Checks.checkId eq 9 }.count().toInt())
        }
    }
}
