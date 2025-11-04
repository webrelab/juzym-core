package kz.juzym.registration

import kz.juzym.registration.RegistrationStatus.ACTIVE
import kz.juzym.registration.RegistrationStatus.PENDING
import kz.juzym.registration.RegistrationTokenType.EMAIL_VERIFICATION
import kz.juzym.registration.RegistrationTokenType.PASSWORD_RESET
import kz.juzym.user.toOffsetDateTime
import kz.juzym.user.toInstantUtc
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class RegistrationServiceImpl(
    private val database: org.jetbrains.exposed.sql.Database,
    private val config: RegistrationConfig = RegistrationConfig(),
    private val clock: Clock = Clock.systemUTC(),
) : RegistrationService {

    override fun checkEmailAvailability(email: String): EmailAvailabilityResponse {
        val normalized = normalizeEmail(email)
        validateEmail(normalized)
        val exists = transaction(database) {
            RegistrationsTable.select { RegistrationsTable.email eq normalized }.empty().not()
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

        return transaction(database) {
            idempotencyKey?.let { key ->
                findIdempotentResponseInTransaction(key)?.let { return@transaction it }
            }

            if (!RegistrationsTable.select { RegistrationsTable.email eq normalizedEmail }.empty()) {
                throw RegistrationConflictException("email_already_registered", "Email is already registered")
            }
            if (!RegistrationsTable.select { RegistrationsTable.iin eq normalizedIin }.empty()) {
                throw RegistrationConflictException("iin_already_registered", "IIN is already registered")
            }

            val userId = UUID.randomUUID()
            val now = clock.instant()
            val verificationToken = UUID.randomUUID().toString()
            val expiresAt = now.plusSeconds(config.emailTokenTtlMinutes * 60)

            RegistrationsTable.insert { statement ->
                statement[RegistrationsTable.id] = EntityID(userId, RegistrationsTable)
                statement[RegistrationsTable.iin] = normalizedIin
                statement[RegistrationsTable.email] = normalizedEmail
                statement[RegistrationsTable.passwordHash] = hashPassword(request.password)
                statement[RegistrationsTable.displayName] = request.displayName.trim()
                statement[RegistrationsTable.locale] = request.locale
                statement[RegistrationsTable.timezone] = request.timezone
                statement[RegistrationsTable.acceptedTermsVersion] = request.acceptedTermsVersion!!
                statement[RegistrationsTable.acceptedPrivacyVersion] = request.acceptedPrivacyVersion!!
                statement[RegistrationsTable.marketingOptIn] = request.marketingOptIn ?: false
                statement[RegistrationsTable.status] = PENDING
                statement[RegistrationsTable.emailTokenExpiresAt] = expiresAt.toOffsetDateTime()
                statement[RegistrationsTable.verificationToken] = verificationToken
                statement[RegistrationsTable.lastEmailSentAt] = now.toOffsetDateTime()
                statement[RegistrationsTable.avatarId] = null
                statement[RegistrationsTable.photoUrl] = null
                statement[RegistrationsTable.about] = null
                statement[RegistrationsTable.resendCount] = 0
                statement[RegistrationsTable.resendCountResetAt] = now.toOffsetDateTime()
                statement[RegistrationsTable.createdAt] = now.toOffsetDateTime()
                statement[RegistrationsTable.updatedAt] = now.toOffsetDateTime()
            }

            RegistrationTokensTable.insert { statement ->
                statement[RegistrationTokensTable.token] = verificationToken
                statement[RegistrationTokensTable.userId] = userId
                statement[RegistrationTokensTable.type] = EMAIL_VERIFICATION
                statement[RegistrationTokensTable.expiresAt] = expiresAt.toOffsetDateTime()
                statement[RegistrationTokensTable.createdAt] = now.toOffsetDateTime()
            }

            val response = RegistrationResponse(
                userId = userId,
                status = PENDING,
                emailVerification = EmailVerificationInfo(
                    sent = true,
                    method = "link",
                    expiresAt = expiresAt,
                ),
                debugVerificationToken = if (config.exposeDebugTokens) verificationToken else null,
            )

            idempotencyKey?.let { key ->
                RegistrationIdempotencyTable.insert { statement ->
                    statement[RegistrationIdempotencyTable.key] = key
                    statement[RegistrationIdempotencyTable.registrationId] = userId
                    statement[RegistrationIdempotencyTable.responseStatus] = response.status
                    statement[RegistrationIdempotencyTable.responseVerificationExpiresAt] = expiresAt.toOffsetDateTime()
                    statement[RegistrationIdempotencyTable.responseDebugToken] = response.debugVerificationToken
                    statement[RegistrationIdempotencyTable.createdAt] = now.toOffsetDateTime()
                }
            }

            response
        }
    }

    override fun resendActivationEmail(iin: String, email: String): ResendEmailResponse {
        val normalizedIin = normalizeIin(iin)
        val normalizedEmail = normalizeEmail(email)
        val now = clock.instant()

        return transaction(database) {
            val row = RegistrationsTable.select { RegistrationsTable.email eq normalizedEmail }.singleOrNull()
                ?: throw RegistrationNotFoundException("not_found_if_not_pending", "Registration not found")
            val registration = row.toRegistration()
            if (registration.iin != normalizedIin) {
                throw RegistrationNotFoundException("not_found_if_not_pending", "Registration not found")
            }
            if (registration.status != PENDING) {
                throw RegistrationConflictException("not_found_if_not_pending", "Registration is not pending")
            }

            registration.lastEmailSentAt?.let { lastSent ->
                val elapsed = Duration.between(lastSent, now).seconds
                if (elapsed < config.resendCooldownSeconds) {
                    throw RegistrationRateLimitException(config.resendCooldownSeconds - elapsed)
                }
            }

            val sameDay = registration.resendCountResetAt?.let { resetAt ->
                val resetOffset = resetAt.atOffset(ZoneOffset.UTC)
                val nowOffset = now.atOffset(ZoneOffset.UTC)
                resetOffset.dayOfYear == nowOffset.dayOfYear && resetOffset.year == nowOffset.year
            } ?: false

            val resendCount = if (sameDay) registration.resendCount else 0
            if (resendCount >= config.maxResendsPerDay) {
                throw RegistrationRateLimitException(config.resendCooldownSeconds)
            }

            RegistrationTokensTable.deleteWhere {
                (RegistrationTokensTable.userId eq registration.userId) and
                    (RegistrationTokensTable.type eq EMAIL_VERIFICATION)
            }

            val newToken = UUID.randomUUID().toString()
            val expiresAt = now.plusSeconds(config.emailTokenTtlMinutes * 60)

            RegistrationTokensTable.insert { statement ->
                statement[RegistrationTokensTable.token] = newToken
                statement[RegistrationTokensTable.userId] = registration.userId
                statement[RegistrationTokensTable.type] = EMAIL_VERIFICATION
                statement[RegistrationTokensTable.expiresAt] = expiresAt.toOffsetDateTime()
                statement[RegistrationTokensTable.createdAt] = now.toOffsetDateTime()
            }

            RegistrationsTable.update({ RegistrationsTable.id eq registration.userId }) { statement ->
                statement[RegistrationsTable.verificationToken] = newToken
                statement[RegistrationsTable.emailTokenExpiresAt] = expiresAt.toOffsetDateTime()
                statement[RegistrationsTable.lastEmailSentAt] = now.toOffsetDateTime()
                statement[RegistrationsTable.resendCount] = resendCount + 1
                statement[RegistrationsTable.resendCountResetAt] = if (sameDay) registration.resendCountResetAt?.toOffsetDateTime() else now.toOffsetDateTime()
                statement[RegistrationsTable.updatedAt] = now.toOffsetDateTime()
            }

            ResendEmailResponse(
                sent = true,
                cooldownSeconds = config.resendCooldownSeconds,
                debugVerificationToken = if (config.exposeDebugTokens) newToken else null,
            )
        }
    }

    override fun verifyEmail(token: String): VerificationResponse {
        val now = clock.instant()
        return transaction(database) {
            val tokenRow = RegistrationTokensTable.select {
                (RegistrationTokensTable.token eq token) and (RegistrationTokensTable.type eq EMAIL_VERIFICATION)
            }.singleOrNull() ?: throw RegistrationInvalidTokenException(
                code = "invalid_or_expired_token",
                messageText = "Verification token is invalid",
            )

            val expiresAt = tokenRow[RegistrationTokensTable.expiresAt].toInstantUtc()
            if (expiresAt.isBefore(now)) {
                RegistrationTokensTable.deleteWhere { RegistrationTokensTable.token eq token }
                throw RegistrationInvalidTokenException("invalid_or_expired_token", "Verification token expired")
            }

            val userId = tokenRow[RegistrationTokensTable.userId]
            val registrationRow = RegistrationsTable.select { RegistrationsTable.id eq userId }.singleOrNull()
                ?: throw RegistrationInvalidTokenException("invalid_or_expired_token", "Verification token not found")
            val registration = registrationRow.toRegistration()
            if (registration.status == ACTIVE) {
                throw RegistrationConflictException("already_verified", "Email already verified")
            }

            val avatarId = registration.avatarId ?: UUID.randomUUID()
            RegistrationsTable.update({ RegistrationsTable.id eq registration.userId }) { statement ->
                statement[RegistrationsTable.status] = ACTIVE
                statement[RegistrationsTable.avatarId] = avatarId
                statement[RegistrationsTable.verificationToken] = null
                statement[RegistrationsTable.updatedAt] = now.toOffsetDateTime()
            }
            RegistrationTokensTable.deleteWhere { RegistrationTokensTable.token eq token }

            val sessionExpiresAt = now.plusSeconds(3600)
            VerificationResponse(
                userId = registration.userId,
                status = ACTIVE,
                avatarId = avatarId,
                session = SessionTokens(
                    accessToken = "access-${registration.userId}",
                    refreshToken = "refresh-${registration.userId}",
                    expiresAt = sessionExpiresAt,
                ),
            )
        }
    }

    override fun completeProfile(userId: UUID, request: CompleteProfileRequest): CompleteProfileResponse {
        val now = clock.instant()
        return transaction(database) {
            val registrationRow = RegistrationsTable.select { RegistrationsTable.id eq userId }.singleOrNull()
                ?: throw RegistrationUnauthorizedException("User not found")
            val registration = registrationRow.toRegistration()
            if (registration.status != ACTIVE) {
                throw RegistrationConflictException("avatar_locked", "Profile cannot be updated in current status")
            }

            val updatedFields = mutableListOf<String>()
            var avatarId = registration.avatarId
            var changed = false

            RegistrationsTable.update({ RegistrationsTable.id eq userId }) { statement ->
                request.photoUrl?.let {
                    statement[RegistrationsTable.photoUrl] = it
                    updatedFields += "photoUrl"
                    changed = true
                }
                request.about?.let {
                    statement[RegistrationsTable.about] = it
                    updatedFields += "about"
                    changed = true
                }
                request.locale?.let {
                    statement[RegistrationsTable.locale] = it
                    updatedFields += "locale"
                    changed = true
                }
                request.timezone?.let {
                    statement[RegistrationsTable.timezone] = it
                    updatedFields += "timezone"
                    changed = true
                }
                if (changed) {
                    if (avatarId == null) {
                        avatarId = UUID.randomUUID()
                    }
                    statement[RegistrationsTable.avatarId] = avatarId
                    statement[RegistrationsTable.updatedAt] = now.toOffsetDateTime()
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

    override fun getPasswordPolicy(): PasswordPolicyResponse = config.passwordPolicy

    override fun getLimits(): RegistrationLimitsResponse = RegistrationLimitsResponse(
        maxAttemptsPerIpPerHour = config.maxAttemptsPerIpPerHour,
        maxResendsPerDay = config.maxResendsPerDay,
        emailTokenTtlMinutes = config.emailTokenTtlMinutes.toInt(),
    )

    override fun requestPasswordReset(email: String): PasswordForgotResponse {
        val normalizedEmail = normalizeEmail(email)
        val now = clock.instant()
        return transaction(database) {
            val registrationRow = RegistrationsTable.select { RegistrationsTable.email eq normalizedEmail }.singleOrNull()
                ?: throw RegistrationNotFoundException("email_not_found", "Email not found")
            val registration = registrationRow.toRegistration()
            val token = UUID.randomUUID().toString()
            val expiresAt = now.plusSeconds(3600)

            RegistrationTokensTable.insert { statement ->
                statement[RegistrationTokensTable.token] = token
                statement[RegistrationTokensTable.userId] = registration.userId
                statement[RegistrationTokensTable.type] = PASSWORD_RESET
                statement[RegistrationTokensTable.expiresAt] = expiresAt.toOffsetDateTime()
                statement[RegistrationTokensTable.createdAt] = now.toOffsetDateTime()
            }

            PasswordForgotResponse(
                sent = true,
                debugResetToken = if (config.exposeDebugTokens) token else null,
            )
        }
    }

    override fun resetPassword(token: String, newPassword: String): PasswordResetResponse {
        validatePassword(newPassword)
        val now = clock.instant()
        return transaction(database) {
            val tokenRow = RegistrationTokensTable.select {
                (RegistrationTokensTable.token eq token) and (RegistrationTokensTable.type eq PASSWORD_RESET)
            }.singleOrNull() ?: throw RegistrationInvalidTokenException(
                code = "invalid_or_expired_token",
                messageText = "Reset token invalid",
            )
            val expiresAt = tokenRow[RegistrationTokensTable.expiresAt].toInstantUtc()
            if (expiresAt.isBefore(now)) {
                RegistrationTokensTable.deleteWhere { RegistrationTokensTable.token eq token }
                throw RegistrationInvalidTokenException("invalid_or_expired_token", "Reset token expired")
            }
            val userId = tokenRow[RegistrationTokensTable.userId]
            val updated = RegistrationsTable.update({ RegistrationsTable.id eq userId }) { statement ->
                statement[RegistrationsTable.passwordHash] = hashPassword(newPassword)
                statement[RegistrationsTable.updatedAt] = now.toOffsetDateTime()
            }
            if (updated == 0) {
                throw RegistrationInvalidTokenException("invalid_or_expired_token", "Reset token invalid")
            }
            RegistrationTokensTable.deleteWhere { RegistrationTokensTable.token eq token }
            PasswordResetResponse(reset = true)
        }
    }

    override fun getRegistrationStatus(email: String): RegistrationStatusResponse {
        val normalizedEmail = normalizeEmail(email)
        val now = clock.instant()
        return transaction(database) {
            val registrationRow = RegistrationsTable.select { RegistrationsTable.email eq normalizedEmail }.singleOrNull()
                ?: throw RegistrationNotFoundException("not_found", "Email not registered")
            val registration = registrationRow.toRegistration()
            val cooldown = registration.lastEmailSentAt?.let { last ->
                val elapsed = Duration.between(last, now).seconds
                if (elapsed >= config.resendCooldownSeconds) 0 else config.resendCooldownSeconds - elapsed
            } ?: 0
            RegistrationStatusResponse(
                email = normalizedEmail,
                status = registration.status,
                verification = VerificationStatus(
                    required = registration.status != ACTIVE,
                    resentCooldownSec = cooldown,
                ),
            )
        }
    }

    private fun findIdempotentResponse(key: String): RegistrationResponse? = transaction(database) {
        RegistrationIdempotencyTable.select { RegistrationIdempotencyTable.key eq key }
            .singleOrNull()
            ?.toRegistrationResponse()
    }

    private fun findIdempotentResponseInTransaction(key: String): RegistrationResponse? {
        return RegistrationIdempotencyTable.select { RegistrationIdempotencyTable.key eq key }
            .singleOrNull()
            ?.toRegistrationResponse()
    }

    private fun ResultRow.toRegistration(): RegistrationRecord = RegistrationRecord(
        userId = this[RegistrationsTable.id].value,
        iin = this[RegistrationsTable.iin],
        email = this[RegistrationsTable.email],
        passwordHash = this[RegistrationsTable.passwordHash],
        displayName = this[RegistrationsTable.displayName],
        locale = this[RegistrationsTable.locale],
        timezone = this[RegistrationsTable.timezone],
        acceptedTermsVersion = this[RegistrationsTable.acceptedTermsVersion],
        acceptedPrivacyVersion = this[RegistrationsTable.acceptedPrivacyVersion],
        marketingOptIn = this[RegistrationsTable.marketingOptIn],
        status = this[RegistrationsTable.status],
        emailTokenExpiresAt = this[RegistrationsTable.emailTokenExpiresAt].toInstantUtc(),
        verificationToken = this[RegistrationsTable.verificationToken],
        lastEmailSentAt = this[RegistrationsTable.lastEmailSentAt]?.toInstantUtc(),
        avatarId = this[RegistrationsTable.avatarId],
        photoUrl = this[RegistrationsTable.photoUrl],
        about = this[RegistrationsTable.about],
        resendCount = this[RegistrationsTable.resendCount],
        resendCountResetAt = this[RegistrationsTable.resendCountResetAt]?.toInstantUtc(),
        createdAt = this[RegistrationsTable.createdAt].toInstantUtc(),
        updatedAt = this[RegistrationsTable.updatedAt].toInstantUtc(),
    )

    private fun ResultRow.toRegistrationResponse(): RegistrationResponse = RegistrationResponse(
        userId = this[RegistrationIdempotencyTable.registrationId],
        status = this[RegistrationIdempotencyTable.responseStatus],
        emailVerification = EmailVerificationInfo(
            sent = true,
            method = "link",
            expiresAt = this[RegistrationIdempotencyTable.responseVerificationExpiresAt].toInstantUtc(),
        ),
        debugVerificationToken = this[RegistrationIdempotencyTable.responseDebugToken],
    )

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
        if (password.length < config.passwordPolicy.minLength) {
            throw RegistrationValidationException("weak_password", "Password is too short")
        }
        val hasLower = password.any { it.isLowerCase() }
        val hasUpper = password.any { it.isUpperCase() }
        val hasDigit = password.any { it.isDigit() }
        val hasSymbol = password.any { !it.isLetterOrDigit() }
        if (config.passwordPolicy.requireLower && !hasLower) {
            throw RegistrationValidationException("weak_password", "Password must contain a lowercase letter")
        }
        if (config.passwordPolicy.requireUpper && !hasUpper) {
            throw RegistrationValidationException("weak_password", "Password must contain an uppercase letter")
        }
        if (config.passwordPolicy.requireDigit && !hasDigit) {
            throw RegistrationValidationException("weak_password", "Password must contain a digit")
        }
        if (config.passwordPolicy.requireSymbol && !hasSymbol) {
            throw RegistrationValidationException("weak_password", "Password must contain a symbol")
        }
        val classes = listOf(hasLower, hasUpper, hasDigit, hasSymbol).count { it }
        if (classes < 2) {
            throw RegistrationValidationException("weak_password", "Password must include at least two character classes")
        }
        val topBreached = setOf("123456", "password", "qwerty", "12345678")
        if (config.passwordPolicy.forbidBreachedTopN && topBreached.contains(password.lowercase())) {
            throw RegistrationValidationException("weak_password", "Password is in breach list")
        }
    }

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(password.toByteArray()).joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }
    }

    private fun normalizeEmail(email: String) = email.trim().lowercase()

    private fun normalizeIin(iin: String) = iin.filter { it.isDigit() }

    private data class RegistrationRecord(
        val userId: UUID,
        val iin: String,
        val email: String,
        val passwordHash: String,
        val displayName: String,
        val locale: String?,
        val timezone: String?,
        val acceptedTermsVersion: String,
        val acceptedPrivacyVersion: String,
        val marketingOptIn: Boolean,
        val status: RegistrationStatus,
        val emailTokenExpiresAt: Instant,
        val verificationToken: String?,
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
