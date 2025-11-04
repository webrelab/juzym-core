package kz.juzym.app

import kz.juzym.app.di.auditModule
import kz.juzym.app.di.configurationModule
import kz.juzym.app.di.infrastructureModule
import kz.juzym.app.di.repositoryModule
import kz.juzym.app.di.serviceModule
import kz.juzym.audit.AuditEventStore
import kz.juzym.auth.AuthService
import kz.juzym.config.AppConfigLoader
import kz.juzym.config.ApplicationConfig
import kz.juzym.config.DatabaseFactory
import kz.juzym.config.Environment
import kz.juzym.config.PostgresDatabaseContext
import kz.juzym.graph.GraphRepository
import kz.juzym.graph.GraphService
import kz.juzym.user.UserRepository
import kz.juzym.user.UserService
import kz.juzym.user.avatar.AvatarService
import kz.juzym.user.security.jwt.JwtService
import kz.juzym.user.security.PasswordHasher
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.neo4j.driver.Driver

class Application(
    private val config: ApplicationConfig = AppConfigLoader.load()
) {

    fun start(): ApplicationContext {
        val koinApplication = startKoin {
            modules(
                configurationModule(config),
                infrastructureModule,
                repositoryModule,
                auditModule,
                serviceModule
            )
        }
        val koin = koinApplication.koin

        val driver = koin.get<Driver>()
        driver.verifyConnectivity()

        val databaseFactory = koin.get<DatabaseFactory>()
        val postgresContext = koin.get<PostgresDatabaseContext>()
        databaseFactory.verifyConnection(postgresContext)
        databaseFactory.ensureSchema(postgresContext)

        val redisConnection = koin.get<StatefulRedisConnection<String, String>>()
        redisConnection.sync().ping()

        return ApplicationContext(
            config = config,
            neo4jDriver = driver,
            postgres = postgresContext,
            redisClient = koin.get(),
            redisConnection = redisConnection,
            graphRepository = koin.get(),
            userRepository = koin.get(),
            userService = koin.get(),
            jwtService = koin.get(),
            graphService = koin.get(),
            auditEventStore = koin.get(),
            avatarService = koin.get(),
            authService = koin.get(),
            passwordHasher = koin.get(),
            koinApplication = koinApplication
        )
    }

    companion object {
        fun load(environment: Environment, overrides: Map<String, String> = emptyMap()): Application {
            val config = AppConfigLoader.load(environment, overrides)
            return Application(config)
        }
    }
}

data class ApplicationContext(
    val config: ApplicationConfig,
    val neo4jDriver: Driver,
    val postgres: PostgresDatabaseContext,
    val redisClient: RedisClient,
    val redisConnection: StatefulRedisConnection<String, String>,
    val graphRepository: GraphRepository,
    val userRepository: UserRepository,
    val userService: UserService,
    val jwtService: JwtService,
    val graphService: GraphService,
    val auditEventStore: AuditEventStore,
    val avatarService: AvatarService,
    val authService: AuthService,
    val passwordHasher: PasswordHasher,
    private val koinApplication: KoinApplication
) {
    fun close() {
        neo4jDriver.close()
        postgres.close()
        redisConnection.close()
        redisClient.shutdown()
        koinApplication.close()
    }
}
