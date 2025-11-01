package kz.juzym.app

import kz.juzym.app.di.auditModule
import kz.juzym.app.di.configurationModule
import kz.juzym.app.di.infrastructureModule
import kz.juzym.app.di.repositoryModule
import kz.juzym.app.di.serviceModule
import kz.juzym.audit.AuditEventStore
import kz.juzym.config.AppConfigLoader
import kz.juzym.config.ApplicationConfig
import kz.juzym.config.DatabaseFactory
import kz.juzym.config.Environment
import kz.juzym.config.PostgresDatabaseContext
import kz.juzym.graph.GraphRepository
import kz.juzym.graph.GraphService
import kz.juzym.user.UsersRepository
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

        return ApplicationContext(
            config = config,
            neo4jDriver = driver,
            postgres = postgresContext,
            graphRepository = koin.get(),
            usersRepository = koin.get(),
            graphService = koin.get(),
            auditEventStore = koin.get(),
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
    val graphRepository: GraphRepository,
    val usersRepository: UsersRepository,
    val graphService: GraphService,
    val auditEventStore: AuditEventStore,
    private val koinApplication: KoinApplication
) {
    fun close() {
        neo4jDriver.close()
        postgres.close()
        koinApplication.close()
    }
}
