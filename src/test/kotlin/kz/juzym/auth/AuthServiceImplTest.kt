package kz.juzym.auth

import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kz.juzym.user.Role
import kz.juzym.user.User
import kz.juzym.user.UserRepository
import kz.juzym.user.UserStatus
import kz.juzym.user.UserRoleRepository
import kz.juzym.user.avatar.Avatar
import kz.juzym.user.avatar.AvatarProfile
import kz.juzym.user.avatar.AvatarService
import kz.juzym.user.avatar.AvatarStats
import kz.juzym.user.security.PasswordHasher
import kz.juzym.user.security.jwt.JwtService

class AuthServiceImplTest {

    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val passwordHasher = mockk<PasswordHasher>(relaxed = true)
    private val jwtService = mockk<JwtService>(relaxed = true)
    private val sessionRepository = mockk<UserSessionRepository>(relaxed = true)
    private val avatarService = mockk<AvatarService>(relaxed = true)
    private val userRoleRepository = mockk<UserRoleRepository>(relaxed = true)

    private val config = AuthConfig(
        accessTokenTtl = Duration.ofMinutes(15),
        refreshTokenTtl = Duration.ofDays(30),
        rememberMeRefreshTokenTtl = Duration.ofDays(60)
    )

    private lateinit var service: AuthServiceImpl

    @BeforeTest
    fun setup() {
        clearAllMocks()
        service = AuthServiceImpl(
            userRepository = userRepository,
            passwordHasher = passwordHasher,
            jwtService = jwtService,
            sessionRepository = sessionRepository,
            authConfig = config,
            avatarService = avatarService,
            userRoleRepository = userRoleRepository
        )
    }

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `login with valid email credentials creates session`() {
        val user = activeUser()
        every { userRepository.findByEmail(any()) } returns user
        every { passwordHasher.verify("password", user.passwordHash) } returns true
        every { userRoleRepository.findRoles(user.id) } returns setOf(Role.USER)
        every { jwtService.generate(user.id, user.iin, setOf(Role.USER)) } returns "access-token"
        val refreshSlot = slot<String>()
        every {
            sessionRepository.create(
                userId = user.id,
                device = any(),
                refreshToken = capture(refreshSlot),
                refreshTokenExpiresAt = any(),
                accessTokenExpiresAt = any(),
                rememberMe = any(),
                ip = any(),
                userAgent = any()
            )
        } answers {
            sampleSession(user.id)
        }
        every { avatarService.getAvatarByUserId(user.id) } returns null

        val metadata = AuthMetadata(ip = "127.0.0.1", userAgent = "JUnit")
        val result = service.login(
            LoginRequest(
                email = user.email,
                password = "password",
                device = DeviceInfo(
                    deviceId = "device-1",
                    platform = "web"
                )
            ),
            metadata
        )

        assertEquals(user.id, result.userId)
        assertNull(result.avatarId)
        assertEquals("access-token", result.tokens.accessToken)
        assertEquals(refreshSlot.captured, result.tokens.refreshToken)
        assertTrue(result.tokens.accessExpiresAt.isAfter(Instant.now().minusSeconds(1)))
        verify(exactly = 1) { sessionRepository.create(any(), any(), any(), any(), any(), rememberMe = false, ip = metadata.ip, userAgent = metadata.userAgent) }
    }

    @Test
    fun `login fails when password invalid`() {
        val user = activeUser()
        every { userRepository.findByEmail(any()) } returns user
        every { passwordHasher.verify("password", user.passwordHash) } returns false
        every { userRoleRepository.findRoles(user.id) } returns setOf(Role.USER)

        assertFailsWith<InvalidCredentialsException> {
            service.login(
                LoginRequest(
                    email = user.email,
                    password = "password",
                    device = DeviceInfo(deviceId = "dev", platform = "web")
                ),
                AuthMetadata(null, null)
            )
        }
    }

    @Test
    fun `login fails for blocked user`() {
        val user = activeUser().copy(status = UserStatus.BLOCKED)
        every { userRepository.findByEmail(any()) } returns user

        assertFailsWith<AccountBlockedException> {
            service.login(
                LoginRequest(
                    email = user.email,
                    password = "password",
                    device = DeviceInfo(deviceId = "dev", platform = "web")
                ),
                AuthMetadata(null, null)
            )
        }
    }

    @Test
    fun `login fails for invalid device platform`() {
        assertFailsWith<InvalidPayloadException> {
            service.login(
                LoginRequest(
                    email = "user@example.com",
                    password = "password",
                    device = DeviceInfo(deviceId = "dev", platform = "console")
                ),
                AuthMetadata(null, null)
            )
        }
    }

    @Test
    fun `refresh rotates tokens successfully`() {
        val user = activeUser()
        val session = sampleSession(user.id)
        every { sessionRepository.findByRefreshToken("rt-1") } returns UserSessionRepository.TokenMatch.Current(session)
        every { userRepository.findById(user.id) } returns user
        every { userRoleRepository.findRoles(user.id) } returns setOf(Role.USER)
        every { jwtService.generate(user.id, user.iin, setOf(Role.USER)) } returns "new-access"
        every {
            sessionRepository.updateTokens(
                sessionId = session.id,
                previousRefreshToken = "rt-1",
                newRefreshToken = any(),
                newRefreshExpiresAt = any(),
                newAccessExpiresAt = any(),
                ip = any(),
                userAgent = any()
            )
        } answers {
            session.copy(updatedAt = Instant.now())
        }

        val result = service.refresh("rt-1", deviceId = session.deviceId, metadata = AuthMetadata("1.1.1.1", "JUnit"))

        assertEquals(user.id, result.userId)
        assertEquals("new-access", result.tokens.accessToken)
        assertTrue(result.tokens.refreshExpiresAt.isAfter(Instant.now().minusSeconds(1)))
        assertFalse(result.tokens.refreshToken.isBlank())
    }

    @Test
    fun `refresh fails when token already rotated`() {
        val session = sampleSession(UUID.randomUUID())
        every { sessionRepository.findByRefreshToken("rt-rotated") } returns UserSessionRepository.TokenMatch.Previous(session)

        assertFailsWith<TokenAlreadyRotatedException> {
            service.refresh("rt-rotated", null, AuthMetadata(null, null))
        }
    }

    @Test
    fun `refresh fails when token expired`() {
        val session = sampleSession(UUID.randomUUID()).copy(refreshTokenExpiresAt = Instant.now().minusSeconds(10))
        every { sessionRepository.findByRefreshToken("rt-expired") } returns UserSessionRepository.TokenMatch.Current(session)

        assertFailsWith<InvalidRefreshTokenException> {
            service.refresh("rt-expired", null, AuthMetadata(null, null))
        }
    }

    @Test
    fun `refresh fails on device mismatch`() {
        val session = sampleSession(UUID.randomUUID())
        every { sessionRepository.findByRefreshToken("rt-device") } returns UserSessionRepository.TokenMatch.Current(session)
        every { userRepository.findById(session.userId) } returns activeUser(session.userId)
        every { userRoleRepository.findRoles(session.userId) } returns setOf(Role.USER)

        assertFailsWith<DeviceMismatchException> {
            service.refresh("rt-device", "other-device", AuthMetadata(null, null))
        }
    }

    @Test
    fun `refresh fails when user blocked`() {
        val session = sampleSession(UUID.randomUUID())
        every { sessionRepository.findByRefreshToken("rt-blocked") } returns UserSessionRepository.TokenMatch.Current(session)
        every { userRepository.findById(session.userId) } returns activeUser(session.userId).copy(status = UserStatus.BLOCKED)
        every { userRoleRepository.findRoles(session.userId) } returns setOf(Role.USER)

        assertFailsWith<AccountBlockedException> {
            service.refresh("rt-blocked", null, AuthMetadata(null, null))
        }
    }

    @Test
    fun `refresh fails when tokens could not be updated`() {
        val session = sampleSession(UUID.randomUUID())
        val user = activeUser(session.userId)
        every { sessionRepository.findByRefreshToken("rt-update") } returns UserSessionRepository.TokenMatch.Current(session)
        every { userRepository.findById(session.userId) } returns user
        every { userRoleRepository.findRoles(user.id) } returns setOf(Role.USER)
        every { jwtService.generate(user.id, user.iin, setOf(Role.USER)) } returns "token"
        every {
            sessionRepository.updateTokens(
                sessionId = session.id,
                previousRefreshToken = "rt-update",
                newRefreshToken = any(),
                newRefreshExpiresAt = any(),
                newAccessExpiresAt = any(),
                ip = any(),
                userAgent = any()
            )
        } returns null

        assertFailsWith<InvalidRefreshTokenException> {
            service.refresh("rt-update", null, AuthMetadata(null, null))
        }
    }

    @Test
    fun `logout removes session`() {
        every { sessionRepository.deleteByRefreshToken("token") } returns true

        service.logout("token")

        verify { sessionRepository.deleteByRefreshToken("token") }
    }

    @Test
    fun `logout throws when token unknown`() {
        every { sessionRepository.deleteByRefreshToken(any()) } returns false

        assertFailsWith<InvalidRefreshTokenException> {
            service.logout("missing")
        }
    }

    @Test
    fun `logoutAll removes all sessions`() {
        every { sessionRepository.deleteByUser(any()) } returns 2

        service.logoutAll(UUID.randomUUID())

        verify { sessionRepository.deleteByUser(any()) }
    }

    @Test
    fun `getCurrentUser returns summary with avatar`() {
        val user = activeUser()
        val avatarProfile = AvatarProfile(
            avatar = Avatar(
                id = UUID.randomUUID(),
                userId = user.id,
                displayName = "Test",
                about = "About",
                photoUrl = "http://example.com",
                level = 3,
                xp = 100,
                reputation = 5,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            ),
            skills = emptyList(),
            achievements = emptyList(),
            stats = AvatarStats(
                avatarId = user.id,
                tasksCompleted = 0,
                votesParticipated = 0,
                level = 3,
                xp = 100,
                reputation = 5,
                lastActivityAt = null
            )
        )
        every { userRepository.findById(user.id) } returns user
        every { avatarService.getAvatarByUserId(user.id) } returns avatarProfile

        val response = service.getCurrentUser(user.id)

        assertEquals(user.email, response.user.email)
        assertNotNull(response.avatar)
        assertEquals(avatarProfile.avatar.id, response.avatar?.id)
    }

    @Test
    fun `getCurrentUser throws when user missing`() {
        every { userRepository.findById(any()) } returns null

        assertFailsWith<UnauthorizedException> {
            service.getCurrentUser(UUID.randomUUID())
        }
    }

    @Test
    fun `getSessions marks current session`() {
        val userId = UUID.randomUUID()
        val session1 = sampleSession(userId)
        val session2 = sampleSession(userId).copy(id = UUID.randomUUID(), deviceId = "device-2")
        every { sessionRepository.listByUser(userId) } returns listOf(session1, session2)
        every { sessionRepository.findByRefreshToken("refresh-current") } returns UserSessionRepository.TokenMatch.Current(session2)

        val response = service.getSessions(userId, "refresh-current")

        assertEquals(2, response.total)
        val currentSession = response.sessions.single { it.current }
        assertEquals(session2.id, currentSession.sessionId)
        val otherSession = response.sessions.single { it.sessionId == session1.id }
        assertFalse(otherSession.current)
    }

    @Test
    fun `revokeSession removes session`() {
        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        every { sessionRepository.deleteById(userId, sessionId) } returns true

        service.revokeSession(userId, sessionId)

        verify { sessionRepository.deleteById(userId, sessionId) }
    }

    @Test
    fun `revokeSession throws when missing`() {
        every { sessionRepository.deleteById(any(), any()) } returns false

        assertFailsWith<SessionNotFoundException> {
            service.revokeSession(UUID.randomUUID(), UUID.randomUUID())
        }
    }

    @Test
    fun `changePassword updates stored hash`() {
        val user = activeUser()
        every { userRepository.findById(user.id) } returns user
        every { passwordHasher.verify("old", user.passwordHash) } returns true
        every { passwordHasher.verify("new-strong", user.passwordHash) } returns false
        every { passwordHasher.hash("new-strong") } returns "hashed-new"

        service.changePassword(user.id, "old", "new-strong")

        verify { userRepository.updatePassword(user.id, "hashed-new") }
    }

    @Test
    fun `changePassword throws when current invalid`() {
        val user = activeUser()
        every { userRepository.findById(user.id) } returns user
        every { passwordHasher.verify("wrong", user.passwordHash) } returns false

        assertFailsWith<InvalidCredentialsException> {
            service.changePassword(user.id, "wrong", "new-password")
        }
    }

    @Test
    fun `changePassword throws when new password weak`() {
        val user = activeUser()
        every { userRepository.findById(user.id) } returns user
        every { passwordHasher.verify("old", user.passwordHash) } returns true

        assertFailsWith<WeakPasswordException> {
            service.changePassword(user.id, "old", "short")
        }
    }

    @Test
    fun `changePassword throws when reusing password`() {
        val user = activeUser()
        every { userRepository.findById(user.id) } returns user
        every { passwordHasher.verify("Password1!", user.passwordHash) } returnsMany listOf(true, true)

        assertFailsWith<PasswordReuseForbiddenException> {
            service.changePassword(user.id, "Password1!", "Password1!")
        }
    }

    private fun activeUser(id: UUID = UUID.randomUUID()) = User(
        id = id,
        iin = "931212123456",
        email = "user@example.com",
        passwordHash = "hashed",
        status = UserStatus.ACTIVE,
        createdAt = Instant.now()
    )

    private fun sampleSession(userId: UUID) = UserSession(
        id = UUID.randomUUID(),
        userId = userId,
        deviceId = "device-1",
        deviceName = "Chrome",
        platform = "web",
        clientVersion = null,
        refreshTokenHash = "hash",
        previousTokenHash = null,
        previousTokenExpiresAt = null,
        refreshTokenExpiresAt = Instant.now().plusSeconds(3600),
        accessTokenExpiresAt = Instant.now().plusSeconds(900),
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        lastSeenAt = Instant.now(),
        ip = "127.0.0.1",
        userAgent = "JUnit",
        rememberMe = false
    )
}
