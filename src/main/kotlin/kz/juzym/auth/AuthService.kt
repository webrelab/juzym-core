package kz.juzym.auth

import kz.juzym.audit.AuditAction
import java.util.UUID

interface AuthService {

    @AuditAction("auth.login")
    fun login(request: LoginRequest, metadata: AuthMetadata): LoginResult

    @AuditAction("auth.refresh")
    fun refresh(refreshToken: String, deviceId: String?, metadata: AuthMetadata): RefreshResult

    @AuditAction("auth.logout")
    fun logout(refreshToken: String)

    @AuditAction("auth.logoutAll")
    fun logoutAll(userId: UUID)

    fun getCurrentUser(userId: UUID): MeResponse

    fun getSessions(userId: UUID, currentRefreshToken: String?): SessionsResponse

    @AuditAction("auth.revokeSession")
    fun revokeSession(userId: UUID, sessionId: UUID)

    @AuditAction("auth.changePassword")
    fun changePassword(userId: UUID, currentPassword: String, newPassword: String)
}
