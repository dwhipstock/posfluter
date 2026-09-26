package dev.dwhipstock.pos.payments

import dev.dwhipstock.pos.payments.jpm.JpmConnector
import dev.dwhipstock.pos.payments.jpm.JpmInStoreAdapter
import dev.dwhipstock.pos.payments.jpm.JpmOnlineAdapter
import dev.dwhipstock.pos.payments.jpm.JpmOnlineApi
import dev.dwhipstock.pos.payments.jpm.JpmOnlineHttp
import dev.dwhipstock.pos.payments.simulator.SimulatedTerminalDevice
import dev.dwhipstock.pos.payments.simulator.SimulatorLink
import dev.dwhipstock.pos.payments.simulator.SimulatorTerminal
import dev.dwhipstock.pos.payments.terminal.TerminalKind
import dev.dwhipstock.pos.restaurant.CheckService
import dev.dwhipstock.pos.sdk.CustomerConfig
import dev.dwhipstock.pos.sdk.PaymentTerminalConfig
import org.slf4j.LoggerFactory

/** Builds the store's card terminal from `payment.terminal` (see [PaymentTerminalConfig]). */
object PaymentTerminals {
    private val log = LoggerFactory.getLogger(PaymentTerminals::class.java)

    fun build(
        kind: TerminalKind,
        config: PaymentTerminalConfig.Resolved,
        checks: CheckService,
        customer: CustomerConfig,
        stripe: StripeService,
        device: SimulatedTerminalDevice? = null,
        simulatorLinkFactory: ((String, Int, () -> String?) -> SimulatorLink)? = null,
        jpmConnector: JpmConnector? = null,
        jpmOnline: JpmOnlineApi? = null,
    ): TerminalPaymentService {
        val currency = customer.profile.currency
        fun hub() = SimulatorHub(
            embedded = device ?: SimulatedTerminalDevice(name = "${customer.displayName} — card terminal"),
            config = config,
            linkFactory = simulatorLinkFactory ?: { h, p, tok -> dev.dwhipstock.pos.payments.simulator.HttpSimulatorLink(h, p, tok) },
        )
        fun service(terminal: dev.dwhipstock.pos.payments.terminal.PaymentTerminal?, hub: SimulatorHub? = null) =
            TerminalPaymentService(kind, terminal, checks, currency, customer.displayName, config, stripe, hub)
        return when (kind) {
            TerminalKind.SIMULATOR -> {
                val hub = hub()
                log.info("Card terminal: simulator — " + (hub.remoteAddress()?.let { "the LAN terminal at $it" }
                    ?: "built in (reader page at /terminal, or the reader sheet on the tablet)"))
                service(SimulatorTerminal(hub::link, config.timeoutSeconds), hub)
            }
            TerminalKind.JPMORGAN -> when (config.jpmMode) {
                PaymentTerminalConfig.JpmMode.INSTORE -> {
                    log.warn("Card terminal: J.P. Morgan in-store (Payment Terminal Application) at ${config.address() ?: "no host set"} — " +
                        "written from the public docs, not yet run against a real terminal")
                    val connector = jpmConnector ?: JpmInStoreAdapter.wssConnector {
                        JpmInStoreAdapter.tlsFactory(config.jpmTruststore, config.jpmTruststorePassword(),
                            config.jpmKeystore, config.jpmKeystorePassword())
                    }
                    service(JpmInStoreAdapter(config.host, config.port ?: JpmInStoreAdapter.DEFAULT_PORT, connector, config.timeoutSeconds))
                }
                PaymentTerminalConfig.JpmMode.ONLINE -> {
                    val hub = hub()
                    val api = jpmOnline ?: JpmOnlineHttp.from(config.jpm)
                    log.info("Card terminal: J.P. Morgan Online Payments SANDBOX behind the simulated reader" +
                        (if (api == null) " — NOT CONFIGURED (JPM_CLIENT_ID / JPM_CLIENT_SECRET / JPM_TOKEN_URL / JPM_MERCHANT_ID)" else ""))
                    service(JpmOnlineAdapter(hub::link, api, config.timeoutSeconds, currency), hub)
                }
            }
            TerminalKind.STRIPE -> service(stripe.adapter)
            TerminalKind.EXTERNAL, TerminalKind.OFF -> service(null)
        }
    }
}
