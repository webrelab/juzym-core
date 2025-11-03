package kz.juzym.registration

import kz.juzym.registration.RegistrationStatus.PENDING
import kz.juzym.registration.RegistrationStatus.ACTIVE
import java.time.Clock
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class InMemoryRegistrationService(
    private val config: RegistrationConfig = RegistrationConfig(),
    private val clock: Clock = Clock.systemUTC()
) : RegistrationService {

    private val registrations = ConcurrentHashMap<String, RegistrationRecord>()
    private val iinIndex = ConcurrentHashMap<String, String>()
    private val tokens = ConcurrentHashMap<String, TokenRecord>()
    private val passwordResetTokens = ConcurrentHashMap<String, TokenRecord>()
    private val idempotency = ConcurrentHashMap<String, RegistrationResponse>()
    private val resendCounters = ConcurrentHashMap<String, ResendCounter>()

    override fun checkEmailAvailability(email: String): EmailAvailabilityResponse {
        val normalized = normalizeEmail(email)
        validateEmail(normalized)
        val available = !registrations.containsKey(normalized)
        return EmailAvailabilityResponse(email = normalized, available = available)
    }

    override fun startRegistration(request: RegistrationRequest, idempotencyKey: String?): RegistrationResponse {
        idempotencyKey?.let { key ->
            idempotency[key]?.let { return it }
        }

        val normalizedEmail = normalizeEmail(request.email)
        val normalizedIin = normalizeIin(request.iin)
        validateEmail(normalizedEmail)
        validateIin(normalizedIin)
        ensureConsentsPresent(request)
        validatePassword(request.password)

        if (registrations.containsKey(normalizedEmail)) {
            throw RegistrationConflictException("email_already_registered", "Email is already registered")
        }
        if (iinIndex.containsKey(normalizedIin)) {
            throw RegistrationConflictException("iin_already_registered", "IIN is already registered")
        }

        val userId = UUID.randomUUID()
        val verificationToken = UUID.randomUUID().toString()
        val now = clock.instant()
        val expiresAt = now.plusSeconds(config.emailTokenTtlMinutes * 60)
        val record = RegistrationRecord(
            userId = userId,
            iin = normalizedIin,
            email = normalizedEmail,
            passwordHash = hashPassword(request.password),
            displayName = request.displayName.trim(),
            locale = request.locale,
            timezone = request.timezone,
            acceptedTermsVersion = request.acceptedTermsVersion!!,
            acceptedPrivacyVersion = request.acceptedPrivacyVersion!!,
            marketingOptIn = request.marketingOptIn ?: false,
            status = PENDING,
            emailTokenExpiresAt = expiresAt,
            verificationToken = verificationToken,
            lastEmailSentAt = now,
            avatarId = null,
            photoUrl = null,
            about = null
        )
        registrations[normalizedEmail] = record
        iinIndex[normalizedIin] = normalizedEmail
        tokens[verificationToken] = TokenRecord(userId = userId, expiresAt = expiresAt)
        val response = RegistrationResponse(
            userId = userId,
            status = PENDING,
            emailVerification = EmailVerificationInfo(
                sent = true,
                method = "link",
                expiresAt = expiresAt
            ),
            debugVerificationToken = if (config.exposeDebugTokens) verificationToken else null
        )
        idempotencyKey?.let { idempotency[it] = response }
        return response
    }

    override fun resendActivationEmail(iin: String, email: String): ResendEmailResponse {
        val normalizedIin = normalizeIin(iin)
        val normalizedEmail = normalizeEmail(email)
        val record = registrations[normalizedEmail]
        if (record == null || record.iin != normalizedIin) {
            throw RegistrationNotFoundException("not_found_if_not_pending", "Registration not found")
        }
        if (record.status != PENDING) {
            throw RegistrationConflictException("not_found_if_not_pending", "Registration is not pending")
        }
        val counter = resendCounters.computeIfAbsent(record.userId.toString()) { ResendCounter() }
        val now = clock.instant()
        val elapsed = record.lastEmailSentAt?.let { now.epochSecond - it.epochSecond } ?: Long.MAX_VALUE
        if (elapsed < config.resendCooldownSeconds) {
            throw RegistrationRateLimitException(config.resendCooldownSeconds - elapsed)
        }
        if (!counter.canResend(config.maxResendsPerDay)) {
            throw RegistrationRateLimitException(config.resendCooldownSeconds)
        }
        counter.increment()
        val previousToken = record.verificationToken
        tokens.remove(previousToken)
        val newToken = UUID.randomUUID().toString()
        val expiresAt = now.plusSeconds(config.emailTokenTtlMinutes * 60)
        record.verificationToken = newToken
        record.emailTokenExpiresAt = expiresAt
        record.lastEmailSentAt = now
        tokens[newToken] = TokenRecord(record.userId, expiresAt)
        return ResendEmailResponse(
            sent = true,
            cooldownSeconds = config.resendCooldownSeconds,
            debugVerificationToken = if (config.exposeDebugTokens) newToken else null
        )
    }

    override fun verifyEmail(token: String): VerificationResponse {
        val record = tokens[token] ?: throw RegistrationInvalidTokenException(
            code = "invalid_or_expired_token",
            messageText = "Verification token is invalid"
        )
        if (record.expiresAt.isBefore(clock.instant())) {
            tokens.remove(token)
            throw RegistrationInvalidTokenException("invalid_or_expired_token", "Verification token expired")
        }
        val registration = registrations.values.singleOrNull { it.userId == record.userId }
            ?: throw RegistrationInvalidTokenException("invalid_or_expired_token", "Verification token not found")
        if (registration.status == ACTIVE) {
            throw RegistrationConflictException("already_verified", "Email already verified")
        }
        registration.status = ACTIVE
        registration.avatarId = registration.avatarId ?: UUID.randomUUID()
        tokens.remove(token)
        val expiresAt = clock.instant().plusSeconds(3600)
        val session = SessionTokens(
            accessToken = "access-${registration.userId}",
            refreshToken = "refresh-${registration.userId}",
            expiresAt = expiresAt
        )
        return VerificationResponse(
            userId = registration.userId,
            status = ACTIVE,
            avatarId = registration.avatarId!!,
            session = session
        )
    }

    override fun completeProfile(userId: UUID, request: CompleteProfileRequest): CompleteProfileResponse {
        val registration = registrations.values.singleOrNull { it.userId == userId }
            ?: throw RegistrationUnauthorizedException("User not found")
        if (registration.status != ACTIVE) {
            throw RegistrationConflictException("avatar_locked", "Profile cannot be updated in current status")
        }
        val updated = mutableListOf<String>()
        request.photoUrl?.let {
            registration.photoUrl = it
            updated += "photoUrl"
        }
        request.about?.let {
            registration.about = it
            updated += "about"
        }
        request.locale?.let {
            registration.locale = it
            updated += "locale"
        }
        request.timezone?.let {
            registration.timezone = it
            updated += "timezone"
        }
        if (updated.isEmpty()) {
            return CompleteProfileResponse(registration.avatarId ?: UUID.randomUUID(), emptyList())
        }
        registration.avatarId = registration.avatarId ?: UUID.randomUUID()
        return CompleteProfileResponse(
            avatarId = registration.avatarId!!,
            updated = updated
        )
    }

    override fun getPasswordPolicy(): PasswordPolicyResponse = config.passwordPolicy

    override fun getLimits(): RegistrationLimitsResponse = RegistrationLimitsResponse(
        maxAttemptsPerIpPerHour = config.maxAttemptsPerIpPerHour,
        maxResendsPerDay = config.maxResendsPerDay,
        emailTokenTtlMinutes = config.emailTokenTtlMinutes.toInt()
    )

    override fun requestPasswordReset(email: String): PasswordForgotResponse {
        val normalizedEmail = normalizeEmail(email)
        val registration = registrations[normalizedEmail]
            ?: throw RegistrationNotFoundException("email_not_found", "Email not found")
        val token = UUID.randomUUID().toString()
        val expiresAt = clock.instant().plusSeconds(3600)
        passwordResetTokens[token] = TokenRecord(registration.userId, expiresAt)
        return PasswordForgotResponse(
            sent = true,
            debugResetToken = if (config.exposeDebugTokens) token else null
        )
    }

    override fun resetPassword(token: String, newPassword: String): PasswordResetResponse {
        validatePassword(newPassword)
        val record = passwordResetTokens[token]
            ?: throw RegistrationInvalidTokenException("invalid_or_expired_token", "Reset token invalid")
        if (record.expiresAt.isBefore(clock.instant())) {
            passwordResetTokens.remove(token)
            throw RegistrationInvalidTokenException("invalid_or_expired_token", "Reset token expired")
        }
        val registration = registrations.values.singleOrNull { it.userId == record.userId }
            ?: throw RegistrationInvalidTokenException("invalid_or_expired_token", "Reset token invalid")
        registration.passwordHash = hashPassword(newPassword)
        passwordResetTokens.remove(token)
        return PasswordResetResponse(reset = true)
    }

    override fun getRegistrationStatus(email: String): RegistrationStatusResponse {
        val normalizedEmail = normalizeEmail(email)
        val registration = registrations[normalizedEmail]
            ?: throw RegistrationNotFoundException("not_found", "Email not registered")
        val now = clock.instant()
        val cooldown = registration.lastEmailSentAt?.let { last ->
            val secondsPassed = now.epochSecond - last.epochSecond
            if (secondsPassed >= config.resendCooldownSeconds) 0 else config.resendCooldownSeconds - secondsPassed
        } ?: 0
        return RegistrationStatusResponse(
            email = normalizedEmail,
            status = registration.status,
            verification = VerificationStatus(
                required = registration.status != ACTIVE,
                resentCooldownSec = cooldown
            )
        )
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
        var passwordHash: String,
        val displayName: String,
        var locale: String?,
        var timezone: String?,
        val acceptedTermsVersion: String,
        val acceptedPrivacyVersion: String,
        val marketingOptIn: Boolean,
        var status: RegistrationStatus,
        var emailTokenExpiresAt: Instant,
        var verificationToken: String,
        var lastEmailSentAt: Instant?,
        var avatarId: UUID?,
        var photoUrl: String?,
        var about: String?
    )

    private data class TokenRecord(
        val userId: UUID,
        val expiresAt: Instant
    )

    private inner class ResendCounter {
        private val counter = AtomicInteger(0)
        private var lastReset: Instant = clock.instant()

        fun canResend(limit: Int): Boolean {
            resetIfNeeded()
            return counter.get() < limit
        }

        fun increment() {
            resetIfNeeded()
            counter.incrementAndGet()
        }

        private fun resetIfNeeded() {
            val now = clock.instant()
            if (now.atOffset(ZoneOffset.UTC).dayOfYear != lastReset.atOffset(ZoneOffset.UTC).dayOfYear) {
                counter.set(0)
                lastReset = now
            }
        }
    }
}
