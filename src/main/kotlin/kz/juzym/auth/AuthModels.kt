package kz.juzym.auth

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

@JsonInclude(JsonInclude.Include.NON_NULL)
data class LoginResponse(
    val userId: UUID,
    val avatarId: UUID?,
    val session: SessionPayload
)

data class SessionPayload(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant
)

data class LoginRequest(
    val email: String? = null,
    val iin: String? = null,
    val password: String,
    val device: DeviceInfo,
    val rememberMe: Boolean? = false,
    val captchaToken: String? = null
)

data class DeviceInfo(
    val deviceId: String,
    val deviceName: String? = null,
    val platform: String,
    val clientVersion: String? = null
)

data class RefreshRequest(
    val refreshToken: String? = null,
    val deviceId: String? = null
)

data class MeResponse(
    val user: UserSummary,
    val avatar: AvatarSummary?
)

data class UserSummary(
    val id: UUID,
    val email: String,
    val iin: String,
    val status: String
)

data class AvatarSummary(
    val id: UUID,
    val displayName: String,
    val photoUrl: String?,
    val level: Int,
    val xp: Int,
    val reputation: Int
)

data class SessionsResponse(
    val sessions: List<SessionInfo>,
    val total: Int
)

data class SessionInfo(
    val sessionId: UUID,
    val deviceId: String,
    val deviceName: String?,
    val platform: String,
    val createdAt: Instant,
    val lastSeenAt: Instant?,
    val ip: String?,
    val current: Boolean
)

data class PasswordChangeRequest(
    val currentPassword: String,
    val newPassword: String
)

data class EmailChangeRequest(
    val newEmail: String
)

data class EmailChangeConfirmationRequest(
    val token: String
)

data class EmailChangeRequestResponse(
    val sent: Boolean,
    val debugLink: String? = null
)

data class EmailChangeConfirmationResponse(
    val updated: Boolean
)

data class AuthErrorResponse(
    val error: AuthErrorBody
)

data class AuthErrorBody(
    val code: String,
    val message: String,
    val details: Map<String, Any?> = emptyMap(),
    val traceId: UUID
)

data class RefreshResponse(
    val session: SessionPayload
)

data class AuthMetadata(
    val ip: String?,
    val userAgent: String?
)

data class LoginResult(
    val userId: UUID,
    val avatarId: UUID?,
    val tokens: SessionTokensResult
)

data class SessionTokensResult(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Instant,
    val refreshExpiresAt: Instant
)

data class RefreshResult(
    val userId: UUID,
    val sessionId: UUID,
    val tokens: SessionTokensResult
)
