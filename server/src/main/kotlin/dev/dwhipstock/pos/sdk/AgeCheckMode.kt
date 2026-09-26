package dev.dwhipstock.pos.sdk

import java.io.File
import java.util.Properties

/**
 * How the counter checks age for restricted items: `age.check` in the
 * tablet's store.properties / `POS_AGE_CHECK` on desktop.
 *
 * - `always` (the default): every sale with a restricted item needs an ID —
 *   a demo shouldn't argue with a cashier's guess;
 * - `looks-under:N` (e.g. `looks-under:40`): the cashier may pass a customer
 *   who clearly looks over N without an ID (method VISUAL), **except** for
 *   tobacco and vape, which always need the ID (federal Tobacco 21 practice).
 *
 * Local config only; a bad value falls back to `always` and never fails startup.
 */
data class AgeCheckMode(val looksOver: Int? = null) {
    val alwaysId: Boolean get() = looksOver == null
    val wire: String get() = if (looksOver == null) "always" else "looks-under:$looksOver"

    companion object {
        const val ENV = "POS_AGE_CHECK"
        const val KEY = "age.check"
        val ALWAYS = AgeCheckMode()

        fun parse(raw: String?): AgeCheckMode {
            val v = raw?.trim()?.lowercase() ?: return ALWAYS
            val n = v.removePrefix("looks-under:").takeIf { v.startsWith("looks-under:") }?.toIntOrNull()
            return if (n != null && n in 21..60) AgeCheckMode(n) else ALWAYS
        }

        fun fromEnv(env: (String) -> String? = System::getenv): AgeCheckMode {
            env(ENV)?.takeIf { it.isNotBlank() }?.let { return parse(it) }
            val file = env(ReceiptPrintMode.ENV_CONFIG_FILE)?.takeIf { it.isNotBlank() }?.let(::File)
            if (file == null || !file.exists()) return ALWAYS
            val props = runCatching { Properties().apply { file.inputStream().use(::load) } }.getOrNull() ?: return ALWAYS
            return parse(props.getProperty(KEY))
        }
    }
}
