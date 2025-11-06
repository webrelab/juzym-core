package kz.juzym.user

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

interface UserRoleRepository {
    fun findRoles(userId: UUID): Set<Role>
    fun assignRole(userId: UUID, role: Role)
    fun removeRole(userId: UUID, role: Role)
}

class ExposedUserRoleRepository(
    private val database: org.jetbrains.exposed.sql.Database
) : UserRoleRepository {

    override fun findRoles(userId: UUID): Set<Role> {
        val current = TransactionManager.currentOrNull()
        val query = {
            UserRolesTable.select { UserRolesTable.userId eq userId }
                .map { it[UserRolesTable.role] }
                .toSet()
        }
        return if (current != null) {
            query()
        } else {
            transaction(database) { query() }
        }
    }

    override fun assignRole(userId: UUID, role: Role) {
        val current = TransactionManager.currentOrNull()
        val insertAction = {
            UserRolesTable.insertIgnore { statement ->
                statement[UserRolesTable.userId] = userId
                statement[UserRolesTable.role] = role
            }
        }
        if (current != null) {
            insertAction()
        } else {
            transaction(database) { insertAction() }
        }
    }

    override fun removeRole(userId: UUID, role: Role) {
        val current = TransactionManager.currentOrNull()
        val deleteAction = {
            UserRolesTable.deleteWhere {
                (UserRolesTable.userId eq userId) and (UserRolesTable.role eq role)
            }
        }
        if (current != null) {
            deleteAction()
        } else {
            transaction(database) { deleteAction() }
        }
    }
}
