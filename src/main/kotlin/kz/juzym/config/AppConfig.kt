package kz.juzym.config

import java.util.*

data class ApplicationConfig(
    val environment: Environment,
    val neo4j: Neo4jConfig,
    val postgres: PostgresConfig,
    val redis: RedisConfig,
    val audit: AuditConfig,
    val jwt: JwtProperties,
    val userLinks: UserLinksConfig,
    val server: ServerConfig
)

data class Neo4jConfig(
    val uri: String,
    val user: String,
    val password: String
)

data class PostgresConfig(
    val jdbcUrl: String,
    val user: String,
    val password: String
)

data class RedisConfig(
    val host: String,
    val port: Int
)

data class AuditConfig(
    val store: AuditStoreType
)

data class JwtProperties(
    val secret: String,
    val issuer: String,
    val ttlSeconds: Long
)

data class UserLinksConfig(
    val activationDomain: String,
    val passwordResetDomain: String,
    val deletionDomain: String,
    val emailChangeDomain: String
) {
    companion object {
        const val ACTIVATION_ENDPOINT = "/activate"
        const val PASSWORD_RESET_ENDPOINT = "/reset"
        const val DELETION_ENDPOINT = "/delete"
        const val EMAIL_CHANGE_ENDPOINT = "/email"
    }

    val activationBaseUrl: String
        get() = activationDomain + ACTIVATION_ENDPOINT

    val passwordResetBaseUrl: String
        get() = passwordResetDomain + PASSWORD_RESET_ENDPOINT

    val deletionBaseUrl: String
        get() = deletionDomain + DELETION_ENDPOINT

    val emailChangeBaseUrl: String
        get() = emailChangeDomain + EMAIL_CHANGE_ENDPOINT
}

data class ServerConfig(
    val host: String,
    val port: Int
)

enum class AuditStoreType {
    STDOUT,
    POSTGRES;

    companion object {
        fun fromValue(raw: String?): AuditStoreType = raw
            ?.takeIf { it.isNotBlank() }
            ?.let { value -> entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } }
            ?: STDOUT
    }
}

enum class Environment {
    DEV,
    TEST;

    companion object {
        private const val ENVIRONMENT_VARIABLE = "APP_ENV"

        fun fromEnv(): Environment = fromValue(System.getenv(ENVIRONMENT_VARIABLE))

        fun fromValue(raw: String?): Environment = raw
            ?.takeIf { it.isNotBlank() }
            ?.let { value -> entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } }
            ?: DEV
    }
}

object AppConfigLoader {
    fun load(
        environment: Environment = Environment.fromEnv(),
        overrides: Map<String, String> = emptyMap()
    ): ApplicationConfig {
        val source = HashMap(System.getenv())
        source.putAll(overrides)

        fun readInternal(uppercaseKey: String): String? {
            val environmentKey = "${uppercaseKey}_${environment.name}"
            return source[environmentKey] ?: source[uppercaseKey]
        }

        fun read(key: String): String {
            val uppercaseKey = key.uppercase(Locale.getDefault())
            return readInternal(uppercaseKey)
                ?: error("Missing configuration value for $uppercaseKey in environment ${environment.name}")
        }

        fun read(key: String, defaultValue: () -> String): String {
            val uppercaseKey = key.uppercase(Locale.getDefault())
            return readInternal(uppercaseKey) ?: defaultValue()
        }

        fun readOptional(key: String): String? {
            val uppercaseKey = key.uppercase(Locale.getDefault())
            return readInternal(uppercaseKey)
        }

        fun normalizeDomain(value: String): String = value.trim().removeSuffix("/")

        fun readUserLinkDomain(key: String, fallback: String): String {
            val raw = readOptional(key) ?: fallback
            return normalizeDomain(raw)
        }

        val defaultUserLinksDomain = readOptional("user_links_domain")?.let(::normalizeDomain)
            ?: "http://localhost:3000"

        return ApplicationConfig(
            environment = environment,
            neo4j = Neo4jConfig(
                uri = read("neo4j_uri") { "bolt://localhost:7687" },
                user = read("neo4j_user") { "neo4j" },
                password = read("neo4j_password") { "juzymneo4j" }
            ),
            postgres = PostgresConfig(
                jdbcUrl = read("postgres_url") { "jdbc:postgresql://localhost:5432/juzym" },
                user = read("postgres_user") { "juzym" },
                password = read("postgres_password") { "juzym" }
            ),
            redis = RedisConfig(
                host = readOptional("redis_host") ?: "localhost",
                port = readOptional("redis_port")?.toInt() ?: 6379
            ),
            audit = AuditConfig(
                store = AuditStoreType.fromValue(readOptional("audit_store"))
            ),
            jwt = JwtProperties(
                secret = read("jwt_secret") { "jwt_secret" },
                issuer = read("jwt_issuer") { "jwt_issuer" },
                ttlSeconds = read("jwt_ttl_seconds") { "3600" }.toLong()
            ),
            userLinks = UserLinksConfig(
                activationDomain = readUserLinkDomain("user_activation_domain", defaultUserLinksDomain),
                passwordResetDomain = readUserLinkDomain("user_password_reset_domain", defaultUserLinksDomain),
                deletionDomain = readUserLinkDomain("user_deletion_domain", defaultUserLinksDomain),
                emailChangeDomain = readUserLinkDomain("user_email_change_domain", defaultUserLinksDomain)
            ),
            server = ServerConfig(
                host = readOptional("server_host") ?: "0.0.0.0",
                port = readOptional("server_port")?.toInt() ?: 8080
            )
        )
    }
}
