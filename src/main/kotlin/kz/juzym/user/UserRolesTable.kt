package kz.juzym.user

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import java.util.UUID

object UserRolesTable : Table(name = "user_roles") {
    val userId: Column<UUID> = uuid("user_id").references(UsersTable.id)
    val role: Column<Role> = enumerationByName("role", length = 32, klass = Role::class)

    override val primaryKey: PrimaryKey = PrimaryKey(userId, role)
}
