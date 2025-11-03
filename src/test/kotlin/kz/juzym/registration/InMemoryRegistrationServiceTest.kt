package kz.juzym.registration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class InMemoryRegistrationServiceTest {

    private val baseInstant: Instant = Instant.parse("2024-01-01T00:00:00Z")

    @Test
    fun `email availability reflects registration state`() {
        val (service, _) = createService()
        val email = "test@example.com"

        assertTrue(service.checkEmailAvailability(email).available)

        service.startRegistration(sampleRequest(email = email), null)

        assertFalse(service.checkEmailAvailability(email).available)
    }

    @Test
    fun `start registration is idempotent with key`() {
        val (service, _) = createService()
        val request = sampleRequest(email = "idempotent@example.com")
        val key = UUID.randomUUID().toString()

        val first = service.startRegistration(request, key)
        val second = service.startRegistration(request, key)

        assertEquals(first, second)
    }

    @Test
    fun `resend activation email after cooldown issues new token`() {
        val (service, clock) = createService()
        val request = sampleRequest(email = "resend@example.com")
        val response = service.startRegistration(request, null)

        assertNotNull(response.debugVerificationToken)

        assertThrows<RegistrationRateLimitException> {
            service.resendActivationEmail(request.iin, request.email)
        }

        clock.advanceSeconds(2)
        val resend = service.resendActivationEmail(request.iin, request.email)

        assertTrue(resend.sent)
        assertNotNull(resend.debugVerificationToken)
        assertNotNull(resend.cooldownSeconds)
    }

    @Test
    fun `verify email activates registration and issues session`() {
        val (service, clock) = createService()
        val request = sampleRequest(email = "verify@example.com")
        val registration = service.startRegistration(request, null)
        val token = registration.debugVerificationToken!!

        val verification = service.verifyEmail(token)

        assertEquals(RegistrationStatus.active, verification.status)
        assertNotNull(verification.session.accessToken)

        assertThrows<RegistrationInvalidTokenException> {
            service.verifyEmail(token)
        }

        clock.advanceSeconds(3600)
        val status = service.getRegistrationStatus(request.email)
        assertEquals(RegistrationStatus.active, status.status)
        assertFalse(status.verification.required)
    }

    @Test
    fun `complete profile updates mutable fields`() {
        val (service, _) = createService()
        val request = sampleRequest(email = "profile@example.com")
        val registration = service.startRegistration(request, null)
        service.verifyEmail(registration.debugVerificationToken!!)

        val result = service.completeProfile(
            userId = registration.userId,
            request = CompleteProfileRequest(
                photoUrl = "http://example.com/avatar.png",
                about = "About me",
                locale = "ru-KZ",
                timezone = "Asia/Almaty"
            )
        )

        assertEquals(setOf("photoUrl", "about", "locale", "timezone"), result.updated.toSet())
        assertNotNull(result.avatarId)
    }

    @Test
    fun `password reset flow generates and consumes token`() {
        val (service, clock) = createService()
        val request = sampleRequest(email = "reset@example.com")
        val registration = service.startRegistration(request, null)
        service.verifyEmail(registration.debugVerificationToken!!)

        val forgot = service.requestPasswordReset(request.email)
        val resetToken = forgot.debugResetToken!!

        clock.advanceSeconds(1)
        val reset = service.resetPassword(resetToken, "StrongPass1!")
        assertTrue(reset.reset)

        assertThrows<RegistrationInvalidTokenException> {
            service.resetPassword(resetToken, "AnotherPass1!")
        }
    }

    @Test
    fun `request password reset for unknown email throws`() {
        val (service, _) = createService()

        assertThrows<RegistrationNotFoundException> {
            service.requestPasswordReset("missing@example.com")
        }
    }

    @Test
    fun `password policy and limits reflect configuration`() {
        val customConfig = RegistrationConfig(
            emailTokenTtlMinutes = 15,
            resendCooldownSeconds = 2,
            maxResendsPerDay = 2,
            maxAttemptsPerIpPerHour = 5,
            passwordPolicy = PasswordPolicyResponse(
                minLength = 12,
                requireUpper = true,
                requireLower = true,
                requireDigit = true,
                requireSymbol = false,
                forbidBreachedTopN = true
            ),
            exposeDebugTokens = true
        )
        val clock = MutableClock(baseInstant)
        val service = InMemoryRegistrationService(customConfig, clock)

        val policy = service.getPasswordPolicy()
        val limits = service.getLimits()

        assertEquals(customConfig.passwordPolicy, policy)
        assertEquals(customConfig.maxAttemptsPerIpPerHour, limits.maxAttemptsPerIpPerHour)
        assertEquals(customConfig.maxResendsPerDay, limits.maxResendsPerDay)
        assertEquals(customConfig.emailTokenTtlMinutes.toInt(), limits.emailTokenTtlMinutes)
    }

    private fun createService(): Pair<InMemoryRegistrationService, MutableClock> {
        val clock = MutableClock(baseInstant)
        val config = RegistrationConfig(
            resendCooldownSeconds = 1,
            maxResendsPerDay = 5,
            exposeDebugTokens = true
        )
        return InMemoryRegistrationService(config, clock) to clock
    }

    private fun sampleRequest(
        iin: String = "123456789012",
        email: String,
        password: String = "Password1!"
    ): RegistrationRequest = RegistrationRequest(
        iin = iin,
        email = email,
        password = password,
        displayName = "Test User",
        locale = "ru-KZ",
        timezone = "Asia/Almaty",
        acceptedTermsVersion = "v1",
        acceptedPrivacyVersion = "v1",
        marketingOptIn = true
    )

    private class MutableClock(initial: Instant) : Clock() {
        private var currentInstant: Instant = initial

        override fun withZone(zone: ZoneId?): Clock = this

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun instant(): Instant = currentInstant

        fun advanceSeconds(seconds: Long) {
            currentInstant = currentInstant.plusSeconds(seconds)
        }
    }
}
