package kz.juzym.registration

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import java.time.OffsetDateTime
import java.util.UUID

object RegistrationsTable : UUIDTable(name = "registrations") {
    val iin: Column<String> = varchar("iin", 12).uniqueIndex()
    val email: Column<String> = varchar("email", 255).uniqueIndex()
    val passwordHash: Column<String> = varchar("password_hash", 255)
    val displayName: Column<String> = varchar("display_name", 255)
    val locale: Column<String?> = varchar("locale", length = 64).nullable()
    val timezone: Column<String?> = varchar("timezone", length = 64).nullable()
    val acceptedTermsVersion: Column<String> = varchar("accepted_terms_version", 64)
    val acceptedPrivacyVersion: Column<String> = varchar("accepted_privacy_version", 64)
    val marketingOptIn: Column<Boolean> = bool("marketing_opt_in")
    val status: Column<RegistrationStatus> = enumerationByName("status", 32, RegistrationStatus::class)
    val emailTokenExpiresAt: Column<OffsetDateTime> = timestampWithTimeZone("email_token_expires_at")
    val verificationToken: Column<String?> = varchar("verification_token", 255).nullable()
    val lastEmailSentAt: Column<OffsetDateTime?> = timestampWithTimeZone("last_email_sent_at").nullable()
    val avatarId: Column<UUID?> = uuid("avatar_id").nullable()
    val photoUrl: Column<String?> = text("photo_url").nullable()
    val about: Column<String?> = text("about").nullable()
    val resendCount: Column<Int> = integer("resend_count").default(0)
    val resendCountResetAt: Column<OffsetDateTime?> = timestampWithTimeZone("resend_count_reset_at").nullable()
    val createdAt: Column<OffsetDateTime> = timestampWithTimeZone("created_at")
    val updatedAt: Column<OffsetDateTime> = timestampWithTimeZone("updated_at")
}

enum class RegistrationTokenType {
    EMAIL_VERIFICATION,
    PASSWORD_RESET,
}

object RegistrationTokensTable : Table(name = "registration_tokens") {
    val token: Column<String> = varchar("token", 255)
    val userId: Column<UUID> = uuid("user_id").references(RegistrationsTable.id)
    val type: Column<RegistrationTokenType> = enumerationByName("type", 32, RegistrationTokenType::class)
    val expiresAt: Column<OffsetDateTime> = timestampWithTimeZone("expires_at")
    val createdAt: Column<OffsetDateTime> = timestampWithTimeZone("created_at")

    override val primaryKey: PrimaryKey = PrimaryKey(token)
}

object RegistrationIdempotencyTable : Table(name = "registration_idempotency") {
    val key: Column<String> = varchar("idempotency_key", length = 255)
    val registrationId: Column<UUID> = uuid("registration_id").references(RegistrationsTable.id)
    val responseStatus: Column<RegistrationStatus> = enumerationByName("response_status", 32, RegistrationStatus::class)
    val responseVerificationExpiresAt: Column<OffsetDateTime> = timestampWithTimeZone("response_verification_expires_at")
    val responseDebugToken: Column<String?> = varchar("response_debug_token", 255).nullable()
    val createdAt: Column<OffsetDateTime> = timestampWithTimeZone("created_at")

    override val primaryKey: PrimaryKey = PrimaryKey(key)
}
