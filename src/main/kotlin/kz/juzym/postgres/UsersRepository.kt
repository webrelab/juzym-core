package kz.juzym.postgres

import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class UsersRepository(private val database: org.jetbrains.exposed.sql.Database) {

    fun create(user: NewUser): UserRecord = transaction(database) {
        UsersTable.insert { statement ->
            statement[UsersTable.id] = user.id
            statement[UsersTable.email] = user.email
            statement[UsersTable.passwordHash] = user.passwordHash
            statement[UsersTable.displayName] = user.displayName
            statement[UsersTable.status] = user.status
            statement[UsersTable.createdAt] = user.createdAt
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
