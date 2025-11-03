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
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskAction
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.jvm.Volatile

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
            logsDirectory.set(layout.buildDirectory.dir("e2e-logs"))
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
    private var currentLogsDirectory: File? = null
    @Volatile
    private var cleanupPerformed: Boolean = true

    @get:Classpath
    abstract val fatJarArchive: RegularFileProperty
    @get:InputFile
    abstract val dockerComposeFile: RegularFileProperty
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dockerWorkingDirectory: DirectoryProperty
    @get:Input
    abstract val readinessPorts: ListProperty<Int>
    @get:Input
    abstract val applicationEnvironment: MapProperty<String, String>
    @get:Input
    abstract val applicationHealthUrl: Property<String>
    @get:OutputDirectory
    abstract val logsDirectory: DirectoryProperty

    init {
        project.gradle.buildFinished {
            stopApplication()
            stopDocker()
            if (!cleanupPerformed) {
                val dockerDir = dockerWorkingDirectory.orNull?.asFile
                val composeFile = dockerComposeFile.orNull?.asFile
                if (dockerDir != null && composeFile != null && composeFile.exists()) {
                    runCleanup("build finished cleanup") {
                        runDockerComposeDown(dockerDir, composeFile, "build finished cleanup", "docker-compose-down-build-finished.log")
                    }
                }
                cleanupPerformed = true
            }
        }
    }

    @TaskAction
    override fun executeTests() {
        val dockerDir = dockerWorkingDirectory.get().asFile
        val composeFile = dockerComposeFile.get().asFile
        require(composeFile.exists()) { "docker-compose file not found at ${composeFile.absolutePath}" }

        prepareLogsDirectory()
        cleanupPerformed = false

        try {
            runDockerComposeDown(dockerDir, composeFile, "pre-run cleanup", "docker-compose-down-before.log")
            deleteDataDirectories(dockerDir)
            startDocker(dockerDir, composeFile)
            waitForServices(readinessPorts.get())
            startApplication()
            waitForHealth()
            super.executeTests()
        } finally {
            var cleanupSucceeded = true
            cleanupSucceeded = cleanupSucceeded && runCleanup("application shutdown") { stopApplication() }
            cleanupSucceeded = cleanupSucceeded && runCleanup("docker compose shutdown") { stopDocker() }
            cleanupSucceeded = cleanupSucceeded && runCleanup("post-run cleanup") {
                if (composeFile.exists()) {
                    runDockerComposeDown(dockerDir, composeFile, "post-run cleanup", "docker-compose-down-after.log")
                }
            }
            cleanupPerformed = cleanupSucceeded
        }
    }

    private fun prepareLogsDirectory() {
        val baseDir = logsDirectory.get().asFile
        if (!baseDir.exists()) {
            check(baseDir.mkdirs()) { "Failed to create base logs directory at ${baseDir.absolutePath}" }
        }
        val runDir = baseDir.resolve("run-${timestamp()}")
        if (runDir.exists()) {
            runDir.deleteRecursively()
        }
        check(runDir.mkdirs() || runDir.exists()) { "Failed to create logs directory at ${runDir.absolutePath}" }
        currentLogsDirectory = runDir
        project.logger.lifecycle("[e2e] Writing process logs to ${runDir.absolutePath}")
    }

    private fun logsDir(): File {
        val existing = currentLogsDirectory
        if (existing != null) {
            return existing
        }
        val base = logsDirectory.get().asFile
        if (!base.exists()) {
            base.mkdirs()
        }
        val fallback = base.resolve("run-${timestamp()}")
        fallback.mkdirs()
        currentLogsDirectory = fallback
        return fallback
    }

    private fun logFile(name: String): File {
        val directory = logsDir()
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return directory.resolve(name)
    }

    private fun timestamp(): String = LocalDateTime.now()
        .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))

    private fun deleteDataDirectories(dockerDir: File) {
        listOf("neo4j", "postgres").forEach { name ->
            val directory = dockerDir.resolve(name)
            if (directory.exists()) {
                project.logger.lifecycle("[e2e] Deleting docker data directory: ${directory.absolutePath}")
                directory.deleteRecursively()
            }
        }
    }

    private fun startDocker(dockerDir: File, composeFile: File) {
        require(composeFile.exists()) { "docker-compose file not found at ${composeFile.absolutePath}" }

        val command = resolveDockerComposeCommand(composeFile)
        project.logger.lifecycle("[e2e] Starting docker compose: ${command.joinToString(" ")}")

        val processBuilder = ProcessBuilder(command)
            .directory(dockerDir)
            .redirectErrorStream(true)

        composeProcess = processBuilder.start()
        composeLogs = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "e2e-docker-logs").apply { isDaemon = true }
        }
        val dockerLogFile = logFile("docker-compose.log")
        composeOutput = composeLogs?.submit(logStreamTask(composeProcess!!, project.logger, "docker", dockerLogFile))
    }

    private fun waitForServices(ports: List<Int>) {
        val timeout = Duration.ofMinutes(5)
        project.logger.lifecycle("[e2e] Waiting for docker services on ports: ${ports.joinToString(", ")}")
        ports.forEach { port ->
            waitForPort("localhost", port, timeout)
        }
    }

    private fun waitForPort(host: String, port: Int, timeout: Duration) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            ensureDockerProcessAlive("waiting for service on $host:$port")
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
        val appLogFile = logFile("application.log")
        applicationOutput = applicationLogs?.submit(logStreamTask(applicationProcess!!, project.logger, "app", appLogFile))
    }

    private fun waitForHealth() {
        val healthUrl = URL(applicationHealthUrl.get())
        val timeout = System.nanoTime() + Duration.ofMinutes(5).toNanos()
        project.logger.lifecycle("[e2e] Waiting for application health at $healthUrl")
        while (System.nanoTime() < timeout) {
            ensureApplicationProcessAlive("waiting for application health at $healthUrl")
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
        val process = applicationProcess
        if (process == null) {
            return
        }

        project.logger.lifecycle("[e2e] Stopping application process")
        process.destroy()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            project.logger.lifecycle("[e2e] Application did not exit gracefully within timeout; forcing termination")
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        val exitCode = runCatching { process.exitValue() }.getOrElse { -1 }
        project.logger.lifecycle("[e2e] Application process exited with code $exitCode")

        awaitTermination(applicationLogs)
        applicationOutput = null
        applicationLogs = null
        applicationProcess = null
    }

    private fun stopDocker() {
        val process = composeProcess
        if (process == null) {
            return
        }

        project.logger.lifecycle("[e2e] Stopping docker compose process")
        process.destroy()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            project.logger.lifecycle("[e2e] Docker compose did not exit gracefully within timeout; forcing termination")
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        val exitCode = runCatching { process.exitValue() }.getOrElse { -1 }
        project.logger.lifecycle("[e2e] Docker compose process exited with code $exitCode")

        awaitTermination(composeLogs)
        composeOutput = null
        composeLogs = null
        composeProcess = null
    }

    private fun runDockerComposeDown(
        dockerDir: File,
        composeFile: File,
        reason: String,
        logFileName: String
    ) {
        val command = resolveDockerComposeCommand(composeFile, down = true)
        project.logger.lifecycle("[e2e] Running docker compose down ($reason): ${command.joinToString(" ")}")
        val downProcess = ProcessBuilder(command)
            .directory(dockerDir)
            .redirectErrorStream(true)
            .start()

        val downLogFile = logFile(logFileName)
        downLogFile.parentFile.mkdirs()
        downLogFile.printWriter().use { writer ->
            downProcess.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    project.logger.lifecycle("[docker-down] $line")
                    writer.println(line)
                }
            }
        }

        val exitCode = downProcess.waitFor()
        project.logger.lifecycle("[e2e] Docker compose down ($reason) completed with exit code $exitCode")
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

    private fun ensureDockerProcessAlive(stage: String) {
        composeProcess?.let { process ->
            if (!process.isAlive) {
                val exitCode = runCatching { process.exitValue() }.getOrElse { -1 }
                error("Docker compose process exited while $stage (exit code $exitCode). Check docker-compose.log for details")
            }
        }
    }

    private fun ensureApplicationProcessAlive(stage: String) {
        applicationProcess?.let { process ->
            if (!process.isAlive) {
                val exitCode = runCatching { process.exitValue() }.getOrElse { -1 }
                error("Application process exited while $stage (exit code $exitCode). Check application.log for details")
            }
        }
    }

    private fun logStreamTask(process: Process, logger: Logger, prefix: String, logFile: File): Callable<Unit> = Callable {
        logFile.parentFile.mkdirs()
        logFile.outputStream().bufferedWriter().use { writer ->
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    logger.lifecycle("[$prefix] $line")
                    writer.appendLine(line)
                    writer.flush()
                }
            }
        }
    }

    private fun awaitTermination(executor: ExecutorService?) {
        if (executor == null) {
            return
        }
        executor.shutdown()
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun runCleanup(stage: String, action: () -> Unit): Boolean {
        return try {
            action()
            true
        } catch (ex: Exception) {
            project.logger.error("[e2e] Failed during $stage", ex)
            false
        }
    }
}
