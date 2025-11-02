package kz.juzym.user.security.jwt

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID

class JwtService(private val config: JwtConfig) {
    private val algorithm: Algorithm = Algorithm.HMAC256(config.secret)
    private val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(config.issuer)
        .build()

    fun generate(userId: UUID, iin: String): String {
        val issuedAt = Instant.now()
        val expiresAt = issuedAt.plus(config.ttl)
        return JWT.create()
            .withIssuer(config.issuer)
            .withIssuedAt(Date.from(issuedAt))
            .withExpiresAt(Date.from(expiresAt))
            .withSubject(userId.toString())
            .withClaim(IIN_CLAIM, iin)
            .sign(algorithm)
    }

    fun verify(token: String): JwtPrincipal? = try {
        val decoded = verifier.verify(token)
        JwtPrincipal(
            userId = UUID.fromString(decoded.subject),
            iin = decoded.getClaim(IIN_CLAIM).asString(),
            issuedAt = decoded.issuedAt.toInstant(),
            expiresAt = decoded.expiresAt.toInstant()
        )
    } catch (ex: JWTVerificationException) {
        null
    }

    companion object {
        private const val IIN_CLAIM = "iin"
    }
}

data class JwtConfig(
    val secret: String,
    val issuer: String,
    val ttl: Duration
)

data class JwtPrincipal(
    val userId: UUID,
    val iin: String,
    val issuedAt: Instant,
    val expiresAt: Instant
)
