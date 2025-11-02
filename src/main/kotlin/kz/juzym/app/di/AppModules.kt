package kz.juzym.app.di

import kz.juzym.audit.AuditEventStore
import kz.juzym.audit.PostgresAuditEventStore
import kz.juzym.audit.StdoutAuditEventStore
import kz.juzym.audit.auditProxy
import kz.juzym.config.ApplicationConfig
import kz.juzym.config.AuditConfig
import kz.juzym.config.AuditStoreType
import kz.juzym.config.DatabaseFactory
import kz.juzym.config.JwtProperties
import kz.juzym.config.Neo4jConfig
import kz.juzym.config.PostgresConfig
import kz.juzym.config.PostgresDatabaseContext
import kz.juzym.config.RedisConfig
import kz.juzym.config.UserLinksConfig
import kz.juzym.graph.GraphRepository
import kz.juzym.graph.GraphService
import kz.juzym.graph.GraphServiceImpl
import kz.juzym.user.ConsoleMailSenderStub
import kz.juzym.user.ExposedUserRepository
import kz.juzym.user.ExposedUserTokenRepository
import kz.juzym.user.MailSenderStub
import kz.juzym.user.UserRepository
import kz.juzym.user.UserService
import kz.juzym.user.UserServiceConfig
import kz.juzym.user.UserTokenRepository
import kz.juzym.user.UserServiceImpl
import kz.juzym.user.security.BcryptPasswordHasher
import kz.juzym.user.security.PasswordHasher
import kz.juzym.user.security.jwt.JwtConfig
import kz.juzym.user.security.jwt.JwtService
import org.koin.core.module.Module
import org.koin.dsl.module
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.StringCodec

fun configurationModule(config: ApplicationConfig): Module = module {
    single { config }
    single { config.neo4j }
    single { config.postgres }
    single { config.redis }
    single { config.audit }
    single { config.jwt }
    single { config.userLinks }
    single { config.server }
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
    single<RedisClient> {
        val redisConfig = get<RedisConfig>()
        val uri = RedisURI.Builder.redis(redisConfig.host, redisConfig.port).build()
        RedisClient.create(uri)
    }
    single<StatefulRedisConnection<String, String>>(createdAtStart = true) {
        get<RedisClient>().connect(StringCodec.UTF8)
    }
}

val repositoryModule = module {
    single { GraphRepository(get()) }
    single<UserRepository> { ExposedUserRepository(get<PostgresDatabaseContext>().database, get()) }
    single<UserTokenRepository> { ExposedUserTokenRepository(get<PostgresDatabaseContext>().database) }
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
    single<PasswordHasher> { BcryptPasswordHasher() }
    single<MailSenderStub> { ConsoleMailSenderStub() }
    single {
        val jwtProps = get<JwtProperties>()
        JwtService(
            JwtConfig(
                secret = jwtProps.secret,
                issuer = jwtProps.issuer,
                ttl = java.time.Duration.ofSeconds(jwtProps.ttlSeconds)
            )
        )
    }
    single {
        val links = get<UserLinksConfig>()
        UserServiceConfig(
            activationLinkBuilder = { token -> "${links.activationBaseUrl}/$token" },
            passwordResetLinkBuilder = { token -> "${links.passwordResetBaseUrl}/$token" },
            deletionLinkBuilder = { token -> "${links.deletionBaseUrl}/$token" },
            emailChangeLinkBuilder = { token -> "${links.emailChangeBaseUrl}/$token" }
        )
    }
    single {
        UserServiceImpl(
            userRepository = get(),
            tokenRepository = get(),
            mailSender = get(),
            passwordHasher = get(),
            jwtService = get(),
            config = get()
        )
    }
    single<UserService> {
        val implementation: UserService = get<UserServiceImpl>()
        auditProxy(implementation, get())
    }
    single { GraphServiceImpl(get()) }
    single<GraphService> {
        val real: GraphService = get<GraphServiceImpl>()
        auditProxy(real, get())
    }
}
