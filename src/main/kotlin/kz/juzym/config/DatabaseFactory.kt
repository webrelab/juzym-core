package kz.juzym.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kz.juzym.audit.AuditEventsTable
import kz.juzym.auth.UserSessionsTable
import kz.juzym.user.UserRegistrationIdempotencyTable
import kz.juzym.user.UserRolesTable
import kz.juzym.user.UserTokensTable
import kz.juzym.user.UsersTable
import kz.juzym.user.avatar.AvatarAchievementsTable
import kz.juzym.user.avatar.AvatarSkillsTable
import kz.juzym.user.avatar.AvatarStatsCacheTable
import kz.juzym.user.avatar.AvatarsTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

class DatabaseFactory(private val config: PostgresConfig) {

    fun connect(): PostgresDatabaseContext {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = config.user
            password = config.password
            maximumPoolSize = 5
            minimumIdle = 1
            isAutoCommit = false
            validate()
        }
        val dataSource = HikariDataSource(hikariConfig)
        val database = Database.connect(dataSource)
        return PostgresDatabaseContext(database, dataSource)
    }

    fun ensureSchema(context: PostgresDatabaseContext) {
        transaction(context.database) {
            SchemaUtils.create(
                UsersTable,
                UserTokensTable,
                UserRolesTable,
                UserRegistrationIdempotencyTable,
                UserSessionsTable,
                AuditEventsTable,
                AvatarsTable,
                AvatarSkillsTable,
                AvatarAchievementsTable,
                AvatarStatsCacheTable,
            )
        }
    }

    fun verifyConnection(context: PostgresDatabaseContext) {
        transaction(context.database) {
            exec("SELECT 1") { rs -> if (rs.next()) rs.getInt(1) else null }
        }
    }
}

data class PostgresDatabaseContext(
    val database: Database,
    val dataSource: HikariDataSource
) {
    fun close() {
        dataSource.close()
    }
}
