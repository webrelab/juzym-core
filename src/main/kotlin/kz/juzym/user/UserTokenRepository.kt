package kz.juzym.user

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Duration
import java.time.Instant
import java.util.UUID

interface UserTokenRepository {
    fun createToken(
        userId: UUID,
        type: UserTokenType,
        validity: Duration,
        payload: String? = null
    ): UserToken

    fun findValidToken(token: String, type: UserTokenType): UserToken?
    fun markConsumed(id: UUID)
    fun deleteTokens(userId: UUID, type: UserTokenType)
    fun deleteByUser(userId: UUID)
}

class ExposedUserTokenRepository(
    private val database: org.jetbrains.exposed.sql.Database
) : UserTokenRepository {

    override fun createToken(userId: UUID, type: UserTokenType, validity: Duration, payload: String?): UserToken {
        val createdAt = Instant.now()
        val token = UserToken(
            id = UUID.randomUUID(),
            userId = userId,
            token = UUID.randomUUID().toString(),
            type = type,
            payload = payload,
            createdAt = createdAt,
            expiresAt = createdAt.plus(validity),
            consumedAt = null
        )
        transaction(database) {
            UserTokensTable.deleteWhere { (UserTokensTable.userId eq userId) and (UserTokensTable.type eq type) }
            UserTokensTable.insert { statement ->
                statement[id] = token.id
                statement.fromUserToken(token)
            }
        }
        return token
    }

    override fun findValidToken(token: String, type: UserTokenType): UserToken? = transaction(database) {
        UserTokensTable.selectAll().where { (UserTokensTable.token eq token) and (UserTokensTable.type eq type) }
            .singleOrNull()
            ?.toUserToken()
            ?.takeIf { !it.isExpired && !it.isConsumed }
    }

    override fun markConsumed(id: UUID) {
        transaction(database) {
            UserTokensTable.update({ UserTokensTable.id eq id }) {
                it[consumedAt] = Instant.now().toOffsetDateTime()
            }
        }
    }

    override fun deleteTokens(userId: UUID, type: UserTokenType) {
        transaction(database) {
            UserTokensTable.deleteWhere { (UserTokensTable.userId eq userId) and (UserTokensTable.type eq type) }
        }
    }

    override fun deleteByUser(userId: UUID) {
        transaction(database) {
            UserTokensTable.deleteWhere { UserTokensTable.userId eq userId }
        }
    }

    private fun ResultRow.toUserToken(): UserToken = UserToken(
        id = this[UserTokensTable.id].value,
        userId = this[UserTokensTable.userId],
        token = this[UserTokensTable.token],
        type = this[UserTokensTable.type],
        payload = this[UserTokensTable.payload],
        createdAt = this[UserTokensTable.createdAt].toInstantUtc(),
        expiresAt = this[UserTokensTable.expiresAt].toInstantUtc(),
        consumedAt = this[UserTokensTable.consumedAt]?.toInstantUtc()
    )
}
