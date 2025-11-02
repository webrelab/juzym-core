package kz.juzym.config

import java.util.HashMap
import java.util.Locale

data class ApplicationConfig(
    val environment: Environment,
    val neo4j: Neo4jConfig,
    val postgres: PostgresConfig,
    val audit: AuditConfig,
    val jwt: JwtProperties,
    val userLinks: UserLinksConfig
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

data class AuditConfig(
    val store: AuditStoreType
)

data class JwtProperties(
    val secret: String,
    val issuer: String,
    val ttlSeconds: Long
)

data class UserLinksConfig(
    val activationBaseUrl: String,
    val passwordResetBaseUrl: String,
    val deletionBaseUrl: String,
    val emailChangeBaseUrl: String
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

        fun read(key: String): String {
            val uppercaseKey = key.uppercase(Locale.getDefault())
            val environmentKey = "${uppercaseKey}_${environment.name}"
            return source[environmentKey]
                ?: source[uppercaseKey]
                ?: error("Missing configuration value for $uppercaseKey in environment ${environment.name}")
        }

        fun readOptional(key: String): String? {
            val uppercaseKey = key.uppercase(Locale.getDefault())
            val environmentKey = "${uppercaseKey}_${environment.name}"
            return source[environmentKey] ?: source[uppercaseKey]
        }

        return ApplicationConfig(
            environment = environment,
            neo4j = Neo4jConfig(
                uri = read("neo4j_uri"),
                user = read("neo4j_user"),
                password = read("neo4j_password")
            ),
            postgres = PostgresConfig(
                jdbcUrl = read("postgres_url"),
                user = read("postgres_user"),
                password = read("postgres_password")
            ),
            audit = AuditConfig(
                store = AuditStoreType.fromValue(readOptional("audit_store"))
            ),
            jwt = JwtProperties(
                secret = read("jwt_secret"),
                issuer = read("jwt_issuer"),
                ttlSeconds = read("jwt_ttl_seconds").toLong()
            ),
            userLinks = UserLinksConfig(
                activationBaseUrl = read("user_activation_base_url"),
                passwordResetBaseUrl = read("user_password_reset_base_url"),
                deletionBaseUrl = read("user_deletion_base_url"),
                emailChangeBaseUrl = read("user_email_change_base_url")
            )
        )
    }
}
