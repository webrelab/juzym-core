package kz.juzym.app

import kz.juzym.config.AppConfigLoader
import kz.juzym.config.ApplicationConfig
import kz.juzym.config.Environment
import kz.juzym.graph.GraphRepository
import kz.juzym.postgres.DatabaseFactory
import kz.juzym.postgres.PostgresDatabaseContext
import kz.juzym.postgres.UsersRepository
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase

class Application(
    private val config: ApplicationConfig = AppConfigLoader.load()
) {

    fun start(): ApplicationContext {
        val driver = createNeo4jDriver(config)
        driver.verifyConnectivity()

        val databaseFactory = DatabaseFactory(config.postgres)
        val postgresContext = databaseFactory.connect()
        databaseFactory.verifyConnection(postgresContext)
        databaseFactory.ensureSchema(postgresContext)

        val graphRepository = GraphRepository(driver)
        val usersRepository = UsersRepository(postgresContext.database)

        return ApplicationContext(
            config = config,
            neo4jDriver = driver,
            postgres = postgresContext,
            graphRepository = graphRepository,
            usersRepository = usersRepository
        )
    }

    private fun createNeo4jDriver(config: ApplicationConfig): Driver {
        return GraphDatabase.driver(
            config.neo4j.uri,
            AuthTokens.basic(config.neo4j.user, config.neo4j.password)
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
    val usersRepository: UsersRepository
) {
    fun close() {
        neo4jDriver.close()
        postgres.close()
    }
}
