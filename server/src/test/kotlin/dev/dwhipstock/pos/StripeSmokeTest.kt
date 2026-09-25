package dev.dwhipstock.pos

import dev.dwhipstock.pos.payments.StripeClient
import dev.dwhipstock.pos.payments.UrlStripeHttp
import dev.dwhipstock.pos.payments.str
import dev.dwhipstock.pos.sdk.StripeConfig
import org.junit.Assume.assumeTrue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * OPT-IN smoke test against the real Stripe TEST mode API. Runs only when
 * STRIPE_KEY holds an sk_test_ key at test time, skipped otherwise:
 *
 *   STRIPE_KEY=sk_test_... ./gradlew test --tests '*StripeSmokeTest*'
 *
 * Creates a Terminal connection token and a card_present PaymentIntent in the
 * account's own currency (works for any account country), then cancels it. No
 * money moves; nothing is left open. The key is never printed.
 */
class StripeSmokeTest {
    @Test
    fun `connection token and a PaymentIntent against Stripe test mode`() {
        val config = StripeConfig.of(System.getenv(StripeConfig.ENV_SECRET), null, StripeConfig.ENV_SECRET)
        assumeTrue("STRIPE_KEY (sk_test_) not set — real-API smoke test skipped", config.enabled)
        val stripe = StripeClient(UrlStripeHttp(config.secretKey!!))

        val account = stripe.account()
        val currency = assertNotNull(account.str("default_currency"), "account has a default currency")
        println("Stripe smoke: account country=${account.str("country")} currency=$currency")

        val token = stripe.connectionToken(null)
        assertTrue(token.str("secret").orEmpty().startsWith("pst_test_"), "test-mode connection token")

        val run = UUID.randomUUID().toString()
        val pi = stripe.createPaymentIntent(listOf(
            "amount" to "1000",
            "currency" to currency,
            "payment_method_types[]" to "card_present",
            "capture_method" to "manual",
            "description" to "POS smoke test (canceled immediately)",
            "metadata[pos_smoke]" to run,
        ), "pos-smoke-pi-$run")
        val id = assertNotNull(pi.str("id"))
        assertEquals("requires_payment_method", pi.str("status"))
        assertEquals(currency, pi.str("currency"))

        val canceled = stripe.cancelPaymentIntent(id, "pos-smoke-cancel-$run")
        assertEquals("canceled", canceled.str("status"))
        println("Stripe smoke: $id created and canceled")
    }
}
