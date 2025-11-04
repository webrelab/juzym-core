package kz.juzym.user

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import java.time.OffsetDateTime

object UserRegistrationIdempotencyTable : Table(name = "user_registration_idempotency") {
    val key: Column<String> = varchar("idempotency_key", length = 255)
    val userId: Column<java.util.UUID> = uuid("user_id").references(UsersTable.id)
    val responseStatus: Column<RegistrationStatus> = enumerationByName("response_status", 32, RegistrationStatus::class)
    val responseVerificationExpiresAt: Column<OffsetDateTime> = timestampWithTimeZone("response_verification_expires_at")
    val responseDebugToken: Column<String?> = varchar("response_debug_token", 255).nullable()
    val createdAt: Column<OffsetDateTime> = timestampWithTimeZone("created_at")

    override val primaryKey: PrimaryKey = PrimaryKey(key)
}
