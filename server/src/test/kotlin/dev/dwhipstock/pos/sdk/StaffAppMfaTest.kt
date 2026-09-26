package dev.dwhipstock.pos.sdk

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StaffAppMfaTest {

    private fun propsFile(body: String): File =
        Files.createTempDirectory("pos-mfa").resolve("store.properties").toFile().apply { writeText(body) }

    @Test
    fun defaultIsOn() {
        val unset = StaffAppMfa.fromEnv { null }
        assertEquals(StaffAppMfa.ON, unset.mfa)
        assertTrue(unset.required)
        assertEquals("default", unset.source)
        assertNull(unset.warning)
        assertEquals(StaffAppMfa.ON, StaffAppMfa.fromFile(null).mfa)
        assertEquals(StaffAppMfa.ON, StaffAppMfa.fromFile(File("/nonexistent/store.properties")).mfa)
        assertEquals(StaffAppMfa.ON, StaffAppMfa.fromFile(propsFile("print.receipts=digital\n")).mfa)
        assertEquals(StaffAppMfa.ON, StaffAppMfa.resolve(null, "store.properties").mfa)
        assertEquals(StaffAppMfa.ON, StaffAppMfa.resolve("  ", "store.properties").mfa)
    }

    @Test
    fun parsesOnAndOff() {
        assertEquals(StaffAppMfa.OFF, StaffAppMfa.resolve(" OFF ", "store.properties").mfa)
        assertEquals(StaffAppMfa.ON, StaffAppMfa.resolve("on", "store.properties").mfa)
        val off = StaffAppMfa.fromFile(propsFile("print.receipts=paper\nstaff.app.mfa=off\n"))
        assertEquals(StaffAppMfa.OFF, off.mfa)
        assertEquals(false, off.required)
    }

    @Test
    fun invalidValueWarnsAndFallsBackToOn() {
        for (bad in listOf("false", "no", "maybe", "0")) {
            val r = StaffAppMfa.resolve(bad, "store.properties")
            assertEquals(StaffAppMfa.ON, r.mfa, bad)
            assertNotNull(r.warning, bad)
        }
        val env = StaffAppMfa.fromEnv { if (it == StaffAppMfa.ENV) "disabled" else null }
        assertEquals(StaffAppMfa.ON, env.mfa)
        assertTrue(env.warning!!.contains("POS_STAFF_APP_MFA"))
        assertEquals(StaffAppMfa.ON, StaffAppMfa.fromFile(propsFile("staff.app.mfa=nope\n")).mfa)
    }

    @Test
    fun envWinsOverTheConfigFile() {
        val offFile = propsFile("staff.app.mfa=off\n")
        val onFile = propsFile("staff.app.mfa=on\n")
        fun env(value: String?, file: File) = { k: String ->
            when (k) {
                StaffAppMfa.ENV -> value
                "POS_CONFIG_FILE" -> file.path
                else -> null
            }
        }
        // file only
        assertEquals(StaffAppMfa.OFF, StaffAppMfa.fromEnv(env(null, offFile)).mfa)
        assertEquals(offFile.path, StaffAppMfa.fromEnv(env(null, offFile)).source)
        // env beats file, both ways
        assertEquals(StaffAppMfa.ON, StaffAppMfa.fromEnv(env("on", offFile)).mfa)
        assertEquals(StaffAppMfa.OFF, StaffAppMfa.fromEnv(env("off", onFile)).mfa)
        assertEquals(StaffAppMfa.ENV, StaffAppMfa.fromEnv(env("off", onFile)).source)
        // blank env → the file decides
        assertEquals(StaffAppMfa.OFF, StaffAppMfa.fromEnv(env(" ", offFile)).mfa)
    }
}
