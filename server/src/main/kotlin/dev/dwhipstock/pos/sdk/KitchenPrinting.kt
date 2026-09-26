package dev.dwhipstock.pos.sdk

import java.io.File
import java.util.Properties

/**
 * Kitchen / station tickets (store config): `kitchen.printing=on|off`.
 *
 * - [OFF] (default): nothing changes. No station tickets, no queue, no extra
 *   buttons on the terminal. A store that never sets this behaves exactly as
 *   it always did.
 * - [ON]: sending an order prints one ticket per station (Kitchen, Bar, …)
 *   through a persistent print queue. Restaurants only: a retail store ignores
 *   it with a warning.
 *
 * `POS_KITCHEN_PRINTING` (desktop / docker) wins, else the `POS_CONFIG_FILE`
 * properties file; the tablet passes `kitchen.printing` from its
 * store.properties. Local config only; a bad value falls back to [OFF] with a
 * warning and never fails startup.
 */
enum class KitchenPrinting {
    ON, OFF;

    val wire: String get() = name.lowercase()
    val enabled: Boolean get() = this == ON

    /** The setting in effect plus where it came from, for the startup log. */
    data class Resolved(val mode: KitchenPrinting, val source: String, val warning: String? = null) {
        val enabled: Boolean get() = mode.enabled
        fun describe(): String = "Kitchen tickets: ${mode.wire} ($source)"
    }

    companion object {
        const val KEY = "kitchen.printing"
        const val ENV = "POS_KITCHEN_PRINTING"
        val DEFAULT = OFF
        val OFF_RESOLVED = Resolved(OFF, "default")

        /** `on` / `off` (case/space-insensitive); anything else is null. */
        fun parse(raw: String?): KitchenPrinting? = when (raw?.trim()?.lowercase()) {
            "on" -> ON
            "off" -> OFF
            else -> null
        }

        /** A raw value (null = unset) as the setting in effect. */
        fun resolve(raw: String?, source: String): Resolved =
            if (raw == null || raw.isBlank()) Resolved(DEFAULT, "default")
            else parse(raw)?.let { Resolved(it, source) }
                ?: Resolved(DEFAULT, "default", "$source: invalid $KEY='$raw' (expected on|off)")

        fun fromFile(file: File?): Resolved {
            if (file == null || !file.exists()) return Resolved(DEFAULT, "default")
            val props = try {
                Properties().apply { file.inputStream().use(::load) }
            } catch (e: Exception) {
                return Resolved(DEFAULT, "default", "${file.path}: unreadable (${e.message})")
            }
            return resolve(props.getProperty(KEY), file.path)
        }

        fun fromEnv(env: (String) -> String? = System::getenv): Resolved {
            env(ENV)?.takeIf { it.isNotBlank() }?.let { return resolve(it, ENV) }
            return fromFile(env(ReceiptPrintMode.ENV_CONFIG_FILE)?.takeIf { it.isNotBlank() }?.let(::File))
        }
    }
}
