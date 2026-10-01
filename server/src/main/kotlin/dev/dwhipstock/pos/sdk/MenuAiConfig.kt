package dev.dwhipstock.pos.sdk

import java.io.File
import java.util.Properties

/**
 * AI menu setup (menu from photos, menu chat). Store config only, like
 * [ImageGenConfig]:
 * - `menu.ai=on|off` — the feature flag. Default off: no AI menu button.
 * - `menu.ai.provider=gemini|openai|anthropic|off`. Default off.
 * - the selected provider's key: `menu.ai.gemini.apiKey` / `menu.ai.openai.apiKey`
 *   / `menu.ai.anthropic.apiKey` in store.properties, or `GEMINI_API_KEY` /
 *   `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` in the env (desktop / Windows / docker).
 * - optional `menu.ai.model` (the provider's model id).
 * - optional `menu.ai.layoutModel`: the model for the room-from-picture and
 *   object-from-photo calls only (spatial work, a one-off setup: slower is
 *   fine). Default: Gemini's thinking flash model, else `menu.ai.model`.
 * - optional `menu.ai.voiceModel`: the model for SPOKEN requests (menu and
 *   floor plan). Unset: an explicit `menu.ai.model` (menu) / `menu.ai.layoutModel`
 *   (floor) applies to voice too; with no override at all, Gemini voice uses
 *   the stronger flash model while typed requests keep the fast lite one.
 *
 * Env vars (`POS_MENU_AI`, `POS_MENU_AI_PROVIDER`, `POS_MENU_AI_MODEL`, the
 * keys) win over the `POS_CONFIG_FILE` properties file. The key is never
 * logged ([toString] / [Resolved.describe] never print it), never synced and
 * never returned. Bad config leaves the feature off; it never fails startup.
 */
object MenuAiConfig {
    const val KEY_ENABLED = "menu.ai"
    const val KEY_PROVIDER = "menu.ai.provider"
    const val KEY_MODEL = "menu.ai.model"
    const val ENV_ENABLED = "POS_MENU_AI"
    const val ENV_PROVIDER = "POS_MENU_AI_PROVIDER"
    const val ENV_MODEL = "POS_MENU_AI_MODEL"
    const val KEY_LAYOUT_MODEL = "menu.ai.layoutModel"
    const val ENV_LAYOUT_MODEL = "POS_MENU_AI_LAYOUT_MODEL"
    const val KEY_VOICE_MODEL = "menu.ai.voiceModel"
    const val ENV_VOICE_MODEL = "POS_MENU_AI_VOICE_MODEL"

    enum class Provider(val wire: String, val keyProperty: String?, val keyEnv: String?) {
        GEMINI("gemini", "menu.ai.gemini.apiKey", "GEMINI_API_KEY"),
        OPENAI("openai", "menu.ai.openai.apiKey", "OPENAI_API_KEY"),
        ANTHROPIC("anthropic", "menu.ai.anthropic.apiKey", "ANTHROPIC_API_KEY"),
        OFF("off", null, null);

        companion object {
            fun parse(raw: String?): Provider? = entries.firstOrNull { it.wire == raw?.trim()?.lowercase() }
        }
    }

    enum class Disabled(val code: String) {
        MENU_AI_OFF("menu_ai_off"),
        PROVIDER_OFF("menu_ai_provider_off"),
        KEY_MISSING("menu_ai_key_missing"),
    }

    class Resolved(
        val enabledFlag: Boolean,
        val provider: Provider,
        apiKey: String?,
        val model: String?,
        val source: String,
        val warning: String? = null,
        val layoutModel: String? = null,
        val voiceModel: String? = null,
    ) {
        val disabled: Disabled? = when {
            !enabledFlag -> Disabled.MENU_AI_OFF
            provider == Provider.OFF -> Disabled.PROVIDER_OFF
            apiKey.isNullOrBlank() -> Disabled.KEY_MISSING
            else -> null
        }

        /** Present only when [enabled]. Internal so it is not serialised or dumped by accident. */
        internal val apiKey: String? = if (disabled == null) apiKey else null
        val enabled: Boolean get() = disabled == null

        /** One line for the startup log. Never contains the key. */
        fun describe(): String = when (disabled) {
            null -> "AI menu: on (${provider.wire}${model?.let { ", model $it" } ?: ""}; $source)"
            Disabled.MENU_AI_OFF -> "AI menu: off (menu.ai is off)"
            Disabled.PROVIDER_OFF -> "AI menu: off (menu.ai.provider is off)"
            Disabled.KEY_MISSING -> "AI menu: off (no ${provider.keyEnv} / ${provider.keyProperty} for ${provider.wire})"
        }

        override fun toString() = describe()
    }

    val OFF = Resolved(false, Provider.OFF, null, null, "default")

    private fun clean(raw: String?) = raw?.trim()?.takeIf { it.isNotEmpty() }

    fun resolve(prop: (String) -> String?, env: (String) -> String? = { null }, source: String): Resolved {
        val warnings = mutableListOf<String>()
        fun pick(envName: String, key: String) = clean(env(envName)) ?: clean(prop(key))

        val rawEnabled = pick(ENV_ENABLED, KEY_ENABLED)
        val enabled = when (rawEnabled?.lowercase()) {
            null, "off", "false", "no" -> false
            "on", "true", "yes" -> true
            else -> { warnings += "invalid $KEY_ENABLED='$rawEnabled' (expected on|off)"; false }
        }
        val rawProvider = pick(ENV_PROVIDER, KEY_PROVIDER)
        val provider = if (rawProvider == null) Provider.OFF else Provider.parse(rawProvider) ?: run {
            warnings += "invalid $KEY_PROVIDER='$rawProvider' (expected gemini|openai|anthropic|off)"
            Provider.OFF
        }
        // only the selected provider's key is ever read
        val key = if (provider.keyEnv == null) null
            else clean(env(provider.keyEnv))?.let { it to provider.keyEnv }
                ?: clean(prop(provider.keyProperty!!))?.let { it to source }
        val keyValue = key?.first?.takeUnless { it.any(Char::isWhitespace) }
        if (key != null && keyValue == null) warnings += "the ${provider.wire} key contains spaces and was ignored"
        return Resolved(
            enabledFlag = enabled,
            provider = provider,
            apiKey = keyValue,
            model = pick(ENV_MODEL, KEY_MODEL),
            source = key?.second ?: source,
            warning = warnings.takeIf { it.isNotEmpty() }?.joinToString("; "),
            layoutModel = pick(ENV_LAYOUT_MODEL, KEY_LAYOUT_MODEL),
            voiceModel = pick(ENV_VOICE_MODEL, KEY_VOICE_MODEL),
        )
    }

    /** Tablet: the store.properties already loaded (null = no file → off). */
    fun fromProperties(props: Properties?, source: String = "store.properties"): Resolved =
        if (props == null) OFF else resolve(props::getProperty, source = source)

    /** Desktop / Windows / docker: env vars win, else the POS_CONFIG_FILE properties file. */
    fun fromEnv(env: (String) -> String? = System::getenv): Resolved {
        val file = env(ReceiptPrintMode.ENV_CONFIG_FILE)?.takeIf { it.isNotBlank() }?.let(::File)
        val props = file?.takeIf { it.exists() }?.let {
            runCatching { Properties().apply { it.inputStream().use(::load) } }.getOrNull()
        }
        return resolve({ props?.getProperty(it) }, env, source = if (props != null) file.path else "env")
    }
}
