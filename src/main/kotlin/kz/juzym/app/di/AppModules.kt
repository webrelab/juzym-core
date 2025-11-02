package kz.juzym.app.di

import kz.juzym.audit.AuditEventStore
import kz.juzym.audit.PostgresAuditEventStore
import kz.juzym.audit.StdoutAuditEventStore
import kz.juzym.audit.auditProxy
import kz.juzym.config.ApplicationConfig
import kz.juzym.config.AuditConfig
import kz.juzym.config.AuditStoreType
import kz.juzym.config.DatabaseFactory
import kz.juzym.config.Neo4jConfig
import kz.juzym.config.PostgresConfig
import kz.juzym.config.PostgresDatabaseContext
import kz.juzym.graph.GraphRepository
import kz.juzym.graph.GraphService
import kz.juzym.graph.GraphServiceImpl
import kz.juzym.user.UsersRepository
import org.koin.core.module.Module
import org.koin.dsl.module
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase

fun configurationModule(config: ApplicationConfig): Module = module {
    single { config }
    single { config.neo4j }
    single { config.postgres }
    single { config.audit }
}

val infrastructureModule = module {
    single { DatabaseFactory(get<PostgresConfig>()) }
    single(createdAtStart = true) { get<DatabaseFactory>().connect() }
    single<Driver> {
        val neo4jConfig = get<Neo4jConfig>()
        GraphDatabase.driver(
            neo4jConfig.uri,
            AuthTokens.basic(neo4jConfig.user, neo4jConfig.password)
        )
    }
}

val repositoryModule = module {
    single { GraphRepository(get()) }
    single { UsersRepository(get<PostgresDatabaseContext>().database) }
}

val auditModule = module {
    single<AuditEventStore> {
        val auditConfig = get<AuditConfig>()
        when (auditConfig.store) {
            AuditStoreType.POSTGRES -> PostgresAuditEventStore(get<PostgresDatabaseContext>().database)
            AuditStoreType.STDOUT -> StdoutAuditEventStore()
        }
    }
}

val serviceModule = module {
    single { GraphServiceImpl(get()) }
    single<GraphService> {
        val real: GraphService = get<GraphServiceImpl>()
        auditProxy(real, get())
    }
}
