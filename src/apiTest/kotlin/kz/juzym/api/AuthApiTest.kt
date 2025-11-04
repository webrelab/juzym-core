package kz.juzym.api

import io.restassured.RestAssured
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

    @BeforeEach
    fun cleanCapturedMail() {
        RestAssured
            .given()
            .`when`()
            .delete("/dev/mail")
            .then()
            .statusCode(204)
    }

    @Test
    fun `login refresh logout flow`() {
        val testUser = createTestUser()

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
            .cookie("refreshToken", refreshToken)
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
    fun `login fails for invalid credentials and blocked user`() {
        val testUser = createTestUser()

        RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "email" to testUser.email,
                    "password" to "wrong",
                    "device" to mapOf("deviceId" to UUID.randomUUID().toString(), "platform" to "web")
                )
            )
            .`when`()
            .post("/auth/login")
            .then()
            .statusCode(401)
            .body("error.code", equalTo("invalid_credentials"))

        val blockedUser = createTestUser(status = "BLOCKED")

        RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "email" to blockedUser.email,
                    "password" to blockedUser.password,
                    "device" to mapOf("deviceId" to UUID.randomUUID().toString(), "platform" to "web")
                )
            )
            .`when`()
            .post("/auth/login")
            .then()
            .statusCode(403)
            .body("error.code", equalTo("account_blocked"))
    }

    @Test
    fun `password change scenarios`() {
        val user = createTestUser()
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
        val registration = startRegistration()
        val activationMail = fetchLatestMail("activation", registration.email)
        val activationToken = activationMail.token

        RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(mapOf("token" to activationToken))
            .`when`()
            .post("/auth/registration/verify-email")
            .then()
            .statusCode(200)

        RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(mapOf("email" to registration.email))
            .`when`()
            .post("/auth/password/forgot")
            .then()
            .statusCode(200)
            .body("sent", equalTo(true))

        val resetMail = fetchLatestMail("password_reset", registration.email)
        val resetToken = resetMail.token

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
        val user = createTestUser()
        val tokens = login(user)

        val newEmail = "updated-${UUID.randomUUID()}@example.com"

        RestAssured
            .given()
            .header("Authorization", "Bearer ${tokens.accessToken}")
            .contentType(ContentType.JSON)
            .body(mapOf("newEmail" to newEmail))
            .`when`()
            .post("/auth/email/change/request")
            .then()
            .statusCode(200)
            .body("sent", equalTo(true))

        val mail = fetchLatestMail("email_change", newEmail)

        RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(mapOf("token" to mail.token))
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

    private fun createTestUser(status: String = "ACTIVE"): TestUser {
        val email = "user-${UUID.randomUUID()}@example.com"
        val iin = generateIin()
        val password = "Password1!"
        val deviceId = "dev-${UUID.randomUUID()}"

        val response = RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "email" to email,
                    "iin" to iin,
                    "password" to password,
                    "status" to status
                )
            )
            .`when`()
            .post("/dev/users")
            .then()
            .statusCode(201)
            .extract()

        val userId = response.jsonPath().getString("userId")
        return TestUser(userId, email, iin, password, deviceId)
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

    private fun startRegistration(): RegistrationContext {
        val email = "reg-${UUID.randomUUID()}@example.com"
        val iin = generateIin()
        val password = "Password1!"

        RestAssured
            .given()
            .contentType(ContentType.JSON)
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

        return RegistrationContext(email, iin, password)
    }

    private fun fetchLatestMail(type: String, to: String): CapturedMailInfo {
        val response = RestAssured
            .given()
            .queryParam("type", type)
            .queryParam("to", to)
            .`when`()
            .get("/dev/mail/latest")
            .then()
            .statusCode(200)
            .extract()

        val link = response.jsonPath().getString("link")
        val token = link.substringAfterLast('/')
        return CapturedMailInfo(link, token)
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

    private data class RegistrationContext(val email: String, val iin: String, val password: String)

    private data class CapturedMailInfo(val link: String, val token: String)
}
