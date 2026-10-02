package dev.dwhipstock.pos.sdk

import java.io.File
import java.time.Duration
import java.util.Properties

/**
 * How long an unpaid pay-first order (a kiosk order nobody came to pay, a
 * counter order left on a screen nobody looks at) is kept before the store
 * drops it: `orders.unpaidExpireMinutes=N` (store config, no UI). Default 30.
 * Counted from the order's last activity: a POS screen that has the order
 * open keeps it alive. Carry-out orders never expire.
 *
 * `POS_UNPAID_EXPIRE_MINUTES` (desktop / docker) wins, else the
 * `POS_CONFIG_FILE` properties file; the tablet passes the key from its
 * store.properties. A bad value falls back to the default with a warning and
 * never fails startup.
 */
object UnpaidExpiry {
    const val KEY = "orders.unpaidExpireMinutes"
    const val ENV = "POS_UNPAID_EXPIRE_MINUTES"
    const val DEFAULT_MINUTES = 30L
    /** A day: anything longer is a typo, not a policy. */
    private const val MAX_MINUTES = 24 * 60L

    /** The setting in effect plus where it came from, for the startup log. */
    data class Resolved(val minutes: Long, val source: String, val warning: String? = null) {
        val duration: Duration get() = Duration.ofMinutes(minutes)
        fun describe(): String = "Unpaid orders expire after $minutes min ($source)"
    }

    val DEFAULT = Resolved(DEFAULT_MINUTES, "default")

    /** A raw value (null = unset) as the setting in effect. */
    fun resolve(raw: String?, source: String): Resolved {
        if (raw == null || raw.isBlank()) return DEFAULT
        val n = raw.trim().toLongOrNull()
        return if (n != null && n in 1..MAX_MINUTES) Resolved(n, source)
        else Resolved(DEFAULT_MINUTES, "default", "$source: invalid $KEY='$raw' (expected 1..$MAX_MINUTES minutes)")
    }

    fun fromFile(file: File?): Resolved {
        if (file == null || !file.exists()) return DEFAULT
        val props = try {
            Properties().apply { file.inputStream().use(::load) }
        } catch (e: Exception) {
            return Resolved(DEFAULT_MINUTES, "default", "${file.path}: unreadable (${e.message})")
        }
        return resolve(props.getProperty(KEY), file.path)
    }

    fun fromEnv(env: (String) -> String? = System::getenv): Resolved {
        env(ENV)?.takeIf { it.isNotBlank() }?.let { return resolve(it, ENV) }
        return fromFile(env(ReceiptPrintMode.ENV_CONFIG_FILE)?.takeIf { it.isNotBlank() }?.let(::File))
    }
}
