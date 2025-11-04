package kz.juzym.auth

import kz.juzym.user.UsersTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

object UserSessionsTable : UUIDTable(name = "user_sessions") {
    val userId: Column<UUID> = uuid("user_id").references(UsersTable.id)
    val deviceId: Column<String> = varchar("device_id", 128)
    val deviceName: Column<String?> = varchar("device_name", 255).nullable()
    val platform: Column<String> = varchar("platform", 32)
    val clientVersion: Column<String?> = varchar("client_version", 64).nullable()
    val refreshTokenHash: Column<String> = varchar("refresh_token_hash", 128)
    val previousTokenHash: Column<String?> = varchar("previous_token_hash", 128).nullable()
    val previousTokenExpiresAt: Column<OffsetDateTime?> = timestampWithTimeZone("previous_token_expires_at").nullable()
    val refreshTokenExpiresAt: Column<OffsetDateTime> = timestampWithTimeZone("refresh_token_expires_at")
    val accessTokenExpiresAt: Column<OffsetDateTime> = timestampWithTimeZone("access_token_expires_at")
    val createdAt: Column<OffsetDateTime> = timestampWithTimeZone("created_at")
    val updatedAt: Column<OffsetDateTime> = timestampWithTimeZone("updated_at")
    val lastSeenAt: Column<OffsetDateTime?> = timestampWithTimeZone("last_seen_at").nullable()
    val ip: Column<String?> = varchar("ip", 45).nullable()
    val userAgent: Column<String?> = varchar("user_agent", 512).nullable()
    val rememberMe: Column<Boolean> = bool("remember_me")
}

data class UserSession(
    val id: UUID,
    val userId: UUID,
    val deviceId: String,
    val deviceName: String?,
    val platform: String,
    val clientVersion: String?,
    val refreshTokenHash: String,
    val previousTokenHash: String?,
    val previousTokenExpiresAt: Instant?,
    val refreshTokenExpiresAt: Instant,
    val accessTokenExpiresAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastSeenAt: Instant?,
    val ip: String?,
    val userAgent: String?,
    val rememberMe: Boolean
)
