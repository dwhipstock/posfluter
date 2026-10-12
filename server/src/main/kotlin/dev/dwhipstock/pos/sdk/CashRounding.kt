package dev.dwhipstock.pos.sdk

import java.io.File
import java.util.Properties

/**
 * Cash rounding (store config, no UI): `cash.rounding=nickel|unit-down|off`.
 * Only the CASH amount settled (or refunded in cash) rounds; prices, fees and
 * every tax stay exact to the cent. Card, debit, QR, Stripe and bank
 * transfers are always the exact amount.
 *
 * - [NICKEL]: Canada and the US no longer make pennies, so cash rounds to the
 *   nearest 5¢ on its last cent digit — 1–2 down to 0, 3–4 up to 5, 6–7 down
 *   to 5, 8–9 up to 10, 0 and 5 unchanged.
 * - [UNIT_DOWN] (`unit-down`, also `whole` or `baht`): cash drops the minor
 *   units — down to the whole unit of the currency, never up (฿1,234.75 →
 *   ฿1,234). The store gives up the difference; it is recorded like nickel
 *   rounding (tender rounding adjustment, shift and cloud reports).
 * - [OFF]: cash is charged to the cent.
 *
 * Unset, a store gets its currency's default ([defaultFor]): THB rounds down
 * to the whole baht, every other currency (CAD, USD, …) to the nickel.
 *
 * `POS_CASH_ROUNDING` (desktop / docker) wins, else the `POS_CONFIG_FILE`
 * properties file; the tablet passes `cash.rounding` from its store.properties.
 * Local config only; a bad value falls back to the default with a warning and
 * never fails startup.
 */
enum class CashRounding {
    NICKEL, UNIT_DOWN, OFF;

    val wire: String get() = when (this) {
        NICKEL -> "nickel"
        UNIT_DOWN -> "unit-down"
        OFF -> "off"
    }

    val policy: RoundingPolicy
        get() = when (this) {
            NICKEL -> RoundingPolicy.NICKEL
            UNIT_DOWN -> RoundingPolicy.WHOLE_UNIT_DOWN
            OFF -> RoundingPolicy.NoRounding
        }

    /**
     * The setting in effect plus where it came from, for the startup log.
     * [explicit] = the store set it (env or file); false = nothing (or a bad
     * value) was set, so [forCurrency] picks the currency's default.
     */
    data class Resolved(
        val rounding: CashRounding,
        val source: String,
        val warning: String? = null,
        val explicit: Boolean = source != "default",
    ) {
        /** What a store in [currency] rounds cash with: the explicit setting, else the currency's default. */
        fun forCurrency(currency: String): CashRounding = if (explicit) rounding else defaultFor(currency)
    }

    companion object {
        const val KEY = "cash.rounding"
        const val ENV = "POS_CASH_ROUNDING"
        val DEFAULT = NICKEL

        /** The default for a store in [currency]: THB down to the whole baht, everything else the nickel. */
        fun defaultFor(currency: String): CashRounding = when (currency.trim().uppercase()) {
            "THB" -> UNIT_DOWN
            else -> NICKEL
        }

        /** The wire name of [policy] (`/health` `cashRounding`): nickel, unit-down, off, or `custom`. */
        fun wireOf(policy: RoundingPolicy): String =
            entries.firstOrNull { it.policy == policy }?.wire ?: "custom"

        /** `nickel` / `unit-down` (`whole`, `baht`) / `off` (case/space-insensitive); anything else is null. */
        fun parse(raw: String?): CashRounding? = when (raw?.trim()?.lowercase()) {
            "nickel" -> NICKEL
            "unit-down", "unit_down", "whole", "baht" -> UNIT_DOWN
            "off" -> OFF
            else -> null
        }

        /** A raw value (null = unset) as the setting in effect. */
        fun resolve(raw: String?, source: String): Resolved =
            if (raw == null || raw.isBlank()) Resolved(DEFAULT, "default")
            else parse(raw)?.let { Resolved(it, source) }
                ?: Resolved(DEFAULT, "default", "$source: invalid $KEY='$raw' (expected nickel|unit-down|off)")

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
