package kz.juzym.api

import io.restassured.RestAssured
import io.restassured.http.ContentType
import kz.juzym.user.UserStatus
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.hamcrest.Matchers.hasItems
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
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
    fun `registration onboarding flow`() {
        val email = "user-${UUID.randomUUID()}@example.com"
        val iin = generateIin()
        val password = "Password1!"

        RestAssured
            .given()
            .`when`()
            .get("/auth/registration/password-policy")
            .then()
            .statusCode(200)
            .body("minLength", greaterThanOrEqualTo(8))
            .body("requireLower", equalTo(true))

        RestAssured
            .given()
            .`when`()
            .get("/auth/registration/limits")
            .then()
            .statusCode(200)
            .body("maxAttemptsPerIpPerHour", greaterThanOrEqualTo(1))
            .body("emailTokenTtlMinutes", greaterThanOrEqualTo(1))

        RestAssured
            .given()
            .queryParam("email", email)
            .`when`()
            .get("/auth/registration/email-availability")
            .then()
            .statusCode(200)
            .body("available", equalTo(true))

        val registrationJson = RestAssured
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
            .body("status", equalTo(UserStatus.PENDING.name))
            .extract()
            .jsonPath()

        val userId = registrationJson.getString("userId")
        val initialToken = registrationJson.getString("debugVerificationToken")
        require(!initialToken.isNullOrBlank()) { "Expected verification token in test environment" }

        RestAssured
            .given()
            .queryParam("email", email)
            .`when`()
            .get("/auth/registration/status")
            .then()
            .statusCode(200)
            .body("status", equalTo(UserStatus.PENDING.name))
            .body("verification.required", equalTo(true))

        RestAssured
            .given()
            .queryParam("email", email)
            .`when`()
            .get("/auth/registration/email-availability")
            .then()
            .statusCode(200)
            .body("available", equalTo(false))

        Thread.sleep(1100)

        val resendJson = RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "iin" to iin,
                    "email" to email
                )
            )
            .`when`()
            .post("/auth/registration/resend-email")
            .then()
            .statusCode(200)
            .body("sent", equalTo(true))
            .extract()
            .jsonPath()

        val verificationToken = resendJson.getString("debugVerificationToken") ?: initialToken
        require(verificationToken.isNotBlank()) { "Expected verification token after resend" }

        val verifyJson = RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(mapOf("token" to verificationToken))
            .`when`()
            .post("/auth/registration/verify-email")
            .then()
            .statusCode(200)
            .body("status", equalTo(UserStatus.ACTIVE.name))
            .extract()
            .jsonPath()

        val avatarId = verifyJson.getString("avatarId")

        RestAssured
            .given()
            .contentType(ContentType.JSON)
            .header("Authorization", "Bearer $userId")
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
            .body("avatarId", equalTo(avatarId))
            .body("updated", hasItems("photoUrl", "about", "locale", "timezone"))

        RestAssured
            .given()
            .queryParam("email", email)
            .`when`()
            .get("/auth/registration/status")
            .then()
            .statusCode(200)
            .body("status", equalTo(UserStatus.ACTIVE.name))
            .body("verification.required", equalTo(false))

        val forgotJson = RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(mapOf("email" to email))
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
            .body(
                mapOf(
                    "token" to resetToken,
                    "newPassword" to "NewPassword1!"
                )
            )
            .`when`()
            .post("/auth/password/reset")
            .then()
            .statusCode(200)
            .body("reset", equalTo(true))
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
}
