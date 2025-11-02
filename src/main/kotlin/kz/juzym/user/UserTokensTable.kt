package kz.juzym.user

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

object UserTokensTable : UUIDTable(name = "user_tokens") {
    val userId: Column<UUID> = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val token: Column<String> = varchar("token", 255).uniqueIndex()
    val type: Column<UserTokenType> = enumerationByName("type", 32, UserTokenType::class)
    val payload: Column<String?> = text("payload").nullable()
    val createdAt: Column<OffsetDateTime> = timestampWithTimeZone("created_at")
    val expiresAt: Column<OffsetDateTime> = timestampWithTimeZone("expires_at")
    val consumedAt: Column<OffsetDateTime?> = timestampWithTimeZone("consumed_at").nullable()
}

data class UserToken(
    val id: UUID,
    val userId: UUID,
    val token: String,
    val type: UserTokenType,
    val payload: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val consumedAt: Instant?
) {
    val isConsumed: Boolean get() = consumedAt != null
    val isExpired: Boolean get() = Instant.now().isAfter(expiresAt)
}

enum class UserTokenType {
    ACTIVATION,
    PASSWORD_RESET,
    DELETION,
    EMAIL_CHANGE
}

internal fun UpdateBuilder<*>.fromUserToken(userToken: UserToken) {
    this[UserTokensTable.userId] = userToken.userId
    this[UserTokensTable.token] = userToken.token
    this[UserTokensTable.type] = userToken.type
    this[UserTokensTable.payload] = userToken.payload
    this[UserTokensTable.createdAt] = userToken.createdAt.toOffsetDateTime()
    this[UserTokensTable.expiresAt] = userToken.expiresAt.toOffsetDateTime()
    this[UserTokensTable.consumedAt] = userToken.consumedAt?.toOffsetDateTime()
}
