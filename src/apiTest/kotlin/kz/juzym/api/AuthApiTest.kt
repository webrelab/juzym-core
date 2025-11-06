package kz.juzym.api

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.restassured.RestAssured
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.UUID
import kotlin.random.Random

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthApiTest {

    @BeforeAll
    fun setUpSuite() {
        RestAssured.baseURI = System.getProperty("apiTest.baseUri", "http://localhost:8080")
        RestAssured.basePath = "/api/v1"
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails()
    }

    @Test
    fun `me endpoint requires authentication`() {
        RestAssured
            .given()
            .`when`()
            .get("/auth/me")
            .then()
            .statusCode(401)
            .body("error.code", equalTo("unauthorized"))
    }

    @Test
    fun `me endpoint forbids pending user`() {
        val registration = registerUser()
        val algorithm = Algorithm.HMAC256("jwt_secret")
        val now = Instant.now()
        val token = JWT.create()
            .withIssuer("jwt_issuer")
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(3600)))
            .withSubject(registration.userId)
            .withClaim("iin", registration.iin)
            .withArrayClaim("roles", emptyArray())
            .sign(algorithm)

        RestAssured
            .given()
            .header("Authorization", "Bearer $token")
            .`when`()
            .get("/auth/me")
            .then()
            .statusCode(403)
            .body("error.code", equalTo("user_not_activated"))
    }

    @Test
    fun `login refresh logout flow`() {
        val testUser = createActiveUser()

        val loginResponse = RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "email" to testUser.email,
                    "password" to testUser.password,
                    "device" to mapOf(
                        "deviceId" to testUser.deviceId,
                        "platform" to "web",
                        "deviceName" to "JUnit",
                        "clientVersion" to "1.0"
                    )
                )
            )
            .`when`()
            .post("/auth/login")
            .then()
            .statusCode(200)
            .body("userId", equalTo(testUser.userId))
            .body("session.accessToken", notNullValue())
            .body("session.refreshToken", notNullValue())
            .extract()

        val refreshToken = loginResponse.cookie("refreshToken")
        val accessToken = loginResponse.jsonPath().getString("session.accessToken")

        RestAssured
            .given()
            .header("Authorization", "Bearer $accessToken")
            .`when`()
            .get("/auth/me")
            .then()
            .statusCode(200)
            .body("user.email", equalTo(testUser.email))

        RestAssured
            .given()
            .header("Authorization", "Bearer $accessToken")
            .cookie("refreshToken", refreshToken)
            .`when`()
            .get("/auth/sessions")
            .then()
            .statusCode(200)
            .body("total", equalTo(1))
            .body("sessions.current", hasItem(true))

        val refreshResponse = RestAssured
            .given()
            .contentType(ContentType.JSON)
            .cookie("refreshToken", refreshToken)
            .body(mapOf("deviceId" to testUser.deviceId))
            .`when`()
            .post("/auth/refresh")
            .then()
            .statusCode(200)
            .body("session.accessToken", notNullValue())
            .body("session.refreshToken", notNullValue())
            .extract()

        val rotatedToken = refreshResponse.cookie("refreshToken")

        RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(mapOf("refreshToken" to refreshToken, "deviceId" to testUser.deviceId))
            .`when`()
            .post("/auth/refresh")
            .then()
            .statusCode(409)
            .body("error.code", equalTo("token_already_rotated"))

        RestAssured
            .given()
            .header("Authorization", "Bearer $accessToken")
            .cookie("refreshToken", rotatedToken)
            .`when`()
            .post("/auth/logout")
            .then()
            .statusCode(204)

        RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(mapOf("refreshToken" to rotatedToken))
            .`when`()
            .post("/auth/refresh")
            .then()
            .statusCode(401)
    }

    @Test
    fun `login fails for invalid credentials and pending registration`() {
        val activeUser = createActiveUser()

        RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "email" to activeUser.email,
                    "password" to "wrong",
                    "device" to mapOf("deviceId" to UUID.randomUUID().toString(), "platform" to "web")
                )
            )
            .`when`()
            .post("/auth/login")
            .then()
            .statusCode(401)
            .body("error.code", equalTo("invalid_credentials"))

        val pendingRegistration = registerUser()

        RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "email" to pendingRegistration.email,
                    "password" to pendingRegistration.password,
                    "device" to mapOf("deviceId" to UUID.randomUUID().toString(), "platform" to "web")
                )
            )
            .`when`()
            .post("/auth/login")
            .then()
            .statusCode(403)
            .body("error.code", equalTo("user_not_activated"))
    }

    @Test
    fun `password change scenarios`() {
        val user = createActiveUser()
        val tokens = login(user)
        val accessToken = tokens.accessToken
        val refreshToken = tokens.refreshToken

        RestAssured
            .given()
            .header("Authorization", "Bearer $accessToken")
            .contentType(ContentType.JSON)
            .body(mapOf("currentPassword" to "wrong", "newPassword" to "AnotherPass1!"))
            .`when`()
            .post("/auth/password/change")
            .then()
            .statusCode(401)
            .body("error.code", equalTo("invalid_credentials"))

        RestAssured
            .given()
            .header("Authorization", "Bearer $accessToken")
            .contentType(ContentType.JSON)
            .body(mapOf("currentPassword" to user.password, "newPassword" to "short"))
            .`when`()
            .post("/auth/password/change")
            .then()
            .statusCode(400)
            .body("error.code", equalTo("weak_password"))

        RestAssured
            .given()
            .header("Authorization", "Bearer $accessToken")
            .contentType(ContentType.JSON)
            .body(mapOf("currentPassword" to user.password, "newPassword" to "FreshPass1!"))
            .`when`()
            .post("/auth/password/change")
            .then()
            .statusCode(204)

        RestAssured
            .given()
            .header("Authorization", "Bearer $accessToken")
            .cookie("refreshToken", refreshToken)
            .`when`()
            .post("/auth/logout")
            .then()
            .statusCode(204)

        RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "email" to user.email,
                    "password" to user.password,
                    "device" to mapOf("deviceId" to UUID.randomUUID().toString(), "platform" to "web")
                )
            )
            .`when`()
            .post("/auth/login")
            .then()
            .statusCode(401)

        RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "email" to user.email,
                    "password" to "FreshPass1!",
                    "device" to mapOf("deviceId" to UUID.randomUUID().toString(), "platform" to "web")
                )
            )
            .`when`()
            .post("/auth/login")
            .then()
            .statusCode(200)
    }

    @Test
    fun `password reset flow via registration`() {
        val registration = registerUser()
        verifyRegistration(registration.verificationToken)

        val forgotJson = RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(mapOf("email" to registration.email))
            .`when`()
            .post("/auth/password/forgot")
            .then()
            .statusCode(200)
            .body("sent", equalTo(true))
            .extract()
            .jsonPath()

        val resetToken = forgotJson.getString("debugResetToken")
        require(!resetToken.isNullOrBlank()) { "Expected password reset token in test environment" }

        RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(mapOf("token" to resetToken, "newPassword" to "ResetPass1!"))
            .`when`()
            .post("/auth/password/reset")
            .then()
            .statusCode(200)
            .body("reset", equalTo(true))

        RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(mapOf("token" to "invalid", "newPassword" to "ResetPass1!"))
            .`when`()
            .post("/auth/password/reset")
            .then()
            .statusCode(400)
            .body("error.code", equalTo("invalid_or_expired_token"))
    }

    @Test
    fun `email change flow`() {
        val user = createActiveUser()
        val tokens = login(user)

        val newEmail = "updated-${UUID.randomUUID()}@example.com"

        val requestResponse = RestAssured
            .given()
            .header("Authorization", "Bearer ${tokens.accessToken}")
            .contentType(ContentType.JSON)
            .body(mapOf("newEmail" to newEmail))
            .`when`()
            .post("/auth/email/change/request")
            .then()
            .statusCode(200)
            .body("sent", equalTo(true))
            .extract()
            .jsonPath()

        val debugLink = requestResponse.getString("debugLink")
        require(!debugLink.isNullOrBlank()) { "Expected debug link in test environment" }
        val token = debugLink.substringAfterLast('/')

        RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(mapOf("token" to token))
            .`when`()
            .post("/auth/email/change/confirm")
            .then()
            .statusCode(200)
            .body("updated", equalTo(true))

        RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "email" to user.email,
                    "password" to user.password,
                    "device" to mapOf("deviceId" to UUID.randomUUID().toString(), "platform" to "web")
                )
            )
            .`when`()
            .post("/auth/login")
            .then()
            .statusCode(401)

        RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "email" to newEmail,
                    "password" to user.password,
                    "device" to mapOf("deviceId" to UUID.randomUUID().toString(), "platform" to "web")
                )
            )
            .`when`()
            .post("/auth/login")
            .then()
            .statusCode(200)

        RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(mapOf("token" to "invalid"))
            .`when`()
            .post("/auth/email/change/confirm")
            .then()
            .statusCode(400)
            .body("error.code", equalTo("invalid_token"))
    }

    private fun createActiveUser(): TestUser {
        val registration = registerUser()
        val verification = verifyRegistration(registration.verificationToken)
        return TestUser(
            userId = verification.userId,
            email = registration.email,
            iin = registration.iin,
            password = registration.password,
            deviceId = "dev-${UUID.randomUUID()}"
        )
    }

    private fun login(user: TestUser): Tokens {
        val response = RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "email" to user.email,
                    "password" to user.password,
                    "device" to mapOf(
                        "deviceId" to user.deviceId,
                        "platform" to "web"
                    )
                )
            )
            .`when`()
            .post("/auth/login")
            .then()
            .statusCode(200)
            .extract()

        return Tokens(
            accessToken = response.jsonPath().getString("session.accessToken"),
            refreshToken = response.cookie("refreshToken")
        )
    }

    private fun registerUser(): RegistrationContext {
        val email = "user-${UUID.randomUUID()}@example.com"
        val iin = generateIin()
        val password = "Password1!"

        val response = RestAssured
            .given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(
                mapOf(
                    "iin" to iin,
                    "email" to email,
                    "password" to password,
                    "displayName" to "Test User",
                    "acceptedTermsVersion" to "v1",
                    "acceptedPrivacyVersion" to "v1"
                )
            )
            .`when`()
            .post("/auth/registration")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()

        val userId = response.getString("userId")
        val token = response.getString("debugVerificationToken")
        require(!token.isNullOrBlank()) { "Expected verification token in test environment" }

        return RegistrationContext(
            userId = userId,
            email = email,
            iin = iin,
            password = password,
            verificationToken = token
        )
    }

    private fun verifyRegistration(token: String): VerificationResult {
        val response = RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(mapOf("token" to token))
            .`when`()
            .post("/auth/registration/verify-email")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()

        return VerificationResult(
            userId = response.getString("userId"),
            accessToken = response.getString("session.accessToken"),
            refreshToken = response.getString("session.refreshToken"),
            avatarId = response.getString("avatarId")
        )
    }

    private fun generateIin(): String {
        val randomTimeMillis = Random.nextLong(System.currentTimeMillis())
        val formatter = DateTimeFormatter.ofPattern("YYMMdd")
        val idRandom = Random.nextInt(999999)
        val dateString = Instant.ofEpochMilli(randomTimeMillis)
            .atZone(ZoneId.of("UTC"))
            .format(formatter)
        val idString = "%06d".format(idRandom)
        return "$dateString$idString"
    }

    private data class TestUser(
        val userId: String,
        val email: String,
        val iin: String,
        val password: String,
        val deviceId: String
    )

    private data class Tokens(val accessToken: String, val refreshToken: String)

    private data class RegistrationContext(
        val userId: String,
        val email: String,
        val iin: String,
        val password: String,
        val verificationToken: String
    )

    private data class VerificationResult(
        val userId: String,
        val accessToken: String,
        val refreshToken: String,
        val avatarId: String
    )
}
