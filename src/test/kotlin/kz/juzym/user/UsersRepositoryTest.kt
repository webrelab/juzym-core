package kz.juzym.user

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kz.juzym.config.DatabaseFactory
import kz.juzym.config.PostgresConfig
import kz.juzym.config.PostgresDatabaseContext
import kz.juzym.user.security.BcryptPasswordHasher
import kz.juzym.user.security.PasswordHasher
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UsersRepositoryTest {

    private lateinit var postgres: EmbeddedPostgres
    private lateinit var context: PostgresDatabaseContext
    private lateinit var repository: UserRepository
    private lateinit var passwordHasher: PasswordHasher

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
        factory.verifyConnection(context)
        factory.ensureSchema(context)
        passwordHasher = BcryptPasswordHasher(logRounds = 4)
        repository = ExposedUserRepository(context.database, passwordHasher)
    }

    @AfterAll
    fun tearDown() {
        context.close()
        postgres.close()
    }

    @Test
    fun `should create and read user`() {
        val user = User(
            id = UUID.randomUUID(),
            iin = "900101301234",
            email = "user@example.com",
            passwordHash = passwordHasher.hash("secret"),
            status = UserStatus.PENDING,
            createdAt = Instant.now()
        )

        repository.create(user)

        val stored = repository.findByIin("900101301234")
        assertNotNull(stored)
        assertEquals(user.id, stored.id)
        assertEquals("user@example.com", stored.email)
        assertTrue(repository.verifyCredentials("900101301234", "secret"))
    }
}
