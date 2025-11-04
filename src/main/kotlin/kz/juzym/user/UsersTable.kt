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
    val displayName: Column<String?> = varchar("display_name", 255).nullable()
    val locale: Column<String?> = varchar("locale", length = 64).nullable()
    val timezone: Column<String?> = varchar("timezone", length = 64).nullable()
    val acceptedTermsVersion: Column<String?> = varchar("accepted_terms_version", 64).nullable()
    val acceptedPrivacyVersion: Column<String?> = varchar("accepted_privacy_version", 64).nullable()
    val marketingOptIn: Column<Boolean> = bool("marketing_opt_in").default(false)
    val avatarId: Column<UUID?> = uuid("avatar_id").nullable()
    val photoUrl: Column<String?> = text("photo_url").nullable()
    val about: Column<String?> = text("about").nullable()
    val activationTokenExpiresAt: Column<OffsetDateTime?> = timestampWithTimeZone("activation_token_expires_at").nullable()
    val lastEmailSentAt: Column<OffsetDateTime?> = timestampWithTimeZone("last_email_sent_at").nullable()
    val resendCount: Column<Int> = integer("resend_count").default(0)
    val resendCountResetAt: Column<OffsetDateTime?> = timestampWithTimeZone("resend_count_reset_at").nullable()
    val createdAt: Column<OffsetDateTime> = timestampWithTimeZone("created_at")
    val updatedAt: Column<OffsetDateTime> = timestampWithTimeZone("updated_at")
}

data class User(
    val id: UUID = UUID.randomUUID(),
    val iin: String,
    val email: String,
    val passwordHash: String,
    val status: UserStatus = UserStatus.PENDING,
    val displayName: String? = null,
    val locale: String? = null,
    val timezone: String? = null,
    val acceptedTermsVersion: String? = null,
    val acceptedPrivacyVersion: String? = null,
    val marketingOptIn: Boolean = false,
    val avatarId: UUID? = null,
    val photoUrl: String? = null,
    val about: String? = null,
    val activationTokenExpiresAt: Instant? = null,
    val lastEmailSentAt: Instant? = null,
    val resendCount: Int = 0,
    val resendCountResetAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = createdAt,
)

enum class UserStatus {
    PENDING,
    ACTIVE,
    BLOCKED
}
