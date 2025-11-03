package kz.juzym.registration

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

data class EmailAvailabilityResponse(
    val email: String,
    val available: Boolean
)

data class RegistrationRequest(
    val iin: String,
    val email: String,
    val password: String,
    val displayName: String,
    val locale: String? = null,
    val timezone: String? = null,
    val acceptedTermsVersion: String?,
    val acceptedPrivacyVersion: String?,
    val marketingOptIn: Boolean? = false
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class RegistrationResponse(
    val userId: UUID,
    val status: RegistrationStatus,
    val emailVerification: EmailVerificationInfo,
    val debugVerificationToken: String? = null
)

data class EmailVerificationInfo(
    val sent: Boolean,
    val method: String,
    val expiresAt: Instant
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ResendEmailResponse(
    val sent: Boolean,
    val cooldownSeconds: Long,
    val debugVerificationToken: String? = null
)

data class ResendEmailRequest(
    val iin: String,
    val email: String
)

data class VerificationRequest(
    val token: String
)

data class VerificationResponse(
    val userId: UUID,
    val status: RegistrationStatus,
    val avatarId: UUID,
    val session: SessionTokens
)

data class SessionTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant
)

data class CompleteProfileRequest(
    val photoUrl: String? = null,
    val about: String? = null,
    val locale: String? = null,
    val timezone: String? = null
)

data class CompleteProfileResponse(
    val avatarId: UUID,
    val updated: List<String>
)

data class PasswordPolicyResponse(
    val minLength: Int,
    val requireUpper: Boolean,
    val requireLower: Boolean,
    val requireDigit: Boolean,
    val requireSymbol: Boolean,
    val forbidBreachedTopN: Boolean
)

data class RegistrationLimitsResponse(
    val maxAttemptsPerIpPerHour: Int,
    val maxResendsPerDay: Int,
    val emailTokenTtlMinutes: Int
)

data class PasswordForgotRequest(
    val email: String
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PasswordForgotResponse(
    val sent: Boolean,
    val debugResetToken: String? = null
)

data class PasswordResetRequest(
    val token: String,
    val newPassword: String
)

data class PasswordResetResponse(
    val reset: Boolean
)

data class RegistrationStatusResponse(
    val email: String,
    val status: RegistrationStatus,
    val verification: VerificationStatus
)

data class VerificationStatus(
    val required: Boolean,
    val resentCooldownSec: Long
)

data class ApiError(
    val error: ApiErrorBody
)

data class ApiErrorBody(
    val code: String,
    val message: String,
    val details: Map<String, Any?> = emptyMap(),
    val traceId: UUID
)

enum class RegistrationStatus {
    pending,
    active,
    blocked
}
