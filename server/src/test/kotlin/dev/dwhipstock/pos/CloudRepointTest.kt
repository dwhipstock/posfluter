package dev.dwhipstock.pos

import dev.dwhipstock.pos.sync.CloudRepoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CloudRepointTest {
    @Test
    fun aSyncedStoreKeepsItsOwnIdentity() {
        assertEquals("id-1", CloudRepoint.resolveInstallId("id-1", null))
        assertEquals("id-1", CloudRepoint.resolveInstallId("id-1", " id-1 "))
    }

    @Test
    fun aMismatchedStagedIdentityIsRefused() {
        assertFailsWith<CloudRepoint.Refused> { CloudRepoint.resolveInstallId("id-1", "id-2") }
    }

    @Test
    fun aNeverSyncedStoreIsRefusedUnlessTheIdIsGivenExplicitly() {
        val refused = assertFailsWith<CloudRepoint.Refused> { CloudRepoint.resolveInstallId(null, null) }
        assertTrue("never synced" in refused.message!!)
        assertFailsWith<CloudRepoint.Refused> { CloudRepoint.resolveInstallId("", "  ") }
        assertEquals("id-9", CloudRepoint.resolveInstallId(null, "id-9"))
    }
}
