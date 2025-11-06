package kz.juzym.user

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kz.juzym.config.DatabaseFactory
import kz.juzym.config.PostgresConfig
import kz.juzym.config.PostgresDatabaseContext
import kz.juzym.user.security.BcryptPasswordHasher
import kz.juzym.user.security.PasswordHasher
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserRegistrationServiceTest {

    private lateinit var postgres: EmbeddedPostgres
    private lateinit var context: PostgresDatabaseContext
    private lateinit var userRepository: UserRepository
    private lateinit var userRoleRepository: UserRoleRepository
    private lateinit var tokenRepository: UserTokenRepository
    private lateinit var passwordHasher: PasswordHasher
    private lateinit var mailSender: TestMailSender

    private val baseInstant: Instant = Instant.parse("2024-01-01T00:00:00Z")

    @BeforeAll
    fun setup() {
        postgres = EmbeddedPostgres.builder().start()
        val config = PostgresConfig(
            jdbcUrl = postgres.getJdbcUrl("postgres", "postgres"),
            user = "postgres",
            password = "",
        )
        val factory = DatabaseFactory(config)
        context = factory.connect()
        factory.ensureSchema(context)
        passwordHasher = BcryptPasswordHasher(logRounds = 4)
        userRepository = ExposedUserRepository(context.database, passwordHasher)
        userRoleRepository = ExposedUserRoleRepository(context.database)
        tokenRepository = ExposedUserTokenRepository(context.database)
        mailSender = TestMailSender()
    }

    @AfterEach
    fun cleanDatabase() {
        mailSender.reset()
        transaction(context.database) {
            UserRegistrationIdempotencyTable.deleteAll()
            UserTokensTable.deleteAll()
            UserRolesTable.deleteAll()
            UsersTable.deleteAll()
        }
    }

    @AfterAll
    fun tearDown() {
        context.close()
        postgres.close()
    }

    @Test
    fun `email availability reflects registration and user state`() {
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
        assertEquals(1, resend.cooldownSeconds)
    }

    @Test
    fun `verify email activates registration creates user and issues session`() {
        val (service, _) = createService()
        val request = sampleRequest(email = "verify@example.com")
        val registration = service.startRegistration(request, null)
        val token = registration.debugVerificationToken!!

        val verification = service.verifyEmail(token)

        assertEquals(RegistrationStatus.ACTIVE, verification.status)
        assertNotNull(verification.session.accessToken)
        assertNotNull(userRepository.findByEmail(request.email))
        assertTrue(userRoleRepository.findRoles(registration.userId).contains(Role.USER))

        assertThrows<RegistrationInvalidTokenException> {
            service.verifyEmail(token)
        }
    }

    @Test
    fun `complete profile updates selected fields`() {
        val (service, _) = createService()
        val request = sampleRequest(email = "profile@example.com")
        val registration = service.startRegistration(request, null)
        service.verifyEmail(registration.debugVerificationToken!!)

        val result = service.completeProfile(
            registration.userId,
            CompleteProfileRequest(photoUrl = "https://cdn.example.com/avatar.png", about = "QA")
        )

        assertEquals(listOf("photoUrl", "about"), result.updated)
        assertNotNull(result.avatarId)
    }

    @Test
    fun `request password reset issues token`() {
        val (service, _) = createService()
        val request = sampleRequest(email = "reset@example.com")
        val registration = service.startRegistration(request, null)
        service.verifyEmail(registration.debugVerificationToken!!)

        val reset = service.requestPasswordReset(request.email)

        assertTrue(reset.sent)
        assertNotNull(reset.debugResetToken)
    }

    @Test
    fun `reset password updates registration and user`() {
        val (service, _) = createService()
        val request = sampleRequest(email = "reset-update@example.com")
        val registration = service.startRegistration(request, null)
        service.verifyEmail(registration.debugVerificationToken!!)
        val forgot = service.requestPasswordReset(request.email)
        val token = forgot.debugResetToken!!

        val response = service.resetPassword(token, "AnotherPass1!")

        assertTrue(response.reset)
        val user = userRepository.findByEmail(request.email)
        assertNotNull(user)
        assertTrue(userRepository.verifyCredentials(request.iin, "AnotherPass1!"))
    }

    @Test
    fun `registration status reports cooldown`() {
        val (service, clock) = createService()
        val request = sampleRequest(email = "status@example.com")
        service.startRegistration(request, null)

        val status = service.getRegistrationStatus(request.email)

        assertEquals(RegistrationStatus.PENDING, status.status)
        assertTrue(status.verification.resentCooldownSec > 0)

        clock.advanceSeconds(5)
        val updated = service.getRegistrationStatus(request.email)
        assertEquals(0, updated.verification.resentCooldownSec)
    }

    private fun createService(
        config: RegistrationConfig = defaultConfig()
    ): Pair<UserService, MutableClock> {
        val clock = MutableClock(baseInstant)
        val service = UserServiceImpl(
            database = context.database,
            userRepository = userRepository,
            userRoleRepository = userRoleRepository,
            tokenRepository = tokenRepository,
            mailSender = mailSender,
            passwordHasher = passwordHasher,
            config = UserServiceConfig(
                activationLinkBuilder = { it },
                passwordResetLinkBuilder = { it },
                deletionLinkBuilder = { it },
                emailChangeLinkBuilder = { it }
            ),
            registrationConfig = config,
            clock = clock
        )
        return service to clock
    }

    private fun defaultConfig(): RegistrationConfig = RegistrationConfig(
        resendCooldownSeconds = 1,
        maxResendsPerDay = 5,
        exposeDebugTokens = true,
        passwordPolicy = PasswordPolicyResponse(
            minLength = 8,
            requireLower = true,
            requireUpper = true,
            requireDigit = true,
            requireSymbol = false,
            forbidBreachedTopN = true
        )
    )

    private fun sampleRequest(
        iin: String = "123456789012",
        email: String,
        password: String = "Password1!",
    ): RegistrationRequest = RegistrationRequest(
        iin = iin,
        email = email,
        password = password,
        displayName = "Test User",
        locale = "ru-KZ",
        timezone = "Asia/Almaty",
        acceptedTermsVersion = "v1",
        acceptedPrivacyVersion = "v1",
        marketingOptIn = true,
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

    private class TestMailSender : MailSenderStub {
        private val sentEmails = mutableListOf<String>()

        override fun sendActivationEmail(to: String, activationLink: String) {
            sentEmails += activationLink
        }

        override fun sendPasswordResetEmail(to: String, resetLink: String) {
            sentEmails += resetLink
        }

        override fun sendDeletionConfirmationEmail(to: String, deletionLink: String) {
            sentEmails += deletionLink
        }

        override fun sendEmailChangeConfirmationEmail(to: String, newEmail: String, confirmationLink: String) {
            sentEmails += confirmationLink
        }

        fun reset() {
            sentEmails.clear()
        }
    }
}
