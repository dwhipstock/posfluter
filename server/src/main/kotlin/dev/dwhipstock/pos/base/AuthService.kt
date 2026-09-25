package dev.dwhipstock.pos.base

import dev.dwhipstock.pos.sdk.VenueClock

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.dwhipstock.pos.sdk.Outbox
import dev.dwhipstock.pos.sdk.i18n.Messages
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID

/**
 * PIN login → opaque session token. PINs are stored as BCrypt hashes; lookup
 * verifies against each user (staff counts are tiny). Sessions: absolute 12h +
 * sliding idle window (owner-tunable via venue settings, default 15min). A user
 * may hold several concurrent sessions — one per device/surface (POS terminal,
 * staff app, …), each with its own token and independent sliding/absolute
 * expiry; logging into one surface no longer kicks the others. The live count
 * is capped at [MAX_SESSIONS_PER_USER], evicting only the oldest beyond the cap.
 * Failed attempts are rate-limited terminal-wide (see [LoginRateLimiter]).
 */
class AuthService(
    private val settings: SettingsRepository? = null,
    private val staffAppMfaRequired: Boolean = true,
) {

    companion object {
        private const val BCRYPT_COST = 10 // 4-digit PINs: the rate limit is the real defense
        private val ABSOLUTE_HOURS = 12L
        // A user may be signed in on several surfaces at once (POS, staff app, …).
        // We don't evict on login; we only bound the table by trimming the oldest
        // live sessions past this cap so a device that never logs out can't grow it
        // without limit.
        private const val MAX_SESSIONS_PER_USER = 10
        // Staff-app 2FA: how long a device stays trusted (PIN-only) after clearing
        // TOTP, before the authenticator code is required again (M7, migration 025).
        private const val TRUST_DAYS = 90L
        // Fallback idle window when no settings repo is wired (e.g. a bare unit test).
        // The live value is venue_settings.session_idle_minutes; see idleMinutes().
        private const val DEFAULT_IDLE_MINUTES = 15L
        // Sliding-window touch granularity: last_used_at is written at most this
        // often per session. The idle window is minutes, so coarser touches lose
        // nothing — and every skipped write is one less writer on the hot path.
        private const val TOUCH_SECONDS = 60L

        fun hashPin(pin: String): String =
            BCrypt.withDefaults().hashToString(BCRYPT_COST, pin.toCharArray())

        fun verifyPin(pin: String, hash: String): Boolean =
            hash.startsWith("$2") &&
                BCrypt.verifyer().verify(pin.toCharArray(), hash.toCharArray()).verified

        /**
         * First-startup upgrade: any plaintext PIN left by a pre-M5 database is
         * hashed in place. Idempotent — hashed rows are recognized and skipped.
         */
        fun upgradePlaintextPins(): Unit = transaction {
            val plain = Users.selectAll().filterNot { it[Users.pin].startsWith("$2") }
            if (plain.isEmpty()) return@transaction
            plain.forEach { row ->
                Users.update({ Users.id eq row[Users.id] }) { it[pin] = hashPin(row[Users.pin]) }
            }
            Outbox.write("security.pins_hashed", "user", "*", buildJsonObject {
                put("count", plain.size)
            })
        }
    }

    private val rateLimiter = LoginRateLimiter()

    /** Live sliding-idle window in minutes: owner-tunable via venue settings. */
    private fun idleMinutes(): Long =
        settings?.get()?.sessionIdleMinutes?.toLong() ?: DEFAULT_IDLE_MINUTES

    fun login(pin: String, deviceId: String? = null): AuthUser? = transaction {
        rateLimiter.checkNotLocked()
        val user = activeUserByPin(pin)
        if (user == null) {
            rateLimiter.recordFailure()
            return@transaction null
        }
        rateLimiter.recordSuccess()
        issueSession(user, deviceId)
    }

    /** Active, non-deleted staff whose PIN matches (cloud-managed, CONTRACT §7). */
    private fun activeUserByPin(pin: String): ResultRow? =
        Users.selectAll().where { (Users.active eq true) and Users.deletedAt.isNull() }
            .firstOrNull { verifyPin(pin, it[Users.pin]) }

    /**
     * Mint a new session for [user] and return the AuthUser. Prior sessions stay
     * live — a user can be signed in on several surfaces at once. We only bound the
     * table: before adding this one, trim the user's live sessions down to
     * [MAX_SESSIONS_PER_USER] − 1 by revoking the oldest, so the fresh session
     * brings the total to at most the cap. (Trimming before the insert keeps the
     * new session out of the eviction set.)
     */
    private fun issueSession(user: ResultRow, deviceId: String? = null): AuthUser {
        val now = VenueClock.now()
        val uid = user[Users.id]
        val live = Sessions.selectAll()
            .where { (Sessions.userId eq uid) and Sessions.revokedAt.isNull() }
            .orderBy(Sessions.createdAt to SortOrder.DESC)
            .map { it[Sessions.token] }
        val evicted = live.drop(MAX_SESSIONS_PER_USER - 1) // oldest beyond the cap
        if (evicted.isNotEmpty()) {
            Sessions.update({ Sessions.token inList evicted }) { it[revokedAt] = now }
        }
        val token = UUID.randomUUID().toString()
        Sessions.insert {
            it[Sessions.token] = token
            it[userId] = uid
            it[createdAt] = now
            it[expiresAt] = now.plusHours(ABSOLUTE_HOURS)
            it[lastUsedAt] = now
            it[Sessions.deviceId] = deviceId // paired terminal (M8); null = staff-app phone
        }
        Outbox.write("auth.login", "user", uid, buildJsonObject {
            put("userId", uid)
            put("role", user[Users.role])
            if (evicted.isNotEmpty()) put("evictedSessions", evicted.size)
        })
        return toAuthUser(token, user, deviceId)
    }

    // --- Staff-app 2FA (M7): TOTP + 90-day trusted device, verified offline -----

    /**
     * Staff-app login step 1: verify the PIN, then choose the path. Returns null
     * on a bad PIN (→ 401). Otherwise the status is one of:
     *   - "ok"     : this device is still trusted → a session is issued, PIN only.
     *   - "totp"   : enrolled but this device isn't trusted → the app collects a code.
     *   - "enroll" : no activated authenticator yet → returns the otpauth URI + secret
     *                for the QR; the app collects the first code to activate.
     */
    fun staffAppBegin(pin: String, deviceTokens: List<String>): StaffAppBegin? = transaction {
        rateLimiter.checkNotLocked()
        val user = activeUserByPin(pin)
        if (user == null) { rateLimiter.recordFailure(); return@transaction null }
        val uid = user[Users.id]

        // Only an explicitly packaged demo build may set this false. Keep the
        // PIN check, rate limit, and normal session expiry; leave enrolled TOTP
        // data intact so a later MFA-on build requires it at the next login.
        if (!staffAppMfaRequired) {
            rateLimiter.recordSuccess()
            return@transaction StaffAppBegin("ok", user = issueSession(user))
        }

        // A still-trusted device completes the login now (PIN only). Clearing the
        // failure counter is correct HERE because authentication is complete.
        if (deviceTokens.any { consumeTrustedDevice(uid, it) }) {
            rateLimiter.recordSuccess()
            return@transaction StaffAppBegin("ok", user = issueSession(user))
        }

        // The PIN is correct but a TOTP factor is still required — deliberately do
        // NOT recordSuccess() here. A valid PIN alone must not clear the shared
        // brute-force counter, or someone who knows the PIN could reset the TOTP
        // lockout between code guesses and brute-force the second factor.
        val existing = StaffTotp.selectAll().where { StaffTotp.userId eq uid }.firstOrNull()
        if (existing != null && existing[StaffTotp.activatedAt] != null)
            return@transaction StaffAppBegin("totp")

        // not yet enrolled: reuse the pending secret if one exists (stable QR) else mint one
        val secret = existing?.get(StaffTotp.secret) ?: Totp.newSecret().also { s ->
            StaffTotp.insert {
                it[userId] = uid
                it[StaffTotp.secret] = s
                it[createdAt] = VenueClock.now()
            }
        }
        StaffAppBegin("enroll",
            otpauthUri = Totp.otpauthUri("Copper Lantern POS", user[Users.name], secret),
            secret = secret, accountName = user[Users.name])
    }

    /**
     * Staff-app login step 2: verify PIN + authenticator code, activate the secret
     * on first success, then issue a session and a ~90-day trusted-device token for
     * THIS device. Returns null on a bad PIN (→ 401); throws (invalid_totp) on a bad
     * code so the app keeps the code field for a retry.
     */
    fun staffAppTotp(pin: String, code: String): StaffAppSession? = transaction {
        rateLimiter.checkNotLocked()
        val user = activeUserByPin(pin)
        if (user == null) { rateLimiter.recordFailure(); return@transaction null }
        val uid = user[Users.id]
        val row = StaffTotp.selectAll().where { StaffTotp.userId eq uid }.firstOrNull()
        val step = row?.let { Totp.matchingCounter(it[StaffTotp.secret], code) }
        val lastStep = row?.get(StaffTotp.lastStep)
        // wrong/absent code, or a code from a 30s step already consumed (single-use → no replay)
        if (row == null || step == null || (lastStep != null && step <= lastStep)) {
            rateLimiter.recordFailure()
            throw dev.dwhipstock.pos.restaurant.BadRequestException("invalid authenticator code", "invalid_totp")
        }
        rateLimiter.recordSuccess()
        StaffTotp.update({ StaffTotp.userId eq uid }) {
            it[StaffTotp.lastStep] = step // replay guard: this 30s window can't be reused
            if (row[StaffTotp.activatedAt] == null) it[activatedAt] = VenueClock.now()
        }
        val authUser = issueSession(user)
        val now = VenueClock.now()
        val expires = now.plusDays(TRUST_DAYS)
        val devToken = UUID.randomUUID().toString()
        TrustedDevices.insert {
            it[token] = devToken
            it[userId] = uid
            it[createdAt] = now
            it[expiresAt] = expires
        }
        StaffAppSession(authUser, devToken, expires.toString())
    }

    /** True if [token] is a live (unexpired) trusted device for [userId]; touches it. */
    private fun consumeTrustedDevice(userId: String, token: String): Boolean {
        val row = TrustedDevices.selectAll()
            .where { (TrustedDevices.token eq token) and (TrustedDevices.userId eq userId) }
            .firstOrNull() ?: return false
        if (!row[TrustedDevices.expiresAt].isAfter(VenueClock.now())) return false
        TrustedDevices.update({ TrustedDevices.token eq token }) { it[lastUsedAt] = VenueClock.now() }
        return true
    }

    /** Manager reset (lost authenticator): drop the secret + every trusted device so
     *  the staff member re-enrolls on their next staff-app login. */
    fun resetTotp(userId: String): Unit = transaction {
        StaffTotp.deleteWhere { StaffTotp.userId eq userId }
        TrustedDevices.deleteWhere { TrustedDevices.userId eq userId }
        Outbox.write("staff.totp_reset", "user", userId, buildJsonObject { put("userId", userId) })
    }

    /**
     * Resolve a bearer token; null = invalid/revoked/expired/deactivated. Refreshes the sliding window.
     *
     * The lookup transaction is deliberately read-only. It used to UPDATE
     * last_used_at in place, which upgrades SQLite's read snapshot to a write —
     * and that upgrade fails with SQLITE_BUSY (immediately, busy_timeout does
     * not apply) whenever any other writer committed since the snapshot began.
     * Since this ran on EVERY authed request, a few polling terminals were
     * enough to turn plain GETs into intermittent 500s. The sliding-window
     * touch now happens after the read commits, in its own short write
     * transaction, throttled to once per [TOUCH_SECONDS] per session, and
     * best-effort: a lost touch just refreshes on a later request.
     * See docs/known-issues/sqlite-busy-session-touch.md.
     */
    fun me(token: String): AuthUser? {
        var expired = false
        var touchDue = false
        val user = transaction {
            // deactivated / soft-deleted staff (cloud-managed, CONTRACT §7) stop resolving
            // immediately — a live session must not outlive the account it belongs to
            val row = Sessions.join(Users, JoinType.INNER, Sessions.userId, Users.id)
                .selectAll()
                .where {
                    (Sessions.token eq token) and Sessions.revokedAt.isNull() and
                        (Users.active eq true) and Users.deletedAt.isNull()
                }
                .firstOrNull() ?: return@transaction null

            val now = VenueClock.now()
            val absolute = row[Sessions.expiresAt] ?: row[Sessions.createdAt].plusHours(ABSOLUTE_HOURS)
            val lastUsed = row[Sessions.lastUsedAt] ?: row[Sessions.createdAt]
            if (now.isAfter(absolute) || now.isAfter(lastUsed.plusMinutes(idleMinutes()))) {
                expired = true
                return@transaction null // → 401 → client re-login
            }
            touchDue = lastUsed.isBefore(now.minusSeconds(TOUCH_SECONDS))
            toAuthUser(token, row, row[Sessions.deviceId])
        }
        // Both writes are safe to lose: an unrevoked expired session still answers
        // null here on every later call, and a missed touch is the next one's job.
        if (expired) runCatching {
            transaction { Sessions.update({ Sessions.token eq token }) { it[revokedAt] = VenueClock.now() } }
        } else if (user != null && touchDue) runCatching {
            transaction { Sessions.update({ Sessions.token eq token }) { it[lastUsedAt] = VenueClock.now() } }
        }
        return user
    }

    fun logout(token: String) = transaction {
        val user = me(token) ?: return@transaction
        Sessions.update({ Sessions.token eq token }) { it[revokedAt] = VenueClock.now() }
        Outbox.write("auth.logout", "user", user.userId, buildJsonObject { put("userId", user.userId) })
    }

    /** Per-user display preferences (UI language + calendar era). */
    fun updatePreferences(userId: String, languageCode: String, calendar: String): Unit = transaction {
        // valid languages = whichever message catalogs are on the classpath
        require(languageCode in Messages.supportedTags()) {
            "languageCode must be one of ${Messages.supportedTags().sorted().joinToString(", ")}"
        }
        // Users.language_code is varchar(8): a longer tag would die inside the
        // UPDATE with an opaque error, so refuse it cleanly here
        require(languageCode.length <= 8) { "languageCode longer than 8 chars cannot be stored" }
        require(calendar == "CE") { "calendar must use the Gregorian CE era" }
        Users.update({ Users.id eq userId }) {
            it[Users.languageCode] = languageCode
            it[Users.calendar] = calendar
        }
        Outbox.write("user.preferences_changed", "user", userId, buildJsonObject {
            put("userId", userId)
            put("languageCode", languageCode)
            put("calendar", calendar)
        })
    }

    /** Staff change their own PIN; current PIN re-verified, attempts rate-limited. */
    fun changePin(userId: String, currentPin: String, newPin: String): Unit = transaction {
        require(newPin.length == 4 && newPin.all { it.isDigit() }) { "PIN must be 4 digits" }
        rateLimiter.checkNotLocked()
        val user = Users.selectAll().where { Users.id eq userId }.first()
        if (!verifyPin(currentPin, user[Users.pin])) {
            rateLimiter.recordFailure()
            throw IllegalArgumentException("current PIN is incorrect")
        }
        rateLimiter.recordSuccess()
        Users.update({ Users.id eq userId }) { it[pin] = hashPin(newPin) }
        Outbox.write("user.pin_changed", "user", userId, buildJsonObject {
            put("userId", userId)
        })
    }

    /**
     * Inline manager approval: a manager taps their PIN into the modal on the
     * staff member's screen. Returns the approver's user id, or null.
     * Shares the terminal-wide rate limit with login.
     */
    fun verifyManagerPin(pin: String): String? = transaction {
        rateLimiter.checkNotLocked()
        val manager = Users.selectAll()
            .where { (Users.role eq "MANAGER") and (Users.active eq true) and Users.deletedAt.isNull() }
            .firstOrNull { verifyPin(pin, it[Users.pin]) }
        if (manager == null) rateLimiter.recordFailure() else rateLimiter.recordSuccess()
        manager?.get(Users.id)
    }

    /**
     * Grant-aware inline approval (CONTRACT §7): the entered PIN must belong to an
     * active staff member who has [permission] effectively granted (a manager by
     * default, but the owner can revoke or grant it). Returns the approver's id or
     * null. Shares the terminal-wide rate limit with login.
     */
    fun verifyApproverPin(pin: String?, permission: String): String? = transaction {
        if (pin == null) return@transaction null
        rateLimiter.checkNotLocked()
        val approver = Users.selectAll()
            .where { (Users.active eq true) and Users.deletedAt.isNull() }
            .filter { verifyPin(pin, it[Users.pin]) }
            .firstOrNull { GrantsRepo.has(it[Users.id], permission) }
        if (approver == null) rateLimiter.recordFailure() else rateLimiter.recordSuccess()
        approver?.get(Users.id)
    }

    /** True if this staff member effectively has [permission]. */
    fun hasGrant(userId: String, permission: String): Boolean = transaction {
        GrantsRepo.has(userId, permission)
    }

    private fun toAuthUser(token: String, row: ResultRow, deviceId: String? = null) = AuthUser(
        token, row[Users.id], row[Users.name], row[Users.role],
        row[Users.languageCode], row[Users.calendar],
        grants = GrantsRepo.effectiveGrants(row[Users.id]),
        deviceId = deviceId,
    )
}

@Serializable
data class AuthUser(
    val token: String, val userId: String, val name: String, val role: String,
    val languageCode: String = "en", val calendar: String = "CE",
    /** The staff member's effective grants (CONTRACT §7) — the POS uses these to
     *  skip the manager-PIN prompt for actions the user is already allowed. */
    val grants: List<String> = emptyList(),
    /** Paired terminal this session is bound to (M8); null = staff-app phone session. */
    val deviceId: String? = null,
)

/**
 * Result of staff-app login step 1. `status`: "ok" (device trusted, `user` set),
 * "totp" (enrolled, need a code), or "enroll" (`otpauthUri`/`secret` set for the
 * first-time authenticator QR). Not serialized directly — the api layer maps it
 * to a response that also carries a server-rendered QR image.
 */
data class StaffAppBegin(
    val status: String,
    val user: AuthUser? = null,
    val otpauthUri: String? = null,
    val secret: String? = null,
    val accountName: String? = null,
)

/** Result of staff-app login step 2: a live session plus a ~90-day trusted-device token. */
@Serializable
data class StaffAppSession(val user: AuthUser, val deviceToken: String, val deviceExpiresAt: String)
