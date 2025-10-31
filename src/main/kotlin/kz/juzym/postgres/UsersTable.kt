package kz.juzym.postgres

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import java.time.OffsetDateTime
import java.util.UUID

object UsersTable : UUIDTable(name = "users") {
    val email: Column<String> = varchar("email", 255).uniqueIndex()
    val passwordHash: Column<String> = text("password_hash")
    val displayName: Column<String> = varchar("display_name", 255)
    val status: Column<String> = varchar("status", 64)
    val createdAt: Column<OffsetDateTime> = timestampWithTimeZone("created_at")
}

data class UserRecord(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val displayName: String,
    val status: String,
    val createdAt: OffsetDateTime
)

data class NewUser(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val displayName: String,
    val status: String,
    val createdAt: OffsetDateTime
)
