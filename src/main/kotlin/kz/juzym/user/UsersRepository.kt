package kz.juzym.user

import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class UsersRepository(private val database: org.jetbrains.exposed.sql.Database) {

    fun create(user: NewUser): UserRecord = transaction(database) {
        UsersTable.insert { statement ->
            statement[UsersTable.id] = user.id
            statement[email] = user.email
            statement[passwordHash] = user.passwordHash
            statement[displayName] = user.displayName
            statement[status] = user.status
            statement[createdAt] = user.createdAt
        }
        user.toRecord()
    }

    fun findById(id: UUID): UserRecord? = transaction(database) {
        UsersTable.select { UsersTable.id eq id }
            .singleOrNull()
            ?.toUserRecord()
    }

    private fun NewUser.toRecord(): UserRecord = UserRecord(
        id = id,
        email = email,
        passwordHash = passwordHash,
        displayName = displayName,
        status = status,
        createdAt = createdAt
    )

    private fun org.jetbrains.exposed.sql.ResultRow.toUserRecord(): UserRecord = UserRecord(
        id = this[UsersTable.id].value,
        email = this[UsersTable.email],
        passwordHash = this[UsersTable.passwordHash],
        displayName = this[UsersTable.displayName],
        status = this[UsersTable.status],
        createdAt = this[UsersTable.createdAt]
    )
}
