package kz.juzym.user

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kz.juzym.config.DatabaseFactory
import kz.juzym.config.PostgresConfig
import kz.juzym.config.PostgresDatabaseContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UsersRepositoryTest {

    private lateinit var postgres: EmbeddedPostgres
    private lateinit var context: PostgresDatabaseContext
    private lateinit var repository: UsersRepository

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
        repository = UsersRepository(context.database)
    }

    @AfterAll
    fun tearDown() {
        context.close()
        postgres.close()
    }

    @Test
    fun `should create and read user`() {
        val newUser = NewUser(
            id = UUID.randomUUID(),
            email = "user@example.com",
            passwordHash = "hash",
            displayName = "Test User",
            status = "active",
            createdAt = OffsetDateTime.now(ZoneOffset.UTC)
        )

        repository.create(newUser)

        val stored = repository.findById(newUser.id)
        assertNotNull(stored)
        assertEquals(newUser.id, stored.id)
        assertEquals("user@example.com", stored.email)
        assertEquals("Test User", stored.displayName)
    }
}
