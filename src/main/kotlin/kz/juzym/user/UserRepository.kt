package kz.juzym.user

import kz.juzym.user.security.PasswordHasher
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

interface UserRepository {
    fun findByIin(iin: String): User?
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

    override fun findById(id: UUID): User? = transaction(database) {
        UsersTable.selectAll().where { UsersTable.id eq id }
            .singleOrNull()
            ?.toUser()
    }

    override fun create(user: User) {
        transaction(database) {
            UsersTable.insert { statement ->
                statement[id] = user.id
                statement[iin] = user.iin
                statement[email] = user.email
                statement[passwordHash] = user.passwordHash
                statement[status] = user.status
                statement[createdAt] = user.createdAt.toOffsetDateTime()
            }
        }
    }

    override fun updateStatus(userId: UUID, status: UserStatus) {
        transaction(database) {
            UsersTable.update({ UsersTable.id eq userId }) {
                it[UsersTable.status] = status
            }
        }
    }

    override fun updateEmail(userId: UUID, email: String) {
        transaction(database) {
            UsersTable.update({ UsersTable.id eq userId }) {
                it[UsersTable.email] = email
            }
        }
    }

    override fun updatePassword(userId: UUID, passwordHash: String) {
        transaction(database) {
            UsersTable.update({ UsersTable.id eq userId }) {
                it[UsersTable.passwordHash] = passwordHash
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
        createdAt = this[UsersTable.createdAt].toInstantUtc()
    )
}
