package kz.juzym.auth

import kz.juzym.user.UserRepository
import kz.juzym.user.UserStatus
import kz.juzym.user.avatar.AvatarService
import kz.juzym.user.security.PasswordHasher
import kz.juzym.user.security.jwt.JwtService
import java.time.Instant
import java.util.*
import kotlin.random.Random

class AuthServiceImpl(
    private val userRepository: UserRepository,
    private val passwordHasher: PasswordHasher,
    private val jwtService: JwtService,
    private val sessionRepository: UserSessionRepository,
    private val authConfig: AuthConfig,
    private val avatarService: AvatarService
) : AuthService {

    override fun login(request: LoginRequest, metadata: AuthMetadata): LoginResult {
        val identifier = resolveIdentifier(request)
        val password = request.password.takeIf { it.isNotBlank() }
            ?: throw InvalidPayloadException("Пароль обязателен")

        val device = validateDevice(request.device)
        val rememberMe = request.rememberMe ?: false

        val user = when (identifier) {
            is Identifier.Email ->
                userRepository.findByEmail(identifier.value)
                    ?: userRepository.findByEmail(identifier.value.lowercase())
            is Identifier.Iin -> userRepository.findByIin(identifier.value)
        } ?: throw InvalidCredentialsException()

        if (user.status == UserStatus.BLOCKED) {
            throw AccountBlockedException()
        }
        if (user.status == UserStatus.PENDING) {
            throw UserNotActivatedException()
        }
        if (user.status != UserStatus.ACTIVE) {
            throw InvalidCredentialsException()
        }

        val credentialsValid = passwordHasher.verify(password, user.passwordHash)
        if (!credentialsValid) {
            throw InvalidCredentialsException()
        }

        val now = Instant.now()
        val accessToken = jwtService.generate(user.id, user.iin)
        val accessExpiresAt = now.plus(authConfig.accessTokenTtl)
        val refreshToken = generateRefreshToken()
        val refreshExpiresAt = now.plus(authConfig.refreshTtl(rememberMe))

        sessionRepository.create(
            userId = user.id,
            device = device,
            refreshToken = refreshToken,
            refreshTokenExpiresAt = refreshExpiresAt,
            accessTokenExpiresAt = accessExpiresAt,
            rememberMe = rememberMe,
            ip = metadata.ip,
            userAgent = metadata.userAgent
        )

        val avatarId = avatarService.getAvatarByUserId(user.id)?.avatar?.id

        return LoginResult(
            userId = user.id,
            avatarId = avatarId,
            tokens = SessionTokensResult(
                accessToken = accessToken,
                refreshToken = refreshToken,
                accessExpiresAt = accessExpiresAt,
                refreshExpiresAt = refreshExpiresAt
            )
        )
    }

    override fun refresh(refreshToken: String, deviceId: String?, metadata: AuthMetadata): RefreshResult {
        val match = sessionRepository.findByRefreshToken(refreshToken)
            ?: throw InvalidRefreshTokenException()
        val session = when (match) {
            is UserSessionRepository.TokenMatch.Current -> match.session
            is UserSessionRepository.TokenMatch.Previous -> throw TokenAlreadyRotatedException()
        }

        if (session.refreshTokenExpiresAt.isBefore(Instant.now())) {
            throw InvalidRefreshTokenException()
        }

        if (deviceId != null && session.deviceId != deviceId) {
            throw DeviceMismatchException()
        }

        val user = userRepository.findById(session.userId)
            ?: throw InvalidRefreshTokenException()
        if (user.status == UserStatus.BLOCKED) {
            throw AccountBlockedException()
        }

        val now = Instant.now()
        val newAccessToken = jwtService.generate(user.id, user.iin)
        val newAccessExpires = now.plus(authConfig.accessTokenTtl)
        val newRefreshToken = generateRefreshToken()
        val newRefreshExpires = now.plus(authConfig.refreshTtl(session.rememberMe))

        val updatedSession = sessionRepository.updateTokens(
            sessionId = session.id,
            previousRefreshToken = refreshToken,
            newRefreshToken = newRefreshToken,
            newRefreshExpiresAt = newRefreshExpires,
            newAccessExpiresAt = newAccessExpires,
            ip = metadata.ip,
            userAgent = metadata.userAgent
        ) ?: throw InvalidRefreshTokenException()

        return RefreshResult(
            userId = user.id,
            sessionId = updatedSession.id,
            tokens = SessionTokensResult(
                accessToken = newAccessToken,
                refreshToken = newRefreshToken,
                accessExpiresAt = newAccessExpires,
                refreshExpiresAt = newRefreshExpires
            )
        )
    }

    override fun logout(refreshToken: String) {
        val removed = sessionRepository.deleteByRefreshToken(refreshToken)
        if (!removed) {
            throw InvalidRefreshTokenException()
        }
    }

    override fun logoutAll(userId: UUID) {
        sessionRepository.deleteByUser(userId)
    }

    override fun getCurrentUser(userId: UUID): MeResponse {
        val user = userRepository.findById(userId) ?: throw UnauthorizedException()
        val avatarProfile = avatarService.getAvatarByUserId(userId)
        val avatarSummary = avatarProfile?.let {
            AvatarSummary(
                id = it.avatar.id,
                displayName = it.avatar.displayName,
                photoUrl = it.avatar.photoUrl,
                level = it.stats.level,
                xp = it.stats.xp,
                reputation = it.stats.reputation
            )
        }
        return MeResponse(
            user = UserSummary(
                id = user.id,
                email = user.email,
                iin = user.iin,
                status = user.status.name.lowercase()
            ),
            avatar = avatarSummary
        )
    }

    override fun getSessions(userId: UUID, currentRefreshToken: String?): SessionsResponse {
        val sessions = sessionRepository.listByUser(userId)
        val currentSessionId = currentRefreshToken?.let { token ->
            (sessionRepository.findByRefreshToken(token) as? UserSessionRepository.TokenMatch.Current)?.session?.id
        }
        val sessionInfos = sessions.map { session ->
            SessionInfo(
                sessionId = session.id,
                deviceId = session.deviceId,
                deviceName = session.deviceName,
                platform = session.platform,
                createdAt = session.createdAt,
                lastSeenAt = session.lastSeenAt,
                ip = session.ip,
                current = currentSessionId == session.id
            )
        }
        return SessionsResponse(sessionInfos, sessionInfos.size)
    }

    override fun revokeSession(userId: UUID, sessionId: UUID) {
        val removed = sessionRepository.deleteById(userId, sessionId)
        if (!removed) {
            throw SessionNotFoundException()
        }
    }

    override fun changePassword(userId: UUID, currentPassword: String, newPassword: String) {
        val user = userRepository.findById(userId) ?: throw UnauthorizedException()
        if (!passwordHasher.verify(currentPassword, user.passwordHash)) {
            throw InvalidCredentialsException()
        }
        if (newPassword.length < 8) {
            throw WeakPasswordException()
        }
        if (passwordHasher.verify(newPassword, user.passwordHash)) {
            throw PasswordReuseForbiddenException()
        }
        val hashed = passwordHasher.hash(newPassword)
        userRepository.updatePassword(userId, hashed)
    }

    private fun resolveIdentifier(request: LoginRequest): Identifier {
        val email = request.email?.trim()?.takeIf { it.isNotEmpty() }
        val iin = request.iin?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            email != null && iin == null -> Identifier.Email(email)
            email == null && iin != null -> Identifier.Iin(iin)
            else -> throw InvalidPayloadException("Необходимо указать email или ИИН")
        }
    }

    private fun validateDevice(device: DeviceInfo): DeviceInfo {
        val deviceId = device.deviceId.trim().takeIf { it.isNotEmpty() }
            ?: throw InvalidPayloadException("deviceId обязателен")
        val platform = device.platform.trim().lowercase()
        val allowedPlatforms = setOf("web", "ios", "android", "desktop")
        if (platform !in allowedPlatforms) {
            throw InvalidPayloadException("Недопустимая платформа: ${device.platform}")
        }
        return device.copy(
            deviceId = deviceId,
            platform = platform,
            deviceName = device.deviceName?.takeIf { it.isNotBlank() },
            clientVersion = device.clientVersion?.takeIf { it.isNotBlank() }
        )
    }

    private fun generateRefreshToken(): String {
        val randomPart = Random.nextBytes(32)
        val hex = randomPart.joinToString(separator = "") { byte ->
            ((byte.toInt() and 0xff) + 0x100).toString(16).substring(1)
        }
        return "rt_${UUID.randomUUID()}_$hex"
    }

    private sealed interface Identifier {
        data class Email(val value: String) : Identifier
        data class Iin(val value: String) : Identifier
    }
}
