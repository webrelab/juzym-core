package kz.juzym.user

import kz.juzym.user.security.PasswordHasher
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.*

class UserServiceImpl(
    private val database: org.jetbrains.exposed.sql.Database,
    private val userRepository: UserRepository,
    private val tokenRepository: UserTokenRepository,
    private val mailSender: MailSenderStub,
    private val passwordHasher: PasswordHasher,
    private val config: UserServiceConfig,
    private val registrationConfig: RegistrationConfig = RegistrationConfig(),
    private val clock: Clock = Clock.systemUTC(),
) : UserService {

    override fun checkEmailAvailability(email: String): EmailAvailabilityResponse {
        val normalized = normalizeEmail(email)
        validateEmail(normalized)
        val exists = transaction(database) {
            !UsersTable.selectAll()
                .where { UsersTable.email eq normalized }.empty()
        }
        return EmailAvailabilityResponse(email = normalized, available = !exists)
    }

    override fun startRegistration(request: RegistrationRequest, idempotencyKey: String?): RegistrationResponse {
        idempotencyKey?.let { key ->
            findIdempotentResponse(key)?.let { return it }
        }

        val normalizedEmail = normalizeEmail(request.email)
        val normalizedIin = normalizeIin(request.iin)
        validateEmail(normalizedEmail)
        validateIin(normalizedIin)
        ensureConsentsPresent(request)
        validatePassword(request.password)

        val now = clock.instant()
        val result: OperationResult<RegistrationResponse> = transaction(database) {
            idempotencyKey?.let { key ->
                findIdempotentResponseInTransaction(key)?.let { response ->
                    return@transaction OperationResult(response, null, false)
                }
            }

            if (!UsersTable.selectAll()
                    .where { UsersTable.email eq normalizedEmail }.empty()
            ) {
                throw RegistrationConflictException("email_already_registered", "Email is already registered")
            }
            if (!UsersTable.selectAll()
                    .where { UsersTable.iin eq normalizedIin }.empty()
            ) {
                throw RegistrationConflictException("iin_already_registered", "IIN is already registered")
            }

            val passwordHash = passwordHasher.hash(request.password)
            val expiresAt = now.plusSeconds(registrationConfig.emailTokenTtlMinutes * 60)
            val entityId: EntityID<UUID> = UsersTable.insertAndGetId { statement ->
                statement[iin] = normalizedIin
                statement[email] = normalizedEmail
                statement[UsersTable.passwordHash] = passwordHash
                statement[status] = UserStatus.PENDING
                statement[displayName] = request.displayName.trim()
                statement[locale] = request.locale
                statement[timezone] = request.timezone
                statement[acceptedTermsVersion] = request.acceptedTermsVersion
                statement[acceptedPrivacyVersion] = request.acceptedPrivacyVersion
                statement[marketingOptIn] = request.marketingOptIn ?: false
                statement[avatarId] = null
                statement[photoUrl] = null
                statement[about] = null
                statement[activationTokenExpiresAt] = expiresAt.toOffsetDateTime()
                statement[lastEmailSentAt] = now.toOffsetDateTime()
                statement[resendCount] = 0
                statement[resendCountResetAt] = now.toOffsetDateTime()
                statement[createdAt] = now.toOffsetDateTime()
                statement[updatedAt] = now.toOffsetDateTime()
            }
            val userId = entityId.value

            val activationToken = createUserToken(
                userId,
                UserTokenType.ACTIVATION,
                Duration.ofMinutes(registrationConfig.emailTokenTtlMinutes),
                now
            )
            val response = RegistrationResponse(
                userId = userId,
                status = RegistrationStatus.PENDING,
                emailVerification = EmailVerificationInfo(
                    sent = true,
                    method = "link",
                    expiresAt = activationToken.expiresAt,
                ),
                debugVerificationToken = if (registrationConfig.exposeDebugTokens) activationToken.token else null,
            )

            idempotencyKey?.let { key ->
                storeIdempotentResponse(key, userId, response, now)
            }

            OperationResult(
                response = response,
                emailLink = config.activationLinkBuilder(activationToken.token),
                shouldSendEmail = true,
            )
        }

        if (result.shouldSendEmail && result.emailLink != null) {
            mailSender.sendActivationEmail(normalizedEmail, result.emailLink)
        }

        return result.response
    }

    override fun resendActivationEmail(iin: String, email: String): ResendEmailResponse {
        val normalizedIin = normalizeIin(iin)
        val normalizedEmail = normalizeEmail(email)
        val now = clock.instant()

        val result: OperationResult<ResendEmailResponse> = transaction(database) {
            val row = UsersTable.selectAll()
                .where { UsersTable.email eq normalizedEmail }.singleOrNull()
                ?: throw RegistrationNotFoundException("not_found_if_not_pending", "Registration not found")
            val user = row.toUserRecord()
            if (user.iin != normalizedIin) {
                throw RegistrationNotFoundException("not_found_if_not_pending", "Registration not found")
            }
            if (user.status != RegistrationStatus.PENDING) {
                throw RegistrationConflictException("not_found_if_not_pending", "Registration is not pending")
            }

            user.lastEmailSentAt?.let { lastSent ->
                val elapsed = Duration.between(lastSent, now).seconds
                if (elapsed < registrationConfig.resendCooldownSeconds) {
                    throw RegistrationRateLimitException(registrationConfig.resendCooldownSeconds - elapsed)
                }
            }

            val sameDay = user.resendCountResetAt?.let { resetAt ->
                val resetOffset = resetAt.atOffset(ZoneOffset.UTC)
                val nowOffset = now.atOffset(ZoneOffset.UTC)
                resetOffset.dayOfYear == nowOffset.dayOfYear && resetOffset.year == nowOffset.year
            } ?: false

            val resendCount = if (sameDay) user.resendCount else 0
            if (resendCount >= registrationConfig.maxResendsPerDay) {
                throw RegistrationRateLimitException(registrationConfig.resendCooldownSeconds)
            }

            val activationToken = createUserToken(
                user.userId,
                UserTokenType.ACTIVATION,
                Duration.ofMinutes(registrationConfig.emailTokenTtlMinutes),
                now
            )

            UsersTable.update({ UsersTable.id eq user.userId }) { statement ->
                statement[activationTokenExpiresAt] = activationToken.expiresAt.toOffsetDateTime()
                statement[lastEmailSentAt] = now.toOffsetDateTime()
                statement[UsersTable.resendCount] = resendCount + 1
                statement[resendCountResetAt] =
                    if (sameDay) user.resendCountResetAt?.toOffsetDateTime() else now.toOffsetDateTime()
                statement[updatedAt] = now.toOffsetDateTime()
            }

            OperationResult(
                response = ResendEmailResponse(
                    sent = true,
                    cooldownSeconds = registrationConfig.resendCooldownSeconds,
                    debugVerificationToken = if (registrationConfig.exposeDebugTokens) activationToken.token else null,
                ),
                emailLink = config.activationLinkBuilder(activationToken.token),
                shouldSendEmail = true,
            )
        }

        if (result.shouldSendEmail && result.emailLink != null) {
            mailSender.sendActivationEmail(normalizedEmail, result.emailLink)
        }

        return result.response
    }

    override fun verifyEmail(token: String): VerificationResponse {
        val now = clock.instant()
        return transaction(database) {
            val tokenRow = UserTokensTable.selectAll()
                .where {
                    (UserTokensTable.token eq token) and (UserTokensTable.type eq UserTokenType.ACTIVATION)
                }.singleOrNull() ?: throw RegistrationInvalidTokenException(
                code = "invalid_or_expired_token",
                messageText = "Verification token is invalid",
            )

            val expiresAt = tokenRow[UserTokensTable.expiresAt].toInstantUtc()
            if (expiresAt.isBefore(now)) {
                UserTokensTable.deleteWhere { UserTokensTable.token eq token }
                throw RegistrationInvalidTokenException("invalid_or_expired_token", "Verification token expired")
            }

            val userId = tokenRow[UserTokensTable.userId]
            val row = UsersTable.selectAll()
                .where { UsersTable.id eq userId }.singleOrNull()
                ?: throw RegistrationInvalidTokenException("invalid_or_expired_token", "Verification token not found")
            val user = row.toUserRecord()
            if (user.status == RegistrationStatus.ACTIVE) {
                throw RegistrationConflictException("already_verified", "Email already verified")
            }

            val avatarId = user.avatarId ?: UUID.randomUUID()
            UsersTable.update({ UsersTable.id eq userId }) { statement ->
                statement[status] = UserStatus.ACTIVE
                statement[UsersTable.avatarId] = avatarId
                statement[activationTokenExpiresAt] = null
                statement[updatedAt] = now.toOffsetDateTime()
            }
            UserTokensTable.deleteWhere { UserTokensTable.token eq token }

            VerificationResponse(
                userId = userId,
                status = RegistrationStatus.ACTIVE,
                avatarId = avatarId,
                session = SessionTokens(
                    accessToken = "access-$userId",
                    refreshToken = "refresh-$userId",
                    expiresAt = now.plusSeconds(3600),
                ),
            )
        }
    }

    override fun completeProfile(userId: UUID, request: CompleteProfileRequest): CompleteProfileResponse {
        val now = clock.instant()
        return transaction(database) {
            val row = UsersTable.selectAll()
                .where { UsersTable.id eq userId }.singleOrNull()
                ?: throw RegistrationUnauthorizedException("User not found")
            val user = row.toUserRecord()
            if (user.status != RegistrationStatus.ACTIVE) {
                throw RegistrationConflictException("avatar_locked", "Profile cannot be updated in current status")
            }

            val updatedFields = mutableListOf<String>()
            var avatarId = user.avatarId
            var changed = false

            UsersTable.update({ UsersTable.id eq userId }) { statement ->
                request.photoUrl?.let {
                    statement[photoUrl] = it
                    updatedFields += "photoUrl"
                    changed = true
                }
                request.about?.let {
                    statement[about] = it
                    updatedFields += "about"
                    changed = true
                }
                request.locale?.let {
                    statement[locale] = it
                    updatedFields += "locale"
                    changed = true
                }
                request.timezone?.let {
                    statement[timezone] = it
                    updatedFields += "timezone"
                    changed = true
                }
                if (changed) {
                    if (avatarId == null) {
                        avatarId = UUID.randomUUID()
                    }
                    statement[UsersTable.avatarId] = avatarId
                    statement[updatedAt] = now.toOffsetDateTime()
                }
            }

            if (!changed && avatarId == null) {
                avatarId = UUID.randomUUID()
            }

            CompleteProfileResponse(
                avatarId = avatarId!!,
                updated = updatedFields,
            )
        }
    }

    override fun getPasswordPolicy(): PasswordPolicyResponse = registrationConfig.passwordPolicy

    override fun getLimits(): RegistrationLimitsResponse = RegistrationLimitsResponse(
        maxAttemptsPerIpPerHour = registrationConfig.maxAttemptsPerIpPerHour,
        maxResendsPerDay = registrationConfig.maxResendsPerDay,
        emailTokenTtlMinutes = registrationConfig.emailTokenTtlMinutes.toInt(),
    )

    override fun requestPasswordReset(email: String): PasswordForgotResponse {
        val normalizedEmail = normalizeEmail(email)
        val now = clock.instant()
        val result = transaction(database) {
            val row = UsersTable.selectAll()
                .where { UsersTable.email eq normalizedEmail }.singleOrNull()
                ?: throw RegistrationNotFoundException("email_not_found", "Email not found")
            val user = row.toUserRecord()
            val token = createUserToken(user.userId, UserTokenType.PASSWORD_RESET, Duration.ofHours(1), now)
            val response = PasswordForgotResponse(
                sent = true,
                debugResetToken = if (registrationConfig.exposeDebugTokens) token.token else null,
            )
            PasswordResetResult(
                response = response,
                emailLink = config.passwordResetLinkBuilder(token.token),
            )
        }

        mailSender.sendPasswordResetEmail(normalizedEmail, result.emailLink)
        return result.response
    }

    override fun resetPassword(token: String, newPassword: String): PasswordResetResponse {
        validatePassword(newPassword)
        val now = clock.instant()
        return transaction(database) {
            val tokenRow = UserTokensTable.selectAll()
                .where {
                    (UserTokensTable.token eq token) and (UserTokensTable.type eq UserTokenType.PASSWORD_RESET)
                }.singleOrNull() ?: throw RegistrationInvalidTokenException(
                code = "invalid_or_expired_token",
                messageText = "Reset token invalid",
            )
            val expiresAt = tokenRow[UserTokensTable.expiresAt].toInstantUtc()
            if (expiresAt.isBefore(now)) {
                UserTokensTable.deleteWhere { UserTokensTable.token eq token }
                throw RegistrationInvalidTokenException("invalid_or_expired_token", "Reset token expired")
            }
            val userId = tokenRow[UserTokensTable.userId]
            val hashed = passwordHasher.hash(newPassword)
            val updated = UsersTable.update({ UsersTable.id eq userId }) { statement ->
                statement[passwordHash] = hashed
                statement[updatedAt] = now.toOffsetDateTime()
            }
            if (updated == 0) {
                throw RegistrationInvalidTokenException("invalid_or_expired_token", "Reset token invalid")
            }
            UserTokensTable.deleteWhere { UserTokensTable.token eq token }
            PasswordResetResponse(reset = true)
        }
    }

    override fun getRegistrationStatus(email: String): RegistrationStatusResponse {
        val normalizedEmail = normalizeEmail(email)
        val now = clock.instant()
        return transaction(database) {
            val row = UsersTable.selectAll()
                .where { UsersTable.email eq normalizedEmail }.singleOrNull()
                ?: throw RegistrationNotFoundException("not_found", "Email not registered")
            val user = row.toUserRecord()
            val cooldown = user.lastEmailSentAt?.let { last ->
                val elapsed = Duration.between(last, now).seconds
                if (elapsed >= registrationConfig.resendCooldownSeconds) 0 else registrationConfig.resendCooldownSeconds - elapsed
            } ?: 0
            RegistrationStatusResponse(
                email = normalizedEmail,
                status = user.status,
                verification = VerificationStatus(
                    required = user.status != RegistrationStatus.ACTIVE,
                    resentCooldownSec = cooldown,
                ),
            )
        }
    }

    override fun requestEmailChange(userId: UUID, newEmail: String): EmailChangeRequestResult {
        val user = userRepository.findById(userId) ?: return EmailChangeRequestResult.NotFound
        val normalizedEmail = normalizeEmail(newEmail)
        val emailTaken = transaction(database) {
            !UsersTable.selectAll()
                .where { (UsersTable.email eq normalizedEmail) and (UsersTable.id neq userId) }.empty()
        }
        if (emailTaken) {
            throw RegistrationConflictException("email_already_registered", "Email is already registered")
        }
        val token = tokenRepository.createToken(
            userId = user.id,
            type = UserTokenType.EMAIL_CHANGE,
            validity = Duration.ofMinutes(20),
            payload = normalizedEmail
        )
        val link = config.emailChangeLinkBuilder(token.token)
        mailSender.sendEmailChangeConfirmationEmail(normalizedEmail, normalizedEmail, link)
        return EmailChangeRequestResult.Sent(link)
    }

    override fun confirmEmailChange(token: String): EmailChangeConfirmationResult {
        val changeToken = tokenRepository.findValidToken(token, UserTokenType.EMAIL_CHANGE)
            ?: return EmailChangeConfirmationResult.InvalidToken
        val newEmail = changeToken.payload ?: return EmailChangeConfirmationResult.InvalidToken
        userRepository.updateEmail(changeToken.userId, newEmail)
        tokenRepository.markConsumed(changeToken.id)
        return EmailChangeConfirmationResult.Updated
    }

    private fun createUserToken(
        userId: UUID,
        type: UserTokenType,
        validity: Duration,
        now: Instant,
        payload: String? = null,
    ): UserToken {
        val token = UserToken(
            id = UUID.randomUUID(),
            userId = userId,
            token = UUID.randomUUID().toString(),
            type = type,
            payload = payload,
            createdAt = now,
            expiresAt = now.plus(validity),
            consumedAt = null,
        )
        UserTokensTable.deleteWhere { (UserTokensTable.userId eq userId) and (UserTokensTable.type eq type) }
        UserTokensTable.insert { statement ->
            statement[UserTokensTable.id] = token.id
            statement.fromUserToken(token)
        }
        return token
    }

    private fun findIdempotentResponse(key: String): RegistrationResponse? = transaction(database) {
        UserRegistrationIdempotencyTable.selectAll()
            .where { UserRegistrationIdempotencyTable.key eq key }
            .singleOrNull()
            ?.toRegistrationResponse()
    }

    private fun findIdempotentResponseInTransaction(key: String): RegistrationResponse? {
        return UserRegistrationIdempotencyTable.selectAll()
            .where { UserRegistrationIdempotencyTable.key eq key }
            .singleOrNull()
            ?.toRegistrationResponse()
    }

    private fun storeIdempotentResponse(key: String, userId: UUID, response: RegistrationResponse, now: Instant) {
        UserRegistrationIdempotencyTable.insert { statement ->
            statement[UserRegistrationIdempotencyTable.key] = key
            statement[UserRegistrationIdempotencyTable.userId] = userId
            statement[responseStatus] = response.status
            statement[responseVerificationExpiresAt] = response.emailVerification.expiresAt.toOffsetDateTime()
            statement[responseDebugToken] = response.debugVerificationToken
            statement[createdAt] = now.toOffsetDateTime()
        }
    }

    private fun ResultRow.toRegistrationResponse(): RegistrationResponse = RegistrationResponse(
        userId = this[UserRegistrationIdempotencyTable.userId],
        status = this[UserRegistrationIdempotencyTable.responseStatus],
        emailVerification = EmailVerificationInfo(
            sent = true,
            method = "link",
            expiresAt = this[UserRegistrationIdempotencyTable.responseVerificationExpiresAt].toInstantUtc(),
        ),
        debugVerificationToken = this[UserRegistrationIdempotencyTable.responseDebugToken],
    )

    private fun ResultRow.toUserRecord(): UserRecord = UserRecord(
        userId = this[UsersTable.id].value,
        iin = this[UsersTable.iin],
        email = this[UsersTable.email],
        passwordHash = this[UsersTable.passwordHash],
        displayName = this[UsersTable.displayName],
        locale = this[UsersTable.locale],
        timezone = this[UsersTable.timezone],
        acceptedTermsVersion = this[UsersTable.acceptedTermsVersion],
        acceptedPrivacyVersion = this[UsersTable.acceptedPrivacyVersion],
        marketingOptIn = this[UsersTable.marketingOptIn],
        status = this[UsersTable.status].toRegistrationStatus(),
        activationTokenExpiresAt = this[UsersTable.activationTokenExpiresAt]?.toInstantUtc(),
        lastEmailSentAt = this[UsersTable.lastEmailSentAt]?.toInstantUtc(),
        avatarId = this[UsersTable.avatarId],
        photoUrl = this[UsersTable.photoUrl],
        about = this[UsersTable.about],
        resendCount = this[UsersTable.resendCount],
        resendCountResetAt = this[UsersTable.resendCountResetAt]?.toInstantUtc(),
        createdAt = this[UsersTable.createdAt].toInstantUtc(),
        updatedAt = this[UsersTable.updatedAt].toInstantUtc(),
    )

    private fun UserStatus.toRegistrationStatus(): RegistrationStatus = when (this) {
        UserStatus.PENDING -> RegistrationStatus.PENDING
        UserStatus.ACTIVE -> RegistrationStatus.ACTIVE
        UserStatus.BLOCKED -> RegistrationStatus.BLOCKED
    }

    private fun validateEmail(email: String) {
        val regex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$".toRegex()
        if (!regex.matches(email)) {
            throw RegistrationValidationException("invalid_email_format", "Email format is invalid")
        }
    }

    private fun validateIin(iin: String) {
        if (iin.length != 12 || iin.any { !it.isDigit() }) {
            throw RegistrationValidationException("invalid_iin", "IIN must contain 12 digits")
        }
    }

    private fun ensureConsentsPresent(request: RegistrationRequest) {
        if (request.acceptedTermsVersion.isNullOrBlank() || request.acceptedPrivacyVersion.isNullOrBlank()) {
            throw RegistrationValidationException("missing_consents", "Required consents are missing")
        }
    }

    private fun validatePassword(password: String) {
        if (password.length < registrationConfig.passwordPolicy.minLength) {
            throw RegistrationValidationException("weak_password", "Password is too short")
        }
        val hasLower = password.any { it.isLowerCase() }
        val hasUpper = password.any { it.isUpperCase() }
        val hasDigit = password.any { it.isDigit() }
        val hasSymbol = password.any { !it.isLetterOrDigit() }
        if (registrationConfig.passwordPolicy.requireLower && !hasLower) {
            throw RegistrationValidationException("weak_password", "Password must contain a lowercase letter")
        }
        if (registrationConfig.passwordPolicy.requireUpper && !hasUpper) {
            throw RegistrationValidationException("weak_password", "Password must contain an uppercase letter")
        }
        if (registrationConfig.passwordPolicy.requireDigit && !hasDigit) {
            throw RegistrationValidationException("weak_password", "Password must contain a digit")
        }
        if (registrationConfig.passwordPolicy.requireSymbol && !hasSymbol) {
            throw RegistrationValidationException("weak_password", "Password must contain a symbol")
        }
        val classes = listOf(hasLower, hasUpper, hasDigit, hasSymbol).count { it }
        if (classes < 2) {
            throw RegistrationValidationException(
                "weak_password",
                "Password must include at least two character classes"
            )
        }
        val topBreached = setOf("123456", "password", "qwerty", "12345678")
        if (registrationConfig.passwordPolicy.forbidBreachedTopN && topBreached.contains(password.lowercase())) {
            throw RegistrationValidationException("weak_password", "Password is in breach list")
        }
    }

    private fun normalizeEmail(email: String) = email.trim().lowercase()

    private fun normalizeIin(iin: String) = iin.filter { it.isDigit() }

    private data class OperationResult<T>(
        val response: T,
        val emailLink: String?,
        val shouldSendEmail: Boolean,
    )

    private data class PasswordResetResult(
        val response: PasswordForgotResponse,
        val emailLink: String,
    )

    private data class UserRecord(
        val userId: UUID,
        val iin: String,
        val email: String,
        val passwordHash: String,
        val displayName: String?,
        val locale: String?,
        val timezone: String?,
        val acceptedTermsVersion: String?,
        val acceptedPrivacyVersion: String?,
        val marketingOptIn: Boolean,
        val status: RegistrationStatus,
        val activationTokenExpiresAt: Instant?,
        val lastEmailSentAt: Instant?,
        val avatarId: UUID?,
        val photoUrl: String?,
        val about: String?,
        val resendCount: Int,
        val resendCountResetAt: Instant?,
        val createdAt: Instant,
        val updatedAt: Instant,
    )
}
