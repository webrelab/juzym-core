package kz.juzym.auth

import kz.juzym.user.toOffsetDateTime
import kz.juzym.user.toInstantUtc
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

class UserSessionRepository(
    private val database: org.jetbrains.exposed.sql.Database
) {

    fun create(
        userId: UUID,
        device: DeviceInfo,
        refreshToken: String,
        refreshTokenExpiresAt: Instant,
        accessTokenExpiresAt: Instant,
        rememberMe: Boolean,
        ip: String?,
        userAgent: String?
    ): UserSession = transaction(database) {
        val now = Instant.now()
        val sessionId = UUID.randomUUID()
        UserSessionsTable.insert { statement ->
            statement[UserSessionsTable.id] = sessionId
            statement[UserSessionsTable.userId] = userId
            statement[UserSessionsTable.deviceId] = device.deviceId
            statement[UserSessionsTable.deviceName] = device.deviceName
            statement[UserSessionsTable.platform] = device.platform
            statement[UserSessionsTable.clientVersion] = device.clientVersion
            statement[refreshTokenHash] = hash(refreshToken)
            statement[previousTokenHash] = null
            statement[previousTokenExpiresAt] = null
            statement[UserSessionsTable.refreshTokenExpiresAt] = refreshTokenExpiresAt.toOffsetDateTime()
            statement[UserSessionsTable.accessTokenExpiresAt] = accessTokenExpiresAt.toOffsetDateTime()
            statement[createdAt] = now.toOffsetDateTime()
            statement[updatedAt] = now.toOffsetDateTime()
            statement[lastSeenAt] = now.toOffsetDateTime()
            statement[UserSessionsTable.ip] = ip
            statement[UserSessionsTable.userAgent] = userAgent
            statement[UserSessionsTable.rememberMe] = rememberMe
        }
        UserSessionsTable.selectAll()
            .where { UserSessionsTable.id eq sessionId }
            .single()
            .toUserSession()
    }

    fun findByRefreshToken(token: String): TokenMatch? = transaction(database) {
        val hashed = hash(token)
        UserSessionsTable.selectAll()
            .where { (UserSessionsTable.refreshTokenHash eq hashed) or (UserSessionsTable.previousTokenHash eq hashed) }
            .singleOrNull()
            ?.let { row ->
                val session = row.toUserSession()
                when {
                    row[UserSessionsTable.refreshTokenHash] == hashed -> TokenMatch.Current(session)
                    row[UserSessionsTable.previousTokenHash] == hashed -> TokenMatch.Previous(session)
                    else -> null
                }
            }
    }

    fun updateTokens(
        sessionId: UUID,
        previousRefreshToken: String,
        newRefreshToken: String,
        newRefreshExpiresAt: Instant,
        newAccessExpiresAt: Instant,
        ip: String?,
        userAgent: String?
    ): UserSession? = transaction(database) {
        val now = Instant.now()
        val previousHash = hash(previousRefreshToken)
        val updated = UserSessionsTable.update({ UserSessionsTable.id eq sessionId }) { statement ->
            statement[previousTokenHash] = previousHash
            statement[previousTokenExpiresAt] = newRefreshExpiresAt.toOffsetDateTime()
            statement[refreshTokenHash] = hash(newRefreshToken)
            statement[refreshTokenExpiresAt] = newRefreshExpiresAt.toOffsetDateTime()
            statement[accessTokenExpiresAt] = newAccessExpiresAt.toOffsetDateTime()
            statement[updatedAt] = now.toOffsetDateTime()
            statement[lastSeenAt] = now.toOffsetDateTime()
            statement[UserSessionsTable.ip] = ip
            statement[UserSessionsTable.userAgent] = userAgent
        }
        if (updated == 0) {
            return@transaction null
        }
        UserSessionsTable.selectAll()
            .where { UserSessionsTable.id eq sessionId }
            .single()
            .toUserSession()
    }

    fun deleteByRefreshToken(refreshToken: String): Boolean = transaction(database) {
        val hashed = hash(refreshToken)
        val deleted = UserSessionsTable.deleteWhere { UserSessionsTable.refreshTokenHash eq hashed }
        deleted > 0
    }

    fun deleteByUser(userId: UUID): Int = transaction(database) {
        UserSessionsTable.deleteWhere { UserSessionsTable.userId eq userId }
    }

    fun deleteById(userId: UUID, sessionId: UUID): Boolean = transaction(database) {
        val deleted = UserSessionsTable.deleteWhere {
            (UserSessionsTable.userId eq userId) and (UserSessionsTable.id eq sessionId)
        }
        deleted > 0
    }

    fun listByUser(userId: UUID): List<UserSession> = transaction(database) {
        UserSessionsTable.selectAll()
            .where { UserSessionsTable.userId eq userId }
            .map { it.toUserSession() }
            .sortedByDescending { it.updatedAt }
    }

    fun updateLastSeen(sessionId: UUID) {
        val now = Instant.now()
        transaction(database) {
            UserSessionsTable.update({ UserSessionsTable.id eq sessionId }) { statement ->
                statement[lastSeenAt] = now.toOffsetDateTime()
                statement[updatedAt] = now.toOffsetDateTime()
            }
        }
    }

    private fun ResultRow.toUserSession(): UserSession = UserSession(
        id = this[UserSessionsTable.id].value,
        userId = this[UserSessionsTable.userId],
        deviceId = this[UserSessionsTable.deviceId],
        deviceName = this[UserSessionsTable.deviceName],
        platform = this[UserSessionsTable.platform],
        clientVersion = this[UserSessionsTable.clientVersion],
        refreshTokenHash = this[UserSessionsTable.refreshTokenHash],
        previousTokenHash = this[UserSessionsTable.previousTokenHash],
        previousTokenExpiresAt = this[UserSessionsTable.previousTokenExpiresAt]?.toInstantUtc(),
        refreshTokenExpiresAt = this[UserSessionsTable.refreshTokenExpiresAt].toInstantUtc(),
        accessTokenExpiresAt = this[UserSessionsTable.accessTokenExpiresAt].toInstantUtc(),
        createdAt = this[UserSessionsTable.createdAt].toInstantUtc(),
        updatedAt = this[UserSessionsTable.updatedAt].toInstantUtc(),
        lastSeenAt = this[UserSessionsTable.lastSeenAt]?.toInstantUtc(),
        ip = this[UserSessionsTable.ip],
        userAgent = this[UserSessionsTable.userAgent],
        rememberMe = this[UserSessionsTable.rememberMe]
    )

    private fun hash(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(token.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { byte ->
            ((byte.toInt() and 0xff) + 0x100).toString(16).substring(1)
        }
    }

    sealed interface TokenMatch {
        data class Current(val session: UserSession) : TokenMatch
        data class Previous(val session: UserSession) : TokenMatch
    }
}

