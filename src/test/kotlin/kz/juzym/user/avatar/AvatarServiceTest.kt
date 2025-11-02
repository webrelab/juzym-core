package kz.juzym.user.avatar

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kz.juzym.config.DatabaseFactory
import kz.juzym.config.PostgresConfig
import kz.juzym.config.PostgresDatabaseContext
import kz.juzym.core.FieldUpdate
import kz.juzym.user.UsersTable
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AvatarServiceTest {

    private lateinit var postgres: EmbeddedPostgres
    private lateinit var context: PostgresDatabaseContext
    private lateinit var service: AvatarService

    private val levelStrategy = AvatarLevelStrategy { _, newXp -> (newXp / 200) + 1 }

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
        service = AvatarServiceImpl(context.database, levelStrategy)
    }

    @AfterAll
    fun tearDown() {
        context.close()
        postgres.close()
    }

    @BeforeEach
    fun cleanTables() {
        transaction(context.database) {
            AvatarStatsCacheTable.deleteAll()
            AvatarAchievementsTable.deleteAll()
            AvatarSkillsTable.deleteAll()
            AvatarsTable.deleteAll()
            UsersTable.deleteAll()
        }
    }

    @Test
    fun `should create and fetch avatar profile`() {
        val userId = createUser()
        val avatarId = service.createAvatar(userId, displayName = "Alice", about = "Bio", photoUrl = "https://cdn/avatar.png")

        val profile = service.getAvatarByUserId(userId)
        assertNotNull(profile)
        assertEquals(avatarId, profile.avatar.id)
        assertEquals("Alice", profile.avatar.displayName)
        assertEquals(1, profile.stats.level)
        assertEquals(0, profile.stats.xp)
        assertEquals(0, profile.stats.reputation)
        assertEquals(0, profile.stats.tasksCompleted)
        assertEquals(0, profile.stats.votesParticipated)
    }

    @Test
    fun `should update profile and manage skills`() {
        val userId = createUser()
        val avatarId = service.createAvatar(userId, displayName = "Bob")

        val updated = service.updateAvatarProfile(
            avatarId = avatarId,
            displayName = FieldUpdate.Value("Robert"),
            about = FieldUpdate.Value(null),
            photoUrl = FieldUpdate.Value("https://img/robert.png"),
        )

        assertEquals("Robert", updated.displayName)
        assertNull(updated.about)
        assertEquals("https://img/robert.png", updated.photoUrl)

        val skill = service.addAvatarSkill(avatarId, code = "kotlin", name = "Kotlin", level = 2)
        assertEquals(2, skill.level)
        val adjusted = service.updateAvatarSkillLevel(avatarId, code = "kotlin", delta = -1)
        assertEquals(1, adjusted.level)
    }

    @Test
    fun `should update progress and expose stats`() {
        val userId = createUser()
        val avatarId = service.createAvatar(userId, displayName = "Charlie")

        val progress = service.updateAvatarProgress(avatarId, xpDelta = 450, reputationDelta = 15)
        assertEquals(3, progress.level)
        assertEquals(450, progress.xp)
        assertEquals(15, progress.reputation)

        val stats = service.getAvatarStats(avatarId)
        assertNotNull(stats)
        assertEquals(3, stats.level)
        assertEquals(450, stats.xp)
        assertEquals(15, stats.reputation)
    }

    private fun createUser(): UUID {
        val userId = UUID.randomUUID()
        transaction(context.database) {
            UsersTable.insert { statement ->
                statement[UsersTable.id] = userId
                statement[UsersTable.iin] = UUID.randomUUID().toString().replace("-", "").take(12)
                statement[UsersTable.email] = "user-${userId}@example.com"
                statement[UsersTable.passwordHash] = "hash"
                statement[UsersTable.status] = kz.juzym.user.UserStatus.ACTIVE
                statement[UsersTable.createdAt] = OffsetDateTime.now(ZoneOffset.UTC)
            }
        }
        return userId
    }
}
