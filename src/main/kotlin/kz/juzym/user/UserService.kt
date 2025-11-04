package kz.juzym.user

import kz.juzym.audit.AuditAction
import kz.juzym.registration.CompleteProfileRequest
import kz.juzym.registration.CompleteProfileResponse
import kz.juzym.registration.EmailAvailabilityResponse
import kz.juzym.registration.PasswordForgotResponse
import kz.juzym.registration.PasswordResetResponse
import kz.juzym.registration.RegistrationLimitsResponse
import kz.juzym.registration.RegistrationRequest
import kz.juzym.registration.RegistrationResponse
import kz.juzym.registration.RegistrationStatusResponse
import kz.juzym.registration.ResendEmailResponse
import kz.juzym.registration.VerificationResponse
import kz.juzym.registration.PasswordPolicyResponse
import java.util.UUID

interface UserService {
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

    @AuditAction("user.requestEmailChange")
    fun requestEmailChange(userId: UUID, newEmail: String): EmailChangeRequestResult

    @AuditAction("user.confirmEmailChange")
    fun confirmEmailChange(token: String): EmailChangeConfirmationResult
}

data class UserServiceConfig(
    val activationLinkBuilder: (String) -> String,
    val passwordResetLinkBuilder: (String) -> String,
    val deletionLinkBuilder: (String) -> String,
    val emailChangeLinkBuilder: (String) -> String
)

sealed interface EmailChangeRequestResult {
    data class Sent(val link: String) : EmailChangeRequestResult
    data object NotFound : EmailChangeRequestResult
}

sealed interface EmailChangeConfirmationResult {
    data object Updated : EmailChangeConfirmationResult
    data object InvalidToken : EmailChangeConfirmationResult
}
