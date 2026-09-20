package dev.dwhipstock.poscloud.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.dwhipstock.poscloud.CloudConfig
import dev.dwhipstock.poscloud.RateLimitException
import dev.dwhipstock.poscloud.UnauthorizedException
import dev.dwhipstock.poscloud.db.LoginPending
import dev.dwhipstock.poscloud.db.PortalBackupCodes
import dev.dwhipstock.poscloud.db.PortalSessions
import dev.dwhipstock.poscloud.db.PortalUsers
import dev.dwhipstock.poscloud.db.Venues
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDateTime

/**
 * Portal auth: email+password → mandatory TOTP → opaque session cookie.
 * Tokens (session + pending) are 32 random bytes hex; only their SHA-256 is
 * stored, so a DB leak doesn't leak live sessions. Sessions: 30-day absolute
 * expiry + last-used touch. Pending tokens: 5 min, single-use.
 */

const val SESSION_COOKIE = "pos_portal_session"
private const val BCRYPT_COST = 12
private val SESSION_DAYS = 30L
private val PENDING_MINUTES = 5L

data class Principal(val tenantId: String, val userId: Long, val email: String, val displayName: String)

fun hashPassword(password: String): String =
    BCrypt.withDefaults().hashToString(BCRYPT_COST, password.toCharArray())

fun verifyPassword(password: String, hash: String): Boolean =
    hash.startsWith("$2") && BCrypt.verifyer().verify(password.toCharArray(), hash.toCharArray()).verified

fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

private val tokenRandom = SecureRandom()

fun newToken(): String = ByteArray(32).also { tokenRandom.nextBytes(it) }
    .joinToString("") { "%02x".format(it) }

/**
 * One-time TOTP recovery codes. The single owner must survive a lost/wiped phone,
 * so enrollment hands out [COUNT] of these; each is single-use. Alphabet drops the
 * ambiguous 0/O/1/I/L so a code copied off a screen can't be mistyped. Matching is
 * case- and separator-insensitive: [normalize] is what we hash, on both ends.
 */
object BackupCodes {
    const val COUNT = 10
    private const val LEN = 10
    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
    private val random = SecureRandom()

    /** Fresh plaintext codes for one-time display, grouped `xxxxx-xxxxx` for reading. */
    fun generate(): List<String> = List(COUNT) {
        val raw = (0 until LEN).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        raw.chunked(5).joinToString("-")
    }

    /** Strip separators/case so "ABCDE-FGHJK", "abcdefghjk" etc. all hash the same. */
    fun normalize(code: String): String = code.lowercase().filter { it in ALPHABET }

    /** Length of a real code once normalized — used to skip the lookup for short TOTP entries. */
    val normalizedLength get() = LEN
}

/** Resolve the session cookie or throw 401. Touches last_used_at. */
fun requirePortal(call: ApplicationCall): Principal {
    val token = call.request.cookies[SESSION_COOKIE] ?: throw UnauthorizedException()
    return transaction {
        val hash = sha256Hex(token)
        val row = PortalSessions.selectAll().where { PortalSessions.tokenSha256 eq hash }.firstOrNull()
            ?: throw UnauthorizedException()
        val now = LocalDateTime.now()
        if (now.isAfter(row[PortalSessions.expiresAt])) {
            PortalSessions.deleteWhere { tokenSha256 eq hash }
            throw UnauthorizedException()
        }
        PortalSessions.update({ PortalSessions.tokenSha256 eq hash }) { it[lastUsedAt] = now }
        val user = PortalUsers.selectAll().where { PortalUsers.id eq row[PortalSessions.userId] }.firstOrNull()
            ?: throw UnauthorizedException()
        Principal(
            row[PortalSessions.tenantId], user[PortalUsers.id],
            user[PortalUsers.email], user[PortalUsers.displayName],
        )
    }
}

/** In-memory login throttle: 10 attempts per rolling minute per email+IP. */
internal object LoginRateLimiter {
    private const val LIMIT = 10
    private const val WINDOW_MS = 60_000L
    // Hard ceiling on tracked keys so the unauthenticated /auth/login path can't be
    // grown without bound by an attacker cycling distinct email+IP keys.
    private const val MAX_KEYS = 10_000
    private val attempts = mutableMapOf<String, MutableList<Long>>()

    /** Live key count — exposed for the boundedness regression test. */
    internal val keyCount: Int @Synchronized get() = attempts.size

    /** Test hook: drop all tracked keys so the shared singleton starts clean. */
    @Synchronized
    internal fun reset() = attempts.clear()

    @Synchronized
    fun record(key: String, limit: Int = LIMIT, nowMs: Long = System.currentTimeMillis()) {
        val cutoff = nowMs - WINDOW_MS
        // Prune every bucket and drop keys that have gone empty (all attempts aged out),
        // so distinct-key traffic is reclaimed rather than accumulating forever. Cheap
        // in the common case (few active keys); the hard cap backstops a fresh-key burst
        // that hasn't had time to age out yet — the window is a minute, so dropping the
        // map just hands honest callers a fresh one.
        attempts.entries.removeAll { (_, times) ->
            times.removeAll { it < cutoff }
            times.isEmpty()
        }
        if (attempts.size > MAX_KEYS) attempts.clear()
        val window = attempts.getOrPut(key) { mutableListOf() }
        if (window.size >= limit) throw RateLimitException()
        window += nowMs
    }
}

/**
 * A pending token is worth at most [LIMIT] TOTP guesses, then it's dead —
 * back to the password step, which is itself rate-limited. Without this a
 * stolen password buys 5 minutes of unthrottled 6-digit guessing. The block
 * lives in memory ONLY: a DB-row delete would roll back with the 401's
 * transaction, and the token expires in 5 minutes regardless.
 */
private object TotpAttempts {
    private const val LIMIT = 5
    private val counts = mutableMapOf<String, Int>()

    @Synchronized
    fun recordFailure(tokenHash: String) {
        if (counts.size > 10_000) counts.clear() // bounded; tokens expire in 5 min anyway
        counts[tokenHash] = (counts[tokenHash] ?: 0) + 1
    }

    @Synchronized
    fun blocked(tokenHash: String): Boolean = (counts[tokenHash] ?: 0) >= LIMIT

    @Synchronized
    fun clear(tokenHash: String) { counts.remove(tokenHash) }
}

// timing pad: unknown emails must cost the same bcrypt work as wrong passwords
private val dummyHash = hashPassword("timing-pad-${System.nanoTime()}")

@Serializable
private data class LoginRequest(val email: String, val password: String)

@Serializable
private data class LoginResponse(
    val stage: String, val pendingToken: String,
    val secret: String? = null, val otpauthUri: String? = null)

@Serializable
private data class TotpRequest(val pendingToken: String, val code: String)

@Serializable
private data class OkResponse(val ok: Boolean = true)

@Serializable
private data class ConfirmResponse(val ok: Boolean = true, val backupCodes: List<String>)

@Serializable
private data class MeResponse(val email: String, val displayName: String, val venueName: String)

fun Route.authRoutes(config: CloudConfig) {

    post("/auth/login") {
        val req = call.receive<LoginRequest>()
        LoginRateLimiter.record("${req.email.lowercase()}|${call.request.origin.remoteHost}")
        val response = transaction {
            // email is unique per tenant, not globally: pick the row the password verifies against
            val candidates = PortalUsers.selectAll().where { PortalUsers.email eq req.email }
                .orderBy(PortalUsers.id).toList()
            if (candidates.isEmpty()) verifyPassword(req.password, dummyHash)
            val user = candidates.firstOrNull { verifyPassword(req.password, it[PortalUsers.passwordHash]) }
                ?: throw UnauthorizedException("wrong email or password", "bad_credentials")
            val now = LocalDateTime.now()
            val token = newToken()
            if (user[PortalUsers.totpEnabled]) {
                insertPending(token, user, "totp", user[PortalUsers.totpSecret]!!, now)
                LoginResponse(stage = "totp", pendingToken = token)
            } else {
                val secret = Totp.newSecret()
                insertPending(token, user, "totp_setup", secret, now)
                val issuer = venueNameOf(user[PortalUsers.tenantId]) ?: config.venueName
                LoginResponse(
                    stage = "totp_setup", pendingToken = token, secret = secret,
                    otpauthUri = Totp.otpauthUri(issuer, user[PortalUsers.email], secret),
                )
            }
        }
        call.respond(response)
    }

    post("/auth/totp") {
        val req = call.receive<TotpRequest>()
        LoginRateLimiter.record("totp|${call.request.origin.remoteHost}", limit = 30)
        val token = transaction {
            val pending = consumablePending(req.pendingToken, "totp")
            // A 6-digit authenticator code, OR — if the phone is gone — a one-time
            // backup code. isDigit() filter tolerates a pasted "123 456".
            val ok = Totp.verify(pending[LoginPending.secret], req.code.filter { it.isDigit() }) ||
                consumeBackupCode(pending[LoginPending.tenantId], pending[LoginPending.userId], req.code)
            if (!ok) {
                failTotpAttempt(pending)
                throw UnauthorizedException("wrong code", "bad_totp")
            }
            TotpAttempts.clear(pending[LoginPending.tokenSha256])
            LoginPending.deleteWhere { tokenSha256 eq pending[LoginPending.tokenSha256] }
            createSession(pending[LoginPending.tenantId], pending[LoginPending.userId])
        }
        call.setSessionCookie(token, config)
        call.respond(OkResponse())
    }

    post("/auth/totp/confirm") {
        val req = call.receive<TotpRequest>()
        LoginRateLimiter.record("totp|${call.request.origin.remoteHost}", limit = 30)
        val (token, backupCodes) = transaction {
            val pending = consumablePending(req.pendingToken, "totp_setup")
            val secret = pending[LoginPending.secret]
            if (!Totp.verify(secret, req.code.filter { it.isDigit() })) {
                failTotpAttempt(pending)
                throw UnauthorizedException("wrong code", "bad_totp")
            }
            TotpAttempts.clear(pending[LoginPending.tokenSha256])
            LoginPending.deleteWhere { tokenSha256 eq pending[LoginPending.tokenSha256] }
            val now = LocalDateTime.now()
            PortalUsers.update({ PortalUsers.id eq pending[LoginPending.userId] }) {
                it[totpSecret] = secret
                it[totpEnabled] = true
            }
            val codes = issueBackupCodes(pending[LoginPending.tenantId], pending[LoginPending.userId], now)
            createSession(pending[LoginPending.tenantId], pending[LoginPending.userId]) to codes
        }
        call.setSessionCookie(token, config)
        call.respond(ConfirmResponse(backupCodes = backupCodes))
    }

    post("/auth/logout") {
        call.request.cookies[SESSION_COOKIE]?.let { token ->
            transaction { PortalSessions.deleteWhere { tokenSha256 eq sha256Hex(token) } }
        }
        call.response.cookies.append(
            Cookie(SESSION_COOKIE, "", maxAge = 0, path = "/", httpOnly = true, secure = config.cookieSecure)
        )
        call.respond(OkResponse())
    }

    get("/auth/me") {
        val principal = requirePortal(call)
        val venueName = transaction { venueNameOf(principal.tenantId) } ?: config.venueName
        call.respond(MeResponse(principal.email, principal.displayName, venueName))
    }
}

private fun ApplicationCall.setSessionCookie(token: String, config: CloudConfig) {
    response.cookies.append(
        Cookie(
            SESSION_COOKIE, token, maxAge = (SESSION_DAYS * 24 * 3600).toInt(), path = "/",
            httpOnly = true, secure = config.cookieSecure,
            extensions = mapOf("SameSite" to "Lax"),
        )
    )
}

// --- helpers (call inside a transaction) ---

fun venueNameOf(tenantId: String): String? =
    Venues.selectAll().where { Venues.tenantId eq tenantId }
        .orderBy(Venues.id).firstOrNull()?.get(Venues.name)

private fun insertPending(
    token: String, user: org.jetbrains.exposed.sql.ResultRow, pendingPurpose: String,
    pendingSecret: String, now: LocalDateTime,
) {
    LoginPending.insert {
        it[tokenSha256] = sha256Hex(token)
        it[tenantId] = user[PortalUsers.tenantId]
        it[userId] = user[PortalUsers.id]
        it[purpose] = pendingPurpose
        it[secret] = pendingSecret
        it[createdAt] = now
        it[expiresAt] = now.plusMinutes(PENDING_MINUTES)
    }
}

/** Wrong code: spend one unit of the token's guess budget. */
private fun failTotpAttempt(pending: org.jetbrains.exposed.sql.ResultRow) {
    TotpAttempts.recordFailure(pending[LoginPending.tokenSha256])
}

/**
 * Replace this user's backup codes with a fresh set (re-enrollment invalidates the
 * old ones) and return the plaintext for one-time display. Only the SHA-256 is kept.
 */
private fun issueBackupCodes(tenant: String, userId: Long, now: LocalDateTime): List<String> {
    PortalBackupCodes.deleteWhere {
        (PortalBackupCodes.tenantId eq tenant) and (PortalBackupCodes.userId eq userId)
    }
    val codes = BackupCodes.generate()
    codes.forEach { code ->
        PortalBackupCodes.insert {
            it[tenantId] = tenant
            it[PortalBackupCodes.userId] = userId
            it[codeSha256] = sha256Hex(BackupCodes.normalize(code))
            it[createdAt] = now
        }
    }
    return codes
}

/** Burn a matching unused backup code for this user; true iff one was spent. */
private fun consumeBackupCode(tenant: String, userId: Long, entered: String): Boolean {
    val normalized = BackupCodes.normalize(entered)
    if (normalized.length != BackupCodes.normalizedLength) return false // not a backup code
    val hash = sha256Hex(normalized)
    val spent = PortalBackupCodes.update({
        (PortalBackupCodes.tenantId eq tenant) and (PortalBackupCodes.userId eq userId) and
            (PortalBackupCodes.codeSha256 eq hash) and PortalBackupCodes.usedAt.isNull()
    }) { it[usedAt] = LocalDateTime.now() }
    return spent > 0
}

private fun consumablePending(token: String, expectedPurpose: String): org.jetbrains.exposed.sql.ResultRow {
    val row = LoginPending.selectAll().where {
        (LoginPending.tokenSha256 eq sha256Hex(token)) and (LoginPending.purpose eq expectedPurpose)
    }.firstOrNull() ?: throw UnauthorizedException("unknown or used login token", "bad_pending_token")
    if (LocalDateTime.now().isAfter(row[LoginPending.expiresAt])) {
        LoginPending.deleteWhere { tokenSha256 eq row[LoginPending.tokenSha256] }
        throw UnauthorizedException("login token expired", "bad_pending_token")
    }
    if (TotpAttempts.blocked(row[LoginPending.tokenSha256]))
        throw UnauthorizedException("too many wrong codes; sign in again", "bad_pending_token")
    return row
}

private fun createSession(tenant: String, user: Long): String {
    val token = newToken()
    val now = LocalDateTime.now()
    PortalSessions.insert {
        it[tokenSha256] = sha256Hex(token)
        it[tenantId] = tenant
        it[userId] = user
        it[createdAt] = now
        it[expiresAt] = now.plusDays(SESSION_DAYS)
        it[lastUsedAt] = now
    }
    return token
}
