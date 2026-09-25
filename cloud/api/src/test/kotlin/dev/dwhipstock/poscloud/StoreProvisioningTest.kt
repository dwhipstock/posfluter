package dev.dwhipstock.poscloud

import dev.dwhipstock.poscloud.db.Checks
import dev.dwhipstock.poscloud.db.Migrations
import dev.dwhipstock.poscloud.db.StoreApiKeys
import dev.dwhipstock.poscloud.db.Venues
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two-store demo tenant: migration 014 renames the original 'main' store to
 * 'vieux-port' without orphaning anything, and the boot seed provisions every
 * configured store with its own key.
 */
class StoreProvisioningTest {

    private val migration014 = File(TestSupport.config.migrationsDir, "014_venue_vieux_port.sql").readText()

    private fun applyMigration014() = transaction {
        Migrations.statements(migration014).forEach { exec(it) }
    }

    @Before
    fun setUp() = TestSupport.reset()

    @Test
    fun mainBecomesVieuxPortWithItsHistoryKeyAndIdentity() {
        // a pre-014 database: the store synced as 'main' (the old boot seed's id)
        seedTenant("copperlantern", "main", "The Copper Lantern Pub")
        seedStoreKey("copperlantern", "main", "key-main")
        seedTenant("othertenant", "main")
        seedStoreKey("othertenant", "main", "key-other")
        testApplication {
            application { module(TestSupport.config.copy(stores = listOf(StoreSeed("main", "The Copper Lantern Pub")))) }
            ingest("key-main", event("check.closed", checkClosedPayload(1, 10000, storeTax(10000)), seq = 1))
        }
        transaction {
            Venues.update({ (Venues.tenantId eq "copperlantern") and (Venues.id eq "main") }) {
                it[storeInstallId] = "install-A"
            }
        }

        // migrations run before the boot seed
        applyMigration014()
        afterMigration()
    }

    private fun afterMigration() = testApplication {
        application { module(TestSupport.config) }
        startApplication()

        transaction {
            val ids = Venues.selectAll().where { Venues.tenantId eq "copperlantern" }.map { it[Venues.id] }
            assertTrue("main" !in ids, "main renamed: $ids")
            val vp = Venues.selectAll().where { (Venues.tenantId eq "copperlantern") and (Venues.id eq "vieux-port") }.single()
            assertEquals("install-A", vp[Venues.storeInstallId])
            assertEquals("vieux-port", Checks.selectAll().where { Checks.tenantId eq "copperlantern" }.single()[Checks.venueId])
            assertEquals("vieux-port", StoreApiKeys.selectAll().where { StoreApiKeys.tenantId eq "copperlantern" }.single()[StoreApiKeys.venueId])
            // another tenant's 'main' is not ours to rename
            assertEquals(listOf("main"), Venues.selectAll().where { Venues.tenantId eq "othertenant" }.map { it[Venues.id] })
        }
        // the same key keeps pushing, now into vieux-port, with the pinned install
        val res = client.post("/v1/ingest") {
            header(HttpHeaders.Authorization, "Bearer key-main")
            contentType(ContentType.Application.Json)
            setBody("""{"installId":"install-A","events":[]}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)

        // re-running is a no-op
        applyMigration014()
        transaction { assertEquals(1, Checks.selectAll().count()) }
    }

    @Test
    fun migrationIsANoOpWhenVieuxPortAlreadyExists() {
        seedTenant("copperlantern", "main")
        seedTenant("copperlantern", "vieux-port")
        applyMigration014()
        transaction {
            assertEquals(setOf("main", "vieux-port"),
                Venues.selectAll().where { Venues.tenantId eq "copperlantern" }.map { it[Venues.id] }.toSet())
        }
    }

    @Test
    fun bootSeedProvisionsEveryStoreWithItsOwnKey() = testApplication {
        val config = TestSupport.config.copy(
            storeApiKey = "key-vp",
            storeApiKeys = mapOf("plateau" to "key-pl", "nowhere" to "key-x"),
            stores = listOf(
                StoreSeed("vieux-port", "Copper Lantern — Vieux-Port"),
                StoreSeed("plateau", "Copper Lantern — Plateau"),
            ),
        )
        application { module(config) }
        startApplication()
        Bootstrap.run(config) // idempotent on a second boot
        transaction {
            val venues = Venues.selectAll().where { Venues.tenantId eq Bootstrap.TENANT }
                .associate { it[Venues.id] to it[Venues.name] }
            assertEquals(mapOf("plateau" to "Copper Lantern — Plateau", "vieux-port" to "Copper Lantern — Vieux-Port"), venues)
            val keys = StoreApiKeys.selectAll().associate { it[StoreApiKeys.keySha256] to it[StoreApiKeys.venueId] }
            assertEquals(2, keys.size)
            assertEquals("vieux-port", keys[dev.dwhipstock.poscloud.auth.sha256Hex("key-vp")])
            assertEquals("plateau", keys[dev.dwhipstock.poscloud.auth.sha256Hex("key-pl")])
            assertNull(keys[dev.dwhipstock.poscloud.auth.sha256Hex("key-x")])
        }
        // each key lands in its own store
        ingest("key-pl", event("check.closed", checkClosedPayload(1, 5000, storeTax(5000)), seq = 1))
        transaction { assertEquals("plateau", Checks.selectAll().single()[Checks.venueId]) }
    }

    @Test
    fun bootSetsAVenueTimezoneOnInsertOnlyAndNeverOverwritesIt() {
        TestSupport.reset()
        val first = TestSupport.config.copy(venueTz = "America/Toronto")
        Bootstrap.run(first)
        fun zone() = transaction {
            Venues.selectAll().where { (Venues.tenantId eq Bootstrap.TENANT) and (Venues.id eq PRIMARY_VENUE) }
                .single()[Venues.timezone]
        }
        assertEquals("America/Toronto", zone())
        // a later boot with another (or a defaulted) VENUE_TZ keeps the stored zone,
        // while the name still follows env
        Bootstrap.run(first.copy(venueTz = "America/Vancouver", stores = listOf(StoreSeed(PRIMARY_VENUE, "Renamed"))))
        assertEquals("America/Toronto", zone())
        transaction {
            assertEquals("Renamed", Venues.selectAll().where { Venues.id eq PRIMARY_VENUE }.single()[Venues.name])
        }
    }

    @Test
    fun storeListParsesInOrder() {
        assertEquals(linkedMapOf("vieux-port" to "Copper Lantern — Vieux-Port", "plateau" to "Copper Lantern — Plateau"),
            parsePairs(" vieux-port=Copper Lantern — Vieux-Port , plateau=Copper Lantern — Plateau,,junk"))
        assertEquals(listOf(StoreSeed(PRIMARY_VENUE, "Test Pub")), TestSupport.config.stores)
    }
}
