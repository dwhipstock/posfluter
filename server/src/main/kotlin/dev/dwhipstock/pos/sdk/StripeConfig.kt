package dev.dwhipstock.pos.sdk

import java.io.File
import java.util.Properties

/**
 * Stripe card payments (store config file, no UI). TEST MODE ONLY for now.
 *
 * - `stripe.secretKey` in the tablet's store.properties, or `STRIPE_KEY` on the
 *   desktop / docker store. Only `sk_test_…` keys are accepted: anything else
 *   (a live key, a restricted key, garbage) is refused and Stripe stays off.
 * - `stripe.locationId` / `STRIPE_LOCATION_ID` (optional): the Terminal
 *   Location readers register to. Unset → the store creates or reuses one.
 *
 * Missing or refused config just leaves the "Card (Stripe)" tender disabled;
 * startup, login and every other tender are unaffected. The key is never
 * logged: [toString] and [Resolved.describe] print only a masked form.
 */
object StripeConfig {
    const val KEY_SECRET = "stripe.secretKey"
    const val KEY_LOCATION = "stripe.locationId"
    const val ENV_SECRET = "STRIPE_KEY"
    const val ENV_LOCATION = "STRIPE_LOCATION_ID"
    const val TEST_PREFIX = "sk_test_"

    /** Why Stripe is off. The code goes to the client; never includes the key. */
    enum class Disabled(val code: String) {
        NOT_CONFIGURED("stripe_not_configured"),
        NOT_TEST_KEY("stripe_live_key_refused"),
    }

    class Resolved(
        secretKey: String?,
        val locationId: String?,
        val source: String,
        val disabled: Disabled?,
    ) {
        /** Present only when [enabled]; kept private-ish so it is not dumped by accident. */
        internal val secretKey: String? = if (disabled == null) secretKey else null
        val enabled: Boolean get() = disabled == null && secretKey != null

        /** One line for the startup log. Never contains the key. */
        fun describe(): String = when (disabled) {
            null -> "Stripe: TEST mode enabled ($source" +
                (locationId?.let { ", location $it" } ?: ", location auto") + ")"
            Disabled.NOT_CONFIGURED -> "Stripe: disabled (no key)"
            Disabled.NOT_TEST_KEY -> "Stripe: DISABLED — $source is not an sk_test_ key; " +
                "only test mode is supported (the key was ignored)"
        }

        override fun toString() = describe()
    }

    val OFF = Resolved(null, null, "default", Disabled.NOT_CONFIGURED)

    /** Validate a raw key + location from [source]. Never throws. */
    fun of(rawKey: String?, rawLocation: String?, source: String): Resolved {
        val key = rawKey?.trim()?.takeIf { it.isNotEmpty() } ?: return OFF
        val location = rawLocation?.trim()?.takeIf { it.isNotEmpty() }
        if (!key.startsWith(TEST_PREFIX) || key.length <= TEST_PREFIX.length || key.any { it.isWhitespace() })
            return Resolved(null, location, source, Disabled.NOT_TEST_KEY)
        return Resolved(key, location, source, null)
    }

    /** Tablet: read store.properties. Missing/unreadable file → disabled. */
    fun fromFile(file: File?): Resolved {
        if (file == null || !file.exists()) return OFF
        val props = try {
            Properties().apply { file.inputStream().use(::load) }
        } catch (_: Exception) {
            return OFF
        }
        return of(props.getProperty(KEY_SECRET), props.getProperty(KEY_LOCATION), file.path)
    }

    /** Desktop / docker: [ENV_SECRET] wins, else the POS_CONFIG_FILE properties file. */
    fun fromEnv(env: (String) -> String? = System::getenv): Resolved {
        env(ENV_SECRET)?.takeIf { it.isNotBlank() }?.let { return of(it, env(ENV_LOCATION), ENV_SECRET) }
        val fromFile = fromFile(env(ReceiptPrintMode.ENV_CONFIG_FILE)?.takeIf { it.isNotBlank() }?.let(::File))
        val envLocation = env(ENV_LOCATION)?.takeIf { it.isNotBlank() }
        return if (envLocation != null && fromFile.locationId == null && fromFile.enabled)
            Resolved(fromFile.secretKey, envLocation, fromFile.source, null)
        else fromFile
    }
}
