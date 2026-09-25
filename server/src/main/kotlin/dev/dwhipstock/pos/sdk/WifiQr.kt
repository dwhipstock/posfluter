package dev.dwhipstock.pos.sdk

/** QR "T" values for a Wi-Fi join code. Stored as-is in `venue_settings.wifi_security`. */
object WifiSecurity {
    const val WPA = "WPA"
    const val WEP = "WEP"
    const val NOPASS = "nopass"
    val all = listOf(WPA, WEP, NOPASS)

    /** Case-insensitive input ("wpa", "NOPASS") to the canonical value, or null if unknown. */
    fun normalize(raw: String): String? = all.firstOrNull { it.equals(raw.trim(), ignoreCase = true) }
}

/**
 * The venue's guest Wi-Fi, as printed on join slips. [configured] needs a
 * network name, plus a password unless the network is open.
 */
data class GuestWifi(
    val ssid: String,
    val password: String,
    val security: String = WifiSecurity.WPA,
    val hidden: Boolean = false,
) {
    val configured: Boolean
        get() = ssid.isNotBlank() && (security == WifiSecurity.NOPASS || password.isNotEmpty())

    /** Payload phones read to join; see [WifiQr.payload]. */
    fun qrPayload(): String = WifiQr.payload(ssid, password, security, hidden)
}

/**
 * The de-facto standard Wi-Fi join QR payload that phone cameras understand:
 * `WIFI:T:<WPA|WEP|nopass>;S:<ssid>;P:<password>;H:true;;`. `H` appears only
 * for a hidden network, and `P` is left out for an open (`nopass`) network.
 * In S and P the characters `\ ; , : "` are escaped with a backslash.
 */
object WifiQr {
    private val special = setOf('\\', ';', ',', ':', '"')

    fun escape(value: String): String = buildString {
        for (c in value) {
            if (c in special) append('\\')
            append(c)
        }
    }

    fun payload(ssid: String, password: String, security: String, hidden: Boolean): String = buildString {
        append("WIFI:T:").append(security).append(';')
        append("S:").append(escape(ssid)).append(';')
        if (security != WifiSecurity.NOPASS) append("P:").append(escape(password)).append(';')
        if (hidden) append("H:true;")
        append(';')
    }
}
