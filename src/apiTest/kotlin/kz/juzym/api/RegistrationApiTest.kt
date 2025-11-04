package kz.juzym.api

import io.restassured.RestAssured
import io.restassured.http.ContentType
import kz.juzym.user.RegistrationStatus
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.random.Random

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistrationApiTest {

    @BeforeAll
    fun setUp() {
        RestAssured.baseURI = System.getProperty("apiTest.baseUri", "http://localhost:8080")
        RestAssured.basePath = "/api/v1"
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails()
    }

    @Test
    fun `password policy describes requirements`() {
        RestAssured
            .given()
            .`when`()
            .get("/auth/registration/password-policy")
            .then()
            .statusCode(200)
            .body("minLength", greaterThanOrEqualTo(8))
            .body("requireLower", equalTo(true))
            .body("requireUpper", equalTo(true))
            .body("requireDigit", equalTo(true))
            .body("requireSymbol", equalTo(true))
    }

    @Test
    fun `registration limits describe quotas`() {
        RestAssured
            .given()
            .`when`()
            .get("/auth/registration/limits")
            .then()
            .statusCode(200)
            .body("maxAttemptsPerIpPerHour", greaterThanOrEqualTo(1))
            .body("maxResendsPerDay", greaterThanOrEqualTo(1))
            .body("emailTokenTtlMinutes", greaterThanOrEqualTo(1))
    }

    @Test
    fun `start registration returns pending status and debug token`() {
        val context = registerUser()

        RestAssured
            .given()
            .queryParam("email", context.email)
            .`when`()
            .get("/auth/registration/email-availability")
            .then()
            .statusCode(200)
            .body("available", equalTo(false))

        RestAssured
            .given()
            .queryParam("email", context.email)
            .`when`()
            .get("/auth/registration/status")
            .then()
            .statusCode(200)
            .body("status", equalTo(RegistrationStatus.PENDING.name))
            .body("verification.required", equalTo(true))
    }

    @Test
    fun `resend email issues new verification token`() {
        val context = registerUser()
        Thread.sleep(1100)

        val resend = RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(mapOf("iin" to context.iin, "email" to context.email))
            .`when`()
            .post("/auth/registration/resend-email")
            .then()
            .statusCode(200)
            .body("sent", equalTo(true))
            .extract()
            .jsonPath()

        val newToken = resend.getString("debugVerificationToken")
        require(!newToken.isNullOrBlank()) { "Expected verification token in test environment" }
        require(newToken != context.verificationToken) { "Expected rotated verification token" }
    }

    @Test
    fun `verify email activates account and updates status`() {
        val context = registerUser()
        val verification = verifyRegistration(context.verificationToken)

        RestAssured
            .given()
            .queryParam("email", context.email)
            .`when`()
            .get("/auth/registration/status")
            .then()
            .statusCode(200)
            .body("status", equalTo(RegistrationStatus.ACTIVE.name))
            .body("verification.required", equalTo(false))

        RestAssured
            .given()
            .queryParam("email", context.email)
            .`when`()
            .get("/auth/registration/email-availability")
            .then()
            .statusCode(200)
            .body("available", equalTo(false))

        RestAssured
            .given()
            .contentType(ContentType.JSON)
            .header("Authorization", "Bearer ${verification.userId}")
            .body(
                mapOf(
                    "photoUrl" to "http://example.com/avatar.png",
                    "about" to "About me",
                    "locale" to "ru-KZ",
                    "timezone" to "Asia/Almaty"
                )
            )
            .`when`()
            .patch("/auth/registration/complete-profile")
            .then()
            .statusCode(200)
            .body("avatarId", equalTo(verification.avatarId))
            .body("updated", hasItems("photoUrl", "about", "locale", "timezone"))
    }

    @Test
    fun `password reset exposes debug token`() {
        val context = registerUser()
        verifyRegistration(context.verificationToken)

        val forgot = RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(mapOf("email" to context.email))
            .`when`()
            .post("/auth/password/forgot")
            .then()
            .statusCode(200)
            .body("sent", equalTo(true))
            .extract()
            .jsonPath()

        val resetToken = forgot.getString("debugResetToken")
        require(!resetToken.isNullOrBlank()) { "Expected password reset token in test environment" }

        RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(mapOf("token" to resetToken, "newPassword" to "NewPassword1!"))
            .`when`()
            .post("/auth/password/reset")
            .then()
            .statusCode(200)
            .body("reset", equalTo(true))
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
                    "locale" to "ru-KZ",
                    "timezone" to "Asia/Almaty",
                    "acceptedTermsVersion" to "v1",
                    "acceptedPrivacyVersion" to "v1",
                    "marketingOptIn" to true
                )
            )
            .`when`()
            .post("/auth/registration")
            .then()
            .statusCode(201)
            .body("status", equalTo(RegistrationStatus.PENDING.name))
            .body("emailVerification.sent", equalTo(true))
            .body("emailVerification.method", equalTo("link"))
            .body("emailVerification.expiresAt", notNullValue())
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
            .body("status", equalTo(RegistrationStatus.ACTIVE.name))
            .extract()
            .jsonPath()

        return VerificationResult(
            userId = response.getString("userId"),
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

    private data class RegistrationContext(
        val userId: String,
        val email: String,
        val iin: String,
        val password: String,
        val verificationToken: String
    )

    private data class VerificationResult(
        val userId: String,
        val avatarId: String
    )
}
