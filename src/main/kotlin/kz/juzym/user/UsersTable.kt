package kz.juzym.user

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

object UsersTable : UUIDTable(name = "users") {
    val iin: Column<String> = varchar("iin", 12).uniqueIndex()
    val email: Column<String> = varchar("email", 255).uniqueIndex()
    val passwordHash: Column<String> = varchar("password_hash", 255)
    val status: Column<UserStatus> = enumerationByName("status", 32, UserStatus::class)
    val createdAt: Column<OffsetDateTime> = timestampWithTimeZone("created_at")
}

data class User(
    val id: UUID = UUID.randomUUID(),
    val iin: String,
    val email: String,
    val passwordHash: String,
    val status: UserStatus = UserStatus.PENDING,
    val createdAt: Instant = Instant.now()
)

enum class UserStatus {
    PENDING,
    ACTIVE,
    BLOCKED
}
