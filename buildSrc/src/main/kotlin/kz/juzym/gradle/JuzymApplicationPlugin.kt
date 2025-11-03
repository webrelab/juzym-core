package kz.juzym.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.language.base.plugins.LifecycleBasePlugin
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class JuzymApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("application") {
            val fatJar = project.configureFatJar()
            project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
                project.configureTesting(fatJar)
            }
        }
    }

    private fun Project.configureFatJar(): TaskProvider<Jar> {
        val application = extensions.getByType(JavaApplication::class.java)
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)

        val fatJar = tasks.register("fatJar", Jar::class.java) {
            group = LifecycleBasePlugin.BUILD_GROUP
            description = "Assembles a runnable fat jar"

            archiveClassifier.set("all")
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE

            manifest {
                attributes["Main-Class"] = application.mainClass
            }

            from(main.output)
            from({
                configurations.getByName(main.runtimeClasspathConfigurationName)
                    .resolve()
                    .filter { it.name.endsWith(".jar") }
                    .map { zipTree(it) }
            })
        }

        tasks.named(LifecycleBasePlugin.BUILD_TASK_NAME) {
            dependsOn(fatJar)
        }

        return fatJar
    }

    private fun Project.configureTesting(fatJar: TaskProvider<Jar>) {
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
        val test = sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME)
        val apiTest = sourceSets.create("apiTest") {
            compileClasspath += main.output + test.compileClasspath
            runtimeClasspath += output + main.output + test.runtimeClasspath
        }

        configurations.named(apiTest.implementationConfigurationName) {
            extendsFrom(configurations.getByName(test.implementationConfigurationName))
        }
        configurations.named(apiTest.runtimeOnlyConfigurationName) {
            extendsFrom(configurations.getByName(test.runtimeOnlyConfigurationName))
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }

        tasks.register("e2eApiTest", E2eApiTest::class.java) {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Runs end-to-end API tests against the dockerized environment"

            testClassesDirs = apiTest.output.classesDirs
            classpath = apiTest.runtimeClasspath

            dependsOn(fatJar)
            dependsOn(tasks.named(apiTest.classesTaskName))

            fatJarArchive.set(fatJar.flatMap { it.archiveFile })
            dockerComposeFile.set(layout.projectDirectory.file("docker/docker-compose.yml"))
            dockerWorkingDirectory.set(layout.projectDirectory.dir("docker"))
            readinessPorts.addAll(listOf(5432, 7687, 6379))
            applicationHealthUrl.set("http://localhost:8080/health")
            applicationEnvironment.putAll(
                mapOf(
                    "APP_ENV" to "TEST",
                    "NEO4J_URI" to "bolt://localhost:7687",
                    "NEO4J_USER" to "neo4j",
                    "NEO4J_PASSWORD" to "juzymneo4j",
                    "POSTGRES_URL" to "jdbc:postgresql://localhost:5432/juzym",
                    "POSTGRES_USER" to "juzym",
                    "POSTGRES_PASSWORD" to "juzym",
                    "REDIS_HOST" to "localhost",
                    "REDIS_PORT" to "6379",
                    "JWT_SECRET" to "test-secret",
                    "JWT_ISSUER" to "test-issuer",
                    "JWT_TTL_SECONDS" to "3600",
                    "AUDIT_STORE" to "STDOUT",
                    "SERVER_PORT" to "8080"
                )
            )
            systemProperty("apiTest.baseUri", "http://localhost:8080")
        }
    }
}

abstract class E2eApiTest : Test() {
    private var composeProcess: Process? = null
    private var composeLogs: ExecutorService? = null
    private var composeOutput: Future<*>? = null
    private var applicationProcess: Process? = null
    private var applicationLogs: ExecutorService? = null
    private var applicationOutput: Future<*>? = null

    abstract val fatJarArchive: RegularFileProperty
    abstract val dockerComposeFile: RegularFileProperty
    abstract val dockerWorkingDirectory: DirectoryProperty
    abstract val readinessPorts: ListProperty<Int>
    @get:Input
    abstract val applicationEnvironment: MapProperty<String, String>
    abstract val applicationHealthUrl: Property<String>

    init {
        doFirst {
            val dockerDir = dockerWorkingDirectory.get().asFile
            deleteDataDirectories(dockerDir)
            startDocker(dockerDir)
            waitForServices(readinessPorts.get())
            startApplication()
            waitForHealth()
        }

        doLast {
            stopApplication()
            stopDocker()
        }

        project.gradle.buildFinished {
            stopApplication()
            stopDocker()
        }
    }

    private fun deleteDataDirectories(dockerDir: File) {
        listOf("neo4j", "postgres").forEach { name ->
            val directory = dockerDir.resolve(name)
            if (directory.exists()) {
                project.logger.lifecycle("[e2e] Deleting docker data directory: ${directory.absolutePath}")
                directory.deleteRecursively()
            }
        }
    }

    private fun startDocker(dockerDir: File) {
        val composeFile = dockerComposeFile.get().asFile
        require(composeFile.exists()) { "docker-compose file not found at ${composeFile.absolutePath}" }

        runDockerComposeDown(dockerDir, composeFile)

        val command = resolveDockerComposeCommand(composeFile)
        project.logger.lifecycle("[e2e] Starting docker compose: ${command.joinToString(" ")}")

        val processBuilder = ProcessBuilder(command)
            .directory(dockerDir)
            .redirectErrorStream(true)

        composeProcess = processBuilder.start()
        composeLogs = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "e2e-docker-logs").apply { isDaemon = true }
        }
        composeOutput = composeLogs?.submit(logStreamTask(composeProcess!!, project.logger, "docker"))
    }

    private fun waitForServices(ports: List<Int>) {
        val timeout = Duration.ofMinutes(5)
        ports.forEach { port ->
            waitForPort("localhost", port, timeout)
        }
    }

    private fun waitForPort(host: String, port: Int, timeout: Duration) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            try {
                Socket(host, port).use {
                    project.logger.lifecycle("[e2e] Service available on $host:$port")
                    return
                }
            } catch (ignored: Exception) {
                try {
                    Thread.sleep(1_000)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    error("Interrupted while waiting for service on $host:$port")
                }
            }
        }
        error("Timed out waiting for service on $host:$port")
    }

    private fun startApplication() {
        val jarFile = fatJarArchive.get().asFile
        val environment = applicationEnvironment.get()
        project.logger.lifecycle("[e2e] Starting application jar: ${jarFile.absolutePath}")

        val builder = ProcessBuilder(listOf("java", "-jar", jarFile.absolutePath))
            .directory(project.projectDir)
            .redirectErrorStream(true)

        builder.environment().putAll(environment)

        applicationProcess = builder.start()
        applicationLogs = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "e2e-app-logs").apply { isDaemon = true }
        }
        applicationOutput = applicationLogs?.submit(logStreamTask(applicationProcess!!, project.logger, "app"))
    }

    private fun waitForHealth() {
        val healthUrl = URL(applicationHealthUrl.get())
        val timeout = System.nanoTime() + Duration.ofMinutes(5).toNanos()
        while (System.nanoTime() < timeout) {
            try {
                val connection = healthUrl.openConnection() as HttpURLConnection
                connection.connectTimeout = 2_000
                connection.readTimeout = 2_000
                connection.requestMethod = "GET"
                val code = connection.responseCode
                connection.inputStream.use { }
                if (code in 200..299) {
                    project.logger.lifecycle("[e2e] Application is healthy at $healthUrl")
                    return
                }
            } catch (_: Exception) {
                // ignore and retry
            }
            try {
                Thread.sleep(1_000)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                error("Interrupted while waiting for application health check")
            }
        }
        error("Timed out waiting for application health check at $healthUrl")
    }

    private fun stopApplication() {
        applicationOutput?.cancel(true)
        applicationLogs?.shutdownNow()
        applicationProcess?.let { process ->
            project.logger.lifecycle("[e2e] Stopping application process")
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        }
        applicationProcess = null
    }

    private fun stopDocker() {
        composeOutput?.cancel(true)
        composeLogs?.shutdownNow()
        composeProcess?.let { process ->
            project.logger.lifecycle("[e2e] Stopping docker compose process")
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        }
        composeProcess = null

        val composeFile = dockerComposeFile.orNull?.asFile ?: return
        runDockerComposeDown(dockerWorkingDirectory.get().asFile, composeFile)
    }

    private fun runDockerComposeDown(dockerDir: File, composeFile: File) {
        val command = resolveDockerComposeCommand(composeFile, down = true)
        project.logger.lifecycle("[e2e] Running docker compose down: ${command.joinToString(" ")}")
        ProcessBuilder(command)
            .directory(dockerDir)
            .inheritIO()
            .start()
            .waitFor()
    }

    private fun resolveDockerComposeCommand(file: File, down: Boolean = false): List<String> {
        val baseCommand = if (isDockerComposeV2Available()) {
            mutableListOf("docker", "compose", "-f", file.absolutePath)
        } else {
            mutableListOf("docker-compose", "-f", file.absolutePath)
        }
        if (down) {
            baseCommand.addAll(listOf("down", "--remove-orphans"))
        } else {
            baseCommand.addAll(listOf("up", "--build"))
        }
        return baseCommand
    }

    private fun isDockerComposeV2Available(): Boolean {
        return try {
            val process = ProcessBuilder("docker", "compose", "version")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
            }
            finished && process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun logStreamTask(process: Process, logger: Logger, prefix: String): Callable<Unit> = Callable {
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                logger.lifecycle("[$prefix] $line")
            }
        }
    }
}
