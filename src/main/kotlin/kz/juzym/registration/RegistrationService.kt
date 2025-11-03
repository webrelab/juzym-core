package kz.juzym.registration

import kz.juzym.audit.AuditAction
import java.util.UUID

interface RegistrationService {
    @AuditAction("registration.checkEmailAvailability")
    fun checkEmailAvailability(email: String): EmailAvailabilityResponse

    @AuditAction("registration.start")
    fun startRegistration(request: RegistrationRequest, idempotencyKey: String?): RegistrationResponse

    @AuditAction("registration.resendEmail")
    fun resendActivationEmail(iin: String, email: String): ResendEmailResponse

    @AuditAction("registration.verifyEmail")
    fun verifyEmail(token: String): VerificationResponse

    @AuditAction("registration.completeProfile")
    fun completeProfile(userId: UUID, request: CompleteProfileRequest): CompleteProfileResponse

    @AuditAction("registration.getPasswordPolicy")
    fun getPasswordPolicy(): PasswordPolicyResponse

    @AuditAction("registration.getLimits")
    fun getLimits(): RegistrationLimitsResponse

    @AuditAction("registration.requestPasswordReset")
    fun requestPasswordReset(email: String): PasswordForgotResponse

    @AuditAction("registration.resetPassword")
    fun resetPassword(token: String, newPassword: String): PasswordResetResponse

    @AuditAction("registration.getStatus")
    fun getRegistrationStatus(email: String): RegistrationStatusResponse
}

data class RegistrationConfig(
    val emailTokenTtlMinutes: Long = 60,
    val resendCooldownSeconds: Long = 300,
    val maxResendsPerDay: Int = 5,
    val maxAttemptsPerIpPerHour: Int = 20,
    val passwordPolicy: PasswordPolicyResponse = PasswordPolicyResponse(
        minLength = 10,
        requireUpper = false,
        requireLower = true,
        requireDigit = true,
        requireSymbol = false,
        forbidBreachedTopN = true
    ),
    val exposeDebugTokens: Boolean = false
)

class RegistrationConflictException(val code: String, val messageText: String) : RuntimeException(messageText)
class RegistrationValidationException(val code: String, val messageText: String) : RuntimeException(messageText)
class RegistrationRateLimitException(val cooldownSeconds: Long) : RuntimeException()
class RegistrationNotFoundException(val code: String, val messageText: String) : RuntimeException(messageText)
class RegistrationInvalidTokenException(val code: String, val messageText: String) : RuntimeException(messageText)
class RegistrationUnauthorizedException(message: String) : RuntimeException(message)
