package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.AuthService
import dev.dwhipstock.pos.base.Permissions
import dev.dwhipstock.pos.base.Users
import dev.dwhipstock.pos.db.SyncState
import dev.dwhipstock.pos.db.initDatabase
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A PIN is checked with bcrypt against each staff member in turn: tens of
 * milliseconds each. The store runs one transaction at a time, so that check
 * must happen outside any transaction, or a sign-in on one device stops every
 * other device for the whole scan (load test, docs/load-test-report.md).
 */
class PinCheckOutsideTransactionTest {

    private val staff = 24

    /** A manager (1234) and [staff] servers with PINs 5001…; the last server's PIN is checked last. */
    private fun store(): AuthService {
        initDatabase(Files.createTempDirectory("pos-pin").resolve("pos.db").toString())
        transaction {
            Users.insert {
                it[id] = "manager"; it[name] = "Manager"; it[role] = "MANAGER"; it[pin] = AuthService.hashPin("1234")
            }
            repeat(staff) { i ->
                Users.insert {
                    it[id] = "s$i"; it[name] = "Server $i"; it[role] = "SERVER"; it[pin] = AuthService.hashPin("${5001 + i}")
                }
            }
        }
        return AuthService(staffAppMfaRequired = false)
    }

    @Test
    fun `other devices keep working while a PIN is being checked`() {
        val auth = store()
        val lastPin = "${5000 + staff}"
        val signedIn = AtomicReference<String?>()
        val t0 = System.nanoTime()
        val login = Thread { signedIn.set(auth.login(lastPin)?.userId) }.apply { start() }
        Thread.sleep(50) // the scan is under way
        // another device's work meanwhile: a write, as a sale would do
        val w0 = System.nanoTime()
        transaction { SyncState.set("sale", "1") }
        val writeMs = (System.nanoTime() - w0) / 1_000_000
        login.join()
        val loginMs = (System.nanoTime() - t0) / 1_000_000
        assertEquals("s${staff - 1}", signedIn.get())
        assertTrue(loginMs > 200, "the PIN scan should take a while with $staff staff (took $loginMs ms)")
        assertTrue(writeMs < loginMs / 2, "a sale waited $writeMs ms for a $loginMs ms sign-in")
    }

    @Test
    fun `a manager approval checks only the staff who hold the grant`() {
        val auth = store()
        val one = System.nanoTime().also { AuthService.verifyPin("0000", AuthService.hashPin("1234")) }
            .let { (System.nanoTime() - it) / 1_000_000.0 }
        val t0 = System.nanoTime()
        assertEquals("manager", auth.verifyApproverPin("1234", Permissions.VOID))
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        // every staff member would be ~${staff + 1} checks; the manager alone is one
        assertTrue(ms < one * 6, "approval took $ms ms, one PIN check $one ms")
        assertNull(auth.verifyApproverPin("5001", Permissions.VOID), "a server's PIN does not approve a void")
        assertNotNull(auth.verifyManagerPin("1234"))
        assertNull(auth.login("0000"))
    }
}
