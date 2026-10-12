package dev.dwhipstock.pos.sdk

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptPayTest {

    private fun baht(n: Long) = Money(n * 100)

    @Test
    fun crc16CcittFalseMatchesKnownVector() {
        // standard CRC-16/CCITT-FALSE check value
        assertEquals("29B1", PromptPayMethod.crc16ccitt("123456789"))
    }

    @Test
    fun phoneProxyEncodesTo0066Format() {
        assertEquals("0066812345678", PromptPayProxy.Phone("081-234-5678").encoded())
    }

    @Test
    fun payloadIsStructurallyValidEmvWithSelfConsistentCrc() {
        val method = PromptPayMethod(PromptPayProxy.Phone("0812345678"), accountName = "TEST SHOP")
        val payload = method.buildPayload(baht(215))

        assertTrue(payload.startsWith("000201"))         // payload format indicator
        assertTrue("010212" in payload)                  // dynamic QR
        assertTrue("0016A000000677010111" in payload)    // PromptPay AID
        assertTrue("01130066812345678" in payload)       // phone proxy, 13 chars
        assertTrue("5303764" in payload)                 // THB
        assertTrue("5406215.00" in payload)              // amount
        assertTrue("5802TH" in payload)
        assertTrue("5909TEST SHOP" in payload)

        val body = payload.dropLast(4)
        val crc = payload.takeLast(4)
        assertTrue(body.endsWith("6304"))
        assertEquals(PromptPayMethod.crc16ccitt(body), crc)
    }

    @Test
    fun exactPayloadForAPhoneAndSatang() {
        val payload = PromptPayMethod(PromptPayProxy.Phone("0812345678"), accountName = "TEST SHOP")
            .buildPayload(Money(12_350))
        val body = "000201" + "010212" +
            "2937" + "0016A000000677010111" + "01130066812345678" +
            "5303764" + "5406123.50" + "5802TH" + "5909TEST SHOP" + "6007BANGKOK" + "6304"
        assertEquals(body + PromptPayMethod.crc16ccitt(body), payload)
    }

    @Test
    fun nationalIdProxyUsesSubTag02() {
        val payload = PromptPayMethod(PromptPayProxy.NationalId("1-2345-67890-12-3"), accountName = "TEST SHOP")
            .buildPayload(baht(1))
        assertTrue("2937" + "0016A000000677010111" + "02131234567890123" in payload, payload)
        assertTrue("54041.00" in payload)
    }

    @Test
    fun aThaiAccountNameIsShownButLeftOutOfTheQr() {
        val method = PromptPayMethod(PromptPayProxy.Phone("0812345678"), accountName = "ร้านทดสอบ")
        val payload = method.buildPayload(baht(100))
        assertFalse("59" + "%02d".format("ร้านทดสอบ".length) in payload.substringAfter("5802TH"))
        assertTrue(payload.substringAfter("5802TH").startsWith("6304"))
        assertEquals(PromptPayMethod.crc16ccitt(payload.dropLast(4)), payload.takeLast(4))
        val shown = method.instructions(baht(100))
        assertEquals("PROMPTPAY", shown.type)
        assertEquals(10_000L, shown.amountCents)
        assertEquals(payload, shown.qrPayload)
        assertEquals(listOf("ร้านทดสอบ"), shown.displayFields.map { it.value })
    }

    @Test
    fun idsParseAsPhoneOrNationalId() {
        assertIs<PromptPayProxy.Phone>(PromptPayProxy.parse("0812345678"))
        assertIs<PromptPayProxy.Phone>(PromptPayProxy.parse(" 081-234-5678 "))
        assertEquals("0066812345678", PromptPayProxy.parse("+66 81 234 5678")!!.encoded())
        assertIs<PromptPayProxy.NationalId>(PromptPayProxy.parse("1234567890123"))
        assertNull(PromptPayProxy.parse("12345"))
        assertNull(PromptPayProxy.parse("1812345678"))
        assertNull(PromptPayProxy.parse("abc0812345678"))
        assertNull(PromptPayProxy.parse(""))
        assertNull(PromptPayProxy.parse(null))
    }

    @Test
    fun configResolvesFromPropertiesAndWarnsOnABadId() {
        assertFalse(PromptPayConfig.fromProperties(null).configured)
        assertFalse(PromptPayConfig.resolve(null, "x", "test").configured)
        val ok = PromptPayConfig.resolve("0812345678", " TEST SHOP ", "test")
        assertTrue(ok.configured)
        assertEquals("TEST SHOP", ok.name)
        assertNull(ok.warning)
        // the log line never carries the whole id
        assertFalse("0812345678" in ok.describe() || "812345678" in ok.describe())
        val bad = PromptPayConfig.resolve("12345", "TEST SHOP", "test")
        assertFalse(bad.configured)
        assertNotNull(bad.warning)
    }

    @Test
    fun aThaiNameInAPlainUtf8PropertiesFileReadsBackThai() {
        val file = Files.createTempFile("store", ".properties").toFile()
        file.writeText("promptpay.id=0812345678\npromptpay.name=ร้านทดสอบ\n", Charsets.UTF_8)
        val env = { k: String -> if (k == ReceiptPrintMode.ENV_CONFIG_FILE) file.path else null }
        val resolved = PromptPayConfig.fromEnv(env)
        assertTrue(resolved.configured)
        assertEquals("ร้านทดสอบ", resolved.name)
        // env wins over the file
        val fromEnv = PromptPayConfig.fromEnv { k ->
            when (k) { PromptPayConfig.ENV_ID -> "1234567890123"; PromptPayConfig.ENV_NAME -> "ENV SHOP"; else -> null }
        }
        assertIs<PromptPayProxy.NationalId>(fromEnv.proxy)
        assertEquals("ENV SHOP", fromEnv.name)
        // plain ASCII and a non-UTF-8 Latin-1 name stay as they are
        assertEquals("Café", PromptPayConfig.utf8Recovered("Café"))
        assertEquals("TEST", PromptPayConfig.utf8Recovered("TEST"))
    }
}
