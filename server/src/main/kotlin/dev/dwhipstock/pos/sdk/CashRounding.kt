package dev.dwhipstock.pos.sdk

import java.io.File
import java.util.Properties

/**
 * Cash rounding to the nickel (store config, no UI): `cash.rounding=nickel|off`.
 * Canada and the US no longer make pennies, so a CASH payment's final amount
 * rounds to the nearest 5¢ on its last cent digit — 1–2 down to 0, 3–4 up to 5,
 * 6–7 down to 5, 8–9 up to 10, 0 and 5 unchanged. Prices, fees and every tax
 * stay exact to the cent; only the cash settled (or refunded in cash) rounds.
 * Card, debit, Stripe and bank transfers are always the exact amount.
 *
 * - [NICKEL] (default, both CAD and USD stores)
 * - [OFF]: cash is charged to the cent.
 *
 * `POS_CASH_ROUNDING` (desktop / docker) wins, else the `POS_CONFIG_FILE`
 * properties file; the tablet passes `cash.rounding` from its store.properties.
 * Local config only; a bad value falls back to the default with a warning and
 * never fails startup.
 */
enum class CashRounding {
    NICKEL, OFF;

    val wire: String get() = name.lowercase()

    val policy: RoundingPolicy
        get() = when (this) {
            NICKEL -> RoundingPolicy.NICKEL
            OFF -> RoundingPolicy.NoRounding
        }

    /** The setting in effect plus where it came from, for the startup log. */
    data class Resolved(val rounding: CashRounding, val source: String, val warning: String? = null)

    companion object {
        const val KEY = "cash.rounding"
        const val ENV = "POS_CASH_ROUNDING"
        val DEFAULT = NICKEL

        /** `nickel` / `off` (case/space-insensitive); anything else is null. */
        fun parse(raw: String?): CashRounding? = when (raw?.trim()?.lowercase()) {
            "nickel" -> NICKEL
            "off" -> OFF
            else -> null
        }

        /** A raw value (null = unset) as the setting in effect. */
        fun resolve(raw: String?, source: String): Resolved =
            if (raw == null || raw.isBlank()) Resolved(DEFAULT, "default")
            else parse(raw)?.let { Resolved(it, source) }
                ?: Resolved(DEFAULT, "default", "$source: invalid $KEY='$raw' (expected nickel|off)")

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
