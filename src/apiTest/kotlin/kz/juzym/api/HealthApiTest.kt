package kz.juzym.api

import io.restassured.RestAssured
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HealthApiTest {

    @BeforeAll
    fun setUp() {
        RestAssured.baseURI = System.getProperty("apiTest.baseUri", "http://localhost:8080")
        RestAssured.basePath = "/api/v1"
    }

    @Test
    fun `health endpoint returns ok`() {
        RestAssured
            .given()
            .`when`()
            .get("/health")
            .then()
            .statusCode(200)
            .body("status", equalTo("ok"))
    }
}
