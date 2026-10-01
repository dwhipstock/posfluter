package dev.dwhipstock.pos.sdk

import java.io.File
import java.util.Properties

/**
 * Demo mode (store config): `demo.mode=on|off`, default off. One switch for
 * the pitch demo's conveniences, so none of them leak into a real store:
 *
 * - the staff phone app signs in by PIN only (forces `staff.app.mfa` off;
 *   with demo mode off the explicit `staff.app.mfa` setting applies as before).
 *   Because of that, a MANAGER PIN signs in and approves only on the POS itself
 *   (loopback / a paired terminal): the staff app, the kitchen screen and any
 *   phone on the Wi-Fi get 403 `manager_pos_only` (red-team; see AuthService);
 * - managers get "Print demo QR sheet" in Venue settings: one slip with a QR
 *   for every app guests can try, and how to sign in to each.
 *
 * The sheet's manager-portal block reads `demo.portal.url`, `demo.portal.user`
 * and `demo.portal.password` from the same file. They are never committed,
 * never logged ([toString] and [describe] leave the password out) and only
 * ever printed on that one slip; unset ones print "ask the presenter".
 *
 * `POS_DEMO_MODE` (+ `POS_DEMO_PORTAL_URL` / `_USER` / `_PASSWORD`) on desktop /
 * docker win, else the `POS_CONFIG_FILE` properties file; the tablet passes its
 * store.properties. A bad value → off with a warning; never fails startup.
 */
class DemoMode(
    val on: Boolean,
    val source: String,
    val portalUrl: String? = null,
    val portalUser: String? = null,
    /** Only read by the demo sheet. Kept out of [toString] so it can't reach a log line. */
    val portalPassword: String? = null,
    val warning: String? = null,
) {
    /** The staff-app MFA in effect: demo mode forces it off, else [explicit] stands. */
    fun staffAppMfa(explicit: StaffAppMfa.Resolved): StaffAppMfa.Resolved =
        if (on) StaffAppMfa.Resolved(StaffAppMfa.OFF, "$KEY=on ($source)") else explicit

    /** For the startup log: never the password, never the user name. */
    fun describe(): String = if (!on) "Demo mode: off ($source)" else
        "Demo mode: on ($source) — staff app MFA off, \"Print demo QR sheet\" in Venue settings; " +
            "portal sign-in ${if (portalUser != null && portalPassword != null) "set" else "not set (prints 'ask the presenter')"}"

    override fun toString(): String =
        "DemoMode(on=$on, source=$source, portalUrl=$portalUrl, portalUser=${if (portalUser == null) "unset" else "set"}, " +
            "portalPassword=${if (portalPassword == null) "unset" else "<redacted>"})"

    companion object {
        const val KEY = "demo.mode"
        const val PORTAL_URL = "demo.portal.url"
        const val PORTAL_USER = "demo.portal.user"
        const val PORTAL_PASSWORD = "demo.portal.password"
        const val ENV = "POS_DEMO_MODE"
        const val ENV_PORTAL_URL = "POS_DEMO_PORTAL_URL"
        const val ENV_PORTAL_USER = "POS_DEMO_PORTAL_USER"
        const val ENV_PORTAL_PASSWORD = "POS_DEMO_PORTAL_PASSWORD"

        val OFF = DemoMode(false, "default")

        /** `on` / `off` (case/space-insensitive); anything else is null. */
        fun parse(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
            "on" -> true
            "off" -> false
            else -> null
        }

        private fun clean(v: String?) = v?.trim()?.takeIf { it.isNotEmpty() }

        /** Resolve from raw values (null = unset); [lookup] reads the demo.portal.* keys. */
        fun resolve(raw: String?, source: String, lookup: (String) -> String?): DemoMode {
            if (raw.isNullOrBlank()) return OFF
            val on = parse(raw)
                ?: return DemoMode(false, "default", warning = "$source: invalid $KEY='${raw.trim()}' (expected on|off)")
            if (!on) return DemoMode(false, source)
            return DemoMode(
                true, source,
                portalUrl = clean(lookup(PORTAL_URL)),
                portalUser = clean(lookup(PORTAL_USER)),
                portalPassword = clean(lookup(PORTAL_PASSWORD)),
            )
        }

        /** Tablet: the store.properties already loaded (null = no file → off). */
        fun fromProperties(props: Properties?, source: String = "store.properties"): DemoMode =
            if (props == null) OFF else resolve(props.getProperty(KEY), source) { props.getProperty(it) }

        fun fromFile(file: File?): DemoMode {
            if (file == null || !file.exists()) return OFF
            val props = try {
                Properties().apply { file.inputStream().use(::load) }
            } catch (e: Exception) {
                return DemoMode(false, "default", warning = "${file.path}: unreadable (${e.message})")
            }
            return fromProperties(props, file.path)
        }

        /** Desktop / docker: POS_DEMO_MODE wins (with POS_DEMO_PORTAL_*), else the POS_CONFIG_FILE file. */
        fun fromEnv(env: (String) -> String? = System::getenv): DemoMode {
            env(ENV)?.takeIf { it.isNotBlank() }?.let { raw ->
                val keys = mapOf(PORTAL_URL to ENV_PORTAL_URL, PORTAL_USER to ENV_PORTAL_USER, PORTAL_PASSWORD to ENV_PORTAL_PASSWORD)
                return resolve(raw, ENV) { key -> keys[key]?.let(env) }
            }
            return fromFile(env(ReceiptPrintMode.ENV_CONFIG_FILE)?.takeIf { it.isNotBlank() }?.let(::File))
        }
    }
}
