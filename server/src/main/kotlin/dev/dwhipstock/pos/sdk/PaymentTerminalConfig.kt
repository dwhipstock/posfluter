package dev.dwhipstock.pos.sdk

import dev.dwhipstock.pos.payments.terminal.TerminalKind
import java.io.File
import java.util.Properties

/**
 * Which card terminal this store uses (store config, no UI):
 * `payment.terminal=stripe|simulator|jpmorgan|external|off`.
 *
 * - `stripe`: Stripe Terminal, test mode (also needs `stripe.secretKey`). Copper
 *   Lantern's default.
 * - `simulator`: the built-in card terminal simulator. Default for every other
 *   store. With no `payment.terminal.host` it runs inside the store (reader page
 *   at /terminal, or a sheet on the tablet); with a host it is the stand-alone
 *   terminal on another machine on the LAN (scripts/demo-terminal.sh).
 * - `jpmorgan`: J.P. Morgan Payment Terminal Application on the LAN. A sketch
 *   until the owner has credentials and a test terminal; never a default.
 * - `external`: a card on the counter's own terminal, keyed by hand (no integration).
 * - `off`: no card tender at all.
 *
 * Other keys (all optional):
 * - `payment.terminal.host` = `host[:port]` of a LAN terminal (simulator default
 *   port 8090, J.P. Morgan 8442). Can also be set by pairing from the POS.
 * - `payment.terminal.timeoutSeconds` = how long a payment waits for a card
 *   (default 90, 15..600).
 * - `payment.jpmorgan.truststore` / `.truststorePassword` / `.keystore` /
 *   `.keystorePassword`: PKCS#12 files for the terminal's TLS (see docs/payments-terminals.md).
 *
 * Env (desktop / docker) wins: POS_PAYMENT_TERMINAL, POS_PAYMENT_TERMINAL_HOST,
 * POS_PAYMENT_TERMINAL_TIMEOUT, POS_JPM_TRUSTSTORE, POS_JPM_TRUSTSTORE_PASSWORD,
 * POS_JPM_KEYSTORE, POS_JPM_KEYSTORE_PASSWORD; else the POS_CONFIG_FILE
 * properties file. The tablet passes its store.properties. Local config only; a
 * bad value falls back to the store's default with a warning and never fails startup.
 */
object PaymentTerminalConfig {
    const val KEY = "payment.terminal"
    const val KEY_HOST = "payment.terminal.host"
    const val KEY_TIMEOUT = "payment.terminal.timeoutSeconds"
    const val KEY_JPM_TRUSTSTORE = "payment.jpmorgan.truststore"
    const val KEY_JPM_TRUSTSTORE_PASSWORD = "payment.jpmorgan.truststorePassword"
    const val KEY_JPM_KEYSTORE = "payment.jpmorgan.keystore"
    const val KEY_JPM_KEYSTORE_PASSWORD = "payment.jpmorgan.keystorePassword"
    const val KEY_JPM_MODE = "payment.jpmorgan.mode"
    const val KEY_JPM_CLIENT_ID = "payment.jpmorgan.clientId"
    const val KEY_JPM_CLIENT_SECRET = "payment.jpmorgan.clientSecret"
    const val KEY_JPM_TOKEN_URL = "payment.jpmorgan.tokenUrl"
    const val KEY_JPM_SCOPE = "payment.jpmorgan.scope"
    const val KEY_JPM_MERCHANT_ID = "payment.jpmorgan.merchantId"
    const val KEY_JPM_BASE_URL = "payment.jpmorgan.baseUrl"
    const val ENV = "POS_PAYMENT_TERMINAL"
    const val ENV_HOST = "POS_PAYMENT_TERMINAL_HOST"
    const val ENV_TIMEOUT = "POS_PAYMENT_TERMINAL_TIMEOUT"
    const val DEFAULT_TIMEOUT_SECONDS = 90

    private val ENV_BY_KEY = mapOf(
        KEY to ENV, KEY_HOST to ENV_HOST, KEY_TIMEOUT to ENV_TIMEOUT,
        KEY_JPM_TRUSTSTORE to "POS_JPM_TRUSTSTORE", KEY_JPM_TRUSTSTORE_PASSWORD to "POS_JPM_TRUSTSTORE_PASSWORD",
        KEY_JPM_KEYSTORE to "POS_JPM_KEYSTORE", KEY_JPM_KEYSTORE_PASSWORD to "POS_JPM_KEYSTORE_PASSWORD",
        // the names J.P. Morgan's developer portal hands out (.env)
        KEY_JPM_MODE to "JPM_MODE",
        KEY_JPM_CLIENT_ID to "JPM_CLIENT_ID", KEY_JPM_CLIENT_SECRET to "JPM_CLIENT_SECRET",
        KEY_JPM_TOKEN_URL to "JPM_TOKEN_URL", KEY_JPM_SCOPE to "JPM_SCOPE",
        KEY_JPM_MERCHANT_ID to "JPM_MERCHANT_ID", KEY_JPM_BASE_URL to "JPM_BASE_URL",
    )

    /** J.P. Morgan: a simulated reader in front of the Online Payments sandbox, or a real in-store terminal. */
    enum class JpmMode { ONLINE, INSTORE }

    /** J.P. Morgan API credentials. The secret is never printed ([toString] masks it). */
    class JpmCredentials(
        val clientId: String?,
        internal val clientSecret: String?,
        val tokenUrl: String?,
        val scope: String?,
        val merchantId: String?,
        val baseUrl: String?,
    ) {
        val complete: Boolean get() = !clientId.isNullOrBlank() && !clientSecret.isNullOrBlank() && !tokenUrl.isNullOrBlank()
        /** The secret, for the token request only. */
        fun secret(): String? = clientSecret
        override fun toString() = "JpmCredentials(clientId=${clientId?.take(4)}…, secret=${if (clientSecret.isNullOrBlank()) "unset" else "***"}, " +
            "tokenUrl=$tokenUrl, scope=$scope, merchantId=$merchantId, baseUrl=$baseUrl)"
    }

    class Resolved(
        /** The kind asked for; null = the store's own default ([resolveFor]). */
        val kind: TerminalKind?,
        val source: String,
        val host: String? = null,
        val port: Int? = null,
        val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
        val jpmTruststore: String? = null,
        internal val jpmTruststorePassword: String? = null,
        val jpmKeystore: String? = null,
        internal val jpmKeystorePassword: String? = null,
        val warnings: List<String> = emptyList(),
        val jpmMode: JpmMode = JpmMode.ONLINE,
        val jpm: JpmCredentials = JpmCredentials(null, null, null, null, null, null),
    ) {
        fun jpmTruststorePassword() = jpmTruststorePassword
        fun jpmKeystorePassword() = jpmKeystorePassword
        /** The kind in effect for a store whose default is [default]. */
        fun resolveFor(default: TerminalKind): TerminalKind = kind ?: default

        fun address(): String? = host?.let { h -> port?.let { "$h:$it" } ?: h }

        fun describe(effective: TerminalKind): String = "Card terminal: ${effective.wire} (" +
            (if (kind == null) "store default" else source) +
            (address()?.let { ", host $it" } ?: "") + ", timeout ${timeoutSeconds}s)"

        // never print passwords
        override fun toString() = "PaymentTerminalConfig(kind=$kind, source=$source, host=${address()})"
    }

    val DEFAULT = Resolved(null, "default")

    /** From raw values (a key → value lookup). Never throws. */
    fun resolve(get: (String) -> String?, source: String): Resolved {
        val warnings = mutableListOf<String>()
        val rawKind = get(KEY)?.trim()?.takeIf { it.isNotEmpty() }
        val kind = rawKind?.let { raw ->
            TerminalKind.parse(raw) ?: null.also {
                warnings += "$source: invalid $KEY='$raw' (expected stripe|simulator|jpmorgan|external|off)"
            }
        }
        val (host, port) = parseHost(get(KEY_HOST)) { warnings += "$source: invalid $KEY_HOST='$it' (expected host[:port])" }
        val timeout = get(KEY_TIMEOUT)?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            raw.toIntOrNull()?.takeIf { it in 15..600 }
                ?: null.also { warnings += "$source: invalid $KEY_TIMEOUT='$raw' (expected 15..600)" }
        } ?: DEFAULT_TIMEOUT_SECONDS
        fun opt(k: String) = get(k)?.trim()?.takeIf { it.isNotEmpty() }
        return Resolved(
            kind = kind,
            source = if (kind == null) "default" else source,
            host = host, port = port, timeoutSeconds = timeout,
            jpmTruststore = opt(KEY_JPM_TRUSTSTORE), jpmTruststorePassword = opt(KEY_JPM_TRUSTSTORE_PASSWORD),
            jpmKeystore = opt(KEY_JPM_KEYSTORE), jpmKeystorePassword = opt(KEY_JPM_KEYSTORE_PASSWORD),
            warnings = warnings,
            jpmMode = when (opt(KEY_JPM_MODE)?.lowercase()) {
                null, "online" -> JpmMode.ONLINE
                "instore", "in-store", "terminal" -> JpmMode.INSTORE
                else -> JpmMode.ONLINE.also { warnings += "$source: invalid $KEY_JPM_MODE (expected online|instore)" }
            },
            jpm = JpmCredentials(opt(KEY_JPM_CLIENT_ID), opt(KEY_JPM_CLIENT_SECRET), opt(KEY_JPM_TOKEN_URL),
                opt(KEY_JPM_SCOPE), opt(KEY_JPM_MERCHANT_ID), opt(KEY_JPM_BASE_URL)),
        )
    }

    /** `host`, `host:port`, `http://host:port/` → (host, port?). Bad → (null, null) + [onBad]. */
    fun parseHost(raw: String?, onBad: (String) -> Unit = {}): Pair<String?, Int?> {
        val v = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null to null
        val bare = v.removePrefix("http://").removePrefix("https://").removePrefix("wss://").removePrefix("ws://").trimEnd('/')
        val m = Regex("""^([A-Za-z0-9.\-]+)(?::(\d{1,5}))?$""").matchEntire(bare)
        val port = m?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (m == null || (port != null && port !in 1..65535)) { onBad(v); return null to null }
        return m.groupValues[1] to port
    }

    fun fromProperties(props: Properties?, source: String = "store.properties"): Resolved =
        if (props == null) DEFAULT else resolve({ props.getProperty(it) }, source)

    fun fromFile(file: File?): Resolved {
        if (file == null || !file.exists()) return DEFAULT
        val props = try {
            Properties().apply { file.inputStream().use(::load) }
        } catch (e: Exception) {
            return Resolved(null, "default", warnings = listOf("${file.path}: unreadable (${e.message})"))
        }
        return fromProperties(props, file.path)
    }

    /** Desktop / docker: each env var wins over the same key in the POS_CONFIG_FILE properties file. */
    fun fromEnv(env: (String) -> String? = System::getenv): Resolved {
        val file = env(ReceiptPrintMode.ENV_CONFIG_FILE)?.takeIf { it.isNotBlank() }?.let(::File)
        val props = file?.takeIf { it.exists() }?.let { f -> runCatching { Properties().apply { f.inputStream().use(::load) } }.getOrNull() }
        val fromEnvKind = env(ENV)?.takeIf { it.isNotBlank() } != null
        return resolve({ key ->
            ENV_BY_KEY[key]?.let(env)?.takeIf { it.isNotBlank() } ?: props?.getProperty(key)
        }, if (fromEnvKind) ENV else file?.path ?: "default")
    }
}
