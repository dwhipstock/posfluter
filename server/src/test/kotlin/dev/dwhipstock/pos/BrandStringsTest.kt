package dev.dwhipstock.pos

import dev.dwhipstock.pos.base.SettingsRepository
import dev.dwhipstock.pos.customers.copperlantern.CopperLanternConfig
import dev.dwhipstock.pos.customers.sagepoppy.SagePoppyConfig
import dev.dwhipstock.pos.payments.jpm.JpmHttp
import dev.dwhipstock.pos.payments.jpm.JpmOnlineHttp
import dev.dwhipstock.pos.sdk.CustomerConfig
import dev.dwhipstock.pos.sdk.EscPosTransport
import dev.dwhipstock.pos.sdk.NetworkThermalPrinter
import dev.dwhipstock.pos.sdk.PaymentTerminalConfig
import dev.dwhipstock.pos.sdk.PrintLine
import dev.dwhipstock.pos.sdk.PrinterAdapter
import dev.dwhipstock.pos.sdk.PrinterTarget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The printer test page and the J.P. Morgan merchant-software name come from
 * the store's config: Copper Lantern reads exactly as it always did, and
 * another brand gets its own name.
 */
class BrandStringsTest {
    private fun dir() = Files.createTempDirectory("brand").toFile()
    private fun virtual() = dir().let { PrinterAdapter.VirtualPrinter(java.io.File(it, "r").path, java.io.File(it, "b").path) }

    private fun copperLantern() = CopperLanternConfig(settings = SettingsRepository(), printer = virtual(), publicBaseUrl = "http://x")
    private fun sagePoppy() = SagePoppyConfig(settings = SettingsRepository(), printer = virtual(), publicBaseUrl = "http://x")

    /** The test page's first line, as the printer is wired in Application (customer id read at print time). */
    private fun testPageHeader(config: CustomerConfig): PrintLine {
        var sent = 0
        val printer = NetworkThermalPrinter(
            audit = virtual(),
            target = { PrinterTarget("10.0.0.9", 9100) },
            transport = object : EscPosTransport {
                override fun send(target: PrinterTarget, bytes: ByteArray) { sent++ }
            },
            customerId = { config.customerId },
        )
        assertTrue(printer.testPrint().online)
        assertEquals(1, sent)
        return printer.testLines().first()
    }

    @Test
    fun `the printer test page header names the brand, unchanged for Copper Lantern`() {
        assertEquals(PrintLine.Header("COPPERLANTERN POS"), testPageHeader(copperLantern()))
        assertEquals(PrintLine.Header("SAGEPOPPY POS"), testPageHeader(sagePoppy()))
    }

    private fun companyNameSent(brandName: String): String {
        val bodies = mutableListOf<String>()
        val http = object : JpmHttp {
            override fun send(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
                if (url.endsWith("/payments")) bodies += body!!
                return if ("access_token" in url) 200 to """{"access_token":"tok","token_type":"Bearer","expires_in":3599}"""
                else 200 to """{"transactionId":"tx","responseStatus":"SUCCESS","transactionState":"AUTHORIZED"}"""
            }
        }
        val creds = PaymentTerminalConfig.JpmCredentials("id", "unit-" + "secret",
            "https://id.example.test/oauth2/access_token", "scope", null, null)
        JpmOnlineHttp(creds, JpmOnlineHttp.MOCK_BASE, http, companyName = JpmOnlineHttp.softwareCompany(brandName))
            .authorize("k", 100, "USD", JpmOnlineHttp.sandboxCard("visa", "approve"), "r")
        val o = Json.parseToJsonElement(bodies.single()).jsonObject
        return o["merchant"]!!.jsonObject["merchantSoftware"]!!.jsonObject["companyName"]!!.jsonPrimitive.content
    }

    @Test
    fun `the J P Morgan merchant software is the brand's, unchanged for Copper Lantern`() {
        assertEquals("Copper Lantern", copperLantern().brandName)
        assertEquals("Copper Lantern POS demo", companyNameSent(copperLantern().brandName))
        assertEquals("Sage & Poppy Bottle Shop POS demo", companyNameSent(sagePoppy().brandName))
    }
}
