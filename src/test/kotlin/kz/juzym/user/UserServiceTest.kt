package kz.juzym.user

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kz.juzym.config.DatabaseFactory
import kz.juzym.config.PostgresConfig
import kz.juzym.config.PostgresDatabaseContext
import kz.juzym.user.security.BcryptPasswordHasher
import kz.juzym.user.security.PasswordHasher
import kz.juzym.user.security.jwt.JwtConfig
import kz.juzym.user.security.jwt.JwtService
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserServiceTest {

    private lateinit var postgres: EmbeddedPostgres
    private lateinit var context: PostgresDatabaseContext
    private lateinit var userRepository: UserRepository
    private lateinit var tokenRepository: UserTokenRepository
    private lateinit var passwordHasher: PasswordHasher
    private lateinit var mailSender: RecordingMailSender
    private lateinit var jwtService: JwtService
    private lateinit var service: UserService

    @BeforeAll
    fun setup() {
        postgres = EmbeddedPostgres.builder().start()
        val config = PostgresConfig(
            jdbcUrl = postgres.getJdbcUrl("postgres", "postgres"),
            user = "postgres",
            password = ""
        )
        val factory = DatabaseFactory(config)
        context = factory.connect()
        factory.verifyConnection(context)
        factory.ensureSchema(context)
        passwordHasher = BcryptPasswordHasher(logRounds = 4)
        userRepository = ExposedUserRepository(context.database, passwordHasher)
        tokenRepository = ExposedUserTokenRepository(context.database)
        mailSender = RecordingMailSender()
        jwtService = JwtService(JwtConfig(secret = "test-secret", issuer = "test", ttl = Duration.ofMinutes(15)))
        service = UserServiceImpl(
            userRepository = userRepository,
            tokenRepository = tokenRepository,
            mailSender = mailSender,
            passwordHasher = passwordHasher,
            jwtService = jwtService,
            config = UserServiceConfig(
                activationLinkBuilder = { token -> "https://app.test/activate/$token" },
                passwordResetLinkBuilder = { token -> "https://app.test/reset/$token" },
                deletionLinkBuilder = { token -> "https://app.test/delete/$token" },
                emailChangeLinkBuilder = { token -> "https://app.test/email/$token" }
            )
        )
    }

    @AfterEach
    fun cleanup() {
        mailSender.reset()
        transaction(context.database) {
            UserTokensTable.deleteAll()
            UsersTable.deleteAll()
        }
    }

    @AfterAll
    fun tearDown() {
        context.close()
        postgres.close()
    }

    @Test
    fun `should register new user and send activation email`() {
        val result = service.registerOrAuthenticate("900101301234", "user@example.com", "secret")

        val pending = assertIs<RegistrationResult.Pending>(result)
        assertTrue(pending.activationLink.startsWith("https://app.test/activate/"))
        assertEquals(pending.activationLink, mailSender.activationLink)
    }

    @Test
    fun `should return pending existing for pending user`() {
        val initial = service.registerOrAuthenticate("900101301234", "user@example.com", "secret") as RegistrationResult.Pending
        val initialLink = mailSender.activationLink

        val result = service.registerOrAuthenticate("900101301234", "user@example.com", "secret")

        assertIs<RegistrationResult.PendingExisting>(result)
        assertEquals(initialLink, mailSender.activationLink)
    }

    @Test
    fun `should activate user via token`() {
        val result = service.registerOrAuthenticate("900101301234", "user@example.com", "secret") as RegistrationResult.Pending
        val token = result.activationLink.substringAfterLast('/')

        val activation = service.activateAccount(token)

        val activated = assertIs<ActivationResult.Activated>(activation)
        assertEquals(UserStatus.ACTIVE, userRepository.findByIin("900101301234")?.status)
        assertTrue(activated.greeting.contains("900101301234"))
    }

    @Test
    fun `should authenticate active user and issue jwt`() {
        val registerResult = service.registerOrAuthenticate("900101301234", "user@example.com", "secret") as RegistrationResult.Pending
        val token = registerResult.activationLink.substringAfterLast('/')
        service.activateAccount(token)

        val authResult = service.registerOrAuthenticate("900101301234", "user@example.com", "secret")

        val authenticated = assertIs<RegistrationResult.Authenticated>(authResult)
        val principal = jwtService.verify(authenticated.jwt)
        assertNotNull(principal)
        assertEquals("900101301234", principal.iin)
    }

    @Test
    fun `should handle password reset flow`() {
        val registerResult = service.registerOrAuthenticate("900101301234", "user@example.com", "secret") as RegistrationResult.Pending
        val activationToken = registerResult.activationLink.substringAfterLast('/')
        service.activateAccount(activationToken)

        val requestResult = service.requestPasswordReset("900101301234")
        val sent = assertIs<PasswordResetRequestResult.Sent>(requestResult)
        val resetToken = sent.link.substringAfterLast('/')
        assertEquals(sent.link, mailSender.passwordResetLink)

        val resetResult = service.resetPassword(resetToken, "new-secret")
        val success = assertIs<PasswordResetResult.Success>(resetResult)
        assertNotNull(jwtService.verify(success.jwt))
        assertTrue(userRepository.verifyCredentials("900101301234", "new-secret"))
    }

    @Test
    fun `should request and confirm deletion`() {
        val registerResult = service.registerOrAuthenticate("900101301234", "user@example.com", "secret") as RegistrationResult.Pending
        val activationToken = registerResult.activationLink.substringAfterLast('/')
        service.activateAccount(activationToken)

        val request = service.requestDeletion("900101301234")
        val sent = assertIs<DeletionRequestResult.Sent>(request)
        val deletionToken = sent.link.substringAfterLast('/')
        assertEquals(sent.link, mailSender.deletionLink)

        val confirmation = service.confirmDeletion(deletionToken)
        assertIs<DeletionConfirmationResult.Deleted>(confirmation)
        assertEquals(null, userRepository.findByIin("900101301234"))
    }

    @Test
    fun `should change email after confirmation`() {
        val registerResult = service.registerOrAuthenticate("900101301234", "user@example.com", "secret") as RegistrationResult.Pending
        val activationToken = registerResult.activationLink.substringAfterLast('/')
        service.activateAccount(activationToken)
        val user = userRepository.findByIin("900101301234")
        requireNotNull(user)

        val request = service.requestEmailChange(user.id, "new@example.com")
        val sent = assertIs<EmailChangeRequestResult.Sent>(request)
        val emailToken = sent.link.substringAfterLast('/')
        assertEquals(sent.link, mailSender.emailChangeLink)

        val confirmation = service.confirmEmailChange(emailToken)
        assertIs<EmailChangeConfirmationResult.Updated>(confirmation)
        assertEquals("new@example.com", userRepository.findByIin("900101301234")?.email)
    }

    @Test
    fun `should block user`() {
        val registerResult = service.registerOrAuthenticate("900101301234", "user@example.com", "secret") as RegistrationResult.Pending
        val activationToken = registerResult.activationLink.substringAfterLast('/')
        service.activateAccount(activationToken)
        val user = userRepository.findByIin("900101301234")
        requireNotNull(user)

        service.blockUser(user.id)

        assertEquals(UserStatus.BLOCKED, userRepository.findByIin("900101301234")?.status)
    }
}

private class RecordingMailSender : MailSenderStub {
    var activationLink: String? = null
    var passwordResetLink: String? = null
    var deletionLink: String? = null
    var emailChangeLink: String? = null

    override fun sendActivationEmail(to: String, activationLink: String) {
        this.activationLink = activationLink
    }

    override fun sendPasswordResetEmail(to: String, resetLink: String) {
        this.passwordResetLink = resetLink
    }

    override fun sendDeletionConfirmationEmail(to: String, confirmationLink: String) {
        this.deletionLink = confirmationLink
    }

    override fun sendEmailChangeConfirmationEmail(to: String, newEmail: String, confirmationLink: String) {
        this.emailChangeLink = confirmationLink
    }

    fun reset() {
        activationLink = null
        passwordResetLink = null
        deletionLink = null
        emailChangeLink = null
    }
}
