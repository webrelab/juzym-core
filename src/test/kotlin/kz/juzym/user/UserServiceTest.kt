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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserServiceTest {

    private lateinit var postgres: EmbeddedPostgres
    private lateinit var context: PostgresDatabaseContext
    private lateinit var userRepository: UserRepository
    private lateinit var tokenRepository: UserTokenRepository
    private lateinit var passwordHasher: PasswordHasher
    private lateinit var mailSender: RecordingMailSender
    private lateinit var service: UserService

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
        tokenRepository = ExposedUserTokenRepository(context.database)
        mailSender = RecordingMailSender()
        service = UserServiceImpl(
            database = context.database,
            userRepository = userRepository,
            tokenRepository = tokenRepository,
            mailSender = mailSender,
            passwordHasher = passwordHasher,
            config = UserServiceConfig(
                activationLinkBuilder = { token -> "https://app.test/activate/$token" },
                passwordResetLinkBuilder = { token -> "https://app.test/reset/$token" },
                deletionLinkBuilder = { token -> "https://app.test/delete/$token" },
                emailChangeLinkBuilder = { token -> "https://app.test/email/$token" }
            ),
            registrationConfig = RegistrationConfig(exposeDebugTokens = true),
            clock = FixedClock(),
        )
    }

    @AfterEach
    fun cleanup() {
        mailSender.reset()
        transaction(context.database) {
            UserTokensTable.deleteAll()
            UserRegistrationIdempotencyTable.deleteAll()
            UsersTable.deleteAll()
        }
    }

    @AfterAll
    fun tearDown() {
        context.close()
        postgres.close()
    }

    @Test
    fun `request and confirm email change updates user`() {
        val user = seedUser()

        val request = service.requestEmailChange(user.id, "new@example.com")
        val sent = assertIs<EmailChangeRequestResult.Sent>(request)
        val token = sent.link.substringAfterLast('/')

        assertEquals(sent.link, mailSender.emailChangeLink)

        val confirmation = service.confirmEmailChange(token)
        assertIs<EmailChangeConfirmationResult.Updated>(confirmation)
        assertEquals("new@example.com", userRepository.findById(user.id)?.email)
    }

    @Test
    fun `confirm email change fails for invalid token`() {
        val result = service.confirmEmailChange("invalid")
        assertIs<EmailChangeConfirmationResult.InvalidToken>(result)
    }

    private fun seedUser(): User {
        val user = User(
            id = UUID.randomUUID(),
            iin = "900101301234",
            email = "user@example.com",
            passwordHash = passwordHasher.hash("Password1!"),
            status = UserStatus.ACTIVE,
            createdAt = Instant.now()
        )
        userRepository.create(user)
        return user
    }

    private class RecordingMailSender : MailSenderStub {
        var emailChangeLink: String? = null

        override fun sendActivationEmail(to: String, activationLink: String) {}

        override fun sendPasswordResetEmail(to: String, resetLink: String) {}

        override fun sendDeletionConfirmationEmail(to: String, deletionLink: String) {}

        override fun sendEmailChangeConfirmationEmail(to: String, newEmail: String, confirmationLink: String) {
            emailChangeLink = confirmationLink
        }

        fun reset() {
            emailChangeLink = null
        }
    }

    private class FixedClock : Clock() {
        private val instant = Instant.parse("2024-01-01T00:00:00Z")

        override fun withZone(zone: java.time.ZoneId?): Clock = this

        override fun getZone(): java.time.ZoneId = ZoneOffset.UTC

        override fun instant(): Instant = instant
    }
}
