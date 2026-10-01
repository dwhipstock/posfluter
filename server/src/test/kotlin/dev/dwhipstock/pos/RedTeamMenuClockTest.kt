package dev.dwhipstock.pos

import dev.dwhipstock.pos.db.SyncState
import dev.dwhipstock.pos.db.initDatabase
import dev.dwhipstock.pos.sync.MenuClock
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * RED-TEAM repros (branch redteam/sync): the store's hybrid logical clock must
 * never issue a stamp that is not greater than the one before it — every
 * last-write-wins decision on both sides relies on it. These FAIL on main
 * (97e0d94); see scripts/e2e/redteam_sync.py (clock_back_offline,
 * counter_saturation) for the end-to-end consequences.
 *
 * The wall clock is moved through the menu clock offset (physicalNow =
 * currentTimeMillis + offset), which is exactly what a tablet clock change
 * looks like until the next successful menu pull re-measures the offset.
 */
class RedTeamMenuClockTest {

    private fun freshDb() {
        initDatabase(Files.createTempDirectory("pos-rt-clock").resolve("pos.db").toString())
    }

    /** MenuClock.now(): a remembered stamp > 10 min ahead of now restarts the clock from now, i.e. BACKWARDS. */
    @Test
    fun aClockStepBackOfMoreThanTenMinutesNeverIssuesAnOlderStamp() {
        freshDb()
        val (before, after) = transaction {
            val a = MenuClock.now()
            SyncState.set(MenuClock.OFFSET_KEY, (-60 * 60_000L).toString()) // the tablet clock goes back 1 hour
            a to MenuClock.now()
        }
        assertTrue(after > before, "stamp went backwards: $before then $after")
    }

    /** Hlc.of clamps the counter at 9999: after 10,000 stamps inside one lag window they all tie. */
    @Test
    fun tenThousandStampsWhileTheClockLagsStayStrictlyIncreasing() {
        freshDb()
        val ties = transaction {
            MenuClock.now()
            SyncState.set(MenuClock.OFFSET_KEY, (-5 * 60_000L).toString()) // NTP steps the clock back 5 minutes
            var prev = MenuClock.now()
            var ties = 0
            repeat(10_050) {
                val s = MenuClock.now()
                if (s <= prev) ties++
                prev = s
            }
            ties
        }
        assertTrue(ties == 0, "$ties stamps were not greater than the previous one (counter saturated at 9999)")
    }
}
