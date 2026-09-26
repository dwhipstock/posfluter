package dev.dwhipstock.pos.sdk

import java.io.File
import java.util.Properties

/**
 * AI menu photos (a paid add-on; store config file, no settings UI).
 *
 * Two switches and a key, all local to the store:
 * - `image.generation=on|off` — the feature flag per client (sold or not).
 *   Default off: the menu editor shows no AI buttons at all.
 * - `image.provider=flux|gemini|openai|off` — which image API. Default off.
 * - the selected provider's key: `image.bfl.apiKey` / `image.gemini.apiKey` /
 *   `image.openai.apiKey` in the tablet's store.properties, or `BFL_API_KEY` /
 *   `GEMINI_API_KEY` / `OPENAI_API_KEY` on the desktop / docker store.
 * - optional `image.model` (the provider's model id) and `image.style` (a
 *   house-style line that replaces the brand's built-in one).
 *
 * Desktop: each `POS_IMAGE_*` / key env var wins over the same setting in the
 * `POS_CONFIG_FILE` properties file (the [StripeConfig] pattern). The tablet
 * passes its store.properties to [fromProperties].
 *
 * The key never leaves the store: it is not logged ([toString] and
 * [Resolved.describe] never print it), never written to the outbox (so never
 * synced), and never in an API response. Only the selected provider's key is
 * kept. Missing / bad config leaves the feature off; it never fails startup
 * and never touches selling.
 */
object ImageGenConfig {
    const val KEY_GENERATION = "image.generation"
    const val KEY_PROVIDER = "image.provider"
    const val KEY_MODEL = "image.model"
    const val KEY_STYLE = "image.style"
    const val ENV_GENERATION = "POS_IMAGE_GENERATION"
    const val ENV_PROVIDER = "POS_IMAGE_PROVIDER"
    const val ENV_MODEL = "POS_IMAGE_MODEL"
    const val ENV_STYLE = "POS_IMAGE_STYLE"

    enum class Provider(val wire: String, val keyProperty: String?, val keyEnv: String?) {
        FLUX("flux", "image.bfl.apiKey", "BFL_API_KEY"),
        GEMINI("gemini", "image.gemini.apiKey", "GEMINI_API_KEY"),
        OPENAI("openai", "image.openai.apiKey", "OPENAI_API_KEY"),
        OFF("off", null, null);

        companion object {
            fun parse(raw: String?): Provider? = entries.firstOrNull { it.wire == raw?.trim()?.lowercase() }
        }
    }

    /** Why AI photos are off. The code goes to the client; never includes the key. */
    enum class Disabled(val code: String) {
        GENERATION_OFF("image_generation_off"),
        PROVIDER_OFF("image_provider_off"),
        KEY_MISSING("image_key_missing"),
    }

    class Resolved(
        val generation: Boolean,
        val provider: Provider,
        apiKey: String?,
        val model: String?,
        val style: String?,
        val source: String,
        val warning: String? = null,
    ) {
        val disabled: Disabled? = when {
            !generation -> Disabled.GENERATION_OFF
            provider == Provider.OFF -> Disabled.PROVIDER_OFF
            apiKey.isNullOrBlank() -> Disabled.KEY_MISSING
            else -> null
        }

        /** Present only when [enabled]. Internal so it is not serialised or dumped by accident. */
        internal val apiKey: String? = if (disabled == null) apiKey else null
        val enabled: Boolean get() = disabled == null

        /** One line for the startup log. Never contains the key. */
        fun describe(): String = when (disabled) {
            null -> "AI photos: on (${provider.wire}${model?.let { ", model $it" } ?: ""}; $source)"
            Disabled.GENERATION_OFF -> "AI photos: off (image.generation is off)"
            Disabled.PROVIDER_OFF -> "AI photos: off (image.provider is off)"
            Disabled.KEY_MISSING -> "AI photos: off (no ${provider.keyEnv} / ${provider.keyProperty} for ${provider.wire})"
        }

        override fun toString() = describe()
    }

    val OFF = Resolved(false, Provider.OFF, null, null, null, "default")

    private fun clean(raw: String?) = raw?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Resolve from a lookup of property keys ([prop]) and, on the desktop, env
     * vars ([env], which win field by field). Never throws.
     */
    fun resolve(prop: (String) -> String?, env: (String) -> String? = { null }, source: String): Resolved {
        val warnings = mutableListOf<String>()
        fun pick(envName: String, key: String) = clean(env(envName)) ?: clean(prop(key))

        val rawGeneration = pick(ENV_GENERATION, KEY_GENERATION)
        val generation = when (rawGeneration?.lowercase()) {
            null, "off", "false", "no" -> false
            "on", "true", "yes" -> true
            else -> { warnings += "invalid $KEY_GENERATION='$rawGeneration' (expected on|off)"; false }
        }
        val rawProvider = pick(ENV_PROVIDER, KEY_PROVIDER)
        val provider = if (rawProvider == null) Provider.OFF else Provider.parse(rawProvider) ?: run {
            warnings += "invalid $KEY_PROVIDER='$rawProvider' (expected flux|gemini|openai|off)"
            Provider.OFF
        }
        // only the selected provider's key is ever read
        val key = if (provider.keyEnv == null) null
            else clean(env(provider.keyEnv))?.let { it to provider.keyEnv }
                ?: clean(prop(provider.keyProperty!!))?.let { it to source }
        val keyValue = key?.first?.takeUnless { it.any(Char::isWhitespace) }
        if (key != null && keyValue == null) warnings += "the ${provider.wire} key contains spaces and was ignored"
        return Resolved(
            generation = generation,
            provider = provider,
            apiKey = keyValue,
            model = pick(ENV_MODEL, KEY_MODEL),
            style = pick(ENV_STYLE, KEY_STYLE),
            source = key?.second ?: source,
            warning = warnings.takeIf { it.isNotEmpty() }?.joinToString("; "),
        )
    }

    /** Tablet: the store.properties already loaded (null = no file → off). */
    fun fromProperties(props: Properties?, source: String = "store.properties"): Resolved =
        if (props == null) OFF else resolve(props::getProperty, source = source)

    fun fromFile(file: File?): Resolved {
        if (file == null || !file.exists()) return OFF
        val props = try {
            Properties().apply { file.inputStream().use(::load) }
        } catch (_: Exception) {
            return OFF
        }
        return fromProperties(props, file.path)
    }

    /** Desktop / docker: env vars win, else the POS_CONFIG_FILE properties file. */
    fun fromEnv(env: (String) -> String? = System::getenv): Resolved {
        val file = env(ReceiptPrintMode.ENV_CONFIG_FILE)?.takeIf { it.isNotBlank() }?.let(::File)
        val props = file?.takeIf { it.exists() }?.let {
            runCatching { Properties().apply { it.inputStream().use(::load) } }.getOrNull()
        }
        return resolve({ props?.getProperty(it) }, env, source = if (props != null) file.path else "env")
    }
}
