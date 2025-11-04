package kz.juzym.user

import kz.juzym.user.security.PasswordHasher
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

interface UserRepository {
    fun findByIin(iin: String): User?
    fun findByEmail(email: String): User?
    fun findById(id: UUID): User?
    fun create(user: User)
    fun updateStatus(userId: UUID, status: UserStatus)
    fun updateEmail(userId: UUID, email: String)
    fun updatePassword(userId: UUID, passwordHash: String)
    fun delete(userId: UUID)
    fun verifyCredentials(iin: String, rawPassword: String): Boolean
}

class ExposedUserRepository(
    private val database: org.jetbrains.exposed.sql.Database,
    private val passwordHasher: PasswordHasher
) : UserRepository {

    override fun findByIin(iin: String): User? = transaction(database) {
        UsersTable.selectAll().where { UsersTable.iin eq iin }
            .singleOrNull()
            ?.toUser()
    }

    override fun findByEmail(email: String): User? = transaction(database) {
        UsersTable.selectAll().where { UsersTable.email eq email }
            .singleOrNull()
            ?.toUser()
    }

    override fun findById(id: UUID): User? = transaction(database) {
        UsersTable.selectAll().where { UsersTable.id eq id }
            .singleOrNull()
            ?.toUser()
    }

    override fun create(user: User) {
        transaction(database) {
            UsersTable.insert { statement ->
                statement[UsersTable.id] = EntityID(user.id, UsersTable)
                statement[UsersTable.iin] = user.iin
                statement[UsersTable.email] = user.email
                statement[UsersTable.passwordHash] = user.passwordHash
                statement[UsersTable.status] = user.status
                statement[UsersTable.displayName] = user.displayName
                statement[UsersTable.locale] = user.locale
                statement[UsersTable.timezone] = user.timezone
                statement[UsersTable.acceptedTermsVersion] = user.acceptedTermsVersion
                statement[UsersTable.acceptedPrivacyVersion] = user.acceptedPrivacyVersion
                statement[UsersTable.marketingOptIn] = user.marketingOptIn
                statement[UsersTable.avatarId] = user.avatarId
                statement[UsersTable.photoUrl] = user.photoUrl
                statement[UsersTable.about] = user.about
                statement[UsersTable.activationTokenExpiresAt] = user.activationTokenExpiresAt?.toOffsetDateTime()
                statement[UsersTable.lastEmailSentAt] = user.lastEmailSentAt?.toOffsetDateTime()
                statement[UsersTable.resendCount] = user.resendCount
                statement[UsersTable.resendCountResetAt] = user.resendCountResetAt?.toOffsetDateTime()
                statement[UsersTable.createdAt] = user.createdAt.toOffsetDateTime()
                statement[UsersTable.updatedAt] = user.updatedAt.toOffsetDateTime()
            }
        }
    }

    override fun updateStatus(userId: UUID, status: UserStatus) {
        transaction(database) {
            UsersTable.update({ UsersTable.id eq userId }) {
                it[UsersTable.status] = status
                it[UsersTable.updatedAt] = Instant.now().toOffsetDateTime()
            }
        }
    }

    override fun updateEmail(userId: UUID, email: String) {
        transaction(database) {
            UsersTable.update({ UsersTable.id eq userId }) {
                it[UsersTable.email] = email
                it[UsersTable.updatedAt] = Instant.now().toOffsetDateTime()
            }
        }
    }

    override fun updatePassword(userId: UUID, passwordHash: String) {
        transaction(database) {
            UsersTable.update({ UsersTable.id eq userId }) {
                it[UsersTable.passwordHash] = passwordHash
                it[UsersTable.updatedAt] = Instant.now().toOffsetDateTime()
            }
        }
    }

    override fun delete(userId: UUID) {
        transaction(database) {
            UsersTable.deleteWhere { UsersTable.id eq userId }
        }
    }

    override fun verifyCredentials(iin: String, rawPassword: String): Boolean {
        val stored = transaction(database) {
            UsersTable.selectAll().where { UsersTable.iin eq iin }
                .singleOrNull()
                ?.get(UsersTable.passwordHash)
        } ?: return false
        return passwordHasher.verify(rawPassword, stored)
    }

    private fun ResultRow.toUser(): User = User(
        id = this[UsersTable.id].value,
        iin = this[UsersTable.iin],
        email = this[UsersTable.email],
        passwordHash = this[UsersTable.passwordHash],
        status = this[UsersTable.status],
        displayName = this[UsersTable.displayName],
        locale = this[UsersTable.locale],
        timezone = this[UsersTable.timezone],
        acceptedTermsVersion = this[UsersTable.acceptedTermsVersion],
        acceptedPrivacyVersion = this[UsersTable.acceptedPrivacyVersion],
        marketingOptIn = this[UsersTable.marketingOptIn],
        avatarId = this[UsersTable.avatarId],
        photoUrl = this[UsersTable.photoUrl],
        about = this[UsersTable.about],
        activationTokenExpiresAt = this[UsersTable.activationTokenExpiresAt]?.toInstantUtc(),
        lastEmailSentAt = this[UsersTable.lastEmailSentAt]?.toInstantUtc(),
        resendCount = this[UsersTable.resendCount],
        resendCountResetAt = this[UsersTable.resendCountResetAt]?.toInstantUtc(),
        createdAt = this[UsersTable.createdAt].toInstantUtc(),
        updatedAt = this[UsersTable.updatedAt].toInstantUtc(),
    )
}
