package dev.dwhipstock.pos.api

import dev.dwhipstock.pos.base.AuthService
import dev.dwhipstock.pos.base.Permissions
import dev.dwhipstock.pos.base.StaffAdmin
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class StaffCreateRequest(val name: String, val role: String, val pin: String)

@Serializable
data class StaffPatchRequest(val name: String? = null, val role: String? = null, val active: Boolean? = null)

@Serializable
data class StaffPinRequest(val pin: String)

@Serializable
data class StaffOverridesRequest(val overrides: Map<String, Boolean>)

@Serializable
data class RoleGrantsRequest(val roles: Map<String, Map<String, Boolean>>)

/**
 * Staff administration on the tablet (one-way sync: the tablet owns staff and
 * pushes snapshots up for the portal to display). Every route needs a session
 * whose user effectively holds `manage_staff` — works fully offline.
 */
fun Route.staffAdminRoutes(auth: AuthService) {
    route("/staff/manage") {
        get {
            requireGrant(auth, call, Permissions.MANAGE_STAFF, null)
            call.respond(StaffAdmin.list())
        }
        post {
            requireGrant(auth, call, Permissions.MANAGE_STAFF, null)
            val req = call.receive<StaffCreateRequest>()
            call.respond(HttpStatusCode.Created, StaffAdmin.create(req.name, req.role, req.pin))
        }
        patch("/{id}") {
            requireGrant(auth, call, Permissions.MANAGE_STAFF, null)
            val req = call.receive<StaffPatchRequest>()
            call.respond(StaffAdmin.update(call.parameters["id"]!!, req.name, req.role, req.active))
        }
        post("/{id}/pin") {
            requireGrant(auth, call, Permissions.MANAGE_STAFF, null)
            StaffAdmin.resetPin(call.parameters["id"]!!, call.receive<StaffPinRequest>().pin)
            call.respond(mapOf("ok" to "true"))
        }
        put("/{id}/grants") {
            requireGrant(auth, call, Permissions.MANAGE_STAFF, null)
            val req = call.receive<StaffOverridesRequest>()
            call.respond(StaffAdmin.setOverrides(call.parameters["id"]!!, req.overrides))
        }
        delete("/{id}") {
            requireGrant(auth, call, Permissions.MANAGE_STAFF, null)
            StaffAdmin.delete(call.parameters["id"]!!)
            call.respond(mapOf("ok" to "true"))
        }
    }
    put("/roles/grants") {
        requireGrant(auth, call, Permissions.MANAGE_STAFF, null)
        call.respond(StaffAdmin.setRoleGrants(call.receive<RoleGrantsRequest>().roles))
    }
}
