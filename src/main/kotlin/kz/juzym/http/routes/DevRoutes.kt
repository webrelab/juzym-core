package kz.juzym.http.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kz.juzym.app.ApplicationContext
import kz.juzym.dev.CapturedMailType
import kz.juzym.dev.CapturedMail
import kz.juzym.dev.DebugMailStore
import kz.juzym.dev.NoopDebugMailStore
import kz.juzym.config.Environment
import kz.juzym.user.User
import kz.juzym.user.UserStatus
import java.time.Instant
import java.util.UUID

fun Route.devRoutes(context: ApplicationContext) {
    if (context.config.environment != Environment.DEV && context.config.environment != Environment.TEST) {
        return
    }

    route("/dev") {
        if (context.debugMailStore !is NoopDebugMailStore) {
            mailRoutes(context.debugMailStore)
        }
        userRoutes(context)
    }
}

private fun Route.mailRoutes(store: DebugMailStore) {
    route("/mail") {
        get {
            val mails = store.list().map { it.toResponse() }
            call.respond(HttpStatusCode.OK, DebugMailListResponse(mails))
        }

        get("/latest") {
            val typeParam = call.request.queryParameters["type"]
            val toParam = call.request.queryParameters["to"]
            val type = typeParam?.let { runCatching { CapturedMailType.valueOf(it.uppercase()) }.getOrNull() }
                ?: typeParam?.let {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        DebugErrorResponse("invalid_type", "Unknown mail type: $it")
                    )
                    return@get
                }
            val latest = store.latest(type, toParam)
            if (latest == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    DebugErrorResponse("mail_not_found", "No captured mail found for provided filters")
                )
            } else {
                call.respond(HttpStatusCode.OK, latest.toResponse())
            }
        }

        delete {
            store.clear()
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun Route.userRoutes(context: ApplicationContext) {
    route("/users") {
        post {
            val payload = call.receive<DebugUserCreateRequest>()
            val status = payload.status?.let { runCatching { UserStatus.valueOf(it.uppercase()) }.getOrNull() }
                ?: UserStatus.ACTIVE

            val hashed = context.passwordHasher.hash(payload.password)
            val user = User(
                iin = payload.iin,
                email = payload.email,
                passwordHash = hashed,
                status = status,
                createdAt = Instant.now()
            )
            context.userRepository.create(user)
            call.respond(
                HttpStatusCode.Created,
                DebugUserResponse(
                    userId = user.id,
                    email = user.email,
                    iin = user.iin,
                    status = user.status.name
                )
            )
        }

        patch("/{userId}/status") {
            val userIdParam = call.parameters["userId"]
            val userId = runCatching { UUID.fromString(userIdParam) }.getOrNull()
            if (userId == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    DebugErrorResponse("invalid_user_id", "userId path parameter is invalid")
                )
                return@patch
            }

            val payload = call.receive<DebugUserStatusUpdateRequest>()
            val status = runCatching { UserStatus.valueOf(payload.status.uppercase()) }.getOrNull()
            if (status == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    DebugErrorResponse("invalid_status", "Unknown status ${payload.status}")
                )
                return@patch
            }

            context.userRepository.updateStatus(userId, status)
            call.respond(HttpStatusCode.OK, DebugUserStatusResponse(userId, status.name))
        }
    }
}

private fun CapturedMail.toResponse() = DebugMailResponse(
    id = id,
    type = type.name.lowercase(),
    to = to,
    link = link,
    createdAt = createdAt,
    metadata = metadata
)

data class DebugMailListResponse(val emails: List<DebugMailResponse>)

data class DebugMailResponse(
    val id: UUID,
    val type: String,
    val to: String,
    val link: String,
    val createdAt: Instant,
    val metadata: Map<String, String?>
)

data class DebugErrorResponse(val code: String, val message: String)

data class DebugUserCreateRequest(
    val iin: String,
    val email: String,
    val password: String,
    val status: String? = null
)

data class DebugUserResponse(
    val userId: UUID,
    val email: String,
    val iin: String,
    val status: String
)

data class DebugUserStatusUpdateRequest(val status: String)

data class DebugUserStatusResponse(val userId: UUID, val status: String)
