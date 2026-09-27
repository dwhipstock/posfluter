package dev.dwhipstock.pos.payments

import dev.dwhipstock.pos.sdk.PaymentTerminalConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StripeSimReaderTest {
    @Test
    fun `the simulator page's card and outcome pick Stripe's documented test card`() {
        assertEquals("card_present" to "4242424242424242", StripeSimReaderAdapter.testCard("Visa", "approve"))
        assertEquals("card_present" to "5555555555554444", StripeSimReaderAdapter.testCard("mastercard", "approve"))
        assertEquals("interac_present" to "4506445006931933", StripeSimReaderAdapter.testCard("interac", "approve"))
        assertEquals("card_present" to "4000000000009995", StripeSimReaderAdapter.testCard("amex", "insufficient_funds"))
        assertEquals("card_present" to "4000000000000002", StripeSimReaderAdapter.testCard("visa", "do_not_honour"))
    }

    @Test
    fun `payment simulator processor setting`() {
        fun of(v: String?) = PaymentTerminalConfig.resolve({ k -> if (k == PaymentTerminalConfig.KEY_SIM_PROCESSOR) v else null }, "test")
        assertEquals(PaymentTerminalConfig.SimProcessor.LOCAL, of(null).simProcessor)
        assertEquals(PaymentTerminalConfig.SimProcessor.STRIPE, of(" Stripe ").simProcessor)
        val bad = of("paypal")
        assertEquals(PaymentTerminalConfig.SimProcessor.LOCAL, bad.simProcessor)
        assertTrue(bad.warnings.any { "payment.simulator.processor" in it })
    }
}
