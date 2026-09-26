package dev.dwhipstock.pos.sdk

import java.io.File
import java.util.Properties

/**
 * Staff web app (`/staff-app`) sign-in MFA (store config, no UI): `staff.app.mfa=on|off`.
 *
 * - [ON] (default): PIN + authenticator code (TOTP), with a trusted device.
 * - [OFF]: PIN only. The PIN check, rate limit and session expiry are kept, and
 *   existing authenticator enrollments stay for when it is turned back on.
 *
 * Only the staff app; the terminal's PIN login and the owner portal are unaffected.
 * `POS_STAFF_APP_MFA` (desktop / docker) wins, else the `POS_CONFIG_FILE`
 * properties file; the tablet passes `staff.app.mfa` from its store.properties.
 * Local config only; a bad value falls back to [ON] with a warning and never
 * fails startup.
 */
enum class StaffAppMfa {
    ON, OFF;

    val wire: String get() = name.lowercase()
    val required: Boolean get() = this == ON

    /** The setting in effect plus where it came from, for the startup log. */
    data class Resolved(val mfa: StaffAppMfa, val source: String, val warning: String? = null) {
        val required: Boolean get() = mfa.required
        fun describe(): String = "Staff app MFA: ${mfa.wire} ($source)" +
            if (mfa == OFF) " — staff sign in to /staff-app with their PIN only" else ""
    }

    companion object {
        const val KEY = "staff.app.mfa"
        const val ENV = "POS_STAFF_APP_MFA"
        val DEFAULT = ON

        /** `on` / `off` (case/space-insensitive); anything else is null. */
        fun parse(raw: String?): StaffAppMfa? = when (raw?.trim()?.lowercase()) {
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
