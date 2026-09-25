package dev.dwhipstock.pos.base

import dev.dwhipstock.pos.sdk.GuestWifi
import dev.dwhipstock.pos.sdk.Outbox
import dev.dwhipstock.pos.sdk.WifiSecurity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Owner-tunable venue settings, read on every transaction via the customer
 * config's data getters — a change takes effect on the next transaction with
 * no restart. Cached in memory; invalidated on update (single process).
 */
class SettingsRepository {

    @Volatile
    private var cache: Settings? = null

    fun get(): Settings = cache ?: transaction {
        VenueSettings.selectAll().where { VenueSettings.id eq 1 }.first().let {
            Settings(
                cardProcessor = it[VenueSettings.cardProcessor],
                bankName = it[VenueSettings.bankName],
                bankAccountNumber = it[VenueSettings.bankAccountNumber],
                bankAccountName = it[VenueSettings.bankAccountName],
                serviceChargePercent = it[VenueSettings.serviceChargePercent],
                corkagePerBottleCents = it[VenueSettings.corkagePerBottleCents],
                receiptFooter = it[VenueSettings.receiptFooter],
                venuePhone = it[VenueSettings.venuePhone],
                venueAddress = it[VenueSettings.venueAddress],
                sessionIdleMinutes = it[VenueSettings.sessionIdleMinutes],
                pendingAlertsEnabled = it[VenueSettings.pendingAlertsEnabled] != 0,
                pendingAlertEscalateSeconds = it[VenueSettings.pendingAlertEscalateSeconds],
                pendingAlertVolume = it[VenueSettings.pendingAlertVolume],
                printerIp = it[VenueSettings.printerIp],
                printerPort = it[VenueSettings.printerPort],
                wifiSsid = it[VenueSettings.wifiSsid],
                wifiPassword = it[VenueSettings.wifiPassword],
                wifiSecurity = it[VenueSettings.wifiSecurity],
                wifiHidden = it[VenueSettings.wifiHidden] != 0,
            )
        }
    }.also { cache = it }

    /** Partial update: null fields stay unchanged. Outbox records only the changed keys. */
    fun update(patch: SettingsPatch): Settings = transaction {
        val before = get()
        val changed = mutableListOf<String>()
        fun <T> merge(new: T?, old: T, key: String): T =
            if (new != null && new != old) { changed += key; new } else old

        val next = Settings(
            cardProcessor = merge(patch.cardProcessor?.trim(), before.cardProcessor, "cardProcessor"),
            bankName = merge(patch.bankName?.trim(), before.bankName, "bankName"),
            bankAccountNumber = merge(patch.bankAccountNumber?.trim(), before.bankAccountNumber, "bankAccountNumber"),
            bankAccountName = merge(patch.bankAccountName?.trim(), before.bankAccountName, "bankAccountName"),
            serviceChargePercent = merge(patch.serviceChargePercent, before.serviceChargePercent, "serviceChargePercent"),
            corkagePerBottleCents = merge(patch.corkagePerBottleCents, before.corkagePerBottleCents, "corkagePerBottleCents"),
            receiptFooter = merge(patch.receiptFooter?.trim(), before.receiptFooter, "receiptFooter"),
            venuePhone = merge(patch.venuePhone?.trim(), before.venuePhone, "venuePhone"),
            venueAddress = merge(patch.venueAddress?.trim(), before.venueAddress, "venueAddress"),
            sessionIdleMinutes = merge(patch.sessionIdleMinutes, before.sessionIdleMinutes, "sessionIdleMinutes"),
            pendingAlertsEnabled = merge(patch.pendingAlertsEnabled, before.pendingAlertsEnabled, "pendingAlertsEnabled"),
            pendingAlertEscalateSeconds = merge(patch.pendingAlertEscalateSeconds, before.pendingAlertEscalateSeconds, "pendingAlertEscalateSeconds"),
            pendingAlertVolume = merge(patch.pendingAlertVolume, before.pendingAlertVolume, "pendingAlertVolume"),
            printerIp = merge(patch.printerIp?.trim(), before.printerIp, "printerIp"),
            printerPort = merge(patch.printerPort, before.printerPort, "printerPort"),
            wifiSsid = merge(patch.wifiSsid?.trim(), before.wifiSsid, "wifiSsid"),
            // not trimmed: leading/trailing spaces are legal in a Wi-Fi passphrase
            wifiPassword = merge(patch.wifiPassword, before.wifiPassword, "wifiPassword"),
            wifiSecurity = merge(
                patch.wifiSecurity?.let {
                    requireNotNull(WifiSecurity.normalize(it)) { "Wi-Fi security must be WPA, WEP or nopass" }
                },
                before.wifiSecurity, "wifiSecurity"),
            wifiHidden = merge(patch.wifiHidden, before.wifiHidden, "wifiHidden"),
        )
        require(next.serviceChargePercent in 0..30) { "service charge must be 0–30%" }
        require(next.corkagePerBottleCents in 0..10_000_00L) { "corkage out of range (max $10,000/bottle)" }
        // 12h is the absolute session cap, so an idle window past that is meaningless
        require(next.sessionIdleMinutes in 1..720) { "session idle timeout must be 1–720 minutes" }
        // escalation window: 10s floor (staff need a beat) up to 10min
        require(next.pendingAlertEscalateSeconds in 10..600) { "escalate-after must be 10–600 seconds" }
        require(next.pendingAlertVolume in 0..100) { "alert volume must be 0–100" }
        // Empty ip = "printer not configured" (prints are skipped silently). Any
        // non-empty value must be a plausible IPv4 / hostname so a typo can't
        // stall the print worker on a nonsense address.
        require(next.printerIp.isEmpty() || next.printerIp.matches(Regex("^[A-Za-z0-9.:-]{1,64}$"))) {
            "printer IP must be a hostname or IPv4 address"
        }
        require(next.printerPort in 1..65535) { "printer port must be 1–65535" }
        validateWifi(next)

        if (changed.isNotEmpty()) {
            VenueSettings.update({ VenueSettings.id eq 1 }) {
                it[cardProcessor] = next.cardProcessor
                it[bankName] = next.bankName
                it[bankAccountNumber] = next.bankAccountNumber
                it[bankAccountName] = next.bankAccountName
                it[serviceChargePercent] = next.serviceChargePercent
                it[corkagePerBottleCents] = next.corkagePerBottleCents
                it[receiptFooter] = next.receiptFooter
                it[venuePhone] = next.venuePhone
                it[venueAddress] = next.venueAddress
                it[sessionIdleMinutes] = next.sessionIdleMinutes
                it[pendingAlertsEnabled] = if (next.pendingAlertsEnabled) 1 else 0
                it[pendingAlertEscalateSeconds] = next.pendingAlertEscalateSeconds
                it[pendingAlertVolume] = next.pendingAlertVolume
                it[printerIp] = next.printerIp
                it[printerPort] = next.printerPort
                it[wifiSsid] = next.wifiSsid
                it[wifiPassword] = next.wifiPassword
                it[wifiSecurity] = next.wifiSecurity
                it[wifiHidden] = if (next.wifiHidden) 1 else 0
            }
            // Key NAMES only, never values: the guest Wi-Fi password (like every
            // other setting value) stays on the store and is never synced.
            Outbox.write("settings.updated", "settings", "venue", buildJsonObject {
                put("changedKeys", changed.joinToString(","))
            })
            cache = next
        }
        next
    }

    /** The guest Wi-Fi for join slips, or null while it isn't set up. */
    fun guestWifi(): GuestWifi? = get().let {
        GuestWifi(it.wifiSsid, it.wifiPassword, it.wifiSecurity, it.wifiHidden)
    }.takeIf { it.configured }

    private fun validateWifi(s: Settings) {
        fun printable(v: String) = v.none { it.isISOControl() }
        require(s.wifiSsid.toByteArray(Charsets.UTF_8).size <= 32) { "Wi-Fi network name must be at most 32 bytes" }
        require(s.wifiPassword.length <= 63) { "Wi-Fi password must be at most 63 characters" }
        require(printable(s.wifiSsid) && printable(s.wifiPassword)) {
            "Wi-Fi name and password can't contain control characters"
        }
        if (s.wifiSsid.isEmpty()) return // not configured: nothing else to check
        when (s.wifiSecurity) {
            WifiSecurity.WPA -> require(s.wifiPassword.length in 8..63) { "a WPA Wi-Fi password must be 8–63 characters" }
            WifiSecurity.WEP -> require(s.wifiPassword.isNotEmpty()) { "a WEP Wi-Fi network needs a password" }
        }
    }
}

/** Full settings row — also the GET /settings response DTO. */
@Serializable
data class Settings(
    val cardProcessor: String,
    val bankName: String,
    val bankAccountNumber: String,
    val bankAccountName: String,
    val serviceChargePercent: Int,
    val corkagePerBottleCents: Long,
    val receiptFooter: String,
    val venuePhone: String,
    val venueAddress: String,
    /** Sliding idle window (minutes): session dies this long after the last request. */
    val sessionIdleMinutes: Int,
    /** Master switch for pending-order chime + escalation alerts. */
    val pendingAlertsEnabled: Boolean,
    /** Seconds an un-actioned pending order may sit before the terminal escalates. */
    val pendingAlertEscalateSeconds: Int,
    /** Chime volume 0..100. */
    val pendingAlertVolume: Int,
    /** Network thermal receipt printer IP/hostname. Empty = not configured (prints skipped). */
    val printerIp: String,
    /** Printer raw-TCP port (JetDirect/RAW convention: 9100). */
    val printerPort: Int,
    /** Guest Wi-Fi network name for the join-QR slips. Empty = not configured. */
    val wifiSsid: String,
    /**
     * Guest Wi-Fi password. Only a manager's POS session reads it back (see
     * api/Settings.kt); it is never written to the outbox or synced.
     */
    val wifiPassword: String,
    /** WPA (default), WEP or nopass: the join QR's "T" value. */
    val wifiSecurity: String,
    /** Hidden (non-broadcast) network: adds H:true to the join QR. */
    val wifiHidden: Boolean,
)

/** PATCH /settings body — every field optional. */
@Serializable
data class SettingsPatch(
    val cardProcessor: String? = null,
    val bankName: String? = null,
    val bankAccountNumber: String? = null,
    val bankAccountName: String? = null,
    val serviceChargePercent: Int? = null,
    val corkagePerBottleCents: Long? = null,
    val receiptFooter: String? = null,
    val venuePhone: String? = null,
    val venueAddress: String? = null,
    val sessionIdleMinutes: Int? = null,
    val pendingAlertsEnabled: Boolean? = null,
    val pendingAlertEscalateSeconds: Int? = null,
    val pendingAlertVolume: Int? = null,
    val printerIp: String? = null,
    val printerPort: Int? = null,
    val wifiSsid: String? = null,
    val wifiPassword: String? = null,
    val wifiSecurity: String? = null,
    val wifiHidden: Boolean? = null,
)
