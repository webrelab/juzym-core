package kz.juzym.http.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kz.juzym.user.ApiError
import kz.juzym.user.ApiErrorBody
import kz.juzym.user.CompleteProfileRequest
import kz.juzym.user.PasswordForgotRequest
import kz.juzym.user.PasswordResetRequest
import kz.juzym.user.RegistrationConflictException
import kz.juzym.user.RegistrationInvalidTokenException
import kz.juzym.user.RegistrationNotFoundException
import kz.juzym.user.RegistrationRateLimitException
import kz.juzym.user.RegistrationRequest
import kz.juzym.user.UserService
import kz.juzym.user.RegistrationUnauthorizedException
import kz.juzym.user.RegistrationValidationException
import kz.juzym.user.ResendEmailRequest
import kz.juzym.user.VerificationRequest
import io.ktor.util.pipeline.PipelineContext
import java.util.UUID

fun Route.registrationRoutes(service: UserService) {
    route("/auth") {
        route("/registration") {
            get("/email-availability") {
                handleRegistration {
                    val email = call.request.queryParameters["email"]
                        ?: throw RegistrationValidationException("invalid_email_format", "Email is required")
                    val response = service.checkEmailAvailability(email)
                    call.respond(HttpStatusCode.OK, response)
                }
            }

            post {
                handleRegistration {
                    val request = call.receive<RegistrationRequest>()
                    val idempotencyKey = call.request.headers["Idempotency-Key"]
                    val response = service.startRegistration(request, idempotencyKey)
                    call.respond(HttpStatusCode.Created, response)
                }
            }

            post("/resend-email") {
                handleRegistration {
                    val payload = call.receive<ResendEmailRequest>()
                    val response = service.resendActivationEmail(payload.iin, payload.email)
                    call.respond(HttpStatusCode.OK, response)
                }
            }

            post("/verify-email") {
                handleRegistration {
                    val request = call.receive<VerificationRequest>()
                    val response = service.verifyEmail(request.token)
                    call.respond(HttpStatusCode.OK, response)
                }
            }

            patch("/complete-profile") {
                handleRegistration {
                    val authHeader = call.request.headers["Authorization"]
                        ?: throw RegistrationUnauthorizedException("Authorization header missing")
                    val token = authHeader.removePrefix("Bearer ").trim()
                    val userId = runCatching { UUID.fromString(token) }
                        .getOrElse { throw RegistrationUnauthorizedException("Invalid authorization token") }
                    val request = call.receive<CompleteProfileRequest>()
                    val response = service.completeProfile(userId, request)
                    call.respond(HttpStatusCode.OK, response)
                }
            }

            get("/password-policy") {
                handleRegistration {
                    call.respond(HttpStatusCode.OK, service.getPasswordPolicy())
                }
            }

            get("/limits") {
                handleRegistration {
                    call.respond(HttpStatusCode.OK, service.getLimits())
                }
            }

            get("/status") {
                handleRegistration {
                    val email = call.request.queryParameters["email"]
                        ?: throw RegistrationValidationException("invalid_email_format", "Email is required")
                    val response = service.getRegistrationStatus(email)
                    call.respond(HttpStatusCode.OK, response)
                }
            }
        }

        route("/password") {
            post("/forgot") {
                handleRegistration {
                    val request = call.receive<PasswordForgotRequest>()
                    val response = service.requestPasswordReset(request.email)
                    call.respond(HttpStatusCode.OK, response)
                }
            }

            post("/reset") {
                handleRegistration {
                    val request = call.receive<PasswordResetRequest>()
                    val response = service.resetPassword(request.token, request.newPassword)
                    call.respond(HttpStatusCode.OK, response)
                }
            }
        }
    }
}

private suspend fun PipelineContext<Unit, ApplicationCall>.handleRegistration(
    block: suspend PipelineContext<Unit, ApplicationCall>.() -> Unit
) {
    try {
        block()
    } catch (ex: RegistrationValidationException) {
        call.respondError(HttpStatusCode.BadRequest, ex.code, ex.messageText)
    } catch (ex: RegistrationConflictException) {
        val status = when (ex.code) {
            "already_verified" -> HttpStatusCode.Conflict
            "email_already_registered", "iin_already_registered" -> HttpStatusCode.Conflict
            "not_found_if_not_pending" -> HttpStatusCode.NotFound
            "avatar_locked" -> HttpStatusCode.Conflict
            else -> HttpStatusCode.Conflict
        }
        call.respondError(status, ex.code, ex.messageText)
    } catch (ex: RegistrationRateLimitException) {
        call.respondError(HttpStatusCode.TooManyRequests, "rate_limited", "Too many requests", mapOf("cooldownSeconds" to ex.cooldownSeconds))
    } catch (ex: RegistrationNotFoundException) {
        val status = if (ex.code == "email_not_found") HttpStatusCode.NotFound else HttpStatusCode.NotFound
        call.respondError(status, ex.code, ex.messageText)
    } catch (ex: RegistrationInvalidTokenException) {
        call.respondError(HttpStatusCode.BadRequest, ex.code, ex.messageText)
    } catch (ex: RegistrationUnauthorizedException) {
        call.respondError(HttpStatusCode.Unauthorized, "unauthorized", ex.message ?: "Unauthorized")
    } catch (ex: Exception) {
        throw ex
    }
}

private suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    message: String,
    details: Map<String, Any?> = emptyMap()
) {
    val error = ApiError(
        ApiErrorBody(
            code = code,
            message = message,
            details = details,
            traceId = UUID.randomUUID()
        )
    )
    respond(status, error)
}
