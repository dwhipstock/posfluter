package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.GrantsRepo
import dev.dwhipstock.pos.base.Permissions
import dev.dwhipstock.pos.base.Users
import dev.dwhipstock.pos.db.SyncOutbox
import dev.dwhipstock.pos.db.SyncState
import dev.dwhipstock.pos.db.initDatabase
import dev.dwhipstock.pos.sync.CatalogChange
import dev.dwhipstock.pos.sync.ChangesPage
import dev.dwhipstock.pos.sync.CloudSync
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CONTRACT §7 down-sync: the store converges its `users` table + grant tables to
 * cloud staff / role_grants snapshots, and enforces the effective grant offline.
 * Staff are cloud-authoritative and down-only, so no echo is written.
 */
class StaffGrantApplyTest {

    private fun freshDb() =
        initDatabase(Files.createTempDirectory("pos-staff-apply").resolve("pos.db").toString())

    private fun page(cursor: Long, vararg changes: CatalogChange) = ChangesPage(cursor, changes.toList())

    private fun staffSnapshot(
        id: String, name: String, role: String, pinHash: String,
        active: Boolean = true, deleted: Boolean = false, refundOverride: Boolean? = null,
    ) = buildJsonObject {
        put("id", id); put("name", name); put("role", role); put("pinHash", pinHash)
        put("active", active); put("languageCode", "fr"); put("calendar", "CE"); put("deleted", deleted)
        put("overrides", buildJsonObject { refundOverride?.let { put("refund", it) } })
    }

    @Test
    fun appliesStaffUpsertOverrideAndSoftDelete() {
        freshDb()
        val t = FakeTransport()
        val sync = CloudSync(t, InMemoryPhotoStore())

        // cloud adds a new server with a refund override
        t.pages[0L] = page(1,
            CatalogChange(1, "staff", "upsert",
                staffSnapshot("nok", "Nong Nok", "SERVER", "\$2a\$10\$fakehashfakehashfakehashfakehashfakehashfa", refundOverride = true)),
        )
        sync.pullOnce()

        transaction {
            val row = Users.selectAll().where { Users.id eq "nok" }.first()
            assertEquals("SERVER", row[Users.role])
            assertEquals(true, row[Users.active])
            assertNull(row[Users.deletedAt])
            // effective grant honours the override: refund on, void still off (server default)
            assertTrue(GrantsRepo.has("nok", Permissions.REFUND))
            assertFalse(GrantsRepo.has("nok", Permissions.VOID))
        }
        assertEquals("1", transaction { SyncState.get(CloudSync.CATALOG_CURSOR) })
        // down-only: no outbox echo for staff
        assertEquals(0L, transaction {
            SyncOutbox.selectAll().where { SyncOutbox.aggregateType eq "staff" }.count()
        })

        // cloud drops the override, then soft-deletes the staff
        t.pages[1L] = page(3,
            CatalogChange(2, "staff", "upsert",
                staffSnapshot("nok", "Nong Nok", "SERVER", "\$2a\$10\$fakehashfakehashfakehashfakehashfakehashfa")),
            CatalogChange(3, "staff", "delete",
                staffSnapshot("nok", "Nong Nok", "SERVER", "\$2a\$10\$fakehashfakehashfakehashfakehashfakehashfa", deleted = true)),
        )
        sync.pullOnce()

        transaction {
            val row = Users.selectAll().where { Users.id eq "nok" }.first()
            assertEquals(false, row[Users.active]) // soft-deleted, row kept for FKs
            assertNotNull(row[Users.deletedAt])
            assertFalse(GrantsRepo.has("nok", Permissions.REFUND)) // override cleared
        }
    }

    @Test
    fun staffUpsertPreservesStoreLocalDisplayPreferences() {
        freshDb()
        val t = FakeTransport()
        val sync = CloudSync(t, InMemoryPhotoStore())
        t.pages[0L] = page(1,
            CatalogChange(1, "staff", "upsert",
                staffSnapshot("nok", "Nong Nok", "SERVER", "\$2a\$10\$fakehashfakehashfakehashfakehashfakehashfa")),
        )
        sync.pullOnce()

        // staff picks English locally — the snapshot's languageCode is only a creation default
        transaction {
            Users.update({ Users.id eq "nok" }) { it[languageCode] = "en"; it[calendar] = "CE" }
        }

        // a portal rename re-sends the snapshot; display preferences must survive it
        t.pages[1L] = page(2,
            CatalogChange(2, "staff", "upsert",
                staffSnapshot("nok", "Nok", "SERVER", "\$2a\$10\$fakehashfakehashfakehashfakehashfakehashfa")),
        )
        sync.pullOnce()

        transaction {
            val row = Users.selectAll().where { Users.id eq "nok" }.first()
            assertEquals("Nok", row[Users.name])
            assertEquals("en", row[Users.languageCode])
            assertEquals("CE", row[Users.calendar])
        }
    }

    @Test
    fun appliesRoleGrantsMatrix() {
        freshDb()
        val t = FakeTransport()
        val sync = CloudSync(t, InMemoryPhotoStore())

        // the owner grants SERVER the void permission across the board
        val roles = buildJsonObject {
            put("roles", buildJsonObject {
                put("MANAGER", buildJsonObject { Permissions.ALL.forEach { put(it, true) } })
                put("SERVER", buildJsonObject {
                    Permissions.ALL.forEach { put(it, it == Permissions.VOID || it == Permissions.PRICE_OVERRIDE) }
                })
            })
        }
        t.pages[0L] = page(1, CatalogChange(1, "role_grants", "upsert", roles))
        sync.pullOnce()

        transaction {
            // seed a server row to resolve the role against
            Users.insert {
                it[id] = "s2"; it[name] = "S2"; it[role] = "SERVER"; it[pin] = "x"
            }
            assertTrue(GrantsRepo.has("s2", Permissions.VOID))
            assertFalse(GrantsRepo.has("s2", Permissions.REFUND))
        }
    }
}
