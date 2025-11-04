package kz.juzym.http.routes

import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.util.pipeline.PipelineContext
import kz.juzym.app.ApplicationContext
import kz.juzym.audit.SecurityContext
import kz.juzym.auth.AuthErrorBody
import kz.juzym.auth.AuthErrorResponse
import kz.juzym.auth.AuthException
import kz.juzym.auth.AuthMetadata
import kz.juzym.auth.EmailChangeConfirmationRequest
import kz.juzym.auth.EmailChangeConfirmationResponse
import kz.juzym.auth.EmailChangeRequest
import kz.juzym.auth.EmailChangeRequestResponse
import kz.juzym.auth.InvalidPayloadException
import kz.juzym.auth.InvalidRefreshTokenException
import kz.juzym.auth.LoginRequest
import kz.juzym.auth.LoginResponse
import kz.juzym.auth.PasswordChangeRequest
import kz.juzym.auth.RefreshRequest
import kz.juzym.auth.RefreshResponse
import kz.juzym.auth.SessionPayload
import kz.juzym.auth.SessionsResponse
import kz.juzym.auth.UnauthorizedException
import kz.juzym.config.Environment
import kz.juzym.user.EmailChangeConfirmationResult
import kz.juzym.user.EmailChangeRequestResult
import kz.juzym.user.security.jwt.JwtPrincipal
import kz.juzym.user.security.jwt.JwtService
import java.time.Duration
import java.time.Instant
import java.util.UUID
import io.ktor.util.date.GMTDate

fun Route.authRoutes(context: ApplicationContext) {
    val authService = context.authService
    val jwtService = context.jwtService
    val secureCookies = context.config.environment != Environment.DEV && context.config.environment != Environment.TEST
    val userService = context.userService

    route("/auth") {
        post("/login") {
            handleAuth {
                val payload = call.receive<LoginRequest>()
                val metadata = call.toAuthMetadata()
                val result = authService.login(payload, metadata)
                setRefreshCookie(call, result.tokens.refreshToken, result.tokens.refreshExpiresAt, secureCookies)
                val response = LoginResponse(
                    userId = result.userId,
                    avatarId = result.avatarId,
                    session = SessionPayload(
                        accessToken = result.tokens.accessToken,
                        refreshToken = result.tokens.refreshToken,
                        expiresAt = result.tokens.accessExpiresAt
                    )
                )
                call.respond(HttpStatusCode.OK, response)
            }
        }

        post("/refresh") {
            handleAuth {
                val cookieToken = call.request.cookies[REFRESH_COOKIE_NAME]
                val payload = call.receiveNullable<RefreshRequest>()
                val refreshToken = payload?.refreshToken ?: cookieToken ?: throw InvalidRefreshTokenException()
                val metadata = call.toAuthMetadata()
                val result = authService.refresh(refreshToken, payload?.deviceId, metadata)
                setRefreshCookie(call, result.tokens.refreshToken, result.tokens.refreshExpiresAt, secureCookies)
                val response = RefreshResponse(
                    session = SessionPayload(
                        accessToken = result.tokens.accessToken,
                        refreshToken = result.tokens.refreshToken,
                        expiresAt = result.tokens.accessExpiresAt
                    )
                )
                call.respond(HttpStatusCode.OK, response)
            }
        }

        post("/logout") {
            handleAuth {
                val refreshToken = call.request.cookies[REFRESH_COOKIE_NAME] ?: throw UnauthorizedException()
                withPrincipal(jwtService) {
                    authService.logout(refreshToken)
                }
                clearRefreshCookie(call, secureCookies)
                call.respond(HttpStatusCode.NoContent)
            }
        }

        post("/logout-all") {
            handleAuth {
                withPrincipal(jwtService) { principal ->
                    authService.logoutAll(principal.userId)
                }
                clearRefreshCookie(call, secureCookies)
                call.respond(HttpStatusCode.NoContent)
            }
        }

        get("/me") {
            handleAuth {
                withPrincipal(jwtService) { principal ->
                    val response = authService.getCurrentUser(principal.userId)
                    call.respond(HttpStatusCode.OK, response)
                }
            }
        }

        get("/sessions") {
            handleAuth {
                withPrincipal(jwtService) { principal ->
                    val refreshToken = call.request.cookies[REFRESH_COOKIE_NAME]
                    val response: SessionsResponse = authService.getSessions(principal.userId, refreshToken)
                    call.respond(HttpStatusCode.OK, response)
                }
            }
        }

        delete("/sessions/{sessionId}") {
            handleAuth {
                withPrincipal(jwtService) { principal ->
                    val sessionIdParam = call.parameters["sessionId"] ?: throw InvalidPayloadException("sessionId обязателен")
                    val sessionId = runCatching { UUID.fromString(sessionIdParam) }
                        .getOrElse { throw InvalidPayloadException("Недопустимый формат sessionId") }
                    authService.revokeSession(principal.userId, sessionId)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        post("/password/change") {
            handleAuth {
                withPrincipal(jwtService) { principal ->
                    val payload = call.receive<PasswordChangeRequest>()
                    authService.changePassword(principal.userId, payload.currentPassword, payload.newPassword)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        post("/email/change/request") {
            handleAuth {
                withPrincipal(jwtService) { principal ->
                    val payload = call.receive<EmailChangeRequest>()
                    val result = userService.requestEmailChange(principal.userId, payload.newEmail)
                    when (result) {
                        is EmailChangeRequestResult.Sent -> {
                            val debugLink = if (context.config.environment == Environment.TEST) result.link else null
                            call.respond(HttpStatusCode.OK, EmailChangeRequestResponse(sent = true, debugLink = debugLink))
                        }
                        EmailChangeRequestResult.NotFound -> {
                            call.respondAuthError(
                                AuthException(
                                    errorCode = "user_not_found",
                                    status = HttpStatusCode.NotFound,
                                    message = "Пользователь не найден"
                                )
                            )
                        }
                    }
                }
            }
        }

        post("/email/change/confirm") {
            handleAuth {
                val payload = call.receive<EmailChangeConfirmationRequest>()
                val result = userService.confirmEmailChange(payload.token)
                when (result) {
                    EmailChangeConfirmationResult.Updated -> {
                        call.respond(HttpStatusCode.OK, EmailChangeConfirmationResponse(updated = true))
                    }
                    EmailChangeConfirmationResult.InvalidToken -> {
                        call.respondAuthError(
                            AuthException(
                                errorCode = "invalid_token",
                                status = HttpStatusCode.BadRequest,
                                message = "Токен подтверждения недействителен"
                            )
                        )
                    }
                }
            }
        }
    }
}

private suspend fun PipelineContext<Unit, ApplicationCall>.handleAuth(
    block: suspend () -> Unit
) {
    try {
        block()
    } catch (ex: AuthException) {
        call.respondAuthError(ex)
    } catch (ex: ContentTransformationException) {
        call.respondAuthError(InvalidPayloadException("Некорректный формат запроса"))
    }
}

private suspend fun ApplicationCall.respondAuthError(exception: AuthException) {
    val response = AuthErrorResponse(
        error = AuthErrorBody(
            code = exception.errorCode,
            message = exception.message,
            details = exception.details,
            traceId = UUID.randomUUID()
        )
    )
    respond(exception.status, response)
}

private fun ApplicationCall.toAuthMetadata(): AuthMetadata = AuthMetadata(
    ip = request.local.remoteHost,
    userAgent = request.headers["User-Agent"]
)

private suspend fun PipelineContext<Unit, ApplicationCall>.withPrincipal(
    jwtService: JwtService,
    block: suspend (JwtPrincipal) -> Unit
) {
    val principal = call.requirePrincipal(jwtService)
    SecurityContext.setCurrentUserId(principal.userId)
    try {
        block(principal)
    } finally {
        SecurityContext.clear()
    }
}

private fun ApplicationCall.requirePrincipal(jwtService: JwtService): JwtPrincipal {
    val header = request.headers["Authorization"] ?: throw UnauthorizedException()
    val token = header.removePrefix("Bearer ").trim()
    if (token.isEmpty()) {
        throw UnauthorizedException()
    }
    return jwtService.verify(token) ?: throw UnauthorizedException()
}

private fun setRefreshCookie(call: ApplicationCall, token: String, expiresAt: Instant, secure: Boolean) {
    val duration = Duration.between(Instant.now(), expiresAt)
    val maxAgeSeconds = duration.seconds.coerceAtLeast(0)
    call.response.cookies.append(
        Cookie(
            name = REFRESH_COOKIE_NAME,
            value = token,
            path = REFRESH_COOKIE_PATH,
            maxAge = maxAgeSeconds.toInt(),
            expires = GMTDate(expiresAt.toEpochMilli()),
            httpOnly = true,
            secure = secure,
            extensions = mapOf("SameSite" to "Strict")
        )
    )
}

private fun clearRefreshCookie(call: ApplicationCall, secure: Boolean) {
    call.response.cookies.append(
        Cookie(
            name = REFRESH_COOKIE_NAME,
            value = "",
            path = REFRESH_COOKIE_PATH,
            maxAge = 0,
            expires = GMTDate(0L),
            httpOnly = true,
            secure = secure,
            extensions = mapOf("SameSite" to "Strict")
        )
    )
}

private const val REFRESH_COOKIE_NAME = "refreshToken"
private const val REFRESH_COOKIE_PATH = "/"
