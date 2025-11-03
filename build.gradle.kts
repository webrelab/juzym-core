plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    id("kz.juzym.gradle.application")
}

group = "kz.juzym"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.neo4j.driver)
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.koin.core)
    implementation(libs.jwt)
    implementation(libs.jbcrypt)
    implementation(libs.lettuce)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.jackson)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.logback.classic)

    implementation(libs.typesafe.config)
    implementation(libs.jackson.module.kotlin)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.neo4j.harness) {
        exclude(group = "org.neo4j", module = "neo4j-slf4j-provider")
    }
    testImplementation(libs.slf4j.simple)
    testImplementation(libs.embedded.postgres)
    testImplementation(libs.mockk)

    apiTestImplementation(libs.rest.assured)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("kz.juzym.core.MainKt")
}
