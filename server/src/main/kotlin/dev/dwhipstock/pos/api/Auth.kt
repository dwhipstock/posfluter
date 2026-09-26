package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.base.AuthService
import dev.dwhipstock.pos.base.AuthUser
import dev.dwhipstock.pos.base.DeviceRegistry
import dev.dwhipstock.pos.base.Users
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val SessionUserKey = AttributeKey<AuthUser>("sessionUser")

/** Thrown when an action needs an inline manager PIN and none/wrong was given. → 403 */
class ManagerApprovalException(message: String = "manager PIN required") :
    RuntimeException(message)

@Serializable
data class LoginRequest(val pin: String)

@Serializable
data class PreferencesRequest(val languageCode: String)

/** Older clients still send retired preference fields; accept and ignore them. */
private val preferencesJson = Json { ignoreUnknownKeys = true }

@Serializable
data class ChangePinRequest(val currentPin: String, val newPin: String)

@Serializable
data class VerifyManagerPinRequest(val pin: String, val permission: String? = null)

// --- Staff-app 2FA (M7): PIN + TOTP with a 90-day trusted device ---
@Serializable
data class StaffAppLoginRequest(
    val pin: String,
    /** Trusted-device tokens this phone holds (one per staff who cleared TOTP here,
     *  so a shared phone remembers each). `deviceToken` is the legacy single form. */
    val deviceTokens: List<String>? = null,
    val deviceToken: String? = null,
)

@Serializable
data class StaffAppTotpRequest(val pin: String, val code: String)

@Serializable
data class StaffTotpResetRequest(val managerPin: String? = null)

@Serializable
data class StaffAppLoginResponse(
    /** ok (device trusted) | totp (enter a code) | enroll (scan the QR, then a code) */
    val status: String,
    val user: AuthUser? = null,
    val otpauthUri: String? = null,
    val secret: String? = null,
    /** enroll only: a server-rendered QR of [otpauthUri] as a data URI (no client lib, no secret in a URL). */
    val qrDataUri: String? = null,
    val accountName: String? = null,
)

val PairedDeviceKey = AttributeKey<DeviceRegistry.PairedDevice>("pairedDevice")

/**
 * Bearer-token gate for the staff API. Customer-facing routes stay open on
 * purpose: the menu page, bill and pending-line submit under /m/t/{token}, and
 * the catalog read all run on guests' phones.
 *
 * M8 device layer, active when [requireDeviceToken] (cloud-hosted venues): the
 * TERMINAL surface additionally needs a paired-device token (X-Device-Token).
 * - /login and the GET /staff login tiles demand a live device up front, so an
 *   unpaired internet client can't even enumerate staff or try PINs;
 * - a session minted on a paired device is bound to it — every later call must
 *   present that same device's token, and a revoked device kills its sessions;
 * - staff-app phone sessions (TOTP 2FA) carry no device binding and are
 *   untouched, as are all customer-facing open routes.
 */
fun Application.installAuthGate(auth: AuthService, requireDeviceToken: Boolean = false) {
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        val method = call.request.httpMethod
        // a browser's CORS preflight carries no credentials by design (the POS
        // client in a browser — the web build — sends one before every call
        // with a JSON body or an Authorization header); the CORS plugin answers
        // it, and the real request that follows goes through the gate below
        if (method == HttpMethod.Options) return@intercept
        val presented = call.request.headers["X-Device-Token"]?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { DeviceRegistry.byToken(it) }
        if (presented != null && !presented.revoked) call.attributes.put(PairedDeviceKey, presented)

        if (requireDeviceToken && isTerminalPreAuthRoute(path, method)) {
            val device = call.attributes.getOrNull(PairedDeviceKey)
            if (device == null) {
                call.respond(HttpStatusCode.Unauthorized, deviceError(presented))
                return@intercept finish()
            }
        }
        if (isOpenRoute(path, method)) {
            call.attributes.getOrNull(PairedDeviceKey)?.let { DeviceRegistry.touch(it.id) }
            return@intercept
        }
        val token = call.request.headers[HttpHeaders.Authorization]
            ?.removePrefix("Bearer")?.trim()
        val user = token?.takeIf { it.isNotEmpty() }?.let { auth.me(it) }
        if (user == null) {
            // a revoked device outranks a dead session in the answer: the terminal
            // must wipe its pairing and re-pair, not bounce to the login screen
            call.respond(HttpStatusCode.Unauthorized,
                if (presented?.revoked == true) deviceError(presented)
                else mapOf("error" to "login required", "code" to "login_required"))
            return@intercept finish()
        }
        val boundDevice = user.deviceId
        val device = call.attributes.getOrNull(PairedDeviceKey)
        if (requireDeviceToken) {
            // On a cloud (device-gated) venue EVERY gated session must ride its own
            // live paired device. A staff-app 2FA session is device-unbound
            // (deviceId=null) — on the LAN that's fine, but over the internet it
            // would otherwise pass the gate with no terminal at all, so require the
            // matching device here (null boundDevice can never equal a device id →
            // staff-app sessions are refused on cloud venues, by design).
            if (device == null || device.id != boundDevice) {
                call.respond(HttpStatusCode.Unauthorized, deviceError(presented))
                return@intercept finish()
            }
        } else if (boundDevice != null) {
            // on-prem: only a device-bound session must keep proving it's on that device
            if (device == null || device.id != boundDevice) {
                call.respond(HttpStatusCode.Unauthorized, deviceError(presented))
                return@intercept finish()
            }
        }
        call.attributes.getOrNull(PairedDeviceKey)?.let { DeviceRegistry.touch(it.id) }
        call.attributes.put(SessionUserKey, user)
    }
}

/** device_revoked tells the terminal to wipe its pairing and re-pair; device_required just to pair. */
private fun deviceError(presented: DeviceRegistry.PairedDevice?): Map<String, String> =
    if (presented?.revoked == true)
        mapOf("error" to "this device's pairing was revoked", "code" to "device_revoked")
    else mapOf("error" to "a paired device is required", "code" to "device_required")

/**
 * Pre-auth surfaces the device gate must cover on a cloud venue: anything that
 * accepts a PIN or hands back a login/enrollment secret BEFORE a session exists.
 * Without this an unpaired internet client could hammer /staff-app/login to guess
 * PINs (tripping the shared rate limiter) and, for any not-yet-TOTP-enrolled
 * staff, be handed the TOTP enrollment secret — all with no paired terminal. On a
 * device-gated venue the staff-phone ordering app is intentionally unavailable
 * (it's a LAN feature; see the requireDeviceToken branch in the gate).
 */
private fun isTerminalPreAuthRoute(path: String, method: HttpMethod): Boolean =
    path == "/login" ||
        (method == HttpMethod.Get && path == "/staff") ||
        (method == HttpMethod.Post && (path == "/staff-app/login" || path == "/staff-app/totp"))

private fun isOpenRoute(path: String, method: HttpMethod): Boolean =
    path == "/" || path == "/health" || path == "/login" ||
        (method == HttpMethod.Post && path == "/pair") || // pairing IS the credential exchange (M8)
        // the staff ordering web app shell (M7): the page itself is public — it logs
        // in via the staff-app 2FA steps inside, then calls the gated API with the bearer
        (method == HttpMethod.Get && path == "/staff-app") ||
        // the LAN kitchen screen's page shell: signs in inside with the same
        // staff-app steps, then polls the gated /kitchen/board with the bearer
        (method == HttpMethod.Get && path == "/kitchen") ||
        // the brand's bundled font for that page (Sage & Poppy)
        (method == HttpMethod.Get && path.matches(Regex("/staff-app/fonts/[A-Za-z0-9-]+\\.ttf"))) ||
        // staff-app 2FA login steps run pre-auth, like /login (the bearer is issued by them)
        (method == HttpMethod.Post && (path == "/staff-app/login" || path == "/staff-app/totp")) ||
        path.startsWith("/m/") ||
        (method == HttpMethod.Get && path == "/items") ||
        (method == HttpMethod.Get && path == "/categories") ||
        (method == HttpMethod.Get && path == "/staff") ||
        (method == HttpMethod.Get && path.matches(Regex("/photos/[^/]+"))) ||
        // printable slip pages: opened in a browser with a short-lived ?ticket= (checked in the route)
        (method == HttpMethod.Get && path.matches(Regex("/tables/[^/]+/slip"))) ||
        (method == HttpMethod.Get && path == "/slips") ||
        // the built-in card terminal SIMULATOR's reader page and its buttons: the
        // customer side of a pretend reader, played from any browser on the LAN.
        // Mounted only when payment.terminal=simulator; it moves no real money and
        // the POS side (starting, recording, refunding a payment) stays gated.
        path == "/terminal" || path.startsWith("/terminal/ui/")

fun Route.authRoutes(auth: AuthService) {
    /** Open: the login screen shows staff tiles ("who's clocking in?"). Names only, no PINs. */
    get("/staff") {
        val staff = transaction {
            Users.selectAll()
                .where { (Users.active eq true) and Users.deletedAt.isNull() } // deactivated staff drop off the login screen
                .orderBy(Users.role) // MANAGER first
                .map { StaffDto(it[Users.id], it[Users.name], it[Users.role]) }
        }
        call.respond(staff)
    }

    post("/login") {
        val req = call.receive<LoginRequest>()
        // bind the session to the paired device presenting the login (M8); on
        // non-enforcing (on-prem) stores this stays null and nothing changes
        val user = auth.login(req.pin, call.attributes.getOrNull(PairedDeviceKey)?.id)
        if (user == null) {
            call.respond(HttpStatusCode.Unauthorized,
                mapOf("error" to "invalid PIN", "code" to "invalid_pin"))
        } else {
            call.respond(user)
        }
    }

    // --- Staff-app 2FA login (M7). Open (pre-auth) like /login; the Flutter
    // terminal keeps using PIN-only /login. Step 1 decides device-trust vs TOTP;
    // step 2 verifies the code and issues the session + a 90-day device token.
    post("/staff-app/login") {
        val req = call.receive<StaffAppLoginRequest>()
        val tokens = (req.deviceTokens ?: emptyList()) + listOfNotNull(req.deviceToken)
        val r = auth.staffAppBegin(req.pin, tokens)
            ?: return@post call.respond(HttpStatusCode.Unauthorized,
                mapOf("error" to "invalid PIN", "code" to "invalid_pin"))
        call.respond(StaffAppLoginResponse(
            status = r.status, user = r.user, otpauthUri = r.otpauthUri,
            secret = r.secret, accountName = r.accountName,
            qrDataUri = r.otpauthUri?.let(::qrDataUri)))
    }

    post("/staff-app/totp") {
        val req = call.receive<StaffAppTotpRequest>()
        // bad PIN → null → 401; bad code → BadRequestException(invalid_totp)
        val session = auth.staffAppTotp(req.pin, req.code)
            ?: return@post call.respond(HttpStatusCode.Unauthorized,
                mapOf("error" to "invalid PIN", "code" to "invalid_pin"))
        call.respond(session)
    }

    // Manager-gated reset for a lost authenticator (gated route — a session is
    // required; the acting manager holds manage_staff, else a manager PIN authorizes).
    post("/staff/{staffId}/totp/reset") {
        val staffId = call.parameters["staffId"]!!
        val req = call.receive<StaffTotpResetRequest>()
        requireGrant(auth, call, dev.dwhipstock.pos.base.Permissions.MANAGE_STAFF, req.managerPin)
        auth.resetTotp(staffId)
        call.respond(mapOf("ok" to "true"))
    }

    get("/me") { call.respond(call.attributes[SessionUserKey]) }

    patch("/me/preferences") {
        val req = preferencesJson.decodeFromString<PreferencesRequest>(call.receiveText())
        val user = call.sessionUser()
        auth.updatePreferences(user.userId, req.languageCode)
        call.respond(user.copy(languageCode = req.languageCode))
    }

    patch("/me/pin") {
        val req = call.receive<ChangePinRequest>()
        auth.changePin(call.sessionUser().userId, req.currentPin, req.newPin)
        call.respond(mapOf("ok" to "true"))
    }

    /**
     * Pre-flight for the manager-approval modal: verifies the PIN inline so a
     * typo is caught in the modal (clear + retry) instead of after the whole
     * flow. The action endpoint still re-verifies the PIN it receives.
     */
    post("/auth/verify-manager-pin") {
        val req = call.receive<VerifyManagerPinRequest>()
        // grant-aware when the action's permission is supplied (the approver must be
        // able to do the very thing being approved); else the legacy manager check.
        val approverId = if (req.permission != null)
            auth.verifyApproverPin(req.pin, req.permission) ?: throw ManagerApprovalException()
        else requireManagerApproval(auth, req.pin)
        call.respond(mapOf("approverId" to approverId))
    }

    post("/logout") {
        auth.logout(call.attributes[SessionUserKey].token)
        call.respond(mapOf("ok" to "true"))
    }
}

/** The logged-in user for this request (present on all gated routes). */
fun ApplicationCall.sessionUser(): AuthUser = attributes[SessionUserKey]

/** Verify an inline manager PIN; returns the approving manager's user id. */
fun requireManagerApproval(auth: AuthService, managerPin: String?): String =
    managerPin?.let { auth.verifyManagerPin(it) } ?: throw ManagerApprovalException()

/**
 * Grant-based gate (CONTRACT §7): if the acting (logged-in) user already has
 * [permission], allow directly and return their id; otherwise fall back to the
 * inline manager-PIN override — a PIN of anyone who has the permission — and
 * return the approver's id. Throws [ManagerApprovalException] (→ 403) when
 * neither holds. This is the single chokepoint the gated routes call.
 */
fun requireGrant(auth: AuthService, call: ApplicationCall, permission: String, managerPin: String?): String {
    val actor = call.sessionUser()
    if (auth.hasGrant(actor.userId, permission)) return actor.userId
    return auth.verifyApproverPin(managerPin, permission) ?: throw ManagerApprovalException()
}

/** Manager-session gate (settings, catalog editing): the whole screen is manager-only. */
fun requireManagerSession(call: ApplicationCall) {
    if (call.sessionUser().role != "MANAGER") throw ManagerApprovalException("manager session required")
}

/**
 * A QR PNG (data URI) for [payload] — server-rendered with zxing (already a dep,
 * used for table QRs), so the staff-app enrollment QR needs no client library and
 * the TOTP secret never travels in a URL that could be logged.
 */
private fun qrDataUri(payload: String): String {
    val matrix = com.google.zxing.MultiFormatWriter()
        .encode(payload, com.google.zxing.BarcodeFormat.QR_CODE, 240, 240)
    val png = dev.dwhipstock.pos.sdk.QrPng.encode(matrix)
    return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png)
}
